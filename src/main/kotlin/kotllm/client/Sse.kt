package kotllm.client

/**
 * Frames a `text/event-stream` body into the payload of each `data:` event.
 *
 * Deliberately line-oriented and push-driven so the caller can stay lazy over
 * a live socket and bail out the moment the user aborts, rather than
 * collecting the whole stream first.
 */
object Sse {

    const val DoneMarker = "[DONE]"

    /**
     * Accumulates lines until an event is complete.
     *
     * Per the SSE spec: `data:` lines accumulate (one optional leading space is
     * stripped), a blank line dispatches the event, lines starting with `:` are
     * comments, and other field names (`event:`, `id:`, `retry:`) are ignored
     * because no LLM gateway uses them meaningfully.
     */
    class Framer {
        private val data = StringBuilder()
        private var finished = false

        val isFinished: Boolean get() = finished

        /**
         * Returns the event payload, if this line completed one.
         */
        fun push(line: String): String? {
            if (finished) return null
            val trimmed = stripTrailingCr(line)

            return when {
                trimmed.isEmpty() -> dispatch()
                trimmed.startsWith(":") -> null
                trimmed.startsWith("data:") -> {
                    val value = trimmed.substring(5)
                    if (data.isNotEmpty()) data.append('\n')
                    data.append(if (value.startsWith(" ")) value.substring(1) else value)
                    null
                }
                else -> null
            }
        }

        /**
         * Dispatches a trailing event on a stream that ended without a final
         * blank line. Some gateways close the socket abruptly.
         */
        fun flush(): String? = if (finished) null else dispatch()

        private fun dispatch(): String? {
            if (data.isEmpty()) return null
            val payload = data.toString()
            data.setLength(0)
            return if (payload.trim() == DoneMarker) {
                finished = true
                null
            } else {
                payload
            }
        }

        private fun stripTrailingCr(s: String): String =
            if (s.endsWith("\r")) s.substring(0, s.length - 1) else s
    }

    /**
     * Drives a framer over `lines`, invoking `f` for each event payload.
     *
     * Stops early once `[DONE]` arrives or `continue` goes false, which is how
     * Ctrl-C cancels a generation without waiting for the server.
     */
    fun foreach(
        lines: Iterator<String>,
        // Renamed from `continue`, which is a Kotlin keyword and cannot
        // be a parameter name. Scala allows it.
        keepGoing: () -> Boolean = { true },
        f: (String) -> Unit
    ) {
        val framer = Framer()
        while (lines.hasNext() && !framer.isFinished && keepGoing()) {
            framer.push(lines.next())?.let(f)
        }
        if (!framer.isFinished && keepGoing()) {
            framer.flush()?.let(f)
        }
    }

    /**
     * Eager variant, for tests.
     */
    fun events(lines: Iterator<String>): List<String> {
        val out = mutableListOf<String>()
        foreach(lines, f = { out.add(it) })
        return out
    }
}
