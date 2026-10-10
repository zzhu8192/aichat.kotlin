package kotllm.util

/**
 * JLine behaviour that has to be switched off before JLine loads.
 *
 * **The alternate character set.** `AttributedCharSequence.toAnsi(Terminal)`
 * rewrites eleven Unicode box-drawing characters -- `─ │ ┌ ┐ └ ┘ ├ ┤ ┬ ┴ ┼`,
 * and `─` is our rule -- into the terminal's terminfo alternate charset. On
 * `TERM=xterm` that is `ESC(0`, then `q` per column, then `ESC(B`.
 *
 * The substitution is unconditional: it never asks whether the output encoding
 * can represent `─`, only whether the terminal declares `smacs`, which every
 * xterm-family terminfo does. And **PuTTY in UTF-8 mode ignores the DEC
 * line-drawing charset** unless a non-default option is ticked, so both rules
 * rendered as rows of `q` over ssh while the footer's `·` and `↓` -- not in
 * JLine's eleven-character table -- came through fine in the same capture.
 *
 * We already decide for ourselves whether the terminal can render these glyphs
 * (`Glyphs.unicode`, with `dividerChar` falling back to ASCII `-`), so a
 * second coarser downgrade underneath ours can only disagree with it.
 *
 * **Must run before `AttributedCharSequence` is initialised**, because JLine
 * reads the flag once with `Boolean.getBoolean` in a static initialiser --
 * hence `init()` at the top of `Main.main` *and* from the `Chrome`
 * constructor. Idempotent. See docs/history.md.
 */
object JLineTuning {

    /**
     * The property JLine reads. Named here once; a typo would be silent, since
     * an unset boolean property is simply false.
     */
    const val DisableAlternateCharset = "org.jline.utils.disableAlternateCharset"

    fun init() {
        if (System.getProperty(DisableAlternateCharset) == null) {
            System.setProperty(DisableAlternateCharset, "true")
        }
        // Silence JLine's JUL logger so internal diagnostics (like history expansion
        // or parser warnings) never leak onto the terminal stderr while typing or redrawing.
        try {
            java.util.logging.Logger.getLogger("org.jline").level = java.util.logging.Level.OFF
        } catch (_: Throwable) {
        }
    }

    /**
     * For the test that asserts we actually did it.
     */
    val alternateCharsetDisabled: Boolean
        get() = System.getProperty(DisableAlternateCharset) == "true"

    val jlineLoggingSilenced: Boolean
        get() = java.util.logging.Logger.getLogger("org.jline").level == java.util.logging.Level.OFF
}
