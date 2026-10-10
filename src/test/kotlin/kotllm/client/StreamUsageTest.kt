package kotllm.client

import com.sun.net.httpserver.HttpServer
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import kotlinx.serialization.json.*
import java.net.InetSocketAddress
import java.nio.charset.StandardCharsets
import kotlin.test.Test

import kotllm.*
import kotllm.config.ClientConfig
import kotllm.config.Config
import kotllm.util.Yaml

/** Whether the client asks the server for token counts on a streamed turn.
  *
  * Over a real socket, because the bug was not in any decoder: `usage` decodes
  * fine and the assembler replaces it correctly, and the token counter still
  * sat at zero against Ollama for the whole of a session. The only place the
  * fault could be was the request body, so that is what is asserted -- the
  * bytes on the wire, not a field on a case class.
  */
class StreamUsageTest {

    /** Replies with two SSE events -- one of content, then a usage-only chunk --
      * and keeps the request body for inspection. */
    private fun <A> withServer(f: (String, () -> String) -> A): A {
        var body = ""
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/") { ex ->
            body = String(ex.requestBody.readAllBytes(), StandardCharsets.UTF_8)
            val sse =
                "data: {\"model\":\"gemma4:26b\",\"choices\":[{\"index\":0,\"delta\":{\"content\":\"hi\"}}]}\n\n" +
                    "data: {\"choices\":[],\"usage\":{\"prompt_tokens\":1234," +
                    "\"completion_tokens\":56,\"total_tokens\":1290}}\n\n" +
                    "data: [DONE]\n\n"
            val bytes = sse.toByteArray(StandardCharsets.UTF_8)
            ex.responseHeaders.add("Content-Type", "text/event-stream")
            ex.sendResponseHeaders(200, bytes.size.toLong())
            ex.responseBody.write(bytes)
            ex.close()
        }
        server.start()
        return try {
            f("http://127.0.0.1:${server.address.port}", { body })
        } finally {
            server.stop(0)
        }
    }

    private fun config() = Config.fromConf(Yaml.loadString("model: m\nmax_retries: 0\n"))

    private fun clientCfg(yaml: String): ClientConfig =
        ClientConfig.from(Yaml.loadString(yaml)).getOrElse { throw AssertionError("client config did not parse") }

    private fun stream(cfg: ClientConfig): ChatResponse =
        DatabricksClient(cfg, config())
            .chatStream(ChatRequest("gemma4:26b", listOf(Message.user("hello"))), onEvent = {})

    @Test
    fun `an Ollama turn asks for usage, so the token counter is not stuck at zero`() {
        // Ollama's /v1 surface sends no `usage` at all unless `include_usage` is
        // set -- unlike Databricks, which sends it on every chunk unasked. Without
        // this the session ledger records 0/0 for every local turn, which is what
        // gemma4:26b did.
        withServer { base, body ->
            val resp = stream(clientCfg("type: ollama\nname: local\napi_base: $base\n"))

            Json.parseToJsonElement(body()).jsonObject["stream_options"]!!
                .jsonObject["include_usage"]!!.jsonPrimitive.boolean shouldBe true
            resp.usage?.promptTokens shouldBe 1234
            resp.usage?.completionTokens shouldBe 56
        }
    }

    @Test
    fun `any other OpenAI-compatible server is asked too`() {
        withServer { base, body ->
            val cfg = clientCfg("type: openai-compatible\nname: hosted\napi_base: $base/v1\n")
            stream(cfg).usage?.promptTokens shouldBe 1234
            Json.parseToJsonElement(body()).jsonObject["stream_options"] shouldNotBe null
        }
    }

    @Test
    fun `Databricks is not, because it reports usage unasked and the key is untested there`() {
        // The live-tested path. An unknown body key is a 400 on a strict gateway,
        // and there is nothing to gain: every chunk already carries a cumulative
        // `usage` (docs/clients.md, finding 4).
        withServer { base, body ->
            val cfg = clientCfg("type: databricks\nname: dbx\napi_base: $base\napi_key: k\n")
            stream(cfg).usage?.promptTokens shouldBe 1234
            Json.parseToJsonElement(body()).jsonObject["stream_options"] shouldBe null
        }
    }
}
