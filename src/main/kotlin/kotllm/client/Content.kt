package kotllm.client

// Wildcard: the JsonPrimitive helpers (contentOrNull, intOrNull) and the
// builder `put` overloads are extension functions in this package.
import kotlinx.serialization.json.*


/**
 * One piece of message content.
 *
 * The Databricks gateway answers with `message.content` as an array of typed
 * parts - an OpenAI chat-completions envelope wrapping Responses-API-style
 * content - while a plain OpenAI-compatible server answers with a bare
 * string. Both shapes decode into a `List<Part>`. See docs/clients.md, finding 2.
 */
sealed interface Part {
    data class Text(val text: String) : Part

    /**
     * Chain-of-thought output. Never replayed back to the endpoint.
     */
    data class Reasoning(val text: String) : Part

    /**
     * An image, as a `data:` URL or a remote one.
     *
     * Output-side only in practice - no endpoint we target returns images -
     * but it decodes too, so `--dry-run` and a replayed history round-trip.
     */
    data class Image(val url: String, val detail: String? = null) : Part {
        /**
         * True for a base64 payload, which is the case every size and token
         * calculation has to special-case: hundreds of thousands of characters
         * that cost a fixed handful of tokens.
         */
        val inline: Boolean get() = url.startsWith("data:")
    }

    /**
     * A part type we do not model. Retained rather than dropped so `--dry-run`
     * and bug reports can show what the endpoint actually sent.
     */
    data class Unknown(val kind: String, val raw: JsonElement) : Part
}

object Content {

    /**
     * Decodes a `content` value that may be a string, an array of parts, a lone
     * part object, or null. Unknown part types never throw.
     */
    fun decode(v: JsonElement): List<Part> = when {
        // `JsonNull` is a JsonPrimitive in kotlinx.serialization, so it must be
        // ruled out FIRST or it falls through to Unknown. Scala's
        // `case ujson.Null => Nil` is a distinct case and cannot be reached by
        // accident; here the subtyping does the opposite of what it looks like.
        v is JsonNull -> emptyList()
        v is JsonPrimitive && v.isString -> {
            val s = v.content
            if (s.isEmpty()) emptyList() else listOf(Part.Text(s))
        }
        v is JsonArray -> v.mapNotNull(::decodePart)
        v is JsonObject -> decodePart(v)?.let { listOf(it) } ?: emptyList()
        else -> listOf(Part.Unknown("", v))
    }

    private fun decodePart(v: JsonElement): Part? = when {
        v is JsonPrimitive -> {
            val s = v.content
            if (s.isEmpty()) null else Part.Text(s)
        }
        v is JsonObject -> {
            when (val kind = kindOf(v)) {
                "text", "output_text", "input_text" -> str(v, "text")?.let { Part.Text(it) }
                "reasoning", "thinking" -> reasoningText(v)?.let { Part.Reasoning(it) }
                "summary_text" -> str(v, "text")?.let { Part.Reasoning(it) }
                "image_url", "input_image" -> {
                    (v["image_url"] as? JsonObject)?.let { i ->
                        str(i, "url")?.let { u ->
                            Part.Image(u, str(i, "detail"))
                        }
                    } ?: str(v, "image_url")?.let { u ->
                        Part.Image(u)
                    }
                }
                else -> {
                    // An unrecognised discriminator must not blank out the reply, so fall
                    // back to any text-ish field before giving up on the part.
                    val textContent = str(v, "text") ?: str(v, "content")
                    if (textContent != null) Part.Text(textContent) else Part.Unknown(kind, v)
                }
            }
        }
        else -> Part.Unknown("", v)
    }

    private fun kindOf(o: JsonObject): String =
        (o["type"] as? JsonPrimitive)?.content ?: ""

    /**
     * Reasoning parts nest their text under `summary[].text`. Some builds put it
     * directly on `.text`, and DeepSeek-style servers use `reasoning_content`;
     * accept all three.
     */
    private fun reasoningText(o: JsonObject): String? {
        val fromSummary = (o["summary"] as? JsonArray)?.mapNotNull { elem ->
            when {
                elem is JsonObject -> str(elem, "text")
                elem is JsonPrimitive -> elem.content.takeIf { it.isNotEmpty() }
                else -> null
            }
        }?.joinToString("")?.takeIf { it.isNotEmpty() }

        return fromSummary ?: str(o, "text") ?: str(o, "reasoning_content")
    }

    private fun str(o: JsonObject, key: String): String? =
        (o[key] as? JsonPrimitive)?.content?.takeIf { it.isNotEmpty() }

    fun text(parts: List<Part>): String =
        parts.filterIsInstance<Part.Text>().joinToString("") { it.text }

    fun reasoning(parts: List<Part>): String =
        parts.filterIsInstance<Part.Reasoning>().joinToString("") { it.text }
}
