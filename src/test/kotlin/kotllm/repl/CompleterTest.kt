package kotllm.repl

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream

import kotlin.test.Test
import io.kotest.assertions.withClue
import io.kotest.matchers.shouldBe
import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.collections.shouldContainAll
import io.kotest.matchers.collections.shouldContainExactlyInAnyOrder
import io.kotest.matchers.collections.shouldNotContain
import io.kotest.matchers.collections.shouldBeEmpty

import org.jline.reader.Candidate
import org.jline.reader.LineReader
import org.jline.reader.impl.DefaultParser
import org.jline.reader.impl.LineReaderImpl

class CompleterTest {

    private val parser = DefaultParser()

    // `ReplCompleter.complete` takes a Kotlin-declared non-null `LineReader`, so
    // unlike the Scala test this cannot pass a literal `null` -- Kotlin emits a
    // runtime null-check on every public non-null parameter, including
    // overrides of Java interface methods, and it would throw before the body
    // ever ran. A real reader that the completer never dereferences (per the
    // comment below) stands in instead; it changes nothing the assertions see.
    private val dummyReader: LineReader = LineReaderImpl(
        testTerminal(ByteArrayInputStream(ByteArray(0)), ByteArrayOutputStream(), type = "dumb")
    )

    private fun completionsFor(
        line: String,
        models: List<String> = listOf("m1", "m2"),
        indexes: List<String> = listOf("docs", "repo")
    ): List<String> {
        val completer = ReplCompleter({ models }, { indexes })
        val parsed = parser.parse(line, line.length)
        val candidates = ArrayList<Candidate>()
        // The reader is only used for options the completer does not consult.
        completer.complete(dummyReader, parsed, candidates)
        return candidates.map { it.value() }
    }

    @Test fun `a dot offers nothing, as dot commands are removed`() {
        val out = completionsFor(".")
        out.shouldBeEmpty()
    }

    @Test fun `a slash offers every command, slash-prefixed`() {
        val out = completionsFor("/")
        out shouldContain "/status"
        out shouldContain "/help"
        out.all { it.startsWith("/") } shouldBe true
    }

    @Test fun `a partial slash command narrows to matches by the reader, but all are offered`() {
        completionsFor("/sta") shouldContain "/status"
    }

    @Test fun `prose offers nothing, since a command cannot appear mid-sentence`() {
        completionsFor("what is").shouldBeEmpty()
        completionsFor("explain").shouldBeEmpty()
    }

    @Test fun `a path is not treated as a command prefix`() {
        completionsFor("usr/bin").shouldBeEmpty()
    }

    @Test fun `model names complete after model`() {
        completionsFor("/model ", models = listOf("dbx:qwen", "dbx:llama")) shouldContainAll
            listOf("dbx:qwen", "dbx:llama")
    }

    @Test fun `setting names complete after set`() {
        completionsFor("/set ") shouldContain "reasoning"
    }

    @Test fun `rag subcommands complete after rag`() {
        completionsFor("/rag ") shouldContainAll listOf("index", "search", "inject")
    }

    @Test fun `index names complete after rag use and rag drop`() {
        completionsFor("/rag use ") shouldContainAll listOf("docs", "repo")
        completionsFor("/rag drop ") shouldContain "docs"
    }

    @Test fun `injection completes to on and off, not to index names`() {
        val out = completionsFor("/rag inject ")
        out shouldContainAll listOf("on", "off")
        out shouldNotContain "docs"
    }

    @Test fun `describe offers only the clear token, not index names`() {
        val out = completionsFor("/rag describe ")
        out shouldContain "-"
        out shouldNotContain "docs"
    }

    @Test fun `a rag subcommand taking free text offers nothing`() {
        // Offering index names as a search query would be actively misleading.
        completionsFor("/rag search ").shouldBeEmpty()
        completionsFor("/rag index ").shouldBeEmpty()
    }

    @Test fun `every rag subcommand the completer offers is one the handler knows`() {
        completionsFor("/rag ") shouldContainExactlyInAnyOrder Commands.ragSubcommands
    }

    @Test fun `an unknown command offers no arguments`() {
        completionsFor("/nosuch ").shouldBeEmpty()
    }

    @Test fun `a shortcode prefix mid-sentence offers matching emoji`() {
        completionsFor("nice :fir") shouldContain ":fire:"
    }

    @Test fun `a shortcode prefix at the start of the line still offers emoji, not commands`() {
        completionsFor(":tada") shouldContain ":tada:"
    }

    @Test fun `every emoji candidate starts with the word being completed`() {
        // The invariant JLine actually enforces, and the one this completer broke:
        // candidates are filtered by matching their *value* against the typed
        // word, so offering a bare `🔥` for `:fir` matches nothing, every
        // candidate is discarded and Tab rings the bell instead of listing.
        listOf(":smil", "nice :fir", ":tad").forEach { line ->
            val word = line.split(" ").last()
            val out = completionsFor(line)
            withClue("$line offered nothing: ") { out.isEmpty() shouldBe false }
            withClue("$line offered a candidate not matching $word: ") {
                out.all { it.startsWith(word) } shouldBe true
            }
        }
    }

    @Test fun `the reported case completes smil offers the smileys`() {
        completionsFor(":smil") shouldContainAll listOf(":smile:", ":smiley:", ":smiling_imp:")
    }

    @Test fun `a broad prefix is capped rather than offered in full`() {
        // `:s` matches 219 shortcodes, which is past JLine's `list-max`: offer
        // them all and JLine suppresses the list entirely, so the key appears
        // dead. That was the second thing reported about this feature.
        val out = completionsFor(":s")
        (out.size <= ReplCompleter.MaxEmojiCandidates) shouldBe true
        (out.size > 1) shouldBe true
        (ReplCompleter.MaxEmojiCandidates < 100) shouldBe true
    }

    @Test fun `a shortcode in progress is recognised, a finished one is not`() {
        ReplCompleter.isShortcodeInProgress(":sm") shouldBe true
        ReplCompleter.isShortcodeInProgress(":small_red_triangle") shouldBe true
        ReplCompleter.isShortcodeInProgress(":+1") shouldBe true
        // Already closed: there is nothing left to complete.
        ReplCompleter.isShortcodeInProgress(":fire:") shouldBe false
        ReplCompleter.isShortcodeInProgress(":") shouldBe false
        ReplCompleter.isShortcodeInProgress("fire") shouldBe false
        ReplCompleter.isShortcodeInProgress("http://x") shouldBe false
    }

    @Test fun `the word before the cursor is the one Tab would complete`() {
        ReplCompleter.wordBeforeCursor("nice :fir") shouldBe ":fir"
        ReplCompleter.wordBeforeCursor(":fir") shouldBe ":fir"
        ReplCompleter.wordBeforeCursor("nice ") shouldBe ""
    }

    @Test fun `an unknown shortcode prefix offers nothing`() {
        completionsFor(":notarealemoji").shouldBeEmpty()
    }

    @Test fun `a bare colon with nothing typed after it offers nothing`() {
        completionsFor(":").shouldBeEmpty()
    }

    @Test fun `every offered command is one the parser would actually accept`() {
        // Guards against the completer advertising something undispatchable.
        completionsFor("/").forEach { name ->
            withClue("$name should parse as a command: ") {
                (Commands.parse(name) != null) shouldBe true
            }
        }
    }

    // `/prompt full` and `/prompt default` are reserved words in front of a free
    // text argument, so the only way anyone discovers them is the completion
    // list. They had no test, which is how a build shipped to a user with the
    // arm missing entirely and nobody noticed the list was empty.
    @Test fun `the two prompt views complete after prompt`() {
        completionsFor("/prompt ") shouldContainAll listOf("full", "default")
    }

    @Test fun `only at the first argument, because the rest is the prompt text`() {
        completionsFor("/prompt full ").shouldBeEmpty()
        completionsFor("/prompt be terse ").shouldBeEmpty()
    }
}
