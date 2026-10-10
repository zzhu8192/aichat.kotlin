package kotllm.render

import java.io.PrintStream
import kotllm.client.StreamEvent
import kotllm.config.ReasoningDisplay

/** Renders a streamed completion to the terminal.
 *
 * Two output modes, chosen by `highlight`:
 *
 *   - on (default): text is buffered a line at a time and flushed highlighted
 *     once its newline arrives. Deliberately avoids echoing raw tokens and
 *     rewriting the line with `\r`, which corrupts the display as soon as a
 *     line wraps.
 *   - off: raw token passthrough, for true token-by-token streaming or piping.
 *
 * Reasoning goes to a separate channel governed by [ReasoningDisplay],
 * because Qwen 3.5 spends the large majority of its tokens there.
 *
 * `gutter` puts the reply in the transcript's marker-and-indent column, and is
 * empty by default so piped output is untouched.
 */
class StreamRender(
    val mode: ReasoningDisplay,
    val highlight: Boolean,
    val gutter: Gutter = Gutter.none,
    val out: PrintStream = System.out,
    val status: PrintStream = System.err
) {

    private val md = MarkdownLineRenderer(highlight)
    private val pending = StringBuilder()

    private var reasoningChars = 0
    private var statusWidth = 0
    private var textStarted = false
    private var atLineStart = true

    /** Whether the gutter's marker has been spent. The first line of a block
     * carries it, every line after it gets the indent. */
    private var marked = false

    private val interactive = System.console() != null

    fun onEvent(e: StreamEvent) = when (e) {
        is StreamEvent.ReasoningDelta -> onReasoning(e.text)
        is StreamEvent.TextDelta -> onText(e.text)
        else -> {}
    }

    private fun onReasoning(t: String) {
        when (mode) {
            ReasoningDisplay.Hide -> {}
            ReasoningDisplay.Show -> {
                write(Ansi.Color.DarkGray(sanitize(t)).render())
                out.flush()
            }
            ReasoningDisplay.Collapse -> {
                reasoningChars += t.length
                if (interactive && !textStarted) {
                    val text = "${gutter.rest}${kotllm.util.Glyphs.ellipsis} thinking ($reasoningChars chars)"
                    statusWidth = maxOf(statusWidth, text.length)
                    status.print("\r${Ansi.Color.DarkGray.on(text)}")
                    status.flush()
                }
            }
        }
    }

    private fun onText(t: String) {
        if (!textStarted) {
            textStarted = true
            clearStatus()
            if (mode == ReasoningDisplay.Show && !atLineStart) {
                out.print("\n")
                atLineStart = true
            }
        }

        if (!highlight) {
            write(t)
            out.flush()
            return
        }

        pending.append(t)
        var idx = pending.indexOf("\n")
        while (idx >= 0) {
            val line = pending.substring(0, idx)
            pending.delete(0, idx + 1)
            write(md.line(line) + "\n")
            idx = pending.indexOf("\n")
        }
        out.flush()
    }

    /** Writes through the gutter, inserting its marker or indent at each line
     * start.
     *
     * Has to work on arbitrary fragments rather than whole lines: with
     * highlighting off the endpoint's tokens arrive mid-line, and reasoning in
     * `show` mode streams the same way. Blank lines are left bare so a gap in
     * the reply does not become a line of trailing spaces, and the marker waits
     * for the first line that has text on it.
     */
    private fun write(s: String) {
        var i = 0
        while (i < s.length) {
            val nl = s.indexOf('\n', i)
            val end = if (nl < 0) s.length else nl
            if (end > i) {
                if (atLineStart) {
                    out.print(if (marked) gutter.rest else { marked = true; gutter.first })
                    atLineStart = false
                }
                out.print(s.substring(i, end))
            }
            if (nl < 0) i = end
            else {
                out.print("\n")
                atLineStart = true
                i = nl + 1
            }
        }
    }

    /** Ends the response: flushes the trailing partial line and leaves the cursor
     * on a fresh line so the next prompt is not glued to the answer. */
    fun finish() {
        clearStatus()
        if (pending.isNotEmpty()) {
            write(md.line(pending.toString()))
            pending.setLength(0)
        }
        if (!atLineStart) out.print("\n")
        out.flush()
        atLineStart = true
    }

    /** Reasoning characters seen, for `/info` and the token ledger. */
    fun reasoningSeen(): Int = reasoningChars

    fun sawText(): Boolean = textStarted

    private fun clearStatus() {
        if (statusWidth > 0) {
            status.print("\r" + " ".repeat(statusWidth + 1) + "\r")
            status.flush()
            statusWidth = 0
        }
    }

    private fun sanitize(s: String): Str =
        Ansi.str(s)
}
