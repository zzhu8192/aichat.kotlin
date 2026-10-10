package kotllm.tools

import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.atomic.AtomicInteger

import kotllm.*
import kotllm.engine.GroovyEngine

/** One tool file, what it produced, and why it did not. */
data class LoadedToolFile(val path: Path, val tools: List<String>, val error: String?) {
    val ok: Boolean get() = error == null
}

/** Groovy tool files in `<config dir>/tools/`, evaluated in the live session.
  *
  * **Interpreted, not compiled.** The Scala version compiled each file with
  * `scala.tools.nsc.Global` into class files, cached them under a fingerprint
  * of the sources, and loaded them back through a dedicated classloader --
  * about four hundred lines, nearly all of it in service of avoiding a 4.4s
  * compiler startup twice. Evaluating the file in the session that is already
  * running removes the cache, the manifest, the classloader and the fingerprint
  * together.
  *
  * The contract is now: **a tool file is a Groovy script whose value is a
  * [Tool].** No reflection over a declared argument type and no schema derived
  * from a case class -- the file states its own schema, which is what the model
  * is shown either way. `KotllmTool` and its argument decoding are gone with it.
  *
  * Each file is evaluated separately so one that fails to compile is reported
  * and skipped rather than taking the others with it.
  */
class UserTools(private val dir: Path, private val engine: () -> GroovyEngine) {

    private var built: List<Tool> = emptyList()
    private var lastLoad: List<LoadedToolFile> = emptyList()
    private var loading = false
    private val revision = AtomicInteger(0)

    /** Bumped whenever the set changes, so the tool registry rebuilds once per
      * change rather than on every turn. */
    val version: Int get() = revision.get()

    /** Whether a background load is still in flight. */
    val compiling: Boolean get() = synchronized(this) { loading }

    /** What is ready right now, without blocking: the status footer asks this on
      * every redraw, and blocking a redraw on a compiler would be the worse bug. */
    val ready: List<Tool> get() = synchronized(this) { built }

    private fun sources(): List<Path> =
        if (!Files.isDirectory(dir)) emptyList()
        else Files.list(dir).use { s ->
            s.filter { p -> Files.isRegularFile(p) && p.fileName.toString().endsWith(".groovy") }
                .sorted()
                .toList()
        }

    fun load(): List<LoadedToolFile> {
        synchronized(this) { loading = true }
        try {
            val files = sources()
            if (files.isEmpty()) {
                synchronized(this) {
                    built = emptyList()
                    lastLoad = emptyList()
                }
                return emptyList()
            }
            val results = files.map { path -> evaluate(path) }
            synchronized(this) {
                built = results.mapNotNull { it.second }
                lastLoad = results.map { it.first }
                revision.incrementAndGet()
                return lastLoad
            }
        } finally {
            synchronized(this) { loading = false }
        }
    }

    private fun evaluate(path: Path): Pair<LoadedToolFile, Tool?> {
        val source = try {
            Files.readString(path)
        } catch (e: Exception) {
            return Pair(LoadedToolFile(path, emptyList(), "could not read: ${e.message}"), null)
        }

        return engine().evalValue(source).fold(
            onErr = { e -> Pair(LoadedToolFile(path, emptyList(), e), null) },
            onOk = { value ->
                if (value is Tool) Pair(LoadedToolFile(path, listOf(value.name), null), value)
                else Pair(
                    LoadedToolFile(
                        path,
                        emptyList(),
                        "the file's last expression must be a kotllm.tools.Tool, but it was " +
                            (value?.let { it::class.qualifiedName } ?: "nothing")
                    ),
                    null
                )
            }
        )
    }

    fun reload(): List<LoadedToolFile> = load()

    fun tools(): List<Tool> = ready

    /** Loads off the critical path. Nothing waits on this thread; `awaitReady`
      * is for the few places that need the answer.
      *
      * **`loading` is set here, before the thread starts, not inside it.**
      * Every waiter checks `compiling` first, so a caller that arrived before
      * the thread was scheduled saw `false`, skipped the wait, and built a
      * request with no user tools in it -- a tool the model is never told about
      * is one that does not exist.
      */
    fun startInBackground() {
        synchronized(this) { loading = true }
        val t = Thread({
            try {
                load()
            } catch (e: Exception) {
                // Not fatal: the next call tries again and reports properly.
                synchronized(this) { loading = false }
            }
        }, "kotllm-user-tools")
        t.isDaemon = true
        t.start()
    }

    fun awaitReady(millis: Long = 60_000L): Boolean {
        val deadline = System.currentTimeMillis() + millis
        while (compiling && System.currentTimeMillis() < deadline) Thread.sleep(25)
        return !compiling
    }

    /** The first file-level error, for the one-line warning on startup. */
    fun firstError(): String? = synchronized(this) { lastLoad.firstNotNullOfOrNull { it.error } }

    fun report(): String {
        val loaded = if (lastLoad.isEmpty()) load() else lastLoad
        return if (!Files.isDirectory(dir))
            "no tools directory. Create $dir and drop a .groovy file in it."
        else if (loaded.isEmpty()) "no .groovy files in $dir"
        else loaded.joinToString("\n") { f ->
            val name = f.path.fileName.toString()
            if (f.ok) "  $name: ${f.tools.joinToString(", ")}"
            else "  $name: ${f.error?.lineSequence()?.firstOrNull() ?: "failed"}"
        }
    }
}
