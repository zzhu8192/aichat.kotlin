package kotllm.tools

import java.nio.file.Files

import kotlin.test.Test
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldStartWith
import io.kotest.matchers.string.shouldNotContain
import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.collections.shouldBeEmpty

import kotlinx.serialization.json.*

import kotllm.*
import kotllm.client.ToolCall
import kotllm.memory.Memory
import kotllm.memory.MemoryConfig

class ToolsTest {

    private fun echoTool(name: String = "echo") = Tool(
        name = name,
        description = "echoes",
        parameters = buildJsonObject { put("type", "object") },
        preview = { args -> Tool.optStr(args, "text") ?: "" },
        run = { args ->
            Tool.str(args, "text").fold(
                onErr = { e -> ToolResult(e, isError = true) },
                onOk = { t -> ToolResult(t) }
            )
        }
    )

    private fun call(name: String, args: String) = ToolCall("call_1", name, args)

    @Test fun `dispatches to the named tool`() {
        val r = ToolRegistry(listOf(echoTool()))
        r.dispatch(call("echo", """{"text":"hi"}""")) shouldBe ToolResult("hi")
    }

    @Test fun `an unknown tool is reported as a result, not thrown`() {
        val r = ToolRegistry(listOf(echoTool()))
        val out = r.dispatch(call("nope", "{}"))

        out.isError shouldBe true
        out.content shouldContain "unknown tool"
    }

    @Test fun `malformed arguments are reported precisely enough to retry`() {
        val r = ToolRegistry(listOf(echoTool()))
        val out = r.dispatch(call("echo", "{not json"))

        out.isError shouldBe true
        out.content shouldContain "could not parse tool arguments"
    }

    @Test fun `empty arguments decode to an empty object`() {
        // Models do send "" for a no-argument call.
        val r = ToolRegistry(listOf(echoTool()))
        r.parseArgs("") shouldBe ok(buildJsonObject { })
        r.parseArgs(null) shouldBe ok(buildJsonObject { })
    }

    @Test fun `a missing required argument is named in the error`() {
        val r = ToolRegistry(listOf(echoTool()))
        r.dispatch(call("echo", "{}")).content shouldContain "missing required argument 'text'"
    }

    @Test fun `a tool that throws is reported rather than losing the turn`() {
        val boom = echoTool().copy(run = { throw RuntimeException("kaboom") })
        val out = ToolRegistry(listOf(boom)).dispatch(call("echo", """{"text":"x"}"""))

        out.isError shouldBe true
        out.content shouldContain "kaboom"
    }

    @Test fun `use_tools selects by name`() {
        val r = ToolRegistry(listOf(echoTool("a"), echoTool("b")))

        r.selected("all").orNull()!!.map { it.name } shouldBe listOf("a", "b")
        r.selected("b").orNull()!!.map { it.name } shouldBe listOf("b")
        r.selected("b,a").orNull()!!.map { it.name } shouldBe listOf("b", "a")
        r.selected("none").orNull()!!.shouldBeEmpty()
        r.selected(null).orNull()!!.shouldBeEmpty()
    }

    @Test fun `an unknown name in use_tools is an error, not a silent no-op`() {
        val r = ToolRegistry(listOf(echoTool("a")))
        r.selected("a,typo").fold(onErr = { it }, onOk = { "" }) shouldContain "typo"
    }

    // ---- tools_exclude -----------------------------------------------------

    private fun registry(vararg names: String) = ToolRegistry(names.map { echoTool(it) })

    private fun offered(r: ToolRegistry, useTools: String, vararg exclude: String): List<String> =
        r.selected(useTools, exclude.toList()).orNull()!!.map { it.name }

    @Test fun `tools_exclude subtracts from what use_tools selected`() {
        val r = registry("scala_eval", "exec", "grep")

        offered(r, "all", "exec") shouldBe listOf("scala_eval", "grep")
        offered(r, "all", "exec", "grep") shouldBe listOf("scala_eval")
        offered(r, "all") shouldBe listOf("scala_eval", "exec", "grep")
    }

    @Test fun `it applies to an explicit use_tools list too, rather than being refused`() {
        // Subtraction is well defined either way, and rejecting the combination
        // would mean editing two keys to express one intent.
        offered(registry("a", "b", "c"), "b,c", "c") shouldBe listOf("b")
    }

    @Test fun `a wildcard pattern catches a family the whitelist cannot summarise`() {
        val r = registry("scala_eval", "java_eval", "python_eval", "read_memory", "grep")

        offered(r, "all", "*_eval") shouldBe listOf("read_memory", "grep")
        offered(r, "all", "*memory*") shouldBe listOf("scala_eval", "java_eval", "python_eval", "grep")
        // Anchored at both ends: a bare fragment is an exact name, not a substring.
        offered(r, "all", "eval") shouldBe listOf("scala_eval", "java_eval", "python_eval", "read_memory", "grep")
    }

    @Test fun `what is between the stars is quoted, so a metacharacter cannot widen the match`() {
        val r = registry("read.me", "readXme")
        offered(r, "all", "read.me") shouldBe listOf("readXme")
        offered(r, "all", "*.me") shouldBe listOf("readXme")
    }

    @Test fun `excluding everything leaves nothing, which is a valid answer`() {
        offered(registry("a", "b"), "all", "*").shouldBeEmpty()
    }

    @Test fun `an exclusion matching nothing is reported, not fatal`() {
        // The opposite rule to use_tools, and deliberately: which tools are
        // registered depends on config, so excluding python_eval on a machine with
        // no Python must not take down the whole tool set. But a typo here leaves
        // a tool on that the user believes is off, so it cannot be silent either.
        val r = registry("scala_eval", "exec")

        offered(r, "all", "pythn_eval", "exec") shouldBe listOf("scala_eval")
        r.unmatchedExclusions(listOf("pythn_eval", "exec")) shouldBe listOf("pythn_eval")
        r.unmatchedExclusions(listOf("*_eval")).shouldBeEmpty()
        r.unmatchedExclusions(listOf("rag_*")) shouldBe listOf("rag_*")
        r.unmatchedExclusions(emptyList()).shouldBeEmpty()
    }

    @Test fun `exclusions do not resurrect tools use_tools never selected`() {
        offered(registry("a", "b", "c"), "none", "a").shouldBeEmpty()
    }

    // ---- exec --------------------------------------------------------------

    private val exec = Tools.exec(defaultTimeoutSeconds = 20)

    @Test fun `exec runs a command and returns its output`() {
        val out = exec.run(buildJsonObject { put("command", "echo hello-from-exec") })
        out.isError shouldBe false
        out.content shouldContain "hello-from-exec"
    }

    @Test fun `exec reports a non-zero exit code as an error, with the output`() {
        val out = exec.run(buildJsonObject { put("command", "exit 3") })
        out.isError shouldBe true
        out.content shouldContain "exit code 3"
    }

    @Test fun `exec merges stderr into the result so failures stay in order`() {
        val out = exec.run(buildJsonObject { put("command", "echo to-stderr 1>&2") })
        out.content shouldContain "to-stderr"
    }

    @Test fun `exec rejects a cwd that is not a directory`() {
        val out = exec.run(
            buildJsonObject { put("command", "echo x"); put("cwd", "no/such/dir/anywhere") }
        )
        out.isError shouldBe true
        out.content shouldContain "not a directory"
    }

    @Test fun `exec kills a command that overruns its timeout`() {
        // Regression guard: reading the process output to completion before
        // waitFor blocks until the command exits, which silently defeats the
        // timeout entirely.
        val sleepLong =
            if (System.getProperty("os.name", "").lowercase().contains("win")) "ping -n 20 127.0.0.1"
            else "sleep 20"

        val started = System.currentTimeMillis()
        val out = Tools.exec(defaultTimeoutSeconds = 1).run(buildJsonObject { put("command", sleepLong) })
        val elapsed = System.currentTimeMillis() - started

        out.isError shouldBe true
        out.content shouldContain "timed out"
        // Actually returned early rather than waiting out the full command.
        (elapsed < 10000L) shouldBe true
    }

    @Test fun `exec requires a command`() {
        exec.run(buildJsonObject { }).isError shouldBe true
    }

    // ---- memories ----------------------------------------------------------

    private fun memoryIn(dir: java.nio.file.Path) = Memory(MemoryConfig.default, dir)

    @Test fun `add_or_update_memory writes a named file and creates the directory`() {
        val dir = Files.createTempDirectory("kotllm-memory-test").resolve("nested")
        val out = Tools
            .addOrUpdateMemory(memoryIn(dir))
            .run(
                buildJsonObject {
                    put("name", "scala-version")
                    put("content", "The user prefers Scala 2.13")
                }
            )

        out.isError shouldBe false
        out.content shouldContain "saved"
        String(Files.readAllBytes(dir.resolve("scala-version.md"))) shouldContain "prefers Scala 2.13"
    }

    @Test fun `the same name replaces, which is the whole point of the rename`() {
        // `remember` could only append, so a fact recorded wrong was permanent and
        // kept being injected into every request.
        val dir = Files.createTempDirectory("kotllm-memory-upsert")
        val tool = Tools.addOrUpdateMemory(memoryIn(dir))

        tool.run(buildJsonObject { put("name", "editor"); put("content", "uses vim") })
        val second =
            tool.run(buildJsonObject { put("name", "editor"); put("content", "uses emacs") })

        second.content shouldContain "replaced"
        val text = String(Files.readAllBytes(dir.resolve("editor.md")))
        text shouldContain "emacs"
        text shouldNotContain "vim"
        Files.list(dir).count() shouldBe 1L
    }

    @Test fun `a name is slugged, and cannot escape the memory directory`() {
        // Containment falls out of the slug rather than sitting beside it as a
        // separate check that could be forgotten.
        val dir = Files.createTempDirectory("kotllm-memory-slug")
        Tools
            .addOrUpdateMemory(memoryIn(dir))
            .run(buildJsonObject { put("name", "../../etc/passwd"); put("content", "nope") })
            .isError shouldBe false

        Files.exists(dir.resolve("etc-passwd.md")) shouldBe true
        Files.list(dir).count() shouldBe 1L
    }

    @Test fun `delete_memory removes one by name and names the others on a miss`() {
        val dir = Files.createTempDirectory("kotllm-memory-delete")
        val mem = memoryIn(dir)
        Tools.addOrUpdateMemory(mem).run(buildJsonObject { put("name", "keep"); put("content", "a") })
        Tools.addOrUpdateMemory(mem).run(buildJsonObject { put("name", "drop"); put("content", "b") })

        Tools.deleteMemory(mem).run(buildJsonObject { put("name", "drop") }).isError shouldBe false
        Files.exists(dir.resolve("drop.md")) shouldBe false
        Files.exists(dir.resolve("keep.md")) shouldBe true

        val miss = Tools.deleteMemory(mem).run(buildJsonObject { put("name", "nosuch") })
        miss.isError shouldBe true
        miss.content shouldContain "keep"
    }

    @Test fun `deleting is not read-only, because it destroys the user's data`() {
        // The confirm-skipping exemption is for outbound reads, not for data loss.
        val dir = Files.createTempDirectory("kotllm-memory-ro")
        Tools.deleteMemory(memoryIn(dir)).readOnly shouldBe false
        Tools.addOrUpdateMemory(memoryIn(dir)).readOnly shouldBe false
        Tools.readMemory(memoryIn(dir)).readOnly shouldBe true
    }

    @Test fun `memory add still writes the one-line dated form to notesmd`() {
        // The quick unnamed note the user types is a different act from the model
        // curating a named fact, so both survive -- and notes.md is addressable as
        // `notes` like any other memory.
        val dir = Files.createTempDirectory("kotllm-memory-append")
        memoryIn(dir).append("line one\nline two").isOk shouldBe true

        // trim() before splitting, rather than a bare lines(): Kotlin's lines(),
        // unlike Scala's linesIterator, keeps a trailing empty entry for text
        // ending in a newline, and append() always writes one.
        val lines = String(Files.readAllBytes(dir.resolve("notes.md"))).trim().lines()
        lines.size shouldBe 1
        lines[0] shouldStartWith "- ("
    }

    @Test fun `tool definitions carry a JSON Schema object`() {
        val d = Tools.exec(10).definition
        d.name shouldBe "exec"
        d.parameters.jsonObject["type"]?.jsonPrimitive?.content shouldBe "object"
        val required = d.parameters.jsonObject["required"]?.jsonArray?.map { it.jsonPrimitive.content } ?: emptyList()
        required shouldContain "command"
    }
}
