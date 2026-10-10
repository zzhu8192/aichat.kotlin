package kotllm.tools

// Wildcard, and it has to be: put(String, String), intOrNull and
// contentOrNull are extension functions in this package, not members of
// JsonObjectBuilder or JsonPrimitive. Importing the classes individually
// compiles the types and loses every helper.
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.*

import kotllm.*
import kotllm.client.ToolCall
import kotllm.client.ToolDef

/** What a tool call produced. `isError` still goes back to the model as a tool
  * result rather than aborting the turn -- an error it can read is an error it
  * can correct. */
data class ToolResult(val content: String, val isError: Boolean = false)

/** A callable tool.
  *
  * @param parameters JSON Schema for the arguments object.
  * @param preview    one-line summary of what a call would do, shown in the
  *                   approval prompt. Kept separate from `run` so the user can
  *                   be told what is about to happen before it happens.
  */
data class Tool(
    val name: String,
    val description: String,
    val parameters: JsonElement,
    val preview: (JsonElement) -> String,
    val run: (JsonElement) -> ToolResult,
    /** True when the call cannot mutate anything and cannot execute code:
      * reading an index, a URL, a search engine.
      *
      * The confirm policy skips these. That is a deliberate exception to
      * "confirm by default" (a locked decision), made because the alternative
      * is worse than the risk it guards: under confirm a background turn or a
      * scripted run has nobody to ask, so retrieval and fetching were refused
      * outright, and `--rag-index . "question"` could not work at all. What is
      * given up is control over outbound reads, so the calls are still printed
      * like every other -- `ToolRender` names the query or the URL.
      */
    val readOnly: Boolean = false
) {
    val definition: ToolDef get() = ToolDef(name, description, parameters)

    companion object {

        /** Parses a tool's JSON Schema from text.
          *
          * Here because a user tool file is **Groovy**, and `Json` is a Kotlin
          * sealed class whose entry point is a named companion. Groovy resolves
          * `Json.Default` to the nested *class* rather than the static field
          * holding the instance, so neither `Json.parseToJsonElement(s)` nor
          * `Json.Default.parseToJsonElement(s)` can be written from a tool file
          * -- both fail at load with "No signature of static method". This is
          * the supported spelling, and it means a tool file needs no kotlinx
          * import at all.
          */
        @JvmStatic
        fun schema(json: String): JsonElement = Json.parseToJsonElement(json)

        /** Reads a required string argument. */
        @JvmStatic
        fun str(args: JsonElement, key: String): Outcome<String> {
            val obj = args as? JsonObject ?: return err("arguments must be a JSON object")
            val v = obj[key] ?: return err("missing required argument '$key'")
            return when {
                v is JsonPrimitive && v.isString && v.content.isNotEmpty() -> ok(v.content)
                v is JsonPrimitive && v.isString -> err("argument '$key' is empty")
                else -> err("argument '$key' must be a string, got ${kindName(v)}")
            }
        }

        @JvmStatic
        fun optStr(args: JsonElement, key: String): String? {
            val obj = args as? JsonObject ?: return null
            val v = obj[key] as? JsonPrimitive ?: return null
            return if (v !is JsonNull && v.isString && v.content.isNotEmpty()) v.content else null
        }

        @JvmStatic
        fun optInt(args: JsonElement, key: String): Int? {
            val obj = args as? JsonObject ?: return null
            val v = obj[key] as? JsonPrimitive ?: return null
            if (v is JsonNull || v.isString) return null
            return v.doubleOrNull?.toInt()
        }

        /** A model will occasionally send `"true"` rather than `true`, so a string
          * spelling is accepted too. Rejecting it would cost a round trip to learn
          * a JSON type the schema already stated. */
        @JvmStatic
        fun optBool(args: JsonElement, key: String): Boolean? {
            val obj = args as? JsonObject ?: return null
            val v = obj[key] as? JsonPrimitive ?: return null
            if (v is JsonNull) return null
            return when {
                !v.isString -> v.booleanOrNull
                v.content.trim().equals("true", ignoreCase = true) -> true
                v.content.trim().equals("false", ignoreCase = true) -> false
                else -> null
            }
        }

        /** ujson named its JSON value variants `Obj`, `Arr`, `Num`, `Bool`, `Str`
          * and `Null`, and error messages here used to report `getClass.getSimpleName`
          * of the mismatched value. `JsonElement` has no such names, so this spells
          * the closest equivalent by hand rather than leaking a kotlinx class name
          * the model has never seen. */
        private fun kindName(v: JsonElement): String = when {
            v is JsonNull -> "Null"
            v is JsonObject -> "Obj"
            v is JsonArray -> "Arr"
            v is JsonPrimitive && v.isString -> "Str"
            v is JsonPrimitive && v.booleanOrNull != null -> "Bool"
            v is JsonPrimitive -> "Num"
            else -> v::class.simpleName ?: "Unknown"
        }
    }
}

/** Tool registration, filtering and dispatch. */
class ToolRegistry(private val tools: List<Tool>) {

    /** First wins, explicitly.
      *
      * `tools.map(t => t.name -> t).toMap` keeps the **last** entry, so a user
      * tool from `config/tools/` appended after the built-ins would silently
      * replace one -- `read_to_context` quietly becoming somebody's half-written
      * version is about the worst outcome available here, and nothing on screen
      * would say so. So a built-in wins and the shadowed name is reported.
      */
    private val byName: Map<String, Tool> = run {
        val acc = LinkedHashMap<String, Tool>()
        for (t in tools) if (!acc.containsKey(t.name)) acc[t.name] = t
        acc
    }

    /** Names offered more than once, in the order they were rejected. Surfaced
      * at startup rather than swallowed: the user wrote a tool that is not being
      * used, and the reason is a name clash they cannot otherwise see. */
    val shadowed: List<String> = run {
        val seen = mutableSetOf<String>()
        val dupes = mutableListOf<String>()
        for (t in tools) if (!seen.add(t.name)) dupes += t.name
        dupes.distinct()
    }

    val all: List<Tool> get() = tools

    fun get(name: String): Tool? = byName[name]

    /** Applies `use_tools` and then subtracts `tools_exclude`.
      *
      * `use_tools` is `all`, `none`/unset, or a comma-separated list of names,
      * and an unknown name in it is an error rather than an omission: a typo
      * would otherwise silently disable a tool the user asked for.
      *
      * `tools_exclude` is applied afterwards and to everything, including an
      * explicit `use_tools` list -- subtraction is well defined either way, and
      * refusing the combination would only force the user to edit two keys to
      * express one intent. It is deliberately **not** an error for an exclusion
      * to match nothing (see [[unmatchedExclusions]]): which tools are even
      * registered depends on config, so `tools_exclude: python_eval` must not
      * break the whole tool set on a machine with no Python.
      */
    fun selected(useTools: String?, exclude: List<String> = emptyList()): Outcome<List<Tool>> {
        val trimmed = useTools?.trim()?.takeIf { it.isNotEmpty() }
        val chosen: Outcome<List<Tool>> = when (trimmed) {
            null -> ok(emptyList())
            "none" -> ok(emptyList())
            "all" -> ok(tools)
            else -> {
                val wanted = trimmed.split(',').map { it.trim() }.filter { it.isNotEmpty() }
                val unknown = wanted.filterNot { byName.containsKey(it) }
                if (unknown.isNotEmpty())
                    err(
                        "unknown tool(s) in use_tools: ${unknown.joinToString(", ")}; " +
                            "available: ${tools.joinToString(", ") { it.name }}"
                    )
                else ok(wanted.mapNotNull { byName[it] })
            }
        }
        return chosen.map { ts -> ts.filterNot { t -> excluded(t.name, exclude) } }
    }

    /** Entries in `tools_exclude` that match no registered tool.
      *
      * Reported rather than enforced. A typo in an exclusion is the dangerous
      * direction -- you believe `exec` is withheld and it is not -- so it has to
      * be visible; but the tool set varies with config, so it cannot be fatal.
      * Surfaced at startup by `Runner.startupHints` and again by `--list-tools`.
      */
    fun unmatchedExclusions(exclude: List<String>): List<String> =
        exclude.filterNot { p -> tools.any { t -> matches(p, t.name) } }.distinct()

    /** Runs one call. Never throws: a tool that blows up reports the failure as
      * a result, because losing the whole turn to a bad argument is worse than
      * letting the model see the exception. */
    fun dispatch(call: ToolCall): ToolResult {
        val tool = byName[call.name] ?: return ToolResult("unknown tool '${call.name}'", isError = true)
        return parseArgs(call.arguments).fold(
            onErr = { e -> ToolResult(e, isError = true) },
            onOk = { args ->
                try {
                    tool.run(args)
                } catch (e: Exception) {
                    ToolResult("${e::class.simpleName}: ${e.message ?: ""}", isError = true)
                }
            }
        )
    }

    /** Arguments arrive as a JSON *string*, and a model will occasionally send
      * an empty string or malformed JSON. Report it precisely so it can retry. */
    fun parseArgs(raw: String?): Outcome<JsonElement> =
        if (raw == null || raw.trim().isEmpty()) ok(buildJsonObject { })
        else try {
            ok(Json.parseToJsonElement(raw))
        } catch (e: SerializationException) {
            err("could not parse tool arguments as JSON (${e.message}); received: ${raw.take(300)}")
        }

    companion object {

        /** Whether `name` is withheld by any entry in `exclude`. */
        fun excluded(name: String, exclude: List<String>): Boolean = exclude.any { matches(it, name) }

        /** An exact name, or a `*` pattern anchored at both ends.
          *
          * Only `*`, and only because tool names come in families the whitelist
          * cannot summarise: `*_eval` is the three interpreters, `*memory*` is the
          * memory set. Everything between the stars is quoted, so a name containing
          * a regex metacharacter cannot turn an exclusion into something else.
          */
        fun matches(pattern: String, name: String): Boolean =
            if (!pattern.contains('*')) pattern == name
            else
                name.matches(
                    Regex(pattern.split("*").joinToString(".*") { java.util.regex.Pattern.quote(it) })
                )
    }
}
