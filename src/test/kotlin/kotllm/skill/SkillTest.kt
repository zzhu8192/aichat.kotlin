package kotllm.skill

import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path

import kotlin.test.Test
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import io.kotest.matchers.string.shouldStartWith
import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.collections.shouldNotContain
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldNotBeEmpty

import kotllm.*

/** Agent Skills: the portable six frontmatter fields, three-tier loading, and
 * discovery across the roots Claude Code uses.
 *
 * Every root is pointed at a temp directory, deliberately. The defaults
 * include `~/.claude/skills` and every `.claude/skills` between the working
 * directory and the filesystem root, so a developer who uses Claude Code has
 * a populated one -- and a spec that read it would pass here and fail on CI,
 * which is the failure a test-isolation helper (`TestIsolation` in the Scala
 * tree) exists for.
 */
class SkillTest {

  private fun expectErr(outcome: Outcome<*>): String =
    outcome.fold({ it }, { throw AssertionError("expected a failure but got $it") })

  private fun sandbox(): Path = Files.createTempDirectory("kotllm-skills")

  private fun install(root: Path, name: String, frontmatter: String, body: String): Path {
    val dir = root.resolve(name)
    Files.createDirectories(dir)
    val text = "---\n$frontmatter\n---\n$body"
    Files.write(dir.resolve("SKILL.md"), text.toByteArray(StandardCharsets.UTF_8))
    return dir
  }

  /** Every root isolated: config skills here, an empty home, an empty project. */
  private fun skills(
    configRoot: Path,
    project: Path = Files.createTempDirectory("kotllm-proj"),
    home: Path = Files.createTempDirectory("kotllm-home"),
    config: SkillsConfig = SkillsConfig.default
  ): Skills = Skills(config, configRoot, { project }, { home })

  // ---- frontmatter -------------------------------------------------------

  @Test fun `frontmatter is the block between the first two fences`() {
    Skill.splitFrontmatter("---\nname: a\n---\nbody here") shouldBe Pair("name: a", "body here")
  }

  @Test fun `CRLF and a byte-order mark are tolerated, because downloads have both`() {
    Skill.splitFrontmatter("﻿---\r\nname: a\r\n---\r\nbody") shouldBe Pair("name: a", "body")
  }

  @Test fun `no fence and an unterminated fence are both 'no frontmatter'`() {
    Skill.splitFrontmatter("# just markdown") shouldBe null
    Skill.splitFrontmatter("---\nname: a\nbody with no closing fence") shouldBe null
  }

  // ---- parsing -----------------------------------------------------------

  @Test fun `the six portable fields are read and everything else is collected, not injected`() {
    val root = sandbox()
    val dir = install(
      root,
      "pdf-forms",
      """name: PDF-Forms
        |description: Fill in PDF forms. Use when the user has a form to complete.
        |license: MIT
        |compatibility: ">=1.0"
        |allowed-tools: read, exec
        |metadata:
        |  author: someone
        |context: fork
        |effort: high
        |hooks:
        |  - a""".trimMargin(),
      "Step one."
    )

    val s = Skill.parse(dir.resolve("SKILL.md"), dir, SkillScope.Config).orNull()!!
    // Lowercased rather than refused: the name is only an identifier here.
    s.name shouldBe "pdf-forms"
    s.description shouldStartWith "Fill in PDF forms"
    s.meta.license shouldBe "MIT"
    s.meta.compatibility shouldBe ">=1.0"
    s.meta.encouragedTools shouldBe listOf("read", "exec")
    s.meta.metadata shouldBe mapOf("author" to "someone")
    // Claude Code extensions: visible, never acted on.
    s.meta.unknownFields shouldContain "context"
    s.meta.unknownFields shouldContain "effort"
    s.meta.unknownFields shouldContain "hooks"
    s.body shouldBe "Step one."
  }

  @Test fun `a missing name or description is reported, naming the file`() {
    val root = sandbox()
    val noName = install(root, "a", "description: does a thing", "b")
    val noDesc = install(root, "b", "name: b", "b")

    expectErr(Skill.parse(noName.resolve("SKILL.md"), noName, SkillScope.Config)) shouldContain "'name'"
    expectErr(Skill.parse(noDesc.resolve("SKILL.md"), noDesc, SkillScope.Config)) shouldContain "'description'"
  }

  @Test fun `an oversized description is refused, because it is injected every turn`() {
    val root = sandbox()
    val dir = install(root, "a", "name: a\ndescription: ${"x".repeat(2000)}", "b")
    expectErr(Skill.parse(dir.resolve("SKILL.md"), dir, SkillScope.Config)) shouldContain "1024"
  }

  @Test fun `frontmatter that is not YAML is reported as that, not as a missing name`() {
    val root = sandbox()
    val dir = install(root, "a", "name: [unclosed\n  : :", "b")
    val err = expectErr(Skill.parse(dir.resolve("SKILL.md"), dir, SkillScope.Config))
    err shouldContain "not valid YAML"
  }

  @Test fun `allowed_tools as a list is read the same as the hyphenated spelling`() {
    val root = sandbox()
    val dir = install(root, "a", "name: a\ndescription: d\nallowed-tools:\n  - read\n  - grep", "b")
    Skill.parse(dir.resolve("SKILL.md"), dir, SkillScope.Config).orNull()!!
      .meta.encouragedTools shouldBe listOf("read", "grep")
  }

  // ---- discovery ---------------------------------------------------------

  @Test fun `skills are found in the config directory, and nested inside it`() {
    val root = sandbox()
    install(root, "flat", "name: flat\ndescription: d", "b")
    install(root, "plugins/vendor/nested", "name: nested\ndescription: d", "b")

    val names = skills(root).load().map { it.name }
    names shouldContain "flat"
    names shouldContain "nested"
  }

  @Test fun `a project claudeskills is found by walking up from the working directory`() {
    val root = sandbox()
    val project = Files.createTempDirectory("kotllm-proj")
    val deep = project.resolve("a/b/c")
    Files.createDirectories(deep)
    install(project.resolve(".claude/skills"), "proj", "name: proj\ndescription: d", "b")

    skills(root, project = deep).load().map { it.name } shouldContain "proj"
  }

  @Test fun `a personal ~claudeskills is read in place, so an install needs no copying`() {
    val root = sandbox()
    val home = Files.createTempDirectory("kotllm-home")
    install(home.resolve(".claude/skills"), "personal", "name: personal\ndescription: d", "b")

    val found = skills(root, home = home).load()
    found.map { it.name } shouldContain "personal"
    found.find { it.name == "personal" }!!.scope shouldBe SkillScope.Personal
  }

  @Test fun `skills_claude_dirs off leaves the Claude Code roots alone`() {
    val root = sandbox()
    val home = Files.createTempDirectory("kotllm-home")
    install(home.resolve(".claude/skills"), "personal", "name: personal\ndescription: d", "b")

    skills(root, home = home, config = SkillsConfig.default.copy(claudeDirs = false))
      .load()
      .map { it.name } shouldNotContain "personal"
  }

  @Test fun `the nearest root wins a name collision`() {
    // Otherwise a project could not override a personal skill, which is the
    // whole point of having more than one root.
    val root = sandbox()
    val project = Files.createTempDirectory("kotllm-proj")
    install(root, "dup", "name: dup\ndescription: from config", "config body")
    install(project.resolve(".claude/skills"), "dup", "name: dup\ndescription: from project", "project body")

    val found = skills(root, project = project).load()
    found.count { it.name == "dup" } shouldBe 1
    found.find { it.name == "dup" }!!.body shouldBe "project body"
  }

  @Test fun `a broken skill is reported rather than dropped in silence`() {
    val root = sandbox()
    install(root, "good", "name: good\ndescription: d", "b")
    install(root, "bad", "description: no name here", "b")

    val (ok, problems) = skills(root).scan()
    ok.map { it.name } shouldBe listOf("good")
    problems.size shouldBe 1
    problems.first() shouldContain "bad"
  }

  @Test fun `disabled means nothing is walked at all`() {
    val root = sandbox()
    install(root, "a", "name: a\ndescription: d", "b")
    val off = skills(root, config = SkillsConfig.default.copy(enabled = false))
    off.load().shouldBeEmpty()
    off.listed().first.shouldBeEmpty()
  }

  // ---- tier 1 ------------------------------------------------------------

  @Test fun `tier one is one line per skill, and says to load the body before working`() {
    val root = sandbox()
    install(root, "a", "name: a\ndescription: Does A. Use when asked for A.", "long body here")

    // The listing is data now; `agents/chat.md` turns it into lines, and
    // ChatAgentSpec asserts the rendering. What belongs here is the data.
    val (kept, dropped) = skills(root).listed()
    kept.map { it.meta.name } shouldBe listOf("a")
    kept.first().meta.description shouldBe "Does A. Use when asked for A."
    dropped.shouldBeEmpty()
    // The body is reachable, and deliberately not part of the listing.
    kept.first().body shouldContain "long body here"
  }

  @Test fun `nothing installed lists nothing`() {
    skills(sandbox()).listed().first.shouldBeEmpty()
  }

  @Test fun `over budget, skills are dropped from the tail and named`() {
    val root = sandbox()
    (1..8).forEach { i -> install(root, "s$i", "name: s$i\ndescription: ${"d".repeat(100)}", "b") }

    val (kept, dropped) = skills(root, config = SkillsConfig.default.copy(maxChars = 250)).listed()
    (kept.size < 8) shouldBe true
    dropped.shouldNotBeEmpty()
    (kept.size + dropped.size) shouldBe 8
    // Tail-dropped, so the survivors are a prefix of the load order.
    kept.map { it.meta.name } shouldBe skills(root).load().take(kept.size).map { it.meta.name }
  }

  @Test fun `the tool-name mapping only mentions tools that are actually offered`() {
    // Telling the model that Read means `read` when no file tools are
    // registered points it at something that is not there.
    Skills.toolNameMapping(setOf("read_to_context", "exec"))!! shouldContain "Read -> read_to_context"
    Skills.toolNameMapping(setOf("read_to_context", "exec"))!! shouldContain "Bash -> exec"
    Skills.toolNameMapping(setOf("read_to_context", "exec"))!! shouldNotContain "Grep"
    Skills.toolNameMapping(setOf("scala_eval")) shouldBe null
  }

  @Test fun `the mapping reaches the prompt only when the tools it names are live`() {
    // `prompt.skillToolNames` is what the template interpolates, and it is
    // empty rather than absent when there is nothing to map -- the template
    // does not guard it, and a collapsed blank line is the whole contract.
    Skills
      .toolNameMapping(setOf("read_to_context", "write", "edit", "glob", "grep", "exec"))!! shouldContain
      "Read -> read_to_context"
    Skills.toolNameMapping(setOf("scala_eval")) shouldBe null
  }

  // ---- tier 2 and 3 ------------------------------------------------------

  @Test fun `tier two carries the directory and the bundled files, which tier three needs`() {
    val root = sandbox()
    val dir = install(root, "a", "name: a\ndescription: d\nallowed-tools: read", "See REFERENCE.md")
    Files.write(dir.resolve("REFERENCE.md"), "ref".toByteArray(StandardCharsets.UTF_8))
    Files.createDirectories(dir.resolve("scripts"))
    Files.write(dir.resolve("scripts/fill.py"), "print(1)".toByteArray(StandardCharsets.UTF_8))

    val out = skills(root).render("a", null).orNull()!!
    out shouldContain dir.toAbsolutePath().normalize().toString()
    // A body saying "run scripts/fill.py" is talking about a directory the
    // model has otherwise never been told about.
    out shouldContain "REFERENCE.md"
    out shouldContain "scripts/fill.py"
    out shouldContain "tools this skill expects to use: read"
    out shouldContain "See REFERENCE.md"
    // SKILL.md itself is the body, not a bundled resource.
    out shouldNotContain "SKILL.md"
  }

  @Test fun `$ARGUMENTS is substituted, and emptied when nothing was supplied`() {
    val root = sandbox()
    install(root, "a", "name: a\ndescription: d", "Commit with message: \$ARGUMENTS")
    val s = skills(root)

    s.render("a", "fix the tests").orNull()!! shouldContain "message: fix the tests"
    // Left in place, a model reads it as a literal instruction.
    s.render("a", null).orNull()!! shouldNotContain "\$ARGUMENTS"
  }

  @Test fun `a runaway body is capped so one tool result cannot spend the window`() {
    val root = sandbox()
    install(root, "a", "name: a\ndescription: d", "y".repeat(5000))
    val out = skills(root, config = SkillsConfig.default.copy(bodyMaxChars = 100)).render("a", null).orNull()!!
    out shouldContain "truncated at 100 of 5000 chars"
  }

  @Test fun `an unknown skill name lists what there is`() {
    val root = sandbox()
    install(root, "commit-helper", "name: commit-helper\ndescription: d", "b")
    val s = skills(root)

    expectErr(s.find("nope")) shouldContain "commit-helper"
    // A near miss is offered first, since that is usually the mistake.
    expectErr(s.find("commit")) shouldContain "commit-helper"
  }

  @Test fun `no skills at all points at where to put one`() {
    expectErr(skills(sandbox()).find("x")) shouldContain "SKILL.md"
  }

  @Test fun `lookup is case-insensitive, matching the lowercasing on the way in`() {
    val root = sandbox()
    install(root, "a", "name: Thing\ndescription: d", "b")
    skills(root).find("THING").orNull()!!.name shouldBe "thing"
  }

  // ---- reporting ---------------------------------------------------------

  @Test fun `the report shows what is not injected, including the fields we ignore`() {
    val root = sandbox()
    install(root, "a", "name: a\ndescription: d\nlicense: MIT\nallowed-tools: read\ncontext: fork", "b")

    val text = skills(root).report()
    text shouldContain "license: MIT"
    // The honest statement about allowed-tools, in the one place a user looks.
    text shouldContain "encouraged, not enforced"
    text shouldContain "ignored fields: context"
    text shouldContain "roots searched"
  }

  @Test fun `the report names the failures too`() {
    val root = sandbox()
    install(root, "bad", "nothing: useful", "b")
    skills(root).report() shouldContain "could not be loaded"
  }
}
