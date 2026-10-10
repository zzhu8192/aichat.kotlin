package kotllm.repl

import org.jline.reader.LineReader
import org.jline.reader.impl.LineReaderImpl
import org.jline.terminal.Terminal
import org.jline.utils.InfoCmp.Capability

import kotllm.Runner
import kotllm.config.Paths

/** LineReader specialised for kotllm REPL.
 *
 * Overrides signal handling so that terminal resize (WINCH) dynamically
 * recomputes divider widths for both the upper prompt rule and the lower
 * pinned status footer, repositioning the prompt directly above the status
 * area at the bottom of the screen without line wrapping or drift.
 */
class ReplLineReader(
    terminal: Terminal,
    private val runner: Runner,
    private val chrome: Chrome,
    var onRedraw: (() -> Unit)? = null,
) : LineReaderImpl(terminal) {

    init {
        setVariable(LineReader.HISTORY_FILE, Paths.historyFile())
        setVariable(LineReader.SECONDARY_PROMPT_PATTERN, "")
        // Conversational prompts use '!' and '?' in normal prose and code;
        // csh-style history expansion (!1, !?, etc.) must not mangle input or fail.
        setOpt(LineReader.Option.DISABLE_EVENT_EXPANSION)
        setCompleter(
            ReplCompleter(
                { runner.config.listModels },
                { runner.rag?.names ?: emptyList() }
            )
        )
    }

    private var menuWasOpen = false

    override fun redisplay(flush: Boolean) {
        if (menuWasOpen && post == null) {
            menuWasOpen = false
            if (chrome.framed && !chrome.suspended) {
                onRedraw?.invoke()
                display.reset()
                redrawLine()
            }
        }
        super.redisplay(flush)
        if (post != null) {
            menuWasOpen = true
        }
    }

    override fun handleSignal(signal: Terminal.Signal) {
        if (signal == Terminal.Signal.WINCH) {
            doAutosuggestion = false
            size.copy(terminal.bufferSize)
            val cols = size.columns
            val rows = size.rows
            display.resize(rows, cols)

            val isMainPrompt = prompt != null && Prompt.isFramed(prompt.toString())

            if (chrome.framed && !chrome.suspended && isMainPrompt) {
                val caretCols = 2
                val inputLen = buf?.length() ?: 0
                val inputRows = maxOf(1, (caretCols + inputLen + cols - 1) / cols)
                val promptRows = 1 + inputRows
                val statusRows = 2
                val targetRow = maxOf(0, rows - statusRows - promptRows)

                try {
                    chrome.suspend()
                    val w = terminal.writer()
                    if (inputRows > 0) w.print("${Chrome.Esc}[${inputRows}A")
                    w.print("\r${Chrome.Esc}[0J")
                    w.flush()
                    terminal.puts(Capability.cursor_address, targetRow, 0)
                    terminal.flush()
                } catch (e: Exception) {
                } finally {
                    chrome.restore()
                }

                setPrompt(chrome.prompt(runner, cols))
                chrome.onResize(runner, size)
                redrawLine()
                redisplay()
            } else {
                if (isMainPrompt) setPrompt(chrome.prompt(runner, cols))
                if (chrome.framed) chrome.onResize(runner, size)
                super.handleSignal(signal)
            }
        } else {
            super.handleSignal(signal)
        }
    }
}
