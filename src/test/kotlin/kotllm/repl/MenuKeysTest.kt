package kotllm.repl

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.nio.charset.StandardCharsets
import java.util.regex.Pattern

import kotlin.test.Test
import io.kotest.assertions.withClue
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldStartWith
import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.collections.shouldNotContain

import org.jline.reader.LineReader
import org.jline.reader.impl.LineReaderImpl
import org.jline.terminal.Size

import kotllm.render.Emoji

/** The menu's colours, and the arrow keys outside it.
 *
 * Both are things the eye catches and a string assertion does not, so
 * these drive a real `LineReader` and read the escape codes it emitted.
 */
class MenuKeysTest {

    private val Esc = 27.toChar()
    private val Down = "$Esc[B"
    private val Up = "$Esc[A"
    // Built from character codes for the same reason MenuKeys does it: a literal
    // control byte in source is one encoding change away from vanishing.
    private val Tab = 9.toChar().toString()
    private val Enter = 10.toChar().toString()

    private fun session(
        keys: String,
        style: Boolean = true,
        history: List<String> = emptyList()
    ): Pair<String, Set<String>> {
        val `in` = ByteArrayInputStream(keys.toByteArray(StandardCharsets.UTF_8))
        val out = ByteArrayOutputStream()
        val terminal = testTerminal(`in`, out, size = Size(100, 30))
        try {
            val reader = LineReaderImpl(terminal)
            reader.setCompleter(ReplCompleter({ listOf("m1") }, { listOf("docs") }))
            reader.setOpt(LineReader.Option.AUTO_LIST)
            reader.setOpt(LineReader.Option.AUTO_MENU)
            reader.setOpt(LineReader.Option.AUTO_MENU_LIST)
            if (style) MenuKeys.styleMenu(reader)
            MenuKeys.bindArrows(reader)
            MenuKeys.bindOpenOnArrow(reader)
            MenuKeys.bindTab(reader)
            MenuKeys.bindListAsYouType(reader)
            history.forEach { reader.history.add(it) }
            val line = try {
                reader.readLine("> ")
            } catch (e: Throwable) {
                ""
            }
            return line to sgrCodes(out.toString("UTF-8"))
        } finally {
            terminal.close()
        }
    }

    /** Everything drawn, with escapes removed.
     *
     * The list colours the matched prefix, so `:haircut:` reaches the screen
     * as `ESC[36m:hai ESC[0m rcut:` -- a search for the whole name finds
     * nothing whether or not it was drawn. */
    private fun drawn(keys: String): String {
        val `in` = ByteArrayInputStream(keys.toByteArray(StandardCharsets.UTF_8))
        val out = ByteArrayOutputStream()
        val terminal = testTerminal(`in`, out, size = Size(120, 40))
        try {
            val reader = LineReaderImpl(terminal)
            reader.setCompleter(ReplCompleter({ listOf("m1") }, { listOf("docs") }))
            reader.setOpt(LineReader.Option.AUTO_LIST)
            reader.setOpt(LineReader.Option.AUTO_MENU)
            reader.setOpt(LineReader.Option.AUTO_MENU_LIST)
            MenuKeys.styleMenu(reader)
            MenuKeys.bindArrows(reader)
            MenuKeys.bindOpenOnArrow(reader)
            MenuKeys.bindTab(reader)
            MenuKeys.bindListAsYouType(reader)
            try {
                reader.readLine("> ")
            } catch (e: Throwable) {
                // The scripted input runs out; the captured bytes are the point.
            }
            val esc = Esc
            return out.toString("UTF-8").replace(Regex(esc.toString() + "\\[[0-9;?]*[a-zA-Z]"), "")
        } finally {
            terminal.close()
        }
    }

    /** Every SGR parameter the session emitted. 105 is a bright-magenta
     * background, 7 is reverse video. */
    private fun sgrCodes(s: String): Set<String> {
        val m = Pattern.compile(Esc.toString() + "\\[([0-9;]*)m").matcher(s)
        val b = mutableSetOf<String>()
        while (m.find()) m.group(1).split(";").forEach { b.add(it) }
        return b
    }

    @Test fun `JLine really does paint the menu magenta, left to itself`() {
        // The control for the test below: without it, asserting the absence of
        // a colour proves nothing, because it might never have been there.
        val (_, codes) = session(":smil" + Down + "\n\n", style = false)
        codes shouldContain "105"
    }

    @Test fun `styleMenu drops the background and highlights by reversing instead`() {
        val (_, codes) = session(":smil" + Down + "\n\n", style = true)
        codes shouldNotContain "105"
        codes shouldNotContain "45"
        // Reverse video for the selected row, cyan for the matched prefix.
        codes shouldContain "7"
        codes shouldContain "36"
    }

    // ---- arrows outside the menu keep doing what they did --------------------

    @Test fun `Up recalls history on an empty line`() {
        val (line, _) = session(Up + "\n", history = listOf("earlier", "most recent"))
        line.trim() shouldBe "most recent"
    }

    @Test fun `Up does not hijack a line that is not a shortcode or a command`() {
        val (line, _) = session("hello" + Up + "\n", history = listOf("earlier"))
        line.trim() shouldBe "hello"
    }

    @Test fun `a colon that is not a shortcode leaves the arrows alone`() {
        // `3:2` is a ratio, not a half-typed `:word`, so Down must not try to
        // complete it.
        val (line, _) = session("3:2" + Down + "\n", history = listOf("earlier"))
        line.trim() shouldBe "3:2"
    }

    // ---- the list has to survive the next keystroke -------------------------

    @Test fun `typing further narrows the list instead of losing it`() {
        // Reported as: ":ha shows list, :hai the list disappears, even though
        // :haircut and :haircut_man is there." Listing only at the threshold
        // meant the next character wiped it and nothing drew it again.
        drawn(":ha\n") shouldContain "rcut"
        drawn(":hai\n") shouldContain "rcut"
        drawn(":hair\n") shouldContain "rcut"
    }

    @Test fun `the narrowed list is the narrowed set, not the old one`() {
        // Deliberately asked of the candidates rather than of the screen. The
        // capture accumulates every redraw, so the broad `:ha` list -- which
        // legitimately included `:hamburger:` a keystroke earlier -- is still in
        // the bytes, and searching them for its absence asserts nothing about
        // what is on screen now.
        val atHa = Emoji.matching("ha").map { it.first }
        val atHai = Emoji.matching("hai").map { it.first }
        atHa shouldContain "hamburger"
        atHai shouldContain "haircut"
        atHai shouldContain "haircut_man"
        atHai shouldNotContain "hamburger"
    }

    // ---- the command list behaves the same way ------------------------------

    @Test fun `a command prefix narrows as it is typed`() {
        // Reported as: "/a -> does not narrow to /agent". Only the shortcode
        // case was ever re-listed, so the command list sat there stale.
        drawn("/a\n") shouldContain "gent"
    }

    @Test fun `Down steps into the command list rather than clearing the line`() {
        // Reported as: "pressing up/down clears it" -- Down fell through to
        // history, and history navigation replaces the line.
        val (bare, _) = session("/" + Down + "\n\n", history = listOf("earlier"))
        bare.trim() shouldStartWith "/"
        (bare.trim() == "earlier") shouldBe false

        val (typed, _) = session("/a" + Down + "\n\n", history = listOf("earlier"))
        typed.trim() shouldStartWith "/agent"
    }

    /** The list a user actually sees for `/prompt`, drawn by a real reader.
     *
     * `CompleterTest` proves the candidates exist; this proves Tab puts them
     * on screen. The two are not the same claim -- the candidates were
     * reachable through `ReplCompleter` for a whole release while the key that
     * had to ask for them was bound elsewhere.
     */
    @Test fun `Tab after prompt draws both views`() {
        val screen = drawn("/prompt " + Tab + Enter)
        screen shouldContain "full"
        screen shouldContain "default"
    }

    /** Typing a command name leaves exactly that command name in the buffer.
     *
     * The list-as-you-type path used `expand-or-complete`, which *completes*:
     * the keystroke that narrowed the prefix to one candidate inserted the
     * whole command, and the characters still being typed landed after it.
     * `/prompt` became `/prompt rompt `, `/rag` became `/rag g`, `/think`
     * became `/think ink`.
     *
     * Reported as three separate faults, which is worth recording because it
     * is why this test asserts the buffer rather than the screen. `/prompt`
     * offering no completions and `/prompt full` being stored as a system
     * prompt were both downstream of a first argument that read `rompt`.
     */
    @Test fun `typing a command name does not complete it into the line`() {
        listOf("/prompt", "/rag", "/set", "/think", "/model", "/memory", "/compact")
            .forEach { cmd ->
                val (line, _) = session("$cmd\n")
                withClue("typing $cmd: ") { line shouldBe cmd }
            }
    }

    @Test fun `a partially typed command is left partial, not silently finished`() {
        // The same defect from the other side: `/promp` is not `/prompt` until
        // the user asks for it with Tab.
        session("/promp\n").first shouldBe "/promp"
        session("/ra\n").first shouldBe "/ra"
    }

    @Test fun `Tab still completes a unique argument, which is what Tab is for`() {
        // The fix must not turn Tab into a no-op: the distinction is that Tab was
        // asked for and a keystroke was not.
        session("/model " + Tab + "\n").first shouldBe "/model m1 "
    }
}
