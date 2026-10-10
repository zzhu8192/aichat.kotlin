package kotllm.config

import java.nio.file.Files
import java.nio.file.Path
import kotllm.*
import kotllm.client.Provider
import kotllm.compact.CompactionConfig
// present under src/main/kotlin/kotllm/engine yet -- only Classpath.kt and ExitGuard.kt exist).
import kotllm.engine.PythonConfig
import kotllm.input.InputLimits
import kotllm.memory.MemoryConfig
import kotllm.rag.ChunkProfile
import kotllm.rag.EmbeddingMode
import kotllm.rag.RagConfig
import kotllm.skill.SkillsConfig
import kotllm.tools.ApprovalMode
// a different file from Approval.scala -- not part of this batch).
import kotllm.tools.FileToolLimits
import kotllm.util.Conf
import kotllm.util.Pricing
import kotllm.util.Yaml
import kotllm.web.Engine
import kotllm.web.SearchConfig

/** How much of the model's reasoning output to show.
  *
  * Qwen 3.5 on Databricks emits reasoning as a distinct content part and is
  * extremely verbose with it -- a trivial arithmetic question spent 278 of 279
  * completion tokens thinking -- so the default collapses it to a status line.
  */
/** How hard the model is asked to think, sent as `reasoning_effort`.
  *
  * **Distinct from [[ReasoningDisplay]], and the difference is the point.**
  * That decides what reaches the screen; this decides whether the tokens are
  * generated at all. Hiding reasoning changes nothing about cost or latency.
  *
  * Values pass through verbatim rather than being mapped per provider, because
  * all three converged on OpenAI's spelling. `Off` sends `none`; `Default`
  * sends no field at all, leaving the provider's behaviour alone.
  * See docs/clients.md.
  */
sealed interface ReasoningEffort {
    val wire: String?

    /** Send nothing, so the provider's default applies. */
    data object Default : ReasoningEffort {
        override val wire: String? = null
    }

    /** An explicit level. Unrecognised strings are allowed through: the levels
      * are provider-defined and new ones keep appearing, so refusing an unknown
      * one would age worse than passing it on and letting the endpoint rule. */
    data class Level(val value: String) : ReasoningEffort {
        override val wire: String? = value
    }

    companion object {

        /** What `/think` offers and the completer suggests. Not a closed set for
          * parsing -- see `Level`. */
        val Known: List<String> = listOf("none", "minimal", "low", "medium", "high")

        fun parse(s: String): ReasoningEffort? {
            val trimmed = s.trim().lowercase()
            return when (trimmed) {
                "" -> null
                "default", "provider", "unset" -> Default
                // What someone types when they mean "stop thinking". `none` is the wire
                // value; support for it varies by provider, which is documented rather
                // than guessed at.
                "off", "no", "false", "disable", "disabled" -> Level("none")
                else -> Level(trimmed)
            }
        }

        fun name(e: ReasoningEffort): String = when (e) {
            is Default -> "default"
            is Level -> e.value
        }
    }
}

sealed interface ReasoningDisplay {
    data object Show : ReasoningDisplay
    data object Collapse : ReasoningDisplay
    data object Hide : ReasoningDisplay

    companion object {
        fun parse(s: String): ReasoningDisplay? = when (s.trim().lowercase()) {
            "show", "full", "true" -> Show
            "collapse", "summary" -> Collapse
            "hide", "none", "off", "false" -> Hide
            else -> null
        }

        fun name(d: ReasoningDisplay): String = when (d) {
            is Show -> "show"
            is Collapse -> "collapse"
            is Hide -> "hide"
        }
    }
}

/** The loaded configuration. Field names mirror the aichat config keys so an
  * existing config.yaml drops in unchanged; `raw` is retained so unimplemented
  * keys survive a round trip and `/info` can show what was in the file.
  */
data class Config(
    val model: String?,
    val stream: Boolean,
    val save: Boolean,
    val temperature: Double?,
    val topP: Double?,
    val maxTokens: Int?,
    val reasoning: ReasoningDisplay,
    /** Request-side thinking control. See [[ReasoningEffort]] for why this is
      * not the same key as `reasoning`. */
    val reasoningEffort: ReasoningEffort,
    /** Short names for models, so one config can carry several without an
      * endpoint id having to be typed at every call site.
      *
      * **The names carry no meaning.** Nothing reads a particular key; this
      * is a lookup table and `resolveModel` resolves whatever it finds. In
      * particular there is no weak/strong tier here, and there should not be:
      * a tier is a routing decision, and the one caller who would have to
      * make it -- the model itself -- cannot judge what a second model is
      * capable of.
      *
      * A separate map rather than an `alias:` key on each model entry,
      * because a model does not have to be declared to be reachable: an
      * Ollama client passes undeclared names through, so `gemma:
      * local:gemma4:26b` must work without a `models:` entry to hang the
      * alias off.
      */
    val aliases: Map<String, String>,
    val functionCalling: Boolean,
    val useTools: String?,
    /** Names withheld from whatever `use_tools` selected, as exact names or
      * `*` patterns.
      *
      * A subtraction rather than a second selector, because `use_tools`
      * already *is* the whitelist and two keys meaning one thing is how a
      * config key ends up parsed and ignored. What it adds is the case the
      * whitelist cannot express without being rewritten every time the tool
      * set grows: everything except these. */
    val toolsExclude: List<String>,
    val highlight: Boolean,
    val systemPrompt: String?,
    val clients: List<ClientConfig>,
    val connectTimeoutSeconds: Int,
    val maxRetries: Int,
    val rateLimit: RateLimitConfig?,
    val toolApproval: ApprovalMode,
    val execAllowlist: List<String>,
    val evalTimeoutSeconds: Int,
    val execTimeoutSeconds: Int,
    val classpathAppend: List<String>,
    val maxToolIterations: Int,
    val template: Boolean,
    val inputLimits: InputLimits,
    val webSearch: SearchConfig,
    val memory: MemoryConfig,
    val skills: SkillsConfig,
    /** The `read`/`write`/`edit`/`glob`/`grep`/`ls` set. A single switch as
      * well as `use_tools`, because "no writing to my disk" is a decision about
      * the whole family rather than a tool list to curate. */
    val fileToolsEnabled: Boolean,
    /** Compile and offer Scala tools from `<config dir>/tools/`. On by
      * default, because an empty directory costs one filesystem check; the
      * lever exists for a machine where startup latency matters more than the
      * tools do. */
    val userToolsEnabled: Boolean,
    /** `java_eval` plus `java` as a `language`. **Off by default**, and the key
      * exists to turn it on.
      *
      * Not because it is weak -- docs/engines.md records the opposite, and the
      * JShell child JVM is the only session here that can hard-stop a runaway
      * loop. It costs ~200 tokens of schema on every request, and a
      * Scala-first tool offering three interpreters by default spends that on
      * most users to serve a few. Still requires `jdk.jshell` to be present. */
    val javaEval: Boolean,
    /** `groovy_eval` plus `groovy` as a `language`. **On by default**, unlike
      * `java_eval`: it is the primary interpreter, and the one the system
      * prompt and every agent template render through. The key exists to turn
      * it off for a session that wants no interpreter at all. */
    val groovyEval: Boolean,
    /** Four tool families that are **off by default**, each purely on cost.
      *
      * Measured on a default config: the schemas are ~3.7k tokens on every
      * single request, against ~360 for the system prompt. These four are
      * ~1,240 of that, and each is dead weight on the many turns that are a
      * question rather than a task.
      *
      * Nothing about them is broken and none of the reasoning that built them
      * has changed. `docs/tools.md` is right that a tool the model is not told
      * about does not get used -- that is the price, and it is why the file
      * family, the interpreters and `exec` stay on. Turning one back on is one
      * key; discovering you are paying for it was not.
      */
    val taskTools: Boolean,
    val memoryTools: Boolean,
    val webTools: Boolean,
    val sessionTransferTool: Boolean,
    /** How many lines of a tool preview and a tool result reach the screen.
      *
      * Display only: the model gets the whole thing regardless, so this is
      * about whether a conversation stays readable when a tool reads a file.
      * The old hardcoded 12 and 16 buried it. */
    val toolPreviewLines: Int,
    val toolResultLines: Int,
    val python: PythonConfig,
    val pricing: Pricing,
    /** Whether the running total appears in the pinned footer. `/status`
      * reports it regardless -- this is only about chrome real estate. */
    val showCost: Boolean,
    val fileToolLimits: FileToolLimits,
    val compaction: CompactionConfig,
    val rag: RagConfig,
    /** Where this config was loaded from, so a diagnostic can name the file the
      * user actually has to edit rather than the default location. */
    val sourcePath: Path?,
    /** Overrides the chat agent template, bypassing `agents/chat.md` on disk.
      *
      * Not a YAML key and not for users: it exists so the test suite is not at
      * the mercy of whatever template happens to be in the developer's config
      * directory. The prompt became a file that anyone can edit, which quietly
      * made every Runner spec depend on that file -- the same hazard
      * `TestIsolation` was written for, arriving by a new route. */
    val chatAgent: String? = null,
    val raw: Conf
) {

    /** Tools are only offered when function calling is on AND something is
      * selected. aichat splits these two keys and so do we: `function_calling`
      * is the global switch, `use_tools` picks which. */
    val toolsEnabled: Boolean
        get() = functionCalling && (useTools?.let { v -> v.trim().isNotEmpty() && v.trim() != "none" } ?: false)

    /** The rate limit that applies to a target: the client's own override if it
      * declares one, else the global default. Limits belong to an endpoint or
      * workspace, so a per-client override is the common case once more than one
      * endpoint is configured. */
    fun rateLimitFor(target: ModelTarget): RateLimitConfig? = target.client.rateLimit ?: rateLimit

    /** Resolves a model id to a concrete endpoint + model name.
      *
      * Accepts `client:model`, a bare model name, or a bare client name. The
      * reference Databricks config names client and model identically, so all
      * three spellings land on the same target there. */
    fun resolveModel(id: String?): Outcome<ModelTarget> {
        val wanted = id?.trim()?.takeIf { it.isNotEmpty() } ?: model
        // Aliases expand first, so every route that resolves a model -- `-m`,
        // `/model`, the top-level `model:` key, `llm(model = ...)` -- gets them
        // from this one point. Adding them anywhere else would have meant four.
        return if (wanted == null) resolveConcrete(null)
        else when (val expanded = expandAlias(wanted, emptyList())) {
            is Outcome.Err -> expanded
            is Outcome.Ok -> resolveConcrete(expanded.value)
        }
    }

    /** Follows `aliases:` until it reaches something that is not an alias.
      *
      * Chained, because `main: qwen` is a reasonable line to write, and one hop
      * would resolve it to `qwen` and then fail with "unknown model".
      * The visited list makes a cycle an error rather than a hang.
      */
    private tailrec fun expandAlias(spec: String, seen: List<String>): Outcome<String> =
        if (seen.contains(spec))
            err("alias cycle in config: ${(seen + spec).joinToString(" -> ")}")
        else when (val target = aliases[spec]) {
            null -> ok(spec)
            else -> expandAlias(target.trim(), seen + spec)
        }

    /** Names an alias may not have.
      *
      * A colon would be read as the `client:model` separator before the alias
      * map is ever consulted, and whitespace cannot be typed after `-m`. Both
      * are reported rather than silently dropped -- a config key that parses and
      * does nothing is the failure this codebase keeps repeating.
      */
    private fun unusableAlias(name: String): String? =
        if (name.isEmpty()) "an alias name cannot be empty"
        else if (name.contains(':')) "alias '$name' cannot contain a colon -- that is the client:model separator"
        else if (name.any { it.isWhitespace() }) "alias '$name' cannot contain whitespace -- it has to be typeable after -m"
        else null

    /** Aliases that will not work, and why.
      *
      * Reported at startup rather than on first use: an alias is typed rarely
      * and often inside an agent file, so the natural moment to discover a typo
      * is halfway through a run that has already spent money.
      */
    val aliasProblems: List<String>
        get() = aliases.toList().sortedBy { it.first }.mapNotNull { (name, target) ->
            unusableAlias(name) ?: run {
                when (val expanded = expandAlias(name, emptyList())) {
                    is Outcome.Err -> expanded.message
                    is Outcome.Ok -> {
                        val spec = expanded.value
                        if (!resolveConcrete(spec).isOk)
                            "alias '$name' points at '$target', which is not a model this config can " +
                                "resolve; try --list-models"
                        // An alias that shadows a real name wins, because it is checked
                        // first. Worth saying so: the two can drift apart and the config
                        // then means something other than it reads like.
                        else if (clients.any { c -> c.id() == name || c.kind == name } ||
                            clients.any { c -> c.models.any { it.name == name } }
                        )
                            "alias '$name' has the same name as a configured client or model, and the " +
                                "alias wins -- '$name' now means '$spec'"
                        else null
                    }
                }
            }
        }

    /** Resolution once aliases are out of the way: `client:model`, a bare model
      * name, or a bare client name. The reference Databricks config names client
      * and model identically, so all three spellings land on the same target
      * there. */
    private fun resolveConcrete(wanted: String?): Outcome<ModelTarget> {
        if (wanted == null) {
            return when {
                clients.isEmpty() -> err("no clients configured; add a `clients:` entry to config.yaml")
                clients.size == 1 -> {
                    val c = clients[0]
                    val m = c.models.firstOrNull()
                    if (m != null) ok(ModelTarget(c, m))
                    else err("client '${c.id()}' declares no models; set a top-level `model:`")
                }
                else -> err("multiple clients configured; set a top-level `model:` to pick one")
            }
        }

        val spec = wanted

        // `client:model` -- but only when the prefix really names a client.
        //
        // **Ollama model names contain a colon.** `qwen3:27b` is the tag, and
        // splitting it produced "unknown client 'qwen3'" for the most ordinary
        // thing a local-server user can type. Exactly the rule `Commands.parse`
        // uses for a leading slash: it counts only when it names one that exists.
        if (spec.contains(':')) {
            val idx = spec.indexOf(':')
            val clientName = spec.substring(0, idx)
            val prefixed = clients.find { c -> c.id() == clientName || c.kind == clientName }
            if (prefixed != null) {
                val modelName = spec.substring(idx + 1)
                return ok(ModelTarget(prefixed, prefixed.model(modelName) ?: ModelConfig.synthetic(modelName)))
            }
        }

        val byModel = clients.asSequence().mapNotNull { c -> c.model(spec)?.let { m -> ModelTarget(c, m) } }.toList()
        val fromModel = byModel.firstOrNull()
        if (fromModel != null) return ok(fromModel)

        val byClient = clients.find { c -> c.id() == spec || c.kind == spec }
        if (byClient != null) {
            // A client named after its model, which is how Databricks
            // serving endpoints are usually configured.
            return ok(ModelTarget(byClient, byClient.models.firstOrNull() ?: ModelConfig.synthetic(spec)))
        }

        // Passed through only for a client whose model set genuinely
        // changes underneath the config: an `ollama pull` should not
        // need a config edit. Deliberately **not** widened -- for a
        // configured gateway the model list is fixed, so an unknown
        // name is a typo, and a local error beats an opaque 404.
        val ollamaOnly = clients.filter { c -> Provider.infer(c.kind, c.apiBase) == Provider.Ollama }
        return when {
            ollamaOnly.size == 1 -> ok(ModelTarget(ollamaOnly[0], ModelConfig.synthetic(spec)))
            // A spec that *looks* qualified failed for a different
            // reason than a bare one, and saying which is the
            // difference between a useful error and a puzzle.
            spec.contains(':') ->
                err("unknown client '${spec.substring(0, spec.indexOf(':'))}' in model id '$spec'; try --list-models")
            else -> err("unknown model '$spec'; try --list-models")
        }
    }

    val defaultTarget: Outcome<ModelTarget> get() = resolveModel(null)

    /** Every *declared* chat model, as `client:model` ids. What a server
      * currently has is a different question with a round trip attached, answered
      * by `--list-models --discover`: a `--list` that sometimes blocks on a dead
      * endpoint would be worse than one that is occasionally stale. */
    val listModels: List<String>
        get() = clients.flatMap { c -> c.models.map { m -> "${c.id()}:${m.name}" } }

    /** Clients worth asking, which is all of them: `/v1/models` is standard, so
      * this is not an Ollama-only feature even though Ollama is why it exists. */
    val discoverableClients: List<ClientConfig> get() = clients

    companion object {

        /** No `max_tokens` default of our own: a small cap truncates *inside* the
          * reasoning block and returns no visible text at all. Omitting the field
          * lets the endpoint apply its own large default, which is the safer failure
          * mode. See docs/clients.md, finding 3. */
        const val DefaultConnectTimeoutSeconds = 15
        const val DefaultMaxRetries = 3
        const val DefaultEvalTimeoutSeconds = 30
        const val DefaultExecTimeoutSeconds = 120

        /** Cap on tool-call round trips within one turn.
          *
          * 100 rather than the original 12: a real task -- read, grep, edit, test,
          * repeat -- passes twelve easily, and hitting the cap mid-task is friction
          * for no safety. Defensible only because Ctrl-C now actually stops a turn;
          * the cap had been doing double duty as the escape from a stuck loop, which
          * a round-trip counter is not. See docs/roadmap.md. */
        const val DefaultMaxToolIterations = 100

        fun load(path: Path = Paths.configFile()): Outcome<Config> {
            if (!Files.exists(path)) return err("no config file at $path")
            return try {
                ok(fromConf(Yaml.loadFile(path)).copy(sourcePath = path))
            } catch (e: IllegalArgumentException) {
                err("cannot parse $path: ${e.message}")
            } catch (e: Exception) {
                err("cannot parse $path: ${e.javaClass.simpleName}: ${e.message}")
            }
        }

        fun fromConf(c: Conf): Config {
            val parsedClients = c.subs("clients").map { ClientConfig.from(it) }
            val clients = parsedClients.mapNotNull { it.orNull() }
            // A malformed client entry should not take down a config that has other
            // working ones; surface it and carry on.
            val clientErrors = parsedClients.mapNotNull { r -> (r as? Outcome.Err)?.message }
            clientErrors.forEach { e -> System.err.println("kotllm: warning: $e") }

            return Config(
                model = c.str("model"),
                stream = c.bool("stream") ?: true,
                save = c.bool("save") ?: true,
                temperature = c.double("temperature"),
                topP = c.double("top_p"),
                maxTokens = c.int("max_tokens"),
                reasoning = c.str("reasoning")?.let { ReasoningDisplay.parse(it) } ?: ReasoningDisplay.Collapse,
                // Unset means unset: kotllm sends no `reasoning_effort` unless asked,
                // so adding this key cannot change what an existing config puts on the
                // wire.
                reasoningEffort = c.str("reasoning_effort")?.let { ReasoningEffort.parse(it) } ?: ReasoningEffort.Default,
                aliases = c.strMap("aliases"),
                functionCalling = c.bool("function_calling") ?: false,
                useTools = c.str("use_tools"),
                // A YAML list, a comma-separated string, or a space-separated one. A
                // tool name cannot contain whitespace, so accepting all three costs
                // nothing and spares the user guessing which this file wanted.
                toolsExclude = c.strList("tools_exclude").flatMap { it.split(Regex("\\s+")) }.map { it.trim() }.filter { it.isNotEmpty() },
                highlight = c.bool("highlight") ?: true,
                systemPrompt = c.str("system_prompt") ?: c.str("prompt"),
                clients = clients,
                connectTimeoutSeconds = c.int("connect_timeout") ?: DefaultConnectTimeoutSeconds,
                maxRetries = c.int("max_retries") ?: DefaultMaxRetries,
                rateLimit = RateLimitConfig.from(c, "rate_limit"),
                toolApproval = c.str("tool_approval")?.let { ApprovalMode.parse(it) } ?: ApprovalMode.Confirm,
                execAllowlist = c.strList("exec_allowlist"),
                evalTimeoutSeconds = c.int("eval_timeout")?.takeIf { it > 0 } ?: DefaultEvalTimeoutSeconds,
                execTimeoutSeconds = c.int("exec_timeout")?.takeIf { it > 0 } ?: DefaultExecTimeoutSeconds,
                classpathAppend = c.strList("classpath_append"),
                maxToolIterations = c.int("max_tool_iterations")?.takeIf { it > 0 } ?: DefaultMaxToolIterations,
                template = c.bool("template") ?: true,
                rag = run {
                    val d = RagConfig.default
                    RagConfig(
                        enabled = c.bool("rag_enabled") ?: d.enabled,
                        profile = c.str("rag_profile")?.let { ChunkProfile.parse(it) } ?: d.profile,
                        // aichat spells these rag_chunk_size / rag_chunk_overlap; same meaning.
                        chunkTokens = c.int("rag_chunk_size")?.takeIf { it > 0 },
                        overlapTokens = c.int("rag_chunk_overlap")?.takeIf { it >= 0 },
                        topK = c.int("rag_top_k")?.takeIf { it > 0 } ?: d.topK,
                        injectTopK = c.int("rag_inject_top_k")?.takeIf { it > 0 } ?: d.injectTopK,
                        injectMaxChars = c.int("rag_inject_max_chars")?.takeIf { it > 0 } ?: d.injectMaxChars,
                        // 0 disables the cap, for a corpus where one file per topic is normal.
                        maxPerFile = c.int("rag_max_per_file")?.takeIf { it >= 0 } ?: d.maxPerFile,
                        embedding = c.str("rag_embedding")?.let { EmbeddingMode.parse(it) } ?: d.embedding,
                        // Bounded rather than trusted: a tiny bucket count makes every
                        // vector collide into noise, and a huge one wastes disk for nothing.
                        embeddingDims = c.int("rag_embedding_dims")?.takeIf { it in 64..8192 } ?: d.embeddingDims,
                        minSimilarity = c.double("rag_min_similarity")?.takeIf { it >= 0 && it < 1 } ?: d.minSimilarity,
                        charGramMin = c.int("rag_char_ngram_min")?.takeIf { it in 2..8 } ?: d.charGramMin,
                        charGramMax = c.int("rag_char_ngram_max")?.takeIf { it in 2..12 } ?: d.charGramMax,
                        rrfK = c.int("rag_rrf_k")?.takeIf { it > 0 } ?: d.rrfK,
                        bm25K1 = c.double("rag_bm25_k1")?.takeIf { it > 0 } ?: d.bm25K1,
                        bm25B = c.double("rag_bm25_b")?.takeIf { it in 0.0..1.0 } ?: d.bm25B,
                        maxFileChars = c.int("rag_max_file_chars")?.takeIf { it > 0 } ?: d.maxFileChars,
                        extraExtensions = c.strList("rag_extra_extensions").map { it.lowercase().removePrefix(".") }.toSet(),
                        charsPerTokenProse = c.double("rag_chars_per_token_prose")?.takeIf { it > 0 } ?: d.charsPerTokenProse,
                        charsPerTokenCode = c.double("rag_chars_per_token_code")?.takeIf { it > 0 } ?: d.charsPerTokenCode
                    )
                },
                compaction = run {
                    val d = CompactionConfig.default
                    CompactionConfig(
                        enabled = c.bool("compact_enabled") ?: d.enabled,
                        thresholdPercent = c.int("compact_threshold_percent")?.takeIf { it in 1..100 } ?: d.thresholdPercent,
                        // aichat spells the absolute trigger `compress_threshold`.
                        absoluteThreshold = c.int("compress_threshold")?.takeIf { it > 0 },
                        keepRecentExchanges = c.int("compact_keep_recent")?.takeIf { it > 0 } ?: d.keepRecentExchanges,
                        minHeadExchanges = c.int("compact_min_head")?.takeIf { it > 0 } ?: d.minHeadExchanges,
                        trimToolResults = c.bool("compact_trim_tool_results") ?: d.trimToolResults,
                        toolResultMaxChars = c.int("compact_tool_result_max_chars")?.takeIf { it > 0 } ?: d.toolResultMaxChars,
                        summaryMaxChars = c.int("compact_summary_max_chars")?.takeIf { it > 0 } ?: d.summaryMaxChars,
                        summarizePrompt = c.str("summarize_prompt") ?: d.summarizePrompt,
                        summaryPrompt = c.str("summary_prompt") ?: d.summaryPrompt
                    )
                },
                memory = MemoryConfig(
                    enabled = c.bool("memory_enabled") ?: true,
                    maxChars = c.int("memory_max_chars")?.takeIf { it > 0 } ?: 8000,
                    projectFile = c.str("memory_project_file") ?: "KOTLLM.md"
                ),
                skills = run {
                    val d = SkillsConfig.default
                    SkillsConfig(
                        enabled = c.bool("skills_enabled") ?: d.enabled,
                        maxChars = c.int("skills_max_chars")?.takeIf { it > 0 } ?: d.maxChars,
                        bodyMaxChars = c.int("skills_body_max_chars")?.takeIf { it > 0 } ?: d.bodyMaxChars,
                        claudeDirs = c.bool("skills_claude_dirs") ?: d.claudeDirs,
                        extraDirs = c.strList("skills_dirs")
                    )
                },
                fileToolsEnabled = c.bool("file_tools") ?: true,
                userToolsEnabled = c.bool("user_tools") ?: true,
                javaEval = c.bool("java_eval") ?: false,
                groovyEval = c.bool("groovy_eval") ?: true,
                taskTools = c.bool("task_tools") ?: false,
                memoryTools = c.bool("memory_tools") ?: false,
                webTools = c.bool("web_tools") ?: false,
                sessionTransferTool = c.bool("session_transfer") ?: false,
                toolPreviewLines = c.int("tool_preview_lines") ?: 3,
                toolResultLines = c.int("tool_output_lines") ?: 6,
                pricing = if (!(c.bool("pricing_enabled") ?: true)) Pricing.disabled
                          else Pricing.load(c.sub("pricing"), c.double("dbu_rate")),
                showCost = c.bool("show_cost") ?: true,
                python = run {
                    val d = PythonConfig.default
                    PythonConfig(
                        enabled = c.bool("python_eval") ?: d.enabled,
                        launcher = c.str("python_exe")?.trim()?.takeIf { it.isNotEmpty() } ?: d.launcher,
                        env = c.str("python_env")?.trim()?.takeIf { it.isNotEmpty() } ?: d.env,
                        packages = c.strList("python_packages"),
                        autoInstall = c.bool("python_auto_install") ?: d.autoInstall,
                        timeoutSeconds = c.int("python_timeout")?.takeIf { it > 0 } ?: d.timeoutSeconds
                    )
                },
                fileToolLimits = run {
                    val d = FileToolLimits.default
                    d.copy(
                        readMaxChars = c.int("file_read_max_chars")?.takeIf { it > 0 } ?: d.readMaxChars,
                        grepMaxResults = c.int("grep_max_results")?.takeIf { it > 0 } ?: d.grepMaxResults,
                        bindMaxChars = c.int("read_to_var_max_chars")?.takeIf { it > 0 } ?: d.bindMaxChars
                    )
                },
                webSearch = run {
                    val w = c.sub("web_search")
                    val d = SearchConfig.default
                    if (w == null) d
                    else SearchConfig(
                        // `provider` governs *general* web search only, and defaults to
                        // none: a scraped engine is the model initiating an outbound
                        // request with a query it invented, and read-only tools skip the
                        // approval prompt. The documented JSON sources need no opt-in.
                        engine = w.str("provider")?.let { Engine.parse(it) } ?: d.engine,
                        apiBase = w.str("api_base")?.trim()?.takeIf { it.isNotEmpty() },
                        results = w.int("results")?.takeIf { it in 1..25 } ?: d.results,
                        timeoutSeconds = w.int("timeout")?.takeIf { it > 0 } ?: d.timeoutSeconds,
                        sites = w.strList("sites"),
                        stackSite = w.str("stack_site")?.trim()?.takeIf { it.isNotEmpty() } ?: d.stackSite,
                        enabled = w.bool("enabled") ?: d.enabled
                    )
                },
                inputLimits = InputLimits(
                    maxFileChars = c.int("input_max_file_chars")?.takeIf { it > 0 } ?: 100000,
                    maxTotalChars = c.int("input_max_total_chars")?.takeIf { it > 0 } ?: 400000,
                    maxFiles = c.int("input_max_files")?.takeIf { it > 0 } ?: 200,
                    maxImageBytes = c.int("input_max_image_bytes")?.takeIf { it > 0 } ?: 5_000_000,
                    htmlToMarkdown = c.bool("html_to_markdown") ?: true,
                    // aichat spells these `document_loaders`; same shape, so an existing
                    // config keeps working.
                    loaders = c.sub("document_loaders")?.let { sub ->
                        sub.raw.entries.mapNotNull { (k, v) -> if (v is String) k.lowercase() to v else null }.toMap()
                    } ?: emptyMap()
                ),
                sourcePath = null,
                chatAgent = null,
                raw = c
            )
        }
    }
}
