package kotllm.render

/** Terminal styling, implemented here rather than taken from a library.
 *
 * The Scala original used fansi, which meant keeping `scala-library` on the
 * classpath for one small rendering type. Once the interpreter moved to
 * Kotlin that was the last Scala artifact in the jar, and what it was being
 * paid for is small: a string that carries a style *per character*, so that
 * `overlay` can style a range and compose with whatever was already there.
 * JLine's `AttributedString` can do that, but its renderer wants a `Terminal`
 * to decide capabilities and rewrites output accordingly -- the same
 * behaviour `JLineTuning` already exists to suppress. Emitting the escapes
 * directly is both smaller and easier to predict.
 *
 * The three properties the renderer actually depends on, pinned by `AnsiTest`:
 *
 *  1. `overlay` composes rather than replaces, which is what makes a bold
 *     span inside a coloured line come out bold *and* coloured.
 *  2. Text entering here never throws, whatever escapes the model emitted.
 *  3. Sanitising preserves length. `Markdown` finds spans with a regex over
 *     the raw line and then overlays by those indices, so a sanitiser that
 *     consumed characters would silently style the wrong range.
 *
 * Point 3 is why escapes are replaced one-for-one with U+241B (the printable
 * symbol for escape) rather than parsed or dropped. It also means a model
 * that emits a colour code cannot bleed that colour into everything printed
 * afterwards, which obeying it would allow.
 */

/** The SGR state one character is rendered under.
 *
 * Colours are held as their parameter text rather than as an enum so that the
 * 256-colour and true-colour spellings ("38;5;12") need no special case. */
data class Style(
    val fg: String? = null,
    val bg: String? = null,
    val bold: Boolean = false,
    val underline: Boolean = false,
    val reverse: Boolean = false
) {
    val isDefault: Boolean
        get() = fg == null && bg == null && !bold && !underline && !reverse

    internal fun codes(): List<String> = buildList {
        if (bold) add("1")
        if (underline) add("4")
        if (reverse) add("7")
        fg?.let { add(it) }
        bg?.let { add(it) }
    }

    companion object {
        val Default = Style()
    }
}

/** A style transform. Composable, and applicable to a whole string or a range. */
class Attrs internal constructor(internal val over: (Style) -> Style)

/** Text plus one style per character. */
class Str internal constructor(private val text: String, private val styles: Array<Style>) {

    val length: Int get() = text.length

    /** The content with no styling, which is how a caller asserts on text. */
    fun plainText(): String = text

    /** Apply `attrs` over `[from, to)`, composing with what is already there.
      * Out-of-range bounds are clamped rather than refused: the indices come
      * from regex matches over a line, and a renderer should not throw. */
    fun overlay(attrs: Attrs, from: Int, to: Int): Str {
        val lo = from.coerceIn(0, text.length)
        val hi = to.coerceIn(lo, text.length)
        if (lo == hi) return this
        val next = styles.copyOf()
        for (i in lo until hi) next[i] = attrs.over(next[i])
        return Str(text, next)
    }

    /** The escape sequences a terminal needs, emitted once per style change. */
    fun render(): String {
        if (text.isEmpty()) return ""
        val sb = StringBuilder(text.length + 16)
        var cur = Style.Default
        for (i in text.indices) {
            val want = styles[i]
            if (want != cur) {
                sb.append(transition(cur, want))
                cur = want
            }
            sb.append(text[i])
        }
        // An unterminated span bleeds into everything printed after it.
        if (!cur.isDefault) sb.append(Ansi.Reset)
        return sb.toString()
    }

    /** Leaving a style needs a reset first, since SGR codes only ever add. */
    private fun transition(from: Style, to: Style): String {
        if (to.isDefault) return Ansi.Reset
        val codes = to.codes()
        val params = if (from.isDefault) codes else listOf("0") + codes
        return "\u001b[" + params.joinToString(";") + "m"
    }
}

object Ansi {

    internal const val Reset = "\u001b[0m"

    /** U+241B SYMBOL FOR ESCAPE: one character in, one character out. */
    private const val EscapeSymbol = '\u241b'

    /** Every escape sequence a terminal might act on, for stripping.
      *
      * CSI (`ESC [ ... final`) covers colour and cursor movement; OSC
      * (`ESC ] ... BEL`, or terminated by `ESC \`) covers title setting, which
      * models do emit; the last alternative catches the two-character escapes. */
    private val Escapes = Regex(
        "\u001b\\[[0-?]*[ -/]*[@-~]" +
            "|\u001b][^\u0007\u001b]*(?:\u0007|\u001b\\\\)" +
            "|\u001b[@-Z\\\\-_]"
    )

    object Color {
        val Red: Attrs get() = fg("31")
        val Green: Attrs get() = fg("32")
        val Yellow: Attrs get() = fg("33")
        val Blue: Attrs get() = fg("34")
        val Cyan: Attrs get() = fg("36")
        val White: Attrs get() = fg("37")
        val DarkGray: Attrs get() = fg("90")

        private fun fg(code: String) = Attrs { it.copy(fg = code) }
    }

    object Bold {
        val On: Attrs get() = Attrs { it.copy(bold = true) }
    }

    val Empty: Attrs get() = Attrs { it }

    /** Text on its way to the terminal.
      *
      * Model output can contain raw escapes. They are made visible rather than
      * obeyed or removed -- obeyed means a reply can recolour the rest of the
      * session, removed means the overlay indices `Markdown` computed over the
      * raw line no longer point at the characters it measured. */
    fun str(s: CharSequence): Str {
        val text = buildString(s.length) {
            for (c in s) append(if (c == '\u001b') EscapeSymbol else c)
        }
        return Str(text, Array(text.length) { Style.Default })
    }

    /** The text with every escape removed, which is how a test asserts on
      * content without asserting on colour. Removed rather than replaced:
      * this one is for comparing against a plain string. */
    fun plain(s: CharSequence): String = Escapes.replace(s.toString(), "")
}

/** Style and render in one step, which is what nearly every call site wants. */
fun Attrs.on(s: CharSequence): String = this(Ansi.str(s)).render()

/** Style every character, keeping the styles already present. For the code
  * that holds on to a `Str` in order to overlay it further. */
operator fun Attrs.invoke(s: Str): Str = s.overlay(this, 0, s.length)

/** `Bold.On + Color.Green`: both, with the right-hand side applied last. */
operator fun Attrs.plus(other: Attrs): Attrs = Attrs { s -> other.over(this.over(s)) }
