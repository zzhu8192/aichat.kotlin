package kotllm.tools

import kotlin.test.Test
import org.junit.jupiter.api.Assumptions
import org.junit.jupiter.api.TestInstance
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.collections.shouldNotContain

import kotlinx.serialization.json.*

import kotllm.*
import kotllm.engine.Classpath
import kotllm.engine.Interpreter
import kotllm.engine.Interpreters
import kotllm.engine.JShellEngine
import kotllm.engine.PythonConfig
import kotllm.engine.PythonEngine
import kotllm.engine.GroovyEngine

/** `session_transfer`: a value crossing between interpreters with no file and
  * no conversation.
  *
  * The bridge underneath this existed for a whole increment with only a test
  * calling it, so the model's only route between sessions was a temp file.
  * These tests are as much about that gap staying closed as about the
  * mechanics.
  *
  * One engine of each kind for the suite, the same reason `JShellEngineTest`
  * gives: starting a compiler or a child process costs real time, and these
  * tests are largely about state surviving between calls anyway.
  */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class SessionTransferTest {

    private val groovyEngine by lazy { GroovyEngine(emptyList(), 60_000L) }

    private val python by lazy {
        PythonEngine(
            PythonConfig.default.copy(
                env = java.nio.file.Files.createTempDirectory("kotllm-xfer").resolve("pyenv").toString(),
                timeoutSeconds = 120
            )
        ) { java.nio.file.Paths.get(".").toAbsolutePath() }
    }

    private val jshellEngine by lazy { JShellEngine(emptyList(), timeoutMillis = 60_000L) }

    private fun args(vararg pairs: Pair<String, String>): JsonElement =
        buildJsonObject { pairs.forEach { (k, v) -> put(k, v) } }

    // Collapsed from the Scala source's two parameter lists (`transfer(engines)(from, to,
    // expr, name)`) into one: Kotlin has no currying, and there is no lambda argument here
    // for a trailing-call shape to preserve.
    private fun transfer(engines: List<Interpreter>, from: String, to: String, expr: String, name: String): ToolResult =
        Tools.sessionTransfer(engines).run(
            args("from" to from, "to" to to, "expression" to expr, "variable" to name)
        )

    private fun requirePython() {
        Assumptions.assumeTrue(
            PythonEngine.launcherWorks(PythonConfig.default.launcher, java.nio.file.Paths.get(".").toAbsolutePath()),
            "no python launcher on PATH"
        )
    }

    private fun requireJava() {
        Assumptions.assumeTrue(Interpreters.jshellAvailable(), "jdk.jshell is not present")
    }

    // ---- the point ---------------------------------------------------------

    @Test fun `groovy to python structure survives, and arrives native`() {
        // The file route would deliver a String that Python has to json.loads.
        requirePython()
        groovyEngine.eval(
            """summary = JsonOutput.toJson([total: 42, names: ["a", "b"]])"""
        ).ok shouldBe true

        val r = transfer(listOf(groovyEngine, python), "groovy", "python", "summary", "cfg")
        r.isError shouldBe false
        r.content shouldContain "native"

        python.eval("type(cfg).__name__").toToolOutput() shouldContain "dict"
        python.eval("cfg['total'] + 1").toToolOutput() shouldContain "43"
        python.eval("len(cfg['names'])").toToolOutput() shouldContain "2"
    }

    @Test fun `python to groovy arrives as a native Map or List, not text to re-parse`() {
        requirePython()
        python.eval("rows = [{'n': 1}, {'n': 2}, {'n': 3}]").ok shouldBe true

        val r = transfer(listOf(groovyEngine, python), "python", "groovy", "json.dumps(rows)", "fromPy")
        r.isError shouldBe false
        r.content shouldContain "native Groovy Map/List"

        // Usable as structure immediately -- no parse step on the Groovy side.
        groovyEngine.eval("""fromPy.sum { it.n }""")
            .toToolOutput() shouldContain "6"
    }

    @Test fun `the value never touches the conversation, only its shape`() {
        requirePython()
        python.eval("secretish = {'k': 'y' * 4000}").ok shouldBe true

        val r = transfer(listOf(groovyEngine, python), "python", "groovy", "json.dumps(secretish)", "big")
        r.isError shouldBe false
        (r.content.length < 200) shouldBe true
        r.content shouldNotContain "y".repeat(50)
        // But it did arrive.
        groovyEngine.eval("""big.k.length()""")
            .toToolOutput() shouldContain "4000"
    }

    @Test fun `nothing is left on disk, which the file route could not promise`() {
        requirePython()
        val before = java.io.File(System.getProperty("java.io.tmpdir")).list()
            ?.count { it.startsWith(".kotllm-") } ?: 0

        python.eval("payload = {'a': 1}").ok shouldBe true
        transfer(listOf(groovyEngine, python), "python", "groovy", "json.dumps(payload)", "p").isError shouldBe false

        val after = java.io.File(System.getProperty("java.io.tmpdir")).list()
            ?.count { it.startsWith(".kotllm-") } ?: 0
        after shouldBe before
    }

    // ---- java, whose legs both go through a temp file internally -----------

    @Test fun `java receives JSON as a String, since the JDK has no parser`() {
        requireJava()
        groovyEngine.eval("""forJava = JsonOutput.toJson([greeting: "hello"])""").ok shouldBe true

        val r = transfer(listOf(groovyEngine, jshellEngine), "groovy", "java", "forJava", "payload")
        r.isError shouldBe false
        // Said plainly rather than pretending to bind an object.
        r.content shouldContain "String of JSON"

        jshellEngine.eval("payload.contains(\"greeting\")").toToolOutput() shouldContain "true"
    }

    @Test fun `java to groovy round trips through the same contract`() {
        requireJava()
        jshellEngine.eval("""var out = "{\"n\":7}";""").ok shouldBe true

        transfer(listOf(groovyEngine, jshellEngine), "java", "groovy", "out", "fromJava").isError shouldBe false
        groovyEngine.eval("""fromJava.n""").toToolOutput() shouldContain "7"
    }

    @Test fun `the python session has the stdlib in scope, which the contract requires`() {
        // Found by this feature failing: `_exec` runs with globals=_NS, so a bare
        // namespace made `json.dumps(x)` raise NameError -- and json.dumps is
        // exactly what session_transfer tells the model to write. The same bug as
        // the JShell API installing none of the default imports its own CLI does.
        requirePython()
        python.eval("json.dumps({'a': 1})").toToolOutput() shouldContain "a"
        python.eval("re.findall(r'[0-9]+', 'a1b22')").toToolOutput() shouldContain "22"
        python.eval("collections.Counter('aab')['a']").toToolOutput() shouldContain "2"
    }

    @Test fun `seeded modules are not listed as the model's working set`() {
        // Filtered by type rather than by name, so adding one to the seed cannot
        // start listing plumbing.
        requirePython()
        python.eval("myOwnValue = 5").ok shouldBe true
        val names = python.bindings().map { it.name }
        names shouldContain "myOwnValue"
        names shouldNotContain "json"
        names shouldNotContain "collections"
    }

    // ---- refusals ----------------------------------------------------------

    @Test fun `an expression that is not JSON is refused, naming what it should produce`() {
        val r = transfer(listOf(groovyEngine, jshellEngine), "groovy", "java", "\"just a bare string\"", "x")
        r.isError shouldBe true
        r.content shouldContain "not JSON"
    }

    @Test fun `an expression that is not a String is refused by the source engine`() {
        // The same guard write_from_eval applies, reached through the same path.
        val r = transfer(listOf(groovyEngine, jshellEngine), "groovy", "java", "42", "x")
        r.isError shouldBe true
        r.content.lowercase() shouldContain "groovy session"
    }

    @Test fun `transferring to the same session is refused as the no-op it is`() {
        val r = transfer(listOf(groovyEngine, jshellEngine), "groovy", "groovy", "\"{}\"", "x")
        r.isError shouldBe true
        r.content shouldContain "already there"
    }

    @Test fun `an unknown session names the ones that exist`() {
        transfer(listOf(groovyEngine), "groovy", "ruby", "x", "y").content shouldContain "unknown session"
    }

    @Test fun `an illegal identifier is refused before anything is evaluated`() {
        transfer(listOf(groovyEngine, jshellEngine), "groovy", "java", "\"{}\"", "not an identifier")
            .content shouldContain "identifier"
    }

    @Test fun `it writes to a session, so it is not read-only`() {
        Tools.sessionTransfer(listOf(groovyEngine, jshellEngine)).readOnly shouldBe false
        Tools.sessionTransfer(listOf(groovyEngine, jshellEngine)).name shouldBe Tools.TransferName
    }

    @Test fun `registered only when there are two sessions to move between`() {
        // With one session the tool could only ever refuse.
        val memory = kotllm.memory.Memory(
            kotllm.memory.MemoryConfig.default,
            java.nio.file.Files.createTempDirectory("kotllm-xfer2")
        )
        val alone =
            Tools.defaults(GroovyEngine(emptyList()), 10, memory, null, transferTool = true).map { it.name }
        alone shouldNotContain Tools.TransferName

        val pair = Tools
            .defaults(
                GroovyEngine(emptyList()), 10, memory, null,
                java = JShellEngine(emptyList()), transferTool = true
            )
            .map { it.name }
        pair shouldContain Tools.TransferName

        // Two sessions is necessary but no longer sufficient: the tool is ~219
        // tokens of schema on every request for a value move that a temp file
        // also does, so it is opt-in via `session_transfer`.
        val notAskedFor = Tools
            .defaults(GroovyEngine(emptyList()), 10, memory, null, java = JShellEngine(emptyList()))
            .map { it.name }
        notAskedFor shouldNotContain Tools.TransferName
    }

    // ---- the SPI underneath ------------------------------------------------

    @Test fun `evalToString gets a value out of kotlin without the transcript or a file`() {
        // An echoed value is truncated in the transcript, so it cannot carry a
        // payload. This is the path that avoids it.
        val big = "z".repeat(5000)
        // A plain escaped string rather than a raw one: a raw string ending in an
        // interpolated value followed immediately by a literal quote and the
        // closing triple-quote is four quote characters in a row, which the
        // lexer reads as content-quote-then-delimiter only with an explicit
        // ${'"'} escape -- simpler to avoid the shape entirely here.
        groovyEngine.eval("held = \"$big\"").ok shouldBe true

        groovyEngine.evalToString("held") shouldBe ok(big)
    }

    @Test fun `evalToString refuses a non-String the way writeExpression does`() {
        groovyEngine.evalToString("[1,2,3]").isOk shouldBe false
    }
}
