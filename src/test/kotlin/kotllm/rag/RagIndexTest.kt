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
import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.collections.shouldNotContain

import kotllm.*

class RagIndexTest {

    private fun tempDir(): Path = Files.createTempDirectory("kotllm-rag")

    private fun write(dir: Path, rel: String, content: String): Path {
        val p = dir.resolve(rel)
        Files.createDirectories(p.parent)
        Files.write(p, content.toByteArray(StandardCharsets.UTF_8))
        return p
    }

    private fun freshIndex(): Pair<Path, RagIndex> {
        val base = tempDir()
        return Pair(base, RagIndex.resolve(base, "test"))
    }

    private val cfg = RagConfig.default

    private fun doIndex(idx: RagIndex, root: Path) =
        Rag.index(idx, root, cfg, emptySet(), Rag::readText)

    // ---- eligibility -------------------------------------------------------

    @Test fun `source and prose extensions are indexed`() {
        val root = tempDir()
        listOf("a.scala", "b.py", "c.md", "d.txt", "e.java", "f.go", "g.sql", "h.yaml").forEach { n ->
            Rag.isEligible(root, root.resolve(n), cfg, emptySet()) shouldBe true
        }
    }

    @Test fun `known binary formats are refused by name, with a reason`() {
        val root = tempDir()
        listOf("a.pdf", "b.docx", "c.class", "d.jar", "e.zip", "f.tar", "g.exe", "h.bz2", "i.png",
            "j.parquet", "k.safetensors").forEach { n ->
            val why = Rag.eligibility(root, root.resolve(n), cfg, emptySet())
            // clue: "$n: "
            why.isOk shouldBe false
            (why as Outcome.Err).message shouldContain "binary"
        }
    }

    @Test fun `a binary format becomes eligible when a document loader is configured`() {
        // The conversion already exists for /file input, so refusing it here would
        // be inconsistent.
        val root = tempDir()
        Rag.isEligible(root, root.resolve("a.pdf"), cfg, setOf("pdf")) shouldBe true
        Rag.isEligible(root, root.resolve("a.docx"), cfg, setOf("pdf")) shouldBe false
    }

    @Test fun `extensionless build files are indexed by name`() {
        val root = tempDir()
        listOf("Makefile", "Dockerfile", "README", "LICENSE", ".gitignore").forEach { n ->
            // clue: "$n: "
            Rag.isEligible(root, root.resolve(n), cfg, emptySet()) shouldBe true
        }
    }

    @Test fun `an unknown extension is refused, naming it`() {
        val root = tempDir()
        ((Rag.eligibility(root, root.resolve("weird.qqq"), cfg, emptySet())) as Outcome.Err).message shouldContain ".qqq"
    }

    @Test fun `extra extensions from config are honoured`() {
        val root = tempDir()
        Rag.isEligible(root, root.resolve("a.qqq"), cfg.copy(extraExtensions = setOf("qqq")), emptySet()) shouldBe true
    }

    @Test fun `build output and dot-directories are skipped`() {
        val root = tempDir()
        listOf("target/X.scala", "node_modules/p/i.js", ".git/config", "build/out.java",
            "__pycache__/m.py").forEach { rel ->
            // clue: "$rel: "
            Rag.isEligible(root, root.resolve(rel), cfg, emptySet()) shouldBe false
        }
        // But a normal nested path is fine.
        Rag.isEligible(root, root.resolve("src/main/A.scala"), cfg, emptySet()) shouldBe true
    }

    // ---- indexing ----------------------------------------------------------

    @Test fun `indexing a folder produces chunks and a metastore`() {
        val root = tempDir()
        write(root, "a.md", "# Title\n\n" + "prose ".repeat(100))
        write(root, "src/B.scala", "class B {\n  def go(): Int = 1\n}\n")
        val (_, idx) = freshIndex()

        val report = doIndex(idx, root).orNull()!!
        report.filesIndexed shouldBe 2
        (report.chunksTotal > 0) shouldBe true
        idx.exists shouldBe true
        idx.meta!!.root shouldBe root.toAbsolutePath().normalize().toString()
    }

    @Test fun `paths are stored relative with forward slashes, so an index is portable`() {
        val root = tempDir()
        write(root, "src/main/A.scala", "object A")
        val (_, idx) = freshIndex()
        doIndex(idx, root)

        idx.chunks.map { it.path } shouldContain "src/main/A.scala"
        idx.chunks.forEach { it.path shouldNotContain "\\" }
    }

    @Test fun `an unchanged file is not re-chunked on the second run`() {
        val root = tempDir()
        write(root, "a.md", "stable content ".repeat(50))
        val (_, idx) = freshIndex()

        doIndex(idx, root)
        val second = doIndex(idx, root).orNull()!!

        second.scan.unchanged.size shouldBe 1
        second.scan.added.isEmpty() shouldBe true
        second.chunksAdded shouldBe 0
    }

    @Test fun `an edited file is re-chunked`() {
        val root = tempDir()
        val f = write(root, "a.md", "original text ".repeat(30))
        val (_, idx) = freshIndex()
        doIndex(idx, root)

        // Size differs, so the size-and-mtime test catches it regardless of clock
        // granularity.
        Files.write(f, "rewritten entirely with different words ".repeat(40).toByteArray(StandardCharsets.UTF_8))
        val second = doIndex(idx, root).orNull()!!

        second.scan.changed.size shouldBe 1
        idx.chunks.joinToString("") { it.text } shouldContain "rewritten"
        idx.chunks.joinToString("") { it.text } shouldNotContain "original"
    }

    @Test fun `a deleted file has its chunks removed`() {
        // Without this the index keeps answering with text no longer on disk,
        // which is worse than having no index.
        val root = tempDir()
        write(root, "keep.md", "keeper content ".repeat(20))
        val doomed = write(root, "gone.md", "doomed content ".repeat(20))
        val (_, idx) = freshIndex()
        doIndex(idx, root)
        idx.chunks.map { it.path }.distinct().size shouldBe 2

        Files.delete(doomed)
        val second = doIndex(idx, root).orNull()!!

        second.scan.removed shouldBe listOf("gone.md")
        idx.chunks.map { it.path }.distinct() shouldBe listOf("keep.md")
        idx.search("doomed", 5).isEmpty() shouldBe true
    }

    @Test fun `a rename is handled as a delete plus an add`() {
        val root = tempDir()
        val f = write(root, "old.md", "movable content ".repeat(20))
        val (_, idx) = freshIndex()
        doIndex(idx, root)

        Files.move(f, root.resolve("new.md"))
        val second = doIndex(idx, root).orNull()!!

        second.scan.removed shouldBe listOf("old.md")
        second.scan.added.map { it.fileName.toString() } shouldBe listOf("new.md")
        idx.chunks.map { it.path }.distinct() shouldBe listOf("new.md")
    }

    @Test fun `a binary file placed among sources is skipped and reported`() {
        val root = tempDir()
        write(root, "a.md", "text ".repeat(20))
        Files.write(root.resolve("blob.bin"), byteArrayOf(1, 2, 0, 3))
        val (_, idx) = freshIndex()

        val report = doIndex(idx, root).orNull()!!
        report.filesIndexed shouldBe 1
    }

    @Test fun `a text-extension file that is really binary is skipped with a reason`() {
        val root = tempDir()
        Files.write(root.resolve("lying.md"), byteArrayOf('a'.code.toByte(), 0, 'b'.code.toByte()))
        val (_, idx) = freshIndex()

        val report = doIndex(idx, root).orNull()!!
        report.skipped.joinToString("") shouldContain "looks binary"
    }

    @Test fun `indexing a path that is not a directory is an error`() {
        val root = tempDir()
        val f = write(root, "a.md", "x")
        val (_, idx) = freshIndex()

        Rag.index(idx, f, cfg, emptySet(), Rag::readText).isOk shouldBe false
    }

    @Test fun `progress is reported per file`() {
        val root = tempDir()
        (1..5).forEach { i -> write(root, "f$i.md", "content $i ".repeat(20)) }
        val (_, idx) = freshIndex()

        val seen = mutableListOf<Pair<Int, Int>>()
        Rag.index(idx, root, cfg, emptySet(), Rag::readText) { d, t -> seen.add(Pair(d, t)) }

        seen.size shouldBe 5
        seen.last() shouldBe Pair(5, 5)
    }

    // ---- persistence -------------------------------------------------------

    @Test fun `an index round-trips through disk`() {
        val root = tempDir()
        write(root, "a.md", "persistent content about kafka streams ".repeat(10))
        val (base, idx) = freshIndex()
        doIndex(idx, root)
        val before = idx.chunks.size

        val reloaded = RagIndex.resolve(base, "test")
        reloaded.load() shouldBe ok(Unit)
        reloaded.chunks.size shouldBe before
        reloaded.meta!!.profile shouldBe "auto"
        reloaded.search("kafka", 3).isEmpty() shouldBe false
    }

    @Test fun `loading a missing index reports it rather than throwing`() {
        val (base, _) = freshIndex()
        RagIndex.resolve(base, "nope").load().isOk shouldBe false
    }

    @Test fun `deleting an index removes it from disk`() {
        val root = tempDir()
        write(root, "a.md", "content ".repeat(20))
        val (base, idx) = freshIndex()
        doIndex(idx, root)
        idx.exists shouldBe true

        idx.delete()
        idx.exists shouldBe false
        RagIndex.list(base).isEmpty() shouldBe true
    }

    @Test fun `indexes are discoverable by name`() {
        val root = tempDir()
        write(root, "a.md", "content ".repeat(20))
        val base = tempDir()
        Rag.index(RagIndex.resolve(base, "alpha"), root, cfg, emptySet(), Rag::readText)
        Rag.index(RagIndex.resolve(base, "beta"), root, cfg, emptySet(), Rag::readText)

        RagIndex.list(base) shouldBe listOf("alpha", "beta")
    }

    @Test fun `a name is derived from the folder, sanitised`() {
        RagIndex.nameFor(Paths.get("/some/path/My Docs")) shouldBe "my-docs"
        RagIndex.nameFor(Paths.get("/some/path/kotllm.scala")) shouldBe "kotllm.scala"
    }

    // ---- searching -----------------------------------------------------------

    @Test fun `search returns chunks with scores and citations`() {
        val root = tempDir()
        write(root, "docs/kafka.md", "Kafka partitions are ordered logs. ".repeat(20))
        write(root, "docs/redis.md", "Redis is an in-memory key value store. ".repeat(20))
        val (_, idx) = freshIndex()
        doIndex(idx, root)

        val hits = idx.search("kafka partitions", 5)
        hits.isEmpty() shouldBe false
        hits.first().first.path shouldBe "docs/kafka.md"
        (hits.first().second > 0.0) shouldBe true
        hits.first().first.citation shouldStartWith "docs/kafka.md:"
    }

    @Test fun `search finds a file by its name even when the body never says it`() {
        // The path is indexed alongside the text for exactly this.
        val root = tempDir()
        write(root, "AuthController.java", "class X { void handle() { return; } }")
        val (_, idx) = freshIndex()
        doIndex(idx, root)

        idx.search("AuthController", 5).isEmpty() shouldBe false
    }

    @Test fun `a path filter narrows results to a subtree`() {
        val root = tempDir()
        write(root, "docs/topic.md", "shared keyword appears here ".repeat(20))
        write(root, "src/topic.scala", "// shared keyword appears here\nobject T\n")
        val (_, idx) = freshIndex()
        doIndex(idx, root)

        (idx.search("shared keyword", 10).map { it.first.path }.distinct().size > 1) shouldBe true
        idx.search("shared keyword", 10, "docs/").map { it.first.path }.forEach { it shouldStartWith "docs/" }
    }

    @Test fun `searching an empty index returns nothing rather than failing`() {
        val (_, idx) = freshIndex()
        idx.search("anything", 5).isEmpty() shouldBe true
    }

    // ---- vectors and hybrid retrieval --------------------------------------

    private fun vectorIndex(base: Path, dims: Int = 512): RagIndex =
        RagIndex.resolve(base, "test", HashingEmbedder(dims))

    @Test fun `vectors are built alongside the chunks when an encoder is configured`() {
        val root = tempDir()
        write(root, "a.md", "content about widgets ".repeat(30))
        val base = tempDir()
        val idx = vectorIndex(base)
        doIndex(idx, root)

        idx.vectorsUsable shouldBe true
        idx.vectors!!.size shouldBe idx.chunkCount
        idx.vectors!!.dimensions shouldBe 512
        Files.isRegularFile(idx.dir.resolve("vectors.f32")) shouldBe true
    }

    @Test fun `the metadata records the encoder that actually ran`() {
        val root = tempDir()
        write(root, "a.md", "content ".repeat(30))
        val idx = vectorIndex(tempDir(), 256)
        doIndex(idx, root)

        idx.meta!!.embeddingModel shouldBe "hashing-v1:d256:g3-5"
        idx.meta!!.dimensions shouldBe 256
    }

    @Test fun `no encoder means no sidecar and no claim of one`() {
        val root = tempDir()
        write(root, "a.md", "content ".repeat(30))
        val (_, idx) = freshIndex()
        doIndex(idx, root)

        idx.vectorsUsable shouldBe false
        idx.vectorStatus shouldContain "bm25 only"
        idx.meta!!.embeddingModel shouldBe null
        Files.isRegularFile(idx.dir.resolve("vectors.f32")) shouldBe false
    }

    @Test fun `vectors survive a reload`() {
        val root = tempDir()
        write(root, "a.md", "durable content about widgets ".repeat(30))
        val base = tempDir()
        doIndex(vectorIndex(base), root)

        val reloaded = vectorIndex(base)
        reloaded.load() shouldBe ok(Unit)
        reloaded.vectorsUsable shouldBe true
        reloaded.vectors!!.size shouldBe reloaded.chunkCount
        reloaded.searchDense("widgets", 3).isEmpty() shouldBe false
    }

    @Test fun `changing the encoder invalidates the vectors instead of using them`() {
        // Vectors from another encoder have the right shape and no meaning, so
        // they would rank nonsense with nothing in the output to explain it.
        val root = tempDir()
        write(root, "a.md", "content ".repeat(30))
        val base = tempDir()
        doIndex(vectorIndex(base, 256), root)

        val different = vectorIndex(base, 512)
        different.load() shouldBe ok(Unit)
        different.vectorsUsable shouldBe false
        val why = different.vectorProblem!!
        why shouldContain "d256"
        why shouldContain "d512"
        why shouldContain "reindex"
    }

    @Test fun `an index built before vectors existed says they are not built yet`() {
        val root = tempDir()
        write(root, "a.md", "content ".repeat(30))
        val base = tempDir()
        doIndex(RagIndex.resolve(base, "test"), root)

        val withEncoder = vectorIndex(base)
        withEncoder.load() shouldBe ok(Unit)
        withEncoder.vectorsUsable shouldBe false
        withEncoder.vectorProblem!! shouldContain "not built yet"
    }

    @Test fun `turning the encoder off removes the stale sidecar`() {
        // Left behind, it would be read as authoritative the next time an encoder
        // was configured, against chunks that had since changed.
        val root = tempDir()
        write(root, "a.md", "content ".repeat(30))
        val base = tempDir()
        doIndex(vectorIndex(base), root)
        Files.isRegularFile(base.resolve("test").resolve("vectors.f32")) shouldBe true

        doIndex(RagIndex.resolve(base, "test"), root.toAbsolutePath())
        Files.isRegularFile(base.resolve("test").resolve("vectors.f32")) shouldBe false
    }

    @Test fun `search still answers from bm25 when the vectors are unusable`() {
        val root = tempDir()
        write(root, "a.md", "findable widgets content ".repeat(30))
        val base = tempDir()
        doIndex(vectorIndex(base, 256), root)

        val mismatched = vectorIndex(base, 1024)
        mismatched.load()
        mismatched.vectorsUsable shouldBe false
        mismatched.search("widgets", 5).isEmpty() shouldBe false
    }

    @Test fun `the dense half recovers a morphological variant that bm25 cannot match`() {
        // The entire justification for pairing a second retriever with BM25. The
        // corpus says `compaction`; the query says `compactor`. No exact token
        // matches, so BM25 has nothing to score.
        val root = tempDir()
        write(
            root, "notes.md",
            "Compaction of the conversation history happens before the request is measured. ".repeat(10)
        )
        write(root, "other.md", "Terminal cursor addressing and scroll regions. ".repeat(10))
        val base = tempDir()
        val idx = vectorIndex(base, 2048)
        doIndex(idx, root)

        idx.searchLexical("compactor", 5).isEmpty() shouldBe true
        idx.searchDense("compactor", 5).map { it.first.path } shouldContain "notes.md"
        // And the hybrid path surfaces it, which is what a user actually gets.
        idx.search("compactor", 5).map { it.first.path } shouldContain "notes.md"
    }

    @Test fun `fusion keeps what both halves agree on at the top`() {
        val root = tempDir()
        write(root, "widgets.md", "widgets are described at length here ".repeat(30))
        write(root, "gadgets.md", "gadgets are something else entirely ".repeat(30))
        val idx = vectorIndex(tempDir(), 2048)
        doIndex(idx, root)

        idx.search("widgets", 4).first().first.path shouldBe "widgets.md"
    }

    @Test fun `a nonsense query returns nothing, because the dense half has a floor`() {
        // Without the floor the dense half returns its nearest neighbours for any
        // input at all, and fusion then gives them confident ranks.
        val root = tempDir()
        write(root, "a.md", "kafka partitions are ordered logs ".repeat(30))
        val idx = vectorIndex(tempDir(), 2048)
        doIndex(idx, root)

        idx.search("zzzznomatchquery", 5).isEmpty() shouldBe true
    }

    @Test fun `the floor is adjustable, and a lower one never returns fewer matches`() {
        // Monotonicity is the actual contract. Asserting that a floor of zero
        // admits *something* would be wrong: with signed hashing an unrelated
        // query can score negative, so zero already excludes it.
        val root = tempDir()
        write(root, "a.md", "kafka partitions are ordered append only logs ".repeat(30))
        val idx = vectorIndex(tempDir(), 2048)
        doIndex(idx, root)

        val q = "partitioned"
        val counts = listOf(0.0, 0.1, 0.2, 0.4, 0.9).map { f -> idx.searchDense(q, 10, f).size }
        counts shouldBe counts.sortedDescending()
        // And the highest floor admits nothing, so the range really is exercised.
        counts.last() shouldBe 0
        (counts.first() > 0) shouldBe true
    }

    @Test fun `vectors stay aligned with chunks across an incremental reindex`() {
        // Row i must be chunk i. A stale sidecar after files change would
        // attribute every passage to the wrong file.
        val root = tempDir()
        write(root, "a.md", "alpha content about widgets ".repeat(20))
        write(root, "b.md", "beta content about gadgets ".repeat(20))
        val base = tempDir()
        val idx = vectorIndex(base, 2048)
        doIndex(idx, root)

        write(root, "c.md", "gamma content about sprockets ".repeat(20))
        Files.delete(root.resolve("a.md"))
        doIndex(idx, root)

        idx.vectors!!.size shouldBe idx.chunkCount
        idx.chunks.map { it.path }.distinct().sorted() shouldBe listOf("b.md", "c.md")
        // The surviving file is still found, and the deleted one is gone.
        idx.search("sprockets", 5).map { it.first.path } shouldContain "c.md"
        idx.search("alpha", 5).map { it.first.path } shouldNotContain "a.md"
    }

    // ---- result diversity --------------------------------------------------

    @Test fun `one file cannot monopolise the results`() {
        // BM25 ranks chunks, not files, so a large file mentioning a term in every
        // chunk floods the result set and buries the file actually named.
        val root = tempDir()
        // A big file that says the term constantly, in many chunks.
        write(root, "big.md", (1..40).joinToString("") { i -> "paragraph $i about widgets and widgets\n\n" })
        write(root, "small.md", "widgets are defined here ".repeat(20))
        val (_, idx) = freshIndex()
        doIndex(idx, root)

        val paths = idx.search("widgets", 6, null, maxPerFile = 2).map { it.first.path }
        (paths.count { it == "big.md" } <= 2) shouldBe true
        paths shouldContain "small.md"
    }

    @Test fun `capping reorders rather than truncates, so the count is unchanged`() {
        // Anything the cap holds back is appended in score order. A question whose
        // answer really does live in one file must still get that file's chunks.
        val root = tempDir()
        write(root, "only.md", (1..30).joinToString("") { i -> "section $i discusses widgets\n\n" })
        val (_, idx) = freshIndex()
        doIndex(idx, root)

        val capped = idx.search("widgets", 6, null, maxPerFile = 1)
        val uncapped = idx.search("widgets", 6, null, maxPerFile = 0)
        capped.size shouldBe uncapped.size
        capped.map { it.first.path }.distinct() shouldBe listOf("only.md")
    }

    @Test fun `results stay in descending score order within the capped head`() {
        val root = tempDir()
        (1..6).forEach { i -> write(root, "f$i.md", "widgets appear here in file $i ".repeat(20)) }
        val (_, idx) = freshIndex()
        doIndex(idx, root)

        val scores = idx.search("widgets", 6, null, maxPerFile = 1).map { it.second }
        scores shouldBe scores.sortedDescending()
    }

    @Test fun `a per-file cap of zero disables capping`() {
        val root = tempDir()
        // Paragraphs long enough to survive chunk packing, so this file really
        // does produce several chunks that a cap would have to hold back.
        write(root, "big.md", (1..12).joinToString("") { i -> "Section $i. ${"widgets ".repeat(90)}\n\n" })
        write(root, "small.md", "widgets ".repeat(30))
        val (_, idx) = freshIndex()
        doIndex(idx, root)
        (idx.chunks.count { it.path == "big.md" } > 3) shouldBe true

        // Uncapped, the dominant file takes the whole head.
        idx.search("widgets", 5, null, maxPerFile = 0).take(2).map { it.first.path }.distinct().size shouldBe 1

        // Capped, the head is shared. The tail may still be the dominant file,
        // because the cap reorders rather than truncates -- so the assertion is
        // about the head, which is the part that changes an answer.
        idx.search("widgets", 5, null, maxPerFile = 1).take(2).map { it.first.path }.distinct().size shouldBe 2
    }

    @Test fun `the cap composes with a path filter`() {
        val root = tempDir()
        write(root, "docs/big.md", (1..20).joinToString("") { i -> "part $i covers widgets\n\n" })
        write(root, "docs/other.md", "widgets described here ".repeat(20))
        write(root, "src/ignore.md", "widgets elsewhere ".repeat(20))
        val (_, idx) = freshIndex()
        doIndex(idx, root)

        val paths = idx.search("widgets", 6, "docs/", maxPerFile = 2).map { it.first.path }
        paths.forEach { it shouldStartWith "docs/" }
        (paths.count { it == "docs/big.md" } <= 2) shouldBe true
    }
}
