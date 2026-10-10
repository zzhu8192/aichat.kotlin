package kotllm.repl

import kotlin.test.Test
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain

import kotllm.Runner
import kotllm.TestIsolation
import kotllm.client.ChatRequest
import kotllm.client.ChatResponse
import kotllm.client.Client
import kotllm.client.StreamEvent
import kotllm.config.ClientConfig
import kotllm.config.Config
import kotllm.config.ModelConfig
import kotllm.config.ModelTarget
import kotllm.config.Session
import kotllm.util.Glyphs
import kotllm.util.TokenLedger
import kotllm.util.ToolCallMetrics
import kotllm.util.TurnMetrics
import kotllm.util.Yaml

class StatusTest {

    /** Never called: `/status` reports on recorded history, so these tests drive
     * the ledger directly rather than faking a conversation. */
    private object UnusedClient : Client {
        override val id: String = "test"
        override fun chat(req: ChatRequest): ChatResponse = throw UnsupportedOperationException()
        override fun chatStream(
            req: ChatRequest,
            onEvent: (StreamEvent) -> Unit,
            keepGoing: (() -> Boolean)?
        ): ChatResponse = throw UnsupportedOperationException()
    }

    private fun runner(maxInputTokens: Int? = 261000): Runner {
        val config = Config.fromConf(Yaml.loadString("model: m\n"))
        val client = ClientConfig("openai-compatible", "c", "https://h/v1", "k", emptyList(), null, null)
        val model = ModelConfig("m", maxInputTokens, null, supportsVision = false, supportsFunctionCalling = true)
        return Runner(TestIsolation.isolate(config), Session(), ModelTarget(client, model), UnusedClient)
    }

    private fun record(
        r: Runner,
        prompt: Int,
        completion: Int,
        reasoning: Int = 0,
        tools: List<String> = emptyList(),
        truncated: Boolean = false
    ) {
        r.session.ledger.record(
            TurnMetrics(
                r.session.ledger.nextIndex(), 0L, "m", prompt, completion, reasoning,
                1500L, 500L, tools, truncated, aborted = false
            )
        )
    }

    @Test fun `reports plainly when nothing has happened yet`() {
        Status.render(runner()) shouldBe "no turns yet in this session"
    }

    @Test fun `reports cumulative tokens and the turn count`() {
        val r = runner()
        record(r, 100, 50)
        record(r, 200, 60)

        val out = Status.render(r)
        out shouldContain "2 requests"
        out shouldContain "300 in / 110 out / 410 total"
    }

    @Test fun `context pressure is measured from the last prompt, not the session total`() {
        val r = runner(maxInputTokens = 1000)
        record(r, 400, 10)
        record(r, 500, 10) // total tokens exceed 900, but the window holds 500

        val out = Status.render(r)
        out shouldContain "500 / 1,000"
        out shouldContain "50%"
    }

    @Test fun `omits the context bar when the model declares no window`() {
        val r = runner(maxInputTokens = null)
        record(r, 400, 10)

        // Scoped to the context line: the bar glyphs degrade to "#" and "." on a
        // terminal without unicode, and "." matches any latency figure, while "["
        // matches every ANSI escape elsewhere in the report.
        val contextLine = Status.render(r).lineSequence().find { it.startsWith("context") }!!
        contextLine shouldContain "400 tokens (window unknown)"
        (contextLine.contains("[")) shouldBe false
        (contextLine.contains("%")) shouldBe false
    }

    @Test fun `shows prompt growth as a sparkline once there are two turns`() {
        val r = runner()
        record(r, 100, 10)
        record(r, 900, 10)

        val out = Status.render(r)
        out shouldContain "prompt growth"
        // Expectation derived from the same renderer the report uses, so this test
        // does not break on a terminal that falls back to ASCII glyphs.
        out shouldContain TokenLedger.sparkline(listOf(100, 900))
        out shouldContain "(100 ${Glyphs.arrow} 900)"
    }

    @Test fun `omits the sparkline for a single turn, where a trend is meaningless`() {
        val r = runner()
        record(r, 100, 10)

        (Status.render(r).contains("prompt growth")) shouldBe false
    }

    private fun recordTool(
        r: Runner,
        name: String,
        durationMs: Long,
        requestIndex: Int = 1,
        resultChars: Int = 100,
        approved: Boolean = true,
        isError: Boolean = false,
        approvalWaitMs: Long = 0L
    ) {
        val start = 1_000_000L
        r.session.ledger.recordToolRun(
            ToolCallMetrics(
                index = r.session.ledger.nextToolIndex(),
                requestIndex = requestIndex,
                name = name,
                startedAt = start,
                endedAt = start + durationMs,
                approvalWaitMs = approvalWaitMs,
                argumentChars = 40,
                resultChars = resultChars,
                estimatedArgumentTokens = 10,
                estimatedResultTokens = resultChars / 4,
                approved = approved,
                isError = isError
            )
        )
    }

    @Test fun `reports executed tool calls per tool, with exact timings`() {
        val r = runner()
        record(r, 10, 10)
        recordTool(r, "scala_eval", 1200)
        recordTool(r, "scala_eval", 800)
        recordTool(r, "exec", 3000)

        val out = Status.render(r)
        out shouldContain "3 in 5.0s"     // total wall time across calls
        out shouldContain "scala_eval"
        out shouldContain "exec"
        out shouldContain "1.0s"          // scala_eval average of 1200 and 800
        out shouldContain "3.0s"          // exec slowest
    }

    @Test fun `marks failed and refused calls without conflating them`() {
        val r = runner()
        record(r, 10, 10)
        recordTool(r, "scala_eval", 100, isError = true)
        recordTool(r, "exec", 0, approved = false, isError = true)

        val out = Status.render(r)
        out shouldContain "1 failed"
        out shouldContain "1 refused"
        r.session.ledger.toolFailureCount() shouldBe 1
        r.session.ledger.toolRefusedCount() shouldBe 1
    }

    @Test fun `distinguishes estimated result tokens from measured feedback cost`() {
        val r = runner()
        record(r, 100, 10)          // request 1 asked for the tool
        recordTool(r, "exec", 500, requestIndex = 1, resultChars = 400)
        record(r, 620, 10)          // request 2 carried the result back

        val out = Status.render(r)
        out shouldContain "(measured)"
        out shouldContain "(estimated)"
        // 620 - 100 of real prompt growth is attributable to the round trip.
        out shouldContain "520"
    }

    @Test fun `reports time spent waiting on the user, separately from tool time`() {
        val r = runner()
        record(r, 10, 10)
        recordTool(r, "exec", 200, approvalWaitMs = 4000)

        val out = Status.render(r)
        out shouldContain "awaiting you"
        // The approval wait must not be folded into the tool duration.
        out shouldContain "0.2s"
    }

    @Test fun `omits the tool section entirely when no tools ran`() {
        val r = runner()
        record(r, 10, 10)
        (Status.render(r).contains("tool calls")) shouldBe false
    }

    @Test fun `flags truncated turns and marks them in the table`() {
        val r = runner()
        record(r, 10, 10, reasoning = 500, truncated = true)

        val out = Status.render(r)
        out shouldContain "truncated"
        out shouldContain "cut off"
    }

    @Test fun `counts failed requests that produced no usage`() {
        val r = runner()
        r.session.ledger.recordError()

        Status.render(r) shouldContain "1 failed"
    }

    @Test fun `reports reasoning volume, which usage alone does not expose`() {
        val r = runner()
        record(r, 10, 300, reasoning = 1234)

        Status.render(r) shouldContain "1,234 chars"
    }

    @Test fun `elides older turns from the table but keeps them in the totals`() {
        val r = runner()
        repeat(13) { record(r, 100, 10) }

        val out = Status.render(r)
        out shouldContain "(+3 earlier)"
        out shouldContain "13 requests"
        out shouldContain "1,300 in"
    }

    @Test fun `survives a turn with no usage data, as an aborted stream produces`() {
        val r = runner()
        record(r, 0, 0)

        Status.render(r)
    }
}
