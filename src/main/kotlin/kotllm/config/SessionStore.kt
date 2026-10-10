package kotllm.config

import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardOpenOption
import java.time.Instant
import java.time.ZoneId
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import kotlin.random.Random
import kotlinx.serialization.json.*
import kotllm.*
import kotllm.util.AtomicWrite

data class StoredMessage(
    val index: Int,
    val role: String,
    val at: Long,
    val text: String,
    val promptTokens: Int? = null,
    val completionTokens: Int? = null
) {
    val `when`: ZonedDateTime get() = ZonedDateTime.ofInstant(Instant.ofEpochMilli(at), ZoneId.systemDefault())

    val stamp: String get() =
        `when`.format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm"))
}

/** A system prompt as it was actually sent, recorded while `/prompt full` is on.
  *
  * Two layers rather than one: `full` is what went on the wire, `user` is the
  * slice the user wrote themselves. `user` is always the tail of `full`, so it
  * is redundant - and worth the bytes anyway, because the question being asked
  * of this record is usually "which of that did I author", and eyeballing the
  * boundary in eight concatenated sections is exactly what the mode exists to
  * avoid.
  *
  * `index` is the position of the first message this prompt applied to, so
  * `StoredSession.promptAt` can resolve the prompt in effect at any message.
  */
data class StoredPrompt(val index: Int, val at: Long, val user: String?, val full: String) {
    val `when`: ZonedDateTime get() = ZonedDateTime.ofInstant(Instant.ofEpochMilli(at), ZoneId.systemDefault())

    val stamp: String get() =
        `when`.format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm"))
}

/** A persisted conversation, and enough about it to list one without reading
  * the whole file. */
data class StoredSession(
    val id: String,
    val path: Path,
    val startedAt: Long,
    val model: String,
    val messages: List<StoredMessage>,
    val prompts: List<StoredPrompt> = emptyList()
) {

    /** The system prompt in effect at `index`: the last one recorded at or before
      * it. A row is written per turn while the mode is on, so this is normally an
      * exact hit - the scan backwards is for the turns before it was switched on,
      * where the nearest later row is the honest answer. */
    fun promptAt(index: Int): StoredPrompt? =
        prompts.filter { it.index <= index }.lastOrNull() ?: prompts.firstOrNull()

    val `when`: String get() =
        ZonedDateTime
            .ofInstant(Instant.ofEpochMilli(startedAt), ZoneId.systemDefault())
            .format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm"))

    /** First thing the user actually said, which is what makes a listing
      * recognisable - an id and a timestamp do not. */
    fun title(): String =
        messages
            .find { it.role == "user" }
            .let { msg ->
                msg?.text?.lines()?.find { it.trim().isNotEmpty() }?.trim()
            }
            ?.let { t -> if (t.length > 72) t.take(72) + "..." else t }
            ?: "(no user message)"

    fun chars(): Int = messages.sumOf { it.text.length }
}

/** Conversations on disk, one JSONL file per session.
  *
  * **`save: true` was a dead key before this** - parsed, printed by `/info`,
  * and read by nothing, which is what made `search_chats` impossible: a search
  * tool over an empty corpus.
  *
  * **JSONL, appended per turn**, for one reason above all: a crash or a kill
  * keeps everything up to the last completed reply, where a single JSON document
  * rewritten on exit loses the whole session to the failure most likely during a
  * long task. It is also greppable without kotllm.
  *
  * Reasoning parts are already stripped by `Session.appendResponse`, so what is
  * stored is what was replayed - no second filter to drift.
  */
class SessionStore(
    private val dir: Path,
    private val enabled: Boolean,
    private val clock: () -> Long = { System.currentTimeMillis() }
) {

    private var file: Path? = null
    private var written = 0
    private var sessionId = ""

    val isEnabled: Boolean
        get() = enabled

    val id: String
        get() = sessionId

    val location: Path?
        get() = file

    /** Opens a file for this session, named so that a directory listing sorts
      * chronologically and a human can find one by eye. The random suffix is
      * what keeps two kotllm processes started in the same second apart. */
    private fun open(model: String): Path? = synchronized(this) {
        if (!enabled) return null
        return file ?: run {
            try {
                Files.createDirectories(dir)
                val now = clock()
                val `when` = ZonedDateTime.ofInstant(Instant.ofEpochMilli(now), ZoneId.systemDefault())
                val stamp = `when`.format(DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH-mm-ss"))
                val salt = Integer.toHexString(Random.nextInt(0xffff))
                    .reversed()
                    .padEnd(4, '0')
                    .reversed()
                sessionId = "$stamp-$salt"
                val target = dir.resolve("$sessionId.jsonl")

                // `JsonPrimitive`, explicitly: ujson serialises a bare `Long` as a JSON
                // *string* - losslessly, since Long exceeds Double's exact integer
                // range - and a quoted timestamp is both surprising to anything
                // reading the file with jq and a trap for our own reader, which is
                // exactly the bug this line used to cause. Epoch millis sit far
                // inside 2^53, so a number loses nothing.
                append(target, buildJsonObject {
                    put("v", 1)
                    put("started", now.toDouble())
                    put("model", model)
                    put("id", sessionId)
                })
                file = target
                target
            } catch (e: Exception) {
                null
            }
        }
    }

    private fun append(target: Path, value: JsonElement): Unit {
        Files.write(
            target,
            (value.toString() + "\n").toByteArray(StandardCharsets.UTF_8),
            StandardOpenOption.CREATE,
            StandardOpenOption.APPEND
        )
    }

    /** Records everything in `session` that has not been recorded yet.
      *
      * Driven off the history's length rather than by hooking every `append`:
      * compaction *replaces* the history wholesale, and a store mirroring every
      * mutation would either lose the original exchanges or duplicate the summary.
      * Writing only the new tail keeps the file the full conversation even after
      * the live history has been compacted down.
      *
      * @param prompt the system prompt as sent, `(userSlice, assembled)`, supplied
      *               only while `/prompt full` is on. Written ahead of the messages
      *               it applied to, on **every** turn rather than only when it
      *               changes: a row that appears only on change makes the reader
      *               reconstruct which prompt was live at turn nine by scanning
      *               backwards, and getting a straight answer is the entire point
      *               of the mode. The near-duplicate bytes are affordable because
      *               the mode is a debugging view nobody leaves on. */
    fun record(
        session: kotllm.config.Session,
        model: String,
        usage: Pair<Int, Int>? = null,
        prompt: Pair<String?, String>? = null
    ): Unit = synchronized(this) {
        if (!enabled) return
        open(model)?.let { target ->
            try {
                val all = session.messages()
                if (all.size > written) {
                    val now = clock()

                    // Ahead of the tail, so a reader scanning forward has the prompt in
                    // hand before the messages it governed.
                    prompt?.let { (user, full) ->
                        val row = buildJsonObject {
                            put("kind", "prompt")
                            put("at", now.toDouble())
                            put("i", written)
                            put("full", full)
                            if (user != null) {
                                put("user", user)
                            }
                        }
                        append(target, row)
                    }

                    all.drop(written).forEachIndexed { idx, m ->
                        val i = written + idx
                        val text = m.text
                        // Tool-call plumbing with no text carries nothing a later search
                        // could match, and the arguments are already in the assistant
                        // message that requested them.
                        if (text.trim().isNotEmpty()) {
                            val row = buildJsonObject {
                                put("role", m.role)
                                put("at", now.toDouble())
                                put("i", i)
                                put("text", text)
                                usage?.let { (inTokens, outTokens) ->
                                    if (m.role == "assistant" && i == all.size - 1) {
                                        put("in", inTokens)
                                        put("out", outTokens)
                                    }
                                }
                            }
                            append(target, row)
                        }
                    }
                    written = all.size
                }
            } catch (e: Exception) {
                // never let logging break a turn
            }
        }
    }

    /** Compaction rewrote the live history, so the on-disk cursor no longer
      * lines up with it. Anything before the rewrite is already stored. */
    fun resyncAfterCompaction(session: kotllm.config.Session): Unit = synchronized(this) {
        written = session.messages().size
    }

    companion object {

        val disabled: SessionStore =
            SessionStore(java.nio.file.Paths.get("."), enabled = false)


        val json = Json { ignoreUnknownKeys = true; isLenient = true }


        /** A timestamp written as either a JSON number or a JSON string.
          *
          * Both shapes exist on disk: a bare `Long` handed to `ujson.Obj` becomes a
          * quoted string, which is what the first version of the writer did before
          * it was made explicit. Accepting both is the same leniency rule the wire
          * decoders follow, and it is what keeps files written by that build
          * readable.
          */
        private fun epochOf(value: JsonElement): Long? =
            when {
                value is JsonPrimitive && value.isString -> {
                    value.content.trim().toLongOrNull()
                }
                value is JsonPrimitive -> {
                    value.doubleOrNull?.toLong()
                }
                else -> null
            }


        /** Every session on disk, newest first. Malformed lines are skipped rather
          * than failing the read: a file truncated by a kill mid-write is exactly
          * the case append-per-turn exists to survive. */
        fun loadAll(dir: Path): List<StoredSession> {
            if (!Files.isDirectory(dir)) return emptyList()
            val stream = Files.list(dir)
            val files = try {
                stream.use { s ->
                    s.filter { p -> Files.isRegularFile(p) && p.fileName.toString().endsWith(".jsonl") }
                        .toList()
                }
            } catch (e: Exception) {
                emptyList()
            }

            return files.mapNotNull { load(it) }.sortedByDescending { it.startedAt }
        }


        fun load(path: Path): StoredSession? {
            return try {
                val lines = Files.readAllLines(path, StandardCharsets.UTF_8)
                val rows = lines.mapNotNull { l ->
                    try {
                        json.parseToJsonElement(l)
                    } catch (e: Exception) {
                        null
                    }
                }
                rows.firstOrNull()?.let { header ->
                    val obj = (header as? JsonObject) ?: return null
                    val id = (obj["id"] as? JsonPrimitive)?.contentOrNull
                        ?: path.fileName.toString().removeSuffix(".jsonl")
                    val started = obj["started"]?.let { epochOf(it) } ?: 0L
                    val model = (obj["model"] as? JsonPrimitive)?.contentOrNull ?: "unknown"

                    // Split by row kind before numbering: a prompt row carries no `role`,
                    // so the old reader skipped it silently - which is what keeps a file
                    // written by this build loadable by an older one, and vice versa. The
                    // positional fallback for `i` counts message rows only, or a prompt row
                    // partway through would shift every index after it.
                    val (promptRows, messageRows) = rows.drop(1)
                        .mapNotNull { it as? JsonObject }
                        .partition { (it["kind"] as? JsonPrimitive)?.contentOrNull == "prompt" }

                    val messages = messageRows.mapIndexed { n, o ->
                        val role = (o["role"] as? JsonPrimitive)?.contentOrNull ?: return@mapIndexed null
                        val text = (o["text"] as? JsonPrimitive)?.contentOrNull ?: return@mapIndexed null
                        StoredMessage(
                            index = (o["i"] as? JsonPrimitive)?.doubleOrNull?.toInt() ?: n,
                            role = role,
                            at = o["at"]?.let { epochOf(it) } ?: started,
                            text = text,
                            promptTokens = (o["in"] as? JsonPrimitive)?.doubleOrNull?.toInt(),
                            completionTokens = (o["out"] as? JsonPrimitive)?.doubleOrNull?.toInt()
                        )
                    }.mapNotNull { it }

                    val prompts = promptRows.mapNotNull { o ->
                        val full = (o["full"] as? JsonPrimitive)?.contentOrNull ?: return@mapNotNull null
                        StoredPrompt(
                            index = (o["i"] as? JsonPrimitive)?.doubleOrNull?.toInt() ?: 0,
                            at = o["at"]?.let { epochOf(it) } ?: started,
                            user = (o["user"] as? JsonPrimitive)?.contentOrNull,
                            full = full
                        )
                    }

                    StoredSession(id, path, started, model, messages, prompts)
                }
            } catch (e: Exception) {
                null
            }
        }


        /** Resolves a user- or model-supplied id. Accepts a full id, the random
          * suffix alone, or any unambiguous prefix, because an id like
          * `2026-09-18T09-27-14-a3f1` is not something anyone retypes. */
        fun find(dir: Path, wanted: String): kotllm.Outcome<StoredSession> {
            val key = wanted.trim().lowercase()
            val all = loadAll(dir)
            if (all.isEmpty()) return kotllm.err("no saved conversations in $dir")

            val exact = all.filter { it.id.lowercase() == key }
            val loose = all.filter { s -> s.id.lowercase().startsWith(key) || s.id.lowercase().endsWith(key) }

            return when {
                exact.size == 1 -> kotllm.ok(exact[0])
                loose.isNotEmpty() && (exact + loose).distinct().size == 1 -> kotllm.ok((exact + loose)[0])
                (exact + loose).distinct().size == 1 -> kotllm.ok((exact + loose)[0])
                (exact + loose).isEmpty() -> kotllm.err("no conversation matching '$wanted'; ${all.size} saved")
                else -> {
                    val several = (exact + loose).distinct()
                    kotllm.err("'$wanted' matches ${several.size} conversations: " +
                        several.take(6).joinToString(", ") { it.id })
                }
            }
        }
    }
}
