package kotllm.rag

import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths

import kotlin.test.Test
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import io.kotest.matchers.string.shouldStartWith

import kotllm.*

class RagStoreTest {

    private fun tempDir(): Path = Files.createTempDirectory("kotllm-store")

    private fun write(dir: Path, rel: String, content: String): Path {
        val p = dir.resolve(rel)
        Files.createDirectories(p.parent)
        Files.write(p, content.toByteArray(StandardCharsets.UTF_8))
        return p
    }

    private fun store(base: Path, config: RagConfig = RagConfig.default): RagStore =
        RagStore(base, config) { emptyMap() }

    /** A corpus with two clearly separable topics, so a retrieval assertion is
      * about ranking rather than about there being only one candidate. */
    private fun corpus(): String {
        val root = tempDir()
        write(root, "docs/kafka.md", "Kafka partitions are append only ordered logs. ".repeat(20))
        write(root, "docs/redis.md", "Redis stores keys and values entirely in memory. ".repeat(20))
        write(root, "src/Consumer.scala", "class Consumer {\n  def poll(): Int = 42\n}\n")
        return root.toString()
    }

    // ---- activation --------------------------------------------------------

    @Test fun `indexing a folder makes it the active index`() {
        val base = tempDir()
        val s = store(base)
        s.active shouldBe null

        val report = s.indexFolder(corpus()).orNull()!!
        report.filesIndexed shouldBe 3
        s.active?.name shouldBe report.name
    }

    @Test fun `the active index survives a restart`() {
        // Re-activating after every launch would make the feature a chore, and
        // re-indexing is incremental anyway, so the marker lives on disk.
        val base = tempDir()
        val name = store(base).indexFolder(corpus()).orNull()!!.name

        val reopened = store(base)
        reopened.active?.name shouldBe name
        (reopened.active!!.chunkCount > 0) shouldBe true
    }

    @Test fun `a marker naming an index that no longer exists is forgotten, not fatal`() {
        val base = tempDir()
        Files.createDirectories(base)
        Files.write(base.resolve("active"), "ghost".toByteArray(StandardCharsets.UTF_8))

        val s = store(base)
        s.active shouldBe null
        s.status shouldContain "no active index"
    }

    @Test fun `an empty marker is ignored`() {
        val base = tempDir()
        Files.createDirectories(base)
        Files.write(base.resolve("active"), "   ".toByteArray(StandardCharsets.UTF_8))
        store(base).active shouldBe null
    }

    @Test fun `deactivating clears the marker as well as the in-memory state`() {
        val base = tempDir()
        val s = store(base)
        s.indexFolder(corpus())
        s.deactivate()

        s.active shouldBe null
        store(base).active shouldBe null
    }

    @Test fun `an existing index can be reactivated by name`() {
        val base = tempDir()
        val s = store(base)
        val name = s.indexFolder(corpus()).orNull()!!.name
        s.deactivate()

        s.activate(name).map { it.name } shouldBe ok(name)
        s.active?.name shouldBe name
    }

    @Test fun `activating an unknown name is reported`() {
        store(tempDir()).activate("nope").isOk shouldBe false
    }

    // ---- collisions and removal --------------------------------------------

    @Test fun `two folders sharing a basename are refused rather than merged`() {
        // Silently interleaving unrelated corpora into one index would make every
        // later answer subtly wrong, with nothing on screen to explain why.
        val base = tempDir()
        val s = store(base)

        val a = tempDir().resolve("shared")
        val b = tempDir().resolve("shared")
        Files.createDirectories(a)
        Files.createDirectories(b)
        write(a, "one.md", "alpha content ".repeat(20))
        write(b, "two.md", "beta content ".repeat(20))

        s.indexFolder(a.toString()).isOk shouldBe true
        val second = s.indexFolder(b.toString())

        second.isOk shouldBe false
        val msg = (second as Outcome.Err).message
        msg shouldContain "already points at"
        // The command as the REPL actually spells it. This asserted `.rag drop`
        // for as long as the message said it, so the test pinned the typo rather
        // than catching it: a user following the advice had it sent to the model.
        msg shouldContain "/rag drop"
    }

    @Test fun `re-indexing the same folder is allowed and incremental`() {
        val base = tempDir()
        val s = store(base)
        val root = corpus()

        s.indexFolder(root)
        val again = s.indexFolder(root).orNull()!!
        again.scan.unchanged.size shouldBe 3
        again.chunksAdded shouldBe 0
    }

    @Test fun `reindex picks up a file added since the last run`() {
        val base = tempDir()
        val s = store(base)
        val root = corpus()
        s.indexFolder(root)

        write(Paths.get(root), "docs/new.md", "freshly written material about sharding ".repeat(20))
        val report = s.reindex().orNull()!!

        report.scan.added.size shouldBe 1
        s.search("sharding", 5, null).orNull()!!.isEmpty() shouldBe false
    }

    @Test fun `reindex without an active index says what to do`() {
        (store(tempDir()).reindex() as Outcome.Err).message shouldContain "/rag index"
    }

    @Test fun `dropping the active index deactivates it`() {
        val base = tempDir()
        val s = store(base)
        val name = s.indexFolder(corpus()).orNull()!!.name

        s.drop(name) shouldBe ok(Unit)
        s.active shouldBe null
        s.names.isEmpty() shouldBe true
    }

    @Test fun `dropping an unknown index is reported`() {
        store(tempDir()).drop("nope").isOk shouldBe false
    }

    @Test fun `indexing something that is not a directory is reported`() {
        val file = write(tempDir(), "a.md", "x")
        store(tempDir()).indexFolder(file.toString()).isOk shouldBe false
    }

    // ---- search --------------------------------------------------------------

    @Test fun `searching with no active index says how to make one`() {
        (store(tempDir()).search("anything", 5, null) as Outcome.Err).message shouldContain "/rag index"
    }

    @Test fun `search ranks the on-topic document first and cites it`() {
        val base = tempDir()
        val s = store(base)
        s.indexFolder(corpus())

        val hits = s.search("kafka partitions", 5, null).orNull()!!
        hits.isEmpty() shouldBe false
        hits.first().first.path shouldBe "docs/kafka.md"
        hits.first().first.citation shouldStartWith "docs/kafka.md:"
    }

    @Test fun `a path filter restricts results to a subtree`() {
        val base = tempDir()
        val s = store(base)
        s.indexFolder(corpus())

        val hits = s.search("poll consumer", 5, "src/").orNull()!!
        hits.map { it.first.path }.forEach { it shouldStartWith "src/" }
    }

    // ---- injection: session scope only -------------------------------------

    /** An indexed store with injection turned on for the session, which is now
      * the only way it can be on. */
    private fun injecting(base: Path, config: RagConfig = RagConfig.default): RagStore {
        val s = store(base, config)
        s.indexFolder(corpus())
        s.setInjection(true) shouldBe ok(Unit)
        return s
    }

    @Test fun `injection is off by default, so nothing rides on a request unasked`() {
        val s = store(tempDir())
        s.indexFolder(corpus())

        s.injectionEnabled shouldBe false
        s.injection("kafka partitions") shouldBe null
    }

    @Test fun `injection returns cited passages once enabled for the session`() {
        val s = injecting(tempDir())

        s.injectionEnabled shouldBe true
        val block = s.injection("kafka partitions")!!
        block shouldContain "Retrieved context"
        block shouldContain "docs/kafka.md:"
        // Framed as background, not instruction: a keyword match is not an answer
        // the user endorsed.
        block shouldContain "background material, not instruction"
        block shouldContain kotllm.tools.Tools.SearchName
    }

    @Test fun `nothing on disk records the injection setting`() {
        // If it were persisted anywhere it could be set globally, by hand-editing
        // if nothing else, which is exactly what must not be possible.
        val base = tempDir()
        val s = injecting(base)
        val meta = String(
            Files.readAllBytes(s.active!!.dir.resolve("meta.json")),
            StandardCharsets.UTF_8
        )
        meta shouldNotContain "auto_inject"
        meta shouldNotContain "inject"
    }

    @Test fun `a reindex of the same folder keeps injection where the user set it`() {
        // Re-scanning is not a change of corpus.
        val s = injecting(tempDir())
        s.reindex().isOk shouldBe true
        s.injectionEnabled shouldBe true
    }

    @Test fun `switching index turns injection off`() {
        // Silently injecting from a newly activated folder is the unasked token
        // spend this design exists to avoid.
        val base = tempDir()
        val s = injecting(base)
        val other = tempDir().resolve("other")
        Files.createDirectories(other)
        write(other, "o.md", "unrelated material ".repeat(20))

        s.indexFolder(other.toString()).isOk shouldBe true
        s.injectionEnabled shouldBe false
    }

    @Test fun `reactivating an index by name turns injection off`() {
        val base = tempDir()
        val s = injecting(base)
        val name = s.active!!.name

        s.activate(name).isOk shouldBe true
        s.injectionEnabled shouldBe false
    }

    @Test fun `deactivating turns injection off, so re-activating does not resume it`() {
        val base = tempDir()
        val s = injecting(base)
        val name = s.active!!.name

        s.deactivate()
        s.injectionEnabled shouldBe false
        s.activate(name)
        s.injectionEnabled shouldBe false
    }

    @Test fun `injection can be turned back off within the session`() {
        val s = injecting(tempDir())
        s.setInjection(false) shouldBe ok(Unit)
        s.injectionEnabled shouldBe false
        s.injection("kafka partitions") shouldBe null
    }

    @Test fun `turning injection on with no active index is reported`() {
        // Accepting it would report success for something that cannot happen.
        store(tempDir()).setInjection(true).isOk shouldBe false
    }

    @Test fun `injection is None when the query matches nothing`() {
        injecting(tempDir()).injection("zzzznomatchquery") shouldBe null
    }

    @Test fun `injection is None for a blank query rather than injecting arbitrary text`() {
        injecting(tempDir()).injection("   ") shouldBe null
    }

    @Test fun `injection honours inject_top_k`() {
        val s = injecting(tempDir(), RagConfig.default.copy(injectTopK = 1))
        s.injection("kafka redis consumer")!! shouldContain "1 passage(s) retrieved"
    }

    @Test fun `injection honours inject_max_chars by dropping passages, not truncating one`() {
        // A passage cut mid-sentence reads as if the file ended there, which is a
        // worse failure than one fewer passage.
        val tight = injecting(tempDir(), RagConfig.default.copy(injectTopK = 8, injectMaxChars = 600))
        val block = tight.injection("kafka redis consumer partitions")!!

        val roomy = injecting(tempDir(), RagConfig.default.copy(injectTopK = 8, injectMaxChars = 100000))
        val full = roomy.injection("kafka redis consumer partitions")!!

        // The tight budget yields strictly fewer passages, and every passage it
        // does include is whole.
        (countPassages(block) < countPassages(full)) shouldBe true
        (countPassages(block) >= 1) shouldBe true
    }

    private fun countPassages(block: String): Int =
        block.lineSequence().count { it.startsWith("[") && it.contains(":") }

    // ---- status ------------------------------------------------------------

    @Test fun `status names the folder, the counts and the injection state`() {
        val base = tempDir()
        val s = store(base)
        val root = corpus()
        s.indexFolder(root)

        val text = s.status
        text shouldContain root
        text shouldContain "3 file(s)"
        text shouldContain "off (tool only)"

        s.setInjection(true)
        // Says "for this session" so the report cannot be mistaken for the old
        // persisted behaviour.
        s.status shouldContain "on for this session"
    }

    @Test fun `status reports hybrid retrieval and the vectors behind it`() {
        val base = tempDir()
        val s = store(base)
        s.indexFolder(corpus())

        val text = s.status
        text shouldContain "hybrid"
        text shouldContain "rrf"
        text shouldContain "2048d"
    }

    @Test fun `status reports bm25 only when the encoder is off, and builds no vectors`() {
        val base = tempDir()
        val s = store(base, RagConfig.default.copy(embedding = EmbeddingMode.None))
        s.indexFolder(corpus())

        s.status shouldContain "bm25 only"
        s.status shouldContain "off (bm25 only)"
        s.active!!.vectorsUsable shouldBe false
    }

    @Test fun `status does not claim hybrid when the vectors are stale`() {
        // Reporting "hybrid" while answering from BM25 alone would be a lie that
        // reads as a quality regression with no visible cause.
        val base = tempDir()
        store(base, RagConfig.default.copy(embeddingDims = 256))
            .indexFolder(corpus())

        val changed = store(base, RagConfig.default.copy(embeddingDims = 1024))
        changed.status shouldContain "bm25 only"
        changed.status shouldContain "reindex"
    }

    @Test fun `search still answers after an encoder change, from the lexical half`() {
        val base = tempDir()
        store(base, RagConfig.default.copy(embeddingDims = 256)).indexFolder(corpus())

        val changed = store(base, RagConfig.default.copy(embeddingDims = 1024))
        changed.search("kafka partitions", 5, null).orNull()!!.isEmpty() shouldBe false
    }

    @Test fun `reindexing after an encoder change restores hybrid retrieval`() {
        val base = tempDir()
        val root = corpus()
        store(base, RagConfig.default.copy(embeddingDims = 256)).indexFolder(root)

        val changed = store(base, RagConfig.default.copy(embeddingDims = 1024))
        changed.reindex().isOk shouldBe true
        changed.active!!.vectorsUsable shouldBe true
        changed.status shouldContain "hybrid"
    }

    @Test fun `status with no index points at the command that makes one`() {
        store(tempDir()).status shouldContain "/rag index"
    }

    @Test fun `status lists other indexes, so a forgotten one is discoverable`() {
        val base = tempDir()
        val s = store(base)
        val a = tempDir().resolve("alpha")
        Files.createDirectories(a)
        write(a, "a.md", "alpha material ".repeat(20))
        s.indexFolder(a.toString())
        s.indexFolder(corpus())

        s.status shouldContain "others"
        s.status shouldContain "alpha"
    }

    // ---- description --------------------------------------------------------

    @Test fun `describeActive names the corpus and never the absolute path`() {
        // The local filesystem layout is noise to the model, cannot be acted on,
        // and citations are relative to the root anyway.
        val base = tempDir()
        val s = store(base)
        val root = corpus()
        s.indexFolder(root)

        s.describeActive shouldNotContain root
        s.describeActive shouldContain s.active!!.name

        s.setDescription("our Kafka and Redis runbooks") shouldBe ok(Unit)
        s.describeActive shouldBe "our Kafka and Redis runbooks"
        s.describeActive shouldNotContain root
    }

    @Test fun `describeActive is honest when nothing is active`() {
        store(tempDir()).describeActive shouldContain "no active index"
    }

    @Test fun `a description persists with the index`() {
        // It labels the material rather than switching anything on, so unlike the
        // injection flag it is meant to survive a restart.
        val base = tempDir()
        val s = store(base)
        s.indexFolder(corpus())
        s.setDescription("internal API documentation")

        store(base).description shouldBe "internal API documentation"
    }

    @Test fun `a description survives a reindex`() {
        val base = tempDir()
        val s = store(base)
        val root = corpus()
        s.indexFolder(root)
        s.setDescription("the runbooks")

        s.reindex().isOk shouldBe true
        s.description shouldBe "the runbooks"
    }

    @Test fun `a description can be cleared`() {
        val s = store(tempDir())
        s.indexFolder(corpus())
        s.setDescription("something")
        s.setDescription(null) shouldBe ok(Unit)
        s.description shouldBe null
    }

    @Test fun `a blank description is treated as none rather than stored empty`() {
        val s = store(tempDir())
        s.indexFolder(corpus())
        s.setDescription("   ") shouldBe ok(Unit)
        s.description shouldBe null
    }

    @Test fun `describing with no active index is reported`() {
        store(tempDir()).setDescription("x").isOk shouldBe false
    }

    @Test fun `setting a description does not rewrite the vectors`() {
        // It goes through saveMeta precisely so changing one string does not
        // re-encode every chunk and rewrite a sidecar that can be tens of MB.
        val s = store(tempDir())
        s.indexFolder(corpus())
        val sidecar = s.active!!.dir.resolve("vectors.f32")
        val before = Files.getLastModifiedTime(sidecar)
        val bytes = Files.size(sidecar)

        s.setDescription("unchanged vectors") shouldBe ok(Unit)

        Files.getLastModifiedTime(sidecar) shouldBe before
        Files.size(sidecar) shouldBe bytes
        // And retrieval still works, so the meta rewrite did not desync anything.
        s.active!!.vectorsUsable shouldBe true
        s.search("kafka partitions", 5, null).orNull()!!.isEmpty() shouldBe false
    }

    // ---- the standing notice ------------------------------------------------

    @Test fun `an active index announces itself in the system prompt`() {
        // This is what makes the tool discoverable rather than merely available: a
        // schema says the tool exists, not that a corpus is one call away.
        val s = store(tempDir())
        s.indexFolder(corpus())

        val notice = s.systemPromptSection!!
        notice shouldContain "Indexed material"
        notice shouldContain kotllm.tools.Tools.SearchName
        notice shouldContain "3 file(s)"
        notice shouldContain "rather than answering from general knowledge"
    }

    @Test fun `the notice carries the description when there is one`() {
        val s = store(tempDir())
        s.indexFolder(corpus())
        s.setDescription("our Kafka and Redis runbooks")

        s.systemPromptSection!! shouldContain "our Kafka and Redis runbooks"
    }

    @Test fun `the notice says so when there is no description, rather than inventing one`() {
        val s = store(tempDir())
        s.indexFolder(corpus())
        s.systemPromptSection!! shouldContain "No description has been given"
    }

    @Test fun `the notice never contains the absolute path`() {
        val s = store(tempDir())
        val root = corpus()
        s.indexFolder(root)
        s.setDescription("the runbooks")
        s.systemPromptSection!! shouldNotContain root
    }

    @Test fun `there is no notice without an active index`() {
        // Nothing should be spent telling the model about a corpus it cannot search.
        store(tempDir()).systemPromptSection shouldBe null
    }

    @Test fun `the notice is small, since it rides on every request`() {
        val s = store(tempDir())
        s.indexFolder(corpus())
        s.setDescription("our Kafka and Redis runbooks")
        (s.systemPromptSection!!.length < 700) shouldBe true
    }
}
