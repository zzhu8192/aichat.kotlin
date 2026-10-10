package kotllm

import java.io.IOException

import kotlinx.serialization.json.*

import kotllm.client.*
import kotllm.compact.CompactionOutcome
import kotllm.compact.Compactor
import kotllm.config.ChatSearch
import kotllm.config.Config
import kotllm.config.ModelTarget
import kotllm.config.Paths
import kotllm.config.ReasoningDisplay
import kotllm.config.Session
import kotllm.config.SessionStore
import kotllm.engine.Classpath
import kotllm.input.InputLoader
import kotllm.memory.Memory
import kotllm.memory.MemoryFile
import kotllm.prompt.ChatAgent
import kotllm.render.*
import kotllm.template.HarnessVars
import kotllm.template.Sink
import kotllm.template.Template
import kotllm.template.TemplateEngine
import kotllm.template.VarDef
import kotllm.template.Vars
import kotllm.tools.*
import kotllm.util.Glyphs
import kotllm.util.RateLimiter
import kotllm.util.TokenEstimator
import kotllm.util.Verdict
import kotllm.util.ToolCallMetrics
import kotllm.util.TurnMetrics

/** Executes a turn against the endpoint, including any tool round trips, and
  * renders it.
  *
  * Shared by single-hit mode and the REPL so both build identical requests.
  * The only difference is the [[Session]] handed in: single-hit mode gets an
  * empty one, which is what makes it context-free by construction.
  */
class Runner(
    private var configRef: Config,
    val session: Session,
    private var targetRef: ModelTarget,
    private var clientRef: Client,
    prompter: Prompter = Prompter.unavailable,
    interactive: Boolean = System.console() != null,
    /** Suppresses all user-facing output. Set for background turns, whose output
      * would otherwise corrupt the line editor, and settable afterwards because an
      * agent decides whether it is watched *in* the file. */
    quiet: Boolean = false,
    /** Shared so background turns throttle against the same window as the
      * foreground; a private window per turn could together exceed the limit. */
    private val sharedLimiters: MutableMap<String, RateLimiter>? = null
) : RunnerEngines(configRef, quiet) {

    /** Renamed from the constructor parameter of the same name: `quiet` is `var`
      * because `setQuiet` mutates it after construction, but the value the base
      * class's eager `extraClasspath` check needed was the argument as passed
      * in, not a live read -- see `RunnerEngines` for why. */
    private var quiet: Boolean = quiet

    private val prompter: Prompter = prompter
    private val interactive: Boolean = interactive

    override fun liveConfig(): Config = configRef

    internal fun say(f: () -> Unit) {
        if (!quiet) f()
    }

    fun setQuiet(value: Boolean) {
        quiet = value
    }

    /** The column a reply is rendered in. Empty in single-hit mode and
      * `--dry-run`, whose output is piped as often as read. */
    var gutter: Gutter = Gutter.none

    /** Where a rendered reply goes. `System.out` for a chat; an agent sets
      * `System.err`, because its stdout belongs to the artifact it produces. */
    var renderTo: java.io.PrintStream = System.out

    val config: Config get() = configRef
    val target: ModelTarget get() = targetRef
    val client: Client get() = clientRef

    /** Shared across models: the chars-per-token ratio tracks the text being sent
      * more than it tracks the tokenizer. */
    private val estimator = TokenEstimator()

    /** One limiter per client, since a rate limit belongs to an endpoint rather
      * than to a model name. Switching models inside a client keeps the window. */
    private val limiters: MutableMap<String, RateLimiter> = sharedLimiters ?: mutableMapOf()

    /** Cheap to construct -- nothing is read from disk until the active index is
      * asked for -- so `/rag` can report state before any turn has run. */
    val rag: kotllm.rag.RagStore? =
        if (!configRef.rag.enabled) null
        else kotllm.rag.RagStore(Paths.ragDir(), configRef.rag) { configRef.inputLimits.loaders }

    /** Cheap to construct; nothing is walked until asked. */
    val skills: kotllm.skill.Skills = kotllm.skill.Skills(configRef.skills, Paths.skillsDir())

    /** One per runner rather than per process, so a background turn or an agent
      * keeps its own plan -- a shared list would have two authors renumbering it
      * under each other. */
    val checklist: kotllm.task.Checklist = kotllm.task.Checklist()

    /** Constructed eagerly but compiles nothing until asked; with no `tools/`
      * directory it costs one `isDirectory` check. */
    val userTools: UserTools = run {
        val ut = UserTools(Paths.toolsDir()) { engine }
        // Off the critical path: a script compile overlaps with the user reading
        // the prompt. Awaited only where the answer is needed.
        if (configRef.userToolsEnabled) ut.startInBackground()
        // The same, for the interpreter itself. Every turn renders the prompt
        // template through it, so it is no longer optional.
        warmEngineInBackground()
        ut
    }

    /** Keyed on `UserTools.version` so it rebuilds exactly once per change. A
      * `by lazy` would freeze the list at whatever existed the first time anything
      * asked, which with a background compile is reliably none. */
    private var registryCache: Pair<Int, ToolRegistry>? = null

    private val registry: ToolRegistry
        get() = synchronized(this) {
            val v = if (configRef.userToolsEnabled) userTools.version else -1
            val cached = registryCache
            if (cached != null && cached.first == v) cached.second
            else {
                val r = buildRegistry()
                registryCache = Pair(v, r)
                r
            }
        }

    private fun buildRegistry(): ToolRegistry =
        ToolRegistry(
            Tools.defaults(
                engine = engine,
                execTimeoutSeconds = configRef.execTimeoutSeconds,
                memory = memory,
                rag = rag,
                webSearchConfig = configRef.webSearch,
                htmlToMarkdown = configRef.inputLimits.htmlToMarkdown,
                fileTools = configRef.fileToolsEnabled,
                // Registered rather than merely available: with none installed the
                // schema is pure cost and an invitation to a call that can only fail.
                skills = if (skills.enabled && skills.load().isNotEmpty()) skills else null,
                fileToolLimits = configRef.fileToolLimits,
                java = javaEngine,
                python = pythonEngine,
                // Withdrawn entirely when nobody could answer, rather than registered
                // and refused on every call.
                prompter = if (interactive) prompter else null,
                interactive = { interactive },
                // Only once something is stored: a search over an empty corpus is a
                // schema the model can only waste a call on.
                chats =
                    if (chatSearch.sessions().isEmpty()) null
                    else Pair(chatSearch, { sessions.id.takeIf { it.isNotEmpty() } }),
                checklist = if (configRef.taskTools) checklist else null,
                memoryTools = configRef.memoryTools,
                webTools = configRef.webTools,
                transferTool = configRef.sessionTransferTool,
                // Non-blocking. The status footer reaches this on every redraw, and
                // blocking a prompt redraw on a compiler would be the worse bug.
                userTools = if (configRef.userToolsEnabled) userTools.ready else emptyList()
            )
        )

    private val approval: Approval by lazy {
        Approval({ configRef.toolApproval }, configRef.execAllowlist, prompter, interactive)
    }

    /** The tools offered on this request, honouring `function_calling`,
      * `use_tools` and `tools_exclude`. An unknown name in `use_tools` is
      * reported once, loudly, rather than quietly disabling the tool. */
    private var useToolsWarned = false

    val activeTools: List<Tool>
        get() {
            if (!configRef.toolsEnabled) return emptyList()
            return registry.selected(configRef.useTools, configRef.toolsExclude).fold(
                onErr = { e ->
                    if (!useToolsWarned) {
                        useToolsWarned = true
                        say { System.err.println(Ansi.Color.Yellow.on("kotllm: $e")) }
                    }
                    emptyList()
                },
                onOk = { ts ->
                    ts.filterNot { t ->
                        (t.name == Tools.SearchName && !ragUsable) ||
                            (t.name == Tools.WebSearchName && !configRef.webSearch.enabled) ||
                            (t.name == Memory.ReadName && !memory.hasIndexOnly())
                    }
                }
            )
        }

    /** Registered whenever RAG is enabled, offered only while an index is active:
      * a tool the model can only fail to use still costs schema tokens. Recomputed
      * per request, so `/rag index` takes effect immediately. */
    private val ragUsable: Boolean get() = rag?.active != null

    /** Same rule: under the injection budget every memory is already in front of
      * the model, so the tool could only re-fetch what it is reading. */

    val toolRegistry: ToolRegistry get() = registry

    /** Shown unprompted because of what happened in practice: the user asks the
      * model to run something, it truthfully says it has no tools, and nothing on
      * screen explains why. */
    val toolStatusSummary: String
        get() {
            if (!configRef.functionCalling) return "off (function_calling: false)"
            val ts = activeTools
            return when {
                // Naming the key that actually emptied the list. `use_tools: all` with
                // everything excluded reported "none selected (use_tools: all)",
                // which points at the one setting that is not the problem.
                ts.isEmpty() && configRef.toolsExclude.isNotEmpty() &&
                    (registry.selected(configRef.useTools).orNull()?.isNotEmpty() ?: false) ->
                    "none left (tools_exclude: ${configRef.toolsExclude.joinToString(", ")})"
                ts.isEmpty() -> "none selected (use_tools: ${configRef.useTools ?: "null"})"
                else -> ts.joinToString(", ") { it.name }
            }
        }

    /** The model declares function-calling support while kotllm offers no tools.
      * Almost always a missing key rather than a deliberate choice. */
    val toolConfigHint: String?
        get() {
            if (activeTools.isNotEmpty() || !targetRef.model.supportsFunctionCalling) return null
            val missing =
                if (!configRef.functionCalling && configRef.useTools == null) "function_calling: true' and 'use_tools: all"
                else if (!configRef.functionCalling) "function_calling: true"
                else "use_tools: all"
            // Telling someone to add `use_tools: all` when they already have it and
            // an exclusion ate the list is advice that cannot work.
            return if (configRef.toolsExclude.isNotEmpty() &&
                (registry.selected(configRef.useTools).orNull()?.isNotEmpty() ?: false)
            )
                "${targetRef.model.name} supports tool calling, but tools_exclude " +
                    "(${configRef.toolsExclude.joinToString(", ")}) withheld every tool " +
                    "use_tools selected. Narrow it in ${configRef.sourcePath ?: Paths.configFile()}."
            else
                "${targetRef.model.name} supports tool calling, but no tools are enabled. " +
                    "Add '$missing' to ${configRef.sourcePath ?: Paths.configFile()} to let the model run code."
        }

    /** Said once at startup when an Ollama endpoint has no declared context
      * window: Ollama runs every model at its own window and **silently truncates**
      * a longer prompt, which compaction, the context gauge and `read_to_var` all
      * then reason about wrongly. `num_ctx` is deliberately not sent to fix it --
      * see docs/clients.md. */
    val contextHint: String?
        get() {
            if (Provider.infer(targetRef.client.kind, targetRef.client.apiBase) != Provider.Ollama) return null
            if (targetRef.model.maxInputTokens != null) return null
            return "${targetRef.model.name} is on an Ollama endpoint with no max_input_tokens set. " +
                "Ollama truncates a long prompt silently at its own window, and kotllm cannot " +
                "see that happen. Set max_input_tokens for the model, and set the server to match " +
                "(OLLAMA_CONTEXT_LENGTH, or PARAMETER num_ctx in a Modelfile)."
        }

    /** Printed rather than pinned in the footer: both are prose about a config
      * file rather than status fields. */
    val startupHints: List<String>
        get() = listOfNotNull(toolConfigHint, contextHint) + userToolHints + toolExcludeHints +
            configRef.aliasProblems.map { p -> "model alias: $p" }

    /** An exclusion that withholds nothing is worth saying out loud: the user
      * believes a tool is off and it is on, which is the one direction of this
      * mistake that can hurt. Not fatal, because which tools are registered
      * depends on config -- see `ToolRegistry.unmatchedExclusions`. */
    private val toolExcludeHints: List<String>
        get() {
            val unmatched = registry.unmatchedExclusions(configRef.toolsExclude)
            return if (unmatched.isEmpty()) emptyList()
            else listOf(
                "tools_exclude entr${if (unmatched.size == 1) "y" else "ies"} matching no tool, " +
                    "so nothing is withheld by ${if (unmatched.size == 1) "it" else "them"}: " +
                    "${unmatched.joinToString(", ")}. Run --list-tools for the names."
            )
        }

    /** A tool file that does not compile is the one failure the user cannot see
      * any other way -- the model is never told the tool exists, which looks
      * exactly like it choosing not to use it. */
    private val userToolHints: List<String>
        get() {
            if (!configRef.userToolsEnabled) return emptyList()
            val broken = userTools.load().mapNotNull { f ->
                f.error?.let { e -> "${f.path.fileName}: ${e.lineSequence().take(2).joinToString(" | ")}" }
            }
            // Two lines: the diagnostic and the source it points at. One was not
            // enough -- see `UserTools.firstError`.
            val clashes =
                if (registry.shadowed.isEmpty()) emptyList()
                else listOf(
                    "tool name(s) already taken by a built-in, so yours are not offered: " +
                        registry.shadowed.joinToString(", ")
                )
            return broken + clashes
        }

    val limiter: RateLimiter?
        get() = configRef.rateLimitFor(targetRef)?.let { cfg ->
            val existing = limiters[targetRef.client.id()]
            // Recreate if the configured limits changed under us, e.g. after a
            // config reload, so a stale window does not keep enforcing old numbers.
            if (existing != null && existing.config == cfg) existing
            else {
                val fresh = RateLimiter(cfg)
                limiters[targetRef.client.id()] = fresh
                fresh
            }
        }

    val tokenEstimator: TokenEstimator get() = estimator

    /** Templates render through the Kotlin session, which is also where
      * `groovy_eval` runs: a value computed in the system prompt stays
      * available to the tool calls that follow it. */
    private val templates: TemplateEngine by lazy { TemplateEngine(templateEngine) }

    /** Context compaction. Reads config live so `/set` can retune it. */
    val compactor: Compactor = Compactor(configRef.compaction)

    /** Resolves `/file` / `-f` arguments. Reads `lastReply` lazily so `%%`
      * always picks up the most recent turn rather than a stale snapshot. */
    val inputLoader: InputLoader by lazy { InputLoader(configRef.inputLimits) { session.lastReply() } }

    val harnessVars: List<VarDef> get() = HarnessVars.table(this)

    /** Conversations on disk, honouring `save:`. One file per session, appended
      * per turn, so a kill keeps everything up to the last completed reply.
      *
      * Not created for a forked runner: a background turn and an agent get
      * `SessionStore.disabled`, since their replies are not the user's
      * conversation. */
    /** Cheap to construct -- nothing is read until asked -- so registration can
      * consult it. */
    val chatSearch: ChatSearch = ChatSearch(Paths.sessionsDir())

    val sessions: SessionStore =
        if (!configRef.save) SessionStore.disabled
        else SessionStore(Paths.sessionsDir(), true)

    /** Recomputed rather than accumulated: the price table can change under a
      * `/set`, and a running total would then be part old prices and part new. */
    val cost: kotllm.util.CostBreakdown get() = configRef.pricing.breakdown(session.ledger.all())

    /** Resolves `{{name}}` variables, then `<% %>` templates.
      *
      * Variables first, so a harness value can be used inside template code; a
      * substituted value has `<%` neutralised, so a fact can never become code.
      * Applied at the input boundary rather than in `ask`, so `/regenerate`
      * replays the expanded text instead of re-evaluating it. */
    fun expand(text: String): Outcome<String> =
        if (!configRef.template) ok(text)
        else Vars.expand(text, HarnessVars.asMap(this)).flatMap { substituted ->
            if (!Template.hasTags(substituted)) ok(substituted) else templates.render(substituted)
        }

    /** Variables are expanded first as for any other input; the template then runs
      * with the sink exposed, which is what lets `system { }` capture its block. */
    fun renderAgent(source: String, onSink: (Sink) -> Unit): Outcome<String> =
        Vars.expand(source, HarnessVars.asMap(this)).flatMap { substituted ->
            if (!Template.hasTags(substituted)) ok(substituted)
            // A ceiling rather than the eval timeout: an agent's program is the
            // user's own and waits on calls it made on purpose. The real limit is the
            // run's `maxSeconds`; this only stops a wedged run sitting for ever.
            else templates.render(substituted, onSink, AgentCeilingMillis)
        }

    fun wouldExpand(text: String): Boolean =
        configRef.template && (Template.hasTags(text) || Vars.hasReferences(text))

    fun updateConfig(f: (Config) -> Config) {
        configRef = f(configRef)
    }

    /** Switches model, rebuilding the client since a different model may live
      * behind a different endpoint entirely. */
    fun switchModel(id: String): Outcome<ModelTarget> {
        val t = configRef.resolveModel(id).fold(onErr = { e -> return err(e) }, onOk = { it })
        val c = Client.forTarget(t, configRef).fold(onErr = { e -> return err(e) }, onOk = { it })
        targetRef = t
        clientRef = c
        return ok(t)
    }

    /** Markdown memories. Reloaded per turn, so editing a memory file in another
      * window takes effect on the next message rather than needing a restart. */
    val memory: Memory by lazy { Memory(configRef.memory, Paths.memoryDir()) }

    val memoryFiles: List<MemoryFile> get() = memory.load()

    /** Whether `/prompt` reports the assembled prompt rather than the user's own
      * slice, and whether the assembled prompt is recorded to the session file.
      *
      * Session-scoped and deliberately unpersistable, the same rule as RAG
      * injection: it is a debugging view, and a config key for it would keep
      * fattening every session file on disk long after the question that
      * prompted it was answered. */
    private var promptFull = false

    var fullPromptView: Boolean
        get() = promptFull
        set(value) {
            promptFull = value
        }

    /** The system prompt as it would be sent on the next turn.
      *
      * Assembled fresh rather than cached, because most of it is rebuilt per
      * request: a memory written this turn, a checklist the model just updated
      * and the RAG injection block all change underneath a stored copy. The
      * retrieval query is the last thing the user said, which is what the next
      * turn would retrieve against on a tool round trip. */
    val assembledPrompt: String? get() = systemPrompt(retrievalQuery(null))

    /** Renders `agents/chat.md`, which *is* the system prompt.
      *
      * This used to be a `List(...)` right here: eight sections in an order only
      * the source revealed, with no way to reorder, drop or annotate any of
      * them. The order and the membership now live in a file the user owns, and
      * every section reaches the prompt by being called from it rather than by
      * being appended behind it.
      */
    private fun systemPrompt(retrievalQuery: String?): String? {
        
        // this file, per the porting plan). Carried over unchanged; assumed
        // constructor shape `PromptApi(runner: Runner, retrievalQuery: String?)`.
        val api = kotllm.prompt.PromptApi(this, retrievalQuery)
        if (!engine.bind("prompt", api)) {
            promptWarning("could not bind the prompt API into the interpreter")
            return null
        }
        // A broken edit costs the user their customisation, never the tool
        // manifest: a silently empty system prompt looks like the model going
        // stupid, and nothing on screen would connect it to the file.
        val template = configRef.chatAgent ?: ChatAgent.source
        return render(template) ?: (if (template == ChatAgent.packaged) null else render(ChatAgent.packaged))
    }

    private fun render(template: String): String? =
        templates.render(template).fold(
            onErr = { e ->
                promptWarning("${ChatAgent.file}: $e")
                null
            },
            onOk = { out -> ChatAgent.tidy(out).takeIf { it.isNotEmpty() } }
        )

    /** Once per distinct message: the prompt is rebuilt every turn, and a broken
      * template would otherwise print the same paragraph on every one. */
    private var warnedPrompt: Set<String> = emptySet()

    private fun promptWarning(message: String) {
        synchronized(this) {
            if (!warnedPrompt.contains(message)) {
                warnedPrompt = warnedPrompt + message
                System.err.println("kotllm: $message")
            }
        }
    }

    /** The message about to be sent, or on a tool round trip the last one the user
      * wrote. Deliberately not the tool results -- retrieving against those would
      * let the injected context drift away from the question over a loop. */
    private fun retrievalQuery(pendingUser: String?): String? =
        pendingUser ?: session.messages().asReversed()
            .find { it.role == "user" }
            ?.text?.trim()
            ?.takeIf { it.isNotEmpty() }

    private fun messagesToSend(pendingUser: String?): List<Message> {
        val head = systemPrompt(retrievalQuery(pendingUser))?.let { listOf(Message.system(it)) } ?: emptyList()
        val tail = pendingUser?.let { listOf(Message.user(it)) } ?: emptyList()
        return head + session.messages() + tail
    }

    fun buildRequest(pendingUser: String?, stream: Boolean): ChatRequest =
        ChatRequest(
            model = targetRef.model.name,
            messages = messagesToSend(pendingUser),
            temperature = configRef.temperature,
            topP = configRef.topP,
            maxTokens = configRef.maxTokens,
            stream = stream,
            tools = activeTools.map { it.definition },
            reasoningEffort = configRef.reasoningEffort.wire
        )

    /** Honouring `supports_vision` is the difference between a clear refusal here
      * and an opaque 400 after uploading a megabyte. The message names the key,
      * because the default is `false`. */
    private fun attachable(images: List<Part.Image>): List<Part.Image> =
        if (images.isEmpty() || targetRef.model.supportsVision) images
        else {
            say {
                System.err.println(
                    Ansi.Color.Yellow.on(
                        "kotllm: skipped ${images.size} image(s): ${targetRef.model.name} does not " +
                            "declare vision support. Add 'supports_vision: true' to its model entry in " +
                            "${configRef.sourcePath ?: Paths.configFile()} if it does."
                    )
                )
            }
            emptyList()
        }

    /** Serialised size of a request and how many inline images it carries, with
      * base64 payloads excluded from the count: a data URL is hundreds of thousands
      * of characters costing a fixed handful of tokens, and counting it would teach
      * the estimator a ratio wrong by two orders of magnitude. */
    private fun measure(req: ChatRequest): Pair<Int, Int> {
        val inline = req.messages.flatMap { it.images }.map { it.url }.filter { it.startsWith("data:") }
        val chars = req.encode().toString().length - inline.sumOf { it.length }
        return Pair(maxOf(0, chars), inline.size)
    }

    /** Tile-based pricing on the endpoints we target lands between 700 and 1100
      * tokens for a full-size image. A deliberate flat guess, labelled as one
      * wherever it surfaces. */
    private val ImageTokenAllowance = 800

    private fun estimateFor(req: ChatRequest): Int {
        val (chars, images) = measure(req)
        return estimator.estimate(chars, 0) + images * ImageTokenAllowance
    }

    /** The request the endpoint last counted: its measured size in characters and
      * the `prompt_tokens` it charged for them. The anchor for [[contextSize]]. */
    private var counted: Pair<Int, Int>? = null

    /** How full the context is *now* -- which is not the same as what the last
      * request cost.
      *
      * Anchored on the endpoint's own `prompt_tokens` and corrected by the
      * characters added or removed since that request was built, so `/empty`,
      * `/compact`, `/prompt`, a memory edit and a `/rag` toggle all show up at
      * the next prompt instead of one turn late. Exact when the payload has not
      * changed at all; flagged approximate otherwise, which after a reply it
      * always is -- the reply itself is new context nobody has counted yet.
      *
      * None until the first turn, so a fresh session's footer stays bare rather
      * than leading with a guess.
      */
    val contextSize: ContextSize?
        get() {
            val c = counted
            return if (c != null) {
                val (base, tokens) = c
                val (chars, _) = measure(buildRequest(null, configRef.stream))
                if (chars == base) ContextSize(tokens, measured = true)
                else {
                    val delta = Math.round((chars - base) / estimator.charsPerToken).toInt()
                    ContextSize(maxOf(0, tokens + delta), measured = false)
                }
            } else {
                // No anchor but a ledger entry: the turn was recorded by something other
                // than `sendOnce`, so the measured figure is all there is.
                session.lastUsage()?.let { u -> ContextSize(u.promptTokens, measured = true) }
            }
        }

    /** What `--dry-run` prints, and it has to be the whole request: a user tool
      * still loading would otherwise be missing from the one view whose entire
      * purpose is showing exactly what would be sent. */
    fun dryRun(text: String): String {
        if (configRef.userToolsEnabled) userTools.awaitReady()
        return PrettyJson.encodeToString(
            JsonElement.serializer(),
            elideImageData(buildRequest(text, configRef.stream).encode())
        )
    }

    /** Sends `text` and renders the reply, running any tools the model calls and
      * continuing until it answers in prose.
      *
      * @param abort polled throughout; true stops streaming and halts the tool
      *              loop between iterations.
      */
    fun ask(
        text: String,
        abort: () -> Boolean = { false },
        images: List<Part.Image> = emptyList()
    ): Outcome<ChatResponse> {
        // The one place the compile has to be finished: the schemas go *in* this
        // request, and a tool missing from it is one the model never learns exists.
        if (configRef.userToolsEnabled && userTools.compiling) {
            say { System.err.println(Ansi.Color.DarkGray.on("kotllm: finishing tool compilation...")) }
            userTools.awaitReady()
        }

        val mark = session.messages().size
        session.append(Message.user(text, attachable(images)))

        val result = converse(abort, 0)
        if (result is Outcome.Err) {
            // Do not leave a dangling user turn: it would be silently re-sent.
            session.truncateTo(mark)
        }
        return result
    }

    /** One request, plus the tool round trips it provokes. */
    private fun converse(abort: () -> Boolean, iteration: Int): Outcome<ChatResponse> {
        val resp = sendOnce(abort).fold(onErr = { e -> return err(e) }, onOk = { it })
        session.appendResponse(resp)
        // After the response, not before the request: a turn that never got an
        // answer has nothing worth storing, and usage is only known now.
        // Re-assembled rather than captured before the send, and identical to
        // it: nothing between `sendOnce` and here mutates a section, because
        // this iteration's tools have not run yet. Only while the view is on --
        // assembling costs a memory read and a retrieval on every turn.
        sessions.record(
            session,
            targetRef.model.name,
            resp.usage?.let { u -> Pair(u.promptTokens, u.completionTokens) },
            if (!promptFull) null
            else assembledPrompt?.let { full ->
                Pair(session.systemPrompt?.takeIf { s -> s.trim().isNotEmpty() }, full)
            }
        )

        return if (resp.toolCalls.isEmpty()) {
            ok(resp)
        } else if (abort()) {
            declineToolCalls(resp.toolCalls, "not run: the turn was interrupted")
            ok(resp)
        } else if (iteration + 1 >= configRef.maxToolIterations) {
            declineToolCalls(
                resp.toolCalls,
                "not run: kotllm stopped after ${configRef.maxToolIterations} tool round trips"
            )
            say {
                System.err.println(
                    Ansi.Color.Yellow.on(
                        "kotllm: stopping after ${configRef.maxToolIterations} tool round trips " +
                            "(max_tool_iterations). The model may not have finished -- say 'continue' to " +
                            "resume, or raise the cap with '/set max_tool_iterations <n>'."
                    )
                )
            }
            ok(resp)
        } else {
            // The request just recorded is the one that asked for these calls,
            // which is what attributes the follow-up prompt growth correctly.
            runToolCalls(resp.toolCalls, session.ledger.turnCount(), abort)
            converse(abort, iteration + 1)
        }
    }

    /** Answers tool calls that will not be executed.
      *
      * The endpoint requires exactly one tool message per `tool_call_id`, so
      * abandoning a round trip unanswered makes the *next* request fail. An
      * explanation beats a blank result: the model reads why it stopped. */
    private fun declineToolCalls(calls: List<ToolCall>, reason: String) {
        calls.forEach { call -> session.append(Message.tool(call.id, reason)) }
    }

    /** Every call gets a result message even when refused or failed -- the endpoint
      * requires one per `tool_call_id`, and omitting any makes the next request
      * malformed. */
    private fun runToolCalls(calls: List<ToolCall>, requestIndex: Int, abort: () -> Boolean) {
        calls.forEach { call ->
            // Per call, not only per round trip. A response carrying five calls used
            // to run all five after Ctrl-C, so pressing it appeared to do nothing.
            if (abort()) {
                session.append(Message.tool(call.id, "not run: the turn was interrupted"))
            } else {
                val tool = registry.get(call.name)
                val preview = previewOf(tool, call)

                say { ToolRender.announce(call.name, preview, configRef.toolPreviewLines) }

                // Excluded from execution time: a slow human is not a slow tool, and
                // conflating them would hide an actually slow command.
                val askedAt = System.currentTimeMillis()
                val decision = approval.decide(call.name, preview, tool?.readOnly ?: false)
                val approvedAt = System.currentTimeMillis()

                val (result, startedAt, endedAt) = when (decision) {
                    is Decision.Deny -> Triple(ToolResult(decision.reason, isError = true), approvedAt, approvedAt)
                    is Decision.Allow -> {
                        val begin = System.currentTimeMillis()
                        val r = registry.dispatch(call)
                        Triple(r, begin, System.currentTimeMillis())
                    }
                }

                say { ToolRender.result(result.content, result.isError, endedAt - startedAt, configRef.toolResultLines) }
                session.append(Message.tool(call.id, result.content))

                session.ledger.recordToolRun(
                    ToolCallMetrics(
                        index = session.ledger.nextToolIndex(),
                        requestIndex = requestIndex,
                        name = call.name,
                        startedAt = startedAt,
                        endedAt = endedAt,
                        approvalWaitMs = maxOf(0L, approvedAt - askedAt),
                        argumentChars = call.arguments.length,
                        resultChars = result.content.length,
                        // Estimates: the endpoint reports no per-call token counts. Same
                        // calibrated ratio as the limiter, with no per-message overhead.
                        estimatedArgumentTokens = estimator.estimate(call.arguments.length, 0),
                        estimatedResultTokens = estimator.estimate(result.content.length, 0),
                        approved = decision == Decision.Allow,
                        isError = result.isError
                    )
                )
            }
        }
    }

    private fun previewOf(tool: Tool?, call: ToolCall): String =
        tool?.let { t -> registry.parseArgs(call.arguments).orNull()?.let { args -> t.preview(args) } }
            ?.takeIf { it.isNotEmpty() }
            ?: call.arguments

    /** A single request/response exchange, gated by the rate limiter. */
    private fun sendOnce(abort: () -> Boolean): Outcome<ChatResponse> {
        // Sized from the serialised request, not the message text. `prompt_tokens`
        // counts the tool schemas too -- ~700 tokens once three are declared -- and
        // calibrating on message text alone made the estimator conclude 1.37
        // chars/token, which throttled far too early.
        //
        // Compaction runs first, against the *projected* size of the request, so the
        // check is never a turn late. The payload is then measured once, after any
        // rewrite, for both the limiter and the calibration below.
        autoCompactIfNeeded()
        val (chars, imageCount) = measure(buildRequest(null, configRef.stream))
        val estimated = estimator.estimate(chars, 0) + imageCount * ImageTokenAllowance

        val admitted = admit(estimated, abort)
        if (admitted is Outcome.Err) return admitted

        val l = limiter
        val reservation = l?.reserve(estimated)
        val startedAt = System.currentTimeMillis()
        return try {
            val (resp, ttft) =
                if (configRef.stream) askStreaming(abort)
                else (askBlocking() to null as Long?)

            // Correct the window with what the endpoint charged, and teach the
            // estimator from the same numbers -- unless an image was sent, where
            // characters and tokens measure different things.
            if (imageCount == 0) resp.usage?.let { u -> estimator.calibrate(chars, 0, u.promptTokens) }
            // Anchored whether or not an image was sent: the base64 is excluded
            // from both sides of the later comparison, so it cancels.
            resp.usage?.let { u -> counted = Pair(chars, u.promptTokens) }
            if (l != null && reservation != null) {
                l.settle(reservation, resp.usage?.promptTokens ?: estimated, resp.usage?.completionTokens ?: 0)
            }

            recordMetrics(resp, startedAt, ttft, aborted = abort())
            warnIfDegenerate(resp)
            ok(resp)
        } catch (e: ChatFailure) {
            // Rejected by the provider still counted against its limit, so keep
            // the reservation rather than releasing it.
            if (l != null && reservation != null) l.settle(reservation, estimated, 0)
            session.ledger.recordError()
            err(e.message ?: "")
        } catch (e: IOException) {
            // Never reached the provider: give the budget back.
            if (l != null && reservation != null) l.release(reservation)
            session.ledger.recordError()
            err("cannot reach ${targetRef.client.apiBase}: ${e.message ?: e::class.simpleName ?: "IOException"}")
        }
    }

    /** Tokens the next request would cost, from the serialised payload -- schemas
      * included, for the reason in `sendOnce`. */
    private fun projectedTokens(): Int = estimateFor(buildRequest(null, configRef.stream))

    /** Message count at the last auto-compaction, so a failed or insufficient
      * one is not retried on every turn. */
    private var lastCompactedAt = -1

    private var futilityWarned = false

    /** Tokens a request costs with no history at all: system prompt, tool schemas,
      * and the current user turn. Compaction cannot touch any of it, so when this
      * alone is over the threshold retrying every turn is pure waste.
      *
      * Exposed because it is also the answer to the question `/status` gets asked
      * most: the context figure counts this once, not once per turn, and seeing
      * the two numbers side by side is what makes that obvious.
      */
    fun fixedOverheadTokens(): Int {
        val bare = ChatRequest(
            model = targetRef.model.name,
            messages = systemPrompt(retrievalQuery(null))?.let { listOf(Message.system(it)) } ?: emptyList(),
            temperature = configRef.temperature,
            topP = configRef.topP,
            maxTokens = configRef.maxTokens,
            stream = configRef.stream,
            tools = activeTools.map { it.definition }
        )
        return estimateFor(bare)
    }

    /** Compacts the history if the projected next request is over the threshold. */
    private fun autoCompactIfNeeded() {
        if (!compactor.shouldCompact(projectedTokens(), targetRef.model.maxInputTokens)) return

        // Nothing added since the last attempt, so the outcome would be identical.
        // Without this it compacts every turn once the threshold is crossed.
        if (session.messages().size <= lastCompactedAt) return

        if (!compactor.shouldCompact(fixedOverheadTokens(), targetRef.model.maxInputTokens)) {
            lastCompactedAt = session.messages().size
            compactNow().fold(
                onErr = { reason ->
                    say { System.err.println(Ansi.Color.DarkGray.on("${Glyphs.ellipsis} not compacted: $reason")) }
                },
                onOk = { outcome ->
                    lastCompactedAt = session.messages().size
                    say { System.err.println(Ansi.Color.DarkGray.on("${Glyphs.ellipsis} compacted context: ${describe(outcome)}")) }
                }
            )
        } else if (!futilityWarned) {
            // The fixed part already exceeds the budget, so no history rewriting
            // helps. Said once, with the numbers, rather than thrashing silently.
            futilityWarned = true
            say {
                System.err.println(
                    Ansi.Color.Yellow.on(
                        "kotllm: the system prompt and tool schemas alone are ~${fixedOverheadTokens()} " +
                            "tokens, over the compaction threshold for a " +
                            "${targetRef.model.maxInputTokens?.toString() ?: "?"}-token window. " +
                            "Compaction cannot help; reduce tools, memories, or use a larger model."
                    )
                )
            }
        }
    }

    /** Runs a compaction now, whatever the threshold says. Used by `/compact`. */
    fun compactNow(): Outcome<CompactionOutcome> =
        compactor.compact(session.messages(), ::summarizeForCompaction).map { (rewritten, outcome) ->
            session.replaceHistory(rewritten)
            // The stored file already holds the original exchanges. Without this the
            // next record() would re-append the summary as though it were new.
            sessions.resyncAfterCompaction(session)
            lastCompactedAt = rewritten.size
            outcome
        }

    /** Issues the summarising request.
      *
      * Goes through the limiter and into the ledger like any other request,
      * because it *is* one -- leaving it out would corrupt the estimator, which
      * calibrates on observed usage. Never streamed and never tool-enabled:
      * offering tools would invite exploring instead of summarising. */
    private fun summarizeForCompaction(messages: List<Message>): Outcome<String> {
        val request = ChatRequest(
            model = targetRef.model.name,
            messages = messages,
            temperature = configRef.temperature,
            topP = configRef.topP,
            maxTokens = configRef.maxTokens,
            stream = false,
            tools = emptyList()
        )

        val (chars, imageCount) = measure(request)
        val estimated = estimator.estimate(chars, 0) + imageCount * ImageTokenAllowance

        val admitted = admit(estimated) { false }
        if (admitted is Outcome.Err) return admitted

        val l = limiter
        val reservation = l?.reserve(estimated)
        val startedAt = System.currentTimeMillis()
        return try {
            val response = clientRef.chat(request)
            if (imageCount == 0) response.usage?.let { u -> estimator.calibrate(chars, 0, u.promptTokens) }
            if (l != null && reservation != null) {
                l.settle(reservation, response.usage?.promptTokens ?: estimated, response.usage?.completionTokens ?: 0)
            }
            recordMetrics(response, startedAt, null, aborted = false)
            ok(response.text)
        } catch (e: ChatFailure) {
            if (l != null && reservation != null) l.settle(reservation, estimated, 0)
            session.ledger.recordError()
            err(e.message ?: "")
        } catch (e: IOException) {
            if (l != null && reservation != null) l.release(reservation)
            session.ledger.recordError()
            err(e.message ?: e::class.simpleName ?: "IOException")
        }
    }

    private fun describe(o: CompactionOutcome): String {
        val parts = listOfNotNull(
            if (o.summarizedExchanges > 0) "${o.summarizedExchanges} exchange(s) summarised into ${o.summaryChars} chars" else null,
            if (o.trimmedResults > 0) "${o.trimmedResults} old tool result(s) trimmed, ${o.trimmedChars} chars saved" else null
        )
        return "${parts.joinToString("; ")} (${o.messagesBefore} -> ${o.messagesAfter} messages)"
    }

    /** Re-checks after each nap rather than trusting the first computed delay: a
      * concurrent background turn may consume the capacity we waited for. */
    private fun admit(estimated: Int, abort: () -> Boolean): Outcome<Unit> {
        val l = limiter ?: return ok(Unit)

        var waitedTotal = 0L
        var settled = false
        var result: Outcome<Unit> = ok(Unit)

        while (!settled) {
            when (val v = l.verdict(estimated)) {
                is Verdict.Admit -> {
                    if (waitedTotal > 0) {
                        l.recordWait(waitedTotal)
                        Throttle.clear()
                    }
                    settled = true
                }

                is Verdict.Deny -> {
                    Throttle.clear()
                    result = err(v.reason)
                    settled = true
                }

                is Verdict.Oversized -> {
                    // Waiting cannot help, so send it and let the provider rule on it.
                    Throttle.clear()
                    System.err.println(Ansi.Color.Yellow.on("kotllm: ${v.reason}; sending anyway"))
                    settled = true
                }

                is Verdict.Wait -> {
                    if (abort()) {
                        Throttle.clear()
                        result = err("cancelled while waiting on the rate limit")
                        settled = true
                    } else {
                        val nap = minOf(v.millis, 250L)
                        say { Throttle.show(v.millis, v.reason) }
                        Thread.sleep(maxOf(nap, 1L))
                        waitedTotal += nap
                    }
                }
            }
        }
        return result
    }

    private fun recordMetrics(
        resp: ChatResponse,
        startedAt: Long,
        timeToFirstToken: Long?,
        aborted: Boolean
    ) {
        session.ledger.record(
            TurnMetrics(
                index = session.ledger.nextIndex(),
                startedAt = startedAt,
                model = targetRef.model.name,
                // Usage is authoritative when present; a stream cut short by Ctrl-C may
                // not carry it, and guessing would be worse than zero.
                promptTokens = resp.usage?.promptTokens ?: 0,
                completionTokens = resp.usage?.completionTokens ?: 0,
                reasoningChars = resp.reasoning.length,
                latencyMs = System.currentTimeMillis() - startedAt,
                timeToFirstTokenMs = timeToFirstToken,
                toolCalls = resp.toolCalls.map { it.name },
                truncated = resp.truncated,
                aborted = aborted,
                local = isLocal
            )
        )
    }

    /** Derived from the provider, never the model name: a local model can be
      * called anything, including a name that would match the price table and
      * invent a bill. */
    private val isLocal: Boolean
        get() = Provider.infer(targetRef.client.kind, targetRef.client.apiBase) == Provider.Ollama

    /** @return the response, and the time to the first content-bearing event. */
    private fun askStreaming(abort: () -> Boolean): Pair<ChatResponse, Long?> {
        val req = buildRequest(null, true)
        val render = StreamRender(configRef.reasoning, configRef.highlight, gutter, renderTo)
        val spinner = Spinner("${targetRef.model.name} is thinking")
        val sentAt = System.currentTimeMillis()
        spinner.start()

        var firstEvent = true
        var ttft: Long? = null

        return try {
            val resp = clientRef.chatStream(
                req,
                onEvent = { e ->
                    if (firstEvent) {
                        spinner.stop()
                        firstEvent = false
                    }
                    // From the first *content* event: this endpoint puts a cumulative
                    // `usage` on every chunk including the opening role-only one, so gating
                    // on the first event of any kind would always measure zero.
                    if (ttft == null) {
                        when (e) {
                            is StreamEvent.TextDelta, is StreamEvent.ReasoningDelta ->
                                ttft = System.currentTimeMillis() - sentAt
                            else -> {}
                        }
                    }
                    render.onEvent(e)
                }
            ) { !abort() }
            render.finish()
            Pair(resp, ttft)
        } finally {
            spinner.stop()
        }
    }

    private fun askBlocking(): ChatResponse {
        val req = buildRequest(null, false)
        val spinner = Spinner("${targetRef.model.name} is thinking")
        spinner.start()
        val resp = try {
            clientRef.chat(req)
        } finally {
            spinner.stop()
        }

        if (quiet) return resp

        when (configRef.reasoning) {
            is ReasoningDisplay.Show ->
                if (resp.reasoning.isNotEmpty()) println(Ansi.Color.DarkGray.on(resp.reasoning))
            is ReasoningDisplay.Collapse ->
                if (resp.reasoning.isNotEmpty())
                    System.err.println(Ansi.Color.DarkGray.on("${Glyphs.ellipsis} ${resp.reasoning.length} chars of reasoning hidden"))
            is ReasoningDisplay.Hide -> {}
        }

        if (resp.text.isNotEmpty()) renderTo.println(gutter.render(Markdown.render(resp.text, configRef.highlight)))
        return resp
    }

    /** Two failure modes specific to reasoning models are worth calling out
      * rather than leaving the user staring at a blank reply. */
    private fun warnIfDegenerate(resp: ChatResponse) {
        if (quiet) return
        // A tool call with no prose is normal, not a degenerate reply.
        if (resp.text.isEmpty() && resp.reasoning.isNotEmpty() && resp.toolCalls.isEmpty())
            System.err.println(
                Ansi.Color.Yellow.on(
                    "kotllm: the model spent its whole output budget reasoning and returned no answer. " +
                        "Raise max_tokens, or re-ask more narrowly."
                )
            )
        else if (resp.truncated)
            System.err.println(Ansi.Color.Yellow.on("kotllm: reply was cut off at the token limit (finish_reason=length)."))
    }

    /** An **empty** session rather than a copy of this one: an agent's context is
      * its own, and inheriting the REPL conversation would make a run depend on
      * whatever was said before it. Limiters are shared, as for background turns. */
    fun forAgent(quiet: Boolean, approval: ApprovalMode): Runner {
        val forked = Runner(
            // save = false for the same reason as the background fork: an agent run
            // has its own directory under runs/ and is not the user's conversation.
            configRef.copy(toolApproval = approval, save = false),
            Session(),
            targetRef,
            clientRef,
            Prompter.unavailable,
            false,
            quiet,
            limiters
        )
        forked.renderTo = System.err
        return forked
    }

    /** Forks a runner for a background turn: a copy of the history for context,
      * but its own timeline, so replies never mutate the foreground conversation.
      *
      *   - Streaming off, output suppressed; either would corrupt the line editor.
      *   - The limiter is shared, so two turns cannot together exceed the window.
      *   - The interpreter is **not**: a background `groovy_eval` defining a val in
      *     the same session would silently alter what the REPL sees.
      *   - Nobody can approve, so under the default `confirm` a background tool
      *     call is refused, with that reason returned to the model. */
    fun forkBackground(): Runner {
        val forked = Session(session.systemPrompt)
        session.messages().forEach { forked.append(it) }

        return Runner(
            // A background reply is not the conversation the user is having, so it
            // must not open a second session file beside the real one.
            configRef.copy(stream = false, save = false),
            forked,
            targetRef,
            clientRef,
            Prompter.unavailable,
            false,
            true,
            limiters
        )
    }

    companion object {

        /** Half an hour. Agent runs are minutes, not sessions -- resume was dropped
          * on exactly that reasoning -- so this is slack, not a budget. */
        internal val AgentCeilingMillis: Long = 30L * 60L * 1000L

        @OptIn(kotlinx.serialization.ExperimentalSerializationApi::class)
        private val PrettyJson = Json { prettyPrint = true; prettyPrintIndent = "  " }

        /** `--dry-run` exists to be read. A megabyte of base64 on one line can wedge
          * a terminal, and all a reader needs is that an image is there and how big. */
        internal fun elideImageData(request: JsonElement): JsonElement = when (request) {
            is JsonObject -> {
                val url = (request["url"] as? JsonPrimitive)?.takeIf { it.isString }?.content
                val rebuilt = request.mapValues { (k, v) ->
                    if (k == "url" && url != null && url.startsWith("data:")) {
                        val head = url.take(maxOf(url.indexOf(',') + 1, 24))
                        JsonPrimitive("$head...[${url.length} chars elided]")
                    } else {
                        elideImageData(v)
                    }
                }
                JsonObject(rebuilt)
            }
            is JsonArray -> JsonArray(request.map { elideImageData(it) })
            else -> request
        }

        fun build(
            config: Config,
            modelId: String?,
            systemPrompt: String?,
            prompter: Prompter = Prompter.unavailable,
            interactive: Boolean = System.console() != null
        ): Outcome<Runner> {
            val target = config.resolveModel(modelId).fold(onErr = { e -> return err(e) }, onOk = { it })
            val client = Client.forTarget(target, config).fold(onErr = { e -> return err(e) }, onOk = { it })
            return ok(Runner(config, Session(systemPrompt ?: config.systemPrompt), target, client, prompter, interactive))
        }
    }
}

/** What the next request will carry, and whether the endpoint has counted it.
  *
  * `measured` is the difference between "the endpoint charged this" and "this
  * is what it would charge if you sent now". Conflating the two is how a
  * cleared context went on reporting the tokens it no longer held. Only
  * `/status` draws the distinction: the footer has one slot and after any
  * reply the answer is always "projected", so a marker there would say
  * nothing.
  */
data class ContextSize(val tokens: Int, val measured: Boolean) {
    fun percentOf(maxInputTokens: Int): Int =
        minOf(100, Math.round(tokens.toDouble() / maxInputTokens * 100).toInt())
}
