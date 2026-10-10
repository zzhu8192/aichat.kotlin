# Tools

The tool harness, approval policy, the built-in tools, file tools, the visible
task checklist, and user-written Groovy tools evaluated from `config/tools/`.
Interpreter tools are in [engines.md](engines.md).

## Tool harness

`groovy_eval` drives `groovy.lang.GroovyShell` in-process -- the same engine behind the stock
`groovysh` -- so the language is real rather than approximated and everything in the fat
jar is importable with no build step. Session state persists across calls, which is what lets
the model explore in steps.

Things that are load-bearing and easy to break:

- **Compiler diagnostics go back verbatim.** A model corrects its own code from a real "No such
  property" or "unexpected token" and cannot from "compilation failed". `EvalResult.toToolOutput`
  returns the transcript, including the value the last expression produced.
- **Capturing printed output swaps the process-wide streams.** `System.setOut` and `setErr` are
  swapped for the duration and restored in a `finally`, so `evaluate` is synchronized: the swap
  is global, not per-session. A timed-out worker is given a grace period to unwind *before* the
  streams go back, or it could write to the real console after the restore.
- **`System.exit` cannot be trapped on Java 17.** `SecurityManager` is deprecated and
  `System.setSecurityManager` throws without `-Djava.security.manager=allow`, so exit calls are
  *refused* by a static check on the submitted snippet instead. The pattern is
  negative-lookbehind guarded so an identifier like `exitCode`, or `.exit` on a case class, is
  not caught.
- **The eval timeout is cooperative.** The worker is interrupted, which a tight non-blocking
  loop ignores. It is a daemon so it cannot keep the JVM alive, but it can keep burning a core;
  that is reported rather than hidden.
- **`exec` must drain output on a separate thread.** Reading the process output to completion
  before `waitFor` blocks until the command exits, which silently defeats the timeout entirely
  -- `waitFor` then always finds it already finished. There is a regression test asserting the
  call returns early.

### Tool call metrics

Each executed call records a `ToolCallMetrics`: start, end, duration, argument and result
character counts, estimated tokens for each, approval wait, and whether it was approved and
whether it failed. `/status` rolls these up per tool (count, total, average, slowest) and
prints the elapsed time inline after each call as it happens.

Be precise about what is measured and what is estimated, because the distinction is the whole
reason the report is trustworthy:

- **Durations and character counts are exact.**
- **Per-call token figures are estimates.** The endpoint reports no per-call token counts. They
  come from the calibrated `TokenEstimator` and are labelled `~tok` / "estimated".
- **`toolFeedbackTokens` is exact.** Every request re-sends the conversation, so the growth in
  `prompt_tokens` from one request to the next is the true cost of what was appended in
  between -- the assistant tool-call message plus the results. Summed only across requests that
  followed a tool call, and floored at zero so a compacted history cannot make it negative.
  Expect it to exceed the per-call result estimate, because it also covers the arguments the
  model generated.
- **Approval wait is tracked separately from execution time.** A slow human is not a slow tool;
  conflating them would hide a genuinely slow command.

### Two config keys are required, and their absence used to be silent

`function_calling: true` **and** `use_tools: all` are both needed. `supports_function_calling`
on a model entry declares only what the *model* is capable of and is not a switch -- an easy
misreading, and exactly the one that bit us: a config carrying `supports_function_calling: true`
but neither of the two keys sent no tools at all, so the model truthfully reported it could not
run anything and nothing in the output explained why.

Never let that be silent again. The REPL banner always states tool status, and
`Runner.toolConfigHint` fires whenever the selected model declares tool support while no tools
are enabled, naming the missing key and the config file *actually loaded* (which is why `Config`
carries `sourcePath` -- pointing at the default path while `--config` was in use is worse than
saying nothing). `RunnerToolsTest` covers every combination.

### `tools_exclude`: a subtraction, not a second whitelist

`use_tools` already *is* a whitelist -- `use_tools: read_to_context, grep, glob` has always
worked. What it cannot express is "everything except these", and with 27 registered tools that
is the form the question usually takes: every new tool is opted in by an existing `all`, and a
whitelist written to withhold three of them has to be edited every time the set grows.

So there is exactly one new key and it subtracts:

```yaml
use_tools: all
tools_exclude: "*_eval, exec"
```

Decisions worth not relitigating:

- **No `tools_include`.** Two keys meaning one thing is how a config key ends up parsed and
  ignored, which has happened here before. `use_tools` keeps the aichat-compatible spelling.
- **Applied after `use_tools`, and to everything it selected**, including an explicit list.
  Subtraction is well defined either way, and rejecting the combination would force two edits
  to express one intent.
- **`*` patterns, anchored, nothing else.** Tool names come in families a whitelist cannot
  summarise (`*_eval` is the three interpreters). Everything between the stars is
  `Pattern.quote`d, so a name containing a regex metacharacter cannot widen the match.
- **An entry matching nothing is a warning, not an error** -- unlike an unknown name in
  `use_tools`, which is fatal. The asymmetry is not an oversight: which tools are *registered*
  depends on the rest of the config (no `jdk.jshell`, no `java_eval`; no index, no `search`),
  so `tools_exclude: python_eval` on a machine without Python must not take down the whole
  tool set. But a typo here is the dangerous direction -- you believe `exec` is withheld and it
  is not -- so `ToolRegistry.unmatchedExclusions` names it in `Runner.startupHints` and again
  in `/tools`.
- **Not settable with `/set`**, for the same reason `use_tools` is not: the tool list in a
  live conversation is already fixed by the schemas the model has seen.
- **Nothing cascades.** `*_eval` withholds `write_from_eval` by spelling, but leaves
  `read_to_var`, `session_vars` and `session_transfer` -- tools whose whole purpose is moving
  values into an interpreter session. Their *descriptions* still name `groovy_eval`, so a
  no-code-execution config should name them too. Deriving the closure automatically was
  rejected: an exclusion that silently removes tools the user did not list is the same class
  of surprise the key exists to avoid.

When an exclusion empties the list, `toolStatusSummary` and `toolConfigHint` say so and name
`tools_exclude`. They used to report `none selected (use_tools: all)`, which points at the one
setting that is not the problem.

### Stopping the loop must leave a valid conversation

The endpoint requires **exactly one tool message per `tool_call_id`**. Any path that abandons a
round trip therefore has to answer the outstanding calls, or the *next* request is malformed and
rejected. That bug shipped: hitting `max_tool_iterations` appended the assistant message with
its `tool_calls` and never the results, so the following user message failed. Ctrl-C between
iterations had the same fault, and a response carrying parallel calls left several unanswered.

`Runner.declineToolCalls` now answers each abandoned call with a short explanation of why it
was not run -- better than a blank result, because the model reads the reason and can resume
coherently when told to continue. `RunnerToolLoopTest` asserts the invariant directly:
`requested tool_call_ids -- answered ids` must be empty however the loop ended.

`max_tool_iterations` is settable at runtime (`/set max_tool_iterations 40`), because you
discover the cap is too low precisely by hitting it and editing config.yaml to continue is
friction. The cap is a crude stopping rule -- it counts round trips rather than measuring
progress or budget -- and is deliberately provisional; see the proposals below.

Anything exposed through `/set` must be read live rather than captured at construction.
`Approval` takes `() => ApprovalMode` for exactly this reason: a snapshotted mode would have
made `/set tool_approval auto` silently do nothing.

Approval defaults to `confirm`, with session-scoped yes-to-all and an anchored-regex allowlist.
Under `confirm` with no terminal to ask, calls are **refused**: auto-approving arbitrary code
because nobody was attached would be the worst possible failure mode.

The tool loop lives in `Runner.converse`. Each round trip is a separate API request, so it goes
through the rate-limit gate and gets its own ledger entry -- which is why `/status` and the
footer now say "requests" rather than "turns". Every tool call gets a result message even when
refused or failed, because the endpoint requires one per `tool_call_id` and omitting any makes
the next request malformed.

### `view_chat` can return the system prompt a past session ran under

`prompt: true`, off by default.
The prompt is several KB and the usual reason to open a stored conversation is what was said in
it, so returning it unasked would crowd the messages out of the tool's output budget.
It is there at all because the row it reads would otherwise be written and never read -- the
failure this codebase has shipped three times.
Only sessions held with `/prompt full` on have one; the rest are told so explicitly, because
"not recorded" and "the prompt was empty" are different facts and only the first names the mode
that would have captured it.

## The read-only approval exemption

`search`, `web_search`, `fetch`, `skill`, `read_to_var`, `session_vars`, `ask_user`,
`search_chats`, `view_chat`, `get_sys_dt`, `create_tasks`, `update_task` and the four
non-mutating file tools (`read_to_context`, `glob`, `grep`, `ls`) carry `Tool.readOnly = true`, and the `confirm`
policy skips them. `write`, `edit` and `write_from_eval` do **not** -- they change the disk, so
they prompt exactly like `exec`, and that split is what lets a scripted run do
useful read-only work with `confirm` still set. This is a deliberate exception to a **locked
decision** ("confirm by default"), taken because the alternative was worse than
the risk: under `confirm` there is nobody to ask in a background turn or a
script, so retrieval and fetching were refused outright and
`--rag-index . "question"` could not work at all -- which the notes had already
flagged as the thing to fix.

What is given up is a prompt before an outbound read. What is kept: `exec` and
`groovy_eval` still ask every time, `deny` still refuses everything (somebody who
set `deny` asked for no tool calls, and carving an exception out of that would
invert the setting), and every call is still printed with its query or URL, so
an unprompted read is never invisible.

## File tools

`read_to_context`, `write`, `edit`, `glob`, `grep`, `ls` -- six first-order tools for things
the model would otherwise write code for. `exec` and `groovy_eval` can do all of it badly: quoting rules
bite, output arrives through the interpreter transcript and its cap, and a three-line edit
becomes a program. They are also what makes a skill written for another harness executable at
all, which is why they landed first. See [skills-design.md](skills-design.md) for the full write-up.

Six schemas rather than one tool with a `mode` argument, because a tool's description is the
only documentation the model gets and one description cannot cover six jobs. The cost is real
and worth stating: the file family adds **~1700 tokens of schema to every request**, taking the
declared tools from ~1160 to ~3080 tokens. On a 261k window that is noise; against a small one
it is most of the budget, and `file_tools: false` is the lever.

Things that are load-bearing:

- **`read_to_context` numbers its lines, and `edit` forgives the numbers.** Numbering is what makes a
  windowed read meaningful, lines up with `grep`, and lets the model cite `path:line`. It is
  also a trap: having just read `    42\tval x = 1`, a model pastes the prefix into `edit`. So
  `edit` retries once with `^\s*\d+\t` stripped from each line. Three lines of code against a
  failure that otherwise costs a round trip and reads as a broken tool.
- **`edit` refuses an ambiguous match, and that is the whole safety property.** String
  replacement is trustworthy only if a search string that appears twice is an error rather than
  a coin flip. Zero matches reports the first line it looked for and blames whitespace, which is
  nearly always right; more than one reports the count and names `replace_all`.
- **`write` will not clobber a file nobody looked at.** Not security -- the user deferred
  that -- **data loss**: the worst thing here is replacing eight hundred lines it never read with
  the twenty it had in mind. Overwriting an existing file requires that this process already
  `read_to_context` or `write`ten it; creating a new one needs nothing, and the refusal
  names both readers.
- **`grep` is implemented here rather than shelling out to `rg`.** Not installed everywhere, and
  its absence surfaces as a confusing shell error rather than a missing tool; `rg`, `grep` and
  `findstr` disagree on flags. A walk plus `java.util.regex` is eighty lines, behaves identically
  on every platform, and can say *why* a file was skipped.
- **Read-only where it is true.** `read_to_context`, `glob`, `grep`, `ls` skip the confirm prompt like
  `search` and `fetch`; `write` and `edit` prompt like `exec`. That split is what lets a scripted
  run do useful read-only work with `tool_approval: confirm` still set.
- **Everything is capped and says what it withheld.** A cap that silently drops results is worse
  than a small one that admits it, because the model draws conclusions from an empty tail.
- **`glob` matches by name when the pattern has no `/`.** `*.kt` is what a model writes when
  it means every Kotlin file, and `**/*.kt` is what the format requires. Both work, and the
  description says so rather than leaving it to surprise.

Verified live: "find every Kotlin file under util/ that mentions Skips, then tell me how many
lines it has" produced a `grep` with a glob filter, then a `read_to_context`, then correct
line citations,
with nothing in the prompt about how to use them.

## Tasks

`create_tasks` and `update_task`: a visible checklist for multi-step work, in the transcript, in
the footer, and in the system prompt.

**kotllm assigns the ids and both tools echo the whole list back.** That is the decision the
rest follows from. A model asked to invent its own keys will eventually address one that does
not exist, and an update matching nothing **fails silently** -- which is the worst possible
shape for a progress tracker, because the list then reads as finished while the work is not.
Echoing costs a handful of tokens and removes the failure class outright. Small integers also
cost one token to emit, which matters for something updated several times a turn.

```
create_tasks{tasks: ["read the spec", "write the parser", "add tests"]}
  -> 1. [ ] read the spec
     2. [ ] write the parser
     3. [ ] add tests

update_task{updates: [{id: 1, status: "done"}, {id: 2, status: "in_progress"}]}
  -> 1. [x] read the spec
     2. [>] write the parser
     3. [ ] add tests
```

**`update_task` takes a batch**, because the most common move by far is exactly two changes --
finish this, start the next -- and one call per change would double the round trips on the thing
this does most. The batch is **all or nothing**: one bad id or unknown status refuses the whole
thing, since a half-applied batch leaves the model unsure which half landed and guessing is what
it does next.

Things that are load-bearing:

- **It is injected into the system prompt, rebuilt per request**, for exactly the reason
  `session_vars` exists: **compaction trims old tool results**, so the message carrying the list
  can be summarised away while the work is still in flight. A model that no longer knows a
  checklist exists simply stops updating it, and the feature decays into a list that was accurate
  once. The block also says *not* to restate the list in prose, since it is already on screen.
- **No new print site.** The rendered list rides back in the tool result, which `ToolRender`
  already prints in the gutter -- so it appears inline on every change for free, through
  `Chrome.whilePrinting` like all other tool output, with no risk of printing under the pinned
  region. `MaxResultLines` is 16, which covers any list worth following.
- **A second `create_tasks` replaces, and names the progress it discarded.** Replacement is right
  -- "create" is what gets called for a *new plan* -- but silently erasing a half-finished list is
  the one destructive thing these tools can do, so the result says `discarding 2 started or
  finished task(s)` and the model reads that it happened.
- **More than one `in_progress` is reported, never corrected.** Demoting whichever was already
  running would be a silent edit of the model's own plan, and two concurrent steps are
  legitimate. The discipline is worth a nudge in the result and not worth a lie in the data.
- **Both tools are read-only.** Nothing outside the process changes; prompting would fire several
  times a turn to confirm a bookkeeping entry.
- **`cancelled` exists** so a step that turned out to be unnecessary is visibly closed rather
  than left pending forever, and it counts as closed in the footer.
- **Bounded at 40 tasks and 200 chars each**, refused with the reason rather than truncated: a
  list that rides on every request has to be bounded, and a plan silently missing its last steps
  is a plan that quietly will not be finished.
- **`kotllm.task` is the checklist, and background turns moved out to get it.** The checklist
  landed in `kotllm.task` first, which already held `Task` (a background LLM turn), `TaskState`
  and `TaskRegistry`. The compiler caught `Task` vs `Task`. It could not catch the worse one:
  **`TaskState` and `TaskStatus` in one package, both sealed, both carrying a `Cancelled`** --
  one meaning a turn that was killed, the other a step that turned out unnecessary. That
  compiles perfectly and reads as one concept. So background turns are `kotllm.background` now,
  named `BackgroundTurn` / `TurnState` / `BackgroundRegistry` for what they are, which is also
  what this file always called them; `kotllm.task` holds what `/tasks`, `create_tasks` and
  `update_task` actually mean. `Repl`'s `tasks` val became `bgTurns` for the same reason -- it
  meant background turns in a file where `/tasks` means the checklist.
- **Markers are ASCII** (`[ ] [>] [x] [-]`). Not an exemption from the `util/Glyphs` rule but
  outside it: that rule exists so a box-drawing character cannot arrive as `?` on a cp437
  console, and `[x]` has nothing to fall back *from*.

**The footer element is `2/5 tasks`, kept longer than the session totals.** It carries
`DropTasks = 1`, below `DropRequests`, because it says what is happening *now* where a cumulative
token count says what already happened. Shown only once a list exists, so an ordinary question
never displays a meaningless `0/0`, and green when everything is closed. A test asserts it
survives a width that has already shed `reqs`.

**One per runner, not one per process.** A background turn and an agent get their own, on the
same reasoning that gives a forked turn its own interpreter: they run concurrently with the
foreground conversation, and a shared list would have two authors renumbering it under each
other.

**`/empty` clears the checklist, unlike the ledger.** Tokens spent stay spent, but a plan the
conversation no longer remembers would go on being injected into every request with nothing left
to explain it -- and the model would be asked to keep updating a list it never made. `/tasks`
shows it, `/tasks clear` empties it.

Nothing is persisted. A checklist describes what is being done *now*, and one restored from disk
into a conversation that no longer remembers it is worse than absent.

Verified live end to end: a three-part request about `Glyphs.kt` produced `create_tasks`, then
`in_progress`/`done` transitions interleaved with the actual `grep` and `groovy_eval` calls, and
finished 3/3 -- with nothing in the prompt about how to use either tool.

## Groovy tools from `config/tools/`

Drop a `.groovy` file in `<config dir>/tools/` and it is a tool on the next
start. No build step, no jar, no new dependency and no compiler wait: the file
is evaluated in the same session `groovy_eval` uses, so it gets the same
classpath LLM-authored code gets.

**The contract is one sentence: the file's last expression must be a
`kotllm.tools.Tool`.** `examples/tools/word_count.groovy` is the worked
example.

```groovy
// config/tools/word_count.groovy
def schema = Tool.schema('''
{ "type": "object",
  "properties": { "text": { "type": "string", "description": "The text to count words in." } },
  "required": ["text"] }
''')

// (name, description, parameters, preview, run, readOnly)
new Tool("word_count",
    "Counts the words in `text`. Pass unique=true to count distinct words.",
    schema,
    { args -> "count words" },     // preview, shown at the confirm prompt
    { args -> ... },
    true)                          // readOnly: skips the confirm prompt
```

**Two interop rules, and both of them shipped broken once.** A tool file is
Groovy calling into Kotlin, which is `CONVENTIONS.md` rule 46 territory: Scala
could not reach a Kotlin declaration by accident, and neither can Groovy.

- **`Tool.schema(text)`, not `Json.parseToJsonElement(text)`.** `Json` is a
  sealed class whose entry point is a named companion, and Groovy resolves
  `Json.Default` to the nested *class* rather than the static field holding the
  instance. Neither spelling works from a tool file.
- **`Tool.optStr(args, k)`, not `Tool.str(args, k).fold { } { }`.** `Outcome`'s
  `fold`, `map` and `flatMap` are Kotlin *extension* functions, so they compile
  to static methods Groovy cannot see as members.

Both fail at **load**, not at edit, and a tool that fails to load is simply
never offered -- which looks exactly like the model declining to use it.
`UserToolsTest` loads the shipped example files themselves and runs them, so a
broken example cannot ship again.

**You write the JSON Schema yourself.** The previous design derived it by
reflection from a Scala case class, which wrote the schema for you and then
could not describe a single argument: reflection cannot see per-argument prose,
so the model was told `text` is a string and nothing about what belongs in it.
Writing the schema is four more lines and means nothing can be derived
*wrongly* -- what the file says is exactly what the model is shown.

A tool is a function -- arguments in, `ToolResult` out, with `groovy.json`, the
JDK and the rest of the fat jar available. No config, no conversation, no
calling the model. An agent file is the thing for that, and keeping this side
pure is what makes a tool testable on its own.

### What evaluating rather than compiling removed

The Scala version compiled each file with `scala.tools.nsc.Global` into class
files, cached them under a fingerprint of the sources, and loaded them back
through a dedicated classloader. About four hundred lines, and nearly all of it
existed to avoid paying 4.4s of compiler startup twice:

| | startup |
| --- | --- |
| no `tools/` directory | 1.6s |
| first run: compiles and caches | 4.0s |
| every later run: cache hit | 1.7s |

Evaluating the file in the session that is already running deletes the cache,
the manifest, the classloader, the fingerprint and the staleness sweep
together, along with every decision that existed to keep them honest -- one
`Global` per file so a typo could not take out every tool, reading the output
directory between runs to attribute classes to files, caching only a clean
compile so an error could not go quiet.

What is left of that list is the part that was never about compilation:

- **Each file is evaluated separately**, so one that fails is reported and
  skipped rather than taking the others with it.
- **A broken file is reported at startup, never dropped.** It is the one
  failure the user cannot otherwise see: the model is simply never told the
  tool exists, which looks exactly like the model declining to use it.
- **`readOnly` is a constructor argument with no default.** kotllm cannot know
  what a user tool does, and the confirm-skipping exemption is for calls that
  provably change nothing.
- **A name clash loses to the built-in and is reported.** A map built by
  last-wins would have let a user tool silently replace one --
  `read_to_context` quietly becoming somebody's half-written version.
  `ToolRegistry` folds first-wins and exposes `shadowed`.
- **Loading happens on a daemon thread.** `startInBackground` keeps it off the
  critical path; `awaitReady` is for the few callers that need the answer.

The one decision that did not survive is **"a separate interpreter from
`groovy_eval`"**. It is now deliberately the same session, which is what makes
the whole mechanism small. The cost is real and is the reason it was avoided
before: the model can redefine a tool's helpers mid-session, and a tool file's
top-level names share a binding with whatever the model has bound. `def` inside
the tool file is local to it, which is the mitigation Groovy gives for free.

### The model did not know they were there

Asked to list what it could do, it enumerated the built-ins, grouped them
neatly, and left `word_count` out entirely -- then, challenged, found it in
the schema list and invented a group to file it under.

**A tool schema says a tool exists. It does not say where it came from, or
that it is worth preferring.** That is the third time this codebase has
learned it: `RagStore.systemPromptSection` exists because a schema did not
make an index discoverable, and the skills tier-1 block exists for the same
reason. So `prompt.userToolList` puts one line per tool under a
`# User-defined tools` heading in `chat.md`, saying the user wrote them.

Verified live afterwards: the same question now produces that section with
`word_count` in it.

