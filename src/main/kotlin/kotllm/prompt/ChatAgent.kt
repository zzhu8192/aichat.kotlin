package kotllm.prompt

import java.nio.charset.StandardCharsets.UTF_8
import java.nio.file.Files
import java.nio.file.Path
import kotllm.*

/** The default chat agent: the system prompt, as an ordinary agent file.
  *
  * It calls none of `agent()`, `system {}`, `llm()`, `finish()` or `fail()`,
  * which by the rule agents already follow makes it a prompt rather than a
  * program. So there is no second file format here and nothing new to learn --
  * it is `agents/chat.md`, it sits beside every other agent, and `/agent chat`
  * renders it.
  *
  * **It is a file on disk, not a resource served from the jar.** The packaged
  * copy exists only to write that file the first time; every render after
  * reads the disk. A prompt that can only be changed by rebuilding is not
  * editable in any sense the word usually carries, and the whole point of
  * pulling it out of `Runner.systemPrompt` was that nobody could see it.
  *
  * Re-read every render rather than cached, because a prompt is the thing
  * people iterate on fastest and a restart between edits is enough friction to
  * stop them.
  */
object ChatAgent {

    val FileName = "chat.md"

    private val ResourceName = "/chat.md"

    val file: Path get() = kotllm.config.Paths.agentsDir().resolve(FileName)

    val exists: Boolean get() = Files.isRegularFile(file)

    /** The packaged copy, used to seed [file] and as the last resort if the
      * user's own edit will not render. */
    val packaged: String
        get() = try {
            javaClass.getResourceAsStream(ResourceName)?.use { stream ->
                String(stream.readAllBytes(), UTF_8)
            } ?: Fallback
        } catch (e: Exception) {
            Fallback
        }

    /** Writes the packaged default to disk at startup, **overwriting whatever
      * was there**.
      *
      * The file is an output, not a setting: it exists so the prompt is legible
      * without reading the source, and it is rewritten every run so it can
      * never drift from the build. Seeding once and then leaving it alone was
      * the obvious design and the wrong one -- an install from before a prompt
      * change keeps serving the old text for ever, and nothing says so. It also
      * put a file in a config directory silently deciding what every request
      * carries.
      *
      * Editing it still works and still applies on the next turn, because the
      * template is re-read per render. What changes is that a restart puts it
      * back, which makes it a scratchpad rather than a place to keep anything.
      *
      * @return true when the contents changed, so startup can say so once.
      */
    fun ensureFile(): Outcome<Boolean> =
        try {
            val wanted = packaged
            val current = if (exists) String(Files.readAllBytes(file), UTF_8) else null
            if (current == wanted) ok(false)
            else {
                Files.createDirectories(file.parent)
                kotllm.util.AtomicWrite.write(file, wanted)
                ok(true)
            }
        } catch (e: Exception) {
            err("could not write $file: ${e.message}")
        }

    /** The template in force. Reads, and never writes.
      *
      * **Seeding happens once at startup, not here.** This is called on every
      * turn, including from the test suite, and a read that creates files puts
      * them in whatever config directory happens to be live -- the exact hazard
      * `TestIsolation` exists for, after the suite once scattered stub
      * conversations through a developer's real history.
      *
      * Falls back to the packaged copy rather than failing: a missing or
      * unreadable file should cost the user their customisation, not their tool
      * manifest.
      */
    val source: String
        get() = try {
            if (exists) String(Files.readAllBytes(file), UTF_8) else packaged
        } catch (e: Exception) {
            packaged
        }

    /** Collapses the gaps an absent section leaves behind.
      *
      * The file puts a blank line between calls so it reads as a document. When
      * a section returns "" that blank line is still there, and three in a row
      * is what the model would be sent. Runs of blank lines become one, which is
      * the separator the hand-assembled version used.
      *
      * Deliberately not trimming each section: a section's own internal blank
      * lines are its business, and squeezing those would reflow the manifest.
      */
    fun tidy(raw: String): String =
        raw.lineSequence()
            .map { it.replace(Regex("\\s+$"), "") }
            .fold(mutableListOf<String>()) { acc, line ->
                if (line.isEmpty() && acc.lastOrNull() == "") acc else acc.also { it.add(line) }
            }
            .joinToString("\n")
            .trim()

    /** Only if the jar was built without its resources. An empty system prompt
      * is a silent behaviour change, so there is always something.
      *
      * Deliberately minimal, and it has to stay reachable: this is the one
      * template nothing renders in normal use, so a call that no longer exists
      * would sit here undetected until the day it is needed. It called
      * `prompt.tools()`, `prompt.skills()`, `prompt.memories()`,
      * `prompt.userTools()` and `prompt.tasks()` for a while after those were
      * removed -- a fallback that cannot render is the same as no fallback.
      */
    private val Fallback =
        """<% if (prompt.hasTool("groovy_eval")) { %>
You are in kotllm: a live Groovy <%= prompt.groovyVersion %> session on Java <%= prompt.javaVersion %> via `groovy_eval`.
<% } %>

<%= prompt.rag() %>

<%= prompt.ragContext() %>

<%= prompt.systemPrompt() %>
"""
}
