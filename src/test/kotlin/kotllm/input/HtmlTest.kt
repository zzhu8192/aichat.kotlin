package kotllm.input

import java.nio.file.Files

import kotlin.test.Test
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import kotllm.*

/** The conversion exists to save tokens, so most of these assert on what is
  * *gone* as much as on what survives. */
class HtmlTest {

  private val page = """<!DOCTYPE html>
      |<html><head>
      |  <title>Ignored</title>
      |  <style>body { color: red; font-family: "Helvetica Neue", sans-serif; }</style>
      |  <script>var tracking = {id: 42}; function noise() { return "<p>not real</p>"; }</script>
      |</head>
      |<body>
      |  <nav><a href="/">Home</a> <a href="/docs">Docs</a></nav>
      |  <h1>Collections</h1>
      |  <p>The <strong>2.13</strong> redesign replaced <code>CanBuildFrom</code> with
      |     <em>views</em>.</p>
      |  <ul>
      |    <li>Strict by default</li>
      |    <li>Lazy <a href="/views">views</a></li>
      |  </ul>
      |  <pre><code>val xs = List(1, 2, 3)
      |xs.map(_ + 1)</code></pre>
      |  <p>See the <a href="https://docs.scala-lang.org/overviews/">overview</a> &amp; the FAQ.</p>
      |  <img src="/img/diagram-2048.png" alt="collection hierarchy">
      |  <table><tr><th>Type</th><th>Strict</th></tr><tr><td>List</td><td>yes</td></tr></table>
      |</body></html>""".trimMargin()

  private val md: String by lazy { Html.toMarkdown(page) }

  private fun <A> expectErr(outcome: Outcome<A>): String =
    outcome.fold({ it }, { throw AssertionError("expected a failure but got $it") })

  @Test fun `script and style content is dropped entirely`() {
    md shouldNotContain "tracking"
    md shouldNotContain "Helvetica"
    md shouldNotContain "not real"
  }

  @Test fun `headings, emphasis and inline code become markdown`() {
    md shouldContain "# Collections"
    md shouldContain "**2.13**"
    md shouldContain "`CanBuildFrom`"
    md shouldContain "*views*"
  }

  @Test fun `list items become bullets, ordered lists become numbers`() {
    md shouldContain "- Strict by default"
    Html.toMarkdown("<ol><li>first</li><li>second</li></ol>") shouldBe "1. first\n2. second"
  }

  @Test fun `a pre block is fenced and keeps its own line breaks`() {
    md shouldContain "```"
    md shouldContain "val xs = List(1, 2, 3)\nxs.map(_ + 1)"
  }

  @Test fun `links keep their text and their target`() {
    md shouldContain "[overview](https://docs.scala-lang.org/overviews/)"
  }

  @Test fun `an image keeps its alt text and loses its URL`() {
    // The model cannot fetch the URL and the alt text is the only part that
    // carries meaning.
    md shouldContain "[image: collection hierarchy]"
    md shouldNotContain "diagram-2048.png"
  }

  @Test fun `entities are decoded`() {
    md shouldContain "& the FAQ"
    Html.toMarkdown("<p>a &lt; b &amp;&amp; c &gt; d &mdash; &#65;&#x42;</p>") shouldBe
      "a < b && c > d -- AB"
  }

  @Test fun `an unknown entity is left alone rather than eaten`() {
    Html.toMarkdown("<p>&foo; and &amp;</p>") shouldBe "&foo; and &"
  }

  @Test fun `table rows become one line each`() {
    md shouldContain "Type | Strict"
    md shouldContain "List | yes"
  }

  @Test fun `markup is the majority of what gets removed`() {
    // The point of the feature. Anything less than half is a bug in the
    // converter, not a property of the page.
    (md.length < page.length / 2) shouldBe true
  }

  @Test fun `no run of three newlines survives div soup`() {
    Html.toMarkdown("<div><div><div><p>one</p></div></div></div><div><p>two</p></div>") shouldBe
      "one\n\ntwo"
  }

  @Test fun `unbalanced tags degrade rather than throw`() {
    Html.toMarkdown("<p><b>bold <i>both</p> <ul><li>stray")
    Html.toMarkdown("<a href='x'>unclosed")
    Html.toMarkdown("<<>><p title='a > b'>ok</p>")
  }

  @Test fun `a tag attribute containing an angle bracket does not end the tag`() {
    Html.toMarkdown("""<p title="a > b">text</p>""") shouldBe "text"
  }

  @Test fun `plainText keeps a fragment on one line, for search snippets`() {
    Html.plainText("The <span class='hit'>2.13</span>\n  redesign &amp; more") shouldBe
      "The 2.13 redesign & more"
  }

  @Test fun `looksLikeHtml is conservative enough to leave source code alone`() {
    Html.looksLikeHtml("<!doctype html><html><body>x</body></html>") shouldBe true
    Html.looksLikeHtml("<html><body><p>hi</p></body></html>") shouldBe true
    // Scala source that merely mentions tags must not be mangled.
    Html.looksLikeHtml("val tag = \"<p>\" ; def render = s\"<p>\$x</p>\"") shouldBe false
    Html.looksLikeHtml("# A markdown file\n\nwith prose") shouldBe false
  }

  // ---- Fetch's decision about when to convert ----------------------------

  @Test fun `the declared content type decides, and beats the sniff`() {
    val html = "<html><body><h1>Title</h1></body></html>"

    Fetch.render("u", html, "text/html; charset=utf-8", convertHtml = true).convertedFromHtml shouldBe true
    // A server that says text/plain is believed: it may be serving HTML source
    // as the subject matter.
    Fetch.render("u", html, "text/plain", convertHtml = true).convertedFromHtml shouldBe false
    Fetch.render("u", html, null, convertHtml = true).convertedFromHtml shouldBe true
    Fetch.render("u", html, "text/html", convertHtml = false).convertedFromHtml shouldBe false
  }

  @Test fun `the saving is reported, since the block is not what the server sent`() {
    val page = Fetch.render("u", "<html><body>" + "<p>word word</p>".repeat(50) + "</body></html>", "text/html", true)
    page.savingSummary!! shouldContain "converted from HTML"
    page.savingSummary!! shouldContain "% smaller"
    Fetch.render("u", "plain", "text/plain", true).savingSummary shouldBe null
  }

  @Test fun `a local html file is converted, and a scala file is not`() {
    val dir  = Files.createTempDirectory("kotllm-html")
    val page = dir.resolve("page.html")
    val code = dir.resolve("Code.scala")
    Files.write(page, "<html><body><h2>Hi</h2></body></html>".toByteArray(Charsets.UTF_8))
    Files.write(code, "val html = \"<p>x</p>\"".toByteArray(Charsets.UTF_8))

    Fetch.local(page).orNull()!!.text shouldBe "## Hi"
    Fetch.local(code).orNull()!!.text shouldContain "<p>x</p>"
  }

  @Test fun `a missing or binary local file is an error, not empty output`() {
    val dir = Files.createTempDirectory("kotllm-html")
    expectErr(Fetch.local(dir.resolve("nope.html"))) shouldBe "no such file"
    expectErr(Fetch.local(dir)) shouldBe "is a directory"

    val bin = dir.resolve("blob.html")
    Files.write(bin, byteArrayOf(1, 2, 0, 3))
    expectErr(Fetch.local(bin)) shouldBe "looks binary"
  }
}
