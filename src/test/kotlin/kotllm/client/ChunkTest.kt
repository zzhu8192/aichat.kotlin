package kotllm.client

import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.shouldBe
import kotlin.test.Test
import kotlinx.serialization.json.*

/** Streaming chunk decoding plus reassembly.
  *
  * The chunk payloads are verbatim from the live Databricks endpoint: note that
  * `delta.content` is an array of parts, not a string, and that `usage` repeats
  * on every chunk with cumulative totals.
  */
class ChunkTest {

    private fun chunk(json: String): List<StreamEvent> =
        Chunk.decode(Json.parseToJsonElement(json))

    @Test
    fun `decodes a reasoning delta from the array-of-parts shape`() {
        val events = chunk("""
            {"choices":[{"index":0,"delta":{"content":[
              {"type":"reasoning","summary":[{"type":"summary_text","text":"Thinking"}]}
            ]},"finish_reason":null}]}""")

        events shouldBe listOf(StreamEvent.ReasoningDelta("Thinking"))
    }

    @Test
    fun `decodes a visible text delta`() {
        val events = chunk("""
            {"choices":[{"delta":{"content":[{"type":"text","text":"4"}]},"finish_reason":null}]}""")

        events shouldBe listOf(StreamEvent.TextDelta("4"))
    }

    @Test
    fun `decodes a string delta from a plain OpenAI-compatible server`() {
        chunk("""{"choices":[{"delta":{"content":"hi"}}]}""") shouldBe listOf(StreamEvent.TextDelta("hi"))
    }

    @Test
    fun `reports cumulative usage and the finish reason`() {
        val events = chunk("""
            {"choices":[{"delta":{},"finish_reason":"stop"}],
             "usage":{"prompt_tokens":17,"completion_tokens":4,"total_tokens":21}}""")

        events shouldContain StreamEvent.UsageUpdate(Usage(17, 4, 21))
        events shouldContain StreamEvent.Finished("stop")
    }

    @Test
    fun `decodes an incremental tool call`() {
        val events = chunk("""
            {"choices":[{"delta":{"tool_calls":[
              {"index":0,"id":"call_1","type":"function",
               "function":{"name":"get_weather","arguments":"{\"city\":"}}
            ]}}]}""")

        events shouldBe listOf(
            StreamEvent.ToolCallDelta(0, "call_1", "get_weather", """{"city":""")
        )
    }

    @Test
    fun `an empty delta produces no events`() {
        chunk("""{"choices":[{"delta":{"role":"assistant","content":""}}]}""").shouldBeEmpty()
    }

    @Test
    fun `assembler replaces cumulative usage rather than summing it`() {
        val a = StreamAssembler()
        a.accept(StreamEvent.UsageUpdate(Usage(17, 1, 18)))
        a.accept(StreamEvent.UsageUpdate(Usage(17, 4, 21)))

        a.result(null).usage shouldBe Usage(17, 4, 21)
    }

    @Test
    fun `assembler separates reasoning from text and orders reasoning first`() {
        val a = StreamAssembler()
        a.accept(StreamEvent.ReasoningDelta("think"))
        a.accept(StreamEvent.TextDelta("ans"))
        a.accept(StreamEvent.TextDelta("wer"))
        a.accept(StreamEvent.Finished("stop"))

        val r = a.result("qwen")
        r.text shouldBe "answer"
        r.reasoning shouldBe "think"
        r.finishReason shouldBe "stop"
        r.model shouldBe "qwen"
    }

    @Test
    fun `assembler stitches tool call argument fragments in arrival order`() {
        val a = StreamAssembler()
        a.accept(StreamEvent.ToolCallDelta(0, "call_1", "get_weather", """{"city":"""))
        a.accept(StreamEvent.ToolCallDelta(0, null, null, """"Chicago"}"""))

        a.result(null).toolCalls shouldBe listOf(
            ToolCall("call_1", "get_weather", """{"city":"Chicago"}""")
        )
    }

    @Test
    fun `assembler keeps parallel tool calls apart by index`() {
        val a = StreamAssembler()
        a.accept(StreamEvent.ToolCallDelta(0, "c0", "first", "{}"))
        a.accept(StreamEvent.ToolCallDelta(1, "c1", "second", "{}"))

        a.result(null).toolCalls.map { it.name } shouldBe listOf("first", "second")
    }

    @Test
    fun `finish_reason length is reported as truncated`() {
        val a = StreamAssembler()
        a.accept(StreamEvent.Finished("length"))
        a.result(null).truncated shouldBe true
    }
}
