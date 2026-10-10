package kotllm.client

import java.io.IOException

/**
 * Retry policy for chat calls.
 *
 * Databricks serving endpoints scale to zero. A cold endpoint answers 503 for
 * the first few seconds, which is not an error the user should have to see or
 * re-issue by hand. Rate limits and gateway timeouts are likewise transient.
 * A 4xx that means the request itself is wrong is never retried.
 */
object Retry {

    fun retryableStatus(status: Int): Boolean =
        status == 408 || status == 429 || status >= 500

    // In Scala this was `def apply[T](attempts, base, onRetry)(op: => T)` --
    // two parameter lists, so the by-name body could be passed in braces.
    //
    // **Kotlin has no multiple parameter lists.** They collapse into one, and
    // the only way to keep the `Retry(n) { ... }` call shape is to put the
    // lambda last, where trailing-lambda syntax picks it up. The by-name
    // `=> T` becomes `() -> T` and the body is invoked explicitly as `op()`.
    operator fun <T> invoke(
        attempts: Int,
        baseDelayMillis: Long = 600L,
        onRetry: (Int, String) -> Unit = { _, _ -> },
        op: () -> T
    ): T {
        val total = maxOf(1, attempts)
        var attempt = 0
        var lastError: Throwable? = null

        while (attempt < total) {
            attempt += 1
            try {
                return op()
            } catch (e: ChatFailure) {
                if (e.retryable && attempt < total) {
                    lastError = e
                    val label = if (e.rateLimited) "rate limited (HTTP 429)" else "HTTP ${e.status}"
                    onRetry(attempt, label)
                    // The provider's Retry-After wins over our backoff curve: it knows
                    // when the window actually reopens and we are only guessing.
                    if (e.retryAfterSeconds != null) {
                        sleepMillis(minOf(e.retryAfterSeconds.toLong() * 1000L, MaxRetryAfterMillis))
                    } else {
                        sleep(baseDelayMillis, attempt)
                    }
                } else {
                    throw e
                }
            } catch (e: IOException) {
                if (attempt < total) {
                    lastError = e
                    onRetry(attempt, e.message ?: e::class.simpleName ?: "error")
                    sleep(baseDelayMillis, attempt)
                } else {
                    throw e
                }
            }
        }
        throw lastError!!
    }

    /**
     * Cap on an honoured Retry-After, so a provider asking for ten minutes does
     * not silently hang the REPL. Past this we fail and let the user decide.
     */
    private val MaxRetryAfterMillis = 60_000L

    /**
     * Exponential backoff, capped so a cold start does not stall for minutes.
     */
    private fun sleep(base: Long, attempt: Int): Unit =
        sleepMillis(minOf(base * (1L shl (attempt - 1)), 5000L))

    private fun sleepMillis(delay: Long): Unit =
        try {
            Thread.sleep(delay)
        } catch (e: InterruptedException) {
            Thread.currentThread().interrupt()
        }
}
