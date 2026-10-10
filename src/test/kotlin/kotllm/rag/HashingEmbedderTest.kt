package kotllm.rag

import kotlin.test.Test
import kotlin.test.assertFailsWith
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import io.kotest.matchers.doubles.plusOrMinus

class HashingEmbedderTest {

  private val e = HashingEmbedder()

  private fun norm(v: FloatArray): Double =
    kotlin.math.sqrt(v.sumOf { it.toDouble() * it.toDouble() })

  private fun cos(a: String, b: String, enc: HashingEmbedder = e): Double =
    HashingEmbedder.cosine(enc.encode(a), enc.encode(b))

  private fun normalised(s: String): String = String(HashingEmbedder.normalise(s))

  // ---- the vector itself -------------------------------------------------

  @Test fun `vectors have the configured dimension`() {
    HashingEmbedder(256).encode("hello world").size shouldBe 256
    HashingEmbedder(1024).encode("hello world").size shouldBe 1024
  }

  @Test fun `vectors are unit length, so cosine is a dot product`() {
    // VectorIndex.search relies on this: it skips normalising per row.
    listOf("hello world", "a much longer piece of prose about rate limiting and windows",
        "class Compactor { def compact(): Unit = () }").forEach { s ->
      norm(e.encode(s)) shouldBe (1.0 plusOrMinus 1e-5)
    }
  }

  @Test fun `encoding is stateless, so two instances agree`() {
    // No IDF table, no fitted state -- a fresh encoder must reproduce vectors
    // written by an earlier one, or every reload would silently mismatch.
    HashingEmbedder(512, 3, 5).encode("declineToolCalls").toList() shouldBe
      HashingEmbedder(512, 3, 5).encode("declineToolCalls").toList()
  }

  @Test fun `text with no usable features encodes to zeros rather than failing`() {
    listOf("", "   ", "a b c", "!!! ??? ...", "of to in").forEach { s ->
      e.encode(s).all { it == 0.0f } shouldBe true
    }
  }

  // ---- what it is for ----------------------------------------------------

  @Test fun `morphological variants are closer than unrelated text`() {
    // The entire reason for character n-grams over hashed words: BM25 cannot
    // connect `compaction` to `Compactor`, and this must.
    (cos("compaction", "Compactor") > cos("compaction", "rate limiter window")) shouldBe true
    (cos("parsing", "parser") > cos("parsing", "rate limiter window")) shouldBe true
    (cos("tokenise", "tokenizer") > cos("tokenise", "rate limiter window")) shouldBe true
  }

  @Test fun `a typo stays close to the identifier it meant`() {
    (cos("declineToolCalls", "declineToolCals") > 0.8) shouldBe true
    cos("declineToolCalls", "declineToolCalls") shouldBe (1.0 plusOrMinus 1e-5)
  }

  @Test fun `a substring query is close to the identifier containing it`() {
    (cos("ToolCall", "declineToolCalls") > cos("ToolCall", "rate limiter window")) shouldBe true
  }

  @Test fun `unrelated text is not similar`() {
    (cos("kafka partition offsets", "terminal cursor addressing") < 0.3) shouldBe true
  }

  @Test fun `it is honestly not semantic, and the tests say so`() {
    // Recorded as an expectation rather than left implicit: a synonym pair has
    // no character overlap, so it cannot and does not match. Anyone who later
    // makes the tool description promise semantics has to break this test.
    (cos("car", "automobile") < 0.2) shouldBe true
    (cos("cat", "feline") < 0.2) shouldBe true
  }

  // ---- normalisation -----------------------------------------------------

  @Test fun `normalisation lowercases and reduces punctuation to single spaces`() {
    normalised("Hello,,,   World!!!") shouldBe " hello world "
  }

  @Test fun `normalisation pads the ends, so edge characters get boundary n-grams`() {
    normalised("parse") shouldBe " parse "
  }

  @Test fun `underscores stay inside a token, so snake_case spans the underscore`() {
    normalised("parse_request") shouldBe " parse_request "
  }

  @Test fun `tokens shorter than three characters are dropped`() {
    // They cannot carry a 3-gram, and without IDF they would be pure noise.
    normalised("a bc parse de f") shouldBe " parse "
  }

  @Test fun `common function words are dropped`() {
    normalised("the rate limiter and the window") shouldBe " rate limiter window "
  }

  @Test fun `normalising text with nothing usable yields an empty array`() {
    HashingEmbedder.normalise("a b !! ??").isEmpty() shouldBe true
  }

  // ---- parameters and identity -------------------------------------------

  @Test fun `the id names the encoder and every parameter that changes output`() {
    // Anything that changes `encode` must change `id`, or stale vectors get
    // compared against new queries and rank nonsense.
    HashingEmbedder(512, 3, 5).id shouldBe "hashing-v1:d512:g3-5"
    HashingEmbedder(1024, 3, 5).id shouldNotBe HashingEmbedder(512, 3, 5).id
    HashingEmbedder(512, 4, 5).id shouldNotBe HashingEmbedder(512, 3, 5).id
    HashingEmbedder(512, 3, 6).id shouldNotBe HashingEmbedder(512, 3, 5).id
  }

  @Test fun `a different n-gram range really does produce different vectors`() {
    // Guards the id claim above from being decorative.
    HashingEmbedder(512, 3, 3).encode("declineToolCalls").toList() shouldNotBe
      HashingEmbedder(512, 4, 4).encode("declineToolCalls").toList()
  }

  @Test fun `an impossible n-gram range is refused at construction`() {
    assertFailsWith<IllegalArgumentException> { HashingEmbedder(512, 5, 3) }
    assertFailsWith<IllegalArgumentException> { HashingEmbedder(0) }
  }

  @Test fun `a config with the range swapped is normalised rather than fatal`() {
    // A bad config should degrade, not take down the REPL on a require().
    val cfg = RagConfig.default.copy(charGramMin = 5, charGramMax = 3)
    val h = cfg.embedder as HashingEmbedder
    Pair(h.minGram, h.maxGram) shouldBe Pair(3, 5)
  }

  @Test fun `embedding mode off means no encoder at all`() {
    RagConfig.default.copy(embedding = EmbeddingMode.None).embedder shouldBe null
    RagConfig.default.copy(embedding = EmbeddingMode.Hashing).embedder shouldNotBe null
  }

  @Test fun `embedding mode parses its spellings and rejects nonsense`() {
    EmbeddingMode.parse("none") shouldBe EmbeddingMode.None
    EmbeddingMode.parse("off") shouldBe EmbeddingMode.None
    EmbeddingMode.parse("bm25") shouldBe EmbeddingMode.None
    EmbeddingMode.parse("hashing") shouldBe EmbeddingMode.Hashing
    EmbeddingMode.parse("LOCAL") shouldBe EmbeddingMode.Hashing
    EmbeddingMode.parse("word2vec") shouldBe null
  }

  // ---- cosine ------------------------------------------------------------

  @Test fun `cosine of mismatched lengths is zero rather than an exception`() {
    HashingEmbedder.cosine(floatArrayOf(1.0f), floatArrayOf(1.0f, 0.0f)) shouldBe 0.0
  }

  @Test fun `signed hashing lets collisions cancel instead of always reinforcing`() {
    // With unsigned counts every vector would be non-negative, and unrelated
    // long documents would all correlate positively.
    val v = e.encode("a long document about limiters, windows, chunks and tokens")
    v.any { it > 0.0f } shouldBe true
    v.any { it < 0.0f } shouldBe true
  }
}
