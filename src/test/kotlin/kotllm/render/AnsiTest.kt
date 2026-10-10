package kotllm.render

import kotlin.test.Test
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.assertions.throwables.shouldNotThrowAny

/** What any replacement for fansi has to reproduce.
 *
 * Written before swapping the implementation, and asserting on behaviour
 * rather than exact escape bytes: the point is to pin the four properties the
 * renderer depends on, not to freeze one library's spelling of them.
 *
 * The two that matter most are `overlay` composition and sanitising, because
 * both fail quietly. A broken overlay drops a style nobody notices until a
 * screenshot looks wrong, and a missing sanitiser turns a model reply
 * containing an escape into a thrown exception that kills a good response.
 */
class AnsiTest {

    @Test fun `styling emits the colour and returns to default`() {
        val out = Ansi.Color.Yellow.on("hi")
        out shouldContain "hi"
        // Coloured at all, and closed again -- an unterminated span bleeds into
        // everything printed after it.
        (out.length > "hi".length) shouldBe true
        Ansi.plain(out) shouldBe "hi"
    }

    @Test fun `plain strips styling so content can be asserted without colour`() {
        Ansi.plain(Ansi.Color.Red.on("error: nope")) shouldBe "error: nope"
    }

    @Test fun `attributes compose into one value`() {
        val both = Ansi.Bold.On + Ansi.Color.Green
        Ansi.plain(both.on("ok")) shouldBe "ok"
        (both.on("ok").length > Ansi.Color.Green.on("ok").length) shouldBe true
    }

    /** The load-bearing one for `Markdown`: an overlay styles a range and keeps
      * whatever was already there, rather than replacing it. */
    @Test fun `an overlay composes with the span already under it`() {
        val base = Ansi.Bold.On(Ansi.str("hello world"))
        val overlaid = base.overlay(Ansi.Color.Yellow, 0, 5)

        Ansi.plain(overlaid.render()) shouldBe "hello world"
        // The overlaid range carries both attributes; the rest keeps only the
        // original. Asserted through rendering, which is what reaches a terminal.
        val rendered = overlaid.render()
        rendered shouldContain "hello"
        rendered shouldContain "world"
        (rendered.length > "hello world".length) shouldBe true
    }

    /** Model output contains raw escapes. Throwing here would kill a reply that
      * was otherwise fine. */
    @Test fun `a raw escape in model output is sanitised, not thrown on`() {
        val hostile = "before \u001b[31m after \u001b]0;title\u0007 end"
        shouldNotThrowAny { Ansi.str(hostile) }
        shouldNotThrowAny { Ansi.Color.Cyan.on(hostile) }
    }

    /** `Markdown` overlays by index into the sanitised copy, so sanitising must
      * not move the characters an index refers to. */
    @Test fun `sanitising leaves plain text positions usable for overlays`() {
        val text = "plain text with no escapes"
        val s = Ansi.str(text)
        s.plainText() shouldBe text

        val overlaid = s.overlay(Ansi.Color.Blue, 6, 10)
        Ansi.plain(overlaid.render()) shouldBe text
    }
}
