package kotllm

import kotlin.test.Test
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.doubles.plusOrMinus

import kotllm.*
import kotllm.client.*
import kotllm.config.*
import kotllm.util.Yaml

/** Exercises the throttle gate through [[Runner]] with a stub transport.
  *
  * Uses a one-second window so a real wait completes quickly, rather than
  * faking the clock: the point is to verify the actual sleep-and-recheck loop,
  * the reservation, and the settle-with-real-usage correction working together.
  */
class RunnerThrottleTest {

    /** Answers instantly, reporting usage derived from the request it was handed.
      *
      * Deriving prompt_tokens from the actual content rather than returning a
      * fixed number matters: a stub claiming 60 tokens for a five-character
      * prompt is an implausible ratio, which the estimator rightly discards, and
      * the throttle then never arms. A realistic four-chars-per-token stub keeps
      * the estimator, the reservation and the window consistent with each other.
      */
    private class StubClient(private val charsPerToken: Double = 4.0, private val outputTokens: Int = 20) : Client {
        var calls = 0
        override val id = "stub"

        private fun reply(req: ChatRequest): ChatResponse {
            calls += 1
            // Counted from the serialised request, as a real endpoint does: it sees
            // the JSON scaffolding and any tool schemas, not just message text.
            val chars = req.encode().toString().length
            val prompt = maxOf(1, Math.round(chars / charsPerToken).toInt())
            return ChatResponse(
                message = Message.assistant("ok"),
                finishReason = "stop",
                usage = Usage(prompt, outputTokens, prompt + outputTokens),
                model = "stub-model"
            )
        }

        override fun chat(req: ChatRequest): ChatResponse = reply(req)
        override fun chatStream(
            req: ChatRequest,
            onEvent: (StreamEvent) -> Unit,
            keepGoing: (() -> Boolean)?
        ): ChatResponse = reply(req)
    }

    /** ~300 characters, so one turn costs roughly 75 prompt tokens and a second
      * turn -- which re-sends the first -- costs roughly 150. */
    private val bigInput = "word ".repeat(60)

    private fun runner(yaml: String, client: Client): Runner {
        val config = Config.fromConf(Yaml.loadString(yaml))
        val cc = ClientConfig("openai-compatible", "c", "https://h/v1", "k", emptyList(), null, null)
        val model = ModelConfig("stub-model", 100000, null, supportsVision = false, supportsFunctionCalling = true)
        return Runner(TestIsolation.isolate(config), Session(), ModelTarget(cc, model), client)
    }

    /** 1200/min over a 10s window scales to a 200-token budget per window.
      *
      * Sized so one turn fits and two do not. A smaller budget would make turn
      * two -- which re-sends turn one -- exceed the entire window on its own,
      * which is the Oversized path, not the waiting path.
      *
      * The window is ten seconds rather than one because a turn costs a Kotlin
      * script compile: binding the prompt API and rendering `chat.md` are both
      * evaluated. Against a one-second window the first turn outlives its own
      * entry, which ages out before the second turn starts, and the gate then
      * correctly never arms. The budget is unchanged; only the wall clock it is
      * measured over is.
      */
    private val tightWindow =
        """model: stub-model
          |stream: false
          |rate_limit:
          |  input_tokens_per_minute: 1200
          |  window_seconds: 10
          |""".trimMargin()

    @Test fun `a request within budget is not delayed`() {
        val client = StubClient()
        val r = runner(tightWindow, client)

        // Rendering the prompt template starts the Kotlin compiler the first
        // time. That is not throttling, which is what this test is about, so it
        // is paid before the clock starts. In a real session the same work
        // happens on a warmup thread while the user is still typing.
        r.assembledPrompt

        val started = System.currentTimeMillis()
        r.ask("hello").isOk shouldBe true
        val elapsed = System.currentTimeMillis() - started

        // Generous on purpose: the bound only has to separate "did not wait for
        // the limiter" from a throttle measured in whole seconds.
        (elapsed < 5_000L) shouldBe true
        r.limiter!!.snapshot().throttleCount shouldBe 0
    }

    @Test fun `a second request that would breach the window waits, then succeeds`() {
        val client = StubClient()
        val r = runner(tightWindow, client)

        r.assembledPrompt // compiler startup, paid before the window matters

        r.ask(bigInput).isOk shouldBe true
        (r.limiter!!.snapshot().inputTokens > 50L) shouldBe true

        // The window is most of the way full, and turn two also re-sends turn one,
        // so the gate must hold until the first entry ages out of the window.
        val started = System.currentTimeMillis()
        r.ask(bigInput).isOk shouldBe true
        val elapsed = System.currentTimeMillis() - started

        (elapsed >= 500L) shouldBe true
        client.calls shouldBe 2

        val w = r.limiter!!.snapshot()
        w.throttleCount shouldBe 1
        (w.totalWaitedMillis > 0L) shouldBe true
    }

    @Test fun `the window is corrected to the reported usage, not left at the estimate`() {
        val client = StubClient(outputTokens = 7)
        val r = runner(tightWindow, client)

        val estimateBefore = r.tokenEstimator.estimate("hello".length, 0)
        val reported = r.ask("hello").orNull()!!.usage!!

        val w = r.limiter!!.snapshot()
        // Asserted against what the endpoint reported rather than a literal, which
        // is the actual invariant: the window must hold the reported usage.
        w.inputTokens shouldBe reported.promptTokens.toLong()
        w.outputTokens shouldBe 7L
        // ...and that is not what was reserved up front.
        (w.inputTokens.toInt() != estimateBefore) shouldBe true
        w.pendingReservations shouldBe 0 // settled, not left at the estimate
    }

    @Test fun `on_limit error fails fast instead of blocking`() {
        val yaml =
            """model: stub-model
              |stream: false
              |rate_limit:
              |  input_tokens_per_minute: 1200
              |  window_seconds: 10
              |  on_limit: error
              |""".trimMargin()
        val client = StubClient()
        val r = runner(yaml, client)

        r.assembledPrompt // compiler startup, paid before the window matters

        r.ask(bigInput).isOk shouldBe true

        val started = System.currentTimeMillis()
        val result = r.ask(bigInput)
        val elapsed = System.currentTimeMillis() - started

        (result is Outcome.Err) shouldBe true
        (result as Outcome.Err).message shouldContain "rate limit reached"
        // Only has to distinguish failing fast from waiting out the window.
        (elapsed < 5_000L) shouldBe true
        // The rejected turn must not have reached the transport.
        client.calls shouldBe 1
    }

    @Test fun `a request larger than the whole budget is sent anyway rather than stalling`() {
        // Waiting can never help, so blocking forever would be the wrong call.
        val yaml =
            """model: stub-model
              |stream: false
              |rate_limit:
              |  input_tokens_per_minute: 60
              |  window_seconds: 10
              |""".trimMargin()
        val client = StubClient()
        val r = runner(yaml, client)

        val started = System.currentTimeMillis()
        r.ask("x".repeat(20000)).isOk shouldBe true
        val elapsed = System.currentTimeMillis() - started

        (elapsed < 5_000L) shouldBe true
        client.calls shouldBe 1
    }

    @Test fun `no rate_limit configured means no limiter and no gate`() {
        val client = StubClient()
        val r = runner("model: stub-model\nstream: false\n", client)

        r.limiter shouldBe null
        r.ask("hello").isOk shouldBe true
    }

    @Test fun `the estimator learns the real chars-per-token ratio from usage`() {
        val client = StubClient(charsPerToken = 4.0)
        val r = runner("model: stub-model\nstream: false\n", client)

        r.tokenEstimator.isCalibrated shouldBe false
        (1..6).forEach { _ -> r.ask(bigInput).isOk shouldBe true }

        r.tokenEstimator.isCalibrated shouldBe true
        r.tokenEstimator.charsPerToken shouldBe (4.0 plusOrMinus 0.5)
    }

    @Test fun `a per-client rate limit overrides the global one`() {
        val config = Config.fromConf(Yaml.loadString(
            """model: stub-model
              |rate_limit:
              |  input_tokens_per_minute: 1000
              |""".trimMargin()))
        val cc = ClientConfig(
            "openai-compatible", "c", "https://h/v1", "k", emptyList(), null, null,
            rateLimit = RateLimitConfig(50, null, null, null, 60, OnLimit.Wait)
        )
        val model = ModelConfig("stub-model", null, null, supportsVision = false, supportsFunctionCalling = true)
        val r = Runner(TestIsolation.isolate(config), Session(), ModelTarget(cc, model), StubClient())

        r.limiter!!.config.inputTokensPerMinute shouldBe 50
    }
}
