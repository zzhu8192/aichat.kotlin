package kotllm.engine

import java.io.ByteArrayOutputStream
import java.io.PrintStream
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.atomic.AtomicReference
import jdk.jshell.EvalException
import jdk.jshell.JShell
import jdk.jshell.Snippet
import jdk.jshell.SnippetEvent
import jdk.jshell.SourceCodeAnalysis.Completeness
import jdk.jshell.UnresolvedReferenceException
import jdk.jshell.VarSnippet
import kotlinx.serialization.json.JsonElement
import kotllm.*

/** A Java session, driven through `jdk.jshell`.
  *
  * Alongside [GroovyEngine] for one measured reason: **JShell executes snippets
  * in a separate JVM by default**, so a crash in evaluated code kills the child
  * rather than the session, and `stop()` genuinely interrupts where an
  * in-process engine's cooperative `Thread.interrupt` is ignored by a tight
  * loop.
  *
  * And it costs no new dependency. Registered only when the module is present:
  * `Interpreters.jshellAvailable` lives in a class that never references
  * JShell, so probing cannot itself throw `NoClassDefFoundError`.
  */
class JShellEngine(
    private val classpathEntries: List<String>,
    private val timeoutMillis: Long = 30_000L,
    private val maxOutputChars: Int = 16_000
) : Interpreter {

    override val id: String = Id
    override val describe: String = "Java via JShell (separate JVM)"

    private val shell = AtomicReference<Session?>(null)

    private inner class Session {
        val captured = ByteArrayOutputStream()
        private val stream = PrintStream(captured, true, StandardCharsets.UTF_8.name())

        // JShell pipes the child's stdout and stderr back to these, so no global
        // System.out redirect is needed -- unlike IMain, where capture requires
        // swapping both System.out and scala.Console.
        val jshell: JShell = JShell.builder().out(stream).err(stream).build()

        init {
            classpathEntries.forEach { e ->
                try {
                    jshell.addToClasspath(e)
                } catch (ex: Exception) {
                    // a bad entry must not sink the session
                }
            }

            JShellEngine.Startup.forEach { snippet ->
                try {
                    jshell.eval(snippet)
                } catch (ex: Exception) {
                }
            }
        }

        fun drain(): String = synchronized(this) {
            val text = String(captured.toByteArray(), StandardCharsets.UTF_8)
            captured.reset()
            text
        }
    }

    private fun session(): Session = synchronized(this) {
        shell.get() ?: run {
            val fresh = Session()
            shell.set(fresh)
            fresh
        }
    }

    override val started: Boolean get() = shell.get() != null

    // ---- eval --------------------------------------------------------------

    override fun eval(code: String): EvalResult = evaluate(code, timeoutMillis, echoValues = true)

    private fun evaluate(code: String, timeout: Long, echoValues: Boolean): EvalResult = synchronized(this) {
        val began = System.currentTimeMillis()
        val s = session()
        s.drain() // discard anything left over from a previous call

        val transcript = StringBuilder()
        // Written on the worker and read here. Scala marked both `@volatile`,
        // and that is load-bearing on exactly the path below where `join`
        // times out: with no completed join there is no happens-before edge,
        // and a captured Kotlin `var` is boxed into a plain non-volatile field.
        val incomplete = java.util.concurrent.atomic.AtomicBoolean(false)
        val failed = java.util.concurrent.atomic.AtomicBoolean(false)

        val worker = Thread(
            {
                try {
                    var remaining = code
                    var go = true
                    while (go && remaining.trim().isNotEmpty()) {
                        // JShell evaluates exactly one snippet per call, so multi-line
                        // input has to be split first. Guessing on `;` would break a
                        // for-loop header or a string containing one.
                        val info = s.jshell.sourceCodeAnalysis().analyzeCompletion(remaining)
                        val src = info.source() ?: ""

                        if (info.completeness() == Completeness.DEFINITELY_INCOMPLETE ||
                            info.completeness() == Completeness.CONSIDERED_INCOMPLETE
                        ) {
                            incomplete.set(true)
                            go = false
                        } else if (src.trim().isEmpty()) {
                            go = false
                        } else {
                            val events = s.jshell.eval(src)
                            events.forEach { e -> render(s, e, transcript, echoValues) }
                            if (events.any { e -> e.status() == Snippet.Status.REJECTED || e.exception() != null }) {
                                // Stop on the first failure, as a REPL would: running the
                                // rest against a broken definition only compounds the error.
                                failed.set(true)
                                go = false
                            }
                            remaining = info.remaining()
                        }
                    }
                } catch (e: Exception) {
                    failed.set(true)
                    transcript.append("${e.javaClass.simpleName}: ${e.message ?: ""}\n")
                }
            },
            "kotllm-jshell-eval"
        )
        worker.isDaemon = true
        worker.start()
        worker.join(maxOf(1L, timeout))

        val timedOut = worker.isAlive
        if (timedOut) {
            // Works, unlike interrupting IMain: the evaluation is in another JVM and
            // JShell can stop it from here.
            try {
                s.jshell.stop()
            } catch (e: Exception) {
            }
            worker.join(2000L)
        }

        EvalResult(
            ok = !failed.get() && !timedOut && !incomplete.get(),
            transcript = truncate(transcript.toString().trimEnd()),
            stdout = truncate(s.drain().trimEnd()),
            elapsedMillis = System.currentTimeMillis() - began,
            incomplete = incomplete.get(),
            timedOut = timedOut
        )
    }

    /** One snippet's outcome, in the shape a REPL would print it. */
    private fun render(s: Session, e: SnippetEvent, out: StringBuilder, echoValues: Boolean) {
        if (e.status() == Snippet.Status.REJECTED) {
            // Diagnostics verbatim, the same contract as the Scala side: a model
            // corrects itself from a real "incompatible types" and cannot from
            // "compilation failed".
            val diags = try {
                s.jshell.diagnostics(e.snippet()).toList()
            } catch (ex: Exception) {
                emptyList()
            }
            if (diags.isEmpty()) {
                out.append("error: snippet rejected\n")
            } else {
                diags.forEach { d ->
                    out.append("error: ${d.getMessage(null)}\n")
                    val src = e.snippet().source() ?: ""
                    val at = d.position.toInt()
                    if (at >= 0 && at <= src.length) {
                        val lineStart = src.lastIndexOf('\n', maxOf(0, at - 1)) + 1
                        val nl = src.indexOf('\n', at)
                        val lineEnd = if (nl < 0) src.length else nl
                        out.append("  ${src.substring(lineStart, lineEnd)}\n")
                        out.append("  ${" ".repeat(maxOf(0, at - lineStart))}^\n")
                    }
                }
            }
            return
        }

        e.exception()?.let { ex ->
            when (ex) {
                is EvalException -> {
                    out.append("${ex.exceptionClassName}: ${ex.message ?: ""}\n")
                    ex.stackTrace.take(8).forEach { f -> out.append("  at $f\n") }
                }
                is UnresolvedReferenceException -> {
                    out.append("unresolved reference: ${ex.snippet.name()}\n")
                }
                else -> {
                    out.append("${ex.javaClass.simpleName}: ${ex.message ?: ""}\n")
                }
            }
        }

        if (echoValues && e.exception() == null) {
            // JShell does *not* truncate a value the way IMain does at 800 chars, so
            // an 800KB String would come back in full. Capped here instead.
            val v = e.value()
            if (!v.isNullOrEmpty()) {
                val name = (e.snippet() as? VarSnippet)?.name() ?: ""
                val shown = if (v.length > 800) v.take(800) + "... [${v.length} chars]" else v
                out.append(if (name.isEmpty()) "==> $shown\n" else "$name ==> $shown\n")
            }
        }
    }

    private fun truncate(s: String): String =
        if (s.length <= maxOutputChars) s
        else {
            val half = maxOutputChars / 2
            s.take(half) + "\n... [${s.length - maxOutputChars} chars elided] ...\n" + s.takeLast(half)
        }

    // ---- the data plane ----------------------------------------------------

    /** The child opens the file itself, so the bytes never cross the process
      * boundary. Also the only workable route: a string literal in a class file
      * cannot exceed 64KB, so eval'ing the content inline could not compile for
      * anything worth binding. Values are not echoed -- `var log = <800KB>`
      * would otherwise report all of it. */
    override fun bindFile(name: String, path: Path): EvalResult {
        val literal = stringLiteral(path.toAbsolutePath().normalize().toString())
        return evaluate("var $name = __KotllmIO.read($literal);", timeoutMillis, echoValues = false)
    }

    override fun writeExpression(path: Path, expression: String): Outcome<Long> {
        val literal = stringLiteral(path.toAbsolutePath().normalize().toString())
        val result = evaluate("__KotllmIO.write($literal, ($expression))", timeoutMillis, echoValues = true)

        if (!result.ok) return err(result.toToolOutput())

        // The helper returns the character count, which arrives as the snippet's
        // value. `write(String, String)` is what makes a Seq or a List fail to
        // compile instead of being written as its toString.
        val m = Regex("""==>\s*(\d+)""").find(result.transcript)
        return if (m != null) ok(m.groupValues[1].toLong())
        else err("the write did not report a size:\n${result.toToolOutput()}")
    }

    /** Out through a temp file, and that is not laziness.
      *
      * JShell runs the snippet in another JVM, so the only channels are the
      * snippet source and the transcript -- and the transcript gives a value's
      * *Java source representation*, so a String comes back quoted and escaped.
      * Un-escaping that reliably is a parser nobody should write.
      *
      * The file is ours, never named to the model, and deleted before the call
      * returns. That is the whole difference from the model managing one. */
    override fun evalToString(expression: String): Outcome<String> {
        val tmp = try {
            Files.createTempFile(".kotllm-jshell-", ".json")
        } catch (e: Exception) {
            return err("could not open a transfer file: ${e.message}")
        }
        try {
            return writeExpression(tmp, expression).flatMap {
                try {
                    ok(String(Files.readAllBytes(tmp), StandardCharsets.UTF_8))
                } catch (e: Exception) {
                    err("could not read the transfer file: ${e.message}")
                }
            }
        } finally {
            try {
                Files.deleteIfExists(tmp)
            } catch (e: Exception) {
            }
        }
    }

    /** As a `String` of JSON, because the JDK ships no JSON parser and binding
      * something the session cannot use would be a worse answer than saying so.
      * The 64KB class-file limit on string literals is why this goes through a
      * file rather than being inlined into the snippet. */
    override fun bindJson(name: String, value: JsonElement): Outcome<String> {
        val text = value.toString()
        val tmp = try {
            Files.createTempFile(".kotllm-jshell-", ".json")
        } catch (e: Exception) {
            return err("could not open a transfer file: ${e.message}")
        }
        try {
            Files.write(tmp, text.toByteArray(StandardCharsets.UTF_8))
            val literal = stringLiteral(tmp.toAbsolutePath().normalize().toString())
            val bound = evaluate("var $name = __KotllmIO.read($literal);", timeoutMillis, echoValues = false)
            return if (bound.ok) ok("String of JSON (the JDK has no JSON parser)")
            else err(bound.toToolOutput())
        } catch (e: Exception) {
            return err("could not bind '$name': ${e.message}")
        } finally {
            try {
                Files.deleteIfExists(tmp)
            } catch (e: Exception) {
            }
        }
    }

    override fun bindings(): List<Binding> = synchronized(this) {
        val s = shell.get() ?: return@synchronized emptyList()
        try {
            s.jshell.variables().toList()
                .filterNot { it.name().startsWith("__") } // harness plumbing
                .map { v ->
                    // `varValue` renders the value in full, so this pulls it across
                    // the process boundary only to measure it. Acceptable for a
                    // listing nobody calls in a loop, and there is no JShell API for
                    // "describe without materialising".
                    val rendered = try {
                        s.jshell.varValue(v) ?: ""
                    } catch (e: Exception) {
                        ""
                    }
                    Binding(v.name(), v.typeName()?.takeIf { it.isNotEmpty() } ?: "?", summarise(rendered))
                }
        } catch (e: Exception) {
            emptyList()
        }
    }

    override fun close() {
        synchronized(this) {
            val s = shell.getAndSet(null)
            if (s != null) {
                try {
                    s.jshell.close()
                } catch (e: Exception) {
                }
            }
        }
    }

    companion object {
        const val Id = "java"

        /** Escapes a path into a Java string literal. Windows paths are full of
          * backslashes, so this is not optional. */
        fun stringLiteral(s: String): String =
            "\"" + s.replace("\\", "\\\\").replace("\"", "\\\"") + "\""

        /** Rendered length rather than a semantic size: JShell hands back the
          * value's printed form and there is nothing to introspect. Labelled as
          * such rather than pretending to be a character count. */
        fun summarise(rendered: String): String =
            if (rendered.isEmpty()) "?"
            else if (rendered.length > 60) "%,d chars rendered".format(rendered.length)
            else rendered.lineSequence().firstOrNull() ?: ""

        /** Evaluated once when the session starts.
          *
          * The imports matter for fluency: the `jshell` *command-line tool* installs a
          * default set from its startup script and the `JShell` API does not, so a
          * model writing `List.of(...)` would fail on a bare session.
          *
          * `__KotllmIO` is declared *into the session* rather than put on the
          * classpath, which avoids shipping a class to the child VM. Fully qualified
          * so it survives a `/reset`. */
        val Startup: List<String> = listOf(
            "import java.util.*;",
            "import java.util.function.*;",
            "import java.util.stream.*;",
            "import java.util.regex.*;",
            "import java.nio.file.*;",
            "import java.nio.charset.StandardCharsets;",
            "import java.io.*;",
            "import java.math.*;",
            "import java.time.*;",
            """class __KotllmIO {
              |  static String read(String p) throws Exception {
              |    return new String(java.nio.file.Files.readAllBytes(java.nio.file.Path.of(p)),
              |                      java.nio.charset.StandardCharsets.UTF_8);
              |  }
              |  static long write(String p, String s) throws Exception {
              |    java.nio.file.Path t = java.nio.file.Path.of(p).toAbsolutePath().normalize();
              |    java.nio.file.Path d = t.getParent();
              |    java.nio.file.Files.createDirectories(d);
              |    java.nio.file.Path tmp = java.nio.file.Files.createTempFile(d, ".kotllm-", ".tmp");
              |    try {
              |      java.nio.file.Files.write(tmp, s.getBytes(java.nio.charset.StandardCharsets.UTF_8));
              |      if (java.nio.file.Files.exists(t)) {
              |        try { java.nio.file.Files.setPosixFilePermissions(tmp,
              |                java.nio.file.Files.getPosixFilePermissions(t)); }
              |        catch (UnsupportedOperationException ignored) { }
              |      }
              |      try { java.nio.file.Files.move(tmp, t,
              |              java.nio.file.StandardCopyOption.ATOMIC_MOVE); }
              |      catch (java.nio.file.AtomicMoveNotSupportedException e) {
              |        java.nio.file.Files.move(tmp, t,
              |              java.nio.file.StandardCopyOption.REPLACE_EXISTING);
              |      }
              |    } finally { java.nio.file.Files.deleteIfExists(tmp); }
              |    return s.length();
              |  }
              |}""".trimMargin()
        )
    }
}
