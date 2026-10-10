package kotllm.template

import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import kotllm.Runner
import kotllm.config.Paths
import kotllm.config.ReasoningDisplay
import kotllm.util.TokenLedger

/** The harness variable table.
  *
  * Scoped to facts the harness already knows and the model otherwise cannot
  * see. Anything requiring computation belongs in `<% %>` instead, which is
  * why nothing here takes an argument and nothing here shells out -- a
  * variable that could hang or fail is a variable that makes prompts
  * unreliable.
  */
object HarnessVars {

    private val IsoMinutes: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")

    fun table(runner: Runner): List<VarDef> {
        val config = runner.config
        val target = runner.target
        val session = runner.session
        val ledger = session.ledger

        fun prop(key: String, fallback: String = "unknown"): String =
            System.getProperty(key)?.takeIf { it.isNotEmpty() } ?: fallback

        val fixed = listOf(
            // ---- endpoint and model ----
            VarDef("model", "Model name as sent to the endpoint") { target.model.name },
            VarDef("model.id", "Fully qualified client:model id") { target.id() },
            VarDef("endpoint", "Base URL of the serving endpoint") { target.client.apiBase },
            VarDef("client", "Configured client name") { target.client.id() },
            VarDef("client.type", "Client type from config") { target.client.kind },

            // ---- context and usage ----
            VarDef(
                "context.limit",
                "Model input window in tokens"
            ) { target.model.maxInputTokens?.toString() ?: "unknown" },
            VarDef(
                "context.used",
                "Tokens the next request will carry"
            ) { runner.contextSize?.tokens?.toString() ?: "0" },
            VarDef(
                "context.percent",
                "Share of the input window the next request would use"
            ) {
                val ctx = runner.contextSize
                val max = target.model.maxInputTokens
                if (ctx != null && max != null && max > 0) "${ctx.percentOf(max)}%" else "unknown"
            },
            VarDef("tokens.in", "Prompt tokens billed this session") { ledger.totalPromptTokens().toString() },
            VarDef("tokens.out", "Completion tokens billed this session") { ledger.totalCompletionTokens().toString() },
            VarDef("tokens.total", "All tokens billed this session") { ledger.totalTokens().toString() },
            VarDef("requests", "API requests made this session") { ledger.turnCount().toString() },
            VarDef("stats", "One-line usage summary") { summary(runner) },

            // ---- session ----
            VarDef("uptime", "How long this session has been running") { TokenLedger.duration(ledger.uptimeMs()) },
            VarDef(
                "session.started",
                "When this session began"
            ) { LocalDateTime.now().minusNanos(ledger.uptimeMs() * 1_000_000L).format(IsoMinutes) },
            VarDef(
                "memory.files",
                "Number of memory files being injected"
            ) { runner.memoryFiles.size.toString() },
            VarDef(
                "memory.chars",
                "Characters of memory in the system prompt"
            ) { runner.memory.plan().injected.sumOf { it.content.length }.toString() },
            VarDef("tools", "Tools currently offered to the model") {
                val names = runner.activeTools.map { it.name }
                if (names.isEmpty()) "none" else names.joinToString(", ")
            },

            // ---- settings the model may need to know ----
            VarDef("reasoning", "Reasoning display mode") { ReasoningDisplay.name(config.reasoning) },
            VarDef(
                "max_tokens",
                "Output token cap, or the endpoint default"
            ) { config.maxTokens?.toString() ?: "endpoint default" },
            VarDef(
                "temperature",
                "Sampling temperature, or the endpoint default"
            ) { config.temperature?.toString() ?: "endpoint default" },
            VarDef(
                "config.path",
                "Config file in use"
            ) { config.sourcePath?.toString() ?: Paths.configFile().toString() },

            // ---- machine ----
            VarDef("pwd", "Working directory") { prop("user.dir", ".") },
            VarDef("home", "Home directory") { prop("user.home", ".") },
            VarDef("user", "Local username") { prop("user.name") },
            VarDef("host", "Hostname") { hostname() },
            VarDef("os", "Operating system and version") { "${prop("os.name")} ${prop("os.version", "")}".trim() },
            VarDef("arch", "CPU architecture") { prop("os.arch") },
            VarDef("shell", "Shell that the exec tool uses") { shell() },
            VarDef(
                "cmdline",
                "Command line this process was launched with"
                // From sun.java.command rather than plumbed through from main, so it
                // stays correct however kotllm was started.
            ) { prop("sun.java.command", "unknown") },

            // ---- versions ----
            VarDef("kotllm", "kotllm version") { kotllm.Cli.Version },
            VarDef("groovy", "Groovy version of the embedded interpreter") { groovy.lang.GroovySystem.getVersion() },
            VarDef("java", "JVM version") { prop("java.version") },

            // ---- time ----
            VarDef("now", "Local date and time") { LocalDateTime.now().format(IsoMinutes) },
            VarDef("date", "Local date") { LocalDate.now().toString() },
            VarDef("time", "Local time") { LocalDateTime.now().format(DateTimeFormatter.ofPattern("HH:mm:ss")) },
            VarDef("epoch_ms", "Milliseconds since the Unix epoch") { Instant.now().toEpochMilli().toString() }
        )

        return fixed + rateVars(runner)
    }

    /** Only present when a rate limit is configured, so the table does not
      * advertise variables that would always read zero. */
    private fun rateVars(runner: Runner): List<VarDef> {
        val limiter = runner.limiter ?: return emptyList()
        return listOf(
            VarDef(
                "rate.window",
                "Rate limit window length in seconds"
            ) { limiter.config.windowSeconds.toString() },
            VarDef(
                "rate.used",
                "Input tokens used in the current rate window"
            ) { limiter.snapshot().inputTokens.toString() },
            VarDef(
                "rate.percent",
                "Share of the tightest rate budget in use"
            ) { limiter.snapshot().pressure()?.let { p -> "${Math.round(p * 100)}%" } ?: "unknown" }
        )
    }

    private fun summary(runner: Runner): String {
        val ledger = runner.session.ledger
        return if (ledger.isEmpty()) "no requests yet"
        else "${ledger.turnCount()} requests, ${ledger.totalPromptTokens()} in / " +
            "${ledger.totalCompletionTokens()} out, up ${TokenLedger.duration(ledger.uptimeMs())}"
    }

    private fun hostname(): String {
        val fromEnv = (System.getenv("COMPUTERNAME") ?: System.getenv("HOSTNAME"))?.takeIf { it.isNotEmpty() }
        return fromEnv ?: try {
            java.net.InetAddress.getLocalHost().hostName
        } catch (e: Exception) {
            "unknown"
        }
    }

    private fun shell(): String =
        if ((System.getProperty("os.name") ?: "").lowercase().contains("win")) "cmd.exe"
        else System.getenv("SHELL")?.takeIf { it.isNotEmpty() } ?: "sh"

    fun asMap(runner: Runner): Map<String, () -> String> =
        table(runner).associate { v -> v.name to v.value }
}
