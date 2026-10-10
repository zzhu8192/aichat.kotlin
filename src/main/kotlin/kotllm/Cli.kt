package kotllm

import java.io.ByteArrayOutputStream

/** Command-line arguments.
  *
  * Hand-rolled rather than pulled from a parser library: the fat-jar classpath
  * is the standard library we advertise to the LLM, so a dependency earns its
  * place by being useful to generated code, not just to us (see the dependency
  * policy in docs/tools.md).
  */
data class Cli(
    val model: String? = null,
    val systemPrompt: String? = null,
    val reasoning: String? = null,
    val configPath: String? = null,
    val noStream: Boolean = false,
    val dryRun: Boolean = false,
    val info: Boolean = false,
    /** What `--list-<kind>` asked for. One field rather than a boolean per
      * kind: they are mutually exclusive by nature and the dispatcher wants to
      * switch on one value. */
    val list: String? = null,
    val help: Boolean = false,
    val version: Boolean = false,
    val files: List<String> = emptyList(),
    /** Name of a RAG index to activate for this run, overriding whichever one
      * was last active. Useful in single-hit mode, where there is no chance to
      * type `/rag use`. */
    val rag: String? = null,
    /** Folder to index before doing anything else, so an index can be created
      * without entering the REPL -- which is what scripted setup needs. */
    val ragIndex: String? = null,
    /** Description to attach to the index, so scripted setup can supply the
      * one thing the model actually needs to know about the corpus. */
    val ragDescribe: String? = null,
    /** Agent to run: a name under `agents/`, or a path to a file. */
    val agent: String? = null,
    /** `--agent-var k=v`, readable in the file as `args("k")`. */
    val agentVars: Map<String, String> = emptyMap(),
    /** Skill to load before asking: its instructions become the prompt, with
      * TEXT as `$ARGUMENTS` or appended as the task. The non-interactive twin
      * of `/skill`. */
    val skill: String? = null,
    val text: String? = null
) {

    companion object {

        /** Delegated rather than declared, so the pom is the only place a version
          * number is written down. */
        val Version: String = kotllm.util.BuildInfo.version

        /** Flags that are part of the planned surface but not built yet. Rejecting
          * them explicitly beats silently ignoring an argument the user meant. */
        private val NotYetImplemented = mapOf(
            "-s" to "named sessions",
            "--session" to "named sessions",
            "--list-sessions" to "named sessions",
            "-e" to "the shell assistant",
            "--execute" to "the shell assistant",
            "-c" to "code-only output",
            "--code" to "code-only output",
            "--serve" to "the HTTP server"
        )

        /** Flags that will never be built, because something else answers them.
          *
          * Kept separate from the list above on purpose: "lands in a later increment"
          * is a promise, and promising a feature that has been deliberately dropped
          * is the kind of over-promising this project tries not to do.
          */
        private val Answered = mapOf(
            "-r" to "an agent file with no llm() call is a role: try --agent <name>",
            "--role" to "an agent file with no llm() call is a role: try --agent <name>",
            "--list-roles" to "roles are agent files: try --list-agents",
            "--macro" to "an agent file does what a macro did, with a real language: try --agent",
            "--list-macros" to "macros are not built; agent files replace them: try --list-agents"
        )

        val usage: String =
            """kotllm - a Groovy LLM REPL for Databricks and Azure endpoints

USAGE:
    kotllm [OPTIONS] [TEXT]...

With TEXT, answers once and exits (single-hit mode, no conversation
context). With no TEXT, starts the interactive REPL (chat mode).
Piped stdin is appended to TEXT.

OPTIONS:
    -f, --file <SRC>      Include a file, directory, URL or `command`.
                          Repeatable. Also accepts %% for the last reply.
    -m, --model <ID>      Model to use, as `model` or `client:model`
        --prompt <TEXT>   System prompt for this run
        --reasoning <M>   Reasoning display: show, collapse, hide
        --config <PATH>   Config file to load
        --rag <NAME>      Retrieve from this index (see /rag index)
        --rag-index <DIR> Index a folder, then exit unless TEXT is given
        --rag-describe <T> What the indexed corpus is, told to the model
    -S, --no-stream       Wait for the whole reply instead of streaming
        --dry-run         Print the request JSON without sending it
        --info            Show resolved configuration and exit
        --list-models     List configured models and aliases, one per line
        --list-models --discover
                          Ask each endpoint what it has, via /v1/models
        --list-tools      List tool names usable in use_tools
        --list-rags       List retrieval indexes
        --list-memories   List memory files being injected
        --list-vars       List {{ }} harness variable names
        --list-agents     List agents in the agents directory
        --list-skills     List installed skills by name
        --list-commands   List REPL commands, for shell completion
    -a, --agent <NAME>    Run an agent file (agents/<NAME>.md)
        --skill <NAME>    Prepend a skill's instructions to TEXT
        --agent-var <K=V> Set an agent argument, readable as args("K")
    -h, --help            Print this help
    -V, --version         Print version

EXAMPLES:
    kotllm                              # REPL
    kotllm "explain Groovy closures"    # single hit
    git diff | kotllm "review this"     # stdin
    kotllm -f src/ -f README.md "review"
    kotllm -f screenshot.png "what is wrong here?"
    kotllm -f '`git diff`' "what changed?"
    kotllm --rag docs "how is auth configured?"
    kotllm --rag-index ./docs --rag-describe "our API docs"
    kotllm --rag-index . "what is this?" # index, then ask
    kotllm --skill commit-helper "stage and commit"
    kotllm --agent review                # run agents/review.md
    kotllm --agent review --dry-run      # see its shape, no model calls
    kotllm --agent reviewer "this diff"  # a file with no llm() is a role
"""

        fun parse(args: Array<String>): Outcome<Cli> {
            val words = mutableListOf<String>()
            var cli = Cli()
            var i = 0
            var error: String? = null

            fun next(flag: String): String? =
                if (i + 1 < args.size) {
                    i += 1
                    args[i]
                } else {
                    error = "$flag requires a value"
                    null
                }

            while (i < args.size && error == null) {
                val arg = args[i]
                when {
                    arg == "-h" || arg == "--help" -> cli = cli.copy(help = true)
                    arg == "-V" || arg == "--version" -> cli = cli.copy(version = true)
                    arg == "-S" || arg == "--no-stream" -> cli = cli.copy(noStream = true)
                    arg == "--dry-run" -> cli = cli.copy(dryRun = true)
                    arg == "--info" -> cli = cli.copy(info = true)
                    arg == "--list-models" -> cli = cli.copy(list = "models")
                    // A modifier rather than its own flag: the question is the same one,
                    // asked of the server instead of the config file.
                    arg == "--discover" -> cli = cli.copy(list = (cli.list ?: "models") + "-discover")
                    arg == "--list-tools" -> cli = cli.copy(list = "tools")
                    arg == "--list-rags" -> cli = cli.copy(list = "rags")
                    arg == "--list-memories" -> cli = cli.copy(list = "memories")
                    arg == "--list-vars" -> cli = cli.copy(list = "vars")
                    arg == "--list-commands" -> cli = cli.copy(list = "commands")
                    arg == "-f" || arg == "--file" -> next(arg)?.let { v -> cli = cli.copy(files = cli.files + v) }
                    arg == "-m" || arg == "--model" -> next(arg)?.let { v -> cli = cli.copy(model = v) }
                    arg == "--prompt" -> next(arg)?.let { v -> cli = cli.copy(systemPrompt = v) }
                    arg == "--reasoning" -> next(arg)?.let { v -> cli = cli.copy(reasoning = v) }
                    arg == "--config" -> next(arg)?.let { v -> cli = cli.copy(configPath = v) }
                    arg == "--rag" -> next(arg)?.let { v -> cli = cli.copy(rag = v) }
                    arg == "--rag-index" -> next(arg)?.let { v -> cli = cli.copy(ragIndex = v) }
                    arg == "--rag-describe" -> next(arg)?.let { v -> cli = cli.copy(ragDescribe = v) }
                    arg == "-a" || arg == "--agent" -> next(arg)?.let { v -> cli = cli.copy(agent = v) }
                    arg == "--agent-var" ->
                        next(arg)?.let { v ->
                            val at = v.indexOf('=')
                            if (at <= 0) error = "--agent-var wants name=value, got '$v'"
                            else cli = cli.copy(agentVars = cli.agentVars + (v.take(at) to v.drop(at + 1)))
                        }
                    arg == "--list-agents" -> cli = cli.copy(list = "agents")
                    arg == "--list-skills" -> cli = cli.copy(list = "skills")
                    arg == "--skill" -> next(arg)?.let { v -> cli = cli.copy(skill = v) }
                    Answered.containsKey(arg) ->
                        error = "$arg is not a kotllm flag -- ${Answered[arg]}"
                    NotYetImplemented.containsKey(arg) ->
                        error = "$arg is not implemented yet (${NotYetImplemented[arg]} land in a later increment)"
                    arg.startsWith("-") && arg.length > 1 ->
                        error = "unknown option '$arg'; try --help"
                    else -> words.add(arg)
                }
                i += 1
            }

            val failure = error
            if (failure != null) return err(failure)

            val typed = words.joinToString(" ")
            val piped = readStdinIfPiped()
            val joined = listOf(typed, piped ?: "").filter { it.isNotEmpty() }.joinToString("\n")
            return ok(cli.copy(text = joined.takeIf { it.isNotBlank() }))
        }

        /** Reads stdin only when it is not a terminal, so the REPL is not starved of
          * its own input. `System.console()` is null exactly when either stream is
          * redirected, so it cannot distinguish the cases on its own; combining it
          * with an availability check is the portable approximation. */
        private fun readStdinIfPiped(): String? =
            if (System.console() != null) null
            else
                try {
                    if (System.`in`.available() <= 0) null
                    else {
                        val buf = ByteArrayOutputStream()
                        val chunk = ByteArray(8192)
                        var n = System.`in`.read(chunk)
                        while (n > 0) {
                            buf.write(chunk, 0, n)
                            n = if (System.`in`.available() > 0) System.`in`.read(chunk) else -1
                        }
                        String(buf.toByteArray(), java.nio.charset.StandardCharsets.UTF_8)
                            .trim()
                            .takeIf { it.isNotEmpty() }
                    }
                } catch (e: java.io.IOException) {
                    null
                }
    }
}
