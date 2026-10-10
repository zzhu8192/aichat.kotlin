package kotllm.agent

import java.nio.charset.StandardCharsets
import java.nio.file.Files
import kotllm.render.Ansi
import kotllm.render.on
import kotllm.util.Glyphs
import kotllm.util.TokenLedger
import kotllm.*

/** How a run ended, for the caller to report and exit on. */
sealed interface AgentResult {

    /** The file called at least one agent primitive: its output is an artifact,
      * already written to the run directory. */
    data class Ran(val verdict: String?, val output: String, val dir: java.nio.file.Path, val failed: Boolean) : AgentResult

    /** The file called none of them, so it is a prompt -- a role. The caller
      * sends it, or opens a REPL with it as the system prompt. */
    data class Prompt(val text: String) : AgentResult
}

/** Loads and runs an agent file.
  *
  * There is no format to parse. The file is an ordinary template: prose is
  * output, `<% %>` is the program, and the five calls come from an [[AgentApi]]
  * bound into the interpreter session. So this object is orchestration only --
  * find the file, make the run, bind the API, render, decide what happened.
  */
object Agent {

    /** Agent files are `<config>/agents/<name>.md`. The filename is the name;
      * there is no registry and no metadata, which is the price of having no
      * frontmatter and is paid deliberately. */
    fun file(name: String): java.nio.file.Path =
        kotllm.config.Paths.agentsDir().resolve(if (name.endsWith(".md")) name else "$name.md")

    fun list(): List<String> =
        try {
            val dir = kotllm.config.Paths.agentsDir()
            if (!Files.isDirectory(dir)) emptyList()
            else Files.list(dir).use { stream ->
                stream.toList()
                    .map { p -> p.fileName.toString() }
                    .filter { n -> n.endsWith(".md") }
                    .map { n -> n.removeSuffix(".md") }
                    .sorted()
            }
        } catch (e: Exception) {
            emptyList()
        }

    /** Reads a file by name, or by path when it looks like one. */
    fun load(name: String): Outcome<Pair<String, String>> {
        val direct = java.nio.file.Paths.get(name)
        val path = if (Files.isRegularFile(direct)) direct else file(name)
        val label = path.fileName.toString().removeSuffix(".md")

        return if (!Files.isRegularFile(path)) {
            val hint = list().let { others ->
                if (others.isEmpty()) "; no agents exist yet -- an agent is one .md file there"
                else ". Available: ${others.joinToString(", ")}"
            }
            err("no agent '$name'. Looked in ${kotllm.config.Paths.agentsDir()}$hint")
        } else {
            try {
                ok(Pair(label, String(Files.readAllBytes(path), StandardCharsets.UTF_8)))
            } catch (e: Exception) {
                err("cannot read $path: ${e.message ?: ""}")
            }
        }
    }

    /** Runs `source` as an agent against `runner`.
      *
      * @param dryRun renders the workflow with `llm` stubbed, so the shape of a
      *               file -- how many calls, which are fresh, what it emits --
      *               can be seen for zero tokens.
      */
    fun run(
        runner: Runner,
        name: String,
        source: String,
        args: Map<String, String>,
        dryRun: Boolean = false,
        base: java.nio.file.Path = kotllm.config.Paths.runsDir()
    ): Outcome<AgentResult> {
        val run = AgentRun(name, source, args, base)

        // Auto-approval is decided before the file runs, because the file's own
        // agent(...) call may narrow it but the default has to be in force for the
        // first tool call either way.
        val main = runner.forAgent(quiet = false, approval = run.settings.approval)
        val scratch = runner.forAgent(quiet = true, approval = run.settings.approval)
        val api = AgentApi(run, main, scratch, dryRun)

        // Bound under an internal name, not "agent": `Shorthands` below defines
        // the real `agent(...)` with its defaults and forwards every argument
        // to this binding explicitly.
        if (!runner.templateEngine.bind("__agentApi", api))
            return err("could not bind the agent API into the interpreter")

        // `agent.*` is control, `prompt.*` is content. Bound here as well as for
        // the chat prompt so an agent's `system { }` can pull in the tool manifest
        // or the memories with `<%= prompt.tools() %>` rather than restating them
        // and drifting from what the harness actually sends.
        if (!runner.templateEngine.bind("prompt", kotllm.prompt.PromptApi(main, null)))
            return err("could not bind the prompt API into the interpreter")

        // Shorthands live in the session rather than being prepended to the
        // generated program: prepending would shift every line and break the map
        // that reports a compile failure against the template line.
        val prelude = runner.templateEngine.eval(Shorthands)
        if (!prelude.ok) return err("could not prepare the agent shorthands:\n${prelude.toToolOutput()}")

        val rendered = runner.renderAgent(source, api::attach)

        val stop = run.stop
        // finish() and fail() unwind through an exception that the interpreter
        // turns into a diagnostic, so a recorded stop is what says the run ended
        // deliberately -- the engine's "error" is expected here.
        return if (stop != null) {
            ok(report(run, stop, run.output ?: ""))
        } else {
            rendered.fold(
                onErr = { e -> err(e) },
                onOk = { output ->
                    if (run.touched) ok(report(run, AgentStop.RanOut, output))
                    // Called none of the five: the file is a prompt, not a program.
                    else ok(AgentResult.Prompt(output))
                }
            )
        }
    }

    private fun report(run: AgentRun, stop: AgentStop, output: String): AgentResult.Ran {
        val (verdict, failed) = when (stop) {
            is AgentStop.Finished -> Pair(stop.verdict, false)
            is AgentStop.Failed -> Pair("failed: ${stop.reason}", true)
            is AgentStop.Budget -> Pair("stopped: ${stop.which}", false)
            AgentStop.RanOut -> Pair(null, false)
        }

        run.log(
            "\n## outcome\n\n${verdict ?: "ran to the end"} " +
                "(${run.callsMade} llm call(s), ${TokenLedger.seconds(run.elapsedMillis)})"
        )
        run.write("report.md", output)
        run.flushLog()

        return AgentResult.Ran(verdict, output, run.dir, failed)
    }

    /** One-line declarations so a file writes `llm(...)` rather than
      * `agent.llm(...)`.
      *
      * Evaluated as a single submission before the file runs. Defaults are
      * repeated here rather than forwarded, because one function cannot inherit
      * another's default arguments.
      *
      * These used to carry the weight of making a Kotlin class reachable from
      * Scala -- named arguments, context bounds, `Class[_]` reified out of a
      * `ClassTag`. None of that is needed now the agent file is Groovy: these
      * are plain forwarders, and the only reason they still exist is the
      * shorter spelling.
      *
      * **Closures in the binding, not methods.** A method declared in one
      * Groovy script belongs to that script's class and is invisible to the
      * next one, but an undeclared assignment lands in the shared `Binding`,
      * which every later snippet sees.
      */
    private val Shorthands: String =
        listOf(
            "agent = { String model = null, String tools = null, int maxCalls = -1, int maxTokens = -1, " +
                "int maxSeconds = -1, boolean quiet = false, String approval = null -> " +
                "__agentApi.apply(model, tools, maxCalls, maxTokens, maxSeconds, quiet, approval) }",
            "llm = { String prompt, boolean fresh = false, String alias = null, String tools = null -> " +
                "__agentApi.llm(prompt, fresh, alias, tools) }",
            "system = { body -> body instanceof CharSequence " +
                "? __agentApi.system(body.toString()) : __agentApi.system(body as Runnable) }",
            "systemText = { String text -> __agentApi.system(text) }",
            "finish = { String verdict -> __agentApi.finish(verdict) }",
            "fail = { String reason -> __agentApi.fail(reason) }",
            "log = { String message -> __agentApi.log(message) }",
            "args = { -> __agentApi.args() }",
            "last = { -> __agentApi.last() }",
            "runDir = { -> __agentApi.dir() }"
        ).joinToString("\n")

    /** Announced, every time. Auto-approval is a real exception to a locked
      * decision, and a shell command nobody was asked about must at least be a
      * shell command somebody was told about. */
    fun banner(name: String): String =
        Ansi.Color.DarkGray.on(
            // No budget quoted here on purpose: the file sets its own with
            // agent(...), which has not run yet, and a banner that says 50 when
            // the file says 4 is worse than one that says nothing.
            "${Glyphs.bullet} agent $name: tool calls are auto-approved for this run and logged"
        )
}
