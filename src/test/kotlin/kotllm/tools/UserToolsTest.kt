package kotllm.tools

import java.nio.file.Files
import java.nio.file.Path

import kotlin.test.Test
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain

import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

import kotllm.engine.GroovyEngine

/** User tool files, which are Groovy evaluated in the live session.
  *
  * The suite exists because the shipped example did not load, and nothing said
  * so: a tool that fails to evaluate is simply never offered, which looks
  * exactly like the model declining to use it. Two independent defects, both
  * invisible from the Kotlin side --
  *
  *  - `Json.parseToJsonElement(s)` is unreachable from Groovy. `Json` is a
  *    sealed class whose entry point is a named companion, and Groovy resolves
  *    `Json.Default` to the nested class rather than the static field holding
  *    the instance. Hence `Tool.schema`.
  *  - the companion helpers had no `@JvmStatic`, so `Tool.optStr(...)` did not
  *    resolve either.
  *
  * Both are CONVENTIONS rule 46: Scala could not reach a Kotlin declaration by
  * accident, and neither can Groovy.
  */
class UserToolsTest {

    private fun dir(): Path = Files.createTempDirectory("kotllm-usertools")

    private fun put(root: Path, name: String, body: String): Path =
        Files.write(root.resolve(name), body.toByteArray())

    private fun load(root: Path): Pair<List<LoadedToolFile>, List<Tool>> {
        val engine = GroovyEngine(emptyList(), 60_000L)
        val ut = UserTools(root) { engine }
        return Pair(ut.load(), ut.ready)
    }

    private fun repoFile(rel: String): String =
        Files.readString(Path.of(rel))

    // ---- the shipped files have to load, or they are not examples ----------

    @Test fun `the bundled example evaluates to a Tool`() {
        val root = dir()
        put(root, "word_count.groovy", repoFile("examples/tools/word_count.groovy"))

        val (loaded, tools) = load(root)

        // The error, not just the boolean: a failure here is a Groovy diagnostic
        // and reading it is the whole point.
        loaded.single().error shouldBe null
        tools.single().name shouldBe "word_count"
        tools.single().readOnly shouldBe true
    }

    @Test fun `the bundled example actually counts`() {
        val root = dir()
        put(root, "word_count.groovy", repoFile("examples/tools/word_count.groovy"))
        val tool = load(root).second.single()

        fun run(text: String, unique: Boolean?) = tool.run(
            buildJsonObject {
                put("text", text)
                if (unique != null) put("unique", unique)
            }
        )

        run("a b c", null).content shouldBe "3"
        run("a A b", true).content shouldBe "2"
        // A missing required argument is a readable refusal, not an exception.
        tool.run(buildJsonObject { }).isError shouldBe true
    }

    @Test fun `the shipped config tool counts a real file`() {
        val root = dir()
        put(root, "file_word_count.groovy", repoFile("examples/tools/file_word_count.groovy"))
        val tool = load(root).second.single()

        val target = Files.write(root.resolve("sample.txt"), "one two two\nthree\n".toByteArray())

        val counted = tool.run(buildJsonObject { put("file_path", target.toString()) })
        counted.isError shouldBe false
        counted.content shouldContain "4 word(s)"
        counted.content shouldContain "2 line(s)"

        val distinct = tool.run(
            buildJsonObject { put("file_path", target.toString()); put("unique", true) }
        )
        distinct.content shouldContain "3 distinct word(s)"

        // The refusal names the path rather than throwing out of the tool loop.
        val missing = tool.run(buildJsonObject { put("file_path", root.resolve("nope").toString()) })
        missing.isError shouldBe true
        missing.content shouldContain "does not exist"
    }

    // ---- the interop each of those depends on ------------------------------

    @Test fun `Tool schema and the arg helpers are reachable from Groovy`() {
        // Without @JvmStatic these resolve in Kotlin and fail at tool-load time,
        // which is the gap that shipped.
        val engine = GroovyEngine(emptyList(), 30_000L)

        engine.eval("""kotllm.tools.Tool.schema('{"a":1}').toString()""").let {
            it.ok shouldBe true
            it.transcript shouldContain "\"a\""
        }
        engine.eval(
            """kotllm.tools.Tool.optStr(kotllm.tools.Tool.schema('{"a":"x"}'), "a")"""
        ).transcript shouldBe "x"
        engine.eval(
            """kotllm.tools.Tool.optBool(kotllm.tools.Tool.schema('{"a":true}'), "a")"""
        ).transcript shouldBe "true"
        engine.eval(
            """kotllm.tools.Tool.optInt(kotllm.tools.Tool.schema('{"a":7}'), "a")"""
        ).transcript shouldBe "7"
    }

    // ---- failure has to be reported, since the model is never told ---------

    @Test fun `a file that does not evaluate is reported, not silently dropped`() {
        val root = dir()
        put(root, "broken.groovy", "this is not groovy at all (((")

        val (loaded, tools) = load(root)

        tools.isEmpty() shouldBe true
        loaded.single().ok shouldBe false
        loaded.single().error!!.isNotBlank() shouldBe true
    }

    @Test fun `a file whose value is not a Tool says so, naming what it got`() {
        val root = dir()
        put(root, "wrong.groovy", "42")

        val error = load(root).first.single().error!!
        error shouldContain "must be a kotllm.tools.Tool"
        error shouldContain "Int"
    }

    @Test fun `one broken file does not take the others with it`() {
        // Each file is evaluated separately for exactly this reason.
        val root = dir()
        put(root, "a_broken.groovy", "(((")
        put(root, "b_good.groovy", repoFile("examples/tools/word_count.groovy"))

        val (loaded, tools) = load(root)

        loaded.size shouldBe 2
        tools.map { it.name } shouldBe listOf("word_count")
    }

    @Test fun `only groovy files are considered`() {
        val root = dir()
        put(root, "notes.md", "not a tool")
        put(root, "old.scala", "object x")

        load(root).first.isEmpty() shouldBe true
    }

    // ---- the background load, whose whole risk is being missed -------------

    @Test fun `a caller arriving before the loader thread still waits for it`() {
        // `compiling` used to be set inside the thread, so a caller that got
        // there first saw false, skipped awaitReady, and built a request with no
        // user tools in it. Nothing failed; the model was just never told.
        val root = dir()
        put(root, "word_count.groovy", repoFile("examples/tools/word_count.groovy"))

        val ut = UserTools(root) { GroovyEngine(emptyList(), 60_000L) }
        ut.startInBackground()
        // Checked with no sleep in between, which is the whole point.
        ut.compiling shouldBe true

        ut.awaitReady() shouldBe true
        ut.ready.map { it.name } shouldBe listOf("word_count")
    }

    @Test fun `an empty directory does not leave the loader stuck`() {
        // The early return skipped the flag it never set; now that the flag is
        // set up front, the empty path has to clear it or awaitReady spins for a
        // minute on the common case of having no tools at all.
        val ut = UserTools(dir()) { GroovyEngine(emptyList(), 5_000L) }
        ut.startInBackground()

        ut.awaitReady(10_000L) shouldBe true
        ut.compiling shouldBe false
        ut.ready.isEmpty() shouldBe true
    }
}
