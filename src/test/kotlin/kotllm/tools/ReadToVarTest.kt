package kotllm.tools

import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path

import kotlin.test.Test
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import io.kotest.matchers.string.shouldEndWith

import kotlinx.serialization.json.*

import kotllm.engine.Classpath
import kotllm.engine.GroovyEngine

/** `read_to_var`: a file becomes a `String` in the interpreter, not text in the
  * conversation.
  *
  * Starts a real compiler, so it is one of the slower specs here. That is the
  * point -- the whole feature is the handoff between a tool result and live
  * interpreter state, and a stubbed engine would test the half that cannot
  * break.
  */
class ReadToVarTest {

    private val limits = FileToolLimits.default

    /** Shared with the FileTools instance in the last test, which is the point:
      * one memo across everything that can replace a file. */
    private val memo = FileMemo()

    /** One engine for the suite: starting a compiler costs a second or two, and
      * these tests are about session state surviving between calls anyway. */
    private val engine: GroovyEngine by lazy { GroovyEngine(emptyList(), 30000L) }

    private fun sandbox(): Path = Files.createTempDirectory("kotllm-bind")

    private fun put(root: Path, rel: String, content: String): Path {
        val p = root.resolve(rel)
        Files.createDirectories(p.parent)
        Files.write(p, content.toByteArray(StandardCharsets.UTF_8))
        return p
    }

    private fun tool(root: Path, lim: FileToolLimits = limits) =
        Tools.readToVar(listOf(engine), lim, { root }, memo)

    private fun args(vararg pairs: Pair<String, JsonElement>): JsonElement = buildJsonObject {
        pairs.forEach { (k, v) -> put(k, v) }
    }

    private fun bind(root: Path, file: String, variable: String, lim: FileToolLimits = limits) =
        tool(root, lim).run(
            args(
                "file_path" to JsonPrimitive(file), "variable" to JsonPrimitive(variable),
                "language" to JsonPrimitive("groovy")
            )
        )

    // ---- the point of the feature ------------------------------------------

    @Test fun `the file lands in the interpreter and the contents stay out of the result`() {
        val root = sandbox()
        val body = (1..500).joinToString("\n") { i -> "line $i ${if (i % 7 == 0) "ERROR boom" else "ok"}" }
        put(root, "app.log", body)

        val result = bind(root, "app.log", "log")

        result.isError shouldBe false
        result.content shouldContain "bound `log` as a String"
        result.content shouldContain "500 line(s)"

        // The whole reason the tool exists. A result carrying the file would be a
        // more expensive `read` with extra steps.
        result.content shouldNotContain "line 200"
        (result.content.length < 600) shouldBe true
        (result.content.length < body.length / 10) shouldBe true

        // And it really is there, computable at full speed.
        val counted = engine.eval("""log.readLines().count { it.contains("ERROR") }""")
        counted.ok shouldBe true
        counted.toToolOutput() shouldContain (1..500).count { it % 7 == 0 }.toString()
    }

    @Test fun `the binding survives into later groovy_eval calls, and regex works on it`() {
        // Two calls, because the model's pattern is bind once then explore.
        val root = sandbox()
        put(root, "d.csv", "id,qty\n1,5\n2,7\n3,11")
        bind(root, "d.csv", "csv").isError shouldBe false

        engine.eval("rows = csv.readLines().drop(1).collect { it.split(',') }").ok shouldBe true
        val total = engine.eval("rows.sum { it[1] as int }")
        total.toToolOutput() shouldContain "23"

        val viaRegex = engine.eval(
            "(csv =~ /(\\d+),(\\d+)/).collect { it[2] as int }.sum()"
        )
        viaRegex.toToolOutput() shouldContain "23"
    }

    @Test fun `the hand-written groovy_eval version puts the whole file in the transcript`() {
        // Measured rather than assumed, and the honest case for this tool: reading
        // a file with `groovy_eval` works, it just spends the file twice -- once
        // into the session and once into the conversation.
        val root = sandbox()
        val body = (1..400).joinToString("\n") { i -> "row $i" }
        put(root, "big.txt", body)

        val quoted = "'''" + root.resolve("big.txt").toString().replace("\\", "/") + "'''"
        engine.eval("naughty = new File($quoted).text").ok shouldBe true
        val echoed = engine.eval("naughty").toToolOutput()

        echoed shouldContain "row 1"
        echoed shouldContain "row 400"

        // This tool's result says something true about the file, in far less space.
        val ours = bind(root, "big.txt", "polite").content
        (ours.length < echoed.length / 10) shouldBe true
        ours shouldContain "400 line(s)"
        ours shouldNotContain "row 200"
    }

    @Test fun `a tiny head preview is included, because code has to be written against it`() {
        val root = sandbox()
        put(root, "t.tsv", "name\tqty\napple\t3\npear\t4\nplum\t5\nfig\t6")

        val out = bind(root, "t.tsv", "tsv").content
        out shouldContain "First lines, for shape only"
        out shouldContain "name\tqty"
        // Bounded: three lines and an ellipsis, never the file.
        out shouldContain "..."
        out shouldNotContain "fig"
    }

    // ---- refusals ----------------------------------------------------------

    @Test fun `the variable name is validated, since it is interpolated into source`() {
        val root = sandbox()
        put(root, "a.txt", "x")

        bind(root, "a.txt", "val").content shouldContain "reserved word"
        bind(root, "a.txt", "2bad").content shouldContain "legal identifier"
        bind(root, "a.txt", "has space").content shouldContain "legal identifier"
        bind(root, "a.txt", "has-dash").content shouldContain "legal identifier"
        bind(root, "a.txt", "ok_1").isError shouldBe false
    }

    @Test fun `a binary file is refused rather than bound as mojibake`() {
        val root = sandbox()
        Files.write(root.resolve("b.bin"), byteArrayOf(1, 2, 0, 3))

        val r = bind(root, "b.bin", "blob")
        r.isError shouldBe true
        r.content shouldContain "binary"
    }

    @Test fun `an oversized file is refused without being read into the heap first`() {
        // The size is checked before the allocation, so the refusal cannot be the
        // thing that causes the OutOfMemoryError it exists to prevent.
        val root = sandbox()
        put(root, "big.txt", "y".repeat(5000))

        val r = bind(root, "big.txt", "big", limits.copy(bindMaxChars = 100))
        r.isError shouldBe true
        r.content shouldContain "over the 100-byte limit"
        r.content shouldContain "read_to_var_max_chars"
    }

    @Test fun `a missing file and a directory are both reported`() {
        val root = sandbox()
        Files.createDirectories(root.resolve("dir"))

        bind(root, "nope.txt", "x").content shouldContain "does not exist"
        bind(root, "dir", "x").content shouldContain "is a directory"
    }

    @Test fun `missing arguments are named`() {
        val root = sandbox()
        tool(root).run(args("file_path" to JsonPrimitive("a"), "language" to JsonPrimitive("groovy")))
            .content shouldContain "'variable'"
        tool(root).run(args("variable" to JsonPrimitive("a"), "language" to JsonPrimitive("groovy")))
            .content shouldContain "'file_path'"
    }

    // ---- registration and framing -------------------------------------------

    @Test fun `re-binding a name replaces it, which is what re-reading an edited file needs`() {
        val root = sandbox()
        put(root, "v.txt", "first")
        bind(root, "v.txt", "v").isError shouldBe false
        engine.eval("v").toToolOutput() shouldContain "first"

        put(root, "v.txt", "second")
        bind(root, "v.txt", "v").isError shouldBe false
        engine.eval("v").toToolOutput() shouldContain "second"
    }

    @Test fun `it is read-only, so an unattended run can use it`() {
        // It reads a file `read` could already have read unprompted and binds a
        // string; it cannot execute anything.
        tool(sandbox()).readOnly shouldBe true
        tool(sandbox()).name shouldBe Tools.ReadToVarName
    }

    @Test fun `the manifest names the pattern, but only when the tool is offered`() {
        // A schema makes the tool available; this is what makes the *pattern*
        // discoverable, which is the failure `search` already taught us once.
        // With groovy_eval too: the pattern is about keeping data in the eval
        // session, so without the interpreter there is no pattern to describe.
        // The prompt is `agents/chat.md`, so this asserts the template: both ends
        // of the pattern named, and the whole block gated on the tool being
        // offered so it is never described when it is not there.
        val template = kotllm.prompt.ChatAgent.packaged

        template shouldContain Tools.ReadToVarName
        template shouldContain Tools.WriteFromName
        template shouldContain "read_to_context"
        template shouldContain """prompt.hasTool("${Tools.ReadToVarName}")"""
    }

    @Test fun `binding counts as having seen the file, so a write back is allowed`() {
        // Reversal of the first version of this test, and the reasoning matters.
        // Binding originally did *not* count as seeing, on the grounds that the
        // content went to the JVM rather than to the model. But the guard exists
        // to stop *blind* replacement, and a model holding the whole file as a
        // String is not blind. Meanwhile read-transform-write-back to the same
        // path is the normal shape of this work and was being refused with advice
        // to go and spend 40k characters on a read.
        val root = sandbox()
        val files = FileTools(limits, { root }, memo)
        put(root, "w.txt", "precious")

        bind(root, "w.txt", "w").isError shouldBe false
        files.write
            .run(args("file_path" to JsonPrimitive("w.txt"), "content" to JsonPrimitive("transformed")))
            .isError shouldBe false

        // A file nobody touched is still protected.
        put(root, "other.txt", "untouched")
        files.write
            .run(args("file_path" to JsonPrimitive("other.txt"), "content" to JsonPrimitive("nope")))
            .content shouldContain "sight unseen"
    }
}
