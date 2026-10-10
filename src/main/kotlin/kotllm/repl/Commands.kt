package kotllm.repl

import kotllm.*
import kotllm.Runner
import kotllm.config.Paths
import kotllm.config.ReasoningDisplay
import kotllm.render.Ansi
import kotllm.render.on
import kotllm.tools.ApprovalMode

data class ReplCommand(val name: String, val help: String)

/** What a dispatched command asks the REPL to do next.
 *
 * Named `ReplOutcome` rather than `Outcome`: the Scala source used `Either`
 * for fallible calls and named this unrelated three-way result `Outcome` with
 * no clash, but the Kotlin port renames `Either[String, A]` to `Outcome<A>`
 * everywhere -- see `kotllm.Outcome`. Keeping this domain type's Scala name
 * would shadow that one throughout the whole `kotllm.repl` package, since a
 * same-package declaration always wins over a star import. This type never
 * crosses a package boundary (nothing outside `kotllm.repl` names it in the
 * Scala source either), so the rename costs nothing.
 */
sealed interface ReplOutcome {
    data object Continue : ReplOutcome
    data object Exit : ReplOutcome

    /** Text to send, plus any images to attach. `/file` is the only command that
     * produces images, and they travel beside the text rather than in it: an
     * image becomes an `image_url` content part, never prompt text. */
    data class Send(val text: String, val images: List<kotllm.client.Part.Image> = emptyList()) : ReplOutcome
}

/** The slash-command table and its dispatcher.
 *
 * Commands present here are implemented. Roles, sessions, macros, `/file`,
 * tools, templates and memories are planned (see docs/) and deliberately
 * absent rather than stubbed, so tab completion never offers something that
 * does not work.
 */
object Commands : CommandReports {

    override val all: List<ReplCommand> = listOf(
        ReplCommand("/help", "Show this help guide"),
        ReplCommand("/info", "Show endpoint, model and runtime settings"),
        ReplCommand("/status", "Show usage metrics as a transient overlay"),
        ReplCommand("/vars", "List the {{ }} harness variables and their values"),
        ReplCommand("/memory", "Show, add, edit or delete durable memories"),
        ReplCommand("/rag", "Index a folder and search it; see /rag for subcommands"),
        ReplCommand("/agent", "Run an agent file, or list the available ones"),
        ReplCommand("/skills", "Show the installed skills and where they came from"),
        ReplCommand("/skill", "Load a skill's instructions and send them as this turn"),
        ReplCommand("/tasks", "Show the current task checklist, or clear it"),
        ReplCommand("/think", "Set how hard the model thinks (reasoning_effort)"),
        ReplCommand("/tools", "Show Scala tools from config/tools, or recompile them"),
        ReplCommand("/bg", "Run a prompt in the background, or list background turns"),
        ReplCommand("/model", "Switch LLM model"),
        ReplCommand("/prompt", "Set, show or clear the system prompt; 'full' shows what is injected"),
        ReplCommand("/set", "Modify a runtime setting"),
        ReplCommand("/file", "Send files, directories, URLs or `commands` as input"),
        ReplCommand("/compact", "Summarise older context now, keeping recent turns"),
        ReplCommand("/empty", "Clear the conversation context"),
        ReplCommand("/regenerate", "Re-send the last input"),
        ReplCommand("/exit", "Exit the REPL")
    )

    override val settableKeys: List<String> = listOf(
        "stream",
        "highlight",
        "reasoning",
        "temperature",
        "top_p",
        "max_tokens",
        "save",
        // Worth changing mid-session: you discover the cap is too low precisely
        // by hitting it, and editing config.yaml to continue is friction.
        "max_tool_iterations",
        // Screen noise, not context: the model gets the whole result either way.
        "tool_output_lines",
        "tool_preview_lines",
        "tool_approval",
        // Also reachable as `/think`, which is the discoverable spelling. Kept
        // in `/set` too because every other request parameter lives there.
        "reasoning_effort"
    )

    private val CommandRe = Regex("""^\s*(/\S*)\s*(.*)$""")

    // `/quit` is a hidden alias that dispatches; it is the only one. `/dump` sat
    // here too with no `case` behind it, so typing it answered "unknown command
    // '/dump'" -- the parser claimed a command the dispatcher did not have.
    // `/prompt full` is what it was reaching for.
    private val known: Set<String> by lazy { all.map { it.name }.toSet() + "/quit" }

    /** Splits a line into command and argument, if it is a command at all.
     *
     * Only commands starting with `/` are parsed. A leading slash only counts
     * when it names a command that exists, because otherwise `/etc/hosts explain this`
     * would be swallowed as a bad command instead of being sent to the model.
     */
    fun parse(line: String): Pair<String, String?>? {
        val m = CommandRe.matchEntire(line) ?: return null
        val raw = m.groupValues[1]
        val rest = m.groupValues[2]
        val args = rest.trim().takeIf { it.isNotEmpty() }
        return if (known.contains(raw)) raw to args else null
    }

    /** @param overlay renders a transient panel, returning false if it cannot;
     *                supplied by the REPL, which owns the terminal. */
    fun dispatch(
        runner: Runner,
        name: String,
        args: String?,
        overlay: (List<String>) -> Boolean = { false }
    ): ReplOutcome = when (name) {
        "/help" -> {
            println(helpText)
            ReplOutcome.Continue
        }

        "/info" -> {
            println(infoText(runner))
            ReplOutcome.Continue
        }

        "/vars" -> {
            val report = varsText(runner)
            if (!overlay(report.lines())) println(report)
            ReplOutcome.Continue
        }

        "/memory" -> {
            when {
                args != null && args.startsWith("add ") -> {
                    runner.memory.append(args.drop(4)).fold(
                        onErr = { e -> warn("could not write the note: $e") },
                        onOk = { file -> println("remembered, in $file") }
                    )
                    ReplOutcome.Continue
                }

                // Named, so it is the same unit the model edits and deletes. `add`
                // stays as the quick unnamed line it always was.
                args != null && args.startsWith("set ") -> {
                    val body = args.drop(4).trim()
                    val parts = body.split(Regex("\\s+"), limit = 2)
                    if (parts.size == 2) {
                        runner.memory.upsert(parts[0], parts[1]).fold(
                            onErr = { e -> warn("could not save the memory: $e") },
                            onOk = { file -> println("saved, in $file") }
                        )
                    } else warn("usage: /memory set <name> <content>")
                    ReplOutcome.Continue
                }

                args != null && (args.startsWith("delete ") || args.startsWith("rm ")) -> {
                    val memName = args.dropWhile { it != ' ' }.trim()
                    runner.memory.delete(memName).fold(
                        onErr = { e -> warn(e) },
                        onOk = { file -> println("deleted $file") }
                    )
                    ReplOutcome.Continue
                }

                args != null && args.startsWith("show ") -> {
                    runner.memory.byName(args.drop(5).trim()).fold(
                        onErr = { e -> warn(e) },
                        onOk = { f -> println(f.content) }
                    )
                    ReplOutcome.Continue
                }

                args != null -> {
                    warn(
                        "unknown /memory argument '$args'; usage: /memory [add <note> | " +
                            "set <name> <content> | show <name> | delete <name>]"
                    )
                    ReplOutcome.Continue
                }

                else -> {
                    val report = memoryText(runner)
                    if (!overlay(report.lines())) println(report)
                    ReplOutcome.Continue
                }
            }
        }

        "/tasks" -> {
            when (val trimmed = args?.trim()?.takeIf { it.isNotEmpty() }) {
                "clear" -> {
                    runner.checklist.clear()
                    println("checklist cleared")
                }
                null ->
                    if (runner.checklist.isEmpty) println("no tasks. The model creates one with create_tasks.")
                    else {
                        val (closed, total) = runner.checklist.counts
                        println("$closed/$total closed")
                        println(runner.checklist.render)
                    }
                else -> warn("unknown /tasks argument '$trimmed'; usage: /tasks [clear]")
            }
            ReplOutcome.Continue
        }

        // Deliberately a command of its own rather than only `/set
        // reasoning_effort`: this is the knob people reach for mid-session when a
        // model is burning its budget on thinking, and `/set` is where settings go
        // to be forgotten. Named `/think` because that is what it does, not
        // `/reasoning`, which would read as the sibling display key.
        "/think" -> {
            val trimmed = args?.trim()?.takeIf { it.isNotEmpty() }
            if (trimmed == null) {
                val current = kotllm.config.ReasoningEffort.name(runner.config.reasoningEffort)
                println("reasoning_effort: $current")
                println("  values: default, ${kotllm.config.ReasoningEffort.Known.joinToString(", ")}")
                println(thinkingSupportNote(runner))
            } else {
                val effort = kotllm.config.ReasoningEffort.parse(trimmed)
                if (effort == null) warn("usage: /think [default|none|minimal|low|medium|high]")
                else {
                    runner.updateConfig { it.copy(reasoningEffort = effort) }
                    val wire = effort.wire
                    if (wire == null) {
                        println("reasoning_effort: default (nothing sent; the provider decides)")
                    } else {
                        println("reasoning_effort: $wire")
                        if (!kotllm.config.ReasoningEffort.Known.contains(wire))
                            warn(
                                "'$wire' is not a level kotllm knows; it is sent as-is and the " +
                                    "endpoint may reject it"
                            )
                        println(thinkingSupportNote(runner))
                    }
                }
            }
            ReplOutcome.Continue
        }

        "/tools" -> {
            when (val trimmed = args?.trim()?.takeIf { it.isNotEmpty() }) {
                "reload" -> {
                    // Recompiles from disk. The whole point of source tools is editing
                    // one and seeing it work, and restarting to do that is the friction
                    // this feature exists to remove.
                    val loaded = runner.userTools.reload()
                    println(runner.userTools.report())
                    loaded.filter { it.error != null }.forEach { f -> warn("${f.path.fileName}: ${f.error}") }
                    warn(
                        "the model is offered the new set from the next request; names already " +
                            "in this conversation do not change"
                    )
                }
                null -> {
                    if (runner.userTools.compiling) println("compiling...")
                    runner.userTools.awaitReady()
                    // The selection first: `/tools` used to answer only "what is in
                    // config/tools", which is the narrower question and not the one
                    // asked after editing use_tools or tools_exclude.
                    println(toolSelectionText(runner))
                    println()
                    println(runner.userTools.report())
                }
                else -> warn("unknown /tools argument '$trimmed'; usage: /tools [reload]")
            }
            ReplOutcome.Continue
        }

        "/rag" -> {
            rag(runner, args, overlay)
            ReplOutcome.Continue
        }

        "/agent" -> {
            agent(runner, args)
            ReplOutcome.Continue
        }

        "/skills" -> {
            println(runner.skills.report())
            ReplOutcome.Continue
        }

        // Deliberate invocation, for when you know which skill you want rather
        // than hoping its description fires. The body becomes the turn's input, so
        // the model receives instructions rather than a description of them.
        "/skill" -> {
            val trimmed = args?.trim()?.takeIf { it.isNotEmpty() }
            if (trimmed == null) {
                println(runner.skills.report())
                ReplOutcome.Continue
            } else {
                val ws = trimmed.indexOfFirst { it.isWhitespace() }
                val skillName = if (ws < 0) trimmed else trimmed.substring(0, ws)
                val rest = if (ws < 0) "" else trimmed.substring(ws)
                val arguments = rest.trim().takeIf { it.isNotEmpty() }
                runner.skills.render(skillName, arguments).fold(
                    onErr = { e -> warn(e); ReplOutcome.Continue },
                    onOk = { text ->
                        // Appended only when the skill does not interpolate them itself,
                        // so arguments are never delivered twice or silently dropped.
                        val task =
                            if (arguments == null ||
                                runner.skills.find(skillName).orNull()?.body?.contains("\$ARGUMENTS") == true
                            ) ""
                            else "\n\nThe task: $arguments"
                        ReplOutcome.Send("Follow these skill instructions.\n\n$text$task")
                    }
                )
            }
        }

        "/status" -> {
            val report = Status.render(runner)
            // Prefer the transient overlay so the report does not land in the
            // scrollback; fall back to printing when there is no pinned area.
            if (!overlay(report.lines())) println(report)
            ReplOutcome.Continue
        }

        "/model" -> {
            when (args) {
                null -> {
                    println(runner.target.id())
                    // The aliases too: they are the values `/model` accepts that
                    // `--list-models` would not have told you about before they existed,
                    // and the point of naming a model by role is not having to remember
                    // the endpoint id.
                    val aliases = runner.config.aliases
                    if (aliases.isNotEmpty()) {
                        val width = aliases.keys.maxOf { it.length }
                        aliases.toList().sortedBy { it.first }.forEach { (aliasName, target) ->
                            println("  ${aliasName.padEnd(width)}  -> $target")
                        }
                    }
                    ReplOutcome.Continue
                }

                // Asks each endpoint what it actually has, over the standard
                // `/v1/models`. Explicit rather than automatic: a network round trip
                // belongs behind a subcommand, not in front of every `/model`, and on a
                // machine where a local server is not running it would be a hang.
                "discover" -> {
                    runner.config.discoverableClients.forEach { c ->
                        kotllm.client.Models.list(c).fold(
                            onErr = { e ->
                                // A local server being down is routine, so this is a report
                                // rather than a failure: the other clients still answer.
                                warn("${c.id()}: $e")
                            },
                            onOk = { names ->
                                println("${c.id()} (${c.apiBase})")
                                names.forEach { n -> println("  ${c.id()}:$n") }
                            }
                        )
                    }
                    ReplOutcome.Continue
                }

                else -> {
                    runner.switchModel(args).fold(
                        onErr = { e -> warn(e) },
                        onOk = { t -> println("switched to ${t.id()}") }
                    )
                    ReplOutcome.Continue
                }
            }
        }

        "/prompt" -> {
            when (args) {
                null -> showPrompt(runner)
                "full", "default" -> {
                    runner.fullPromptView = (args == "full")
                    showPrompt(runner)
                }
                "-", "none" -> {
                    runner.session.systemPrompt = null
                    println("system prompt cleared")
                }
                else -> {
                    runner.session.systemPrompt = args
                    println("system prompt set (${args.length} chars)")
                }
            }
            ReplOutcome.Continue
        }

        "/set" -> {
            if (args == null) println(settingsText(runner))
            else {
                val parts = args.split(Regex("\\s+"), limit = 2)
                if (parts.size < 2) warn("usage: /set <key> <value>")
                else set(runner, parts[0], parts[1])
            }
            ReplOutcome.Continue
        }

        "/file" -> {
            if (args == null) {
                warn("usage: /file <path|dir|url|`cmd`|%%> ... [-- message]")
                ReplOutcome.Continue
            } else loadFiles(runner, args)
        }

        "/compact" -> {
            runner.compactNow().fold(
                onErr = { e -> warn(e) },
                onOk = { o ->
                    println(
                        "compacted: ${o.summarizedExchanges} exchange(s) summarised, " +
                            "${o.trimmedResults} tool result(s) trimmed, " +
                            "${o.messagesBefore} -> ${o.messagesAfter} messages"
                    )
                }
            )
            ReplOutcome.Continue
        }

        "/empty" -> {
            runner.session.clear()
            // The checklist goes too, unlike the ledger. Tokens spent stay spent,
            // but a plan the conversation no longer remembers would be injected
            // into every request with nothing left to explain it -- and the model
            // would be asked to keep updating a list it never made.
            runner.checklist.clear()
            println("context cleared")
            ReplOutcome.Continue
        }

        "/regenerate" -> {
            val text = runner.session.dropLastExchange()
            if (text != null) ReplOutcome.Send(text)
            else {
                warn("nothing to regenerate")
                ReplOutcome.Continue
            }
        }

        "/exit", "/quit" -> ReplOutcome.Exit

        else -> {
            warn("unknown command '$name'. Type \"/help\" for the command list.")
            ReplOutcome.Continue
        }
    }

    /** Runs an agent, or lists what is available.
     *
     * Everything an agent needs is in the file, so this takes a name and at most
     * a few `k=v` arguments -- there is nothing else to configure from here.
     */
    private fun agent(runner: Runner, args: String?) {
        val trimmed = args?.trim()?.takeIf { it.isNotEmpty() }
        if (trimmed == null) {
            val names = kotllm.agent.Agent.list()
            if (names.isEmpty())
                println(
                    "no agents yet. An agent is one .md file in ${Paths.agentsDir()} -- " +
                        "prose is output, <% %> is the program, and llm(...) calls the model."
                )
            else names.forEach { println(it) }
        } else {
            val parts = trimmed.split(Regex("\\s+"))
            val agentName = parts[0]
            val vars = parts.drop(1).mapNotNull { v ->
                val i = v.indexOf('=')
                if (i <= 0) {
                    warn("ignoring '$v': agent arguments are name=value")
                    null
                } else v.take(i) to v.drop(i + 1)
            }.toMap()

            kotllm.agent.Agent.load(agentName).fold(
                onErr = { e -> warn(e) },
                onOk = { (label, source) ->
                    println(kotllm.agent.Agent.banner(label))
                    kotllm.agent.Agent.run(runner, label, source, vars).fold(
                        onErr = { e -> warn(e) },
                        onOk = { result ->
                            when (result) {
                                // No agent primitive was called, so the file is a role: adopt it
                                // as the system prompt rather than sending it as a question.
                                is kotllm.agent.AgentResult.Prompt -> {
                                    runner.session.systemPrompt = result.text
                                    println("adopted '$label' as the system prompt (${result.text.length} chars)")
                                }
                                is kotllm.agent.AgentResult.Ran -> {
                                    println(kotllm.render.Markdown.render(result.output, runner.config.highlight))
                                    println(
                                        Ansi.Color.DarkGray.on(
                                            "${kotllm.util.Glyphs.bullet} ${result.verdict ?: "ran to the end"} " +
                                                "${kotllm.util.Glyphs.bullet} ${result.dir}"
                                        )
                                    )
                                }
                            }
                        }
                    )
                }
            )
        }
    }

    /** The two words `/prompt` reserves.
     *
     * They shadow a system prompt of exactly "full" or "default", which is the
     * price of not inventing a `/prompt set` prefix that every existing use
     * would have to grow. Both are single common words no one sets as a prompt,
     * and `-s` at startup is the escape hatch if anyone does.
     */
    val promptViews: List<String> = listOf("full", "default")

    val ragSubcommands: List<String> =
        listOf("index", "reindex", "use", "off", "drop", "describe", "inject", "search")

    /** Retrieval over an indexed folder.
     *
     * One command with subcommands rather than seven top-level ones: they are
     * only meaningful relative to an index, and `/rag` bare answering "what is
     * active" is the question actually asked most often.
     */
    private fun rag(runner: Runner, args: String?, overlay: (List<String>) -> Boolean) {
        val store = runner.rag
        if (store == null) {
            warn("rag is disabled (rag_enabled: false)")
            return
        }

        val (sub, rest) = args?.split(Regex("\\s+"), limit = 2)?.let { parts ->
            when (parts.size) {
                1 -> parts[0] to ""
                2 -> parts[0] to parts[1].trim()
                else -> "" to ""
            }
        } ?: ("" to "")

        when {
            sub == "" -> {
                val report = store.status
                if (!overlay(report.lines())) println(report)
            }

            sub == "index" && rest.isEmpty() -> warn("usage: /rag index <folder>")

            sub == "index" -> {
                store.indexFolder(rest, indexProgress(rest)).fold(
                    onErr = { e -> warn(e) },
                    onOk = { report ->
                        println(report.summary)
                        report.skipped.take(10).forEach { s -> warn("skipped $s") }
                        if (report.skipped.size > 10)
                            warn("and ${report.skipped.size - 10} more skipped; see /rag for totals")
                        println("the ${kotllm.tools.Tools.SearchName} tool is now available to the model")
                        if (store.description == null)
                            println(
                                "tip: '/rag describe <what this is>' tells the model what it can find here, " +
                                    "so it searches instead of guessing"
                            )
                    }
                )
            }

            sub == "reindex" -> {
                store.reindex(indexProgress()).fold(
                    onErr = { e -> warn(e) },
                    onOk = { report -> println(report.summary) }
                )
            }

            sub == "use" && rest.isEmpty() ->
                warn("usage: /rag use <name>; available: ${store.names.joinToString(", ")}")

            sub == "use" -> {
                store.activate(rest).fold(
                    onErr = { e -> warn(e) },
                    onOk = { idx -> println("active index: ${idx.name} (${idx.chunkCount} chunks)") }
                )
            }

            sub == "off" -> {
                if (store.active == null) warn("no index is active")
                else {
                    store.deactivate()
                    println("rag off; the ${kotllm.tools.Tools.SearchName} tool is withdrawn")
                }
            }

            sub == "drop" && rest.isEmpty() ->
                warn("usage: /rag drop <name>; available: ${store.names.joinToString(", ")}")

            sub == "drop" -> {
                store.drop(rest).fold(
                    onErr = { e -> warn(e) },
                    onOk = { println("dropped index '$rest'") }
                )
            }

            sub == "describe" && rest.isEmpty() -> {
                val d = store.description
                if (d != null) println(d)
                else println(
                    "no description set. '/rag describe <text>' tells the model what this corpus " +
                        "is, so it knows when searching is worthwhile."
                )
            }

            sub == "describe" -> {
                val text = if (rest == "-" || rest == "none") null else rest
                store.setDescription(text).fold(
                    onErr = { e -> warn(e) },
                    onOk = {
                        if (text == null) println("description cleared")
                        else println("description set: $text")
                    }
                )
            }

            sub == "inject" && rest.isEmpty() ->
                // Every other subcommand says its usage; falling through to
                // `bool` here reported "expected true or false, got ''", which
                // does not tell you what to type.
                warn("usage: /rag inject on|off (currently ${if (store.injectionEnabled) "on" else "off"})")

            sub == "inject" -> {
                bool(rest)?.let { on ->
                    store.setInjection(on).fold(
                        onErr = { e -> warn(e) },
                        onOk = {
                            if (on)
                                // Says "this session" explicitly, because the previous
                                // version persisted the choice and this one must not be
                                // mistaken for it.
                                println(
                                    "injection on for this session: up to ${runner.config.rag.injectTopK} " +
                                        "passage(s) will be added to every request. Off again on exit, or on " +
                                        "'/rag use', or with '/rag inject off'."
                                )
                            else println("injection off; the model must call the tool to retrieve")
                        }
                    )
                }
            }

            sub == "search" && rest.isEmpty() -> warn("usage: /rag search <query>")

            sub == "search" -> {
                store.search(rest, runner.config.rag.topK, null).fold(
                    onErr = { e -> warn(e) },
                    onOk = { hits ->
                        if (hits.isEmpty()) println("no passages matched '$rest'")
                        else {
                            println("${hits.size} passage(s) for '$rest':")
                            hits.forEach { (c, score) ->
                                val snippet =
                                    c.text.lines().map { it.trim() }.filter { it.isNotEmpty() }.take(2).joinToString(" ")
                                println("  %8.4f  %-40s %s".format(score, c.citation, snippet.take(90)))
                            }
                        }
                    }
                )
            }

            else -> warn("unknown /rag subcommand '$sub'; usage: /rag [${ragSubcommands.joinToString("|")}]")
        }
    }

    private fun loadFiles(runner: Runner, rest: String): ReplOutcome {
        val loader = runner.inputLoader
        val (specs, message) = loader.splitMessage(rest)
        val tokens = loader.tokenise(specs)

        return loader.load(tokens).fold(
            onErr = { e ->
                warn(e)
                ReplOutcome.Continue
            },
            onOk = { loaded ->
                // Skips are reported, never swallowed: believing the model saw a file
                // it never received is worse than sending nothing.
                loaded.skipped.forEach { s -> warn("skipped $s") }
                val images =
                    if (loaded.medias.isEmpty()) ""
                    else ", ${loaded.medias.size} image(s) ${loaded.medias.sumOf { it.bytes } / 1024}KB"
                println(
                    Ansi.Color.DarkGray.on(
                        "included ${loaded.blocks.size} block(s), ${loaded.totalChars} chars$images"
                    )
                )
                ReplOutcome.Send(
                    loaded.render(message),
                    loaded.medias.map { m -> kotllm.client.Part.Image(m.dataUrl) }
                )
            }
        )
    }

    private fun set(runner: Runner, key: String, value: String) {
        when (key) {
            "stream" -> bool(value)?.let { v -> runner.updateConfig { it.copy(stream = v) } }
            "highlight" -> bool(value)?.let { v -> runner.updateConfig { it.copy(highlight = v) } }
            "save" -> bool(value)?.let { v -> runner.updateConfig { it.copy(save = v) } }
            "reasoning" -> {
                val m = ReasoningDisplay.parse(value)
                if (m != null) runner.updateConfig { it.copy(reasoning = m) }
                else warn("reasoning must be one of: show, collapse, hide")
            }
            "reasoning_effort" -> {
                val e = kotllm.config.ReasoningEffort.parse(value)
                if (e != null) runner.updateConfig { it.copy(reasoningEffort = e) }
                else warn(
                    "reasoning_effort must be default or one of: " +
                        kotllm.config.ReasoningEffort.Known.joinToString(", ")
                )
            }
            "temperature" -> num(value)?.let { v -> runner.updateConfig { it.copy(temperature = v) } }
            "top_p" -> num(value)?.let { v -> runner.updateConfig { it.copy(topP = v) } }
            "max_tool_iterations" -> {
                val v = value.trim().toIntOrNull()?.takeIf { it > 0 }
                if (v != null) runner.updateConfig { it.copy(maxToolIterations = v) }
                else warn("max_tool_iterations must be a positive integer")
            }
            // Worth changing mid-session for the same reason as max_tool_iterations:
            // you discover a result was worth seeing in full by watching it get
            // elided, and editing config.yaml to look again is friction.
            "tool_output_lines" -> {
                val v = value.trim().toIntOrNull()?.takeIf { it >= 0 }
                if (v != null) runner.updateConfig { it.copy(toolResultLines = v) }
                else warn("tool_output_lines must be zero or a positive integer")
            }
            "tool_preview_lines" -> {
                val v = value.trim().toIntOrNull()?.takeIf { it >= 0 }
                if (v != null) runner.updateConfig { it.copy(toolPreviewLines = v) }
                else warn("tool_preview_lines must be zero or a positive integer")
            }
            "tool_approval" -> {
                val m = ApprovalMode.parse(value)
                if (m != null) runner.updateConfig { it.copy(toolApproval = m) }
                else warn("tool_approval must be one of: confirm, auto, deny")
            }
            "max_tokens" -> {
                if (value == "null" || value == "-") runner.updateConfig { it.copy(maxTokens = null) }
                else {
                    val v = value.trim().toIntOrNull()
                    if (v != null) runner.updateConfig { it.copy(maxTokens = v) }
                    else warn("max_tokens must be an integer, or null to let the endpoint decide")
                }
            }
            else -> warn("unknown setting '$key'; known: ${settableKeys.joinToString(", ")}")
        }
    }

    private fun bool(v: String): Boolean? {
        val t = v.trim().lowercase()
        return when (t) {
            "true", "yes", "on", "1" -> true
            "false", "no", "off", "0" -> false
            else -> {
                warn("expected true or false, got '$t'")
                null
            }
        }
    }

    private fun num(v: String): Double? =
        v.trim().toDoubleOrNull() ?: run {
            warn("expected a number, got '$v'")
            null
        }

    /** What `/prompt` reports, in whichever view is current.
     *
     * `full` prints the assembled prompt **verbatim**, not a section-by-section
     * digest: the question it answers is "what is actually being sent", and a
     * reformatted answer to that is not an answer. The footer carries the
     * arithmetic instead -- the user's own slice against everything the harness
     * prepended is the number that explains a context bill.
     */
    private fun showPrompt(runner: Runner) {
        val own = runner.session.systemPrompt?.takeIf { it.trim().isNotEmpty() }
        if (!runner.fullPromptView) {
            println(own ?: "(no system prompt)")
            println(dim("view: default -- '/prompt full' shows the injected sections too"))
        } else {
            val full = runner.assembledPrompt
            if (full == null) println("(no system prompt)")
            else {
                println(full)
                val mine = own?.length ?: 0
                val injected = full.length - mine
                println(
                    dim(
                        "view: full -- ${full.length} chars: $injected injected, $mine yours. " +
                            (if (runner.sessions.isEnabled) "Recorded to the session file every turn."
                             else "Not recorded: save is off.")
                    )
                )
                println()
                println(toolPayload(runner))
            }
        }
    }

    /** What the tool schemas cost, beside what the prompt costs.
     *
     * `/prompt full` reported the system message and stopped there, which on
     * this harness is an eighth of what a request carries: the schemas are a
     * sibling field of `messages`, not part of the prompt, but the provider
     * bills them as input all the same. Asking "why is my context so big" and
     * being shown the smaller half is how the question gets answered wrong.
     */
    private fun toolPayload(runner: Runner): String {
        val tools = runner.activeTools
        if (tools.isEmpty()) return dim("tools: none offered this turn")

        val sized = tools
            .map { t -> t.name to kotllm.client.ChatRequest.toolSchemas(listOf(t.definition)).toString().length }
            .sortedByDescending { it.second }
        val total = kotllm.client.ChatRequest.toolSchemas(tools.map { it.definition }).toString().length
        val width = sized.maxOf { it.first.length }

        val rows = sized.map { (name, chars) ->
            dim("  ${name.padEnd(width)}  %5d ch  ~%4dt".format(chars, chars / 4))
        }
        return (listOf(
            dim(
                "tool schemas -- ${tools.size} tools, %,d chars, ~%,d tokens, ".format(total, total / 4) +
                    "sent on every request alongside the prompt"
            )
        ) + rows).joinToString("\n")
    }

    private fun dim(msg: String): String =
        Ansi.Color.DarkGray.on(msg)

    private fun warn(msg: String) =
        System.err.println(Ansi.Color.Yellow.on("kotllm: $msg"))
}
