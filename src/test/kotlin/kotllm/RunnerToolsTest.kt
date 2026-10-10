package kotllm

import java.nio.file.Paths as JPaths

import kotlin.test.Test
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.collections.shouldBeEmpty

import kotllm.client.ChatRequest
import kotllm.client.ChatResponse
import kotllm.client.Client
import kotllm.client.StreamEvent
import kotllm.config.*
import kotllm.util.Yaml

/** Guards the configuration that decides whether tools reach the model at all.
 *
 * Regression cover for a real trap: a config with neither `function_calling`
 * nor `use_tools` silently sent no tools, so the model truthfully reported it
 * could not run anything and nothing explained why.
 */
class RunnerToolsTest {

    private object UnusedClient : Client {
        override val id = "test"
        override fun chat(req: ChatRequest): ChatResponse = throw UnsupportedOperationException()
        override fun chatStream(
            req: ChatRequest,
            onEvent: (StreamEvent) -> Unit,
            keepGoing: (() -> Boolean)?
        ): ChatResponse = throw UnsupportedOperationException()
    }

    private fun runner(yaml: String, supportsFc: Boolean = true): Runner {
        val config = Config.fromConf(Yaml.loadString(yaml))
        val cc = ClientConfig("openai-compatible", "c", "https://h/v1", "k", emptyList(), null, null)
        val model = ModelConfig("m", 1000, null, supportsVision = false, supportsFunctionCalling = supportsFc)
        return Runner(TestIsolation.isolate(config), Session(), ModelTarget(cc, model), UnusedClient)
    }

    private val enabled = "model: m\nfunction_calling: true\nuse_tools: all\n"

    @Test fun `tools are offered when both keys are set`() {
        val r = runner(enabled)
        r.activeTools.map { it.name }.containsAll(listOf("groovy_eval", "exec")) shouldBe true
        r.buildRequest("hi", stream = false).tools.isNotEmpty() shouldBe true
    }

    @Test fun `no tools are sent when function_calling is absent`() {
        // The exact state the user config was in.
        val r = runner("model: m\nuse_tools: all\n")
        r.activeTools.shouldBeEmpty()
        r.buildRequest("hi", stream = false).tools.shouldBeEmpty()
    }

    @Test fun `no tools are sent when use_tools is absent`() {
        val r = runner("model: m\nfunction_calling: true\n")
        r.activeTools.shouldBeEmpty()
    }

    @Test fun `supports_function_calling on the model is not itself a switch`() {
        // It declares model capability; kotllm still needs the two config keys.
        runner("model: m\n", supportsFc = true).activeTools.shouldBeEmpty()
    }

    @Test fun `use_tools none disables tools even with function_calling on`() {
        runner("model: m\nfunction_calling: true\nuse_tools: none\n").activeTools.shouldBeEmpty()
    }

    @Test fun `use_tools can select a subset`() {
        val r = runner("model: m\nfunction_calling: true\nuse_tools: groovy_eval\n")
        r.activeTools.map { it.name } shouldBe listOf("groovy_eval")
    }

    @Test fun `the status summary names the reason tools are off`() {
        runner("model: m\n").toolStatusSummary shouldContain "function_calling: false"
        runner("model: m\nfunction_calling: true\n").toolStatusSummary shouldContain "use_tools"
        runner(enabled).toolStatusSummary shouldContain "groovy_eval"
    }

    @Test fun `a tool-capable model with no tools enabled produces an actionable hint`() {
        val hint = runner("model: m\n").toolConfigHint
        (hint != null) shouldBe true
        hint!! shouldContain "function_calling: true"
        hint shouldContain "use_tools: all"
    }

    @Test fun `the hint names only the key that is actually missing`() {
        runner("model: m\nfunction_calling: true\n").toolConfigHint!! shouldContain "use_tools: all"
        (runner("model: m\nfunction_calling: true\n").toolConfigHint!!.contains("function_calling: true'")) shouldBe false
    }

    @Test fun `no hint once tools are working`() {
        runner(enabled).toolConfigHint shouldBe null
    }

    @Test fun `no hint for a model that cannot call tools anyway`() {
        runner("model: m\n", supportsFc = false).toolConfigHint shouldBe null
    }

    @Test fun `the hint names the config file actually loaded, not the default path`() {
        val loaded = Config.fromConf(Yaml.loadString("model: m\n")).copy(sourcePath = JPaths.get("X:/custom/here.yaml"))
        val cc = ClientConfig("openai-compatible", "c", "https://h/v1", "k", emptyList(), null, null)
        val model = ModelConfig("m", null, null, supportsVision = false, supportsFunctionCalling = true)
        val r = Runner(TestIsolation.isolate(loaded), Session(), ModelTarget(cc, model), UnusedClient)

        r.toolConfigHint!! shouldContain "here.yaml"
    }

    // ---- tools_exclude, end to end -----------------------------------------

    @Test fun `tools_exclude withholds from what use_tools selected, and reaches the wire`() {
        val r = runner(enabled + "tools_exclude: exec, groovy_eval\n")

        r.activeTools.map { it.name }.none { it in setOf("exec", "groovy_eval") } shouldBe true
        r.activeTools.map { it.name } shouldContain "grep"
        // The schemas are what actually cost context, so the check that matters is
        // the request, not the registry.
        ("exec" in r.buildRequest("hi", stream = false).tools.map { it.name }) shouldBe false
    }

    @Test fun `a star pattern reaches config, whatever separator the file used`() {
        fun names(yaml: String) = runner(enabled + yaml).activeTools.map { it.name }

        // A tool name cannot contain whitespace, so all three spellings are read.
        names("tools_exclude: \"*_eval, exec\"\n").none { it in setOf("groovy_eval", "exec") } shouldBe true
        names("tools_exclude: \"*_eval exec\"\n").none { it in setOf("groovy_eval", "exec") } shouldBe true
        names("tools_exclude:\n  - \"*_eval\"\n  - exec\n").none { it in setOf("groovy_eval", "exec") } shouldBe true
        // And they all leave the rest alone.
        names("tools_exclude: \"*_eval, exec\"\n") shouldContain "grep"
    }

    @Test fun `the manifest follows the exclusion, not just the schemas`() {
        // The manifest tells the model what it can run, and a withheld tool
        // described in prose is an invitation to call something that is not there.
        val r = runner(enabled + "tools_exclude: groovy_eval\n")
        (r.buildRequest("hi", stream = false).messages.first().text.contains("groovy_eval")) shouldBe false
    }

    @Test fun `an exclusion that empties the list blames itself, not use_tools`() {
        // `none selected (use_tools: all)` points at the one setting that is not
        // the problem, and the hint told the user to add a key they already have.
        val r = runner(enabled + "tools_exclude: \"*\"\n")

        r.activeTools.shouldBeEmpty()
        r.toolStatusSummary shouldContain "tools_exclude"
        (r.toolStatusSummary.contains("none selected")) shouldBe false
        r.toolConfigHint!! shouldContain "tools_exclude"
    }

    @Test fun `an exclusion matching nothing is a startup hint, not a failure`() {
        val r = runner(enabled + "tools_exclude: pythn_eval, exec\n")

        // The tool set still works -- only the typo is inert.
        r.activeTools.map { it.name } shouldContain "groovy_eval"
        ("exec" in r.activeTools.map { it.name }) shouldBe false
        r.startupHints.joinToString(" ") shouldContain "pythn_eval"
    }

    @Test fun `no hint when every exclusion matched something`() {
        (runner(enabled + "tools_exclude: exec\n").startupHints
            .joinToString(" ").contains("tools_exclude")) shouldBe false
    }

    @Test fun `tools_exclude is absent by default, so an existing config is unchanged`() {
        runner(enabled).config.toolsExclude.shouldBeEmpty()
        runner(enabled).activeTools.map { it.name } shouldContain "exec"
    }

    @Test fun `tools names what was withheld, not only what survived`() {
        // "I excluded exec and it was called anyway" and "I excluded one thing and
        // now nothing works" are both questions about the missing tools, and a
        // list of the survivors answers neither.
        val report = kotllm.repl.Commands.toolSelectionText(
            runner(enabled + "tools_exclude: exec, pythn_eval\n")
        )

        report shouldContain "offered"
        report shouldContain "withheld"
        report shouldContain "exec"
        report shouldContain "tools_exclude"
        // And the entry that withholds nothing is called out in the same place.
        report shouldContain "pythn_eval"
    }

    @Test fun `tools says nothing about exclusions when there are none`() {
        val report = kotllm.repl.Commands.toolSelectionText(runner(enabled))
        report shouldContain "offered"
        (report.contains("withheld")) shouldBe false
    }

    @Test fun `the tool manifest is prepended to the system prompt when tools are live`() {
        val r = runner(enabled)
        r.session.systemPrompt = "BE TERSE"
        val req = r.buildRequest("hi", stream = false)

        val system = req.messages.first()
        system.role shouldBe "system"
        // Deliberately not a tool name: the prompt no longer restates what the
        // schemas already say, so the marker for "the interpreter section
        // rendered" has to be something only the prompt carries.
        system.text shouldContain "Bare assignment persists"
        // The library hints used to open by restating the artifact name -- "os --
        // os-lib: ..." -- which is not something the model can type. Only the call
        // shapes are left, so this asserts on one of those.
        system.text shouldContain "Json.parseToJsonElement"
        system.text shouldContain "BE TERSE"
    }

    @Test fun `no manifest is added when tools are off`() {
        val r = runner("model: m\n")
        r.session.systemPrompt = "BE TERSE"
        r.buildRequest("hi", stream = false).messages.first().text shouldBe "BE TERSE"
    }

    /** What a request costs before anyone has said anything.
     *
     * Both halves, because they are paid together and only one of them was
     * ever visible: the system prompt is what `/prompt full` showed, and the
     * tool schemas -- a sibling field of `messages`, not part of the prompt --
     * were seven times larger and reported nowhere.
     *
     * Ceilings rather than exact figures, and deliberately loose: this is a
     * ratchet against drift, not a golden file. Adding a tool is allowed;
     * adding six without noticing is what this catches. Measured on a default
     * config, which is the only configuration most people ever run.
     */
    @Test fun `the fixed overhead of a default request stays within budget`() {
        val r = runner(enabled)

        val prompt = r.assembledPrompt ?: ""
        val schemas = kotllm.client.ChatRequest.toolSchemas(r.activeTools.map { it.definition }).toString()

        (prompt.length < 2200) shouldBe true
        (schemas.length < 11000) shouldBe true
        // It was 21,691 before the prompt was externalised and the schemas cut.
        ((prompt.length + schemas.length) < 13000) shouldBe true
    }

    @Test fun `the four opt-in families are not in a default tool set`() {
        // Each is off purely on cost, and each is one config key away. The test
        // exists because the cheapest way to undo 1,200 tokens of saving is to
        // register something unconditionally again.
        val r = runner(enabled)
        val names = r.activeTools.map { it.name }
        names.none {
            it in setOf(
                "create_tasks", "update_task", "add_or_update_memory", "delete_memory",
                "web_search", "fetch", "session_transfer", "java_eval"
            )
        } shouldBe true
        // And the ones that earn their place are still there.
        val expected = buildList {
            addAll(listOf("groovy_eval", "read_to_var", "write_from_eval", "grep", "edit", "exec"))
            if (r.pythonEngine != null) add("python_eval")
        }
        names.containsAll(expected) shouldBe true
    }
}
