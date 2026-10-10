package kotllm.util

/** What one completed turn cost.
  *
  * Recorded per turn rather than folded into running totals so `/status` can
  * show usage *over time* -- the shape of the curve is what tells you a session
  * is about to hit the context wall, and a single cumulative counter cannot.
  */
data class TurnMetrics(
    val index: Int,
    val startedAt: Long,
    val model: String,
    val promptTokens: Int,
    val completionTokens: Int,
    val reasoningChars: Int,
    val latencyMs: Long,
    val timeToFirstTokenMs: Long?,
    val toolCalls: List<String>,
    val truncated: Boolean,
    val aborted: Boolean,
    /** True when this turn ran against a local server, so it cost no money.
      *
      * Recorded per turn for the same reason `model` is: `/model` can switch
      * from a local endpoint to a hosted one mid-session, and costing the
      * whole session by whatever happens to be selected at report time would
      * be wrong in both directions. Defaulted so every existing positional
      * construction still compiles. */
    val local: Boolean = false
) {
  fun totalTokens(): Int = promptTokens + completionTokens

  /** Output tokens per second, excluding the wait for the first token so this
    * measures generation speed rather than queueing. */
  fun outputTokensPerSecond(): Double? {
    val generating = latencyMs - (timeToFirstTokenMs ?: 0L)
    return if (generating > 0 && completionTokens > 0) {
      completionTokens * 1000.0 / generating
    } else {
      null
    }
  }
}

/** What one executed tool call cost.
  *
  * The endpoint never reports per-call token counts, so the exact numbers here
  * are the *character* counts and the token figures are estimates. What is
  * measured exactly is the input-token growth across the following request --
  * see `TokenLedger.toolFeedbackTokens` -- which is the real price of handing
  * results back to the model.
  *
  * `durationMs` covers execution only; approval wait is tracked separately,
  * because a slow human is not a slow tool. */
data class ToolCallMetrics(
    val index: Int,
    val requestIndex: Int,
    val name: String,
    val startedAt: Long,
    val endedAt: Long,
    val approvalWaitMs: Long,
    val argumentChars: Int,
    val resultChars: Int,
    val estimatedArgumentTokens: Int,
    val estimatedResultTokens: Int,
    val approved: Boolean,
    val isError: Boolean
) {
  fun durationMs(): Long = kotlin.math.max(0L, endedAt - startedAt)
}

/** Per-tool rollup for `/status`. */
data class ToolRollup(
    val name: String,
    val calls: Int,
    val errors: Int,
    val refused: Int,
    val totalMillis: Long,
    val slowestMillis: Long,
    val estimatedResultTokens: Int
) {
  fun averageMillis(): Long = if (calls == 0) 0L else totalMillis / calls
}

/** Per-session usage history and derived metrics.
  *
  * Survives `/empty`: clearing the conversation resets what gets sent to the
  * endpoint, not what the session has already spent. The prompt-token
  * sparkline showing the drop after a clear is useful information.
  */
class TokenLedger {

  private val turns = mutableListOf<TurnMetrics>()
  private val runs = mutableListOf<ToolCallMetrics>()
  private var errors = 0
  private val started = System.currentTimeMillis()

  fun record(m: TurnMetrics) {
    turns.add(m)
  }

  fun recordError() {
    errors += 1
  }

  /** Records an executed (or refused) tool call. */
  fun recordToolRun(m: ToolCallMetrics) {
    runs.add(m)
  }

  fun toolCalls(): List<ToolCallMetrics> = runs.toList()

  fun nextToolIndex(): Int = runs.size + 1

  /** Tool calls that ran but reported a failure. Counted separately from
    * request errors: a compile error the model then fixes is normal progress,
    * not a fault. */
  fun toolFailureCount(): Int = runs.count { m -> m.approved && m.isError }

  fun toolRefusedCount(): Int = runs.count { !it.approved }

  fun executedToolCalls(): Int = runs.size

  fun totalToolMillis(): Long = runs.map { it.durationMs() }.sum()

  fun totalApprovalWaitMillis(): Long = runs.map { it.approvalWaitMs }.sum()

  fun estimatedToolResultTokens(): Int = runs.map { it.estimatedResultTokens }.sum()

  /** Per-tool rollup, busiest first. */
  fun toolRollups(): List<ToolRollup> {
    val grouped = runs.groupBy { it.name }
    return grouped.map { (name, runs) ->
      ToolRollup(
        name = name,
        calls = runs.size,
        errors = runs.count { m -> m.approved && m.isError },
        refused = runs.count { !it.approved },
        totalMillis = runs.map { it.durationMs() }.sum(),
        slowestMillis = runs.map { it.durationMs() }.maxOrNull() ?: 0L,
        estimatedResultTokens = runs.map { it.estimatedResultTokens }.sum()
      )
    }
      .sortedWith(compareBy({ -it.calls }, { it.name }))
  }

  /** The measured input-token cost of feeding tool results back.
    *
    * Every request re-sends the conversation, so the growth in `prompt_tokens`
    * from one request to the next is the true cost of whatever was appended in
    * between -- the assistant's own tool-call message plus the results. Summed
    * only across requests that followed a tool call, so a plain conversational
    * turn does not inflate it. Exact, unlike the per-call estimates.
    */
  fun toolFeedbackTokens(): Int {
    val requestsAfterTool = runs.map { it.requestIndex }.toSet()
    return turns.windowed(2)
      .map { (before, after) ->
        if (requestsAfterTool.contains(before.index)) {
          kotlin.math.max(0, after.promptTokens - before.promptTokens)
        } else {
          0
        }
      }
      .sum()
  }

  fun all(): List<TurnMetrics> = turns.toList()

  fun isEmpty(): Boolean = turns.isEmpty()
  fun turnCount(): Int = turns.size
  fun errorCount(): Int = errors
  fun uptimeMs(): Long = System.currentTimeMillis() - started
  fun nextIndex(): Int = turns.size + 1

  fun lastUsage(): Pair<Int, Int>? = turns.lastOrNull()?.let { m -> m.promptTokens to m.completionTokens }

  fun totalPromptTokens(): Int = turns.map { it.promptTokens }.sum()
  fun totalCompletionTokens(): Int = turns.map { it.completionTokens }.sum()
  fun totalTokens(): Int = totalPromptTokens() + totalCompletionTokens()

  /** Prompt tokens per turn -- the series that matters for context pressure,
    * since every turn re-sends the whole history. */
  fun promptSeries(): List<Int> = turns.map { it.promptTokens }

  fun completionSeries(): List<Int> = turns.map { it.completionTokens }

  fun truncatedCount(): Int = turns.count { it.truncated }
  fun abortedCount(): Int = turns.count { it.aborted }

  /** Turns where the model produced reasoning but no visible answer. */
  fun emptyReplyCount(): Int =
    turns.count { m -> m.completionTokens > 0 && m.reasoningChars > 0 && m.truncated }

  fun toolCallCounts(): List<Pair<String, Int>> =
    turns.flatMap { it.toolCalls }
      .groupingBy { it }
      .eachCount()
      .map { (name, n) -> name to n }
      .sortedWith(compareBy({ -it.second }, { it.first }))

  fun totalToolCalls(): Int = turns.map { it.toolCalls.count() }.sum()

  fun averageLatencyMs(): Long? =
    if (turns.isEmpty()) null else turns.map { it.latencyMs }.sum() / turns.size

  fun averageTimeToFirstTokenMs(): Long? {
    val observed = turns.mapNotNull { it.timeToFirstTokenMs }
    return if (observed.isEmpty()) null else observed.sum() / observed.size
  }

  fun models(): List<String> = turns.map { it.model }.distinct()

  fun clear() {
    turns.clear()
    runs.clear()
    errors = 0
  }

  companion object {

    private fun Blocks(): List<Char> = Glyphs.ramp

    /** A unicode sparkline, scaled to the series maximum.
      *
      * Scaled from zero rather than from the series minimum: these are token
      * counts, where the distance from zero is the meaningful quantity, and a
      * min-scaled chart would make a flat high series look like a flat low one.
      */
    fun sparkline(values: List<Int>): String {
      return if (values.isEmpty()) ""
      else {
        val max = values.maxOrNull() ?: 0
        if (max <= 0) Blocks().first().toString().repeat(values.size)
        else {
          val blocks = Blocks()
          values.map { v ->
            val idx = ((v.toDouble() / max) * (blocks.size - 1)).toLong().toInt()
            blocks[kotlin.math.max(0, kotlin.math.min(blocks.size - 1, idx))]
          }.joinToString("")
        }
      }
    }

    /** A proportional bar, e.g. `████░░░░░░`. */
    fun bar(fraction: Double, width: Int = 20): String {
      val clamped = kotlin.math.max(0.0, kotlin.math.min(1.0, fraction))
      val filled = (clamped * width).toLong().toInt()
      return Glyphs.barFilled.repeat(filled) + Glyphs.barEmpty.repeat(width - filled)
    }

    /** Thousands separators, so six-figure context windows stay readable. */
    fun commas(n: Int): String = "%,d".format(n)

    /** Short form for the status footer, where horizontal space is scarce:
      * 19500 becomes "19.5k". Full precision stays in `/status`.
      *
      * One decimal below 100k, where it carries real information, and none above,
      * where "261k" reads better than "261.0k". Trailing ".0" is dropped so 5000
      * is "5k" rather than "5.0k".
      */
    fun compact(n: Long): String =
      when {
        n >= 1_000_000 -> trimZero("%.1f".format(n / 1000000.0)) + "M"
        n >= 100_000 -> "${(n + 500) / 1000}k"
        n >= 1_000 -> trimZero("%.1f".format(n / 1000.0)) + "k"
        else -> n.toString()
      }

    private fun trimZero(s: String): String =
      if (s.endsWith(".0")) s.dropLast(2) else s

    fun duration(ms: Long): String {
      val totalSeconds = ms / 1000
      val h = totalSeconds / 3600
      val m = (totalSeconds % 3600) / 60
      val s = totalSeconds % 60
      return when {
        h > 0 -> "%dh %02dm".format(h, m)
        m > 0 -> "%dm %02ds".format(m, s)
        else -> "${s}s"
      }
    }

    fun seconds(ms: Long): String = "%.1fs".format(ms / 1000.0)
  }
}
