package kotllm.template

import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.collections.shouldNotContain
import kotlin.test.Test
import kotlin.test.assertFailsWith

import kotllm.*
import kotllm.Runner
import kotllm.client.ChatRequest
import kotllm.client.ChatResponse
import kotllm.client.Client
import kotllm.client.StreamEvent
import kotllm.TestIsolation
import kotllm.config.*
import kotllm.util.TurnMetrics
import kotllm.util.Yaml

class HarnessVarsTest {

    private object UnusedClient : Client {
        override val id = "test"
        override fun chat(req: ChatRequest): ChatResponse = throw UnsupportedOperationException()
        override fun chatStream(
            req: ChatRequest,
            onEvent: (StreamEvent) -> Unit,
            keepGoing: (() -> Boolean)?
        ): ChatResponse = throw UnsupportedOperationException()
    }

    private fun runner(yaml: String = "model: m\n"): Runner {
        val config = Config.fromConf(Yaml.loadString(yaml))
        val cc = ClientConfig("openai-compatible", "dbx", "https://host/serving-endpoints", "k", emptyList(), null, null)
        val model = ModelConfig("qwen35-122b", 261000, null, supportsVision = false, supportsFunctionCalling = true)
        return Runner(TestIsolation.isolate(config), Session(), ModelTarget(cc, model), UnusedClient)
    }

    private fun valueOf(r: Runner, name: String): String =
        (HarnessVars.asMap(r)[name] ?: throw AssertionError("no such variable: $name")).invoke()

    @Test fun `every variable computes without throwing`() {
        val r = runner()
        HarnessVars.table(r).forEach { v ->
            v.value()
        }
    }

    @Test fun `every variable has a non-empty name and description`() {
        HarnessVars.table(runner()).forEach { v ->
            (v.name.trim().isNotEmpty()) shouldBe true
            (v.description.trim().isNotEmpty()) shouldBe true
        }
    }

    @Test fun `names are unique, so none shadows another`() {
        val names = HarnessVars.table(runner()).map { it.name }
        names.distinct().size shouldBe names.size
    }

    @Test fun `every name is reference-shaped, so it can actually be used`() {
        HarnessVars.table(runner()).forEach { v ->
            Vars.expand("{{${v.name}}}", HarnessVars.asMap(runner())).isOk shouldBe true
        }
    }

    @Test fun `endpoint and model come from the resolved target`() {
        val r = runner()
        valueOf(r, "model") shouldBe "qwen35-122b"
        valueOf(r, "model.id") shouldBe "dbx:qwen35-122b"
        valueOf(r, "endpoint") shouldBe "https://host/serving-endpoints"
        valueOf(r, "client") shouldBe "dbx"
        valueOf(r, "context.limit") shouldBe "261000"
    }

    @Test fun `usage variables read zero before any request, not unknown`() {
        val r = runner()
        valueOf(r, "tokens.in") shouldBe "0"
        valueOf(r, "requests") shouldBe "0"
        valueOf(r, "context.used") shouldBe "0"
        valueOf(r, "stats") shouldContain "no requests yet"
    }

    @Test fun `usage variables track the ledger`() {
        val r = runner()
        r.session.ledger.record(
            TurnMetrics(1, 0L, "qwen35-122b", 1000, 200, 0, 1500L, 500L, emptyList(), truncated = false, aborted = false)
        )

        valueOf(r, "tokens.in") shouldBe "1000"
        valueOf(r, "tokens.out") shouldBe "200"
        valueOf(r, "tokens.total") shouldBe "1200"
        valueOf(r, "requests") shouldBe "1"
        valueOf(r, "context.used") shouldBe "1000"
        valueOf(r, "context.percent") shouldBe "0%" // 1000 of 261000
        valueOf(r, "stats") shouldContain "1 requests"
    }

    @Test fun `tools reads none when none are enabled, and names them when they are`() {
        valueOf(runner(), "tools") shouldBe "none"
        valueOf(runner("model: m\nfunction_calling: true\nuse_tools: all\n"), "tools") shouldContain "groovy_eval"
    }

    @Test fun `rate variables appear only when a limit is configured`() {
        HarnessVars.asMap(runner()).keys shouldNotContain "rate.used"

        val limited = runner("model: m\nrate_limit:\n  input_tokens_per_minute: 1000\n")
        HarnessVars.asMap(limited).keys shouldContain "rate.used"
        valueOf(limited, "rate.window") shouldBe "60"
    }

    @Test fun `machine variables report something concrete`() {
        val r = runner()
        valueOf(r, "pwd").trim().isNotEmpty() shouldBe true
        val shell = valueOf(r, "shell")
        (shell == "cmd.exe" || shell == "sh" || shell.contains("/")) shouldBe true
        // The variable is named `kotlin` and means it: it reports the version of
        // the embedded interpreter, which is what the model is writing against.
        valueOf(r, "groovy") shouldBe groovy.lang.GroovySystem.getVersion()
        valueOf(r, "kotllm") shouldBe kotllm.Cli.Version
    }

    @Test fun `time variables are well formed`() {
        val r = runner()
        Regex("""\d{4}-\d{2}-\d{2}""").matches(valueOf(r, "date")) shouldBe true
        Regex("""\d{2}:\d{2}:\d{2}""").matches(valueOf(r, "time")) shouldBe true
        (valueOf(r, "epoch_ms").toLong() > 1_600_000_000_000L) shouldBe true
    }
}
