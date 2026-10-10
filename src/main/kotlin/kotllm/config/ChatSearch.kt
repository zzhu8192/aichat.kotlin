package kotllm.config

import java.nio.file.Path
import kotllm.*
import kotllm.rag.Bm25
import kotllm.rag.Fusion
import kotllm.rag.HashingEmbedder
import kotllm.rag.Hit

data class ChatHit(val session: StoredSession, val message: StoredMessage, val score: Double) {

    /** A citation the model can act on and the user can verify. Deliberately the
      * session id and message number rather than a file path: the path is noise
      * (it is derivable from the id), and `view_chat` takes the id. */
    fun citation(): String =
        "${session.id}  msg ${message.index}  ${message.stamp}  ${message.role}"
}

/** Search over persisted conversations.
  *
  * **Built on the RAG machinery, not on `RagIndex`.** `Bm25`, `HashingEmbedder`
  * and `Fusion` are reused verbatim; the index itself is not, for two reasons:
  *
  *  - **Citations differ in kind.** `RagIndex` cites `path:startLine-endLine`,
  *    which for a conversation is a JSONL line number - useless. A chat needs a
  *    session id, a message number and a timestamp.
  *  - **There is one active RAG index and it belongs to the user.** Chat search
  *    must not occupy that slot or be switched off by `/rag off`.
  *
  * Chunked per message, not per N characters: a conversation already has
  * natural units, and a boundary drawn mid-reply would cite a message number
  * that only partly matches. Messages under a floor are skipped - "ok",
  * "thanks" match everything and mean nothing.
  *
  * Rebuilt on demand rather than persisted: indexing a few hundred messages
  * takes milliseconds, and a stored index would need invalidation rules against
  * a directory that grows every turn. See docs/sessions.md.
  */
class ChatSearch(private val dir: Path, private val minMessageChars: Int = 24) {

    private fun eligible(session: StoredSession): List<Pair<StoredSession, StoredMessage>> =
        session.messages
            .filter { m -> m.role == "user" || m.role == "assistant" }
            .filter { it.text.trim().length >= minMessageChars }
            .map { session to it }

    /** Hybrid retrieval over every stored conversation.
      *
      * `excludeSession` drops the conversation in progress: searching your own
      * current session returns what you just said, which is never the question
      * being asked.
      */
    fun search(
        query: String,
        topK: Int,
        excludeSession: String? = null,
        role: String? = null
    ): List<ChatHit> {
        val corpus = SessionStore
            .loadAll(dir)
            .filterNot { s -> excludeSession?.let { it == s.id } ?: false }
            .flatMap { eligible(it) }
            .filter { (_, m) -> role?.let { it == m.role } ?: true }

        if (corpus.isEmpty() || query.trim().isEmpty()) return emptyList()

        val lexical = Bm25()
        // The model name goes in alongside the text for the same reason the RAG
        // index includes the path: "what did I ask about qwen" should find a
        // conversation held on that model even if the word never appears in it.
        corpus.forEach { (s, m) -> lexical.add("${s.model} ${m.text}") }

        val over = maxOf(topK * 4, 20)
        val byWord = lexical.search(query, over)

        val embedder = HashingEmbedder()
        val queryVec = embedder.encode(query)
        val byShape = corpus.mapIndexed { i, (_, m) ->
            Hit(i, HashingEmbedder.cosine(queryVec, embedder.encode(m.text)))
        }
            .filter { it.score >= 0.20 } // the same floor RAG uses; cosine is never zero
            .sortedByDescending { it.score }
            .take(over)

        return Fusion
            .reciprocalRank(listOf(byWord, byShape), topK = topK)
            .mapNotNull { hit ->
                corpus.getOrNull(hit.chunkIndex)?.let { (s, m) -> ChatHit(s, m, hit.score) }
            }
    }

    fun sessions(): List<StoredSession> = SessionStore.loadAll(dir)

    fun view(id: String): kotllm.Outcome<StoredSession> = SessionStore.find(dir, id)
}
