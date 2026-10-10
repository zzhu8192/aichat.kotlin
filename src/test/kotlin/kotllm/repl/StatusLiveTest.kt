package kotllm.repl

import kotlin.test.Test
import org.junit.jupiter.api.Assumptions
import org.junit.jupiter.api.Tag
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.collections.shouldContain

import kotllm.*
import kotllm.config.Config
import kotllm.config.OnLimit
import kotllm.config.Paths
import kotllm.config.RateLimitConfig
import kotllm.tools.ApprovalMode

/** Drives two real turns and checks the metrics pipeline recorded them.
 *
 * The unit tests in StatusTest feed the ledger directly; this is the only
 * coverage that the wiring from a live response through Runner into the ledger
 * actually works -- token counts, latency, and time-to-first-token all come
 * from the wire.
 */
class StatusLiveTest {

    /** Mirrors the Scala suite's private `OptionOps.value`: the value, or a
     * loud assertion failure rather than a silent NPE. */
    private val <T> T?.value: T
        get() = this ?: throw AssertionError("expected a value")

    @Tag("Live")
    @Test fun `two live turns produce a populated status report`() {
        val runnerOutcome = Config.load(Paths.configFile())
            .flatMap { c -> Runner.build(c, null, "Answer in one short sentence.") }
        Assumptions.assumeTrue(runnerOutcome.isOk) {
            "no usable endpoint config: " + runnerOutcome.fold({ it }, { "" })
        }
        val runner = runnerOutcome.orNull()!!

        runner.ask("Name one benefit of the JVM.").isOk shouldBe true
        runner.ask("And one drawback?").isOk shouldBe true

        val ledger = runner.session.ledger
        ledger.turnCount() shouldBe 2
        (ledger.totalPromptTokens() > 0) shouldBe true
        (ledger.totalCompletionTokens() > 0) shouldBe true
        ledger.errorCount() shouldBe 0

        // The second turn re-sends the first exchange, so its prompt must be larger.
        val prompts = ledger.promptSeries()
        (prompts[1] > prompts[0]) shouldBe true

        // Streaming is on by default, so a first-token time must have been observed.
        (ledger.averageTimeToFirstTokenMs() != null) shouldBe true
        (ledger.averageLatencyMs().value > 0L) shouldBe true

        val report = Status.render(runner)
        report shouldContain "2 requests"
        report shouldContain "prompt growth"
        report shouldContain "context"

        println("\n" + report)
    }

    /** Checks the limiter is wired into the live request path and charged with
     * real usage.
     *
     * Deliberately does *not* assert that a wait occurred, because whether one
     * does is a race between turn latency and the window length. Both outcomes
     * were observed against this endpoint with an identical 100-token/10s
     * budget: three turns taking 65s never throttled, because each turn aged out
     * of the window before the next was submitted, while the same three turns
     * taking 21s throttled once for 5.4s. The lesson generalises -- an
     * interactive single-user session struggles to fill a short window, so local
     * throttling earns its keep on large windows with large prompts, or once
     * background turns run concurrently.
     *
     * The deterministic proof of the wait-and-settle loop is in
     * RunnerThrottleTest, against a stub transport.
     */
    @Tag("Live")
    @Test fun `the rate limit window is charged with real usage from live turns`() {
        val baseOutcome = Config.load(Paths.configFile())
        Assumptions.assumeTrue(baseOutcome.isOk) {
            "no usable endpoint config: " + baseOutcome.fold({ it }, { "" })
        }
        val base = baseOutcome.orNull()!!

        // 600/min over a 10s window = 100 input tokens per window. A couple of
        // short turns fit; the next has to wait for the window to roll.
        val throttled = base.copy(
            rateLimit = RateLimitConfig(600, null, null, null, 10, OnLimit.Wait),
            systemPrompt = "Reply with one very short sentence."
        )

        val runnerOutcome = Runner.build(throttled, null, throttled.systemPrompt)
        Assumptions.assumeTrue(runnerOutcome.isOk) {
            runnerOutcome.fold({ it }, { "" })
        }
        val runner = runnerOutcome.orNull()!!

        (runner.limiter != null) shouldBe true
        runner.limiter!!.config.inputBudget shouldBe 100L

        val started = System.currentTimeMillis()
        val replies = (1..3).map { i -> runner.ask("Name colour number $i.") }
        val elapsed = System.currentTimeMillis() - started

        replies.forEach { it.isOk shouldBe true }

        val w = runner.limiter!!.snapshot()
        println(
            "window after 3 turns in ${elapsed}ms: ${w.inputTokens} input tokens, " +
                "${w.throttleCount} throttles, ${w.totalWaitedMillis}ms waited"
        )

        // Real prompt tokens reached the window, and nothing was left stranded at
        // its pre-flight estimate.
        (w.inputTokens > 0L) shouldBe true
        w.pendingReservations shouldBe 0
        if (w.throttleCount > 0) (w.totalWaitedMillis > 0L) shouldBe true

        // The estimator had real usage to learn from.
        runner.tokenEstimator.isCalibrated shouldBe true

        val report = Status.render(runner)
        report shouldContain "rate window"
        println(System.lineSeparator() + report)
    }

    /** Tool metrics against the real endpoint and the real interpreter. */
    @Tag("Live")
    @Test fun `tool calls record duration and attributable token cost`() {
        val baseOutcome = Config.load(Paths.configFile())
        Assumptions.assumeTrue(baseOutcome.isOk) {
            "no usable endpoint config: " + baseOutcome.fold({ it }, { "" })
        }
        val base = baseOutcome.orNull()!!

        // Tools forced on here regardless of the local config, and approval set to
        // auto because a test has nobody to ask.
        val withTools = base.copy(
            functionCalling = true,
            useTools = "all",
            toolApproval = ApprovalMode.Auto,
            rateLimit = null
        )

        val runnerOutcome = Runner.build(withTools, null, "Use groovy_eval to compute things. Be brief.")
        Assumptions.assumeTrue(runnerOutcome.isOk) {
            runnerOutcome.fold({ it }, { "" })
        }
        val runner = runnerOutcome.orNull()!!

        try {
            runner.activeTools.map { it.name } shouldContain "groovy_eval"

            runner
                .ask("Compute the sum of the first 100 primes using groovy_eval, then report just the number.")
                .isOk shouldBe true

            val ledger = runner.session.ledger
            (ledger.executedToolCalls() > 0) shouldBe true

            val evals = ledger.toolRollups().find { it.name == "groovy_eval" }
            (evals != null) shouldBe true
            // A real compile-and-run is never instantaneous.
            (evals!!.totalMillis > 0L) shouldBe true
            ledger.toolCalls().all { it.resultChars > 0 } shouldBe true

            // Feeding the result back must have grown the prompt measurably, since a
            // follow-up request happened.
            if (ledger.turnCount() > 1) (ledger.toolFeedbackTokens() > 0) shouldBe true

            println(
                "${ledger.executedToolCalls()} tool calls in ${ledger.totalToolMillis()}ms; " +
                    "fed back ${ledger.toolFeedbackTokens()} input tokens (measured), " +
                    "~${ledger.estimatedToolResultTokens()} result tokens (estimated)"
            )
            println(System.lineSeparator() + Status.render(runner))
        } finally {
            runner.close()
        }
    }
}
