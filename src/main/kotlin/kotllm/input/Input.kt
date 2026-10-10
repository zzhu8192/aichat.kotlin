package kotllm.input

import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths as JPaths
import kotlin.math.max
import kotlin.math.min
import kotllm.*

sealed interface Source

data class LocalFile(val path: Path) : Source

data class Directory(val path: Path) : Source

data class Url(val url: String) : Source

data class Command(val command: String) : Source

data object LastReply : Source

data class Block(val label: String, val content: String, val language: String)

data class Media(val label: String, val dataUrl: String, val bytes: Int, val mimeType: String)

data class Loaded(
    val blocks: List<Block>,
    val medias: List<Media> = emptyList(),
    val skipped: List<String> = emptyList()
) {

    val isEmpty: Boolean get() = blocks.isEmpty() && medias.isEmpty()

    val totalChars: Int get() = blocks.sumOf { it.content.length }

    fun render(message: String?): String {
        val body = blocks
            .joinToString("\n\n") { b ->
                val fence = fenceFor(b.content)
                "### ${b.label}\n$fence${b.language}\n${b.content.trimEnd()}\n$fence"
            }

        val parts = listOfNotNull(
            body.takeIf { it.isNotEmpty() },
            message?.trim()?.takeIf { it.isNotEmpty() }
        )
        return parts.joinToString("\n\n")
    }

    private fun fenceFor(content: String): String {
        val longest = """`+""".toRegex().findAll(content).maxOfOrNull { it.value.length } ?: 0
        return "`".repeat(max(3, longest + 1))
    }
}

data class InputLimits(
    val maxFileChars: Int,
    val maxTotalChars: Int,
    val maxFiles: Int,
    val loaders: Map<String, String>,
    val maxImageBytes: Int = 5_000_000,
    val htmlToMarkdown: Boolean = true
) {
    companion object {
        val default: InputLimits = InputLimits(100000, 400000, 200, emptyMap())
    }
}

class InputLoader(val limits: InputLimits, val lastReply: () -> String?) {

    val SkipDirs = kotllm.util.Skips.dirs

    private val ImageTypes = mapOf(
        "png" to "image/png",
        "jpg" to "image/jpeg",
        "jpeg" to "image/jpeg",
        "webp" to "image/webp",
        "gif" to "image/gif"
    )

    val LanguageByExt = mapOf(
        "scala" to "scala", "sc" to "scala", "java" to "java", "kt" to "kotlin",
        "py" to "python", "rb" to "ruby", "rs" to "rust", "go" to "go",
        "js" to "javascript", "ts" to "typescript", "tsx" to "tsx", "jsx" to "jsx",
        "sh" to "bash", "bash" to "bash", "ps1" to "powershell", "bat" to "bat",
        "sql" to "sql", "json" to "json", "yaml" to "yaml", "yml" to "yaml",
        "xml" to "xml", "html" to "html", "css" to "css", "toml" to "toml",
        "md" to "markdown", "c" to "c", "h" to "c", "cpp" to "cpp", "cs" to "csharp"
    )

    fun splitMessage(args: String): Pair<String, String?> {
        val marker = """(?m)(?:^|\s)--(?:\s|$)""".toRegex()
        val match = marker.find(args)
        return if (match != null) {
            Pair(args.substring(0, match.range.first), args.substring(match.range.last + 1).trim().takeIf { it.isNotEmpty() })
        } else {
            Pair(args, null)
        }
    }

    fun tokenise(spec: String): List<String> {
        val out = mutableListOf<String>()
        val current = StringBuilder()
        var quote: Char? = null

        fun flush() {
            if (current.isNotEmpty()) {
                out.add(current.toString())
                current.setLength(0)
            }
        }

        spec.forEach { c ->
            if (quote != null) {
                current.append(c)
                if (c == quote) {
                    flush()
                    quote = null
                }
            } else {
                when (c) {
                    '`', '"', '\'' -> {
                        current.append(c)
                        quote = c
                    }
                    else -> {
                        if (c.isWhitespace()) flush()
                        else current.append(c)
                    }
                }
            }
        }
        flush()
        return out
    }

    fun classify(token: String): Source {
        val t = token.trim()
        return when {
            t == "%%" -> LastReply
            t.startsWith("`") && t.endsWith("`") && t.length > 1 -> Command(t.substring(1, t.length - 1))
            t.startsWith("http://") || t.startsWith("https://") -> Url(t)
            else -> {
                val unquoted = when {
                    t.startsWith("\"") && t.endsWith("\"") -> t.substring(1, t.length - 1)
                    t.startsWith("'") && t.endsWith("'") -> t.substring(1, t.length - 1)
                    else -> t
                }
                val path = JPaths.get(unquoted)
                if (Files.isDirectory(path)) Directory(path) else LocalFile(path)
            }
        }
    }

    fun load(specs: List<String>): Outcome<Loaded> {
        if (specs.isEmpty()) return err("nothing to load; give at least one file, directory, URL or `command`")

        val blocks = mutableListOf<Block>()
        val medias = mutableListOf<Media>()
        val skipped = mutableListOf<String>()
        var total = 0
        var files = 0
        var stopped = false

        fun addImage(path: Path, ext: String) {
            if (!stopped) {
                if (files >= limits.maxFiles) {
                    skipped.add("stopped at ${limits.maxFiles} files (input_max_files)")
                    stopped = true
                } else {
                    readImage(path, ext).let { result ->
                        when (result) {
                            is Outcome.Ok -> {
                                medias.add(result.value)
                                files += 1
                            }
                            is Outcome.Err -> skipped.add("$path: ${result.message}")
                        }
                    }
                }
            }
        }

        fun add(label: String, content: String, language: String) {
            if (!stopped) {
                if (files >= limits.maxFiles) {
                    skipped.add("stopped at ${limits.maxFiles} files (input_max_files)")
                    stopped = true
                } else if (total + content.length > limits.maxTotalChars) {
                    skipped.add("$label: would exceed the total budget of ${limits.maxTotalChars} chars (input_max_total_chars)")
                    stopped = true
                } else {
                    blocks.add(Block(label, content, language))
                    total += content.length
                    files += 1
                }
            }
        }

        specs.map { classify(it) }.forEach { source ->
            when (source) {
                is LastReply -> {
                    val reply = lastReply()
                    if (reply != null) {
                        add("previous reply", reply, "")
                    } else {
                        skipped.add("%%: there is no previous reply yet")
                    }
                }
                is Command -> {
                    runCommand(source.command).let { result ->
                        when (result) {
                            is Outcome.Ok -> add("output of `${source.command}`", result.value, "")
                            is Outcome.Err -> skipped.add("`${source.command}`: ${result.message}")
                        }
                    }
                }
                is Url -> {
                    Fetch.get(source.url, limits.htmlToMarkdown).let { result ->
                        when (result) {
                            is Outcome.Ok -> {
                                val page = result.value
                                val label = page.savingSummary?.let { s -> "${source.url}  ($s)" } ?: source.url
                                add(label, truncate(page.text, source.url), if (page.convertedFromHtml) "markdown" else "")
                            }
                            is Outcome.Err -> skipped.add("${source.url}: ${result.message}")
                        }
                    }
                }
                is LocalFile -> {
                    loadLocal(source.path, ::addImage, ::add) { skipped.add(it) }
                }
                is Directory -> {
                    walk(source.path).forEach { path -> loadLocal(path, ::addImage, ::add) { skipped.add(it) } }
                }
            }
        }

        val loaded = Loaded(blocks, medias, skipped)
        return if (loaded.isEmpty && loaded.skipped.isNotEmpty()) {
            err("nothing could be loaded: ${loaded.skipped.joinToString("; ")}")
        } else {
            ok(loaded)
        }
    }

    private fun loadLocal(
        path: Path,
        addImage: (Path, String) -> Unit,
        add: (String, String, String) -> Unit,
        skip: (String) -> Unit
    ) {
        val ext = extensionOf(path)
        if (ImageTypes.containsKey(ext)) {
            addImage(path, ext)
        } else {
            readFile(path).let { result ->
                when (result) {
                    is Outcome.Ok -> add(path.toString(), result.value, languageOf(path))
                    is Outcome.Err -> skip("$path: ${result.message}")
                }
            }
        }
    }

    private fun readImage(path: Path, ext: String): Outcome<Media> = runCatching {
        val size = Files.size(path)
        if (size > limits.maxImageBytes) {
            err(
                // Scala wrote this with the `f` interpolator's `%,d`, which groups
                // thousands. Kotlin has no such interpolator, and dropping the `%`
                // leaves the specifier as literal text in the message.
                "image is %,dKB, over the %,dKB limit (input_max_image_bytes)"
                    .format(size / 1024, limits.maxImageBytes / 1024)
            )
        } else {
            val bytes = Files.readAllBytes(path)
            val mime = ImageTypes.getOrDefault(ext, "application/octet-stream")
            val b64 = java.util.Base64.getEncoder().encodeToString(bytes)
            ok(Media(path.toString(), "data:$mime;base64,$b64", bytes.size, mime))
        }
    }.fold(
        onSuccess = { it },
        onFailure = { e -> err(e.message ?: e.javaClass.simpleName) }
    )

    private fun extensionOf(path: Path): String {
        val name = path.fileName.toString()
        val i = name.lastIndexOf('.')
        return if (i < 0) "" else name.substring(i + 1).lowercase()
    }

    private fun languageOf(path: Path): String {
        val name = path.fileName.toString()
        val ext = when (val i = name.lastIndexOf('.')) {
            -1 -> ""
            else -> name.substring(i + 1).lowercase()
        }
        return LanguageByExt.getOrDefault(ext, "")
    }

    private fun walk(dir: Path): List<Path> {
        return try {
            Files.walk(dir).use { stream ->
                stream
                    .filter { Files.isRegularFile(it) }
                    .filter { p ->
                        (0 until p.nameCount).none { i ->
                            val part = p.getName(i).toString()
                            SkipDirs.contains(part) || (part.startsWith(".") && part.length > 1)
                        }
                    }
                    .sorted()
                    .toList()
            }
        } catch (e: Exception) {
            emptyList()
        }
    }

    private fun readFile(path: Path): Outcome<String> {
        if (!Files.exists(path)) return err("no such file")
        if (!Files.isReadable(path)) return err("not readable")

        val ext = run {
            val name = path.fileName.toString()
            val i = name.lastIndexOf('.')
            if (i < 0) "" else name.substring(i + 1).lowercase()
        }

        return limits.loaders[ext]?.let { _ ->
            Loaders.convert(path, ext, limits.loaders).map { it }
        } ?: run {
            runCatching {
                val bytes = Files.readAllBytes(path)
                if (looksBinary(bytes)) {
                    err("looks binary, skipped")
                } else {
                    val text = String(bytes, StandardCharsets.UTF_8)
                    val ext2 = extensionOf(path)
                    val rendered = if (limits.htmlToMarkdown && (ext2 == "html" || ext2 == "htm" || ext2 == "xhtml")) {
                        Html.toMarkdown(text)
                    } else {
                        text
                    }
                    ok(truncate(rendered, path.toString()))
                }
            }.fold(
                onSuccess = { it },
                onFailure = { e -> err(e.message ?: e.javaClass.simpleName) }
            )
        }
    }

    private fun truncate(text: String, label: String): String {
        return if (text.length <= limits.maxFileChars) {
            text
        } else {
            text.take(limits.maxFileChars) +
                "\n... [truncated at ${limits.maxFileChars} chars of ${text.length}; raise input_max_file_chars]"
        }
    }

    private fun looksBinary(bytes: ByteArray): Boolean =
        bytes.take(8000).contains(0.toByte())

    private fun runCommand(command: String): Outcome<String> = runCatching {
        val shell = if (System.getProperty("os.name", "").lowercase().contains("win")) {
            listOf("cmd.exe", "/d", "/s", "/c", command)
        } else {
            listOf("sh", "-c", command)
        }

        val process = ProcessBuilder(shell).redirectErrorStream(true).start()
        process.outputStream.close()
        val out = String(process.inputStream.readAllBytes(), StandardCharsets.UTF_8)
        val finished = process.waitFor(60L, java.util.concurrent.TimeUnit.SECONDS)
        if (!finished) {
            process.destroyForcibly()
            err("timed out after 60s")
        } else if (process.exitValue() != 0) {
            err("exit code ${process.exitValue()}: ${out.trim().take(200)}")
        } else {
            ok(out)
        }
    }.fold(
        onSuccess = { it },
        onFailure = { e -> err(e.message ?: e.javaClass.simpleName) }
    )
}
