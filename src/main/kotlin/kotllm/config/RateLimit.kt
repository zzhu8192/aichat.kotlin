package kotllm.config

import kotllm.util.Conf

/** What to do when a request would breach the window. */
sealed interface OnLimit {

  /** Sleep until capacity frees up, showing a countdown. The default: a
    * throttled request is a scheduling problem, not an error.
    */
  data object Wait : OnLimit

  /** Fail immediately. For scripted use where a stalled process is worse than
    * a failed one.
    */
  data object Error : OnLimit

  companion object {
    fun parse(s: String): OnLimit? = when (s.trim().lowercase()) {
      "wait", "sleep", "block"  -> Wait
      "error", "fail", "reject" -> Error
      else                       -> null
    }

    fun name(o: OnLimit): String = when (o) {
      is Wait  -> "wait"
      is Error -> "error"
    }
  }
}

/** Client-side rate limiting over a rolling window.
  *
  * Provider limits are enforced with a 429, and discovering one by being
  * rejected wastes a round trip and, on a long conversation, re-sends a large
  * prompt to do it. Throttling locally turns that into a visible pause.
  *
  * All four dimensions are configurable and any subset may be unset, because
  * Databricks expresses limits per endpoint or per user, historically as
  * queries per minute and more recently as tokens per minute.
  *
  * The asymmetry is inherent, not a shortcut: input is checked *before* sending
  * from an estimate, since true prompt_tokens only comes back with the
  * response, while output can only be counted afterwards - so an output or
  * total limit throttles the *next* request, never the current one.
  */
data class RateLimitConfig(
    val inputTokensPerMinute: Int? = null,
    val outputTokensPerMinute: Int? = null,
    val totalTokensPerMinute: Int? = null,
    val requestsPerMinute: Int? = null,
    val windowSeconds: Int = DefaultWindowSeconds,
    val onLimit: OnLimit = OnLimit.Wait
) {

  val isActive: Boolean get() =
    inputTokensPerMinute != null || outputTokensPerMinute != null ||
      totalTokensPerMinute != null || requestsPerMinute != null

  val windowMillis: Long get() = windowSeconds.toLong() * 1000L

  /** Limits are quoted per minute; a non-60s window scales them pro rata so
    * window_seconds: 30 with 1M/min means 500k per 30s rather than 1M.
    */
  private fun scaled(perMinute: Int?): Long? =
    perMinute?.let { v -> maxOf(1L, (v.toLong() * windowSeconds) / 60L) }

  val inputBudget: Long? get() = scaled(inputTokensPerMinute)
  val outputBudget: Long? get() = scaled(outputTokensPerMinute)
  val totalBudget: Long? get() = scaled(totalTokensPerMinute)
  val requestBudget: Long? get() = scaled(requestsPerMinute)

  /** Quotes the configured per-minute numbers, and separately the budget they
    * scale to, so a non-60s window cannot be misread as the limit itself.
    */
  fun describe(): String {
    val parts = listOfNotNull(
      inputTokensPerMinute?.let { "$it input" },
      outputTokensPerMinute?.let { "$it output" },
      totalTokensPerMinute?.let { "$it total" },
      requestsPerMinute?.let { "$it requests" }
    )

    if (parts.isEmpty()) return "none"

    val perMinute = "${parts.joinToString(", ")} per minute"
    val effective =
      if (windowSeconds == 60) ""
      else {
        val budgets = listOfNotNull(
          inputBudget?.let { "$it input" },
          outputBudget?.let { "$it output" },
          totalBudget?.let { "$it total" },
          requestBudget?.let { "$it requests" }
        )
        " = ${budgets.joinToString(", ")} per ${windowSeconds}s window"
      }
    return "$perMinute$effective (on_limit: ${OnLimit.name(onLimit)})"
  }

  companion object {
    const val DefaultWindowSeconds = 60

    /** Accepts both the nested block and a bare integer shorthand:
      * rate_limit: 1000000 means that many input tokens per minute, which is
      * the only limit most users have to think about.
      */
    fun from(c: Conf, key: String): RateLimitConfig? {
      val block = c.sub(key)?.let { fromBlock(it) }
      if (block != null && block.isActive) return block

      val tokens = c.int(key)
      if (tokens != null) {
        val config = RateLimitConfig(
          inputTokensPerMinute = tokens,
          windowSeconds = DefaultWindowSeconds,
          onLimit = OnLimit.Wait
        )
        if (config.isActive) return config
      }

      return null
    }

    private fun fromBlock(b: Conf): RateLimitConfig =
      RateLimitConfig(
        inputTokensPerMinute = b.int("input_tokens_per_minute") ?: b.int("input_tpm"),
        outputTokensPerMinute = b.int("output_tokens_per_minute") ?: b.int("output_tpm"),
        totalTokensPerMinute = b.int("total_tokens_per_minute") ?: b.int("total_tpm"),
        requestsPerMinute = b.int("requests_per_minute") ?: b.int("rpm"),
        windowSeconds = b.int("window_seconds")?.takeIf { it > 0 } ?: DefaultWindowSeconds,
        onLimit = b.str("on_limit")?.let { OnLimit.parse(it) } ?: OnLimit.Wait
      )
  }
}
