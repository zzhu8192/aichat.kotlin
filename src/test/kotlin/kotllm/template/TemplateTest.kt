package kotllm.template

import kotllm.*
import kotlin.test.Test
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain

class TemplateTest {

  private fun parse(t: String): Outcome<List<Part>> = Template.parse(t)

  /** Extracts the error message, or fails the test if parsing actually succeeded. */
  private fun errorMessage(result: Outcome<List<Part>>): String =
    result.fold({ it }, { throw AssertionError("expected a parse error") })

  @Test fun `plain text is a single literal`() {
    parse("hello world") shouldBe ok(listOf(Part.Literal("hello world")))
  }

  @Test fun `text with no tags needs no expansion`() {
    Template.hasTags("just prose, 50% done") shouldBe false
    Template.hasTags("a <% tag %>") shouldBe true
  }

  @Test fun `an expression tag is captured and trimmed`() {
    parse("n=<%= 1 + 1 %>!") shouldBe ok(
      listOf(Part.Literal("n="), Part.Expression("1 + 1", 1), Part.Literal("!"))
    )
  }

  @Test fun `a statement tag is captured`() {
    val parts = parse("<% val x = 1 %>done").orNull()!!
    parts.size shouldBe 2
    (parts[0] is Part.Statements) shouldBe true
    (parts[0] as Part.Statements).code.trim() shouldBe "val x = 1"
  }

  @Test fun `an empty statement tag is dropped rather than emitting nothing useful`() {
    parse("a<%  %>b") shouldBe ok(listOf(Part.Literal("a"), Part.Literal("b")))
  }

  @Test fun `an empty expression tag is an error, since it cannot mean anything`() {
    errorMessage(parse("a<%= %>b")) shouldContain "nothing to evaluate"
  }

  @Test fun `a comment is dropped but keeps its newlines, so prose keeps its shape`() {
    val parts = parse("a<%-- note\nspanning --%>b").orNull()!!
    val text = parts.filterIsInstance<Part.Literal>().joinToString("") { it.text }
    text shouldBe "a\nb"
  }

  @Test fun `a doubled delimiter escapes to a literal`() {
    parse("use <%% for a tag") shouldBe ok(listOf(Part.Literal("use <% for a tag")))
  }

  @Test fun `an unterminated tag is reported with its position`() {
    // TemplateError's line/column are folded into the Outcome's single message
    // string by the port (Outcome.Err carries only a String), so the position
    // is checked as text in the formatted message rather than as separate
    // fields the way the Scala Either[TemplateError, _] let it.
    val message = errorMessage(parse("line one\nhere <%= 1 + 1"))
    message shouldContain "unterminated"
    message shouldContain "line 2"
    message shouldContain "column 6"
  }

  @Test fun `an unterminated comment is reported`() {
    errorMessage(parse("<%-- forever")) shouldContain "unterminated"
  }

  @Test fun `tag line numbers are tracked across newlines`() {
    val parts = parse("one\ntwo\n<%= x %>").orNull()!!
    parts.filterIsInstance<Part.Expression>().firstOrNull()?.line shouldBe 3
  }

  @Test fun `multiple tags on one line are all captured`() {
    val parts = parse("<%= a %> and <%= b %>").orNull()!!
    parts.filterIsInstance<Part.Expression>().map { it.code } shouldBe listOf("a", "b")
  }

  // ---- emission ----------------------------------------------------------

  @Test fun `quoting escapes what would otherwise break the generated literal`() {
    Template.quote("a\"b") shouldBe "\"a\\\"b\""
    Template.quote("a\\b") shouldBe "\"a\\\\b\""
    Template.quote("a\nb") shouldBe "\"a\\nb\""
    Template.quote("a\tb") shouldBe "\"a\\tb\""
  }

  @Test fun `a dollar sign survives quoting, since the literal is not interpolated`() {
    Template.quote("cost is \$5") shouldBe "\"cost is \$5\""
  }

  @Test fun `triple quotes in the template do not break emission`() {
    // The reason literals are escaped rather than wrapped in triple quotes.
    val emitted = Template.emit(Template.parse("say \"\"\"this\"\"\"").orNull()!!)
    emitted.code shouldContain "\\\"\\\"\\\""
  }

  @Test fun `emitted code appends to the sink`() {
    val emitted = Template.emit(Template.parse("hi <%= 1 %>").orNull()!!)
    emitted.code shouldContain "${Template.sinkName}.append"
  }

  @Test fun `statement code is emitted verbatim, so the compiler reports on user code`() {
    val emitted = Template.emit(Template.parse("<% val files = List(1,2) %>").orNull()!!)
    emitted.code shouldContain "val files = List(1,2)"
  }

  @Test fun `the line map covers every generated line`() {
    val template = "one\ntwo\n<% val x = 1 %>\n<%= x %>\n"
    val emitted = Template.emit(Template.parse(template).orNull()!!)

    // Every generated line ends with '\n' (Template.emit appends one after
    // each addLine), so counting them is the Kotlin equivalent of Scala's
    // linesIterator.size, which does not manufacture a trailing empty entry
    // the way Kotlin's own lines() would for text ending in a newline.
    emitted.lineMap.size shouldBe emitted.code.count { it == '\n' }
    emitted.templateLineFor(1) shouldBe 1
    (emitted.lineMap.max() <= 5) shouldBe true
  }

  @Test fun `the line map points an expression at its template line`() {
    val emitted = Template.emit(Template.parse("a\nb\n<%= boom %>").orNull()!!)
    val idx = emitted.code.lines().indexOfFirst { it.contains("boom") }

    emitted.templateLineFor(idx + 1) shouldBe 3
  }
}
