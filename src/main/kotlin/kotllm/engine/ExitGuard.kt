package kotllm.engine

/** Makes `System.exit` a no-op inside the interpreter.
  *
  * Evaluated code shares this JVM, so a real exit takes the REPL, the session
  * file and any unfinished work with it. Java 17 deprecated `SecurityManager`
  * and `System.setSecurityManager` throws unless the JVM was started with
  * `-Djava.security.manager=allow`, which we do not control - users run
  * `java -jar kotllm.jar`. So the call cannot be trapped at runtime.
  *
  * It was therefore *refused*: any snippet whose text contained the call came
  * back as an error. That worked, but it put the constraint in the system
  * prompt on every single request to explain itself, and a rule the model has
  * to be told about is worse than one it cannot break. Rewriting the call site
  * before compiling costs nothing per request and needs no explanation.
  *
  * The substitute returns `Unit` rather than throwing, so a snippet that ends
  * in `sys.exit(0)` finishes normally instead of reporting an exception the
  * model then tries to fix. The one thing that changes is a call in a position
  * demanding `Nothing` - `val n: Int = sys.exit(1)` - which stops compiling.
  * That is pathological, and the previous behaviour there was a flat refusal
  * anyway.
  */
object ExitGuard {

    /** Where a rewritten exit call lands. Prints, because silence would leave a
      * model that asked to exit assuming it had.
      *
      * `@JvmStatic` and `@JvmOverloads` are both load-bearing, and neither is
      * decoration. The rewritten call is compiled by IMain **as Scala**, and
      * it reads `kotllm.engine.ExitGuard.exit(0)`. Without `@JvmStatic` a
      * Kotlin object member is only reachable as `ExitGuard.INSTANCE.exit(0)`;
      * without `@JvmOverloads` the no-argument `sys.exit` rewrite finds no
      * `exit()`, because Scala cannot see a Kotlin default parameter. Both
      * failures land as a compile error inside the eval, which reads as the
      * guard not working at all. */
    @JvmStatic
    @JvmOverloads
    fun exit(code: Int = 0): Unit =
        println("(exit($code) ignored - the kotllm interpreter session stays alive)")

    private val Target = "kotllm.engine.ExitGuard.exit"

    /** Call sites, not mentions.
      *
      * Anchored on the opening paren so prose survives: the system prompt said
      * "do not call System.exit" for a year, and the guard's textual scan was
      * happy to refuse that sentence once the prompt became a template. The
      * lookbehind keeps `myApp.System.exit` and `r.sys.exitCode` out of it.
      */
    private val CallSites = listOf(
        Regex("""(?<![\w.])System\.exit\s*\("""),
        Regex("""(?<![\w.])sys\.exit\s*\("""),
        // Kotlin's, still listed because an agent file or a skill written for
        // another harness can carry it, and rewriting a call that would not
        // have compiled anyway costs nothing.
        Regex("""(?<![\w.])(?:kotlin\.system\.)?exitProcess\s*\("""),
        Regex("""(?<![\w.])Runtime\.getRuntime\(\)\s*\.\s*(?:halt|exit)\s*\(""")
    )

    /** `sys.exit` is callable without parens, so the paren-anchored rules miss
      * it. Not applied to `System.exit`, which always takes an argument. */
    private val BareSysExit = Regex("""(?<![\w.])sys\.exit(?![\w.(])""")

    /** Rewrites every exit call in `code` to something harmless.
      *
      * A flat regex replace over the whole snippet, including strings and
      * comments. Blanking them first so a mention survived intact was tried and
      * removed: it cost a hand-rolled lexer for a case that does not come up,
      * since the only text reaching here is code the model just wrote. A quoted
      * `System.exit(0)` inside such a snippet gets rewritten too; the string
      * still compiles and reads oddly, which is a better failure than carrying
      * a scanner to prevent it.
      */
    fun defuse(code: String): String {
        val withCalls = CallSites.fold(code) { acc, re -> re.replace(acc, "$Target(") }
        return BareSysExit.replace(withCalls, "$Target()")
    }

    /** Whether `defuse` would change anything, for tests and diagnostics. */
    fun wouldDefuse(code: String): Boolean = defuse(code) != code
}
