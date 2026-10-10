package kotllm.background

import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import kotllm.util.TokenLedger

sealed interface TurnState {
    data object Running : TurnState
    data object Succeeded : TurnState
    data class Failed(val error: String) : TurnState
    data object Cancelled : TurnState

    companion object {
        fun name(s: TurnState): String = when (s) {
            Running -> "running"
            Succeeded -> "done"
            is Failed -> "failed"
            Cancelled -> "cancelled"
        }
    }
}

/**
 * A background LLM turn.
 *
 * In `kotllm.background` rather than `kotllm.task`, and named for what it is
 * rather than `Task`, because `kotllm.task` holds the checklist `/tasks`
 * shows. Both had a `Cancelled` status one package apart - a turn that was
 * killed versus a step that turned out unnecessary - which compiled perfectly
 * and read as one concept.
 */
data class BackgroundTurn(
    val id: Int,
    val prompt: String,
    val startedAt: Long,
    val state: TurnState,
    val finishedAt: Long?,
    val reply: String?,
    val promptTokens: Int,
    val completionTokens: Int
) {
    val elapsedMillis: Long get() = (finishedAt ?: System.currentTimeMillis()) - startedAt

    val isRunning: Boolean get() = state == TurnState.Running

    /**
     * One line for `/bg`, or for the notice printed when it finishes.
     */
    val summary: String get() {
        val label = prompt.replace("\n", " ").trim()
        val short = if (label.length > 48) label.take(45) + "..." else label
        val timing = TokenLedger.seconds(elapsedMillis)
        val tokens = if (completionTokens > 0) ", ${completionTokens} out" else ""
        return "[$id] ${TurnState.name(state)} in $timing$tokens: $short"
    }
}

/**
 * Registry of background turns.
 *
 * Two things make them safe rather than merely concurrent: output is captured
 * and never streamed, since a background reply interleaving with the line
 * editor would corrupt both; and completion is reported through a queue the
 * REPL drains between prompts, so a notice never lands mid-keystroke.
 *
 * Deliberately small: no attach, no tail, no per-turn kill. A half-built
 * job-control surface is worse than none.
 */
final class BackgroundRegistry {

    private val nextId = AtomicInteger(0)
    private val tasks = ConcurrentHashMap<Int, BackgroundTurn>()
    private val finished = ConcurrentLinkedQueue<BackgroundTurn>()
    private val cancelled = ConcurrentHashMap<Int, AtomicBoolean>()

    /**
     * Registers a task and returns its id and its own abort flag.
     */
    fun start(prompt: String): Pair<Int, AtomicBoolean> {
        val id = nextId.incrementAndGet()
        val flag = AtomicBoolean(false)
        tasks[id] = BackgroundTurn(id, prompt, System.currentTimeMillis(), TurnState.Running, null, null, 0, 0)
        cancelled[id] = flag
        return Pair(id, flag)
    }

    fun complete(id: Int, reply: String, promptTokens: Int, completionTokens: Int) {
        finish(id, TurnState.Succeeded, reply, promptTokens, completionTokens)
    }

    fun fail(id: Int, error: String) {
        finish(id, TurnState.Failed(error), null, 0, 0)
    }

    private fun finish(
        id: Int,
        state: TurnState,
        reply: String?,
        promptTokens: Int,
        completionTokens: Int
    ) {
        val task = tasks[id]
        if (task != null) {
            val done = task.copy(
                state = if (cancelled[id]?.get() == true) TurnState.Cancelled else state,
                finishedAt = System.currentTimeMillis(),
                reply = reply,
                promptTokens = promptTokens,
                completionTokens = completionTokens
            )
            tasks[id] = done
            finished.add(done)
        }
    }

    /**
     * Newly finished tasks, drained. Called by the REPL between prompts so a
     * notice cannot appear mid-keystroke.
     */
    fun drainFinished(): List<BackgroundTurn> {
        val out = mutableListOf<BackgroundTurn>()
        while (true) {
            val t = finished.poll() ?: break
            out.add(t)
        }
        return out
    }

    val all: List<BackgroundTurn> get() = tasks.values.sortedBy { it.id }

    val running: List<BackgroundTurn> get() = all.filter { it.isRunning }

    fun get(id: Int): BackgroundTurn? = tasks[id]

    val runningCount: Int get() = running.size

    fun cancelAll() {
        cancelled.values.forEach { it.set(true) }
    }

    fun cancel(id: Int): Boolean {
        val flag = cancelled[id]
        return if (flag != null && tasks[id]?.isRunning == true) {
            flag.set(true)
            true
        } else {
            false
        }
    }
}
