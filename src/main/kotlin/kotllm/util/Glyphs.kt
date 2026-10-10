package kotllm.util

import java.nio.charset.Charset

/**
 * Box-drawing and spinner glyphs, with ASCII fallbacks.
 *
 * Java 17 does not default stdout to UTF-8 the way 18+ does: on Windows the
 * console encoding is whatever the active codepage says, commonly cp1252 or
 * cp437. Printing block-drawing characters there yields a row of `?`, which is
 * exactly what `/status` would have rendered. Rather than forcing UTF-8 on the
 * stream -- which turns the wrong glyphs into mojibake instead of question
 * marks -- ask the charset what it can represent and degrade gracefully.
 */
object Glyphs {

    /**
     * The charset stdout will actually be encoded with.
     */
    private fun outputCharset(): Charset =
        try {
            System.console()?.charset()
                ?: System.getProperty("stdout.encoding")?.let { byName(it) }
                ?: System.getProperty("sun.stdout.encoding")?.let { byName(it) }
                ?: Charset.defaultCharset()
        } catch (e: Exception) {
            Charset.defaultCharset()
        }

    private fun byName(name: String): Charset? =
        try {
            Charset.forName(name)
        } catch (e: Exception) {
            null
        }

    /**
     * Explicit override, for a terminal whose declared charset understates what
     * it can actually display.
     *
     * Needed because the detection below is necessarily conservative: a Windows
     * console reports Cp1252 unless its codepage is switched (`chcp 65001`),
     * even when the font and terminal would render box-drawing characters
     * perfectly well.
     */
    private fun forced(): Boolean? =
        (System.getenv("KOTLLM_UNICODE")
            ?: System.getenv("SCALLM_UNICODE")
            ?: System.getProperty("kotllm.unicode"))
            ?.trim()
            ?.lowercase()
            ?.let {
                when (it) {
                    "1", "true", "yes", "on" -> true
                    "0", "false", "no", "off" -> false
                    else -> null
                }
            }

    /**
     * True when the terminal can render the block and braille glyphs.
     */
    val unicode: Boolean = forced() ?: run {
        try {
            outputCharset().newEncoder().canEncode("█▁⠋→⋯━·↓")
        } catch (e: Exception) {
            false
        }
    }

    /**
     * Sparkline ramp, ascending density. Eight levels either way so callers do
     * not care which set is in use.
     */
    val ramp: List<Char> =
        if (unicode) listOf('▁', '▂', '▃', '▄', '▅', '▆', '▇', '█')
        else listOf('.', ':', '-', '=', '+', '*', '#', '@')

    val barFilled: String = if (unicode) "█" else "#"
    val barEmpty: String = if (unicode) "░" else "."

    val arrow: String = if (unicode) "→" else "->"
    val ellipsis: String = if (unicode) "⋯" else "..."

    /**
     * REPL chrome: the divider rules bracketing the input line, the prompt
     * caret, and the separators inside the status footer.
     *
     * Gated like every other glyph here, which it was not: it was an
     * unconditional box-drawing character and therefore the one place this
     * object broke its own rule. The ASCII fallback is `-` rather than `_`,
     * which would read as a blank line, or `=`, which reads as a heading.
     *
     * **U+2501 HEAVY, not U+2500 LIGHT, and that is load-bearing.** JLine
     * rewrites exactly eleven light box-drawing characters and nothing else:
     * into the terminal's alternate charset on 3.x, which rendered both rules
     * as rows of `q` over ssh, and into ASCII `-` unconditionally on 4.x, which
     * overrides the `unicode` decision made right here. The heavy glyph is in
     * neither table, so the rule survives on any JLine, with or without
     * `JLineTuning`, and tiles seamlessly just the same.
     */
    val dividerChar: String = if (unicode) "━" else "-"
    val promptCaret: String = ">"
    val bullet: String = if (unicode) "·" else "|"
    val tokensIn: String = if (unicode) "↑" else "^"
    val tokensOut: String = if (unicode) "↓" else "v"

    val spinnerFrames: List<String> =
        if (unicode) listOf("⠋", "⠙", "⠹", "⠸", "⠼", "⠴", "⠦", "⠧", "⠇", "⠏")
        else listOf("|", "/", "-", "\\")
}
