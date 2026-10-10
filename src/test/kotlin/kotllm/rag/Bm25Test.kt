package kotllm.rag

import kotlin.test.Test
import io.kotest.matchers.shouldBe
import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.collections.shouldContainAll
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.doubles.plusOrMinus

class Bm25Test {

  private fun indexed(vararg docs: String): Bm25 {
    val b = Bm25()
    b.addAll(docs.toList())
    return b
  }

  // ---- tokenisation ------------------------------------------------------

  @Test fun `tokenising lowercases and splits on punctuation`() {
    Bm25.tokenise("Hello, World! Parsing.") shouldContainAll listOf("hello", "world", "parsing")
  }

  @Test fun `identifiers survive intact, which is the point of lexical search`() {
    // A dense vector blurs these; BM25 must not.
    val tokens = Bm25.tokenise("Error ERR_4032 raised by parse_request in utf8 mode")
    tokens shouldContain "err_4032"
    tokens shouldContain "parse_request"
    tokens shouldContain "utf8"
  }

  @Test fun `underscore-joined names also yield their parts`() {
    // So a query for `parse` finds `parse_request`.
    val tokens = Bm25.tokenise("parse_request")
    tokens shouldContain "parse_request"
    tokens shouldContain "parse"
    tokens shouldContain "request"
  }

  @Test fun `single characters and common stopwords are dropped`() {
    val tokens = Bm25.tokenise("a the of x in it")
    tokens.shouldBeEmpty()
  }

  @Test fun `code keywords are kept, since they are meaningful terms in source`() {
    val tokens = Bm25.tokenise("if case for match")
    tokens shouldContainAll listOf("if", "case", "for", "match")
  }

  // ---- scoring -----------------------------------------------------------

  @Test fun `an empty index returns nothing`() {
    Bm25().search("anything", 5).shouldBeEmpty()
  }

  @Test fun `a query with no usable terms returns nothing`() {
    indexed("some document text").search("a of the", 5).shouldBeEmpty()
  }

  @Test fun `a term present in one document retrieves that document`() {
    val b = indexed("cats sleep often", "dogs bark loudly", "fish swim quietly")
    val hit = b.search("bark", 5)

    hit.size shouldBe 1
    hit.first().chunkIndex shouldBe 1
  }

  @Test fun `results are ordered best first`() {
    val b = indexed(
      "kafka mentioned once here",
      "kafka kafka kafka repeated many times in this document about kafka"
    )
    b.search("kafka", 5).first().chunkIndex shouldBe 1
  }

  @Test fun `a rare term outranks a common one, via IDF`() {
    // "database" is everywhere and carries little information; "zookeeper"
    // appears once and should dominate a query containing both.
    val b = indexed(
      "database tuning guide",
      "database replication guide",
      "database sharding guide",
      "zookeeper database ensemble guide"
    )
    b.search("zookeeper database", 5).first().chunkIndex shouldBe 3
  }

  @Test fun `length normalisation stops a long document winning on padding alone`() {
    val b = indexed(
      "scala concurrency",
      "scala concurrency " + "filler text ".repeat(200)
    )
    b.search("scala concurrency", 5).first().chunkIndex shouldBe 0
  }

  @Test fun `topK bounds the result size`() {
    val b = indexed(*(1..20).map { i -> "document $i about scala" }.toTypedArray())
    b.search("scala", 5).size shouldBe 5
  }

  @Test fun `only documents containing a query term are scored`() {
    val b = indexed("alpha", "beta", "gamma")
    b.search("alpha gamma", 10).map { it.chunkIndex }.sorted() shouldBe listOf(0, 2)
  }

  @Test fun `scores are positive even for a term in most documents`() {
    // The +1 in the IDF is what prevents a negative score here.
    val b = indexed("common word", "common word", "common word", "rare")
    b.search("common", 5).forEach { (it.score > 0.0) shouldBe true }
  }

  @Test fun `ties break deterministically by index`() {
    val b = indexed("same text", "same text", "same text")
    b.search("same", 5).map { it.chunkIndex } shouldBe listOf(0, 1, 2)
  }

  @Test fun `clear empties the index`() {
    val b = indexed("something")
    b.clear()
    b.isEmpty shouldBe true
    b.search("something", 5).shouldBeEmpty()
  }

  // ---- fusion ------------------------------------------------------------

  @Test fun `fusion uses rank position, not score magnitude`() {
    // BM25 scores and cosine similarities are on incomparable scales. A
    // retriever reporting huge numbers must not thereby dominate.
    val lexical = listOf(Hit(1, 14.2), Hit(2, 9.1))
    val dense = listOf(Hit(2, 0.83), Hit(1, 0.81))

    val fused = Fusion.reciprocalRank(listOf(lexical, dense))
    // Doc 1 is 1st and 2nd; doc 2 is 2nd and 1st. Symmetric, so order falls to
    // the index tiebreak, and neither is decided by the raw numbers.
    fused.map { it.chunkIndex }.toSet() shouldBe setOf(1, 2)
    fused.first().score shouldBe (fused[1].score plusOrMinus 1e-9)
  }

  @Test fun `a document both retrievers rank highly outranks one only either loved`() {
    val lexical = listOf(Hit(10, 5.0), Hit(20, 4.0))
    val dense = listOf(Hit(10, 0.9), Hit(30, 0.8))

    Fusion.reciprocalRank(listOf(lexical, dense)).first().chunkIndex shouldBe 10
  }

  @Test fun `fusion of a single list preserves its order`() {
    val only = listOf(Hit(5, 1.0), Hit(6, 0.5), Hit(7, 0.1))
    Fusion.reciprocalRank(listOf(only)).map { it.chunkIndex } shouldBe listOf(5, 6, 7)
  }

  @Test fun `a larger k flattens the advantage of the top rank`() {
    val lists = listOf(listOf(Hit(1, 1.0), Hit(2, 1.0)))
    val tight = Fusion.reciprocalRank(lists, k = 1)
    val flat = Fusion.reciprocalRank(lists, k = 1000)

    ((tight[0].score / tight[1].score) > (flat[0].score / flat[1].score)) shouldBe true
  }

  @Test fun `fusion bounds its output and tolerates empty input`() {
    Fusion.reciprocalRank(emptyList()).shouldBeEmpty()
    Fusion.reciprocalRank(listOf(emptyList(), emptyList())).shouldBeEmpty()
    Fusion.reciprocalRank(listOf((1..30).map { i -> Hit(i, 1.0) }), topK = 4).size shouldBe 4
  }
}
