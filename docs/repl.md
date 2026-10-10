# The REPL

Chrome, the command surface, and Ctrl-C.
Two chrome bugs and their diagnoses are in [history.md](history.md).

## REPL chrome

**The two rules bracket the live input line and nothing else.** That is the invariant; everything
below exists to hold it.

```
> which model are you

* I'm a language model assistant that can run Groovy code and shell commands.

  What can I help you with today?

  * Processed for 13s · done 6:04 PM · ↓ 412 tokens

────────────────────────────────────────────────────
❯ your input
────────────────────────────────────────────────────
  kotllm 1.0.2-SNAPSHOT · qwen35-122b · 3 tools · 18.4k/261k ctx (7%) · ↓ 2.3k tokens · 3 reqs · /help
```

**Nothing is printed above the upper rule.** There is no startup banner in the framed layout: a
line above the box is exactly what the box is supposed to exclude, and the facts it carried --
which build, which model, whether there are tools -- are asked about at arbitrary moments later,
which a line that scrolls away cannot answer. They live in the footer now. Two exceptions, both
deliberate: the banner *is* printed when the layout is **not** framed, because then there is no
footer to carry it; and the tool misconfiguration hint is printed whenever it fires, because it
is a sentence of prose about a config file rather than a status field.

The upper rule is part of the multi-line prompt, so it redraws in place with the input. The
lower rule and footer are pinned by JLine's `Status`, which reserves the bottom rows; doing that
by hand breaks as soon as a line wraps or the window is resized. Context and rate percentages go
amber past two thirds and red past ninety.

**Tool state is pinned, not just announced** -- a count, amber `no tools` when there are none.
That is the same failure the two config keys caused: the model truthfully reports it cannot run
anything and nothing on screen explains why. The count rather than the names because the footer
is a line, not a report; `/info` lists them. There is no pinned *header* row, and there will not
be one: `Status` reserves rows at the *bottom* via `change_scroll_region`, so a fixed top row
would mean hand-managing a second region and re-deriving it on every resize and wrap.

**The footer degrades by dropping elements, never by being truncated.** It is now the only place
the version, the model and the tool state appear, and JLine truncates a status line at whatever
column the window ends -- which would cut the volatile numbers off the right-hand end, the ones
worth reading. So each element carries a drop order and `Prompt.fit` sheds the most expendable
until the line fits: the `/help` hint first, then the version (`/info` has it), then the session
totals. Model, tool state, context pressure, the rate window and failures are never dropped, and
when nothing droppable is left the line is allowed to overflow rather than shedding one of those.

**`KOTLLM_CHROME=plain` turns the whole framed layout off**, leaving the plain single-line
prompt. A terminal can advertise `change_scroll_region` and then not honour it -- Windows conhost
is the classic case -- and nothing here can detect that: the symptom is the pinned rows scrolling
away or landing on the input line, and there is no query that answers the question in advance. So
the switch is deliberately *off only*; forcing the framed layout onto a terminal that fails the
capability check would reintroduce the `Status` NPE on exit, and there is nothing to force it back
on with. Same precedent as `KOTLLM_UNICODE`, which exists for the same class of terminal lie.

### A finished turn is reprinted, not left where the editor drew it

The line editor echoes the question *between* the rules, and anything printed afterwards lands
between that echo and the pinned rule -- so the box appeared to swell around the whole
conversation, which is what it did before this was fixed. Every turn therefore:

1. **erases the echo** the editor left (`Chrome.eraseRows`), then
2. **reprints the question** with the caret (`Turn.question`), the reply in a marker-and-indent
   gutter, and a grey cost line: `Processed for 13s · done 6:04 PM · ↓ 412 tokens`.

Things that are load-bearing:

- **`echoRows` is tallied while the input is read, not reconstructed after.** A `:::` fence is
  several prompts deep; guessing its height would erase either too little of the echo or part of
  the transcript above it. Ctrl-C at the prompt reports **zero** rows, because how much the
  editor left on screen depends on what had been typed.
- **Erasing clears exactly its own rows, one at a time.** Not `ED(0J)`: "erase to the end of the
  screen" reaches past the scroll region into the reserved rows and wipes the rule and footer.
  Repainting them afterwards needed `Status.reset()`, and that is where it got genuinely bad --
  `reset()` addresses the cursor to the row *below* the region and does not put it back, a line
  feed outside the scroll region does not scroll, and from then on every line overwrote the
  bottom row. On Windows that showed as the echoed command printing on top of the footer; on
  Linux as "all the text scrunched at the bottom, never scrolls up". Clearing only our own rows
  (`CUU`, then `EL 2` and `CUD` per row, then back up) needs no repaint at all and leaves the
  cursor inside the region. Both the wrong version and the fix were diagnosed from a real pty
  capture, never from an assertion.
- **Output tokens on the cost line are a ledger delta, not the last request.** A turn that called
  tools spans several requests and all of them generated tokens. The footer cannot serve here --
  it carries the session total, which after a few turns says nothing about the answer above it.
- **The gutter is REPL-only.** `Runner.gutter` defaults to `Gutter.none`, so single-hit mode and
  `--dry-run` are byte-for-byte what the model sent; indenting a pipe would break whatever is
  downstream. `ToolRender` already emitted this exact gutter, so a tool call and a reply line up.
- **The reply is streamed into the gutter, so `StreamRender` inserts the prefix at line starts**
  rather than per line: the endpoint sends fragments, and with `highlight: false` they arrive
  mid-line. Blank lines are left bare, so nothing carries trailing whitespace and the marker
  waits for the first line that has text.

### The chrome cannot be tested by asserting on strings

Three chrome bugs in a row passed the entire suite: the footer erased off the screen, the cursor
parked below the scroll region, and the prompt drawn over the reserved rows. Escapes are
*positions*, and a test that greps for `ESC[2K` cannot tell you where the cursor ended up.

So there are two dev tools, deliberately in `scripts/`, outside `target/` so a `mvn clean` cannot take them:

- **`scripts/wslrun.sh`** runs kotllm under a real pty via `script(1)` and captures every byte it
  emits. The test JVM has no tty and a JLine terminal over byte streams takes a different code
  path, so this is the only way to see what a live console gets. Type commands with a **leading
  space** (`" /help"`) -- still parsed, but the `/` does not land in an empty buffer, so the
  completion menu does not fill the capture with candidate lists.
- **`scripts/vt.py`** replays a capture into a character grid and prints the screen, the cursor
  position and the scroll region. It takes an optional stop marker, because the screen after the
  REPL exits is blank -- it clears the pinned rows on the way out -- so a mid-session checkpoint
  is what you want to look at.

```sh
wsl -- bash scripts/wslrun.sh 24 100 target/fixtures/cap.bin " /help" " /info" " /exit"
python scripts/vt.py target/fixtures/cap.bin 24 100 "tokens billed"
```

**Put captures and fixtures in `target/fixtures/`, not `target/kotlin-ic/`.** The
kotlin-maven-plugin owns `target/kotlin-ic` -- it writes its incremental compiler state there on
every `compile` and `test-compile` -- and it will clear out anything else. Found the hard way: a
live test failed with "app.log does not exist" about a file that had been read successfully
twenty minutes earlier, and the tool harness was innocent.

The output that matters is the last four rows and the two numbers under the grid: the cursor must
be **inside** the scroll region, and the pinned rows must hold the rule and the footer rather
than anything the transcript printed.

## REPL command surface

Commands start with `/` (`/status`, `/help`, etc.). Commands are **anchored to the start of
the input**, which is the only rule the user needs to know -- a slash inside a sentence or a
path is just text, with nothing to escape. `Commands.parse` is `^`-anchored, and additionally a
leading slash only counts when it names a command that exists, so `/etc/hosts explain this`
still reaches the model rather than erroring as a bad command.

Typing `/` at the start of a line pops the command menu, via a widget bound to `/` that only
triggers completion when the buffer is empty -- the same start-of-input rule.
Mid-sentence slashes insert a plain slash.
Pasted text arrives through bracketed paste rather than the key map, so a pasted path is
unaffected.
The completer offers slash commands (`/help`, `/status`, etc.), and a test asserts every name the
completer offers is one the parser would actually dispatch.

Input from a `:::` fence is modelled as `Input.Fenced` rather than a plain string, and is never
reinterpreted as a command: a deliberately composed multi-line message that happens to open with
a slash should be sent, not dispatched.

### `/prompt full` reserves two words rather than growing a `set` prefix

`/prompt <text>` sets the prompt and `/prompt` bare shows it, so a view switch has nowhere to
live that is not also valid prompt text.
Three options, and the cheap one won:

- **Reserve `full` and `default` exactly.** Two common words shadowed as system prompts, both
  recoverable with `--prompt` at startup, and nothing existing changes. A longer prompt starting
  with either word is unaffected, because the match is on the whole argument.
- **`/prompt set <text>`.** Unambiguous, and it breaks every existing use of the command for a
  collision nobody will hit.
- **A separate command.** `/prompt` bare already answers "what is my system prompt", and a second
  command answering the same question differently is the thing a view mode avoids.

**What it prints is verbatim, not a section-by-section digest.** The question is "what is
actually being sent", and a reformatted answer to that is not an answer -- the arithmetic goes in
a dim footer instead, where the split between injected and user-written is the number that
explains a context bill.

**Session-scoped, with no config key**, the same rule as `/rag inject`: while it is on the
assembled prompt is written to the session file every turn, and a setting that survived a restart
would keep doing that long after the question was answered.

### Never print underneath the pinned region

**Every write to stdout or stderr must happen with the pinned status region released.** JLine's
`Status` reserves the bottom rows and manages a scroll region; printing into that while it is
active corrupts it. The symptom is distinctive and was reported twice: the footer migrates up
the screen and then etches itself permanently into the scrollback.

`Chrome.whilePrinting` exists for this, and `Chrome.suspended` exposes the state so it is
testable. In the REPL the invariant is held by exactly two places:

- `Repl.runCommand` suspends around the whole of `Commands.dispatch`, because most commands
  print. The overlay is the exception -- it takes the terminal over entirely -- so the overlay
  callback re-enables the region for itself, and puts it back if the overlay declines.
- `Repl.send` suspends around the whole turn, **including template expansion**. This was the
  second instance of the bug: expansion prints its own progress notice and its errors, and those
  two calls originally sat above the `suspend()`.

`Repl.banner` now runs *after* the first `chrome.refresh` -- docking has to reserve the rows
before it scrolls -- so it prints through `whilePrinting` like everything else. There is no
unprotected print site left in the REPL, and a new one belongs inside one of those windows or
wrapped in `whilePrinting`.

### The overlay is a second screen, not a bigger footer

`/status`, `/vars`, `/memory` and `/rag describe` are consulted mid-session and answer a
question about *now*; none of them belongs in the transcript permanently. They go through
`Chrome.overlay`, which switches the terminal to the **alternate screen** (`enter_ca_mode` /
`exit_ca_mode` -- what `less` and `vim` use), draws the report, waits for one keypress, and
switches back. Leaving restores the original screen byte for byte, so dismissal costs nothing.

It was first built by growing JLine's pinned region to most of the window instead. That is
fine for three lines and destructive for thirty: growing the region scrolls the transcript off
to make room, and shrinking it back does not scroll it in again. `/status` is the longest
report there is, so dismissing it handed back a black screen -- see docs/history.md.

There is deliberately **no third path**. Every terminal type that passes the pinned-region
capability check also has an alternate screen (checked: `windows-vtp` and the xterm family
have both; plain `windows`, `windows-256color` and `ansi` have neither, and on those the
chrome is unframed anyway). So `overlay` returns false when it cannot swap screens and the
caller prints the report normally, which is the same fallback a dumb terminal already took.

Height is the one limit: the alternate screen does not scroll here, so a report taller than
the window is trimmed with a `... N more line(s)` note.

### Erasing the echoed prompt

**Every** input has its echo erased now, because the transcript is reprinted above the rules --
see "A finished turn is reprinted" above. A command is echoed back as `> /help` before its output
so the output has the command above it, and a command whose whole output was a transient overlay
has *that* echo erased too, so `/status` leaves no trace at all -- otherwise the scrollback
accumulates an echoed `/status` with nothing beneath it, which is the clutter the overlay exists
to avoid.

The row arithmetic was verified against what JLine actually emits for the two-line prompt, which
is the rule terminated by `CR CR LF` then the caret row terminated the same way -- so the cursor
lands two rows below a short command, and one row further per wrap of the input. Related: the
rule is drawn one column short of the terminal width. A rule that exactly fills the width leaves
the cursor in the final column, and the terminal then auto-wraps on the next character, which
desyncs both this arithmetic and the pinned region's row count.

## Ctrl-C, and what it could not reach

`abort()` was consulted in exactly three places: during streaming, after a
response before running tools, and in the rate-limit wait. **It was never
checked inside `runToolCalls`**, so a response carrying five calls ran all
five after Ctrl-C, and a single long `exec` could not be interrupted at all.
The visible effect of pressing it was often nothing.

**One press is cooperative, a second is forceful**, because the two things a
user means by Ctrl-C have different costs. Stopping between tool calls loses
nothing. Killing a running `exec` can leave a half-written file -- the same
exposure the eval timeout already carries -- and is not something to do on a
single keystroke.

```
^C   kotllm: stopping after exec: npm install. Ctrl-C again to kill it.
^C   kotllm: killed 1 running operation(s).
```

- **`util/Cancellation` exists because interrupting a thread is not enough.**
  `Thread.interrupt` reaches a `sleep` and nothing else: a child process keeps
  running, a tight loop in evaluated code ignores it entirely (`GroovyEngine`
  documents exactly that), an HTTP read finishes on its own schedule. So
  anything owning a killable resource registers how to kill it -- `exec`
  registers `destroyForcibly`, and the Python child has `killChild`.
- **A flat global registry, not a hook on `Tool`.** The alternative was a
  per-invocation cancel field every tool would carry and almost none would
  use, for a signal that is process-wide by nature. The cost is that a
  foreground Ctrl-C can also cancel a background turn's subprocess, which is
  rare and is the honest reading of "stop what you are doing" anyway.
- **The stated risk of escalation -- a second press arriving too late -- cannot
  happen.** The window never closes while the turn is running, so a second
  press escalates at any point. What it cannot do is escalate after the turn
  has ended, which is correct: there is nothing left to kill and the press
  belongs to the line editor.
- **The handler does the least possible**, because it runs on the signal
  thread and anything blocking there would make Ctrl-C itself unresponsive --
  which is the failure being fixed. Printing from it is safe only because the
  pinned region is already suspended for the whole turn.
- **Every abandoned call is still answered.** The endpoint requires exactly one
  tool message per `tool_call_id`, so the per-call abort appends
  `not run: the turn was interrupted` rather than skipping -- the invariant a
  Runner tool-loop test asserts however the loop ends.

### `max_tool_iterations` is 100

Raised from 12 on the user's instruction. Twelve was a guess made before the
tool surface was this large, and a real task -- read, grep, edit, test, repeat
-- passes it easily; hitting the cap mid-task and typing "continue" is
friction for no safety.

It is only defensible because Ctrl-C now actually stops a turn. The cap had
been doing double duty as the escape from a stuck loop, and a round-trip
counter is not that. It remains a crude stopping rule; the budget-based one is
still in the proposals below. `/info` shows the current value, since 100 is a
number worth being able to read off.

## `--list-*`

`--list-models`, `--list-tools`, `--list-rags`, `--list-memories`,
`--list-vars`, `--list-agents`, `--list-skills`, `--list-commands`. **Bare names, one per line, no colour and no
decoration**, because these exist for scripts and shell completion -- which is
what separates them from `/info`, `/rag`, `/memory` and `/vars`, the reports for
a human, which are free to be pretty. Anything that would have to be parsed off
does not belong in a `--list`.

- `--list-tools` lists every *registered* name, not the ones offered right now:
  the question it answers is "what can go in `use_tools`", and a tool withheld
  because no index is active is still a valid answer.
- A disabled subsystem is an **error naming its key**, not an empty list. Empty
  and switched-off are different facts and a script cannot tell them apart from
  an exit code.
- `--list-roles`, `--list-sessions` and `--list-macros` are rejected with a
  sentence saying what replaced them rather than "unknown option", which reads
  like a typo.

