package kotllm.skill

import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import kotllm.*
import kotllm.util.Conf
import kotllm.util.Yaml

sealed interface SkillScope {
    // Nested, not top-level. Scala's `case object Config extends SkillScope`
    // inside `object SkillScope` is namespaced by its parent; a top-level
    // Kotlin `data object Config` would sit in `kotllm.skill` next to
    // `kotllm.config.Config` and be imported by accident.
    data object Config : SkillScope
    data object Personal : SkillScope
    data object Project : SkillScope
    data object Configured : SkillScope

    companion object {
        fun name(s: SkillScope): String = when (s) {
            is Config -> "config"
            is Personal -> "personal"
            is Project -> "project"
            is Configured -> "configured"
        }
    }
}

data class SkillMeta(
    val name: String,
    val description: String,
    val license: String?,
    val compatibility: String?,
    val metadata: Map<String, String>,
    val encouragedTools: List<String>,
    val unknownFields: List<String>
)

data class Skill(
    val meta: SkillMeta,
    val body: String,
    val dir: Path,
    val file: Path,
    val scope: SkillScope
) {
    val name: String get() = meta.name
    val description: String get() = meta.description

    // Everything in the skill directory except SKILL.md, as paths relative to it.
    // This is tier 3, and it has to be listed rather than merely reachable: a body
    // that says "run `scripts/fill.py`" is talking about a directory the model has
    // otherwise never been told about, and without the listing it would not know
    // the file existed.
    val bundled: List<Pair<String, Long>>
        get() = try {
            Files.walk(dir, 3).use { s ->
                s.toList()
                    .filter { p -> Files.isRegularFile(p) && p != file }
                    .map { p -> dir.relativize(p).toString().replace('\\', '/') to size(p) }
                    .sortedBy { it.first }
            }
        } catch (e: Exception) {
            emptyList()
        }

    private fun size(p: Path): Long = try {
        Files.size(p)
    } catch (e: Exception) {
        -1L
    }

    val chars: Int get() = body.length

    // A companion, not a sibling `object Skill`. Scala lets a class and an
    // object share a name at the same level; Kotlin calls that a redeclaration.
    companion object {
        private const val Fence = "---"

        const val MaxDescriptionChars = 1024
        const val MaxNameChars = 64

        fun parse(file: Path, dir: Path, scope: SkillScope): Outcome<Skill> {
            val raw = try {
                String(Files.readAllBytes(file), StandardCharsets.UTF_8)
            } catch (e: Exception) {
                return err("$file could not be read: ${e::class.simpleName}")
            }

            val frontmatter = splitFrontmatter(raw)
                ?: return err("$file has no '---' YAML frontmatter, so it declares no name or description")
            val (front, body) = frontmatter

            // A malformed frontmatter block is reported as itself rather than as
            // a missing `name`, which is what a bare `Conf.empty` fallback would
            // have said and would have sent the user looking in the wrong place.
            val conf = try {
                Yaml.loadString(front)
            } catch (e: Exception) {
                val msg = e.message ?: e::class.simpleName ?: ""
                return err("$file has frontmatter that is not valid YAML: ${msg.lines().firstOrNull() ?: ""}")
            }

            return meta(conf, file).map { m -> Skill(m, body.trim(), dir, file, scope) }
        }

        // Frontmatter is the block between the first `---` line and the next one.
        // Tolerant of a leading BOM and of CRLF, both of which a downloaded skill
        // routinely has.
        fun splitFrontmatter(raw: String): Pair<String, String>? {
            val text = raw.removePrefix("﻿").replace("\r\n", "\n")
            val lines = text.split("\n")
            val start = lines.indexOfFirst { it.trim().isNotEmpty() }
            if (start < 0 || lines[start].trim() != Fence) return null
            val end = lines.drop(start + 1).indexOfFirst { it.trim() == Fence }
            return if (end < 0) null else (lines.slice(start + 1 until start + 1 + end).joinToString("\n") to lines.drop(start + 1 + end + 1).joinToString("\n"))
        }

        private val Known = setOf("name", "description", "license", "compatibility", "metadata", "allowed-tools", "allowed_tools")

        private fun meta(conf: Conf, file: Path): Outcome<SkillMeta> {
            val name = conf.str("name")?.trim()?.takeIf { it.isNotEmpty() }
            val desc = conf.str("description")?.trim()?.takeIf { it.isNotEmpty() }

            return when {
                name == null -> err("$file has no 'name' in its frontmatter, which the format requires")
                desc == null -> err("$file has no 'description', which is what tells a model when to use it")
                (desc.length > MaxDescriptionChars) -> err("$file has a ${desc.length}-char description; the limit is $MaxDescriptionChars, and it is injected on every turn")
                (name.length > MaxNameChars) -> err("$file has a ${name.length}-char name; the limit is $MaxNameChars")
                else -> ok(
                    SkillMeta(
                        // Lowercased rather than refused. The format says lowercase and
                        // Claude Code enforces it, but the name is only an identifier
                        // here, and rejecting a working skill over its capitalisation
                        // would be pedantry with a cost.
                        name = name.lowercase(),
                        description = desc,
                        license = conf.str("license")?.trim()?.takeIf { it.isNotEmpty() },
                        compatibility = conf.str("compatibility")?.trim()?.takeIf { it.isNotEmpty() },
                        metadata = conf
                            .sub("metadata")
                            ?.raw
                            ?.filterValues { it != null }
                            ?.mapValues { it.value.toString() }
                            ?: emptyMap(),
                        encouragedTools = conf.strList("allowed-tools") + conf.strList("allowed_tools"),
                        unknownFields = conf.keys.filterNot { Known.contains(it) }.sorted()
                    )
                )
            }
        }

        // Substitutes $ARGUMENTS, which is what hub skills use for a parameter.
        //
        // The `arguments:` frontmatter field that declares named placeholders is a
        // Claude Code extension and is deliberately not implemented; this one is a
        // single string replace and is worth having because so many skills expect
        // it. An unsupplied $ARGUMENTS becomes empty rather than being left in
        // the text, where the model would read it as a literal instruction.
        fun withArguments(body: String, arguments: String?): String =
            body.replace("\$ARGUMENTS", arguments?.trim() ?: "")
    }
}
