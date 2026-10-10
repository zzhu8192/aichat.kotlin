package kotllm.util

import kotllm.*
import kotllm.config.OnLimit
import kotllm.config.RateLimitConfig

/** The limiter's answer to "may I send this?".
  *
  * Nested, as Scala's `object Verdict` namespaced them. Flattened to top
  * level these put `Wait`, `Deny` and `Admit` loose in `kotllm.util`, where
  * `Deny` reads as a peer of `kotllm.tools.Decision.Deny` and is not one.
  */
sealed interface Verdict {

    data object Admit : Verdict

    /** Capacity frees up in `millis`; the caller should sleep and re-ask. */
    data class Wait(val millis: Long, val reason: String) : Verdict

    /** `on_limit: error` and the window is full. */
    data class Deny(val reason: String) : Verdict

    /** The request alone exceeds the whole budget, so no amount of waiting
      * helps. Reported rather than silently stalling forever; the caller sends
      * anyway and lets the provider decide. */
    data class Oversized(val reason: String) : Verdict
}

/** A point-in-time view of the window, for `/status`. */
data class WindowSnapshot(
    val inputTokens: Long,
    val outputTokens: Long,
    val requests: Int,
    val windowSeconds: Int,
    val inputBudget: Long?,
    val outputBudget: Long?,
    val totalBudget: Long?,
    val requestBudget: Long?,
    val oldestExpiresInMillis: Long?,
    val pendingReservations: Int,
    val throttleCount: Int,
    val totalWaitedMillis: Long
) {
  fun totalTokens(): Long = inputTokens + outputTokens

  /** Fraction of the tightest configured token budget currently consumed. */
  fun pressure(): Double? {
    val candidates = listOfNotNull(
      inputBudget?.let { inputTokens.toDouble() / it },
      outputBudget?.let { outputTokens.toDouble() / it },
      totalBudget?.let { totalTokens().toDouble() / it },
      requestBudget?.let { requests.toDouble() / it }
    )
    return if (candidates.isEmpty()) null else candidates.maxOrNull()
  }
}

/** Rolling-window rate limiter.
  *
  * Reserve-then-settle rather than a single record call: input has to be
  * charged from an estimate *before* the request goes out, then corrected to
  * the true `prompt_tokens` once the response arrives. Without the correction
  * the window would drift by whatever the estimator got wrong, compounding over
  * a long session.
  *
  * The clock is injected so the tests can advance time instead of sleeping.
  */
class RateLimiter(
    val config: RateLimitConfig,
    val clock: () -> Long = { System.currentTimeMillis() }
) {

  private data class Entry(val at: Long, val input: Long, val output: Long, val settled: Boolean)

  private val lock = Any()
  private val entries = linkedMapOf<Long, Entry>()
  private var nextId = 0L

  private var throttles = 0
  private var waitedMs = 0L

  private fun windowMs(): Long = config.windowMillis

  /** Drops entries that have aged out of the window. */
  private fun prune(now: Long) {
    val cutoff = now - windowMs()
    entries.entries.removeAll { (_, e) -> e.at <= cutoff }
  }

  /** @param estimatedInput the caller's best guess at prompt tokens. */
  fun verdict(estimatedInput: Int): Verdict = synchronized(lock) {
    val now = clock()
    prune(now)

    val waits = listOf(
      waitFor(now, config.inputBudget, estimatedInput.toLong(), { it.input }, "input tokens"),
      // Output and total are charged retroactively, so the pending request adds
      // nothing to them here -- they can only be over from previous turns.
      waitFor(now, config.outputBudget, 0L, { it.output }, "output tokens"),
      waitFor(now, config.totalBudget, estimatedInput.toLong(), { it.input + it.output }, "total tokens"),
      waitFor(now, config.requestBudget, 1L, { 1L }, "requests")
    ).filterNotNull()

    if (waits.isEmpty()) return Verdict.Admit

    // Check for oversized errors
    val oversized = waits.filterIsInstance<Outcome.Err>().firstOrNull()?.message
    if (oversized != null) return Verdict.Oversized(oversized)

    // All are Ok, find the maximum wait
    val successes = waits.filterIsInstance<Outcome.Ok<Pair<Long, String>>>().map { it.value }
    val (millis, reason) = successes.maxByOrNull { it.first } ?: return Verdict.Admit

    return when (config.onLimit) {
      OnLimit.Error -> Verdict.Deny("rate limit reached: $reason")
      OnLimit.Wait -> Verdict.Wait(millis, reason)
    }
  }

  /** @return None if it fits, Some(Right(wait)) if waiting helps,
    *         Some(Left(reason)) if the request can never fit. */
  private fun waitFor(
      now: Long,
      budget: Long?,
      need: Long,
      charge: (Entry) -> Long,
      label: String
  ): Outcome<Pair<Long, String>>? =
    budget?.let { limit ->
      val used = entries.values.map { charge(it) }.sum()
      when {
        used + need <= limit -> null
        need > limit -> err("a single request needs $need $label but the budget is only $limit per ${config.windowSeconds}s")
        else -> {
          // Wait until enough of the oldest entries age out to make room.
          val required = used + need - limit
          var freed = 0L
          var until: Long? = null
          val ordered = entries.values.sortedBy { it.at }
          for (e in ordered) {
            freed += charge(e)
            if (freed >= required) {
              until = e.at + windowMs()
              break
            }
          }
          when {
            until != null -> {
              val millis = kotlin.math.max(0L, until - now) + 25L // clear the boundary
              ok(millis to "$used/$limit $label used in the last ${config.windowSeconds}s")
            }
            else -> {
              // Everything expiring is still not enough; only possible if
              // reservations are in flight.
              ok(windowMs() to "$used/$limit $label used, waiting for the window to clear")
            }
          }
        }
      }
    }

  /** Charges an estimate against the window and returns the reservation id. */
  fun reserve(estimatedInput: Int): Long = synchronized(lock) {
    val now = clock()
    prune(now)
    nextId += 1
    entries[nextId] = Entry(now, kotlin.math.max(0, estimatedInput).toLong(), 0L, settled = false)
    return nextId
  }

  /** Replaces the estimate with the endpoint's reported usage. */
  fun settle(id: Long, actualInput: Int, actualOutput: Int) {
    synchronized(lock) {
      entries[id]?.let { e ->
        entries[id] = e.copy(
          input = kotlin.math.max(0, actualInput).toLong(),
          output = kotlin.math.max(0, actualOutput).toLong(),
          settled = true
        )
      }
    }
  }

  /** Drops a reservation whose request never reached the provider, so a
    * connection failure does not eat budget. A request rejected *by* the
    * provider should be settled, not released -- it counted against the limit. */
  fun release(id: Long) {
    synchronized(lock) {
      entries.remove(id)
    }
  }

  fun recordWait(millis: Long) {
    synchronized(lock) {
      throttles += 1
      waitedMs += millis
    }
  }

  fun snapshot(): WindowSnapshot = synchronized(lock) {
    val now = clock()
    prune(now)
    return WindowSnapshot(
      inputTokens = entries.values.map { it.input }.sum(),
      outputTokens = entries.values.map { it.output }.sum(),
      requests = entries.size,
      windowSeconds = config.windowSeconds,
      inputBudget = config.inputBudget,
      outputBudget = config.outputBudget,
      totalBudget = config.totalBudget,
      requestBudget = config.requestBudget,
      oldestExpiresInMillis =
        entries.values.map { it.at }.minOrNull()?.let { oldest -> kotlin.math.max(0L, oldest + windowMs() - now) },
      pendingReservations = entries.values.count { !it.settled },
      throttleCount = throttles,
      totalWaitedMillis = waitedMs
    )
  }

  fun reset() {
    synchronized(lock) {
      entries.clear()
      throttles = 0
      waitedMs = 0L
    }
  }
}
