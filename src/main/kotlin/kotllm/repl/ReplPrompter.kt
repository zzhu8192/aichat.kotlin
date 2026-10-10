package kotllm.repl

import org.jline.reader.EndOfFileException
import org.jline.reader.LineReader
import org.jline.reader.UserInterruptException

import kotllm.render.Ansi
import kotllm.render.on
import kotllm.tools.Prompter
import kotllm.tools.Prompter.Answer
import kotllm.util.Glyphs

/** Asks the user to approve a tool call, using the REPL's line reader.
 *
 * Reuses the active LineReader rather than reading System.in directly: JLine
 * holds the terminal in raw mode while the REPL is running, so a bare
 * Console/Scanner read would either block or return nothing.
 */
class ReplPrompter(private val reader: () -> LineReader) : Prompter {

    override fun confirm(toolName: String, preview: String): Answer {
        val question = Ansi.Color.Yellow.on("  run $toolName? [y]es / [n]o / [a]ll ${Glyphs.promptCaret} ")
        return loop(question)
    }

    /** Numbered choices, with a free-text escape as the last option.
     *
     * The escape is not decoration: a fixed list is a guess about what the
     * answer might be, and the one time it is wrong the user has no way to say
     * so. Claude Code's prompt works the same way, and the reason is the same
     * -- an "Other" row costs one line and removes the dead end.
     *
     * Reuses the REPL's LineReader for the same reason `confirm` does: JLine
     * holds the terminal in raw mode, so a bare Console read would block or
     * return nothing.
     */
    override fun ask(question: String, options: List<String>, multi: Boolean): List<String>? {
        val free = options.size + 1
        val width = free.toString().length

        println(Ansi.Color.Cyan.on("\n  ${Glyphs.promptCaret} $question"))
        options.forEachIndexed { i, option ->
            println(Ansi.Color.DarkGray.on("    ${(i + 1).toString().padStart(width)}. ") + option)
        }
        println(Ansi.Color.DarkGray.on("    ${free.toString().padStart(width)}. " + "(type your own answer)"))

        val hint =
            if (multi) "  choose one or more (e.g. 1,3), or type an answer ${Glyphs.promptCaret} "
            else "  choose 1-$free, or type an answer ${Glyphs.promptCaret} "

        val line =
            try {
                reader().readLine(Ansi.Color.Yellow.on(hint))?.trim()
            } catch (e: UserInterruptException) {
                // Ctrl-C or Ctrl-D at a question is not an answer. Reporting it as
                // "unanswered" lets the model say so rather than invent a choice.
                null
            } catch (e: EndOfFileException) {
                null
            }

        return when {
            line == null -> null
            line.isEmpty() -> null
            else -> {
                val picked = line.split(Regex("[,\\s]+")).filter { it.isNotEmpty() }
                val asNumbers = picked.mapNotNull { it.toIntOrNull() }

                // Every token has to be a number in range, or the whole thing is
                // free text -- otherwise "2 maybe" would silently become option 2.
                val allNumeric = asNumbers.size == picked.size && picked.isNotEmpty() &&
                    asNumbers.all { n -> n in 1..free }

                if (!allNumeric) listOf(line)
                else if (asNumbers.contains(free))
                    // They chose the free-text row, so ask what they meant rather than
                    // returning "(type your own answer)" as though it were an answer.
                    reader().readLine(Ansi.Color.Yellow.on("  your answer ${Glyphs.promptCaret} "))
                        ?.trim()?.takeIf { it.isNotEmpty() }?.let { listOf(it) }
                else {
                    val chosen = (if (multi) asNumbers.distinct() else asNumbers.take(1)).map { n -> options[n - 1] }
                    if (chosen.isEmpty()) null else chosen
                }
            }
        }
    }

    private fun loop(question: String): Answer {
        var answer: Answer? = null
        while (answer == null) {
            val line =
                try {
                    reader().readLine(question)?.trim()?.lowercase()
                } catch (e: UserInterruptException) {
                    // Ctrl-C or Ctrl-D at an approval prompt means no. Declining is the
                    // only safe reading of an interrupted question.
                    "n"
                } catch (e: EndOfFileException) {
                    "n"
                }

            answer = when (line) {
                "y", "yes" -> Answer.Yes
                "" -> Answer.Yes // bare Enter accepts
                "n", "no" -> Answer.No
                "a", "all" -> Answer.YesToAll
                else -> null
            }
        }
        return answer
    }
}
