package kotllm.rag

import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path

// Wildcard: put(String, String), jsonObject, jsonPrimitive, jsonArray and the
// numeric accessors are extension functions/properties in this package.
import kotlinx.serialization.json.*

import kotllm.*

@OptIn(kotlinx.serialization.ExperimentalSerializationApi::class)
private val prettyJsonIndex = Json { prettyPrint = true; prettyPrintIndent = "  " }

/** What the metastore remembers about one indexed file.
  *
  * Size and mtime are the change test: cheap, and the failure mode -- an edit
  * that preserves both -- is rare enough to document rather than pay a content
  * hash on every file for.
  */
data class FileRecord(val path: String, val size: Long, val modified: Long, val chunks: Int)

/** What changed since the last index. */
data class ScanResult(
    val added: List<Path>,
    val changed: List<Path>,
    val removed: List<String>,
    val unchanged: List<String>
) {
    val hasWork: Boolean get() = added.isNotEmpty() || changed.isNotEmpty() || removed.isNotEmpty()
    val summary: String
        get() = "${added.size} new, ${changed.size} changed, ${removed.size} removed, ${unchanged.size} unchanged"
}

data class IndexMeta(
    val name: String,
    val root: String,
    val profile: String,
    /** Recorded so a later embedding run can detect a model change. Vectors
      * from a different model are numerically comparable but semantically
      * meaningless, which is silent garbage -- the worst failure available. */
    val embeddingModel: String?,
    val dimensions: Int?,
    /** What this corpus *is*, in the user's words, for the model's benefit.
      *
      * Persisted, unlike the injection switch: this is a label describing the
      * material, not a setting that spends context on every request, and
      * re-typing it every session would be absurd. */
    val description: String?,
    val files: List<FileRecord>
)

/** An on-disk index: chunks, the file metastore, the BM25 index over them, and
  * optionally dense vectors.
  *
  * Chunks persist as JSON lines and BM25 is rebuilt from them on load, which
  * costs milliseconds and avoids inventing a serialisation format for an
  * inverted index. Vectors cannot be rebuilt that cheaply, so they persist as a
  * binary float32 sidecar -- and because they can therefore be *stale*, load
  * validates them rather than trusting them.
  *
  * @param embedder when null the index is BM25 only: no vectors are written, and
  *                 an existing sidecar is ignored and removed on the next save.
  */
class RagIndex(val dir: Path, val name: String, val embedder: Embedder? = null) {

    private val metaFile = dir.resolve("meta.json")
    private val chunkFile = dir.resolve("chunks.jsonl")
    private val vectorFile = dir.resolve("vectors.f32")

    private var metaState: IndexMeta? = null
    private var chunkState: List<Chunk> = emptyList()
    private var bm25State: Bm25? = null
    private var vectorState: VectorIndex? = null
    private var vectorIssue: String? = null

    val meta: IndexMeta? get() = metaState
    val chunks: List<Chunk> get() = chunkState
    val exists: Boolean get() = Files.isRegularFile(metaFile)

    val chunkCount: Int get() = chunkState.size

    val bm25: Bm25
        get() {
            bm25State?.let { return it }
            val b = Bm25()
            chunkState.forEach { c -> b.add(indexedText(c)) }
            bm25State = b
            return b
        }

    /** The dense vectors, if an encoder is configured and the sidecar on disk
      * passed validation. */
    val vectors: VectorIndex? get() = vectorState

    val vectorsUsable: Boolean get() = vectorState != null

    /** Why vectors are unavailable, when they are. Surfaced by `/rag` rather
      * than swallowed: retrieval silently degrading to BM25 after an encoder
      * change looks like a quality regression with no visible cause. */
    val vectorProblem: String? get() = vectorIssue

    val vectorStatus: String
        get() {
            val e = embedder
            val v = vectorState
            val why = vectorIssue
            return when {
                e == null -> "off (bm25 only)"
                v != null -> "${v.size} vectors, ${e.dimensions}d, ${e.id}"
                why != null -> "unavailable -- $why"
                else -> "none built yet for ${e.id}"
            }
        }

    // ---- persistence -------------------------------------------------------

    fun load(): Outcome<Unit> {
        if (!exists) return err("no index at $dir")
        return try {
            val json = Json.parseToJsonElement(String(Files.readAllBytes(metaFile), StandardCharsets.UTF_8)).jsonObject
            metaState = IndexMeta(
                name = json["name"]!!.jsonPrimitive.content,
                root = json["root"]!!.jsonPrimitive.content,
                profile = json["profile"]?.jsonPrimitive?.content ?: "auto",
                embeddingModel = (json["embedding_model"] as? JsonPrimitive)?.takeIf { it.isString }?.content,
                dimensions = (json["dimensions"] as? JsonPrimitive)?.doubleOrNull?.toInt(),
                description = (json["description"] as? JsonPrimitive)?.takeIf { it.isString }?.content?.takeIf { it.trim().isNotEmpty() },
                // `auto_inject` was persisted by an earlier version and is now
                // ignored on purpose: injection is session state, so an index left
                // switched on weeks ago must not start spending tokens today.
                files = json["files"]!!.jsonArray.map { f ->
                    val fo = f.jsonObject
                    FileRecord(
                        fo["path"]!!.jsonPrimitive.content,
                        fo["size"]!!.jsonPrimitive.double.toLong(),
                        fo["modified"]!!.jsonPrimitive.double.toLong(),
                        fo["chunks"]!!.jsonPrimitive.double.toInt()
                    )
                }
            )
            chunkState =
                if (!Files.isRegularFile(chunkFile)) emptyList()
                else Files.readAllLines(chunkFile, StandardCharsets.UTF_8)
                    .filter { it.trim().isNotEmpty() }
                    .map { line ->
                        val c = Json.parseToJsonElement(line).jsonObject
                        Chunk(
                            c["path"]!!.jsonPrimitive.content,
                            c["l0"]!!.jsonPrimitive.double.toInt(),
                            c["l1"]!!.jsonPrimitive.double.toInt(),
                            c["text"]!!.jsonPrimitive.content,
                            c["lang"]?.jsonPrimitive?.content ?: ""
                        )
                    }
            bm25State = null
            loadVectors()
            ok(Unit)
        } catch (e: Exception) {
            err("cannot read index at $dir: ${e.message ?: e.toString()}")
        }
    }

    /** Reads the sidecar, refusing vectors that do not provably belong to these
      * chunks and this encoder.
      *
      * Vectors from a different encoder have the right shape and the wrong
      * meaning, so they would search successfully and rank nonsense -- the one
      * failure here no user could diagnose from the output. */
    private fun loadVectors() {
        vectorState = null
        vectorIssue = null

        embedder?.let { e ->
            val built = metaState?.embeddingModel
            if (!Files.isRegularFile(vectorFile))
                vectorIssue = "not built yet; run '/rag reindex' to build them"
            else if (built != e.id)
                vectorIssue =
                    "built by '${built ?: "an unknown encoder"}' but the configured encoder is " +
                        "'${e.id}'; run '/rag reindex' to rebuild"
            else
                when (val res = VectorIndex.readFrom(vectorFile, e.dimensions, chunkState.size)) {
                    is Outcome.Ok -> vectorState = res.value
                    is Outcome.Err -> vectorIssue = res.message
                }
        }
    }

    /** Writes meta, chunks and (when an encoder is configured) vectors.
      *
      * The embedding fields in `m` are overwritten from the encoder that actually
      * ran: they describe the sidecar, so the code writing it is the only thing
      * entitled to set them. Otherwise a caller could record a model that never
      * produced these vectors. */
    fun save(m: IndexMeta, cs: List<Chunk>): Outcome<Unit> =
        try {
            Files.createDirectories(dir)

            val effective = if (embedder != null) m.copy(embeddingModel = embedder.id, dimensions = embedder.dimensions)
                            else m.copy(embeddingModel = null, dimensions = null)

            // Vectors first: if encoding fails there is no half-written index
            // claiming vectors it does not have.
            val vectorResult: Outcome<VectorIndex?> =
                if (embedder == null) {
                    // A sidecar left from when an encoder *was* configured would be
                    // read back as authoritative the moment one is configured again,
                    // against chunks that have since changed.
                    Files.deleteIfExists(vectorFile)
                    ok(null)
                } else {
                    val v = VectorIndex(embedder.dimensions)
                    cs.forEach { c -> v.add(embedder.encode(indexedText(c))) }
                    v.writeTo(vectorFile).map { v }
                }

            vectorResult.flatMap { builtVectors ->
                Files.write(metaFile, prettyJsonIndex.encodeToString(JsonObject.serializer(), metaJson(effective)).toByteArray(StandardCharsets.UTF_8))

                val lines = cs.map { c ->
                    buildJsonObject {
                        put("path", c.path)
                        put("l0", c.lineStart.toDouble())
                        put("l1", c.lineEnd.toDouble())
                        put("lang", c.language)
                        put("text", c.text)
                    }.toString()
                }
                Files.write(chunkFile, lines, StandardCharsets.UTF_8)

                metaState = effective
                chunkState = cs
                bm25State = null
                vectorState = builtVectors
                vectorIssue = null
                ok(Unit)
            }
        } catch (e: Exception) {
            err("cannot write index to $dir: ${e.message ?: e.toString()}")
        }

    /** The metadata JSON. Shared by `save` and `saveMeta` so the two cannot
      * write different shapes. */
    private fun metaJson(m: IndexMeta): JsonObject = buildJsonObject {
        put("version", 1)
        put("name", m.name)
        put("root", m.root)
        put("profile", m.profile)
        put("files", buildJsonArray {
            m.files.forEach { f ->
                add(buildJsonObject {
                    put("path", f.path)
                    put("size", f.size.toDouble())
                    put("modified", f.modified.toDouble())
                    put("chunks", f.chunks.toDouble())
                })
            }
        })
        m.description?.let { put("description", it) }
        m.embeddingModel?.let { put("embedding_model", it) }
        m.dimensions?.let { put("dimensions", it.toDouble()) }
    }

    /** Rewrites only the metadata.
      *
      * Exists so setting a description does not re-encode every chunk and
      * rewrite a sidecar that can be tens of megabytes, which is what going
      * through `save` would do to change one string.
      */
    fun saveMeta(m: IndexMeta): Outcome<Unit> =
        try {
            Files.createDirectories(dir)
            Files.write(metaFile, prettyJsonIndex.encodeToString(JsonObject.serializer(), metaJson(m)).toByteArray(StandardCharsets.UTF_8))
            metaState = m
            ok(Unit)
        } catch (e: Exception) {
            err("cannot write index metadata to $dir: ${e.message ?: e.toString()}")
        }

    fun delete() {
        try {
            if (Files.isDirectory(dir)) {
                val stream = Files.list(dir)
                try {
                    stream.toList().forEach { p -> Files.deleteIfExists(p) }
                } finally {
                    stream.close()
                }
                Files.deleteIfExists(dir)
            }
            metaState = null
            chunkState = emptyList()
            bm25State = null
            vectorState = null
            vectorIssue = null
        } catch (e: Exception) {
            // ignore, matching Scala's `case NonFatal(_) => ()`
        }
    }

    // ---- searching ---------------------------------------------------------

    /** Hybrid search: BM25, plus dense vectors when available, fused by
      * reciprocal rank.
      *
      * The halves are chosen to disagree usefully. BM25 is exact and
      * IDF-weighted, so it nails identifiers and rare terms; the dense half is
      * character n-grams, so it survives morphology and typos. Fusing by *rank*
      * rather than score is what lets them combine at all -- a BM25 score of 5.4
      * and a cosine of 0.31 have no common scale.
      *
      * Degrades to BM25 alone rather than failing when vectors are unavailable.
      * `vectorProblem` says why, because silent degradation looks exactly like a
      * quality regression.
      *
      * @param maxPerFile soft cap on passages from one file; see `diversify`.
      */
    fun search(
        query: String,
        topK: Int,
        pathFilter: String? = null,
        maxPerFile: Int = 3,
        rrfK: Int = 60,
        minSimilarity: Double = 0.20
    ): List<Pair<Chunk, Double>> {
        if (chunkState.isEmpty()) return emptyList()
        val want = maxOf(1, topK)
        // Over-fetch always, because the result is post-processed three times --
        // fusion, the path filter and the per-file cap -- and each would otherwise
        // silently shrink it.
        val over = maxOf(want * 10, 100)

        val lexical = bm25.search(query, over)
        val e = embedder
        val v = vectorState
        val dense: List<Hit>? = if (e != null && v != null) v.search(e.encode(query), over, minSimilarity) else null

        val ranked = if (dense != null && dense.isNotEmpty()) Fusion.reciprocalRank(listOf(lexical, dense), rrfK, over) else lexical

        val scored = ranked
            .mapNotNull { h -> chunkState.getOrNull(h.chunkIndex)?.let { c -> Pair(c, h.score) } }
            .filter { (c, _) -> pathFilter?.let { f -> c.path.lowercase().contains(f.lowercase()) } ?: true }

        return diversify(scored, want, maxPerFile)
    }

    /** Runs only the lexical half. Exists for diagnostics -- comparing the two
      * halves on a real corpus is the only way to tell whether the dense half is
      * earning its index size. */
    fun searchLexical(query: String, topK: Int): List<Pair<Chunk, Double>> =
        bm25.search(query, maxOf(1, topK))
            .mapNotNull { h -> chunkState.getOrNull(h.chunkIndex)?.let { c -> Pair(c, h.score) } }

    /** Runs only the dense half, or nothing when vectors are unavailable. */
    fun searchDense(query: String, topK: Int, minSimilarity: Double = 0.20): List<Pair<Chunk, Double>> {
        val e = embedder ?: return emptyList()
        val v = vectorState ?: return emptyList()
        return v.search(e.encode(query), maxOf(1, topK), minSimilarity)
            .mapNotNull { h -> chunkState.getOrNull(h.chunkIndex)?.let { c -> Pair(c, h.score) } }
    }

    /** Reorders so no single file monopolises the head of the results, then
      * backfills from what the cap held back.
      *
      * BM25 ranks chunks, not files, so a large file mentioning a term in every
      * chunk floods the results. Measured here: searching `Compactor.scala`
      * returned five consecutive chunks of `CompactorSpec.scala` and pushed
      * `Compactor.scala` to rank six, out of a top-five window.
      *
      * A cap on *position*, not a filter: what is held back is appended in score
      * order, so this never returns fewer results than the uncapped version. */
    private fun diversify(
        scored: List<Pair<Chunk, Double>>,
        topK: Int,
        maxPerFile: Int
    ): List<Pair<Chunk, Double>> {
        if (maxPerFile <= 0) return scored.take(topK)

        val perFile = mutableMapOf<String, Int>()
        val kept = mutableListOf<Pair<Chunk, Double>>()
        val held = mutableListOf<Pair<Chunk, Double>>()

        scored.forEach { hit ->
            val path = hit.first.path
            val count = perFile.getOrDefault(path, 0)
            if (count < maxPerFile) {
                perFile[path] = count + 1
                kept.add(hit)
            } else held.add(hit)
        }

        return (kept + held).take(topK)
    }

    companion object {

        /** What actually gets indexed for a chunk, by both retrievers.
          *
          * The path is prepended so a query naming a file finds it even when the
          * text inside never mentions the name. Shared between BM25 and the encoder
          * deliberately: if the two indexed different text, a chunk could rank in
          * one half and be invisible to the other for no reason a user could see.
          */
        internal fun indexedText(c: Chunk): String = "${c.path} ${c.text}"

        /** Filesystem-safe name derived from a folder, so `/rag index ./docs` needs
          * no name to be invented by the user. */
        fun nameFor(root: Path): String {
            val base = root.toAbsolutePath().normalize().fileName?.toString() ?: "index"
            val safe = base.replace(Regex("[^A-Za-z0-9._-]"), "-").replace(Regex("-+"), "-").removePrefix("-").removeSuffix("-")
            return if (safe.isEmpty()) "index" else safe.lowercase()
        }

        /** Compares the tree against the metastore.
          *
          * Removal is handled explicitly: without it a deleted file keeps answering
          * queries, and an index that confidently returns text no longer on disk is
          * worse than no index at all.
          */
        fun scan(root: Path, eligible: (Path) -> Boolean, known: List<FileRecord>): ScanResult {
            val onDisk: Map<String, Path> =
                if (!Files.isDirectory(root)) emptyMap()
                else {
                    val stream = Files.walk(root)
                    try {
                        stream.toList()
                            .filter { p -> Files.isRegularFile(p) && eligible(p) }
                            .associate { p -> relativise(root, p) to p }
                    } catch (e: Exception) {
                        emptyMap()
                    } finally {
                        stream.close()
                    }
                }

            val byPath = known.associateBy { it.path }

            val added = mutableListOf<Path>()
            val changed = mutableListOf<Path>()
            val same = mutableListOf<String>()

            onDisk.forEach { (rel, abs) ->
                val record = byPath[rel]
                if (record == null) added.add(abs)
                else {
                    val size = try { Files.size(abs) } catch (e: Exception) { -1L }
                    val modified = try { Files.getLastModifiedTime(abs).toMillis() } catch (e: Exception) { -1L }
                    if (size != record.size || modified != record.modified) changed.add(abs) else same.add(rel)
                }
            }

            // A rename is a delete plus an add, which falls out of this naturally.
            val removed = byPath.keys.filterNot { onDisk.containsKey(it) }.sorted()

            return ScanResult(
                added.sortedBy { it.toString() },
                changed.sortedBy { it.toString() },
                removed,
                same.sorted()
            )
        }

        /** Forward slashes and relative to the root, so an index stays portable and
          * reads the same on Windows as elsewhere. */
        fun relativise(root: Path, file: Path): String =
            root.toAbsolutePath().normalize().relativize(file.toAbsolutePath().normalize()).toString().replace('\\', '/')

        fun resolve(base: Path, name: String, embedder: Embedder? = null): RagIndex =
            RagIndex(base.resolve(name), name, embedder)

        /** Every index under `base`. */
        fun list(base: Path): List<String> {
            if (!Files.isDirectory(base)) return emptyList()
            val stream = Files.list(base)
            try {
                return stream.toList()
                    .filter { p -> Files.isRegularFile(p.resolve("meta.json")) }
                    .map { it.fileName.toString() }
                    .sorted()
            } catch (e: Exception) {
                return emptyList()
            } finally {
                stream.close()
            }
        }
    }
}
