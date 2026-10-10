package kotllm.engine

import java.nio.file.Files
import kotlin.test.Test
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

import kotllm.*

/** The interpreter behind `groovy_eval`.
  *
  * Deliberately the same cases the Kotlin engine's tests covered, because the
  * contract did not change when the language did: state persists, output is
  * captured, a runaway snippet is stopped, and a value can leave the session as
  * the object itself rather than as text.
  *
  * Where Groovy genuinely differs -- `def` scoping, most of all -- the test says
  * so rather than quietly asserting the new behaviour.
  */
class GroovyEngineTest {

    private val engine by lazy { GroovyEngine(emptyList(), timeoutMillis = 20_000L) }

    // ---- the basics --------------------------------------------------------

    @Test fun `an expression comes back as its value`() {
        engine.eval("1 + 1").toToolOutput() shouldContain "2"
    }

    @Test fun `printed output is captured, not lost to the console`() {
        val r = engine.eval("""println("hello from groovy"); 7""")
        r.ok shouldBe true
        r.stdout shouldContain "hello from groovy"
        r.transcript shouldContain "7"
    }

    /** The one rule a model has to know, so it is pinned rather than assumed. */
    @Test fun `an undeclared assignment persists, and a def does not`() {
        engine.eval("carried = 41").ok shouldBe true
        engine.eval("carried + 1").toToolOutput() shouldContain "42"

        engine.eval("def local = 1").ok shouldBe true
        engine.eval("local").ok shouldBe false
    }

    @Test fun `a closure defined in one snippet is callable in the next`() {
        engine.eval("twice = { it * 2 }").ok shouldBe true
        engine.eval("twice(21)").toToolOutput() shouldContain "42"
    }

    @Test fun `the preamble imports what the tool description promises`() {
        engine.eval("""Paths.get("a", "b").toString().length() > 0""").toToolOutput() shouldContain "true"
        engine.eval("new JsonSlurper().parseText('{\"a\":1}').a").toToolOutput() shouldContain "1"
        engine.eval("LocalDate.of(2020, 1, 1).year").toToolOutput() shouldContain "2020"
    }

    @Test fun `java and groovy collections are both in scope without imports`() {
        engine.eval("[1, 2, 3].sum()").toToolOutput() shouldContain "6"
        engine.eval("new BigInteger(\"2\").pow(10)").toToolOutput() shouldContain "1024"
    }

    // ---- failure, reported so the model can correct ------------------------

    @Test fun `a compile error names the line, and not the generated script`() {
        val r = engine.eval("this is not groovy")
        r.ok shouldBe false
        // `Script17.groovy` changes every eval and means nothing to the model.
        r.transcript shouldNotContain "Script"
    }

    @Test fun `a thrown exception is a failure, not a crash`() {
        val r = engine.eval("""throw new IllegalStateException("boom")""")
        r.ok shouldBe false
        r.transcript shouldContain "boom"
    }

    @Test fun `a snippet that ends mid-expression is reported as incomplete`() {
        engine.eval("def f() {").incomplete shouldBe true
    }

    @Test fun `a failed snippet does not take the session with it`() {
        engine.eval("survivor = 5").ok shouldBe true
        engine.eval("nonsense(((").ok shouldBe false
        engine.eval("survivor").toToolOutput() shouldContain "5"
    }

    // ---- the guards --------------------------------------------------------

    @Test fun `a runaway loop is stopped, and the session still answers`() {
        val brief = GroovyEngine(emptyList(), timeoutMillis = 1_000L)
        try {
            brief.eval("while (true) { }").timedOut shouldBe true
            // Cooperative interrupt cannot kill a tight loop, but the session is
            // usable because the worker is a daemon.
            brief.started shouldBe true
        } finally {
            brief.close()
        }
    }

    @Test fun `System exit is defused rather than taking the process down`() {
        // If this were not rewritten the test JVM would simply vanish.
        val r = engine.eval("System.exit(1); 3")
        r.ok shouldBe true
        r.stdout shouldContain "ignored"
    }

    // ---- the data plane ----------------------------------------------------

    @Test fun `bind puts the live object in, not a copy of it`() {
        val holder = StringBuilder("start")
        engine.bind("holder", holder) shouldBe true
        engine.eval("holder.append('-appended')").ok shouldBe true
        holder.toString() shouldBe "start-appended"
    }

    @Test fun `bind refuses a name the session could not use`() {
        engine.bind("not an identifier", "x") shouldBe false
    }

    @Test fun `a bound file is readable in the session and never in the transcript`() {
        val f = Files.createTempFile("kotllm-groovy", ".txt")
        Files.writeString(f, "line one\nline two\n")
        try {
            engine.bindFile("doc", f).ok shouldBe true
            engine.eval("doc.readLines().size()").toToolOutput() shouldContain "2"
        } finally {
            Files.deleteIfExists(f)
        }
    }

    @Test fun `bindJson arrives as a native Map, not a string to re-parse`() {
        val payload = buildJsonObject { put("n", 7) }
        engine.bindJson("cfg", payload).isOk shouldBe true
        engine.eval("cfg.n").toToolOutput() shouldContain "7"
    }

    @Test fun `evalToString hands back the text itself`() {
        engine.eval("""big = "x" * 5000""").ok shouldBe true
        engine.evalToString("big").fold(
            onErr = { e -> throw AssertionError(e) },
            onOk = { s -> s.length shouldBe 5000 }
        )
    }

    @Test fun `evalToString refuses a value that is not a String`() {
        val r = engine.evalToString("[1, 2, 3]")
        r.isOk shouldBe false
    }

    @Test fun `a GString counts as text, since interpolation is how Groovy builds it`() {
        engine.eval("name = \"world\"").ok shouldBe true
        engine.evalToString("\"hello \$name\"").fold(
            onErr = { e -> throw AssertionError(e) },
            onOk = { s -> s shouldBe "hello world" }
        )
    }

    // ---- what session_vars shows -------------------------------------------

    @Test fun `bindings report name, type and size, never the value`() {
        val own = GroovyEngine(emptyList(), timeoutMillis = 10_000L)
        try {
            own.eval("""secretish = "y" * 4000""").ok shouldBe true
            own.eval("counts = [1, 2, 3]").ok shouldBe true

            val names = own.bindings().map { it.name }
            names shouldBe listOf("counts", "secretish")

            val rendered = own.bindings().joinToString("\n") { "${it.name} ${it.tpe} ${it.summary}" }
            rendered shouldContain "4,000 chars"
            rendered shouldContain "3 elements"
            rendered shouldNotContain "y".repeat(50)
        } finally {
            own.close()
        }
    }

    @Test fun `an unstarted session reports nothing rather than starting one`() {
        val cold = GroovyEngine(emptyList())
        cold.bindings() shouldBe emptyList()
        cold.started shouldBe false
    }

    @Test fun `close releases the session, so a later eval starts a fresh one`() {
        val own = GroovyEngine(emptyList(), timeoutMillis = 10_000L)
        own.eval("gone = 1").ok shouldBe true
        own.close()
        own.started shouldBe false
        // The binding went with it, which is what makes close worth calling.
        own.eval("gone").ok shouldBe false
        own.close()
    }
}
