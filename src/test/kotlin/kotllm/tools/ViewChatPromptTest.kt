package kotllm.tools

import java.nio.file.Files
import java.nio.file.Path

import kotlin.test.Test
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain

import kotlinx.serialization.json.*

import kotllm.client.Message
import kotllm.config.ChatSearch
import kotllm.config.Session
import kotllm.config.SessionStore

/** Reading back the system prompt recorded while `/prompt full` was on.
  *
  * This exists because of the rule the codebase has broken three times: a
  * mechanism that writes and has no reader. `remember` wrote a file nothing
  * loaded, `save: true` was parsed and ignored, and the Python bridge sat
  * behind a private method. `view_chat` is the reader.
  */
class ViewChatPromptTest {

    private fun sandbox(): Path = Files.createTempDirectory("kotllm-viewchat")

    private fun recorded(dir: Path, prompt: Pair<String?, String>?): String {
        val store = SessionStore(dir, true) { 1_758_000_000_000L }
        val s = Session()
        s.append(Message.user("a question about the wire format"))
        s.append(Message.assistant("an answer about the wire format"))
        store.record(s, "stub", prompt = prompt)
        return store.id
    }

    private fun view(dir: Path, id: String, withPrompt: Boolean): String {
        val args = buildJsonObject {
            put("session", id)
            put("context", 4)
            if (withPrompt) put("prompt", true)
        }
        return Tools.viewChat(ChatSearch(dir)).run(args).content
    }

    @Test fun `the recorded prompt comes back on request, both layers of it`() {
        val dir = sandbox()
        val id = recorded(dir, Pair("be terse", "## Tools -- be terse"))

        val out = view(dir, id, withPrompt = true)
        out shouldContain "system prompt as sent"
        out shouldContain "## Tools -- be terse"
        out shouldContain "of which the user wrote"
        // The messages are still the point of the tool.
        out shouldContain "a question about the wire format"
    }

    @Test fun `it is not returned unless asked for, because it is several KB`() {
        val dir = sandbox()
        val id = recorded(dir, Pair("be terse", "## Tools -- be terse"))

        val out = view(dir, id, withPrompt = false)
        out shouldNotContain "system prompt as sent"
        out shouldNotContain "## Tools"
        out shouldContain "a question about the wire format"
    }

    @Test fun `a session that never recorded one says so, rather than looking empty`() {
        // "not recorded" and "the prompt was blank" are different facts, and the
        // first one names the mode that would have captured it.
        val dir = sandbox()
        val id = recorded(dir, null)

        val out = view(dir, id, withPrompt = true)
        out shouldContain "not recorded"
        out shouldContain "/prompt full"
    }

    @Test fun `the prompt precedes the messages, the same order as on disk`() {
        val dir = sandbox()
        val id = recorded(dir, Pair(null, "## Tools -- a distinctive marker"))

        // Against the message delimiter, not the message text: the header repeats
        // the first user message as the conversation's title, so the text itself
        // appears before anything else either way.
        val out = view(dir, id, withPrompt = true)
        (out.indexOf("a distinctive marker") < out.indexOf("--- msg 0")) shouldBe true
    }
}
