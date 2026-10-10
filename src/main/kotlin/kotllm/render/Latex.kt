package kotllm.render

import kotllm.util.Glyphs

/** LaTeX, rendered as far as a terminal reasonably can.
 *
 * Models emit LaTeX for anything mathematical whether or not it was asked for,
 * so the choice is not "render maths or not" -- it is between showing
 * `\frac{4x}{x+2}=8` and showing `(4x)/(x+2) = 8`.
 *
 * **Not a typesetter.** No stacked fractions, no matrices in box characters, no
 * integral signs spanning rows. A fraction becomes a division, a superscript
 * becomes a superscript digit where Unicode has one, and a command with no
 * plain-text form keeps its name rather than vanishing -- a silently dropped
 * operator changes what the answer says.
 *
 * Every substitution has an ASCII fallback, gated on [Glyphs.unicode]. This
 * is the file that would break that rule most easily, and the cost is not
 * cosmetic: `!=` on a cp437 console beats `?`.
 */
object Latex {

    /** `\command` -> what to show, Unicode first and ASCII when the console
     * cannot encode it.
     *
     * The ASCII side is chosen to stay *unambiguous* rather than to look
     * similar: `\leq` falls back to `<=`, not to `=`, because a wrong operator
     * in a maths answer is worse than an ugly one.
     */
    private val symbols: List<Triple<String, String, String>> = listOf(
        // relations
        Triple("neq", "≠", "!="),
        Triple("leq", "≤", "<="),
        Triple("le", "≤", "<="),
        Triple("geq", "≥", ">="),
        Triple("ge", "≥", ">="),
        Triple("ll", "≪", "<<"),
        Triple("gg", "≫", ">>"),
        Triple("approx", "≈", "~="),
        Triple("equiv", "≡", "=="),
        Triple("propto", "∝", "prop to"),
        Triple("sim", "~", "~"),
        // arithmetic
        Triple("times", "×", "*"),
        Triple("cdot", "·", "*"),
        Triple("div", "÷", "/"),
        Triple("pm", "±", "+/-"),
        Triple("mp", "∓", "-/+"),
        Triple("ast", "*", "*"),
        // arrows
        Triple("to", "→", "->"),
        Triple("rightarrow", "→", "->"),
        Triple("leftarrow", "←", "<-"),
        Triple("Rightarrow", "⇒", "=>"),
        Triple("Leftarrow", "⇐", "<="),
        Triple("leftrightarrow", "↔", "<->"),
        Triple("implies", "⇒", "=>"),
        Triple("iff", "⇔", "<=>"),
        Triple("mapsto", "↦", "|->"),
        // sets and logic
        Triple("in", "∈", " in "),
        Triple("notin", "∉", " not in "),
        Triple("subset", "⊂", " subset "),
        Triple("subseteq", "⊆", " subseteq "),
        Triple("supset", "⊃", " superset "),
        Triple("cup", "∪", " union "),
        Triple("cap", "∩", " intersect "),
        Triple("emptyset", "∅", "{}"),
        Triple("varnothing", "∅", "{}"),
        Triple("forall", "∀", "for all "),
        Triple("exists", "∃", "there exists "),
        Triple("neg", "¬", "not "),
        Triple("land", "∧", " and "),
        Triple("lor", "∨", " or "),
        // big operators and calculus
        Triple("sum", "∑", "sum"),
        Triple("prod", "∏", "prod"),
        Triple("int", "∫", "integral"),
        Triple("iint", "∬", "integral2"),
        Triple("oint", "∮", "integral"),
        Triple("partial", "∂", "d"),
        Triple("nabla", "∇", "grad"),
        Triple("infty", "∞", "infinity"),
        Triple("lim", "lim", "lim"),
        // punctuation
        Triple("ldots", "…", "..."),
        Triple("dots", "…", "..."),
        Triple("cdots", "⋯", "..."),
        Triple("vdots", "⋮", "..."),
        Triple("therefore", "∴", "therefore"),
        Triple("because", "∵", "because"),
        Triple("angle", "∠", "angle "),
        Triple("degree", "°", " deg"),
        Triple("circ", "°", " deg"),
        Triple("prime", "'", "'"),
        Triple("percent", "%", "%"),
        // greek, lower
        Triple("alpha", "α", "alpha"),
        Triple("beta", "β", "beta"),
        Triple("gamma", "γ", "gamma"),
        Triple("delta", "δ", "delta"),
        Triple("epsilon", "ε", "epsilon"),
        Triple("varepsilon", "ε", "epsilon"),
        Triple("zeta", "ζ", "zeta"),
        Triple("eta", "η", "eta"),
        Triple("theta", "θ", "theta"),
        Triple("iota", "ι", "iota"),
        Triple("kappa", "κ", "kappa"),
        Triple("lambda", "λ", "lambda"),
        Triple("mu", "μ", "mu"),
        Triple("nu", "ν", "nu"),
        Triple("xi", "ξ", "xi"),
        Triple("pi", "π", "pi"),
        Triple("rho", "ρ", "rho"),
        Triple("sigma", "σ", "sigma"),
        Triple("tau", "τ", "tau"),
        Triple("upsilon", "υ", "upsilon"),
        Triple("phi", "φ", "phi"),
        Triple("varphi", "φ", "phi"),
        Triple("chi", "χ", "chi"),
        Triple("psi", "ψ", "psi"),
        Triple("omega", "ω", "omega"),
        // greek, upper
        Triple("Gamma", "Γ", "Gamma"),
        Triple("Delta", "Δ", "Delta"),
        Triple("Theta", "Θ", "Theta"),
        Triple("Lambda", "Λ", "Lambda"),
        Triple("Xi", "Ξ", "Xi"),
        Triple("Pi", "Π", "Pi"),
        Triple("Sigma", "Σ", "Sigma"),
        Triple("Phi", "Φ", "Phi"),
        Triple("Psi", "Ψ", "Psi"),
        Triple("Omega", "Ω", "Omega")
    )

    /** Longest name first, so `\leq` is not eaten by `\le` and `\subseteq` not
     * by `\subset`. */
    val ordered: List<Triple<String, String, String>> = symbols.sortedByDescending { it.first.length }

    private fun glyph(uni: String, ascii: String): String = if (Glyphs.unicode) uni else ascii

    val superscripts: Map<Char, Char> = mapOf(
        '0' to '⁰', '1' to '¹', '2' to '²', '3' to '³', '4' to '⁴',
        '5' to '⁵', '6' to '⁶', '7' to '⁷', '8' to '⁸', '9' to '⁹',
        '+' to '⁺', '-' to '⁻', 'n' to 'ⁿ', 'i' to 'ⁱ'
    )

    val subscripts: Map<Char, Char> = mapOf(
        '0' to '₀', '1' to '₁', '2' to '₂', '3' to '₃', '4' to '₄',
        '5' to '₅', '6' to '₆', '7' to '₇', '8' to '₈', '9' to '₉',
        '+' to '₊', '-' to '₋'
    )

    /** True when a line is nothing but an opening display-math delimiter. */
    fun opensDisplay(line: String): Boolean {
        val t = line.trim()
        return (t == "\\[" || t == "$$") ||
                (t.startsWith("\\[") && !t.endsWith("\\]")) ||
                (t.startsWith("$$") && t.length > 2 && !t.endsWith("$$"))
    }

    fun closesDisplay(line: String): Boolean {
        val t = line.trim()
        return t == "\\]" || t == "$$" || t.endsWith("\\]") || (t.length > 2 && t.endsWith("$$"))
    }

    /** A whole line of display maths: delimiters removed, body converted. */
    fun display(line: String): String {
        val t = line.trim()
            .removePrefix("\\[").removeSuffix("\\]")
            .removePrefix("$$").removeSuffix("$$")
            .trim()
        return if (t.isEmpty()) "" else convert(t)
    }

    /** Converts inline maths inside a line of prose, leaving the prose alone.
     *
     * Three delimiters, and `$...$` is the awkward one: `it costs $5 and $10`
     * is not a formula. So a single-dollar span only counts when it contains
     * something that could not be currency -- a backslash command, a caret or
     * an underscore. Getting that wrong in the cautious direction leaves
     * `$x^2$` unrendered; getting it wrong the other way eats a price list.
     */
    fun inlineSpans(raw: String): String {
        var out = raw

        // \( ... \)
        out = Regex("""\\\((.+?)\\\)""").replace(out) { m -> convert(m.groupValues[1]) }
        // $$ ... $$ on one line
        out = Regex("""\$\$(.+?)\$\$""").replace(out) { m -> convert(m.groupValues[1]) }
        // $ ... $, only when it looks like maths
        out = Regex("""\$([^$\n]+)\$""").replace(out) { m ->
            if (mathish(m.groupValues[1])) convert(m.groupValues[1]) else m.value
        }
        // \[ ... \] that the model put inline rather than on its own lines
        out = Regex("""\\\[(.+?)\\\]""").replace(out) { m -> convert(m.groupValues[1]) }
        return out
    }

    private fun mathish(s: String): Boolean =
        s.contains('\\') || s.contains('^') || s.contains('_')

    /** The body of a formula, with no delimiters. */
    fun convert(inputMath: String): String {
        var s = inputMath

        // Structure first, because these carry braces the symbol pass would see.
        s = replaceFractions(s)
        s = replaceCommandWithArg(s, "sqrt") { a -> glyph("√", "sqrt") + paren(a) }
        // A boxed answer is the point of the whole formula, so it stays marked.
        s = replaceCommandWithArg(s, "boxed") { a -> "[ $a ]" }
        listOf("text", "mathrm", "mathbf", "mathit", "mathsf", "operatorname", "textbf").forEach { c ->
            s = replaceCommandWithArg(s, c) { a -> a }
        }

        // Sizing and spacing carry no meaning in a terminal.
        s = s.replace("\\left", "").replace("\\right", "")
        s = s.replace("\\quad", "  ").replace("\\qquad", "    ")
        s = s.replace("\\,", " ").replace("\\;", " ").replace("\\:", " ").replace("\\!", "")
        s = s.replace("\\\\", "  ")

        s = replaceScripts(s)

        ordered.forEach { (name, uni, ascii) ->
            // The boundary stops `\int` matching inside `\intercal`; a trailing
            // brace or digit is a legal end to a command name.
            s = s.replace(
                Regex("""\\""" + name + """(?![A-Za-z])"""),
                // Quoted because this overload takes a replacement *pattern*,
                // where `$` and `\` are group references -- and a glyph table
                // is exactly where backslashes and dollars live. The lambda
                // form of `Regex.replace` below is literal and must not be
                // quoted; Scala needed it on both.
                java.util.regex.Matcher.quoteReplacement(glyph(uni, ascii))
            )
        }

        // Anything still `\name` is a command with no plain-text form. Keep the
        // name rather than deleting it: a silently dropped operator changes what
        // the answer says.
        s = Regex("""\\([A-Za-z]+)""").replace(s) { m -> m.groupValues[1] }
        s = s.replace("\\{", "{").replace("\\}", "}")

        return s.replace(Regex("[ \t]+"), " ").trim()
    }

    /** `\frac{a}{b}` -> `a/b`, parenthesising anything that is not a single
     * token, so `\frac{4x}{x+2}` reads `(4x)/(x+2)` and not `4x/x+2`, which
     * means something else entirely. */
    private fun replaceFractions(input: String): String {
        var s = input
        listOf("dfrac", "tfrac", "frac").forEach { name ->
            var guard = 0
            var idx = s.indexOf("\\" + name)
            while (idx >= 0 && guard < 100) {
                val afterName = idx + name.length + 1
                val numResult = readGroup(s, afterName)
                when {
                    numResult != null -> {
                        val (num, afterNum) = numResult
                        val denResult = readGroup(s, afterNum)
                        if (denResult != null) {
                            val (den, afterDen) = denResult
                            s = s.substring(0, idx) + paren(num) + "/" + paren(den) + s.substring(afterDen)
                        } else {
                            guard = 100
                        }
                    }
                    else -> guard = 100
                }
                guard += 1
                idx = s.indexOf("\\" + name)
            }
        }
        return s
    }

    /** A `{...}` group starting at `from` (skipping spaces), with brace
     * nesting honoured so `\frac{\frac{1}{2}}{3}` does not end early. */
    fun readGroup(s: String, from: Int): Pair<String, Int>? {
        var i = from
        while (i < s.length && s[i] == ' ') i += 1
        if (i >= s.length) return null
        else if (s[i] != '{') {
            // `\frac12` is legal LaTeX: one character each.
            return s[i].toString() to (i + 1)
        } else {
            var depth = 0
            var j = i
            while (j < s.length) {
                if (s[j] == '{') depth += 1
                else if (s[j] == '}') {
                    depth -= 1
                    if (depth == 0) return s.substring(i + 1, j) to (j + 1)
                }
                j += 1
            }
            return null
        }
    }

    /** Parenthesise unless it is already a single token or already wrapped. */
    private fun paren(raw: String): String {
        val s = convert(raw).trim()
        if (s.length <= 1) return s
        else if (s.startsWith("(") && s.endsWith(")")) return s
        // A bare number or a bare identifier needs no help; `4x` is a product and
        // does, because `4x/(x+2)` is right only if the reader applies precedence
        // and `(4x)/(x+2)` is right on sight.
        else if (s.all { it.isDigit() } || s.all { it.isLetter() }) return s
        else return "($s)"
    }

    private fun replaceCommandWithArg(input: String, name: String, f: (String) -> String): String {
        var s = input
        var guard = 0
        var idx = s.indexOf("\\" + name)
        while (idx >= 0 && guard < 100) {
            val groupResult = readGroup(s, idx + name.length + 1)
            when {
                groupResult != null -> {
                    val (arg, after) = groupResult
                    s = s.substring(0, idx) + f(convert(arg)) + s.substring(after)
                }
                else -> guard = 100
            }
            guard += 1
            idx = s.indexOf("\\" + name)
        }
        return s
    }

    /** `x^2` -> `x²`, `a_1` -> `a₁`, when every character has a form. Left as
     * written otherwise: `x^(n+1)` is clearer than a half-converted mess, and
     * on a console with no Unicode it is the only option anyway. */
    private fun replaceScripts(input: String): String {
        fun convertRun(body: String, table: Map<Char, Char>, marker: String): String =
            if (Glyphs.unicode && body.isNotEmpty() && body.all { it in table })
                body.map { table[it] }.joinToString("")
            else marker + (if (body.length == 1) body else "($body)")

        var s = input
        s = Regex("""\^\{([^{}]*)\}""").replace(s) { m -> convertRun(m.groupValues[1], superscripts, "^") }
        s = Regex("""_\{([^{}]*)\}""").replace(s) { m -> convertRun(m.groupValues[1], subscripts, "_") }
        s = Regex("""\^(-?[0-9A-Za-z])""").replace(s) { m -> convertRun(m.groupValues[1], superscripts, "^") }
        s = Regex("""_(-?[0-9A-Za-z])""").replace(s) { m -> convertRun(m.groupValues[1], subscripts, "_") }
        return s
    }
}
