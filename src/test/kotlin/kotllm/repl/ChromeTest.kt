package kotllm.repl

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.nio.charset.StandardCharsets

import org.jline.terminal.Size
import org.jline.terminal.Terminal
import org.jline.utils.InfoCmp.Capability

import kotlin.test.Test
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldBeEmpty
import io.kotest.matchers.collections.shouldContainAll
import io.kotest.assertions.throwables.shouldNotThrowAny
import io.kotest.assertions.throwables.shouldThrow

import kotllm.Runner
import kotllm.TestIsolation
import kotllm.client.ChatRequest
import kotllm.client.ChatResponse
import kotllm.client.Client
import kotllm.client.Message
import kotllm.client.StreamEvent
import kotllm.config.ClientConfig
import kotllm.config.Config
import kotllm.config.ModelConfig
import kotllm.config.ModelTarget
import kotllm.config.Session
import kotllm.render.Ansi
import kotllm.render.Attrs
import kotllm.render.on
import kotllm.task.TaskStatus
import kotllm.util.Glyphs
import kotllm.util.TurnMetrics
import kotllm.util.Yaml

/** Exercises the REPL chrome against a real JLine terminal.
 *
 * The terminal is built over byte streams with an explicit `xterm` type rather
 * than the system console, because the test JVM has no tty. That still gives a
 * cursor-addressable terminal, which is what `Status` needs, so the pinned
 * footer path is genuinely exercised and its output can be inspected.
 */
class ChromeTest {

    private object UnusedClient : Client {
        override val id: String = "test"
        override fun chat(req: ChatRequest): ChatResponse = throw UnsupportedOperationException()
        override fun chatStream(
            req: ChatRequest,
            onEvent: (StreamEvent) -> Unit,
            keepGoing: (() -> Boolean)?
        ): ChatResponse = throw UnsupportedOperationException()
    }

    private fun runner(yaml: String = "model: m\n", maxInput: Int? = 261000): Runner {
        val config = Config.fromConf(Yaml.loadString(yaml))
        val client = ClientConfig("openai-compatible", "c", "https://h/v1", "k", emptyList(), null, null)
        val model = ModelConfig("qwen35-122b", maxInput, null, supportsVision = false, supportsFunctionCalling = true)
        return Runner(TestIsolation.isolate(config), Session(), ModelTarget(client, model), UnusedClient)
    }

    private fun record(r: Runner, prompt: Int, completion: Int, tools: List<String> = emptyList()) {
        r.session.ledger.record(
            TurnMetrics(
                r.session.ledger.nextIndex(), 0L, "qwen35-122b", prompt, completion, 0, 1500L,
                500L, tools, truncated = false, aborted = false
            )
        )
    }

    private fun <A> withTerminal(f: (Terminal, ByteArrayOutputStream) -> A): A {
        val out = ByteArrayOutputStream()
        val ins = ByteArrayInputStream(ByteArray(0))
        val terminal = testTerminal(ins, out)
        try {
            return f(terminal, out)
        } finally {
            terminal.close()
        }
    }

    private fun rendered(out: ByteArrayOutputStream): String =
        String(out.toByteArray(), StandardCharsets.UTF_8)

    /** How many whole-line erases the chrome emitted. */
    private fun clearedRows(screen: String): Int =
        Regex("""\[2K""").findAll(screen).count()

    /** The raw colour escape a `colour.on(...)` call prefixes onto its text,
     * isolated from any particular text: what a test wants to know is that
     * *some* element carries this colour, not which words it wraps. `X` is a
     * marker that cannot appear inside an ANSI escape code itself, so slicing
     * in front of it leaves exactly the escape prefix behind. */
    private fun escapeOf(colour: Attrs): String =
        colour.on("X").substringBefore('X')

    @Test fun `a cursor-addressable terminal gets the framed two-rule layout`() {
        withTerminal { terminal, _ ->
            Chrome(terminal).framed shouldBe true
        }
    }

    @Test fun `the prompt carries the upper rule and the caret on its own line`() {
        withTerminal { terminal, _ ->
            val prompt = Chrome(terminal).prompt(runner())
            val lines = prompt.split("\n")

            lines.size shouldBe 2
            lines[0] shouldContain Glyphs.dividerChar
            lines[1] shouldContain Glyphs.promptCaret
        }
    }

    @Test fun `painting the footer emits the lower rule and the status text`() {
        withTerminal { terminal, out ->
            val r = runner()
            record(r, 18_400, 2_300)

            val chrome = Chrome(terminal)
            chrome.refresh(r)
            terminal.flush()

            val screen = rendered(out)
            // JLine emits either the literal character or DEC alternate character set (ESC ( 0 q ... ESC ( B)
            screen shouldContain Glyphs.dividerChar
            screen shouldContain "qwen35-122b"
            screen shouldContain "18.4k/261k ctx"
            screen shouldContain "7%"
        }
    }

    @Test fun `the busy footer replaces the idle one while a turn is in flight`() {
        withTerminal { terminal, out ->
            val chrome = Chrome(terminal)
            chrome.busy("qwen35-122b is thinking")
            terminal.flush()

            rendered(out) shouldContain "is thinking"
        }
    }

    @Test fun `the footer degrades to a single-line prompt without a pinned area`() {
        // A dumb terminal cannot reserve rows, so rules with nothing under them
        // would be noise.
        val out = ByteArrayOutputStream()
        val terminal = dumbTestTerminal(ByteArrayInputStream(ByteArray(0)), out)
        try {
            val chrome = Chrome(terminal)
            chrome.framed shouldBe false
            // Asserted as "one line", not "no divider glyph": the ASCII fallback
            // divider is a hyphen, which also occurs in the model name.
            (chrome.prompt(runner()).contains("\n")) shouldBe false
            shouldNotThrowAny { chrome.refresh(runner()) }
        } finally {
            terminal.close()
        }
    }

    @Test fun `footer shows session output tokens and turn count`() {
        val r = runner()
        record(r, 100, 2_300)
        record(r, 200, 1_000)

        val footer = Prompt.footer(r)
        footer shouldContain "3.3k tokens"
        footer shouldContain "2 reqs"
    }

    @Test fun `footer reports the rate-limit window when one is configured`() {
        val r = runner("model: m\nrate_limit:\n  input_tokens_per_minute: 1000\n")
        (r.limiter != null) shouldBe true
        r.limiter!!.settle(r.limiter!!.reserve(500), 500, 0)

        Prompt.footer(r) shouldContain "rate 50%"
    }

    @Test fun `footer omits the rate window when no limit is set`() {
        (Prompt.footer(runner()).contains("rate")) shouldBe false
    }

    @Test fun `footer carries task progress only once a checklist exists`() {
        // A one-shot question must never show `0/0 tasks`, which means nothing.
        val r = runner()
        (Prompt.footer(r).contains("tasks")) shouldBe false

        r.checklist.create(listOf("read the spec", "write the parser", "add tests"))
        Prompt.footer(r) shouldContain "0/3 tasks"

        r.checklist.update(listOf(1 to TaskStatus.Done))
        Prompt.footer(r) shouldContain "1/3 tasks"
    }

    @Test fun `task progress outlives the session totals when the window is narrow`() {
        // It says what is happening now; a cumulative token count says what
        // already happened. Both are droppable, but not in that order.
        val r = runner()
        r.checklist.create(listOf("one task"))
        record(r, 900, 900)

        val narrow = Prompt.footer(r, width = 60)
        narrow shouldContain "tasks"
        (narrow.contains("reqs")) shouldBe false
    }

    @Test fun `footer surfaces failures`() {
        val r = runner()
        r.session.ledger.recordError()

        Prompt.footer(r) shouldContain "1 failed"
    }

    @Test fun `footer pins the tool count, since the banner scrolls away`() {
        val r = runner("model: m\nfunction_calling: true\nuse_tools: all\n")
        r.activeTools.map { it.name } shouldContainAll listOf("groovy_eval", "exec", "read_to_var")

        // Derived, not written down: the tool set grows, and a hard-coded count
        // turns every new tool into a failure in a test about the footer.
        Prompt.footer(r) shouldContain "${r.activeTools.size} tools"
    }

    @Test fun `footer says so in amber when no tools are enabled`() {
        // The failure this guards against: the model truthfully reports it cannot
        // run anything and nothing on screen explains why.
        val footer = Prompt.footer(runner("model: m\n"))

        footer shouldContain "no tools"
        footer shouldContain escapeOf(Ansi.Color.Yellow)
    }

    @Test fun `footer is bare before the first turn`() {
        val footer = Prompt.footer(runner())
        footer shouldContain "qwen35-122b"
        (footer.contains("ctx")) shouldBe false
        (footer.contains("reqs")) shouldBe false
    }

    @Test fun `footer carries the build version, since there is no banner to state it`() {
        // The framed layout prints nothing above the upper rule, so "which build is
        // this?" has to be answerable from the footer.
        Prompt.footer(runner()) shouldContain kotllm.util.BuildInfo.version
    }

    @Test fun `footer carries the help hint the banner used to`() {
        Prompt.footer(runner()) shouldContain "/help"
    }

    @Test fun `a narrow window drops the expendable elements, not the volatile ones`() {
        val r = runner("model: m\nfunction_calling: true\nuse_tools: all\n")
        record(r, 18_400, 2_300)

        fun plain(width: Int): String = Ansi.plain(Prompt.footer(r, width))

        // Wide enough for everything.
        plain(200) shouldContain "/help"
        plain(200) shouldContain kotllm.util.BuildInfo.version

        // The hint goes first, then the version, then the session totals. Model,
        // tool state and context pressure survive to the end: they are why anyone
        // looks at the footer.
        val narrow = plain(46)
        (narrow.contains("/help")) shouldBe false
        (narrow.contains(kotllm.util.BuildInfo.version)) shouldBe false
        narrow shouldContain "qwen35-122b"
        narrow shouldContain "${r.activeTools.size} tools"
        narrow shouldContain "ctx"
        (narrow.length <= 46) shouldBe true
    }

    @Test fun `an unfittable footer keeps what it must rather than looping`() {
        // Narrower than the model name alone: nothing droppable is left, so it
        // overflows instead of dropping something load-bearing or hanging.
        val r = runner()
        record(r, 100, 10)

        val plain = Ansi.plain(Prompt.footer(r, 5))
        plain shouldContain "qwen35-122b"
    }

    @Test fun `KOTLLM_CHROME=plain degrades to the single-line prompt`() {
        // The escape hatch for a terminal that advertises change_scroll_region and
        // then ignores it: the pinned rows land on the input line and nothing here
        // can detect that in advance.
        withTerminal { terminal, _ ->
            System.setProperty("kotllm.chrome", "plain")
            try {
                val chrome = Chrome(terminal)
                chrome.framed shouldBe false
                (chrome.prompt(runner()).contains("\n")) shouldBe false
                shouldNotThrowAny { chrome.refresh(runner()) }
                shouldNotThrowAny { chrome.close() }
            } finally {
                System.clearProperty("kotllm.chrome")
            }
        }
    }

    @Test fun `an unrecognised KOTLLM_CHROME value leaves the framed layout alone`() {
        withTerminal { terminal, _ ->
            System.setProperty("kotllm.chrome", "sideways")
            try {
                Chrome(terminal).framed shouldBe true
            } finally {
                System.clearProperty("kotllm.chrome")
            }
        }
    }

    @Test fun `an overlay is unavailable without a pinned area, so callers can fall back`() {
        val out = ByteArrayOutputStream()
        val terminal = dumbTestTerminal(ByteArrayInputStream(ByteArray(0)), out)
        try {
            Chrome(terminal).overlay(listOf("a line"), "footer") shouldBe false
        } finally {
            terminal.close()
        }
    }

    /** A terminal primed with one keypress, which is what dismisses an overlay. */
    private fun <A> withDismissableTerminal(rows: Int = 24, f: (Terminal, ByteArrayOutputStream) -> A): A {
        val out = ByteArrayOutputStream()
        val terminal = testTerminal(
            ByteArrayInputStream(byteArrayOf(' '.code.toByte())), out, size = Size(80, rows)
        )
        try {
            return f(terminal, out)
        } finally {
            terminal.close()
        }
    }

    @Test fun `an overlay shows the report and dismisses to a restored screen`() {
        withDismissableTerminal { terminal, out ->
            val chrome = Chrome(terminal)
            chrome.overlay(listOf("tokens   1,234", "context  7%"), "the footer") shouldBe true
            terminal.flush()

            val screen = rendered(out)
            screen shouldContain "tokens   1,234"
            screen shouldContain "press any key to dismiss"
            // The footer is repainted after dismissal, whichever path drew the panel.
            screen shouldContain "the footer"
        }
    }

    @Test fun `the overlay borrows the alternate screen, so dismissing it is not destructive`() {
        // The bug: the panel was painted by growing JLine's pinned region to most
        // of the window, which scrolls the transcript off to make room and does
        // not scroll it back when the region shrinks again. `/status` is thirty
        // lines long, so dismissing it left a black screen. The alternate screen
        // is what `less` uses precisely because leaving it restores the original
        // byte for byte.
        withDismissableTerminal { terminal, out ->
            Chrome(terminal).overlay(listOf("a long report"), "footer") shouldBe true
            terminal.flush()

            val screen = rendered(out)
            val enter = terminal.getStringCapability(Capability.enter_ca_mode)
            val exit = terminal.getStringCapability(Capability.exit_ca_mode)
            (enter != null) shouldBe true
            // Both, and in that order: entering without leaving would strand the
            // session on the scratch buffer.
            (screen.indexOf("${Chrome.Esc}[?1049h") >= 0) shouldBe true
            (screen.indexOf("${Chrome.Esc}[?1049l") > screen.indexOf("${Chrome.Esc}[?1049h")) shouldBe true
            (exit != null) shouldBe true
        }
    }

    @Test fun `an overlay taller than the window is trimmed rather than corrupting it`() {
        withDismissableTerminal(rows = 10) { terminal, out ->
            val lines = (1..40).map { i -> "line-$i" }
            Chrome(terminal).overlay(lines, "footer") shouldBe true
            terminal.flush()

            val screen = rendered(out)
            screen shouldContain "more line(s)"
            (screen.contains("line-40")) shouldBe false
        }
    }

    @Test fun `the rule spans the terminal width, within sane bounds`() {
        fun ruleLength(width: Int): Int = Ansi.plain(Prompt.divider(width)).length

        ruleLength(80) shouldBe 80
        ruleLength(0) shouldBe 20    // clamped up; a zero-width rule is invisible
        ruleLength(5000) shouldBe 200 // clamped down
    }

    @Test fun `the rule stops one column short, so the terminal cannot auto-wrap`() {
        // A rule filling the width leaves the cursor in the last column and the
        // next character wraps, which desyncs row accounting and leaves stray
        // blank lines between the rule and the caret.
        withTerminal { terminal, _ ->
            Chrome(terminal).width shouldBe 79 // terminal is 80 wide
        }
    }

    @Test fun `erasing the echoed prompt accounts for a wrapped input line`() {
        withTerminal { terminal, out ->
            val chrome = Chrome(terminal)
            chrome.refresh(runner())
            out.reset()

            // 80-column terminal: a 200-character command occupies three rows for
            // the caret line, plus one for the rule above it.
            chrome.eraseEchoedPrompt("x".repeat(200))
            terminal.flush()

            val screen = rendered(out)
            screen shouldContain "[4A"                     // up to the top of the block
            clearedRows(screen) shouldBe 4   // one clear per row
        }
    }

    @Test fun `erasing a short command clears exactly two rows and returns to the top`() {
        withTerminal { terminal, out ->
            val chrome = Chrome(terminal)
            chrome.refresh(runner())
            out.reset()

            chrome.eraseEchoedPrompt("/status")
            terminal.flush()

            val screen = rendered(out)
            screen shouldContain "[2A"
            clearedRows(screen) shouldBe 2
        }
    }

    @Test fun `erasing never clears past the reserved rows`() {
        // `ED 0` clears to the end of the *screen*, which wipes the pinned rule and
        // footer. Repainting them then needs Status.reset(), which addresses the
        // cursor below the scroll region and leaves it there -- and a line feed
        // outside the region does not scroll, so every later line overwrote the
        // bottom row. Reproduced on a real pty; the cure is to clear only our rows.
        withTerminal { terminal, out ->
            val chrome = Chrome(terminal)
            chrome.refresh(runner())
            out.reset()

            chrome.eraseEchoedPrompt("/status")
            terminal.flush()

            val screen = rendered(out)
            (screen.contains("[0J")) shouldBe false // erase to end of screen
            (screen.contains("[J")) shouldBe false  // its default-parameter spelling
            (screen.contains("r")) shouldBe false   // no scroll-region change, so no reset()
        }
    }

    @Test fun `suspend and restore track whether the pinned rows are released`() {
        withTerminal { terminal, _ ->
            val chrome = Chrome(terminal)
            chrome.refresh(runner())

            chrome.suspended shouldBe false
            chrome.suspend()
            chrome.suspended shouldBe true
            chrome.restore()
            chrome.suspended shouldBe false
        }
    }

    @Test fun `whilePrinting releases the pinned rows for the duration`() {
        // Printing underneath an active status area corrupts the reserved rows;
        // the symptom was the footer migrating up the screen and etching itself
        // into the scrollback after /help printed its table.
        withTerminal { terminal, _ ->
            val chrome = Chrome(terminal)
            chrome.refresh(runner())

            var observed = false
            chrome.whilePrinting { observed = chrome.suspended }

            observed shouldBe true
            chrome.suspended shouldBe false // and restored afterwards
        }
    }

    @Test fun `whilePrinting restores even when the body throws`() {
        withTerminal { terminal, _ ->
            val chrome = Chrome(terminal)
            chrome.refresh(runner())

            shouldThrow<RuntimeException> { chrome.whilePrinting { throw RuntimeException("x") } }
            chrome.suspended shouldBe false
        }
    }

    @Test fun `whilePrinting leaves an already-suspended region alone`() {
        withTerminal { terminal, _ ->
            val chrome = Chrome(terminal)
            chrome.refresh(runner())
            chrome.suspend()

            chrome.whilePrinting { }
            // Not spuriously restored by an inner call.
            chrome.suspended shouldBe true
        }
    }

    @Test fun `erasing is a no-op without a pinned area, rather than emitting escapes`() {
        val out = ByteArrayOutputStream()
        val terminal = dumbTestTerminal(ByteArrayInputStream(ByteArray(0)), out)
        try {
            val chrome = Chrome(terminal)
            out.reset()
            chrome.eraseEchoedPrompt("/status")
            terminal.flush()

            (rendered(out).contains("[2K")) shouldBe false
        } finally {
            terminal.close()
        }
    }

    @Test fun `chrome width and prompt adapt dynamically when terminal geometry changes`() {
        withTerminal { terminal, _ ->
            val chrome = Chrome(terminal)
            val r = runner()
            chrome.width shouldBe 79 // initial 80x24

            terminal.setSize(Size(120, 30))
            chrome.width shouldBe 119
            val widePrompt = chrome.prompt(r)
            Ansi.plain(widePrompt.split("\n")[0]).length shouldBe 119

            terminal.setSize(Size(60, 20))
            chrome.width shouldBe 59
            val narrowPrompt = chrome.prompt(r)
            Ansi.plain(narrowPrompt.split("\n")[0]).length shouldBe 59
        }
    }

    @Test fun `ReplLineReader handles WINCH signal and adapts prompt and status`() {
        withTerminal { terminal, _ ->
            val chrome = Chrome(terminal)
            val r = runner()
            val reader = ReplLineReader(terminal, r, chrome)

            val initialSize = Size(80, 24)
            terminal.setSize(initialSize)
            val initialPrompt = chrome.prompt(r)
            reader.setPrompt(initialPrompt)

            val newSize = Size(100, 35)
            terminal.setSize(newSize)

            shouldNotThrowAny {
                terminal.raise(Terminal.Signal.WINCH)
            }
        }
    }

    @Test fun `dockBottom scrolls the screen clear before addressing the row`() {
        // Addressing the cursor on its own moves it over whatever the shell left
        // on screen, so the prompt was drawn on top of somebody else's output and
        // the rules bracketed half a screen of it. A screenful of newlines first
        // means there is nothing left to overwrite.
        withTerminal { terminal, out ->
            val chrome = Chrome(terminal)
            chrome.dockBottom()
            terminal.flush()

            val screen = rendered(out)
            (screen.count { it == '\n' } >= 24) shouldBe true // one screenful, 80x24 terminal
            // 80x24 -> targetRow = 20 (24 - 2 reserved - 2 prompt), 1-based CUP row 21
            screen shouldContain "[21;1H"
            (screen.indexOf("\n") < screen.indexOf("[21;1H")) shouldBe true
        }
    }

    @Test fun `dockBottom leaves room above the input line for a banner`() {
        withTerminal { terminal, out ->
            Chrome(terminal).dockBottom(rowsAbove = 2)
            terminal.flush()

            // Two fewer rows down, so the banner it is about to print does not push
            // the caret into the pinned region.
            rendered(out) shouldContain "[19;1H"
        }
    }

    @Test fun `dockBottom is a safe no-op on a small terminal`() {
        withTerminal { terminal, out ->
            val chrome = Chrome(terminal)
            terminal.setSize(Size(80, 4))
            // Drain before resetting the sink: anything the constructor wrote is
            // still sitting in the terminal's buffer and would otherwise arrive
            // on the flush below, and be read as output from dockBottom.
            terminal.flush()
            out.reset()
            chrome.dockBottom()
            terminal.flush()

            rendered(out).shouldBeEmpty()
        }
    }

    @Test fun `prompt echo rows count the rule, the caret line and every wrap of it`() {
        withTerminal { terminal, _ ->
            val chrome = Chrome(terminal) // 80 columns

            chrome.echoedPromptRows("") shouldBe 2          // rule + caret
            chrome.echoedPromptRows("/status") shouldBe 2
            chrome.echoedPromptRows("x".repeat(78)) shouldBe 2    // caret + 78 = exactly one row
            chrome.echoedPromptRows("x".repeat(79)) shouldBe 3    // wraps once
            chrome.echoedPromptRows("x".repeat(200)) shouldBe 4
        }
    }

    @Test fun `continuation rows carry no rule, since the fence draws one only once`() {
        withTerminal { terminal, _ ->
            val chrome = Chrome(terminal)
            val caret = Glyphs.ellipsis.length + 1

            chrome.continuationRows("a line") shouldBe 1
            chrome.continuationRows("x".repeat(80 - caret)) shouldBe 1
            chrome.continuationRows("x".repeat(81 - caret)) shouldBe 2
        }
    }

    @Test fun `erasing rows is a no-op for zero, so an abandoned line eats nothing`() {
        // Ctrl-C at the prompt reports no rows: how much the editor left on screen
        // depends on what had been typed, and over-erasing would eat the transcript.
        withTerminal { terminal, out ->
            val chrome = Chrome(terminal)
            chrome.refresh(runner())
            out.reset()

            chrome.eraseRows(0)
            terminal.flush()

            (rendered(out).contains("[2K")) shouldBe false
        }
    }

    @Test fun `repl redraw clears screen and docks prompt at bottom when session is empty`() {
        withTerminal { terminal, out ->
            val r = runner()
            val chrome = Chrome(terminal)
            val reader = ReplLineReader(terminal, r, chrome)
            val repl = Repl(r, terminal, chrome, reader)

            out.reset()
            repl.redraw()
            terminal.flush()

            val screen = rendered(out)
            (screen.contains("[2J") || screen.contains("[H")) shouldBe true
            // targetRow = 20 (24 - 2 reserved - 2 prompt), 1-based CUP row 21
            screen shouldContain "[21;1H"
            // Pinned status is repainted
            screen shouldContain "qwen35-122b"
        }
    }

    @Test fun `repl redraw replays previous turns snug directly above the prompt rule`() {
        withTerminal { terminal, out ->
            val r = runner()
            r.session.append(Message.user("what is 2+2?"))
            r.session.append(Message.assistant("4"))
            record(r, 100, 10)

            val chrome = Chrome(terminal)
            val reader = ReplLineReader(terminal, r, chrome)
            val repl = Repl(r, terminal, chrome, reader)

            out.reset()
            repl.redraw()
            terminal.flush()

            val screen = rendered(out)
            screen shouldContain "what is 2+2?"
            screen shouldContain "4"
            screen shouldContain "Processed for"
            screen shouldContain "[21;1H"
            screen shouldContain "qwen35-122b"
        }
    }

    @Test fun `overlay command like status triggers redraw and leaves prompt snug at bottom`() {
        val inputStr = "/status\n"
        val `in` = ByteArrayInputStream(inputStr.toByteArray(StandardCharsets.UTF_8))
        val out = ByteArrayOutputStream()
        val terminal = testTerminal(`in`, out, size = Size(80, 24))
        try {
            val r = runner()
            val chrome = Chrome(terminal)
            val reader = ReplLineReader(terminal, r, chrome)
            val repl = Repl(r, terminal, chrome, reader)

            r.session.append(Message.user("hi"))
            r.session.append(Message.assistant("hello"))
            record(r, 50, 5)

            var overlaid = false
            chrome.suspend()
            Commands.dispatch(
                r,
                "/status",
                null,
                { lines ->
                    chrome.restore()
                    overlaid = chrome.overlay(lines, Prompt.footer(r, chrome.width))
                    if (!overlaid) chrome.suspend()
                    overlaid
                }
            )
            overlaid shouldBe true
            out.reset()
            if (overlaid) {
                chrome.eraseRows(chrome.wrapRows(Chrome.CaretCols + "/status".length))
                repl.redraw()
            }
            terminal.flush()

            val screen = rendered(out)
            screen shouldContain "[21;1H"
            screen shouldContain "qwen35-122b"
        } finally {
            terminal.close()
        }
    }

    @Test fun `typing slash opens menu and backspace restores original prompt height`() {
        // Input: "/" (opens completion menu), "\u007f" (backspace deletes slash and closes menu), "\n" (enter empty line)
        val inputStr = "/\u007f\n"
        val inStream = ByteArrayInputStream(inputStr.toByteArray(StandardCharsets.UTF_8))
        val out = ByteArrayOutputStream()
        val terminal = testTerminal(inStream, out, size = Size(80, 24))
        try {
            val r = runner()
            val chrome = Chrome(terminal)
            val reader = ReplLineReader(terminal, r, chrome)
            val repl = Repl(r, terminal, chrome, reader)

            chrome.refresh(r)
            chrome.dockBottom()

            out.reset()
            val line = reader.readLine(chrome.prompt(r))
            line shouldBe ""

            val screen = rendered(out)
            // Screen was redrawn to original height on backspace:
            screen shouldContain "[21;1H"
            screen shouldContain "qwen35-122b"
        } finally {
            terminal.close()
        }
    }
}
