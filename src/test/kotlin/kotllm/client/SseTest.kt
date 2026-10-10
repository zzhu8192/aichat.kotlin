package kotllm.client

import io.kotest.matchers.shouldBe
import kotlin.test.Test

class SseTest {

    @Test
    fun `frames one data line per blank-line-terminated event`() {
        val lines = listOf("data: {\"a\":1}", "", "data: {\"a\":2}", "").iterator()
        Sse.events(lines) shouldBe listOf("""{"a":1}""", """{"a":2}""")
    }

    @Test
    fun `strips exactly one space after the colon, preserving further indent`() {
        Sse.events(listOf("data:  padded", "").iterator()) shouldBe listOf(" padded")
    }

    @Test
    fun `joins multi-line data fields with newlines`() {
        Sse.events(listOf("data: one", "data: two", "").iterator()) shouldBe listOf("one\ntwo")
    }

    @Test
    fun `stops at DONE and does not emit it as an event`() {
        val lines = listOf("data: {\"a\":1}", "", "data: [DONE]", "", "data: {\"a\":2}", "").iterator()
        Sse.events(lines) shouldBe listOf("""{"a":1}""")
    }

    @Test
    fun `ignores comments and non-data fields`() {
        val lines = listOf(": keep-alive", "event: message", "id: 7", "data: payload", "").iterator()
        Sse.events(lines) shouldBe listOf("payload")
    }

    @Test
    fun `tolerates CRLF line endings`() {
        Sse.events(listOf("data: payload\r", "\r").iterator()) shouldBe listOf("payload")
    }

    @Test
    fun `emits a trailing event when the stream ends without a blank line`() {
        // Gateways do sometimes just close the socket.
        Sse.events(listOf("data: last").iterator()) shouldBe listOf("last")
    }

    @Test
    fun `stops early when the continue check goes false, for Ctrl-C`() {
        var seen = 0
        val lines = listOf("data: a", "", "data: b", "", "data: c", "").iterator()
        Sse.foreach(lines, { seen < 2 }) { seen += 1 }
        seen shouldBe 2
    }
}
