package kotllm.render

import kotlin.test.Test
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import kotllm.util.Glyphs

/** LaTeX rendered as far as a terminal reasonably can.
 *
 * Expectations are derived from `Glyphs.unicode` rather than written as
 * literals, for the reason the glyph rules already give: a test asserting
 * `!=` (Unicode not-equal) fails on precisely the console the ASCII
 * fallback exists for.
 */
class LatexTest {

    private fun g(uni: String, ascii: String) = if (Glyphs.unicode) uni else ascii

    private fun render(s: String) = Markdown.render(s, highlight = false)

    // ---- the reported case -------------------------------------------------

    @Test fun `the fraction equation from the report reads as maths`() {
        val out = render(
            """\[
              |\frac{4x}{x+2}=8
              |\]
              |""".trimMargin())

        out shouldContain "(4x)/(x+2)=8"
        // The delimiters are gone rather than shown as literal backslashes.
        out shouldNotContain "\\["
        out shouldNotContain "\\frac"
    }

    @Test fun `inline maths inside prose loses its delimiters`() {
        // `\(` and `\)` are maths delimiters, not parentheses -- so they come off
        // and nothing replaces them. This test first expected `(x+2)`, which was
        // reading the delimiter as part of the expression.
        val out = render("""Multiply both sides by \(x+2\):""")
        out shouldBe "Multiply both sides by x+2:"
    }

    @Test fun `a boxed answer stays marked, since it is the point of the formula`() {
        render("""\[ \boxed{x=-4} \]""").trim() shouldBe "[ x=-4 ]"
    }

    @Test fun `neq renders, and falls back rather than becoming a question mark`() {
        val out = render("""Since \(x\neq -2\), this solution is valid.""")
        out shouldContain g("\u2260", "!=")
        out shouldContain "this solution is valid"
    }

    @Test fun `the whole reported reply renders without leaving LaTeX behind`() {
        val reply =
            """\[
              |\frac{4x}{x+2}=8
              |\]
              |
              |Multiply both sides by \(x+2\):
              |
              |\[
              |4x=8(x+2)
              |\]
              |
              |\[
              |\boxed{x=-4}
              |\]
              |
              |Since \(x\neq -2\), this solution is valid.""".trimMargin()

        val out = render(reply)
        out shouldNotContain "\\frac"
        out shouldNotContain "\\boxed"
        out shouldNotContain "\\neq"
        out shouldNotContain "\\("
        out shouldNotContain "\\["
        out shouldContain "(4x)/(x+2)=8"
        out shouldContain "[ x=-4 ]"
    }

    // ---- fractions ---------------------------------------------------------

    @Test fun `a compound numerator or denominator is parenthesised`() {
        // `4x/x+2` would mean something else entirely, which is the whole reason
        // this is not a plain textual swap.
        Latex.convert("""\frac{4x}{x+2}""") shouldBe "(4x)/(x+2)"
        Latex.convert("""\frac{1}{2}""") shouldBe "1/2"
        Latex.convert("""\frac{a}{b}""") shouldBe "a/b"
    }

    @Test fun `nested fractions do not end at the first closing brace`() {
        Latex.convert("""\frac{\frac{1}{2}}{3}""") shouldBe "(1/2)/3"
    }

    @Test fun `dfrac and tfrac are the same thing`() {
        Latex.convert("""\dfrac{1}{2}""") shouldBe "1/2"
        Latex.convert("""\tfrac{x+1}{y}""") shouldBe "(x+1)/y"
    }

    // ---- scripts -----------------------------------------------------------

    @Test fun `superscripts and subscripts use real characters when they exist`() {
        if (Glyphs.unicode) {
            Latex.convert("x^2") shouldBe "x\u00b2"
            Latex.convert("a_1") shouldBe "a\u2081"
            Latex.convert("x^{10}") shouldBe "x\u00b9\u2070"
        } else {
            Latex.convert("x^2") shouldBe "x^2"
            Latex.convert("x^{10}") shouldBe "x^(10)"
        }
    }

    @Test fun `a script with no character form is left readable rather than mangled`() {
        // `x^(a+b)` is clearer than half a conversion, so a run converts only when
        // *every* character has a form.
        Latex.convert("x^{a+b}") shouldBe "x^(a+b)"

        // Whereas n, + and 1 all have one, so this does convert -- which the
        // first version of this test got wrong by assuming n did not.
        if (Glyphs.unicode) Latex.convert("x^{n+1}") shouldBe "x\u207f\u207a\u00b9"
        else Latex.convert("x^{n+1}") shouldBe "x^(n+1)"
    }

    // ---- the dollar problem ------------------------------------------------

    @Test fun `prices are not formulas`() {
        // The failure worth avoiding: "it costs dollar5 and dollar10" becoming maths.
        val out = render("It costs \$5 and \$10 per seat.")
        out shouldBe "It costs \$5 and \$10 per seat."
    }

    @Test fun `a dollar span that is clearly maths is converted`() {
        render("The value \$x^2\$ is positive.") shouldContain
            (if (Glyphs.unicode) "x\u00b2" else "x^2")
    }

    @Test fun `double dollars are display maths wherever they appear`() {
        render("\$\$\\frac{1}{2}\$\$").trim() shouldBe "1/2"
    }

    // ---- what must not be touched ------------------------------------------

    @Test fun `LaTeX inside a code fence is left exactly alone`() {
        // It is source the user is reading, not maths to render.
        val out = render(
            """```latex
              |\frac{1}{2}
              |```
              |""".trimMargin())
        out shouldContain "\\frac{1}{2}"
    }

    @Test fun `ordinary prose with a stray backslash is not damaged`() {
        render("""Use C:\Users\me and the \n escape.""") shouldBe
            """Use C:\Users\me and the \n escape."""
    }

    @Test fun `latex = false leaves everything untouched`() {
        val src = """\[ \frac{1}{2} \]"""
        Markdown.render(src, highlight = false, latex = false) shouldBe src
    }

    // ---- symbols and fallbacks ---------------------------------------------

    @Test fun `a command with no plain-text form keeps its name rather than vanishing`() {
        // A silently dropped operator changes what the answer says.
        Latex.convert("""\wibble x""") shouldContain "wibble"
    }

    @Test fun `longer command names win over their prefixes`() {
        // `\leq` must not be eaten by `\le`, nor `\subseteq` by `\subset`.
        Latex.convert("""a \leq b""") shouldContain g("\u2264", "<=")
        Latex.convert("""a \subseteq b""") shouldContain g("\u2286", " subseteq ")
    }

    @Test fun `every ASCII fallback is encodable, which is the point of having one`() {
        val ascii = Latex.convert("""\neq \leq \geq \times \pm \to \infty \alpha""")
        if (!Glyphs.unicode) ascii.all { it.code < 128 } shouldBe true
    }

    @Test fun `spacing and sizing commands disappear without eating their operands`() {
        Latex.convert("""\left( x \right)""") shouldBe "( x )"
        Latex.convert("""a \, b \quad c""") shouldContain "a b"
    }

    @Test fun `text inside a formula survives`() {
        Latex.convert("""\text{for all } x""") shouldContain "for all"
    }

    // ---- streaming ---------------------------------------------------------

    @Test fun `display maths spans lines, which is how it arrives when streaming`() {
        // The renderer sees one line at a time, so the open, body and close each
        // arrive separately.
        val r = MarkdownLineRenderer(highlight = false)
        r.line("\\[") shouldBe ""
        r.line("4x=8x+16") shouldBe "  4x=8x+16"
        r.line("\\]") shouldBe ""
        // And prose after it is prose again.
        r.line("So x = -4.") shouldBe "So x = -4."
    }
}
