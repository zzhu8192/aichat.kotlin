package kotllm.tools

import kotlin.test.Test
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.types.shouldBeInstanceOf

class ApprovalTest {

    /** Answers a fixed sequence, and records what it was asked. */
    private class ScriptedPrompter(vararg answers: Prompter.Answer) : Prompter {
        private val queue = ArrayDeque(answers.toList())
        var asked: List<String> = emptyList()

        override fun confirm(toolName: String, preview: String): Prompter.Answer {
            asked = asked + "$toolName:$preview"
            return if (queue.isEmpty()) Prompter.Answer.No else queue.removeFirst()
        }

        // This spec is about approval, not questions.
        override fun ask(question: String, options: List<String>, multi: Boolean): List<String>? = null
    }

    private fun approval(
        mode: ApprovalMode,
        prompter: Prompter = Prompter.unavailable,
        allowlist: List<String> = emptyList(),
        interactive: Boolean = true
    ): Approval = Approval({ mode }, allowlist, prompter, interactive)

    @Test fun `auto mode never asks`() {
        val p = ScriptedPrompter()
        approval(ApprovalMode.Auto, p).decide("exec", "rm -rf /") shouldBe Decision.Allow
        p.asked shouldBe emptyList()
    }

    @Test fun `deny mode refuses without asking`() {
        val p = ScriptedPrompter(Prompter.Answer.Yes)
        approval(ApprovalMode.Deny, p).decide("exec", "ls").shouldBeInstanceOf<Decision.Deny>()
        p.asked shouldBe emptyList()
    }

    @Test fun `confirm mode asks, and yes allows`() {
        val p = ScriptedPrompter(Prompter.Answer.Yes)
        approval(ApprovalMode.Confirm, p).decide("exec", "ls") shouldBe Decision.Allow
        p.asked.size shouldBe 1
    }

    @Test fun `confirm mode asks, and no refuses`() {
        val p = ScriptedPrompter(Prompter.Answer.No)
        approval(ApprovalMode.Confirm, p).decide("exec", "ls").shouldBeInstanceOf<Decision.Deny>()
    }

    @Test fun `yes-to-all stops asking for the rest of the session`() {
        val p = ScriptedPrompter(Prompter.Answer.YesToAll)
        val policy = approval(ApprovalMode.Confirm, p)

        policy.decide("exec", "first") shouldBe Decision.Allow
        policy.decide("exec", "second") shouldBe Decision.Allow
        policy.decide("scala_eval", "third") shouldBe Decision.Allow

        // Asked exactly once, not once per call.
        p.asked.size shouldBe 1
        policy.approvingAll() shouldBe true
    }

    @Test fun `an allowlisted command is not prompted`() {
        val p = ScriptedPrompter(Prompter.Answer.No)
        val policy = approval(ApprovalMode.Confirm, p, allowlist = listOf("git (status|diff|log).*"))

        policy.decide("exec", "git status") shouldBe Decision.Allow
        policy.decide("exec", "git log --oneline -3") shouldBe Decision.Allow
        p.asked shouldBe emptyList()
    }

    @Test fun `the allowlist is anchored, so it cannot be prefixed past`() {
        val p = ScriptedPrompter(Prompter.Answer.No)
        val policy = approval(ApprovalMode.Confirm, p, allowlist = listOf("git status"))

        // Would be allowed by an unanchored match.
        policy.decide("exec", "git status && rm -rf /").shouldBeInstanceOf<Decision.Deny>()
    }

    @Test fun `an invalid allowlist pattern is ignored rather than failing the call`() {
        val p = ScriptedPrompter(Prompter.Answer.Yes)
        val policy = approval(ApprovalMode.Confirm, p, allowlist = listOf("[unclosed"))

        policy.decide("exec", "ls") shouldBe Decision.Allow
    }

    @Test fun `confirm mode refuses when there is no terminal to ask`() {
        // Auto-approving arbitrary code because nobody was attached would be the
        // worst possible default.
        val policy = approval(ApprovalMode.Confirm, Prompter.unavailable, interactive = false)
        val decision = policy.decide("exec", "ls")

        decision.shouldBeInstanceOf<Decision.Deny>()
        decision.reason shouldContain "no terminal"
    }

    @Test fun `an allowlisted command still runs with no terminal`() {
        val policy = approval(ApprovalMode.Confirm, Prompter.unavailable, listOf("ls.*"), interactive = false)
        policy.decide("exec", "ls -la") shouldBe Decision.Allow
    }

    @Test fun `auto mode works with no terminal, for unattended runs`() {
        approval(ApprovalMode.Auto, Prompter.unavailable, interactive = false)
            .decide("exec", "ls") shouldBe Decision.Allow
    }

    @Test fun `reset clears yes-to-all`() {
        val p = ScriptedPrompter(Prompter.Answer.YesToAll, Prompter.Answer.No)
        val policy = approval(ApprovalMode.Confirm, p)

        policy.decide("exec", "one") shouldBe Decision.Allow
        policy.reset()
        policy.decide("exec", "two").shouldBeInstanceOf<Decision.Deny>()
    }

    @Test fun `the mode is re-read on every decision, so set takes effect`() {
        // A mode captured at construction would make `/set tool_approval auto`
        // silently do nothing.
        var current: ApprovalMode = ApprovalMode.Deny
        val policy = Approval({ current }, emptyList(), ScriptedPrompter(), interactive = true)

        policy.decide("exec", "ls").shouldBeInstanceOf<Decision.Deny>()
        current = ApprovalMode.Auto
        policy.decide("exec", "ls") shouldBe Decision.Allow
    }

    @Test fun `a read-only tool is not prompted for under confirm`() {
        // The exemption exists because under confirm there is nobody to ask in a
        // background turn or a script, so retrieval and fetching were refused
        // outright. These three read; they cannot mutate or execute.
        val p = ScriptedPrompter(Prompter.Answer.No)

        approval(ApprovalMode.Confirm, p).decide("search", "query", readOnly = true) shouldBe Decision.Allow
        approval(ApprovalMode.Confirm, p).decide("fetch", "https://x/y", readOnly = true) shouldBe Decision.Allow
        // ... and the code tools still ask, which is the whole point of keeping
        // "confirm by default".
        approval(ApprovalMode.Confirm, p).decide("exec", "rm -rf /").shouldBeInstanceOf<Decision.Deny>()
    }

    @Test fun `deny outranks read-only, because deny means no tool calls at all`() {
        val p = ScriptedPrompter()
        approval(ApprovalMode.Deny, p).decide("fetch", "https://x/y", readOnly = true).shouldBeInstanceOf<Decision.Deny>()
    }

    @Test fun `a read-only tool needs no terminal, so unattended runs work`() {
        val policy = Approval({ ApprovalMode.Confirm }, emptyList(), Prompter.unavailable, interactive = false)

        policy.decide("search", "query", readOnly = true) shouldBe Decision.Allow
        policy.decide("scala_eval", "1 + 1").shouldBeInstanceOf<Decision.Deny>()
    }

    @Test fun `approval modes parse their documented spellings`() {
        ApprovalMode.parse("confirm") shouldBe ApprovalMode.Confirm
        ApprovalMode.parse("AUTO") shouldBe ApprovalMode.Auto
        ApprovalMode.parse("deny") shouldBe ApprovalMode.Deny
        ApprovalMode.parse("maybe") shouldBe null
    }
}
