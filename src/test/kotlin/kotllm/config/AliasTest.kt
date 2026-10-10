package kotllm.config

import kotlin.test.Test
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import kotllm.*
import kotllm.util.Yaml

/** Short names for models.
 *
 * A lookup table and nothing more -- no key is special, and the names below
 * are chosen to look like abbreviations of the models they point at rather
 * than categories, because kotllm has no notion of a model category and
 * should not appear to. Everything here hangs off a single insertion point
 * in `resolveModel`, which is what makes `-m`, `/model`, the top-level
 * `model:` key and `llm(alias = ...)` all understand an alias without four
 * separate changes.
 */
class AliasTest {

    private val twoTiers =
        """model: qwen
          |aliases:
          |  qwen: dbx:big-model
          |  gemma: local:gemma4:26b
          |  main: qwen
          |clients:
          |- type: openai-compatible
          |  name: dbx
          |  api_base: https://h/v1
          |  api_key: k
          |  models:
          |  - name: big-model
          |    max_input_tokens: 261000
          |- type: ollama
          |  name: local
          |  api_base: http://127.0.0.1:11434
          |""".trimMargin()

    private fun config(yaml: String = twoTiers) = Config.fromConf(Yaml.loadString(yaml))

    @Test fun `an alias resolves to the model it names`() {
        config().resolveModel("gemma").map { it.id() }.orNull() shouldBe "local:gemma4:26b"
        config().resolveModel("qwen").map { it.id() }.orNull() shouldBe "dbx:big-model"
    }

    @Test fun `the default model may itself be an alias`() {
        // model: qwen is half the point of the feature -- switching the
        // default should be one word, not an endpoint id copied around.
        config().defaultTarget.map { it.id() }.orNull() shouldBe "dbx:big-model"
    }

    @Test fun `an alias may point at another alias`() {
        // One hop would resolve main to qwen and then fail with "unknown
        // model 'qwen'", which reads like the alias map was ignored.
        config().resolveModel("main").map { it.id() }.orNull() shouldBe "dbx:big-model"
    }

    @Test fun `a cycle is an error, not a hang`() {
        val looped = config(
            """aliases:
              |  a: b
              |  b: a
              |clients:
              |- type: openai-compatible
              |  name: c
              |  api_base: https://h/v1
              |  models:
              |  - name: m
              |""".trimMargin()
        )
        val err = looped.resolveModel("a").fold({ it }, { "" })
        err shouldContain "cycle"
        err shouldContain "a -> b -> a"
    }

    @Test fun `a name that is not an alias is resolved exactly as before`() {
        // The whole existing resolution path has to be untouched: alias expansion
        // is a prefix step, not a replacement.
        config().resolveModel("dbx:big-model").map { it.id() }.orNull() shouldBe "dbx:big-model"
        config().resolveModel("big-model").map { it.id() }.orNull() shouldBe "dbx:big-model"
        // Including the Ollama passthrough, which is the case an alias: key on a
        // model entry could not have covered -- the model is never declared.
        config().resolveModel("qwen3:27b").map { it.model.name }.orNull() shouldBe "qwen3:27b"
        // Same rule for a bare undeclared name: it goes to the one client whose
        // model set can change under the config. Aliases must not disturb that.
        config().resolveModel("nope").map { it.client.id() }.orNull() shouldBe "local"
    }

    @Test fun `no aliases means nothing changes`() {
        val plain = config(
            """model: m
              |clients:
              |- type: openai-compatible
              |  name: c
              |  api_base: https://h/v1
              |  models:
              |  - name: m
              |""".trimMargin()
        )
        plain.aliases.isEmpty() shouldBe true
        plain.aliasProblems.isEmpty() shouldBe true
        plain.defaultTarget.map { it.id() }.orNull() shouldBe "c:m"
    }

    // ---- what gets reported --------------------------------------------------

    @Test fun `an alias pointing at nothing is reported, since it is typed rarely`() {
        val broken = config(
            """aliases:
              |  gemma: no-such-model
              |clients:
              |- type: openai-compatible
              |  name: c
              |  api_base: https://h/v1
              |  models:
              |  - name: m
              |""".trimMargin()
        )
        val problems = broken.aliasProblems.joinToString(" ")
        problems shouldContain "gemma"
        problems shouldContain "no-such-model"
        // And it is a report, not a load failure: the rest of the config works.
        broken.defaultTarget.map { it.id() }.orNull() shouldBe "c:m"
    }

    @Test fun `a name a config could never type is reported rather than silently dead`() {
        val bad = config(
            """aliases:
              |  "we ak": c:m
              |  "a:b": c:m
              |clients:
              |- type: openai-compatible
              |  name: c
              |  api_base: https://h/v1
              |  models:
              |  - name: m
              |""".trimMargin()
        )
        val problems = bad.aliasProblems.joinToString(" ")
        problems shouldContain "whitespace"
        problems shouldContain "colon"
    }

    @Test fun `an alias shadowing a real name wins, and says so`() {
        // It is checked first, so it has to win; the two can then drift apart and
        // the config means something other than it reads like.
        val shadow = config(
            """aliases:
              |  m: c:other
              |clients:
              |- type: openai-compatible
              |  name: c
              |  api_base: https://h/v1
              |  models:
              |  - name: m
              |  - name: other
              |""".trimMargin()
        )
        shadow.resolveModel("m").map { it.model.name }.orNull() shouldBe "other"
        shadow.aliasProblems.joinToString(" ") shouldContain "alias wins"
    }

    @Test fun `a non-scalar alias target is dropped rather than stringified`() {
        // gemma: with a nested block underneath would otherwise become a model
        // id spelled "Map(...)", which nothing could debug.
        val odd = config(
            """aliases:
              |  gemma:
              |    name: x
              |clients:
              |- type: openai-compatible
              |  name: c
              |  api_base: https://h/v1
              |  models:
              |  - name: m
              |""".trimMargin()
        )
        odd.aliases.isEmpty() shouldBe true
    }

    // ---- end to end, through Runner and the REPL -----------------------------

    private fun runner(yaml: String): kotllm.Runner {
        val config = kotllm.TestIsolation.isolate(config(yaml))
        val target = config.defaultTarget.getOrElse { e -> throw AssertionError(e) }
        val client = object : kotllm.client.Client {
            override val id = "stub"
            private fun reply() = kotllm.client.ChatResponse(
                kotllm.client.Message.assistant("ok"), "stop", kotllm.client.Usage(1, 1, 2), "stub"
            )
            override fun chat(req: kotllm.client.ChatRequest) = reply()
            override fun chatStream(
                req: kotllm.client.ChatRequest,
                onEvent: (kotllm.client.StreamEvent) -> Unit,
                keepGoing: (() -> Boolean)?
            ) = reply()
        }
        return kotllm.Runner(config, Session(), target, client, quiet = true)
    }

    @Test fun `a broken alias is reported at startup, not at the call that needs it`() {
        // The failure this catches is a typo answering from the wrong tier and
        // looking completely normal. `aliasProblems` is data; this is the guard
        // that something actually surfaces it.
        val hints = runner(
            """model: m
              |aliases:
              |  gemma: no-such-model
              |clients:
              |- type: openai-compatible
              |  name: c
              |  api_base: https://h/v1
              |  api_key: k
              |  models:
              |  - name: m
              |""".trimMargin()
        ).startupHints.joinToString(" ")

        hints shouldContain "model alias"
        hints shouldContain "gemma"
    }

    @Test fun `info lists every alias and flags the broken ones`() {
        // `--info` is documented as the command that checks your setup, and the
        // startup hint only fires in the REPL -- a single-hit user would never
        // see it otherwise.
        val text = kotllm.repl.Commands.infoText(
            runner(
                """model: m
                  |aliases:
                  |  big: c:m
                  |  gemma: no-such-model
                  |clients:
                  |- type: openai-compatible
                  |  name: c
                  |  api_base: https://h/v1
                  |  api_key: k
                  |  models:
                  |  - name: m
                  |""".trimMargin()
            )
        )

        text shouldContain "aliases"
        text shouldContain "big"
        text shouldContain "c:m"
        text shouldContain "gemma"
    }

    @Test fun `info says nothing about aliases when there are none`() {
        val text = kotllm.repl.Commands.infoText(
            runner(
                """model: m
                  |clients:
                  |- type: openai-compatible
                  |  name: c
                  |  api_base: https://h/v1
                  |  api_key: k
                  |  models:
                  |  - name: m
                  |""".trimMargin()
            )
        )

        (text.contains("aliases")) shouldBe false
    }
}
