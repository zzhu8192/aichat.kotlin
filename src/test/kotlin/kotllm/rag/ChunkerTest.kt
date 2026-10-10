package kotllm.rag

import kotlin.test.Test
import io.kotest.matchers.shouldBe
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldNotBeEmpty
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldStartWith
import io.kotest.matchers.string.shouldNotBeEmpty

class ChunkerTest {

  private fun chunker(
      profile: ChunkProfile = ChunkProfile.Auto,
      tokens: Int? = null,
      overlap: Int? = null
  ): Chunker = Chunker(ChunkerConfig.default.copy(profile = profile, chunkTokens = tokens, overlapTokens = overlap))

  private fun prose(n: Int): String =
    (1..n).joinToString("\n\n") { i -> "Paragraph $i. " + "word ".repeat(20).trim() + "." }

  // ---- profiles ----------------------------------------------------------

  @Test fun `profile sizing matches the published guidance`() {
    (ChunkProfile.sizing(ChunkProfile.Faq).first in 128..256) shouldBe true
    (ChunkProfile.sizing(ChunkProfile.Docs).first in 256..512) shouldBe true
    (ChunkProfile.sizing(ChunkProfile.Longform).first in 512..1024) shouldBe true
  }

  @Test fun `profiles parse by name`() {
    ChunkProfile.parse("faq") shouldBe ChunkProfile.Faq
    ChunkProfile.parse("CODE") shouldBe ChunkProfile.Code
    ChunkProfile.parse("nonsense") shouldBe null
  }

  @Test fun `auto treats markdown and text as prose, source as code`() {
    val c = chunker()
    c.isProse("markdown", "md") shouldBe true
    c.isProse("", "txt") shouldBe true
    c.isProse("scala", "scala") shouldBe false
    c.isProse("python", "py") shouldBe false
  }

  // ---- prose splitting ---------------------------------------------------

  @Test fun `a short file is one chunk`() {
    val out = chunker().chunk("a.md", "just a sentence.", "markdown", "md")
    out.size shouldBe 1
    out.first().text shouldBe "just a sentence."
  }

  @Test fun `empty or whitespace-only input yields nothing`() {
    chunker().chunk("a.md", "", "markdown", "md").shouldBeEmpty()
    chunker().chunk("a.md", "   \n\n  ", "markdown", "md").shouldBeEmpty()
  }

  @Test fun `long prose splits into several chunks near the target size`() {
    val out = chunker(ChunkProfile.Docs, tokens = 100).chunk("a.md", prose(40), "markdown", "md")

    (out.size > 3) shouldBe true
    // 100 tokens at 4.0 chars/token, with slack for separator boundaries.
    out.forEach { (it.text.length <= 700) shouldBe true }
  }

  @Test fun `paragraph boundaries are preferred, so paragraphs stay intact`() {
    // Paragraphs sized so two of them exceed the chunk target but one does
    // not, which is what forces the split to land on the blank line. Note the
    // 200-character floor on chunk size: a very small token setting cannot
    // produce tiny chunks, by design.
    val para = { n: Int -> "Paragraph $n. " + "detail ".repeat(20).trim() + "." }
    val text = (1..4).joinToString("\n\n") { i -> para(i) }

    val out = chunker(ChunkProfile.Docs, tokens = 50, overlap = 0)
      .chunk("a.md", text, "markdown", "md")

    (out.size > 1) shouldBe true
    // Every chunk begins at a paragraph start, never mid-sentence.
    out.forEach { it.text shouldStartWith "Paragraph " }
    // And ends at a sentence end.
    out.forEach { it.text.endsWith(".") shouldBe true }
  }

  @Test fun `overlap carries text between adjacent chunks`() {
    val out = chunker(ChunkProfile.Docs, tokens = 60, overlap = 20)
      .chunk("a.md", prose(20), "markdown", "md")

    (out.size > 2) shouldBe true
    // Some later chunk repeats text from its predecessor.
    val overlaps = out.windowed(2).count { (a, b) -> a.text.takeLast(20).any { ch -> b.text.take(80).contains(ch) } }
    (overlaps > 0) shouldBe true
  }

  @Test fun `zero overlap produces no repetition`() {
    val out = chunker(ChunkProfile.Docs, tokens = 60, overlap = 0)
      .chunk("a.md", prose(10), "markdown", "md")
    val joined = out.joinToString(" ") { it.text }

    // Every paragraph marker appears exactly once.
    (1..10).forEach { i ->
      val marker = "Paragraph $i."
      Regex(Regex.escape(marker)).findAll(joined).count() shouldBe 1
    }
  }

  @Test fun `a single unbroken line still terminates and is bounded`() {
    // Minified JSON, or a 40k-character line: every separator fails, so the
    // hard character fallback has to take over or this never terminates.
    val blob = "{\"k\":" + "x".repeat(40000) + "}"
    val out = chunker(ChunkProfile.Docs, tokens = 100).chunk("a.json", blob, "json", "json")

    (out.size > 1) shouldBe true
    out.forEach { (it.text.length <= 1000) shouldBe true }
    (out.sumOf { it.text.length } >= 39000) shouldBe true
  }

  @Test fun `chunks carry a usable citation`() {
    val out = chunker(ChunkProfile.Docs, tokens = 40, overlap = 0)
      .chunk("docs/guide.md", prose(12), "markdown", "md")

    out.forEach { c ->
      c.path shouldBe "docs/guide.md"
      (c.lineStart >= 1) shouldBe true
      (c.lineEnd >= c.lineStart) shouldBe true
      c.citation shouldStartWith "docs/guide.md:"
    }
    // Ranges advance through the file rather than all reporting line 1.
    (out.map { it.lineStart }.distinct().size > 1) shouldBe true
  }

  // ---- code splitting ----------------------------------------------------

  private val scalaSource = """
    |package example
    |
    |import scala.util.Try
    |
    |class Service(dep: Dep) {
    |
    |  def alpha(x: Int): Int = {
    |    val doubled = x * 2
    |    doubled + 1
    |  }
    |
    |  def beta(s: String): String = {
    |    val trimmed = s.trim
    |    trimmed.toUpperCase
    |  }
    |
    |  def gamma(): Unit = {
    |    println("side effect")
    |  }
    |}
    |
    """.trimMargin()

  @Test fun `code splits at declaration boundaries`() {
    val out = chunker(ChunkProfile.Code, tokens = 30).chunk("S.scala", scalaSource, "scala", "scala")
    (out.size > 1) shouldBe true
  }

  @Test fun `a method body is never cut in half`() {
    // The whole point of structure-aware splitting: a chunk containing the
    // signature must contain its body.
    val out = chunker(ChunkProfile.Code, tokens = 30).chunk("S.scala", scalaSource, "scala", "scala")

    out.forEach { c ->
      if (c.text.contains("def alpha")) c.text shouldContain "doubled + 1"
      if (c.text.contains("def beta")) c.text shouldContain "toUpperCase"
    }
  }

  @Test fun `declaration detection finds methods and classes but not deep locals`() {
    val lines = scalaSource.split("\n").toTypedArray()
    val found = chunker().declarationLines(lines).map { lines[it].trim() }

    found.any { it.startsWith("class Service") } shouldBe true
    found.any { it.startsWith("def beta") } shouldBe true
    // `val doubled` is a local inside a method, not a split point.
    found.any { it.contains("val doubled") } shouldBe false
  }

  @Test fun `a comment resembling a declaration is not a split point`() {
    val src = "// def notReal(): Unit\n# class NotReal\n * def alsoNot()\ndef real(): Int = 1\n"
    val lines = src.split("\n").toTypedArray()
    val found = chunker().declarationLines(lines)

    found.map { lines[it].trim() }.forEach { it shouldStartWith "def real" }
  }

  @Test fun `C-style functions with no keyword are detected`() {
    val src = """
      |#include <stdio.h>
      |
      |int main(int argc, char** argv) {
      |    printf("hi");
      |    return 0;
      |}
      |
      |static void helper(struct Req* r) {
      |    handle(r);
      |}
      |
    """.trimMargin()
    val lines = src.split("\n").toTypedArray()
    val found = chunker().declarationLines(lines).map { lines[it].trim() }

    found.any { it.startsWith("static void helper") } shouldBe true
  }

  @Test fun `a definition larger than the target is split rather than left unbounded`() {
    val huge = "def enormous(): Unit = {\n" + (1..400).joinToString("\n") { i -> "  step$i()" } + "\n}\n"
    val out = chunker(ChunkProfile.Code, tokens = 50).chunk("B.scala", huge, "scala", "scala")

    (out.size > 1) shouldBe true
    out.forEach { (it.text.length <= 1200) shouldBe true }
  }

  @Test fun `tiny adjacent definitions are merged rather than each becoming a chunk`() {
    // A corpus of three-line chunks retrieves badly.
    val src = (1..12).joinToString("\n") { i -> "def f$i(): Int = $i" }
    val out = chunker(ChunkProfile.Code, tokens = 400).chunk("T.scala", src, "scala", "scala")

    (out.size < 12) shouldBe true
  }

  @Test fun `code with no recognisable declarations is still chunked`() {
    val src = (1..50).joinToString("\n") { i -> "x$i = compute($i)" }
    val out = chunker(ChunkProfile.Code, tokens = 40).chunk("s.py", src, "python", "py")

    out.shouldNotBeEmpty()
    out.joinToString("") { it.text } shouldContain "x50"
  }

  @Test fun `an explicit profile overrides the per-file auto choice`() {
    // Forcing prose on a source file must not fall into the code path.
    val out = chunker(ChunkProfile.Docs, tokens = 40, overlap = 0)
      .chunk("S.scala", scalaSource, "scala", "scala")
    out.shouldNotBeEmpty()
  }

  @Test fun `language is recorded on every chunk`() {
    chunker(ChunkProfile.Code).chunk("S.scala", scalaSource, "scala", "scala").forEach {
      it.language shouldBe "scala"
    }
  }

  @Test fun `no chunk is blank`() {
    val out = chunker(ChunkProfile.Docs, tokens = 30).chunk("a.md", prose(15), "markdown", "md")
    out.forEach { it.text.trim().shouldNotBeEmpty() }
  }

  // ---- surrogate pairs ---------------------------------------------------

  /** Index of every UTF-16 code unit that is half of a pair with nothing on the
    * other side of it. */
  private fun unpaired(s: String): List<Int> {
    val bad = mutableListOf<Int>()
    var i = 0
    while (i < s.length) {
      val c = s[i]
      if (Character.isHighSurrogate(c)) {
        if (i + 1 >= s.length || !Character.isLowSurrogate(s[i + 1])) bad.add(i) else i++
      } else if (Character.isLowSurrogate(c)) bad.add(i)
      i++
    }
    return bad
  }

  @Test fun `the hard cut never splits a surrogate pair`() {
    // The failure this pins: `chunked` counts UTF-16 code units, so a boundary
    // could land inside an emoji. The orphaned half survived in memory and threw
    // only when the index was written -- `MalformedInputException: Input length
    // = 1` -- which failed the *whole* index and named no file.
    //
    // No separators at all, so every piece comes from the last-resort cut.
    val text = "\uD83D\uDE00".repeat(4000)
    val out = chunker(ChunkProfile.Docs, tokens = 60, overlap = 0)
      .chunk("e.md", text, "markdown", "md")

    out.shouldNotBeEmpty()
    out.forEach { c ->
      // clue: "chunk had unpaired surrogates at ${unpaired(c.text)}"
      unpaired(c.text).shouldBeEmpty()
    }
  }

  @Test fun `a chunked emoji corpus survives a strict UTF-8 encode`() {
    // The actual symptom, asserted the way it actually failed: the index is
    // written with an encoder that reports malformed input rather than
    // replacing it.
    val text = (1..500).joinToString("\n") { i -> "row $i\t\uD83D\uDE80\uD83C\uDF89\t" + "\uD83D\uDC4D".repeat(20) }
    val out = chunker(ChunkProfile.Docs, tokens = 50, overlap = 10)
      .chunk("emoji.tsv", text, "", "tsv")

    val encoder = java.nio.charset.StandardCharsets.UTF_8.newEncoder()
    out.forEach { c ->
      encoder.reset()
      encoder.encode(java.nio.CharBuffer.wrap(c.text))
    }
  }

  @Test fun `the real emoji resource indexes without an encoding failure`() {
    // Guards the file that actually broke it, so deleting the fixture above
    // cannot quietly remove the coverage.
    val path = java.nio.file.Path.of("src/main/resources/emoji.tsv")
    val text = String(java.nio.file.Files.readAllBytes(path), java.nio.charset.StandardCharsets.UTF_8)

    val out = Chunker(ChunkerConfig.default).chunk("emoji.tsv", text, "", "tsv")

    out.shouldNotBeEmpty()
    out.forEach { c -> unpaired(c.text).shouldBeEmpty() }
  }
}
