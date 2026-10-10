package kotllm.render

import java.nio.file.Files
import kotlin.test.Test
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.AfterEach

/** Usage counts, and the ordering they buy.
 *
 * The completion list is capped, so *which* candidates survive the cap is
 * the whole question. Alphabetically it is whichever names sort first,
 * which for a one-letter prefix is a screenful of near-neighbours; by
 * frequency it is the ones this user actually sends.
 */
class EmojiUsageTest {

    private lateinit var saved: EmojiUsage

    @BeforeEach fun setUp() {
        // Never the real config directory: these tests write.
        saved = EmojiUsage.current
        EmojiUsage.current = EmojiUsage(Files.createTempFile("emoji-usage", ".tsv"))
    }

    @AfterEach fun tearDown() {
        EmojiUsage.current = saved
    }

    @Test fun `a shortcode that has been used sorts ahead of one that has not`() {
        val before = Emoji.matching("sm").map { it.first }
        (before.first() == "smirk") shouldBe false

        EmojiUsage.current.record(listOf("smirk", "smirk", "smirk"))
        EmojiUsage.current.record(listOf("smiley"))

        val after = Emoji.matching("sm").map { it.first }
        after.take(2) shouldBe listOf("smirk", "smiley")
        // Everything else keeps the order it had.
        after.toSet() shouldBe before.toSet()
    }

    @Test fun `ties stay alphabetical, so the list does not shuffle around`() {
        val names = Emoji.matching("smil").map { it.first }
        names shouldBe names.sorted()
    }

    @Test fun `counts survive a reload, which is the point of writing them down`() {
        val path = Files.createTempFile("emoji-usage", ".tsv")
        val one = EmojiUsage(path)
        one.record(listOf("fire", "fire", "tada"))

        val two = EmojiUsage(path)
        two.count("fire") shouldBe 2
        two.count("tada") shouldBe 1
        two.count("never_used") shouldBe 0
    }

    @Test fun `an unreadable or absent file is simply no history`() {
        val missing = EmojiUsage(Files.createTempDirectory("nope").resolve("absent.tsv"))
        missing.count("fire") shouldBe 0
        // And recording into it still works, creating what it needs.
        missing.record(listOf("fire"))
        missing.count("fire") shouldBe 1
    }

    @Test fun `only real shortcodes are counted, so an unknown word teaches nothing`() {
        Emoji.shortcodesIn("hi :fire: and :definitely_not_an_emoji: and :tada:") shouldBe
            listOf("fire", "tada")
    }

    @Test fun `a shortcode is counted once per use, including twice in one line`() {
        Emoji.shortcodesIn(":fire: :fire:") shouldBe listOf("fire", "fire")
    }
}
