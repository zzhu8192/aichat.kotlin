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

private val jsonLenientAzure = Json { ignoreUnknownKeys = true; isLenient = true }

/** Client for Azure AI Foundry hosted models and Azure OpenAI endpoints.
  *
  * Handles the OpenAI v1 route (`/openai/v1/chat/completions`) and classic
  * deployment routes (`/openai/deployments/{model}/chat/completions`), with
  * either `api-key` or Bearer auth. Streaming requests
  * `stream_options: {"include_usage": true}` for exact token accounting.
  */
class AzureFoundryClient(private val cfg: ClientConfig, private val config: Config) : Client {

    override val id: String get() = cfg.id()

    private val connectTimeout: Duration =
        Duration.ofSeconds((cfg.connectTimeoutSeconds ?: config.connectTimeoutSeconds).toLong())

    private val http: HttpClient =
        HttpClient
            .newBuilder()
            .connectTimeout(connectTimeout)
            .version(HttpClient.Version.HTTP_1_1)
            .followRedirects(HttpClient.Redirect.NORMAL)
            .build()

    fun endpoint(model: String): URI =
        AzureFoundryClient.resolveEndpoint(cfg.apiBase, model, cfg.apiVersion)

    private val apiKey: String?
        get() = cfg.apiKey
            ?: System.getenv("AZURE_OPENAI_API_KEY")
            ?: System.getenv("AZURE_API_KEY")

    private fun request(req: ChatRequest, sse: Boolean): HttpRequest {
        val body = req.encode().toString()
        val b = HttpRequest
            .newBuilder()
            .uri(endpoint(req.model))
            .header("Content-Type", "application/json")
            .header("Accept", if (sse) "text/event-stream" else "application/json")
            .POST(HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8))

        apiKey?.let { k ->
            b.header("api-key", k)
            b.header("Authorization", "Bearer $k")
        }

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
            ChatResponse.decode(jsonLenientAzure.parseToJsonElement(resp.body()))
        }

    override fun chatStream(
        req: ChatRequest,
        onEvent: (StreamEvent) -> Unit,
        keepGoing: (() -> Boolean)?
    ): ChatResponse =
        Retry(config.maxRetries, onRetry = ::warnRetry) {
            val resp = http.send(
                request(req.copy(stream = true, streamOptions = true), sse = true),
                HttpResponse.BodyHandlers.ofLines()
            )

            if (resp.statusCode() / 100 != 2) {
                val body = resp.body().iterator().asSequence().joinToString("\n")
                throw ChatFailure(resp.statusCode(), body, retryAfter(resp))
            }

            val assembler = StreamAssembler()
            var model: String? = null

            Sse.foreach(resp.body().iterator(), keepGoing ?: { true }) { payload ->
                try {
                    val json = jsonLenientAzure.parseToJsonElement(payload)
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

    companion object {
        /** Resolves the target chat completions URI for Azure AI Foundry or Azure OpenAI. */
        fun resolveEndpoint(apiBase: String, model: String, apiVersion: String?): URI {
            val base = apiBase.replace(Regex("/+$"), "")
            val uriStr =
                if (base.endsWith("/chat/completions")) base
                else if (base.endsWith("/openai/v1")) "$base/chat/completions"
                else if (base.contains("/openai/v1/")) {
                    if (base.endsWith("/chat/completions")) base else "$base/chat/completions"
                }
                else if (base.contains("/deployments/")) {
                    "$base/chat/completions"
                }
                else if (apiVersion != null) {
                    "$base/openai/deployments/$model/chat/completions"
                }
                else {
                    "$base/openai/v1/chat/completions"
                }

            val withVersion = if (apiVersion != null && apiVersion.trim().isNotEmpty()) {
                val sep = if (uriStr.contains("?")) "&" else "?"
                "$uriStr${sep}api-version=${apiVersion.trim()}"
            } else uriStr

            return URI.create(withVersion)
        }
    }
}
