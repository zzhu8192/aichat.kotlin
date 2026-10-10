package kotllm.repl

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.nio.charset.StandardCharsets

import kotlin.test.Test
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain

import org.jline.reader.LineReader
import org.jline.reader.impl.LineReaderImpl
import org.jline.terminal.Size

/** Emoji completion driven through a real `LineReader`, keystrokes and all.
 *
 * `CompleterTest` calls `complete` directly and inspects the candidates it
 * collected. That is not the same question, and the difference shipped a
 * bug: the completer offered the emoji itself as each candidate's *value*,
 * JLine filters candidates by matching their value against the word being
 * completed, `🔥` does not start with `:fir`, so every candidate was
 * discarded and Tab rang the bell. Every direct-call test passed throughout.
 *
 * So this drives the widget the keyboard actually reaches: feed `\t` in,
 * assert on what comes back out.
 */
class EmojiCompletionTest {

    private val Esc = 27.toChar()
    private val Down = "$Esc[B"
    private val Up = "$Esc[A"
    private val Right = "$Esc[C"

    /** @return the line the reader returned, and everything it drew */
    private fun typeIn(keys: String): Pair<String, String> {
        val `in` = ByteArrayInputStream(keys.toByteArray(StandardCharsets.UTF_8))
        val out = ByteArrayOutputStream()
        val terminal = testTerminal(
            `in`, out,
            // A dumb terminal takes a different path through JLine and does not
            // complete at all, which would make this suite pass by saying nothing.
            type = "xterm-256color",
            size = Size(100, 30)
        )
        try {
            val reader = LineReaderImpl(terminal)
            reader.setCompleter(ReplCompleter({ listOf("m1") }, { listOf("docs") }))
            // Exactly what the REPL sets. AUTO_LIST turns an ambiguous completion
            // into a printed list rather than a beep; AUTO_MENU makes the *second*
            // completion a selectable menu without changing the first.
            reader.setOpt(LineReader.Option.AUTO_LIST)
            reader.setOpt(LineReader.Option.AUTO_MENU)
            reader.setOpt(LineReader.Option.AUTO_MENU_LIST)
            MenuKeys.styleMenu(reader)
            MenuKeys.bindArrows(reader)
            MenuKeys.bindOpenOnArrow(reader)
            return reader.readLine("> ") to out.toString("UTF-8")
        } finally {
            terminal.close()
        }
    }

    /** The listing colours the matched prefix, so `:smiley:` reaches the
     * screen as `ESC[36m:smil ESC[0mey:` and a plain substring search finds
     * nothing. Stripping escapes is the difference between verifying this and
     * only appearing to. */
    private fun stripAnsi(s: String): String = s.replace(Regex("\u001b\\[[0-9;?]*[a-zA-Z]"), "")

    @Test fun `a shortcode that matches exactly one emoji completes on Tab`() {
        // Fails on the bug this spec exists for: with the emoji as the candidate
        // value, JLine matches nothing and the line comes back as it was typed.
        val (line, _) = typeIn(":smiley_c\t\n")
        line.trim() shouldBe ":smiley_cat:"
    }

    @Test fun `an ambiguous shortcode lists the options rather than ringing the bell`() {
        val (line, drawn) = typeIn(":smil\t\n")
        val screen = stripAnsi(drawn)

        screen shouldContain ":smile:"
        screen shouldContain ":smiley:"
        screen shouldContain ":smiling_imp:"
        // The buffer is left alone: there is no single answer to insert.
        line.trim() shouldBe ":smil"
        drawn shouldNotContain "\u0007"
    }

    @Test fun `the emoji each candidate names is shown beside it, so the list is readable`() {
        val (_, drawn) = typeIn(":smil\t\n")
        if (kotllm.util.Glyphs.unicode) stripAnsi(drawn) shouldContain "😄"
    }

    @Test fun `command completion still works, and is not shadowed by the emoji branch`() {
        val (line, _) = typeIn("/he\t\n")
        line.trim() shouldBe "/help"
    }

    // ---- arrow navigation ---------------------------------------------------
    //
    // The second Tab opens the menu with the first candidate selected; the
    // trailing newlines are Enter-to-accept and then Enter-to-submit.

    @Test fun `a second Tab opens a menu and Enter takes the highlighted candidate`() {
        val (line, _) = typeIn(":smil\t\t\n\n")
        line.trim() shouldBe ":smile:"
    }

    @Test fun `down and right move forward through the candidates`() {
        typeIn(":smil\t\t" + Down + "\n\n").first.trim() shouldBe ":smile_cat:"
        typeIn(":smil\t\t" + Down + Down + "\n\n").first.trim() shouldBe ":smiley:"
        typeIn(":smil\t\t" + Right + "\n\n").first.trim() shouldBe ":smile_cat:"
    }

    @Test fun `up moves back again`() {
        typeIn(":smil\t\t" + Down + Down + Up + "\n\n").first.trim() shouldBe ":smile_cat:"
    }

    @Test fun `an arrow does not leak its own escape sequence into the line`() {
        // Unbound, `ESC [ B` dropped out of the menu and left a `B` behind --
        // the reported "can't use arrow keys to navigate".
        val (line, _) = typeIn(":smil\t\t" + Down + "\n\n")
        line shouldNotContain "B"
        line shouldNotContain "["
    }

    @Test fun `the command menu is navigable too, since the arrows were dead in both`() {
        val (line, _) = typeIn("/mo\t\t\n\n")
        line.trim().startsWith("/model") shouldBe true
    }

    // ---- down steps into the list without a Tab first ------------------------

    @Test fun `Down alone opens the list, no Tab needed`() {
        typeIn(":smil" + Down + "\n\n").first.trim() shouldBe ":smile:"
        typeIn(":smil" + Down + Down + "\n\n").first.trim() shouldBe ":smile_cat:"
    }

    @Test fun `Down alone opens the command list too`() {
        typeIn("/mo" + Down + "\n\n").first.trim().startsWith("/model") shouldBe true
    }

    @Test fun `a prefix matching hundreds still lists, because the offer is capped`() {
        // Uncapped this is 219 candidates, past JLine's `list-max`, and JLine's
        // response to a list that long is to print nothing at all -- the key
        // looks dead. Capping is what keeps the list a list.
        val (_, drawn) = typeIn(":s\t\n")
        val screen = stripAnsi(drawn)
        screen shouldContain ":sa"
        drawn shouldNotContain "\u0007"
    }
}
