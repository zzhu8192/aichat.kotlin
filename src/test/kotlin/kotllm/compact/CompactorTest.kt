package kotllm.compact

import kotlin.test.Test
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.collections.shouldNotContain
import io.kotest.matchers.collections.shouldBeEmpty
import kotllm.*
import kotllm.client.Message
import kotllm.client.Part
import kotllm.client.ToolCall

class CompactorTest {

  private fun compactor(
      keepRecent: Int = 2,
      minHead: Int = 1,
      trim: Boolean = true,
      toolMax: Int = 50,
      percent: Int = 70,
      absolute: Int? = null,
      enabled: Boolean = true
  ): Compactor = Compactor(
    CompactionConfig.default.copy(
      enabled = enabled,
      thresholdPercent = percent,
      absoluteThreshold = absolute,
      keepRecentExchanges = keepRecent,
      minHeadExchanges = minHead,
      trimToolResults = trim,
      toolResultMaxChars = toolMax
    )
  )

  /** A conversation of `n` plain exchanges. */
  private fun plainHistory(n: Int): List<Message> =
    (1..n).flatMap { i -> listOf(Message.user("question $i"), Message.assistant("answer $i")) }

  /** An exchange whose assistant turn called a tool. */
  private fun toolExchange(i: Int, resultChars: Int = 200): List<Message> =
    listOf(
      Message.user("do task $i"),
      Message("assistant", listOf(Part.Text("working")), listOf(ToolCall("call_$i", "exec", "{}"))),
      Message.tool("call_$i", "x".repeat(resultChars)),
      Message.assistant("done $i")
    )

  // Named to avoid colliding with the top-level `kotllm.ok`, which this would
  // otherwise shadow and recurse into.
  private fun okSummarizer(summary: String): (List<Message>) -> Outcome<String> = { _ -> ok(summary) }

  private fun <A> expectErr(outcome: Outcome<A>): String =
    outcome.fold({ it }, { throw AssertionError("expected a failure but got $it") })

  // ---- trigger -----------------------------------------------------------

  @Test fun `the trigger fires on a share of the model window`() {
    val c = compactor(percent = 70)
    c.shouldCompact(6999, 10000) shouldBe false
    c.shouldCompact(7001, 10000) shouldBe true
  }

  @Test fun `70 percent is the default, not 95`() {
    // The compaction request itself sends the head and needs room for the
    // summary, so waiting until nearly full means being unable to afford the
    // call that would fix it.
    CompactionConfig.default.thresholdPercent shouldBe 70
  }

  @Test fun `an absolute threshold works when the model declares no window`() {
    val c = compactor(absolute = 4000)
    c.shouldCompact(4001, null) shouldBe true
    c.shouldCompact(3999, null) shouldBe false
  }

  @Test fun `with no window and no absolute threshold, nothing fires`() {
    compactor().shouldCompact(999999, null) shouldBe false
  }

  @Test fun `disabled never fires`() {
    compactor(enabled = false).shouldCompact(999999, 1000) shouldBe false
  }

  // ---- exchange grouping -------------------------------------------------

  @Test fun `an exchange runs from one user message to just before the next`() {
    val groups = compactor().exchanges(plainHistory(3))
    groups.size shouldBe 3
    groups.forEach { it.size shouldBe 2 }
  }

  @Test fun `tool traffic stays inside the exchange that caused it`() {
    // The endpoint requires one tool result per tool_call_id, so a split
    // between an assistant tool_calls message and its results is malformed.
    val groups = compactor().exchanges(toolExchange(1) + toolExchange(2))
    groups.size shouldBe 2
    groups.first().size shouldBe 4
  }

  @Test fun `messages before the first user turn form their own leading group`() {
    // A compaction summary sits here, so this is the normal case after the
    // first compaction, not an oddity.
    val messages = listOf(Message.assistant("orphan")) + plainHistory(1)
    val groups   = compactor().exchanges(messages)

    groups.size shouldBe 2
    groups.first().map { it.text } shouldBe listOf("orphan")
    groups.flatten().size shouldBe messages.size
  }

  // ---- splitting ---------------------------------------------------------

  @Test fun `the split keeps the configured number of recent exchanges verbatim`() {
    val (head, tail) = compactor(keepRecent = 2).split(plainHistory(5))!!
    compactor().exchanges(head).size shouldBe 3
    compactor().exchanges(tail).size shouldBe 2
    tail.map { it.text } shouldContain "question 5"
  }

  @Test fun `a split never separates a tool call from its result`() {
    val history      = (1..4).flatMap { i -> toolExchange(i) }
    val (head, tail) = compactor(keepRecent = 2).split(history)!!

    fun balanced(ms: List<Message>): Boolean {
      val requested = ms.flatMap { it.toolCalls }.map { it.id }.toSet()
      val answered  = ms.mapNotNull { it.toolCallId }.toSet()
      return requested == answered
    }

    balanced(head) shouldBe true
    balanced(tail) shouldBe true
  }

  @Test fun `too little history to be worth a request yields no split`() {
    compactor(keepRecent = 3, minHead = 2).split(plainHistory(4)) shouldBe null
  }

  // ---- trimming ----------------------------------------------------------

  @Test fun `old tool results are trimmed and say so`() {
    val history                 = (1..4).flatMap { i -> toolExchange(i, 500) }
    val (trimmed, count, saved) = compactor(keepRecent = 2, toolMax = 50).trimOldToolResults(history)

    count shouldBe 2 // the two oldest exchanges
    saved shouldBe (500 - 50) * 2
    val trimmedText = trimmed.filter { it.role == "tool" }.map { it.text }
    trimmedText.first() shouldContain "older tool result trimmed"
    trimmedText.first() shouldContain "500 chars originally"
  }

  @Test fun `recent tool results are left alone, since they may still be in play`() {
    val history      = (1..4).flatMap { i -> toolExchange(i, 500) }
    val (trimmed, _, _) = compactor(keepRecent = 2, toolMax = 50).trimOldToolResults(history)

    trimmed.filter { it.role == "tool" }.last().text.length shouldBe 500
  }

  @Test fun `trimming never touches user or assistant messages`() {
    val history      = (1..4).flatMap { i -> toolExchange(i, 500) }
    val (trimmed, _, _) = compactor(keepRecent = 1, toolMax = 10).trimOldToolResults(history)

    trimmed.filter { it.role == "user" }.map { it.text } shouldBe history.filter { it.role == "user" }.map { it.text }
  }

  @Test fun `trimming preserves message count and order`() {
    val history      = (1..3).flatMap { i -> toolExchange(i, 500) }
    val (trimmed, _, _) = compactor(keepRecent = 1, toolMax = 10).trimOldToolResults(history)

    trimmed.size shouldBe history.size
    trimmed.map { it.role } shouldBe history.map { it.role }
  }

  @Test fun `trimming can be disabled`() {
    val history         = (1..4).flatMap { i -> toolExchange(i, 500) }
    val (out, count, _) = compactor(trim = false).trimOldToolResults(history)

    count shouldBe 0
    out shouldBe history
  }

  // ---- summarising -------------------------------------------------------

  @Test fun `the summarising request is one user message plus an instruction`() {
    // Replaying raw tool_calls into a side request would have to carry its
    // results to stay well formed, and invites continuation over summary.
    val request = compactor().summarizeRequest(toolExchange(1))

    request.size shouldBe 2
    request.first().role shouldBe "system"
    request[1].role shouldBe "user"
    request.flatMap { it.toolCalls }.shouldBeEmpty()
    request.mapNotNull { it.toolCallId }.shouldBeEmpty()
  }

  @Test fun `the transcript labels roles and records tool calls`() {
    val transcript = compactor().renderTranscript(toolExchange(7))
    transcript shouldContain "User: do task 7"
    transcript shouldContain "Assistant:"
    transcript shouldContain "Tool result:"
    transcript shouldContain "called exec"
  }

  @Test fun `the summary re-enters as a user message with the recap prefix`() {
    val m = compactor().summaryMessage("they decided to use Maven")
    m.role shouldBe "user"
    m.text shouldContain "summary of the earlier conversation"
    m.text shouldContain "they decided to use Maven"
  }

  // ---- full compaction ---------------------------------------------------

  @Test fun `compaction replaces the head with a summary and keeps the tail`() {
    val history               = plainHistory(6)
    val (rewritten, outcome)  =
      compactor(keepRecent = 2).compact(history, okSummarizer("the recap")).orNull()!!

    rewritten.first().text shouldContain "the recap"
    rewritten.map { it.text } shouldContain "question 6"
    rewritten.map { it.text } shouldNotContain "question 1"
    outcome.summarizedExchanges shouldBe 4
    (outcome.messagesAfter < outcome.messagesBefore) shouldBe true
  }

  @Test fun `a compacted history is still well formed for the next request`() {
    val history         = (1..5).flatMap { i -> toolExchange(i) }
    val (rewritten, _)  = compactor(keepRecent = 2).compact(history, okSummarizer("recap")).orNull()!!

    val requested = rewritten.flatMap { it.toolCalls }.map { it.id }.toSet()
    val answered  = rewritten.mapNotNull { it.toolCallId }.toSet()
    (requested - answered).shouldBeEmpty()
    (answered - requested).shouldBeEmpty()
  }

  @Test fun `a failed summarisation is not destructive`() {
    // Losing the conversation because a side call failed would be far worse
    // than carrying a large history.
    val history = plainHistory(6)
    val result  = compactor(keepRecent = 2, trim = false).compact(history) { err("endpoint down") }

    expectErr(result) shouldContain "summarisation failed"
  }

  @Test fun `a failed summarisation still keeps any trimming already done`() {
    val history        = (1..5).flatMap { i -> toolExchange(i, 500) }
    val (out, outcome) =
      compactor(keepRecent = 2, toolMax = 50).compact(history) { err("down") }.orNull()!!

    (outcome.trimmedResults > 0) shouldBe true
    outcome.summarizedExchanges shouldBe 0
    out.size shouldBe history.size
  }

  @Test fun `a summary no smaller than what it replaced is refused`() {
    // Found live: summarising a handful of short exchanges produced *more*
    // prose than it replaced, so the context grew and then compacted on every
    // turn. Compaction that does not shrink is worse than none.
    val history = plainHistory(6)
    val bloated = "verbose recap ".repeat(200)

    val result = compactor(keepRecent = 2, trim = false).compact(history, okSummarizer(bloated))
    expectErr(result) shouldContain "no smaller than the history it replaced"
  }

  @Test fun `a shrinking summary is accepted`() {
    val history = (1..6).flatMap { listOf(Message.user("q".repeat(200)), Message.assistant("a".repeat(200))) }
    val (rewritten, outcome) =
      compactor(keepRecent = 2, trim = false).compact(history, okSummarizer("short recap")).orNull()!!

    outcome.summarizedExchanges shouldBe 4
    (compactor().chars(rewritten) < compactor().chars(history)) shouldBe true
  }

  @Test fun `an over-long summary is capped`() {
    val c = Compactor(
      CompactionConfig.default.copy(keepRecentExchanges = 2, minHeadExchanges = 1,
                                    trimToolResults = false, summaryMaxChars = 100)
    )
    val history = (1..6).flatMap { listOf(Message.user("q".repeat(500)), Message.assistant("a".repeat(500))) }

    val (rewritten, outcome) = c.compact(history, okSummarizer("x".repeat(5000))).orNull()!!
    (outcome.summaryChars <= 100 + 30) shouldBe true // plus the truncation note
    rewritten.first().text shouldContain "summary truncated"
  }

  @Test fun `a bloated summary still keeps any trimming already done`() {
    val history        = (1..5).flatMap { i -> toolExchange(i, 500) }
    val (out, outcome) =
      compactor(keepRecent = 2, toolMax = 50).compact(history, okSummarizer("verbose ".repeat(500))).orNull()!!

    (outcome.trimmedResults > 0) shouldBe true
    outcome.summarizedExchanges shouldBe 0
    out.size shouldBe history.size
  }

  @Test fun `an empty summary is refused rather than replacing history with nothing`() {
    val result = compactor(keepRecent = 2, trim = false).compact(plainHistory(6), okSummarizer("   "))
    expectErr(result) shouldContain "returned nothing"
  }

  @Test fun `too little history reports that, and changes nothing`() {
    val result = compactor(keepRecent = 3, minHead = 2)
      .compact(plainHistory(2), okSummarizer("recap"))
    expectErr(result) shouldContain "not enough history"
  }

  @Test fun `trimming alone can satisfy a compaction, with no summary request`() {
    // minHead is set high enough that there is not yet enough history to be
    // worth a summarising request, but old tool results can still be trimmed
    // -- the cheap mechanism working on its own, with no round trip.
    var summarised = false
    val history    = (1..5).flatMap { i -> toolExchange(i, 500) }
    val c          = compactor(keepRecent = 3, minHead = 5, toolMax = 50)

    val (out, outcome) = c
      .compact(history) { summarised = true; ok("recap") }
      .orNull()!!

    summarised shouldBe false
    outcome.trimmedResults shouldBe 2
    outcome.summarizedExchanges shouldBe 0
    out.size shouldBe history.size
  }
}
