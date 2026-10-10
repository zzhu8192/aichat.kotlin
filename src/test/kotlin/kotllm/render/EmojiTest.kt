package kotllm.render

import kotlin.test.Test
import io.kotest.matchers.shouldBe
import kotllm.util.Glyphs

/** `:shortcode:` expansion, gated on [Glyphs.unicode] like every other
 * glyph substitution -- see `LatexTest` for why expectations are derived
 * from the flag rather than written as literals.
 */
class EmojiTest {

    private fun g(withUnicode: String, withoutUnicode: String) =
        if (Glyphs.unicode) withUnicode else withoutUnicode

    @Test fun `a known shortcode expands to its emoji`() {
        Emoji.expand("nice :fire: work") shouldBe g("nice 🔥 work", "nice :fire: work")
    }

    @Test fun `an unknown shortcode is left exactly as written`() {
        Emoji.expand("see :not_a_real_emoji: here") shouldBe "see :not_a_real_emoji: here"
    }

    @Test fun `plain colons with no shortcode are untouched`() {
        Emoji.expand("time: 10:30, ratio 2:1") shouldBe "time: 10:30, ratio 2:1"
    }

    @Test fun `+1 and -1 are valid shortcode characters`() {
        Emoji.expand(":+1: :-1:") shouldBe g("👍 👎", ":+1: :-1:")
    }

    @Test fun `multiple shortcodes in one line all expand`() {
        Emoji.expand(":tada: :eyes: :rocket:") shouldBe
            g("🎉 👀 🚀", ":tada: :eyes: :rocket:")
    }

    // ---- ASCII emoticons ----------------------------------------------------

    @Test fun `classic smiley emoticons expand`() {
        Emoji.expand("sounds good :)") shouldBe g("sounds good 🙂", "sounds good :)")
        Emoji.expand("sounds good :-)") shouldBe g("sounds good 🙂", "sounds good :-)")
        Emoji.expand("nope :(") shouldBe g("nope 🙁", "nope :(")
        Emoji.expand("ha :D") shouldBe g("ha 😃", "ha :D")
    }

    @Test fun `an emoticon at the start of a line still expands`() {
        Emoji.expand(":) good morning") shouldBe g("🙂 good morning", ":) good morning")
    }

    @Test fun `an emoticon followed by punctuation still expands`() {
        Emoji.expand("great :)!") shouldBe g("great 🙂!", "great :)!")
    }

    @Test fun `an emoticon glued to another word is not one`() {
        // `x:)y` is not a smiley at a word boundary -- and code containing a
        // trailing `:D`-shaped token is not either.
        Emoji.expand("see x:)y here") shouldBe "see x:)y here"
    }

    @Test fun `shortcodes and emoticons both apply on the same line`() {
        Emoji.expand(":fire: nice :)") shouldBe g("🔥 nice 🙂", ":fire: nice :)")
    }
}
