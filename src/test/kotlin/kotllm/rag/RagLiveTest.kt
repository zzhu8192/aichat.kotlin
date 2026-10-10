package kotllm.rag

import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths as JPaths

import kotlin.test.Test
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Disabled
import org.junit.jupiter.api.TestInstance
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldStartWith
import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.collections.shouldNotContain

import kotllm.*
import kotllm.config.Config
import kotllm.config.Paths

/** Retrieval against this repository, and then against the live model.
  *
  * Excluded from `mvn test`; run with `mvn -Plive test`. The indexing half
  * needs no credentials but walks a real tree, which is the point: a corpus
  * built from temp files with invented content cannot show that the
  * eligibility rules, the chunker and BM25 agree with each other on real
  * source. The endpoint half cancels rather than fails without a config.
  *
  * Every path literal below names a file in *this* tree. That is load-bearing
  * and it is why the suite was disabled for so long: the assertions were
  * inherited from the Scala project and pointed at `src/main/scala/...`, so
  * they failed for a reason that had nothing to do with retrieval.
  */
@Tag("Live")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class RagLiveTest {

    /** This repository. Tests run from the project root. */
    private val repo: Path = JPaths.get(".").toAbsolutePath().normalize()

    private fun scratch(): Path = Files.createTempDirectory("kotllm-rag-live")

    private fun indexRepo(config: RagConfig = RagConfig.default): Pair<RagStore, IndexReport> {
        val store = RagStore(scratch(), config) { emptyMap() }
        // The message, not `!!`: indexing refuses for several readable reasons and
        // a NullPointerException names none of them.
        val report = store.indexFolder(repo.toString())
            .getOrElse { e -> throw AssertionError("could not index $repo: $e") }
        return Pair(store, report)
    }

    @Tag("Live")
    @Test fun `indexing this repository produces a usable index`() {
        val (store, report) = indexRepo()

        // Sanity bounds rather than exact counts, which change with every commit.
        (report.filesIndexed > 40) shouldBe true
        (report.chunksTotal > 100) shouldBe true
        (store.active != null) shouldBe true

        // target/ is skipped, so the shaded jar and the class files never appear.
        val paths = store.active!!.chunks.map { it.path }
        paths.forEach { p ->
            // clue: "$p should not have been indexed: "
            p.startsWith("target/") shouldBe false
            p.startsWith(".git/") shouldBe false
        }
        paths shouldContain "README.md"
    }

    @Tag("Live")
    @Test fun `a distinctive identifier reaches the file that defines it`() {
        // The real test of the code-aware chunker and the identifier-preserving
        // tokenizer: an invented corpus cannot fail this.
        //
        // Asserts membership rather than rank 1 on purpose. These identifiers are
        // also named in this repository's own docs and specs, and those are real
        // documents that legitimately match -- demanding the definition rank first
        // would be asserting something retrieval does not promise.
        val (store, _) = indexRepo()

        store.search("declineToolCalls", 8, null).orNull()!!.map { it.first.path } shouldContain
            "src/main/kotlin/kotllm/Runner.kt"
        store.search("fixedOverheadTokens", 8, null).orNull()!!.map { it.first.path } shouldContain
            "src/main/kotlin/kotllm/Runner.kt"
    }

    @Tag("Live")
    @Test fun `an underscore-separated config key is found by either half`() {
        val (store, _) = indexRepo()

        store.search("rag_inject_top_k", 5, null).orNull()!!.isEmpty() shouldBe false
        // The underscore parts are emitted too, so a partial term still hits.
        store.search("inject", 8, null).orNull()!!.isEmpty() shouldBe false
    }

    @Tag("Live")
    @Test fun `a file is reachable by its name`() {
        // Deliberately not `Compactor.kt`, which this suite used to search for:
        // the assertions and comments in these test files now *contain* that
        // literal, so the query finds the tests talking about the file instead of
        // the file. Same trap the nonsense queries below are generated to avoid,
        // reached from the other direction.
        val (store, _) = indexRepo()
        val hits = store.search("VectorIndex.kt", 12, null).orNull()!!
        hits.map { it.first.path } shouldContain "src/main/kotlin/kotllm/rag/VectorIndex.kt"
    }

    @Tag("Live")
    @Test fun `no single file monopolises the results`() {
        // Before per-file capping, `Compactor` returned five consecutive chunks of
        // the compactor's own spec and pushed the definition out of a top-five
        // window.
        val (store, _) = indexRepo()
        val paths = store.search("Compactor.kt", 8, null).orNull()!!.map { it.first.path }
        val worst = paths.groupingBy { it }.eachCount().values.max()
        // clue: "results were: ${paths.joinToString(", ")}"
        (worst <= RagConfig.default.maxPerFile) shouldBe true
        (paths.distinct().size > 1) shouldBe true
    }

    @Tag("Live")
    @Test fun `a path filter confines results to a subtree of real source`() {
        val (store, _) = indexRepo()
        val hits = store.search("tool approval", 10, "src/main/kotlin/kotllm/tools/").orNull()!!
        hits.isEmpty() shouldBe false
        hits.map { it.first.path }.forEach { it shouldStartWith "src/main/kotlin/kotllm/tools/" }
    }

    @Tag("Live")
    @Test fun `citations point at line ranges that really contain the match`() {
        // A citation the user cannot verify is worse than no citation, so this
        // checks the line arithmetic against the file on disk rather than trusting
        // the chunker's own bookkeeping.
        val (store, _) = indexRepo()
        val (chunk, _) = store.search("declineToolCalls", 3, null).orNull()!!.first()

        val lines = String(
            Files.readAllBytes(repo.resolve(chunk.path)),
            StandardCharsets.UTF_8
        ).lineSequence().toList()

        (chunk.lineStart >= 1) shouldBe true
        (chunk.lineEnd <= lines.size) shouldBe true
        (chunk.lineEnd >= chunk.lineStart) shouldBe true
        val slice = lines.subList(chunk.lineStart - 1, chunk.lineEnd).joinToString("\n")
        slice shouldContain "declineToolCalls"
    }

    @Tag("Live")
    @Test fun `a second index run over an unchanged tree does no work`() {
        val store = RagStore(scratch(), RagConfig.default) { emptyMap() }
        store.indexFolder(repo.toString()).orNull()!!
        val again = store.indexFolder(repo.toString()).orNull()!!

        again.scan.added.isEmpty() shouldBe true
        again.scan.changed.isEmpty() shouldBe true
        again.chunksAdded shouldBe 0
        again.scan.unchanged.isEmpty() shouldBe false
    }

    // ---- hybrid retrieval --------------------------------------------------

    @Tag("Live")
    @Test fun `indexing this repository builds vectors for every chunk`() {
        val (store, _) = indexRepo()
        val idx = store.active!!

        idx.vectorsUsable shouldBe true
        idx.vectors!!.size shouldBe idx.chunkCount
        idx.meta!!.embeddingModel shouldBe RagConfig.default.embedder!!.id
    }

    @Tag("Live")
    @Test fun `the dense half finds a morphological variant that bm25 misses`() {
        // The measured justification for the second retriever, re-measured on
        // this tree. `chunking` sends BM25 to README.md and docs/rag.md -- prose
        // that uses the word -- and it never reaches `Chunker.kt`, because BM25
        // matches terms rather than stems. The dense half gets there.
        //
        // The original pair was `compaction` -> `Compactor.kt`, and it no longer
        // holds: this repository has grown so much prose *about* compaction that
        // the source file is outside the top ten on both halves. That is a
        // property of the corpus, not a regression in retrieval.
        val (store, _) = indexRepo()
        val idx = store.active!!

        val lexical = idx.searchLexical("chunking", 8).map { it.first.path }
        lexical shouldNotContain "src/main/kotlin/kotllm/rag/Chunker.kt"

        val dense = idx.searchDense("chunking", 8).map { it.first.path }
        dense shouldContain "src/main/kotlin/kotllm/rag/Chunker.kt"

        // And the fused result surfaces it, which is what a user actually gets.
        idx.search("chunking", 10).map { it.first.path } shouldContain
            "src/main/kotlin/kotllm/rag/Chunker.kt"
    }

    @Tag("Live")
    @Test fun `a typo in an identifier still reaches its definition`() {
        val (store, _) = indexRepo()
        val idx = store.active!!
        idx.searchDense("declineToolCals", 8).map { it.first.path } shouldContain
            "src/main/kotlin/kotllm/Runner.kt"
    }

    /** Queries that must match nothing in this repository.
      *
      * *Generated*, not written down. Every literal attempt at this failed,
      * because the literal became part of the corpus:
      *
      *   1. a literal in this spec -- found by BM25 at score 4.4
      *   2. still present in a throwaway diagnostic spec -- found at 12.3
      *   3. quoted in README.md and CLAUDE.md as an illustration -- found again
      *
      * A fixed seed keeps runs reproducible; sixteen random letters cannot
      * plausibly occur in the repository or in anything written about it.
      */
    private fun nonsenseQueries(): List<String> {
        val rng = java.util.Random(20260915L)
        val alphabet = "bcdfghjklmnpqrstvwxz"
        return (1..3).map { (1..16).map { alphabet[rng.nextInt(alphabet.length)] }.joinToString("") }
    }

    @Tag("Live")
    @Test fun `gibberish retrieves nothing, on a real corpus`() {
        // Measured: nonsense peaks around 0.17 cosine on this corpus and wanted
        // matches bottom out around 0.24, which is what the 0.20 floor sits
        // between. Without the floor the dense half would return its nearest
        // neighbours here, and fusion would give them confident ranks.
        val (store, _) = indexRepo()
        nonsenseQueries().forEach { q ->
            // clue: "$q: "
            store.search(q, 8, null).orNull()!!.isEmpty() shouldBe true
        }
    }

    @Tag("Live")
    @Test fun `the similarity floor still separates wanted from nonsense`() {
        // Asserts the margin directly, so an encoder change that quietly erodes it
        // fails here rather than showing up as junk in answers.
        val (store, _) = indexRepo()
        val idx = store.active!!
        val floor = RagConfig.default.minSimilarity

        val wanted = listOf(
            "declineToolCalls", "rate limiter rolling window", "Compactor.kt",
            "compaction", "reciprocal rank fused"
        ).flatMap { q -> idx.searchDense(q, 1, 0.0).map { it.second } }
        val junk = nonsenseQueries().flatMap { q -> idx.searchDense(q, 1, 0.0).map { it.second } }

        // clue: "wanted=${wanted.min()}, junk=${junk.max()}, floor=$floor: "
        (junk.max() < floor) shouldBe true
        (wanted.min() > floor) shouldBe true
    }

    @Tag("Live")
    @Test fun `an encoder change invalidates this repository's vectors rather than misusing them`() {
        val base = scratch()
        val store = RagStore(base, RagConfig.default) { emptyMap() }
        store.indexFolder(repo.toString()).orNull()!!

        val other = RagStore(base, RagConfig.default.copy(embeddingDims = 1024)) { emptyMap() }
        other.active!!.vectorsUsable shouldBe false
        other.active!!.vectorProblem!! shouldContain "reindex"
        // Still answers, from BM25 alone.
        other.search("declineToolCalls", 5, null).orNull()!!.isEmpty() shouldBe false
    }

    @Tag("Live")
    @Test fun `hybrid search over a real tree stays fast enough to be interactive`() {
        // Brute-force cosine is the claim; this is the measurement behind it.
        val (store, _) = indexRepo()
        val idx = store.active!!

        val queries = listOf(
            "declineToolCalls", "compaction", "rate limiter", "chunk overlap",
            "terminal status region", "tool approval allowlist"
        )
        queries.forEach { q -> idx.search(q, 8) } // warm the JIT

        val start = System.nanoTime()
        queries.forEach { q -> idx.search(q, 8) }
        val perQueryMs = (System.nanoTime() - start) / 1e6 / queries.size

        // clue: "${perQueryMs}ms per hybrid query over ${idx.chunkCount} chunks: "
        (perQueryMs < 150.0) shouldBe true
        println("hybrid search over ${idx.chunkCount} chunks: ${"%.1f".format(perQueryMs)}ms per query")
    }

    // ---- the live model ----------------------------------------------------

    /** Cancels rather than fails when there is no usable config, which is what
      * keeps `-Plive` runnable on a machine with no credentials. */
    private val setup: Config? by lazy {
        Config.load(Paths.configFile()).orNull()?.takeIf { config ->
            config.resolveModel(null).orNull()?.client?.apiKey != null
        }
    }

    @Tag("Live")
    @Test fun `the model calls search and answers from this repository`() {
        // The end of the chain the rest of this file tests one link at a time:
        // an index exists, the tool is offered, the model decides on its own to
        // call it, and the answer comes back with a citation. Nothing in the
        // prompt tells it to search.
        val config = setup
        org.junit.jupiter.api.Assumptions.assumeTrue(config != null, "no usable live config")

        val withRag = config!!.copy(
            functionCalling = true,
            useTools = "all",
            toolApproval = kotllm.tools.ApprovalMode.Auto,
            rateLimit = null
        )

        val built = Runner.build(withRag, null, "Answer from the indexed repository and cite paths.")
        org.junit.jupiter.api.Assumptions.assumeTrue(built.isOk) { built.fold({ it }, { "" }) }
        val runner = built.orNull()!!

        // Indexed through the runner's own store, not a scratch one: the index
        // directory comes from `Paths.ragDir()` rather than from config, so a
        // scratch store would be writing somewhere the runner never looks.
        // Dropped in the finally, because this is the user's real rag directory.
        val report = runner.rag!!.indexFolder(repo.toString()).orNull()!!

        try {
            // Offered only while an index is active, so this is also the guard on
            // that wiring.
            runner.activeTools.map { it.name } shouldContain "search"

            runner.ask("Which file defines declineToolCalls, and what does it do?").isOk shouldBe true

            val called = runner.session.ledger.toolCalls().map { it.name }
            // clue: "tools called: ${called.joinToString(", ")}"
            called shouldContain "search"

            val answer = runner.session.messages().last().text
            answer shouldContain "Runner"
        } finally {
            runner.rag?.drop(report.name)
            runner.close()
        }
    }
}
