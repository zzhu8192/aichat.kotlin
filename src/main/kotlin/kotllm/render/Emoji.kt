package kotllm.render

import kotllm.util.Glyphs

/** Two conventions for typing emoji, both expanded to the Unicode character
 * they name.
 *
 *   - Slack/GitHub-style `:shortcode:` -- `:fire:` becomes `🔥`. The table is
 *     [https://github.com/github/gemoji gemoji]'s (MIT; see
 *     `THIRD_PARTY_NOTICES.md`), vendored as a flat resource rather than a
 *     dependency: it is data, not code, and the fat jar already carries
 *     everything the model sees.
 *   - Classic ASCII emoticons -- `:)`, `:-)`, `:D`, `;)` and the like. Not
 *     gemoji's business (it only knows named shortcodes), so this is a small
 *     hand-picked table of the ones common enough that autocorrecting them is
 *     more helpful than surprising.
 *
 * Gated on [Glyphs.unicode] like every other glyph substitution in this
 * package. An emoji has no ASCII fallback the way `\neq` has `!=`, so on a
 * console that cannot encode it the honest behaviour is to leave the text as
 * written rather than print a `?` in its place.
 */
object Emoji {

    private val table: Map<String, String> =
        try {
            val stream = javaClass.getResourceAsStream("/emoji.tsv")
            if (stream == null) emptyMap()
            else
                try {
                    String(stream.readAllBytes(), Charsets.UTF_8)
                        .lines()
                        .mapNotNull { line ->
                            val i = line.indexOf('\t')
                            if (i < 0) null else line.substring(0, i) to line.substring(i + 1)
                        }
                        .toMap()
                } finally {
                    stream.close()
                }
        } catch (e: Exception) {
            emptyMap()
        }

    private val shortcode = Regex(""":([a-zA-Z0-9_+-]+):""")

    /** Shortcode names beginning with `prefix`, paired with the emoji they
     * name -- for tab completion, where seeing the options is the point.
     *
     * **Most-used first, then alphabetical.** The offer is capped, and
     * alphabetical order chooses badly when it is: `:a` would spend the whole
     * budget inside the a's. Ranking by how often a shortcode has actually
     * been sent puts the handful anyone really uses at the top of the list
     * they are looking at. Ties, which is nearly everything, stay
     * alphabetical so the order is stable and predictable.
     */
    fun matching(prefix: String): List<Pair<String, String>> {
        val used = EmojiUsage.current.known
        return table
            .filter { it.key.startsWith(prefix) }
            .toList()
            .sortedWith(compareBy(
                { -(used[it.first] ?: 0) },
                { it.first }
            ))
    }

    /** The known shortcodes `text` actually uses, for the usage counter.
     *
     * Reads the same pattern `expand` does, so what is counted is exactly
     * what was substituted -- an unknown `:word:` was never an emoji and must
     * not teach the completion list that it is one. */
    fun shortcodesIn(text: String): List<String> =
        shortcode.findAll(text)
            .map { it.groupValues[1] }
            .filter { table.containsKey(it) }
            .toList()

    /** Longest alternatives first only matters where one is a prefix of
     * another with the same leading character run, which none of these are --
     * kept sorted anyway so a reader does not have to check that by hand. */
    private val emoticons: Map<String, String> = mapOf(
        ":-)" to "🙂", ":)" to "🙂",
        ":-(" to "🙁", ":(" to "🙁",
        ":-D" to "😃", ":D" to "😃",
        ";-)" to "😉", ";)" to "😉",
        ":-P" to "😛", ":P" to "😛", ":-p" to "😛", ":p" to "😛",
        ":'(" to "😢",
        ":-O" to "😮", ":O" to "😮", ":-o" to "😮", ":o" to "😮"
    )

    /** An emoticon only counts as one at a word boundary: `x<3:D` is not a
     * smiley, and code containing `:D` as some other token's tail is not
     * either. Requiring whitespace or start/end on both sides is stricter
     * than prose ever needs to be deliberate -- it is prose typed by a person,
     * not text mined for every possible occurrence. */
    private val emoticon =
        Regex("""(^|\s)(:-\)|:\)|:-\(|:\(|:-D|:D|;-\)|;\)|:-P|:P|:-p|:p|:'\(|:-O|:O|:-o|:o)(?=$|[\s.,!?;])""")

    /** Replaces every known `:shortcode:` and ASCII emoticon with its emoji. A
     * shortcode gemoji does not have -- or a stray `:word:` that was never
     * meant as one -- is left exactly as written rather than swallowed. */
    fun expand(text: String): String =
        if (!Glyphs.unicode) text
        else expandEmoticons(expandShortcodes(text))

    private fun expandShortcodes(text: String): String =
        if (table.isEmpty()) text
        else
            shortcode.replace(text) { m ->
                java.util.regex.Matcher.quoteReplacement(table[m.groupValues[1]] ?: m.value)
            }

    private fun expandEmoticons(text: String): String =
        emoticon.replace(text) { m ->
            java.util.regex.Matcher.quoteReplacement(m.groupValues[1] + emoticons[m.groupValues[2]])
        }
}
