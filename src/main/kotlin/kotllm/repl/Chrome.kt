package kotllm.repl

import org.jline.terminal.Size
import org.jline.terminal.Terminal
import org.jline.utils.AttributedString
import org.jline.utils.InfoCmp.Capability
import org.jline.utils.Status as JLineStatus

import kotllm.Runner
import kotllm.render.Ansi
import kotllm.render.on

/** Pins the lower rule and the status footer beneath the input line.
 *
 * Uses JLine's `Status`, which reserves the bottom rows and keeps them painted
 * while the line editor works above. Doing it by hand breaks the moment a line
 * wraps or the window is resized.
 *
 * `Status` is unavailable on a dumb terminal (piped output, a CI log), where
 * the chrome degrades to a plain one-line prompt rather than emitting rules
 * with nothing pinned under them. See docs/repl.md.
 */
class Chrome(private val terminal: Terminal) {

    // Belt and braces alongside `Main.main`: whatever gets to a terminal first
    // has disabled the alternate charset before JLine can read the flag.
    init {
        kotllm.util.JLineTuning.init()
    }

    /** Off switch for the framed layout.
     *
     * `KOTLLM_CHROME=plain` degrades to the plain single-line prompt. A terminal
     * can advertise `change_scroll_region` and then not honour it -- Windows
     * conhost is the classic case -- and no query tells us in advance.
     *
     * Deliberately *off only*: forcing the framed layout onto a terminal that
     * fails the capability check would reintroduce the `Status` NPE on exit. */
    private val forcedPlain: Boolean =
        (System.getenv("KOTLLM_CHROME") ?: System.getenv("SCALLM_CHROME")
            ?: System.getProperty("kotllm.chrome"))
            ?.trim()?.lowercase()
            ?.let { v -> v == "plain" || v == "off" || v == "none" }
            ?: false

    /** Whether a pinned status area can work here.
     *
     * Checked *before* constructing a `Status`: JLine leaves its internal display
     * null on an unsupported terminal and then dereferences it in `close()`,
     * which the terminal calls on shutdown whether or not we do. So merely
     * creating one plants an NPE that fires on exit. Same four capabilities
     * Status itself tests. */
    private val capable: Boolean =
        if (forcedPlain) false
        else
            try {
                val t = terminal.type
                t != null && t != Terminal.TYPE_DUMB && t != Terminal.TYPE_DUMB_COLOR &&
                    listOf(
                        Capability.change_scroll_region,
                        Capability.save_cursor,
                        Capability.restore_cursor,
                        Capability.cursor_address
                    ).all { c -> terminal.getStringCapability(c) != null }
            } catch (e: Exception) {
                false
            }

    private val status: JLineStatus? =
        if (!capable) null
        else
            try {
                JLineStatus.getStatus(terminal)
            } catch (e: Exception) {
                null
            }

    val framed: Boolean = status != null

    init {
        status?.setBorder(false)
        if (framed) armTerminalReset()
    }

    /** One column short of the terminal width. A rule that exactly fills it
     * leaves the cursor in the last column, and the terminal then auto-wraps on
     * the next character, desyncing the row accounting for both the prompt and
     * the pinned region. */
    fun width(cols: Int): Int =
        maxOf(20, (if (cols > 0) cols else 80) - 1)

    val width: Int get() = width(terminal.width)

    private val columns: Int get() {
        val w = terminal.width
        return if (w > 0) w else 80
    }

    /** Erases the prompt and echoed input a just-completed readLine left on
     * screen.
     *
     * For commands whose whole output was a transient overlay: leaving `/status`
     * echoed under a rule with nothing beneath it is the clutter the overlay
     * exists to avoid. Commands that print to the transcript keep their echo. */
    fun eraseEchoedPrompt(input: String) = eraseRows(echoedPromptRows(input))

    /** Rows the line editor leaves on screen for one prompt: the rule on its own
     * row, plus the caret row, which wraps once per terminal width consumed by
     * caret and input together. */
    fun echoedPromptRows(input: String): Int =
        if (framed) 1 + wrapRows(CaretCols + input.length) else 0

    /** Rows a `:::` continuation line leaves on screen. No rule above it, since
     * the fence draws one only for its opening prompt. */
    fun continuationRows(line: String): Int =
        if (framed) wrapRows(Prompt.continuationCols + line.length) else 0

    /** How many terminal rows `cols` printed columns occupy. */
    fun wrapRows(cols: Int): Int =
        maxOf(1, (maxOf(cols, 1) + columns - 1) / columns)

    /** Erases `rows` rows above the cursor, and everything after them.
     *
     * The REPL reprints the transcript once a turn is done, so the echo the line
     * editor left between the rules has to go first -- otherwise the two rules
     * bracket the whole conversation rather than the input line. */
    fun eraseRows(rows: Int) {
        if (framed && rows > 0)
            // Released first: writing raw cursor escapes underneath an active pinned
            // region corrupts it. Repainted on the way out, and a no-op when the
            // caller has already released it.
            whilePrinting {
                try {
                    val w = terminal.writer()
                    // Exactly `rows` rows, one at a time, cursor back where it started.
                    //
                    // Emphatically *not* `ED 0` ("erase to end of screen"), which is what
                    // this did first: it clears past the scroll region into the reserved
                    // rows, and repainting them needs `Status.reset()`, which parks the
                    // cursor below the region without putting it back. A line feed outside
                    // the region does not scroll, so every later line overwrote the bottom
                    // row -- the "everything scrunched at the bottom" bug. Clearing only
                    // our own rows needs no repaint and leaves the cursor inside.
                    //
                    // Built from a named constant so no literal control byte sits in the
                    // source, where an encoding change could silently eat it.
                    val b = StringBuilder()
                    b.append("$Esc[${rows}A") // to the top of the block
                    for (i in 1..rows) b.append("$Esc[2K$Esc[B") // clear the row, step down
                    b.append("$Esc[${rows}A\r") // back to the top, column 0
                    w.print(b.toString())
                    w.flush()
                } catch (e: Exception) {
                }
            }
    }

    fun prompt(runner: Runner): String =
        prompt(runner, terminal.width)

    fun prompt(runner: Runner, cols: Int): String =
        if (framed) Prompt.input(width(cols)) else Prompt.plain(runner)

    /** Repaints the lower rule and the idle footer. Called before each read so
     * the footer reflects the turn that just completed. */
    fun refresh(runner: Runner) =
        refresh(runner, terminal.width)

    fun refresh(runner: Runner, cols: Int) =
        paint(Prompt.footer(runner, width(cols)), cols)

    /** Swaps in the in-flight footer for the duration of a turn. */
    fun busy(label: String) =
        paint(Prompt.busyFooter(label))

    /** Scrolls the screen clear and parks the cursor so the input line starts
     * docked directly above the pinned rows.
     *
     * The pinned rows are at the bottom but the prompt is drawn wherever the
     * cursor happens to be, so a shell whose output ends halfway up the screen
     * used to get rules bracketing somebody else's text -- this addressed the
     * cursor directly, which *moves over* what is already there.
     *
     * Printing a screenful of newlines first scrolls that into the scrollback
     * and leaves every row below blank, so nothing is overwritten and the target
     * needs no cursor query -- which JLine cannot answer on Windows anyway.
     *
     * @param rowsAbove rows to leave free above the input line, for a caller
     *                  that prints a banner after docking.
     */
    fun dockBottom(rowsAbove: Int = 0) {
        if (framed) {
            val rows = terminal.height
            val targetRow = rows - ReservedRows - PromptRows - rowsAbove
            if (targetRow > 0) {
                try {
                    val w = terminal.writer()
                    w.print("\n".repeat(rows))
                    w.flush()
                    terminal.puts(Capability.cursor_address, targetRow, 0)
                    terminal.flush()
                } catch (e: Exception) {
                }
            }
        }
    }

    fun onResize(runner: Runner, size: Size) {
        status?.let { s ->
            try {
                s.resize(size)
                s.reset()
                paint(Prompt.footer(runner, width(size.columns)), size.columns)
            } catch (e: Exception) {
            }
        }
    }

    /** Clears the cached status lines in JLine's Status so the next paint forces a full repaint. */
    fun clearStatus() {
        status?.let { s ->
            try {
                s.update(emptyList())
            } catch (e: Exception) {
            }
        }
    }

    private fun paint(footer: String, cols: Int = terminal.width) {
        status?.let { s ->
            try {
                s.update(lines(footer, cols))
            } catch (e: Exception) {
            }
        }
    }

    private fun lines(footer: String, cols: Int = terminal.width): List<AttributedString> {
        val list = java.util.ArrayList<AttributedString>(2)
        list.add(AttributedString.fromAnsi(Prompt.divider(width(cols))))
        list.add(AttributedString.fromAnsi(footer))
        return list
    }

    /** Whether the terminal can lend us a second screen.
     *
     * `enter_ca_mode` / `exit_ca_mode` are what `less` and `vim` use: everything
     * drawn goes to a scratch buffer, and leaving restores the original screen
     * byte for byte, scrollback included. */
    private val canSwapScreen: Boolean =
        capable &&
            terminal.getStringCapability(Capability.enter_ca_mode) != null &&
            terminal.getStringCapability(Capability.exit_ca_mode) != null

    /** Shows a transient report, dismissed by any keypress, leaving the screen
     * exactly as it was.
     *
     * The first version painted into the pinned status area, which is fine for
     * three lines and destructive for thirty: growing the reserved region
     * scrolls the transcript off to make room, and shrinking it back does not
     * scroll it in again. `/status` is the longest report there is, so
     * dismissing it handed back a black screen.
     *
     * The alternate screen has none of that problem -- it is what `less` and
     * `vim` use, and leaving it restores the original byte for byte. Every
     * terminal that passes the pinned-region capability check has one, so there
     * is no third path: without it the caller prints instead.
     *
     * @return false when there is nowhere to draw, so the caller can fall back
     *         to printing.
     */
    fun overlay(lines: List<String>, footer: String): Boolean {
        if (!canSwapScreen) return false
        // Released first: the pinned region owns the scroll region, and leaving it
        // set across a screen swap strands it on the wrong buffer.
        val hadStatus = framed && !isSuspended
        if (hadStatus) suspend()
        var entered = false
        try {
            val w = terminal.writer()
            val rows = maxOf(3, terminal.height)
            // One row for the dismissal hint, which must stay visible.
            val shown = lines.take(rows - 1)
            val elided = lines.size - shown.size

            terminal.puts(Capability.enter_ca_mode)
            entered = true
            terminal.puts(Capability.clear_screen)
            terminal.flush()
            shown.forEach { l -> w.println(l) }
            if (elided > 0)
                w.println(
                    Ansi.Color.DarkGray.on("  ... $elided more line(s); make the window taller to see them")
                )
            w.println(Ansi.Color.DarkGray.on("  press any key to dismiss"))
            w.flush()

            awaitKeypress()
            return true
        } catch (e: Exception) {
            return false
        } finally {
            // In the finally, not the happy path: a throw between entering and
            // leaving would strand the whole session on the scratch buffer, with the
            // conversation apparently gone.
            if (entered) {
                try {
                    terminal.puts(Capability.exit_ca_mode)
                    terminal.flush()
                } catch (e: Exception) {
                }
            }
            if (hadStatus) restore()
            paint(footer)
        }
    }

    /** Blocks for a single keypress. Raw mode is entered explicitly: outside
     * `readLine` the terminal is cooked, where a read would not return until
     * Enter. Attributes are restored afterwards. */
    private fun awaitKeypress() {
        val saved = terminal.enterRawMode()
        try {
            terminal.reader().read()
        } catch (e: Exception) {
        } finally {
            try {
                terminal.attributes = saved
            } catch (e: Exception) {
            }
        }
    }

    /** Releases the reserved rows so streamed output can use the full screen.
     * Writing to stdout while the status area is active corrupts it. */
    fun suspend() {
        status?.let { s ->
            try {
                s.suspend()
                isSuspended = true
            } catch (e: Exception) {
            }
        }
    }

    fun restore() {
        status?.let { s ->
            try {
                s.restore()
                isSuspended = false
            } catch (e: Exception) {
            }
        }
    }

    val suspended: Boolean get() = isSuspended

    @Volatile
    private var isSuspended = false

    /** Runs `body` with the pinned region released.
     *
     * Anything that writes to stdout must go through this. The observed symptom
     * otherwise was the footer migrating up the screen and then etching itself
     * permanently into the scrollback. */
    fun <A> whilePrinting(body: () -> A): A {
        val reactivate = framed && !isSuspended
        if (reactivate) suspend()
        try {
            return body()
        } finally {
            if (reactivate) restore()
        }
    }

    /** Clears the footer for good, on the way out. */
    fun close() {
        status?.let { s ->
            try {
                s.update(emptyList())
                s.close()
            } catch (e: Exception) {
            }
        }
        if (framed) releaseTerminal()
    }

    companion object {

        /** ASCII escape, for the cursor sequences used to erase a prompt. */
        internal val Esc: Char = 27.toChar()

        /** Hands the whole screen back: scroll region reset, cursor shown, and off
         * the alternate screen if an overlay was mid-flight.
         *
         * **`ESC[r` with no parameters, deliberately.** The pinned region is a
         * DECSTBM, and a scroll region outlives the process that set it -- the
         * terminal keeps it until something resets it, so every command run after
         * kotllm is confined to the rows kotllm was using. JLine's own teardown
         * emits `ESC[1;Nr` for the N it last measured, which is only a reset if
         * that N still matches the window; resize before exiting, or exit from a
         * terminal whose height JLine read wrong, and the region is left wrong
         * rather than cleared. The parameterless form means "the whole screen",
         * whatever the screen currently is, which is the only form that cannot be
         * stale. */
        private val Release: String = "$Esc[r$Esc[?25h$Esc[?1049l"

        /** Written straight to the real stdout rather than through JLine, because
         * this runs when JLine is closing or already gone. */
        private fun releaseTerminal() {
            try {
                val out = java.io.PrintStream(java.io.FileOutputStream(java.io.FileDescriptor.out), true)
                out.print(Release)
                out.flush()
            } catch (e: Exception) {
            }
        }

        /** Registered once, the first time a framed chrome exists.
         *
         * `Chrome.close()` already releases the terminal on the way out of a clean
         * exit, and this is for every other way a process ends: an exception
         * escaping the loop, `System.exit` from the interpreter's exit trap, or a
         * SIGINT/SIGTERM from outside. Those paths never reach `close()`, and what
         * they leave behind is a terminal that is still scrolling inside kotllm's
         * region long after kotllm is gone -- reported as the terminal being
         * smashed on the *next* run, which is the confusing part: the damage is
         * done by the previous one.
         *
         * Only armed when `framed`, so nothing is ever written to a pipe or a
         * dumb terminal, where these bytes would be corruption rather than
         * cleanup. */
        private fun armTerminalReset() = synchronized(this) {
            if (!resetArmed) {
                resetArmed = true
                try {
                    Runtime.getRuntime().addShutdownHook(Thread({ releaseTerminal() }, "kotllm-terminal-reset"))
                } catch (e: Exception) {
                }
            }
        }

        private var resetArmed = false

        /** Rows the pinned region holds: the lower rule and the footer. */
        internal const val ReservedRows = 2

        /** Rows the prompt itself holds: the upper rule and the caret line. */
        internal const val PromptRows = 2

        /** Columns the caret and its trailing space occupy. */
        internal const val CaretCols = 2
    }
}
