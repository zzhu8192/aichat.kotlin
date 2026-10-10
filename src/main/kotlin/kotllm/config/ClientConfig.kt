// Scala's `case class X` + `object X` is a class and its companion. Kotlin
// forbids two top-level declarations sharing a name: the companion must be
// declared INSIDE the class. Call sites are unchanged either way.
package kotllm.config

import kotllm.*
import kotllm.util.Conf

/** One entry of the models: list under a client. */
data class ModelConfig(
    val name: String,
    val maxInputTokens: Int? = null,
    val maxOutputTokens: Int? = null,
    val supportsVision: Boolean = false,
    val supportsFunctionCalling: Boolean = false
) {
    companion object {

      /** A model the config never declared. The gateway is the authority on which
        * names are valid, so an undeclared name is passed through rather than
        * rejected; we just have no context window or capability hints for it.
        */
      fun synthetic(name: String): ModelConfig =
        ModelConfig(name, supportsVision = false, supportsFunctionCalling = true)

      fun from(c: Conf): ModelConfig? = c.str("name")?.let { name ->
        ModelConfig(
          name = name,
          maxInputTokens = c.int("max_input_tokens"),
          maxOutputTokens = c.int("max_output_tokens"),
          supportsVision = c.bool("supports_vision") ?: false,
          supportsFunctionCalling = c.bool("supports_function_calling") ?: false
        )
      }
    }
}


/** One entry of the top-level clients: list.
  *
  * type in YAML becomes kind here. Recognised kinds:
  *   - openai-compatible / databricks - the Databricks AI Gateway and any
  *     plain OpenAI-compatible server. Both route to the same client.
  *   - azure-foundry - Azure AI Foundry.
  *   - ollama - a local (or remote) Ollama server, over its OpenAI-compatible
  *     /v1 surface rather than its own API.
  */
data class ClientConfig(
    val kind: String,
    val name: String? = null,
    val apiBase: String,
    val apiKey: String? = null,
    val models: List<ModelConfig>,
    val connectTimeoutSeconds: Int? = null,
    val readTimeoutSeconds: Int? = null,
    val rateLimit: RateLimitConfig? = null,
    val apiVersion: String? = null
) {

  /** How this client is addressed in a client:model model id. */
  fun id(): String = name ?: kind

  fun model(name: String): ModelConfig? = models.find { it.name == name }

    companion object {

      /** Ollama's default, so an ollama client needs no api_base at all. The
        * appeal of that server is that it needs no setup, and making the user look
        * up a port number they never chose is setup.
        */
      const val OllamaDefaultBase = "http://localhost:11434/v1"

      /** Ollama serves the OpenAI API under /v1, but its own documentation and
        * every example show the bare host - so that is what people paste, and the
        * request then goes to /chat/completions at the root and 404s with nothing
        * explaining why.
        *
        * Appending the suffix is safe because it is only done for a client already
        * identified as Ollama, and only when no version segment is present: a base
        * ending in /v1, or a deliberate /v1beta, is left exactly as written.
        */
      fun normaliseOllamaBase(apiBase: String): String {
        val trimmed = apiBase.replace(Regex("/+$"), "")
        val last = trimmed.reversed().takeWhile { it != '/' }.reversed()
        return if (last.matches(Regex("v[0-9].*"))) trimmed else "$trimmed/v1"
      }

      fun from(c: Conf): Outcome<ClientConfig> {
        val kind = c.str("type") ?: "openai-compatible"
        val name = c.str("name")
        val isOllama =
          kind.trim().lowercase() == "ollama" ||
            c.str("api_base")?.contains(":11434") == true

        return when (val apiBase = c.str("api_base") ?: if (isOllama) OllamaDefaultBase else null) {
          null ->
            err("client '${name ?: kind}' is missing required key 'api_base'")
          else -> {
            val extra = c.sub("extra") ?: Conf.empty
            ok(
              ClientConfig(
                kind = kind,
                name = name,
                // A trailing slash here would produce //chat/completions, which
                // some gateways 404 on. Normalise once, at the edge.
                apiBase =
                  if (isOllama) normaliseOllamaBase(apiBase) else apiBase.replace(Regex("/+$"), ""),
                apiKey = c.str("api_key"),
                models = c.subs("models").mapNotNull { ModelConfig.from(it) },
                connectTimeoutSeconds = extra.int("connect_timeout") ?: c.int("connect_timeout"),
                readTimeoutSeconds = extra.int("read_timeout") ?: c.int("read_timeout"),
                rateLimit = RateLimitConfig.from(c, "rate_limit"),
                apiVersion = extra.str("api_version") ?: c.str("api_version")
              )
            )
          }
        }
      }
    }
}


/** A fully resolved target: which endpoint to call, and with which model name. */
data class ModelTarget(val client: ClientConfig, val model: ModelConfig) {
  fun id(): String = "${client.id()}:${model.name}"
}
