package kotllm.util

import kotlin.test.Test
import io.kotest.matchers.shouldBe

class TokenLedgerTest {

    private fun turn(
        index: Int,
        prompt: Int,
        completion: Int,
        reasoning: Int = 0,
        latencyMs: Long = 1000,
        ttft: Long? = null,
        tools: List<String> = emptyList(),
        truncated: Boolean = false,
        aborted: Boolean = false
    ): TurnMetrics =
        TurnMetrics(index, 0L, "m", prompt, completion, reasoning, latencyMs, ttft, tools, truncated, aborted)

    @Test fun `totals sum across turns, counting re-sent prompt tokens each time`() {
        val l = TokenLedger()
        l.record(turn(1, 100, 50))
        l.record(turn(2, 160, 40))

        l.totalPromptTokens() shouldBe 260
        l.totalCompletionTokens() shouldBe 90
        l.totalTokens() shouldBe 350
        l.turnCount() shouldBe 2
    }

    @Test fun `lastUsage reflects the most recent turn, not the totals`() {
        val l = TokenLedger()
        l.record(turn(1, 100, 50))
        l.record(turn(2, 160, 40))

        l.lastUsage() shouldBe (160 to 40)
    }

    @Test fun `tool calls are counted and ordered by frequency then name`() {
        val l = TokenLedger()
        l.record(turn(1, 1, 1, tools = listOf("exec", "scala_eval")))
        l.record(turn(2, 1, 1, tools = listOf("scala_eval")))

        l.toolCallCounts() shouldBe listOf("scala_eval" to 2, "exec" to 1)
        l.totalToolCalls() shouldBe 3
    }

    @Test fun `flags are counted independently`() {
        val l = TokenLedger()
        l.record(turn(1, 1, 1, truncated = true))
        l.record(turn(2, 1, 1, aborted = true))
        l.recordError()

        l.truncatedCount() shouldBe 1
        l.abortedCount() shouldBe 1
        l.errorCount() shouldBe 1
    }

    @Test fun `averages ignore turns that never reported a first-token time`() {
        val l = TokenLedger()
        l.record(turn(1, 1, 1, latencyMs = 1000, ttft = 400))
        l.record(turn(2, 1, 1, latencyMs = 3000, ttft = null)) // a blocking call

        l.averageLatencyMs() shouldBe 2000L
        l.averageTimeToFirstTokenMs() shouldBe 400L
    }

    @Test fun `averages are None on an empty ledger rather than dividing by zero`() {
        val l = TokenLedger()
        l.averageLatencyMs() shouldBe null
        l.averageTimeToFirstTokenMs() shouldBe null
        l.lastUsage() shouldBe null
        l.isEmpty() shouldBe true
    }

    @Test fun `throughput excludes the wait for the first token`() {
        // 100 tokens generated over 2s of the 2.5s total.
        turn(1, 0, 100, latencyMs = 2500, ttft = 500).outputTokensPerSecond() shouldBe 50.0
    }

    @Test fun `throughput is None when nothing was generated`() {
        turn(1, 10, 0, latencyMs = 1000).outputTokensPerSecond() shouldBe null
    }

    @Test fun `nextIndex numbers turns from one`() {
        val l = TokenLedger()
        l.nextIndex() shouldBe 1
        l.record(turn(1, 1, 1))
        l.nextIndex() shouldBe 2
    }

    // Glyph expectations come from Glyphs.ramp rather than being hard-coded, so
    // these pass on a terminal whose charset forces the ASCII fallback.
    private val lowest = Glyphs.ramp.first()
    private val highest = Glyphs.ramp.last()

    @Test fun `sparkline scales from zero to the series maximum`() {
        TokenLedger.sparkline(listOf(0, 100)) shouldBe "$lowest$highest"
        TokenLedger.sparkline(listOf(100, 100)) shouldBe "$highest$highest"
    }

    @Test fun `sparkline handles the degenerate series without dividing by zero`() {
        TokenLedger.sparkline(emptyList()) shouldBe ""
        TokenLedger.sparkline(listOf(0, 0, 0)) shouldBe lowest.toString().repeat(3)
        TokenLedger.sparkline(listOf(42)) shouldBe highest.toString()
    }

    @Test fun `bar fills proportionally and clamps out-of-range input`() {
        val f = Glyphs.barFilled
        val e = Glyphs.barEmpty
        TokenLedger.bar(0.0, 4) shouldBe e.repeat(4)
        TokenLedger.bar(0.5, 4) shouldBe (f.repeat(2) + e.repeat(2))
        TokenLedger.bar(1.0, 4) shouldBe f.repeat(4)
        TokenLedger.bar(2.0, 4) shouldBe f.repeat(4)
        TokenLedger.bar(-1.0, 4) shouldBe e.repeat(4)
    }

    @Test fun `durations read naturally at each scale`() {
        TokenLedger.duration(5000) shouldBe "5s"
        TokenLedger.duration(65000) shouldBe "1m 05s"
        TokenLedger.duration(3_725_000) shouldBe "1h 02m"
    }

    @Test fun `token counts get thousands separators`() {
        TokenLedger.commas(261000) shouldBe "261,000"
    }

    @Test fun `compact form keeps a decimal only where it informs`() {
        TokenLedger.compact(412) shouldBe "412"
        TokenLedger.compact(2300) shouldBe "2.3k"
        TokenLedger.compact(5000) shouldBe "5k"      // not 5.0k
        TokenLedger.compact(18400) shouldBe "18.4k"
        TokenLedger.compact(19500) shouldBe "19.5k"
        TokenLedger.compact(261000) shouldBe "261k"  // not 261.0k
        TokenLedger.compact(1_500_000) shouldBe "1.5M"
        TokenLedger.compact(2_000_000) shouldBe "2M"
    }

    private fun toolRun(
        name: String,
        durationMs: Long,
        requestIndex: Int = 1,
        resultChars: Int = 100,
        approved: Boolean = true,
        isError: Boolean = false,
        approvalWaitMs: Long = 0L
    ) = ToolCallMetrics(
        index = 1, requestIndex = requestIndex, name = name,
        startedAt = 1000L, endedAt = 1000L + durationMs, approvalWaitMs = approvalWaitMs,
        argumentChars = 20, resultChars = resultChars,
        estimatedArgumentTokens = 5, estimatedResultTokens = resultChars / 4,
        approved = approved, isError = isError
    )

    @Test fun `tool duration is derived from the recorded start and end`() {
        toolRun("exec", 1500).durationMs() shouldBe 1500
    }

    @Test fun `a clock that appears to go backwards yields zero, not a negative`() {
        ToolCallMetrics(1, 1, "exec", 5000L, 4000L, 0L, 0, 0, 0, 0, approved = true, isError = false)
            .durationMs() shouldBe 0
    }

    @Test fun `rollups aggregate per tool, busiest first`() {
        val l = TokenLedger()
        l.recordToolRun(toolRun("exec", 3000))
        l.recordToolRun(toolRun("scala_eval", 1000))
        l.recordToolRun(toolRun("scala_eval", 500))

        val rollups = l.toolRollups()
        rollups.map { it.name } shouldBe listOf("scala_eval", "exec")

        val evals = rollups.first()
        evals.calls shouldBe 2
        evals.totalMillis shouldBe 1500
        evals.averageMillis() shouldBe 750
        evals.slowestMillis shouldBe 1000
    }

    @Test fun `failures and refusals are counted apart`() {
        val l = TokenLedger()
        l.recordToolRun(toolRun("scala_eval", 10, isError = true))
        l.recordToolRun(toolRun("exec", 0, approved = false, isError = true))
        l.recordToolRun(toolRun("exec", 10))

        // A refused call is not a tool failure: nothing ran.
        l.toolFailureCount() shouldBe 1
        l.toolRefusedCount() shouldBe 1
        l.executedToolCalls() shouldBe 3
    }

    @Test fun `approval wait is tracked separately from execution time`() {
        val l = TokenLedger()
        l.recordToolRun(toolRun("exec", 200, approvalWaitMs = 9000))

        l.totalToolMillis() shouldBe 200
        l.totalApprovalWaitMillis() shouldBe 9000
    }

    @Test fun `feedback tokens measure real prompt growth after a tool call`() {
        val l = TokenLedger()
        l.record(turn(1, 100, 20))
        l.recordToolRun(toolRun("exec", 100, requestIndex = 1))
        l.record(turn(2, 450, 30))

        // 450 - 100: what feeding the result back actually cost, exactly.
        l.toolFeedbackTokens() shouldBe 350
    }

    @Test fun `feedback tokens ignore growth from plain conversational turns`() {
        val l = TokenLedger()
        l.record(turn(1, 100, 20))
        l.record(turn(2, 400, 30)) // no tool call in between

        l.toolFeedbackTokens() shouldBe 0
    }

    @Test fun `feedback tokens never go negative when the history was compacted`() {
        val l = TokenLedger()
        l.record(turn(1, 900, 20))
        l.recordToolRun(toolRun("exec", 10, requestIndex = 1))
        l.record(turn(2, 100, 30))

        l.toolFeedbackTokens() shouldBe 0
    }

    @Test fun `estimated result tokens are summed across calls`() {
        val l = TokenLedger()
        l.recordToolRun(toolRun("exec", 10, resultChars = 400))
        l.recordToolRun(toolRun("exec", 10, resultChars = 800))

        l.estimatedToolResultTokens() shouldBe 300
    }

    @Test fun `clear resets the ledger`() {
        val l = TokenLedger()
        l.record(turn(1, 100, 50))
        l.recordToolRun(toolRun("exec", 10))
        l.recordError()
        l.clear()

        l.isEmpty() shouldBe true
        l.totalTokens() shouldBe 0
        l.errorCount() shouldBe 0
        l.executedToolCalls() shouldBe 0
    }
}
