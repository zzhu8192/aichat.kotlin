package kotllm.util

import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.Test
import kotlin.test.assertFailsWith
import io.kotest.matchers.shouldBe
import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.collections.shouldNotContain

/** Stopping work that is already running.
 *
 * The reason this exists rather than a `Thread.interrupt`: interrupting
 * reaches a sleep and nothing else. A child process keeps running, evaluated
 * code in a tight loop ignores it, an HTTP read finishes on its own schedule.
 */
class CancellationTest {

    @Test fun `a registered killer is called, and only while it is registered`() {
        val killed = AtomicInteger(0)
        val h = Cancellation.register("thing") { killed.incrementAndGet() }

        (Cancellation.cancelAll() >= 1) shouldBe true
        killed.get() shouldBe 1

        // A stale entry would let a later Ctrl-C kill a process that has since
        // been replaced, which is why every registration is closed in a finally.
        h.close()
        Cancellation.cancelAll()
        killed.get() shouldBe 1
    }

    @Test fun `around registers for the body and cleans up even when it throws`() {
        val killed = AtomicInteger(0)
        assertFailsWith<IllegalStateException> {
            Cancellation.around("boom", { killed.incrementAndGet() }) {
                Cancellation.labels() shouldContain "boom"
                throw IllegalStateException("x")
            }
        }
        Cancellation.labels() shouldNotContain "boom"
        Cancellation.cancelAll()
        killed.get() shouldBe 0
    }

    @Test fun `one uncooperative killer does not stop the others`() {
        // Last resort: a resource that refuses to die must not strand the rest.
        val killed = AtomicInteger(0)
        val a = Cancellation.register("bad") { throw RuntimeException("nope") }
        val b = Cancellation.register("good") { killed.incrementAndGet() }
        try {
            Cancellation.cancelAll()
            killed.get() shouldBe 1
        } finally {
            a.close(); b.close()
        }
    }

    @Test fun `labels name what is running, for the message that asks to press again`() {
        val h = Cancellation.register("exec: npm install") {}
        try {
            Cancellation.labels() shouldContain "exec: npm install"
        } finally {
            h.close()
        }
    }
}
