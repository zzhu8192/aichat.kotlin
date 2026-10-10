package kotllm.tools

/** How a tool call was authorised. */
sealed interface Decision {
    data object Allow : Decision
    data class Deny(val reason: String) : Decision
}

/** Policy for whether a tool call needs a human yes.
  *
  * `groovy_eval` and `exec` are arbitrary code execution on the user's machine,
  * so the default is to ask. Asking on *every* call would make an agentic loop
  * unusable, hence session-scoped yes-to-all and a never-prompt allowlist.
  */
sealed interface ApprovalMode {

    /** Prompt before each call; `a` approves the rest of the session. */
    data object Confirm : ApprovalMode

    /** Never prompt. Fast, and lets the model rm -rf on a hallucination. */
    data object Auto : ApprovalMode

    /** Refuse every call. Useful for a read-only session, and the safe fallback
      * when nothing can be prompted. */
    data object Deny : ApprovalMode

    companion object {
        fun parse(s: String): ApprovalMode? = when (s.trim().lowercase()) {
            "confirm", "ask", "prompt" -> Confirm
            "auto", "always", "yes" -> Auto
            "deny", "never", "off" -> Deny
            else -> null
        }

        fun name(m: ApprovalMode): String = when (m) {
            is Confirm -> "confirm"
            is Auto -> "auto"
            is Deny -> "deny"
        }
    }
}

/** Asks the user. Supplied by the REPL, which owns the terminal. */
interface Prompter {

    /** @return true to allow, false to refuse. */
    fun confirm(toolName: String, preview: String): Prompter.Answer

    /** Puts a question to the user with numbered choices.
      *
      * `null` means there was nobody to ask, which the caller must report rather
      * than paper over -- see `Prompter.unavailable`.
      */
    fun ask(question: String, options: List<String>, multi: Boolean): List<String>?

    // In the interface, not its companion: nested in the companion its real
    // name is `Prompter.Companion.Answer`, and every `Prompter.Answer` call
    // site fails.
    sealed interface Answer {
        data object Yes : Answer
        data object No : Answer
        data object YesToAll : Answer
    }

    companion object {

        /** Used when there is nobody to ask -- piped input, single-hit mode, a
          * script. Refusing is the only safe default: silently auto-approving
          * arbitrary code because no terminal was attached would be the worst
          * possible failure mode. */
        val unavailable: Prompter = object : Prompter {
            override fun confirm(toolName: String, preview: String): Answer = Answer.No

            /** `null`, never a default choice. Picking the first option because no
              * terminal was attached would fabricate a decision the user never made,
              * and the model would then build on it as though they had. */
            override fun ask(question: String, options: List<String>, multi: Boolean): List<String>? = null
        }
    }
}

/** Applies [[ApprovalMode]], the allowlist, and session-scoped yes-to-all. */
class Approval(
    /** Read on every decision, not captured once: `/set tool_approval auto`
      * has to take effect on the next call, and a mode snapshotted at
      * construction would make that setting silently do nothing. */
    private val mode: () -> ApprovalMode,
    allowlist: List<String>,
    private val prompter: Prompter,
    private val interactive: Boolean
) {

    private var approveAll = false

    /** Allowlist entries are matched as anchored regexes against the preview, so
      * `git (status|diff|log).*` works. An unparseable pattern is ignored with a
      * warning rather than failing the call. */
    private val patterns: List<Regex> = allowlist.mapNotNull { p ->
        try {
            Regex("^" + p + "$")
        } catch (e: Exception) {
            System.err.println("kotllm: ignoring invalid exec_allowlist pattern '$p'")
            null
        }
    }

    fun allowlisted(preview: String): Boolean =
        patterns.any { it.containsMatchIn(preview.trim()) }

    /** @param readOnly true for a tool that can only read; see `Tool.readOnly`.
      *                 Exempt under `confirm`, never under `deny` -- somebody who
      *                 set deny asked for no tool calls at all, and carving an
      *                 exception out of that would be the opposite of what the
      *                 setting says.
      */
    fun decide(toolName: String, preview: String, readOnly: Boolean = false): Decision = when (mode()) {
        is ApprovalMode.Auto -> Decision.Allow

        is ApprovalMode.Deny ->
            Decision.Deny(
                "refused: tool_approval is set to deny, so '$toolName' was not run"
            )

        is ApprovalMode.Confirm ->
            if (readOnly || approveAll || allowlisted(preview)) Decision.Allow
            else if (!interactive)
                Decision.Deny(
                    "refused: '$toolName' needs approval but there is no terminal to ask. " +
                        "Set tool_approval: auto to allow unattended runs, or add a matching exec_allowlist entry."
                )
            else when (prompter.confirm(toolName, preview)) {
                is Prompter.Answer.Yes -> Decision.Allow
                is Prompter.Answer.YesToAll -> {
                    approveAll = true
                    Decision.Allow
                }
                is Prompter.Answer.No -> Decision.Deny("declined by the user")
            }
    }

    /** True once the user has said yes-to-all, for `/status`. */
    fun approvingAll(): Boolean = approveAll

    fun reset() {
        approveAll = false
    }
}
