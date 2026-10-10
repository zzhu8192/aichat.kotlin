package kotllm.repl

import org.jline.reader.Candidate
import org.jline.reader.LineReader
import org.jline.reader.ParsedLine

import kotllm.render.Emoji

/** Tab completion for commands and their arguments, and for `:shortcode:`
 * emoji anywhere in the line.
 *
 * Argument completion is per-command rather than a flat word list, so
 * `/model <TAB>` offers models and `/set <TAB>` offers setting names instead
 * of every token kotllm knows.
 *
 * Command completion only fires on the first word, matching the parser: a
 * command is only a command at the start of the input, so suggesting one
 * mid-sentence would be offering something that cannot work. Emoji is the
 * one exception -- `nice :fir<TAB>` is prose, and the shortcode can land
 * anywhere in it.
 */
class ReplCompleter(
    private val models: () -> List<String>,
    /** Index names, supplied lazily because indexes appear and disappear
     * during a session. Defaults to none so tests need not wire a store. */
    private val ragIndexes: () -> List<String> = { emptyList() }
) : org.jline.reader.Completer {

    override fun complete(reader: LineReader, line: ParsedLine, candidates: MutableList<Candidate>) {
        // Read from the buffer rather than ParsedLine.words()/wordIndex().
        // JLine changed whether a cursor sitting after a trailing space gets an
        // empty word of its own: on 3.30+ `/model ` reports wordIndex 0, so the
        // argument branch below was never reached and Tab offered every command
        // instead of the models. The buffer says the same thing on any version.
        val upToCursor = line.line().take(line.cursor())
        val word = wordBeforeCursor(upToCursor)
        val priorWords = upToCursor
            .dropLast(word.length)
            .trim()
            .split(Regex("""\s+"""))
            .filter { it.isNotEmpty() }
        val idx = priorWords.size

        if (isShortcodeInProgress(word)) completeEmoji(word, candidates)
        else if (idx <= 0) completeCommand(word, candidates)
        else {
            val typed = priorWords[0]
            val prior = if (priorWords.size > 1) priorWords[1] else ""
            argumentsFor(typed, idx, prior).forEach { a -> candidates.add(Candidate(a)) }
        }
    }

    /** Offers `:shortcode:` names starting with what was typed, each displayed
     * next to the emoji it names -- seeing the options is the point.
     *
     * **The value has to be the shortcode text, not the emoji.** JLine filters
     * candidates by matching their *value* against the word being completed,
     * so a value of `🔥` against a typed `:fir` matches nothing, every
     * candidate is discarded and Tab rings the bell -- which is exactly what
     * it did. The emoji still arrives: `Emoji.expand` converts the inserted
     * `:fire:` when the line is sent. */
    private fun completeEmoji(word: String, candidates: MutableList<Candidate>) {
        val prefix = word.removePrefix(":")
        Emoji.matching(prefix).take(MaxEmojiCandidates).forEach { (name, glyph) ->
            val shortcode = ":$name:"
            // The glyph is display only, so a console that cannot encode it shows
            // the name alone rather than a `?` beside every row.
            val display = if (kotllm.util.Glyphs.unicode) "$shortcode $glyph" else shortcode
            candidates.add(Candidate(shortcode, display, null, null, null, null, true))
        }
    }

    /** Offers slash commands. */
    private fun completeCommand(word: String, candidates: MutableList<Candidate>) {
        if (word.startsWith("/") || word.isEmpty()) {
            Commands.all.forEach { c ->
                candidates.add(Candidate(c.name, c.name, null, c.help, null, null, true))
            }
        }
    }

    /** @param position which argument is being completed, 1-based
     * @param first    the first argument already typed, for subcommands
     */
    private fun argumentsFor(command: String, position: Int, first: String): List<String> =
        when (command) {
            "/model" -> models()
            "/set" -> Commands.settableKeys
            "/think" -> listOf("default") + kotllm.config.ReasoningEffort.Known
            // Only at position 1, and only these two words: anything else typed here
            // is the system prompt itself, which must not be completed against.
            "/prompt" -> if (position == 1) Commands.promptViews else emptyList()
            "/info" -> listOf("config")
            "/rag" ->
                // Paths are deliberately not completed for `index`: JLine's own file
                // completer is not composable here, and half-working path completion
                // is more annoying than none.
                if (position == 1) Commands.ragSubcommands
                else when (first) {
                    "use", "drop" -> ragIndexes()
                    "inject" -> listOf("on", "off")
                    "describe" -> listOf("-")
                    else -> emptyList()
                }
            else -> emptyList()
        }

    companion object {

        /** How many emoji candidates are worth offering at once.
         *
         * **Below JLine's `list-max`, which is the whole point.** A one-letter
         * prefix matches far more than a screen can hold -- `:s` is 219 -- and
         * JLine's answer to a list that long is to suppress it and ask nothing,
         * so Tab appears to do nothing whatsoever. That was the second reported
         * failure. Offering a screenful and stopping is the honest version: the
         * list is a way to find the name, and nobody reads to entry 219.
         */
        internal const val MaxEmojiCandidates = 60

        /** Characters after the `:` before a shortcode is worth listing. One is too
         * few -- `:a` is 76 candidates and `:s` is 219. */
        internal const val MinShortcodeChars = 2

        /** Whether there is a candidate list for the text behind the cursor: a
         * `:shortcode` far enough along to narrow things down, or a `/command` as
         * the first word.
         *
         * One predicate, used by three callers -- the key that re-lists as you
         * type, the arrows that step into the list, and the completer itself.
         * They drifted apart once already and the result was a list that appeared
         * at `:ha`, vanished at `:hai`, and a `/` list that never narrowed
         * because only the shortcode case was ever re-triggered.
         */
        fun wantsList(bufferUpToCursor: String): Boolean {
            val word = wordBeforeCursor(bufferUpToCursor)
            val shortcode =
                isShortcodeInProgress(word) && word.length >= MinShortcodeChars + 1
            // A command is only a command as the first word, which is the rule the
            // parser uses. Bare `/` counts: typing it is what put the list up.
            val command = word.startsWith("/") && bufferUpToCursor.trim() == word
            return shortcode || command
        }

        /** Whether `word` is a `:shortcode` someone is part-way through typing.
         *
         * Shared with the key binding that decides how Tab should behave, so the
         * menu opens for exactly the words the completer will offer emoji for --
         * the two drifting apart is how Tab ends up opening an empty menu.
         *
         * An already-closed `:fire:` is not in progress: it is finished, and the
         * trailing colon is not a shortcode character, so it falls through to
         * ordinary completion rather than offering to complete itself.
         */
        fun isShortcodeInProgress(word: String): Boolean =
            word.length > 1 && word.startsWith(":") &&
                word.drop(1).all { c -> c.isLetterOrDigit() || c == '_' || c == '+' || c == '-' }

        /** The word ending at the cursor, which is what Tab is about to complete.
         *
         * Taken from the buffer rather than a `ParsedLine`, because the key
         * binding runs before JLine parses anything. */
        fun wordBeforeCursor(bufferUpToCursor: String): String =
            bufferUpToCursor.reversed().takeWhile { c -> !c.isWhitespace() }.reversed()
    }
}
