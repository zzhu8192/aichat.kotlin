package kotllm.tools

import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path

import kotlin.test.Test
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.collections.shouldNotContain

import kotlinx.serialization.json.*

import kotllm.*
import kotllm.engine.GroovyEngine
import kotllm.memory.Memory
import kotllm.memory.MemoryConfig
import kotllm.rag.RagConfig
import kotllm.rag.RagStore

class SearchToolTest {

    private fun tempDir(): Path = Files.createTempDirectory("kotllm-searchtool")

    private fun write(dir: Path, rel: String, content: String) {
        val p = dir.resolve(rel)
        Files.createDirectories(p.parent)
        Files.write(p, content.toByteArray(StandardCharsets.UTF_8))
    }

    private fun indexed(config: RagConfig = RagConfig.default): RagStore {
        val root = tempDir()
        write(root, "docs/kafka.md", "Kafka partitions are append only ordered logs. ".repeat(20))
        write(root, "docs/redis.md", "Redis stores keys and values entirely in memory. ".repeat(20))
        write(root, "src/Consumer.scala", "class Consumer {\n  def poll(): Int = 42\n}\n")
        val store = RagStore(tempDir(), config) { emptyMap() }
        store.indexFolder(root.toString()).orNull()!!
        return store
    }

    private fun call(store: RagStore, vararg args: Pair<String, JsonElement>): ToolResult =
        Tools.search(store).run(buildJsonObject { args.forEach { (k, v) -> put(k, v) } })

    @Test fun `the tool is named search, and keeps that name when vectors land`() {
        // The name is part of the contract the model learns; changing it later
        // would invalidate saved roles and mid-conversation habits.
        Tools.search(indexed()).name shouldBe "search"
        Tools.SearchName shouldBe "search"
    }

    @Test fun `a match comes back with a path and line range citation`() {
        val r = call(indexed(), "query" to JsonPrimitive("kafka partitions"))
        r.isError shouldBe false
        r.content shouldContain "docs/kafka.md:"
        r.content shouldContain "passage(s) for"
    }

    @Test fun `results name the corpus, never the local path`() {
        // The absolute path is noise the model cannot act on, and citations are
        // relative to the root anyway. A description is what it needs instead.
        val store = indexed()
        call(store, "query" to JsonPrimitive("kafka")).content shouldNotContain store.active!!.meta!!.root

        store.setDescription("our Kafka and Redis runbooks")
        call(store, "query" to JsonPrimitive("kafka")).content shouldContain "our Kafka and Redis runbooks"
    }

    @Test fun `a missing query argument is an error the model can correct`() {
        val r = call(indexed())
        r.isError shouldBe true
        r.content shouldContain "query"
    }

    @Test fun `no match is reported as an ordinary result, not an error`() {
        // Flagging it would push the model to retry the identical query.
        val r = call(indexed(), "query" to JsonPrimitive("zzzznomatchquery"))
        r.isError shouldBe false
        r.content shouldContain "No passages matched"
    }

    @Test fun `with no index active the error names the command that creates one`() {
        val store = RagStore(tempDir(), RagConfig.default) { emptyMap() }
        val r = call(store, "query" to JsonPrimitive("anything"))
        r.isError shouldBe true
        r.content shouldContain "/rag index"
    }

    @Test fun `top_k bounds the number of passages returned`() {
        val r = call(indexed(), "query" to JsonPrimitive("kafka redis consumer"), "top_k" to JsonPrimitive(1))
        r.content shouldContain "1 passage(s) for"
    }

    @Test fun `a nonsensical top_k falls back to the configured default rather than returning nothing`() {
        val r = call(indexed(), "query" to JsonPrimitive("kafka"), "top_k" to JsonPrimitive(0))
        r.isError shouldBe false
        r.content shouldContain "docs/kafka.md:"
    }

    @Test fun `a path prefix narrows the search to a subtree`() {
        val r = call(indexed(), "query" to JsonPrimitive("poll consumer"), "path" to JsonPrimitive("src/"))
        r.isError shouldBe false
        r.content shouldContain "src/Consumer.scala"
        r.content shouldNotContain "docs/"
    }

    @Test fun `a path prefix matching nothing says so, naming the prefix`() {
        val r = call(indexed(), "query" to JsonPrimitive("kafka"), "path" to JsonPrimitive("nowhere/"))
        r.isError shouldBe false
        r.content shouldContain "nowhere/"
    }

    @Test fun `output is bounded, and says how many passages it withheld`() {
        // An unbounded tool result can spend the whole context window in one call.
        val store = indexed()
        val tool = Tools.search(store, maxOutputChars = 900)
        val r = tool.run(
            buildJsonObject {
                put("query", "kafka redis consumer partitions")
                put("top_k", 8)
            }
        )

        (r.content.length <= 900 + 200) shouldBe true // the omission notice may overshoot the cap
        r.content shouldContain "omitted"
    }

    @Test fun `the preview shows the query, and the path filter when there is one`() {
        // Shown in the approval prompt, so it has to say what would actually run.
        val tool = Tools.search(indexed())
        tool.preview(buildJsonObject { put("query", "kafka") }) shouldBe "kafka"
        tool.preview(buildJsonObject { put("query", "kafka"); put("path", "docs/") }) shouldContain "docs/"
    }

    @Test fun `the description tells the model to cite, and to prefer narrow queries`() {
        val d = Tools.search(indexed()).description
        d shouldContain "citation"
        d shouldContain "narrow"
    }

    @Test fun `defaults omit search when rag is disabled`() {
        // Registering a tool that cannot work costs schema tokens on every request.
        val names = Tools
            .defaults(GroovyEngine(emptyList()), 10, Memory(MemoryConfig.default, tempDir()), null)
            .map { it.name }
        names shouldNotContain "search"
        names shouldContain "groovy_eval"
    }

    @Test fun `defaults include search when a store is supplied`() {
        val names = Tools
            .defaults(GroovyEngine(emptyList()), 10, Memory(MemoryConfig.default, tempDir()), indexed())
            .map { it.name }
        names shouldContain "search"
    }
}
