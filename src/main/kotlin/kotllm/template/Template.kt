package kotllm.template

import kotllm.*

/** One piece of a parsed template. */
sealed interface Part {

    /** Text passed through untouched. */
    data class Literal(val text: String) : Part

    /** `<%= ... %>` -- evaluated, converted to a string, and emitted. */
    data class Expression(val code: String, val line: Int) : Part

    /** `<% ... %>` -- evaluated for its effect, emitting nothing itself. */
    data class Statements(val code: String, val line: Int) : Part
}

data class TemplateError(val message: String, val line: Int, val column: Int) {
    val describe: String get() = "template error at line $line, column $column: $message"
}

/** The emitted Scala program, plus a map back to template lines.
  *
  * `lineMap[i]` is the template line that generated line `i + 1` came from, so
  * a compiler diagnostic pointing at generated code can be reported against
  * the template the user actually wrote.
  */
data class Emitted(val code: String, val lineMap: List<Int>) {
    fun templateLineFor(generatedLine: Int): Int? = lineMap.getOrNull(generatedLine - 1)
}

/** JSP-style client-side templating.
  *
  * Expansion happens locally, before anything is sent: the model receives only
  * the resulting text and never sees the tags. That is the point -- assembling a
  * prompt from files, command output or computed values is the client's job.
  *
  *   - `<% statements %>`  Scala statements, emitting nothing themselves
  *   - `<%= expression %>` evaluated and interpolated
  *   - `<%-- comment --%>` dropped
  *   - `<%%`               a literal `<%`
  *
  * Chosen over `${...}` and Twirl-style `@` because a prompt very often contains
  * shell snippets, JSON or pasted code, which collide with both.
  *
  * Known limitation, shared with every templating language of this family: a
  * `%>` inside a string literal within a tag ends the tag early. Write it as
  * `"%" + ">"`.
  */
object Template {

    private const val OutputSink = "__kotllm_out"

    /** True when the text contains anything needing expansion.
      *
      * Checked before doing any work: a plain prompt must not pay for starting a
      * compiler, which is most prompts.
      */
    fun hasTags(text: String): Boolean = text.contains("<%")

    fun parse(text: String): Outcome<List<Part>> {
        val parts = mutableListOf<Part>()
        val literal = StringBuilder()
        var i = 0
        var line = 1
        var col = 1

        fun advanceOver(s: String) {
            for (c in s) {
                if (c == '\n') {
                    line += 1
                    col = 1
                } else {
                    col += 1
                }
            }
        }

        fun flushLiteral() {
            if (literal.isNotEmpty()) {
                parts.add(Part.Literal(literal.toString()))
                literal.setLength(0)
            }
        }

        while (i < text.length) {
            val openAt = text.indexOf("<%", i)
            if (openAt < 0) {
                literal.append(text.substring(i))
                i = text.length
            } else {
                val before = text.substring(i, openAt)
                literal.append(before)
                advanceOver(before)

                val tagLine = line
                val tagCol = col

                if (text.startsWith("<%%", openAt)) {
                    // Escape: emit a literal <% and carry on.
                    literal.append("<%")
                    advanceOver("<%%")
                    i = openAt + 3
                } else if (text.startsWith("<%--", openAt)) {
                    val closeAt = text.indexOf("--%>", openAt + 4)
                    if (closeAt < 0) {
                        return err(TemplateError("unterminated <%-- comment, expected --%>", tagLine, tagCol).describe)
                    }
                    val body = text.substring(openAt, closeAt + 4)
                    // Newlines inside a comment are preserved as literal text so the
                    // surrounding prose keeps its shape; dropping them would silently
                    // join paragraphs.
                    literal.append("\n".repeat(body.count { it == '\n' }))
                    advanceOver(body)
                    i = closeAt + 4
                } else {
                    val isExpression = text.startsWith("<%=", openAt)
                    val bodyStart = if (isExpression) openAt + 3 else openAt + 2
                    val closeAt = text.indexOf("%>", bodyStart)
                    if (closeAt < 0) {
                        val tag = if (isExpression) "<%=" else "<%"
                        return err(TemplateError("unterminated $tag tag, expected %>", tagLine, tagCol).describe)
                    }

                    val body = text.substring(bodyStart, closeAt)
                    if (isExpression && body.trim().isEmpty()) {
                        return err(TemplateError("empty <%= %> tag has nothing to evaluate", tagLine, tagCol).describe)
                    }

                    flushLiteral()
                    if (isExpression) parts.add(Part.Expression(body.trim(), tagLine))
                    else if (body.trim().isNotEmpty()) parts.add(Part.Statements(body, tagLine))

                    advanceOver(text.substring(openAt, closeAt + 2))
                    i = closeAt + 2
                }
            }
        }

        flushLiteral()
        return ok(parts.toList())
    }

    /** Turns parsed parts into a Kotlin program that fills the bound sink.
      *
      * Appending to a sink rather than returning a value keeps the result out of
      * the interpreter transcript and away from any output truncation, and means
      * a template can be arbitrarily large.
      */
    fun emit(parts: List<Part>): Emitted {
        val out = StringBuilder()
        val lineMap = mutableListOf<Int>()

        fun addLine(code: String, templateLine: Int) {
            out.append(code).append('\n')
            lineMap.add(templateLine)
        }

        var templateLine = 1

        for (part in parts) {
            when (part) {
                is Part.Literal -> {
                    // One append per template line, so generated and template line numbers
                    // stay in step and a diagnostic can be mapped back.
                    val segments = splitKeepingNewlines(part.text)
                    for (seg in segments) {
                        addLine("$OutputSink.append(${quote(seg)})", templateLine)
                        if (seg.endsWith("\n")) templateLine += 1
                    }
                }

                is Part.Expression -> {
                    templateLine = part.line
                    // A Groovy closure called immediately, not a bare block: an
                    // expression tag may hold several statements ending in a
                    // value, and `append(a; b)` is not valid. `{ ... }()` keeps
                    // the tag's own scope and appends what it returns.
                    addLine("$OutputSink.append({ ${part.code} }())", part.line)
                }

                is Part.Statements -> {
                    templateLine = part.line
                    // Verbatim, preserving internal newlines, so the user's own code is
                    // what the compiler sees and reports on.
                    part.code.split("\n").forEach { l ->
                        addLine(l, templateLine)
                        templateLine += 1
                    }
                }
            }
        }

        return Emitted(out.toString(), lineMap.toList())
    }

    /** Splits text into pieces that each end with a newline, plus any trailing
      * remainder, so line accounting is exact. */
    private fun splitKeepingNewlines(text: String): List<String> {
        val pieces = mutableListOf<String>()
        var start = 0
        var i = 0
        while (i < text.length) {
            if (text[i] == '\n') {
                pieces.add(text.substring(start, i + 1))
                start = i + 1
            }
            i += 1
        }
        if (start < text.length) pieces.add(text.substring(start))
        return pieces
    }

    /** A Scala string literal for arbitrary text.
      *
      * Escaped explicitly rather than using triple quotes, which cannot contain
      * a `"""` sequence and would break on any template that does.
      */
    internal fun quote(s: String): String {
        val sb = StringBuilder("\"")
        for (c in s) {
            when {
                c == '\\' -> sb.append("\\\\")
                c == '"' -> sb.append("\\\"")
                c == '\n' -> sb.append("\\n")
                c == '\r' -> sb.append("\\r")
                c == '\t' -> sb.append("\\t")
                c == '$' -> sb.append("\$") // not an interpolator, so a dollar is literal
                c < ' ' || c == '\u007f' -> sb.append("\\u%04x".format(c.code))
                else -> sb.append(c)
            }
        }
        sb.append('"')
        return sb.toString()
    }

    /** The sink identifier the emitted program appends to. */
    val sinkName: String get() = OutputSink
}
