package kotllm.repl

import org.jline.utils.AttributedCharSequence
import org.jline.utils.AttributedString
import org.jline.utils.ColorPalette

import kotlin.test.Test
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain

import kotllm.util.Glyphs
import kotllm.util.JLineTuning

/** The rules rendered as rows of `q` over ssh.
 *
 * Cause, in one sentence: JLine rewrites eleven Unicode box-drawing
 * characters into the terminal's terminfo *alternate charset*, and PuTTY in
 * UTF-8 mode ignores the DEC line-drawing charset unless a non-default box is
 * ticked -- so `ESC(0 qqqq ESC(B` arrived and `qqqq` was drawn.
 *
 * The substitution is real and worth pinning down here rather than describing,
 * because it is the thing we are switching off. `ChromeSpec` had two
 * assertions reading `should (include(dividerChar) or include("qqq"))`, which
 * tolerated the bug instead of catching it; they are exact now.
 */
class AltCharsetTest {

    private val Smacs = "\u001b(0"
    private val Rmacs = "\u001b(B"

    /** Renders through the same call JLine makes internally, with the alternate
     * charset handed in explicitly. That keeps the test independent of a static
     * initialiser another spec may already have triggered, while still
     * exercising the exact code path. */
    private fun render(text: String, altIn: String?, altOut: String?): String =
        AttributedString
            .fromAnsi(text)
            .toAnsi(256, AttributedCharSequence.ForceMode.None, ColorPalette.DEFAULT, altIn, altOut)

    @Test fun `given an alternate charset, JLine rewrites the rule into ESC(0 and q`() {
        // This is the bug, reproduced. `q` is the acsc byte for U+2500 on every
        // xterm-family terminfo, which is why the screen filled with them.
        val out = render("───", Smacs, Rmacs)

        out shouldContain Smacs
        out shouldContain "qqq"
        (out.contains("─")) shouldBe false
    }

    @Test fun `with no alternate charset, the rule goes out as the character we wrote`() {
        val out = render(Glyphs.dividerChar.repeat(3), null, null)

        out shouldContain Glyphs.dividerChar.repeat(3)
        (out.contains(Smacs)) shouldBe false
        (out.contains("qqq")) shouldBe false
    }

    /** The divider is immune to the rewrite, which is the actual defence.
      *
      * `JLineTuning` only switches off the alternate charset. JLine 4 answers
      * that by substituting ASCII instead, so the property alone would still
      * lose the rule. Choosing a glyph outside JLine's eleven-character table
      * is what survives both, and this fails if anyone moves the divider back
      * to a light box-drawing character.
      */
    @Test fun `the divider glyph is not one JLine rewrites`() {
        val rewritten = "\u2500\u2502\u250c\u2510\u2514\u2518\u251c\u2524\u252c\u2534\u253c"

        (Glyphs.dividerChar.length == 1 && rewritten.contains(Glyphs.dividerChar)) shouldBe false
        render(Glyphs.dividerChar.repeat(3), Smacs, Rmacs) shouldContain Glyphs.dividerChar.repeat(3)
    }

    @Test fun `kotllm disables the substitution, and the property name is exact`() {
        // `Boolean.getBoolean` in a static initialiser: a typo here would be
        // completely silent, because an unset boolean property is just false.
        JLineTuning.init()
        JLineTuning.DisableAlternateCharset shouldBe "org.jline.utils.disableAlternateCharset"
        JLineTuning.alternateCharsetDisabled shouldBe true
        JLineTuning.jlineLoggingSilenced shouldBe true
    }

    @Test fun `init is idempotent and does not stamp on an explicit setting`() {
        val previous = System.getProperty(JLineTuning.DisableAlternateCharset)
        try {
            System.setProperty(JLineTuning.DisableAlternateCharset, "false")
            JLineTuning.init()
            // Someone who set it by hand wanted it, even set to false.
            System.getProperty(JLineTuning.DisableAlternateCharset) shouldBe "false"
        } finally {
            if (previous != null) System.setProperty(JLineTuning.DisableAlternateCharset, previous)
            else System.clearProperty(JLineTuning.DisableAlternateCharset)
        }
    }

    @Test fun `only the rule was ever at risk, which is why the footer looked fine`() {
        // The reported capture had correct separators and arrows beside broken
        // rules. JLine's table is exactly eleven box-drawing characters, so the
        // footer glyphs were never candidates -- the detail that confirmed the
        // diagnosis rather than an encoding problem.
        val footerGlyphs = Glyphs.bullet + Glyphs.tokensOut + Glyphs.arrow + Glyphs.ellipsis
        (render(footerGlyphs, Smacs, Rmacs).contains(Smacs)) shouldBe false
    }

    @Test fun `the divider glyph agrees with what the output charset can encode`() {
        // Derived from Glyphs rather than hard-coded, or this fails on exactly the
        // terminals the fallback exists for. The heavy rule, not the light one:
        // U+2500 is in JLine's substitution table and the test above forbids it.
        if (Glyphs.unicode) Glyphs.dividerChar shouldBe "━"
        else Glyphs.dividerChar shouldBe "-"
    }

    @Test fun `a rule is still one character wide per column in either mode`() {
        // The row and column arithmetic in Chrome counts printed columns, so an
        // ASCII fallback wider than one column would desync the erase.
        Glyphs.dividerChar.length shouldBe 1
        Prompt.divider(20) shouldContain Glyphs.dividerChar.repeat(19)
    }

    @Test fun `isFramed recognises the framed prompt and not the others`() {
        // It used to be `contains(dividerChar)`, which is a single character, and
        // the ASCII fallback is a hyphen -- so every hyphenated model name in the
        // plain prompt would have matched.
        Prompt.isFramed(Prompt.input(40)) shouldBe true
        Prompt.isFramed(Prompt.continuation) shouldBe false
        Prompt.isFramed("databricks-qwen35-122b-a10b> ") shouldBe false
        Prompt.isFramed("") shouldBe false
    }

    @Test fun `POSIX US-ASCII locale fallback upgrades to UTF-8 while Windows and explicit 8-bit charsets stay as-is`() {
        // SSH into Ubuntu Server/ARM with an ungenerated client LANG (or unset/C)
        // makes glibc fall back to ANSI_X3.4-1968 (US-ASCII). On POSIX that is a
        // locale fallback rather than a 7-bit terminal; on Windows or ISO-8859-1
        // the declared single-byte charset is respected.
        Glyphs.resolveCharset(java.nio.charset.StandardCharsets.US_ASCII, windows = false) shouldBe
            java.nio.charset.StandardCharsets.UTF_8
        Glyphs.resolveCharset(java.nio.charset.StandardCharsets.US_ASCII, windows = true) shouldBe
            java.nio.charset.StandardCharsets.US_ASCII
        Glyphs.resolveCharset(java.nio.charset.StandardCharsets.ISO_8859_1, windows = false) shouldBe
            java.nio.charset.StandardCharsets.ISO_8859_1
        Glyphs.resolveCharset(java.nio.charset.StandardCharsets.UTF_8, windows = false) shouldBe
            java.nio.charset.StandardCharsets.UTF_8
    }

    @Test fun `JLineTuning pins JLine 4 stream encodings to UTF-8 when unicode is active`() {
        JLineTuning.init()
        if (Glyphs.unicode && Glyphs.outputCharset() == java.nio.charset.StandardCharsets.UTF_8) {
            for (prop in JLineTuning.TerminalEncodingProps) {
                System.getProperty(prop) shouldBe "UTF-8"
            }
        }
    }
}
