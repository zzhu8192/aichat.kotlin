package kotllm.tools

import kotlinx.serialization.json.*

import kotllm.*

/** Tools about this session and past ones: the visible checklist, asking the
  * user, the clock, and search over stored conversations.
  *
  * Mixed into [[Tools]]; split out only for file size.
  */
interface SessionTools {
    val CreateTasksName: String get() = "create_tasks"
    val UpdateTaskName: String get() = "update_task"

    /** A visible checklist for multi-step work.
      *
      * **kotllm assigns the ids and both tools echo the whole list back**, which
      * is the decision the rest follows from: a model inventing its own keys will
      * eventually address one that does not exist, and an update matching nothing
      * fails silently -- the worst shape for a progress tracker. */
    fun createTasks(checklist: kotllm.task.Checklist): Tool = Tool(
        name = CreateTasksName,
        description =
            "Visible checklist for multi-step work. Use for three or more steps; skip it for one or " +
                "two. Each task a short imperative. Calling again REPLACES the list, so use it for a " +
                "new plan, not for progress.",
        parameters = buildJsonObject {
            put("type", "object")
            putJsonObject("properties") {
                putJsonObject("tasks") {
                    put("type", "array")
                    putJsonObject("items") { put("type", "string") }
                    put("description", "Steps, in order.")
                }
            }
            putJsonArray("required") { add("tasks") }
        },
        preview = { args ->
            val tasks = (args as? JsonObject)?.get("tasks") as? JsonArray
            tasks?.let { "${it.size} tasks" } ?: ""
        },
        // Nothing outside this process changes, so prompting would fire several
        // times a turn to confirm a bookkeeping entry.
        readOnly = true,
        run = { args ->
            val obj = args as? JsonObject
            if (obj == null) ToolResult("arguments must be a JSON object", isError = true)
            else {
                val tasksEl = obj["tasks"]
                when {
                    tasksEl == null -> ToolResult("missing required argument 'tasks'", isError = true)
                    tasksEl !is JsonArray -> ToolResult("`tasks` must be an array of strings", isError = true)
                    else -> {
                        val texts = tasksEl.mapNotNull { el -> (el as? JsonPrimitive)?.takeIf { it.isString }?.content }
                        if (texts.size != tasksEl.size)
                            ToolResult("every entry in `tasks` must be a string", isError = true)
                        else
                            checklist.create(texts).fold(
                                onErr = { e -> ToolResult(e, isError = true) },
                                onOk = { out -> ToolResult(out) }
                            )
                    }
                }
            }
        }
    )

    /** Moves tasks between states.
      *
      * **Takes a batch**, because the common move is exactly two changes at once
      * -- finish this, start the next -- and splitting that across two calls
      * doubles the round trips for the single most frequent thing this tool
      * does.
      */
    fun updateTask(checklist: kotllm.task.Checklist): Tool = Tool(
        name = UpdateTaskName,
        description =
            "Update checklist task status. Mark in_progress when you start and done the moment you " +
                "finish, not in a batch. Statuses: ${kotllm.task.TaskStatus.all.joinToString(", ") { it.wire }}.",
        parameters = buildJsonObject {
            put("type", "object")
            putJsonObject("properties") {
                putJsonObject("updates") {
                    put("type", "array")
                    putJsonObject("items") {
                        put("type", "object")
                        putJsonObject("properties") {
                            putJsonObject("id") {
                                put("type", "integer")
                                put("description", "Task id from create_tasks.")
                            }
                            putJsonObject("status") {
                                put("type", "string")
                                putJsonArray("enum") { kotllm.task.TaskStatus.all.forEach { add(it.wire) } }
                                put("description", "New status for that task.")
                            }
                        }
                        putJsonArray("required") { add("id"); add("status") }
                    }
                    put("description", "One entry per task changed.")
                }
            }
            putJsonArray("required") { add("updates") }
        },
        preview = { args ->
            val updates = (args as? JsonObject)?.get("updates") as? JsonArray
            updates?.mapNotNull { u ->
                val uo = u as? JsonObject ?: return@mapNotNull null
                val id = uo["id"]?.toString() ?: "?"
                val st = (uo["status"] as? JsonPrimitive)?.takeIf { it.isString }?.content ?: "?"
                "$id -> $st"
            }?.joinToString(", ") ?: ""
        },
        readOnly = true,
        run = { args ->
            val obj = args as? JsonObject
            if (obj == null) ToolResult("arguments must be a JSON object", isError = true)
            else {
                val updatesEl = obj["updates"]
                when {
                    updatesEl == null -> ToolResult("missing required argument 'updates'", isError = true)
                    updatesEl !is JsonArray -> ToolResult("`updates` must be an array", isError = true)
                    else -> {
                        // Parsed into Outcome first so one bad entry refuses the batch
                        // rather than applying the good half of it.
                        val parsed: List<Outcome<Pair<Int, kotllm.task.TaskStatus>>> = updatesEl.map { u ->
                            val uo = u as? JsonObject
                            if (uo == null) err("each update must be an object with 'id' and 'status'")
                            else {
                                val idField = uo["id"]
                                val idOutcome: Outcome<Int> = when {
                                    idField is JsonPrimitive && !idField.isString ->
                                        idField.doubleOrNull?.let { ok(it.toInt()) }
                                            ?: err("each update needs an integer 'id'")
                                    // A model occasionally sends "2"; rejecting it would cost
                                    // a round trip to learn a JSON type the schema states.
                                    idField is JsonPrimitive && idField.isString ->
                                        idField.content.trim().toIntOrNull()?.let { ok(it) }
                                            ?: err("'${idField.content}' is not a task id")
                                    else -> err("each update needs an integer 'id'")
                                }
                                val statusField = uo["status"]
                                val statusOutcome: Outcome<kotllm.task.TaskStatus> =
                                    if (statusField is JsonPrimitive && statusField.isString)
                                        kotllm.task.TaskStatus.parse(statusField.content)
                                    else err("each update needs a 'status'")
                                idOutcome.flatMap { i -> statusOutcome.map { s -> i to s } }
                            }
                        }

                        val firstError = parsed.firstNotNullOfOrNull { p ->
                            when (p) {
                                is Outcome.Err -> p.message
                                is Outcome.Ok -> null
                            }
                        }
                        if (firstError != null) ToolResult(firstError, isError = true)
                        else {
                            val goods = parsed.mapNotNull { p ->
                                when (p) {
                                    is Outcome.Ok -> p.value
                                    is Outcome.Err -> null
                                }
                            }
                            checklist.update(goods).fold(
                                onErr = { e -> ToolResult(e, isError = true) },
                                onOk = { out -> ToolResult(out) }
                            )
                        }
                    }
                }
            }
        }
    )

    val AskUserName: String get() = "ask_user"

    /** Puts a clarifying question to the user, with choices.
      *
      * A model that guesses on an ambiguous request and is wrong spends a whole
      * turn on work nobody wanted. The *choices* are what make it usable: an open
      * question invites an essay.
      *
      * **The free-text row is always there**, appended by the prompter rather than
      * supplied by the model -- a fixed list is a guess, and the one time it is
      * wrong the user needs a way to say so.
      *
      * With nobody to ask this **refuses and says why**, as `confirm` does.
      * Returning the first option would fabricate a decision the user never made. */
    fun askUser(prompter: Prompter, interactive: () -> Boolean): Tool = Tool(
        name = AskUserName,
        description =
            "Ask the user a clarifying question with 2-4 short, mutually exclusive options. Use when " +
                "the request is genuinely ambiguous, not for permission or for what you could read. A " +
                "free-text choice is added for you, so never add one yourself. Refused when nobody is " +
                "attached: then state your assumption and carry on.",
        parameters = buildJsonObject {
            put("type", "object")
            putJsonObject("properties") {
                putJsonObject("question") {
                    put("type", "string")
                    put("description", "One sentence, ending in a question mark.")
                }
                putJsonObject("options") {
                    put("type", "array")
                    putJsonObject("items") { put("type", "string") }
                    put("description", "2-4 short choices. Free text is appended for you.")
                }
                putJsonObject("multi_select") {
                    put("type", "boolean")
                    put("description", "Allow several choices. Default false.")
                }
            }
            putJsonArray("required") { add("question"); add("options") }
        },
        preview = { args -> Tool.optStr(args, "question") ?: "" },
        // It cannot mutate anything and cannot run code -- and prompting for
        // permission to ask a question would be absurd.
        readOnly = true,
        run = { args ->
            Tool.str(args, "question").fold(
                onErr = { e -> ToolResult(e, isError = true) },
                onOk = { question ->
                    val options = ((args as? JsonObject)?.get("options") as? JsonArray)
                        ?.mapNotNull { el ->
                            (el as? JsonPrimitive)?.takeIf { it.isString }?.content?.trim()?.takeIf { it.isNotEmpty() }
                        }
                        ?: emptyList()

                    if (options.isEmpty())
                        ToolResult(
                            "ask_user needs at least one option; an open question invites an essay rather " +
                                "than a decision",
                            isError = true
                        )
                    else if (!interactive())
                        // The same refusal `confirm` gives, and for the same reason.
                        ToolResult(
                            "there is nobody attached to this session to answer, so the question was not " +
                                "asked. State your assumption explicitly and continue.",
                            isError = true
                        )
                    else {
                        val answers = prompter.ask(question, options, Tool.optBool(args, "multi_select") ?: false)
                        if (answers == null)
                            ToolResult(
                                "the question was not answered (cancelled or no terminal). State your " +
                                    "assumption explicitly and continue.",
                                isError = true
                            )
                        else ToolResult("the user answered: ${answers.joinToString("; ")}")
                    }
                }
            )
        }
    )

    val TimestampName: String get() = "get_sys_dt"

    /** The current time, in every form worth having.
      *
      * The model has no clock, so this is one of very few facts it cannot derive.
      * All the forms in one result rather than a `format` argument, which would
      * guarantee a second call whenever the first guess was wrong.
      *
      * Deliberately *not* injected into the system prompt: a value that changes
      * every turn sits in front of ~3,000 tokens of tool schemas and would bust
      * prompt caching for all of it. */
    fun timestamp(clock: () -> java.time.Instant = { java.time.Instant.now() }): Tool = Tool(
        name = TimestampName,
        description =
            "Current date and time; you have no clock. Epoch seconds and millis, ISO-8601 UTC, and " +
                "local time with zone.",
        parameters = buildJsonObject {
            put("type", "object")
            putJsonObject("properties") { }
        },
        preview = { _ -> "current date and time" },
        readOnly = true,
        run = { _ ->
            val now = clock()
            val zone = java.time.ZoneId.systemDefault()
            val local = java.time.ZonedDateTime.ofInstant(now, zone)
            ToolResult(
                "epoch_seconds   ${now.epochSecond}\n" +
                    "epoch_millis    ${now.toEpochMilli()}\n" +
                    "iso_utc         ${java.time.format.DateTimeFormatter.ISO_INSTANT.format(now)}\n" +
                    "local           ${local.format(java.time.format.DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"))} " +
                    "${zone.id} (UTC${local.offset.id})\n" +
                    "day_of_week     ${local.dayOfWeek.toString().lowercase().replaceFirstChar { it.uppercase() }}"
            )
        }
    )

    val SearchChatsName: String get() = "search_chats"
    val ViewChatName: String get() = "view_chat"

    /** Retrieval over the user's own past conversations.
      *
      * The corpus that matters most and was previously unreachable: what the user
      * already decided, rejected or explained. Hybrid, reusing `Bm25` +
      * `HashingEmbedder` + RRF -- see `ChatSearch` for why the *index* is
      * chat-specific rather than a second `RagIndex`.
      *
      * **The current session is excluded.** Searching the conversation in progress
      * returns what was said a moment ago, which is never what is being asked. */
    fun searchChats(
        search: kotllm.config.ChatSearch,
        currentSession: () -> String?,
        defaultTopK: Int = 6,
        maxOutputChars: Int = 12_000
    ): Tool = Tool(
        name = SearchChatsName,
        description =
            "Search this user's earlier conversations; returns passages with a session id, message " +
                "number and timestamp. Use it when the question refers to something discussed before. " +
                "Matching is lexical, not semantic: prefer distinctive words, names and error strings. " +
                "This conversation is excluded. Follow a hit with view_chat.",
        parameters = buildJsonObject {
            put("type", "object")
            putJsonObject("properties") {
                putJsonObject("query") {
                    put("type", "string")
                    put("description", "Distinctive words, names or a phrase.")
                }
                putJsonObject("top_k") {
                    put("type", "integer")
                    put("description", "Passages to return.")
                }
                putJsonObject("role") {
                    put("type", "string")
                    putJsonArray("enum") { add("user"); add("assistant") }
                    put("description", "Restrict to user or assistant. Omit for both.")
                }
            }
            putJsonArray("required") { add("query") }
        },
        preview = { args -> Tool.optStr(args, "query") ?: "" },
        readOnly = true,
        run = { args ->
            Tool.str(args, "query").fold(
                onErr = { e -> ToolResult(e, isError = true) },
                onOk = { query ->
                    val k = Tool.optInt(args, "top_k")?.takeIf { it > 0 } ?: defaultTopK
                    val role = Tool.optStr(args, "role")?.takeIf { it == "user" || it == "assistant" }
                    val hits = search.search(query, k, currentSession(), role)

                    if (search.sessions().isEmpty())
                        // The distinction matters: nothing saved is a configuration fact,
                        // not an empty result the model should retry differently.
                        ToolResult(
                            "there are no saved conversations to search. Persisting them needs `save: true` " +
                                "in the config, and only sessions since then are stored."
                        )
                    else if (hits.isEmpty())
                        ToolResult(
                            "no past conversation matched '$query'. Matching is lexical rather than " +
                                "semantic, so try distinctive words, a name, or an error string."
                        )
                    else renderChatHits(query, hits, maxOutputChars)
                }
            )
        }
    )

    private fun renderChatHits(query: String, hits: List<kotllm.config.ChatHit>, cap: Int): ToolResult {
        val header = "${hits.size} passage(s) for '$query' in past conversations:"
        val body = StringBuilder(header)
        var budget = cap - header.length
        var shown = 0

        hits.forEachIndexed { i, hit ->
            val text = hit.message.text.trim()
            val quote = if (text.length > 900) text.take(900) + "..." else text
            val block = "\n\n[${i + 1}] ${hit.citation()}\n$quote"
            // Stop at the first that does not fit rather than skipping it: the
            // results are ranked, so a hole in the middle is more confusing than a
            // truncation at the end.
            if (block.length <= budget) {
                body.append(block)
                budget -= block.length
                shown += 1
            }
        }

        if (shown < hits.size)
            body.append(
                "\n\n[${hits.size - shown} further passage(s) omitted to stay within the " +
                    "result size limit; narrow the query or lower top_k]"
            )

        return ToolResult(body.toString())
    }

    /** One past conversation, by id.
      *
      * A window rather than the whole file by default. A long session is tens of
      * thousands of characters and the reason for reading one is almost always a
      * specific passage that `search_chats` already pointed at -- so `around`
      * takes the message number from a citation and returns its neighbourhood.
      */
    fun viewChat(search: kotllm.config.ChatSearch, maxOutputChars: Int = 16_000): Tool = Tool(
        name = ViewChatName,
        description =
            "Read a past conversation by session id from search_chats. Pass `around` with a message " +
                "number to read that passage in context rather than the whole thing. A prefix or the " +
                "short suffix of an id works.",
        parameters = buildJsonObject {
            put("type", "object")
            putJsonObject("properties") {
                putJsonObject("session") {
                    put("type", "string")
                    put("description", "Session id from a search_chats citation, or part of one.")
                }
                putJsonObject("around") {
                    put("type", "integer")
                    put("description", "Message number to centre on. Omit for the start.")
                }
                putJsonObject("context") {
                    put("type", "integer")
                    put("description", "Messages either side of `around`. Default 4.")
                }
                putJsonObject("prompt") {
                    put("type", "boolean")
                    put(
                        "description",
                        "Include the system prompt that was in effect for this passage. Only some " +
                            "conversations recorded one, and it is long -- ask for it when the question is " +
                            "about how that session was configured, not about what was said."
                    )
                }
            }
            putJsonArray("required") { add("session") }
        },
        preview = { args ->
            val id = Tool.optStr(args, "session") ?: ""
            Tool.optInt(args, "around")?.let { n -> "$id around msg $n" } ?: id
        },
        readOnly = true,
        run = { args ->
            Tool.str(args, "session").fold(
                onErr = { e -> ToolResult(e, isError = true) },
                onOk = { wanted ->
                    search.view(wanted).fold(
                        onErr = { e -> ToolResult(e, isError = true) },
                        onOk = { session ->
                            val span = Tool.optInt(args, "context")?.takeIf { it >= 0 } ?: 4
                            val around = Tool.optInt(args, "around")
                            val ordered = session.messages.sortedBy { it.index }

                            val window =
                                if (around != null) ordered.filter { kotlin.math.abs(it.index - around) <= span }
                                else ordered.take(span * 2 + 1)

                            val head =
                                "conversation ${session.id}  ${session.`when`}  ${session.model}  " +
                                    "${ordered.size} message(s)\n${session.title()}"

                            if (window.isEmpty())
                                ToolResult("$head\n\nno messages near ${around ?: 0}", isError = true)
                            else {
                                val body = window.joinToString("") { m ->
                                    val text = m.text.trim()
                                    "\n\n--- msg ${m.index}  ${m.role}  ${m.stamp} ---\n$text"
                                }

                                // Ahead of the messages, mirroring the order on disk, and only
                                // on request: it is several KB and the usual reason to open a
                                // conversation is what was said in it.
                                val prompt =
                                    if (!(Tool.optBool(args, "prompt") ?: false)) ""
                                    else {
                                        val p = session.promptAt(window[0].index)
                                        if (p == null)
                                            "\n\n--- system prompt ---\n(not recorded; the session ran " +
                                                "without `/prompt full`)"
                                        else {
                                            val own = p.user?.let { u -> "\n\n--- of which the user wrote ---\n$u" } ?: ""
                                            "\n\n--- system prompt as sent, ${p.stamp} ---\n${p.full}$own"
                                        }
                                    }

                                val full = head + prompt + body
                                val note =
                                    if (window.size < ordered.size)
                                        "\n\n[showing ${window.size} of ${ordered.size} messages; " +
                                            "pass `around` with another message number for a different part]"
                                    else ""

                                val text = full + note
                                ToolResult(
                                    if (text.length <= maxOutputChars) text
                                    else text.take(maxOutputChars) +
                                        "\n\n[truncated at $maxOutputChars chars; narrow with `around` and `context`]"
                                )
                            }
                        }
                    )
                }
            )
        }
    )
}
