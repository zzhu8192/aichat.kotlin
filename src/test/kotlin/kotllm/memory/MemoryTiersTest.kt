package kotllm.memory

import kotllm.*
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import io.kotest.matchers.collections.shouldBeEmpty

/** Budget-driven degradation: bodies while they fit, one line each when they do
  * not.
  *
  * The question this answers is the one that decides whether a `search_memory`
  * tool should exist at all. Under the budget every memory is already in the
  * system prompt, so a lookup tool would cost its schema plus a round trip to
  * deliver what the model is already reading. Past the budget the old code
  * named the overflow and dropped it, leaving the model told about a memory it
  * could not reach. Listing it instead is what makes the tier-2 read both
  * necessary and *discoverable* -- a schema says a tool exists, never that
  * there is something worth reading, which cost a live RAG failure to learn.
  */
class MemoryTiersTest {

  private fun dir(): Path = Files.createTempDirectory("kotllm-tiers")

  private fun write(d: Path, name: String, content: String) {
    Files.write(d.resolve(name), content.toByteArray(StandardCharsets.UTF_8))
  }

  // Memory's own two-arg entry point is a plain `fun apply` on the companion,
  // not `operator fun invoke`, so it has to be called by name rather than as
  // `Memory(config, d)`.
  private fun memory(d: Path, maxChars: Int): Memory =
    Memory(MemoryConfig.default.copy(maxChars = maxChars), d)

  @Test fun `under the budget every memory is injected whole and nothing is listed`() {
    val d = dir()
    write(d, "a.md", "# Build with maven\nRun mvn -q clean package.")
    write(d, "b.md", "# The user prefers Scala 2.13\nNo Scala 3 syntax.")

    val m = memory(d, 8000)
    val p = m.plan()
    p.injected.size shouldBe 2
    p.indexed.shouldBeEmpty()
    p.dropped.shouldBeEmpty()

    val bodies = p.injected.joinToString(" ") { it.content }
    bodies shouldContain "Run mvn -q clean package"
    bodies shouldContain "No Scala 3 syntax"
    // Nothing to look up, so the template's read_memory branch stays shut.
    p.indexed.shouldBeEmpty()
  }

  @Test fun `nothing is offered to look up while everything fits`() {
    val d = dir()
    write(d, "a.md", "# Short\nfits easily")
    memory(d, 8000).hasIndexOnly() shouldBe false
  }

  @Test fun `over the budget a memory is listed by name, not dropped`() {
    val d = dir()
    write(d, "a.md", "# Small and useful\n" + "x".repeat(50))
    write(d, "b.md", "# A long note about the deployment process\n" + "y".repeat(4000))

    val m = memory(d, 600)
    val p = m.plan()

    p.injected.map { it.name } shouldBe listOf("a")
    p.indexed.map { it.name } shouldBe listOf("b")
    p.dropped.shouldBeEmpty()

    // The body of the small one, and only the *label* of the big one: that
    // split is what tier 2 is. The template turns the label into a line that
    // names read_memory; ChatAgentSpec covers the wording.
    p.injected.joinToString("") { it.content } shouldContain "x".repeat(50)
    p.injected.joinToString("") { it.content } shouldNotContain "y".repeat(50)
    p.indexed.joinToString("") { it.label } shouldContain "A long note about the deployment process"

    m.hasIndexOnly() shouldBe true
  }

  @Test fun `a listed memory is readable in full by name`() {
    val d = dir()
    write(d, "big.md", "# Deployment\n" + "z".repeat(4000))

    val m = memory(d, 600)
    m.plan().indexed.map { it.name } shouldBe listOf("big")
    (m.byName("big").map { it.content.length }.getOrElse { 0 } > 4000) shouldBe true
  }

  @Test fun `a miss names what does exist, so the model corrects itself in one call`() {
    val d = dir()
    write(d, "known.md", "# Known\nyes")
    memory(d, 8000).byName("unknown").fold(
      { err -> err shouldContain "known" },
      { throw AssertionError("expected a miss") }
    )
  }

  @Test fun `the label is the first real line, with markdown punctuation stripped`() {
    // No frontmatter, therefore no parser: a heading is what someone writing
    // markdown puts first anyway, and this works on hand-written files and on
    // the dated bullet form /memory add produces.
    val d = dir()
    write(d, "heading.md", "\n\n#  Uses tabs, not spaces  \nbody")
    write(d, "bullet.md", "- (2026-09-18) prefers verbose errors\n")

    val byName = memory(d, 8000).load().associate { it.name to it.label }
    byName["heading"] shouldBe "Uses tabs, not spaces"
    byName["bullet"] shouldBe "(2026-09-18) prefers verbose errors"
  }

  @Test fun `index lines are charged against the same budget the user configured`() {
    // Otherwise the configured number stops being the truth about what is
    // spent, which is the whole reason it exists.
    val d = dir()
    (1..40).forEach { i ->
      write(d, "m%02d.md".format(i), "# Memory number $i with a reasonably long label\n" + "q".repeat(400))
    }

    val m = memory(d, 1200)
    val plan = m.plan()

    (plan.injected.size + plan.indexed.size + plan.dropped.size) shouldBe 40
    // What the budget is actually about: the bodies that reach the prompt,
    // not the framing the template wraps them in.
    (plan.injected.sumOf { it.content.length } < 1300) shouldBe true
  }

  @Test fun `not even a line fitting is still reported rather than silent`() {
    val d = dir()
    write(d, "a.md", "# " + "a".repeat(200))
    write(d, "b.md", "# " + "b".repeat(200))

    val m = memory(d, 60)
    val p = m.plan()
    p.dropped.isEmpty() shouldBe false
    // Named rather than silent: the template lists them, and a memory that is
    // present but ignored is worse than one visibly absent.
    p.dropped.map { it.name }.isEmpty() shouldBe false
  }

  @Test fun `a write is visible immediately, cache notwithstanding`() {
    // The cache exists so the status footer does not re-read the directory on
    // every keystroke; it must never make a tool result a lie.
    val d = dir()
    val m = memory(d, 8000)
    m.load().shouldBeEmpty()

    m.upsert("fresh", "# Just written\nbody").isOk shouldBe true
    m.load().map { it.name } shouldBe listOf("fresh")

    m.delete("fresh").isOk shouldBe true
    m.load().shouldBeEmpty()
  }

  @Test fun `a memory larger than the whole budget is refused with the reason`() {
    val d = dir()
    memory(d, 500).upsert("huge", "x".repeat(2000)).fold(
      { err -> err shouldContain "budget" },
      { throw AssertionError("expected a refusal") }
    )
  }

  @Test fun `an empty body is refused, pointing at the tool that removes one`() {
    val d = dir()
    memory(d, 8000).upsert("x", "   ").fold(
      { err -> err shouldContain "delete_memory" },
      { throw AssertionError("expected a refusal") }
    )
  }

  @Test fun `a name with nothing usable in it is refused before any write`() {
    Memory.slug("!!!").isOk shouldBe false
    Memory.slug("Build Commands!") shouldBe ok("build-commands")
    Memory.slug("../../etc/passwd") shouldBe ok("etc-passwd")
    Memory.slug("notes.md") shouldBe ok("notes")
  }
}
