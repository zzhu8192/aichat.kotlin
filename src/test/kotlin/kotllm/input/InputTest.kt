package kotllm.input

import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path

import kotlin.test.Test
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import io.kotest.matchers.string.shouldEndWith
import kotllm.*

class InputTest {

  private fun loader(
      limits: InputLimits = InputLimits.default,
      lastReply: String? = null
  ): InputLoader = InputLoader(limits) { lastReply }

  private fun tempDir(): Path = Files.createTempDirectory("kotllm-input")

  private fun write(dir: Path, name: String, content: String): Path {
    val p = dir.resolve(name)
    Files.createDirectories(p.parent)
    Files.write(p, content.toByteArray(StandardCharsets.UTF_8))
    return p
  }

  private fun <A> expectErr(outcome: Outcome<A>): String =
    outcome.fold({ it }, { throw AssertionError("expected a failure but got $it") })

  // ---- argument parsing --------------------------------------------------

  @Test fun `the -- marker separates sources from the message`() {
    // The whitespace around the marker is consumed with it.
    loader().splitMessage("a.scala dir/ -- explain this") shouldBe
      Pair("a.scala dir/", "explain this")
  }

  @Test fun `no marker means everything is a source`() {
    loader().splitMessage("a.scala b.scala") shouldBe Pair("a.scala b.scala", null)
  }

  @Test fun `a double dash inside a word is not the marker`() {
    // Otherwise --flag or a filename containing -- would split wrongly.
    loader().splitMessage("weird--name.txt") shouldBe Pair("weird--name.txt", null)
  }

  @Test fun `tokenising keeps a backticked command whole`() {
    loader().tokenise("a.txt `git diff --stat` b.txt") shouldBe
      listOf("a.txt", "`git diff --stat`", "b.txt")
  }

  @Test fun `tokenising keeps a quoted path with spaces whole`() {
    loader().tokenise("\"my file.txt\" other.txt") shouldBe listOf("\"my file.txt\"", "other.txt")
  }

  @Test fun `classification distinguishes the source kinds`() {
    val l = loader()
    l.classify("%%") shouldBe LastReply
    l.classify("`ls`") shouldBe Command("ls")
    (l.classify("https://example.com") is Url) shouldBe true
    (l.classify("http://example.com") is Url) shouldBe true
    (l.classify("some/file.txt") is LocalFile) shouldBe true
  }

  @Test fun `an existing directory classifies as a directory`() {
    val dir = tempDir()
    loader().classify(dir.toString()) shouldBe Directory(dir)
  }

  // ---- loading -----------------------------------------------------------

  @Test fun `a file is loaded with its path as the label and a fence language`() {
    val dir  = tempDir()
    val file = write(dir, "Thing.scala", "object Thing")

    val loaded = loader().load(listOf(file.toString())).orNull()!!
    loaded.blocks.size shouldBe 1
    loaded.blocks.first().label shouldBe file.toString()
    loaded.blocks.first().language shouldBe "scala"
    loaded.blocks.first().content shouldContain "object Thing"
  }

  @Test fun `a missing file is reported as skipped, not silently dropped`() {
    val loaded = loader().load(listOf("no/such/file.txt"))
    expectErr(loaded) shouldContain "no such file"
  }

  @Test fun `one bad source among good ones is reported but does not fail the batch`() {
    val dir  = tempDir()
    val good = write(dir, "good.txt", "fine")

    val loaded = loader().load(listOf(good.toString(), "no/such/file.txt")).orNull()!!
    loaded.blocks.size shouldBe 1
    loaded.skipped.joinToString("") shouldContain "no such file"
  }

  @Test fun `a directory is walked, and build output is skipped`() {
    val dir = tempDir()
    write(dir, "src/A.scala", "object A")
    write(dir, "src/B.scala", "object B")
    write(dir, "target/Generated.scala", "object Generated")
    write(dir, ".git/config", "[core]")

    val loaded = loader().load(listOf(dir.toString())).orNull()!!
    val labels = loaded.blocks.joinToString(" ") { it.label }

    labels shouldContain "A.scala"
    labels shouldContain "B.scala"
    labels shouldNotContain "Generated.scala"
    labels shouldNotContain ".git"
  }

  @Test fun `a binary file is skipped rather than wasting the budget`() {
    val dir  = tempDir()
    val file = dir.resolve("blob.bin")
    Files.write(file, byteArrayOf(1, 2, 0, 3, 4))

    val loaded = loader().load(listOf(file.toString()))
    expectErr(loaded) shouldContain "binary"
  }

  @Test fun `an oversized file is truncated, and says so`() {
    val dir    = tempDir()
    val file   = write(dir, "big.txt", "x".repeat(500))
    val limits = InputLimits.default.copy(maxFileChars = 100)

    val content = loader(limits).load(listOf(file.toString())).orNull()!!.blocks.first().content
    content shouldContain "truncated at 100 chars of 500"
    (content.length < 300) shouldBe true
  }

  @Test fun `the total budget stops further loading and reports why`() {
    val dir = tempDir()
    val a   = write(dir, "a.txt", "x".repeat(80))
    val b   = write(dir, "b.txt", "y".repeat(80))
    val limits = InputLimits.default.copy(maxTotalChars = 100)

    val loaded = loader(limits).load(listOf(a.toString(), b.toString())).orNull()!!
    loaded.blocks.size shouldBe 1
    loaded.skipped.joinToString("") shouldContain "total budget"
  }

  @Test fun `the file count cap stops further loading`() {
    val dir = tempDir()
    (1..5).forEach { i -> write(dir, "f$i.txt", "x") }
    val limits = InputLimits.default.copy(maxFiles = 2)

    val loaded = loader(limits).load(listOf(dir.toString())).orNull()!!
    loaded.blocks.size shouldBe 2
    loaded.skipped.joinToString("") shouldContain "stopped at 2 files"
  }

  @Test fun `the double-percent token includes the previous reply`() {
    val loaded = loader(lastReply = "the earlier answer").load(listOf("%%")).orNull()!!
    loaded.blocks.first().content shouldBe "the earlier answer"
    loaded.blocks.first().label shouldContain "previous reply"
  }

  @Test fun `the double-percent token with no previous reply is reported rather than sending nothing silently`() {
    expectErr(loader().load(listOf("%%"))) shouldContain "no previous reply"
  }

  @Test fun `a backticked command has its output included`() {
    val loaded = loader().load(listOf("`echo hello-from-input`")).orNull()!!
    loaded.blocks.first().content shouldContain "hello-from-input"
    loaded.blocks.first().label shouldContain "echo hello-from-input"
  }

  @Test fun `a failing command is reported with its exit code`() {
    expectErr(loader().load(listOf("`exit 7`"))) shouldContain "exit code 7"
  }

  @Test fun `a configured loader converts a format we cannot read as text`() {
    val dir  = tempDir()
    val file = write(dir, "doc.weird", "ignored")
    // Stands in for `pdftotext $1 -`; the point is that $1 is substituted.
    val limits = InputLimits.default.copy(loaders = mapOf("weird" to "echo converted-content"))

    val loaded = loader(limits).load(listOf(file.toString())).orNull()!!
    loaded.blocks.first().content shouldContain "converted-content"
  }

  @Test fun `nothing to load is an error, not an empty send`() {
    expectErr(loader().load(emptyList())) shouldContain "nothing to load"
  }

  // ---- rendering ---------------------------------------------------------

  @Test fun `blocks render fenced and labelled, with the message last`() {
    val loaded = Loaded(listOf(Block("a.scala", "object A", "scala")), emptyList())
    val out    = loaded.render("review this")

    out shouldContain "### a.scala"
    out shouldContain "```scala"
    out shouldContain "object A"
    out.trim() shouldEndWith "review this"
  }

  @Test fun `a fence grows to outrun backticks in the content`() {
    // A three-backtick fence would be closed early by the content itself.
    val loaded = Loaded(listOf(Block("readme", "text with ``` inside", "")), emptyList())
    val out    = loaded.render(null)

    out shouldContain "````"
  }

  @Test fun `rendering without a message emits only the blocks`() {
    val out = Loaded(listOf(Block("a", "body", "")), emptyList()).render(null)
    out shouldContain "body"
    out.trim() shouldEndWith "`"
  }
}
