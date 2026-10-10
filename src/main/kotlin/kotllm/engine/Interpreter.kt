package kotllm.engine

import java.nio.file.Path
import kotlinx.serialization.json.JsonElement

/** Outcome of one evaluation, whichever interpreter ran it. */
data class EvalResult(
    val ok: Boolean,
    val transcript: String,
    val stdout: String,
    val elapsedMillis: Long,
    val incomplete: Boolean = false,
    val timedOut: Boolean = false
) {

    /** What goes back to the model as the tool result.
      *
      * On failure the compiler diagnostics are returned verbatim rather than
      * summarised, so the model can correct its own code from the real error -
      * which it does reliably, and cannot do from "compilation failed".
      */
    fun toToolOutput(): String {
        val sb = StringBuilder()
        if (stdout.isNotEmpty()) {
            sb.append(stdout.trimEnd())
            sb.append('\n')
        }
        if (transcript.isNotEmpty()) sb.append(transcript.trimEnd())
        if (timedOut) sb.append("\n[timed out after ${elapsedMillis}ms and was interrupted]")
        if (incomplete) sb.append("\n[incomplete input: the snippet ends mid-expression]")
        val text = sb.toString().trim()
        return if (text.isEmpty()) "(no output)" else text
    }
}

/** A name in scope in an interpreter session: what it is called, its type, and
  * how big it is. **Never its value** - the whole point of holding data in an
  * interpreter is that it is not in the conversation, so a listing that printed
  * contents would undo the feature it serves. */
data class Binding(val name: String, val tpe: String, val summary: String)

/** A live interpreter session the model can compute in.
  *
  * Extracted when a second language arrived. The three data-plane tools -
  * `read_to_var`, `write_from_eval`, `session_vars` - differ only in *which*
  * session they touch, so they are written once against this and selected by a
  * `language` argument.
  *
  * The four methods are exactly what those tools need. In particular there is
  * deliberately **no `bind(name, value)`**: that works in-process and cannot
  * work across a process boundary, so the SPI asks for the *outcome*
  * (`bindFile`) and lets each engine reach it however it can.
  */
interface Interpreter {

    /** The `language` value that selects this engine. */
    val id: String

    /** Human-readable, for tool results and `/info`. */
    val describe: String

    /** Compiles and runs a snippet, keeping session state. */
    fun eval(code: String): EvalResult

    /** Everything currently in scope. Empty when the session has never started,
      * rather than starting one: asking what is in an empty session must not
      * cost a compiler. */
    fun bindings(): List<Binding>

    /** Makes the text of `path` available in the session under `name`.
      *
      * A path rather than a String on purpose: an out-of-process engine would
      * otherwise have to ship the bytes across, and for JShell the only other
      * route is a string literal, which cannot exceed 64KB in a class file. Given
      * the path, the remote case becomes "the other JVM opens the file itself",
      * which copies nothing. */
    fun bindFile(name: String, path: Path): EvalResult

    /** Evaluates `expression`, requires it to be a String, and writes it to
      * `path` atomically. Returns the number of characters written.
      *
      * The value must not travel through the transcript, which is the round trip
      * being avoided, so the write happens on the far side of the boundary.
      */
    fun writeExpression(path: Path, expression: String): kotllm.Outcome<Long>

    /** Evaluates an expression that must produce a String, and returns it -
      * without the transcript, the disk, or the conversation.
      *
      * The other half of `writeExpression`: same requirement, same reason (the
      * transcript is capped and IMain truncates a value at 800 characters),
      * different destination. This one hands the text back to the harness, which
      * is what makes a session-to-session transfer possible at all. */
    fun evalToString(expression: String): kotllm.Outcome<String>

    /** Makes a JSON value available in the session under `name`.
      *
      * Bound **as natively as the language allows**, and the result string says
      * which: Python gets a real `dict`/`list`, Scala a `ujson.Value`, and Java a
      * `String` of JSON, because the JDK ships no JSON parser and pretending
      * otherwise would be the over-promise this project keeps warning about.
      */
    fun bindJson(name: String, value: JsonElement): kotllm.Outcome<String>

    val started: Boolean

    fun close(): Unit
}

object Interpreters {

    /** Whether `jdk.jshell` is present.
      *
      * Deliberately in an object that never mentions JShell: a trimmed runtime
      * may not carry the module, and probing by touching `JShellEngine` would
      * throw `NoClassDefFoundError` instead of answering the question.
      */
    fun jshellAvailable(): Boolean =
        try {
            Class.forName("jdk.jshell.JShell")
            true
        } catch (e: Throwable) {
            false
        }
}
