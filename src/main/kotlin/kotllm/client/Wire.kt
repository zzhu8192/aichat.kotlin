package kotllm.client

// Wildcard, and it has to be: put(String, String), intOrNull and
// contentOrNull are extension functions in this package, not members of
// JsonObjectBuilder or JsonPrimitive. Importing the classes individually
// compiles the types and loses every helper.
import kotlinx.serialization.json.*


data class ToolCall(val id: String, val name: String, val arguments: String)

data class Usage(val promptTokens: Int, val completionTokens: Int, val totalTokens: Int) {
    companion object {
        fun decode(v: JsonElement): Usage? = when {
            v is JsonObject -> {
                fun n(key: String) = (v[key] as? JsonPrimitive)?.intOrNull ?: 0
                val u = Usage(n("prompt_tokens"), n("completion_tokens"), n("total_tokens"))
                if (u.totalTokens == 0 && u.promptTokens == 0 && u.completionTokens == 0) null else u
            }
            else -> null
        }
    }
}

data class Message(
    val role: String,
    val parts: List<Part> = emptyList(),
    val toolCalls: List<ToolCall> = emptyList(),
    val toolCallId: String? = null
) {
    val text: String
        get() = Content.text(parts)

    val reasoning: String
        get() = Content.reasoning(parts)

    val images: List<Part.Image>
        get() = parts.filterIsInstance<Part.Image>()

    companion object {
        fun system(text: String): Message = Message("system", listOf(Part.Text(text)))
        fun user(text: String): Message = Message("user", listOf(Part.Text(text)))
        fun assistant(text: String): Message = Message("assistant", listOf(Part.Text(text)))

        fun user(text: String, images: List<Part.Image>): Message =
            Message("user", listOf(Part.Text(text)) + images)

        fun tool(callId: String, result: String): Message =
            Message("tool", listOf(Part.Text(result)), toolCallId = callId)

        fun decode(v: JsonElement): Message = when {
            v is JsonObject -> {
                Message(
                    role = (v["role"] as? JsonPrimitive)?.content ?: "assistant",
                    // **The parentheses are load-bearing.** Kotlin's `?:` binds
                    // LOOSER than `+`, so without them this reads as
                    // `content ?: (emptyList() + reasoningParts)` -- and the
                    // reasoning parts are silently dropped on every message
                    // that HAS content, which is almost all of them. Scala's
                    // `.getOrElse(Nil) ++ rest` has the opposite grouping.
                    parts = (v["content"]?.let { Content.decode(it) } ?: emptyList()) +
                        // Some servers hoist reasoning to a sibling field instead of making
                        // it a content part, under any of three names: DeepSeek-style
                        // `reasoning_content`, Ollama's `thinking`, or a bare `reasoning`.
                        // Same list as the streaming path in `Chunk.decode`.
                        listOf("reasoning_content", "thinking", "reasoning")
                            .flatMap { k -> v[k]?.let { listOf(it) } ?: emptyList() }
                            .mapNotNull { el ->
                                when {
                                    el is JsonPrimitive && el.content.isNotEmpty() ->
                                        Part.Reasoning(el.content)
                                    else -> null
                                }
                            },
                    toolCalls = v["tool_calls"]?.let { decodeToolCalls(it) } ?: emptyList(),
                    toolCallId = (v["tool_call_id"] as? JsonPrimitive)?.content
                )
            }
            else -> Message("assistant")
        }

        private fun decodeToolCalls(v: JsonElement): List<ToolCall> = when {
            v is JsonArray -> v.mapNotNull { item ->
                when {
                    item is JsonObject -> {
                        val fn = (item["function"] as? JsonObject)
                        ToolCall(
                            id = (item["id"] as? JsonPrimitive)?.content ?: "",
                            name = (fn?.get("name") as? JsonPrimitive)?.content ?: "",
                            arguments = (fn?.get("arguments") as? JsonPrimitive)?.content ?: ""
                        )
                    }
                    else -> null
                }
            }
            else -> emptyList()
        }

        /** Request-side encoding.
          *
          * Content goes out as a plain string even though the gateway answers with
          * parts: every OpenAI-compatible server accepts a string on input, and
          * reasoning is deliberately dropped here -- replaying it burns budget and
          * endpoints may reject unknown part types on input (docs/clients.md, finding 3).
          */
        fun encode(m: Message): JsonObject {
            val map = mutableMapOf<String, JsonElement>("role" to JsonPrimitive(m.role))
            val text = m.text
            val images = m.images

            when {
                m.toolCalls.isNotEmpty() && text.isEmpty() -> map["content"] = JsonNull
                // An array of parts on the *input* side, which is otherwise avoided on
                // purpose (a bare string is what every OpenAI-compatible server accepts).
                // Images leave no choice: `image_url` parts are the only way to send one,
                // so the array form is used exactly when there is an image and never
                // otherwise.
                images.isNotEmpty() -> {
                    map["content"] = buildJsonArray {
                        if (text.isNotEmpty()) {
                            add(buildJsonObject {
                                put("type", "text")
                                put("text", text)
                            })
                        }
                        images.forEach { img ->
                            val url = buildJsonObject {
                                put("url", img.url)
                                img.detail?.let { put("detail", it) }
                            }
                            add(buildJsonObject {
                                put("type", "image_url")
                                put("image_url", url)
                            })
                        }
                    }
                }
                else -> map["content"] = JsonPrimitive(text)
            }

            if (m.toolCalls.isNotEmpty()) {
                map["tool_calls"] = buildJsonArray {
                    m.toolCalls.forEach { tc ->
                        add(buildJsonObject {
                            put("id", tc.id)
                            put("type", "function")
                            put("function", buildJsonObject {
                                put("name", tc.name)
                                put("arguments", tc.arguments)
                            })
                        })
                    }
                }
            }

            m.toolCallId?.let { map["tool_call_id"] = JsonPrimitive(it) }

            return JsonObject(map)
        }
    }
}

/** A function the model may call. `parameters` is a JSON Schema object. */
data class ToolDef(val name: String, val description: String, val parameters: JsonElement)

data class ChatRequest(
    val model: String,
    val messages: List<Message>,
    val temperature: Double? = null,
    val topP: Double? = null,
    val maxTokens: Int? = null,
    val stream: Boolean = false,
    val tools: List<ToolDef> = emptyList(),
    val streamOptions: Boolean = false,
    /** How hard the model should think, as OpenAI's `reasoning_effort`.
      *
      * One field for all three providers, which is only possible because they
      * converged on it. Anthropic's `thinking: {budget_tokens}` shape is
      * deliberately not modelled -- a token budget is a different kind of
      * control, and supporting both would make one setting mean two things.
      *
      * Absent means "send nothing", which is not `none`: one lets the provider
      * apply its default, the other asks it not to think. */
    val reasoningEffort: String? = null
) {
    fun encode(): JsonObject = buildJsonObject {
        put("model", model)
        put("messages", buildJsonArray {
            messages.forEach { m ->
                add(Message.encode(m))
            }
        })
        temperature?.let { put("temperature", it) }
        topP?.let { put("top_p", it) }
        maxTokens?.let { put("max_tokens", it.toDouble()) }
        reasoningEffort?.let { put("reasoning_effort", it) }
        if (stream) {
            put("stream", true)
            if (streamOptions) {
                put("stream_options", buildJsonObject {
                    put("include_usage", true)
                })
            }
        }
        if (tools.isNotEmpty()) {
            put("tools", toolSchemas(tools))
        }
    }

    companion object {
        /** The `tools` array exactly as it goes on the wire.
          *
          * Extracted so `/prompt full` can weigh the real payload rather than an
          * approximation of it. Tool schemas are not part of the prompt -- they are
          * a sibling field of `messages` -- but the provider serialises them into
          * the model's context and bills them as input just the same, and on this
          * harness they are an order of magnitude larger than the system prompt.
          * Reporting one without the other answers "what am I sending?" with an
          * eighth of the answer.
          */
        fun toolSchemas(tools: List<ToolDef>): JsonArray =
            buildJsonArray {
                tools.forEach { t ->
                    add(buildJsonObject {
                        put("type", "function")
                        put("function", buildJsonObject {
                            put("name", t.name)
                            put("description", t.description)
                            put("parameters", t.parameters)
                        })
                    })
                }
            }
    }
}

data class ChatResponse(
    val message: Message,
    val finishReason: String? = null,
    val usage: Usage? = null,
    val model: String? = null
) {
    val text: String
        get() = message.text

    val reasoning: String
        get() = message.reasoning

    val toolCalls: List<ToolCall>
        get() = message.toolCalls

    /** True when the reply was cut off by the token cap. Worth surfacing loudly:
      * with a reasoning model this can mean an empty visible answer. */
    val truncated: Boolean
        get() = finishReason == "length"

    companion object {
        fun decode(v: JsonElement): ChatResponse {
            val o = if (v is JsonObject) v else JsonObject(emptyMap())
            val choice = (o["choices"] as? JsonArray)?.firstOrNull()
            val cObj = if (choice is JsonObject) choice else null

            return ChatResponse(
                message = cObj?.get("message")?.let { Message.decode(it) } ?: Message("assistant"),
                finishReason = (cObj?.get("finish_reason") as? JsonPrimitive)?.content,
                usage = o["usage"]?.let { Usage.decode(it) },
                model = (o["model"] as? JsonPrimitive)?.content
            )
        }
    }
}

/** Decoding for one streamed `chat.completion.chunk`. */
object Chunk {

    fun decode(v: JsonElement): List<StreamEvent> {
        val o = if (v is JsonObject) v else return emptyList()
        val events = mutableListOf<StreamEvent>()

        val cObj = (o["choices"] as? JsonArray)?.firstOrNull()
            ?.let { if (it is JsonObject) it else null }

        cObj?.get("delta")?.let { delta ->
            val dObj = if (delta is JsonObject) delta else null

            // delta.content carries the same array-of-parts shape as a blocking
            // reply, not a string (docs/clients.md, finding 2).
            dObj?.get("content")?.let { c ->
                Content.decode(c).forEach { part ->
                    when (part) {
                        is Part.Text -> events.add(StreamEvent.TextDelta(part.text))
                        is Part.Reasoning -> events.add(StreamEvent.ReasoningDelta(part.text))
                        else -> {}
                    }
                }
            }

            // Servers hoist reasoning to a sibling of `content` under three
            // different names: DeepSeek-style `reasoning_content`, Ollama's
            // `thinking`, and a bare `reasoning`. All three mean the same channel,
            // and the cost of missing one is a model's chain-of-thought rendered as
            // its answer.
            listOf("reasoning_content", "thinking", "reasoning")
                .flatMap { k -> dObj?.get(k)?.let { listOf(it) } ?: emptyList() }
                .mapNotNull { el ->
                    when {
                        el is JsonPrimitive && el.content.isNotEmpty() -> el.content
                        else -> null
                    }
                }
                .forEach { s -> events.add(StreamEvent.ReasoningDelta(s)) }

            dObj?.get("tool_calls")?.let { tc ->
                when {
                    tc is JsonArray -> {
                        tc.forEachIndexed { fallbackIdx, item ->
                            when {
                                item is JsonObject -> {
                                    val fn = (item["function"] as? JsonObject)
                                    events.add(StreamEvent.ToolCallDelta(
                                        index = (item["index"] as? JsonPrimitive)?.intOrNull ?: fallbackIdx,
                                        id = (item["id"] as? JsonPrimitive)?.content?.takeIf { it.isNotEmpty() },
                                        name = (fn?.get("name") as? JsonPrimitive)?.content?.takeIf { it.isNotEmpty() },
                                        arguments = (fn?.get("arguments") as? JsonPrimitive)?.content ?: ""
                                    ))
                                }
                                else -> {}
                            }
                        }
                    }
                    else -> {}
                }
            }
        }

        // Usage is repeated on every chunk and is cumulative, so the assembler
        // replaces rather than accumulates (docs/clients.md, finding 4).
        o["usage"]?.let { Usage.decode(it)?.let { u -> events.add(StreamEvent.UsageUpdate(u)) } }

        // `contentOrNull`, not `is JsonPrimitive`: JsonNull is a JsonPrimitive
        // here, so the obvious check matches `"finish_reason": null` and emits
        // a spurious Finished on every mid-stream chunk. Scala's
        // `.collect { case ujson.Str(s) => s }` matched only real strings.
        (cObj?.get("finish_reason") as? JsonPrimitive)?.contentOrNull
            ?.let { events.add(StreamEvent.Finished(it)) }

        return events
    }
}
