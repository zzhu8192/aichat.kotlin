package kotllm.util

import java.nio.charset.Charset
import java.nio.charset.StandardCharsets

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

    internal val isWindows: Boolean =
        System.getProperty("os.name")?.lowercase()?.contains("win") == true

    /**
     * The raw charset reported by the JVM for stdout before POSIX C-locale recovery.
     */
    internal fun rawOutputCharset(): Charset =
        try {
            System.console()?.charset()
                ?: System.getProperty("stdout.encoding")?.let { byName(it) }
                ?: System.getProperty("sun.stdout.encoding")?.let { byName(it) }
                ?: Charset.defaultCharset()
        } catch (e: Exception) {
            Charset.defaultCharset()
        }

    /**
     * Effective output charset after upgrading glibc's `ANSI_X3.4-1968` (`US-ASCII`)
     * fallback on POSIX to `UTF-8`.
     *
     * On Ubuntu Server / ARM over SSH, `sshd` accepts `LANG` / `LC_*` from the
     * client (`AcceptEnv LANG LC_*`), while minimal/ARM images only ship `C.utf8`
     * in `/usr/lib/locale`. When the forwarded locale (e.g. `en_US.UTF-8` or
     * macOS `LC_CTYPE=UTF-8`) is not installed -- or `LANG` is unset / `C` --
     * `setlocale(LC_ALL, "")` falls back to `"C"` and `nl_langinfo(CODESET)`
     * reports `ANSI_X3.4-1968` (`US-ASCII`). Unlike Windows `cp1252`/`cp437` or
     * an explicit 8-bit locale (`ISO-8859-1`), `US-ASCII` on POSIX is a locale
     * fallback rather than a 7-bit hardware terminal.
     */
    internal fun resolveCharset(raw: Charset, windows: Boolean = isWindows): Charset =
        if (!windows && raw == StandardCharsets.US_ASCII) StandardCharsets.UTF_8
        else raw

    /**
     * The charset stdout will actually be encoded with.
     */
    fun outputCharset(): Charset = resolveCharset(rawOutputCharset())

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
            outputCharset().newEncoder().canEncode("█▁⠋→⋯─·↓")
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
     * **Must be `U+2500` (`─`, LIGHT HORIZONTAL), not `U+2501` (`━`, HEAVY).**
     * Classic Windows SSH fonts (`Courier New` — PuTTY's default — and `Lucida
     * Console`) include `U+2500` (`─`) and PuTTY draws it procedurally as a
     * continuous solid line, whereas `U+2501` (`━`) is absent from those fonts
     * and hits PuTTY's `ucs_fallback` table, which substitutes ASCII `'-'` and
     * renders dashed rules (`-------------`) over SSH. JLine 3's `smacs`
     * rewrite of `U+2500` into `q` is switched off by [JLineTuning].
     */
    val dividerChar: String = if (unicode) "─" else "-"
    val promptCaret: String = ">"
    val bullet: String = if (unicode) "·" else "|"
    val tokensIn: String = if (unicode) "↑" else "^"
    val tokensOut: String = if (unicode) "↓" else "v"

    val spinnerFrames: List<String> =
        if (unicode) listOf("⠋", "⠙", "⠹", "⠸", "⠼", "⠴", "⠦", "⠧", "⠇", "⠏")
        else listOf("|", "/", "-", "\\")
}
