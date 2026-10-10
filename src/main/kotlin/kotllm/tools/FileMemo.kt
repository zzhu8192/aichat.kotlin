package kotllm.tools

import java.nio.file.Path

/** Caps on what one file-tool call may return.
  *
  * Every one of them reports what it withheld rather than truncating in
  * silence: a model draws conclusions from an empty tail, so a small cap that
  * admits itself is far better than a large one that does not.
  */
data class FileToolLimits(
    val readMaxChars: Int = 40_000,
    val globMaxResults: Int = 200,
    val grepMaxResults: Int = 200,
    /** A bound on the walk itself, not on the output. A `grep` launched at the
      * root of a large tree must terminate in a bounded time even when almost
      * nothing matches. */
    val grepMaxFilesScanned: Int = 20_000,
    val lsMaxEntries: Int = 500,
    val outputMaxChars: Int = 24_000,
    /** Ceiling on a file bound into the interpreter by `read_into`.
      *
      * Far larger than `readMaxChars`, and for a different reason: the content
      * never reaches the model, so the constraint is heap rather than context
      * window. It exists only so a 5GB file fails with a sentence instead of
      * an OutOfMemoryError that takes the session with it. */
    val bindMaxChars: Int = 10_000_000
) {
    companion object {
        val default: FileToolLimits = FileToolLimits()
    }
}

/** Which files this process has read or written.
  *
  * Consulted before an existing file is replaced. Not a security measure -- a
  * data-loss one: the worst thing a file tool can do is replace eight hundred
  * lines it never looked at with the twenty the model had in mind.
  *
  * Injected rather than owned by `FileTools`, because `write_from_eval` lives in
  * `Tools` (it needs the interpreter) and writes to disk too; unshared, the
  * guard would have had a one-call bypass.
  *
  * Per-process is the right scope: a file that changed under us between sessions
  * ought to be looked at again.
  */
class FileMemo {

    private val seen = mutableSetOf<String>()

    private fun key(p: Path): String = p.toAbsolutePath().normalize().toString()

    fun mark(p: Path) {
        synchronized(this) { seen += key(p) }
    }

    fun hasSeen(p: Path): Boolean = synchronized(this) { seen.contains(key(p)) }

    val size: Int get() = synchronized(this) { seen.size }
}
