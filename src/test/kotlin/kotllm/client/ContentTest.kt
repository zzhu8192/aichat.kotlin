package kotllm.client

import kotlin.test.Test
import io.kotest.matchers.shouldBe
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.types.shouldBeInstanceOf
import kotlinx.serialization.json.*

/** Locks in the decoding of the content shapes actually observed in the wild.
  *
  * The array-of-parts payloads here are verbatim from the live Databricks
  * Qwen 3.5-122b endpoint; a stock OpenAI decoder that assumes
  * `content: String` fails every one of them.
  */
class ContentTest {

    @Test
    fun `decodes a plain string, as any OpenAI-compatible server returns`() {
        Content.decode(JsonPrimitive("hello")) shouldBe listOf(Part.Text("hello"))
    }

    @Test
    fun `an empty string yields no parts rather than an empty text part`() {
        Content.decode(JsonPrimitive("")).shouldBeEmpty()
    }

    @Test
    fun `decodes null content, which accompanies a tool call`() {
        Content.decode(JsonNull).shouldBeEmpty()
    }

    @Test
    fun `decodes the Databricks array-of-parts shape, splitting reasoning from text`() {
        val payload = Json.parseToJsonElement("""
            [
              {"type":"reasoning","summary":[{"type":"summary_text","text":"Thinking Process: 2+2"}]},
              {"type":"text","text":"4"}
            ]""")

        val parts = Content.decode(payload)
        parts shouldBe listOf(Part.Reasoning("Thinking Process: 2+2"), Part.Text("4"))
        Content.text(parts) shouldBe "4"
        Content.reasoning(parts) shouldBe "Thinking Process: 2+2"
    }

    @Test
    fun `concatenates a reasoning part split across several summary entries`() {
        val payload = Json.parseToJsonElement("""
            [{"type":"reasoning","summary":[
              {"type":"summary_text","text":"first "},
              {"type":"summary_text","text":"second"}
            ]}]""")

        Content.decode(payload) shouldBe listOf(Part.Reasoning("first second"))
    }

    @Test
    fun `accepts reasoning carried directly on text instead of summary`() {
        val payload = Json.parseToJsonElement("""[{"type":"reasoning","text":"direct"}]""")
        Content.decode(payload) shouldBe listOf(Part.Reasoning("direct"))
    }

    @Test
    fun `treats output_text as visible text, as the Responses API names it`() {
        Content.decode(Json.parseToJsonElement("""[{"type":"output_text","text":"hi"}]""")) shouldBe
            listOf(Part.Text("hi"))
    }

    @Test
    fun `an unrecognised part with a text field still shows its text`() {
        // A new part type must not blank out an otherwise good answer.
        Content.decode(Json.parseToJsonElement("""[{"type":"future_thing","text":"still visible"}]""")) shouldBe
            listOf(Part.Text("still visible"))
    }

    @Test
    fun `an unrecognised part with no text is retained as Unknown, not dropped`() {
        val parts = Content.decode(Json.parseToJsonElement("""[{"type":"image","url":"http://x/y.png"}]"""))
        parts.size shouldBe 1
        (parts.first() is Part.Unknown) shouldBe true
        (parts.first() as Part.Unknown).kind shouldBe "image"
    }

    @Test
    fun `a lone part object, not wrapped in an array, decodes too`() {
        Content.decode(Json.parseToJsonElement("""{"type":"text","text":"bare"}""")) shouldBe listOf(Part.Text("bare"))
    }

    @Test
    fun `text() concatenates only visible parts, ignoring interleaved reasoning`() {
        val parts = listOf(Part.Text("a"), Part.Reasoning("ignored"), Part.Text("b"))
        Content.text(parts) shouldBe "ab"
    }
}
