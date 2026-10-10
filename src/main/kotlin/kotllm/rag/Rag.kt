package kotllm.rag

import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path

import kotllm.*

/** How retrieval is configured. */
data class RagConfig(
    val enabled: Boolean,
    val profile: ChunkProfile,
    val chunkTokens: Int?,
    val overlapTokens: Int?,
    val topK: Int,
    /** How many passages auto-injection adds, separate from `topK` because a
      * block that rides on *every* request must be much smaller than a result
      * set the model asked for deliberately. */
    val injectTopK: Int,
    val injectMaxChars: Int,
    /** Soft cap on passages from one file, so a large file that mentions a
      * term in every chunk cannot fill the whole result set. */
    val maxPerFile: Int,
    /** Which dense encoder to pair with BM25, or `None` for lexical only. */
    val embedding: EmbeddingMode,
    val embeddingDims: Int,
    /** Cosine floor for a dense hit to count at all. Empirically calibrated;
      * see the sweep in docs/rag.md. Raising it costs recall on typos and
      * morphology, lowering it lets gibberish retrieve confident noise. */
    val minSimilarity: Double,
    val charGramMin: Int,
    val charGramMax: Int,
    val rrfK: Int,
    val bm25K1: Double,
    val bm25B: Double,
    val maxFileChars: Int,
    /** Extra extensions to treat as indexable, beyond the built-in set. */
    val extraExtensions: Set<String>,
    val charsPerTokenProse: Double,
    val charsPerTokenCode: Double
) {
    val chunkerConfig: ChunkerConfig
        get() = ChunkerConfig(profile, chunkTokens, overlapTokens, charsPerTokenProse, charsPerTokenCode)

    /** The encoder these settings describe, or null for BM25 only.
      *
      * Built fresh from config each time rather than cached, so `id` always
      * reflects the settings currently in force -- which is what index
      * invalidation compares against.
      */
    val embedder: Embedder?
        get() = when (embedding) {
            is EmbeddingMode.None -> null
            is EmbeddingMode.Hashing -> {
                // Normalised rather than validated: a swapped range in config should
                // work, not take down the REPL on a constructor requirement.
                val lo = maxOf(1, minOf(charGramMin, charGramMax))
                val hi = maxOf(charGramMin, charGramMax)
                HashingEmbedder(embeddingDims, lo, hi)
            }
        }

    companion object {
        val default: RagConfig = RagConfig(
            enabled = true,
            profile = ChunkProfile.Auto,
            chunkTokens = null,
            overlapTokens = null,
            topK = 8,
            injectTopK = 4,
            injectMaxChars = 8000,
            maxPerFile = 3,
            embedding = EmbeddingMode.Hashing,
            embeddingDims = HashingEmbedder.DefaultDimensions,
            minSimilarity = 0.20,
            charGramMin = 3,
            charGramMax = 5,
            rrfK = 60,
            bm25K1 = 1.2,
            bm25B = 0.75,
            maxFileChars = 400000,
            extraExtensions = emptySet(),
            charsPerTokenProse = 4.0,
            charsPerTokenCode = 3.0
        )
    }
}

data class IndexReport(
    val name: String,
    val root: String,
    val scan: ScanResult,
    val filesIndexed: Int,
    val chunksTotal: Int,
    val chunksAdded: Int,
    val chunksRemoved: Int,
    val skipped: List<String>
) {
    val summary: String
        get() = "index '$name': ${scan.summary}; $chunksTotal chunks total " +
            "(+$chunksAdded, -$chunksRemoved) over $filesIndexed file(s)"
}

/** Indexing and retrieval over a folder.
  *
  * What is indexed is decided by extension, with a denylist for formats that
  * are not text at all. Both lists are deliberately explicit rather than
  * "anything that looks like text": an accidental 200MB minified bundle or a
  * vendored dependency tree turns a useful index into noise.
  */
object Rag {

    /** Source and text formats worth indexing. */
    val TextExtensions: Set<String> = setOf(
        // prose and data
        "md", "markdown", "txt", "rst", "adoc", "org", "text", "csv", "tsv", "log",
        "json", "yaml", "yml", "toml", "ini", "cfg", "conf", "properties", "env", "xml", "html", "htm",
        "css", "scss", "less", "sql", "graphql", "proto", "tf", "tfvars",
        // source
        "scala", "sc", "sbt", "java", "kt", "kts", "groovy", "clj", "cljs",
        "py", "pyi", "rb", "rs", "go", "c", "h", "cc", "cpp", "cxx", "hpp", "hh",
        "cs", "fs", "fsx", "swift", "m", "mm", "php", "pl", "pm", "lua", "r",
        "js", "jsx", "ts", "tsx", "mjs", "cjs", "vue", "svelte", "dart", "ex", "exs", "erl", "hrl",
        "hs", "ml", "mli", "nim", "zig", "jl", "vb", "pas", "asm", "s",
        // shells and build
        "sh", "bash", "zsh", "fish", "ps1", "psm1", "bat", "cmd", "mk", "cmake", "gradle", "bazel", "bzl"
    )

    /** Filenames with no extension that are still worth indexing. */
    val TextFilenames: Set<String> = setOf(
        "makefile", "dockerfile", "rakefile", "gemfile", "procfile", "justfile", "vagrantfile",
        "cmakelists.txt", "readme", "license", "licence", "changelog", "authors", "notice",
        ".gitignore", ".gitattributes", ".editorconfig", ".dockerignore"
    )

    /** Formats that are not text, and directories never walked. Both live in
      * `util.Skips` now: `/file` input, indexing and the `grep`/`glob` tools all
      * need them, and the copies had drifted. */
    val BinaryExtensions: Set<String> = kotllm.util.Skips.binaryExtensions

    val SkipDirs: Set<String> = kotllm.util.Skips.dirs

    fun extensionOf(path: Path): String {
        val name = path.fileName.toString()
        val i = name.lastIndexOf('.')
        return if (i <= 0) "" else name.substring(i + 1).lowercase()
    }

    fun isSkippedDir(root: Path, file: Path): Boolean {
        val rel = try {
            root.toAbsolutePath().normalize().relativize(file.toAbsolutePath().normalize())
        } catch (e: Exception) {
            file
        }
        return (0 until rel.nameCount - 1).any { i ->
            val part = rel.getName(i).toString()
            SkipDirs.contains(part) || (part.startsWith(".") && part.length > 1 && !TextFilenames.contains(part))
        }
    }

    /** Whether a file should be indexed, and why not when it should not. */
    fun eligibility(root: Path, file: Path, config: RagConfig, loaders: Set<String>): Outcome<Unit> {
        val name = file.fileName.toString().lowercase()
        val ext = extensionOf(file)

        return if (isSkippedDir(root, file)) err("in an ignored directory")
        else if (BinaryExtensions.contains(ext) && !loaders.contains(ext))
            // Honours document_loaders when one is configured for the format, since
            // that conversion already exists for `/file` input.
            err("binary format .$ext")
        else if (TextExtensions.contains(ext) || config.extraExtensions.contains(ext) ||
                 TextFilenames.contains(name) || loaders.contains(ext)) ok(Unit)
        else if (ext.isEmpty()) err("no extension and not a known filename")
        else err("extension .$ext not indexed")
    }

    fun isEligible(root: Path, file: Path, config: RagConfig, loaders: Set<String>): Boolean =
        eligibility(root, file, config, loaders).isOk

    /** Re-indexes only what changed, and drops what no longer exists.
      *
      * @param read converts a file to text; supplied so a configured
      *             `document_loaders` command can be used and so tests need no
      *             real conversion.
      */
    fun index(
        idx: RagIndex,
        root: Path,
        config: RagConfig,
        loaders: Set<String>,
        read: (Path) -> Outcome<String>,
        onProgress: (Int, Int) -> Unit = { _, _ -> }
    ): Outcome<IndexReport> {
        if (!Files.isDirectory(root)) return err("not a directory: $root")

        val known = idx.meta?.files ?: emptyList()
        val scan = RagIndex.scan(root, { p -> isEligible(root, p, config, loaders) }, known)

        val chunker = Chunker(config.chunkerConfig)
        val skipped = mutableListOf<String>()

        // Everything except the files being replaced or removed survives
        // untouched, which is what makes this incremental.
        val stale = (scan.changed.map { RagIndex.relativise(root, it) } + scan.removed).toSet()
        val keptFiles = known.filterNot { stale.contains(it.path) }
        val kept = idx.chunks.filterNot { stale.contains(it.path) }

        val work = scan.added + scan.changed
        val fresh = mutableListOf<Chunk>()
        val freshRecords = mutableListOf<FileRecord>()
        var done = 0

        work.forEach { file ->
            val rel = RagIndex.relativise(root, file)
            when (val r = read(file)) {
                is Outcome.Err -> skipped.add("$rel: ${r.message}")
                is Outcome.Ok -> {
                    val raw = r.value
                    val text = if (raw.length > config.maxFileChars) raw.take(config.maxFileChars) else raw
                    val cs = chunker.chunk(rel, text, languageOf(extensionOf(file)), extensionOf(file))
                    if (cs.isEmpty()) skipped.add("$rel: produced no chunks")
                    else {
                        fresh.addAll(cs)
                        freshRecords.add(
                            FileRecord(
                                rel,
                                try { Files.size(file) } catch (e: Exception) { -1L },
                                try { Files.getLastModifiedTime(file).toMillis() } catch (e: Exception) { -1L },
                                cs.size
                            )
                        )
                    }
                }
            }
            done += 1
            onProgress(done, work.size)
        }

        val newChunks = kept + fresh
        val removedCount = idx.chunks.size - kept.size

        val meta = IndexMeta(
            name = idx.name,
            root = root.toAbsolutePath().normalize().toString(),
            profile = ChunkProfile.name(config.profile),
            embeddingModel = idx.meta?.embeddingModel,
            dimensions = idx.meta?.dimensions,
            // Carried over, so a reindex does not silently discard the description.
            description = idx.meta?.description,
            files = (keptFiles + freshRecords).sortedBy { it.path }
        )

        return idx.save(meta, newChunks).map {
            IndexReport(
                name = idx.name,
                root = meta.root,
                scan = scan,
                filesIndexed = meta.files.size,
                chunksTotal = newChunks.size,
                chunksAdded = fresh.size,
                chunksRemoved = removedCount,
                skipped = skipped
            )
        }
    }

    /** Reads a file as UTF-8, refusing anything that looks binary despite its
      * extension. */
    fun readText(file: Path): Outcome<String> =
        try {
            val bytes = Files.readAllBytes(file)
            if (bytes.asList().take(8000).contains(0.toByte())) err("looks binary")
            else ok(String(bytes, StandardCharsets.UTF_8))
        } catch (e: Exception) {
            err(e.message ?: e::class.simpleName ?: "unknown error")
        }

    private fun languageOf(ext: String): String = when (ext.lowercase()) {
        "scala", "sc", "sbt" -> "scala"
        "java" -> "java"
        "kt", "kts" -> "kotlin"
        "py", "pyi" -> "python"
        "rb" -> "ruby"
        "rs" -> "rust"
        "go" -> "go"
        "js", "mjs", "cjs", "jsx" -> "javascript"
        "ts", "tsx" -> "typescript"
        "c", "h" -> "c"
        "cc", "cpp", "cxx", "hpp", "hh" -> "cpp"
        "cs" -> "csharp"
        "sh", "bash", "zsh", "fish" -> "bash"
        "ps1", "psm1" -> "powershell"
        "sql" -> "sql"
        "json" -> "json"
        "yaml", "yml" -> "yaml"
        "xml" -> "xml"
        "html", "htm" -> "html"
        "md", "markdown" -> "markdown"
        "txt", "text", "log" -> "text"
        else -> ext
    }
}
