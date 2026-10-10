package kotllm

import java.io.ByteArrayInputStream
import java.io.InputStream

import kotlin.test.Test
import io.kotest.assertions.withClue
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach

class CliTest {

    private var priorStdin: InputStream = System.`in`

    /** An empty stdin, because the harness's is not empty and not free to read.
      *
      * Surefire's fork listens for commands on `System.in` from its own thread,
      * which holds that stream's monitor while blocked in `read`. `Cli.parse`
      * calls `System.in.available()` to decide whether input was piped, and
      * blocks on the same monitor -- about twelve seconds per test, for a code
      * path none of these tests exercise. It cost this suite 229 seconds, most
      * of the whole run.
      */
    @BeforeEach fun emptyStdin() {
        priorStdin = System.`in`
        System.setIn(ByteArrayInputStream(ByteArray(0)))
    }

    @AfterEach fun restoreStdin() {
        System.setIn(priorStdin)
    }

    private fun parse(vararg args: String): Outcome<Cli> = Cli.parse(arrayOf(*args))

    @Test fun `--rag takes an index name`() {
        parse("--rag", "docs", "how is auth configured?").orNull()!!.rag shouldBe "docs"
    }

    @Test fun `--rag without a value is an error rather than a silent default`() {
        parse("--rag").fold({ it }, { "" }) shouldContain "requires a value"
    }

    @Test fun `no --rag leaves whichever index was last active in charge`() {
        parse("hello").orNull()!!.rag shouldBe null
    }

    @Test fun `flags that really are unbuilt are still rejected, naming what is missing`() {
        // Silently ignoring an argument the user meant is the failure this avoids.
        listOf("-s", "--session", "--serve", "-e", "-c").forEach { flag ->
            withClue("$flag: ") {
                parse(flag, "x").fold({ it }, { "" }) shouldContain "not implemented yet"
            }
        }
    }

    @Test fun `flags that something else answers say so, rather than promising them`() {
        // "lands in a later increment" is a promise, and roles and macros are
        // deliberately dropped rather than pending: an agent file is both.
        listOf("-r", "--role", "--macro", "--list-roles", "--list-macros").forEach { flag ->
            withClue("$flag: ") {
                val err = parse(flag, "x").fold({ it }, { "" })
                // Points at the feature that answers it, whether that is --agent or
                // --list-agents, and promises nothing.
                err shouldContain "agent"
                err shouldNotContain "later increment"
            }
        }
    }

    @Test fun `an unknown option is reported, not swallowed as prose`() {
        parse("--nosuch").fold({ it }, { "" }) shouldContain "unknown option"
    }

    @Test fun `bare words become the prompt text`() {
        parse("explain", "scala", "sams").orNull()!!.text shouldBe "explain scala sams"
    }

    @Test fun `-f is repeatable and keeps its order`() {
        parse("-f", "a.scala", "-f", "README.md", "review").orNull()!!.files shouldBe
            listOf("a.scala", "README.md")
    }

    @Test fun `--rag-index takes a folder`() {
        parse("--rag-index", "./docs").orNull()!!.ragIndex shouldBe "./docs"
    }

    @Test fun `--rag-index and --rag are independent, and both can be given`() {
        // Indexing activates what it built, so a later --rag can still redirect
        // retrieval elsewhere.
        val cli = parse("--rag-index", "./docs", "--rag", "other").orNull()!!
        cli.ragIndex shouldBe "./docs"
        cli.rag shouldBe "other"
    }

    @Test fun `--rag-index alone leaves no text, which is what makes it exit`() {
        parse("--rag-index", "./docs").orNull()!!.text shouldBe null
    }

    @Test fun `--rag-index with text keeps the text, so it indexes then asks`() {
        val cli = parse("--rag-index", ".", "what is this project?").orNull()!!
        cli.ragIndex shouldBe "."
        cli.text shouldBe "what is this project?"
    }

    @Test fun `--rag-describe takes text`() {
        parse("--rag-describe", "our API docs").orNull()!!.ragDescribe shouldBe "our API docs"
    }

    @Test fun `--rag-index and --rag-describe compose, which is the scripted setup`() {
        val cli = parse("--rag-index", "./docs", "--rag-describe", "our API docs").orNull()!!
        cli.ragIndex shouldBe "./docs"
        cli.ragDescribe shouldBe "our API docs"
    }

    @Test fun `the usage text documents --rag-index and what it does with TEXT`() {
        Cli.usage shouldContain "--rag-index"
        Cli.usage shouldContain "exit unless TEXT"
    }

    @Test fun `each --list flag selects its kind`() {
        Cli.parse(arrayOf("--list-models")).orNull()!!.list shouldBe "models"
        Cli.parse(arrayOf("--list-tools")).orNull()!!.list shouldBe "tools"
        Cli.parse(arrayOf("--list-rags")).orNull()!!.list shouldBe "rags"
        Cli.parse(arrayOf("--list-memories")).orNull()!!.list shouldBe "memories"
        Cli.parse(arrayOf("--list-vars")).orNull()!!.list shouldBe "vars"
        Cli.parse(arrayOf("--list-commands")).orNull()!!.list shouldBe "commands"
        Cli.parse(arrayOf("hello")).orNull()!!.list shouldBe null
    }

    @Test fun `a --list for something unbuilt names the feature, not 'unknown option'`() {
        // "unknown option" reads like a typo; this is a missing increment.
        Cli.parse(arrayOf("--list-sessions")).fold({ it }, { "" }) shouldContain "sessions"
    }

    @Test fun `--agent and --agent-var parse, and agents are listable`() {
        Cli.parse(arrayOf("--agent", "review")).orNull()!!.agent shouldBe "review"
        Cli.parse(arrayOf("-a", "review")).orNull()!!.agent shouldBe "review"
        Cli.parse(arrayOf("--list-agents")).orNull()!!.list shouldBe "agents"

        val vars = Cli.parse(arrayOf("--agent-var", "base=main", "--agent-var", "who=zzhu")).orNull()!!.agentVars
        vars shouldBe mapOf("base" to "main", "who" to "zzhu")

        // A value containing '=' keeps it: base64 and query strings are normal.
        Cli.parse(arrayOf("--agent-var", "q=a=b")).orNull()!!.agentVars shouldBe mapOf("q" to "a=b")
        Cli.parse(arrayOf("--agent-var", "nope")).fold({ it }, { "" }) shouldContain "name=value"
    }

    @Test fun `the usage text documents every --list flag that works`() {
        listOf("--list-models", "--list-tools", "--list-rags", "--list-memories", "--list-vars",
            "--list-commands", "--list-agents", "--agent").forEach { flag -> Cli.usage shouldContain flag }
    }
}
