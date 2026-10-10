package kotllm.repl

import kotllm.Runner
import kotllm.render.Ansi
import kotllm.render.on
import kotllm.util.Glyphs
import kotllm.util.TokenLedger

/** The `/status` report: token usage over time plus the metrics that tell you
 * whether a session is healthy.
 *
 * Separate from `/info`, which answers "how am I configured". This answers
 * "what has this session cost and how is it behaving".
 */
object Status {

    /** How many turns to show in the per-turn table. Older turns still count
     * toward the totals and the sparkline. */
    private const val TableRows = 10

    fun render(runner: Runner, width: Int = 20): String {
        val ledger = runner.session.ledger
        val target = runner.target

        if (ledger.isEmpty() && ledger.errorCount() == 0)
            return "no turns yet in this session"

        val sb = StringBuilder()

        // Built by concatenation rather than one f-string: a trailing %n would be
        // parsed as the format spec of the preceding argument.
        fun row(label: String, value: String) {
            sb.append("%-16s".format(label)).append(value).append('\n')
        }

        // "requests", not "turns": one user message can produce several requests
        // once the model starts calling tools, and every one of them costs tokens.
        row("session", "${TokenLedger.duration(ledger.uptimeMs())}, ${ledger.turnCount()} requests")
        row("model", if (ledger.models().size <= 1) target.model.name else ledger.models().joinToString(", "))

        // ---- tokens ----
        sb.append('\n')
        row(
            "tokens",
            "${TokenLedger.commas(ledger.totalPromptTokens())} in / " +
                "${TokenLedger.commas(ledger.totalCompletionTokens())} out / " +
                "${TokenLedger.commas(ledger.totalTokens())} total"
        )

        // Context pressure is about the *next* prompt, not the session total: every
        // turn re-sends the history, so the total exceeds the window long before
        // the window is actually full. "projected" marks a payload the endpoint has
        // not counted, which after `/empty`, `/compact` or any reply it has not.
        runner.contextSize?.let { ctx ->
            val note = if (ctx.measured) "" else "  (projected)"
            val max = target.model.maxInputTokens
            if (max != null && max > 0) {
                val fraction = ctx.tokens.toDouble() / max
                row(
                    "context",
                    "${TokenLedger.commas(ctx.tokens)} / ${TokenLedger.commas(max)}  " +
                        "[${TokenLedger.bar(fraction, width)}] ${Math.round(fraction * 100)}%$note"
                )
            } else {
                row("context", "${TokenLedger.commas(ctx.tokens)} tokens (window unknown)$note")
            }

            // The line that stops "context" being misread as a running total. It is
            // occupancy: the overhead below is in the figure above exactly once,
            // however many turns have happened, because one request carries it once.
            val fixed = runner.fixedOverheadTokens()
            val history = maxOf(0, ctx.tokens - fixed)
            row(
                "  of which",
                "${TokenLedger.commas(fixed)} system prompt + tool schemas, " +
                    "${TokenLedger.commas(history)} conversation"
            )
        }

        if (runner.session.compactionCount() > 0)
            row(
                "compacted",
                "${runner.session.compactionCount()}x this session " +
                    "(summaries of summaries lose detail)"
            )

        val prompts = ledger.promptSeries()
        if (prompts.size >= 2)
            row(
                "prompt growth",
                "${TokenLedger.sparkline(prompts)}  " +
                    "(${TokenLedger.commas(prompts.first())} ${Glyphs.arrow} ${TokenLedger.commas(prompts.last())})"
            )

        val completions = ledger.completionSeries()
        if (completions.size >= 2)
            row("output per turn", TokenLedger.sparkline(completions))

        // ---- cost ----
        //
        // Here rather than in the footer for the DBU derivation: Databricks bills
        // in DBU and the dollar figure is a conversion, so the report shows the
        // arithmetic and the footer shows only the answer.
        val spend = runner.cost
        if (spend.anyPriced || spend.anyUnpriced || spend.anyLocal) {
            sb.append('\n')
            val pricing = runner.config.pricing

            fun money(label: String, tokens: Long, usd: Double, dbu: Double) {
                val dbuPart = if (dbu > 0.0) "  %9s DBU".format(kotllm.util.Pricing.dbu(dbu)) else ""
                row(
                    label,
                    "%11s tok$dbuPart   ".format(TokenLedger.commas(tokens.toInt())) +
                        kotllm.util.Pricing.usd(usd)
                )
            }

            if (spend.anyPriced) {
                money("cost in", spend.inputTokens, spend.inputUsd, spend.inputDbu)
                money("cost out", spend.outputTokens, spend.outputUsd, spend.outputDbu)
                money("cost total", spend.pricedTokens, spend.totalUsd, spend.totalDbu)
            }

            // Local turns cost nothing, and saying so is different from admitting a
            // lookup failed. Green rather than amber, and no remedy offered, because
            // there is nothing to fix.
            if (spend.anyLocal) {
                row(
                    "local, free",
                    spend.localTurns.toList().sortedBy { it.first }.joinToString(", ") { (model, io) ->
                        val (i, o) = io
                        "$model (${TokenLedger.commas((i + o).toInt())} tok)"
                    }
                )
            }

            // Named, never folded into the total. A figure that quietly excludes half
            // the session is worse than no figure, and the fix -- add a key to the
            // price table -- needs the name to be visible.
            if (spend.anyUnpriced) {
                row(
                    "unpriced",
                    spend.unpriced.toList().sortedBy { it.first }.joinToString(", ") { (model, io) ->
                        val (i, o) = io
                        "$model (${TokenLedger.commas((i + o).toInt())} tok)"
                    }
                )
                // Naming the model is not enough on its own. The first person to hit
                // this pasted the documented example verbatim, so their entry was
                // keyed `my-azure-deployment` while the model was `gpt-5.6-luna`, and
                // all the report said was "unpriced". So it now says what key to add.
                val example = spend.unpriced.keys.sorted().first()
                sb.append(
                    Ansi.Color.Yellow.on(
                        "                 to cost it, add a `pricing:` entry in " +
                            "${runner.config.sourcePath?.fileName?.toString() ?: "config.yaml"} " +
                            "keyed `$example`\n"
                    )
                )
            }

            // The caveats belong next to the number, not in a manual. Cached input is
            // the big one: the gateway reports no cache breakdown, so the input line
            // above is an upper bound.
            val basis =
                "list price, \$${pricing.dbuRate} per DBU" +
                    (pricing.verified?.let { v -> ", table verified $v" } ?: "")
            sb.append(
                Ansi.Color.DarkGray.on(
                    "                 estimate: $basis\n" +
                        "                 cached input is not reported by the endpoint, so input " +
                        "cost is an upper bound\n"
                )
            )
        }

        // ---- speed ----
        sb.append('\n')
        ledger.averageLatencyMs()?.let { avg ->
            val last = ledger.all().last().latencyMs
            row("latency", "avg ${TokenLedger.seconds(avg)}, last ${TokenLedger.seconds(last)}")
        }
        ledger.averageTimeToFirstTokenMs()?.let { ttft ->
            row("first token", "avg ${TokenLedger.seconds(ttft)}")
        }
        ledger.all().lastOrNull()?.outputTokensPerSecond()?.let { tps ->
            row("throughput", "%.0f tok/s output (last turn)".format(tps))
        }

        // ---- rate limit window ----
        runner.limiter?.let { l ->
            val w = l.snapshot()
            sb.append('\n')

            fun dimension(label: String, used: Long, budget: Long?) {
                budget?.let { b ->
                    val fraction = used.toDouble() / b
                    row(
                        label,
                        "${TokenLedger.commas(used.toInt())} / ${TokenLedger.commas(b.toInt())}  " +
                            "[${TokenLedger.bar(fraction, width)}] ${Math.round(fraction * 100)}%"
                    )
                }
            }

            row("rate window", "rolling ${w.windowSeconds}s")
            dimension("  input", w.inputTokens, w.inputBudget)
            dimension("  output", w.outputTokens, w.outputBudget)
            dimension("  total", w.totalTokens(), w.totalBudget)
            dimension("  requests", w.requests.toLong(), w.requestBudget)

            // When the oldest entry ages out is the only thing that tells you how
            // long a full window will stay full.
            w.oldestExpiresInMillis?.let { ms ->
                row("  frees up in", TokenLedger.seconds(ms))
            }
            if (w.pendingReservations > 0)
                row("  in flight", "${w.pendingReservations} request(s) charged at estimate")
            if (w.throttleCount > 0)
                row(
                    "  throttled",
                    Ansi.Color.Yellow.on("${w.throttleCount}x, ${TokenLedger.seconds(w.totalWaitedMillis)} waited")
                )

            val est = runner.tokenEstimator
            row(
                "  estimator",
                if (est.isCalibrated)
                    "%.2f chars/token (%d samples)".format(est.charsPerToken, est.sampleCount)
                else "%.2f chars/token (uncalibrated)".format(est.charsPerToken)
            )
        }

        // ---- tools ----
        val rollups = ledger.toolRollups()
        if (rollups.isNotEmpty()) {
            sb.append('\n')
            row(
                "tool calls",
                "${ledger.executedToolCalls()} in ${TokenLedger.seconds(ledger.totalToolMillis())}"
            )

            // Per-tool, busiest first. Time is exact; the token column is an
            // estimate, because the endpoint reports no per-call token counts.
            sb.append(
                Ansi.Color.DarkGray.on(
                    "%-14s %3s %8s %8s %8s %7s  notes".format("tool", "n", "total", "avg", "slowest", "~tok")
                )
            )
            sb.append('\n')
            rollups.forEach { r ->
                val notes = listOfNotNull(
                    if (r.errors > 0) "${r.errors} failed" else null,
                    if (r.refused > 0) "${r.refused} refused" else null
                ).joinToString(", ")
                sb.append(
                    "%-14s %3d %8s %8s %8s %7s  ".format(
                        r.name, r.calls, TokenLedger.seconds(r.totalMillis),
                        TokenLedger.seconds(r.averageMillis()), TokenLedger.seconds(r.slowestMillis),
                        TokenLedger.compact(r.estimatedResultTokens.toLong())
                    )
                ).append(if (notes.isEmpty()) "-" else Ansi.Color.Yellow.on(notes)).append('\n')
            }

            // The one exact token figure available: how much the prompt actually grew
            // across the requests that followed a tool call.
            val fedBack = ledger.toolFeedbackTokens()
            if (fedBack > 0)
                row("  fed back", "${TokenLedger.commas(fedBack)} input tokens (measured)")
            row(
                "  results",
                "${TokenLedger.commas(ledger.toolCalls().sumOf { it.resultChars })} chars, " +
                    "~${TokenLedger.commas(ledger.estimatedToolResultTokens())} tokens (estimated)"
            )
            if (ledger.totalApprovalWaitMillis() > 500)
                row("  awaiting you", TokenLedger.seconds(ledger.totalApprovalWaitMillis()))
        }

        // ---- reasoning ----
        val reasoningChars = ledger.all().sumOf { it.reasoningChars }
        if (reasoningChars > 0) {
            sb.append('\n')
            row("reasoning", "${TokenLedger.commas(reasoningChars)} chars across ${ledger.turnCount()} requests")
        }

        // ---- warnings ----
        val flags = listOfNotNull(
            if (ledger.truncatedCount() > 0) "${ledger.truncatedCount()} truncated" else null,
            if (ledger.abortedCount() > 0) "${ledger.abortedCount()} aborted" else null,
            if (ledger.errorCount() > 0) "${ledger.errorCount()} failed" else null
        )
        if (flags.isNotEmpty()) {
            sb.append('\n')
            row("flags", Ansi.Color.Yellow.on(flags.joinToString(", ")))
        }

        // ---- per-turn table ----
        if (ledger.turnCount() > 0) {
            sb.append('\n')
            val shown = ledger.all().takeLast(TableRows)
            val elided = ledger.turnCount() - shown.size
            sb.append(
                Ansi.Color.DarkGray.on(
                    "%5s %9s %8s %8s %8s  tools".format("req", "in", "out", "think", "time")
                )
            )
            sb.append('\n')
            if (elided > 0) sb.append(Ansi.Color.DarkGray.on("%5s".format("(+$elided earlier)")) + "\n")
            shown.forEach { m ->
                val tools = if (m.toolCalls.isEmpty()) "-" else m.toolCalls.joinToString(",")
                val note = if (m.aborted) " (aborted)" else if (m.truncated) " (cut off)" else ""
                val inTok = TokenLedger.commas(m.promptTokens)
                val outTok = TokenLedger.commas(m.completionTokens)
                val think = TokenLedger.commas(m.reasoningChars)
                val time = TokenLedger.seconds(m.latencyMs)
                sb.append("%5d %9s %8s %8s %8s  ".format(m.index, inTok, outTok, think, time))
                    .append(tools)
                    .append(note)
                    .append('\n')
            }
        }

        return stripLineEnd(sb.toString())
    }

    /** Mirrors Scala's `String.stripLineEnd`: removes at most one trailing line
     * terminator (`\r\n`, `\r`, or `\n`), not every trailing newline. */
    private fun stripLineEnd(s: String): String = when {
        s.endsWith("\r\n") -> s.substring(0, s.length - 2)
        s.endsWith("\n") || s.endsWith("\r") -> s.substring(0, s.length - 1)
        else -> s
    }
}
