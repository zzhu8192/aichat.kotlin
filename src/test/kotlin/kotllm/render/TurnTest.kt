package kotllm.render

import java.io.ByteArrayOutputStream
import java.io.PrintStream
import java.nio.charset.StandardCharsets
import java.time.LocalTime
import kotlin.test.Test
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import kotllm.client.StreamEvent
import kotllm.config.ReasoningDisplay
import kotllm.util.Glyphs

/** The transcript layout: a question echoed with the caret, the reply in a
 * bulleted gutter, and the cost line under it.
 *
 * Expectations are derived from `Glyphs` rather than written as literals,
 * because the glyphs fall back to ASCII on a console that cannot encode them
 * -- which is exactly the terminal the fallback exists for.
 */
class TurnTest {

    // Strips escapes so the assertions can compare plain text; production
    // rendering keeps them.
    private fun plain(s: String): String = Ansi.plain(s)

    @Test fun `a question is echoed with the prompt caret`() {
        plain(Turn.question("which model are you")) shouldBe
            "${Glyphs.promptCaret} which model are you"
    }

    @Test fun `multi-line input keeps the caret on the first line and indents the rest`() {
        val lines = plain(Turn.question("first\nsecond\nthird")).split("\n")

        lines[0] shouldBe "${Glyphs.promptCaret} first"
        lines[1] shouldBe "${Turn.Indent}second"
        lines[2] shouldBe "${Turn.Indent}third"
    }

    @Test fun `input carrying ANSI escapes is sanitized rather than throwing`() {
        Turn.question("colour \u001B[31mme\u001B[0m")
    }

    @Test fun `the cost line reports elapsed time, the clock and the turn's tokens`() {
        val line = plain(Turn.processed(13_400L, 412, LocalTime.of(18, 4)))

        line shouldBe
            "${Turn.Indent}${Turn.Marker} Processed for 13s ${Glyphs.bullet} done 6:04 PM " +
                "${Glyphs.bullet} ${Glyphs.tokensOut} 412 tokens"
    }

    @Test fun `a sub-ten-second turn keeps one decimal, where a whole second hides it`() {
        plain(Turn.processed(400L, 0, LocalTime.NOON)) shouldContain "Processed for 0.4s"
        plain(Turn.processed(9_700L, 0, LocalTime.NOON)) shouldContain "Processed for 9.7s"
        plain(Turn.processed(95_000L, 0, LocalTime.NOON)) shouldContain "Processed for 1m 35s"
    }

    @Test fun `tokens are omitted when the turn generated none, rather than saying zero`() {
        plain(Turn.processed(1_000L, 0, LocalTime.of(9, 30))) shouldBe
            "${Turn.Indent}${Turn.Marker} Processed for 1.0s ${Glyphs.bullet} done 9:30 AM"
    }

    @Test fun `a gutter marks the first line with text and indents the rest`() {
        Turn.gutter.render("one\ntwo\nthree") shouldBe "${Turn.Marker} one\n  two\n  three"
    }

    @Test fun `blank lines stay bare, so nothing carries trailing whitespace`() {
        Turn.gutter.render("one\n\ntwo") shouldBe "${Turn.Marker} one\n\n  two"
    }

    @Test fun `the marker waits for the first line that actually has text`() {
        Turn.gutter.render("\nfirst real line") shouldBe "\n${Turn.Marker} first real line"
    }

    @Test fun `an empty gutter leaves text exactly as it was, for piped output`() {
        Gutter.none.render("one\ntwo") shouldBe "one\ntwo"
    }

    // ---- streaming through the gutter ---------------------------------------

    private fun streamed(
        deltas: List<String>,
        highlight: Boolean = false,
        gutter: Gutter = Turn.gutter
    ): String {
        val captured = ByteArrayOutputStream()
        val out = PrintStream(captured, true, StandardCharsets.UTF_8.name())
        val render = StreamRender(ReasoningDisplay.Hide, highlight, gutter, out, out)
        deltas.forEach { d -> render.onEvent(StreamEvent.TextDelta(d)) }
        render.finish()
        return plain(String(captured.toByteArray(), StandardCharsets.UTF_8))
    }

    @Test fun `a streamed reply lands in the gutter even though deltas split lines`() {
        // The endpoint sends fragments, not lines: the marker must attach to the
        // first fragment and the indent to whatever follows a newline.
        streamed(listOf("I'm a lang", "uage model.\nWhat ", "can I help with?\n")) shouldBe
            "${Turn.Marker} I'm a language model.\n  What can I help with?\n"
    }

    @Test fun `a trailing partial line is still closed off in the gutter`() {
        streamed(listOf("no trailing newline")) shouldBe "${Turn.Marker} no trailing newline\n"
    }

    @Test fun `a blank line inside a streamed reply is not padded`() {
        streamed(listOf("para one\n\npara two\n")) shouldBe
            "${Turn.Marker} para one\n\n  para two\n"
    }

    @Test fun `highlighting on takes the same gutter, buffered a line at a time`() {
        streamed(listOf("# Heading\n", "body\n"), highlight = true) shouldBe
            "${Turn.Marker} # Heading\n  body\n"
    }

    @Test fun `without a gutter the stream is byte-for-byte what the model sent`() {
        streamed(listOf("one\ntwo\n"), gutter = Gutter.none) shouldBe "one\ntwo\n"
    }
}
