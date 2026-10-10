package kotllm.render

import kotllm.util.Glyphs
import kotllm.util.TokenLedger

/** Shows tool activity as it happens.
 *
 * A tool call is the one point where the model reaches out of the sandbox and
 * touches the machine, so it is never silent: the call and a condensed result
 * are always printed, whether or not approval was required.
 */
object ToolRender {

    /** Lines of a preview or result to show before eliding.
     *
     * **Display only. The model receives the whole thing either way**, so this
     * is screen noise rather than context, and turning it down costs nothing
     * but the ability to read a result without scrolling.
     *
     * Configurable because the right answer is not the same twice: 12 and 16
     * were the hardcoded values from the first tool harness and they bury a
     * conversation the moment a tool reads a file. Reported as "too much",
     * correctly. `tool_output_lines` and `tool_preview_lines` set them, and
     * `/set` moves them mid-session, which is when you actually discover a
     * result was worth seeing in full.
     */
    const val DefaultPreviewLines = 3
    const val DefaultResultLines = 6

    private const val MaxLineWidth = 160

    private val marker = "*"
    private val gutter = if (Glyphs.unicode) "│" else "|"

    /** Announces a call about to be made. */
    fun announce(toolName: String, preview: String, maxLines: Int = DefaultPreviewLines) {
        println(Ansi.Color.Blue.on("$marker $toolName"))
        if (preview.trim().isNotEmpty()) {
            println(indent(preview, maxLines, Ansi.Color.DarkGray))
        }
    }

    /** Shows what came back. Errors are coloured but not treated as fatal: a
     * compile error the model then fixes is normal progress.
     *
     * The elapsed time is printed inline because a tool call is the one place a
     * turn can stall for an unbounded period, and seeing 40s against a command
     * is how you notice.
     */
    fun result(
        content: String,
        isError: Boolean,
        durationMs: Long = -1L,
        maxLines: Int = DefaultResultLines
    ) {
        val colour = if (isError) Ansi.Color.Yellow else Ansi.Color.DarkGray
        if (content.trim().isNotEmpty()) {
            println(indent(content, maxLines, colour))
        }
        if (durationMs >= 0) {
            println(
                Ansi.Color.DarkGray.on("  $gutter ${TokenLedger.seconds(durationMs)}")
            )
        }
    }

    private fun indent(text: String, maxLines: Int, colour: Attrs): String {
        val lines = text.trimEnd().split("\n")
        val shown = lines.take(maxLines)
        val elided = lines.size - shown.size
        val body = shown.map { l ->
            val clipped = if (l.length > MaxLineWidth) l.take(MaxLineWidth) + "..." else l
            colour(Ansi.str("  $gutter $clipped")).render()
        }
        val tail =
            if (elided > 0)
                listOf(colour(Ansi.str("  $gutter ... $elided more line(s)")).render())
            else
                emptyList()
        return (body + tail).joinToString("\n")
    }
}
