package kotllm.engine

import java.nio.file.Path
import kotllm.*
import kotllm.util.AtomicWrite

/** The receiving end of `write_from_eval`, bound into the interpreter session.
  *
  * A computed `String` has to reach a file without passing through the
  * conversation, and the interpreter can put values *in* (`bind`) but only hands
  * them *out* as transcript text - the round trip being avoided. So the write
  * happens on this side: the tool arms the sink with a target, evaluates
  * `__kotllmSink.write({ <expression> })`, and reads the outcome off the object
  * it still holds. The content never leaves the JVM.
  *
  * A named public class because `IMain.bindValue` binds by runtime type and the
  * generated code has to typecheck against it.
  *
  * `write(content: String)`, deliberately not `Any`: an `Any` overload would
  * accept a `Seq` and silently write `List(a, b, c)`, where as it is the
  * compiler's "type mismatch" goes back to the model verbatim.
  */
class FileSink {

    private var target: Path? = null
    private var outcome: kotllm.Outcome<Long> = err("nothing was written")
    private var holding = false
    private var heldText: kotllm.Outcome<String> = err("nothing was held")

    /** Points the sink at a file and clears the previous outcome, so a failed
      * evaluation cannot be read as the success of the call before it. */
    internal fun arm(path: Path): Unit = synchronized(this) {
        holding = false
        target = path
        outcome = err("the expression did not call write")
    }

    internal val result: kotllm.Outcome<Long>
        get() = synchronized(this) { outcome }

    /** Arms the sink to keep the value in memory instead of writing it.
      *
      * This is how a value leaves the Scala session *without* touching the
      * transcript or the disk. The transcript is capped - and IMain truncates an
      * echoed value at 800 characters anyway - so it cannot carry a payload; and
      * a temp file would be the very indirection this exists to remove.
      */
    internal fun armHold(): Unit = synchronized(this) {
        holding = true
        target = null
        heldText = err("the expression did not call hold")
    }

    internal val held: kotllm.Outcome<String>
        get() = synchronized(this) { heldText }

    /** Called by the generated code. Public because that code has to see it. */
    fun hold(content: String): Unit = synchronized(this) {
        heldText = if (holding) ok(content) else err("the sink was not armed to hold")
    }

    /** Called by the generated code. Public because that code has to see it. */
    fun write(content: String): Unit = synchronized(this) {
        outcome = target?.let { path ->
            try {
                AtomicWrite.write(path, content)
                ok(content.length.toLong())
            } catch (e: Exception) {
                err("${e.javaClass.simpleName}: ${e.message ?: ""}")
            }
        } ?: err("no output file was armed for this call")
    }

    companion object {

        /** The name it is bound under. Underscore-prefixed so it reads as harness
          * plumbing rather than something the model is invited to use directly -
          * though calling it from `groovy_eval` is harmless, since the sink enforces
          * the same guards the tool does and `groovy_eval` asks for approval anyway. */
        const val BindingName = "__kotllmSink"
    }
}
