package kotllm.util

import kotlin.test.Test
import io.kotest.matchers.shouldBe
import io.kotest.matchers.comparables.shouldBeGreaterThan
import io.kotest.matchers.comparables.shouldBeLessThan
import io.kotest.matchers.comparables.shouldBeLessThanOrEqualTo
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.doubles.plusOrMinus
import kotllm.config.OnLimit
import kotllm.config.RateLimitConfig

class RateLimiterTest {

    /** Injected clock: these tests advance time rather than sleeping, so they are
     * deterministic and take no wall-clock time. */
    private class FakeClock(var now: Long = 1_000_000L) : () -> Long {
        override fun invoke(): Long = now
        fun advance(millis: Long) { now += millis }
    }

    private fun cfg(
        input: Int? = null,
        output: Int? = null,
        total: Int? = null,
        requests: Int? = null,
        windowSeconds: Int = 60,
        onLimit: OnLimit = OnLimit.Wait
    ) = RateLimitConfig(input, output, total, requests, windowSeconds, onLimit)

    private fun limiter(c: RateLimitConfig, clock: FakeClock) = RateLimiter(c, clock)

    @Test fun `admits a request that fits the budget`() {
        val clock = FakeClock()
        val l = limiter(cfg(input = 1000), clock)

        l.verdict(500) shouldBe Verdict.Admit
    }

    @Test fun `admits right up to the budget boundary`() {
        val clock = FakeClock()
        val l = limiter(cfg(input = 1000), clock)
        l.settle(l.reserve(600), 600, 0)

        l.verdict(400) shouldBe Verdict.Admit
        (l.verdict(401) is Verdict.Wait) shouldBe true
    }

    @Test fun `asks the caller to wait once the window is full`() {
        val clock = FakeClock()
        val l = limiter(cfg(input = 1000), clock)
        l.settle(l.reserve(900), 900, 0)

        when (val v = l.verdict(200)) {
            is Verdict.Wait -> {
                // The blocking entry was charged at t0, so it ages out a window later.
                v.millis shouldBeGreaterThan 59_000L
                v.millis shouldBeLessThanOrEqualTo 60_100L
                v.reason shouldContain "900/1000"
            }
            else -> throw AssertionError("expected a wait, got $v")
        }
    }

    @Test fun `waits only until enough of the oldest entries age out`() {
        val clock = FakeClock()
        val l = limiter(cfg(input = 1000), clock)

        l.settle(l.reserve(500), 500, 0) // t+0
        clock.advance(30_000)
        l.settle(l.reserve(400), 400, 0) // t+30s

        // Needs 100 freed; the first entry alone supplies it, and expires at t+60s.
        when (val v = l.verdict(200)) {
            is Verdict.Wait -> {
                v.millis shouldBeGreaterThan 29_000L
                v.millis shouldBeLessThan 31_000L
            }
            else -> throw AssertionError("expected a wait, got $v")
        }
    }

    @Test fun `admits again once the window has rolled past the old entries`() {
        val clock = FakeClock()
        val l = limiter(cfg(input = 1000), clock)
        l.settle(l.reserve(900), 900, 0)

        (l.verdict(500) is Verdict.Wait) shouldBe true
        clock.advance(60_001)
        l.verdict(500) shouldBe Verdict.Admit
    }

    @Test fun `reports Oversized when one request cannot fit the budget at all`() {
        val clock = FakeClock()
        val l = limiter(cfg(input = 1000), clock)

        // No amount of waiting helps, so this must not be reported as a wait.
        (l.verdict(5000) is Verdict.Oversized) shouldBe true
    }

    @Test fun `denies instead of waiting under on_limit error`() {
        val clock = FakeClock()
        val l = limiter(cfg(input = 1000, onLimit = OnLimit.Error), clock)
        l.settle(l.reserve(900), 900, 0)

        when (val v = l.verdict(500)) {
            is Verdict.Deny -> v.reason shouldContain "rate limit reached"
            else -> throw AssertionError("expected a denial, got $v")
        }
    }

    @Test fun `settling replaces the estimate, it does not add to it`() {
        val clock = FakeClock()
        val l = limiter(cfg(input = 1000), clock)

        val id = l.reserve(300)
        l.snapshot().inputTokens shouldBe 300
        l.settle(id, 250, 80)

        // Drift would compound across a session if this accumulated.
        l.snapshot().inputTokens shouldBe 250
        l.snapshot().outputTokens shouldBe 80
    }

    @Test fun `an under-estimate is corrected upward on settle`() {
        val clock = FakeClock()
        val l = limiter(cfg(input = 1000), clock)

        val id = l.reserve(100)
        l.settle(id, 950, 0)

        l.snapshot().inputTokens shouldBe 950
        (l.verdict(100) is Verdict.Wait) shouldBe true
    }

    @Test fun `release returns budget for a request that never reached the provider`() {
        val clock = FakeClock()
        val l = limiter(cfg(input = 1000), clock)

        val id = l.reserve(900)
        l.release(id)

        l.snapshot().inputTokens shouldBe 0
        l.verdict(900) shouldBe Verdict.Admit
    }

    @Test fun `output tokens throttle the next request, never the current one`() {
        val clock = FakeClock()
        val l = limiter(cfg(output = 1000), clock)

        // A pending request contributes no output, so it is always admitted...
        l.verdict(999999) shouldBe Verdict.Admit

        // ...but a previous turn's output can block the following one.
        l.settle(l.reserve(10), 10, 1200)
        (l.verdict(10) is Verdict.Wait) shouldBe true
    }

    @Test fun `a total budget counts prompt and completion together`() {
        val clock = FakeClock()
        val l = limiter(cfg(total = 1000), clock)
        l.settle(l.reserve(300), 300, 600) // 900 of 1000 consumed

        l.verdict(50) shouldBe Verdict.Admit
        (l.verdict(200) is Verdict.Wait) shouldBe true
    }

    @Test fun `a request budget limits call rate regardless of size`() {
        val clock = FakeClock()
        val l = limiter(cfg(requests = 2), clock)

        l.settle(l.reserve(1), 1, 1)
        l.settle(l.reserve(1), 1, 1)

        (l.verdict(1) is Verdict.Wait) shouldBe true
    }

    @Test fun `the tightest of several limits wins`() {
        val clock = FakeClock()
        val l = limiter(cfg(input = 10_000, requests = 1), clock)
        l.settle(l.reserve(10), 10, 0)

        // Tokens are nowhere near the cap, but the request budget is spent.
        (l.verdict(10) is Verdict.Wait) shouldBe true
    }

    @Test fun `limits scale pro rata for a non-minute window`() {
        val c = cfg(input = 1_000_000, windowSeconds = 30)
        c.inputBudget shouldBe 500_000L

        cfg(input = 1_000_000).inputBudget shouldBe 1_000_000L
    }

    @Test fun `snapshot reports when the window starts freeing up`() {
        val clock = FakeClock()
        val l = limiter(cfg(input = 1000), clock)
        l.settle(l.reserve(100), 100, 0)
        clock.advance(20_000)

        l.snapshot().oldestExpiresInMillis shouldBe 40_000L
    }

    @Test fun `snapshot pressure reflects the tightest dimension`() {
        val clock = FakeClock()
        val l = limiter(cfg(input = 1000, requests = 2), clock)
        l.settle(l.reserve(100), 100, 0) // 10% of tokens, 50% of requests

        l.snapshot().pressure()!! shouldBe (0.5 plusOrMinus 0.001)
    }

    @Test fun `snapshot counts unsettled reservations`() {
        val clock = FakeClock()
        val l = limiter(cfg(input = 1000), clock)
        val id = l.reserve(100)

        l.snapshot().pendingReservations shouldBe 1
        l.settle(id, 100, 0)
        l.snapshot().pendingReservations shouldBe 0
    }

    @Test fun `throttle waits are accumulated for reporting`() {
        val clock = FakeClock()
        val l = limiter(cfg(input = 1000), clock)
        l.recordWait(1500)
        l.recordWait(500)

        val w = l.snapshot()
        w.throttleCount shouldBe 2
        w.totalWaitedMillis shouldBe 2000
    }

    @Test fun `a config with no limits set is inactive`() {
        cfg().isActive shouldBe false
        cfg(input = 1).isActive shouldBe true
    }
}
