package kotllm.repl

import org.jline.reader.LineReader

/** Breaks the cycle between the runner and the REPL.
 *
 * Tool approval has to ask the user, which needs the REPL's line reader; the
 * REPL needs the runner, which needs the prompter. Rather than restructure
 * ownership around one prompt, the prompter holds this and the REPL fills it
 * in once constructed.
 */
class ReaderHolder {
    @Volatile
    private var reader: LineReader? = null

    fun set(r: LineReader) {
        reader = r
    }

    val get: LineReader
        get() = reader ?: throw IllegalStateException("no line reader available for tool approval")
}
