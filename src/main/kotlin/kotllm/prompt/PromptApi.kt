package kotllm.prompt

import kotllm.Runner

/** What `prompt.*` means inside `agents/chat.md`.
  *
  * Two kinds of member, and the split is the point.
  *
  * **Data** -- `memoryList`, `skillList`, `libraries`, `taskList` -- is what
  * the harness knows. The template loops over it and decides how it reads, so
  * the wording of the prompt lives in the file the user owns rather than in a
  * `StringBuilder` they would have to recompile to change.
  *
  * **Sections** -- `rag()`, `ragContext()`, `systemPrompt()` -- are text this
  * class renders itself, and the template is their only caller, so each has
  * exactly one copy. There were five more, kept as a shorthand alongside the
  * loops; they diverged within a day and are gone. Anything new belongs in
  * the data half.
  *
  * Every String is empty rather than null when there is nothing to say, and
  * every List is empty rather than null: a template author should never have
  * to write `?: ""`. Runs of blank lines are collapsed after rendering, so
  * guarding a section with an `if` is optional.
  *
  * This class used to be the sharp end of rule 46. `agents/chat.md` was
  * compiled as Scala, so every property needed `@get:JvmName` to be reachable
  * at all, and every collection had to be a real
  * `scala.collection.immutable.List`, because a Kotlin `List` is a
  * `java.util.List` with no `foreach`, `nonEmpty` or `mkString`. Both problems
  * were solved by conversion layers that no longer exist: the template is
  * Kotlin now, and this is a plain Kotlin class again.
  *
  * @param retrievalQuery what [ragContext] retrieves against: the message
  *                       about to be sent, or on a tool round trip the last
  *                       one the user wrote. Fixed for the whole render, so
  *                       two calls cannot disagree.
  */
class PromptApi(private val runner: Runner, private val retrievalQuery: String?) {

    // ---- data: what the template loops over --------------------------------

    /** Memories small enough to include whole, in the order they are injected. */
    val memoryList: List<MemoryItem>
        get() = runner.memory.plan().injected.map { f ->
            MemoryItem(
                f.path.fileName.toString(),
                kotllm.memory.MemoryScope.name(f.scope),
                f.content
            )
        }

    /** Over the memory budget, so listed by name for the model to read on
      * demand rather than dropped silently. */
    val memoryIndex: List<NamedItem>
        get() = runner.memory.plan().indexed.map { f -> NamedItem(f.name, f.label) }

    /** Did not fit even as a line. Named, because a memory that is present but
      * ignored is worse than one visibly absent. */
    val memoryDropped: List<String>
        get() = runner.memory.plan().dropped.map { it.name }

    /** Skills that fit the listing budget. The cap lives in `Skills.listed`,
      * not here: it is a rule about the data, and putting it in the renderer is
      * how it got lost when the renderer changed. */
    val skillList: List<NamedItem>
        get() = runner.skills.listed().first.map { s -> NamedItem(s.meta.name, s.meta.description) }

    /** Names left out of [skillList] by the budget. */
    val skillsDropped: List<String> get() = runner.skills.listed().second

    /** How this model's own tool names map onto kotllm's, when they differ. */
    val skillToolNames: String
        get() = kotllm.skill.Skills.toolNameMapping(runner.activeTools.map { it.name }.toSet()) ?: ""

    val userToolList: List<NamedItem>
        get() =
            if (!runner.config.userToolsEnabled) emptyList()
            else runner.userTools.ready.map { t ->
                NamedItem(t.name, t.description.lineSequence().first().trim())
            }

    val taskList: List<TaskItem>
        get() = runner.checklist.all.map { t -> TaskItem(t.id, t.text, t.status.wire, t.status.marker) }

    val tasksOpen: Int
        get() = runner.checklist.all.count { t ->
            t.status == kotllm.task.TaskStatus.Pending || t.status == kotllm.task.TaskStatus.InProgress
        }

    /** Retrieval indexes that exist, and the one in use. */
    val indexes: List<String> get() = runner.rag?.names ?: emptyList()
    val activeIndex: String get() = runner.rag?.active?.name ?: ""
    val injectingContext: Boolean get() = runner.rag?.injectionEnabled ?: false

    /** Libraries on the interpreter's classpath, each with the one line saying
      * what it is for. */
    val libraries: List<Library>
        get() = kotllm.engine.Manifest.hints.map { (p, h) -> Library(p, h) }

    /** Every bundled artifact, `group:name:version`. The largest single thing in
      * the default prompt, and the first candidate when trimming it. */
    val artifacts: List<String> get() = kotllm.engine.Manifest.dependencies

    /** Jars appended to the classpath at runtime, beyond the bundled set. */
    val extraClasspath: List<String> get() = runner.extraClasspath

    val toolNames: List<String> get() = runner.activeTools.map { it.name }

    fun hasTool(name: String): Boolean = toolNames.contains(name)

    /** Read from the running Groovy rather than the build, because the version
      * the model is told about must be the one its snippets actually compile
      * against. */
    val groovyVersion: String get() = groovy.lang.GroovySystem.getVersion()

    /** Likewise the JVM: a prompt that says 17 on a 21 runtime is wrong about
      * the one thing it is describing. */
    val javaVersion: String get() = System.getProperty("java.specification.version") ?: "17"

    val model: String get() = runner.target.model.name
    val date: String get() = java.time.LocalDate.now().toString()
    val cwd: String get() = System.getProperty("user.dir") ?: "."

    // ---- sections that are still rendered in Kotlin ------------------------
    //
    // Only two left, and neither is duplicated: the template is their sole
    // caller, so there is one copy of the text. The other five -- tools,
    // skills, memories, user tools, tasks -- used to exist both here and as
    // loops in `chat.md`, and diverged within a day of being written: the
    // template was condensed and the `StringBuilder` behind `prompt.tools()`
    // was not, so a hand-written chat.md calling the aggregate silently served
    // the older, longer prose. They are gone; the template is the only place
    // the prompt's words live.

    fun rag(): String = runner.rag?.systemPromptSection ?: ""

    fun ragContext(): String =
        retrievalQuery?.let { q -> runner.rag?.injection(q) } ?: ""

    /** The user's own prompt, from `-s` or `/prompt`. */
    fun systemPrompt(): String =
        runner.session.systemPrompt?.takeIf { it.isNotBlank() } ?: ""
}
