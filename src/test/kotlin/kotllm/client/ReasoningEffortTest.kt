// DEFERRED: the test "think is a real command and reasoning_effort is
// settable" needs `kotllm.repl.Commands.all` and
// `kotllm.repl.Commands.settableKeys`. Only `kotllm.repl.CommandReports` (an
// interface `Commands` is meant to mix in, per its own doc comment) exists in
// src/main/kotlin/kotllm/repl so far -- there is no `object Commands`
// anywhere in the Kotlin main tree yet. Every other dependency this file
// needs (`kotllm.config.Config`, `ReasoningDisplay`, `ReasoningEffort`,
// `kotllm.util.Yaml`) is already ported and matches the Scala semantics
// exactly. Since one unresolved reference blocks the whole file from
// compiling, the whole suite is deferred. Rename to `ReasoningEffortTest.kt`
// once `kotllm.repl.Commands` exists.
package kotllm.client

import kotlin.test.Test
import io.kotest.matchers.shouldBe
import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.collections.shouldNotContain
import kotlinx.serialization.json.*

import kotllm.config.Config
import kotllm.config.ReasoningDisplay
import kotllm.config.ReasoningEffort
import kotllm.util.Yaml

/** `reasoning_effort`: asking the model to think less, as opposed to hiding
  * the fact that it did.
  *
  * The pair of settings is easy to confuse and expensive to confuse, so most
  * of what is pinned here is that they stay independent. `reasoning` is a
  * renderer setting and changes nothing on the wire; `reasoning_effort` is a
  * request parameter and changes nothing on screen.
  */
class ReasoningEffortTest {

  private fun request(effort: String?) =
    ChatRequest(model = "m", messages = listOf(Message.user("hi")), reasoningEffort = effort)

  private fun config(yaml: String) = Config.fromConf(Yaml.loadString(yaml))

  // ---- the wire ----------------------------------------------------------

  @Test fun `nothing is sent unless asked, so an existing config is unaffected`() {
    // Adding the key must not change what anyone's current setup puts on the
    // wire; absent and `none` are different requests.
    request(null).encode().containsKey("reasoning_effort") shouldBe false
    config("model: m\n").reasoningEffort shouldBe ReasoningEffort.Default
  }

  @Test fun `a level is sent verbatim as reasoning_effort`() {
    val o = request("low").encode()
    o["reasoning_effort"]!!.jsonPrimitive.content shouldBe "low"
  }

  @Test fun `off means none on the wire, which is not the same as sending nothing`() {
    ReasoningEffort.parse("off")?.wire shouldBe "none"
    ReasoningEffort.parse("default")?.wire shouldBe null
  }

  // ---- parsing -----------------------------------------------------------

  @Test fun `the spellings a user actually types are accepted`() {
    listOf("off", "no", "false", "disable", "disabled").forEach { s ->
      ReasoningEffort.parse(s)?.wire shouldBe "none"
    }
    listOf("default", "provider", "unset").forEach { s ->
      ReasoningEffort.parse(s) shouldBe ReasoningEffort.Default
    }
    ReasoningEffort.parse("HIGH")?.wire shouldBe "high"
    ReasoningEffort.parse("  medium  ")?.wire shouldBe "medium"
  }

  @Test fun `an unknown level passes through rather than being refused`() {
    // Levels are provider-defined and new ones keep appearing; refusing one
    // would age worse than letting the endpoint rule on it. `/think` warns,
    // but it still sends.
    ReasoningEffort.parse("ultra")?.wire shouldBe "ultra"
    ReasoningEffort.Known shouldNotContain "ultra"
  }

  @Test fun `an empty value is not a setting`() {
    ReasoningEffort.parse("") shouldBe null
    ReasoningEffort.parse("   ") shouldBe null
  }

  // ---- config ------------------------------------------------------------

  @Test fun `the config key is read, and is separate from the display key`() {
    val c = config("model: m\nreasoning: hide\nreasoning_effort: minimal\n")
    c.reasoning shouldBe ReasoningDisplay.Hide
    c.reasoningEffort.wire shouldBe "minimal"
  }

  @Test fun `setting one does not move the other`() {
    // The confusion this guards against: `reasoning: hide` looks like it turns
    // thinking off. It does not -- the tokens are still generated and billed,
    // which CLAUDE.md finding 3 measured at a full 2048-token budget spent
    // inside a reasoning block that produced no visible text.
    val hidden = config("model: m\nreasoning: hide\n")
    hidden.reasoning shouldBe ReasoningDisplay.Hide
    hidden.reasoningEffort shouldBe ReasoningEffort.Default

    val quiet = config("model: m\nreasoning_effort: none\n")
    quiet.reasoningEffort.wire shouldBe "none"
    quiet.reasoning shouldBe ReasoningDisplay.Collapse // the default, untouched
  }

  @Test fun `an unparseable effort falls back to sending nothing`() {
    // Better than guessing a level: the provider default is a known quantity.
    config("model: m\nreasoning_effort: ''\n").reasoningEffort shouldBe ReasoningEffort.Default
  }

  // ---- the surface -------------------------------------------------------

  @Test fun `think is a real command and reasoning_effort is settable`() {
    kotllm.repl.Commands.all.map { it.name } shouldContain "/think"
    kotllm.repl.Commands.settableKeys shouldContain "reasoning_effort"
  }

  @Test fun `every level think offers round-trips through the parser`() {
    // A completion suggesting a value the parser would reject is the kind of
    // thing a test should catch rather than a user.
    (listOf("default") + ReasoningEffort.Known).forEach { v ->
      (ReasoningEffort.parse(v) != null) shouldBe true
    }
  }

  @Test fun `name round-trips for display`() {
    ReasoningEffort.name(ReasoningEffort.Default) shouldBe "default"
    ReasoningEffort.name(ReasoningEffort.Level("high")) shouldBe "high"
  }
}
