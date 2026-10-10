package kotllm.rag

import kotllm.*

/** Turns text into a unit-length dense vector.
  *
  * `id` is stored in the index metadata and compared on load. Vectors from a
  * different encoder are numerically comparable and semantically meaningless --
  * silent garbage -- so any change to what `encode` produces must change `id`.
  */
interface Embedder {

  /** Identifies the encoder *and its parameters*, for invalidation. */
  val id: String

  val dimensions: Int

  /** A unit-length vector, or all zeros for text with no usable features. */
  fun encode(text: String): FloatArray
}

/** A dense encoder built entirely from code: no model file, no service call,
  * no dependency.
  *
  * Character n-grams hashed into a fixed number of buckets, sub-linear term
  * frequency, L2 normalised. Cosine over the result measures shared character
  * sequences rather than shared whole tokens.
  *
  * **Character n-grams, not words.** A hashed bag of words would be a worse
  * reimplementation of BM25, and fusing two retrievers that agree by
  * construction adds nothing. N-grams match what exact-token search cannot:
  * morphology (`compaction` retrieves `Compactor`), typos, substring queries
  * (`ToolCall` reaches `declineToolCalls`), and compound identifiers.
  *
  * **It is not semantic and must never be described as such.** No synonyms:
  * `car` will not retrieve `automobile`. See docs/rag.md.
  *
  * **Deliberately stateless** -- no IDF table, no corpus statistics. Query and
  * document vectors are then consistent by construction, incremental indexing
  * needs no invalidation, and BM25 is already the IDF-weighted half, which is
  * what keeps the two complementary rather than near-duplicate.
  *
  * @param dimensions  bucket count; one vector costs `dimensions * 4` bytes.
  * @param minGram     shortest character n-gram, inclusive.
  * @param maxGram     longest character n-gram, inclusive.
  */
class HashingEmbedder(
    override val dimensions: Int = HashingEmbedder.DefaultDimensions,
    val minGram: Int = 3,
    val maxGram: Int = 5
) : Embedder {

  init {
    require(dimensions > 0, { "dimensions must be positive" })
    require(minGram >= 1 && maxGram >= minGram, { "bad n-gram range $minGram..$maxGram" })
  }

  /** Versioned, so a change to the feature extraction invalidates old vectors
    * rather than being compared against them. */
  override val id: String = "${HashingEmbedder.Name}:d$dimensions:g$minGram-$maxGram"

  override fun encode(text: String): FloatArray {
    val vec = FloatArray(dimensions)
    if (text.isEmpty()) return vec

    val normalised = HashingEmbedder.normalise(text)
    if (normalised.isEmpty()) return vec

    // Raw counts first, so term frequency can be damped before normalising.
    // Without damping, a chunk repeating one identifier fifty times points
    // almost entirely along that identifier's buckets.
    val counts = IntArray(dimensions)
    val chars = normalised
    val len = chars.size

    var n = minGram
    while (n <= maxGram) {
      if (len >= n) {
        var start = 0
        val last = len - n
        while (start <= last) {
          // The n-gram is hashed straight out of the char array. Allocating a
          // String per n-gram would mean tens of millions of short-lived
          // strings when indexing a real tree.
          val h = HashingEmbedder.hashRange(chars, start, start + n, HashingEmbedder.BucketSeed + n)
          val bucket = h.mod(dimensions)
          // A separate hash bit decides the sign, so colliding features cancel
          // in expectation instead of always reinforcing each other. This is
          // what keeps a small bucket count usable.
          val signed = HashingEmbedder.hashRange(chars, start, start + n, HashingEmbedder.SignSeed)
          counts[bucket] += (if ((signed and 1) == 0) 1 else -1)
          start += 1
        }
      }
      n += 1
    }

    var i = 0
    var normSq = 0.0
    while (i < dimensions) {
      val c = counts[i]
      if (c != 0) {
        // Sub-linear in the count, sign preserved.
        val w = kotlin.math.sign(c.toDouble()) * (1.0 + kotlin.math.ln(kotlin.math.abs(c).toDouble()))
        vec[i] = w.toFloat()
        normSq += w * w
      }
      i += 1
    }

    if (normSq > 0.0) {
      val inv = (1.0 / kotlin.math.sqrt(normSq)).toFloat()
      i = 0
      while (i < dimensions) {
        vec[i] = vec[i] * inv
        i += 1
      }
    }
    return vec
  }

  companion object {

    val Name = "hashing-v1"

    /** Measured rather than guessed. A sweep over this repository put the margin
      * between wanted matches and nonsense at +0.056 (512), +0.067 (2048) and
      * +0.047 (8192): raising the bucket count sixteenfold does not separate
      * them, because the overlap is real shared character n-grams rather than
      * hash collisions. 2048 wins on margin at a fifth of the disk of 8192. */
    val DefaultDimensions = 2048


    /** Two seeds, so the bucket and the sign are independent. Folding the n-gram
      * length into the bucket seed keeps `abc` from colliding with the `abc`
      * prefix of a 4-gram for structural rather than random reasons. */
    private val BucketSeed: Int = 0x9e3779b9.toInt()

    private val SignSeed: Int = 0x85ebca6b.toInt()


    /** Lowercases, reduces punctuation and whitespace runs to one space, and
      * drops tokens that cannot carry a useful n-gram.
      *
      * Tokens under three characters and common function words go first. This is
      * where the absence of IDF is compensated: without it, n-grams like `the`
      * and `ing` would appear in nearly every vector and consume a share of a
      * unit-length budget that should distinguish one chunk from another. `_` is
      * kept inside tokens, so `parse_request` stays one token.
      *
      * Spaces survive into the n-grams on purpose: they carry word-boundary
      * information, so `parse` scores higher against `parse` than against the
      * middle of `superparsen`. */
    internal fun normalise(text: String): CharArray {
      val lower = text.lowercase()
      val out = StringBuilder(lower.length + 2)
      val token = StringBuilder(32)

      fun flush() {
        if (token.length >= 3) {
          val t = token.toString()
          if (!Stopgrams.contains(t)) {
            if (out.isNotEmpty()) out.append(' ')
            out.append(t)
          }
        }
        token.setLength(0)
      }

      var i = 0
      while (i < lower.length) {
        val c = lower[i]
        if ((c >= 'a' && c <= 'z') || (c >= '0' && c <= '9') || c == '_') token.append(c)
        else flush()
        i += 1
      }
      flush()

      return if (out.isEmpty()) CharArray(0)
        else {
          // Padded, so the first and last real characters also sit next to a
          // boundary and produce the same n-grams they would mid-string.
          val s = " " + out.toString() + " "
          s.toCharArray()
        }
    }


    /** The same set BM25 drops, plus a few that only matter once text is cut into
      * character n-grams. Kept small for the reason given in `Bm25.Stopwords`:
      * guessing which words are uninformative is usually worse than letting the
      * statistics decide. Here there are no statistics, so a short list earns
      * its place -- but it stays a short list. */
    private val Stopgrams: Set<String> =

      setOf("the", "and", "that", "with", "this", "from", "have", "has", "was", "are", "were",
          "but", "you", "your", "its", "for", "not", "can", "will", "would", "should", "could",
          "there", "their", "then", "than", "them", "they", "what", "when", "which", "while",
          "into", "onto", "over", "under", "also", "just", "only", "such", "same", "each",
          "any", "all", "one", "two", "may", "might", "must", "been", "being", "does", "did")


    /** FNV-1a over a character range, finished with the murmur3 avalanche.
      *
      * Hand-rolled because it must hash a *range* of an existing array. FNV-1a
      * alone has poor high-bit distribution, which shows up immediately as
      * uneven bucket occupancy once the bucket count is a power of two; the
      * avalanche step fixes that and costs four instructions.
      */
    internal fun hashRange(cs: CharArray, from: Int, until: Int, seed: Int): Int {
      var h = seed
      var i = from
      while (i < until) {
        h = h xor cs[i].code
        h = h * 0x01000193
        i += 1
      }
      h = h xor (h ushr 16)
      h = h * 0x85ebca6b.toInt()
      h = h xor (h ushr 13)
      h = h * 0xc2b2ae35.toInt()
      h = h xor (h ushr 16)
      return h
    }


    /** Cosine similarity of two vectors the encoder produced.
      *
      * A plain dot product, because `encode` returns unit-length vectors. Kept
      * here rather than inlined at the call sites so the assumption is stated
      * once.
      */
    fun cosine(a: FloatArray, b: FloatArray): Double {
      if (a.size != b.size) return 0.0
      var sum = 0.0
      var i = 0
      while (i < a.size) {
        sum += a[i].toDouble() * b[i].toDouble()
        i += 1
      }
      return sum
    }
  }
}

/** Which encoder an index uses. */
sealed interface EmbeddingMode {

  /** BM25 only. No vectors are built and none are read. */
  data object None : EmbeddingMode

  /** The local character n-gram encoder. */
  data object Hashing : EmbeddingMode

  companion object {
    fun parse(s: String): EmbeddingMode? = when (s.trim().lowercase()) {
      "none", "off", "false", "bm25" -> None
      "hashing", "local", "on", "true" -> Hashing
      else -> null
    }

    fun name(m: EmbeddingMode): String = when (m) {
      None -> "none"
      Hashing -> "hashing"
    }
  }
}
