package kotllm.engine

import kotlin.test.Test
import io.kotest.assertions.withClue
import io.kotest.matchers.shouldBe

/** The rewrite that replaced the refusal.
  *
  * Driven directly rather than through the interpreter, because a missed
  * rewrite is not something a unit test can survive discovering: a real
  * `System.exit` takes the test JVM with it.
  */
class ExitGuardTest {

  private fun defused(code: String): Boolean = ExitGuard.wouldDefuse(code)

  @Test fun `every shape of exit call is rewritten`() {
    listOf(
      "sys.exit(0)",
      "sys.exit()",
      "sys.exit",
      "System.exit(1)",
      "System.exit( 2 )",
      "Runtime.getRuntime().halt(1)",
      "Runtime.getRuntime().exit(1)",
      "Runtime.getRuntime() . halt (1)",
      "if (bad) sys.exit(1) else ()",
      "println(1); System.exit(0)"
    ).forEach { c -> withClue("$c: ") { defused(c) shouldBe true } }
  }

  @Test fun `a mention inside a string or comment is rewritten too, and that is accepted`() {
    // Not a bug being enshrined: the guard is a flat regex replace, so prose
    // that looks like a call is rewritten with it. The input is always code the
    // model just wrote, so the cost is a string that reads oddly, and the
    // alternative was a hand-rolled lexer for a case that does not arise.
    listOf(
      "val advice = \"do not call System.exit or sys.exit\"",
      "// System.exit(0) would end the session",
      "println(\"sys.exit is refused\")"
    ).forEach { c -> withClue("$c: ") { defused(c) shouldBe true } }
  }

  @Test fun `a name that merely ends in exit is left alone`() {
    listOf(
      "val exitCode = 0",
      "case class R(exit: Int); R(1).exit",
      "myApp.System.exit",
      "result.sys.exitCode",
      "process.exitValue()"
    ).forEach { c -> withClue("$c: ") { defused(c) shouldBe false } }
  }

  @Test fun `the rewrite targets something that exists and returns Unit`() {
    // If the substitute did not resolve, every defused snippet would become a
    // compile error instead of a no-op -- a worse failure than the refusal it
    // replaced, and one only an end-to-end test would otherwise catch.
    ExitGuard.defuse("sys.exit(3)") shouldBe "kotllm.engine.ExitGuard.exit(3)"
    ExitGuard.exit(3) shouldBe Unit
  }
}
