package kotllm.engine

import groovy.lang.Binding as GroovyBinding
import groovy.lang.GString
import groovy.lang.GroovyShell
import groovy.json.JsonSlurper
import org.codehaus.groovy.control.CompilerConfiguration
import org.codehaus.groovy.control.customizers.ImportCustomizer

import java.io.ByteArrayOutputStream
import java.io.PrintStream
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.atomic.AtomicReference
import kotlinx.serialization.json.JsonElement
import kotllm.*

/** A Groovy session, driven through `GroovyShell`.
  *
  * The interpreter behind `groovy_eval`, and **in-process**, so capturing
  * printed output means swapping `System.out` for the duration and `eval` has
  * to be synchronized -- that swap is global.
  *
  * Session state lives in a `groovy.lang.Binding` rather than in generated
  * class members. That is the whole reason this engine is simpler than a
  * `.kts` one: an undeclared assignment in a Groovy script *is* a binding
  * entry, so `x = 41` survives to the next snippet, `bind` is a map put rather
  * than a compiled declaration, and `bindings()` can just read the map instead
  * of tracking declarations by hand.
  *
  * A `def x = 1` does **not** survive, because `def` makes it local to the
  * script. That is Groovy's rule, not ours, and the preamble below cannot
  * change it.
  *
  * @param extraClasspath jars to compile against **beyond** what this class's
  *   own loader already sees. Deliberately not the whole runtime classpath:
  *   the `GroovyClassLoader` is child-first, so naming a directory the parent
  *   already provides makes it load a *second* copy of those classes, and a
  *   live object handed in by `bind` then fails to match the class the script
  *   resolved -- "object is not an instance of declaring class".
  */
class GroovyEngine(
    private val extraClasspath: List<String>,
    private val timeoutMillis: Long = 30_000L,
    private val maxOutputChars: Int = 16_000
) : Interpreter {

    override val id: String get() = Id
    override val describe: String get() = "Groovy in-process (GroovyShell)"

    private val shellRef = AtomicReference<GroovyShell?>(null)

    /** Survives across snippets; also what [bindings] reports. */
    private val binding = GroovyBinding()

    /** The last expression value, for [evalValue]. Guarded by the same lock as
      * `evaluate`, which is synchronized on this. */
    private var lastValue: Any? = null

    override val started: Boolean get() = shellRef.get() != null

    private fun shell(): GroovyShell = synchronized(this) {
        shellRef.get() ?: create().also { shellRef.set(it) }
    }

    private fun create(): GroovyShell {
        val config = CompilerConfiguration().apply {
            // Groovy already defaults `java.lang`, `java.util`, `java.io`,
            // `java.net`, `java.math.BigDecimal/BigInteger` and `groovy.lang`.
            // Only what it does not is listed here.
            addCompilationCustomizers(
                ImportCustomizer()
                    .addStarImports("java.nio.file", "java.time", "groovy.json")
                    .addImports("java.nio.charset.StandardCharsets")
            )
            if (extraClasspath.isNotEmpty()) setClasspathList(extraClasspath)
        }
        return GroovyShell(javaClass.classLoader, binding, config)
    }

    // ---- eval --------------------------------------------------------------

    override fun eval(code: String): EvalResult = evaluate(code, timeoutMillis)

    /** @param timeoutMillis overrides the engine default for this call, which is
      * what an agent needs: its program legitimately waits minutes on calls it
      * made itself, where the default exists to stop a runaway snippet. */
    fun eval(code: String, timeoutMillis: Long): EvalResult = evaluate(code, timeoutMillis)

    private fun evaluate(code: String, timeout: Long): EvalResult = synchronized(this) {
        val trimmed = code.trim()
        if (trimmed.isEmpty())
            return EvalResult(ok = false, transcript = "error: empty snippet", stdout = "", elapsedMillis = 0L)

        val source = ExitGuard.defuse(trimmed)
        val began = System.currentTimeMillis()

        // Resolved here, on the calling thread, and never inside the worker:
        // this method holds the monitor for as long as it waits on `join`, so a
        // worker that called `shell()` would block on the same monitor and
        // could only ever time out.
        val live = shell()

        val captured = ByteArrayOutputStream()
        val capture = PrintStream(captured, true, StandardCharsets.UTF_8.name())
        val priorOut = System.out
        val priorErr = System.err

        val value = AtomicReference<Any?>(null)
        val failure = AtomicReference<Throwable?>(null)

        val worker = Thread({
            try {
                value.set(live.evaluate(source, nextScriptName()))
            } catch (e: Throwable) {
                failure.set(e)
            }
        }, "kotllm-groovy-eval")
        worker.isDaemon = true

        System.setOut(capture)
        System.setErr(capture)
        var timedOut = false
        try {
            worker.start()
            worker.join(timeout)
            timedOut = worker.isAlive
            if (timedOut) {
                // Cooperative only: a tight loop in model-authored code ignores
                // this. The session survives either way because the thread is a
                // daemon.
                worker.interrupt()
                // Then wait for it to actually unwind before the streams go
                // back, so a worker still running cannot clobber the global
                // System.out after we have restored it.
                worker.join(UnwindGraceMillis)
            }
        } finally {
            System.setOut(priorOut)
            System.setErr(priorErr)
        }

        val elapsed = System.currentTimeMillis() - began
        val stdout = clamp(String(captured.toByteArray(), StandardCharsets.UTF_8))
        val error = failure.get()
        lastValue = if (timedOut || error != null) null else value.get()

        return when {
            timedOut ->
                EvalResult(false, "", stdout, elapsed, timedOut = true)
            error != null ->
                EvalResult(false, clamp(diagnostic(error)), stdout, elapsed, incomplete = looksIncomplete(error))
            else ->
                EvalResult(true, clamp(render(value.get())), stdout, elapsed)
        }
    }

    /** What the model is shown when a snippet does not compile or throws.
      *
      * Passed through with its position intact. The script is *named*, rather
      * than left as the generated `Script17.groovy`, precisely so the position
      * survives: `TemplateEngine` matches on it to report a failure against the
      * template line the user actually wrote.
      */
    private fun diagnostic(e: Throwable): String {
        val raw = e.message ?: e.toString()
        return raw
            .lineSequence()
            .filterNot { it.isBlank() }
            .joinToString("\n")
            .trim()
            .ifEmpty { e.toString() }
    }

    /** A snippet that ends mid-expression, reported as such rather than as a
      * syntax error the model would try to fix by rewriting the whole thing.
      *
      * Groovy says `Unexpected input: '<EOF>'` when the source simply stops,
      * and names the offending token otherwise. Taken from the compiler rather
      * than guessed: an unterminated string reports `Unexpected character`,
      * which is the same situation and would otherwise be missed. */
    private fun looksIncomplete(e: Throwable): Boolean {
        val text = e.message ?: ""
        return "Unexpected input: '<EOF>'" in text ||
            "unexpected token: <EOF>" in text.lowercase() ||
            "Unexpected character: '\"'" in text
    }

    /** `null` is what most statements evaluate to, and echoing it on every
      * assignment would be noise in the transcript. */
    private fun render(value: Any?): String =
        when (value) {
            null, Unit -> ""
            is String -> value
            else -> value.toString()
        }

    private fun clamp(text: String): String =
        if (text.length <= maxOutputChars) text
        else text.take(maxOutputChars) + "\n[output truncated at $maxOutputChars characters]"

    // ---- the data plane ----------------------------------------------------

    /** The trailing expression's value itself, not its rendering.
      *
      * `eval` returns a transcript because that is what a tool result is. A
      * caller that needs the live object -- `UserTools` needs the actual `Tool`
      * a script evaluated to -- has nothing to parse it back out of.
      */
    fun evalValue(code: String): Outcome<Any?> = synchronized(this) {
        val r = evaluate(code, timeoutMillis)
        return if (r.ok) ok(lastValue) else err(r.toToolOutput())
    }

    override fun bindings(): List<Binding> = synchronized(this) {
        return binding.variables
            .mapNotNull { (k, v) ->
                val name = k?.toString() ?: return@mapNotNull null
                if (name.startsWith("__")) null
                else Binding(name, v?.let { it::class.qualifiedName ?: it::class.java.name } ?: "null", summarise(v))
            }
            .sortedBy { it.name }
    }

    /** Never the value itself. The point of holding data in a session is that it
      * is not in the conversation. */
    private fun summarise(v: Any?): String =
        when (v) {
            null -> "null"
            is CharSequence -> "%,d chars".format(v.length)
            is Array<*> -> "%,d elements".format(v.size)
            is Collection<*> -> "%,d elements".format(v.size)
            is Map<*, *> -> "%,d entries".format(v.size)
            else -> v::class.simpleName ?: "value"
        }

    /** Puts a live object into the session under `name`.
      *
      * Not on [Interpreter] on purpose -- the SPI omits `bind` because it cannot
      * work across a process boundary. This engine is in-process, so it can, and
      * the template engine needs it: the sink has to be the very object the
      * harness reads back afterwards, not a copy.
      *
      * A map put, not a compiled declaration: nothing here starts the compiler,
      * which is why binding the prompt API on every turn costs nothing.
      */
    fun bind(name: String, value: Any, typeName: String = value::class.java.name): Boolean {
        if (rejectName(name) != null) return false
        binding.setVariable(name, value)
        return true
    }

    override fun bindFile(name: String, path: Path): EvalResult {
        val bad = rejectName(name)
        if (bad != null) return EvalResult(false, bad, "", 0L)
        return try {
            binding.setVariable(name, Files.readString(path))
            EvalResult(true, "", "", 0L)
        } catch (e: Exception) {
            EvalResult(false, "error: ${e.message}", "", 0L)
        }
    }

    /** Bound as a Groovy `Map`/`List`, which is what "as natively as the
      * language allows" means here.
      *
      * Not the `JsonElement` itself: kotlinx's accessors -- `jsonObject`,
      * `jsonPrimitive`, `int` -- are Kotlin extensions, which compile to static
      * methods that Groovy cannot see as properties. Handing the element over
      * would give the model an object it can hold but not read. `JsonSlurper`
      * produces the same shape Python gets.
      */
    override fun bindJson(name: String, value: JsonElement): Outcome<String> {
        val bad = rejectName(name)
        if (bad != null) return err(bad)
        return try {
            binding.setVariable(name, JsonSlurper().parseText(value.toString()))
            ok("bound $name as a native Groovy Map/List")
        } catch (e: Exception) {
            err("could not bind $name: ${e.message}")
        }
    }

    /** The contract `write_from_eval` and `session_transfer` both ride on: the
      * expression must *be* a String.
      *
      * Checked against the live object rather than the transcript, because
      * every value has a `toString` -- taking the rendering would have written
      * `[a, b]` to a file and called it a success, which is exactly the failure
      * mode `FileSink.write` refuses an `Any` overload to avoid.
      */
    override fun evalToString(expression: String): Outcome<String> =
        evalValue(expression).flatMap { value ->
            when (value) {
                null -> err("the expression produced no value; it must evaluate to a String")
                is String -> ok(value)
                is GString -> ok(value.toString())
                else -> err(
                    "type mismatch: the expression is a ${value::class.simpleName ?: "value"}, " +
                        "not a String. Add .toString() or build the text you want written."
                )
            }
        }

    override fun writeExpression(path: Path, expression: String): Outcome<Long> =
        evalToString(expression).flatMap { text ->
            try {
                kotllm.util.AtomicWrite.write(path, text)
                ok(text.length.toLong())
            } catch (e: Exception) {
                err("could not write $path: ${e.message}")
            }
        }

    private fun rejectName(name: String): String? =
        if (Identifier.matches(name)) null
        else "error: '$name' is not a usable Groovy identifier"

    /** Drops the shell and every class it compiled.
      *
      * Unlike the reference-nulling this replaces, the loader goes with it:
      * `GroovyShell` holds a `GroovyClassLoader` that retains a class per
      * snippet, so a session that is finished with must let go of the loader or
      * the classes stay reachable for the life of the process.
      */
    override fun close() {
        synchronized(this) {
            shellRef.getAndSet(null)?.classLoader?.let { loader ->
                try {
                    loader.clearCache()
                    loader.close()
                } catch (e: Exception) {
                }
            }
            binding.variables.clear()
        }
    }

    companion object {
        const val Id = "groovy"

        /** What `read_to_var` and friends may name a binding. */
        private val Identifier = Regex("""[A-Za-z_][A-Za-z0-9_]*""")

        private val scriptSeq = java.util.concurrent.atomic.AtomicLong(0)

        /** A fresh name per snippet, ending in `.groovy`.
          *
          * **Unique, and that is not cosmetic.** Reusing one name makes every
          * snippet compile to the same class name, and the classloader then
          * hands back the first one: a closure defined in an earlier snippet
          * fails with "object is not an instance of declaring class". The
          * extension is what `TemplateEngine` matches on to report a failure
          * against the template line, so it has to survive. */
        private fun nextScriptName(): String = "snippet${scriptSeq.incrementAndGet()}.groovy"

        /** How long a timed-out worker gets to unwind before `System.out` is
          * restored. Nothing rides on the exact value. */
        private const val UnwindGraceMillis = 500L
    }
}
