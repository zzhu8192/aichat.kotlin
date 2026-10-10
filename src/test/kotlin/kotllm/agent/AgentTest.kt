package kotllm.agent

import java.nio.file.Files
import java.nio.file.Path

import kotlin.test.Test
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import io.kotest.matchers.collections.shouldBeEmpty

import kotllm.*
import kotllm.Runner
import kotllm.TestIsolation
import kotllm.client.*
import kotllm.config.*
import kotllm.util.Yaml

/** The agent harness.
  *
  * The model is stubbed: what is being tested is the harness -- the mode
  * switch, `system { }` capture, the budgets, the terminal calls -- and a real
  * endpoint would make every one of these assertions depend on the weather.
  * The live check is `scripts/smoke.sc` and a run by hand.
  */
class AgentTest {

    /** Echoes back what it was asked, so a test can see which conversation a
      * call joined: a shared conversation grows, a fresh one never does. */
    private class StubClient : Client {
        val requests: MutableList<ChatRequest> = mutableListOf()
        override val id = "stub"
        private fun reply(req: ChatRequest): ChatResponse {
            requests.add(req)
            val n = req.messages.count { it.role == "user" }
            return ChatResponse(
                Message.assistant("reply-$n\n"),
                "stop",
                Usage(10, 2, 12),
                "stub"
            )
        }
        override fun chat(req: ChatRequest): ChatResponse = reply(req)
        override fun chatStream(
            req: ChatRequest,
            onEvent: (StreamEvent) -> Unit,
            keepGoing: (() -> Boolean)?
        ): ChatResponse = reply(req)
    }

    private fun fixture(yaml: String = "model: m\nstream: false\n"): Pair<Runner, StubClient> {
        val config = Config.fromConf(Yaml.loadString(yaml))
        val cc = ClientConfig("openai-compatible", "c", "https://h/v1", "k", emptyList(), null, null)
        val model = ModelConfig("stub", 100000, null, supportsVision = false, supportsFunctionCalling = false)
        val client = StubClient()
        val runner = Runner(TestIsolation.isolate(config), Session(), ModelTarget(cc, model), client, quiet = true)
        return Pair(runner, client)
    }

    private fun runs(): Path = Files.createTempDirectory("kotllm-agent-runs")

    private fun run(
        source: String,
        args: Map<String, String> = emptyMap(),
        dryRun: Boolean = false,
        yaml: String = "model: m\nstream: false\n"
    ): Pair<Outcome<AgentResult>, StubClient> {
        val (runner, client) = fixture(yaml)
        return Pair(Agent.run(runner, "spec", source, args, dryRun, runs()), client)
    }

    // ---- the mode switch ---------------------------------------------------

    @Test fun `a file that calls nothing is a prompt, not an agent`() {
        // This is what makes roles free: no llm(), no agent(), no verdict -- the
        // output is prompt text for the caller to send.
        val (result, client) = run("You are a careful reviewer.\nCite files.\n")

        result shouldBe ok(AgentResult.Prompt("You are a careful reviewer.\nCite files.\n"))
        client.requests.shouldBeEmpty()
    }

    @Test fun `a tag alone does not make it an agent -- only calling a primitive does`() {
        // Adding <% %> to a prompt file must not silently change what the file is.
        val (result, _) = run("Today is <%= 2 + 2 %>. Be terse.")

        result shouldBe ok(AgentResult.Prompt("Today is 4. Be terse."))
    }

    @Test fun `one llm call makes it an agent, and the output is the artifact`() {
        val (result, client) = run("Answer: <%= llm(\"anything\") %>")

        val ran = result.orNull() as AgentResult.Ran
        ran.output shouldBe "Answer: reply-1"
        ran.failed shouldBe false
        client.requests.size shouldBe 1
    }

    // ---- system { } --------------------------------------------------------

    @Test fun `system { } captures its block as the prompt instead of emitting it`() {
        val (result, client) = run(
            """<% system { %>
              |You are terse.
              |<% } %>
              |Visible.
              |<% def ignored = llm("hi") %>""".trimMargin()
        )

        val ran = result.orNull() as AgentResult.Ran
        // The block is gone from the output ...
        ran.output shouldContain "Visible."
        ran.output shouldNotContain "You are terse."
        // ... and present in the request.
        client.requests.first().messages.first().role shouldBe "system"
        client.requests.first().messages.first().text shouldContain "You are terse."
    }

    // ---- fresh vs shared ---------------------------------------------------

    @Test fun `shared calls accumulate context, fresh calls never do`() {
        val (result, client) = run(
            """<% llm("one"); llm("two"); llm("three") %>ok"""
        )
        (result.orNull() is AgentResult.Ran) shouldBe true

        // Every turn re-sends the conversation, so the user-message count climbs.
        client.requests.map { it.messages.count { m -> m.role == "user" } } shouldBe listOf(1, 2, 3)

        val (_, fresh) = run("""<% llm("one", true); llm("two", true) %>ok""")
        // A scratch conversation is cleared before each call, so it never grows.
        fresh.requests.map { it.messages.count { m -> m.role == "user" } } shouldBe listOf(1, 1)
    }

    @Test fun `a fresh call's prompt and reply stay out of the shared conversation`() {
        val (_, client) = run(
            """<% llm("shared one"); llm("BULKY", true); llm("shared two") %>ok"""
        )

        val third = client.requests.last()
        third.messages.joinToString("") { it.text } shouldNotContain "BULKY"
    }

    // ---- terminal state ----------------------------------------------------

    @Test fun `finish stops the run, keeps what was emitted, and records the verdict`() {
        val (result, client) = run(
            """before
              |<% finish("done early") %>
              |after
              |<%= llm("never runs") %>""".trimMargin()
        )

        val ran = result.orNull() as AgentResult.Ran
        ran.verdict shouldBe "done early"
        ran.output shouldContain "before"
        ran.output shouldNotContain "after"
        ran.failed shouldBe false
        client.requests.shouldBeEmpty()
    }

    @Test fun `fail stops the run and reports failure, for a non-zero exit`() {
        val ran = run("""<% fail("no git") %>x""").first.orNull() as AgentResult.Ran

        ran.failed shouldBe true
        ran.verdict!! shouldContain "no git"
    }

    // ---- budgets -----------------------------------------------------------

    @Test fun `maxCalls ends the run with a verdict rather than throwing`() {
        val (result, client) = run(
            """<% agent(null, null, 2)
              |   for (i in 1..10) llm("call ${'$'}i") %>done""".trimMargin()
        )

        val ran = result.orNull() as AgentResult.Ran
        client.requests.size shouldBe 2
        ran.verdict!! shouldContain "maxCalls (2)"
        // A doom-loop costs a budget, not a mystery.
        ran.failed shouldBe false
    }

    @Test fun `the same prompt three times running is treated as no progress`() {
        val (result, client) = run("""<% for (i in 1..5) llm("identical") %>done""")

        (client.requests.size < 5) shouldBe true
        (result.orNull() as AgentResult.Ran).verdict!! shouldContain "no progress"
    }

    // ---- args, dry run, artifacts ------------------------------------------

    @Test fun `args carry --agent-var values, with a default when absent`() {
        val (result, _) = run(
            """base=<%= args()["base"] ?: "origin/main" %> who=<%= args()["who"] %>""" +
                """<% agent() %>""",
            args = mapOf("who" to "zzhu")
        )

        (result.orNull() as AgentResult.Ran).output shouldContain "base=origin/main who=zzhu"
    }

    @Test fun `a dry run shows the shape of the workflow and calls no model`() {
        val (result, client) = run(
            """<% for (f in ["a", "b"]) { %>- <%= llm("review ${'$'}f", true) %>
              |<% } %>""".trimMargin(),
            dryRun = true
        )

        client.requests.shouldBeEmpty()
        val out = (result.orNull() as AgentResult.Ran).output
        out shouldContain "dry run: llm call 1, fresh"
        out shouldContain "dry run: llm call 2, fresh"
    }

    @Test fun `a run writes its source, log and artifact, so it is reproducible`() {
        val base = runs()
        val (runner, _) = fixture()
        val source = """<% log("checking") %>report body<% finish("ok") %>"""

        val ran = Agent.run(runner, "spec", source, emptyMap(), false, base)
            .orNull() as AgentResult.Ran

        Files.readString(ran.dir.resolve("agent.md")) shouldBe source
        Files.readString(ran.dir.resolve("report.md")) shouldContain "report body"
        val log = Files.readString(ran.dir.resolve("log.md"))
        log shouldContain "checking"
        log shouldContain "ok"
    }

    @Test fun `a compile error in the file is reported against the template line`() {
        val (result, _) = run(
            """ok
              |<% def x = 1; def x = 2 %>""".trimMargin()
        )

        (result as Outcome.Err).message shouldContain "template line 2"
    }

    // ---- loading -----------------------------------------------------------

    @Test fun `a missing agent names where it looked, and says what an agent is`() {
        val err = (Agent.load("no-such-agent-" + System.nanoTime()) as Outcome.Err).message

        err shouldContain "agents"
        (err.contains("no agents exist yet") || err.contains("Available:")) shouldBe true
    }
}
