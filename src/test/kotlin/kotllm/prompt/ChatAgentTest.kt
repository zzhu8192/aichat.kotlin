package kotllm.prompt

import kotlin.test.Test
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import io.kotest.matchers.collections.shouldNotContain

import kotllm.*
import kotllm.engine.Classpath
import kotllm.engine.GroovyEngine
import kotllm.template.Template
import kotllm.template.TemplateEngine

/** Stands in for `PromptApi` so the shipped template can be rendered without a
  * Runner, an endpoint or a config directory.
  *
  * Top level and public on purpose: binding needs a type the session can name,
  * and a class nested inside the spec cannot be bound -- the bind returns false
  * and every render then quietly produces nothing.
  *
  * Plain Kotlin properties, mirroring `PromptApi`: the template reads
  * `prompt.memoryList` with no parens, so each of these has to be a property
  * rather than a method.
  */
open class EchoSections {
    open val memoryList: List<MemoryItem> = listOf(MemoryItem("note.md", "global", "REMEMBERED"))
    open val memoryIndex: List<NamedItem> = listOf(NamedItem("big.md", "TOO BIG"))
    open val memoryDropped: List<String> = listOf("huge.md")
    open val skillList: List<NamedItem> = listOf(NamedItem("review", "WHEN REVIEWING"))
    val skillsDropped: List<String> get() = emptyList()
    val skillToolNames: String = ""
    open val userToolList: List<NamedItem> = listOf(NamedItem("word_count", "COUNTS WORDS"))
    open val taskList: List<TaskItem> = listOf(TaskItem(1, "FIRST TASK", "pending", "[ ]"))
    open val tasksOpen: Int = 1
    val indexes: List<String> get() = emptyList()
    val activeIndex: String = ""
    val injectingContext: Boolean = false
    val libraries: List<Library> get() = listOf(Library("os", "OSLIB"), Library("ujson", "UJSON"))
    val artifacts: List<String> get() = listOf("g:a:1", "g:b:2")
    val extraClasspath: List<String> get() = emptyList()
    open val toolNames: List<String> = listOf("groovy_eval", "exec", "read_to_var")
    fun hasTool(n: String): Boolean = toolNames.contains(n)
    val groovyVersion: String = "5.1.99"
    val javaVersion: String = "17"
    val model: String = "m"
    val date: String = "d"
    val cwd: String = "c"

    // Aggregate sections. `PromptApi` itself no longer has these -- the
    // template owns the wording now -- but the ordering tests below compose
    // their own templates out of them, which is the cheapest way to assert
    // that the file's order is the prompt's order.
    open fun tools(): String = "TOOLS"
    open fun skills(): String = "SKILLS"
    open fun memories(): String = "MEMORIES"
    open fun userTools(): String = "USERTOOLS"
    open fun tasks(): String = "TASKS"
    open fun rag(): String = "RAG"
    open fun ragContext(): String = "RAGCONTEXT"
    open fun systemPrompt(): String = "OWN"
}

/** Nothing to say this turn, which is the common case for most sections. */
class QuietSections : EchoSections() {
    override val memoryList: List<MemoryItem> = emptyList()
    override val memoryIndex: List<NamedItem> = emptyList()
    override val memoryDropped: List<String> = emptyList()
    override val skillList: List<NamedItem> = emptyList()
    override val userToolList: List<NamedItem> = emptyList()
    override val taskList: List<TaskItem> = emptyList()
    override val tasksOpen: Int = 0
    override fun rag(): String = ""
    override fun ragContext(): String = ""
    override fun systemPrompt(): String = ""
}

/** The system prompt as a file the user owns, rather than a `List(...)` in
  * `Runner` that only the source revealed.
  */
class ChatAgentTest {

    private val engine by lazy { GroovyEngine(emptyList(), timeoutMillis = 30_000L) }
    private val templates by lazy { TemplateEngine(engine) }

    private fun renderWith(template: String, api: Any): String {
        engine.bind("prompt", api) shouldBe true
        return templates.render(template).fold(
            onErr = { err -> throw AssertionError("render failed: $err") },
            onOk = { out -> ChatAgent.tidy(out) }
        )
    }

    private fun shipped(api: Any): String = renderWith(ChatAgent.packaged, api)

    @Test fun `the packaged template parses`() {
        // It is a template before it is anything else, and a syntax error here
        // would take out every turn rather than one feature.
        Template.parse(ChatAgent.packaged).isOk shouldBe true
    }

    @Test fun `the shipped template renders every section`() {
        // The test that would have caught the header comment ending early: a
        // closing delimiter written inside it as documentation closed it at that
        // line, and every line below became live template code.
        val out = shipped(EchoSections())
        out shouldContain "5.1.99" // intro, from data not a literal
        out shouldContain "OSLIB" // libraries loop
        out shouldContain "WHEN REVIEWING" // skills loop
        out shouldContain "REMEMBERED" // memories loop
        out shouldContain "COUNTS WORDS" // user tools loop
        out shouldContain "FIRST TASK" // task loop
        out shouldContain "OWN" // the user's own prompt
    }

    @Test fun `the bundled-artifact list stays out, and so does the exit constraint`() {
        // Two deliberate cuts, both worth a guard because both are one careless
        // edit away from coming back. The full group:artifact:version list was 128
        // tokens of transitive dependencies on every request; the exit sentence is
        // unnecessary now that ExitGuard rewrites the call rather than refusing it.
        val out = shipped(EchoSections())
        out shouldNotContain "g:a:1"
        out shouldNotContain "bundled artifacts"
        out shouldNotContain "System.exit"
        // The data is still bound for anyone who wants it back.
        EchoSections().artifacts.isEmpty() shouldBe false
    }

    @Test fun `sections keep the order the file puts them in`() {
        val out = shipped(EchoSections())
        val at = listOf("OSLIB", "WHEN REVIEWING", "REMEMBERED", "COUNTS WORDS", "FIRST TASK", "OWN")
            .map { out.indexOf(it) }
        at shouldNotContain -1
        at shouldBe at.sorted()
    }

    @Test fun `the built-in fallback renders too, even though nothing normally uses it`() {
        // The one template no render exercises, so a call removed from PromptApi
        // sits in it undetected until the day the jar has no resources and it is
        // the only prompt there is. It called prompt.tools()/skills()/memories()
        // for a while after those were deleted.
        val fallback = ChatAgent::class.java.getDeclaredField("Fallback")
            .apply { isAccessible = true }
            .get(ChatAgent) as String

        renderWith(fallback, EchoSections()) shouldContain "OWN"
    }

    @Test fun `nothing in the header comment reaches the model`() {
        val out = shipped(EchoSections())
        out shouldNotContain "prompt.tools()"
        out shouldNotContain "agent template"
    }

    @Test fun `a loop puts each item on its own line, with no blank line between`() {
        // A tag alone on a line contributes that line's newline, so a `for` on its
        // own line emitted a blank line per item. The fix is that the tag shares a
        // line with the body, and this is what holds it in place.
        val out = shipped(EchoSections())
        out shouldContain "  os  -- OSLIB\n  ujson  -- UJSON\n"
    }

    @Test fun `a silent section leaves no gap`() {
        // The file spaces its calls out to stay readable; sections with nothing to
        // say must not turn that whitespace into blank lines in the prompt.
        val out = shipped(QuietSections())
        out shouldNotContain "REMEMBERED"
        out shouldNotContain "# Skills available"
        out shouldNotContain "# User-defined tools"
        out shouldNotContain "# Task list"
        out shouldNotContain "\n\n\n"
        out shouldContain "OSLIB" // still there: the tools are still on
    }

    @Test fun `tidy collapses runs of blank lines but keeps a single separator`() {
        ChatAgent.tidy("a\n\n\n\n\nb") shouldBe "a\n\nb"
        ChatAgent.tidy("  \n\na\n   \n\nb\n\n") shouldBe "a\n\nb"
        ChatAgent.tidy("a\nb") shouldBe "a\nb"
        ChatAgent.tidy("\n\n\n") shouldBe ""
    }

    @Test fun `trailing whitespace goes, because it is invisible and still costs tokens`() {
        ChatAgent.tidy("a   \nb\t") shouldBe "a\nb"
    }

    @Test fun `the order is the file's, so editing the file reorders the prompt`() {
        // The whole point of externalising it: this is a user edit, not a code
        // change, and it is the only thing deciding what the model sees.
        val reordered =
            """<%= prompt.systemPrompt() %>
              |
              |<%= prompt.tools() %>
              |""".trimMargin()
        renderWith(reordered, EchoSections()) shouldBe "OWN\n\nTOOLS"
    }

    @Test fun `a section left out of the file does not reach the model`() {
        renderWith("<%= prompt.tools() %>", EchoSections()) shouldBe "TOOLS"
    }

    @Test fun `a template may write its own loop over the data`() {
        // What the calls are for: the construction is in the file, not in a
        // StringBuilder the user would have to recompile to change.
        val own =
            """Libraries:
              |<% for (l in prompt.libraries) { %>* <%= l.name %>
              |<% } %>done
              |""".trimMargin()
        renderWith(own, EchoSections()) shouldBe "Libraries:\n* os\n* ujson\ndone"
    }

    @Test fun `a template may be plain prose, calling nothing at all`() {
        // Which is what makes it a prompt rather than a program, by the same rule
        // agent files already follow.
        renderWith("You are a terse assistant.", EchoSections()) shouldBe
            "You are a terse assistant."
    }

    @Test fun `reading the template never writes one`() {
        // `source` runs on every turn, including under the test suite. It briefly
        // called `ensureFile`, which is how a test run created `agents/chat.md`
        // inside the repo's own config directory -- the hazard `TestIsolation`
        // exists for. Seeding belongs to startup, once.
        val before = ChatAgent.exists
        ChatAgent.source.isEmpty() shouldBe false
        ChatAgent.exists shouldBe before
    }
}
