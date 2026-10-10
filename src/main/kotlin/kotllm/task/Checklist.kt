package kotllm.task

import kotllm.*

/** What one task is doing right now. */
sealed class TaskStatus(val wire: String, val marker: String) {

    /**
     * Markers are ASCII on purpose, and this is not an oversight of the rule
     * that glyphs go through `util/Glyphs`. That rule exists so a
     * block-drawing character cannot arrive as `?` on a cp437 console; `[x]`
     * has nothing to fall back from. A prettier `✓` would need adding to the
     * encodability probe to buy nothing a checklist needs.
     */
    // Nested in the class, not in the companion: a `data object` inside
    // `companion object` is `TaskStatus.Companion.Done`, which is not what any
    // call site writes.
    data object Pending : TaskStatus("pending", "[ ]")
    data object InProgress : TaskStatus("in_progress", "[>]")
    data object Done : TaskStatus("done", "[x]")
    data object Cancelled : TaskStatus("cancelled", "[-]")

    companion object {
        // `by lazy`, not a plain val. The subtypes are nested in this class,
        // so initialising `TaskStatus` runs the companion, which reads
        // `Pending` -- whose own initialiser needs `TaskStatus`, already in
        // progress. The JVM does not deadlock, it hands back null, and the
        // first `all.joinToString { it.wire }` is an NPE. Scala had no cycle
        // because `object TaskStatus` initialised separately from the trait.
        val all: List<TaskStatus> by lazy { listOf(Pending, InProgress, Done, Cancelled) }

        fun parse(raw: String): Outcome<TaskStatus> {
            val wanted = raw.trim().lowercase().replace('-', '_').replace(" ", "_")
            val found = all.firstOrNull { it.wire == wanted }
            return if (found != null) ok(found)
            else err("unknown status '$raw'; use one of: ${all.joinToString(", ") { it.wire }}")
        }
    }
}

/** One line of the checklist. */
data class ChecklistItem(val id: Int, val text: String, val status: TaskStatus) {
    val render: String get() = "$id. ${status.marker} $text"
}

/**
 * A visible checklist for multi-step work.
 *
 * Ids are assigned here and echoed back on every call, which is the reason
 * the tool surface is shaped as it is: a model that has to invent a key will
 * eventually invent one that does not exist, and the update then silently
 * addresses nothing. Small integers also cost one token to emit, which matters
 * for something updated several times a turn.
 *
 * Session-scoped and in memory. Nothing is persisted: a checklist describes
 * what is being done now, and one restored from disk into a conversation that
 * no longer remembers it would be worse than absent.
 *
 * (Background turns moved to `kotllm.background` to make room: this package
 * had a `TaskState` and a `TaskStatus` that each carried a `Cancelled` and
 * compiled without complaint.)
 */
final class Checklist {

    private val tasks = mutableListOf<ChecklistItem>()

    /**
     * A list injected into every request has to be bounded. Forty is far more
     * than a plan anyone can follow and still cheap; the refusal says so
     * rather than silently truncating, because a plan missing its last steps
     * is a plan that quietly will not be finished.
     */
    val MaxTasks = 40
    val MaxTextChars = 200

    val all: List<ChecklistItem> get() = synchronized(this) { tasks.toList() }

    val isEmpty: Boolean get() = synchronized(this) { tasks.isEmpty() }

    fun clear() {
        synchronized(this) {
            tasks.clear()
        }
    }

    val counts: Pair<Int, Int> get() = synchronized(this) {
        val done = tasks.count { t -> t.status == TaskStatus.Done || t.status == TaskStatus.Cancelled }
        Pair(done, tasks.size)
    }

    /**
     * Replaces the whole list.
     *
     * Replace rather than append, because "create" is what the model calls when
     * it has a new plan - but any progress thrown away is named in the
     * result, so a second call made by mistake cannot silently erase a
     * half-finished list without the model reading that it did.
     */
    fun create(texts: List<String>): Outcome<String> = synchronized(this) {
        val cleaned = texts.map { it.trim().replace(Regex("\\s+"), " ") }.filter { it.isNotEmpty() }

        when {
            cleaned.isEmpty() -> return err("a checklist needs at least one task")
            cleaned.size > MaxTasks -> return err(
                "${cleaned.size} tasks is over the $MaxTasks-task limit; this list rides on every " +
                    "request, so keep it to the steps that need tracking"
            )
        }

        val longTask = cleaned.firstOrNull { it.length > MaxTextChars }
        if (longTask != null) {
            return err(
                "a task is one line, not a paragraph: '${longTask.take(60)}...' is " +
                    "${longTask.length} chars, over $MaxTextChars"
            )
        }

        val discarded = tasks.count { t -> t.status == TaskStatus.InProgress || t.status == TaskStatus.Done }
        tasks.clear()
        cleaned.forEachIndexed { i, text ->
            tasks.add(ChecklistItem(i + 1, text, TaskStatus.Pending))
        }
        val note = if (discarded > 0)
            " (replaced a previous list, discarding $discarded started or finished task(s))"
        else ""
        return ok("${tasks.size} task(s)$note:\n$render")
    }

    /**
     * Applies a batch of status changes.
     *
     * All or nothing. A partially applied batch leaves the model unsure
     * which half landed, and the next thing it does is guess. Validating every
     * id before touching anything costs one extra pass and removes the
     * question.
     */
    fun update(updates: List<Pair<Int, TaskStatus>>): Outcome<String> = synchronized(this) {
        if (updates.isEmpty()) return err("no updates given; pass at least one {id, status}")

        val known = tasks.map { it.id }.toSet()
        val unknown = updates.map { it.first }.distinct().filter { !known.contains(it) }
        if (unknown.isNotEmpty()) {
            return err(
                "no task with id ${unknown.joinToString(", ")}. " +
                    (if (tasks.isEmpty())
                        "There is no checklist yet -- call create_tasks first."
                    else
                        "Current ids: ${tasks.map { it.id }.joinToString(", ")}.")
            )
        }

        val wanted = updates.toMap()
        tasks.indices.forEach { i ->
            wanted[tasks[i].id]?.let { s ->
                tasks[i] = tasks[i].copy(status = s)
            }
        }

        // Reported, never corrected. Demoting whatever was in progress would be a
        // silent edit of the model's own plan, and two genuinely concurrent steps
        // are possible; the discipline is worth a nudge and not worth a lie.
        val running = tasks.filter { it.status == TaskStatus.InProgress }
        val warning = if (running.size > 1)
            "\n\nNote: ${running.size} tasks are in_progress (${running.map { it.id }.joinToString(", ")}). " +
                "Prefer exactly one at a time."
        else ""

        val done = tasks.count { it.status == TaskStatus.Done }
        val total = tasks.size
        return ok("$done/$total done:\n$render$warning")
    }

    val render: String get() = synchronized(this) {
        tasks.map { t -> "  ${t.render}" }.joinToString("\n")
    }
}
