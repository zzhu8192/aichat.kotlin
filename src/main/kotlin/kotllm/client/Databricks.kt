package kotllm.client

import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.charset.StandardCharsets
import java.time.Duration

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

import kotllm.config.ClientConfig
import kotllm.config.Config

private val jsonLenient = Json { ignoreUnknownKeys = true; isLenient = true }

/** Client for Databricks AI Gateway serving endpoints, and for any plain
  * OpenAI-compatible server.
  *
  * Transport is `java.net.http` rather than requests-scala on purpose:
  * `BodyHandlers.ofLines` hands back exactly the lazy line stream SSE framing
  * wants. requests-scala stays in the fat jar for LLM-authored code, not for
  * our own transport.
  *
  * The gateway accepts the collection route `{api_base}/chat/completions` with
  * `model` in the body, so no per-endpoint `/invocations` rewrite is needed
  * (docs/clients.md, finding 1).
  */
class DatabricksClient(private val cfg: ClientConfig, private val config: Config) : Client {

    override val id: String get() = cfg.id()

    private val connectTimeout: Duration =
        Duration.ofSeconds((cfg.connectTimeoutSeconds ?: config.connectTimeoutSeconds).toLong())

    private val http: HttpClient =
        HttpClient
            .newBuilder()
            .connectTimeout(connectTimeout)
            // Databricks terminates HTTP/2 fine, but a forced downgrade avoids an
            // upgrade round trip and some corporate proxies mangle h2 streaming.
            .version(HttpClient.Version.HTTP_1_1)
            .followRedirects(HttpClient.Redirect.NORMAL)
            .build()

    private val endpoint: URI get() = URI.create("${cfg.apiBase}/chat/completions")

    /** Whether a streaming request must ask for `stream_options.include_usage`.
      *
      * Databricks puts a cumulative `usage` on every chunk unasked
      * (docs/clients.md, finding 4). Ollama and the rest of the OpenAI-compatible
      * world emit none at all unless it is requested, which is the whole of the
      * "token count stays at zero against Ollama" bug: nothing was wrong with the
      * decoder, the server was never told to send the numbers. Asked for only
      * where it is needed, because an unknown body key is a 400 on a strict
      * gateway and the Databricks path is the live-tested one. */
    private val includeUsage: Boolean =
        when (Provider.infer(cfg.kind, cfg.apiBase)) {
            is Provider.Ollama, is Provider.GenericOpenAI -> true
            else -> false
        }

    private fun request(req: ChatRequest, sse: Boolean): HttpRequest {
        val body = req.encode().toString()
        val b = HttpRequest
            .newBuilder()
            .uri(endpoint)
            .header("Content-Type", "application/json")
            .header("Accept", if (sse) "text/event-stream" else "application/json")
            .POST(HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8))

        cfg.apiKey?.let { k -> b.header("Authorization", "Bearer $k") }

        // A total-request timeout is right for a blocking call but would guillotine
        // a long stream mid-answer, so streaming relies on the connect timeout only.
        if (!sse) cfg.readTimeoutSeconds?.let { s -> b.timeout(Duration.ofSeconds(s.toLong())) }
        return b.build()
    }

    override fun chat(req: ChatRequest): ChatResponse =
        Retry(config.maxRetries, onRetry = ::warnRetry) {
            val resp = http.send(
                request(req.copy(stream = false), sse = false),
                HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8)
            )
            if (resp.statusCode() / 100 != 2)
                throw ChatFailure(resp.statusCode(), resp.body(), retryAfter(resp))
            ChatResponse.decode(jsonLenient.parseToJsonElement(resp.body()))
        }

    override fun chatStream(
        req: ChatRequest,
        onEvent: (StreamEvent) -> Unit,
        keepGoing: (() -> Boolean)?
    ): ChatResponse =
        Retry(config.maxRetries, onRetry = ::warnRetry) {
            val resp = http.send(
                request(req.copy(stream = true, streamOptions = includeUsage), sse = true),
                HttpResponse.BodyHandlers.ofLines()
            )

            if (resp.statusCode() / 100 != 2) {
                // The error body arrived as "lines"; reassemble it to report properly.
                val body = resp.body().iterator().asSequence().joinToString("\n")
                throw ChatFailure(resp.statusCode(), body, retryAfter(resp))
            }

            val assembler = StreamAssembler()
            var model: String? = null

            Sse.foreach(resp.body().iterator(), keepGoing ?: { true }) { payload ->
                // One malformed chunk should not abort a good generation; the stream is
                // best-effort by nature.
                try {
                    val json = jsonLenient.parseToJsonElement(payload)
                    if (model == null)
                        model = ((json as? JsonObject)?.get("model") as? JsonPrimitive)?.takeIf { it.isString }?.content
                    Chunk.decode(json).forEach { e ->
                        assembler.accept(e)
                        onEvent(e)
                    }
                } catch (e: Exception) {
                    // ignore malformed chunk
                }
            }

            assembler.result(model ?: req.model)
        }

    /** The provider's own backoff instruction, when it sends one. Only the
      * delta-seconds form is handled; the HTTP-date form is legal but no LLM
      * gateway uses it, and a misparse would be worse than falling back to our
      * own schedule. */
    private fun retryAfter(resp: HttpResponse<*>): Int? =
        resp
            .headers()
            .firstValue("retry-after")
            .let { if (it.isPresent) it.get() else null }
            ?.let { v -> v.trim().toIntOrNull() }
            ?.takeIf { it >= 0 }

    private fun warnRetry(attempt: Int, reason: String) {
        System.err.println("kotllm: $reason from ${cfg.id()}, retrying (attempt $attempt)...")
    }
}
