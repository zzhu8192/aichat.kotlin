package kotllm.input

object Html {

    private val Dropped = setOf(
        "script", "style", "noscript", "svg", "head", "template", "iframe", "object", "canvas",
        "audio", "video", "map", "select", "datalist"
    )

    private val Blocks = setOf(
        "p", "div", "section", "article", "header", "footer", "main", "aside", "figure",
        "figcaption", "form", "fieldset", "address", "details", "summary", "dl", "dt", "dd",
        "nav", "center"
    )

    private val Void = setOf(
        "br", "hr", "img", "input", "meta", "link", "base", "col", "area", "source", "track",
        "embed", "param", "wbr"
    )

    fun looksLikeHtml(text: String): Boolean {
        val head = text.take(4000).lowercase()
        return head.contains("<!doctype html") || head.contains("<html") || head.contains("<body") || {
            val hits = listOf("<div", "<p>", "<p ", "<span", "<table", "<h1", "<h2", "<a href").count { head.contains(it) }
            hits >= 3
        }()
    }

    fun plainText(fragment: String): String =
        collapse(decode(fragment.replace("(?s)<[^>]*>".toRegex(), " ")))
            .replace("""\s+([.,;:!?)])""".toRegex(), "$1")
            .replace("""([(])\s+""".toRegex(), "$1")
            .trim()

    fun toMarkdown(html: String): String {
        val lower = html.lowercase()
        val out = Out()
        val lists = ArrayDeque<ListState>()
        var pre = 0
        var href: String? = null

        var i = 0
        val n = html.length

        while (i < n) {
            when {
                html[i] == '<' && html.startsWith("<!--", i) -> {
                    i = after(html, i, "-->")
                }
                html[i] == '<' && i + 1 < n && startsTag(html[i + 1]) -> {
                    val tag = readTag(html, i)
                    i = tag.end

                    when {
                        Dropped.contains(tag.name) -> {
                            if (!tag.closing && !tag.selfClosing) i = afterClosing(lower, i, tag.name)
                        }
                        tag.name == "br" -> out.newline()
                        tag.name == "hr" -> {
                            out.blank()
                            out.append("---")
                            out.blank()
                        }
                        tag.name == "img" -> {
                            attr(tag.attrs, "alt")?.trim()?.takeIf { it.isNotEmpty() }?.let { a ->
                                out.append("[image: $a]")
                            }
                        }
                        tag.name.length == 2 && tag.name[0] == 'h' && tag.name[1].isDigit() -> {
                            val level = tag.name[1].digitToInt()
                            if (tag.closing) {
                                out.blank()
                            } else {
                                out.blank()
                                out.append("#".repeat(kotlin.math.max(1, kotlin.math.min(6, level))) + " ")
                            }
                        }
                        tag.name == "pre" -> {
                            if (tag.closing) {
                                pre = kotlin.math.max(0, pre - 1)
                                out.verbatim("\n```")
                                out.blank()
                            } else {
                                out.blank()
                                out.verbatim("```\n")
                                pre += 1
                            }
                        }
                        tag.name == "code" && pre == 0 -> out.append("`")
                        tag.name == "strong" || tag.name == "b" -> out.append("**")
                        tag.name == "em" || tag.name == "i" -> out.append("*")
                        tag.name == "a" -> {
                            if (tag.closing) {
                                href?.let { h -> out.append("]($h)") }
                                href = null
                            } else {
                                href = attr(tag.attrs, "href")?.trim()?.takeIf { usableHref(it) }
                                if (href != null) out.append("[")
                            }
                        }
                        tag.name == "ul" || tag.name == "ol" -> {
                            if (tag.closing) {
                                if (lists.isNotEmpty()) lists.removeLast()
                                out.blank()
                            } else {
                                out.blank()
                                lists.add(ListState(tag.name == "ol"))
                            }
                        }
                        tag.name == "li" -> {
                            if (!tag.closing) {
                                out.newline()
                                val depth = kotlin.math.max(0, lists.size - 1)
                                val marker = lists.lastOrNull()?.next() ?: "- "
                                out.append("  ".repeat(depth) + marker)
                            }
                        }
                        tag.name == "tr" -> {
                            if (!tag.closing) out.newline()
                        }
                        tag.name == "td" || tag.name == "th" -> {
                            if (tag.closing) out.append(" | ")
                        }
                        tag.name == "blockquote" -> {
                            out.blank()
                            if (!tag.closing) out.append("> ")
                        }
                        Blocks.contains(tag.name) -> out.blank()
                    }
                }
                else -> {
                    val stop = html.indexOf('<', i + 1).takeIf { it >= 0 } ?: n
                    val raw = decode(html.substring(i, stop))
                    if (pre > 0) out.verbatim(raw) else out.append(collapse(raw))
                    i = stop
                }
            }
        }

        return tidy(out.result)
    }

    private fun startsTag(c: Char): Boolean = c.isLetter() || c == '/' || c == '!'

    private fun usableHref(h: String): Boolean =
        !h.startsWith("#") && !h.lowercase().startsWith("javascript:") && !h.startsWith("data:")

    private class ListState(val ordered: Boolean) {
        private var index = 0
        fun next(): String {
            index += 1
            return if (ordered) "$index. " else "- "
        }
    }

    private data class Tag(
        val name: String,
        val closing: Boolean,
        val selfClosing: Boolean,
        val attrs: String,
        val end: Int
    )

    private fun readTag(html: String, start: Int): Tag {
        var i = start + 1
        val n = html.length
        val close = i < n && html[i] == '/'
        if (close) i += 1

        val nameStart = i
        while (i < n && (html[i].isLetterOrDigit() || html[i] == '-' || html[i] == '!')) i += 1
        val name = html.substring(nameStart, i).lowercase()

        val attrStart = i
        var quote = ' '
        var done = false
        while (i < n && !done) {
            val c = html[i]
            if (quote != ' ') {
                if (c == quote) quote = ' '
            } else if (c == '"' || c == '\'') {
                quote = c
            } else if (c == '>') {
                done = true
            }
            i += 1
        }
        val attrs = html.substring(attrStart, kotlin.math.max(attrStart, i - 1))
        return Tag(name, close, attrs.trim().endsWith("/") || Void.contains(name), attrs, i)
    }

    private fun attr(attrs: String, name: String): String? {
        val pattern = ("(?i)\\b" + name + "\\s*=\\s*(\"([^\"]*)\"|'([^']*)'|([^\\s\"'>]+))").toRegex()
        return pattern.find(attrs)?.let { m ->
            decode(m.groupValues.getOrNull(2) ?: m.groupValues.getOrNull(3) ?: m.groupValues.getOrNull(4) ?: "")
        }
    }

    private fun after(html: String, from: Int, marker: String): Int =
        html.indexOf(marker, from).takeIf { it >= 0 }?.let { it + marker.length } ?: html.length

    private fun afterClosing(lower: String, from: Int, name: String): Int {
        val idx = lower.indexOf("</$name", from)
        return if (idx < 0) {
            lower.length
        } else {
            lower.indexOf('>', idx).takeIf { it >= 0 }?.let { it + 1 } ?: lower.length
        }
    }

    private fun collapse(text: String): String = text.replace("\\s+".toRegex(), " ")

    private val Entities = mapOf(
        "amp" to "&", "lt" to "<", "gt" to ">", "quot" to "\"", "apos" to "'", "nbsp" to " ",
        "ndash" to "-", "mdash" to "--", "hellip" to "...", "middot" to "-", "bull" to "-",
        "ldquo" to "\"", "rdquo" to "\"", "lsquo" to "'", "rsquo" to "'", "laquo" to "<<",
        "raquo" to ">>", "copy" to "(c)", "reg" to "(R)", "trade" to "(TM)", "deg" to " deg",
        "times" to "x", "divide" to "/", "plusmn" to "+/-", "frac12" to "1/2", "frac14" to "1/4",
        "sup2" to "^2", "sup3" to "^3", "euro" to "EUR", "pound" to "GBP", "yen" to "JPY",
        "cent" to "c", "sect" to "S", "para" to "P", "dagger" to "+", "permil" to "o/oo",
        "larr" to "<-", "rarr" to "->", "harr" to "<->", "hearts" to "<3", "check" to "v",
        "shy" to "", "zwj" to "", "zwnj" to "", "ensp" to " ", "emsp" to " ", "thinsp" to " "
    )

    internal fun decode(text: String): String {
        if (!text.contains('&')) return text
        val sb = StringBuilder(text.length)
        var i = 0
        while (i < text.length) {
            val c = text[i]
            if (c != '&') {
                sb.append(c)
                i += 1
            } else {
                val semi = text.indexOf(';', i)
                if (semi < 0 || semi - i > 12) {
                    sb.append(c)
                    i += 1
                } else {
                    val body = text.substring(i + 1, semi)
                    val decoded = when {
                        body.startsWith("#x") || body.startsWith("#X") -> codePoint(body.drop(2), 16)
                        body.startsWith("#") -> codePoint(body.drop(1), 10)
                        else -> Entities[body.lowercase()]
                    }
                    if (decoded != null) {
                        sb.append(decoded)
                        i = semi + 1
                    } else {
                        sb.append(c)
                        i += 1
                    }
                }
            }
        }
        return sb.toString()
    }

    private fun codePoint(digits: String, radix: Int): String? = runCatching {
        val cp = Integer.parseInt(digits, radix)
        if (cp <= 0 || cp > Character.MAX_CODE_POINT) null else String(Character.toChars(cp))
    }.getOrNull()

    private fun tidy(text: String): String =
        text.split("\n")
            .map { it.replace("\\s+$".toRegex(), "").replace("\\s\\|$".toRegex(), "") }
            .joinToString("\n")
            .replace("\n{3,}".toRegex(), "\n\n")
            .trim()

    private class Out {
        private val sb = StringBuilder()
        private var breaks = 2

        fun append(s: String) {
            if (s.isNotEmpty() && !(s == " " && breaks > 0)) {
                sb.append(s)
                breaks = 0
            }
        }

        fun newline() {
            if (breaks < 1) {
                sb.append('\n')
                breaks = 1
            }
        }

        fun blank() {
            newline()
            if (breaks < 2) {
                sb.append('\n')
                breaks = 2
            }
        }

        fun verbatim(s: String) {
            if (s.isNotEmpty()) {
                sb.append(s)
                breaks = if (s.endsWith("\n")) 1 else 0
            }
        }

        val result: String get() = sb.toString()
    }
}
