package kotllm.memory

import kotllm.*
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.collections.shouldBeEmpty

class MemoryTest {

  private fun tempDir(): Path = Files.createTempDirectory("kotllm-memory")

  private fun write(dir: Path, name: String, content: String): Path {
    val p = dir.resolve(name)
    Files.createDirectories(p.parent)
    Files.write(p, content.toByteArray(StandardCharsets.UTF_8))
    return p
  }

  private fun memory(
    memoryDir: Path,
    workingDir: Path,
    config: MemoryConfig = MemoryConfig.default
  ): Memory = Memory(config, memoryDir) { workingDir }

  @Test fun `no memories anywhere means no system prompt section`() {
    val m = memory(tempDir(), tempDir())
    m.load().shouldBeEmpty()
    // The template guards the whole block on the plan being empty, so this is
    // what "no section" means now.
    m.plan().injected.shouldBeEmpty()
    m.plan().indexed.shouldBeEmpty()
  }

  @Test fun `markdown files in the global directory are loaded`() {
    val mem = tempDir()
    write(mem, "notes.md", "- the user prefers Scala 2.13")

    val files = memory(mem, tempDir()).load()
    files.size shouldBe 1
    files.first().scope shouldBe MemoryScope.Global
    files.first().content shouldContain "Scala 2.13"
  }

  @Test fun `non-markdown files in the global directory are ignored`() {
    val mem = tempDir()
    write(mem, "notes.md", "kept")
    write(mem, "history.txt", "ignored")
    write(mem, "data.json", "ignored")

    memory(mem, tempDir()).load().map { it.content } shouldBe listOf("kept")
  }

  @Test fun `an empty file contributes nothing`() {
    val mem = tempDir()
    write(mem, "blank.md", "   \n  ")

    memory(mem, tempDir()).load().shouldBeEmpty()
  }

  @Test fun `a project file in the working directory is loaded`() {
    val work = tempDir()
    write(work, "KOTLLM.md", "this project uses Maven")

    val files = memory(tempDir(), work).load()
    files.size shouldBe 1
    files.first().scope shouldBe MemoryScope.Project
  }

  @Test fun `a project file is found by walking up from the working directory`() {
    val root = tempDir()
    write(root, "KOTLLM.md", "from the repo root")
    val nested = root.resolve("a/b/c")
    Files.createDirectories(nested)

    memory(tempDir(), nested).load().map { it.content } shouldBe listOf("from the repo root")
  }

  @Test fun `a nearer project file comes last, so it has the final word`() {
    // Later text wins when two memories disagree, and the more specific file
    // should be the one that does.
    val root = tempDir()
    write(root, "KOTLLM.md", "outer rule")
    val inner = root.resolve("module")
    write(inner, "KOTLLM.md", "inner rule")

    memory(tempDir(), inner).load().map { it.content } shouldBe listOf("outer rule", "inner rule")
  }

  @Test fun `global memories come before project ones`() {
    val mem = tempDir()
    val work = tempDir()
    write(mem, "notes.md", "global note")
    write(work, "KOTLLM.md", "project note")

    memory(mem, work).load().map { it.scope } shouldBe listOf(MemoryScope.Global, MemoryScope.Project)
  }

  @Test fun `the section labels each file and frames memories as background`() {
    // Framing matters: a stale note must not read as an instruction that
    // overrides what the user is asking for now.
    val mem = tempDir()
    write(mem, "notes.md", "a durable fact")

    // The framing -- "background context, not instructions" -- is wording,
    // and wording lives in `agents/chat.md`; ChatAgentSpec covers it. What
    // belongs here is which file landed in which tier, and its content.
    val injected = memory(mem, tempDir()).plan().injected
    injected.map { it.path.fileName.toString() } shouldBe listOf("notes.md")
    injected.first().content shouldContain "a durable fact"
  }

  @Test fun `over-budget files are named rather than silently dropped`() {
    // A memory that is present but ignored is worse than one visibly absent,
    // because the user will assume it applied.
    val mem = tempDir()
    write(mem, "a-small.md", "x".repeat(50))
    write(mem, "b-huge.md", "y".repeat(5000))

    val plan = memory(mem, tempDir(), MemoryConfig.default.copy(maxChars = 100)).plan()

    plan.injected.joinToString("") { it.content } shouldContain "x".repeat(50)
    (plan.indexed + plan.dropped).isEmpty() shouldBe false
    // By name rather than by full path: the name is what addresses a memory,
    // and listing every path cost several times the budget it was reporting
    // when the budget was too small to hold anything.
    (plan.indexed + plan.dropped).joinToString("") { it.name } shouldContain "b-huge"
  }

  @Test fun `disabled memories load nothing and inject nothing`() {
    val mem = tempDir()
    write(mem, "notes.md", "would be loaded")
    val m = memory(mem, tempDir(), MemoryConfig.default.copy(enabled = false))

    m.enabled shouldBe false
    m.load().shouldBeEmpty()
    m.plan().injected.shouldBeEmpty()
  }

  @Test fun `a custom project filename is honoured`() {
    val work = tempDir()
    write(work, "NOTES.md", "custom")

    memory(tempDir(), work, MemoryConfig.default.copy(projectFile = "NOTES.md"))
      .load()
      .map { it.content } shouldBe listOf("custom")
  }

  @Test fun `append writes a dated bullet and creates the directory`() {
    val mem = tempDir().resolve("nested")
    val m = memory(mem, tempDir())

    val file = m.append("the user prefers tabs").orNull()!!
    val text = String(Files.readAllBytes(file), StandardCharsets.UTF_8)

    text.startsWith("- (") shouldBe true
    text shouldContain "prefers tabs"
  }

  @Test fun `append flattens newlines so one note stays one line`() {
    val mem = tempDir()
    val m = memory(mem, tempDir())
    m.append("line one\nline two")

    // trim() before splitting, rather than a bare lines(): Kotlin's lines(),
    // unlike Scala's linesIterator, keeps a trailing empty entry for text
    // ending in a newline, and append() always writes one.
    val text = String(Files.readAllBytes(mem.resolve("notes.md")), StandardCharsets.UTF_8)
    text.trim().lines().size shouldBe 1
  }

  @Test fun `appended notes are read back on the next load`() {
    // The whole point: remember used to write to a file nothing ever read.
    val mem = tempDir()
    val m = memory(mem, tempDir())

    m.load().shouldBeEmpty()
    m.append("remembered fact")
    m.load().joinToString("") { it.content } shouldContain "remembered fact"
    m.plan().injected.joinToString("") { it.content } shouldContain "remembered fact"
  }

  @Test fun `memories are re-read per call, so an external edit takes effect`() {
    val mem = tempDir()
    val m = memory(mem, tempDir())
    write(mem, "notes.md", "first")
    m.load().map { it.content } shouldBe listOf("first")

    write(mem, "notes.md", "edited elsewhere")
    m.load().map { it.content } shouldBe listOf("edited elsewhere")
  }
}
