package kotllm.client

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject

import kotllm.config.Config
import kotllm.config.ModelTarget

import kotllm.*

sealed interface StreamEvent {
    data class TextDelta(val text: String) : StreamEvent
    data class ReasoningDelta(val text: String) : StreamEvent
    data class ToolCallDelta(
        val index: Int,
        val id: String?,
        val name: String?,
        val arguments: String
    ) : StreamEvent
    data class UsageUpdate(val usage: Usage) : StreamEvent
    data class Finished(val reason: String?) : StreamEvent
}

/** An HTTP-level failure from a chat endpoint.
  *
  * `retryAfterSeconds` carries the provider's own `Retry-After` header when it
  * sent one. That is authoritative -- it beats any backoff curve we invent --
  * so [[Retry]] honours it in preference to its own schedule.
  */
class ChatFailure(
    val status: Int,
    val body: String,
    message: String,
    val retryAfterSeconds: Int? = null
) : RuntimeException(message) {
    val retryable: Boolean
        get() = Retry.retryableStatus(status)

    /** True for the status that means "you are being rate limited", as opposed
      * to a transient server fault. */
    val rateLimited: Boolean
        get() = status == 429

    companion object {
        /** One instance: a Json format is expensive to build and this runs per
          * failed request. */
        private val Lenient = Json { ignoreUnknownKeys = true; isLenient = true }

        /** Gateways bury the useful part of an error in varying envelopes; dig out a
          * human-readable message instead of printing a wall of JSON. */
        operator fun invoke(
            status: Int,
            body: String,
            retryAfterSeconds: Int? = null
        ): ChatFailure {
            val detail = try {
                val json = Lenient.parseToJsonElement(body)
                when (json) {
                    is JsonObject -> {
                        val direct = (json["message"] as? JsonPrimitive)?.takeIf { it.isString }?.content
                        val nested = (json["error"] as? JsonObject)
                            ?.get("message")
                            ?.let { (it as? JsonPrimitive)?.takeIf { p -> p.isString }?.content }
                        val plain = (json["error"] as? JsonPrimitive)?.takeIf { it.isString }?.content
                        direct ?: nested ?: plain ?: body
                    }
                    else -> body
                }
            } catch (e: Exception) {
                body
            }

            val short = detail.replace("\\s+".toRegex(), " ").take(500)
            val hint = retryAfterSeconds?.let { " (retry after ${it}s)" } ?: ""
            return ChatFailure(status, body, "endpoint returned HTTP $status: $short$hint", retryAfterSeconds)
        }
    }
}

interface Client {
    /** Stable identifier, used in messages and `/info`. */
    val id: String

    fun chat(req: ChatRequest): ChatResponse

    /** Streams a completion, calling `onEvent` as deltas arrive and returning the
      * fully assembled response.
      *
      * @param keepGoing polled between events; returning false stops the stream
      *                  early and returns whatever was assembled so far.
      */
    fun chatStream(
        req: ChatRequest,
        onEvent: (StreamEvent) -> Unit,
        keepGoing: (() -> Boolean)? = null
    ): ChatResponse

    companion object {
        fun forTarget(target: ModelTarget, config: Config): Outcome<Client> =
            when (val provider = Provider.infer(target.client.kind, target.client.apiBase)) {
                Provider.AzureFoundry ->
                    ok(AzureFoundryClient(target.client, config))
                Provider.Databricks, Provider.GenericOpenAI, Provider.Ollama ->
                    // One transport for all three: Ollama's `/v1` *is* the OpenAI surface
                    // this client already speaks.
                    ok(DatabricksClient(target.client, config))
                is Provider.Unknown ->
                    err("unknown client type '${provider.kind}' for client '${target.client.id()}'")
            }
    }
}

sealed interface Provider {
    data object AzureFoundry : Provider
    data object Databricks : Provider
    data object GenericOpenAI : Provider

    /** Ollama, which speaks OpenAI on `/v1` and is therefore **not** a new
      * transport.
      *
      * A distinct `Provider` only so three things can differ: the `/v1` suffix is
      * filled in when the base URL omits it, no api_key is expected, and its
      * turns are costed as local rather than reported as `unpriced`. The wire
      * format is the generic one and the client is `DatabricksClient`.
      *
      * Deliberately not Ollama's own `/api/chat` -- work on the OpenAI path serves
      * vLLM, llama.cpp, LM Studio and LocalAI too. See docs/clients.md. */
    data object Ollama : Provider

    data class Unknown(val kind: String) : Provider

    companion object {
        /** Infers the provider from the configured client kind and api_base URL.
          *
          * An explicit azure/databricks kind wins; otherwise the URL decides, and a
          * URL beats the generic `openai-compatible` label. Port 11434 on any host
          * infers Ollama. */
        fun infer(kind: String, apiBase: String): Provider {
            val normKind = kind.trim().lowercase()
            val normUrl = apiBase.trim().lowercase()

            return when {
                normKind == "azure-foundry" || normKind == "azure-openai" -> AzureFoundry
                normKind == "databricks" -> Databricks
                normKind == "ollama" -> Ollama
                // Port 11434 is Ollama's and nothing else's, so an unlabelled client on it
                // is one. Worth inferring because the alternative is a `type` the user has
                // to know to write, for a server whose whole appeal is that it needs no
                // setup.
                normUrl.contains(":11434") -> Ollama
                normUrl.contains("services.ai.azure.com") ||
                    normUrl.contains("openai.azure.com") ||
                    normUrl.contains("cognitiveservices.azure.com") ||
                    normUrl.contains("api.cognitive.microsoft.com") -> AzureFoundry
                normUrl.contains("azuredatabricks.net") ||
                    normUrl.contains("databricks.com") ||
                    normUrl.contains("/serving-endpoints") -> Databricks
                normKind == "openai-compatible" || normKind == "openai" -> GenericOpenAI
                else -> Unknown(kind)
            }
        }
    }
}

/** Rebuilds a complete [[ChatResponse]] from a stream of deltas.
  *
  * Kept separate from any one client so every transport assembles identically,
  * and so it can be unit-tested against recorded chunks.
  */
class StreamAssembler {
    private val text = StringBuilder()
    private val reasoning = StringBuilder()
    private val calls = linkedMapOf<Int, Triple<String?, String?, StringBuilder>>()
    private var finish: String? = null
    private var usage: Usage? = null

    // Block body, not `= when (...)`. Scala's `def accept(e): Unit = e match`
    // discards each branch's value; a Kotlin expression body declared Unit
    // demands every branch already be Unit, which `text.append(...)` and a
    // one-armed `if` are not.
    fun accept(e: StreamEvent) {
        when (e) {
        is StreamEvent.TextDelta -> text.append(e.text)
        is StreamEvent.ReasoningDelta -> reasoning.append(e.text)
        is StreamEvent.UsageUpdate -> usage = e.usage // cumulative, so replace
        is StreamEvent.Finished -> if (finish == null) finish = e.reason
        is StreamEvent.ToolCallDelta -> {
            var (curId, curName, buf) = calls.getOrPut(e.index) {
                Triple(null, null, StringBuilder())
            }
            buf.append(e.arguments)
            calls[e.index] = Triple(e.id ?: curId, e.name ?: curName, buf)
        }
    }
    }

    fun hasText(): Boolean = text.isNotEmpty()

    fun result(model: String?): ChatResponse {
        val parts = mutableListOf<Part>()
        if (reasoning.isNotEmpty()) parts.add(Part.Reasoning(reasoning.toString()))
        if (text.isNotEmpty()) parts.add(Part.Text(text.toString()))

        val toolCalls = calls.entries.map { (idx, triple) ->
            ToolCall(triple.first ?: "call_$idx", triple.second ?: "", triple.third.toString())
        }

        return ChatResponse(
            message = Message("assistant", parts, toolCalls),
            finishReason = finish,
            usage = usage,
            model = model
        )
    }
}
