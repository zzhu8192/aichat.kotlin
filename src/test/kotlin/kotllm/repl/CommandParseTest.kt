package kotllm.repl

import kotlin.test.Test
import io.kotest.matchers.shouldBe

class CommandParseTest {

    @Test fun `a slash command parses with no argument`() {
        Commands.parse("/status") shouldBe Pair<String, String?>("/status", null)
    }

    @Test fun `leading and trailing whitespace is tolerated`() {
        Commands.parse("  /status  ") shouldBe Pair<String, String?>("/status", null)
    }

    @Test fun `an argument is captured whole`() {
        Commands.parse("/set reasoning show") shouldBe ("/set" to "reasoning show")
        Commands.parse("/prompt be terse, and precise") shouldBe
            ("/prompt" to "be terse, and precise")
    }

    @Test fun `the two prompt view words arrive as arguments, not as a separate command`() {
        Commands.parse("/prompt full") shouldBe ("/prompt" to "full")
        Commands.parse("/prompt default") shouldBe ("/prompt" to "default")
        Commands.promptViews shouldBe listOf("full", "default")
    }

    @Test fun `a prompt that merely starts with a view word is still a prompt`() {
        // Only the bare word switches the view; the shadowing is two exact strings
        // wide, not a prefix.
        Commands.parse("/prompt full sentences only, please") shouldBe
            ("/prompt" to "full sentences only, please")
        Commands.parse("/prompt default to Scala 2.13") shouldBe
            ("/prompt" to "default to Scala 2.13")
    }

    @Test fun `common slash commands parse correctly`() {
        Commands.parse("/status") shouldBe Pair<String, String?>("/status", null)
        Commands.parse("/help") shouldBe Pair<String, String?>("/help", null)
        Commands.parse("/set stream false") shouldBe ("/set" to "stream false")
    }

    @Test fun `dot commands are not parsed as commands and treated as ordinary text`() {
        Commands.parse(".status") shouldBe null
        Commands.parse(".help") shouldBe null
        Commands.parse(".nosuchthing") shouldBe null
        Commands.parse(".") shouldBe null
    }

    @Test fun `an unknown slash word or path is sent to the model, not rejected as a command`() {
        // Otherwise a path or a date would be swallowed as a bad command.
        Commands.parse("/etc/hosts explain this file") shouldBe null
        Commands.parse("/usr/bin/scala --version") shouldBe null
        Commands.parse("/nosuchthing") shouldBe null
    }

    @Test fun `ordinary prose is never a command`() {
        Commands.parse("what is 2+2?") shouldBe null
        Commands.parse("") shouldBe null
        Commands.parse("the ratio is 3/4") shouldBe null
    }

    @Test fun `aliases that are not in the visible table still resolve`() {
        Commands.parse("/quit") shouldBe Pair<String, String?>("/quit", null)
    }
}
