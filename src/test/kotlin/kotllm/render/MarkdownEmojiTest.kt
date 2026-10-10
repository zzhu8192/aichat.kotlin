package kotllm.render

import kotlin.test.Test
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import kotllm.util.Glyphs

/** `:shortcode:` expansion as it runs inside the streaming markdown
 * pipeline, not just `Emoji.expand` in isolation -- in particular the fence
 * rule, which is shared with LaTeX for the same reason: a shortcode inside a
 * code sample is source the user is reading, not shorthand to expand.
 */
class MarkdownEmojiTest {

    private fun g(withUnicode: String, withoutUnicode: String) =
        if (Glyphs.unicode) withUnicode else withoutUnicode

    private fun render(s: String) = Markdown.render(s, highlight = false)

    @Test fun `a shortcode in prose expands`() {
        render("nice :fire: work") shouldBe g("nice 🔥 work", "nice :fire: work")
    }

    @Test fun `a fence wins over emoji expansion, same as it does over LaTeX`() {
        val out = render(
            """```
              |val x = 1 // :fire:
              |```
              |""".trimMargin()
        )
        out shouldContain ":fire:"
    }

    @Test fun `emoji and LaTeX both apply on the same line`() {
        render("""this is \(x=1\) and also :tada:""") shouldBe g(
            "this is x=1 and also 🎉",
            "this is x=1 and also :tada:"
        )
    }
}
