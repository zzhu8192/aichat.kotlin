package kotllm.template

import kotllm.*

/** One harness variable. */
data class VarDef(val name: String, val description: String, val value: () -> String)

/** `{{name}}` substitution for facts about the harness.
  *
  * A separate, lighter mechanism from `<% %>`: that one runs arbitrary Scala,
  * this only interpolates known values, so the common case -- dropping the model
  * name into a prompt -- needs no compiler and cannot fail interestingly.
  *
  * `{{ }}` rather than `${ }` because a prompt very often contains shell or JS
  * that would collide, and because aichat already uses this spelling.
  *
  * Runs *before* `<% %>`, so a value can be used inside Scala code:
  * `<% val m = "{{model}}" %>` works. Escape the opening braces for a literal.
  */
object Vars {

    /** A reference is a conservative identifier shape. Anything else inside
      * braces -- prose, JSON, Handlebars intended for something else -- is left
      * exactly as it was found rather than guessed at. */
    private val Reference = Regex("""\{\{\s*([A-Za-z_][A-Za-z0-9_.]*)\s*\}\}""")

    fun hasReferences(text: String): Boolean = text.contains("{{")

    /** Substitutes every `{{name}}`.
      *
      * An unknown but reference-shaped name is an error rather than a
      * pass-through: silently leaving `{{modle}}` in a prompt would send the
      * model a literal brace expression and look like the feature was broken.
      */
    fun expand(text: String, table: Map<String, () -> String>): Outcome<String> {
        if (!hasReferences(text) && !text.contains("\\{{")) return ok(text)

        val out = StringBuilder()
        var i = 0
        val unknown = mutableListOf<String>()

        while (i < text.length) {
            if (text.startsWith("\\{{", i)) {
                // Escaped: the braces are literal, the backslash is consumed.
                out.append("{{")
                i += 3
            } else if (text.startsWith("{{", i)) {
                val m = Reference.matchAt(text, i)
                if (m != null) {
                    val name = m.groupValues[1]
                    val thunk = table[name]
                    if (thunk != null) out.append(guard(safely(thunk)))
                    else unknown.add(name)
                    i = m.range.last + 1
                } else {
                    // Not a reference at all; pass the braces through untouched.
                    out.append("{{")
                    i += 2
                }
            } else {
                out.append(text[i])
                i += 1
            }
        }

        return if (unknown.isEmpty()) {
            ok(out.toString())
        } else {
            val details = unknown.distinct().map { n ->
                val hint = suggest(n, table.keys.toList())?.let { s -> "; did you mean {{$s}}?" } ?: ""
                "{{$n}}$hint"
            }
            err(
                "unknown harness variable ${details.joinToString(", ")}. " +
                    "Run /vars to list them, or escape the braces as \\{{...}}."
            )
        }
    }

    /** A variable whose computation fails must not take down the prompt.
      *
      * Takes `() -> String?` although the table is `() -> String`: a caller
      * outside this module's type-checking can supply a thunk that returns
      * null, which `VarsTest` pins with an unchecked cast. */
    private fun safely(thunk: () -> String?): String =
        try {
            thunk() ?: ""
        } catch (e: Exception) {
            "(unavailable: ${e.javaClass.simpleName})"
        }

    /** Neutralises any `<%` inside a substituted value.
      *
      * Substitution happens before templating, so a value containing `<%` --
      * `cmdline` plausibly could -- would otherwise be executed as Scala. A
      * harness fact should never become code.
      */
    private fun guard(value: String): String =
        if (value.contains("<%")) value.replace("<%", "<%%") else value

    /** Closest name by edit distance, when it is close enough to be a likely
      * typo rather than a different word entirely. */
    internal fun suggest(name: String, candidates: List<String>): String? {
        val scored = candidates.map { c -> c to distance(name.lowercase(), c.lowercase()) }
        return scored.filter { (c, d) -> d <= kotlin.math.max(2, c.length / 3) }
            .sortedBy { it.second }
            .firstOrNull()
            ?.first
    }

    private fun distance(a: String, b: String): Int {
        val prev = IntArray(b.length + 1) { it }
        val cur = IntArray(b.length + 1)
        for (i in 1..a.length) {
            cur[0] = i
            for (j in 1..b.length) {
                val cost = if (a[i - 1] == b[j - 1]) 0 else 1
                cur[j] = kotlin.math.min(kotlin.math.min(cur[j - 1] + 1, prev[j] + 1), prev[j - 1] + cost)
            }
            cur.copyInto(prev)
        }
        return prev[b.length]
    }
}
