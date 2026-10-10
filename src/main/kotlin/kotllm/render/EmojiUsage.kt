package kotllm.render

import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardOpenOption

/** How often each `:shortcode:` has been sent, so the completion list can put
 * the ones this user reaches for at the top.
 *
 * The list has to be capped -- a one-letter prefix matches up to 219 and
 * JLine silently refuses to print a list that long -- and alphabetical is a
 * poor way to choose which 60 survive: `:a` spends its whole budget in the
 * a's and never reaches `:astonished:`. Frequency is the ordering that makes
 * a truncated list useful, because the thing most likely wanted is the thing
 * most recently and most often wanted before.
 *
 * **Counted on send, not on completion.** Accepting a candidate is a guess
 * about intent; a shortcode that went to the model in an actual message is
 * the fact. It also means shortcodes typed in full, without the completer,
 * still teach the list.
 *
 * Only what the user typed is counted. The same expansion runs over model
 * output, and counting that would rank the model's habits rather than the
 * user's.
 */
class EmojiUsage(val path: Path) {

    private var counts: Map<String, Int> = load()

    private fun load(): Map<String, Int> =
        try {
            if (!Files.exists(path)) emptyMap()
            else
                String(Files.readAllBytes(path), StandardCharsets.UTF_8)
                    .lines()
                    .mapNotNull { line ->
                        val i = line.indexOf('\t')
                        if (i < 0) null
                        else
                            try {
                                line.substring(0, i) to line.substring(i + 1).trim().toInt()
                            } catch (_: NumberFormatException) {
                                null
                            }
                    }
                    .toMap()
        } catch (e: Exception) {
            emptyMap()
        }

    fun count(name: String): Int = synchronized(this) { counts[name] ?: 0 }

    val known: Map<String, Int>
        get() = synchronized(this) { counts }

    /** Adds one for each name and writes the table out.
     *
     * Rewritten whole rather than appended to: it is a few kilobytes at the
     * very most -- there are only 1913 shortcodes in existence -- and a
     * rewrite cannot accumulate duplicate keys the way an append log would.
     * Failure is ignored on purpose; a read-only config directory should cost
     * the ordering, not the message being sent.
     */
    fun record(names: List<String>) {
        if (names.isNotEmpty()) synchronized(this) {
            counts = names.fold(counts) { acc, n -> acc + (n to (acc[n] ?: 0) + 1) }
            try {
                path.parent?.let { p ->
                    if (!Files.exists(p)) Files.createDirectories(p)
                }
                val body = counts
                    .toList()
                    .sortedByDescending { it.second }
                    .map { (n, c) -> "$n\t$c" }
                    .joinToString("\n") + "\n"
                Files.write(
                    path,
                    body.toByteArray(StandardCharsets.UTF_8),
                    StandardOpenOption.CREATE,
                    StandardOpenOption.TRUNCATE_EXISTING
                )
            } catch (e: Exception) {
                // Failure ignored on purpose
            }
        }
    }

    companion object {
        /** The store the REPL and the completer share.
         *
         * A `var` so a test can point it somewhere harmless: the alternative is
         * threading it through the completer, the key bindings and the send path,
         * and every one of those already reaches `Emoji` as an object.
         */
        @Volatile
        var current: EmojiUsage = EmojiUsage(kotllm.config.Paths.emojiUsageFile())
    }
}
