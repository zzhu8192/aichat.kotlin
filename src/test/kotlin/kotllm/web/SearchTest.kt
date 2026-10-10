package kotllm.web

import java.nio.file.Files

import kotlin.test.Test
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.collections.shouldNotContain
import kotlinx.serialization.json.*

import kotllm.*
import kotllm.tools.Tools

/** Search parsing and configuration, without touching the network.
 *
 * Only the pure parts are exercised here on purpose: a test that queried
 * DuckDuckGo would fail on a plane, in CI, and on the day they change their
 * markup -- which is exactly the day the parser needs to be *edited*, not the
 * day the build should start failing for everyone.
 */
class SearchTest {

  private fun expectErr(outcome: Outcome<*>): String =
    outcome.fold({ it }, { throw AssertionError("expected a failure but got $it") })

  @Test fun `sources parse by name and by the abbreviations a model will guess`() {
    SearchSource.parse("web") shouldBe Web
    SearchSource.parse("stackoverflow") shouldBe StackOverflow
    SearchSource.parse("so") shouldBe StackOverflow
    SearchSource.parse("wiki") shouldBe Wikipedia
    SearchSource.parse("mvn") shouldBe Maven
    SearchSource.parse("gh") shouldBe GitHub
    SearchSource.parse("bing") shouldBe null
  }

  @Test fun `engines parse, and the default is none`() {
    Engine.parse("ddg-lite") shouldBe DdgLite
    Engine.parse("duckduckgo") shouldBe DdgLite
    Engine.parse("searxng") shouldBe Searxng
    Engine.parse("off") shouldBe None
    Engine.parse("google") shouldBe null

    // General web search is opt-in: the model chooses the query, and read-only
    // tools do not stop to ask.
    SearchConfig.default.engine shouldBe None
    SearchConfig.default.webEnabled shouldBe false
    SearchConfig.default.enabled shouldBe true // the named JSON sources still work
  }

  @Test fun `an unconfigured general search says what to set, not just 'no results'`() {
    val err = expectErr(Search(SearchConfig.default).query("anything", Web))
    err shouldContain "provider: ddg-lite"
    err shouldContain "stackoverflow"
  }

  @Test fun `searxng without an instance says so rather than guessing one`() {
    val err = expectErr(Search(SearchConfig(engine = Searxng)).query("x", Web))
    err shouldContain "api_base"
  }

  // ---- the scraped result parser ------------------------------------------

  /** Trimmed from what lite.duckduckgo.com actually returns: a numbered cell,
    * the link, then the snippet in its own row. */
  private val ddgLite =
    """<table>
<tr><td valign="top">1.&nbsp;</td>
    <td><a rel="nofollow" href="//duckduckgo.com/l/?uddg=https%3A%2F%2Fdocs.scala%2Dlang.org%2Foverviews%2F&amp;rut=abc" class="result-link">Collections &amp; views</a></td></tr>
<tr><td class="result-snippet">The 2.13 redesign replaced <b>CanBuildFrom</b>.</td></tr>
<tr><td valign="top">2.&nbsp;</td>
    <td><a href="https://www.scala-lang.org/api/2.13.x/" class='result-link'>API docs</a></td></tr>
<tr><td class='result-snippet'>Scaladoc for 2.13.</td></tr>
</table>"""

  @Test fun `result links are found in either attribute order and the text is cleaned`() {
    val found = Search.ResultLink.findAll(ddgLite).map { Search.hrefAndText(it) }.toList()

    found.size shouldBe 2
    kotllm.input.Html.plainText(found[0].second) shouldBe "Collections & views"
    kotllm.input.Html.plainText(found[1].second) shouldBe "API docs"
    found[1].first shouldBe "https://www.scala-lang.org/api/2.13.x/"
  }

  @Test fun `the DuckDuckGo redirect is unwrapped, so the model gets the real URL`() {
    Search.unwrap("//duckduckgo.com/l/?uddg=https%3A%2F%2Fdocs.scala%2Dlang.org%2F&rut=abc") shouldBe
      "https://docs.scala-lang.org/"
    // A direct link is left alone; a protocol-relative one is made absolute.
    Search.unwrap("https://example.org/x") shouldBe "https://example.org/x"
    Search.unwrap("//example.org/x") shouldBe "https://example.org/x"
  }

  @Test fun `snippets are found and stripped of their highlight markup`() {
    val snippets =
      // groupValues[2]: group 1 is the backreferenced tag name, which is what
      // stops the match at the cell's own closing tag rather than a nested </b>.
      Search.ResultSnippet.findAll(ddgLite).map { kotllm.input.Html.plainText(it.groupValues[2]) }.toList()

    snippets.size shouldBe 2
    snippets.first() shouldBe "The 2.13 redesign replaced CanBuildFrom."
  }

  // ---- the tools ----------------------------------------------------------

  @Test fun `fetch and web_search are read-only, and the code tools are not`() {
    val fetch = Tools.fetch(convertHtml = true)
    val web = Tools.webSearch(Search(SearchConfig.default), SearchConfig.default)

    fetch.readOnly shouldBe true
    web.readOnly shouldBe true
    Tools.exec(10).readOnly shouldBe false
  }

  @Test fun `the web_search schema offers only the sources that can actually answer`() {
    val without = Tools.webSearch(Search(SearchConfig.default), SearchConfig.default)
    val sources = without.parameters.jsonObject["properties"]!!.jsonObject["source"]!!
      .jsonObject["enum"]!!.jsonArray.map { it.jsonPrimitive.content }

    sources shouldNotContain "web" // no general engine configured
    sources shouldContain "stackoverflow"
    without.description shouldContain "not configured on this machine"

    val enabled = SearchConfig(engine = DdgLite, sites = listOf("docs.scala-lang.org"))
    val withEnabled = Tools.webSearch(Search(enabled), enabled)
    val enabledSources = withEnabled.parameters.jsonObject["properties"]!!.jsonObject["source"]!!
      .jsonObject["enum"]!!.jsonArray.map { it.jsonPrimitive.content }
    enabledSources shouldContain "web"
    // Configured domains are told to the model rather than enforced as a filter.
    withEnabled.description shouldContain "docs.scala-lang.org"
  }

  @Test fun `fetch reads a local file through the HTML conversion`() {
    val dir = Files.createTempDirectory("kotllm-fetch")
    val file = dir.resolve("page.html")
    Files.write(file, "<html><body><h1>Title</h1><p>Body text.</p></body></html>".toByteArray())

    val result = Tools.fetch(convertHtml = true).run(buildJsonObject { put("url", file.toString()) })

    result.isError shouldBe false
    result.content shouldContain "# Title"
    result.content shouldContain "Body text."
    result.content shouldContain "converted from HTML"
  }

  @Test fun `fetch reports a missing file as an error the model can act on`() {
    val result = Tools.fetch(convertHtml = true).run(buildJsonObject { put("url", "/no/such/page.html") })
    result.isError shouldBe true
    result.content shouldContain "no such file"
  }

  @Test fun `fetch truncates rather than spending the whole window`() {
    val dir = Files.createTempDirectory("kotllm-fetch")
    val file = dir.resolve("long.txt")
    Files.write(file, ("word ".repeat(5000)).toByteArray())

    val result = Tools.fetch(convertHtml = true).run(
      buildJsonObject {
        put("url", file.toString())
        put("max_chars", 500)
      }
    )

    (result.content.length < 900) shouldBe true
    result.content shouldContain "truncated at 500"
  }

  @Test fun `the preview names the query and the source, since nothing prompts`() {
    val web = Tools.webSearch(Search(SearchConfig.default), SearchConfig.default)
    web.preview(
      buildJsonObject {
        put("query", "scala 2.13")
        put("source", "stackoverflow")
      }
    ) shouldBe "scala 2.13 [stackoverflow]"
    Tools.fetch(convertHtml = true).preview(buildJsonObject { put("url", "https://x/y") }) shouldBe "https://x/y"
  }
}
