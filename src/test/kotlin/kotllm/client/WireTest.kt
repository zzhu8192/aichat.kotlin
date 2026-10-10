package kotllm.client

import kotlin.test.Test
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.collections.shouldContain
import kotlinx.serialization.json.*

class WireTest {

    @Test
    fun `decodes a blocking response with reasoning and text parts`() {
        val body = Json.parseToJsonElement("""
            {"id":"chatcmpl_x","object":"chat.completion","model":"qwen35-122b-a10b",
             "choices":[{"index":0,"message":{"role":"assistant","content":[
                {"type":"reasoning","summary":[{"type":"summary_text","text":"thought"}]},
                {"type":"text","text":"4"}]},
              "finish_reason":"stop"}],
             "usage":{"prompt_tokens":20,"completion_tokens":279,"total_tokens":299}}""")

        val r = ChatResponse.decode(body)
        r.text shouldBe "4"
        r.reasoning shouldBe "thought"
        r.finishReason shouldBe "stop"
        r.usage shouldBe Usage(20, 279, 299)
        r.model shouldBe "qwen35-122b-a10b"
        r.truncated shouldBe false
    }

    @Test
    fun `decodes a tool call arriving alongside a reasoning part`() {
        val body = Json.parseToJsonElement("""
            {"choices":[{"message":{"role":"assistant",
              "content":[{"type":"reasoning","summary":[{"type":"summary_text","text":"need tool"}]}],
              "tool_calls":[{"id":"call_7","type":"function",
                "function":{"name":"get_weather","arguments":"{\"city\": \"Chicago\"}"}}]},
              "finish_reason":"tool_calls"}]}""")

        val r = ChatResponse.decode(body)
        r.text shouldBe ""
        r.reasoning shouldBe "need tool"
        r.toolCalls shouldBe listOf(ToolCall("call_7", "get_weather", """{"city": "Chicago"}"""))
        r.finishReason shouldBe "tool_calls"
    }

    @Test
    fun `a truncated reply is flagged`() {
        val body = Json.parseToJsonElement("""{"choices":[{"message":{"role":"assistant","content":[]},
                                      "finish_reason":"length"}]}""")
        ChatResponse.decode(body).truncated shouldBe true
    }

    @Test
    fun `a malformed response decodes to an empty assistant message rather than throwing`() {
        ChatResponse.decode(Json.parseToJsonElement("""{"unexpected":true}""")).text shouldBe ""
    }

    @Test
    fun `requests send content as a plain string and omit reasoning entirely`() {
        val req = ChatRequest(
            model = "m",
            messages = listOf(
                Message.system("sys"),
                Message("assistant", listOf(Part.Reasoning("private"), Part.Text("visible")))
            )
        )
        val json = req.encode()

        val messages = json.jsonObject["messages"]!!.jsonArray
        messages[0].jsonObject["content"]!!.jsonPrimitive.content shouldBe "sys"
        // Reasoning must never be replayed: it burns context and endpoints may
        // reject unknown part types on input.
        messages[1].jsonObject["content"]!!.jsonPrimitive.content shouldBe "visible"
        json.toString().contains("private") shouldBe false
    }

    @Test
    fun `omits optional sampling fields when unset so the endpoint applies its defaults`() {
        val json = ChatRequest("m", listOf(Message.user("hi"))).encode()
        json.jsonObject.containsKey("temperature") shouldBe false
        json.jsonObject.containsKey("top_p") shouldBe false
        json.jsonObject.containsKey("max_tokens") shouldBe false
        json.jsonObject.containsKey("stream") shouldBe false
    }

    @Test
    fun `includes sampling fields when set`() {
        val json = ChatRequest(
            "m", listOf(Message.user("hi")),
            temperature = 0.2, topP = 0.9, maxTokens = 256, stream = true
        ).encode()

        json.jsonObject["temperature"]!!.jsonPrimitive.double shouldBe 0.2
        json.jsonObject["top_p"]!!.jsonPrimitive.double shouldBe 0.9
        json.jsonObject["max_tokens"]!!.jsonPrimitive.double shouldBe 256.0
        json.jsonObject["stream"]!!.jsonPrimitive.boolean shouldBe true
    }

    @Test
    fun `an assistant message that is only a tool call sends null content`() {
        val json = Message.encode(
            Message("assistant", emptyList(), listOf(ToolCall("call_1", "exec", "{}")))
        )
        json.jsonObject["content"] shouldBe JsonNull
        json.jsonObject["tool_calls"]!!.jsonArray[0].jsonObject["function"]!!.jsonObject["name"]!!.jsonPrimitive.content shouldBe "exec"
    }

    @Test
    fun `a tool result carries its call id`() {
        val json = Message.encode(Message.tool("call_1", "output"))
        json.jsonObject["role"]!!.jsonPrimitive.content shouldBe "tool"
        json.jsonObject["tool_call_id"]!!.jsonPrimitive.content shouldBe "call_1"
        json.jsonObject["content"]!!.jsonPrimitive.content shouldBe "output"
    }

    @Test
    fun `encodes tool definitions in the OpenAI function envelope`() {
        val schema = Json.parseToJsonElement("""{"type":"object","properties":{"code":{"type":"string"}}}""")
        val json = ChatRequest(
            "m", listOf(Message.user("hi")),
            tools = listOf(ToolDef("scala_eval", "Run Scala", schema))
        ).encode()

        val tool = json.jsonObject["tools"]!!.jsonArray[0].jsonObject
        tool["type"]!!.jsonPrimitive.content shouldBe "function"
        tool["function"]!!.jsonObject["name"]!!.jsonPrimitive.content shouldBe "scala_eval"
        tool["function"]!!.jsonObject["parameters"]!!.jsonObject["type"]!!.jsonPrimitive.content shouldBe "object"
    }
}
