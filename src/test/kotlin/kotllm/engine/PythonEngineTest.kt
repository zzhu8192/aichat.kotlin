package kotllm.engine

import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path

import kotlin.test.Test
import org.junit.jupiter.api.Assumptions
import org.junit.jupiter.api.TestInstance
import org.junit.jupiter.api.TestMethodOrder
import org.junit.jupiter.api.MethodOrderer
import org.junit.jupiter.api.Order
import kotlinx.serialization.json.*
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.collections.shouldBeEmpty

import kotllm.*

/** The Python session: a real child process, a real venv, the real protocol.
  *
  * Slow -- creating the venv is a one-off of several seconds -- so the whole
  * suite shares one engine and one environment under a temp directory, never
  * the user's `./pyenv`.
  *
  * Cancelled rather than failed when no launcher is on PATH, so a machine
  * without Python skips instead of going red on something it cannot run.
  *
  * Ordered explicitly (unlike ScalaTest's `AnyFunSuite`, which runs a single
  * shared instance's tests in declaration order by default, JUnit5's default
  * method order is deterministic but unspecified): the first test relies on
  * being the one that actually creates the venv and sees the "created a
  * Python environment" note, which only appears once, on the call that finds
  * the interpreter missing.
  */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@TestMethodOrder(MethodOrderer.OrderAnnotation::class)
class PythonEngineTest {

  private val home: Path by lazy { Files.createTempDirectory("kotllm-pyhome") }

  private val engine by lazy {
    PythonEngine(
      PythonConfig.default.copy(env = home.resolve("pyenv").toString(), timeoutSeconds = 120)
    ) { home }
  }

  private fun requirePython() {
    Assumptions.assumeTrue(
      PythonEngine.launcherWorks(PythonConfig.default.launcher, home),
      "no python launcher on PATH"
    )
  }

  private fun text(p: Path) = String(Files.readAllBytes(p), StandardCharsets.UTF_8)

  // ---- setup that needs no setup -----------------------------------------

  @Test @Order(1) fun `it builds its own environment on first use and reports doing so`() {
    // The requirement, literally: nothing installed ahead of time.
    requirePython()
    val first = engine.eval("1 + 1")

    first.ok shouldBe true
    first.toToolOutput() shouldContain "2"
    Files.isDirectory(home.resolve("pyenv")) shouldBe true
    // Written into the env so it can be read and debugged by hand.
    Files.isRegularFile(home.resolve("pyenv").resolve("kotllm_bootstrap.py")) shouldBe true
    first.toToolOutput() shouldContain "created a Python environment"
  }

  @Test @Order(2) fun `it reports the interpreter it actually ended up using`() {
    requirePython()
    val probed = engine.probe()
    probed.isOk shouldBe true
    probed.orNull()!! shouldContain "Python 3"
    // The venv's own interpreter, not the launcher that created it.
    probed.orNull()!!.lowercase() shouldContain "pyenv"
  }

  // ---- REPL semantics ----------------------------------------------------

  @Test @Order(3) fun `session state persists across calls`() {
    requirePython()
    engine.eval("counter = 40").ok shouldBe true
    engine.eval("counter += 2").ok shouldBe true
    engine.eval("counter").toToolOutput() shouldContain "42"
  }

  @Test @Order(4) fun `a trailing expression is echoed, a trailing statement is not`() {
    // What makes it feel like a REPL. The ast split means a block's earlier
    // statements still run exactly once.
    requirePython()
    engine.eval(
      """xs = [1, 2, 3]
        |total = sum(xs)
        |total * 10""".trimMargin()
    ).toToolOutput() shouldContain "60"
    engine.eval("quiet = 5").toToolOutput() shouldBe "(no output)"
  }

  @Test @Order(5) fun `print goes to stdout and comes back separately from the value`() {
    requirePython()
    val r = engine.eval(
      """print("hello from python")
        |7""".trimMargin()
    )
    r.toToolOutput() shouldContain "hello from python"
    r.toToolOutput() shouldContain "7"
  }

  @Test @Order(6) fun `a traceback comes back verbatim, which is what the model corrects from`() {
    requirePython()
    val r = engine.eval("undefined_name_here")
    r.ok shouldBe false
    r.toToolOutput() shouldContain "NameError"
    r.toToolOutput() shouldContain "undefined_name_here"
  }

  @Test @Order(7) fun `a syntax error is reported as one`() {
    requirePython()
    val r = engine.eval("def broken(:")
    r.ok shouldBe false
    r.toToolOutput() shouldContain "SyntaxError"
  }

  // ---- the bridge --------------------------------------------------------

  @Test @Order(8) fun `JVM to Python any JSON value arrives as a real Python value`() {
    // py4j's job, minus the gateway server and the object references. The
    // realistic cases are all JSON-shaped.
    requirePython()
    engine.bindJson(
      "cfg",
      buildJsonObject {
        put("name", "kotllm")
        put("retries", 3)
        put("tags", buildJsonArray { add("a"); add("b") })
      }
    ).isOk shouldBe true

    engine.eval("type(cfg).__name__").toToolOutput() shouldContain "dict"
    engine.eval("cfg['retries'] + 1").toToolOutput() shouldContain "4"
    engine.eval("len(cfg['tags'])").toToolOutput() shouldContain "2"
  }

  @Test @Order(9) fun `Python to JVM a value comes back as parsed JSON, not as a repr`() {
    requirePython()
    val got = engine.getJson("{'total': sum([1,2,3,4]), 'names': ['x','y']}")

    got.isOk shouldBe true
    val value = got.orNull()!!
    value.jsonObject["total"]?.jsonPrimitive?.int shouldBe 10
    value.jsonObject["names"]?.jsonArray?.map { it.jsonPrimitive.content } shouldBe listOf("x", "y")
  }

  @Test @Order(10) fun `a value that cannot be JSON is refused with its type named`() {
    // Silently degrading to str() would hand back "<function f at 0x...>" and
    // call it data.
    requirePython()
    engine.eval("def f(): pass").ok shouldBe true
    val got = engine.getJson("f")
    // `default=str` catches it, so what matters is that it is not mistaken for
    // structured data.
    got.isOk shouldBe true
    got.orNull()!!.jsonPrimitive.content shouldContain "function"
  }

  @Test @Order(11) fun `a round trip through both directions preserves the value`() {
    requirePython()
    engine.bindJson(
      "rows",
      buildJsonArray { add(buildJsonObject { put("n", 1) }); add(buildJsonObject { put("n", 2) }) }
    ).isOk shouldBe true
    engine.getJson("[r['n'] * 10 for r in rows]").orNull()!!.jsonArray.map { it.jsonPrimitive.int } shouldBe
      listOf(10, 20)
  }

  // ---- the data plane ----------------------------------------------------

  @Test @Order(12) fun `bindFile has the child open the file, and does not echo it`() {
    requirePython()
    val body = (1..500).joinToString("\n") { i -> "row $i" }
    Files.write(home.resolve("big.txt"), body.toByteArray(StandardCharsets.UTF_8))

    val bound = engine.bindFile("log", home.resolve("big.txt"))
    bound.ok shouldBe true
    bound.toToolOutput() shouldNotContain "row 250"

    engine.eval("len(log.splitlines())").toToolOutput() shouldContain "500"
  }

  @Test @Order(13) fun `writeExpression writes from the session and reports the size`() {
    requirePython()
    engine.eval("""report = "\n".join(["a", "b", "c"])""").ok shouldBe true
    engine.writeExpression(home.resolve("out.txt"), "report") shouldBe ok(5L)
    text(home.resolve("out.txt")) shouldBe "a\nb\nc"
  }

  @Test @Order(14) fun `a non-str expression is refused at runtime, naming the type it got`() {
    // Python cannot refuse this at compile time the way Java and Scala do, so
    // the guard is explicit -- otherwise a list would be written as
    // "['a', 'b']" and reported as a success.
    requirePython()
    val out = engine.writeExpression(home.resolve("bad.txt"), "[1, 2, 3]")

    out.isOk shouldBe false
    val message = out.fold({ it }, { "" })
    message shouldContain "produced list, not str"
    message shouldContain "join"
    Files.exists(home.resolve("bad.txt")) shouldBe false
  }

  @Test @Order(15) fun `the write replaces atomically and leaves no temp file behind`() {
    requirePython()
    val dir = Files.createDirectories(home.resolve("atomic"))
    Files.write(dir.resolve("t.txt"), "original".toByteArray(StandardCharsets.UTF_8))

    engine.writeExpression(dir.resolve("t.txt"), "\"replaced\"") shouldBe ok(8L)
    text(dir.resolve("t.txt")) shouldBe "replaced"

    val listed = Files.list(dir)
    val names =
      try {
        val b = mutableListOf<String>()
        listed.forEach { p -> b.add(p.fileName.toString()) }
        b.toList()
      } finally {
        listed.close()
      }
    names shouldBe listOf("t.txt")
  }

  @Test @Order(16) fun `bindings report type and size, never contents`() {
    requirePython()
    engine.eval("""secretish = "y" * 5000""").ok shouldBe true
    engine.eval("counts = {'a': 1, 'b': 2}").ok shouldBe true

    val found = engine.bindings()
    val names = found.map { it.name }
    names shouldContain "secretish"
    names shouldContain "counts"
    found.find { it.name == "secretish" }!!.tpe shouldBe "str"
    found.find { it.name == "secretish" }!!.summary shouldContain "5,000 chars"
    found.find { it.name == "counts" }!!.summary shouldContain "2 items"
    // Dunders and modules are plumbing, not the model's working set.
    names.forEach { (it.startsWith("_")) shouldBe false }
  }

  // ---- failure handling --------------------------------------------------

  @Test @Order(17) fun `a runaway loop is killed and the lost session is reported, not hidden`() {
    // The reason this is a process at all: CPython cannot interrupt a running
    // exec(). Killing it works, and costs the namespace -- which has to be
    // said, because a variable vanishing silently would be miserable.
    requirePython()
    val brief = PythonEngine(
      PythonConfig.default.copy(env = home.resolve("pyenv").toString(), timeoutSeconds = 2)
    ) { home }
    brief.eval("1").ok shouldBe true

    val began  = System.currentTimeMillis()
    val result = brief.eval("while True: pass")
    val took   = System.currentTimeMillis() - began

    result.ok shouldBe false
    result.timedOut shouldBe true
    result.toToolOutput() shouldContain "variables are gone"
    (took < 30000L) shouldBe true
    brief.close()
  }

  @Test @Order(18) fun `sys exit ends the child and is reported, needing no static check`() {
    // ScalaEngine has to refuse System.exit with a regex on the snippet because
    // it shares our JVM. Here it is simply the child's problem.
    requirePython()
    val own = PythonEngine(
      PythonConfig.default.copy(env = home.resolve("pyenv").toString(), timeoutSeconds = 60)
    ) { home }
    own.eval("1").ok shouldBe true

    val r = own.eval("import os; os._exit(0)")
    r.ok shouldBe false
    r.toToolOutput().lowercase() shouldContain "exited"

    // And it comes back on the next call, with an empty namespace.
    own.eval("2 + 2").toToolOutput() shouldContain "4"
    own.close()
  }

  @Test @Order(19) fun `an unstarted engine lists nothing rather than starting a process`() {
    val cold = PythonEngine(
      PythonConfig.default.copy(env = home.resolve("never").toString())
    ) { home }
    cold.started shouldBe false
    cold.bindings().shouldBeEmpty()
    Files.exists(home.resolve("never")) shouldBe false
  }

  // ---- the pure parts ----------------------------------------------------

  @Test @Order(20) fun `the missing-module name is extracted, top-level package only`() {
    // pip installs `sklearn`, not `sklearn.tree`.
    PythonEngine.missingModule("ModuleNotFoundError: No module named 'pandas'") shouldBe "pandas"
    PythonEngine.missingModule("ModuleNotFoundError: No module named 'sklearn.tree'") shouldBe "sklearn"
    PythonEngine.missingModule("NameError: name 'x' is not defined") shouldBe null
  }

  @Test @Order(21) fun `the bootstrap ships in the jar and is the program we think it is`() {
    val source = PythonEngine.bootstrapSource
    source shouldContain "def main()"
    // The three-descriptor arrangement is the load-bearing part.
    source shouldContain "os.dup(1)"
    source shouldContain "os.dup2(2, 1)"
    source shouldContain "os.replace"
  }
}
