package kotllm.config

import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Paths
import kotlin.test.Test
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import kotllm.*
import kotllm.util.Yaml

class ConfigTest {

    /** The reference config supplied for this project, verbatim apart from the
     * key. It must keep parsing unchanged. */
    private val referenceYaml =
        """|model: databricks-qwen35-122b-a10b
           |save: true
           |function_calling: true
           |use_tools: all
           |
           |clients:
           |- type: openai-compatible
           |  name: databricks-qwen35-122b-a10b
           |  api_base: https://adb-719.13.azuredatabricks.net/serving-endpoints
           |  api_key: dapi-test
           |  models:
           |  - name: databricks-qwen35-122b-a10b
           |    max_input_tokens: 261000
           |    supports_vision: false
           |    supports_function_calling: true
           |#  patch:
           |#      chat_completions:
           |#        '.*':
           |#          url: https://example/invocations
           |""".trimMargin()

    private val reference: Config get() = Config.fromConf(Yaml.loadString(referenceYaml))

    @Test fun `parses the reference Databricks config`() {
        val c = reference
        c.model shouldBe "databricks-qwen35-122b-a10b"
        c.save shouldBe true
        c.functionCalling shouldBe true
        c.useTools shouldBe "all"
        c.clients.size shouldBe 1

        val client = c.clients.first()
        client.kind shouldBe "openai-compatible"
        client.apiBase shouldBe "https://adb-719.13.azuredatabricks.net/serving-endpoints"
        client.apiKey shouldBe "dapi-test"
        client.models.first().maxInputTokens shouldBe 261000
        client.models.first().supportsFunctionCalling shouldBe true
    }

    @Test fun `resolves the default model from the top-level model key`() {
        val t = reference.defaultTarget.orNull()!!
        t.model.name shouldBe "databricks-qwen35-122b-a10b"
        t.client.id() shouldBe "databricks-qwen35-122b-a10b"
    }

    @Test fun `resolves an explicit client model id`() {
        val t = reference
            .resolveModel("databricks-qwen35-122b-a10b:databricks-qwen35-122b-a10b")
            .orNull()!!
        t.model.name shouldBe "databricks-qwen35-122b-a10b"
    }

    @Test fun `passes through an undeclared model name on a known client`() {
        // The gateway, not our config, decides which names are servable.
        val t = reference.resolveModel("databricks-qwen35-122b-a10b:some-other-endpoint").orNull()!!
        t.model.name shouldBe "some-other-endpoint"
        t.model.maxInputTokens shouldBe null
    }

    @Test fun `rejects an unknown client in a qualified id`() {
        reference.resolveModel("nope:model").fold({ it }, { "" }) shouldContain "unknown client"
    }

    @Test fun `rejects an unknown bare model name`() {
        reference.resolveModel("nonexistent").fold({ it }, { "" }) shouldContain "unknown model"
    }

    @Test fun `streaming defaults on and reasoning defaults to collapsed`() {
        val c = reference
        c.stream shouldBe true
        c.reasoning shouldBe ReasoningDisplay.Collapse
    }

    @Test fun `sends no max_tokens unless configured`() {
        // A small cap truncates inside the reasoning block and returns no visible
        // answer at all, so the endpoint default is the safer choice.
        reference.maxTokens shouldBe null
    }

    @Test fun `strips a trailing slash from api_base`() {
        val c = Config.fromConf(Yaml.loadString(
            """|clients:
               |- type: openai-compatible
               |  api_base: https://host/serving-endpoints/
               |""".trimMargin()))

        c.clients.first().apiBase shouldBe "https://host/serving-endpoints"
    }

    @Test fun `a client missing api_base is skipped, keeping valid siblings`() {
        val c = Config.fromConf(Yaml.loadString(
            """|clients:
               |- type: openai-compatible
               |  name: broken
               |- type: openai-compatible
               |  name: good
               |  api_base: https://host/v1
               |""".trimMargin()))

        c.clients.map { it.id() } shouldBe listOf("good")
    }

    @Test fun `unknown config keys are ignored, not fatal`() {
        val c = Config.fromConf(Yaml.loadString(
            """|model: m
               |rag_top_k: 5
               |left_prompt: '{color.green}>'
               |document_loaders:
               |  pdf: 'pdftotext $1 -'
               |""".trimMargin()))

        c.model shouldBe "m"
        c.raw.contains("rag_top_k") shouldBe true
    }

    @Test fun `reasoning display accepts its documented spellings`() {
        ReasoningDisplay.parse("show") shouldBe ReasoningDisplay.Show
        ReasoningDisplay.parse("COLLAPSE") shouldBe ReasoningDisplay.Collapse
        ReasoningDisplay.parse("hide") shouldBe ReasoningDisplay.Hide
        ReasoningDisplay.parse("sideways") shouldBe null
    }

    @Test fun `an empty clients list reports a useful error instead of resolving`() {
        Config.fromConf(Yaml.loadString("model: m\n")).defaultTarget.fold({ it }, { "" }) shouldContain
            "unknown model"
    }

    // ---- examples/config.example.yaml ---------------------------------------

    /** Keys documented in the shipped example, in the order they appear. */
    private fun exampleKeys(prefix: String): List<String> {
        val text = String(
            Files.readAllBytes(Paths.get("examples/config.example.yaml")),
            StandardCharsets.UTF_8
        )
        return text.lineSequence()
            .map { it.trim() }
            .filter { it.startsWith(prefix) }
            .map { it.takeWhile { ch -> ch != ':' } }
            .filter { it.all { ch -> ch.isLetterOrDigit() || ch == '_' } }
            .toList()
            .distinct()
    }

    @Test fun `the shipped example config parses`() {
        (Config.load(Paths.get("examples/config.example.yaml")).isOk) shouldBe true
    }

    @Test fun `the example documents the rag defaults it claims to`() {
        val c = Config.load(Paths.get("examples/config.example.yaml")).orNull()!!.rag
        c.enabled shouldBe true
        c.profile shouldBe kotllm.rag.ChunkProfile.Auto
        c.topK shouldBe 8
        c.injectTopK shouldBe 4
        c.injectMaxChars shouldBe 8000
        c.bm25K1 shouldBe 1.2
        c.bm25B shouldBe 0.75
        // Commented out in the example, so the profile decides.
        c.chunkTokens shouldBe null
        c.overlapTokens shouldBe null
    }

    @Test fun `every rag_ key the example documents is one Config actually reads`() {
        // Documenting a key nothing reads is the worst kind of config bug: it
        // looks configured and silently is not.
        //
        // TODO(port): the Scala test reads src/main/scala/kotllm/config/Config.scala;
        // this reads the ported Kotlin source at the equivalent path instead, since
        // that is the parser this build actually runs.
        val source = String(
            Files.readAllBytes(Paths.get("src/main/kotlin/kotllm/config/Config.kt")),
            StandardCharsets.UTF_8
        )
        val documented = exampleKeys("rag_")
        documented.isEmpty() shouldBe false
        documented.forEach { key ->
            source.contains("\"" + key + "\"") shouldBe true
        }
    }

    @Test fun `the example documents the cost keys it claims to`() {
        val p = Config.load(Paths.get("examples/config.example.yaml")).orNull()!!.pricing
        p.dbuRate shouldBe 0.07
        // pricing_enabled: true, so the bundled table is live and the model this
        // project runs on resolves through subsequence matching.
        p.find("databricks-qwen35-122b-a10b")?.input shouldBe 3.143
        p.find("databricks-qwen35-122b-a10b")?.inDbu shouldBe true
        // Both halves of the shipped table, since the example documents both: the
        // Databricks side is priced in DBU and the Azure side directly in USD.
        val gpt = p.find("gpt-4.1-mini")
        gpt?.input shouldBe 0.40
        gpt?.inDbu shouldBe false
    }

    @Test fun `the example's pricing block does not shadow the bundled table`() {
        // Every entry in the example is commented out on purpose. Copying the file
        // must not change what any model costs, which is the whole reason they are
        // commented: an override should be a decision, not a side effect.
        val fromExample = Config.load(Paths.get("examples/config.example.yaml")).orNull()!!.pricing
        val bundled = kotllm.util.Pricing.load(null, null)
        fromExample.entries.map { listOf(it.key, it.input, it.output, it.inDbu) } shouldBe
            bundled.entries.map { listOf(it.key, it.input, it.output, it.inDbu) }
    }

    @Test fun `the pricing shape the example documents is one Pricing actually parses`() {
        // Uncommenting the block is the documented way to override a price, so the
        // block is uncommented here and loaded. Without this, the example could
        // document a shape the parser does not read and nothing would notice --
        // the same failure the rag_ key test above exists to prevent.
        val lines = String(
            Files.readAllBytes(Paths.get("examples/config.example.yaml")),
            StandardCharsets.UTF_8
        ).lines()

        val from = lines.indexOfFirst { it.trim() == "#pricing:" }
        (from >= 0) shouldBe true
        val block = lines
            .drop(from)
            // Stops at the next `# ---- section ----` divider. Without that the
            // divider itself gets un-commented into a bare scalar and the parse
            // fails somewhere unrelated.
            .takeWhile { (it.trim().isEmpty() || it.trim().startsWith("#")) && !it.trim().startsWith("# ----") }
            .map { it.replaceFirst(Regex("^#"), "") }
            .joinToString("\n")

        val loaded = kotllm.util.Pricing.load(Yaml.loadString(block).sub("pricing")!!, null)

        // A Databricks entry, priced in DBU and converted by dbu_rate.
        loaded.find("databricks-glm-5-3-flash")?.inDbu shouldBe true
        // An omitted output defaults to zero, which is what an embedding endpoint
        // needs; the example says so, so it had better be true.
        loaded.find("databricks-gte-large-en")?.output shouldBe 0.0
        // The Azure deployment-name case, which is the entry most Azure users need.
        val prod = loaded.find("prod-chat")
        prod?.input shouldBe 2.00
        prod?.inDbu shouldBe false
        // A zero-priced local endpoint costs nothing rather than reporting unpriced.
        val quad = loaded.costOf("my-vllm-model", 1000000L, 1000000L)!!
        quad.inUsd shouldBe 0.0
        quad.outUsd shouldBe 0.0
        quad.inDbu shouldBe 0.0
        quad.outDbu shouldBe 0.0
    }
}
