package kotllm

import java.nio.file.Path
import kotllm.config.Config
import kotllm.config.Paths
import kotllm.config.ReasoningDisplay
import kotllm.render.Ansi
import kotllm.render.on

/** Kotlin's entry point is a top-level function, unlike Scala's `object Main`
  * with a `def main`. The object stays for everything else -- and for
  * `main` itself, kept as an ordinary member below -- so every other file
  * that names `Main.xxx` still resolves, and this top-level function is the
  * only new thing the JVM actually looks for.
  */
fun main(args: Array<String>) {
    Main.main(args)
}

object Main {

    fun main(args: Array<String>) {
        // First, and it has to be first: JLine reads this once in a static
        // initialiser. See JLineTuning for what it turns off and why.
        kotllm.util.JLineTuning.init()

        val code =
            try {
                Cli.parse(args).fold(
                    onErr = { e -> usageError(e) },
                    onOk = { cli ->
                        when {
                            cli.help -> {
                                println(Cli.usage)
                                0
                            }
                            cli.version -> {
                                println("kotllm ${kotllm.util.BuildInfo.full()}")
                                0
                            }
                            else -> run(cli)
                        }
                    }
                )
            } catch (e: LinkageError) {
                staleJar(e)
            }
        if (code != 0) kotlin.system.exitProcess(code)
    }

    /** Explains the one runtime failure that looks like a bug in kotllm and is
      * not: the jar was replaced while this session was running.
      *
      * `java -jar` holds the jar open and loads classes lazily, so a class first
      * needed after a rebuild cannot be found -- and the session had been running
      * stale code since the file changed. It surfaces at whatever is touched
      * first, which makes it look random. See docs/building.md. */
    private fun staleJar(e: LinkageError): Int {
        // Not every LinkageError is a stale jar -- a failed static initializer
        // looks identical from here -- so the trace is one env var away rather
        // than swallowed for good.
        if (System.getenv("KOTLLM_DEBUG")?.trim()?.isNotEmpty() == true) e.printStackTrace()
        val missing = e.message?.replace('/', '.') ?: "a class"
        System.err.println(
            Ansi.Color.Red.on(
                "kotllm: $missing could not be loaded, which almost always means the jar was " +
                    "rebuilt while this session was running. Restart kotllm; it has been running " +
                    "the old code since the rebuild. (runit.bat launches a private copy of the jar " +
                    "so a build cannot do this.)"
            )
        )
        return 1
    }

    private fun run(cli: Cli): Int {
        val configPath = cli.configPath?.let { p -> java.nio.file.Paths.get(p) } ?: Paths.configFile()

        // `ensureDirs` existed and nothing called it, so the directories it
        // promises were only ever created by whoever wrote into them first. The
        // chat agent needs `agents/` to exist before it can seed itself, which is
        // what finally gave it a caller.
        Paths.ensureDirs()
        // stderr, not stdout: `--dry-run` and CMD mode write machine-readable
        // output there, and a friendly notice ahead of the JSON is a parse error
        // for whatever is reading it.
        kotllm.prompt.ChatAgent.ensureFile().fold(
            onErr = { e -> System.err.println("kotllm: $e") },
            onOk = { wrote -> if (wrote) System.err.println("kotllm: wrote the default chat agent to ${kotllm.prompt.ChatAgent.file}") }
        )

        return Config.load(configPath).fold(
            onErr = { e -> configError(e, configPath) },
            onOk = { base ->
                applyOverrides(base, cli).fold(
                    onErr = { e -> usageError(e) },
                    onOk = { config ->
                        buildRunner(config, cli).fold(
                            onErr = { e -> fail(e) },
                            onOk = { runner -> dispatch(runner, cli) }
                        )
                    }
                )
            }
        )
    }

    /** What to do once the runner exists: the one-shot reports, the retrieval
      * flags, then either a single hit or the REPL.
      *
      * Split out of `run` because the nesting was otherwise five levels deep for
      * no gain.
      */
    private fun dispatch(runner: Runner, cli: Cli): Int {
        if (cli.info) {
            // TODO(port): kotllm.repl.Commands is being ported concurrently; not yet available.
            println(kotllm.repl.Commands.infoText(runner))
            return 0
        }
        if (cli.list != null) return listItems(runner, cli.list)
        if (cli.agent != null) return runAgent(runner, cli)
        if (cli.skill != null) return runSkill(runner, cli)

        val indexed = applyRagFlags(runner, cli).fold(onErr = { e -> return fail(e) }, onOk = { it })
        val composed = composeInput(runner, cli).fold(onErr = { e -> return fail(e) }, onOk = { it })

        return when {
            composed != null && cli.dryRun -> {
                val (text, _) = composed
                // Expanded first, so --dry-run shows what would really be sent
                // rather than the unexpanded template.
                runner.expand(text).fold(
                    onErr = { e -> fail(e) },
                    onOk = { expanded ->
                        println(runner.dryRun(expanded))
                        0
                    }
                )
            }
            composed != null -> {
                val (text, images) = composed
                singleHit(runner, text, images)
            }
            cli.dryRun -> usageError("--dry-run needs input text or -f")
            // Indexing was the whole job, so exit rather than opening a REPL
            // that a script would hang on.
            indexed -> 0
            else -> {
                // TODO(port): kotllm.repl.Repl is being ported concurrently; not yet available.
                val repl = kotllm.repl.Repl(runner)
                readerHolder?.set(repl.lineReader)
                repl.run()
                0
            }
        }
    }

    /** Loads a skill and asks with it, which is `/skill` without a REPL.
      *
      * The instructions become the prompt rather than a system message: a skill
      * is a procedure for the task at hand, and framing it as standing
      * configuration would leave it in effect for everything after -- which for
      * a single hit is the same thing, and for a piped sequence is not.
      */
    private fun runSkill(runner: Runner, cli: Cli): Int {
        val name = cli.skill!!
        return runner.skills.find(name).fold(
            onErr = { e -> fail(e) },
            onOk = { skill ->
                val instructions = runner.skills.render(skill, cli.text)
                // Appended only when the skill does not interpolate TEXT itself, so
                // arguments are never delivered twice nor silently dropped.
                val task =
                    if (cli.text.isNullOrEmpty() || skill.body.contains("\$ARGUMENTS")) ""
                    else "\n\nThe task: ${cli.text}"

                // `-f` still composes: sources land after the instructions, which is
                // the order they are meant to be read in.
                composeInput(runner, cli.copy(text = null)).fold(
                    onErr = { e -> fail(e) },
                    onOk = { attached ->
                        val sources = attached?.let { (src, _) -> "\n\n$src" } ?: ""
                        val images = attached?.second ?: emptyList()
                        val prompt = "Follow these skill instructions.\n\n$instructions$task$sources"
                        if (cli.dryRun) {
                            println(runner.dryRun(prompt))
                            0
                        } else {
                            singleHit(runner, prompt, images)
                        }
                    }
                )
            }
        )
    }

    /** Runs an agent file, or adopts it as a role.
      *
      * The two outcomes are decided by what the file *did*: a file that called
      * none of the agent primitives is a prompt, so it becomes the system prompt
      * and TEXT becomes the question -- which is the whole of what a role ever
      * was. A file that called any of them produced an artifact, and there is
      * nothing further to send.
      */
    private fun runAgent(runner: Runner, cli: Cli): Int {
        val name = cli.agent!!
        return kotllm.agent.Agent.load(name).fold(
            onErr = { e -> fail(e) },
            onOk = { (label, source) ->
                if (!cli.dryRun) System.err.println(kotllm.agent.Agent.banner(label))

                kotllm.agent.Agent.run(runner, label, source, cli.agentVars, cli.dryRun).fold(
                    onErr = { e -> fail(e) },
                    onOk = { result ->
                        when (result) {
                            is kotllm.agent.AgentResult.Prompt -> {
                                runner.session.systemPrompt = result.text
                                val question = cli.text
                                if (question != null) {
                                    singleHit(runner, question)
                                } else {
                                    // A role with no question is an interactive session in that
                                    // role, which is exactly what aichat's -r does.
                                    // TODO(port): kotllm.repl.Repl is being ported concurrently; not yet available.
                                    val repl = kotllm.repl.Repl(runner)
                                    readerHolder?.set(repl.lineReader)
                                    repl.run()
                                    0
                                }
                            }
                            is kotllm.agent.AgentResult.Ran -> {
                                println(result.output)
                                System.err.println(
                                    Ansi.Color.DarkGray.on("kotllm: ${result.verdict ?: "ran to the end"} -- ${result.dir}")
                                )
                                if (result.failed) 1 else 0
                            }
                        }
                    }
                )
            }
        )
    }

    /** Combines `-f` sources with the typed text into one input.
      *
      * Returns null only when there is genuinely nothing to send, which is the
      * signal to start the REPL. `-f` with no text is still a real request --
      * "here are some files" -- so it must not fall through to interactive mode.
      */
    private fun composeInput(
        runner: Runner,
        cli: Cli
    ): Outcome<Pair<String, List<kotllm.client.Part.Image>>?> =
        if (cli.files.isEmpty()) ok(cli.text?.let { t -> Pair(t, emptyList()) })
        else runner.inputLoader.load(cli.files).map { loaded ->
            // Reported, never swallowed: believing the model saw a file it never
            // received is worse than sending nothing.
            loaded.skipped.forEach { skip ->
                System.err.println(Ansi.Color.Yellow.on("kotllm: skipped $skip"))
            }
            loaded.medias.forEach { m ->
                System.err.println(
                    Ansi.Color.DarkGray.on("kotllm: attached ${m.label} (${m.bytes / 1024}KB ${m.mimeType})")
                )
            }
            Pair(loaded.render(cli.text), loaded.medias.map { m -> kotllm.client.Part.Image(m.dataUrl) })
        }

    /** Builds the runner, wiring tool approval to the terminal when there is
      * one.
      *
      * The prompter needs the REPL's line reader and the REPL needs the runner,
      * so the reader is supplied indirectly through a holder that the REPL fills
      * in once it exists. */
    private fun buildRunner(config: Config, cli: Cli): Outcome<Runner> {
        val interactive = System.console() != null && cli.text == null
        // TODO(port): kotllm.repl.ReaderHolder and kotllm.repl.ReplPrompter are being
        // ported concurrently; not yet available.
        val holder = kotllm.repl.ReaderHolder()
        return Runner.build(config, cli.model, cli.systemPrompt, kotllm.repl.ReplPrompter { holder.get }, interactive)
            .map { runner ->
                readerHolder = holder
                runner
            }
    }

    /** Applies `--rag-index` then `--rag`, in that order, so a later `--rag` can
      * still redirect retrieval away from the folder just built.
      *
      * @return whether an index was built, which decides what happens when there
      *         is no text to send: `--rag-index ./docs` alone is a setup command
      *         and exits, where dropping into the REPL would hang a script.
      */
    private fun applyRagFlags(runner: Runner, cli: Cli): Outcome<Boolean> {
        val built = cli.ragIndex?.let { folder ->
            indexFolder(runner, folder).fold(onErr = { e -> return err(e) }, onOk = { true })
        } ?: false
        selectRag(runner, cli.rag).fold(onErr = { e -> return err(e) }, onOk = {})
        // Last, so it applies to whichever index the two flags above settled on.
        describeRag(runner, cli.ragDescribe).fold(onErr = { e -> return err(e) }, onOk = {})
        return ok(built)
    }

    /** Applies `--rag-describe`, which needs an index to attach to. */
    private fun describeRag(runner: Runner, text: String?): Outcome<Unit> {
        if (text == null) return ok(Unit)
        val store = runner.rag ?: return err("--rag-describe was given but rag is disabled (rag_enabled: false)")
        return store.setDescription(text).fold(onErr = { e -> err("--rag-describe: $e") }, onOk = { ok(Unit) })
    }

    private fun indexFolder(runner: Runner, folder: String): Outcome<Unit> {
        val store = runner.rag ?: return err("--rag-index was given but rag is disabled (rag_enabled: false)")
        // TODO(port): kotllm.repl.Commands.indexProgress is being ported concurrently; not yet available.
        return store.indexFolder(folder, kotllm.repl.Commands.indexProgress()).fold(
            onErr = { e -> err("--rag-index $folder: $e") },
            onOk = { report ->
                println(report.summary)
                // Skips are reported, never swallowed: believing a file was
                // indexed when it was refused is worse than indexing nothing.
                report.skipped.take(10).forEach { s ->
                    System.err.println(Ansi.Color.Yellow.on("kotllm: skipped $s"))
                }
                if (report.skipped.size > 10)
                    System.err.println(
                        Ansi.Color.Yellow.on("kotllm: and ${report.skipped.size - 10} more skipped")
                    )
                ok(Unit)
            }
        )
    }

    /** Applies `--rag <name>`.
      *
      * A failure here is fatal rather than a warning: someone who named an index
      * on the command line is asking a question about it, and answering from
      * general knowledge instead would look like a correct answer.
      */
    private fun selectRag(runner: Runner, name: String?): Outcome<Unit> {
        if (name == null) return ok(Unit)
        val store = runner.rag ?: return err("--rag was given but rag is disabled (rag_enabled: false)")
        return store.activate(name).fold(
            onErr = { e ->
                val known = store.names
                val hint =
                    if (known.isEmpty()) "no indexes exist yet; create one with '/rag index <folder>'"
                    else "available: ${known.joinToString(", ")}"
                err("--rag $name: $e ($hint)")
            },
            onOk = { ok(Unit) }
        )
    }

    private var readerHolder: kotllm.repl.ReaderHolder? = null

    /** CLI flags win over the config file for this run only; nothing is written
      * back. */
    private fun applyOverrides(base: Config, cli: Cli): Outcome<Config> {
        val withStream = if (cli.noStream) base.copy(stream = false) else base
        return cli.reasoning?.let { v ->
            ReasoningDisplay.parse(v)?.let { m -> ok(withStream.copy(reasoning = m)) }
                ?: err("--reasoning must be one of: show, collapse, hide (got '$v')")
        } ?: ok(withStream)
    }

    /** Single-hit mode: the runner's session starts empty and nothing is carried
      * in, so the request holds only the system prompt and this one input. */
    private fun singleHit(
        runner: Runner,
        text: String,
        images: List<kotllm.client.Part.Image> = emptyList()
    ): Int {
        // stderr, so it cannot corrupt piped output.
        runner.startupHints.forEach { hint ->
            System.err.println(Ansi.Color.Yellow.on("kotllm: $hint"))
        }
        return runner.expand(text).fold(
            onErr = { e -> fail(e) },
            onOk = { expanded ->
                runner.ask(expanded, images = images).fold(
                    onErr = { e -> fail(e) },
                    onOk = { 0 }
                )
            }
        )
    }

    /** `--list-<kind>`: names on stdout, one per line, so the output is a pipe
      * away from being useful. An empty list is an error with the reason on
      * stderr rather than silence, which a script cannot distinguish from a
      * successful nothing. */
    private fun listItems(runner: Runner, kind: String): Int =
        // TODO(port): kotllm.repl.Commands is being ported concurrently; not yet available.
        kotllm.repl.Commands.listing(runner, kind).fold(
            onErr = { e -> fail(e) },
            onOk = { items ->
                if (items.isEmpty()) {
                    System.err.println("kotllm: no $kind configured")
                    1
                } else {
                    items.forEach { println(it) }
                    0
                }
            }
        )

    private fun fail(message: String): Int {
        System.err.println(Ansi.Color.Red.on("kotllm: $message"))
        return 1
    }

    private fun usageError(message: String): Int {
        System.err.println(Ansi.Color.Red.on("kotllm: $message"))
        return 2
    }

    private fun configError(err: String, path: Path): Int {
        System.err.println(Ansi.Color.Red.on("kotllm: $err"))
        System.err.println(
            """
            |Create $path with an endpoint to talk to. For a Databricks serving
            |endpoint that looks like:
            |
            |  model: my-endpoint-name
            |  save: true
            |
            |  clients:
            |  - type: openai-compatible
            |    name: my-endpoint-name
            |    api_base: https://<workspace>.azuredatabricks.net/serving-endpoints
            |    api_key: dapi...
            |    models:
            |    - name: my-endpoint-name
            |      max_input_tokens: 128000
            |      supports_function_calling: true
            |
            |Set KOTLLM_CONFIG_DIR to keep the config somewhere else.
            """.trimMargin()
        )
        return 1
    }
}
