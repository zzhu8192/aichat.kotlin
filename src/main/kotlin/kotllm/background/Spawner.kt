package kotllm.background

import kotllm.Runner
import kotllm.Outcome

/**
 * Launches background turns and keeps the registry current.
 *
 * Separate from [BackgroundRegistry] so the registry stays a pure bookkeeping
 * type that tests can drive without threads or a live endpoint.
 */
final class Spawner(private val registry: BackgroundRegistry, private val fork: () -> Runner) {

    /**
     * Starts a turn on a daemon thread.
     *
     * Daemon so a forgotten background turn can never keep the JVM alive after
     * the user exits -- the alternative is a REPL that appears to hang on quit.
     *
     * @return the task id.
     */
    fun spawn(prompt: String): Int {
        val (id, abort) = registry.start(prompt)

        val thread = Thread(
            {
                // A fresh fork per task: two background turns must not share a
                // session, or their histories interleave into nonsense.
                val runner = fork()
                try {
                    when (val result = runner.ask(prompt, { abort.get() })) {
                        is Outcome.Ok -> {
                            val response = result.value
                            registry.complete(
                                id,
                                response.text,
                                response.usage?.promptTokens ?: 0,
                                response.usage?.completionTokens ?: 0
                            )
                        }
                        is Outcome.Err -> registry.fail(id, result.message)
                    }
                } catch (e: Throwable) {
                    // Nothing above catches a Throwable from the tool loop, and a
                    // background thread dying silently would leave the task Running
                    // for ever.
                    registry.fail(id, "${e::class.simpleName}: ${e.message ?: ""}")
                } finally {
                    try {
                        runner.close()
                    } catch (e: Throwable) {
                        // Ignore
                    }
                }
            },
            "kotllm-bg-$id"
        )
        thread.isDaemon = true
        thread.start()
        return id
    }
}
