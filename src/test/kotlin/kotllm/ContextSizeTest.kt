package kotllm

import kotlin.test.Test
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import io.kotest.matchers.string.shouldContain

import kotllm.client.*
import kotllm.config.*
import kotllm.render.Ansi
import kotllm.repl.Prompt
import kotllm.repl.Status
import kotllm.util.Yaml

/** What the footer and `/status` say the context holds.
  *
  * The bug this pins down: the figure came straight off the last ledger entry,
  * so `/empty` cleared the conversation and left "18.4k ctx" on screen until
  * the next turn -- the one moment the number is read is exactly the moment it
  * was wrong.
  */
class ContextSizeTest {

    /** Reports prompt tokens derived from the serialised request, as a real
      * endpoint does, so the estimator and the anchor stay consistent. */
    private class StubClient : Client {
        override val id = "stub"
        private fun reply(req: ChatRequest): ChatResponse {
            val chars = req.encode().toString().length
            return ChatResponse(
                message = Message.assistant("a reply from the model"),
                finishReason = "stop",
                usage = Usage(maxOf(1, chars / 4), 20, maxOf(1, chars / 4) + 20),
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

    private fun runner(): Runner {
        val config = Config.fromConf(Yaml.loadString("model: stub-model\nstream: false\n"))
        val cc = ClientConfig("openai-compatible", "c", "https://h/v1", "k", emptyList(), null, null)
        val model = ModelConfig("stub-model", 100000, null, supportsVision = false,
            supportsFunctionCalling = true)
        val r = Runner(TestIsolation.isolate(config), Session(), ModelTarget(cc, model),
            StubClient(), quiet = true)
        r.renderTo = java.io.PrintStream(java.io.ByteArrayOutputStream())
        return r
    }

    private val longQuestion = "tell me about " + ("scala generics ".repeat(200))

    @Test fun `nothing is claimed before the first turn`() {
        runner().contextSize shouldBe null
    }

    @Test fun `the size tracks the conversation, and the endpoint's own count anchors it`() {
        val r = runner()
        r.ask(longQuestion).isOk shouldBe true

        val after = r.contextSize ?: throw AssertionError("no context size after a turn")
        // Close to what the endpoint charged, but not equal to it: the reply is
        // new context nobody has counted yet, so the figure is projected.
        val charged = r.session.lastUsage()?.promptTokens ?: 0
        (after.tokens > charged) shouldBe true
        after.measured shouldBe false
    }

    @Test fun `empty drops the count immediately, without waiting for a turn`() {
        val r = runner()
        r.ask(longQuestion).isOk shouldBe true
        val full = r.contextSize?.tokens ?: 0
        (full > 500) shouldBe true

        r.session.clear()

        val cleared = r.contextSize?.tokens ?: throw AssertionError("no context size after /empty")
        (cleared < (full / 4)) shouldBe true
        // Not zero: the system prompt and the tool schemas are still in every
        // request, and reporting an empty context as free is the same class of
        // lie in the other direction.
        (cleared > 0) shouldBe true
        // The ledger is deliberately left alone -- the tokens were still spent --
        // so the session total must not have moved with it.
        (r.session.ledger.totalPromptTokens() > 0) shouldBe true
    }

    @Test fun `the footer and status both follow, since both are read after empty`() {
        val r = runner()
        r.ask(longQuestion).isOk shouldBe true
        val before = Ansi.plain(Prompt.footer(r))

        r.session.clear()
        val after = Ansi.plain(Prompt.footer(r))

        before shouldContain "ctx"
        after shouldContain "ctx"
        after shouldNotBe before
        // A projected figure is labelled as one where there is room to say so.
        Status.render(r) shouldContain "projected"
    }

    @Test fun `a system prompt set mid-session shows up at the next prompt`() {
        val r = runner()
        r.ask("hello").isOk shouldBe true
        val before = r.contextSize?.tokens ?: 0

        r.session.systemPrompt = "you are a helpful assistant. ".repeat(300)

        ((r.contextSize?.tokens ?: 0) > (before + 500)) shouldBe true
    }

    @Test fun `the fixed overhead is carried once, not once per turn`() {
        // Reported as a bug twice, and it is the single most misread number on
        // screen. The footer says what the NEXT request will carry, and a request
        // carries the system prompt and the tool schemas exactly once. So the
        // first turn jumps to roughly the overhead, and every turn after adds only
        // its own exchange -- it does not step by the overhead again.
        //
        // The number that does accumulate is spend, not occupancy: `/status`
        // reports "tokens billed" and the ledger sums every request.
        val r = toolRunner()
        r.ask("hi").isOk shouldBe true
        val first = r.contextSize!!.tokens

        r.ask("again").isOk shouldBe true
        val second = r.contextSize!!.tokens

        r.ask("once more").isOk shouldBe true
        val third = r.contextSize!!.tokens

        // Each step is a small exchange, nowhere near the overhead it would be if
        // the prompt and schemas were being counted again.
        val firstStep = second - first
        val secondStep = third - second
        // clue: "overhead=$first, steps=$firstStep then $secondStep"
        (firstStep > 0) shouldBe true
        (firstStep < first / 4) shouldBe true
        (secondStep < first / 4) shouldBe true

        // Meanwhile the billed total does grow by roughly a whole request each
        // time, which is the distinction the two numbers exist to draw.
        (r.session.ledger.totalPromptTokens() > third * 2) shouldBe true
    }

    @Test fun `status splits the context figure into overhead and conversation`() {
        // The line that answers the question this number keeps being asked.
        val r = toolRunner()
        r.ask("hi").isOk shouldBe true

        val out = Ansi.plain(Status.render(r))
        out shouldContain "of which"
        out shouldContain "system prompt + tool schemas"
        // The overhead is a real share of the figure above it, not a rounding note.
        (r.fixedOverheadTokens() > r.contextSize!!.tokens / 2) shouldBe true
    }

    /** Tools on, so the overhead is dominated by schemas as it is in real use. */
    private fun toolRunner(): Runner {
        val config = Config.fromConf(
            Yaml.loadString("model: stub-model\nstream: false\nfunction_calling: true\nuse_tools: all\n")
        )
        val cc = ClientConfig("openai-compatible", "c", "https://h/v1", "k", emptyList(), null, null)
        val model = ModelConfig("stub-model", 100000, null, supportsVision = false,
            supportsFunctionCalling = true)
        val r = Runner(TestIsolation.isolate(config), Session(), ModelTarget(cc, model),
            StubClient(), quiet = true)
        r.renderTo = java.io.PrintStream(java.io.ByteArrayOutputStream())
        return r
    }
}
