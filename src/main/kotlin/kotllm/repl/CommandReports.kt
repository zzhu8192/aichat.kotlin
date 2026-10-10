package kotllm.repl

import kotllm.*
import kotllm.Runner
import kotllm.config.Paths
import kotllm.config.ReasoningDisplay
import kotllm.render.Ansi
import kotllm.render.on
import kotllm.tools.ApprovalMode
import kotllm.tools.ToolRegistry

/** The text `/info`, `/help`, `/vars`, `/tools`, `/settings` and `--list-*`
 * print, plus the progress and summary lines the dispatcher reuses.
 *
 * Mixed into [Commands]; split out only for file size. Everything here
 * builds a String from a [Runner] and prints nothing itself, which is what
 * lets `Repl.runCommand` hold the pinned-region invariant in one place.
 */
interface CommandReports {

    /** The command table, supplied by [Commands]; `/help` and `--list-commands`
     * both render from it so the two can never disagree. */
    val all: List<ReplCommand>
    val settableKeys: List<String>

    /** Indexing a folder is slow enough to need feedback, but a line per file
     * would bury the summary, so this reports at intervals only.
     *
     * Public because `--rag-index` needs the same reporting from `Main`, and two
     * copies of "how often to print" would drift. */
    fun indexProgress(label: String = ""): (Int, Int) -> Unit = { done, total ->
        val step = maxOf(25, total / 10)
        if (total > 50 && (done % step == 0 || done == total))
            println(Ansi.Color.DarkGray.on("  indexing $done/$total files$label"))
    }

    fun compactionSummary(runner: Runner): String {
        val c = runner.config.compaction
        return if (!c.enabled) "off (compact_enabled: false)"
        else {
            val trigger = listOfNotNull(
                runner.target.model.maxInputTokens?.let { w ->
                    "${c.thresholdPercent}% of $w (${w * c.thresholdPercent / 100} tokens)"
                },
                c.absoluteThreshold?.let { t -> "or over $t tokens" }
            )
            val where = if (trigger.isEmpty()) "no trigger (model declares no window)" else trigger.joinToString(" ")
            "$where, keeping ${c.keepRecentExchanges} recent exchange(s)"
        }
    }

    /** What is actually being injected, with sizes, so an unexpectedly large or
     * stale memory is visible rather than inferred from behaviour. */
    fun memoryText(runner: Runner): String {
        if (!runner.memory.enabled) return "memories are disabled (memory_enabled: false)"

        val files = runner.memoryFiles
        return if (files.isEmpty()) {
            listOf(
                "no memories yet. Global dir: ${runner.memory.dir}",
                "The model can add one with ${kotllm.tools.Tools.AddMemoryName}, " +
                    "or use: /memory set <name> <content>"
            ).joinToString("\n")
        } else {
            val plan = runner.memory.plan()
            // The memory text itself, not the framing around it: the framing now
            // lives in the prompt template, and measuring a renderer that no longer
            // feeds the prompt would report a number nobody is charged for.
            val injected = plan.injected.sumOf { it.content.length }

            // Which tier each memory landed in is the thing worth reading here: a
            // memory listed rather than injected is one the model has to choose to
            // read, which changes whether it will be acted on.
            fun rows(fs: List<kotllm.memory.MemoryFile>): List<String> = fs.map { f ->
                val scope = kotllm.memory.MemoryScope.name(f.scope)
                "  %6d chars  [%-7s]  %-24s %s".format(f.chars, scope, f.name, f.label)
            }

            (listOf(
                "${files.size} memory file(s), $injected chars injected into the system prompt",
                "budget ${runner.config.memory.maxChars} chars; global dir ${runner.memory.dir}",
                "${plan.injected.size} injected in full:"
            ) + rows(plan.injected)
                + (if (plan.indexed.isEmpty()) emptyList()
                   else listOf(
                       "${plan.indexed.size} over budget, listed by name only " +
                           "(the model can call ${kotllm.memory.Memory.ReadName}):"
                   ) + rows(plan.indexed))
                + (if (plan.dropped.isEmpty()) emptyList()
                   else listOf("${plan.dropped.size} not included at all:") + rows(plan.dropped)))
                .joinToString("\n")
        }
    }

    /** Resolves the sources, reports anything skipped, and sends the result. */
    val helpText: String
        get() {
            val width = all.maxOf { it.name.length }
            val rows = all.joinToString("\n") { c -> "  ${c.name.padEnd(width)}  ${c.help}" }
            return """$rows

  Commands start with '/' (/status, /help).
  Anything else is sent to the model.
  Wrap input in ::: fences for multi-line messages.
  Ctrl-C stops a generation in progress, Ctrl-D exits.
"""
        }

    /** The `--list-<kind>` families.
     *
     * Bare names, one per line, no colour and no decoration: these exist for
     * scripts and shell completion, which is why they are separate from
     * `/info`, `/rag` and `/memory` -- those are the reports for a human and
     * are free to be pretty. Anything that would need parsing to be useful does
     * not belong here.
     */
    fun listing(runner: Runner, kind: String): Outcome<List<String>> = when (kind) {
        // Aliases included: the question this answers is "what can I pass to
        // -m", and an alias is a valid answer to it. Bare names either way, so
        // the pipeable contract holds.
        //
        // Only the ones that resolve, though. An alias pointing at nothing is not
        // an answer to that question, and offering it for shell completion would
        // hand the user a name that errors. It is not swallowed -- `aliasProblems`
        // names it at startup and in `/info`.
        "models" -> {
            val usable = runner.config.aliases.keys.sorted()
                .filter { a -> runner.config.resolveModel(a).isOk }
            ok((runner.config.listModels + usable).distinct())
        }

        // `--list-models --discover`. Bare names, one per line, like every other
        // `--list`: this exists for scripts and shell completion, so an
        // unreachable endpoint is an error rather than a decorated note.
        "models-discover" -> {
            val results = runner.config.discoverableClients.map { c -> c to kotllm.client.Models.list(c) }
            val found = results.flatMap { (c, r) -> r.orNull()?.map { n -> "${c.id()}:$n" } ?: emptyList() }
            if (found.isNotEmpty()) ok(found)
            else err(
                results.mapNotNull { (c, r) -> (r as? Outcome.Err)?.let { e -> "${c.id()}: ${e.message}" } }
                    .joinToString("; ")
            )
        }

        // Every registered name, not just the ones active right now: the question
        // this answers is "what can I put in use_tools", and a tool withheld
        // because no index is active is still a valid answer to that.
        "tools" -> {
            // Waits, unlike the footer: this answers "what can go in use_tools",
            // and omitting a tool because a background compile has not finished
            // would be a wrong answer rather than a stale one.
            runner.userTools.awaitReady()
            ok(runner.toolRegistry.all.map { it.name })
        }

        "rags" -> when (val store = runner.rag) {
            null -> err("rag is disabled (rag_enabled: false)")
            else -> ok(store.names)
        }

        "memories" ->
            if (!runner.memory.enabled) err("memories are disabled (memory_enabled: false)")
            else ok(runner.memoryFiles.map { it.path.toString() })

        "agents" -> ok(kotllm.agent.Agent.list())

        "skills" ->
            if (!runner.skills.enabled) err("skills are disabled (skills_enabled: false)")
            else ok(runner.skills.load().map { it.name })

        "vars" -> ok(runner.harnessVars.map { it.name })

        "commands" -> ok(all.map { it.name })

        else -> err(
            "unknown list '$kind'; known: models, tools, agents, skills, rags, memories, vars, commands"
        )
    }

    /** What is known about whether this endpoint will act on `reasoning_effort`.
     *
     * **kotllm cannot tell whether it was honoured.** A model that ignores the
     * field and thinks anyway is byte-for-byte indistinguishable from one that
     * honoured it and decided to think -- there is nothing in the response
     * saying which happened. So the support level is stated up front from the
     * provider rather than inferred afterwards from an answer, and the one
     * measurement that *is* real is pointed at: `/status` reports reasoning
     * volume in characters, which is what actually shows whether the setting
     * changed anything.
     */
    fun thinkingSupportNote(runner: Runner): String {
        val provider =
            kotllm.client.Provider.infer(runner.target.client.kind, runner.target.client.apiBase)
        val support = when (provider) {
            kotllm.client.Provider.Ollama ->
                "ollama passes reasoning_effort through on /v1, for models that think at all"
            kotllm.client.Provider.AzureFoundry ->
                "azure/openai reasoning models define this field; others ignore it"
            kotllm.client.Provider.Databricks ->
                "databricks support varies by served model, and some ignore it silently"
            else ->
                "support varies by server"
        }
        return listOf(
            "  $support.",
            "  Nothing in a reply says whether it was applied; " +
                "compare reasoning chars in /status."
        ).joinToString(System.lineSeparator())
    }

    /** One line for `/info`, naming the price source so the number is never
     * mistaken for an invoice. */
    fun costLine(runner: Runner): String {
        val spend = runner.cost
        return if (!runner.config.showCost && spend.totalUsd == 0.0) "(pricing disabled)"
        else {
            val priced = kotllm.util.Pricing.usd(spend.totalUsd)
            val unpriced =
                if (spend.anyLocal && !spend.anyUnpriced) "; local (${spend.localTokens} tok), no cost"
                else if (!spend.anyUnpriced) ""
                else "; ${spend.unpriced.keys.joinToString(", ")} unpriced"
            "$priced at list price, cached input not deducted$unpriced"
        }
    }

    /** `save` used to print a bare boolean for a key that did nothing. Now it
     * says where the conversation is actually going. */
    fun saveLine(runner: Runner): String =
        if (!runner.config.save) "false (conversations are not saved)"
        else
            runner.sessions.location?.let { p -> "true -> ${p.fileName}" }
                ?: "true -> ${Paths.sessionsDir()} (nothing written yet)"

    fun infoText(runner: Runner): String {
        val c = runner.config
        val t = runner.target
        val s = runner.session
        val window = t.model.maxInputTokens?.toString() ?: "unknown"
        val billed = s.billed()
        return """version           ${kotllm.util.BuildInfo.full()}
model             ${t.id()}
endpoint          ${t.client.apiBase}
client type       ${t.client.kind}
max_input_tokens  $window
tools             ${toolSummary(runner)}
stream            ${c.stream}
reasoning         ${ReasoningDisplay.name(c.reasoning)} (display only)
reasoning_effort  ${kotllm.config.ReasoningEffort.name(c.reasoningEffort)}
max_tokens        ${c.maxTokens?.toString() ?: "(endpoint default)"}
max_tool_iterations ${c.maxToolIterations} round trips per turn
rate limit        ${c.rateLimitFor(t)?.describe() ?: "none"}
temperature       ${c.temperature?.toString() ?: "(endpoint default)"}
highlight         ${c.highlight}
config dir        ${Paths.configDir()}
system prompt     ${s.systemPrompt?.let { p -> "${p.length} chars" } ?: "(none)"}
context           ${s.turns()} turns, ${s.messages().size} messages${
            if (s.compactionCount() > 0) ", compacted ${s.compactionCount()}x" else ""
        }
compaction        ${compactionSummary(runner)}
tokens billed     ${billed.promptTokens} in / ${billed.completionTokens} out
cost (estimated)  ${costLine(runner)}""" + aliasLines(runner)
    }

    /** Aliases, and anything wrong with them.
     *
     * `--info` is documented as the command that checks your setup, and a
     * broken alias is exactly the kind of thing it should catch -- the startup
     * hint only fires in the REPL, so single-hit users would never see it.
     */
    private fun aliasLines(runner: Runner): String {
        val aliases = runner.config.aliases
        val problems = runner.config.aliasProblems
        return if (aliases.isEmpty() && problems.isEmpty()) ""
        else {
            val indent = " ".repeat(18)
            val width = aliases.keys.maxOfOrNull { it.length } ?: 0
            val rows = aliases.toList().sortedBy { it.first }.joinToString("") { (name, target) ->
                "\n$indent${name.padEnd(width)} -> $target"
            }
            val warnings = problems.joinToString("") { p -> "\n" + Ansi.Color.Yellow.on("$indent! $p") }
            "\naliases           ${aliases.size}$rows$warnings"
        }
    }

    /** The variable table with live values, so what you see is what a prompt
     * would actually receive. */
    fun varsText(runner: Runner): String {
        val vars = runner.harnessVars
        val width = vars.maxOfOrNull { it.name.length } ?: 4
        val rows = vars.map { v ->
            val value = try {
                v.value()
            } catch (e: Exception) {
                "(unavailable)"
            }
            val shown = if (value.length > 60) value.take(57) + "..." else value
            "  {{${v.name.padEnd(width)}}}  ${Ansi.Color.Green.on(shown)}"
        }
        val header = "${vars.size} harness variables; escape a literal as " + "\\{{name}}"
        return (listOf(Ansi.Color.DarkGray.on(header)) + rows).joinToString("\n")
    }

    fun toolSummary(runner: Runner): String {
        val c = runner.config
        val detail =
            if (runner.activeTools.isEmpty()) ""
            else " [approval: ${ApprovalMode.name(c.toolApproval)}, eval timeout: ${c.evalTimeoutSeconds}s]"
        return runner.toolStatusSummary + detail
    }

    /** What `use_tools` and `tools_exclude` actually resolved to.
     *
     * `/info` has the one-line version. This one names what was *withheld*,
     * because the two ways an exclusion goes wrong -- "I excluded exec and the
     * model called it anyway" and "I excluded one thing and now nothing works"
     * -- are both questions about the tools that are missing, and a list of the
     * survivors answers neither.
     */
    fun toolSelectionText(runner: Runner): String {
        val c = runner.config
        if (!c.toolsEnabled)
            return "tools are off (function_calling: ${c.functionCalling}, " +
                "use_tools: ${c.useTools ?: "null"})"

        fun row(label: String, value: String): String = label.padEnd(15) + value

        val active = runner.activeTools.map { it.name }
        val lines = mutableListOf<String>()
        lines += row("offered (${active.size})", if (active.isEmpty()) "none" else active.joinToString(", "))

        if (c.toolsExclude.isNotEmpty()) {
            val withheld =
                runner.toolRegistry.all.map { it.name }.filter { n -> ToolRegistry.excluded(n, c.toolsExclude) }
            lines += row(
                "withheld (${withheld.size})",
                if (withheld.isEmpty()) "none" else withheld.joinToString(", ")
            )
            lines += row("", "by tools_exclude: ${c.toolsExclude.joinToString(", ")}")
            val unmatched = runner.toolRegistry.unmatchedExclusions(c.toolsExclude)
            if (unmatched.isNotEmpty())
                lines += Ansi.Color.Yellow.on(
                    row("", "matching no tool, so withholding nothing: ${unmatched.joinToString(", ")}")
                )
        }
        return lines.joinToString("\n")
    }

    fun settingsText(runner: Runner): String {
        val c = runner.config
        return """stream       ${c.stream}
highlight    ${c.highlight}
reasoning    ${ReasoningDisplay.name(c.reasoning)}
temperature  ${c.temperature?.toString() ?: "null"}
top_p        ${c.topP?.toString() ?: "null"}
max_tokens   ${c.maxTokens?.toString() ?: "null"}
save         ${saveLine(runner)}
max_tool_iterations  ${c.maxToolIterations}
tool_approval        ${ApprovalMode.name(c.toolApproval)}"""
    }
}
