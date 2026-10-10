package kotllm.engine

import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path

import kotlin.test.Test
import org.junit.jupiter.api.Assumptions
import org.junit.jupiter.api.TestInstance
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.collections.shouldNotContain

import kotllm.*

/** The Java session.
  *
  * Slow by nature: JShell launches a second JVM. One engine for the suite,
  * which is also the honest shape -- these tests are largely about state
  * surviving between calls.
  *
  * Cancelled rather than failed where `jdk.jshell` is absent, so a trimmed
  * runtime skips instead of going red on something it cannot run.
  */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class JShellEngineTest {

  private val engine by lazy { JShellEngine(emptyList(), timeoutMillis = 30_000L) }

  private fun requireJShell() {
    Assumptions.assumeTrue(Interpreters.jshellAvailable(), "jdk.jshell is not present in this runtime")
  }

  private fun sandbox(): Path = Files.createTempDirectory("kotllm-jshell")

  private fun put(root: Path, rel: String, content: String): Path {
    val p = root.resolve(rel)
    Files.write(p, content.toByteArray(StandardCharsets.UTF_8))
    return p
  }

  private fun text(p: Path) = String(Files.readAllBytes(p), StandardCharsets.UTF_8)

  // ---- basics ------------------------------------------------------------

  @Test fun `it identifies itself as the java session and starts lazily`() {
    engine.id shouldBe "java"
    // Nothing should have launched a JVM just by constructing the engine.
    JShellEngine(emptyList()).started shouldBe false
  }

  @Test fun `an expression comes back with its value`() {
    requireJShell()
    val r = engine.eval("2 + 3")
    r.ok shouldBe true
    r.toToolOutput() shouldContain "5"
  }

  @Test fun `session state persists across calls, which is the whole point`() {
    requireJShell()
    engine.eval("var counter = 40;").ok shouldBe true
    engine.eval("counter += 2;").ok shouldBe true
    engine.eval("counter").toToolOutput() shouldContain "42"
  }

  @Test fun `multi-statement input is split into snippets rather than rejected`() {
    // JShell evaluates exactly one snippet per call, so the engine has to
    // split. Guessing on ';' would break a for-loop header.
    requireJShell()
    val r = engine.eval(
      """int total = 0;
        |for (int i = 1; i <= 4; i++) { total += i; }
        |total""".trimMargin()
    )
    r.ok shouldBe true
    r.toToolOutput() shouldContain "10"
  }

  @Test fun `the default imports are installed, which the JShell API does not do itself`() {
    // The `jshell` command-line tool loads them from a startup script; the API
    // does not. Without them a model writing List.of or Files.readString --
    // exactly what it expects in a Java REPL -- fails on a bare session.
    requireJShell()
    engine.eval("""List.of("a","b").size()""").toToolOutput() shouldContain "2"
    engine.eval("Path.of(\"x\").getFileName().toString()").toToolOutput() shouldContain "x"
    engine.eval("IntStream.rangeClosed(1,3).sum()").toToolOutput() shouldContain "6"
  }

  @Test fun `printed output is captured without redirecting our own streams`() {
    // JShell pipes the child's stdout back to the PrintStream we gave it, so
    // there is no global System.out swap -- unlike IMain, where capture needs
    // both System.out and scala.Console replaced.
    requireJShell()
    engine.eval("""System.out.println("from the child");""").toToolOutput() shouldContain
      "from the child"
  }

  @Test fun `a compile error comes back as the compiler's own diagnostic`() {
    requireJShell()
    val r = engine.eval("""String s = 42;""")
    r.ok shouldBe false
    // Verbatim, the same contract as the Scala side.
    r.toToolOutput().lowercase() shouldContain "incompatible types"
  }

  @Test fun `a runtime exception is reported with its class and a short trace`() {
    requireJShell()
    val r = engine.eval("""Object o = null; o.toString()""")
    r.ok shouldBe false
    r.toToolOutput() shouldContain "NullPointerException"
  }

  @Test fun `evaluation stops at the first failure rather than compounding it`() {
    requireJShell()
    val r = engine.eval(
      """var good = 1;
        |String bad = 2;
        |var never = 3;""".trimMargin()
    )
    r.ok shouldBe false
    engine.bindings().map { it.name } shouldNotContain "never"
  }

  @Test fun `a runaway loop is actually stopped, which IMain cannot manage`() {
    // The strongest reason this engine exists. JShell runs snippets in another
    // JVM, so stop() genuinely interrupts; ScalaEngine's cooperative
    // Thread.interrupt is ignored by exactly this loop.
    requireJShell()
    val brief  = JShellEngine(emptyList(), timeoutMillis = 2000L)
    val began  = System.currentTimeMillis()
    val result = brief.eval("while (true) { }")
    val took   = System.currentTimeMillis() - began

    result.timedOut shouldBe true
    result.ok shouldBe false
    (took < 25000L) shouldBe true
    brief.close()
  }

  // ---- the data plane ----------------------------------------------------

  @Test fun `bindFile has the child read the file, and does not echo the contents`() {
    // Two reasons it is done this way: the bytes never cross the process
    // boundary, and a string literal in a class file cannot exceed 64KB, so
    // inlining the content could not compile for anything worth binding.
    requireJShell()
    val root = sandbox()
    val body = (1..400).joinToString("\n") { i -> "row $i" }
    put(root, "big.txt", body)

    val bound = engine.bindFile("log", root.resolve("big.txt"))
    bound.ok shouldBe true
    // JShell does *not* truncate values the way IMain does, so echoing here
    // would have returned the whole file.
    bound.toToolOutput() shouldNotContain "row 200"

    engine.eval("log.lines().count()").toToolOutput() shouldContain "400"
  }

  @Test fun `writeExpression writes from the session and reports the size`() {
    requireJShell()
    val root = sandbox()
    engine.eval("""var report = String.join("\n", List.of("a","b","c"));""").ok shouldBe true

    val out = engine.writeExpression(root.resolve("out.txt"), "report")
    out shouldBe ok(5L)
    text(root.resolve("out.txt")) shouldBe "a\nb\nc"
  }

  @Test fun `a non-String expression is refused by the compiler, not written as toString`() {
    // Why __KotllmIO.write takes (String, String): an Object overload would
    // accept a List and cheerfully write "[a, b]".
    requireJShell()
    val root = sandbox()
    val out  = engine.writeExpression(root.resolve("n.txt"), "List.of(1,2)")

    out.isOk shouldBe false
    val message = out.fold({ it }, { "" })
    message.lowercase() shouldContain "incompatible types"
    Files.exists(root.resolve("n.txt")) shouldBe false
  }

  @Test fun `the write replaces atomically and leaves no temp file behind`() {
    requireJShell()
    val root = sandbox()
    put(root, "t.txt", "original")
    engine.writeExpression(root.resolve("t.txt"), "\"replaced\"") shouldBe ok(8L)
    text(root.resolve("t.txt")) shouldBe "replaced"

    val listed = Files.list(root)
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

  @Test fun `bindings report names and types, and hide the harness helper`() {
    requireJShell()
    engine.eval("""var greeting = "hello";""").ok shouldBe true
    val found = engine.bindings()

    found.map { it.name } shouldContain "greeting"
    found.find { it.name == "greeting" }!!.tpe shouldBe "String"
    // __KotllmIO is plumbing; offering it would invite the model to call it.
    found.map { it.name }.forEach { (it.startsWith("__")) shouldBe false }
  }

  @Test fun `a path with backslashes survives being put into a Java literal`() {
    // Windows paths are full of them, so the escaping is not optional.
    JShellEngine.stringLiteral("C:\\tmp\\a\"b") shouldBe "\"C:\\\\tmp\\\\a\\\"b\""
  }

  @Test fun `the summary reports rendered length and never the whole value`() {
    JShellEngine.summarise("short") shouldBe "short"
    JShellEngine.summarise("x".repeat(5000)) shouldContain "chars rendered"
    JShellEngine.summarise("x".repeat(5000)) shouldNotContain "x".repeat(100)
    JShellEngine.summarise("") shouldBe "?"
  }

  @Test fun `the startup block declares the helper and the imports`() {
    JShellEngine.Startup.any { it.contains("class __KotllmIO") } shouldBe true
    JShellEngine.Startup.any { it.contains("import java.nio.file.*;") } shouldBe true
    // Fully qualified inside the helper, so it does not depend on the imports
    // above it surviving a reset.
    val helper = JShellEngine.Startup.first { it.contains("class __KotllmIO") }
    helper shouldContain "java.nio.file.StandardCopyOption.ATOMIC_MOVE"
  }
}
