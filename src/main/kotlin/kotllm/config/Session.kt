package kotllm.config

import kotllm.client.ChatResponse
import kotllm.client.Message
import kotllm.client.Part
import kotllm.client.Usage
import kotllm.util.TokenLedger

/** The live conversation.
  *
  * In-memory only for now: named, persisted sessions (/session <name>,
  * auto-save, compression at a token threshold) are the next increment. What
  * is already load-bearing here is that reasoning is stripped before a reply
  * enters the history, so it is never replayed to the endpoint.
  */
class Session(var systemPrompt: String? = null) {

  private val history = mutableListOf<Message>()

  /** All token accounting lives here, so there is one source of truth for
    * /status, the prompt's token counter, and /info.
    */
  val ledger = TokenLedger()

  private var exchanges = 0

  private var compactions = 0

  fun messages(): List<Message> = history.toList()

  fun isEmpty(): Boolean = history.isEmpty()

  fun turns(): Int = exchanges

  /** The message list to send: system prompt first, then history. */
  fun requestMessages(pendingUser: String? = null): List<Message> {
    val head = systemPrompt?.trim()?.takeIf { it.isNotEmpty() }?.let { Message.system(it) }
    return listOfNotNull(head) + history + listOfNotNull(pendingUser?.let { Message.user(it) })
  }

  fun append(m: Message): Unit {
    history.add(m)
  }

  /** Records an assistant reply, dropping reasoning parts.
    *
    * Reasoning is display-only: replaying it wastes a large fraction of the
    * context window and endpoints may reject unknown part types on input
    * (docs/clients.md, finding 3).
    */
  fun appendResponse(r: ChatResponse): Unit {
    val visible = r.message.parts.filterIsInstance<Part.Text>()
    history.add(r.message.copy(parts = visible))
    exchanges += 1
  }

  fun lastReply(): String? =
    history.asReversed().find { it.role == "assistant" }?.text?.takeIf { it.isNotEmpty() }

  /** Removes the trailing assistant reply and the user turn that produced it,
    * so the same input can be re-sent.
    */
  fun dropLastExchange(): String? {
    val lastUser = history.indexOfLast { it.role == "user" }
    if (lastUser < 0) return null

    val text = history[lastUser].text
    history.subList(lastUser, history.size).clear()
    if (exchanges > 0) exchanges -= 1
    return text
  }

  /** Replaces the whole history, for compaction.
    *
    * Counted, because summaries of summaries degrade: a session on its fifth
    * compaction has lost most of its detail, and that is worth being able to
    * see in /status rather than inferring from behaviour.
    */
  fun replaceHistory(messages: List<Message>): Unit {
    history.clear()
    history.addAll(messages)
    compactions += 1
  }

  fun compactionCount(): Int = compactions

  /** Rolls the history back to a previous length, used to drop a user turn
    * whose request failed so it is not silently re-sent next time.
    */
  fun truncateTo(size: Int): Unit {
    if (size >= 0 && size < history.size) {
      history.subList(size, history.size).clear()
    }
  }

  /** Clears what gets sent to the endpoint. Deliberately leaves the ledger
    * alone: the session has still spent those tokens, and seeing the prompt
    * series drop after a clear is useful.
    */
  fun clear(): Unit {
    history.clear()
    exchanges = 0
    compactions = 0
  }

  fun lastUsage(): Usage? =
    ledger.all().lastOrNull()?.let { m ->
      Usage(m.promptTokens, m.completionTokens, m.totalTokens())
    }

  /** Cumulative tokens across the session, which is not the same as the last
    * turn's totals: every turn re-sends the whole history, so prompt tokens are
    * paid for again each time.
    */
  fun billed(): Usage =
    Usage(ledger.totalPromptTokens(), ledger.totalCompletionTokens(), ledger.totalTokens())
}
