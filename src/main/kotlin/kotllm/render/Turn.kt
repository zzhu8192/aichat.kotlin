package kotllm.render

import java.time.LocalTime
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotllm.util.Glyphs
import kotllm.util.TokenLedger

/** A column gutter for one block of output: a marker on the first line, an
 * indent on every line after it.
 *
 * Empty by default everywhere except the REPL. Single-hit mode and `--dry-run`
 * write to a pipe as often as to a terminal, and reflowing piped output would
 * make kotllm unusable in a script.
 */
data class Gutter(val first: String, val rest: String) {

    fun isEmpty(): Boolean = first.isEmpty() && rest.isEmpty()

    /** Applies the gutter to an already-rendered block.
     *
     * Blank lines are left bare rather than padded, so the marker lands on the
     * first line that actually carries text and nothing in the transcript ends
     * in trailing whitespace.
     */
    fun render(text: String): String =
        if (isEmpty()) text
        else {
            var marked = false
            text
                .split("\n")
                .map { line ->
                    if (line.isEmpty()) line
                    else if (marked) rest + line
                    else { marked = true; first + line }
                }
                .joinToString("\n")
        }

    companion object {
        val none: Gutter = Gutter("", "")
    }
}

/** How a finished turn is laid out in the transcript.
 *
 * The two rules bracket the live input line and nothing else, so a turn that
 * has been answered is *reprinted* above them: the question echoed with the
 * caret, the reply in a bulleted gutter, then a grey line saying what the turn
 * cost. The line editor's own echo is erased first ([kotllm.repl.Chrome]),
 * because leaving it is what made the box appear to swell around the whole
 * conversation.
 */
object Turn {

    const val Marker = "*"
    const val Indent = "  "

    /** The gutter a reply shares with its tool calls, so both line up under the
     * question. Matches what [ToolRender] already emits. */
    val gutter: Gutter = Gutter("$Marker ", Indent)

    /** The question, as it goes into the transcript. Multi-line input keeps the
     * caret on the first line only and indents the rest under it. */
    fun question(input: String): String =
        input.lines().withIndex()
            .map { (i, line) ->
                if (i == 0)
                    Ansi.Color.White.on("${Glyphs.promptCaret} ") + plain(line)
                else
                    Indent + plain(line)
            }
            .joinToString("\n")

    /** The grey line under a reply: how long the turn took, when it finished, and
     * what it generated.
     *
     * Output tokens are this turn's own, which is the one number the footer
     * cannot give -- it carries the session total, and after a few turns that
     * says nothing about the answer just printed.
     */
    fun processed(elapsedMs: Long, outputTokens: Int, at: LocalTime = LocalTime.now()): String {
        val parts = mutableListOf<String>()
        parts += "Processed for ${elapsed(elapsedMs)}"
        parts += "done ${clock.format(at)}"
        if (outputTokens > 0)
            parts += "${Glyphs.tokensOut} ${TokenLedger.compact(outputTokens.toLong())} tokens"

        return Ansi.Color.DarkGray.on("$Indent$Marker ${parts.joinToString(" ${Glyphs.bullet} ")}")
    }

    /** One decimal under ten seconds, whole seconds past it: 0.4s and 13s are
     * each reported in the unit that carries information. */
    private fun elapsed(ms: Long): String =
        if (ms < 10_000L) TokenLedger.seconds(ms) else TokenLedger.duration(ms)

    /** Locale-fixed on purpose: a locale whose am/pm marker is empty would drop
     * the half of the day from the line. */
    private val clock = DateTimeFormatter.ofPattern("h:mm a", Locale.US)

    /** Model and user text can both contain raw ANSI escapes; fansi throws on
     * those unless they are sanitized, and an exception here would lose the
     * turn. */
    private fun plain(line: String): String =
        Ansi.Color.White(Ansi.str(line)).render()
}
