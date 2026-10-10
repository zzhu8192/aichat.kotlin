package kotllm.render

import java.io.PrintStream
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.jvm.Volatile

/** A spinner for the dead air between sending a request and the first token.
 *
 * Databricks endpoints can take seconds to schedule a cold model, so without
 * this the REPL looks hung. Writes to stderr so piping stdout stays clean.
 */
class Spinner(val label: String, val out: PrintStream = System.err) {

    private val running = AtomicBoolean(false)
    private var thread: Thread? = null
    @Volatile private var lastWidth = 0

    private val frames = kotllm.util.Glyphs.spinnerFrames

    /** No-ops when not attached to a terminal, so logs and pipes stay readable. */
    private val interactive = System.console() != null

    fun start() {
        if (!interactive || !running.compareAndSet(false, true)) return
        val t = Thread(Runnable {
            var i = 0
            var interrupted = false
            while (running.get() && !interrupted) {
                val text = "${frames[i % frames.size]} $label"
                lastWidth = text.length
                out.print("\r${Ansi.Color.DarkGray.on(text)}")
                out.flush()
                i += 1
                try {
                    Thread.sleep(80L)
                } catch (_: InterruptedException) {
                    interrupted = true
                }
            }
        }, "kotllm-spinner")
        t.isDaemon = true
        thread = t
        t.start()
    }

    /** Stops and erases the spinner line. Safe to call when never started. */
    fun stop() {
        if (!running.compareAndSet(true, false)) return
        thread?.interrupt()
        thread = null
        val width = lastWidth
        if (width > 0) {
            out.print("\r" + " ".repeat(width + 1) + "\r")
            out.flush()
            lastWidth = 0
        }
    }
}
