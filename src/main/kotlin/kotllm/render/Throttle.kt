package kotllm.render

import java.io.PrintStream
import kotllm.util.Glyphs
import kotllm.util.TokenLedger

/** The "we are waiting on the rate limit" status line.
 *
 * A silent pause is indistinguishable from a hang, and a rate-limit wait can
 * run for most of a minute. Written to stderr on a single rewritten line so a
 * countdown does not scroll the transcript and piped stdout stays clean.
 */
object Throttle {

    private var width = 0

    private fun out(): PrintStream = System.err

    private fun interactive(): Boolean = System.console() != null

    /** Shows (or updates) the countdown. */
    // Explicit `: Unit` because the body has a bare `return`, which Kotlin 2.5
    // rejects in an expression body with an inferred type (KTLC-288).
    fun show(remainingMillis: Long, reason: String): Unit = synchronized(this) {
        if (!interactive()) return
        val text = "${Glyphs.ellipsis} rate limit: waiting ${TokenLedger.seconds(remainingMillis)} ($reason)"
        width = maxOf(width, text.length)
        out().print("\r${Ansi.Color.Yellow.on(text)}")
        out().flush()
    }

    /** Erases the line. Safe to call when nothing was shown. */
    fun clear() = synchronized(this) {
        if (width > 0) {
            out().print("\r" + (" ".repeat(width + 1)) + "\r")
            out().flush()
            width = 0
        }
    }
}
