package kotllm.util

import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong

/**
 * A way to stop work that is already running.
 *
 * **Interrupting a thread is not enough, which is the whole reason this
 * exists.** `Thread.interrupt` reaches a `sleep` and nothing else: a child
 * process keeps running, a tight loop in evaluated code ignores it entirely,
 * an HTTP read finishes on its own schedule. So anything owning a killable
 * resource registers how to kill it.
 *
 * Deliberately a flat global registry rather than a hook on `Tool`, which every
 * tool would carry and almost none would use, for a signal that is
 * process-wide by nature. The cost is that a foreground Ctrl-C can also cancel
 * a background turn's subprocess -- rare, and the honest reading of "stop what
 * you are doing" anyway.
 */
object Cancellation {

    /**
     * Undoes a registration. Always call it, from a `finally`: a stale entry
     * would let a later Ctrl-C kill a process that has already been replaced.
     */
    class Handle(private val id: Long) : AutoCloseable {
        override fun close() {
            registry.remove(id)
        }
    }

    private val ids = AtomicLong(0L)
    private val registry = ConcurrentHashMap<Long, Pair<String, () -> Unit>>()

    /**
     * Registers a way to stop something.
     *
     * @param label what it is, shown when kotllm says what it is waiting for
     * @param kill  must be safe to call more than once and from another thread
     */
    fun register(label: String, kill: () -> Unit): Handle {
        val id = ids.incrementAndGet()
        registry[id] = Pair(label, kill)
        return Handle(id)
    }

    fun <A> around(label: String, kill: () -> Unit, body: () -> A): A {
        val h = register(label, kill)
        return try {
            body()
        } finally {
            h.close()
        }
    }

    /**
     * What is currently killable, for the message that names what is being
     * waited on. A count rather than only the labels, because two `exec`s look
     * identical in a list and the number is the surprising part.
     */
    fun labels(): List<String> = registry.values.map { it.first }.toList()

    /**
     * Runs every registered killer. Failures are swallowed: this is the last
     * resort, and one uncooperative resource must not stop the others being
     * released. Returns how many were called.
     */
    fun cancelAll(): Int {
        val entries = registry.values.toList()
        entries.forEach { (_, kill) ->
            try {
                kill()
            } catch (e: Exception) {
                // Swallow exceptions
            }
        }
        return entries.size
    }
}
