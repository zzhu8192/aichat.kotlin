package kotllm.repl

import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

import org.jline.reader.EndOfFileException
import org.jline.reader.LineReader
import org.jline.reader.Reference
import org.jline.reader.UserInterruptException
import org.jline.reader.Widget
import org.jline.terminal.Terminal
import org.jline.terminal.TerminalBuilder
import org.jline.utils.InfoCmp.Capability

import kotllm.*
import kotllm.background.BackgroundRegistry
import kotllm.background.Spawner
import kotllm.render.Ansi
import kotllm.render.Markdown
import kotllm.render.on
import kotllm.render.Turn

/** The interactive chat REPL.
 *
 * Chat mode: every turn carries the accumulated context. Single-hit mode lives
 * in [kotllm.Main] and simply never enters this loop.
 *
 * Screen layout, output scrolling away above:
 *
 *     ────────────────────────────────────────
 *     ❯ your input
 *     ────────────────────────────────────────
 *       model · context · tokens · rate · turns
 */
class Repl(
    private val runner: Runner,
    private val terminal: Terminal = run {
        val cs = kotllm.util.Glyphs.outputCharset()
        TerminalBuilder.builder()
            .system(true)
            .dumb(true)
            .encoding(cs)
            .stdinEncoding(cs)
            .stdoutEncoding(cs)
            .stderrEncoding(cs)
            .build()
    },
    private val chrome: Chrome = Chrome(terminal),
    private val reader: LineReader = ReplLineReader(terminal, runner, chrome),
) {

    /** Exposed so tool approval can prompt through the same reader; JLine holds
     * the terminal in raw mode, so a separate System.in read would not work. */
    val lineReader: LineReader get() = reader

    init {
        // Replies are rendered as a block under the question they answer. Set here
        // rather than in Runner.build because only the REPL frames a transcript:
        // single-hit output is piped as often as it is read.
        runner.gutter = Turn.gutter

        (reader as? ReplLineReader)?.let { it.onRedraw = { redraw() } }

        bindSlashMenu()
        bindEmojiMenu()
        bindClearScreen()
    }

    /** Pops the command menu the moment `/` is typed at the start of a line.
     *
     * Bound only when the buffer is empty, which is the rule the parser uses: a
     * command is only a command as the first thing in the input, so a slash
     * inside a sentence or a path just inserts a slash. Pasted text arrives
     * through bracketed paste rather than the key map, so a pasted path
     * beginning with `/` will not trigger it either. */
    private fun bindSlashMenu() {
        try {
            // Ambiguous completions list themselves rather than cycling, so typing
            // `/` shows the commands instead of silently inserting the first one.
            // `AUTO_MENU` does not undo that -- the first completion still only
            // lists; it is the *second* that opens the selectable menu. Verified,
            // because the whole point of unsetting it was the first-Tab behaviour.
            reader.setOpt(LineReader.Option.AUTO_LIST)
            reader.setOpt(LineReader.Option.AUTO_MENU)
            reader.setOpt(LineReader.Option.AUTO_MENU_LIST)
            reader.setOpt(LineReader.Option.DISABLE_EVENT_EXPANSION)
            MenuKeys.styleMenu(reader)
            MenuKeys.bindArrows(reader)
            MenuKeys.bindOpenOnArrow(reader)

            val name = "kotllm-slash-menu"
            reader.widgets[name] = Widget {
                val atLineStart = reader.buffer.length() == 0
                reader.buffer.write('/'.code)
                if (atLineStart) reader.callWidget(LineReader.EXPAND_OR_COMPLETE)
                true
            }
            reader.keyMaps[LineReader.MAIN]!!.bind(Reference(name), "/")
        } catch (e: Exception) {
            // Not worth failing startup over: without the binding, TAB still
            // completes commands.
        }
    }

    /** Shows the emoji candidates the way `/` shows commands: a list, as soon
     * as there is enough of a shortcode to narrow it down.
     *
     * The keys themselves live in [MenuKeys], where they can be tested
     * against a real `LineReader`; this only decides that the REPL wants
     * them. Tab lists whatever is under the cursor, and typing inside a
     * `:shortcode` keeps the list narrowed as the name grows.
     *
     * **A list, deliberately, rather than a menu that inserts.** JLine's
     * `MENU_COMPLETE` selects the first candidate on the first Tab, which is
     * the "silently inserting the first one" the command menu already
     * rejected; for emoji it is worse, because the first of 219 is never the
     * wanted one.
     */
    private fun bindEmojiMenu() {
        MenuKeys.bindTab(reader)
        MenuKeys.bindListAsYouType(reader)
    }

    /** Binds Ctrl-L to redraw the terminal screen cleanly. */
    private fun bindClearScreen() {
        try {
            val name = "kotllm-redraw"
            reader.widgets[name] = Widget {
                redraw()
                reader.callWidget(LineReader.REDRAW_LINE)
                reader.callWidget(LineReader.REDISPLAY)
                true
            }
            reader.keyMaps[LineReader.MAIN]?.bind(Reference(name), "\u000c")
        } catch (e: Exception) {
        }
    }

    /** Expands the shortcodes in a line about to be sent, and counts them.
     *
     * Counted here rather than where a candidate is accepted, because this is
     * the point where a shortcode is definitely *used*: it covers names typed
     * out in full as well as ones picked from the list, and it never counts a
     * candidate that was offered and passed over. */
    private fun expandEmoji(text: String): String {
        try {
            kotllm.render.EmojiUsage.current.record(kotllm.render.Emoji.shortcodesIn(text))
        } catch (e: Exception) {
        }
        return kotllm.render.Emoji.expand(text)
    }

    /** Set by the SIGINT handler that is installed only while a generation is in
     * flight, so Ctrl-C cancels the reply rather than the whole process. */
    private val aborted = AtomicBoolean(false)

    /** When the first Ctrl-C of this turn arrived, for the escalation.
     *
     * **One press is cooperative, a second is forceful.** Stopping between tool
     * calls loses nothing; killing a running `exec` can leave a half-written
     * file, which is not something to do on a single keystroke.
     *
     * A second press cannot arrive "too late": the window never closes while the
     * turn is running. What it cannot do is escalate after the turn has ended,
     * which is correct -- by then the press belongs to the line editor. */
    private val firstInterruptAt = AtomicLong(0L)

    /** Handles one Ctrl-C during a turn.
     *
     * Runs on the signal thread, so it does the least possible: set a flag,
     * print one line, and on the second press call the killers. Anything
     * blocking here would make Ctrl-C itself unresponsive.
     *
     * Printing from here is safe only because `Repl.send` has already suspended
     * the pinned region for the whole turn. */
    private fun interrupt() {
        aborted.set(true)
        val now = System.currentTimeMillis()
        val first = firstInterruptAt.compareAndSet(0L, now)

        if (first) {
            val running = kotllm.util.Cancellation.labels()
            val what =
                if (running.isEmpty()) "after the current step"
                else "after ${running.joinToString(", ")}"
            System.err.println(Ansi.Color.Yellow.on("kotllm: stopping $what. Ctrl-C again to kill it."))
        } else {
            val killed = kotllm.util.Cancellation.cancelAll()
            System.err.println(
                Ansi.Color.Yellow.on(
                    if (killed > 0) "kotllm: killed $killed running operation(s)."
                    else "kotllm: nothing left to kill; waiting for the turn to unwind."
                )
            )
        }
    }

    private val MultilineFence = ":::"

    private val bgTurns = BackgroundRegistry()
    private val spawner = Spawner(bgTurns) { runner.forkBackground() }

    fun run() {
        // Order matters: the pinned rows are reserved first, the screen is then
        // scrolled so the input line starts directly above them, and anything
        // printed comes last -- docking scrolls, so a print before it is gone.
        chrome.refresh(runner)
        chrome.dockBottom(rowsAbove = noticeRows)
        if (startupNotices.isNotEmpty()) chrome.whilePrinting { printStartupNotices() }

        var running = true
        while (running) {
            // Drained between prompts, never mid-keystroke: a notice printed while
            // the line editor is active would corrupt the input line.
            reportFinishedTurns()
            chrome.refresh(runner)
            val input = readInput()
            if (input == null) {
                running = false
            } else {
                // The echo the line editor left between the rules is erased, and the
                // turn reprinted above them. Without this the rules end up
                // bracketing the whole conversation instead of the input line.
                chrome.eraseRows(input.echoRows)
                if (input.text.trim().isNotEmpty()) dispatchInput(input) { running = false }
            }
        }
        close()
    }

    private fun dispatchInput(input: Input, stop: () -> Unit) {
        when (input) {
            // Deliberately composed multi-line text: never reinterpreted as a command,
            // even if it happens to open with a slash.
            is Input.Fenced -> send(expandEmoji(input.text))
            is Input.Typed -> {
                val parsed = Commands.parse(input.text)
                if (parsed == null) {
                    // Shortcodes expand only in prose sent to the model, not in a command
                    // line: an argument like a file path has no business being scanned
                    // for `:word:` patterns.
                    send(expandEmoji(input.text))
                } else {
                    val (name, args) = parsed
                    when (val outcome = runCommand(input.text, name, args)) {
                        ReplOutcome.Exit -> stop()
                        ReplOutcome.Continue -> Unit
                        // The command echoed itself already, so the turn it hands back is
                        // not echoed again -- and `/file` would otherwise dump every
                        // included file into the transcript twice.
                        is ReplOutcome.Send -> send(outcome.text, echo = false, images = outcome.images)
                    }
                }
            }
        }
    }

    /** What still gets printed above the input line at startup, as plain text so
     * its height can be measured before docking.
     *
     * **There is no banner in the framed layout** -- the version, model and tool
     * state are in the pinned footer, where they stay readable instead of
     * scrolling away. Two things still earn a printed line:
     *
     *   - The banner, but only when the layout is *not* framed: with no footer
     *     there is nowhere else for it to go.
     *   - The tool misconfiguration hint, whenever it fires. It is prose about a
     *     config file rather than a status field.
     */
    private val startupNotices: List<String>
        get() {
            val banner =
                if (chrome.framed) emptyList()
                else listOf(
                    "kotllm ${kotllm.util.BuildInfo.full()} | ${runner.target.model.name} | " +
                        "tools: ${runner.toolStatusSummary} | /help for commands"
                )
            return banner + runner.startupHints.map { hint -> "kotllm: $hint" }
        }

    private val noticeRows: Int
        get() = startupNotices.sumOf { l -> chrome.wrapRows(l.length) }

    private fun printStartupNotices() {
        val notices = startupNotices
        val w = terminal.writer()
        // The hint is the tail whenever a banner precedes it, and it is the one
        // that should read as a warning.
        notices.dropLast(runner.startupHints.size).forEach { l ->
            w.println(Ansi.Color.DarkGray.on(l))
        }
        runner.startupHints.forEach { h -> w.println(Ansi.Color.Yellow.on("kotllm: $h")) }
        w.flush()
    }

    /** Where a line came from, which decides whether it can be a command.
     *
     * `echoRows` is tallied as the line is read rather than reconstructed after:
     * a fence is several prompts deep, and guessing its height would erase
     * either too much of the transcript or too little of the echo. */
    private sealed interface Input {
        val text: String
        val echoRows: Int

        data class Typed(override val text: String, override val echoRows: Int) : Input
        data class Fenced(override val text: String, override val echoRows: Int) : Input
    }

    /** @return null when the user asked to leave (Ctrl-D). */
    private fun readInput(): Input? =
        try {
            val line = reader.readLine(chrome.prompt(runner))
            if (line == null) null
            else if (line.trim() == MultilineFence) readFenced(chrome.echoedPromptRows(line))
            else Input.Typed(line, chrome.echoedPromptRows(line))
        } catch (e: UserInterruptException) {
            // Ctrl-C at the prompt abandons the line, as in a shell. Nothing is
            // erased for it: what the editor left on screen depends on how much had
            // been typed, and over-erasing would eat the transcript above it.
            Input.Typed("", 0)
        } catch (e: EndOfFileException) {
            null
        }

    /** Collects lines until a closing ::: fence, tallying the rows they occupy. */
    private fun readFenced(openRows: Int): Input? {
        val buf = StringBuilder()
        var rows = openRows
        var open = true
        while (open) {
            try {
                val line = reader.readLine(Prompt.continuation)
                if (line == null) {
                    open = false
                } else {
                    rows += chrome.continuationRows(line)
                    if (line.trim() == MultilineFence) open = false
                    else {
                        if (buf.isNotEmpty()) buf.append('\n')
                        buf.append(line)
                    }
                }
            } catch (e: UserInterruptException) {
                return Input.Typed("", 0)
            } catch (e: EndOfFileException) {
                open = false
            }
        }
        return Input.Fenced(buf.toString(), rows)
    }

    /** Dispatches a command with the pinned region released.
     *
     * Most commands print, and printing underneath an active status area corrupts
     * the reserved rows. The overlay is the exception -- it paints *into* that
     * area -- so it re-enables the region for itself. */
    private fun runCommand(line: String, name: String, args: String?): ReplOutcome {
        // Echoed into the transcript, so a command's output has the command above
        // it. The editor's own echo between the rules has already been erased.
        val echoRows = chrome.wrapRows(Chrome.CaretCols + line.length)
        chrome.whilePrinting { println(Turn.question(line)) }

        // Handled here rather than in Commands because it needs the spawner, which
        // belongs to the REPL: only the REPL can drain notices between prompts.
        if (name == "/bg") return chrome.whilePrinting {
            val o = backgroundCommand(args)
            println()
            o
        }

        var overlaid = false
        chrome.suspend()
        try {
            val outcome = Commands.dispatch(
                runner,
                name,
                args,
                { lines ->
                    chrome.restore()
                    overlaid = chrome.overlay(lines, Prompt.footer(runner, chrome.width))
                    // The overlay declined, so the caller will print instead: go back to
                    // a released region before that happens.
                    if (!overlaid) chrome.suspend()
                    overlaid
                }
            )
            // A command whose whole output was a transient overlay leaves no trace at
            // all, including the echo printed above -- otherwise `/status` would
            // accumulate a bare echoed command line per use.
            if (overlaid) {
                chrome.eraseRows(echoRows)
                redraw()
            } else println()
            return outcome
        } finally {
            if (!overlaid) chrome.restore()
        }
    }

    /** Redraws the terminal screen cleanly when framed layout is active.
     *
     * Used after an overlay dismissal (or via Ctrl-L): clears the screen,
     * replays any transcript turns snug directly above the upper prompt rule,
     * restores and repaints the pinned status footer, and positions the cursor
     * at targetRow so the prompt is docked snug above the status area without
     * any stray empty lines.
     */
    fun redraw() {
        if (!chrome.framed) return
        chrome.whilePrinting {
            terminal.puts(Capability.clear_screen)
            terminal.flush()

            val msgs = runner.session.messages()
            if (msgs.isNotEmpty()) {
                val lines = mutableListOf<String>()
                val ledger = runner.session.ledger.all()
                var turnIdx = 0
                for (m in msgs) {
                    when (m.role) {
                        "user" -> {
                            lines.addAll(Turn.question(m.text).lines())
                            lines.add("")
                        }
                        "assistant" -> {
                            if (m.text.isNotEmpty()) {
                                val rendered = Markdown.render(m.text, runner.config.highlight)
                                lines.addAll(runner.gutter.render(rendered).lines())
                                lines.add("")
                            }
                            if (turnIdx < ledger.size) {
                                val metric = ledger[turnIdx]
                                lines.add(Turn.processed(metric.latencyMs, metric.completionTokens))
                                lines.add("")
                                turnIdx++
                            }
                        }
                    }
                }

                val rows = terminal.height
                val targetRow = maxOf(0, rows - Chrome.ReservedRows - Chrome.PromptRows)
                val totalRows = lines.sumOf { chrome.wrapRows(it.length) }
                val startRow = maxOf(0, targetRow - totalRows)

                terminal.puts(Capability.cursor_address, startRow, 0)
                terminal.flush()
                val w = terminal.writer()
                lines.forEach { w.println(it) }
                w.flush()
            } else {
                if (startupNotices.isNotEmpty()) {
                    val rows = terminal.height
                    val targetRow = maxOf(0, rows - Chrome.ReservedRows - Chrome.PromptRows)
                    val startRow = maxOf(0, targetRow - noticeRows)
                    terminal.puts(Capability.cursor_address, startRow, 0)
                    terminal.flush()
                    printStartupNotices()
                }
            }
        }
        chrome.clearStatus()
        chrome.refresh(runner)
        val rows = terminal.height
        val targetRow = maxOf(0, rows - Chrome.ReservedRows - Chrome.PromptRows)
        terminal.puts(Capability.cursor_address, targetRow, 0)
        terminal.flush()
    }

    /** Sends one turn and lays it out in the transcript: the question, the reply
     * in its gutter, then what the turn cost.
     *
     * @param echo false when the caller has already echoed the input, which is
     *             what a command that hands back text has done.
     */
    private fun send(
        raw: String,
        echo: Boolean = true,
        images: List<kotllm.client.Part.Image> = emptyList()
    ) {
        aborted.set(false)
        firstInterruptAt.set(0L)
        // Take over SIGINT for the duration of the request, then hand it back so
        // the line reader keeps its own Ctrl-C behaviour at the prompt.
        val previous = terminal.handle(Terminal.Signal.INT) { interrupt() }

        // The reserved rows are released for the whole of this, not just the
        // request: template expansion reports its own progress and errors, and
        // anything printed under an active status area corrupts it.
        chrome.suspend()
        try {
            if (echo) {
                println(Turn.question(raw))
                println()
            }

            val startedAt = System.currentTimeMillis()
            // Measured as a delta rather than read off the last request: a turn that
            // called tools spans several requests, and all of them generated tokens.
            val tokensBefore = runner.session.ledger.totalCompletionTokens()

            // Templates resolve here, on the client: the model receives only the
            // expanded text and never sees the tags.
            runner.expand(raw).fold(
                onErr = { e ->
                    // Not sent: a half-expanded prompt is worse than none.
                    System.err.println(Ansi.Color.Red.on("kotllm: $e"))
                },
                onOk = { text ->
                    if (runner.wouldExpand(raw))
                        println(
                            Ansi.Color.DarkGray.on(
                                "${Turn.Indent}${kotllm.util.Glyphs.ellipsis} template expanded: " +
                                    "${raw.length} ${kotllm.util.Glyphs.arrow} ${text.length} chars"
                            )
                        )

                    runner.ask(text, { aborted.get() }, images).fold(
                        onErr = { e ->
                            // No cost line: the error is what happened, and pairing it with a
                            // duration reads as though something was produced.
                            System.err.println(Ansi.Color.Red.on("kotllm: $e"))
                        },
                        onOk = {
                            if (aborted.get())
                                System.err.println(Ansi.Color.Yellow.on("kotllm: stopped."))
                            println()
                            println(
                                Turn.processed(
                                    System.currentTimeMillis() - startedAt,
                                    runner.session.ledger.totalCompletionTokens() - tokensBefore
                                )
                            )
                        }
                    )
                }
            )
            println()
        } finally {
            chrome.restore()
            terminal.handle(Terminal.Signal.INT, previous)
        }
    }

    /** `/bg <prompt>` starts a turn in the background; `/bg` lists them. */
    private fun backgroundCommand(args: String?): ReplOutcome {
        val trimmed = args?.trim()?.takeIf { it.isNotEmpty() }
        if (trimmed == null) {
            val all = bgTurns.all
            if (all.isEmpty()) println("no background turns this session")
            else all.forEach { t -> println(t.summary) }
        } else {
            // Expanded here, in the foreground, so a template failure is reported
            // immediately rather than surfacing minutes later as a failed background turn.
            runner.expand(trimmed).fold(
                onErr = { e -> System.err.println(Ansi.Color.Red.on("kotllm: $e")) },
                onOk = { expanded ->
                    val id = spawner.spawn(expanded)
                    println(Ansi.Color.DarkGray.on("[$id] started in the background; /bg to check"))
                    if (runner.config.toolApproval == kotllm.tools.ApprovalMode.Confirm &&
                        runner.activeTools.isNotEmpty()
                    )
                        println(
                            Ansi.Color.DarkGray.on(
                                "      note: tool calls in a background turn are refused under " +
                                    "tool_approval: confirm, since there is nobody to ask"
                            )
                        )
                }
            )
        }
        return ReplOutcome.Continue
    }

    /** Prints a one-line notice for anything that finished since the last
     * prompt, with the reply itself so the answer is not lost. */
    private fun reportFinishedTurns() {
        val done = bgTurns.drainFinished()
        if (done.isNotEmpty()) chrome.whilePrinting {
            done.forEach { task ->
                val colour = when (task.state) {
                    kotllm.background.TurnState.Succeeded -> Ansi.Color.Green
                    kotllm.background.TurnState.Cancelled -> Ansi.Color.DarkGray
                    else -> Ansi.Color.Yellow
                }
                println(colour.on("${kotllm.util.Glyphs.bullet} ${task.summary}"))
                task.reply?.takeIf { it.trim().isNotEmpty() }?.let { reply ->
                    println(kotllm.render.Markdown.render(reply, runner.config.highlight))
                }
            }
        }
    }

    private fun close() {
        // Signals background turns to stop. They are daemons, so an unresponsive
        // one cannot hold the process open either way.
        bgTurns.cancelAll()
        chrome.close()
        runner.close()
        try {
            reader.history.save()
        } catch (e: Exception) {
        }
        try {
            terminal.close()
        } catch (e: Exception) {
        }
    }
}
