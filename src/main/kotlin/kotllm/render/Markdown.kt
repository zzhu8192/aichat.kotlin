package kotllm.render

/** Line-at-a-time markdown highlighting for the terminal.
 *
 * Stateful by design: fenced code blocks span lines, and the streaming
 * renderer feeds lines in as they complete rather than handing over a whole
 * document. Scope is deliberately small -- fences, headings, inline code,
 * bold, list bullets, LaTeX, and `:shortcode:` emoji. This is highlighting,
 * not layout: no reflow, no tables, no nested-block tracking.
 *
 * @param latex convert LaTeX to readable text. Separate from `highlight`
 *              because they are different questions: one is about colour,
 *              the other about what the characters say. Someone piping
 *              output may well want the maths readable and the escapes gone.
 */
class MarkdownLineRenderer(val highlight: Boolean, val latex: Boolean = true) {

    private var inCode = false
    private var inDisplay = false

    fun line(raw: String): String {
        val isFence = raw.trim().startsWith("```")

        // A fence wins over everything: a LaTeX command inside a code sample is
        // source the user is reading, not maths to render.
        if (isFence) {
            inCode = !inCode
            return if (highlight) Ansi.Color.Cyan(sanitize(raw)).render() else raw
        }
        if (inCode) return if (highlight) Ansi.Color.Cyan(sanitize(raw)).render() else raw

        // A fence wins for emoji too: `:shrug:` in a code sample is source the
        // user is reading, not shorthand to expand.
        val text = Emoji.expand(raw)

        if (latex) {
            if (inDisplay) {
                val closing = Latex.closesDisplay(text)
                val body = Latex.display(text)
                if (closing) inDisplay = false
                // Display maths is indented rather than coloured. It is usually the
                // answer, and dimming the answer to distinguish it from the prose
                // would be the wrong way round.
                return if (body.isEmpty()) "" else DisplayIndent + styled(body)
            }
            if (Latex.opensDisplay(text)) {
                inDisplay = true
                val body = Latex.display(text)
                return if (body.isEmpty()) "" else DisplayIndent + styled(body)
            }
            // A one-line `\[ ... \]`, and any inline `\( ... \)` or `$...$`.
            val converted = Latex.inlineSpans(text)
            return styled(converted)
        }

        return styled(text)
    }

    private val DisplayIndent = "  "

    fun insideCodeBlock(): Boolean = inCode

    private fun styled(text: String): String =
        if (!highlight) text else inlineSpans(text)

    private fun inlineSpans(raw: String): String {
        val base =
            if (Markdown.Heading.containsMatchIn(raw))
                Ansi.Color.Green(sanitize(raw))
            else if (Markdown.Bullet.containsMatchIn(raw))
                sanitize(raw)
            else sanitize(raw)

        var out = base
        // Overlays are applied over the sanitized copy, which has the same length
        // as the input only because sanitize strips nothing -- it replaces escapes.
        if (out.length == raw.length) {
            Markdown.InlineCode.findAll(raw).forEach { m ->
                out = out.overlay(Ansi.Color.Yellow, m.range.first, m.range.last + 1)
            }
            Markdown.BoldSpan.findAll(raw).forEach { m ->
                out = out.overlay(Ansi.Bold.On, m.range.first, m.range.last + 1)
            }
        }
        return out.render()
    }

    /** Model output can contain raw ANSI escapes. Sanitizing replaces them
     * one character for one, which is what keeps the overlay indices above
     * pointing at the characters the regex matched. */
    private fun sanitize(s: String): Str =
        Ansi.str(s)
}

object Markdown {
    internal val Heading = Regex("""^\s{0,3}#{1,6}\s""")
    internal val Bullet = Regex("""^\s*([-*+]|\d+\.)\s""")
    internal val InlineCode = Regex("""`[^`\n]+`""")
    internal val BoldSpan = Regex("""\*\*[^*\n]+\*\*""")

    fun render(text: String, highlight: Boolean = true, latex: Boolean = true): String {
        val r = MarkdownLineRenderer(highlight, latex)
        return text.split("\n").map { r.line(it) }.joinToString("\n")
    }
}
