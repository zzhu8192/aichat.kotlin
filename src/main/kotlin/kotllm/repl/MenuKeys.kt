package kotllm.repl

import org.jline.keymap.KeyMap
import org.jline.reader.Binding
import org.jline.reader.LineReader
import org.jline.reader.Reference
import org.jline.reader.Widget

/** Keys and colours for the completion menu.
 *
 * Separate from [Repl] so it can be driven by a real `LineReader` in a
 * test. Binding key sequences and setting display styles are both things
 * that cannot be verified by reading the code.
 */
object MenuKeys {

    private val Esc = Chrome.Esc

    /** Down and right advance, up and left go back.
     *
     * Both encodings of each: a terminal sends `ESC [ A` normally and
     * `ESC O A` once the keypad is in application mode, and JLine puts it
     * there for the duration of a read, so binding one form works half the
     * time. */
    private val Forward = listOf("$Esc[B", "${Esc}OB", "$Esc[C", "${Esc}OC")
    private val Backward = listOf("$Esc[A", "${Esc}OA", "$Esc[D", "${Esc}OD")

    private const val ReverseMenuComplete = "reverse-menu-complete"

    /** Arrow keys inside an open menu.
     *
     * **JLine does not bind them there.** Its `menu` keymap has Tab,
     * Shift-Tab and Enter but leaves the arrows on ordinary cursor movement,
     * so an arrow press was not a navigation that failed -- it left the menu
     * and self-inserted the tail of its own escape sequence. The stray `B`
     * after a selection is the `B` of `ESC [ B`.
     *
     * Bound on the shared `menu` keymap, so the command menu gets this too:
     * the arrows were dead in both. */
    fun bindArrows(reader: LineReader) {
        try {
            val menu = reader.keyMaps[LineReader.MENU]
            if (menu != null) {
                Forward.forEach { k -> menu.bind(Reference(LineReader.MENU_COMPLETE), k) }
                Backward.forEach { k -> menu.bind(Reference(ReverseMenuComplete), k) }
            }
        } catch (e: Exception) {
        }
    }

    /** Makes Up and Down step into the list that is already on screen, instead
     * of requiring a Tab first.
     *
     * The list appears after one completion, but at that point the menu is
     * not open yet -- the keymap is still `main`, where the arrows are
     * history navigation, so the obvious key did the wrong thing and Tab was
     * the only way in. This binds Up and Down in `main` to a widget that
     * opens the menu *when the cursor is on something completable* and
     * otherwise does exactly what it did before.
     *
     * That condition is the whole reason this is safe: history navigation is
     * the common case and keeps working everywhere except inside a
     * half-typed `:shortcode` or a leading `/command`, which are the two
     * places a list is on screen to be stepped into. Once the menu is open
     * the `menu` keymap takes over and [bindArrows] is what runs.
     */
    fun bindOpenOnArrow(reader: LineReader) {
        try {
            val main = reader.keyMaps[LineReader.MAIN]
            if (main != null) {
                bindOne(
                    reader, main, listOf("$Esc[B", "${Esc}OB"),
                    "kotllm-down", LineReader.MENU_COMPLETE, "down-line-or-search"
                )
                bindOne(
                    reader, main, listOf("$Esc[A", "${Esc}OA"),
                    "kotllm-up", ReverseMenuComplete, "up-line-or-history"
                )
            }
        } catch (e: Exception) {
        }
    }

    private fun bindOne(
        reader: LineReader,
        main: KeyMap<Binding>,
        keys: List<String>,
        name: String,
        whenCompleting: String,
        fallbackDefault: String
    ) {
        // Whatever the arrow already did is what it keeps doing outside a
        // completion, rather than a guess at what JLine's default was.
        val fallback = keys.asSequence()
            .mapNotNull { k -> main.boundKeys[k] as? Reference }
            .firstOrNull()
            ?.name()
            ?: fallbackDefault

        reader.widgets[name] = Widget {
            val widget = if (wantsMenu(reader)) whenCompleting else fallback
            try {
                reader.callWidget(widget)
            } catch (e: Exception) {
            }
            true
        }
        keys.forEach { k -> main.bind(Reference(name), k) }
    }

    private fun bindOne(
        reader: LineReader,
        main: KeyMap<Binding>,
        keys: List<String>,
        name: String,
        whenCompleting: String
    ) = bindOne(reader, main, keys, name, whenCompleting, "self-insert")

    /** Lists the candidates for whatever is under the cursor, via JLine.
     *
     * **Not wrapped in `Chrome.whilePrinting`, which is the one surprise in
     * this file.** That rule -- anything writing to stdout suspends the
     * pinned rows first -- is about *our* raw escapes. JLine's candidate list
     * is drawn by JLine's own display, which already knows about the `Status`
     * region it shares the screen with, so suspending underneath makes the
     * restore repaint over the list. Measured both ways: with the suspend,
     * completion runs without error and draws nothing at all. */
    private fun listNow(reader: LineReader) {
        try {
            reader.callWidget(LineReader.EXPAND_OR_COMPLETE)
        } catch (e: Exception) {
        }
    }

    /** Draws the candidate list and touches nothing else.
     *
     * **The distinction from [listNow] is the whole bug.** `expand-or-complete`
     * is a *completion*: when the prefix narrows to exactly one candidate it
     * inserts it. That is correct for Tab, which the user pressed to ask for it,
     * and catastrophic on every keystroke -- typing `/p` made the buffer
     * `/prompt `, and the `r`, `o`, `m`, `p`, `t` still to come landed after it,
     * leaving `/prompt rompt `.
     *
     * Every command was hit: `/rag` became `/rag g`, `/think` became
     * `/think ink`. The damage was not only cosmetic. The parser saw a first
     * argument of `rompt`, so `/prompt` never offered `full` or `default`, and
     * `/prompt full` reached the dispatcher as the text `rompt full` and was
     * stored as the system prompt.
     *
     * `list-choices` is the widget that draws the same list and leaves the
     * buffer alone, which is all this path ever wanted. */
    private fun listOnly(reader: LineReader) {
        try {
            reader.callWidget(LineReader.LIST_CHOICES)
        } catch (e: Exception) {
        }
    }

    /** Tab lists whatever is under the cursor. */
    fun bindTab(reader: LineReader) {
        try {
            val name = "kotllm-complete"
            reader.widgets[name] = Widget {
                listNow(reader)
                true
            }
            reader.keyMaps[LineReader.MAIN]!!.bind(Reference(name), Tab)
        } catch (e: Exception) {
        }
    }

    /** Re-lists as the name is typed, so the candidates narrow.
     *
     * **On every keystroke, not just the one that crosses the threshold.**
     * Listing once and stopping is what made the list appear at `:ha` and
     * vanish at `:hai`: the next character redraws the line, which wipes the
     * list, and nothing put it back. Narrowing while typing is the whole
     * reason for showing it.
     *
     * This puts a widget in the path of every alphanumeric keystroke, which
     * is why it does as little as possible: insert the character, look at the
     * word behind the cursor, and in the overwhelmingly common case where
     * there is no list to draw, stop.
     */
    fun bindListAsYouType(reader: LineReader) {
        try {
            val name = "kotllm-list-as-you-type"
            reader.widgets[name] = Widget {
                reader.buffer.write(reader.lastBinding)
                if (wantsMenu(reader)) listOnly(reader)
                true
            }
            val main = reader.keyMaps[LineReader.MAIN]!!
            ShortcodeChars.forEach { c -> main.bind(Reference(name), c.toString()) }
        } catch (e: Exception) {
        }
    }

    /** The Tab key. Built from its character code rather than written as an
     * escape, so no literal control byte sits in the source where an encoding
     * change could eat it -- the reason `Chrome.Esc` exists. */
    private val Tab: String = 9.toChar().toString()

    /** Every character a shortcode or a command name can contain. */
    private val ShortcodeChars: List<Char> =
        ('a'..'z') + ('A'..'Z') + ('0'..'9') + listOf('_', '+', '-')

    /** Whether there is a candidate list for the arrows to step into.
     *
     * The same predicate the key that *drew* the list uses, so the arrows
     * step into exactly the lists that exist. Asking a slightly different
     * question here is what left bare `/` out: its list was on screen and
     * Down went to history instead, which replaces the line -- reported as
     * the arrows clearing the menu. */
    private fun wantsMenu(reader: LineReader): Boolean =
        try {
            ReplCompleter.wantsList(reader.buffer.upToCursor())
        } catch (e: Exception) {
            false
        }

    /** Repaints JLine's menu list in the terminal's own colours.
     *
     * **JLine's default is `bg:bright-magenta`**, with the rest of the row in
     * grey on top of it -- reported, reasonably, as "the background for the
     * list is pink and text grey, very hard to read". It is a deliberate
     * default rather than a bug, but it is the only thing in kotllm that
     * paints its own background, and a REPL that has otherwise left the
     * user's colour scheme alone should not start now.
     *
     * So: no background at all, and the selected row in reverse video rather
     * than a colour, because reverse is the one highlight that is legible on
     * a light theme and a dark one without knowing which is in use. The
     * matched prefix keeps the cyan the plain list already used, so the two
     * kinds of list look like the same feature.
     */
    fun styleMenu(reader: LineReader) {
        try {
            fun set(key: String, value: String) = reader.setVariable(key, value)
            set(LineReader.COMPLETION_STYLE_LIST_BACKGROUND, "bg:default")
            set(LineReader.COMPLETION_STYLE_BACKGROUND, "bg:default")
            set(LineReader.COMPLETION_STYLE_LIST_SELECTION, "inverse")
            set(LineReader.COMPLETION_STYLE_SELECTION, "inverse")
            set(LineReader.COMPLETION_STYLE_LIST_STARTING, "fg:cyan")
            set(LineReader.COMPLETION_STYLE_STARTING, "fg:cyan")
            set(LineReader.COMPLETION_STYLE_LIST_DESCRIPTION, "fg:bright-black")
            set(LineReader.COMPLETION_STYLE_DESCRIPTION, "fg:bright-black")
            set(LineReader.COMPLETION_STYLE_LIST_GROUP, "fg:bright-black")
            set(LineReader.COMPLETION_STYLE_GROUP, "fg:bright-black")
        } catch (e: Exception) {
        }
    }
}
