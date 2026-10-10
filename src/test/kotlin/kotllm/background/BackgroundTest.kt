package kotllm.background

import kotlin.test.Test
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.collections.shouldNotContain

import kotllm.Runner
import kotllm.TestIsolation
import kotllm.client.ChatFailure
import kotllm.client.ChatRequest
import kotllm.client.ChatResponse
import kotllm.client.Client
import kotllm.client.Message
import kotllm.client.StreamEvent
import kotllm.client.Usage
import kotllm.config.ClientConfig
import kotllm.config.Config
import kotllm.config.ModelConfig
import kotllm.config.ModelTarget
import kotllm.config.Session
import kotllm.util.Yaml

class BackgroundTest {

    // ---- registry ----------------------------------------------------------

    @Test fun `a started task is running and has an id`() {
        val r = BackgroundRegistry()
        val (id, _) = r.start("do the thing")

        id shouldBe 1
        r.get(id)!!.isRunning shouldBe true
        r.runningCount shouldBe 1
    }

    @Test fun `completing a task records the reply and its usage`() {
        val r = BackgroundRegistry()
        val (id, _) = r.start("q")
        r.complete(id, "the answer", 100, 20)

        val task = r.get(id)!!
        task.state shouldBe TurnState.Succeeded
        task.reply shouldBe "the answer"
        task.completionTokens shouldBe 20
        task.isRunning shouldBe false
        r.runningCount shouldBe 0
    }

    @Test fun `failing a task records the error`() {
        val r = BackgroundRegistry()
        val (id, _) = r.start("q")
        r.fail(id, "endpoint exploded")

        r.get(id)!!.state shouldBe TurnState.Failed("endpoint exploded")
    }

    @Test fun `finished tasks are drained exactly once`() {
        // Drained rather than polled, so a notice cannot print twice.
        val r = BackgroundRegistry()
        val (id, _) = r.start("q")
        r.complete(id, "done", 1, 1)

        r.drainFinished().map { it.id } shouldBe listOf(id)
        r.drainFinished() shouldBe emptyList()
    }

    @Test fun `draining preserves completion order`() {
        val r = BackgroundRegistry()
        val a = r.start("a").first
        val b = r.start("b").first
        r.complete(b, "b done", 1, 1)
        r.complete(a, "a done", 1, 1)

        r.drainFinished().map { it.id } shouldBe listOf(b, a)
    }

    @Test fun `a cancelled task reports as cancelled, not succeeded`() {
        val r = BackgroundRegistry()
        val (id, flag) = r.start("q")
        flag.set(true)
        r.complete(id, "partial", 1, 1)

        r.get(id)!!.state shouldBe TurnState.Cancelled
    }

    @Test fun `cancelAll signals every running task`() {
        val r = BackgroundRegistry()
        val (_, flagA) = r.start("a")
        val (_, flagB) = r.start("b")

        r.cancelAll()
        flagA.get() shouldBe true
        flagB.get() shouldBe true
    }

    @Test fun `cancelling a finished task reports that it did nothing`() {
        val r = BackgroundRegistry()
        val (id, _) = r.start("q")
        r.complete(id, "done", 1, 1)

        r.cancel(id) shouldBe false
    }

    @Test fun `completing an unknown id is ignored rather than throwing`() {
        BackgroundRegistry().complete(99, "x", 1, 1)
    }

    @Test fun `the summary is one line and abbreviates a long prompt`() {
        val r = BackgroundRegistry()
        val (id, _) = r.start("a very long prompt ".repeat(10))
        r.complete(id, "ok", 10, 5)

        val summary = r.get(id)!!.summary
        summary shouldNotContain "\n"
        summary shouldContain "[$id]"
        summary shouldContain "done"
        summary shouldContain "..."
    }

    // ---- spawning ------------------------------------------------------------

    private class StubClient(private val reply: String, private val fail: Boolean = false) : Client {
        override val id = "stub"
        private fun answer(): ChatResponse =
            if (fail) throw ChatFailure(500, "boom")
            else ChatResponse(Message.assistant(reply), "stop", Usage(10, 4, 14), "stub")

        override fun chat(req: ChatRequest): ChatResponse = answer()
        override fun chatStream(
            req: ChatRequest,
            onEvent: (StreamEvent) -> Unit,
            keepGoing: (() -> Boolean)?
        ): ChatResponse = answer()
    }

    private fun runner(client: Client): Runner {
        val config = Config.fromConf(Yaml.loadString("model: stub\nstream: true\n"))
        val cc = ClientConfig("openai-compatible", "c", "https://h/v1", "k", emptyList(), null, null)
        val model = ModelConfig("stub", 1000, null, supportsVision = false, supportsFunctionCalling = false)
        return Runner(TestIsolation.isolate(config), Session(), ModelTarget(cc, model), client)
    }

    private fun await(millis: Long = 5000, condition: () -> Boolean): Boolean {
        val deadline = System.currentTimeMillis() + millis
        while (!condition() && System.currentTimeMillis() < deadline) Thread.sleep(20)
        return condition()
    }

    @Test fun `a spawned turn completes and its reply is recorded`() {
        val registry = BackgroundRegistry()
        val parent = runner(StubClient("background answer"))
        val spawner = Spawner(registry) { parent.forkBackground() }

        val id = spawner.spawn("what is up")
        await { registry.get(id)?.isRunning == false } shouldBe true

        val task = registry.get(id)!!
        task.state shouldBe TurnState.Succeeded
        task.reply shouldBe "background answer"
    }

    @Test fun `a failing turn is recorded as failed, not left running for ever`() {
        val registry = BackgroundRegistry()
        val parent = runner(StubClient("", fail = true))
        val spawner = Spawner(registry) { parent.forkBackground() }

        val id = spawner.spawn("doomed")
        await { registry.get(id)?.isRunning == false } shouldBe true
        (registry.get(id)!!.state is TurnState.Failed) shouldBe true
    }

    @Test fun `a background turn does not touch the foreground conversation`() {
        // The fork carries a copy of the history; replies land in the fork.
        val registry = BackgroundRegistry()
        val parent = runner(StubClient("background answer"))
        parent.session.append(Message.user("foreground question"))
        val before = parent.session.messages().size

        val spawner = Spawner(registry) { parent.forkBackground() }
        val id = spawner.spawn("background question")
        await { registry.get(id)?.isRunning == false } shouldBe true

        parent.session.messages().size shouldBe before
        parent.session.messages().map { it.text } shouldNotContain "background answer"
    }

    @Test fun `the fork inherits the history, so it has context`() {
        val parent = runner(StubClient("x"))
        parent.session.append(Message.user("earlier turn"))

        val fork = parent.forkBackground()
        fork.session.messages().map { it.text } shouldContain "earlier turn"
    }

    @Test fun `the fork never streams, whatever the parent is set to`() {
        // Streaming output would interleave with the foreground and the line
        // editor.
        val parent = runner(StubClient("x"))
        parent.config.stream shouldBe true
        parent.forkBackground().config.stream shouldBe false
    }

    @Test fun `the fork shares the rate limit window with its parent`() {
        val config = Config.fromConf(
            Yaml.loadString("model: stub\nrate_limit:\n  input_tokens_per_minute: 1000\n")
        )
        val cc = ClientConfig("openai-compatible", "c", "https://h/v1", "k", emptyList(), null, null)
        val model = ModelConfig("stub", null, null, supportsVision = false, supportsFunctionCalling = false)
        val parent = Runner(TestIsolation.isolate(config), Session(), ModelTarget(cc, model), StubClient("x"))

        // Charging through the parent must be visible to the fork, or two
        // concurrent turns could together exceed the provider limit.
        val limiter = parent.limiter
        limiter shouldNotBe null
        limiter!!.settle(limiter.reserve(400), 400, 0)
        parent.forkBackground().limiter!!.snapshot().inputTokens shouldBe 400L
    }

    @Test fun `two spawned turns get separate sessions`() {
        val registry = BackgroundRegistry()
        val parent = runner(StubClient("answer"))
        val spawner = Spawner(registry) { parent.forkBackground() }

        val a = spawner.spawn("first")
        val b = spawner.spawn("second")
        await { listOf(a, b).all { registry.get(it)?.isRunning == false } } shouldBe true

        registry.get(a)!!.prompt shouldBe "first"
        registry.get(b)!!.prompt shouldBe "second"
    }
}
