package kotllm

import kotlin.test.Test
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.assertions.withClue

import kotllm.client.*
import kotllm.config.*
import kotllm.util.Yaml

/** The agentic tool loop: its stopping rules, and the invariant that the
 * conversation stays valid however it stops.
 */
class RunnerToolLoopTest {

    /** Always asks for a tool call, so the loop only ends when kotllm stops it. */
    private class LoopingClient(val callsPerResponse: Int = 1) : Client {
        var requests = 0
        override val id = "stub"

        private fun reply(): ChatResponse {
            requests += 1
            val calls = (1..callsPerResponse).map { i ->
                ToolCall("call_${requests}_$i", "scala_eval", """{"code":"1 + 1"}""")
            }
            return ChatResponse(
                message = Message("assistant", listOf(Part.Text("working")), calls),
                finishReason = "tool_calls",
                usage = Usage(10, 5, 15),
                model = "stub"
            )
        }

        override fun chat(req: ChatRequest): ChatResponse = reply()
        override fun chatStream(
            req: ChatRequest,
            onEvent: (StreamEvent) -> Unit,
            keepGoing: (() -> Boolean)?
        ): ChatResponse = reply()
    }

    private fun runner(client: Client, iterations: Int = 3): Runner {
        val config = Config.fromConf(
            Yaml.loadString(
                """|model: stub
                   |stream: false
                   |function_calling: true
                   |use_tools: all
                   |tool_approval: deny
                   |max_tool_iterations: $iterations
                   |""".trimMargin()
            )
        )
        val cc = ClientConfig("openai-compatible", "c", "https://h/v1", "k", emptyList(), null, null)
        val model = ModelConfig("stub", 100000, null, supportsVision = false, supportsFunctionCalling = true)
        return Runner(TestIsolation.isolate(config), Session(), ModelTarget(cc, model), client)
    }

    /** Every tool_call_id in the history must have a matching tool result.
     *
     * The endpoint rejects a request where an assistant message asks for a tool
     * call that no tool message answers, so this has to hold no matter how the
     * loop terminated.
     */
    private fun assertHistoryValid(r: Runner) {
        val requested = r.session.messages().flatMap { it.toolCalls }.map { it.id }.toSet()
        val answered = r.session.messages().mapNotNull { it.toolCallId }.toSet()
        withClue("tool calls with no result: ${(requested - answered).joinToString(", ")}: ") {
            (requested - answered).shouldBeEmpty()
        }
    }

    @Test fun `the loop stops at max_tool_iterations`() {
        val client = LoopingClient()
        val r = runner(client, iterations = 3)

        r.ask("go").isOk shouldBe true
        client.requests shouldBe 3
    }

    @Test fun `a conversation stopped by the cap stays valid for the next request`() {
        // Regression: the capped response was appended with its tool_calls but the
        // matching tool results never were, so the following request was malformed.
        val client = LoopingClient()
        val r = runner(client, iterations = 2)

        r.ask("go").isOk shouldBe true
        assertHistoryValid(r)
    }

    @Test fun `the next request after a cap is well formed`() {
        val client = LoopingClient()
        val r = runner(client, iterations = 2)
        r.ask("go").isOk shouldBe true

        val req = r.buildRequest("carry on", stream = false)
        val requested = req.messages.flatMap { it.toolCalls }.map { it.id }.toSet()
        val answered = req.messages.mapNotNull { it.toolCallId }.toSet()

        (requested - answered).shouldBeEmpty()
    }

    @Test fun `parallel tool calls in a capped response are all answered`() {
        val client = LoopingClient(callsPerResponse = 3)
        val r = runner(client, iterations = 1)

        r.ask("go").isOk shouldBe true
        assertHistoryValid(r)
    }

    @Test fun `a loop halted by abort also leaves a valid conversation`() {
        val client = LoopingClient()
        val r = runner(client, iterations = 10)

        // Aborts after the first response, the same early-exit path as the cap.
        var calls = 0
        r.ask("go", abort = { calls += 1; calls > 1 }).isOk shouldBe true
        assertHistoryValid(r)
    }

    @Test fun `abort stops the rest of a batch, not just the next round trip`() {
        // The gap this closes: the only abort check sat *above* the batch, so a
        // response carrying several calls ran every one of them after Ctrl-C --
        // the visible effect of pressing it was nothing at all for however long
        // the remainder took.
        val client = LoopingClient(callsPerResponse = 4)
        val r = runner(client, iterations = 10)

        // Aborted from the very first check, so no call in the batch should run.
        r.ask("go", abort = { true }).isOk shouldBe true
        assertHistoryValid(r)

        val results = r.session.messages().filter { it.role == "tool" }.map { it.text }
        (results.isEmpty()) shouldBe false
        results.map { it.lowercase() }.all { it.contains("interrupted") } shouldBe true
    }

    @Test fun `the unexecuted calls are explained rather than answered with silence`() {
        val client = LoopingClient()
        val r = runner(client, iterations = 1)
        r.ask("go").isOk shouldBe true

        val results = r.session.messages().filter { it.role == "tool" }.map { it.text }
        (results.isEmpty()) shouldBe false
        results.first().lowercase() shouldContain "not run"
    }

    @Test fun `a normal conversation with no tool calls is unaffected`() {
        val plain = object : Client {
            override val id = "plain"
            private fun reply() = ChatResponse(Message.assistant("done"), "stop", Usage(5, 5, 10), "stub")
            override fun chat(req: ChatRequest): ChatResponse = reply()
            override fun chatStream(
                req: ChatRequest,
                onEvent: (StreamEvent) -> Unit,
                keepGoing: (() -> Boolean)?
            ): ChatResponse = reply()
        }
        val r = runner(plain)

        r.ask("hello").isOk shouldBe true
        r.session.messages().count { it.role == "tool" } shouldBe 0
        assertHistoryValid(r)
    }

    @Test fun `max_tool_iterations is settable at runtime`() {
        val r = runner(LoopingClient(), iterations = 3)
        r.config.maxToolIterations shouldBe 3

        r.updateConfig { it.copy(maxToolIterations = 30) }
        r.config.maxToolIterations shouldBe 30
    }
}
