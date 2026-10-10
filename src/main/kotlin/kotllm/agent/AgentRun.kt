package kotllm.agent

import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import kotllm.tools.ApprovalMode

/** Configuration an agent sets for itself with `agent(...)`.
  *
  * Named arguments rather than YAML frontmatter, which is not only one fewer
  * parser but strictly more expressive: `maxCalls = if (files.size > 40) 80
  * else 30` is a thing a file can say and no config format can.
  */
data class AgentSettings(
    val model: String? = null,
    val tools: String? = null,
    val maxCalls: Int = 50,
    val maxTokens: Int? = null,
    val maxSeconds: Int? = null,
    /** Watched by default: the first runs of an agent are always debugging, and
      * you want to see the model reach a wrong conclusion as it happens rather
      * than read it afterwards. `quiet = true` reduces each call to one
      * progress line, which is what a scheduled or piped run wants. */
    val quiet: Boolean = false,
    /** `auto` inside a run, because an agent is unattended by definition and
      * under `confirm` its first `exec` would refuse. Announced when the run
      * starts and every call is logged. */
    val approval: ApprovalMode = ApprovalMode.Auto
)

sealed interface AgentStop {
    data class Finished(val verdict: String) : AgentStop
    data class Failed(val reason: String) : AgentStop
    data class Budget(val which: String) : AgentStop
    data object RanOut : AgentStop
}

/** Thrown by `finish` and `fail` to unwind the template.
  *
  * The interpreter catches everything the evaluated program throws and reports it
  * as a diagnostic string rather than rethrowing, so this is *not* caught by
  * the caller -- `Agent` checks `AgentRun.stop` instead and treats a recorded
  * stop as a clean exit. The output is captured at throw time for the same
  * reason.
  *
  * The four-argument `RuntimeException` constructor with `writableStackTrace =
  * false` is the JVM equivalent of Scala's `with NoStackTrace`: filling in a
  * stack trace for a control-flow exception thrown on every `finish()` call
  * would be pure waste.
  */
internal class AgentHalt : RuntimeException("agent stopped", null, false, false)

/** One run: its directory, its budgets, its log, and how it ended. */
class AgentRun(
    val name: String,
    val source: String,
    val args: Map<String, String>,
    /** Where run directories are created. Injectable so a test does not write
      * into the developer's own config directory -- the same hazard
      * `TestIsolation` exists for. */
    val base: Path = kotllm.config.Paths.runsDir()
) {

    private val startedAt = System.currentTimeMillis()

    var settings: AgentSettings = AgentSettings()

    /** True once the file has called any agent primitive.
      *
      * This is the mode switch, and it is deliberately a fact about what the file
      * *did* rather than about how it looks: a file that calls none of them emits
      * a prompt and is a role, so adding a `<% %>` tag to a prompt cannot
      * silently change its meaning.
      */
    var touched: Boolean = false

    /** Set by `agent(...)`, so calling it late can be reported rather than
      * silently ignored. */
    var callsMade: Int = 0

    private var stopped: AgentStop? = null
    private var captured: String? = null

    val stop: AgentStop? get() = stopped
    val output: String? get() = captured

    fun record(s: AgentStop, out: String) {
        if (stopped == null) {
            stopped = s
            captured = out
        }
    }

    val elapsedMillis: Long get() = System.currentTimeMillis() - startedAt

    /** The prompts of the last few calls, for the no-progress rule. A model
      * asking the identical question three times running is stuck, and the loop
      * should cost a budget rather than a mystery. */
    private val recentPrompts = ArrayDeque<String>()

    fun noProgress(prompt: String): Boolean {
        recentPrompts.addLast(prompt)
        if (recentPrompts.size > 3) recentPrompts.removeFirst()
        return recentPrompts.size == 3 && recentPrompts.toSet().size == 1
    }

    /** Which budget, if any, this run has exhausted. */
    fun exceeded(tokensSoFar: Int): String? {
        if (callsMade >= settings.maxCalls) return "maxCalls (${settings.maxCalls})"
        settings.maxTokens?.let { m -> if (tokensSoFar >= m) return "maxTokens ($m)" }
        settings.maxSeconds?.let { s -> if (elapsedMillis >= s * 1000L) return "maxSeconds ($s)" }
        return null
    }

    // ---- the run directory -------------------------------------------------

    private val stamp = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH-mm-ss"))

    /** Created lazily: a run that fails to parse should not litter a directory.
      *
      * The source is written directly here, **not** through `write`, which
      * reads `dir`. Going through `write` here would make this initialiser
      * re-enter its own lazy value before it has finished computing, which in
      * Scala broke in a spectacular way (see the Scala source comment this was
      * ported from); `by lazy` in Kotlin has the same re-entrancy hazard, so
      * the source is written with the local `d` instead of `dir`.
      */
    val dir: Path by lazy {
        val d = base.resolve("$name-$stamp")
        try {
            Files.createDirectories(d)
            // So a run is reproducible from its own directory.
            Files.write(d.resolve("agent.md"), source.toByteArray(StandardCharsets.UTF_8))
        } catch (e: Exception) {
            // Ignore
        }
        d
    }

    fun write(file: String, text: String) {
        try {
            Files.write(dir.resolve(file), text.toByteArray(StandardCharsets.UTF_8))
        } catch (e: Exception) {
            // Ignore
        }
    }

    private val logLines = mutableListOf<String>()

    fun log(line: String) {
        logLines.add(line)
    }

    val logText: String get() = (listOf("# $name") + logLines).joinToString("\n")

    fun flushLog() {
        write("log.md", logText)
    }
}
