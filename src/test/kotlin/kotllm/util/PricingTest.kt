package kotllm.util

import kotlin.test.Test
import io.kotest.matchers.shouldBe
import io.kotest.matchers.doubles.plusOrMinus

/** Token pricing: the lookup rule, the DBU conversion, and the refusals.
 *
 * The lookup rule is the part worth pinning down, and it failed twice before
 * settling. Exact keys miss `databricks-qwen35-122b-a10b` against a published
 * "Qwen 3.5 122B"; longest-prefix then misses
 * `databricks-deepseek-v4-1-flash` against "DeepSeek V4 Flash", where the
 * point release is spliced into the middle. Both real configs. The rule is now
 * an ordered subsequence of whole dash-separated segments.
 */
class PricingTest {

    private fun turn(model: String, `in`: Int, out: Int, index: Int = 1): TurnMetrics =
        TurnMetrics(index, 0L, model, `in`, out, 0, 1000L, 100L, emptyList(), truncated = false, aborted = false)

    private val bundled = Pricing.load(null, null)

    // ---- the bundled table -------------------------------------------------

    @Test fun `the bundled table loads and carries the model this project runs on`() {
        bundled.isEmpty shouldBe false
        bundled.dbuRate shouldBe 0.07
        (bundled.verified != null) shouldBe true
    }

    @Test fun `a served endpoint name matches the published price`() {
        // The case that rules out exact-match lookup entirely.
        val found = bundled.find("databricks-qwen35-122b-a10b")
        found!!.input shouldBe 3.143
        found.output shouldBe 31.429
        found.inDbu shouldBe true
    }

    @Test fun `the most specific match wins, so a precise key overrides a general one`() {
        val pricing = Pricing(
            listOf(ModelPrice("databricks-qwen35", 1.0, 2.0, inDbu = true),
                   ModelPrice("databricks-qwen35-122b", 3.0, 4.0, inDbu = true)),
            0.07, null
        )
        // Both keys match; the one with more segments is chosen.
        pricing.find("databricks-qwen35-122b-a10b")!!.input shouldBe 3.0
        // Only the general one matches here, so it is used.
        pricing.find("databricks-qwen35-9b")!!.input shouldBe 1.0
    }

    @Test fun `a partial segment is not a match, which is stricter than prefix was`() {
        // Worth asserting because it is the deliberate cost of whole-segment
        // comparison: `databricks-qwen` does NOT cover `databricks-qwen3-80b`, so
        // a family-wide key has to be spelled as a real segment.
        val pricing = Pricing(listOf(ModelPrice("databricks-qwen", 1.0, 2.0, inDbu = true)), 0.07, null)
        pricing.find("databricks-qwen3-80b") shouldBe null
        (pricing.find("databricks-qwen") != null) shouldBe true
    }

    @Test fun `a version segment spliced into the middle of a name still matches`() {
        // The second real failure, and the one that killed prefix matching: a live
        // config named databricks-deepseek-v4-1-flash against a published
        // "DeepSeek V4 Flash". Prefix cannot see past the inserted `1`.
        val found = bundled.find("databricks-deepseek-v4-1-flash")
        found!!.input shouldBe 2.000
        found.output shouldBe 4.000
    }

    @Test fun `segments compare whole, so near-miss sizes and versions do not match`() {
        // The looseness has to stop somewhere, and this is where.
        val pricing = Pricing(
            listOf(ModelPrice("databricks-gpt-oss-20b", 1.0, 1.0, inDbu = true),
                   ModelPrice("databricks-glm-5-2", 2.0, 2.0, inDbu = true)),
            0.07, null
        )
        pricing.find("databricks-gpt-oss-120b") shouldBe null
        pricing.find("databricks-glm-5-3") shouldBe null
    }

    @Test fun `the subsequence rule is order-sensitive, not a bag of words`() {
        Pricing.isSubsequence(listOf("a", "b"), listOf("a", "x", "b")) shouldBe true
        Pricing.isSubsequence(listOf("a", "b"), listOf("b", "a")) shouldBe false
        Pricing.isSubsequence(listOf("a", "a"), listOf("a")) shouldBe false
        Pricing.isSubsequence(emptyList(), listOf("a")) shouldBe true
    }

    @Test fun `a dot is a separator, because Azure deployments are named gpt-4dot1-mini`() {
        Pricing.segments("gpt-4.1-mini") shouldBe listOf("gpt", "4", "1", "mini")
        (bundled.find("gpt-4.1-mini-2026-01-01") != null) shouldBe true
    }

    @Test fun `lookup is case-insensitive and tolerant of surrounding whitespace`() {
        (bundled.find("  DATABRICKS-QWEN35-122B-A10B  ") != null) shouldBe true
    }

    @Test fun `an unknown model matches nothing rather than falling back`() {
        // Costing an unknown model at zero would be the worst failure available:
        // a session that looks free.
        bundled.find("some-model-nobody-priced") shouldBe null
        bundled.find("") shouldBe null
        bundled.find(null) shouldBe null
    }

    // ---- the arithmetic ----------------------------------------------------

    @Test fun `DBU prices convert to dollars through the rate`() {
        // 1M in at 3.143 DBU = 3.143 DBU = dollar0.22001 at dollar0.07.
        val quad = bundled.costOf("databricks-qwen35-122b-a10b", 1_000_000L, 1_000_000L)!!

        quad.inDbu shouldBe (3.143 plusOrMinus 0.0001)
        quad.outDbu shouldBe (31.429 plusOrMinus 0.0001)
        quad.inUsd shouldBe ((3.143 * 0.07) plusOrMinus 0.0001)
        quad.outUsd shouldBe ((31.429 * 0.07) plusOrMinus 0.0001)
    }

    @Test fun `a USD-priced model needs no conversion and reports no DBUs`() {
        val quad = bundled.costOf("gpt-4o", 1_000_000L, 1_000_000L)!!
        quad.inUsd shouldBe (2.50 plusOrMinus 0.0001)
        quad.outUsd shouldBe (10.00 plusOrMinus 0.0001)
        quad.inDbu shouldBe 0.0
        quad.outDbu shouldBe 0.0
    }

    @Test fun `a changed dbu rate moves every DBU-priced model and no USD one`() {
        val cheap = Pricing.load(null, 0.035)
        cheap.dbuRate shouldBe 0.035
        cheap.costOf("databricks-qwen35-122b", 1_000_000L, 0L)!!.inUsd shouldBe
            ((3.143 * 0.035) plusOrMinus 0.0001)
        cheap.costOf("gpt-4o", 1_000_000L, 0L)!!.inUsd shouldBe (2.50 plusOrMinus 0.0001)
    }

    // ---- session totals ----------------------------------------------------

    @Test fun `a session is costed per turn, at each turn's own model`() {
        // Why TurnMetrics carries the model: a /model switch mid-session would
        // otherwise price the whole thing at whichever was selected last.
        val spend = bundled.breakdown(
            listOf(turn("databricks-qwen35-122b-a10b", 1_000_000, 0, 1),
                   turn("gpt-4o", 1_000_000, 0, 2))
        )

        spend.inputTokens shouldBe 2_000_000L
        spend.inputUsd shouldBe ((3.143 * 0.07 + 2.50) plusOrMinus 0.0001)
        // Only the Databricks half contributes DBUs.
        spend.inputDbu shouldBe (3.143 plusOrMinus 0.0001)
        spend.anyUnpriced shouldBe false
    }

    @Test fun `unpriced turns are named and their tokens kept out of the total`() {
        val spend = bundled.breakdown(
            listOf(turn("databricks-qwen35-122b", 1_000_000, 0, 1),
                   turn("mystery-model", 500_000, 250_000, 2),
                   turn("mystery-model", 100_000, 0, 3))
        )

        spend.anyUnpriced shouldBe true
        spend.unpriced.keys shouldBe setOf("mystery-model")
        spend.unpriced["mystery-model"] shouldBe (600_000L to 250_000L)
        // The priced total counts only what was actually costed.
        spend.inputTokens shouldBe 1_000_000L
        spend.unpricedTokens shouldBe 850_000L
    }

    @Test fun `an empty session costs nothing and claims nothing`() {
        val spend = bundled.breakdown(emptyList())
        spend.totalUsd shouldBe 0.0
        spend.anyPriced shouldBe false
        spend.anyUnpriced shouldBe false
    }

    @Test fun `pricing disabled reports everything as unpriced, never as free`() {
        val spend = Pricing.disabled.breakdown(listOf(turn("databricks-qwen35-122b", 1_000_000, 0)))
        spend.totalUsd shouldBe 0.0
        spend.anyUnpriced shouldBe true
    }

    // ---- overrides ---------------------------------------------------------

    @Test fun `a config entry replaces a bundled one of the same name`() {
        // Equal-length keys would otherwise make find a coin toss.
        val overridden = Pricing.load(
            Yaml.loadString(
                "dbu_per_million:\n  databricks-qwen35-122b:\n    input: 99.0\n    output: 1.0\n"),
            null
        )
        overridden.find("databricks-qwen35-122b-a10b")!!.input shouldBe 99.0
        // And the rest of the table survives.
        (overridden.find("gpt-4o") != null) shouldBe true
    }

    @Test fun `a config entry can add a model the bundled table never had`() {
        val extended = Pricing.load(
            Yaml.loadString("usd_per_million:\n  my-own-endpoint:\n    input: 1.0\n    output: 3.0\n"),
            null
        )
        extended.find("my-own-endpoint-v2")!!.output shouldBe 3.0
    }

    @Test fun `an entry with no input price is dropped, not costed at nothing`() {
        val broken = Pricing.load(
            Yaml.loadString("usd_per_million:\n  half-entry:\n    output: 3.0\n"), null)
        broken.find("half-entry") shouldBe null
    }

    @Test fun `a missing output price is legitimate, because embeddings generate nothing`() {
        bundled.find("databricks-gte-large-en")!!.output shouldBe 0.0
        bundled.costOf("databricks-gte-large-en", 1_000_000L, 0L)!!.outUsd shouldBe 0.0
    }

    // ---- the whole config.yaml round trip ----------------------------------

    @Test fun `a pricing block in a real config yaml overrides, extends and re-rates`() {
        // The outer nesting, which the tests above skip by handing load the
        // sub-block directly. This is the shape a user actually writes.
        val config = kotllm.config.Config.fromConf(Yaml.loadString(
            """model: databricks-qwen35-122b-a10b
              |dbu_rate: 0.035
              |
              |pricing:
              |  dbu_per_million:
              |    databricks-qwen35-122b:
              |      input: 99.0
              |      output: 1.0
              |    databricks-my-private-endpoint:
              |      input: 5.0
              |      output: 10.0
              |  usd_per_million:
              |    my-azure-deployment:
              |      input: 1.25
              |      output: 5.0
              |""".trimMargin()))

        val pricing = config.pricing

        // A top-level dbu_rate re-rates every DBU-priced model.
        pricing.dbuRate shouldBe 0.035
        // An existing key is replaced, not duplicated.
        pricing.find("databricks-qwen35-122b-a10b")!!.input shouldBe 99.0
        // A new Databricks endpoint, matched with its own variant suffix.
        pricing.find("databricks-my-private-endpoint-v2")!!.output shouldBe 10.0
        // A USD entry ignores dbu_rate.
        pricing.find("my-azure-deployment")!!.inDbu shouldBe false
        // And the rest of the bundled table is still there.
        pricing.find("databricks-llama-4-maverick")!!.input shouldBe 7.143

        pricing.costOf("databricks-qwen35-122b-a10b", 1_000_000L, 0L)!!.inUsd shouldBe
            ((99.0 * 0.035) plusOrMinus 0.0001)
    }

    @Test fun `the key must be the model name, which is how a copied example goes wrong`() {
        // Reported from a real config: the usd_per_million example was pasted
        // verbatim, so the entry was keyed my-azure-deployment while the model
        // was gpt-5.6-luna. Nothing matched, and the footer said unpriced.
        val pasted = kotllm.config.Config.fromConf(Yaml.loadString(
            """model: gpt-5.6-luna
              |pricing:
              |  usd_per_million:
              |    my-azure-deployment:
              |      input: 0.20
              |      output: 1.20
              |""".trimMargin()))
        pasted.pricing.find("gpt-5.6-luna") shouldBe null

        val keyed = kotllm.config.Config.fromConf(Yaml.loadString(
            """model: gpt-5.6-luna
              |pricing:
              |  usd_per_million:
              |    gpt-5.6-luna:
              |      input: 0.20
              |      output: 1.20
              |""".trimMargin()))
        val found = keyed.pricing.find("gpt-5.6-luna")
        found!!.input shouldBe 0.20
        found.inDbu shouldBe false
        // A dotted version in the key is split like any other separator, so the
        // deployment's own suffixes still match.
        (keyed.pricing.find("gpt-5.6-luna-2026-08-01") != null) shouldBe true
    }

    @Test fun `pricing_enabled false disables it from config, and show_cost is separate`() {
        val off = kotllm.config.Config.fromConf(Yaml.loadString("pricing_enabled: false\n"))
        off.pricing.isEmpty shouldBe true
        // Chrome and pricing are independent switches.
        kotllm.config.Config.fromConf(Yaml.loadString("show_cost: false\n")).showCost shouldBe false
        kotllm.config.Config.fromConf(Yaml.loadString("model: m\n")).showCost shouldBe true
    }

    @Test fun `dbu_rate can also live inside the pricing block`() {
        val nested = kotllm.config.Config.fromConf(
            Yaml.loadString("pricing:\n  dbu_rate: 0.10\n"))
        nested.pricing.dbuRate shouldBe 0.10
    }

    // ---- formatting --------------------------------------------------------

    @Test fun `small amounts keep four decimals, because cents would erase them`() {
        // dollar0.0087 rounded to cents reads as dollar0.01 -- ten times the real figure.
        Pricing.usd(0.0087) shouldBe "\$0.0087"
        Pricing.usd(0.22001) shouldBe "\$0.2200"
        Pricing.usd(12.3456) shouldBe "\$12.35"
        Pricing.usd(0.0) shouldBe "\$0.0000"
        Pricing.usd(0.00001) shouldBe "<\$0.0001"
    }

    @Test fun `a four-figure session is grouped, so it cannot be misread by an order of magnitude`() {
        Pricing.usd(1234.5) shouldBe "\$1,234.50"
    }
}
