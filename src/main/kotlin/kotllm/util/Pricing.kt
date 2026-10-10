package kotllm.util

/** What one model costs per million tokens. */
data class ModelPrice(val key: String, val input: Double, val output: Double, val inDbu: Boolean)

/** A session's estimated spend, split the way the bill is.
  *
  * `unpriced` names the models that matched nothing, and it is deliberately a
  * set of names rather than a count: a total that quietly excludes half the
  * session is worse than no total, and the only way to make that visible is to
  * say which model was missing so the user can add it.
  */
data class CostBreakdown(
    val inputTokens: Long,
    val outputTokens: Long,
    val inputUsd: Double,
    val outputUsd: Double,
    val inputDbu: Double,
    val outputDbu: Double,
    /** Turns whose model matched no price entry, and their token totals. */
    val unpriced: Map<String, Pair<Long, Long>>,
    /** Turns that ran on a local server, and their token totals.
      *
      * Kept apart from `unpriced`, which means "I could not find a price" -- a
      * warning that the total is understated by an unknown amount. Local means
      * "this cost nothing", a fact needing no remedy, and reporting it in amber
      * would train the user to ignore the one signal that matters. */
    val localTurns: Map<String, Pair<Long, Long>> = emptyMap()
) {
  val totalUsd: Double get() = inputUsd + outputUsd
  val totalDbu: Double get() = inputDbu + outputDbu
  val anyPriced: Boolean get() = inputTokens > 0 || outputTokens > 0
  val anyUnpriced: Boolean get() = unpriced.isNotEmpty()
  val anyLocal: Boolean get() = localTurns.isNotEmpty()
  val localTokens: Long get() = localTurns.values.sumOf { (i, o) -> i + o }

  /** Tokens that were actually costed, so a partial total can be reported
    * honestly against the session's real volume. */
  val pricedTokens: Long get() = inputTokens + outputTokens
  val unpricedTokens: Long get() = unpriced.values.sumOf { (i, o) -> i + o }

  companion object {
    val empty: CostBreakdown = CostBreakdown(0L, 0L, 0.0, 0.0, 0.0, 0.0, emptyMap(), emptyMap())
  }
}

/** Token prices, and the estimate they produce.
  *
  * **Every figure is an estimate, and the largest reason is not arithmetic:
  * cached input is invisible.** The gateway's `usage` block says nothing about
  * cache hits, so a prompt served from cache is charged here at full rate.
  * Everything reported is therefore an **upper bound** on input cost.
  *
  * Databricks publishes DBU per million rather than dollars, so that is what
  * the table stores and `dbuRate` converts -- a workspace on a negotiated rate
  * changes one number instead of a table.
  *
  * **Lookup matches the key's segments as an ordered subsequence of the model
  * id's**, which got there by failing twice: exact keys miss
  * `databricks-qwen35-122b-a10b` against "Qwen 3.5 122B", and longest *prefix*
  * then misses `databricks-deepseek-v4-1-flash` against "DeepSeek V4 Flash",
  * where the point release sits in the middle. Segments compare whole, so
  * `gpt-oss-20b` does not match `gpt-oss-120b`. Among several matches the most
  * specific wins, so a precise entry always overrides a general one.
  *
  * A matched *variant* is still a guess: `v4-1-flash` is costed at `v4-flash`'s
  * rate. Better than `unpriced`, and why the estimate is labelled.
  * See docs/cost.md.
  */
class Pricing(private val prices: List<ModelPrice>, val dbuRate: Double, val verified: String?) {

  private val indexed: List<Pair<ModelPrice, List<String>>> =
    prices.map { p -> p to Pricing.segments(p.key) }

  val entries: List<ModelPrice> get() = prices.sortedBy { it.key }

  val isEmpty: Boolean get() = prices.isEmpty()

  fun find(modelId: String?): ModelPrice? {
    val wanted = Pricing.segments(modelId ?: "")
    if (wanted.isEmpty()) return null
    return indexed
      .filter { (_, key) -> Pricing.isSubsequence(key, wanted) }
      // Most segments matched first, then the longer key: the most specific
      // entry wins, which is what makes a user override reliable.
      .sortedWith(compareBy({ -it.second.size }, { -it.first.key.length }))
      .firstOrNull()
      ?.first
  }

  /** Cost of one turn's tokens, at the price of the model that ran it. */
  fun costOf(model: String, inputTokens: Long, outputTokens: Long): CostQuad? =
    find(model)?.let { p ->
      val inUnits = inputTokens.toDouble() / 1_000_000.0 * p.input
      val outUnits = outputTokens.toDouble() / 1_000_000.0 * p.output
      if (p.inDbu) CostQuad(inUnits * dbuRate, outUnits * dbuRate, inUnits, outUnits)
      else CostQuad(inUnits, outUnits, 0.0, 0.0)
    }

  /** Adds up a whole session.
    *
    * Costed **per turn, at that turn's own model**, which is why `TurnMetrics`
    * carries the model name. A session that used `/model` to switch halfway
    * would otherwise be priced entirely at whichever model happened to be
    * selected when the report was asked for.
    */
  fun breakdown(turns: List<TurnMetrics>): CostBreakdown =
    turns.fold(CostBreakdown.empty) { acc, turn ->
      // A local turn is free, and is never looked up: a price table entry that
      // happened to match the model name of something running on the user's own
      // GPU would invent a bill.
      if (turn.local) {
        val (i, o) = acc.localTurns[turn.model] ?: (0L to 0L)
        acc.copy(localTurns =
          acc.localTurns + (turn.model to (i + turn.promptTokens to o + turn.completionTokens)))
      } else {
        val cost = costOf(turn.model, turn.promptTokens.toLong(), turn.completionTokens.toLong())
        if (cost != null) {
          acc.copy(
            inputTokens = acc.inputTokens + turn.promptTokens,
            outputTokens = acc.outputTokens + turn.completionTokens,
            inputUsd = acc.inputUsd + cost.inUsd,
            outputUsd = acc.outputUsd + cost.outUsd,
            inputDbu = acc.inputDbu + cost.inDbu,
            outputDbu = acc.outputDbu + cost.outDbu
          )
        } else {
          val (i, o) = acc.unpriced[turn.model] ?: (0L to 0L)
          acc.copy(unpriced =
            acc.unpriced + (turn.model to (i + turn.promptTokens to o + turn.completionTokens)))
        }
      }
    }

  companion object {

    /** Lowercased, split on the separators these names actually use. `.` is in
      * there because an Azure deployment can be `gpt-4.1-mini`, and treating
      * `4.1` as one segment would stop `gpt-4.1` matching it. */
    fun segments(name: String): List<String> =
      name.trim().lowercase().split(Regex("[-_.\\s]+")).filter { it.isNotEmpty() }

    /** Whether every element of `key` appears in `model`, in order, allowing
      * gaps -- which is what lets a spliced-in version segment through without
      * loosening whole-segment equality. */
    fun isSubsequence(key: List<String>, model: List<String>): Boolean {
      var at = 0
      return key.all { segment ->
        val found = model.subList(at, model.size).indexOf(segment)
        if (found < 0) false else { at += found + 1; true }
      }
    }

    /** Empty, for a config with pricing switched off. Reports everything as
      * unpriced rather than as free. */
    val disabled: Pricing = Pricing(emptyList(), 0.07, null)

    /** Small amounts need four decimals to be meaningful at all -- a session that
      * cost $0.0087 rounds to $0.01 and looks ten times worse than it was. Past a
      * dollar, cents are enough and four decimals are noise. */
    fun usd(amount: Double): String =
      if (amount >= 1.0) "${'$'}${String.format("%,.2f", amount)}"
      else if (amount > 0.0 && amount < 0.0001) "<${'$'}0.0001"
      else "${'$'}${String.format("%.4f", amount)}"

    fun dbu(amount: Double): String = String.format("%,.4f", amount)

    /** The bundled table, then the user's `pricing:` block layered on top.
      *
      * Config entries are appended *after* the defaults and the lookup takes the
      * longest key, so a user entry only wins where it is at least as specific --
      * which is the behaviour you want: overriding one model should not silently
      * discard the rest of the table.
      */
    fun load(overrides: Conf?, dbuRateOverride: Double?): Pricing {
      val bundled = bundledConf()
      val fromJar = parse(bundled)
      val fromCfg = overrides?.let { parse(it) } ?: emptyList()

      // A user key that exactly matches a bundled one must replace it, not sit
      // beside it -- equal length makes `find` a coin toss otherwise.
      val userKeys = fromCfg.map { it.key }.toSet()
      val merged = fromJar.filterNot { p -> userKeys.contains(p.key) } + fromCfg

      val rate = dbuRateOverride
        ?: overrides?.double("dbu_rate")
        ?: bundled?.double("dbu_rate")
        ?: 0.07

      return Pricing(merged, rate, bundled?.str("verified"))
    }

    private fun bundledConf(): Conf? =
      try {
        val stream = javaClass.getResourceAsStream("/pricing.yaml")
        if (stream == null) null
        else
          try {
            Yaml.loadString(String(stream.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8))
          } finally {
            stream.close()
          }
      } catch (e: Exception) {
        null
      }

    // One function, not the Scala pair. `parse(Option[Conf])` and
    // `parse(Conf)` are distinct signatures on the JVM only because Scala
    // boxes the Option; `Conf?` and `Conf` erase to the same thing.
    private fun parse(conf: Conf?): List<ModelPrice> =
      if (conf == null) emptyList()
      else section(conf, "dbu_per_million", inDbu = true) + section(conf, "usd_per_million", inDbu = false)

    private fun section(conf: Conf, key: String, inDbu: Boolean): List<ModelPrice> =
      conf.sub(key).let { models ->
        if (models == null) emptyList()
        else
          models.keys.flatMap { name ->
            models.sub(name)?.let { entry ->
              // A missing `output` is legitimate -- an embedding endpoint generates
              // nothing -- so it defaults to zero. A missing `input` is a broken
              // entry and is dropped rather than costed at nothing.
              entry.double("input")?.let { input ->
                listOf(
                  ModelPrice(
                    name.trim().lowercase(),
                    input,
                    entry.double("output") ?: 0.0,
                    inDbu
                  )
                )
              } ?: emptyList()
            } ?: emptyList()
          }
      }
  }
}

/** A 4-tuple for cost calculation: (inUsd, outUsd, inDbu, outDbu).
 * Replaces Scala's (Double, Double, Double, Double) tuple. */
data class CostQuad(val inUsd: Double, val outUsd: Double, val inDbu: Double, val outDbu: Double)
