package kotllm.agent

import java.net.InetSocketAddress
import java.nio.charset.StandardCharsets
import java.nio.file.Files

import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import kotlin.test.Test
import io.kotest.assertions.withClue
import io.kotest.matchers.shouldBe

import kotlinx.serialization.json.*

import kotllm.*
import kotllm.Runner
import kotllm.TestIsolation
import kotllm.client.Client
import kotllm.config.*
import kotllm.util.Yaml

/** Choosing the model per `llm()` call, by alias.
  *
  * Over a real socket, because the thing worth asserting is which model name
  * left the machine. `switchModel` builds a *new client* for the new target,
  * so a stubbed transport would be swapped out by the very code under test and
  * the assertion would be about nothing.
  */
class AgentModelTest {

    /** Records the `model` field of every request and answers each one. */
    private fun <A> withServer(f: (String, MutableList<String>) -> A): A {
        val seen = mutableListOf<String>()
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/") { ex: HttpExchange ->
            val body = Json.parseToJsonElement(String(ex.requestBody.readAllBytes(), StandardCharsets.UTF_8))
            val model = body.jsonObject["model"]!!.jsonPrimitive.content
            synchronized(seen) { seen.add(model) }
            val reply = buildJsonObject {
                put("model", model)
                putJsonArray("choices") {
                    addJsonObject {
                        put("index", 0)
                        putJsonObject("message") {
                            put("role", "assistant")
                            put("content", "ok")
                        }
                        put("finish_reason", "stop")
                    }
                }
                putJsonObject("usage") {
                    put("prompt_tokens", 5)
                    put("completion_tokens", 1)
                    put("total_tokens", 6)
                }
            }
            val bytes = reply.toString().toByteArray(StandardCharsets.UTF_8)
            ex.responseHeaders.add("Content-Type", "application/json")
            ex.sendResponseHeaders(200, bytes.size.toLong())
            ex.responseBody.write(bytes)
            ex.close()
        }
        server.start()
        return try {
            f("http://127.0.0.1:${server.address.port}/v1", seen)
        } finally {
            server.stop(0)
        }
    }

    /** Two models, both served by the same test endpoint so only the model
      * name distinguishes them -- which is exactly what is being asserted. The
      * alias names are abbreviations of the models, not categories: kotllm has
      * no notion of a model category. */
    private fun yaml(base: String) =
        """model: big
          |stream: false
          |save: false
          |aliases:
          |  small: local:small-model
          |  big: hosted:big-model
          |clients:
          |- type: openai-compatible
          |  name: hosted
          |  api_base: $base
          |  api_key: k
          |  models:
          |  - name: big-model
          |    max_input_tokens: 100000
          |- type: openai-compatible
          |  name: local
          |  api_base: $base
          |  api_key: k
          |  models:
          |  - name: small-model
          |    max_input_tokens: 32000
          |""".trimMargin()

    private fun runAgent(base: String, source: String): Outcome<AgentResult> {
        val config = TestIsolation.isolate(Config.fromConf(Yaml.loadString(yaml(base))))
        val target = config.defaultTarget.getOrElse { throw AssertionError("the default model did not resolve") }
        val client = Client.forTarget(target, config).getOrElse { throw AssertionError("no client for the target") }
        val runner = Runner(config, Session(), target, client, quiet = true)
        return Agent.run(runner, "spec", source, emptyMap(), dryRun = false,
            base = Files.createTempDirectory("kotllm-agent-model"))
    }

    @Test fun `alias picks the model, and leaving it out sends the default`() {
        withServer { base, seen ->
            val result = runAgent(
                base,
                """<% llm("one", false, "small") %><% llm("two", false, "") %><% llm("three") %>"""
            )

            withClue(result.fold({ it }, { "" })) { result.isOk shouldBe true }
            seen.toList() shouldBe listOf("small-model", "big-model", "big-model")
        }
    }

    @Test fun `the model is put back afterwards, so one call cannot retune the run`() {
        // The override is a mutate-and-restore on the shared runner. If the
        // restore were skipped, every later call in the file would silently
        // inherit the cheap tier -- and on a real config that is a quality
        // regression nothing on screen would explain.
        withServer { base, seen ->
            runAgent(base, """<% llm("one", false, "small") %><% llm("after") %>""").isOk shouldBe true
            seen.toList() shouldBe listOf("small-model", "big-model")
        }
    }

    @Test fun `a fresh call takes the alias in the same position`() {
        // `fresh` and `alias` are adjacent positional parameters, so getting one
        // right and the other wrong is the transposition worth a guard.
        withServer { base, seen ->
            runAgent(base, """<% llm("one", true, "small") %>""").isOk shouldBe true
            seen.toList() shouldBe listOf("small-model")
        }
    }

    @Test fun `a mistyped alias names the model it fell back to, rather than answering quietly`() {
        // The dangerous outcome: a typo answers from the expensive default and
        // looks completely normal. The run continues, but it is not silent.
        withServer { base, seen ->
            runAgent(base, """<% llm("one", false, "smal") %>""").isOk shouldBe true
            seen.toList() shouldBe listOf("big-model")
        }
    }
}
