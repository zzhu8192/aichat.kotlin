package kotllm.client

import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.charset.StandardCharsets
import java.time.Duration

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

import kotllm.*
import kotllm.config.ClientConfig

private val jsonLenientModels = Json { ignoreUnknownKeys = true; isLenient = true }

/** Asks a server which models it has, over OpenAI's `GET /v1/models`.
  *
  * Written against the standard endpoint rather than Ollama's `/api/tags`, so
  * the same call answers for vLLM, llama.cpp, LM Studio, LocalAI and any hosted
  * gateway. It exists because a local server is one `ollama pull` away from a
  * model the config has never heard of.
  *
  * Deliberately narrow:
  *
  *  - **Never called unless asked.** Discovery on startup would put a round
  *    trip in front of `--version`, and a hang in front of the prompt.
  *  - **Failure is a `Left`.** A server that is down is normal for a local one,
  *    and there is a real reason to give rather than an empty list that reads
  *    as "you have no models".
  *  - **No capability guessing.** The endpoint returns ids and nothing else, so
  *    a discovered model gets `ModelConfig.synthetic` and anything declared in
  *    config wins. Inventing a context window would feed a wrong number
  *    straight into compaction.
  */
object Models {

    /** `id` for each model the server reports, in the order it reported them. */
    fun list(cfg: ClientConfig, timeoutSeconds: Int = 10): Outcome<List<String>> {
        val uri = try {
            URI.create("${cfg.apiBase}/models")
        } catch (e: Exception) {
            return err("bad api_base '${cfg.apiBase}': ${e.message}")
        }

        return try {
            val http = HttpClient
                .newBuilder()
                .connectTimeout(Duration.ofSeconds(timeoutSeconds.toLong()))
                .version(HttpClient.Version.HTTP_1_1)
                .followRedirects(HttpClient.Redirect.NORMAL)
                .build()

            val b = HttpRequest
                .newBuilder()
                .uri(uri)
                .timeout(Duration.ofSeconds(timeoutSeconds.toLong()))
                .header("Accept", "application/json")
                .GET()
            cfg.apiKey?.let { k -> b.header("Authorization", "Bearer $k") }

            val resp = http.send(b.build(), HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8))
            if (resp.statusCode() / 100 != 2)
                err("$uri returned HTTP ${resp.statusCode()}: ${resp.body().take(200)}")
            else parse(resp.body())
        } catch (e: Exception) {
            // Distinguish "not running" from "broken", because for a local server
            // the first is routine and the fix is different.
            val why = e.message?.takeIf { it.isNotEmpty() } ?: e::class.simpleName ?: "error"
            err("could not reach ${cfg.apiBase}: $why")
        }
    }

    /** OpenAI's shape is `{"object":"list","data":[{"id":...}]}`. A bare array is
      * accepted too, because more than one local server has shipped that. */
    fun parse(body: String): Outcome<List<String>> =
        try {
            val ids: List<kotlinx.serialization.json.JsonElement> = when (val v = jsonLenientModels.parseToJsonElement(body)) {
                is JsonObject -> (v["data"] as? JsonArray)?.toList() ?: emptyList()
                is JsonArray -> v.toList()
                else -> emptyList()
            }
            val names = ids.mapNotNull { item ->
                when {
                    item is JsonObject -> (item["id"] as? JsonPrimitive)?.takeIf { it.isString }?.content?.takeIf { it.isNotEmpty() }
                    item is JsonPrimitive && item.isString -> item.content
                    else -> null
                }
            }.distinct()
            if (names.isEmpty()) err("the server reported no models") else ok(names)
        } catch (e: Exception) {
            err("could not parse the model list: ${e.message ?: ""}")
        }
}
