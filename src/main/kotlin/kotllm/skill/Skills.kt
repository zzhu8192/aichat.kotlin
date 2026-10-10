package kotllm.skill

import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import kotllm.*

data class SkillsConfig(
    val enabled: Boolean,
    val maxChars: Int,
    val bodyMaxChars: Int,
    val claudeDirs: Boolean,
    val extraDirs: List<String>
) {
    companion object {
        val default: SkillsConfig =
            SkillsConfig(enabled = true, maxChars = 4000, bodyMaxChars = 40000, claudeDirs = true, extraDirs = emptyList())
    }
}

// Agent Skills: a directory with a SKILL.md, loaded in three tiers.
//
// The tiers are the whole idea. Many skills can be available for the price of
// one line each; only the relevant one costs a body; its bundled files cost
// nothing until read.
//
//   1. metadata  - `name: description`, injected every turn, here.
//   2. body      - returned by the `skill` tool when the model asks.
//   3. resources - the bundled files, reached with `read_to_context` and `exec`.
//
// Read fresh on every call rather than cached, like memories.
//
// `allowed-tools` is reported, never enforced, on the user's instruction:
// enforcement would mean tracking when a skill is "in effect" - real state,
// for a constraint the model will honour or not regardless. A model reaching
// for something else is a prompt problem, visible in the transcript.
// See docs/skills.md.
class Skills(
    val config: SkillsConfig,
    private val configSkillsDir: Path,
    private val workingDir: () -> Path,
    private val home: () -> Path = { Paths.get(System.getProperty("user.home", ".")) }
) {
    // How far up the tree to look for .claude/skills, and how deep to walk
    // inside a root. Bounded so a deep path on a network share does not turn
    // every turn into a crawl; 4 is enough for the nested plugin layouts hubs
    // produce.
    private val MaxAncestors = 12
    private val MaxDepth = 4

    val enabled: Boolean get() = config.enabled

    // Every root that exists, nearest last.
    val roots: List<Pair<Path, SkillScope>>
        get() {
            val configured = config.extraDirs.mapNotNull { d ->
                try {
                    val path = Paths.get(d.trim()).toAbsolutePath().normalize()
                    path to SkillScope.Configured
                } catch (e: Exception) {
                    null
                }
            }
            val claude = if (!config.claudeDirs) emptyList()
            else listOf(home().resolve(".claude").resolve("skills") to SkillScope.Personal) + projectRoots()

            return (listOf(configSkillsDir to SkillScope.Config) + configured + claude)
                .filter { (r, _) -> Files.isDirectory(r) }
        }

    // .claude/skills from the working directory upwards, nearest last so the
    // most specific project wins a name collision.
    private fun projectRoots(): List<Pair<Path, SkillScope>> {
        val found = mutableListOf<Path>()
        var dir: Path? = workingDir().toAbsolutePath().normalize()
        var depth = 0
        while (dir != null && depth < MaxAncestors) {
            found.add(dir.resolve(".claude").resolve("skills"))
            dir = dir.parent
            depth += 1
        }
        return found.asReversed().map { it to SkillScope.Project }
    }

    // Everything that parsed, and everything that did not.
    //
    // One walk, both answers, because the failures have to be reported: a
    // SKILL.md that is present and ignored is worse than one visibly absent.
    fun scan(): Pair<List<Skill>, List<String>> {
        if (!config.enabled) return emptyList<Skill>() to emptyList()

        val ok = mutableListOf<Skill>()
        val problems = mutableListOf<String>()

        roots.forEach { (root, scope) ->
            manifests(root).forEach { file ->
                when (val result = Skill.parse(file, file.parent, scope)) {
                    is Outcome.Ok -> ok.add(result.value)
                    is Outcome.Err -> problems.add(result.message)
                }
            }
        }

        // Nearest last, so a later root replaces an earlier one of the same name.
        // Reverse and keep only the first occurrence of each name (which is the last
        // in the original list).
        val all = ok
        val seen = mutableSetOf<String>()
        val deduped = mutableListOf<Skill>()
        for (s in all.asReversed()) {
            if (!seen.contains(s.name)) {
                deduped.add(0, s)
                seen.add(s.name)
            }
        }

        return deduped.sortedBy { it.name } to problems
    }

    fun load(): List<Skill> = scan().first

    fun problems(): List<String> = scan().second

    private fun manifests(root: Path): List<Path> = try {
        val stream = Files.walk(root, MaxDepth)
        try {
            stream.use { s ->
                s.toList()
                    .filter { p -> Files.isRegularFile(p) && p.fileName.toString() == "SKILL.md" }
                    .sortedBy { it.toString() }
            }
        } finally {
            stream.close()
        }
    } catch (e: Exception) {
        emptyList()
    }

    fun find(name: String): Outcome<Skill> {
        val wanted = name.trim().lowercase()
        val all = load()
        return when (val s = all.find { it.name == wanted }) {
            is Skill -> ok(s)
            null -> {
                if (all.isEmpty()) {
                    err("no skill named '$name'; no skills are installed. Put one in $configSkillsDir/<name>/SKILL.md")
                } else {
                    val near = all.map { it.name }.filter { n -> n.contains(wanted) || wanted.contains(n) }
                    val hint = if (near.isNotEmpty()) near.joinToString(", ") else all.take(12).map { it.name }.joinToString(", ")
                    err("no skill named '$name'; available: $hint")
                }
            }
        }
    }

    // ---- tier 1 ------------------------------------------------------------

    // Skills that fit the listing budget, and the names of those that do not.
    //
    // The budget belongs here rather than in whatever renders the list. It
    // moved out to the prompt template for one revision and the cap was
    // silently lost - the template looped over load() entire - which on a
    // machine with .claude/skills on the search path is an unbounded system
    // prompt. Data and the rule about how much of it is affordable are the
    // same concern, and this is where both can be tested without a Runner.
    //
    // Costed as `- name: description`, which is what the listing writes.
    fun listed(): Pair<List<Skill>, List<String>> {
        var used = 0
        val kept = mutableListOf<Skill>()
        val over = mutableListOf<String>()
        load().forEach { s ->
            val cost = s.meta.name.length + s.meta.description.length + 4
            if (used + cost <= config.maxChars) {
                kept.add(s)
                used += cost
            } else {
                over.add(s.meta.name)
            }
        }
        return kept to over
    }

    // ---- tier 2 ------------------------------------------------------------

    // One skill's instructions, plus where it lives and what is bundled with it.
    //
    // The absolute directory and the file listing are not decoration. A body
    // says "run `scripts/fill.py`" or "see `REFERENCE.md`", and those paths are
    // relative to a directory the model has otherwise never been told; without
    // the listing it would not know tier 3 existed.
    fun render(skill: Skill, arguments: String? = null): String {
        val sb = StringBuilder()
        sb.append("skill: ${skill.name}  (${SkillScope.name(skill.scope)})\n")
        sb.append("directory: ${skill.dir.toAbsolutePath().normalize()}\n")

        if (skill.meta.encouragedTools.isNotEmpty()) {
            sb.append("tools this skill expects to use: ${skill.meta.encouragedTools.joinToString(", ")}\n")
        }

        val bundled = skill.bundled
        if (bundled.isNotEmpty()) {
            sb.append("files bundled with this skill, named relative to the directory above:\n")
            bundled.take(40).forEach { (rel, size) ->
                sb.append("  $rel  ($size bytes)\n")
            }
            if (bundled.size > 40) {
                sb.append("  [${bundled.size - 40} more]\n")
            }
        }

        sb.append("\n")
        val body = Skill.withArguments(skill.body, arguments)
        if (body.length <= config.bodyMaxChars) {
            sb.append(body)
        } else {
            sb.append(body.take(config.bodyMaxChars))
            sb.append("\n\n[skill body truncated at ${config.bodyMaxChars} of ${body.length} chars]")
        }
        return sb.toString()
    }

    fun render(name: String, arguments: String?): Outcome<String> =
        find(name).map { render(it, arguments) }

    // ---- reporting ---------------------------------------------------------

    // `/skills`: everything discovered, with the facts that are not injected -
    // scope, size, license, and any frontmatter fields we do not implement, so
    // a Claude Code skill relying on `context: fork` is visibly doing so.
    fun report(): String {
        val (skills, problems) = scan()
        val sb = StringBuilder()

        if (!config.enabled) return "skills are disabled (skills_enabled: false)"

        if (skills.isEmpty() && problems.isEmpty()) {
            sb.append("no skills found. Roots searched:\n")
        } else {
            sb.append("${skills.size} skill(s):\n")
        }

        skills.forEach { s ->
            sb.append(String.format("\n  %-28s %-10s %6d chars\n", s.name, SkillScope.name(s.scope), s.chars))
            sb.append("    ${s.description.take(160)}\n")
            s.meta.license?.let { l -> sb.append("    license: $l\n") }
            s.meta.compatibility?.let { c -> sb.append("    compatibility: $c\n") }
            if (s.meta.encouragedTools.isNotEmpty()) {
                sb.append("    expects tools: ${s.meta.encouragedTools.joinToString(", ")} (encouraged, not enforced)\n")
            }
            val bundled = s.bundled
            if (bundled.isNotEmpty()) {
                sb.append("    bundled: ${bundled.size} file(s)\n")
            }
            if (s.meta.unknownFields.isNotEmpty()) {
                sb.append("    ignored fields: ${s.meta.unknownFields.joinToString(", ")}\n")
            }
        }

        if (problems.isNotEmpty()) {
            sb.append("\n${problems.size} file(s) could not be loaded:\n")
            problems.forEach { p -> sb.append("  $p\n") }
        }

        val searched = roots
        sb.append(if (searched.isEmpty()) "\nno skill directories exist yet.\n" else "\nroots searched:\n")
        searched.forEach { (r, scope) -> sb.append("  $r  (${SkillScope.name(scope)})\n") }
        if (searched.isEmpty()) sb.append("  create $configSkillsDir/<name>/SKILL.md\n")

        return sb.toString().trimEnd()
    }

        companion object {
        // The name the model sees. Fixed, like `search` and `fetch`: it is a
        // contract the model learns and a string that skill descriptions will
        // eventually mention.
        val ToolName = "skill"

        // What a skill written for Claude Code will call a tool, and what it is
        // here. Two lines in the prompt, and without them half of every downloaded
        // skill is unexecutable - which is the reason the file tools were built
        // first rather than after.
        val ClaudeCodeNames: List<Pair<String, String>> = listOf(
            "Read" to "read_to_context",
            "Write" to "write",
            "Edit" to "edit",
            "Glob" to "glob",
            "Grep" to "grep",
            "Bash" to "exec",
            "WebFetch" to "fetch",
            "WebSearch" to "web_search"
        )

        // Only the mappings whose target is actually active. Telling the model that
        // `Read` means `read` when no file tools are registered would point it at
        // something that is not there.
        fun toolNameMapping(availableTools: Set<String>): String? {
            val usable = ClaudeCodeNames.filter { (_, ours) -> availableTools.contains(ours) }
            return if (usable.isEmpty()) {
                null
            } else {
                "Skill instructions are often written for other agent harnesses and name tools " +
                    "accordingly. Those names map to the tools you have here: " +
                    usable.joinToString(", ") { (theirs, ours) -> "$theirs -> $ours" } +
                    ". Use the tool you actually have rather than reporting that one is missing."
            }
        }

        operator fun invoke(config: SkillsConfig, skillsDir: Path): Skills =
            Skills(config, skillsDir, { Paths.get(System.getProperty("user.dir", ".")).toAbsolutePath() })
    }
}
