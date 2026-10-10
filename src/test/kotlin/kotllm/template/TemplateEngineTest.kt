package kotllm.template

import kotllm.*
import kotllm.engine.Classpath
import kotllm.engine.GroovyEngine
import kotlin.test.Test
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.TestInstance

/** Expansion against the real interpreter.
  *
  * One engine for the suite, both because starting a compiler is slow and
  * because sharing state between templates is itself a behaviour under test.
  */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class TemplateEngineTest {

  private val engine by lazy { GroovyEngine(emptyList(), timeoutMillis = 20_000L) }
  private val template by lazy { TemplateEngine(engine) }

  @AfterAll
  fun afterAll() {
    engine.close()
  }

  /** Extracts the error message, or fails the test if rendering actually succeeded. */
  private fun errorMessage(result: Outcome<String>): String =
    result.fold({ it }, { throw AssertionError("expected a rendering error") })

  @Test fun `text with no tags is returned untouched, without starting the compiler`() {
    val fresh = GroovyEngine(emptyList())
    try {
      TemplateEngine(fresh).render("just a normal prompt") shouldBe ok("just a normal prompt")
      // The common case must not pay for a compiler.
      fresh.started shouldBe false
    } finally {
      fresh.close()
    }
  }

  @Test fun `an expression is evaluated and interpolated`() {
    template.render("2 + 2 = <%= 2 + 2 %>") shouldBe ok("2 + 2 = 4")
  }

  @Test fun `statements run without emitting anything themselves`() {
    template.render("<% n = 21 %>answer: <%= n * 2 %>") shouldBe ok("answer: 42")
  }

  @Test fun `a loop can emit repeated text`() {
    val out = template.render("<% for (i in 1..3) { %>line <%= i %>\n<% } %>")
    out shouldBe ok("line 1\nline 2\nline 3\n")
  }

  @Test fun `state persists across renders, and is shared with the interpreter`() {
    template.render("<% shared = 99 %>ok") shouldBe ok("ok")
    template.render("<%= shared + 1 %>") shouldBe ok("100")
    // The same session groovy_eval uses, so a template value is visible there.
    engine.eval("shared * 2").toToolOutput() shouldContain "198"
  }

  @Test fun `bundled libraries are usable inside a template`() {
    val out = template.render("""<%= JsonOutput.toJson([k: 1]) %>""")
    out.orNull()!! shouldContain "\"k\""
  }

  @Test fun `multi-line output keeps its shape`() {
    template.render("a\n<%= 1 %>\nb") shouldBe ok("a\n1\nb")
  }

  @Test fun `a null value renders as null rather than throwing`() {
    template.render("<%= null %>") shouldBe ok("null")
  }

  @Test fun `a collection renders with its normal toString`() {
    template.render("<%= [1, 2, 3] %>") shouldBe ok("[1, 2, 3]")
  }

  @Test fun `an escaped delimiter reaches the output as a literal, with no compiler run`() {
    template.render("write <%% like this") shouldBe ok("write <% like this")
  }

  @Test fun `a comment-only template needs no evaluation`() {
    template.render("before<%-- hidden --%>after") shouldBe ok("beforeafter")
  }

  @Test fun `a parse error is reported against the template, not the generated code`() {
    val message = errorMessage(template.render("oops <%= 1 + "))
    message shouldContain "template error"
    message shouldContain "unterminated"
  }

  @Test fun `a compile error names the template line and shows the annotated program`() {
    val message = errorMessage(template.render("line one\nline two\n<%= notARealSymbol %>"))

    message.lowercase() shouldContain "no such property"
    // The generated program is included, annotated with template lines, since
    // the user never wrote that code and a raw diagnostic into it is useless.
    message shouldContain "generated program"
    message shouldContain "your template line"
  }

  @Test fun `a runtime failure inside a template is reported, not swallowed`() {
    val message = errorMessage(template.render("""<%= throw IllegalStateException("boom") %>"""))
    message shouldContain "boom"
  }

  @Test fun `the engine survives a failed template and keeps working`() {
    (!template.render("<%= alsoNotReal %>").isOk) shouldBe true
    template.render("<%= 1 + 1 %>") shouldBe ok("2")
  }

  @Test fun `a large template is not truncated`() {
    // Appending to a bound sink rather than capturing stdout is what makes
    // this safe: captured output is length-capped, a sink is not.
    val out = template.render("""<%= "x".repeat(50000) %>""")
    out.orNull()!!.length shouldBe 50000
  }

  @Test fun `templates can read the filesystem, which is the point of the feature`() {
    val file = java.nio.file.Files.createTempFile("kotllm-template", ".txt")
    java.nio.file.Files.write(file, "from disk".toByteArray(Charsets.UTF_8))
    val path = file.toString().replace("\\", "/")

    val out = template.render("""contents: <%= Files.readString(Paths.get("$path")) %>""")
    out shouldBe ok("contents: from disk")
  }
}
