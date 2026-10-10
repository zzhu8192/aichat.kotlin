package kotllm.rag

import kotllm.*

/** A scored hit, with the rank assigned by whichever retriever produced it. */
data class Hit(val chunkIndex: Int, val score: Double)

/** BM25 lexical search over an inverted index.
  *
  * Hand-rolled rather than pulled from a library, because the formula is
  * closed-form and small, and this project treats every dependency as widening
  * the classpath handed to the LLM. Apache Lucene is the upgrade path if real
  * analysis is ever needed -- stemming, phrase queries, fuzzy matching, or a
  * corpus past a few hundred thousand chunks.
  *
  * BM25 is the lexical half of hybrid search: it guarantees precision on exact
  * tokens, identifiers and error codes, which is exactly where dense vectors
  * blur. `ERR_4032` and a function name are found here, not by similarity.
  */
class Bm25(val k1: Double = 1.2, val b: Double = 0.75) {

  /** documents, as token sequences, in chunk order. */
  private val lengths = mutableListOf<Int>()
  private val postings = mutableMapOf<String, MutableList<Pair<Int, Int>>>()
  private var totalLen = 0L

  val size: Int get() = lengths.size

  val isEmpty: Boolean get() = lengths.isEmpty()

  private val averageLength: Double get() = if (lengths.isEmpty()) 0.0 else totalLen.toDouble() / lengths.size

  /** Adds a document, returning its index. */
  fun add(text: String): Int {
    val terms = Bm25.tokenise(text)
    val index = lengths.size
    lengths.add(terms.size)
    totalLen += terms.size

    // Term frequencies collapsed up front, so postings hold one entry per
    // (term, document) rather than one per occurrence.
    val counts = mutableMapOf<String, Int>()
    terms.forEach { t -> counts[t] = (counts[t] ?: 0) + 1 }
    counts.forEach { (term, tf) ->
      postings.getOrPut(term) { mutableListOf() }.add(Pair(index, tf))
    }
    return index
  }

  fun addAll(texts: List<String>) {
    texts.forEach(::add)
  }

  /** Ranked hits, best first.
    *
    * Scores only documents containing at least one query term, which is what
    * makes this fast: the posting lists are the candidate set, not the corpus.
    */
  fun search(query: String, topK: Int): List<Hit> {
    if (lengths.isEmpty()) return emptyList()
    val terms = Bm25.tokenise(query).distinct()
    if (terms.isEmpty()) return emptyList()

    val n = lengths.size.toDouble()
    val avgLen = averageLength
    val scores = mutableMapOf<Int, Double>()

    terms.forEach { term ->
      postings[term]?.let { plist ->
        val df = plist.size.toDouble()
        // Standard BM25 IDF, with the +1 that keeps it positive for a term
        // appearing in more than half the corpus.
        val idf = kotlin.math.ln(1.0 + (n - df + 0.5) / (df + 0.5))
        plist.forEach { (doc, tf) ->
          val dl = lengths[doc].toDouble()
          val numerator = tf * (k1 + 1.0)
          val denom = tf + k1 * (1.0 - b + b * (if (avgLen > 0) dl / avgLen else 1.0))
          scores[doc] = (scores[doc] ?: 0.0) + idf * (numerator / denom)
        }
      }
    }

    return scores
      .map { (doc, score) -> Hit(doc, score) }
      .sortedWith(compareBy({ -it.score }, { it.chunkIndex }))
      .take(kotlin.math.max(1, topK))
  }

  fun clear() {
    lengths.clear()
    postings.clear()
    totalLen = 0L
  }

  companion object {

    /** Minimal analysis: lowercase, split on non-alphanumerics, drop
      * single characters and stopwords.
      *
      * Deliberately keeps `_` and digits joined, so `ERR_4032`, `snake_case` and
      * `utf8` survive as single terms. That matters more than stemming here,
      * because identifiers are the thing lexical search exists to nail. It also
      * splits camelCase into parts *and* keeps the whole, so `parseRequest`
      * matches a query for `parse` as well as the exact name.
      */
    fun tokenise(text: String): List<String> {
      val raw = text.lowercase().split(Regex("[^a-z0-9_]+")).filter { it.isNotEmpty() }
      val out = mutableListOf<String>()

      raw.forEach { token ->
        if (token.length > 1 && !Stopwords.contains(token)) out.add(token)
        val parts = splitCamel(token)
        if (parts.size > 1) parts.forEach { p -> if (p.length > 1 && !Stopwords.contains(p)) out.add(p) }
      }
      return out
    }


    /** Splits on the original casing before lowercasing loses it. */
    internal fun splitCamel(token: String): List<String> =

      token.split("_").filter { it.isNotEmpty() }


    /** Small and deliberate.
      *
      * An aggressive stoplist hurts code search, where `if`, `for`, `case` and
      * `not` are meaningful terms that a user may well be searching for. Left
      * out on purpose, because IDF already does this job better: a term
      * appearing in nearly every document earns almost no weight, without
      * anyone having to guess in advance which words those are. The list here
      * only covers English function words that add query noise while never
      * being what someone meant to search for.
      */
    private val Stopwords: Set<String> =

      setOf("the", "and", "that", "with", "this", "from", "have", "has", "was", "are", "were",
          "but", "you", "your", "its", "it", "of", "to", "in", "on", "at", "by", "as", "is",
          "an", "be", "or", "a", "i")
  }
}

/** Reciprocal rank fusion.
  *
  * Merges ranked lists using position only, never scores. That is the whole
  * point: BM25 scores and cosine similarities live on different scales with
  * different meanings, so adding or averaging them requires a normalisation
  * that is arbitrary and corpus-dependent. Ranks need none.
  *
  * A document ranked first by either retriever contributes `1/(k+1)`, second
  * `1/(k+2)`, and contributions sum across retrievers -- so something both
  * retrievers like outranks something only one of them loved.
  */
object Fusion {

  /** `k` damps the influence of top ranks; 60 is the value from the original
    * RRF paper and the usual default. */
  fun reciprocalRank(lists: List<List<Hit>>, k: Int = 60, topK: Int = 10): List<Hit> {
    val fused = mutableMapOf<Int, Double>()

    lists.forEach { list ->
      list.forEachIndexed { position, hit ->
        val contribution = 1.0 / (k + position + 1)
        fused[hit.chunkIndex] = (fused[hit.chunkIndex] ?: 0.0) + contribution
      }
    }

    return fused
      .map { (doc, score) -> Hit(doc, score) }
      .sortedWith(compareBy({ -it.score }, { it.chunkIndex }))
      .take(kotlin.math.max(1, topK))
  }
}
