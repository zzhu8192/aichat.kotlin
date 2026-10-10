package kotllm.tools

import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path

import kotlin.test.Test
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import io.kotest.matchers.collections.shouldContainExactlyInAnyOrder
import io.kotest.matchers.collections.shouldContain

import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject

/** `read`, `write`, `edit`, `glob`, `grep`, `ls`.
 *
 * The behaviour worth pinning down is mostly the *refusals*: an ambiguous
 * edit, an unread overwrite, a binary read. Those are what make a
 * string-replacement file tool trustworthy rather than a coin flip, and they
 * are exactly what a happy-path test would miss.
 */
class FileToolsTest {

    private fun sandbox(): Path = Files.createTempDirectory("kotllm-files")

    private fun put(root: Path, rel: String, content: String): Path {
        val p = root.resolve(rel)
        Files.createDirectories(p.parent)
        Files.write(p, content.toByteArray(StandardCharsets.UTF_8))
        return p
    }

    private fun tools(root: Path, limits: FileToolLimits = FileToolLimits.default): FileTools =
        FileTools(limits, { root })

    private fun args(vararg pairs: Pair<String, JsonElement>): JsonElement = buildJsonObject {
        for ((k, v) in pairs) put(k, v)
    }

    private fun read(root: Path): String = Files.readAllLines(root).joinToString("\n")

    // ---- read --------------------------------------------------------------

    @Test fun `read numbers its lines and reports the size`() {
        val root = sandbox()
        put(root, "a.txt", "one\ntwo\nthree")
        val r = tools(root).read.run(args("file_path" to JsonPrimitive("a.txt")))

        r.isError shouldBe false
        r.content shouldContain "3 line(s)"
        r.content shouldContain "1\tone"
        r.content shouldContain "3\tthree"
    }

    @Test fun `offset and limit window the file and say how to continue`() {
        val root = sandbox()
        put(root, "a.txt", (1..10).joinToString("\n") { i -> "line$i" })
        val r = tools(root).read.run(
            args(
                "file_path" to JsonPrimitive("a.txt"),
                "offset" to JsonPrimitive(3),
                "limit" to JsonPrimitive(2)
            )
        )

        r.content shouldContain "lines 3-4 of 10"
        r.content shouldContain "3\tline3"
        r.content shouldNotContain "line5\n"
        // A truncated read that does not say where to resume is a dead end.
        r.content shouldContain "continue at offset 5"
    }

    @Test fun `read refuses a directory, a missing file and a binary file`() {
        val root = sandbox()
        Files.createDirectories(root.resolve("dir"))
        Files.write(root.resolve("b.bin"), byteArrayOf(1, 2, 0, 3))
        val t = tools(root)

        t.read.run(args("file_path" to JsonPrimitive("dir"))).content shouldContain "use the ls tool"
        t.read.run(args("file_path" to JsonPrimitive("nope.txt"))).isError shouldBe true
        val bin = t.read.run(args("file_path" to JsonPrimitive("b.bin")))
        bin.isError shouldBe true
        bin.content shouldContain "binary"
    }

    @Test fun `an empty file reads as empty rather than as an error`() {
        val root = sandbox()
        put(root, "empty.txt", "")
        val r = tools(root).read.run(args("file_path" to JsonPrimitive("empty.txt")))
        r.isError shouldBe false
        r.content shouldContain "is empty"
    }

    // ---- write -------------------------------------------------------------

    @Test fun `write creates a file and its parent directories`() {
        val root = sandbox()
        val r = tools(root).write.run(args("file_path" to JsonPrimitive("a/b/c.txt"), "content" to JsonPrimitive("hi")))

        r.isError shouldBe false
        r.content shouldContain "created"
        read(root.resolve("a/b/c.txt")) shouldBe "hi"
    }

    @Test fun `write refuses to overwrite a file this session has not read`() {
        // The worst thing a file tool can do is replace what it never looked at.
        val root = sandbox()
        put(root, "a.txt", "important\n".repeat(50))
        val t = tools(root)

        val refused = t.write.run(args("file_path" to JsonPrimitive("a.txt"), "content" to JsonPrimitive("oops")))
        refused.isError shouldBe true
        refused.content shouldContain "sight unseen"
        refused.content shouldContain "read"
        read(root.resolve("a.txt")) shouldContain "important"

        // Having read it, replacing it is a decision rather than an accident.
        t.read.run(args("file_path" to JsonPrimitive("a.txt")))
        val allowed = t.write.run(args("file_path" to JsonPrimitive("a.txt"), "content" to JsonPrimitive("ok")))
        allowed.isError shouldBe false
        allowed.content shouldContain "overwrote"
    }

    @Test fun `writing an empty file is allowed, since emptying one is a real intent`() {
        val root = sandbox()
        val r = tools(root).write.run(args("file_path" to JsonPrimitive("e.txt"), "content" to JsonPrimitive("")))
        r.isError shouldBe false
        Files.exists(root.resolve("e.txt")) shouldBe true
    }

    @Test fun `write without content says which argument is missing`() {
        val root = sandbox()
        tools(root).write.run(args("file_path" to JsonPrimitive("x"))).content shouldContain "'content'"
    }

    // ---- edit --------------------------------------------------------------

    @Test fun `a unique edit is applied and reports the line it landed on`() {
        val root = sandbox()
        put(root, "a.scala", "val x = 1\nval y = 2\nval z = 3")
        val r = tools(root).edit.run(
            args(
                "file_path" to JsonPrimitive("a.scala"),
                "old_string" to JsonPrimitive("val y = 2"),
                "new_string" to JsonPrimitive("val y = 22")
            )
        )

        r.isError shouldBe false
        r.content shouldContain "line 2"
        read(root.resolve("a.scala")) shouldContain "val y = 22"
    }

    @Test fun `an ambiguous edit is refused with the count, and nothing changes`() {
        // The whole safety property: a search string that appears twice is an
        // error, not a coin flip.
        val root = sandbox()
        put(root, "a.scala", "drop()\nkeep()\ndrop()")
        val r = tools(root).edit.run(
            args(
                "file_path" to JsonPrimitive("a.scala"),
                "old_string" to JsonPrimitive("drop()"),
                "new_string" to JsonPrimitive("gone()")
            )
        )

        r.isError shouldBe true
        r.content shouldContain "appears 2 times"
        r.content shouldContain "replace_all"
        read(root.resolve("a.scala")) shouldNotContain "gone()"
    }

    @Test fun `replace_all changes every occurrence and counts them`() {
        val root = sandbox()
        put(root, "a.scala", "drop()\nkeep()\ndrop()")
        val r = tools(root).edit.run(
            args(
                "file_path" to JsonPrimitive("a.scala"),
                "old_string" to JsonPrimitive("drop()"),
                "new_string" to JsonPrimitive("gone()"),
                "replace_all" to JsonPrimitive(true)
            )
        )

        r.content shouldContain "2 occurrence(s)"
        read(root.resolve("a.scala")) shouldBe "gone()\nkeep()\ngone()"
    }

    @Test fun `a missing old_string blames whitespace, because that is nearly always it`() {
        val root = sandbox()
        put(root, "a.scala", "  val x = 1")
        val r = tools(root).edit.run(
            args(
                "file_path" to JsonPrimitive("a.scala"),
                "old_string" to JsonPrimitive("val x = 2"),
                "new_string" to JsonPrimitive("val x = 3")
            )
        )

        r.isError shouldBe true
        r.content shouldContain "was not found"
        r.content shouldContain "whitespace"
    }

    @Test fun `line-number prefixes left over from read are stripped rather than failing`() {
        // Having just read a numbered listing, a model pastes the prefix in. One
        // retry costs three lines and saves a round trip that reads as a bug.
        val root = sandbox()
        put(root, "a.scala", "val x = 1\nval y = 2")
        val t = tools(root)
        val pasted = FileTools.numbered(listOf("val y = 2"), 2)

        val r = t.edit.run(
            args(
                "file_path" to JsonPrimitive("a.scala"),
                "old_string" to JsonPrimitive(pasted),
                "new_string" to JsonPrimitive("val y = 99")
            )
        )

        r.isError shouldBe false
        r.content shouldContain "prefixes in old_string were ignored"
        read(root.resolve("a.scala")) shouldContain "val y = 99"
    }

    @Test fun `an edit that changes nothing is refused rather than rewriting the file`() {
        val root = sandbox()
        put(root, "a.scala", "x")
        tools(root).edit
            .run(args("file_path" to JsonPrimitive("a.scala"), "old_string" to JsonPrimitive("x"), "new_string" to JsonPrimitive("x")))
            .content shouldContain "identical"
    }

    @Test fun `an edit unlocks a later write, since the file has been seen`() {
        val root = sandbox()
        put(root, "a.txt", "one")
        val t = tools(root)
        t.edit.run(
            args("file_path" to JsonPrimitive("a.txt"), "old_string" to JsonPrimitive("one"), "new_string" to JsonPrimitive("two"))
        )
        t.write.run(args("file_path" to JsonPrimitive("a.txt"), "content" to JsonPrimitive("three")))
            .isError shouldBe false
    }

    // ---- glob --------------------------------------------------------------

    @Test fun `a pattern with a slash matches the relative path, one without matches the name`() {
        val root = sandbox()
        put(root, "src/main/A.scala", "a")
        put(root, "src/test/B.scala", "b")
        put(root, "README.md", "r")
        val t = tools(root)

        val deep = t.glob.run(args("pattern" to JsonPrimitive("src/**/*.scala")))
        deep.content shouldContain "src/main/A.scala"
        deep.content shouldContain "src/test/B.scala"
        deep.content shouldNotContain "README.md"

        // The bare form is what a model writes when it means "every Scala file".
        val leaf = t.glob.run(args("pattern" to JsonPrimitive("*.scala")))
        leaf.content shouldContain "src/main/A.scala"
    }

    @Test fun `glob never walks build output or version control`() {
        val root = sandbox()
        put(root, "keep/A.scala", "a")
        put(root, "target/classes/B.scala", "b")
        put(root, "node_modules/p/C.scala", "c")
        put(root, ".git/D.scala", "d")

        val r = tools(root).glob.run(args("pattern" to JsonPrimitive("*.scala")))
        r.content shouldContain "keep/A.scala"
        r.content shouldNotContain "target"
        r.content shouldNotContain "node_modules"
        r.content shouldNotContain ".git"
    }

    @Test fun `glob returns newest first and says how many it withheld`() {
        val root = sandbox()
        val old = put(root, "old.txt", "o")
        val fresh = put(root, "new.txt", "n")
        Files.setLastModifiedTime(old, java.nio.file.attribute.FileTime.fromMillis(1000L))
        Files.setLastModifiedTime(fresh, java.nio.file.attribute.FileTime.fromMillis(9_000_000L))

        val ordered = tools(root).glob.run(args("pattern" to JsonPrimitive("*.txt")))
        (ordered.content.indexOf("new.txt") < ordered.content.indexOf("old.txt")) shouldBe true

        val capped = tools(root).glob.run(args("pattern" to JsonPrimitive("*.txt"), "limit" to JsonPrimitive(1)))
        capped.content shouldContain "1 further match(es) withheld"
    }

    @Test fun `no glob match is a plain answer, not an error`() {
        val root = sandbox()
        val r = tools(root).glob.run(args("pattern" to JsonPrimitive("*.nothing")))
        r.isError shouldBe false
        r.content shouldContain "no files match"
    }

    @Test fun `an unparseable glob is reported as a glob problem`() {
        val root = sandbox()
        tools(root).glob.run(args("pattern" to JsonPrimitive("[unclosed"))).isError shouldBe true
    }

    // ---- grep --------------------------------------------------------------

    @Test fun `grep lists matching files by default, with a count each`() {
        val root = sandbox()
        put(root, "a.scala", "class Foo\nclass Foo2")
        put(root, "b.scala", "class Bar")

        val r = tools(root).grep.run(args("pattern" to JsonPrimitive("class Foo")))
        r.content shouldContain "a.scala  (2)"
        r.content shouldNotContain "b.scala"
    }

    @Test fun `content mode returns numbered lines, count mode returns totals`() {
        val root = sandbox()
        put(root, "a.scala", "one\ntarget\nthree\ntarget")

        val t = tools(root)
        val content = t.grep.run(
            args("pattern" to JsonPrimitive("target"), "output_mode" to JsonPrimitive("content"))
        )
        content.content shouldContain "2: target"
        content.content shouldContain "4: target"

        val count = t.grep.run(args("pattern" to JsonPrimitive("target"), "output_mode" to JsonPrimitive("count")))
        count.content shouldContain "2\ta.scala"
    }

    @Test fun `content mode with context shows surrounding lines, marked differently`() {
        val root = sandbox()
        put(root, "a.scala", "before\nhit\nafter")
        val r = tools(root).grep.run(
            args(
                "pattern" to JsonPrimitive("hit"), "output_mode" to JsonPrimitive("content"),
                "context" to JsonPrimitive(1)
            )
        )
        // Matches use ':' and context uses '-', the same convention grep uses.
        r.content shouldContain "2: hit"
        r.content shouldContain "1- before"
        r.content shouldContain "3- after"
    }

    @Test fun `case_insensitive and the glob filter both apply`() {
        val root = sandbox()
        put(root, "a.scala", "Widget")
        put(root, "b.md", "widget")
        val t = tools(root)

        t.grep.run(args("pattern" to JsonPrimitive("widget"))).content shouldContain "b.md"
        t.grep
            .run(args("pattern" to JsonPrimitive("widget"), "case_insensitive" to JsonPrimitive(true)))
            .content shouldContain "a.scala"
        t.grep
            .run(
                args(
                    "pattern" to JsonPrimitive("widget"), "case_insensitive" to JsonPrimitive(true),
                    "glob" to JsonPrimitive("*.scala")
                )
            )
            .content shouldNotContain "b.md"
    }

    @Test fun `grep skips binary files instead of reporting them as unreadable`() {
        val root = sandbox()
        Files.write(root.resolve("x.png"), "match".toByteArray(StandardCharsets.UTF_8))
        Files.write(root.resolve("y.txt"), byteArrayOf('m'.code.toByte(), 0, 'x'.code.toByte()))
        put(root, "z.txt", "match")

        val r = tools(root).grep.run(args("pattern" to JsonPrimitive("match")))
        r.content shouldContain "z.txt"
        r.content shouldNotContain "x.png"
        r.content shouldNotContain "y.txt"
    }

    @Test fun `a bad regex is reported as a regex problem, with where it failed`() {
        val root = sandbox()
        val r = tools(root).grep.run(args("pattern" to JsonPrimitive("(unclosed")))
        r.isError shouldBe true
        r.content shouldContain "not a valid regular expression"
    }

    @Test fun `no grep match explains that the pattern is a regex`() {
        val root = sandbox()
        put(root, "a.txt", "nothing here")
        val r = tools(root).grep.run(args("pattern" to JsonPrimitive("absent")))
        r.isError shouldBe false
        r.content shouldContain "regular expression"
    }

    @Test fun `an unknown output_mode lists the ones that exist`() {
        val root = sandbox()
        put(root, "a.txt", "x")
        tools(root).grep
            .run(args("pattern" to JsonPrimitive("x"), "output_mode" to JsonPrimitive("json")))
            .content shouldContain "files_with_matches"
    }

    @Test fun `grep can be pointed at a single file`() {
        val root = sandbox()
        put(root, "a.txt", "hit")
        put(root, "b.txt", "hit")
        val r = tools(root).grep.run(args("pattern" to JsonPrimitive("hit"), "path" to JsonPrimitive("a.txt")))
        r.content shouldContain "a.txt"
        r.content shouldNotContain "b.txt"
    }

    // ---- ls ----------------------------------------------------------------

    @Test fun `ls puts directories first and gives file sizes`() {
        val root = sandbox()
        put(root, "zdir/x", "x")
        put(root, "a.txt", "1234")

        val r = tools(root).ls.run(buildJsonObject {})
        r.content shouldContain "zdir/"
        r.content shouldContain "a.txt  (4 bytes)"
        (r.content.indexOf("zdir/") < r.content.indexOf("a.txt")) shouldBe true
    }

    @Test fun `ls on a file says so rather than guessing`() {
        val root = sandbox()
        put(root, "a.txt", "x")
        tools(root).ls.run(args("path" to JsonPrimitive("a.txt"))).content shouldContain "not a directory"
    }

    // ---- caps and wiring -----------------------------------------------------

    @Test fun `read truncates at the configured cap and admits it`() {
        val root = sandbox()
        put(root, "big.txt", "x".repeat(5000))
        val r = tools(root, FileToolLimits.default.copy(readMaxChars = 200))
            .read
            .run(args("file_path" to JsonPrimitive("big.txt")))

        (r.content.length < 600) shouldBe true
        r.content shouldContain "truncated at 200 chars"
    }

    @Test fun `only the non-mutating tools are read-only`() {
        val t = tools(sandbox())
        // `ls` is gone: `glob` with `*` lists a directory and skips build output
        // while doing it, so two tools were charging schema tokens for one job.
        t.all.filter { it.readOnly }.map { it.name } shouldContainExactlyInAnyOrder
            listOf("read_to_context", "glob", "grep")
        t.all.filterNot { it.readOnly }.map { it.name } shouldContainExactlyInAnyOrder listOf("write", "edit")
    }

    @Test fun `every file tool declares its required arguments`() {
        // A schema missing `required` produces a call with no arguments at all,
        // which the model then cannot diagnose from the error.
        tools(sandbox()).all.filter { it.name != "ls" }.forEach { t ->
            (t.parameters as JsonObject).containsKey("required") shouldBe true
        }
    }

    // ---- the pure parts ------------------------------------------------------

    @Test fun `stripLineNumbers removes exactly the prefix read emits`() {
        val numbered = FileTools.numbered(listOf("a", "  b"), 41)
        FileTools.stripLineNumbers(numbered) shouldBe "a\n  b"
        // Text that merely starts with a digit is untouched: there is no tab.
        FileTools.stripLineNumbers("42 apples") shouldBe "42 apples"
    }

    @Test fun `countOccurrences does not overlap and is zero for an empty needle`() {
        FileTools.countOccurrences("aaaa", "aa") shouldBe 2
        FileTools.countOccurrences("abc", "z") shouldBe 0
        FileTools.countOccurrences("abc", "") shouldBe 0
    }

    @Test fun `the skip sets are shared rather than copied`() {
        // Three callers now, and two of the copies had already drifted.
        kotllm.util.Skips.dirs shouldContain "node_modules"
        kotllm.rag.Rag.SkipDirs shouldBe kotllm.util.Skips.dirs
        kotllm.rag.Rag.BinaryExtensions shouldBe kotllm.util.Skips.binaryExtensions
        kotllm.util.Skips.containsSkipped(java.nio.file.Paths.get("a/target/b.scala")) shouldBe true
        kotllm.util.Skips.containsSkipped(java.nio.file.Paths.get("a/src/b.scala")) shouldBe false
    }
}
