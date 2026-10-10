package kotllm.tools

import kotlin.test.Test
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.collections.shouldNotContain

import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject

/** `ask_user` and `get_sys_dt`.
 *
 * The interesting behaviour in `ask_user` is the refusals. A tool that
 * fabricates an answer when nobody is listening is worse than one that is
 * absent, because the model then builds on a decision the user never made.
 */
class AskUserTest {

    /** Records the question and returns a scripted answer. */
    private class Scripted(private val answer: List<String>?) : Prompter {
        var asked: Triple<String, List<String>, Boolean>? = null
        override fun confirm(toolName: String, preview: String): Prompter.Answer = Prompter.Answer.No
        override fun ask(question: String, options: List<String>, multi: Boolean): List<String>? {
            asked = Triple(question, options, multi)
            return answer
        }
    }

    private fun args(vararg pairs: Pair<String, JsonElement>): JsonElement = buildJsonObject {
        for ((k, v) in pairs) put(k, v)
    }

    private fun options(vararg xs: String): JsonElement = buildJsonArray {
        for (x in xs) add(JsonPrimitive(x))
    }

    // ---- ask_user ----------------------------------------------------------

    @Test fun `the answer comes back as a tool result, and the prompter saw the question`() {
        val p = Scripted(listOf("use Postgres"))
        val tool = Tools.askUser(p) { true }

        val r = tool.run(args(
            "question" to JsonPrimitive("Which database?"),
            "options" to options("use Postgres", "use SQLite")
        ))

        r.isError shouldBe false
        r.content shouldContain "use Postgres"
        p.asked!!.first shouldBe "Which database?"
        p.asked!!.second shouldBe listOf("use Postgres", "use SQLite")
        p.asked!!.third shouldBe false
    }

    @Test fun `multi_select is passed through and several answers are joined`() {
        val p = Scripted(listOf("logging", "metrics"))
        val r = Tools.askUser(p) { true }.run(args(
            "question" to JsonPrimitive("Which features?"),
            "options" to options("logging", "metrics", "tracing"),
            "multi_select" to JsonPrimitive(true)
        ))

        p.asked!!.third shouldBe true
        r.content shouldContain "logging"
        r.content shouldContain "metrics"
    }

    @Test fun `with nobody attached the question is refused, never answered by default`() {
        // The whole point. Returning the first option would invent a decision and
        // the model would then act as though the user had made it.
        val p = Scripted(listOf("should never be used"))
        val r = Tools.askUser(p) { false }.run(args(
            "question" to JsonPrimitive("Which one?"),
            "options" to options("a", "b")
        ))

        r.isError shouldBe true
        r.content shouldContain "nobody attached"
        // And it did not even reach the prompter.
        p.asked shouldBe null
        r.content shouldNotContain "should never be used"
    }

    @Test fun `a cancelled question is reported as unanswered, with what to do next`() {
        val p = Scripted(null)
        val r = Tools.askUser(p) { true }.run(args(
            "question" to JsonPrimitive("Which one?"),
            "options" to options("a", "b")
        ))

        r.isError shouldBe true
        r.content shouldContain "not answered"
        // Actionable: the model has to proceed, not ask again in a loop.
        r.content shouldContain "State your assumption"
    }

    @Test fun `an open question with no options is refused`() {
        // An open question invites an essay; the choices are the feature.
        val r = Tools.askUser(Scripted(null)) { true }
            .run(args("question" to JsonPrimitive("What should I do?"), "options" to buildJsonArray {}))

        r.isError shouldBe true
        r.content shouldContain "at least one option"
    }

    @Test fun `blank and non-string options are dropped rather than shown as empty rows`() {
        val p = Scripted(listOf("real"))
        Tools.askUser(p) { true }.run(args(
            "question" to JsonPrimitive("Which?"),
            "options" to buildJsonArray { add(JsonPrimitive("real")); add(JsonPrimitive("   ")); add(JsonPrimitive(7)) }
        ))
        p.asked!!.second shouldBe listOf("real")
    }

    @Test fun `a missing question is named`() {
        Tools.askUser(Scripted(null)) { true }
            .run(args("options" to options("a"))).content shouldContain "'question'"
    }

    @Test fun `it is read-only, since prompting to ask a question would be absurd`() {
        Tools.askUser(Scripted(null)) { true }.readOnly shouldBe true
        Tools.askUser(Scripted(null)) { true }.name shouldBe Tools.AskUserName
    }

    @Test fun `the description tells the model not to add its own free-text option`() {
        // The prompter appends one. A model-supplied "Other" would produce two.
        val d = Tools.askUser(Scripted(null)) { true }.description
        d shouldContain "free-text"
        d shouldContain "never add one yourself"
    }

    @Test fun `registration is gated on somebody being there to answer`() {
        // Not just refused per call -- withdrawn from the schema entirely, the same
        // rule as `search` with no index. Verified here rather than through the pty
        // harness, which feeds stdin from a file and so reports no console at all.
        val engine = kotllm.engine.GroovyEngine(emptyList())
        val memory = kotllm.memory.Memory(
            kotllm.memory.MemoryConfig.default,
            java.nio.file.Files.createTempDirectory("kotllm-ask")
        )

        val withUser = Tools
            .defaults(engine, 10, memory, null, prompter = Scripted(null), interactive = { true })
            .map { it.name }
        withUser shouldContain Tools.AskUserName

        val without = Tools.defaults(engine, 10, memory, null).map { it.name }
        without shouldNotContain Tools.AskUserName
        // The clock is always there, since it needs nobody.
        without shouldContain Tools.TimestampName
    }

    // ---- get_sys_dt ---------------------------------------------

    @Test fun `every form of the same instant, from one call`() {
        val fixed = java.time.Instant.parse("2026-09-18T14:30:05Z")
        val r = Tools.timestamp { fixed }.run(buildJsonObject {})

        r.isError shouldBe false
        r.content shouldContain "epoch_seconds   ${fixed.epochSecond}"
        r.content shouldContain "epoch_millis    ${fixed.toEpochMilli()}"
        r.content shouldContain "2026-09-18T14:30:05Z"
        r.content shouldContain "Friday"
        // The local line names its zone, or a bare wall-clock time is ambiguous.
        r.content shouldContain java.time.ZoneId.systemDefault().id
    }

    @Test fun `it takes no arguments, so there is nothing to get wrong`() {
        val tool = Tools.timestamp()
        (tool.parameters as JsonObject).containsKey("required") shouldBe false
        tool.readOnly shouldBe true
        tool.name shouldBe Tools.TimestampName
        // Callable with an empty object, which is what a model sends.
        tool.run(buildJsonObject {}).isError shouldBe false
    }
}
