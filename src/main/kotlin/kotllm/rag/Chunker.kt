package kotllm.rag

import kotllm.*

/** One indexed piece of a file.
  *
  * Carries its line range so a retrieval result can cite `path:12-48` and the
  * user can open it and check. Retrieval without citations asks to be trusted;
  * with them it can be verified.
  */
data class Chunk(
    val path: String,
    val lineStart: Int,
    val lineEnd: Int,
    val text: String,
    val language: String
) {
  val citation: String get() = "$path:$lineStart-$lineEnd"
}

/** How aggressively to split, by corpus type. */
sealed interface ChunkProfile {

  /** Pinpoint facts. Small chunks minimise semantic dilution. */
  data object Faq : ChunkProfile

  /** One or two complete paragraphs. The general default. */
  data object Docs : ChunkProfile

  /** Legal and academic text, where a clause needs its surrounding
    * definitions to not be misread. */
  data object Longform : ChunkProfile

  /** Structure-aware: split at declarations, never mid-body. */
  data object Code : ChunkProfile

  /** Pick per file from its extension. */
  data object Auto : ChunkProfile

  companion object {

    fun parse(s: String): ChunkProfile? = when (s.trim().lowercase()) {
      "faq" -> Faq
      "docs" -> Docs
      "longform" -> Longform
      "code" -> Code
      "auto" -> Auto
      else -> null
    }


    fun name(p: ChunkProfile): String = when (p) {
      Faq -> "faq"
      Docs -> "docs"
      Longform -> "longform"
      Code -> "code"
      Auto -> "auto"
    }


    /** Target size and overlap in *tokens*, from the published guidance. */
    fun sizing(p: ChunkProfile): Pair<Int, Int> = when (p) {
      Faq -> Pair(256, 40)
      Docs -> Pair(512, 80)
      Longform -> Pair(1024, 150)
      Code -> Pair(400, 0) // by block; overlap is meaningless across definitions
      Auto -> Pair(512, 80)
    }
  }
}

data class ChunkerConfig(
    val profile: ChunkProfile,
    /** Overrides the profile target, in tokens. */
    val chunkTokens: Int?,
    val overlapTokens: Int?,
    /** Token counts are approximate: kotllm has no tokenizer, only a ratio.
      * Prose and code differ enough to be worth separate figures. */
    val charsPerTokenProse: Double,
    val charsPerTokenCode: Double
) {
  companion object {
    val default: ChunkerConfig =

      ChunkerConfig(ChunkProfile.Auto, null, null, charsPerTokenProse = 4.0, charsPerTokenCode = 3.0)
  }
}

/** Splits files into chunks.
  *
  * Two strategies:
  *
  *   - **Prose** uses recursive separators -- paragraph, line, sentence, word --
  *     so paragraphs stay intact rather than being cut every N characters.
  *   - **Code** splits at declaration boundaries while tracking brace depth and
  *     indentation, so a function body is never cut in half.
  *
  * Deliberately *not* AST-based: real parsing would need a parser per language,
  * where these heuristics cover every language in the extension map. Imperfect
  * on braces inside string literals, which is the trade for covering twenty
  * languages instead of one.
  *
  * Sizes are configured in tokens but enforced in characters via a ratio, since
  * kotllm has no tokenizer -- so **chunk sizes are approximate.** Fine for a
  * boundary decision; bundling vocabulary files per model would not be.
  */
class Chunker(val config: ChunkerConfig = ChunkerConfig.default) {

  /** Separators tried in order, coarsest first. */
  private val Separators = listOf("\n\n", "\n", ". ", " ")

  /** Extensions treated as code when the profile is Auto. Prose otherwise. */
  private val ProseExtensions =
    setOf("md", "markdown", "txt", "rst", "adoc", "org", "text", "csv", "tsv", "log")

  private val DeclarationStart =
    ("""^\s*(?:@[\w.]+\s*)*""" +
      """(?:(?:public|private|protected|internal|final|static|abstract|sealed|implicit|""" +
      """override|open|export|declare|async|inline|suspend|virtual|partial|case)\s+)*""" +
      """(?:def|fun|fn|func|function|class|object|trait|interface|enum|struct|impl|module|""" +
      """namespace|record|type|sub|procedure|proc)\b""").toRegex()

  /** A C-family signature with no leading keyword: `int main(int argc)` or
    * `static void handle(Request r) {`. */
  private val CStyleSignature =
    """^\s*[A-Za-z_][\w:<>,*&\[\]\s]*\s+[*&]?[A-Za-z_]\w*\s*\([^;{}]*\)\s*(?:const\s*)?\{?\s*$""".toRegex()

  private val CommentStart = """^\s*(?://|#|\*|/\*|--|;)""".toRegex()

  fun isProse(language: String, extension: String): Boolean =
    ProseExtensions.contains(extension.lowercase()) || language.isEmpty() ||
      language == "markdown" || language == "text"

  fun chunk(path: String, text: String, language: String, extension: String): List<Chunk> {
    if (text.trim().isEmpty()) return emptyList()

    val prose = when (config.profile) {
      ChunkProfile.Code -> false
      ChunkProfile.Auto -> isProse(language, extension)
      else -> true
    }

    val (tokens, overlapTok) = run {
      val (p, o) = ChunkProfile.sizing(
        if (config.profile == ChunkProfile.Auto) (if (prose) ChunkProfile.Docs else ChunkProfile.Code)
        else config.profile
      )
      Pair(config.chunkTokens ?: p, config.overlapTokens ?: o)
    }

    val ratio = if (prose) config.charsPerTokenProse else config.charsPerTokenCode
    val maxChars = kotlin.math.max(200, (tokens * ratio).toInt())
    val overlapCh = kotlin.math.max(0, kotlin.math.min((overlapTok * ratio).toInt(), maxChars / 2))

    val pieces =
      if (prose) proseChunks(text, maxChars, overlapCh)
      else codeChunks(text, maxChars)

    return pieces.map { (start, end, body) -> Chunk(path, start, end, body, language) }
  }

  // ---- prose -------------------------------------------------------------

  /** Recursive separator splitting, with overlap carried between chunks.
    *
    * @return (startLine, endLine, text) triples.
    */
  internal fun proseChunks(text: String, maxChars: Int, overlapChars: Int): List<Triple<Int, Int, String>> {
    val segments = splitRecursive(text, maxChars, 0)
    val out = mutableListOf<Triple<Int, Int, String>>()

    var buffer = StringBuilder()
    var bufStart = 0 // character offset of the buffer in the original text
    var cursor = 0

    fun flush() {
      if (buffer.isNotEmpty()) {
        val body = buffer.toString()
        out.add(Triple(lineOf(text, bufStart) + 1, lineOf(text, bufStart + body.length - 1) + 1, body.trim()))
        // Carry the tail forward so a sentence spanning a boundary is still
        // retrievable from either chunk.
        val carry = carryTail(body, overlapChars)
        buffer = StringBuilder(carry)
        bufStart = bufStart + body.length - carry.length
      }
    }

    segments.forEach { seg ->
      if (buffer.isNotEmpty() && buffer.length + seg.length > maxChars) flush()
      if (buffer.isEmpty()) bufStart = cursor
      buffer.append(seg)
      cursor += seg.length
    }
    if (buffer.toString().trim().isNotEmpty()) {
      val body = buffer.toString()
      out.add(Triple(lineOf(text, bufStart) + 1, lineOf(text, kotlin.math.min(bufStart + body.length - 1, text.length - 1)) + 1, body.trim()))
    }

    return out.filter { it.third.isNotEmpty() }
  }

  /** Breaks text into pieces no larger than `maxChars`, preferring coarse
    * separators. Falls through to a hard character cut, which is what makes
    * this terminate on minified JSON or a 40,000-character single line. */
  private fun splitRecursive(text: String, maxChars: Int, sepIndex: Int): List<String> {
    if (text.length <= maxChars) return listOf(text)
    if (sepIndex >= Separators.size) return hardCut(text, maxChars)

    val sep = Separators[sepIndex]
    val parts = text.split(Regex(Regex.escape(sep)))
    if (parts.size == 1) return splitRecursive(text, maxChars, sepIndex + 1)

    // Separators are re-attached so no character is lost.
    return parts.withIndex().flatMap { (i, part) ->
      val withSep = if (i < parts.size - 1) part + sep else part
      when {
        withSep.isEmpty() -> emptyList()
        withSep.length <= maxChars -> listOf(withSep)
        else -> splitRecursive(withSep, maxChars, sepIndex + 1)
      }
    }
  }

  /** The overlap carried into the next chunk, never starting mid-pair.
    *
    * `takeLast` counts UTF-16 code units, so an overlap landing inside an
    * astral character began with an orphaned low surrogate. Same defect as
    * [hardCut] and the same symptom -- it only surfaces when the index is
    * encoded -- but a separate code path, which is why fixing one left the
    * other live.
    */
  private fun carryTail(body: String, overlapChars: Int): String {
    if (overlapChars <= 0 || body.isEmpty()) return ""
    var start = kotlin.math.max(0, body.length - overlapChars)
    // Its high half sits at start - 1, so give the pair up rather than split it.
    if (start > 0 && Character.isLowSurrogate(body[start])) start++
    return body.substring(start)
  }

  /** The last-resort cut, and it must not land inside a surrogate pair.
    *
    * `String.chunked` counts UTF-16 code units, so a boundary at `maxChars`
    * can fall between the halves of an astral character -- an emoji, which a
    * real corpus has plenty of. The orphaned half survives in memory and in
    * BM25, and fails only on the way out: the index is written with
    * `Files.write(..., UTF_8)`, whose encoder reports rather than replaces, so
    * one emoji in one file failed the *entire* index with
    * `MalformedInputException: Input length = 1` and no indication of where.
    *
    * Pulling the boundary back by one leaves the pair whole at the head of the
    * next piece. `maxChars` is at least 200, so this can never produce an
    * empty piece and loop.
    */
  private fun hardCut(text: String, maxChars: Int): List<String> {
    val out = mutableListOf<String>()
    var i = 0
    while (i < text.length) {
      var end = kotlin.math.min(i + maxChars, text.length)
      if (end < text.length && Character.isHighSurrogate(text[end - 1])) end--
      out.add(text.substring(i, end))
      i = end
    }
    return out
  }

  // ---- code --------------------------------------------------------------

  /** Splits at declaration boundaries, then packs.
    *
    * Blocks smaller than a quarter of the target are merged with the following
    * one: a three-line accessor is not worth its own chunk, and a corpus of
    * tiny chunks retrieves badly.
    */
  internal fun codeChunks(text: String, maxChars: Int): List<Triple<Int, Int, String>> {
    val lines = text.split("\n")
    val starts = declarationLines(lines.toTypedArray())

    val blocks = mutableListOf<Pair<Int, Int>>()
    var from = 0
    starts.forEach { at ->
      if (at > from) { blocks.add(Pair(from, at - 1)); from = at }
    }
    if (from <= lines.size - 1) blocks.add(Pair(from, lines.size - 1))

    val packed = mutableListOf<Triple<Int, Int, String>>()
    var open: Pair<Int, Int>? = null

    fun body(a: Int, b: Int): String = lines.slice(a..b).joinToString("\n")

    fun emit(a: Int, b: Int) {
      val t = body(a, b)
      if (t.trim().isNotEmpty()) {
        // A single definition can still exceed the target; fall back to prose
        // splitting inside it rather than emitting something unbounded.
        if (t.length > maxChars * 2) {
          proseChunks(t, maxChars, 0).forEach { (s, e, piece) ->
            packed.add(Triple(a + s - 1, a + e - 1, piece))
          }
        } else {
          packed.add(Triple(a + 1, b + 1, t.trim()))
        }
      }
    }

    blocks.forEach { (a, b) ->
      when (open) {
        null -> open = Pair(a, b)
        else -> {
          val oa = open.first
          val ob = open.second
          val current = body(oa, ob)
          if (current.length < maxChars / 4 || current.length + body(a, b).length <= maxChars) {
            open = Pair(oa, b)
          } else {
            emit(oa, ob)
            open = Pair(a, b)
          }
        }
      }
    }
    open?.let { (a, b) -> emit(a, b) }

    return packed
  }

  /** Line indices that start a new definition.
    *
    * Requires shallow nesting as well as a matching keyword: at brace depth 3
    * a `def` is a local helper, and splitting there would separate it from the
    * method that owns it.
    */
  internal fun declarationLines(lines: Array<String>): List<Int> {
    val out = mutableListOf<Int>()
    var depth = 0

    lines.withIndex().forEach { (i, line) ->
      val indent = line.takeWhile { it == ' ' }.length + line.takeWhile { it == '\t' }.length * 4
      val isComment = CommentStart.matchAt(line, 0) != null

      val candidate =
        !isComment && depth <= 1 && indent <= 8 &&
          (DeclarationStart.matchAt(line, 0) != null || CStyleSignature.matchAt(line, 0) != null)

      if (candidate && i > 0) out.add(i)

      // Counted after the decision, so the line opening a block still counts
      // as being at the outer depth.
      depth += line.count { it == '{' } - line.count { it == '}' }
      if (depth < 0) depth = 0
    }
    return out
  }

  private fun lineOf(text: String, offset: Int): Int {
    val at = kotlin.math.max(0, kotlin.math.min(offset, text.length))
    var n = 0
    var i = 0
    while (i < at) {
      if (text[i] == '\n') n += 1
      i += 1
    }
    return n
  }
}
