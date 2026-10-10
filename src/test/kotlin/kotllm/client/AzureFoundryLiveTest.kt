package kotllm.client

import java.nio.file.Files
import java.nio.file.Paths as JPaths

import kotlin.test.Test
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Assumptions
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import kotlinx.serialization.json.*

import kotllm.*
import kotllm.config.Config
import kotllm.config.Paths

/**
 * Live integration tests against Azure AI Foundry hosted endpoint.
 *
 * Excluded from `mvn test`; run with `mvn -Plive test`.
 * Loads from `config/config.azure-gpt-5-6-luna.yaml` or default config dir.
 */
class AzureFoundryLiveTest {

    private val setup: Triple<Config, Client, String>? by lazy {
        val localAzureConfig = JPaths.get("config/config.azure-gpt-5-6-luna.yaml")
        val path = if (Files.exists(localAzureConfig)) localAzureConfig else Paths.configFile()
        Config.load(path).orNull()?.let { config ->
            val target = config.resolveModel(null).orNull()
            val client = target?.let { t -> Client.forTarget(t, config).orNull() }
            if (target != null && client != null && client is AzureFoundryClient &&
                (target.client.apiKey != null ||
                    System.getenv("AZURE_OPENAI_API_KEY") != null ||
                    System.getenv("AZURE_API_KEY") != null)
            ) {
                Triple(config, client, target.model.name)
            } else {
                null
            }
        }
    }

    private fun withLive(test: (Client, String) -> Unit) {
        val s = setup
        Assumptions.assumeTrue(s != null) { "no usable Azure AI Foundry config found; skipping live test" }
        test(s!!.second, s.third)
    }

    @Tag("Live")
    @Test fun `Azure AI Foundry a blocking call returns visible text`() {
        withLive { client, model ->
            val resp = client.chat(
                ChatRequest(
                    model = model,
                    messages = listOf(Message.system("Reply with one word."), Message.user("Say hello"))
                )
            )

            resp.text.trim().isEmpty() shouldBe false
            ((resp.usage?.completionTokens ?: 0) > 0) shouldBe true
            resp.finishReason shouldBe "stop"
        }
    }

    @Tag("Live")
    @Test fun `Azure AI Foundry a streaming call delivers text deltas and usage`() {
        withLive { client, model ->
            val events = mutableListOf<StreamEvent>()
            val resp = client.chatStream(
                ChatRequest(
                    model = model,
                    messages = listOf(Message.system("Reply in 3 words."), Message.user("Say hi"))
                ),
                onEvent = { e -> events.add(e) }
            )

            val textDeltas = events.filterIsInstance<StreamEvent.TextDelta>().map { it.text }
            textDeltas.isEmpty() shouldBe false
            resp.text shouldBe textDeltas.joinToString("")
            resp.finishReason shouldBe "stop"
            (resp.usage != null) shouldBe true
            (resp.usage!!.totalTokens > 0) shouldBe true
        }
    }

    @Tag("Live")
    @Test fun `Azure AI Foundry tool call returns function arguments`() {
        withLive { client, model ->
            val tool = ToolDef(
                name = "get_weather",
                description = "Get current weather for location",
                parameters = buildJsonObject {
                    put("type", "object")
                    put("properties", buildJsonObject {
                        put("location", buildJsonObject {
                            put("type", "string")
                        })
                    })
                    put("required", buildJsonArray {
                        add(JsonPrimitive("location"))
                    })
                }
            )

            val resp = client.chat(
                ChatRequest(
                    model = model,
                    messages = listOf(Message.user("What is the weather in Paris? Call the tool.")),
                    tools = listOf(tool)
                )
            )

            resp.finishReason shouldBe "tool_calls"
            resp.toolCalls.isEmpty() shouldBe false
            resp.toolCalls.first().name shouldBe "get_weather"
            resp.toolCalls.first().arguments shouldContain "Paris"
        }
    }
}
