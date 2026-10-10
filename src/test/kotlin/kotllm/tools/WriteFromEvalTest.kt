package kotllm.tools

import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path

import kotlin.test.Test
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain

import kotlinx.serialization.json.*

import kotllm.engine.Classpath
import kotllm.engine.GroovyEngine

/** `write_from_eval` and `session_vars`: the other two halves of using the
  * interpreter as a data plane, plus the atomic replacement all three writers
  * now share.
  */
class WriteFromEvalTest {

    private val limits = FileToolLimits.default
    private val memo = FileMemo()

    private val engine: GroovyEngine by lazy { GroovyEngine(emptyList(), 30000L) }

    private fun sandbox(): Path = Files.createTempDirectory("kotllm-wfs")

    private fun put(root: Path, rel: String, content: String): Path {
        val p = root.resolve(rel)
        Files.createDirectories(p.parent)
        Files.write(p, content.toByteArray(StandardCharsets.UTF_8))
        return p
    }

    private fun text(p: Path) = String(Files.readAllBytes(p), StandardCharsets.UTF_8)

    private fun args(vararg pairs: Pair<String, JsonElement>): JsonElement = buildJsonObject {
        pairs.forEach { (k, v) -> put(k, v) }
    }

    private fun lang(id: String): JsonElement = args("language" to JsonPrimitive(id))

    private fun writer(root: Path) = Tools.writeFromEval(listOf(engine), memo, { root })

    private fun write(root: Path, path: String, expr: String) =
        writer(root).run(
            args(
                "file_path" to JsonPrimitive(path), "expression" to JsonPrimitive(expr),
                "language" to JsonPrimitive("groovy")
            )
        )

    // ---- the round trip ----------------------------------------------------

    @Test fun `a computed value reaches disk without appearing in the result`() {
        val root = sandbox()
        engine.eval("""report = (1..2000).collect { i -> "row ${'$'}i" }.join("\n")""").ok shouldBe true

        val r = write(root, "out.txt", "report")

        r.isError shouldBe false
        r.content shouldContain "out.txt"
        // The point: a 20KB payload, and the model is told only how much went out.
        (r.content.length < 120) shouldBe true
        r.content shouldNotContain "row 1999"

        val written = text(root.resolve("out.txt"))
        written.lines().size shouldBe 2000
        written shouldContain "row 2000"
    }

    @Test fun `an expression, not just a variable name, so no throwaway val is needed`() {
        // Naming the tool `write_from_var` would have pushed the model to bind an
        // intermediate first, and a top-level val echoes 800 characters of itself.
        val root = sandbox()
        engine.eval("nums = (1..5).toList()").ok shouldBe true

        write(root, "sum.txt", """nums.collect { (it * 2).toString() }.join(",")""").isError shouldBe false
        text(root.resolve("sum.txt")) shouldBe "2,4,6,8,10"
    }

    @Test fun `read in, transform, write back to the same path`() {
        // The workflow the whole feature exists for, and the one the earlier
        // seen-guard decision was blocking.
        val root = sandbox()
        put(root, "data.csv", "id,qty\n1,5\n2,7")

        Tools
            .readToVar(listOf(engine), limits, { root }, memo)
            .run(
                args(
                    "file_path" to JsonPrimitive("data.csv"), "variable" to JsonPrimitive("src"),
                    "language" to JsonPrimitive("groovy")
                )
            )
            .isError shouldBe false

        write(root, "data.csv", """src.readLines().collect { it.toUpperCase() }.join("\n")""")
            .isError shouldBe false
        text(root.resolve("data.csv")) shouldContain "ID,QTY"
    }

    @Test fun `a multi-statement block works, since the expression is wrapped`() {
        val root = sandbox()
        write(root, "b.txt", """def a = 2; def b = 3; (a * b).toString()""").isError shouldBe false
        text(root.resolve("b.txt")) shouldBe "6"
    }

    // ---- refusals ------------------------------------------------------------

    @Test fun `a non-String expression comes back as the compiler's own diagnostic`() {
        // Verbatim, like everywhere else: a type mismatch is something the model
        // corrects itself from.
        val root = sandbox()
        val r = write(root, "n.txt", "42")

        r.isError shouldBe true
        r.content.lowercase() shouldContain "string"
        Files.exists(root.resolve("n.txt")) shouldBe false
    }

    @Test fun `a collection is refused rather than silently written as its toString`() {
        // The reason FileSink.write takes String and not Any: an Any overload
        // would have written `List(a, b)` and called it a success.
        val root = sandbox()
        engine.eval("""items = ["a", "b"]""").ok shouldBe true

        write(root, "c.txt", "items").isError shouldBe true
        Files.exists(root.resolve("c.txt")) shouldBe false
    }

    @Test fun `an expression that does not compile leaves no file behind`() {
        val root = sandbox()
        write(root, "d.txt", "this is not groovy at all (((").isError shouldBe true
        Files.exists(root.resolve("d.txt")) shouldBe false
    }

    @Test fun `overwriting an unseen file is refused, so the write guard has no bypass`() {
        // Without this, "cannot overwrite a file you have not read" would have been
        // one tool call away from being false.
        val root = sandbox()
        put(root, "precious.txt", "important")

        val r = write(root, "precious.txt", "\"clobbered\"")
        r.isError shouldBe true
        r.content shouldContain "sight unseen"
        r.content shouldContain "read_to_var"
        text(root.resolve("precious.txt")) shouldBe "important"
    }

    @Test fun `a new path needs no permission, since there is nothing to lose`() {
        val root = sandbox()
        write(root, "nested/new.txt", "\"fresh\"").isError shouldBe false
        text(root.resolve("nested/new.txt")) shouldBe "fresh"
    }

    @Test fun `with no file family there is no guard, because nothing could satisfy it`() {
        // `file_tools: false` removes read_to_context, so a guard would be a
        // deadlock rather than a safety feature.
        val root = sandbox()
        put(root, "x.txt", "old")

        Tools
            .writeFromEval(listOf(engine), null, { root })
            .run(
                args(
                    "file_path" to JsonPrimitive("x.txt"), "expression" to JsonPrimitive("\"new\""),
                    "language" to JsonPrimitive("groovy")
                )
            )
            .isError shouldBe false
        text(root.resolve("x.txt")) shouldBe "new"
    }

    @Test fun `it writes to disk, so it is not read-only`() {
        writer(sandbox()).readOnly shouldBe false
        writer(sandbox()).name shouldBe Tools.WriteFromName
    }

    // ---- session_vars --------------------------------------------------------

    @Test fun `session_vars lists names and types and sizes, never values`() {
        val root = sandbox()
        engine.eval("""secretish = "y" * 5000""").ok shouldBe true
        engine.eval("counts = [1, 2, 3]").ok shouldBe true

        val listed = Tools.sessionVars(listOf(engine)).run(lang("groovy"))

        listed.isError shouldBe false
        listed.content shouldContain "secretish"
        listed.content shouldContain "String"
        listed.content shouldContain "5,000 chars"
        listed.content shouldContain "counts"
        listed.content shouldContain "3 elements"
        // The whole discipline: a listing that printed contents would undo the
        // feature it exists to serve.
        listed.content shouldNotContain "y".repeat(50)
    }

    @Test fun `session_vars also sees what read_to_var bound, under its own name`() {
        val root = sandbox()
        put(root, "seen.log", "a\nb\nc")
        Tools
            .readToVar(listOf(engine), limits, { root }, memo)
            .run(
                args(
                    "file_path" to JsonPrimitive("seen.log"), "variable" to JsonPrimitive("mylog"),
                    "language" to JsonPrimitive("groovy")
                )
            )

        Tools.sessionVars(listOf(engine)).run(lang("groovy")).content shouldContain "mylog"
    }

    @Test fun `session_vars is read-only and never starts a compiler to answer`() {
        val fresh = GroovyEngine(emptyList(), 5000L)
        val tool = Tools.sessionVars(listOf(fresh))

        tool.readOnly shouldBe true
        // Asking what is in an empty session must not cost a second of compiler.
        tool.run(lang("groovy")).content shouldContain "nothing is bound"
        fresh.started shouldBe false
    }

    // ---- atomic replacement ----------------------------------------------------

    @Test fun `a replaced file is moved into place, not truncated and rewritten`() {
        // The failure this fixes: Files.write opens with TRUNCATE_EXISTING, so an
        // exception between the truncate and the last byte left the file empty.
        val root = sandbox()
        val target = put(root, "big.txt", "original content here")

        FileTools.atomicWrite(target, "replacement")
        text(target) shouldBe "replacement"

        // The temp file is created in the target's own directory, so that the
        // rename can be atomic. It must not be left there.
        val listed = Files.list(root)
        val all = try {
            listed.toList().map { it.fileName.toString() }
        } finally {
            listed.close()
        }
        all shouldBe listOf("big.txt")
    }

    @Test fun `atomicWrite creates parent directories and a file that did not exist`() {
        val root = sandbox()
        FileTools.atomicWrite(root.resolve("a/b/c.txt"), "made")
        text(root.resolve("a/b/c.txt")) shouldBe "made"
    }

    @Test fun `a failed edit does not leave a truncated file`() {
        // `edit` writes through atomicWrite now, so the only states are old and
        // new. Exercised through the tool, which is where it matters.
        val root = sandbox()
        val files = FileTools(limits, { root }, memo)
        put(root, "e.txt", "alpha\nbeta\ngamma")

        files.read.run(args("file_path" to JsonPrimitive("e.txt")))
        files.edit
            .run(
                args(
                    "file_path" to JsonPrimitive("e.txt"), "old_string" to JsonPrimitive("beta"),
                    "new_string" to JsonPrimitive("BETA")
                )
            )
            .isError shouldBe false

        text(root.resolve("e.txt")) shouldBe "alpha\nBETA\ngamma"
    }
}
