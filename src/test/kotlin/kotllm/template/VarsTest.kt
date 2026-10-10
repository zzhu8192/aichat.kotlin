package kotllm.template

import kotllm.*
import kotlin.test.Test
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain

class VarsTest {

  private val table: Map<String, () -> String> = mapOf(
    "model" to { "qwen35-122b" },
    "pwd" to { "D:/work" },
    "endpoint" to { "https://host/serving-endpoints" },
    "uptime" to { "4m 12s" }
  )

  private fun expand(t: String): Outcome<String> = Vars.expand(t, table)

  /** Extracts the error message, or fails the test if expansion actually succeeded. */
  private fun errorMessage(result: Outcome<String>): String =
    result.fold({ it }, { throw AssertionError("expected an unknown-variable error") })

  @Test fun `a reference is substituted`() {
    expand("running on {{model}}") shouldBe ok("running on qwen35-122b")
  }

  @Test fun `several references in one line are all substituted`() {
    expand("{{model}} at {{endpoint}}") shouldBe
      ok("qwen35-122b at https://host/serving-endpoints")
  }

  @Test fun `whitespace inside the braces is allowed`() {
    expand("{{ model }}") shouldBe ok("qwen35-122b")
  }

  @Test fun `text with no references is returned untouched`() {
    expand("nothing to do here") shouldBe ok("nothing to do here")
  }

  @Test fun `an unknown variable is an error, not a silent pass-through`() {
    // Leaving {{modle}} in the prompt would look like the feature was broken.
    val message = errorMessage(expand("{{modle}}"))
    message shouldContain "unknown harness variable"
    message shouldContain "{{modle}}"
  }

  @Test fun `a near-miss gets a suggestion`() {
    errorMessage(expand("{{modl}}")) shouldContain "did you mean {{model}}?"
  }

  @Test fun `all unknown names are reported together, not one per attempt`() {
    val message = errorMessage(expand("{{nope1}} {{nope2}}"))
    message shouldContain "nope1"
    message shouldContain "nope2"
  }

  @Test fun `braces around prose are left alone rather than guessed at`() {
    // Handlebars meant for something else, or just prose.
    expand("use {{ this is not a var }} here") shouldBe
      ok("use {{ this is not a var }} here")
    expand("JSON like {{\"a\": 1}} stays") shouldBe ok("JSON like {{\"a\": 1}} stays")
  }

  @Test fun `a single brace is never a reference`() {
    expand("{model} stays") shouldBe ok("{model} stays")
  }

  @Test fun `a backslash escapes the braces to a literal`() {
    expand("""literally \{{model}} here""") shouldBe ok("literally {{model}} here")
  }

  @Test fun `an escaped reference does not count as unknown`() {
    expand("""\{{whatever}}""") shouldBe ok("{{whatever}}")
  }

  @Test fun `a value containing a template tag is neutralised, so a fact cannot become code`() {
    // cmdline could plausibly contain <%, and substitution runs before
    // templating, so an unguarded value would be executed as Scala.
    val hostile: Map<String, () -> String> = mapOf("cmdline" to { "kotllm \"<%= sys.exit(1) %>\"" })
    val out = Vars.expand("ran: {{cmdline}}", hostile).orNull()!!

    out shouldContain "<%%"
    out shouldNotContain "<%="
  }

  @Test fun `a variable that throws degrades instead of failing the prompt`() {
    val broken: Map<String, () -> String> = mapOf("host" to { throw RuntimeException("no dns") })
    Vars.expand("on {{host}}", broken).orNull()!! shouldContain "unavailable"
  }

  @Test fun `a null value becomes empty rather than the string null`() {
    // Vars.expand's table is typed as Map<String, () -> String> (non-null),
    // but Vars.safely still guards with `thunk() ?: ""`, so the null case the
    // Scala source tested is reachable at runtime through an unchecked cast --
    // exactly as it would be if a caller outside this module's type-checking
    // (e.g. a Java caller) supplied one.
    val nullLambda: () -> String? = { null }
    @Suppress("UNCHECKED_CAST")
    val nullThunk = nullLambda as () -> String
    val nully: Map<String, () -> String> = mapOf("x" to nullThunk)
    Vars.expand("[{{x}}]", nully) shouldBe ok("[]")
  }

  @Test fun `references are detected cheaply before any work`() {
    Vars.hasReferences("plain") shouldBe false
    Vars.hasReferences("has {{model}}") shouldBe true
  }

  @Test fun `suggestions only fire for genuinely close names`() {
    Vars.suggest("modl", table.keys.toList()) shouldBe "model"
    Vars.suggest("completely_different_thing", table.keys.toList()) shouldBe null
  }

  @Test fun `multi-line text keeps its shape`() {
    expand("line one\n{{model}}\nline three") shouldBe
      ok("line one\nqwen35-122b\nline three")
  }
}
