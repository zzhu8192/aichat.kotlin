package kotllm.template

import kotllm.*
import kotllm.engine.GroovyEngine

/** Collects template output.
  *
  * A concrete class rather than a raw StringBuilder because it is bound into
  * the interpreter session by value: binding needs a stable, public type on the
  * classpath for the generated code to typecheck against.
  */
class Sink {
    private val sb = StringBuilder()

    fun append(value: Any?) {
        sb.append(
            when (value) {
                null -> "null"
                else -> value.toString()
            }
        )
    }

    val result: String get() = sb.toString()

    /** How much has been appended so far. */
    val length: Int get() = sb.length

    /** Removes everything appended since `from` and returns it.
      *
      * This is how an agent's `system { }` block captures prose: the block's text
      * appends to the output exactly as any other literal would, and the capture
      * then cuts that span back out. Append-then-cut rather than a stack of
      * sinks, because it needs no indirection in the generated code and so leaves
      * `Template.emit` and the line map alone -- and reporting a compile failure
      * against the template line is worth more than elegance here.
      */
    fun cut(from: Int): String {
        val start = kotlin.math.max(0, kotlin.math.min(from, sb.length))
        val text = sb.substring(start)
        sb.setLength(start)
        return text
    }
}

/** Expands `<% %>` templates against the live interpreter.
  *
  * Shares the session with `groovy_eval`, deliberately: a value computed in a
  * template stays available to later tool calls and vice versa, which is what
  * makes the feature composable rather than a separate sandbox.
  */
class TemplateEngine(private val engine: GroovyEngine) {

    /** Expands `text`, or explains why it could not be.
      *
      * Text with no tags is returned untouched without starting the compiler --
      * which is the common case and must stay free.
      */
    /** @param onSink called once the sink exists and is bound, before the program
      *               runs. Agents use it to give their API a handle on the sink;
      *               ordinary templates pass nothing.
      */
    fun render(
        text: String,
        onSink: (Sink) -> Unit = {},
        // Overrides the interpreter's eval timeout. An agent's own program is
        // user-authored and waits on the model calls it makes, so the limit that
        // guards against a runaway `groovy_eval` snippet is the wrong one.
        timeoutMillis: Long? = null
    ): Outcome<String> {
        if (!Template.hasTags(text)) return ok(text)
        return Template.parse(text).flatMap { parts ->
            // Nothing dynamic survived parsing (only comments, or escaped tags),
            // so no compiler is needed.
            val dynamic = parts.any { it !is Part.Literal }
            if (!dynamic) {
                ok(parts.filterIsInstance<Part.Literal>().joinToString("") { it.text })
            } else {
                run(Template.emit(parts), onSink, timeoutMillis)
            }
        }
    }

    private fun run(
        emitted: Emitted,
        onSink: (Sink) -> Unit,
        timeoutMillis: Long?
    ): Outcome<String> {
        val sink = Sink()
        if (!engine.bind(Template.sinkName, sink)) {
            return err("could not prepare the template sink in the interpreter")
        }
        onSink(sink)

        val result = if (timeoutMillis == null) engine.eval(emitted.code) else engine.eval(emitted.code, timeoutMillis)
        return if (result.ok) ok(sink.result) else err(explain(result.toToolOutput(), emitted))
    }

    /** Reports the failure against the template, not the generated program.
      *
      * The generated code is an implementation detail the user never wrote, so a
      * bare compiler diagnostic pointing into it is close to useless. Line
      * numbers are translated where they can be, and the annotated program is
      * appended so the remaining cases are still debuggable.
      */
    private fun explain(diagnostics: String, emitted: Emitted): String {
        fun translate(pattern: Regex, render: (Int) -> String, text: String): String =
            pattern.replace(text) { m ->
                val templateLine = emitted.templateLineFor(m.groupValues[1].toInt())
                if (templateLine != null) render(templateLine) else m.value
            }

        // Two spellings, because Groovy uses both: a `snippet.groovy: 12:`
        // position on a compile failure, and an `@ line 12, column 5` pair on
        // the detail line under it. Translating only the first left the most
        // common diagnostic of all pointing into a program the user never wrote.
        val afterLinePattern = translate(LinePattern, { l -> "template line $l:" }, diagnostics)
        val translated = translate(OnLinePattern, { l -> "on template line $l" }, afterLinePattern)
            .let { ClassPattern.replace(it, "") }

        val annotated = emitted.code.lineSequence().withIndex().joinToString("\n") { (i, l) ->
            val tl = emitted.templateLineFor(i + 1)?.toString() ?: "?"
            "%4s | %s".format(tl, l)
        }

        return """template failed to compile or run:
            |$translated
            |
            |generated program (left column is your template line):
            |$annotated""".trimMargin()
    }

    /** Matches the `snippet7.groovy: <line>:` position Groovy puts at the head
      * of a compile failure.
      *
      * The script name is consumed rather than left in place: it is a counter,
      * it means nothing to the reader, and leaving it produced
      * `snippet263template line 1::`. A template line is emitted as one
      * generated line, so the number maps straight across. */
    private val LinePattern = Regex("""\bsnippet\d+\.groovy:\s*(\d+):""")

    /** The `@ line <line>, column <column>` form Groovy repeats underneath. */
    private val OnLinePattern = Regex("""@ line (\d+), column (\d+)""")

    /** `for class: snippet7` in a runtime failure. Same reasoning: the generated
      * class name is noise the reader cannot act on. */
    private val ClassPattern = Regex("""\s*for class: snippet\d+""")
}
