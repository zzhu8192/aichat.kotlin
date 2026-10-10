package kotllm

import kotlin.test.Test
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain

import kotllm.client.*
import kotllm.config.*
import kotllm.util.Yaml

/** `/prompt full`: seeing the prompt the harness assembles, not just the slice
 * the user wrote.
 *
 * Seven of the eight sections in `Runner.systemPrompt` were invisible from
 * inside the REPL -- `/prompt` reported `session.systemPrompt` and nothing
 * else, so the tool manifest, skills, memories, user tools, the RAG notice and
 * the checklist could only be seen by leaving the REPL and running
 * `--dry-run`.
 */
class RunnerPromptViewTest {

    private object SilentClient : Client {
        override val id = "stub"
        private fun reply(): ChatResponse = ChatResponse(
            message = Message("assistant", listOf(Part.Text("ok"))),
            finishReason = "stop",
            usage = Usage(1, 1, 2),
            model = "stub"
        )
        override fun chat(req: ChatRequest): ChatResponse = reply()
        override fun chatStream(
            req: ChatRequest,
            onEvent: (StreamEvent) -> Unit,
            keepGoing: (() -> Boolean)?
        ): ChatResponse = reply()
    }

    private fun runner(own: String?, tools: Boolean = true): Runner {
        val config = Config.fromConf(
            Yaml.loadString(
                """|model: stub
                   |stream: false
                   |function_calling: $tools
                   |use_tools: ${if (tools) "all" else "none"}
                   |tool_approval: deny
                   |save: false
                   |""".trimMargin()
            )
        )
        val cc = ClientConfig("openai-compatible", "c", "https://h/v1", "k", emptyList(), null, null)
        val model = ModelConfig("stub", 100000, null, supportsVision = false, supportsFunctionCalling = tools)
        return Runner(TestIsolation.isolate(config), Session(own), ModelTarget(cc, model), SilentClient)
    }

    @Test fun `the view is off until asked for, and never reads back on from anywhere`() {
        // Session-scoped by design: no config key sets it, the same rule as RAG
        // injection. A debugging view that survived a restart would keep fattening
        // every session file long after the question was answered.
        runner("be terse").fullPromptView shouldBe false
    }

    @Test fun `the view toggles both ways`() {
        val r = runner("be terse")
        r.fullPromptView = true
        r.fullPromptView shouldBe true
        r.fullPromptView = false
        r.fullPromptView shouldBe false
    }

    @Test fun `the assembled prompt carries what the session prompt alone never showed`() {
        val r = runner("be terse")
        val full = r.assembledPrompt ?: throw AssertionError("expected an assembled prompt")

        // The manifest is the section the user could not see: it is why a prompt
        // reported as 8 characters actually costs thousands of tokens.
        full shouldContain "be terse"
        (full.length > "be terse".length) shouldBe true
        r.session.systemPrompt shouldBe "be terse"
    }

    @Test fun `the user's own slice is the tail, which is what lets the footer subtract`() {
        // `Commands.showPrompt` reports "N injected, M yours" by subtracting the
        // session prompt's length from the assembled one. That arithmetic is only
        // honest while the user's slice is appended last and verbatim.
        val full = runner("be terse").assembledPrompt ?: throw AssertionError("expected a prompt")
        (full.endsWith("be terse")) shouldBe true
    }

    @Test fun `an assembled prompt exists even with no session prompt set`() {
        // The point of the mode: "(no system prompt)" was a lie whenever tools
        // were on.
        val r = runner(null)
        r.session.systemPrompt shouldBe null
        (r.assembledPrompt != null) shouldBe true
    }

    @Test fun `nothing at all to say is still reported as nothing, not as an empty string`() {
        val r = runner(null, tools = false)
        r.assembledPrompt shouldBe null
    }

    @Test fun `the assembled prompt is rebuilt per call, so a changed session prompt shows at once`() {
        val r = runner("be terse")
        r.session.systemPrompt = "be expansive"
        r.assembledPrompt!! shouldContain "be expansive"
        (r.assembledPrompt!!.contains("be terse")) shouldBe false
    }

    // ---- what the command actually prints ----------------------------------

    private fun dispatch(r: Runner, args: String?): String {
        val original = System.out
        val out = java.io.ByteArrayOutputStream()
        System.setOut(java.io.PrintStream(out, true, "UTF-8"))
        try {
            kotllm.repl.Commands.dispatch(r, "/prompt", args)
        } finally {
            System.setOut(original)
        }
        return out.toString("UTF-8")
    }

    @Test fun `prompt default reports only the user's slice, as it always has`() {
        val r = runner("be terse")
        val printed = dispatch(r, null)
        printed shouldContain "be terse"
        (printed.contains("groovy_eval")) shouldBe false
        printed shouldContain "view: default"
    }

    @Test fun `prompt full switches the view and prints the injected sections`() {
        val r = runner("be terse")
        val printed = dispatch(r, "full")

        r.fullPromptView shouldBe true
        printed shouldContain "be terse"
        // The manifest, which `/prompt` alone never showed.
        printed shouldContain "groovy_eval"
        printed shouldContain "view: full"
    }

    @Test fun `prompt full weighs the tool schemas too, not just the prompt`() {
        // The schemas are a sibling field of `messages` rather than part of the
        // prompt, so `/prompt full` reported the system message and stopped --
        // which on this harness is an eighth of what a request actually carries.
        // Asking "why is my context so big" and being shown the smaller half is
        // how the question gets answered wrong.
        val printed = dispatch(runner("be terse"), "full")
        printed shouldContain "tool schemas"
        printed shouldContain "sent on every request alongside the prompt"
        printed shouldContain "groovy_eval"
    }

    @Test fun `prompt default switches back, and the footer arithmetic adds up`() {
        val r = runner("be terse")
        val full = dispatch(r, "full")
        // "N injected, M yours" must sum to the length it reports, or the footer
        // is worse than no footer.
        val counts = Regex("""(\d+) chars: (\d+) injected, (\d+) yours""").find(full)
            ?: throw AssertionError("no footer arithmetic in: $full")
        (counts.groupValues[2].toInt() + counts.groupValues[3].toInt()) shouldBe counts.groupValues[1].toInt()
        counts.groupValues[3].toInt() shouldBe "be terse".length

        dispatch(r, "default")
        r.fullPromptView shouldBe false
    }

    @Test fun `a view word does not become the system prompt, but a longer phrase does`() {
        val r = runner("be terse")
        dispatch(r, "full")
        r.session.systemPrompt shouldBe "be terse"

        dispatch(r, "full sentences only")
        r.session.systemPrompt shouldBe "full sentences only"
    }

    @Test fun `clearing still works, and reports nothing rather than an empty line`() {
        val r = runner("be terse")
        dispatch(r, "-") shouldContain "cleared"
        r.session.systemPrompt shouldBe null
        dispatch(r, null) shouldContain "(no system prompt)"
    }
}
