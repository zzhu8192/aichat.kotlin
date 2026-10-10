# Agents

The reasoning is in [agent-design.md](agent-design.md); what follows is what is
load-bearing in the code.

Designed and justified in [agent-design.md](agent-design.md); what matters here is what is load-bearing in the
code.

**There is no agent file format.** An agent is a template. Prose is output, `<% %>` is the
program, and the calls come from `AgentApi`, bound into the interpreter session as
`__agentApi` rather than `agent`.
`AgentApi` is a plain Kotlin class, so `agent(...)` itself is one of the shorthands bound into
the session alongside `llm`, `system`, `finish` and `fail`.

**The shorthands are closures in the binding, not methods.** A method declared in one Groovy
script belongs to that script's class and is invisible to the next one; an undeclared assignment
lands in the shared `Binding`, which every later snippet sees. They are also evaluated *into the
session* rather than prepended to the generated program -- prepending would shift every line and
break the map that reports a compile failure against the template line.

**Every shorthand is positional.** Groovy has no named arguments for closures: it parses
`llm("x", alias = "gemma")` as an assignment expression and then calls `llm(String, String)`,
which has no signature. So it is `llm(prompt, fresh, alias, tools)`, all with defaults, and you
pass `null` to skip one.

- **The mode switch is what the file did**, not what it looks like: `AgentRun.touched` is set by
  any of the five calls. A file that calls none of them returns `AgentResult.Prompt` and becomes a
  system prompt, so **a role is a file that never calls `llm`**, and adding a `<% %>` tag to a
  prompt cannot silently change what the file means.
- **`system { }` captures by append-then-cut.** The block's prose appends to the output sink like
  any other literal and is then cut back out (`Sink.length`, `Sink.cut`). No dynamic sink stack,
  nothing changed in `Template.parse` or `emit`, line map intact.
- **`llm(prompt, true)` reuses one scratch runner and clears its session**, rather than forking a
  runner per call -- which would start a compiler per call the first time one used `groovy_eval`.
  Twenty 3,000-token files cost ~630k input tokens in a shared conversation, because call *n*
  re-sends files 1..*n*, and ~61k with `fresh`.
- **`llm` has one signature.**
  `llm(prompt, fresh = false, alias = null, tools = null)`. `alias` takes an `aliases:` name or a
  model id; absent or blank means the run's default.

  An `llm(alias, prompt)` overload was built and then removed. It made two spellings of one
  call whose leading arguments were both `String`, so transposing them was a silent bug the
  compiler could not catch -- and it forced `llm("", prompt)` on every call that did not care
  about the model, which is most of them. Keeping the prompt first means the common call stays
  `llm("...")` and choosing a model is `llm("...", false, "gemma")`.

  That last spelling is worse than it was under Scala, where the argument could be named. It is
  the price of the shorthands being closures, and the mitigation is that `fresh` and `alias` are
  adjacent and differently typed, so a transposition fails rather than answering on the wrong
  model. There is a test for exactly that pair.

  An unresolvable alias is not fatal -- the call answers on the current model -- but the warning
  names the model it fell back to and lists the known aliases. The failure that matters is a
  typo quietly answering from the expensive tier.
- **`finish`/`fail` throw, and the throw does not reach the caller.** `GroovyEngine` catches
  everything the evaluated program throws and reports it as a diagnostic, so `Agent.run` checks
  `AgentRun.stop` instead and treats a recorded stop as a clean exit. The output is captured at
  throw time for the same reason.
- **Auto-approval is announced and logged.** An agent is unattended, so under `confirm` its first
  `exec` would refuse; the exemption is scoped to the run, printed when it starts, and every tool
  call lands in `log.md`. Unprompted must not also mean unrecorded.
- **stderr for replies, stdout for the artifact** (`Runner.renderTo`), so a run can be watched and
  piped at once.
- **Typed output is gone.** `llmAs[T]` and `enumOf[T]` derived a JSON schema and a reader from a
  Scala case class through upickle's `macroRW`, plus a registry keyed by runtime type. None of
  that survives without a Scala compiler in the process, and it is not rebuilt: an agent that
  wants a shape asks for it in the prompt and checks the reply itself, which
  `examples/agents/review.md` does with a regex. That is the honest version -- the model can
  still answer something else, and the file notices rather than pretending it parsed.

  What is lost with it is real and worth stating. The schema was derived from the type, so it
  could not drift from what the code then read; and a parse failure was *repaired* rather than
  retried, sending a different prompt on the same conversation so the model saw its own bad
  reply. A regex check gets neither.
- **Budgets end a run with a verdict rather than throwing**: `maxCalls` (50), optional
  `maxTokens`/`maxSeconds`, and a no-progress rule that stops after the same prompt three times
  running. A doom-loop should cost a budget, not a mystery.
- **`Paths.rolesDir` and `macrosDir` are gone**, along with the directories they created. Roles are
  agent files; macros are not being built. `-r`, `--role`, `--macro`, `--list-roles` and
  `--list-macros` are rejected with what answers them instead of "lands in a later increment" --
  promising a feature that has been deliberately dropped is the over-promising this file warns
  about elsewhere.

Known cosmetic: a `<% %>` tag alone on a line still leaves its newline, so agent output has more
blank lines than it needs. A trim marker (`-%>`) would fix it and would change existing template
semantics, so it is deliberately not in this increment.


## chat.md: the system prompt is an agent too

`agents/chat.md` is the default chat agent. It calls none of the five, so by the rule above it is
a prompt rather than a program -- which is exactly what a system prompt is.
It is seeded on first run and read from disk on every turn after, so an edit applies to the next
message with no restart.

That file *is* the system prompt. It used to be a `List(...)` inside `Runner.systemPrompt`: eight
sections, in an order only the source revealed, that no user could reorder, drop or annotate.
Now each section reaches the model by being rendered:

```
<% for (t in prompt.userToolList) { %>  <%= t.name %> -- <%= t.description %>
<% } %>

<%= prompt.systemPrompt() %>
```

Delete a loop and that section is gone from the prompt. Nothing is appended behind the template,
which is the property worth protecting: what the model receives is the rendering of this file and
nothing else.

`prompt.*` comes in two kinds. **Data you loop over:**

| Call | Section |
| --- | --- |
| `prompt.libraries`, `.artifacts`, `.extraClasspath` | the interpreter manifest: what `groovy_eval` can import |
| `prompt.toolNames`, `.hasTool(n)` | which tools are offered this turn |
| `prompt.skillList`, `.skillsDropped`, `.skillToolNames` | installed skills, and when to load them |
| `prompt.memoryList`, `.memoryIndex`, `.memoryDropped` | durable memories |
| `prompt.userToolList` | your own Groovy tools from `config/tools` |
| `prompt.taskList`, `.tasksOpen` | the current task checklist |
| `prompt.indexes`, `.activeIndex`, `.injectingContext` | which retrieval indexes exist |
| `prompt.groovyVersion`, `.javaVersion`, `.model`, `.date`, `.cwd` | facts about the session |

**And three already-formatted sections**, because nothing else renders them:
`prompt.rag()`, `prompt.ragContext()`, `prompt.systemPrompt()`.

There used to be a formatted twin for each of the loops -- `tools()`, `skills()`, `memories()`,
`userTools()`, `tasks()`. They are gone, and the reason is worth keeping: two copies of the same
text drifted within a day. `chat.md` was condensed and the Kotlin behind `tools()` was not, so a
template calling it quietly served the older, longer prose. The template owns the wording now.

Three things worth knowing:

- **`prompt` is bound in ordinary agent runs as well.** `agent.*` is control, `prompt.*` is
  content, so an agent's `system { }` can pull in the live library list rather than restating it
  and drifting from what the harness actually sends.
- **The binding could not be called `kotllm`.** Measured, not assumed, on the Scala engine this
  replaced: binding a value of that name shadowed the root package for the rest of the session,
  and the imports the agent shorthands themselves ran stopped resolving. The name stayed
  `__agentApi`, and the reason is recorded because the hazard is not obviously Scala-specific.
- **Runs of blank lines are collapsed after rendering.** A section with nothing to say renders
  nothing, and the blank line the file left around it would otherwise reach the model. That is
  why the loops need no `if` around them, and why each formatted section returns `String` rather
  than `String?`: a template author should never write `?: ""`.

**Do not document the comment syntax inside a comment.** Writing the closing delimiter as prose in
the header block ends the comment at that line, and everything below it becomes live template
code. The shipped file did exactly that for one revision, and the example further down -- a read
of a file that did not exist -- ran on every turn. `ChatAgentTest` renders the packaged template
against a stub and asserts both the section order and that every section appears.
