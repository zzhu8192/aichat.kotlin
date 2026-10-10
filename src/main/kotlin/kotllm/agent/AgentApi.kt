package kotllm.agent

import kotllm.template.Sink
import kotllm.util.Glyphs
import kotllm.util.TokenLedger
import kotllm.render.Ansi
import kotllm.render.on
import kotllm.*

/** The five calls an agent file can make, bound into the interpreter as
  * `agent`.
  *
  * A named public class because `IMain.bindValue` binds by runtime type, so the
  * generated program can only typecheck against a stable public name -- the
  * same reason `template.Sink` is a class.
  *
  * `apply` is the configuration call, so `agent(maxCalls = 30)` is just named
  * arguments. The shorthands (`llm`, `system`, `finish`, `fail`, `log`, `args`,
  * `last`) are interpreted into the session as one-line `def`s before the file
  * runs, so nothing is prepended to the generated program and the template line
  * map stays intact.
  *
  * **Every member here is a plain JVM method, deliberately, and several of
  * them for a reason beyond the usual Kotlin-property trap.** `agents/<name>.md`
  * files are Scala source, compiled fresh by IMain against this class, and two
  * mechanisms Scala relies on for a Scala-defined callee have no Kotlin
  * equivalent at all:
  *
  *   - Scala's `x(args)` application sugar looks for a method literally named
  *     `apply` -- not `invoke`, which is what Kotlin's own `operator fun
  *     invoke` compiles to. So [[apply]] is a plain `fun`, never `operator`.
  *   - Scala's default/named arguments and context-bound implicits are
  *     resolved by the *caller's* compiler from the callee's Scala-specific
  *     metadata, which a Kotlin classfile does not carry. A literal
  *     `agent(maxCalls = 30)` or `llmAs[Foo](...)` written directly against
  *     this class would therefore fail to compile.
  *
  * `Agent.Shorthands` is what closes both gaps: it is genuine Scala,
  * interpreted once per run, so its own `def agent(model: String = null, ...)`
  * and `def llmAs[T: upickle.default.Reader: scala.reflect.ClassTag](...)`
  * get real Scala defaults and real implicit resolution, and then forward
  * every argument to this class *explicitly and positionally* -- a `Class[_]`
  * in place of a `ClassTag`, a reified `Reader[T]` in place of the implicit,
  * all seven `agent(...)` parameters always present. Nothing on this class
  * needs Scala to see a default value or an implicit parameter, so the split
  * between "what a `.md` file writes" and "what this class actually exposes"
  * is deliberate, not a simplification.
  */
final class AgentApi(
    private val run: AgentRun,
    /** The run's own conversation. Every `llm` call joins it. */
    private val main: Runner,
    /** Reused for `fresh = true` calls, cleared before each one: one throwaway
      * conversation rather than one throwaway *runner* per call, which would
      * start a compiler per call the first time any of them used `groovy_eval`. */
    private val scratch: Runner,
    /** When set, no model call is made at all: `--dry-run` renders the whole
      * workflow for zero tokens, which is the cheapest way to see its shape. */
    private val dryRun: Boolean
) {

    // Typed agent output -- `llmAs[T]` and `enumOf` -- lived here and is gone.
    // It was built on upickle's `Reader`, `macroRW` and `scala.Product`
    // reflection, none of which survives the move off IMain, and it was never
    // unit-testable outside a real interpreter run (see the disabled specs it
    // took with it). It should come back on kotlinx.serialization, where a
    // script can declare `@Serializable data class R(...)` and the harness can
    // both decode it and derive the JSON shape the prompt needs.

    private var sink: Sink? = null

    /** Handed the sink once the template engine has bound it. */
    internal fun attach(s: Sink) {
        sink = s
    }

    private fun emitted(): String = sink?.result ?: ""

    // ---- agent(...) --------------------------------------------------------

    /** Configuration. Every argument optional; `agent()` alone is legal and
      * means "an agent with defaults".
      *
      * No Kotlin default values: `Agent.Shorthands`'s own `def agent(...)`
      * carries the real defaults and always calls this with all seven
      * arguments, so there is nothing for Scala to resolve against this class.
      */
    fun apply(
        model: String?,
        tools: String?,
        maxCalls: Int,
        maxTokens: Int,
        maxSeconds: Int,
        quiet: Boolean,
        approval: String?
    ) {
        touch()
        if (run.callsMade > 0)
            note(
                "agent(...) was called after the first llm() call, so model, tools and budgets for the " +
                    "calls already made were the defaults. Move it to the top of the file."
            )

        run.settings = run.settings.copy(
            model = model?.trim()?.takeIf { it.isNotEmpty() },
            tools = tools?.trim()?.takeIf { it.isNotEmpty() },
            maxCalls = if (maxCalls > 0) maxCalls else run.settings.maxCalls,
            maxTokens = if (maxTokens > 0) maxTokens else run.settings.maxTokens,
            maxSeconds = if (maxSeconds > 0) maxSeconds else run.settings.maxSeconds,
            quiet = quiet,
            approval = approval?.let { kotllm.tools.ApprovalMode.parse(it) } ?: run.settings.approval
        )

        run.settings.model?.let { m ->
            main.switchModel(m).fold(onErr = { e -> note("agent(model = \"$m\"): $e") }, onOk = {})
            scratch.switchModel(m)
        }
        run.settings.tools?.let { t ->
            main.updateConfig { c -> c.copy(useTools = t, functionCalling = true) }
            scratch.updateConfig { c -> c.copy(useTools = t, functionCalling = true) }
        }
        // Auto by default, and announced: an agent is unattended, so under confirm
        // its first exec would refuse -- but an unprompted shell command must never
        // also be an unrecorded one.
        main.updateConfig { c -> c.copy(toolApproval = run.settings.approval) }
        scratch.updateConfig { c -> c.copy(toolApproval = run.settings.approval) }
    }

    // ---- system { } --------------------------------------------------------

    /** Captures the prose inside the block as the system prompt.
      *
      * Append-then-cut: the block's text lands in the output like any other
      * literal and is cut back out, so `Template.emit` needs no notion of a
      * capturing block and the line map is untouched.
      *
      * Takes a `Runnable` rather than `() -> Unit` so a Groovy closure coerces
      * to it: the agent file is Groovy, and `Function0<Unit>` would require the
      * block to return `Unit` rather than whatever its last statement produced.
      */
    fun system(body: Runnable) {
        touch()
        val before = sink?.length ?: 0
        body.run()
        val text = (sink?.cut(before) ?: "").trim()
        if (text.isNotEmpty()) {
            main.session.systemPrompt = text
            scratch.session.systemPrompt = text
            run.log("\n## system prompt\n\n${text.length} chars")
        }
    }

    /** The string form, for a prompt assembled in code rather than written as
      * prose. */
    fun system(text: String) {
        touch()
        main.session.systemPrompt = text
        scratch.session.systemPrompt = text
    }

    // ---- llm(...) ----------------------------------------------------------

    /** One turn, and the reply as a `String`.
      *
      * **One signature.** An `llm(alias, prompt)` overload was tried and pulled:
      * two spellings of one call, with two leading `String`s between them, is a
      * transposition waiting to happen and the compiler could not catch it.
      * Prompt stays first, so the common call is still `llm("...")`, and the
      * model is named rather than positional -- `llm("...", alias = "gemma")`
      * reads as what it is.
      *
      * @param alias which model, by `aliases:` name or by model id. Absent or
      *              blank means the run's default.
      * @param fresh runs it on a scratch conversation thrown away afterwards, so
      *              bulky input never enters the run's own context. Twenty
      *              3,000-token files cost ~630k input tokens shared (call n
      *              re-sends 1..n) against ~61k with `fresh`.
      */
    fun llm(
        prompt: String,
        fresh: Boolean,
        alias: String?,
        tools: String?
    ): String {
        val model = alias?.trim()?.takeIf { it.isNotEmpty() }
        touch()
        if (prompt.isBlank()) return ""

        if (halted != null) return ""

        run.exceeded(tokensSoFar())?.let { which ->
            stop(AgentStop.Budget(which))
            return ""
        }
        if (run.noProgress(prompt)) {
            stop(AgentStop.Budget("no progress: the same prompt three times running"))
            return ""
        }

        run.callsMade += 1
        val index = run.callsMade
        val runner = if (fresh) prepareScratch() else main
        val started = System.currentTimeMillis()

        if (dryRun) {
            val marker = "[dry run: llm call $index${if (fresh) ", fresh" else ""}]"
            lastReply = marker
            progress(index, fresh, prompt, started, 0)
            return marker
        }

        val overrides = Pair(tools?.trim()?.takeIf { it.isNotEmpty() }, model?.takeIf { it.isNotEmpty() })
        val restore = applyOverrides(runner, overrides)
        val before = runner.session.ledger.totalCompletionTokens()

        val result =
            try {
                runner.ask(prompt)
            } catch (e: Exception) {
                err(e.message ?: e::class.simpleName ?: "")
            }
        restore()

        return result.fold(
            onErr = { e ->
                run.log("\n### call $index failed\n\n$e")
                say(Ansi.Color.Red.on("  ! llm call $index failed: $e"))
                // Not fatal by itself: a file can inspect the empty reply and decide.
                // A run that cannot proceed should say so with fail(...).
                ""
            },
            onOk = { response ->
                val out = runner.session.ledger.totalCompletionTokens() - before
                // Trimmed, because a reply is nearly always interpolated into a
                // sentence -- `2 doubled is <%= llm(...) %>` should not acquire the
                // blank lines a model likes to wrap its answers in.
                val text = response.text.trim()
                lastReply = text
                progress(index, fresh, prompt, started, out)
                logTools(runner)
                text
            }
        )
    }


    // ---- terminal state ----------------------------------------------------

    /** Stop now, successfully, with a verdict. */
    fun finish(verdict: String) {
        touch()
        stop(AgentStop.Finished(verdict))
    }

    /** Stop now, unsuccessfully. Exits non-zero. */
    fun fail(reason: String) {
        touch()
        stop(AgentStop.Failed(reason))
    }

    // ---- odds and ends ----------------------------------------------------

    /** The previous reply, so the common case needs no variable.
      *
      * A method, not a property: `agents/<name>.md` and `Agent.Shorthands` call this
      * as `agent.last`, which needs a JVM method literally named `last`, not
      * the `getLast()` a Kotlin `val` would compile to.
      */
    fun last(): String = lastReply

    fun args(): Map<String, String> = run.args

    fun log(message: String) {
        run.log("\n- $message")
        say(Ansi.Color.DarkGray.on("  ${Glyphs.bullet} $message"))
    }

    /** This run's directory, for temp files: `os.write(agent.dir / "test.log", x)`. */
    fun dir(): java.nio.file.Path = run.dir

    // ---- internals ---------------------------------------------------------

    private var lastReply: String = ""
    private var halted: AgentStop? = null

    private fun touch() {
        run.touched = true
    }

    private fun stop(s: AgentStop) {
        halted = s
        run.record(s, emitted())
        run.flushLog()
        throw AgentHalt()
    }

    private fun tokensSoFar(): Int =
        main.session.ledger.totalTokens() + scratch.session.ledger.totalTokens()

    /** A cleared conversation, which is what "fresh" means. The runner, its
      * client and its interpreter are reused. */
    private fun prepareScratch(): Runner {
        scratch.session.clear()
        return scratch
    }

    private fun applyOverrides(
        runner: Runner,
        overrides: Pair<String?, String?>
    ): () -> Unit {
        val (tools, model) = overrides
        if (tools == null && model == null) return {}

        val priorTools = runner.config.useTools
        val priorModel = runner.target.id()
        tools?.let { t -> runner.updateConfig { c -> c.copy(useTools = t, functionCalling = true) } }
        // Naming the fallback, not just the failure. A mistyped alias used to
        // print a warning and then answer from the default model, which on a
        // config whose aliases point at models an order of magnitude apart in
        // price, that is a silent 10x.
        model?.let { m ->
            runner.switchModel(m).fold(
                onErr = { e ->
                    val known =
                        if (runner.config.aliases.isEmpty()) ""
                        else " Known aliases: ${runner.config.aliases.keys.sorted().joinToString(", ")}."
                    note("""llm(alias = "$m"): $e -- answering on ${runner.target.id()} instead.$known""")
                },
                onOk = {}
            )
        }

        return {
            tools?.let { runner.updateConfig { c -> c.copy(useTools = priorTools) } }
            model?.let { runner.switchModel(priorModel) }
        }
    }

    private fun progress(
        index: Int,
        fresh: Boolean,
        prompt: String,
        startedAt: Long,
        outTokens: Int
    ) {
        val took = TokenLedger.seconds(System.currentTimeMillis() - startedAt)
        val scope = if (fresh) "fresh" else "shared"
        val head = prompt.lineSequence().find { it.isNotBlank() }?.trim()?.take(60) ?: ""

        run.log("\n### call $index ($scope, $took, ${Glyphs.tokensOut} $outTokens)\n\n> $head")
        say(
            Ansi.Color.DarkGray.on(
                "  ${Glyphs.arrow} llm $index ($scope) $took ${Glyphs.tokensOut} $outTokens ${Glyphs.bullet} $head"
            )
        )
    }

    /** Logged even though nothing prompted for them: unprompted must not also
      * mean unrecorded. Only what is new since the last look, tracked per runner,
      * or the log fills with repeats of the tail. */
    private fun logTools(runner: Runner) {
        val seen = logged.getOrDefault(runner.hashCode(), 0)
        val calls = runner.session.ledger.toolCalls()
        calls.drop(seen).forEach { t ->
            run.log(
                "  - tool `${t.name}` ${TokenLedger.seconds(t.durationMs())}" +
                    (if (t.isError) " (error)" else "") + (if (!t.approved) " (refused)" else "")
            )
        }
        logged[runner.hashCode()] = calls.size
    }

    private val logged: MutableMap<Int, Int> = mutableMapOf()

    private fun note(message: String) {
        run.log("\n- note: $message")
        System.err.println(Ansi.Color.Yellow.on("kotllm: $message"))
    }

    /** The progress line is printed either way -- it is one line, and in quiet
      * mode it is the only thing the run shows. What `quiet` suppresses is the
      * streamed reply itself, which the runner handles. */
    private fun say(line: String) {
        println(line)
    }

}
