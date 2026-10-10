package kotllm.config

import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import io.kotest.matchers.string.shouldStartWith
import kotlinx.serialization.json.*
import kotllm.*
import kotllm.client.Message
import kotllm.client.Part

/** Persisted conversations, and search over them.
 *
 * `save: true` was parsed, printed by `/info`, and read by nothing before
 * this, with `sessions/` created at startup and never written to -- the same
 * over-promise `remember` made when it wrote a file nothing loaded. These
 * tests exist mostly to keep the key honest.
 */
class SessionStoreTest {

    private fun sandbox(): Path = Files.createTempDirectory("kotllm-sessions")

    private fun store(dir: Path, enabled: Boolean = true) =
        SessionStore(dir, enabled) { 1_758_000_000_000L }

    private fun conversation(vararg pairs: Pair<String, String>): Session {
        val s = Session()
        pairs.forEach { (role, text) ->
            when (role) {
                "user" -> s.append(Message.user(text))
                "assistant" -> s.append(Message.assistant(text))
                else -> s.append(Message(role, listOf(Part.Text(text))))
            }
        }
        return s
    }

    // ---- writing -----------------------------------------------------------

    @Test fun `a turn is appended as JSONL, so a kill keeps everything before it`() {
        val dir = sandbox()
        val st = store(dir)
        st.record(conversation("user" to "what is 2+2", "assistant" to "4"),
                  "databricks-qwen35-122b-a10b")

        val files = Files.list(dir)
        val found = try { files.iterator().next() } finally { files.close() }
        val lines = Files.readAllLines(found, StandardCharsets.UTF_8)

        // Header plus one line per message, each a complete JSON object -- which
        // is the property that makes a truncated file still readable.
        lines.size shouldBe 3
        lines.forEach { l -> (SessionStore.json.parseToJsonElement(l) is JsonObject) shouldBe true }
        SessionStore.json.parseToJsonElement(lines[0]).jsonObject["model"]!!.jsonPrimitive.content shouldBe
            "databricks-qwen35-122b-a10b"
        SessionStore.json.parseToJsonElement(lines[1]).jsonObject["role"]!!.jsonPrimitive.content shouldBe "user"
        SessionStore.json.parseToJsonElement(lines[2]).jsonObject["text"]!!.jsonPrimitive.content shouldBe "4"
    }

    @Test fun `only the new tail is written, never the whole history again`() {
        val dir = sandbox()
        val st = store(dir)
        val s = conversation("user" to "first question", "assistant" to "first answer")
        st.record(s, "m")

        s.append(Message.user("second question"))
        s.append(Message.assistant("second answer"))
        st.record(s, "m")

        val files = Files.list(dir)
        val found = try { files.iterator().next() } finally { files.close() }
        val text = String(Files.readAllBytes(found), StandardCharsets.UTF_8)

        // Each appears exactly once. A store that mirrored the whole history per
        // turn would quadratically duplicate a long conversation.
        (text.split("first question").size - 1) shouldBe 1
        (text.split("second answer").size - 1) shouldBe 1
    }

    @Test fun `usage lands on the assistant message it belongs to`() {
        val dir = sandbox()
        store(dir).record(conversation("user" to "hello there", "assistant" to "hi"), "m",
                          usage = 4886 to 34)

        val loaded = SessionStore.loadAll(dir).first()
        loaded.messages.last().promptTokens shouldBe 4886
        loaded.messages.last().completionTokens shouldBe 34
        loaded.messages.first().promptTokens shouldBe null
    }

    @Test fun `messages with no text are skipped, since they carry nothing to search`() {
        val dir = sandbox()
        val s = Session()
        s.append(Message.user("a real question"))
        s.append(Message("tool", listOf(Part.Text(""))))
        store(dir).record(s, "m")

        SessionStore.loadAll(dir).first().messages.map { it.role } shouldBe listOf("user")
    }

    @Test fun `disabled writes nothing at all, not even a directory`() {
        val dir = sandbox().resolve("never")
        store(dir, enabled = false).record(conversation("user" to "hello there"), "m")
        Files.exists(dir) shouldBe false
        SessionStore.disabled.isEnabled shouldBe false
    }

    @Test fun `compaction resyncs the cursor rather than re-appending the rewrite`() {
        // Compaction replaces the live history. Without the resync the next record
        // would treat the summary and everything after it as new.
        val dir = sandbox()
        val st = store(dir)
        val s = conversation("user" to "the original question", "assistant" to "the original answer")
        st.record(s, "m")

        s.replaceHistory(listOf(Message.user("a summary of earlier")))
        st.resyncAfterCompaction(s)
        s.append(Message.assistant("a later answer"))
        st.record(s, "m")

        val text = String(Files.readAllBytes(SessionStore.loadAll(dir).first().path), StandardCharsets.UTF_8)
        // The original survives on disk even though it is gone from the history.
        text shouldContain "the original question"
        text shouldContain "a later answer"
        text shouldNotContain "a summary of earlier"
    }

    // ---- reading -----------------------------------------------------------

    @Test fun `a truncated final line is skipped rather than failing the read`() {
        // Exactly the case append-per-turn exists to survive.
        val dir = sandbox()
        val file = dir.resolve("2026-09-18T09-00-00-aaaa.jsonl")
        Files.createDirectories(dir)
        Files.write(file, (
            """{"v":1,"started":1758000000000,"model":"m","id":"2026-09-18T09-00-00-aaaa"}""" + "\n" +
                """{"role":"user","at":1758000000000,"i":0,"text":"a complete question"}""" + "\n" +
                """{"role":"assistant","at":17580000"""
        ).toByteArray(StandardCharsets.UTF_8))

        val loaded = SessionStore.load(file)
        (loaded != null) shouldBe true
        loaded!!.messages.map { it.text } shouldBe listOf("a complete question")
    }

    @Test fun `a listing is recognisable, which an id and a timestamp alone are not`() {
        val dir = sandbox()
        store(dir).record(
            conversation("user" to "how does the alternate character set break ssh rules?",
                        "assistant" to "JLine rewrites box drawing"), "m")

        val listed = SessionStore.loadAll(dir).first()
        listed.title() shouldStartWith "how does the alternate"
        listed.model shouldBe "m"
        (listed.chars() > 0) shouldBe true
    }

    @Test fun `sessions come back newest first`() {
        val dir = sandbox()
        SessionStore(dir, true) { 1_000_000L }.record(conversation("user" to "the older one"), "m")
        SessionStore(dir, true) { 2_000_000L }.record(conversation("user" to "the newer one"), "m")

        SessionStore.loadAll(dir).map { it.title() } shouldBe listOf("the newer one", "the older one")
    }

    @Test fun `an id resolves from a prefix or its short suffix, since nobody retypes one`() {
        val dir = sandbox()
        val st = store(dir)
        st.record(conversation("user" to "a question worth finding"), "m")
        val id = st.id

        SessionStore.find(dir, id).orNull()!!.id shouldBe id
        SessionStore.find(dir, id.take(13)).orNull()!!.id shouldBe id
        SessionStore.find(dir, id.takeLast(4)).orNull()!!.id shouldBe id
        (SessionStore.find(dir, "nope") is Outcome.Err) shouldBe true
    }

    @Test fun `no saved conversations is reported as that, not as no match`() {
        SessionStore.find(sandbox(), "anything").fold({ it }, { "" }) shouldContain "no saved"
    }

    @Test fun `timestamps round trip through JSON as numbers, not lossy strings`() {
        // The bug this pins, on the Scala side: `ujson.Obj("at" -> someLong)` wrote
        // "at":"1758..." as a JSON *string*, and the reader only tried a numeric
        // parse, so every timestamp loaded as 0 and sessions came back in the wrong
        // order. `SessionStore.open` writes `started` as `now.toDouble()` precisely
        // to avoid the kotlinx.serialization equivalent of that trap; this asserts
        // the round trip actually holds.
        val dir = sandbox()
        store(dir).record(conversation("user" to "a question with a timestamp"), "m")
        SessionStore.loadAll(dir).first().startedAt shouldBe 1_758_000_000_000L
    }

    @Test fun `a timestamp stored as a string is still read, so old files keep working`() {
        val dir = sandbox()
        val file = dir.resolve("2026-09-18T09-00-00-bbbb.jsonl")
        Files.createDirectories(dir)
        Files.write(file, (
            """{"v":1,"started":"1758000000000","model":"m","id":"old-string-form"}""" + "\n" +
                """{"role":"user","at":"1758000000000","i":0,"text":"written by the first build"}"""
        ).toByteArray(StandardCharsets.UTF_8))

        val loaded = SessionStore.load(file)!!
        loaded.startedAt shouldBe 1_758_000_000_000L
        loaded.messages.first().at shouldBe 1_758_000_000_000L
    }

    // ---- search ------------------------------------------------------------

    @Test fun `a past conversation is found by a distinctive word, with a usable citation`() {
        val dir = sandbox()
        store(dir).record(
            conversation(
                "user" to "why did the rules render as rows of q over ssh?",
                "assistant" to "JLine rewrites eleven box-drawing characters into the alternate charset"
            ), "databricks-qwen35-122b-a10b")

        val hits = ChatSearch(dir).search("alternate charset", 5)

        hits.isEmpty() shouldBe false
        hits.first().message.text shouldContain "alternate charset"
        // The citation is what view_chat consumes, so it has to carry the id.
        hits.first().citation() shouldContain hits.first().session.id
        hits.first().citation() shouldContain "msg "
    }

    @Test fun `the current session is excluded, because it is never the question`() {
        val dir = sandbox()
        val st = store(dir)
        st.record(conversation("user" to "a uniquely phrased inquiry about widgets"), "m")

        ChatSearch(dir).search("uniquely phrased widgets", 5).isEmpty() shouldBe false
        ChatSearch(dir).search("uniquely phrased widgets", 5, st.id).isEmpty() shouldBe true
    }

    @Test fun `role filters to one side of the conversation`() {
        val dir = sandbox()
        store(dir).record(
            conversation("user" to "tell me about compaction thresholds",
                        "assistant" to "compaction thresholds are checked against the projection"),
            "m")

        val cs = ChatSearch(dir)
        cs.search("compaction thresholds", 5, null, "user").map { it.message.role }.distinct() shouldBe
            listOf("user")
        cs.search("compaction thresholds", 5, null, "assistant").map { it.message.role }.distinct() shouldBe
            listOf("assistant")
    }

    @Test fun `trivial messages are not indexed, because they match everything`() {
        val dir = sandbox()
        store(dir).record(conversation("user" to "ok", "assistant" to "yes"), "m")
        ChatSearch(dir).search("ok", 5).isEmpty() shouldBe true
    }

    @Test fun `the model name is searchable even when the text never mentions it`() {
        // Same reasoning as indexing the path alongside a RAG chunk.
        val dir = sandbox()
        store(dir).record(conversation("user" to "a question with no model name in it at all"),
                          "databricks-deepseek-v4-1-flash")

        ChatSearch(dir).search("deepseek", 5).isEmpty() shouldBe false
    }

    @Test fun `an empty corpus and an empty query both return nothing rather than throwing`() {
        val cs = ChatSearch(sandbox())
        cs.search("anything", 5).isEmpty() shouldBe true
        cs.sessions().isEmpty() shouldBe true
        ChatSearch(sandbox()).search("", 5).isEmpty() shouldBe true
    }

    // ---- the assembled system prompt, recorded while /prompt full is on ----

    @Test fun `no prompt is recorded unless the full view asked for one`() {
        val dir = sandbox()
        store(dir).record(conversation("user" to "a question", "assistant" to "an answer"), "m")
        SessionStore.loadAll(dir).first().prompts.isEmpty() shouldBe true
    }

    @Test fun `both layers are stored, so the user's own slice is not left to be eyeballed`() {
        val dir = sandbox()
        store(dir).record(
            conversation("user" to "a question", "assistant" to "an answer"),
            "m",
            prompt = "be terse" to "## Tools -- be terse"
        )

        val prompts = SessionStore.loadAll(dir).first().prompts
        prompts.size shouldBe 1
        val p = prompts.first()
        p.full shouldBe "## Tools -- be terse"
        p.user shouldBe "be terse"
        p.index shouldBe 0
    }

    @Test fun `a prompt row is written every turn, even when the text has not changed`() {
        // Decided against change-only: reconstructing which prompt was live at turn
        // nine by scanning backwards is exactly what the mode exists to avoid, and
        // nobody leaves it on long enough for the duplication to matter.
        val dir = sandbox()
        val st = store(dir)
        val s = conversation("user" to "first question", "assistant" to "first answer")
        val same: Pair<String?, String> = "be terse" to "## Tools -- be terse"
        st.record(s, "m", prompt = same)

        s.append(Message.user("second question"))
        s.append(Message.assistant("second answer"))
        st.record(s, "m", prompt = same)

        val prompts = SessionStore.loadAll(dir).first().prompts
        prompts.size shouldBe 2
        // Indexed by the first message each governed, which is what makes
        // promptAt resolve rather than guess.
        prompts.map { it.index } shouldBe listOf(0, 2)
    }

    @Test fun `a prompt row precedes the messages it governed, for a forward reader`() {
        val dir = sandbox()
        store(dir).record(conversation("user" to "a question"), "m",
                          prompt = Pair(null, "## Tools"))

        val lines = Files.readAllLines(SessionStore.loadAll(dir).first().path, StandardCharsets.UTF_8)
        SessionStore.json.parseToJsonElement(lines[1]).jsonObject["kind"]!!.jsonPrimitive.content shouldBe "prompt"
        SessionStore.json.parseToJsonElement(lines[2]).jsonObject["role"]!!.jsonPrimitive.content shouldBe "user"
    }

    @Test fun `promptAt resolves the prompt live at a message, including before it was on`() {
        val dir = sandbox()
        val st = store(dir)
        val s = conversation("user" to "before the view was on", "assistant" to "an answer")
        st.record(s, "m")

        s.append(Message.user("after the view was on"))
        s.append(Message.assistant("another answer"))
        st.record(s, "m", prompt = Pair(null, "## Tools"))

        val loaded = SessionStore.loadAll(dir).first()
        loaded.promptAt(2)?.full shouldBe "## Tools"
        loaded.promptAt(3)?.full shouldBe "## Tools"
        // Turn 0 predates the recording. The nearest later row is the honest
        // answer, not a claim that there was no prompt.
        loaded.promptAt(0)?.full shouldBe "## Tools"
    }

    @Test fun `prompt rows do not shift message numbering, and are not searchable`() {
        val dir = sandbox()
        val st = store(dir)
        val s = conversation("user" to "a question about compaction thresholds")
        st.record(s, "m", prompt = Pair(null, "## Tools, something about compaction thresholds"))
        s.append(Message.assistant("an answer about compaction thresholds and how they work"))
        st.record(s, "m", prompt = Pair(null, "## Tools, something about compaction thresholds"))

        val loaded = SessionStore.loadAll(dir).first()
        loaded.messages.map { it.index } shouldBe listOf(0, 1)
        loaded.messages.map { it.role } shouldBe listOf("user", "assistant")

        // The prompt is kotllm's own text, not something anyone said. Indexing it
        // would make every session match every harness-flavoured query.
        ChatSearch(dir).search("compaction thresholds", 10)
            .map { it.message.role }.distinct().sorted() shouldBe listOf("assistant", "user")
    }

    @Test fun `a file written without prompt rows still loads, and one with them loads on an old reader`() {
        // Forward compatibility runs both ways: a prompt row carries no role, so
        // the reader that predates it skips the line rather than failing the file.
        val dir = sandbox()
        store(dir).record(conversation("user" to "a question", "assistant" to "an answer"), "m",
                          prompt = Pair(null, "## Tools"))

        val loaded = SessionStore.loadAll(dir).first()
        loaded.messages.size shouldBe 2
        loaded.prompts.size shouldBe 1

        val rows = Files.readAllLines(loaded.path, StandardCharsets.UTF_8)
        val promptRow = SessionStore.json.parseToJsonElement(rows[1]).jsonObject
        promptRow["role"] shouldBe null
        promptRow["text"] shouldBe null
    }
}
