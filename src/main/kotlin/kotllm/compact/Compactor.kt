package kotllm.compact

import kotllm.client.Message
import kotllm.client.Part
import kotllm.*

data class CompactionConfig(
    val enabled: Boolean,
    val thresholdPercent: Int,
    val absoluteThreshold: Int?,
    val keepRecentExchanges: Int,
    val minHeadExchanges: Int,
    val trimToolResults: Boolean,
    val toolResultMaxChars: Int,
    val summaryMaxChars: Int,
    val summarizePrompt: String,
    val summaryPrompt: String
) {
    companion object {
        val default: CompactionConfig = CompactionConfig(
            enabled = true,
            thresholdPercent = 70,
            absoluteThreshold = null,
            keepRecentExchanges = 3,
            minHeadExchanges = 2,
            trimToolResults = true,
            toolResultMaxChars = 2000,
            summaryMaxChars = 2000,
            summarizePrompt =
                "Summarise the conversation below so it can be used as context to continue the work. " +
                "Include: the goal and current status; decisions made and constraints agreed; files or " +
                "paths touched and what changed; what was tried and failed, so it is not retried; and " +
                "the open next steps. Omit pleasantries and do not reproduce code that can be re-read " +
                "from disk. Be specific and brief: this replaces the text it summarises, so a summary " +
                "longer than the original is worse than none. Use far fewer words than the input.",
            summaryPrompt = "This is a summary of the earlier conversation, as a recap: "
        )
    }
}

data class CompactionOutcome(
    val trimmedResults: Int,
    val trimmedChars: Int,
    val summarizedExchanges: Int,
    val summaryChars: Int,
    val messagesBefore: Int,
    val messagesAfter: Int
) {
    val didSomething: Boolean get() = trimmedResults > 0 || summarizedExchanges > 0
}

class Compactor(val config: CompactionConfig) {

    val enabled: Boolean get() = config.enabled

    fun shouldCompact(projectedTokens: Int, windowTokens: Int?): Boolean {
        if (!config.enabled) return false

        val overAbsolute = config.absoluteThreshold != null && projectedTokens > config.absoluteThreshold
        val overPercent = windowTokens != null && windowTokens > 0 && projectedTokens.toDouble() > windowTokens.toDouble() * config.thresholdPercent / 100.0
        return overAbsolute || overPercent
    }

    fun exchanges(messages: List<Message>): List<List<Message>> {
        val out = mutableListOf<List<Message>>()
        var current = mutableListOf<Message>()

        messages.forEach { m ->
            if (m.role == "user" && current.isNotEmpty()) {
                out.add(current.toList())
                current = mutableListOf(m)
            } else {
                current.add(m)
            }
        }
        if (current.isNotEmpty()) out.add(current.toList())
        return out
    }

    fun trimOldToolResults(messages: List<Message>): Triple<List<Message>, Int, Int> {
        if (!config.trimToolResults) return Triple(messages, 0, 0)

        val groups = exchanges(messages)
        val keep = kotlin.math.max(0, config.keepRecentExchanges)
        val (head, tail) = groups.withIndex().partition { (idx, _) -> idx < groups.size - keep }

        var trimmed = 0
        var saved = 0

        val newHead = head.map { (_, group) ->
            group.map { m ->
                val text = m.text
                if (m.role == "tool" && text.length > config.toolResultMaxChars) {
                    trimmed += 1
                    saved += text.length - config.toolResultMaxChars
                    val note = "\n... [older tool result trimmed; ${text.length} chars originally]"
                    m.copy(parts = listOf(Part.Text(text.take(config.toolResultMaxChars) + note)))
                } else {
                    m
                }
            }
        }.flatten()

        val tailMessages = tail.map { (_, group) -> group }.flatten()
        return Triple(newHead + tailMessages, trimmed, saved)
    }

    fun split(messages: List<Message>): Pair<List<Message>, List<Message>>? {
        val groups = exchanges(messages)
        val keep = kotlin.math.max(1, config.keepRecentExchanges)
        return if (groups.size < keep + config.minHeadExchanges) {
            null
        } else {
            val (head, tail) = groups.withIndex().partition { (idx, _) -> idx < groups.size - keep }
            val headMessages = head.map { (_, group) -> group }.flatten()
            if (headMessages.isEmpty()) {
                null
            } else {
                val tailMessages = tail.map { (_, group) -> group }.flatten()
                Pair(headMessages, tailMessages)
            }
        }
    }

    fun renderTranscript(head: List<Message>): String =
        head.joinToString("\n\n") { m ->
            val label = when (m.role) {
                "user" -> "User"
                "assistant" -> "Assistant"
                "tool" -> "Tool result"
                "system" -> "System"
                else -> m.role
            }
            val calls = if (m.toolCalls.isEmpty()) {
                ""
            } else {
                m.toolCalls.joinToString("") { c -> "\n[called ${c.name} with ${c.arguments}]" }
            }
            "$label: ${m.text}$calls"
        }

    fun summarizeRequest(head: List<Message>): List<Message> =
        listOf(
            Message.system(config.summarizePrompt),
            Message.user(renderTranscript(head))
        )

    fun chars(messages: List<Message>): Int = messages.sumOf { it.text.length }

    private fun capSummary(summary: String): String {
        val trimmedText = summary.trim()
        return if (trimmedText.length <= config.summaryMaxChars) {
            trimmedText
        } else {
            trimmedText.take(config.summaryMaxChars) + "\n... [summary truncated]"
        }
    }

    fun summaryMessage(summary: String): Message =
        Message.user(config.summaryPrompt + summary.trim())

    fun compact(
        messages: List<Message>,
        summarize: (List<Message>) -> Outcome<String>
    ): Outcome<Pair<List<Message>, CompactionOutcome>> {
        val before = messages.size
        val (trimmedMessages, trimmed, saved) = trimOldToolResults(messages)

        return when (val splitResult = split(trimmedMessages)) {
            null -> {
                if (trimmed > 0) {
                    ok(Pair(
                        trimmedMessages,
                        CompactionOutcome(trimmed, saved, 0, 0, before, trimmedMessages.size)
                    ))
                } else {
                    err("not enough history to compact")
                }
            }
            else -> {
                val (head, tail) = splitResult
                when (val summaryResult = summarize(summarizeRequest(head))) {
                    is Outcome.Err -> {
                        if (trimmed > 0) {
                            ok(Pair(
                                trimmedMessages,
                                CompactionOutcome(trimmed, saved, 0, 0, before, trimmedMessages.size)
                            ))
                        } else {
                            err("summarisation failed: ${summaryResult.message}")
                        }
                    }
                    is Outcome.Ok -> {
                        if (summaryResult.value.trim().isEmpty()) {
                            err("summarisation returned nothing; history left alone")
                        } else {
                            val summary = capSummary(summaryResult.value)
                            val rewritten = listOf(summaryMessage(summary)) + tail

                            if (chars(rewritten) >= chars(trimmedMessages)) {
                                if (trimmed > 0) {
                                    ok(Pair(
                                        trimmedMessages,
                                        CompactionOutcome(trimmed, saved, 0, 0, before, trimmedMessages.size)
                                    ))
                                } else {
                                    err(
                                        "the summary (${chars(rewritten)} chars) was no smaller than the history it " +
                                        "replaced (${chars(trimmedMessages)} chars); history left alone"
                                    )
                                }
                            } else {
                                ok(Pair(
                                    rewritten,
                                    CompactionOutcome(
                                        trimmedResults = trimmed,
                                        trimmedChars = saved,
                                        summarizedExchanges = exchanges(head).size,
                                        summaryChars = summary.length,
                                        messagesBefore = before,
                                        messagesAfter = rewritten.size
                                    )
                                ))
                            }
                        }
                    }
                }
            }
        }
    }
}
