package kotllm.repl

import java.nio.file.Files

import kotlin.test.Test
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.collections.shouldContainAll

import kotllm.*
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
import kotllm.util.Yaml

/** The `--list-<kind>` bodies.
 *
 * Each returns bare names for a script to consume, which is the contract that
 * separates them from `/info`, `/rag` and `/memory` -- those are reports for a
 * human. So the assertions here are mostly "no decoration": no counts, no
 * colour, nothing that would have to be parsed off.
 */
class ListingTest {

    private object UnusedClient : Client {
        override val id: String = "test"
        override fun chat(req: ChatRequest): ChatResponse = throw UnsupportedOperationException()
        override fun chatStream(
            req: ChatRequest,
            onEvent: (StreamEvent) -> Unit,
            keepGoing: (() -> Boolean)?
        ): ChatResponse = throw UnsupportedOperationException()
    }

    /** A config with a real `clients:` block, because `--list-models` reads the
     * declared clients rather than the resolved target. */
    private val yamlWithClient =
        """model: m1
          |function_calling: true
          |use_tools: all
          |clients:
          |- type: openai-compatible
          |  name: c
          |  api_base: https://h/v1
          |  api_key: k
          |  models:
          |  - name: m1
          |    max_input_tokens: 1000
          |""".trimMargin()

    private fun runner(yaml: String = yamlWithClient): Runner {
        val config = Config.fromConf(Yaml.loadString(yaml))
        val client = ClientConfig(
            "openai-compatible",
            "c",
            "https://h/v1",
            "k",
            listOf(ModelConfig("m1", 1000, null, supportsVision = false, supportsFunctionCalling = true)),
            null,
            null
        )
        val model = ModelConfig("m1", 1000, null, supportsVision = false, supportsFunctionCalling = true)
        return Runner(TestIsolation.isolate(config), Session(), ModelTarget(client, model), UnusedClient)
    }

    @Test fun `models lists client model ids`() {
        Commands.listing(runner(), "models") shouldBe ok(listOf("c:m1"))
    }

    @Test fun `tools lists every registered name, not just the ones offered now`() {
        // The question this answers is "what can I put in use_tools", and a tool
        // withheld because no index is active is still a valid answer to it.
        val names = Commands.listing(runner(), "tools").orNull()!!

        names shouldContainAll listOf("groovy_eval", "exec", "read_to_var", "grep")
        names.forEach { n -> n shouldNotContain " " } // bare names, pipeable

        // The families that are off by default are genuinely absent, not merely
        // unoffered: `memory_tools`, `web_tools`, `task_tools` and
        // `session_transfer` decide whether they are registered at all, because a
        // schema costs its tokens on every request whether or not it is called.
        names.none { it in listOf("add_or_update_memory", "web_search", "create_tasks") } shouldBe true
    }

    @Test fun `vars lists variable names without their braces`() {
        val names = Commands.listing(runner(), "vars").orNull()!!

        names shouldContain "model"
        names.forEach { n ->
            n shouldNotContain "{"
            n shouldNotContain "}"
        }
    }

    @Test fun `commands lists exactly what the parser would dispatch`() {
        val listed = Commands.listing(runner(), "commands").orNull()!!

        listed shouldContain "/help"
        // Every name offered is one the parser accepts -- the same invariant the
        // completer is held to.
        listed.forEach { name -> (Commands.parse(name) != null) shouldBe true }
    }

    @Test fun `a disabled subsystem is an error naming its key, not an empty list`() {
        // An empty list and "this is switched off" are different facts, and a
        // script cannot tell them apart from exit code alone.
        val r = runner()
        Commands.listing(r, "rags").fold({ it }, { "" }) shouldContain "rag_enabled"
        Commands.listing(r, "memories").fold({ it }, { "" }) shouldContain "memory_enabled"
    }

    @Test fun `memories lists the files that are actually being injected`() {
        val dir = Files.createTempDirectory("kotllm-listing")
        Files.write(dir.resolve("notes.md"), "- a note\n".toByteArray(Charsets.UTF_8))

        val config = Config
            .fromConf(Yaml.loadString("model: m\n"))
            .copy(memory = kotllm.memory.MemoryConfig(enabled = true, maxChars = 8000, projectFile = "KOTLLM.md"))
        val client = ClientConfig("openai-compatible", "c", "https://h/v1", "k", emptyList(), null, null)
        val model = ModelConfig("m1", 1000, null, supportsVision = false, supportsFunctionCalling = false)
        val r = Runner(
            config.copy(rag = config.rag.copy(enabled = false)),
            Session(),
            ModelTarget(client, model),
            UnusedClient
        )

        // Points at the real config dir, so this asserts the shape rather than the
        // contents: whatever is injected is what is listed.
        Commands.listing(r, "memories").orNull()!! shouldBe r.memoryFiles.map { it.path.toString() }
    }

    @Test fun `an unknown kind lists the known ones`() {
        val err = Commands.listing(runner(), "roles").fold({ it }, { "" })
        err shouldContain "unknown list 'roles'"
        err shouldContain "models, tools, agents, skills, rags, memories, vars, commands"
    }
}
