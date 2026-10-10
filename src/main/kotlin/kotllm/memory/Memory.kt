package kotllm.memory

import kotllm.*

import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import kotlin.math.min

data class MemoryFile(val path: Path, val content: String, val scope: MemoryScope) {
    val chars: Int get() = content.length

    /**
     * The name the model addresses this memory by: the filename without `.md`.
     *
     * Identity is the filename and nothing else, which is what makes
     * `add_or_update_memory` an upsert and `delete_memory` possible at all.
     */
    val name: String get() {
        val f = path.fileName.toString()
        return if (f.lowercase().endsWith(".md")) f.dropLast(3) else f
    }

    /**
     * A one-line label for the index block, taken from the first non-empty
     * line rather than from frontmatter - no format, therefore no parser, and
     * it works unchanged on hand-written files. Leading markdown punctuation is
     * stripped because `#` and `-` are noise in a label.
     */
    val label: String get() {
        val first = content.lines().map { it.trim() }.firstOrNull { it.isNotEmpty() } ?: ""
        val bare = first.dropWhile { it == '#' || it == '-' || it == '*' || it == ' ' }.trim()
        val clean = bare.replace(Regex("\\s+"), " ").removeSuffix("#").trim()
        return if (clean.length <= 120) clean else clean.take(117) + "..."
    }
}

sealed interface MemoryScope {
    /** From the config directory: applies wherever kotllm runs. */
    data object Global : MemoryScope

    /** A KOTLLM.md found in the working directory or an ancestor. */
    data object Project : MemoryScope

    companion object {
        fun name(s: MemoryScope): String = when (s) {
            Global -> "global"
            Project -> "project"
        }
    }
}

data class MemoryConfig(
    val enabled: Boolean,
    val maxChars: Int,
    /** Filename looked for in the working directory and its ancestors. */
    val projectFile: String
) {
    companion object {
        val default: MemoryConfig = MemoryConfig(enabled = true, maxChars = 8000, projectFile = "KOTLLM.md")
    }
}

/**
 * Markdown memories, injected into the system prompt.
 *
 * Two scopes, both plain files: a global directory in the config dir, and a
 * `KOTLLM.md` found by walking up from the working directory. Files rather
 * than a database or an embedding store - greppable and diffable means
 * inspectable without kotllm, which is most of the value when the model acts
 * on them every turn.
 *
 * Loaded fresh each turn, so editing one in another window takes effect on the
 * next message. See docs/memory.md.
 */
final class Memory(val config: MemoryConfig, val memoryDir: Path, val workingDir: () -> Path) {

    /** How far up the tree to look for a project file. Bounded so a deep path on
     * a network share does not turn every turn into a directory crawl. */
    private val MaxAncestors = 12

    val enabled: Boolean get() = config.enabled

    /**
     * Global files first, then project: the project file is more specific, and
     * later text wins when two memories disagree.
     *
     * Uncached, and there is a test asserting it - an edit in another window
     * must be visible to the very next call. Caching this was tried and
     * reverted; see `indexOnlyCache` for the narrower version that survived.
     */
    fun load(): List<MemoryFile> {
        return if (!config.enabled) emptyList()
        else globalFiles() + projectFiles()
    }

    private fun globalFiles(): List<MemoryFile> {
        if (!Files.isDirectory(memoryDir)) return emptyList()
        return try {
            Files.list(memoryDir).use { stream ->
                stream
                    .filter { p -> Files.isRegularFile(p) && p.fileName.toString().endsWith(".md") }
                    .sorted()
                    .toList()
                    .mapNotNull { read(it, MemoryScope.Global) }
            }
        } catch (e: Exception) {
            emptyList()
        }
    }

    /**
     * Walks from the working directory upwards, nearest last so the most
     * specific file has the final word.
     */
    private fun projectFiles(): List<MemoryFile> {
        val found = mutableListOf<MemoryFile>()
        var dir: Path? = workingDir()
        var depth = 0

        while (dir != null && depth < MaxAncestors) {
            val candidate = dir.resolve(config.projectFile)
            read(candidate, MemoryScope.Project)?.let { found.add(it) }
            dir = dir.parent
            depth += 1
        }
        return found.reversed()
    }

    private fun read(path: Path, scope: MemoryScope): MemoryFile? {
        return try {
            val text = String(Files.readAllBytes(path), StandardCharsets.UTF_8).trim()
            if (text.isEmpty()) null else MemoryFile(path, text, scope)
        } catch (e: Exception) {
            null
        }
    }

    /**
     * How the budget was spent this turn.
     *
     * @param injected bodies that fit, in load order
     * @param indexed  memories over budget, carried as one `name -- label` line
     *                 each instead of being dropped
     * @param dropped  pathological case: not even an index line fit
     */
    data class MemoryPlan(
        val injected: List<MemoryFile>,
        val indexed: List<MemoryFile>,
        val dropped: List<MemoryFile>
    )

    /**
     * Decides what this turn can afford.
     *
     * Injection beats lookup while the corpus fits, and the reason is
     * arithmetic. A lookup tool costs its schema on every request plus a round
     * trip plus the risk the model never calls it, so under a few thousand
     * characters it pays more to deliver less.
     *
     * Past the budget, the overflow degrades to one line each
     * (`name -- first line`) rather than being dropped. That is what makes
     * `read_memory` discoverable: a tool schema announces that a tool exists,
     * never that a corpus worth reading does. Index lines are charged against
     * the same budget, so the configured number stays the truth about what is
     * spent. See docs/memory.md.
     */
    fun plan(): MemoryPlan {
        val files = load()
        val injected = mutableListOf<MemoryFile>()
        val indexed = mutableListOf<MemoryFile>()
        val dropped = mutableListOf<MemoryFile>()
        var used = 0

        for (f in files) {
            val bodyCost = f.chars + f.name.length + 24
            val indexCost = f.name.length + f.label.length + 7
            when {
                used + bodyCost <= config.maxChars -> {
                    injected.add(f)
                    used += bodyCost
                }
                used + indexCost <= config.maxChars -> {
                    indexed.add(f)
                    used += indexCost
                }
                else -> dropped.add(f)
            }
        }
        return MemoryPlan(injected, indexed, dropped)
    }

    /**
     * True when at least one memory is present only as an index line, the one
     * condition under which a lookup tool is worth its schema.
     *
     * Cached for a second, while `load` deliberately is not.
     * `Runner.activeTools` asks this to decide whether to offer `read_memory`,
     * and the status footer asks `activeTools` on every redraw - so without it,
     * drawing a prompt lists the memory directory and reads every file in it.
     *
     * Safe to stale: all a second can do is offer or withhold a tool whose
     * absence costs nothing, while the prompt the model reads is always built
     * from a fresh load. Invalidated on every write from here.
     */
    @Volatile
    private var indexOnlyCache: Pair<Long, Boolean>? = null
    private val CacheMillis = 1000L

    fun hasIndexOnly(): Boolean {
        if (!config.enabled) return false
        return synchronized(this) {
            val now = System.currentTimeMillis()
            val cached = indexOnlyCache
            if (cached != null && now - cached.first < CacheMillis) {
                cached.second
            } else {
                val answer = plan().indexed.isNotEmpty()
                indexOnlyCache = Pair(now, answer)
                answer
            }
        }
    }

    private fun invalidate() {
        synchronized(this) {
            indexOnlyCache = null
        }
    }

    /**
     * Creates or replaces one named memory in the global directory.
     *
     * Global only: a project `KOTLLM.md` is a file the user curates and may have
     * checked in, and a tool rewriting it wants the read-before-overwrite guard
     * `write` has, which there is nothing here to satisfy.
     *
     * Through `atomicWrite`, since a file injected into every request is the
     * last place a half-written one should be possible.
     */
    fun upsert(rawName: String, content: String): Outcome<Path> {
        return slug(rawName).flatMap { name ->
            val body = content.trim()
            when {
                body.isEmpty() -> err("a memory needs content; to remove one use delete_memory")
                body.length > config.maxChars -> err(
                    "that memory is ${body.length} chars, over the whole ${config.maxChars}-char budget. " +
                        "Memories are injected into every request, so keep one to a fact."
                )
                else -> try {
                    if (!Files.exists(memoryDir)) Files.createDirectories(memoryDir)
                    val file = memoryDir.resolve("$name.md")
                    kotllm.util.AtomicWrite.write(file, if (body.endsWith("\n")) body else body + "\n")
                    invalidate()
                    ok(file)
                } catch (e: Exception) {
                    err(e.message ?: e::class.simpleName ?: "unknown error")
                }
            }
        }
    }

    /**
     * Removes one named global memory. Reports a miss with the names that do
     * exist: the model is working from an index block it read a turn or two ago,
     * and the list is what lets it correct itself in the same call.
     */
    fun delete(rawName: String): Outcome<Path> {
        return slug(rawName).flatMap { name ->
            val file = memoryDir.resolve("$name.md")
            if (!Files.isRegularFile(file)) {
                val known = load().filter { it.scope == MemoryScope.Global }.map { it.name }
                val message = if (known.isEmpty())
                    "no memory named '$name', and there are none stored"
                else
                    "no memory named '$name'; stored: ${known.joinToString(", ")}"
                err(message)
            } else {
                try {
                    Files.delete(file)
                    invalidate()
                    ok(file)
                } catch (e: Exception) {
                    err(e.message ?: e::class.simpleName ?: "unknown error")
                }
            }
        }
    }

    /**
     * One memory by name, for the tier-2 read.
     *
     * Searched over loaded files rather than resolved as a path, so a project
     * `KOTLLM.md` listed in the index block can be read too. On a collision
     * across ancestors the nearest wins, matching the rule that decides
     * which text has the final word when two memories disagree.
     */
    fun byName(rawName: String): Outcome<MemoryFile> {
        val wanted = rawName.trim().removeSuffix(".md").lowercase()
        val files = load()
        val found = files.reversed().firstOrNull { it.name.lowercase() == wanted }
        return if (found != null) {
            ok(found)
        } else {
            val known = files.map { it.name }
            val message = if (known.isEmpty())
                "no memory named '$rawName', and there are none stored"
            else
                "no memory named '$rawName'; stored: ${known.joinToString(", ")}"
            err(message)
        }
    }

    /**
     * Appends a note to the global notes file, which is what `/memory add`
     * writes to. Kept alongside `upsert` because a quick unnamed line typed by
     * the user is a genuinely different act from the model curating a named
     * fact - and it is one file, so it stays addressable as `notes`.
     */
    fun append(note: String): Outcome<Path> {
        return try {
            if (!Files.exists(memoryDir)) Files.createDirectories(memoryDir)
            val file = memoryDir.resolve("notes.md")
            val stamp = java.time.LocalDate.now().toString()
            val line = "- ($stamp) ${note.trim().replace("\n", " ")}\n"
            Files.write(
                file,
                line.toByteArray(StandardCharsets.UTF_8),
                java.nio.file.StandardOpenOption.CREATE,
                java.nio.file.StandardOpenOption.APPEND
            )
            invalidate()
            ok(file)
        } catch (e: Exception) {
            err(e.message ?: e::class.simpleName ?: "unknown error")
        }
    }

    val dir: Path get() = memoryDir

    companion object {
        /**
         * The name the model sees for the tier-2 read. Interpolated into the index
         * block rather than repeated as a literal, so a rename cannot leave the
         * prompt telling the model to call a tool that no longer exists - the same
         * rule `Tools.SearchName` earns its existence for.
         */
        const val ReadName = "read_memory"

        /**
         * Filename identity from whatever the model sent.
         *
         * Also the containment check: every path separator and dot run is collapsed
         * into `-`, so `../../etc/passwd` cannot address anything outside the memory
         * directory. That is a consequence of the slug rather than a check bolted
         * beside it, which is the version that cannot be forgotten.
         */
        fun slug(raw: String): Outcome<String> {
            var s = raw.trim().removeSuffix(".md").lowercase()
                .replace(Regex("[^a-z0-9]+"), "-")
                .replace(Regex("-+"), "-")
                .removePrefix("-")
                .removeSuffix("-")
            if (s.isEmpty()) {
                return err("'$raw' has no letters or digits to make a memory name from")
            }
            if (s.length > 60) {
                s = s.take(60).removeSuffix("-")
            }
            return ok(s)
        }

        operator fun invoke(config: MemoryConfig, memoryDir: Path): Memory {
            return Memory(config, memoryDir) { Paths.get(System.getProperty("user.dir", ".")).toAbsolutePath() }
        }
    }
}
