<%-- An agent is an ordinary template. Prose is output, <% %> is the program,
     and these calls are bound:

       agent(model, tools, maxCalls, maxTokens, maxSeconds, quiet, approval)
       system { ... }   systemText(s)   llm(prompt, fresh, alias, tools)
       finish(v)  fail(r)  log(m)  args()  last()  runDir()

     All positional, all with defaults, so pass null to skip one.

       kotllm --agent review --dry-run              see its shape, no tokens
       kotllm --agent review --agent-var base=main  run it

     Copy into <config dir>/agents/ to use it.

     The program is Groovy. Inside one file `def` is fine; it only matters
     across separate eval calls, where a `def` does not survive and a bare
     assignment does. --%>

<% agent(null, "exec", 40) %>

<% system { %>
You review changes for release risk. You are terse and you cite files.
Prefer the least alarming verdict the evidence supports.
<% } %>

# Release review

<% // Typed output is deliberately not used: `llmAs`/`enumOf` were removed with
   // upickle and have not returned. So the shape is asked for in the prompt and
   // verified here. That is the honest version -- the model can still answer
   // something else, and this notices rather than pretending it parsed.
   def base = args().get("base") ?: "HEAD~1"

   def sh = { String cmd ->
     try {
       def p = ["git"] + cmd.split(" ").toList()
       def proc = p.execute(null, runDir().toFile().parentFile)
       def out = proc.text
       proc.waitFor()
       out
     } catch (Throwable t) { "" }
   }

   def verdictOf = { String reply ->
     def m = reply =~ /(?i)verdict:\s*(ok|risky|blocked)/
     m ? m[0][1].toLowerCase() : "unread"
   }

   def changed = sh("diff --name-only ${base}")
     .readLines()
     .findAll { it.trim() && (it.endsWith(".kt") || it.endsWith(".groovy")) }

   if (!changed) finish("no reviewable files changed against ${base}")

   def verdicts = []
   def blocked = []
%>
<%= changed.size() %> file(s) changed against `<%= base %>`.

<% for (f in changed) {
     def diff = sh("diff ${base} -- ${f}")

     // fresh = true: the diff goes into a scratch conversation and dies there.
     // Only the verdict comes back, so reviewing forty files costs forty small
     // calls rather than one conversation holding forty diffs.
     def reply = llm("Review this diff for release risk.\nAnswer with VERDICT: ok|risky|blocked on its own line, then one sentence of why.\n\n" + diff, true)
     def verdict = verdictOf(reply)
     verdicts << verdict
     if (verdict == "blocked") blocked << f
     def why = reply.readLines().find { it.trim() && !(it =~ /(?i)verdict:/) } ?: ""
%>- `<%= f %>` **<%= verdict %>** <%= why %>
<% } %>

<% if (blocked) { finish("blocked: ${blocked.size()} file(s) -- ${blocked.join(', ')}") }
   else if (verdicts.any { it == "risky" }) { finish("risky: ${verdicts.count { it == 'risky' }} of ${verdicts.size()}") }
   else { finish("ok: ${verdicts.size()} file(s) reviewed") } %>
