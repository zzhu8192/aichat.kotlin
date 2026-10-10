package kotllm

import java.nio.file.Files

import kotlin.test.Test
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldStartWith
import io.kotest.matchers.string.shouldNotContain
import io.kotest.matchers.collections.shouldContain

import kotlinx.serialization.json.*

import kotllm.client.*
import kotllm.config.*
import kotllm.input.InputLimits
import kotllm.input.InputLoader
import kotllm.util.Yaml

/** Image input, end to end: loaded as a data URL, encoded as a content part,
  * gated on `supports_vision`, and kept away from the token estimator. */
class ImageTest {

    /** A one-pixel PNG, so the bytes are real without being large. */
    private val onePixelPng: ByteArray = java.util.Base64.getDecoder().decode(
        "iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAYAAAAfFcSJAAAADUlEQVR42mP8z8AAAwAB/AF+AmsAAAAASUVORK5CYII="
    )

    private fun <A> withImage(name: String = "shot.png", bytes: ByteArray = onePixelPng, f: (java.nio.file.Path) -> A): A {
        val dir = Files.createTempDirectory("kotllm-img")
        val file = dir.resolve(name)
        Files.write(file, bytes)
        return f(file)
    }

    private fun loader(limits: InputLimits = InputLimits.default) =
        InputLoader(limits) { null }

    // ---- loading -----------------------------------------------------------

    @Test fun `an image becomes a data URL held apart from the text blocks`() {
        withImage { file ->
            val loaded = loader().load(listOf(file.toString())).orNull()!!

            loaded.blocks.isEmpty() shouldBe true // never pasted into the prompt as text
            loaded.medias.size shouldBe 1
            loaded.medias[0].mimeType shouldBe "image/png"
            loaded.medias[0].dataUrl shouldStartWith "data:image/png;base64,"
            loaded.medias[0].bytes shouldBe onePixelPng.size
            loaded.isEmpty shouldBe false // an image alone is still real input
        }
    }

    @Test fun `each supported extension maps to the right mime type`() {
        listOf("a.png" to "image/png", "a.jpg" to "image/jpeg", "a.jpeg" to "image/jpeg",
            "a.webp" to "image/webp", "a.gif" to "image/gif").forEach { (name, mime) ->
            withImage(name) { file ->
                loader().load(listOf(file.toString())).orNull()!!.medias[0].mimeType shouldBe mime
            }
        }
    }

    @Test fun `an oversized image is skipped, and the message names the key to raise`() {
        withImage("big.png", ByteArray(4096) { 7 }) { file ->
            val loaded = loader(InputLimits.default.copy(maxImageBytes = 1024)).load(listOf(file.toString()))

            // Nothing loadable at all, so this is an error rather than a silent pass.
            val message = loaded.fold({ it }, { "" })
            message shouldContain "input_max_image_bytes"
            // The sizes, and not a leftover format specifier. The Scala spec
            // asserted only on the config key, so it did not notice when the
            // port turned `%,d` into the literal text `,d`: the message read
            // "image is 4,dKB, over the 1,dKB limit".
            message shouldContain "4KB"
            message shouldContain "1KB"
        }
    }

    @Test fun `a binary file that is not an image is still skipped as binary`() {
        withImage("blob.dat", byteArrayOf(1, 2, 0, 3)) { file ->
            loader().load(listOf(file.toString())).fold({ it }, { "" }) shouldContain "looks binary"
        }
    }

    @Test fun `images count against input_max_files, not against the character budget`() {
        withImage { file ->
            val one = loader(InputLimits.default.copy(maxFiles = 1))
                .load(listOf(file.toString(), file.toString()))
                .orNull()!!

            one.medias.size shouldBe 1
            one.skipped.joinToString("") shouldContain "input_max_files"
        }
    }

    // ---- the wire ----------------------------------------------------------

    @Test fun `a text-only message still encodes content as a bare string`() {
        // The array form is what gateways vary on, so it is used only when needed.
        val encoded = Message.encode(Message.user("hello"))
        encoded["content"] shouldBe JsonPrimitive("hello")
    }

    @Test fun `a message with an image encodes the parts array`() {
        val m = Message.user("what is this?", listOf(Part.Image("data:image/png;base64,AAA")))
        val encoded = Message.encode(m)

        val parts = encoded["content"] as JsonArray
        parts.size shouldBe 2
        (parts[0] as JsonObject)["type"]!!.jsonPrimitive.content shouldBe "text"
        (parts[0] as JsonObject)["text"]!!.jsonPrimitive.content shouldBe "what is this?"
        (parts[1] as JsonObject)["type"]!!.jsonPrimitive.content shouldBe "image_url"
        ((parts[1] as JsonObject)["image_url"] as JsonObject)["url"]!!.jsonPrimitive.content shouldBe
            "data:image/png;base64,AAA"
    }

    @Test fun `an image with no accompanying text sends only the image part`() {
        val parts = Message.encode(Message.user("", listOf(Part.Image("data:image/png;base64,AAA"))))["content"] as JsonArray
        parts.size shouldBe 1
        (parts[0] as JsonObject)["type"]!!.jsonPrimitive.content shouldBe "image_url"
    }

    @Test fun `an image_url part decodes back, so a request round-trips`() {
        val decoded = Content.decode(
            buildJsonArray {
                add(buildJsonObject { put("type", "text"); put("text", "hi") })
                add(buildJsonObject {
                    put("type", "image_url")
                    put("image_url", buildJsonObject { put("url", "https://x/y.png"); put("detail", "low") })
                })
            }
        )

        decoded shouldContain Part.Text("hi")
        decoded shouldContain Part.Image("https://x/y.png", "low")
    }

    // ---- the Runner's gate and accounting ----------------------------------

    private object StubClient : Client {
        var lastRequest: ChatRequest? = null
        override val id = "stub"
        private fun reply(req: ChatRequest): ChatResponse {
            lastRequest = req
            return ChatResponse(Message.assistant("ok"), "stop", Usage(40, 5, 45), "stub")
        }
        override fun chat(req: ChatRequest): ChatResponse = reply(req)
        override fun chatStream(
            req: ChatRequest,
            onEvent: (StreamEvent) -> Unit,
            keepGoing: (() -> Boolean)?
        ): ChatResponse = reply(req)
    }

    private fun runner(vision: Boolean): Runner {
        val config = Config.fromConf(Yaml.loadString("model: m\nstream: false\n"))
        val cc = ClientConfig("openai-compatible", "c", "https://h/v1", "k", emptyList(), null, null)
        val model = ModelConfig("stub", 100000, null, supportsVision = vision, supportsFunctionCalling = false)
        return Runner(TestIsolation.isolate(config), Session(), ModelTarget(cc, model), StubClient, quiet = true)
    }

    private val image = Part.Image("data:image/png;base64," + "A".repeat(40000))

    @Test fun `a model that does not declare vision has its images dropped, not sent`() {
        val r = runner(vision = false)
        r.ask("what is this?", images = listOf(image)).isOk shouldBe true

        r.session.messages()[0].images.isEmpty() shouldBe true
        StubClient.lastRequest!!.messages.flatMap { it.images }.isEmpty() shouldBe true
    }

    @Test fun `a vision model gets the image as a content part`() {
        val r = runner(vision = true)
        r.ask("what is this?", images = listOf(image)).isOk shouldBe true

        r.session.messages()[0].images.size shouldBe 1
        StubClient.lastRequest!!.messages.flatMap { it.images }[0].url shouldStartWith "data:image/png"
    }

    @Test fun `base64 payloads never reach the estimator`() {
        // 40k characters of base64 that cost a handful of tokens would teach the
        // estimator a ratio wrong by two orders of magnitude, which is the exact
        // mistake that produced 1.37 chars/token from message text once before.
        val withVision = runner(vision = true)
        withVision.ask("hi", images = listOf(image))
        val ratioAfterImage = withVision.tokenEstimator.charsPerToken

        val plain = runner(vision = true)
        plain.ask("hi")
        val ratioAfterText = plain.tokenEstimator.charsPerToken

        // The image turn must not have calibrated at all, so the ratio is still the
        // untouched default while the text-only turn has moved.
        withVision.tokenEstimator.sampleCount shouldBe 0
        plain.tokenEstimator.sampleCount shouldBe 1
        (ratioAfterImage == ratioAfterText) shouldBe false
    }

    @Test fun `--dry-run elides the payload instead of printing a megabyte`() {
        val r = runner(vision = true)
        r.session.append(Message.user("look", listOf(image)))
        val json = r.dryRun("and this?")

        json shouldNotContain "A".repeat(200)
        json shouldContain "chars elided"
        json shouldContain "data:image/png;base64,"
    }
}
