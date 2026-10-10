# Agents: a design proposal

Status: **built.** 1.0.5-SNAPSHOT: `agent()`, `system { }`, `llm()`, `finish()`, `fail()`, the
run directory, `--agent` / `--agent-var` / `/agent` / `--list-agents`, `--dry-run`.
1.0.6-SNAPSHOT: `llmAs[T]` with a repair loop. 1.0.7-SNAPSHOT: `enumOf[T]`, so a closed set of
values reaches the prompt and is enforced on the way back. Deferred, as recorded below: a
`state { }` block and concurrent fan-out.

**Two things below are no longer true, and the file is kept as the reasoning record rather than
rewritten around them.** Agent files are **Groovy**, not Scala, so every call is positional --
Groovy has no named arguments for closures. And `llmAs[T]`/`enumOf[T]` are **gone**: both were
built on upickle's `macroRW` and a registry keyed by Scala runtime types, neither of which
survives without a Scala compiler in the process. `examples/agents/review.md` is the current
worked example, and it checks the shape it asked for with a regex instead. Sections that argue
for typed output still explain *why* it was worth having, which is the part worth keeping.

## The short version

An agent is a single markdown file, and **there is no agent file format**. It is an ordinary
kotllm template: prose is output, `<% %>` is the program, and everything structural is a
function call. No frontmatter, no special headings, no new syntax to learn or to parse.

```
llm(prompt)                  one turn on the run's conversation; returns the reply as a String
llm(prompt, false, "gemma")  the same, on the model that alias names (see `aliases:` in config)
llm(prompt, true)            one turn on a scratch conversation, thrown away afterwards
llmAs[T](prompt)             the same, deserialised into T; String stays the default
enumOf[T](values*)           a ReadWriter for a closed set, whose values reach the prompt
```

`llm` is **not** interactivity. It is a function call to the model from inside the file. Nothing in
a run stops to ask a human: that is what auto-approval means, and the REPL is where a conversation
with a person belongs.

**There is one conversation per run.** Every `llm` call joins it and every reply stays in it, so a
later call remembers an earlier one. `fresh = true` opts out: a scratch conversation, one question,
take the answer, burn the conversation. Only the returned `String` survives, and only where you
put it.

The only conventions left are where the file lives and where a run writes its artifacts.

---

## 1. What an agent actually is, and what kotllm already has

Your reading is right, and the framing worth keeping is the one it ends on: **an agent is a
control loop where the model chooses the next step instead of an `if`**. Strip the metaphors and
there are four mechanical parts. kotllm has all four already:

| Part | Where it already lives |
| --- | --- |
| Policy engine (a model that emits tool calls) | `client/`, `Wire`, `Content` -- verified against the live gateway |
| Tools (deterministic primitives behind a schema) | `tools/Tools.kt`: `groovy_eval`, `exec`, `remember`, `search`, `fetch`, `web_search` |
| Working state | `config/Session.kt` + `util/TokenLedger` |
| The harness (the outer while-loop) | `Runner.converse` -- the ReAct loop, with `max_tool_iterations`, per-call metrics, and the one-tool-message-per-id invariant |

So the ReAct loop is not the thing to build. It exists, it has been debugged against a real
endpoint, and `RunnerToolLoopTest` pins its invariants.
counterpart, `RunnerToolLoopTest`, has not been ported yet -- `Runner.kt` itself is fully ported,
only the test is missing (see [testing.md](testing.md)). What is missing from the product is one
level up:

- **no way to write a multi-step workflow down** -- today a turn is one prompt from the user;
- **no way for template code to call the model** -- `<% %>` can read files and shell out, but it
  cannot `ask`;
- **no state that outlives a turn** except the conversation itself;
- **no budget or stopping rule above the turn** (`max_tool_iterations` counts round trips inside
  one turn, which CLAUDE.md already calls out as a poor proxy).

Four gaps. That is the whole proposal.

### The distinction that matters here

The industry pitch is "autonomous agents"; the useful version is a **two-tier control flow**:

```
  deterministic outer flow      written by you, in Scala, in the file
        |
        +-- step 1 --> llm(...)              ---> autonomous inner loop (model picks tools)
        +-- step 2 --> llm(..., fresh = true) ---> the same, on a scratch conversation
        +-- step 3 --> plain Scala, no model at all
```

Pinning the *plan* in code and letting the model do the *local* reasoning is what makes an agent
reliable enough to run unattended. Fully autonomous is a demo; fully scripted is a shell script
with extra steps. The file format has to make the mix natural, and `<% %>` already is that.

---

## 2. The file

One file, `~/.config/kotllm/agents/<name>.md`. The filename is the agent name -- no registry, no
manifest, no metadata.

```markdown
<% agent(null, "exec, groovy_eval, fetch", 30) %>

<% system { %>
You audit repositories for release readiness. You are terse and you cite files.
Finished when every changed file has a verdict of ok, risky or blocked.
<% } %>

# Release review

<% def base     = args().get("base") ?: "origin/main"
   def changed  = "git diff --name-only ${base}".execute().text.readLines()
   def verdicts = []                            // ordinary variables are the state
   def blocked  = []

   if (!changed) finish("nothing changed against " + base) %>

Reviewing <%= changed.size() %> file(s) changed against <%= base %>.

<% for (f in changed) { %>
## <%= f %>

<%  // fresh: a scratch conversation, so the file's contents never enter
    // the run's own. Only these three lines come back.
    def verdict = llm("""Review ${f} for release risk. Answer in three lines:
                          verdict: ok|risky|blocked
                          why: one sentence
                          risk: one sentence or 'none'""", true)
    verdicts << "${f} -> ${verdict.readLines().head()}"
    if (verdict.contains("blocked")) blocked << f %>
<%= verdict %>

<% } %>

## Verdict

<%= llm("""Here are the verdicts:
            ${verdicts.join("\n")}
            Blocked: ${blocked ? blocked.join(", ") : "none"}
            Name the single highest risk and what to do about it.""") %>

<% finish("${changed.size()} reviewed, ${blocked.size()} blocked") %>
```

Note what the harness has to parse: **nothing**. `# Release review` and `## <%= f %>` are prose
that lands in the report; they mean nothing to kotllm. `var verdicts` is the state; `for` is the
control flow; `fresh = true` is what stops twenty files becoming twenty files' worth of
context -- roughly 61k input tokens across the loop instead of 630k, since a shared conversation
re-sends every file it has already seen.

### Typed replies, and why there is no retry

`llm` returns a `String` and always will -- most answers are prose. `llmAs[T]` is for the other
case, where the file is going to *branch* on the answer:

```
<% case class Risk(verdict: String, why: String, risk: String)
   implicit val rw: upickle.default.ReadWriter[Risk] = upickle.default.macroRW

   val r = llmAs[Risk](s"Review this diff. verdict must be one of ok, risky, blocked\n\n$diff")
   if (r.verdict == "blocked") blocked :+= f %>
```

- **The instruction is written from the type.** Reflection gives the field names *and* their JSON
  types, so the model is told `Use exactly these keys: verdict (string), count (number)` and the
  prompt cannot drift from the class. `$outer` is filtered -- a case class declared inside an
  agent file is an inner class of the generated program, so reflection reports the enclosing
  reference as a field, and telling a model to emit a key called `$outer` would be an
  entertaining bug.
- **A `String` field enforces shape, never vocabulary -- so use an enum.** A live run of the
  example agent came back with `verdict: "CAUTION"` and `"low"` where `ok|risky|blocked` was
  meant: perfectly parseable, entirely useless. `enumOf` closes that, from one declaration:

  ```
  <% sealed trait Verdict
     case object Ok extends Verdict
     case object Risky extends Verdict
     case object Blocked extends Verdict
     implicit val rw: upickle.default.ReadWriter[Verdict] = enumOf[Verdict](Ok, Risky, Blocked)

     case class Risk(verdict: Verdict, why: String)
     implicit val riskRW: upickle.default.ReadWriter[Risk] = upickle.default.macroRW %>
  ```

  The instruction then reads `verdict (one of: ok, risky, blocked)`, a reply outside the set
  **fails to parse**, and the repair prompt carries the list -- so the second attempt has strictly
  more information than the first. The file compares with `r.verdict == Blocked`, which the
  compiler checks, rather than against a string nobody spell-checks. Re-run live after this
  landed: every verdict came back inside the vocabulary.

  Three things this rests on, all verified in the interpreter before being designed on:
  a locally declared sealed trait can have a `bimap` `ReadWriter`; `macroRW` on the case class
  picks that implicit up; and **a field whose type is a sealed trait reports that trait as its
  runtime class**, which is what lets a registry keyed by type find the values at all.
  `scala.Enumeration` is the form that cannot work here -- every `Value` erases to
  `Enumeration#Value`, so two enumerations in one file would collide. They still serialise; they
  just cannot be named in the prompt.
- **Case and whitespace are forgiven on the way in.** Rejecting `" OK "` would spend a repair on
  punctuation.
- **JSON is found through a fence or through prose.** Models fence JSON when told not to, and a
  parser refusing a good object over three backticks would make the feature feel broken. So: strip
  a fence, else take the first balanced `{...}` or `[...]` ignoring braces inside strings, else try
  the whole reply.
- **A parse failure is *repaired*, not retried.** This is the distinction that matters: at
  temperature 0 an identical request is deterministic, so a bare retry pays twice for the same
  answer -- and would trip the no-progress rule this harness already enforces. The repair sends a
  **different** prompt (`That could not be parsed as JSON: <error>. Reply with only the JSON
  value.`) on the **same** conversation, so the model can see its own bad reply. Default one
  repair; `repairs = 0` for none.
- **Giving up throws rather than ending the run.** `AgentParseFailure` is catchable, so a loop
  over forty files can lose one and keep thirty-nine. Uncaught, it ends the run with the message.
- **A dry run fabricates a value of the right shape**, not a marker -- and for an enum field a
  *real* value from the set, since its own reader would reject a placeholder:
  so the rendered workflow reads `**<verdict>** -- <why>` and the happy path is visible for zero
  tokens. Returning the stub string instead made every typed item render as "could not be read",
  which is the one path a dry run should never show.

The other half of the LangChain comparison -- `.with_retry()` / `.with_fallbacks()` per step --
is deliberately **not** built. A retry that changes nothing about the input is not a strategy, and
the three cases where a repeat is genuinely justified are all "something else changed": a
transport 5xx or 429 (already handled by `client/Retry`), a reply truncated at the token cap
(needs a bigger budget, not a repeat), and this model's documented degenerate case where the whole
budget goes to reasoning and the text comes back empty -- reasoning length varies run to run, so
there a repeat can legitimately succeed, and a file can simply call `llm` again.

### Everything structural is a call

| Call | What it does |
| --- | --- |
| `agent(model, tools, maxCalls, maxTokens, maxSeconds, quiet, approval)` | Configuration. Every argument optional with a default, so `agent()` on its own is legal and means "an agent with defaults"; pass `null` to skip one. |
| `system { ... }` | The prose inside the block becomes the system prompt instead of output. |
| `llm(prompt)` | One turn on the run's conversation. Returns the reply. |
| `llm(prompt, true)` | One turn on a scratch conversation, discarded afterwards. Same call, one flag. |
| `finish(verdict)` | Terminal state: stop now, record the verdict, exit 0. |
| `fail(reason)` | Terminal state: stop now, exit non-zero. |

Five calls. Configuration as arguments rather than YAML is not just fewer parsers -- it is
**typed, completable, and computable**: `agent(null, null, changed.size() > 40 ? 80 : 30)` is a
thing you can write, and no frontmatter format can express.

`fresh` is a flag rather than a second function on purpose. `sub()` was the first name -- short for
"subagent", which is framework jargon -- and it needed a paragraph of explanation every time it
appeared, which is the definition of a bad name. One verb for "call the model" and one flag naming
the only thing that differs leaves nothing to memorise.

Two names that were nearly one: what the *model* should treat as finished is prose inside
`system { }` -- it was never more than prompt text, and inventing a `# Done when` directive for a
paragraph was structure for its own sake. What the *harness* does is `finish`/`fail`. Different
mechanisms, different words.

### The mode switch is what the file *did*, not what it looks like

- A file that **calls none of them** emits text, and that text is the prompt, sent as an
  ordinary turn. **That is a role**, with no rule to learn and no code to support it.
- A file that **calls any of them** is an agent: its emitted output is the artifact and nothing
  further is sent.

Decided at runtime, after expansion. Templates are already expanded for every prompt kotllm
sends, so this is a check on the way out rather than new machinery. Adding a `<% %>` tag to a
prompt file therefore cannot silently change what the file means -- only calling an agent
primitive can.

### How `system { }` captures prose

By **append-then-cut**, which is why it needs no new syntax and no dynamic sink stack: the prose
inside the block appends to the output sink exactly as any other literal would, and `system` then
removes that span and keeps it as the prompt. Two additions to `template/TemplateEngine.Sink`
(`length`, `cut(from)`), nothing touched in `Template.parse` or `Template.emit`, and the line map
stays intact -- which matters, because "a compile failure is reported against the template line"
is a property worth keeping.

### What is lost, and why it is acceptable

**Nothing about an agent is knowable without running it.** There is no frontmatter to read, so
`--list-agents` can only list filenames and `/agent` cannot show a description. The alternative --
one reserved convention for a description line -- buys a line of help text at the cost of
reintroducing the exact thing this design removed. The filename is the name.

## 3. The bindings

Bound into the interpreter session before the file is rendered, so they cost nothing at startup
and nothing in the generated program's line numbering (see §7).

| Binding | Type | What it does |
| --- | --- | --- |
| `llm(prompt)` | `String` | One turn on the run's conversation. Tools, approval, retry, rate limiting, compaction, metrics -- all the existing `Runner.converse`. Returns the visible reply. |
| `llm(prompt, true)` | `String` | A forked session with its own context, discarded on return. `Runner.forkBackground` is already exactly this machinery, run synchronously. |
| `llm(prompt, fresh, alias, tools)` | `String` | Model and tool set overridden for this call. |
| `agent(...)` | | Configuration; see §2. Must precede the first `llm`, and says so plainly if it does not. |
| `system { ... }` | | Captures its block as the system prompt. |
| `finish(v)` / `fail(r)` | | Terminal state. |
| `last()` | `String` | The previous reply. Convention, so the common case needs no variable. |
| `args()` | `Map<String, String>` | Invocation arguments: `--agent-var name=value`. |
| `runDir()` | `Path` | This run's directory. |

| `runDir()` | `Path` | This run's directory. |
| `log(msg)` | | One line into the run log and onto the progress line. |
| everything `<% %>` already has | | `groovy.json`, `java.nio.file`, `java.time`, the JDK, `{{ }}` variables, and anything `groovy_eval` bound earlier in the session. |

Deliberately **not** provided: a DAG type, a planner, a retry decorator, an eval harness. Those
are `for`, `if`, `try` and a `while` loop in a language that is already there.

Also not provided, after review:

- **No `state` store.** Its only real job was the replaced-scratchpad trick below, and with no
  resume there is nothing to persist. Ordinary Scala variables carry data between steps, and
  `fresh = true` handles the context growth. Deferred until a real agent demonstrates the need -- and
  note that making "your own variables" visible to a separately-rendered `state { }` block needs
  either a forward-reference trick in the emitted program or a scoping rule the user has to learn,
  which is a second reason to wait for evidence.
- **No concurrent fan-out.** Fresh calls are independent, so `task/Spawner` would make a parallel
  map easy; easy is not the same as warranted, and a sequential loop is one line and always
  debuggable.
- **No `promptUser()`.** An agent that stops to ask a human cannot run unattended, and the
  approval prompt is already the one human gate that exists.

---

## 4. Context control -- the answer to your second question

Your instinct (write the iteration out, clear context, read it back) is right in its diagnosis and
too blunt in its cure. Clearing everything loses the goal, the negative constraints and the reason
the last step happened -- so the model re-derives them, badly, and the loop goes in circles. The
tiered version your reading lands on is what I would build, and every tier maps onto machinery
kotllm already has:

```
[ system { } ]                      pinned, byte-stable, never rewritten
[ conversation so far ]             compacted by Compactor when it grows (exists)
[ the current step's prompt ]       what llm() was called with
[ state { }, re-rendered ]          DEFERRED: replaced each request, never appended
```

- **`fresh = true` is the lever that ships.** Everything noisy -- grepping a tree, reading a
  12,000-line test log, scanning a codebase -- runs in a scratch conversation whose context dies
  with it and returns three lines. Nothing else in this list has anything like the same effect.
  Twenty 3,000-token files reviewed in a shared conversation cost roughly 630k input tokens,
  because call *n* re-sends files 1..*n*; the same loop with `fresh = true` costs roughly 61k and
  leaves the run's own conversation holding nothing but what the program chose to keep.
- **A `state { }` block is the deferred half, and the cheap one when it comes.** Memories and retrieved
  passages are *already* rebuilt per request and never written into history
  (`Runner.systemPrompt`), so a replaced scratchpad rides an existing path: a 200-line summary
  that changes every step would cost 200 lines once rather than 200 lines times twenty turns.
  Held back because a first cut should not carry a feature justified by a hypothesis.
- **`fresh = true` for anything noisy.** Grep a tree, read a 12,000-line test log, scan a
  codebase: do it in a scratch conversation whose context dies with it, and return three lines.
  This is the single biggest lever, and `forkBackground` already implements it.
- **Tools already return pointers, not payloads.** `exec` truncates in the middle and says how much
  it elided; `search` drops whole passages and says how many; `fetch` truncates and says so. So
  "wrote 12,000 lines to run.log, exit 1, stderr tail: AssertionError line 42" is the *existing*
  behaviour, not something to add.
- **Compaction is already automatic** and already refuses to commit a rewrite that is not smaller.

On prompt caching, one honest caveat: the 80-90% cached-input discount is an Anthropic/OpenAI
property. **Nothing in this repo has verified that the Databricks gateway does prefix caching at
all**, and the wire-format findings in CLAUDE.md do not mention it. So I would build the stable
prefix because it is good hygiene and because it is what the Azure path may reward -- but I would
not claim a saving we have not measured, and `/status` should be the thing that tells us.

---

## 5. Budgets and stopping

CLAUDE.md already lists "a real agentic stopping rule" as wanted. An agent run is where it
belongs, because a turn is the wrong scope:

| Budget | Default | Enforced where |
| --- | --- | --- |
| `maxCalls` | 50 | In `llm` -- the calls that cost money and time |
| `max_tokens` | none | From `session.ledger`, before each `llm` |
| `max_seconds` | none | Wall clock, before each `llm` |
| no-progress | 3 identical `llm` prompts in a row | In `llm` |

`max_steps` is deliberately absent: there are no declared steps to count, and counting markdown
headings would have been a budget spent on formatting.

Exceeding one **ends the run with a verdict**, it does not throw: the run directory keeps the log
and whatever the template emitted, and the report says which budget stopped it and at which step.
A doom-loop should cost you a budget, not a mystery.

A run is **all or nothing**. No checkpoints, no `--agent-resume`, no partial-state reload -- these
are minutes-long agents, not Claude-Code-style sessions, and resume was the most complexity for
the least value in the original sketch. Same call as the one already taken for RAG indexing.

---

## 6. Running one

```sh
kotllm --agent review                          # convention: agents/review.md
kotllm --agent review --agent-var base=main    # args("base")
kotllm --agent review --dry-run                # render the file, run no model calls at all
```

```
/agent review           in the REPL, streaming progress
/agent                  list agents (and --list-agents, already reserved)
```

`--dry-run` on an agent is worth calling out: it renders the template with `llm` stubbed
to return a marker, so you can see the whole shape of the workflow, and what it would send, for
zero tokens. That is the cheapest possible way to debug an agent and it costs one flag.

### The run directory

```
<config dir>/runs/review-2026-09-17T02-14-08/
  agent.md          the file as it was, so a run is reproducible
  log.md            progress, timings, tokens, and every tool call that was auto-approved
  report.md         what the template emitted -- the artifact
  test.log          whatever the agent itself wrote
```

No `state.json`, no `steps/` -- those existed to support resume, which is not being built.

Convention again: no configuration, and "write the iteration out to a temp file" is just
`os.write(run.dir / "...", ...)`.

---

## 7. What was built, and the traps

New code, in rough order:

1. ~~`agent/Agent.kt` -- parse frontmatter and headings.~~ **Deleted from the plan.** There is
   nothing to parse: the file is a template, and `agent()`/`system { }` are calls. A markdown
   section parser, a YAML frontmatter path, heading-name matching and mode detection all
   disappeared with one decision.
2. **`agent/AgentRun.kt`** -- run directory, budgets, verdict, log.
3. **`agent/AgentApi.kt`** -- the six calls, plus `last`, `args`, `log`, `run.dir`. **Must be a
   named public class**: `bindValue`
   binds by runtime type and the generated code has to typecheck against it, which is exactly why
   `template/TemplateEngine.Sink` is a named public class and not an anonymous structural type.
4. **`Runner`**: a value-returning quiet turn (`converse` already returns the response), plus a
   `stateSection: () -> String?` hook alongside the memory and RAG sections.
5. **`template/TemplateEngine.Sink`**: `length` and `cut(from)`, for `system { }`.
6. **`Cli` / `Commands`**: `--agent`, `--agent-var`, `/agent`. `--list-agents` already exists as a
   rejected-with-a-reason flag, so it becomes real -- listing filenames, since there is no
   metadata to read without running the file.

Traps, the first two found the hard way while building it:

- **A lazy val whose initialiser calls a method that reads the same lazy val recurses until the
  stack dies.** `AgentRun.dir` created the directory and then called `write("agent.md", ...)`,
  and `write` resolves against `dir` -- so the initialiser re-entered itself, because the
  bitmap is not set until it returns. What surfaced was not a StackOverflowError: whatever class
  happened to be initialising when the stack ran out stays permanently broken, so the symptom was
  `NoClassDefFoundError: Could not initialize class scala.util.control.NonFatal$` reported from an
  unrelated line, which the stale-jar handler then misdiagnosed. `KOTLLM_DEBUG=1` now prints the
  trace behind that handler for exactly this reason.
- **IMain reports a type error as `On line 2:`, not `:2:`.** `TemplateEngine` translated only the
  second spelling, so the single most common diagnostic -- a type mismatch -- still pointed into
  the generated program the user never wrote. Both forms are translated now.
- **An agent's replies go to stderr, its artifact to stdout.** Watching a run and piping its
  report are both normal, and with replies on stdout the pipe received the transcript twice.
  `Runner.renderTo` exists for this.
- **`llm` returns trimmed text.** A reply is nearly always interpolated into a sentence, and
  `2 doubled is <%= llm(...) %>` should not acquire the blank lines a model wraps answers in.

And the ones anticipated, all grounded in something this repo had already been bitten by:

- **Shorthands go into the session, not the emitted program.** They must be evaluated into the
  interpreter session *before* rendering. Prepending lines to the generated
  program would desync the line map, and "a compile failure is reported against the template line"
  is a property worth keeping.
- **Re-entrancy.** Template expansion happens at the input boundary; `llm()` inside a template
  calls the model, whose turn could itself expand a template. Needs a depth guard (1 by default)
  or an agent can recurse into itself through its own output.
- **The interpreter is shared with `groovy_eval`.** An agent's bindings are visible to the model's
  own code and vice versa. Powerful and occasionally surprising; the background-turn precedent
  says isolate on fork (`sub` gets its own engine) and share otherwise.
- **Approval is `auto` inside a run** (settled with the user). An agent is unattended by nature,
  so under `confirm` its first `exec` would refuse and the run would be pointless. The exception
  is scoped to the run, **announced loudly when it starts**, and every auto-approved call is
  written to `log.md` with its arguments -- so the record exists even though the prompt does not.
  `agent(approval = "confirm")` opts back in for an agent you do not trust yet.
- **`Session` per run, not per ask.** Otherwise the pinned prefix is not stable and every step
  re-explains the goal.

---

## 8. Decisions taken

| Question | Decision |
| --- | --- |
| A markdown DSL -- headings, frontmatter? | **No, none.** `<% %>` is the only interpreted thing; everything structural is a typed call. |
| Roles as a separate feature? | **No.** A file that calls no agent primitive is a role; `-r/--role` and `roles/` are never built. |
| Macros? | **No, permanently.** An agent file is a macro with a real language. |
| Resume? | **No.** All or nothing. No checkpoints, no `state.json`, no `--agent-resume`. |
| Approval inside a run? | **`auto`**, announced at the start, every call logged; `approval: confirm` opts back in. |
| Concurrent fan-out? | **Not now.** A sequential loop is one line and always debuggable. |
| `state` store? | **Not now.** Ordinary variables carry data; `fresh` bounds the context. |

### The first cut

```
One file:        agents/<name>.md, an ordinary template
Five calls:      agent()  system { }  llm()  finish()  fail()
Zero syntax:     no frontmatter, no directive headings, nothing below <% %>
Budgets:         maxCalls (50), optional maxTokens / maxSeconds, no-progress detector
Watched by default, quiet by choice.  Auto-approval, announced and logged.
No resume.  No fan-out.  No state store.  No macros.  No roles.  No steps.
```

Plus: `--agent <name>`, `--agent-var k=v`, `/agent`, the run directory, and `--dry-run` rendering
the workflow with `llm` stubbed for zero tokens.

### Also settled

| Question | Decision |
| --- | --- |
| `ask` or `llm`? | **`llm`, and only `llm`.** One verb for calling the model, no alias to learn or explain. |
| Watched or quiet by default? | **Watched.** Replies stream as they arrive; `agent(quiet = true)` or `--quiet` turns it into one progress line per call. |
| Are headings directives? | **No.** Nothing in the markdown is read by kotllm; `system { }` carries the prompt. |
| `max_steps`? | **Gone.** `maxCalls` counts the `llm` calls, which are what cost something. |

Watching is the default because the first few runs of an agent are always debugging: you want to
see the model reach the wrong conclusion at the moment it happens, not read it afterwards in
`report.md`. Once an agent is trusted, `agent(quiet = true)` in the file itself makes it produce an
artifact silently -- which is the state a scheduled or piped run wants.
