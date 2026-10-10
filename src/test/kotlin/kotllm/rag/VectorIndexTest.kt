package kotllm.rag

import java.nio.file.Files
import java.nio.file.Path

import kotlin.test.Test
import kotlin.test.assertFailsWith
import io.kotest.matchers.shouldBe
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.doubles.plusOrMinus
import io.kotest.matchers.string.shouldContain

import kotllm.*

class VectorIndexTest {

  private fun tempFile(): Path =
    Files.createTempDirectory("kotllm-vec").resolve("vectors.f32")

  /** A unit vector pointing along one axis, so similarities are exact and the
    * assertions do not depend on the encoder. */
  private fun axis(dims: Int, i: Int): FloatArray {
    val v = FloatArray(dims)
    v[i] = 1.0f
    return v
  }

  private fun unit(vararg values: Double): FloatArray {
    val n = kotlin.math.sqrt(values.sumOf { v -> v * v })
    return values.map { v -> (v / n).toFloat() }.toFloatArray()
  }

  /** Pulls the message out of a failed `Outcome`, or fails the test if it was
    * actually a success -- the Kotlin stand-in for `.left.toOption.get`. */
  private fun <A> Outcome<A>.errorMessage(): String =
    fold({ it }, { throw AssertionError("expected an error but got a value: $it") })

  // ---- building ------------------------------------------------------------

  @Test fun `vectors are stored and counted`() {
    val v = VectorIndex(4)
    v.isEmpty shouldBe true
    v.add(axis(4, 0))
    v.add(axis(4, 1))
    v.size shouldBe 2
    v.isEmpty shouldBe false
  }

  @Test fun `a row reads back exactly as written`() {
    val v = VectorIndex(3)
    v.add(unit(1.0, 2.0, 3.0))
    v.vector(0).toList() shouldBe unit(1.0, 2.0, 3.0).toList()
  }

  @Test fun `a returned row is a copy, so a caller cannot corrupt the store`() {
    val v = VectorIndex(3)
    v.add(axis(3, 0))
    val row = v.vector(0)
    row[0] = 99.0f
    v.vector(0)[0] shouldBe 1.0f
  }

  @Test fun `growth past the initial capacity keeps every earlier vector intact`() {
    // The doubling in `ensure` is where an off-by-one would silently corrupt
    // rows written before the copy.
    val v = VectorIndex(8)
    (0 until 200).forEach { i -> v.add(axis(8, i % 8)) }
    v.size shouldBe 200
    (0 until 200).forEach { i -> v.vector(i).toList() shouldBe axis(8, i % 8).toList() }
  }

  @Test fun `a vector of the wrong width is refused rather than stored misaligned`() {
    // One short row would shift every subsequent row, attributing passages to
    // the wrong files with no error anywhere.
    assertFailsWith<IllegalArgumentException> { VectorIndex(4).add(axis(3, 0)) }
  }

  @Test fun `reading a row out of range is refused`() {
    assertFailsWith<IllegalArgumentException> { VectorIndex(4).vector(0) }
  }

  @Test fun `a non-positive dimension is refused`() {
    assertFailsWith<IllegalArgumentException> { VectorIndex(0) }
  }

  // ---- searching -------------------------------------------------------

  @Test fun `search ranks by cosine, best first`() {
    val v = VectorIndex(2)
    v.add(unit(1.0, 0.0))   // row 0, exactly the query
    v.add(unit(1.0, 1.0))   // row 1, 45 degrees off
    v.add(unit(0.2, 1.0))   // row 2, further

    val hits = v.search(unit(1.0, 0.0), 3)
    hits.map { it.chunkIndex } shouldBe listOf(0, 1, 2)
    hits.first().score shouldBe (1.0 plusOrMinus 1e-6)
  }

  @Test fun `topK bounds the result`() {
    val v = VectorIndex(4)
    (0 until 4).forEach { i -> v.add(axis(4, i)) }
    v.search(unit(1.0, 1.0, 1.0, 1.0), 2).size shouldBe 2
  }

  @Test fun `an empty index returns nothing rather than failing`() {
    VectorIndex(4).search(axis(4, 0), 5).shouldBeEmpty()
  }

  @Test fun `a query of the wrong width returns nothing rather than reading past the row`() {
    val v = VectorIndex(4)
    v.add(axis(4, 0))
    v.search(axis(8, 0), 5).shouldBeEmpty()
  }

  @Test fun `a zero query returns nothing rather than ranking by noise`() {
    // This is what an encoder produces for text with no usable features, so it
    // is reachable from a real query like "a b c".
    val v = VectorIndex(4)
    v.add(axis(4, 0))
    v.search(FloatArray(4), 5).shouldBeEmpty()
  }

  @Test fun `orthogonal rows score zero and are excluded`() {
    val v = VectorIndex(2)
    v.add(axis(2, 1))
    v.search(axis(2, 0), 5).shouldBeEmpty()
  }

  @Test fun `the minimum score excludes weak matches, which is what lets it say nothing`() {
    // Cosine between two real texts is essentially never zero, so without this
    // the dense half returns its nearest neighbours for gibberish.
    val v = VectorIndex(2)
    v.add(unit(1.0, 0.0))     // cosine 1.0 with the query
    v.add(unit(0.1, 1.0))     // cosine ~0.1

    v.search(unit(1.0, 0.0), 5, minScore = 0.0).size shouldBe 2
    v.search(unit(1.0, 0.0), 5, minScore = 0.2).map { it.chunkIndex } shouldBe listOf(0)
    v.search(unit(1.0, 0.0), 5, minScore = 0.99).map { it.chunkIndex } shouldBe listOf(0)
    v.search(unit(1.0, 0.0), 5, minScore = 1.01).shouldBeEmpty()
  }

  @Test fun `ties break on row order, so results are stable across runs`() {
    val v = VectorIndex(2)
    v.add(unit(1.0, 0.0))
    v.add(unit(1.0, 0.0))
    v.search(unit(1.0, 0.0), 2).map { it.chunkIndex } shouldBe listOf(0, 1)
  }

  // ---- persistence -------------------------------------------------------

  @Test fun `an index round-trips through the binary sidecar`() {
    val file = tempFile()
    val v = VectorIndex(6)
    val rows = (0 until 25).map { i -> unit((i + 1).toDouble(), 2.0, 3.0, 4.0, 5.0, 6.0) }
    rows.forEach { v.add(it) }
    v.writeTo(file) shouldBe ok(Unit)

    val back = VectorIndex.readFrom(file, 6, 25).orNull()!!
    back.size shouldBe 25
    back.dimensions shouldBe 6
    (0 until 25).forEach { i -> back.vector(i).toList() shouldBe rows[i].toList() }
  }

  @Test fun `search works on a reloaded index`() {
    val file = tempFile()
    val v = VectorIndex(2)
    v.add(unit(1.0, 0.0))
    v.add(unit(0.0, 1.0))
    v.writeTo(file)

    VectorIndex.readFrom(file, 2, 2).orNull()!!.search(unit(1.0, 0.0), 2).map { it.chunkIndex } shouldBe listOf(0)
  }

  @Test fun `a missing sidecar is reported, not treated as empty`() {
    VectorIndex.readFrom(tempFile(), 4, 0).errorMessage() shouldContain "no vector sidecar"
  }

  @Test fun `a dimension mismatch is refused and says to reindex`() {
    // Vectors of the wrong width are still searchable and entirely
    // meaningless, which is the failure nobody could diagnose from output.
    val file = tempFile()
    val v = VectorIndex(4)
    v.add(axis(4, 0))
    v.writeTo(file)

    val err = VectorIndex.readFrom(file, 8, 1).errorMessage()
    err shouldContain "4 dimensions"
    err shouldContain "reindex"
  }

  @Test fun `a count mismatch against the chunk list is refused`() {
    // Row i must be chunk i. Trimming to fit would misattribute every passage.
    val file = tempFile()
    val v = VectorIndex(4)
    v.add(axis(4, 0))
    v.add(axis(4, 1))
    v.writeTo(file)

    val err = VectorIndex.readFrom(file, 4, 5).errorMessage()
    err shouldContain "cover 2 chunks"
    err shouldContain "holds 5"
  }

  @Test fun `a file that is not a sidecar at all is refused`() {
    val file = tempFile()
    Files.createDirectories(file.parent)
    Files.write(file, "this is not a vector file, it is prose".toByteArray(Charsets.UTF_8))
    VectorIndex.readFrom(file, 4, 1).errorMessage() shouldContain "not a kotllm vector sidecar"
  }

  @Test fun `a truncated header is refused`() {
    val file = tempFile()
    Files.createDirectories(file.parent)
    Files.write(file, byteArrayOf(1, 2, 3))
    VectorIndex.readFrom(file, 4, 1).errorMessage() shouldContain "truncated"
  }

  @Test fun `a sidecar truncated mid-payload is refused rather than read short`() {
    val file = tempFile()
    val v = VectorIndex(4)
    (0 until 10).forEach { i -> v.add(axis(4, i % 4)) }
    v.writeTo(file)

    val bytes = Files.readAllBytes(file)
    Files.write(file, bytes.copyOfRange(0, bytes.size - 20))
    VectorIndex.readFrom(file, 4, 10).errorMessage() shouldContain "truncated"
  }

  @Test fun `an unknown format version is refused rather than guessed at`() {
    val file = tempFile()
    val v = VectorIndex(4)
    v.add(axis(4, 0))
    v.writeTo(file)

    // The version int sits immediately after the 5-byte magic.
    val bytes = Files.readAllBytes(file)
    bytes[5] = 99.toByte()
    Files.write(file, bytes)
    VectorIndex.readFrom(file, 4, 1).errorMessage() shouldContain "version 99"
  }

  @Test fun `writing to an unwritable path is reported rather than thrown`() {
    val dir = Files.createTempDirectory("kotllm-vec-dir")
    // A directory where the file should be: the write cannot succeed.
    VectorIndex(4).writeTo(dir).isOk shouldBe false
  }
}
