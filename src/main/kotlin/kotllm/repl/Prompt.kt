package kotllm.repl

import kotllm.Runner
import kotllm.render.*
import kotllm.util.Glyphs
import kotllm.util.TokenLedger

/** Composes the REPL chrome: the rules bracketing the input line, the prompt
 * caret, and the status footer below.
 *
 *     ...model output...
 *     ────────────────────────────────────────
 *     ❯ what you type
 *     ────────────────────────────────────────
 *       qwen35-122b · 18.4k/261k ctx (7%) · ↓ 2.3k · rate 41% · 5 turns
 *
 * Layout only. The mechanics of pinning the lower rule and footer beneath the
 * cursor live in [Chrome].
 */
object Prompt {

    private const val MinWidth = 20
    private const val MaxWidth = 200

    fun divider(width: Int): String {
        val w = maxOf(MinWidth, minOf(MaxWidth, width))
        return Ansi.Color.DarkGray.on(Glyphs.dividerChar.repeat(w))
    }

    /** Upper rule plus the caret, as a single multi-line prompt so the rule sits
     * directly above whatever the user is typing however far the output above it
     * has scrolled. */
    fun input(width: Int): String =
        divider(width) + "\n" + (Ansi.Bold.On + Ansi.Color.White).on("${Glyphs.promptCaret} ")

    /** True when a rendered prompt carries the upper rule.
     *
     * A run of three, not one character. `contains(dividerChar)` was enough
     * while the rule was always a box-drawing glyph, and stopped being enough
     * the moment it could fall back to ASCII `-`: the plain prompt is the model
     * name, and `databricks-qwen35-122b-a10b>` is full of hyphens. */
    fun isFramed(rendered: String): Boolean =
        rendered.contains(Glyphs.dividerChar.repeat(3))

    /** Fallback for a terminal that cannot pin a footer: no rules, since a rule
     * with nothing under it is just noise. */
    fun plain(runner: Runner): String =
        Ansi.Color.Green.on(runner.target.model.name) +
            (Ansi.Bold.On + Ansi.Color.White).on("${Glyphs.promptCaret} ")

    /** Continuation prompt inside a ::: fence. Uses the ellipsis rather than the
     * rule glyph, which as an ASCII underscore would read as a blank line. */
    val continuation: String
        get() = Ansi.Color.DarkGray.on("${Glyphs.ellipsis} ")

    /** Printed width of the continuation prompt, for the row arithmetic that
     * erases a fence's echo. The ASCII fallback is three characters wide, so
     * this cannot be a constant. */
    val continuationCols: Int
        get() = Glyphs.ellipsis.length + 1

    /** One element of the footer.
     *
     * `width` is the printed width, kept alongside the rendered string because
     * the rendered one is full of colour escapes and measuring it would count
     * them.
     */
    private class Element(val rendered: String, val width: Int, val dropOrder: Int)

    private fun element(text: String, colour: Attrs, dropOrder: Int = Keep): Element =
        Element(
            colour.on(text),
            text.length,
            dropOrder
        )

    /** Never dropped, however narrow the window. */
    private const val Keep = 0

    /** What gets sacrificed first when the line will not fit, highest first.
     *
     * The footer is now the only place some of this appears, so it degrades
     * deliberately instead of letting JLine truncate at the window edge -- which
     * would cut off the volatile numbers, the ones worth reading. Hint first
     * (needed once), then the version (`/info` has it), then the cumulative
     * totals. Model, tool state, context pressure, the rate window and failures
     * are never dropped. */
    private const val DropHint = 6
    private const val DropVersion = 5
    private const val DropTokens = 4
    private const val DropCost = 3
    private const val DropRequests = 2

    /** Kept longer than the session totals, and deliberately: `2/5 tasks` says
     * what is happening now, where a cumulative token count says what already
     * happened. Still droppable, because the list itself is in the transcript
     * and `/tasks` reprints it. */
    private const val DropTasks = 1

    private const val Indent = "  "

    /** The status footer.
     *
     * Carries what used to be in the startup banner -- version, model, tool
     * state -- because a banner states its facts once and then scrolls away,
     * while the questions it answered are asked at arbitrary moments later.
     * Ordered least- to most-volatile left to right, so the eye lands on the
     * changing numbers at a stable place.
     *
     * @param width printed columns available; narrower drops elements by
     *              [DropHint] and friends rather than truncating.
     */
    fun footer(runner: Runner, width: Int = Int.MAX_VALUE): String {
        val session = runner.session
        val ledger = session.ledger
        val target = runner.target

        val elements = mutableListOf<Element>()

        // Which build is on screen. The whole point of bumping the version every
        // build is being able to read it off without asking.
        elements += element("kotllm ${kotllm.util.BuildInfo.version}", Ansi.Color.DarkGray, DropVersion)

        elements += element(target.model.name, Ansi.Color.Green)

        // Tool state is never dropped and is amber when there is none: "the model
        // says it cannot run anything and nothing explains why" is the exact
        // failure two config keys already caused (see docs/tools.md). A count is
        // enough -- `/info` names them.
        elements += when (runner.activeTools.size) {
            0 -> element("no tools", Ansi.Color.Yellow)
            1 -> element("1 tool", Ansi.Color.DarkGray)
            else -> element("${runner.activeTools.size} tools", Ansi.Color.DarkGray)
        }

        // Context pressure: what the *next* request would carry, not the session
        // total and not the last request either -- `/empty` and `/compact` change
        // it without a turn happening, and reading a stale number is exactly the
        // moment the footer is consulted.
        runner.contextSize?.let { ctx ->
            val max = target.model.maxInputTokens
            elements += if (max != null && max > 0) {
                val pct = ctx.percentOf(max)
                val text = "${TokenLedger.compact(ctx.tokens.toLong())}/${TokenLedger.compact(max.toLong())} ctx ($pct%)"
                // Amber past two thirds, red past ninety percent: the point where a
                // long session is about to start failing.
                if (pct >= 90) element(text, Ansi.Color.Red)
                else if (pct >= 66) element(text, Ansi.Color.Yellow)
                else element(text, Ansi.Color.DarkGray)
            } else {
                element("${TokenLedger.compact(ctx.tokens.toLong())} ctx", Ansi.Color.DarkGray)
            }
        }

        if (ledger.totalCompletionTokens() > 0)
            elements += element(
                "${Glyphs.tokensOut} ${TokenLedger.compact(ledger.totalCompletionTokens().toLong())} tokens",
                Ansi.Color.DarkGray,
                DropTokens
            )

        // Rate-limit window, only when one is configured.
        runner.limiter?.let { l ->
            val w = l.snapshot()
            w.pressure()?.let { fraction ->
                val pct = Math.round(fraction * 100).toInt()
                val text = "rate $pct%"
                elements += if (pct >= 90) element(text, Ansi.Color.Red)
                else if (pct >= 66) element(text, Ansi.Color.Yellow)
                else element(text, Ansi.Color.DarkGray)
            }
            if (w.throttleCount > 0)
                elements += element("throttled ${w.throttleCount}x", Ansi.Color.Yellow)
        }

        // Only while a checklist exists, so an ordinary one-shot question never
        // carries a `0/0 tasks` that means nothing.
        run {
            val (closed, total) = runner.checklist.counts
            if (total > 0)
                elements += element(
                    "$closed/$total tasks",
                    if (closed == total) Ansi.Color.Green else Ansi.Color.DarkGray,
                    DropTasks
                )
        }

        if (ledger.turnCount() > 0)
            elements += element(
                "${ledger.turnCount()} req${if (ledger.turnCount() == 1) "" else "s"}",
                Ansi.Color.DarkGray,
                DropRequests
            )

        // Dollars only here; the DBU derivation belongs in `/status`, which has
        // room to show the arithmetic. Omitted entirely when nothing priced has
        // been spent, so an unpriced model never shows a misleading $0.0000.
        if (runner.config.showCost) {
            val spend = runner.cost
            if (spend.totalUsd > 0.0)
                elements += element(kotllm.util.Pricing.usd(spend.totalUsd), Ansi.Color.DarkGray, DropCost)
            // Nothing at all for a purely local session: `$0.0000` would waste the
            // slot and `unpriced` would claim a problem that does not exist. The
            // absence is the honest reading -- no money is being spent.
            else if (spend.anyLocal && !spend.anyUnpriced) {
                // nothing
            } else if (spend.anyUnpriced)
            // Worth a slot: a session that looks free because nothing matched the
            // price table is exactly the case a running cost exists to prevent.
                elements += element("unpriced", Ansi.Color.Yellow, DropCost)
        }

        if (ledger.errorCount() > 0)
            elements += element("${ledger.errorCount()} failed", Ansi.Color.Red)

        // Last, because it is the least interesting thing on the line and the
        // first to go. It replaces the banner's "/help for commands".
        elements += element("/help", Ansi.Color.DarkGray, DropHint)

        val kept = fit(elements, width)
        val sep = Ansi.Color.DarkGray.on(" ${Glyphs.bullet} ")
        return Indent + kept.joinToString(sep) { it.rendered }
    }

    /** Printed width of the separator between two elements. */
    private val separatorWidth: Int get() = Glyphs.bullet.length + 2

    /** Drops the most expendable elements until the line fits. */
    private fun fit(all: List<Element>, width: Int): List<Element> {
        var kept = all.toMutableList()
        fun used(): Int = Indent.length + kept.sumOf { it.width } + separatorWidth * maxOf(0, kept.size - 1)
        while (used() > width && kept.any { it.dropOrder > Keep }) {
            val expendable = kept.filter { it.dropOrder > Keep }.maxByOrNull { it.dropOrder }
            kept = kept.filterNot { it === expendable }.toMutableList()
        }
        return kept
    }

    /** Footer shown while a turn is in flight, replacing the idle one. */
    fun busyFooter(label: String): String =
        "  " + Ansi.Color.Yellow.on("${Glyphs.ellipsis} $label")
}
