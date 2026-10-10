package kotllm.client

import kotlin.test.Test
import kotlin.test.assertFailsWith
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Assumptions
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import kotlinx.serialization.json.*

import kotllm.*
import kotllm.config.Config
import kotllm.config.Paths

/**
 * Integration tests against a real serving endpoint.
 *
 * Excluded from `mvn test`; run with `mvn -Plive test`. Credentials come from
 * the config file that `KOTLLM_CONFIG_DIR` points at, never from this
 * repository. Each test cancels rather than fails when no usable config is
 * present, so `-Plive` on a fresh checkout reports "canceled" instead of a
 * misleading red build.
 */
class DatabricksLiveTest {

    private val setup: Triple<Config, Client, String>? by lazy {
        Config.load(Paths.configFile()).orNull()?.let { config ->
            val target = config.resolveModel(null).orNull()
            val client = target?.let { t -> Client.forTarget(t, config).orNull() }
            if (target != null && client != null && target.client.apiKey != null) {
                Triple(config, client, target.model.name)
            } else {
                null
            }
        }
    }

    private fun withLive(test: (Client, String) -> Unit) {
        val s = setup
        Assumptions.assumeTrue(s != null) {
            "no usable endpoint config at ${Paths.configFile()}; set KOTLLM_CONFIG_DIR"
        }
        test(s!!.second, s.third)
    }

    // maxTokens is deliberately left unset in the tests that expect an answer.
    // Reasoning length is wildly unpredictable on this model -- "Count from 1 to
    // 3, digits only." consumed a full 2048-token budget and emitted no text part
    // at all -- so any cap we pick here is a flaky test waiting to happen. This
    // mirrors the production default; see `max_tokens` in Config.
    @Tag("Live")
    @Test fun `a blocking call returns visible text`() {
        withLive { client, model ->
            val resp = client.chat(
                ChatRequest(
                    model = model,
                    messages = listOf(Message.system("Reply with one word."), Message.user("Say OK"))
                )
            )

            resp.text.trim().isEmpty() shouldBe false
            ((resp.usage?.completionTokens ?: 0) > 0) shouldBe true
            resp.finishReason shouldBe "stop"
        }
    }

    @Tag("Live")
    @Test fun `a streaming call delivers text deltas and cumulative usage`() {
        withLive { client, model ->
            val events = mutableListOf<StreamEvent>()
            val resp = client.chatStream(
                ChatRequest(
                    model = model,
                    messages = listOf(Message.system("Reply with one word."), Message.user("Say OK")),
                    stream = true
                ),
                onEvent = { events.add(it) }
            )

            resp.text.trim().isEmpty() shouldBe false
            (events.count { it is StreamEvent.TextDelta } > 0) shouldBe true

            // Usage repeats per chunk and is cumulative, so the last value must be
            // the largest and the assembler must have kept only that one.
            val usages = events.filterIsInstance<StreamEvent.UsageUpdate>().map { it.usage.totalTokens }
            if (usages.size > 1) usages.last() shouldBe usages.max()
            (resp.usage?.totalTokens) shouldBe usages.max()
        }
    }

    /**
     * The failure mode CLAUDE.md finding 3 exists to describe, pinned down as a
     * test: a tight budget is swallowed whole by the reasoning block and the
     * reply carries no text part at all. Everything downstream -- the empty-reply
     * warning in Runner, the decision not to default max_tokens -- rests on
     * this, so it is worth asserting against the real endpoint rather than
     * trusting a note.
     */
    @Tag("Live")
    @Test fun `a tight max_tokens truncates inside reasoning and yields no text`() {
        withLive { client, model ->
            val resp = client.chat(
                ChatRequest(
                    model = model,
                    messages = listOf(Message.user("What is 2+2? Answer briefly.")),
                    maxTokens = 16
                )
            )

            resp.finishReason shouldBe "length"
            resp.truncated shouldBe true
            resp.text shouldBe ""
            resp.reasoning.isEmpty() shouldBe false
        }
    }

    @Tag("Live")
    @Test fun `the endpoint returns tool calls in the standard OpenAI shape`() {
        withLive { client, model ->
            val schema = Json.parseToJsonElement(
                """{"type":"object","properties":{"city":{"type":"string"}},"required":["city"]}"""
            )
            val resp = client.chat(
                ChatRequest(
                    model = model,
                    messages = listOf(Message.user("What is the weather in Chicago? Use the tool.")),
                    maxTokens = 2048,
                    tools = listOf(ToolDef("get_weather", "Get the weather for a city", schema))
                )
            )

            resp.finishReason shouldBe "tool_calls"
            resp.toolCalls.size shouldBe 1
            resp.toolCalls.first().name shouldBe "get_weather"
            Json.parseToJsonElement(resp.toolCalls.first().arguments)
                .jsonObject["city"]!!.jsonPrimitive.content.lowercase() shouldContain "chicago"
        }
    }

    @Tag("Live")
    @Test fun `a bad api key fails with a clear, non-retryable error`() {
        val s = setup
        Assumptions.assumeTrue(s != null) { "no usable endpoint config" }
        val (config, _, model) = s!!
        val target = config.resolveModel(null).orNull()!!
        val broken = target.client.copy(apiKey = "dapi-definitely-not-valid")
        val client = DatabricksClient(broken, config.copy(maxRetries = 1))

        val failure = assertFailsWith<ChatFailure> {
            client.chat(ChatRequest(model, listOf(Message.user("hi"))))
        }
        (failure.status in 400..499) shouldBe true
        failure.retryable shouldBe false
    }
}
