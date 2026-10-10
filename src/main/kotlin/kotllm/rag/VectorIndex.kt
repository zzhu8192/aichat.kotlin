package kotllm.rag

import kotllm.*
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardOpenOption
import java.nio.charset.StandardCharsets

/** Dense vectors for the chunks of one index, searched by brute force.
  *
  * **Brute force is the right algorithm at this scale, not a shortcut.** A scan
  * of 10,000 vectors at 512 dimensions is about five million multiply-adds --
  * single-digit milliseconds, faster than the JSON parse of the chunks it
  * accompanies. An ANN index would add a dependency, a build step, a tuning
  * surface and *approximation*, to save time that is not being spent.
  *
  * Vectors live in one flat array with a stride of `dimensions`: one allocation
  * instead of n, contiguous memory for the scan, and the on-disk form is then
  * just the array's bytes.
  *
  * **Row `i` corresponds to chunk `i`**, and a count mismatch is treated as
  * corruption rather than trimmed to fit -- misaligned vectors would attribute
  * every passage to the wrong file.
  */
class VectorIndex(val dimensions: Int) {

  init {
    require(dimensions > 0, { "dimensions must be positive" })
  }

  private var data = FloatArray(0)
  private var count = 0

  val size: Int get() = count

  val isEmpty: Boolean get() = count == 0

  fun add(vector: FloatArray) {
    require(vector.size == dimensions, { "expected $dimensions dimensions, got ${vector.size}" })
    ensure(count + 1)
    System.arraycopy(vector, 0, data, count * dimensions, dimensions)
    count += 1
  }

  fun addAll(vectors: List<FloatArray>) {
    ensure(count + vectors.size)
    vectors.forEach(::add)
  }

  private fun ensure(rows: Int) {
    val needed = rows * dimensions
    if (data.size < needed) {
      // Doubling, so building an index of n vectors is O(n) copies rather than
      // O(n^2).
      val grown = FloatArray(kotlin.math.max(needed, kotlin.math.max(data.size * 2, dimensions * 16)))
      System.arraycopy(data, 0, grown, 0, count * dimensions)
      data = grown
    }
  }

  /** Reads row `i`, copied so a caller cannot corrupt the store. */
  fun vector(i: Int): FloatArray {
    require(i >= 0 && i < count, { "row $i out of range" })
    val out = FloatArray(dimensions)
    System.arraycopy(data, i * dimensions, out, 0, dimensions)
    return out
  }

  /** Ranked hits by cosine similarity, best first.
    *
    * The query is assumed unit-length, as `Embedder.encode` produces, so this
    * is a dot product.
    *
    * `minScore` is not an optimisation, it is what gives the dense half the
    * ability to say "nothing". Cosine between two texts is essentially never
    * zero -- any two English or code strings share some character n-grams --
    * so without a floor this returns its `topK` nearest neighbours for a query
    * of pure gibberish, and fusion then hands them confident ranks. Measured
    * on this repository, nonsense queries peak at 0.17 while wanted matches
    * bottom out at 0.24; see docs/rag.md for the sweep.
    */
  fun search(query: FloatArray, topK: Int, minScore: Double = 0.0): List<Hit> {
    if (count == 0 || query.size != dimensions) return emptyList()

    var queryNorm = 0.0
    var d = 0
    while (d < dimensions) {
      queryNorm += query[d].toDouble() * query[d].toDouble()
      d += 1
    }
    if (queryNorm <= 0.0) return emptyList()

    val hits = mutableListOf<Hit>()
    var row = 0
    while (row < count) {
      val base = row * dimensions
      var sum = 0.0
      var i = 0
      while (i < dimensions) {
        sum += data[base + i].toDouble() * query[i].toDouble()
        i += 1
      }
      if (sum > minScore) hits.add(Hit(row, sum))
      row += 1
    }

    return hits
      .sortedWith(compareBy({ -it.score }, { it.chunkIndex }))
      .take(kotlin.math.max(1, topK))
  }

  fun clear() {
    data = FloatArray(0)
    count = 0
  }

  // ---- persistence -------------------------------------------------------

  /** Writes the binary sidecar.
    *
    * Binary rather than JSON because the numbers are the payload: 10,000
    * vectors at 512 dimensions is 20MB as float32 and roughly 100MB as JSON
    * text, which would also be slower to parse than the search it enables.
    */
  fun writeTo(file: Path): Outcome<Unit> =
    try {
      val header = VectorIndex.Magic.toByteArray(StandardCharsets.US_ASCII)
      val buffer = ByteBuffer
        .allocate(header.size + 12 + count * dimensions * 4)
        .order(ByteOrder.LITTLE_ENDIAN)

      buffer.put(header)
      buffer.putInt(VectorIndex.Version)
      buffer.putInt(dimensions)
      buffer.putInt(count)
      // Bulk put through a float view: one copy rather than per-element calls.
      buffer.asFloatBuffer().put(data, 0, count * dimensions)

      Files.write(
        file,
        buffer.array(),
        StandardOpenOption.CREATE,
        StandardOpenOption.TRUNCATE_EXISTING,
        StandardOpenOption.WRITE
      )
      ok(Unit)
    } catch (e: Exception) {
      err("cannot write vectors to $file: ${VectorIndex.describe(e)}")
    }

  companion object {

    private val Magic = "SCVEC"

    private val Version = 1


    private fun describe(e: Throwable): String =

      e.message ?: e::class.simpleName ?: "failed"


    /** Reads a sidecar, refusing anything that does not match what the caller
      * expects.
      *
      * Every check here exists because passing on a mismatch would produce
      * plausible, wrong answers rather than an error: vectors from another
      * encoder, another dimension count, or a truncated write are all
      * numerically searchable and semantically meaningless.
      */
    fun readFrom(file: Path, expectDimensions: Int, expectCount: Int): Outcome<VectorIndex> =

      try {
        if (!Files.isRegularFile(file)) err("no vector sidecar at $file")
        else {
          val bytes = Files.readAllBytes(file)
          val header = Magic.toByteArray(StandardCharsets.US_ASCII)
          if (bytes.size < header.size + 12) err("vector sidecar $file is truncated")
          else {
            val buffer = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
            val magic = ByteArray(header.size)
            buffer.get(magic)
            if (!magic.contentEquals(header)) err("$file is not a kotllm vector sidecar")
            else {
              val version = buffer.int
              val dims = buffer.int
              val rows = buffer.int
              when {
                version != Version ->
                  err("vector sidecar version $version, expected $Version; reindex to rebuild")
                dims != expectDimensions ->
                  err("vectors have $dims dimensions but the encoder produces $expectDimensions; reindex to rebuild")
                rows != expectCount ->
                  err("vectors cover $rows chunks but the index holds $expectCount; reindex to rebuild")
                else -> {
                  val expectedBytes = header.size + 12 + rows * dims * 4
                  if (bytes.size < expectedBytes) {
                    err("vector sidecar $file is truncated: ${bytes.size} bytes, expected $expectedBytes")
                  } else {
                    val index = VectorIndex(dims)
                    val flat = FloatArray(rows * dims)
                    buffer.asFloatBuffer().get(flat)
                    index.data = flat
                    index.count = rows
                    ok(index)
                  }
                }
              }
            }
          }
        }
      } catch (e: Exception) {
        err("cannot read vectors from $file: ${describe(e)}")
      }
  }
}
