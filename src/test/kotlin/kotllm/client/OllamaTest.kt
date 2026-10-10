// DEFERRED: this suite's last test ("/status says local and free; the footer
// spends no slot on a zero") needs `kotllm.repl.Status.render`, which does not
// exist yet in src/main/kotlin/kotllm/repl -- only Chrome, CommandReports,
// Prompt and ReaderHolder are ported there so far. Everything else this file
// needs (`kotllm.Runner`, `kotllm.config.Session`, `kotllm.repl.Prompt`,
// `kotllm.TestIsolation`) already exists and this file compiles against all
// of it; only `Status` is missing. Since one unresolved reference anywhere in
// a Kotlin file blocks the whole file from compiling, the whole suite is
// deferred rather than only that one test. Rename to `OllamaTest.kt` once
// `kotllm.repl.Status` is ported.
package kotllm.client

import kotlin.test.Test
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain

import kotllm.*
import kotllm.config.ClientConfig
import kotllm.config.Config
import kotllm.config.ModelConfig
import kotllm.config.ModelTarget
import kotllm.util.TurnMetrics
import kotllm.util.Yaml

/** Ollama, reached over its OpenAI-compatible `/v1` surface.
  *
  * There is no Ollama transport and there should not be: `/api/chat` would buy
  * `num_ctx`, `keep_alive` and a `thinking` field against a second codec and
  * newline-delimited-JSON framing, while `/v1/models` already answers
  * discovery, `reasoning_content` already carries the thinking, and the context
  * window is a server-side memory decision a chat client has no business
  * changing per request. So what is covered here is the four papercuts that
  * made the generic path unusable, plus the two improvements that are generic
  * on purpose.
  */
class OllamaTest {

  private fun client(yaml: String): ClientConfig =
    ClientConfig.from(Yaml.loadString(yaml)).getOrElse { throw AssertionError("client config did not parse") }

  /** A config with one Ollama client, which is how model resolution is reached. */
  private val ollamaConfig: Config = Config.fromConf(Yaml.loadString(
    """clients:
      |- type: ollama
      |  name: local
      |  api_base: http://localhost:11434
      |""".trimMargin()
  ))

  private val pricing = kotllm.util.Pricing.load(null, null)

  private fun turn(model: String, `in`: Int, out: Int, local: Boolean = false) =
    TurnMetrics(0, 0L, model, `in`, out, 0, 1000L, 100L, emptyList(),
                truncated = false, aborted = false, local = local)

  // ---- recognising it ----------------------------------------------------

  @Test fun `type ollama routes to the generic OpenAI client, not a new transport`() {
    Provider.infer("ollama", "http://localhost:11434/v1") shouldBe Provider.Ollama

    val target = ModelTarget(ollamaConfig.clients.first(), ModelConfig.synthetic("qwen3:27b"))
    when (val c = Client.forTarget(target, ollamaConfig)) {
      is Outcome.Ok -> (c.value is DatabricksClient) shouldBe true
      is Outcome.Err -> throw AssertionError("expected a client, got ${c.message}")
    }
  }

  @Test fun `port 11434 identifies it even with no type given`() {
    Provider.infer("openai-compatible", "http://gpubox.lan:11434/v1") shouldBe Provider.Ollama
    // An unrelated OpenAI-compatible server is still generic.
    Provider.infer("openai-compatible", "https://api.example.com/v1") shouldBe
      Provider.GenericOpenAI
  }

  // ---- the /v1 suffix ----------------------------------------------------

  @Test fun `a bare host gets v1, because that is what Ollama's own docs show`() {
    // Without this the POST goes to `/chat/completions` at the root and 404s
    // with nothing on screen explaining why.
    client("type: ollama\napi_base: http://localhost:11434\n").apiBase shouldBe
      "http://localhost:11434/v1"
    client("type: ollama\napi_base: http://localhost:11434/\n").apiBase shouldBe
      "http://localhost:11434/v1"
  }

  @Test fun `an explicit version segment is left exactly as written`() {
    client("type: ollama\napi_base: http://localhost:11434/v1\n").apiBase shouldBe
      "http://localhost:11434/v1"
    ClientConfig.normaliseOllamaBase("http://host/v1beta") shouldBe "http://host/v1beta"
  }

  @Test fun `api_base is optional for ollama, since the port was never the user's choice`() {
    client("type: ollama\nname: local\n").apiBase shouldBe ClientConfig.OllamaDefaultBase
    // Still required for anything else: there is no default worth guessing.
    ClientConfig.from(Yaml.loadString("type: openai-compatible\nname: x\n")).isOk shouldBe false
  }

  @Test fun `no api_key is needed, and none is sent`() {
    client("type: ollama\napi_base: http://localhost:11434\n").apiKey shouldBe null
  }

  // ---- a colon is part of an Ollama model name ---------------------------

  @Test fun `qwen3 27b resolves as a model name, not as a client called qwen3`() {
    // The bug: `client:model` split on the first colon, and an Ollama tag *is*
    // name:tag, so the most ordinary thing a local user can type produced
    // "unknown client 'qwen3'".
    ollamaConfig.resolveModel("qwen3:27b").map { it.model.name } shouldBe ok("qwen3:27b")
  }

  @Test fun `the qualified form still works when the prefix really names a client`() {
    when (val t = ollamaConfig.resolveModel("local:qwen3:27b")) {
      is Outcome.Ok -> {
        t.value.client.id() shouldBe "local"
        // Only the first colon separates, so the tag survives intact.
        t.value.model.name shouldBe "qwen3:27b"
      }
      is Outcome.Err -> throw AssertionError(t.message)
    }
  }

  @Test fun `an undeclared model on a single client is passed through, not refused`() {
    // `ollama pull` should not require a config edit, and the server is the
    // authority on which names are valid.
    ollamaConfig.resolveModel("llama3.3:70b").map { it.model.name } shouldBe
      ok("llama3.3:70b")
  }

  @Test fun `an undeclared name goes to the one client whose model set can change`() {
    // Mixed config: the hosted client's model list is fixed, so a name absent
    // from it is a typo; the Ollama one can have had anything pulled into it
    // since the config was written. That makes the choice unambiguous rather
    // than a guess -- which is why this is not simply "two clients, error".
    val two = Config.fromConf(Yaml.loadString(
      """clients:
        |- type: openai-compatible
        |  name: hosted
        |  api_base: https://api.example.com/v1
        |  models:
        |  - name: gpt-4o
        |- type: ollama
        |  name: local
        |  api_base: http://localhost:11434
        |""".trimMargin()
    ))
    when (val t = two.resolveModel("mystery-model")) {
      is Outcome.Ok -> t.value.client.id() shouldBe "local"
      is Outcome.Err -> throw AssertionError(t.message)
    }
    // A declared hosted model still resolves to its own client.
    two.resolveModel("gpt-4o").map { it.client.id() } shouldBe ok("hosted")
  }

  @Test fun `with no ollama client an undeclared name is still an error`() {
    // The useful local error is preserved for a configured gateway, where the
    // model list is fixed and a name outside it is a typo. Shipping it to the
    // endpoint for an opaque 404 would be worse, and two older tests said so.
    val hosted = Config.fromConf(Yaml.loadString(
      """clients:
        |- type: openai-compatible
        |  name: hosted
        |  api_base: https://api.example.com/v1
        |  models:
        |  - name: gpt-4o
        |""".trimMargin()
    ))
    hosted.resolveModel("mystery-model").isOk shouldBe false
    val swapped = hosted.resolveModel("nope:model").fold(onErr = { it }, onOk = { "" })
    swapped shouldContain "unknown client"
  }

  // ---- /v1/models, generic on purpose ------------------------------------

  @Test fun `the OpenAI list shape parses`() {
    val body =
      """{"object":"list","data":[
        |  {"id":"qwen3:27b","object":"model"},
        |  {"id":"llama3.3:70b","object":"model"}]}""".trimMargin()
    Models.parse(body) shouldBe ok(listOf("qwen3:27b", "llama3.3:70b"))
  }

  @Test fun `a bare array parses too, because local servers have shipped that`() {
    Models.parse("""[{"id":"a"},{"id":"b"}]""") shouldBe ok(listOf("a", "b"))
  }

  @Test fun `an empty list is a reported reason, never an empty success`() {
    // "You have no models" and "I could not read the answer" are different
    // facts, and an empty List would say the first for both.
    Models.parse("""{"object":"list","data":[]}""").isOk shouldBe false
    Models.parse("not json at all").isOk shouldBe false
  }

  @Test fun `an unreachable server is an Err naming the base, never an exception`() {
    // A local server being down is routine, and the fix differs from "broken",
    // so the two must not read the same.
    val cfg = client("type: ollama\napi_base: http://127.0.0.1:1/v1\n")
    when (val result = Models.list(cfg, timeoutSeconds = 2)) {
      is Outcome.Err -> result.message shouldContain "127.0.0.1:1"
      is Outcome.Ok -> throw AssertionError("expected a failure, got ${result.value}")
    }
  }

  // ---- reasoning, under all three spellings ------------------------------

  private fun streamedReasoning(delta: String): String =
    Chunk
      .decode(kotlinx.serialization.json.Json.parseToJsonElement("""{"choices":[{"index":0,"delta":$delta}]}"""))
      .filterIsInstance<StreamEvent.ReasoningDelta>()
      .joinToString("") { it.text }

  @Test fun `thinking and reasoning reach the reasoning channel, like reasoning_content`() {
    // Ollama calls it `thinking`. Missing the alias renders a model's
    // chain-of-thought as its answer, which is the failure this prevents.
    streamedReasoning("""{"reasoning_content":"a"}""") shouldBe "a"
    streamedReasoning("""{"thinking":"b"}""") shouldBe "b"
    streamedReasoning("""{"reasoning":"c"}""") shouldBe "c"
  }

  @Test fun `ordinary content is still content`() {
    streamedReasoning("""{"content":"hello"}""") shouldBe ""
    Chunk
      .decode(kotlinx.serialization.json.Json.parseToJsonElement("""{"choices":[{"index":0,"delta":{"content":"hello"}}]}"""))
      .filterIsInstance<StreamEvent.TextDelta>()
      .joinToString("") { it.text } shouldBe "hello"
  }

  @Test fun `the blocking path reads the same three names`() {
    fun reasoningOf(msg: String): String =
      Message.decode(kotlinx.serialization.json.Json.parseToJsonElement(msg)).reasoning

    reasoningOf("""{"role":"assistant","content":"hi","reasoning_content":"p"}""") shouldBe "p"
    reasoningOf("""{"role":"assistant","content":"hi","thinking":"p"}""") shouldBe "p"
    reasoningOf("""{"role":"assistant","content":"hi","reasoning":"p"}""") shouldBe "p"
    // And the visible answer is untouched by the alias.
    Message.decode(kotlinx.serialization.json.Json.parseToJsonElement(
      """{"role":"assistant","content":"hi","thinking":"x"}"""
    )).text shouldBe "hi"
  }

  // ---- cost: local is free, which is not the same as unknown -------------

  @Test fun `a local turn is free and reported as local, never as unpriced`() {
    // `unpriced` means "I could not find a price", a warning that the total is
    // understated by an unknown amount. Local means "this cost nothing", which
    // is a fact and needs no remedy.
    val b = pricing.breakdown(listOf(turn("qwen3:27b", 1000, 200, local = true)))

    b.totalUsd shouldBe 0.0
    b.anyUnpriced shouldBe false
    b.anyLocal shouldBe true
    b.localTokens shouldBe 1200L
  }

  @Test fun `a local model whose name matches the price table is still free`() {
    // A local model can be called anything, and a lookup hit would invent a
    // bill for the user's own GPU.
    val b = pricing.breakdown(listOf(turn("gpt-4o", 1000, 1000, local = true)))
    b.totalUsd shouldBe 0.0
    b.anyLocal shouldBe true
    b.anyUnpriced shouldBe false
  }

  @Test fun `a hosted turn in the same session is still costed`() {
    // Recorded per turn precisely so /model switching mid-session cannot
    // mis-cost the whole session in either direction.
    val b = pricing.breakdown(
      listOf(turn("qwen3:27b", 1000, 200, local = true), turn("gpt-4o", 1000000, 0))
    )
    b.anyLocal shouldBe true
    b.inputTokens shouldBe 1000000L
    (b.totalUsd > 0.0) shouldBe true
  }

  @Test fun `status says local and free, the footer spends no slot on a zero`() {
    // Verifying the rendered text, not just the breakdown: the whole point of
    // the distinction is what the user reads, and `$0.0000` in the footer would
    // waste the one slot the cost element gets.
    val cfg = kotllm.TestIsolation.isolate(
      Config.fromConf(Yaml.loadString("""model: qwen3:27b"""))
    )
    val runner = kotllm.Runner(
      cfg,
      kotllm.config.Session(),
      ModelTarget(ollamaConfig.clients.first(), ModelConfig.synthetic("qwen3:27b")),
      StubClient
    )
    runner.session.ledger.record(turn("qwen3:27b", 1200, 40, local = true))

    val status = kotllm.repl.Status.render(runner)
    status shouldContain "local, free"
    status shouldNotContain "unpriced"

    val footer = kotllm.repl.Prompt.footer(runner)
    footer shouldNotContain "unpriced"
    footer shouldNotContain "\$0.0000"
  }

  private object StubClient : Client {
    override val id = "stub"
    override fun chat(req: ChatRequest): ChatResponse = throw UnsupportedOperationException()
    override fun chatStream(
      req: ChatRequest,
      onEvent: (StreamEvent) -> Unit,
      keepGoing: (() -> Boolean)?
    ): ChatResponse = throw UnsupportedOperationException()
  }

  @Test fun `an unknown hosted model is still unpriced, not quietly called local`() {
    val b = pricing.breakdown(listOf(turn("some-model-nobody-priced", 100, 100)))
    b.anyUnpriced shouldBe true
    b.anyLocal shouldBe false
  }
}
