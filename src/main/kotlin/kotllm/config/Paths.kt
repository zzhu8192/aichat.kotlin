package kotllm.config

import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths as JPaths

/** Filesystem locations kotllm reads and writes.
  *
  * KOTLLM_CONFIG_DIR overrides everything, which is what the integration
  * tests use to avoid touching a developer's real config.
  *
  * **The `scallm` spellings are still honoured**, and deliberately so: the
  * rename must not silently relocate an existing installation's config,
  * sessions and memories, which is what "we changed the directory" reads like
  * from the outside. A `~/.config/scallm` that already exists keeps being used
  * until it is moved by hand. Drop this after a release.
  */
object Paths {

  private fun isWindows(): Boolean =
    System.getProperty("os.name", "").lowercase().contains("win")

  private fun home(): Path = JPaths.get(System.getProperty("user.home", ".")).toAbsolutePath()

  private fun env(name: String): String? =
    System.getenv(name)?.trim()?.takeIf { it.isNotEmpty() }

  fun configDir(): Path =
    (env("KOTLLM_CONFIG_DIR") ?: env("SCALLM_CONFIG_DIR"))
      ?.let { JPaths.get(it).toAbsolutePath().normalize() }
      ?: defaultConfigDir()

  private fun defaultConfigDir(): Path {
    val base =
      if (isWindows()) env("APPDATA")?.let { JPaths.get(it) } ?: home().resolve(".config")
      else home().resolve(".config")
    val legacy = base.resolve("scallm")
    // Only when it is already there: a fresh install gets the new name.
    return if (Files.isDirectory(legacy)) legacy else base.resolve("kotllm")
  }

  fun configFile(): Path  = configDir().resolve("config.yaml")
  fun sessionsDir(): Path = configDir().resolve("sessions")
  fun memoryDir(): Path   = configDir().resolve("memory")
  fun agentsDir(): Path   = configDir().resolve("agents")

  /** One directory per skill, each holding a SKILL.md. Claude Code's own
    * ~/.claude/skills and .claude/skills are read too; see kotllm.skill.Skills.
    */
  fun skillsDir(): Path   = configDir().resolve("skills")

  /** Scala tool files, compiled at startup. Created like the others so the
    * directory is discoverable without reading the docs.
    */
  fun toolsDir(): Path    = configDir().resolve("tools")

  /** One directory per agent run: its source, its log, its artifact. */
  fun runsDir(): Path     = configDir().resolve("runs")
  fun ragDir(): Path      = configDir().resolve("rag")
  fun historyFile(): Path = configDir().resolve("history.txt")

  /** How often each `:shortcode:` has been sent, so the completion list can
    * lead with the ones this user actually reaches for. Beside the history
    * file, and the same kind of thing: a record of what was typed.
    */
  fun emojiUsageFile(): Path = configDir().resolve("emoji-usage.tsv")

  /** Creates the config directory tree if absent. Never overwrites. */
  fun ensureDirs(): Unit {
    val dirs = listOf(
      configDir(), agentsDir(), skillsDir(), toolsDir(),
      sessionsDir(), memoryDir(), ragDir()
    )
    dirs.forEach { p ->
      if (!Files.exists(p)) Files.createDirectories(p)
    }
  }
}
