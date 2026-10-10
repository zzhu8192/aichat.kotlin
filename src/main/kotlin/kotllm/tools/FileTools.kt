package kotllm.tools

import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths

import kotlinx.serialization.json.*

import kotllm.*
import kotllm.util.Skips

/** Scala's `String.stripLineEnd`: removes exactly one trailing line terminator
  * (`\r\n`, `\r` or `\n`), not every trailing newline. File-private because
  * `Tools.kt` needs the identical behaviour and keeps its own copy rather than
  * sharing one across files neither owns. */
private fun String.stripLineEnd(): String {
    val len = length
    if (len == 0) return this
    return when (this[len - 1]) {
        '\n' -> if (len >= 2 && this[len - 2] == '\r') substring(0, len - 2) else substring(0, len - 1)
        '\r' -> substring(0, len - 1)
        else -> this
    }
}

/** `read`, `write`, `edit`, `glob`, `grep`, `ls` as first-order tools.
  *
  * All six are things the model would otherwise write code for. `exec` and
  * `groovy_eval` can do every one of them badly: quoting rules bite, output
  * arrives through an interpreter transcript and its cap, and a three-line edit
  * becomes a program. They also give a skill written for another harness --
  * which says "use the Read tool" -- something to land on.
  *
  * Six schemas rather than one tool with a `mode` argument, because a tool's
  * description is the only documentation the model gets and one cannot cover
  * six jobs. See docs/tools.md.
  */
class FileTools(
    private val limits: FileToolLimits = FileToolLimits.default,
    private val cwd: () -> Path = FileTools.processDir,
    /** Shared with `write_from`, which also writes. See `FileMemo`. */
    private val memo: FileMemo = FileMemo()
) {

    // `ls` is gone: `glob` with `*` lists a directory, skips build output while
    // doing it, and `ls`'s own description already said "use glob when you know
    // what kind of file you want". Two tools for one job is 79 tokens on every
    // request to make the model choose between them.
    val all: List<Tool> get() = listOf(read, write, edit, glob, grep)

    /** Names in the order the model should reach for them, for the manifest. */
    val names: List<String> get() = all.map { it.name }

    // ---- path handling -----------------------------------------------------

    // Both delegate to the companion, because `read_into` lives in `Tools` --
    // it needs the interpreter, which this class deliberately knows nothing
    // about -- and a second copy of path resolution is exactly the drift this
    // project has been bitten by before.
    private fun resolve(raw: String): Outcome<Path> = FileTools.resolve(raw, cwd())

    private fun display(p: Path): String = FileTools.display(p, cwd())

    private fun readBytes(p: Path): Outcome<ByteArray> =
        try {
            ok(Files.readAllBytes(p))
        } catch (e: Exception) {
            err("could not read ${display(p)}: ${e.message ?: e::class.simpleName}")
        }

    private fun text(bytes: ByteArray): String = String(bytes, StandardCharsets.UTF_8)

    // ---- read ----------------------------------------------------------

    val read: Tool get() = Tool(
        name = "read_to_context",
        description =
            "File -> conversation, line-numbered `N<tab>`; strip before passing to edit. " +
                "Unlocks write. Costs tokens on every later request; to compute, use read_to_var.",
        parameters = buildJsonObject {
            put("type", "object")
            putJsonObject("properties") {
                putJsonObject("file_path") {
                    put("type", "string")
                    put("description", "File path.")
                }
                putJsonObject("offset") {
                    put("type", "integer")
                    put("description", "First line, 1-based.")
                }
                putJsonObject("limit") {
                    put("type", "integer")
                    put("description", "Lines. Default all.")
                }
            }
            putJsonArray("required") { add("file_path") }
        },
        preview = { args -> Tool.optStr(args, "file_path") ?: "" },
        readOnly = true,
        run = { args ->
            withPath(args) { path ->
                if (!Files.exists(path)) ToolResult("${display(path)} does not exist", isError = true)
                else if (Files.isDirectory(path))
                    ToolResult("${display(path)} is a directory; use the ls tool", isError = true)
                else
                    readBytes(path).fold(
                        onErr = { e -> ToolResult(e, isError = true) },
                        onOk = { bytes ->
                            if (FileTools.looksBinary(bytes))
                                ToolResult(
                                    "${display(path)} is a binary file (${bytes.size} bytes); it has no text to read",
                                    isError = true
                                )
                            else ToolResult(renderRead(path, text(bytes), args))
                        }
                    )
            }
        }
    )

    private fun renderRead(path: Path, content: String, args: JsonElement): String {
        val lines = if (content.isEmpty()) emptyList() else content.split("\n")
        val total = lines.size
        val offset = maxOf(1, Tool.optInt(args, "offset") ?: 1)
        val limit = Tool.optInt(args, "limit")?.takeIf { it > 0 } ?: Int.MAX_VALUE

        if (content.isEmpty()) return "${display(path)} is empty (0 bytes)"
        if (offset > total) return "${display(path)} has only $total line(s); offset $offset is past the end"

        val toIndex = minOf(total.toLong(), (offset - 1).toLong() + limit).toInt()
        val window = lines.subList(offset - 1, toIndex)
        val body = FileTools.numbered(window, offset)
        val capped = body.length > limits.readMaxChars
        val shown = if (capped) body.take(limits.readMaxChars) else body
        val windowed = offset > 1 || window.size < total

        val header =
            if (windowed) "${display(path)}  lines $offset-${offset + window.size - 1} of $total"
            else "${display(path)}  $total line(s), ${content.length} chars"

        val note =
            if (capped)
                "\n\n[truncated at ${limits.readMaxChars} chars; read a narrower range with offset/limit]"
            else if (offset + window.size - 1 < total)
                "\n\n[${total - (offset + window.size - 1)} further line(s); continue at offset ${offset + window.size}]"
            else ""

        // Recorded even for a partial read: the model has now seen enough of this
        // file that an overwrite is its decision rather than an accident.
        memo.mark(path)
        return "$header\n$shown$note"
    }

    // ---- write -------------------------------------------------------------

    val write: Tool get() = Tool(
        name = "write",
        description =
            "Create or replace a file. Overwrite needs a read first. Part of a file: edit.",
        parameters = buildJsonObject {
            put("type", "object")
            putJsonObject("properties") {
                putJsonObject("file_path") {
                    put("type", "string")
                    put("description", "Path to write.")
                }
                putJsonObject("content") {
                    put("type", "string")
                    put("description", "Full contents, no line numbers.")
                }
            }
            putJsonArray("required") {
                add("file_path")
                add("content")
            }
        },
        preview = { args ->
            val p = Tool.optStr(args, "file_path") ?: ""
            val n = Tool.optStr(args, "content")?.length ?: 0
            "$p  ($n chars)"
        },
        run = { args ->
            withPath(args) { path ->
                // An empty file is a legitimate thing to write, so this reads the key
                // directly rather than going through `Tool.str`, which rejects "".
                val content = stringField(args, "content")
                if (content == null) ToolResult("missing required argument 'content'", isError = true)
                else {
                    val body = content
                    val existed = Files.isRegularFile(path)
                    if (Files.isDirectory(path))
                        ToolResult("${display(path)} is a directory", isError = true)
                    else if (existed && !memo.hasSeen(path))
                        ToolResult(
                            "refusing to overwrite ${display(path)} sight unseen: read it first with " +
                                "read_to_context or read_to_var, or use the edit tool to change part of it",
                            isError = true
                        )
                    else
                        try {
                            FileTools.atomicWrite(path, body)
                            memo.mark(path)
                            val lines = if (body.isEmpty()) 0 else body.split("\n").size
                            val verb = if (existed) "overwrote" else "created"
                            ToolResult("$verb ${display(path)}: $lines line(s), ${body.length} chars")
                        } catch (e: Exception) {
                            ToolResult(
                                "could not write ${display(path)}: ${e::class.simpleName}: ${e.message ?: ""}",
                                isError = true
                            )
                        }
                }
            }
        }
    )

    // ---- edit --------------------------------------------------------------

    val edit: Tool get() = Tool(
        name = "edit",
        description =
            "Replace text matching EXACTLY ONCE incl. whitespace, else refused: add context or " +
                "replace_all. No line-number prefixes.",
        parameters = buildJsonObject {
            put("type", "object")
            putJsonObject("properties") {
                putJsonObject("file_path") {
                    put("type", "string")
                    put("description", "File to change.")
                }
                putJsonObject("old_string") {
                    put("type", "string")
                    put("description", "Exact text incl. whitespace.")
                }
                putJsonObject("new_string") {
                    put("type", "string")
                    put("description", "Replacement. Empty deletes.")
                }
                putJsonObject("replace_all") {
                    put("type", "boolean")
                    put("description", "All occurrences.")
                }
            }
            putJsonArray("required") {
                add("file_path")
                add("old_string")
                add("new_string")
            }
        },
        preview = { args ->
            val p = Tool.optStr(args, "file_path") ?: ""
            val o = (Tool.optStr(args, "old_string") ?: "").lineSequence().firstOrNull() ?: ""
            "$p  <- ${o.take(60)}"
        },
        run = { args ->
            withPath(args) { path ->
                val newString = stringField(args, "new_string")
                Tool.str(args, "old_string").fold(
                    onErr = { e -> ToolResult(e, isError = true) },
                    onOk = { oldString ->
                        if (newString == null) ToolResult("missing required argument 'new_string'", isError = true)
                        else if (oldString == newString)
                            ToolResult(
                                "old_string and new_string are identical, so there is nothing to do",
                                isError = true
                            )
                        else if (!Files.isRegularFile(path))
                            ToolResult("${display(path)} does not exist or is not a file", isError = true)
                        else
                            readBytes(path).fold(
                                onErr = { e -> ToolResult(e, isError = true) },
                                onOk = { bytes ->
                                    applyEdit(
                                        path, text(bytes), oldString, newString,
                                        Tool.optBool(args, "replace_all") ?: false
                                    )
                                }
                            )
                    }
                )
            }
        }
    )

    private fun applyEdit(
        path: Path,
        content: String,
        requested: String,
        replacement: String,
        replaceAll: Boolean
    ): ToolResult {
        // Having just read a numbered listing, a model will paste the prefix in.
        // Retrying once without it costs three lines and saves a round trip that
        // would read as a broken tool.
        val (needle, destripped) =
            if (content.contains(requested)) Pair(requested, false)
            else {
                val stripped = FileTools.stripLineNumbers(requested)
                if (stripped != requested && content.contains(stripped)) Pair(stripped, true)
                else Pair(requested, false)
            }

        val count = FileTools.countOccurrences(content, needle)

        return if (count == 0) {
            val first = (needle.lineSequence().firstOrNull() ?: "").trim()
            ToolResult(
                "old_string was not found in ${display(path)}. Nothing was changed. The first line " +
                    "looked for was: \"${first.take(120)}\" -- whitespace and indentation must " +
                    "match exactly, so read the file and copy the text rather than retyping it.",
                isError = true
            )
        } else if (count > 1 && !replaceAll) {
            ToolResult(
                "old_string appears $count times in ${display(path)}, so the edit is ambiguous and " +
                    "nothing was changed. Include more surrounding lines to identify the one you mean, " +
                    "or pass replace_all: true if every occurrence should change.",
                isError = true
            )
        } else {
            val updated = if (replaceAll) content.replace(needle, replacement)
            else {
                val at = content.indexOf(needle)
                content.substring(0, at) + replacement + content.substring(at + needle.length)
            }
            try {
                FileTools.atomicWrite(path, updated)
                memo.mark(path)
                val line = content.substring(0, content.indexOf(needle)).count { it == '\n' } + 1
                val where = if (replaceAll && count > 1) "$count occurrence(s)" else "1 occurrence at line $line"
                val note = if (destripped) " (line-number prefixes in old_string were ignored)" else ""
                ToolResult("edited ${display(path)}: replaced $where$note")
            } catch (e: Exception) {
                ToolResult("could not write ${display(path)}: ${e::class.simpleName}", isError = true)
            }
        }
    }

    // ---- glob --------------------------------------------------------------

    val glob: Tool get() = Tool(
        name = "glob",
        description =
            "Files by path pattern, newest first. With '/' matches from root ('src/**/*.kt'); " +
                "without, filename at any depth ('*.kt').",
        parameters = buildJsonObject {
            put("type", "object")
            putJsonObject("properties") {
                putJsonObject("pattern") {
                    put("type", "string")
                    put("description", "Glob, e.g. '*.kt'.")
                }
                putJsonObject("path") {
                    put("type", "string")
                    put("description", "Search root. Default cwd.")
                }
                putJsonObject("limit") {
                    put("type", "integer")
                    put("description", "Max paths.")
                }
            }
            putJsonArray("required") { add("pattern") }
        },
        preview = { args ->
            val p = Tool.optStr(args, "pattern") ?: ""
            Tool.optStr(args, "path")?.let { d -> "$p  (under $d)" } ?: p
        },
        readOnly = true,
        run = { args ->
            Tool.str(args, "pattern").fold(
                onErr = { e -> ToolResult(e, isError = true) },
                onOk = { pattern ->
                    withRoot(Tool.optStr(args, "path")) { root ->
                        FileTools.matcher(pattern).fold(
                            onErr = { e -> ToolResult(e, isError = true) },
                            onOk = { matches ->
                                val cap = Tool.optInt(args, "limit")?.takeIf { it > 0 } ?: limits.globMaxResults
                                val hits = walk(root).filter { matches(relative(root, it)) }.toList()
                                if (hits.isEmpty())
                                    ToolResult("no files match '$pattern' under ${display(root)}")
                                else {
                                    val ordered = hits.sortedByDescending { FileTools.modifiedAt(it) }
                                    val shown = ordered.take(cap)
                                    val body = shown.joinToString("\n") { relative(root, it) }
                                    val note =
                                        if (ordered.size > shown.size)
                                            "\n\n[${ordered.size - shown.size} further match(es) withheld; " +
                                                "narrow the pattern or raise limit]"
                                        else ""
                                    ToolResult(
                                        "${ordered.size} match(es) for '$pattern' under ${display(root)}, " +
                                            "newest first:\n$body$note"
                                    )
                                }
                            }
                        )
                    }
                }
            )
        }
    )

    // ---- grep --------------------------------------------------------------

    val grep: Tool get() = Tool(
        name = "grep",
        description =
            "Regex search in file contents. output_mode: files_with_matches (default), content, count.",
        parameters = buildJsonObject {
            put("type", "object")
            putJsonObject("properties") {
                putJsonObject("pattern") {
                    put("type", "string")
                    put("description", "Java regex, per line.")
                }
                putJsonObject("path") {
                    put("type", "string")
                    put("description", "File or dir. Default cwd.")
                }
                putJsonObject("glob") {
                    put("type", "string")
                    put("description", "Filter by glob.")
                }
                putJsonObject("output_mode") {
                    put("type", "string")
                    putJsonArray("enum") {
                        add("files_with_matches")
                        add("content")
                        add("count")
                    }
                    put("description", "Default files_with_matches.")
                }
                putJsonObject("case_insensitive") {
                    put("type", "boolean")
                    put("description", "Ignore case.")
                }
                putJsonObject("context") {
                    put("type", "integer")
                    put("description", "Context lines, content mode.")
                }
                putJsonObject("limit") {
                    put("type", "integer")
                    put("description", "Max results.")
                }
            }
            putJsonArray("required") { add("pattern") }
        },
        preview = { args ->
            val p = Tool.optStr(args, "pattern") ?: ""
            val g = Tool.optStr(args, "glob")?.let { "  ($it)" } ?: ""
            val d = Tool.optStr(args, "path")?.let { "  under $it" } ?: ""
            "$p$g$d"
        },
        readOnly = true,
        run = { args ->
            Tool.str(args, "pattern").fold(
                onErr = { e -> ToolResult(e, isError = true) },
                onOk = { pattern -> runGrep(pattern, args) }
            )
        }
    )

    private fun runGrep(pattern: String, args: JsonElement): ToolResult {
        val flags = if (Tool.optBool(args, "case_insensitive") ?: false)
            java.util.regex.Pattern.CASE_INSENSITIVE
        else 0

        val re: java.util.regex.Pattern
        try {
            re = java.util.regex.Pattern.compile(pattern, flags)
        } catch (e: java.util.regex.PatternSyntaxException) {
            return ToolResult(
                "'$pattern' is not a valid regular expression: ${e.description} at index ${e.index}",
                isError = true
            )
        }

        val globPattern = Tool.optStr(args, "glob")
        val globOutcome: Outcome<(String) -> Boolean>? = globPattern?.let { FileTools.matcher(it) }
        val keep: (String) -> Boolean = when (globOutcome) {
            null -> { _ -> true }
            is Outcome.Err -> return ToolResult(globOutcome.message, isError = true)
            is Outcome.Ok -> globOutcome.value
        }

        return withRoot(Tool.optStr(args, "path")) { root ->
            val mode = Tool.optStr(args, "output_mode") ?: "files_with_matches"
            if (!FileTools.Modes.contains(mode))
                ToolResult(
                    "unknown output_mode '$mode'; use one of: ${FileTools.Modes.joinToString(", ")}",
                    isError = true
                )
            else {
                val single = Files.isRegularFile(root)
                val files: Sequence<Path> =
                    if (single) sequenceOf(root) else walk(root).filter { keep(relative(root, it)) }
                val capped = files.take(limits.grepMaxFilesScanned)
                val cap = Tool.optInt(args, "limit")?.takeIf { it > 0 } ?: limits.grepMaxResults
                val context = Tool.optInt(args, "context")?.takeIf { it >= 0 } ?: 0
                val base = if (single) (root.parent ?: root) else root
                val found = capped.mapNotNull { p -> FileTools.matchesIn(p, re)?.let { p to it } }.toList()
                renderGrep(pattern, root, base, found, mode, context, cap)
            }
        }
    }

    private fun renderGrep(
        pattern: String,
        root: Path,
        base: Path,
        found: List<Pair<Path, List<Pair<Int, String>>>>,
        mode: String,
        context: Int,
        cap: Int
    ): ToolResult {
        if (found.isEmpty())
            return ToolResult(
                "no matches for '$pattern' under ${display(root)}. The pattern is a regular expression " +
                    "matched against each line; try a shorter or less anchored one."
            )

        val totalMatches = found.sumOf { it.second.size }
        val header = "$totalMatches match(es) for '$pattern' in ${found.size} file(s) under ${display(root)}"

        return when (mode) {
            "files_with_matches" -> {
                val shown = found.take(cap)
                val body = shown.joinToString("\n") { (p, ms) -> "${relative(base, p)}  (${ms.size})" }
                ToolResult(cappedText("$header:\n$body", found.size - shown.size, "file(s)"))
            }

            "count" -> {
                val shown = found.sortedByDescending { it.second.size }.take(cap)
                val body = shown.joinToString("\n") { (p, ms) -> "${ms.size}\t${relative(base, p)}" }
                ToolResult(cappedText("$header, most first:\n$body", found.size - shown.size, "file(s)"))
            }

            else -> {
                val lines = mutableListOf<String>()
                var shown = 0
                for ((p, ms) in found) {
                    val rel = relative(base, p)
                    if (shown < cap) {
                        lines += "\n$rel"
                        for ((n, line) in ms.take(cap - shown)) {
                            if (context > 0) {
                                for ((cn, cl) in FileTools.contextAround(p, n, context)) {
                                    lines += if (cn == n) "  $cn: ${cl.take(400)}" else "  $cn- ${cl.take(400)}"
                                }
                            } else {
                                lines += "  $n: ${line.take(400)}"
                            }
                        }
                        shown += minOf(ms.size, cap - shown)
                    }
                }
                val withheld = totalMatches - shown
                val body = lines.joinToString("\n")
                ToolResult(cappedText("$header:\n$body", withheld, "match(es)"))
            }
        }
    }

    private fun cappedText(body: String, withheld: Int, unit: String): String {
        val note =
            if (withheld > 0) "\n\n[$withheld further $unit withheld; narrow the pattern or raise limit]" else ""
        val full = body + note
        return if (full.length <= limits.outputMaxChars) full
        else full.take(limits.outputMaxChars) +
            "\n\n[result truncated at ${limits.outputMaxChars} chars; narrow the search]"
    }

    // ---- ls ----------------------------------------------------------------

    val ls: Tool get() = Tool(
        name = "ls",
        description =
            "List one directory, dirs first, with sizes.",
        parameters = buildJsonObject {
            put("type", "object")
            putJsonObject("properties") {
                putJsonObject("path") {
                    put("type", "string")
                    put("description", "Directory. Default cwd.")
                }
            }
        },
        preview = { args -> Tool.optStr(args, "path") ?: "." },
        readOnly = true,
        run = { args ->
            withRoot(Tool.optStr(args, "path")) { root ->
                if (!Files.isDirectory(root))
                    ToolResult("${display(root)} is not a directory", isError = true)
                else
                    try {
                        val stream = Files.list(root)
                        val entries = try {
                            stream.toList()
                        } finally {
                            stream.close()
                        }

                        if (entries.isEmpty()) ToolResult("${display(root)} is empty")
                        else {
                            val (dirs, files) = entries.partition { Files.isDirectory(it) }
                            val rendered =
                                dirs.map { it.fileName.toString() }.sorted().map { "$it/" } +
                                    files.sortedBy { it.fileName.toString() }.map { p ->
                                        val size = try {
                                            Files.size(p)
                                        } catch (e: Exception) {
                                            -1L
                                        }
                                        "${p.fileName}  ($size bytes)"
                                    }
                            val shown = rendered.take(limits.lsMaxEntries)
                            val note =
                                if (rendered.size > shown.size)
                                    "\n\n[${rendered.size - shown.size} further entry(s) not shown]"
                                else ""
                            ToolResult(
                                "${display(root)}  ${entries.size} entry(s):\n${shown.joinToString("\n")}$note"
                            )
                        }
                    } catch (e: Exception) {
                        ToolResult("could not list ${display(root)}: ${e::class.simpleName}", isError = true)
                    }
            }
        }
    )

    // ---- plumbing ----------------------------------------------------------

    private fun withPath(args: JsonElement, f: (Path) -> ToolResult): ToolResult =
        Tool.str(args, "file_path").fold(
            onErr = { e -> ToolResult(e, isError = true) },
            onOk = { raw ->
                resolve(raw).fold(
                    onErr = { e -> ToolResult(e, isError = true) },
                    onOk = { path -> f(path) }
                )
            }
        )

    private fun withRoot(raw: String?, f: (Path) -> ToolResult): ToolResult {
        if (raw == null) return f(cwd().toAbsolutePath().normalize())
        return resolve(raw).fold(
            onErr = { e -> ToolResult(e, isError = true) },
            onOk = { p ->
                if (!Files.exists(p)) ToolResult("${display(p)} does not exist", isError = true)
                else f(p)
            }
        )
    }

    /** Reads a key directly as a (possibly empty) string, bypassing `Tool.str`'s
      * non-empty check. Used by `write`'s `content` and `edit`'s `new_string`,
      * both of which treat an empty string as a legitimate value rather than a
      * missing argument. */
    private fun stringField(args: JsonElement, key: String): String? {
        val obj = args as? JsonObject ?: return null
        val v = obj[key] as? JsonPrimitive ?: return null
        return if (v.isString) v.content else null
    }

    private fun relative(base: Path, p: Path): String {
        val rel = try {
            base.toAbsolutePath().normalize().relativize(p.toAbsolutePath().normalize())
        } catch (e: Exception) {
            null
        }
        return rel?.toString()?.replace('\\', '/') ?: p.toString()
    }

    /** Regular files under `root`, skipping build output and unreadable
      * subtrees. A directory we cannot enter is skipped rather than aborting the
      * walk: one protected folder must not lose the other nine hundred files. */
    private fun walk(root: Path): Sequence<Path> =
        if (Files.isRegularFile(root)) sequenceOf(root)
        else if (!Files.isDirectory(root)) emptySequence()
        else
            try {
                val stream = Files.walk(root)
                stream.iterator().asSequence().filter { p ->
                    Files.isRegularFile(p) &&
                        !Skips.containsSkipped(
                            root.toAbsolutePath().normalize().relativize(p.toAbsolutePath().normalize())
                        )
                }
            } catch (e: Exception) {
                emptySequence()
            }

    companion object {

        val Modes: List<String> = listOf("files_with_matches", "content", "count")

        fun resolve(raw: String, cwd: Path): Outcome<Path> =
            try {
                val p = Paths.get(raw.trim())
                ok((if (p.isAbsolute) p else cwd.resolve(p)).normalize())
            } catch (e: Exception) {
                err("'$raw' is not a usable path (${e::class.simpleName})")
            }

        /** Shown in results. Relative to the working directory when that is shorter,
          * because `src/main/scala/X.scala` is what the user can click and grep for,
          * while an absolute path is mostly noise they already know. */
        fun display(p: Path, cwd: Path): String {
            val abs = p.toAbsolutePath().normalize()
            val rel = try {
                cwd.toAbsolutePath().normalize().relativize(abs)
            } catch (e: Exception) {
                null
            }
            val relStr = rel?.toString()
            return if (relStr != null && relStr.isNotEmpty() && !relStr.startsWith("..")) relStr else abs.toString()
        }

        /** One file as text, with every guard `read` applies, and nothing rendered.
          *
          * Split out for `read_into`, which wants the content to go into the
          * interpreter rather than into a tool result -- so it needs the reading and
          * the refusals, and none of the numbering, windowing or capping.
          */
        fun loadText(raw: String, cwd: Path, maxChars: Int): Outcome<Pair<Path, String>> =
            resolve(raw, cwd).flatMap { path ->
                val shown = display(path, cwd)
                if (!Files.exists(path)) err("$shown does not exist")
                else if (Files.isDirectory(path)) err("$shown is a directory")
                else
                    try {
                        // Checked before reading: a refusal must not require allocating the
                        // thing being refused.
                        val size = Files.size(path)
                        if (size > maxChars.toLong())
                            err(
                                "$shown is $size bytes, over the ${maxChars}-byte limit for binding a file " +
                                    "into the interpreter; raise read_to_var_max_chars, or read a part of it with " +
                                    "the read tool"
                            )
                        else {
                            val bytes = Files.readAllBytes(path)
                            if (looksBinary(bytes)) err("$shown is a binary file; it has no text to bind")
                            else ok(Pair(path, String(bytes, StandardCharsets.UTF_8)))
                        }
                    } catch (e: Exception) {
                        err("could not read $shown: ${e.message ?: e::class.simpleName}")
                    }
            }

        /** Result of [[checkText]]: the resolved path, the file's length in
          * characters, its line count, and its first three lines. Mirrors the
          * `(Path, Int, Int, Seq[String])` tuple the Scala side returned. */
        data class CheckedText(val path: Path, val chars: Int, val lines: Int, val head: List<String>)

        /** Validates a file for binding and reports its shape, without returning it.
          *
          * `read_to_var` needs the guards (exists, not a directory, not binary, not
          * over the ceiling) and the shape (lines, chars, first lines) but not the
          * content -- the interpreter does the real read, and for an out-of-process
          * session that read never crosses our boundary at all. Returning the text
          * here would mean reading an 800KB file twice.
          */
        fun checkText(raw: String, cwd: Path, maxChars: Int): Outcome<CheckedText> =
            resolve(raw, cwd).flatMap { path ->
                val shown = display(path, cwd)
                if (!Files.exists(path)) err("$shown does not exist")
                else if (Files.isDirectory(path)) err("$shown is a directory")
                else
                    try {
                        // Checked before any read: a refusal must not require allocating the
                        // thing being refused.
                        val size = Files.size(path)
                        if (size > maxChars.toLong())
                            err(
                                "$shown is $size bytes, over the $maxChars-byte limit for binding a file " +
                                    "into an interpreter; raise read_to_var_max_chars, or read part of it with " +
                                    "read_to_context"
                            )
                        else {
                            val bytes = Files.readAllBytes(path)
                            if (looksBinary(bytes)) err("$shown is a binary file; it has no text to bind")
                            else {
                                val content = String(bytes, StandardCharsets.UTF_8)
                                val lines = content.lineSequence().toList()
                                ok(CheckedText(path, content.length, lines.size, lines.take(3)))
                            }
                        }
                    } catch (e: Exception) {
                        err("could not read $shown: ${e.message ?: e::class.simpleName}")
                    }
            }

        /** Kotlin reserved words, so a bind cannot produce a session that will not
          * compile. `val val = ...` is not a diagnostic anybody wants to debug from
          * a tool result. */
        private val Keywords = setOf(
            "as", "break", "class", "continue", "do", "else", "false", "for", "fun", "if", "in",
            "interface", "is", "null", "object", "package", "return", "super", "this", "throw",
            "true", "try", "typealias", "typeof", "val", "var", "when", "while"
        )

        private val Identifier = Regex("^[A-Za-z_][A-Za-z0-9_]*$")

        /** Validates a variable name the model chose.
          *
          * The model picks it, because it is the one that has to write the follow-up
          * code and a name we derived from the filename would be a second thing to
          * guess. But it is interpolated into source, so it is checked rather
          * than trusted.
          */
        fun validIdentifier(name: String): Outcome<String> {
            val n = name.trim()
            return if (n.isEmpty()) err("variable name is empty")
            else if (!Identifier.matches(n))
                err(
                    "'$n' is not a legal identifier: use a letter or underscore followed by " +
                        "letters, digits or underscores"
                )
            else if (Keywords.contains(n)) err("'$n' is a reserved word; pick another name")
            else ok(n)
        }

        /** Writes `content` to `path` by replacing it, not by truncating it.
          *
          * Lives in `util/AtomicWrite` now -- named memories write too, and the
          * dependency from `kotllm.memory` into `kotllm.tools` would have been
          * backwards. Kept here as the name every file tool already calls, so there
          * is still exactly one implementation.
          */
        fun atomicWrite(path: Path, content: String) {
            kotllm.util.AtomicWrite.write(path, content)
        }

        val processDir: () -> Path
            get() = { Paths.get(System.getProperty("user.dir", ".")).toAbsolutePath().normalize() }

        /** A NUL byte in the first 8KB, the same test `/file` input uses. Cheap, and
          * the failure mode -- a text file with an embedded NUL -- is rarer than the
          * binary it catches. */
        fun looksBinary(bytes: ByteArray): Boolean {
            var i = 0
            val n = minOf(bytes.size, 8192)
            while (i < n) {
                if (bytes[i].toInt() == 0) return true
                i += 1
            }
            return false
        }

        /** `cat -n` style, right-aligned so the text lines up and a long file does
          * not ragged out at the thousand mark. */
        fun numbered(lines: List<String>, startLine: Int): String =
            lines.mapIndexed { i, l -> "%6d\t%s".format(startLine + i, l) }.joinToString("\n")

        private val LineNumberPrefix = Regex("^\\s*\\d+\\t")

        /** Removes a leading `   42<tab>` from every line. What `read` emits, and
          * therefore what a model will hand back to `edit`. */
        fun stripLineNumbers(s: String): String =
            s.split("\n").joinToString("\n") { LineNumberPrefix.replaceFirst(it, "") }

        fun countOccurrences(haystack: String, needle: String): Int {
            if (needle.isEmpty()) return 0
            var count = 0
            var at = haystack.indexOf(needle)
            while (at >= 0) {
                count += 1
                at = haystack.indexOf(needle, at + needle.length)
            }
            return count
        }

        /** Compiles a glob into a predicate over a forward-slash relative path.
          *
          * A pattern with no separator is matched against the file name at any
          * depth, because `*.scala` is what a model writes when it means every Scala
          * file -- documented in the tool description rather than left to surprise.
          */
        fun matcher(pattern: String): Outcome<(String) -> Boolean> =
            try {
                val fs = java.nio.file.FileSystems.getDefault()
                val m = fs.getPathMatcher("glob:$pattern")
                val leaf = !pattern.contains('/')
                val predicate: (String) -> Boolean = { rel ->
                    val p = Paths.get(rel)
                    m.matches(p) || (leaf && p.fileName?.let { m.matches(it) } == true)
                }
                ok(predicate)
            } catch (e: Exception) {
                when (e) {
                    is java.util.regex.PatternSyntaxException, is IllegalArgumentException ->
                        err("'$pattern' is not a valid glob: ${e.message ?: ""}")
                    else -> throw e
                }
            }

        fun modifiedAt(p: Path): Long =
            try {
                Files.getLastModifiedTime(p).toMillis()
            } catch (e: Exception) {
                0L
            }

        /** Matching lines of one file, or nothing when it is binary or unreadable.
          * Silence is right here: a `grep` over a tree will meet jars and images, and
          * naming each one would bury the matches. */
        fun matchesIn(p: Path, re: java.util.regex.Pattern): List<Pair<Int, String>>? {
            if (Skips.isBinaryExtension(p)) return null
            return try {
                val bytes = Files.readAllBytes(p)
                if (looksBinary(bytes)) null
                else {
                    val hits = String(bytes, StandardCharsets.UTF_8)
                        .split("\n")
                        .asSequence()
                        .mapIndexedNotNull { i, line ->
                            if (re.matcher(line).find()) Pair(i + 1, line.stripLineEnd()) else null
                        }
                        .toList()
                    if (hits.isEmpty()) null else hits
                }
            } catch (e: Exception) {
                null
            }
        }

        /** Lines `n - context` to `n + context`, for content mode. Re-reads the
          * file, which is cheap next to the round trip it saves the model. */
        fun contextAround(p: Path, n: Int, context: Int): List<Pair<Int, String>> =
            try {
                val lines = String(Files.readAllBytes(p), StandardCharsets.UTF_8).split("\n")
                val from = maxOf(1, n - context)
                val to = minOf(lines.size, n + context)
                (from..to).map { i -> Pair(i, lines[i - 1].stripLineEnd()) }
            } catch (e: Exception) {
                listOf(Pair(n, ""))
            }
    }
}
