# History: bugs that shipped, and what they cost

An archive. Nothing here describes current behaviour -- the subsystem docs do that.
What is kept is the *evidence*: the measurement, the failure, and the reason a
decision is the way it is, so a later change cannot quietly undo it without
first disagreeing with a number.

## REPL chrome

### The alternate character set, and the rules that rendered as `q`

Reported over ssh, `TERM=xterm`, PuTTY: both rules came out as rows of `q`, while the footer's
`·` separators and `↓` arrow in the *same* capture were perfect.

```
qqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqq
>
qqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqq   kotllm 1.0.4-SNAPSHOT · ...
```

That asymmetry is the whole diagnosis, and it rules out an encoding problem: if the charset
could not carry `─` it could not carry `·` or `↓` either.

**`AttributedCharSequence.toAnsi(Terminal)` rewrites eleven Unicode box-drawing characters
into the terminal's terminfo alternate charset** -- `─ │ ┌ ┐ └ ┘ ├ ┤ ┬ ┴ ┼`, and `─` is our rule. It emits
`enter_alt_charset_mode`, the `acsc` byte, then `exit_alt_charset_mode`; on xterm-family
terminfo that is `ESC(0`, `q` per column, `ESC(B`. Confirmed in the bytecode: a `lookupswitch`
on eleven keys, the first being `9472`, which is `0x2500`.

The substitution is **unconditional**. It never asks whether the output encoding can represent
`─`; it asks only whether the terminal declares `smacs`, which every xterm-family terminfo
does. And **PuTTY in UTF-8 mode ignores the DEC line-drawing charset** unless
*Window → Translation → "Enable VT100 line drawing even in UTF-8 mode"* is ticked, which is off by
default. So a fully capable terminal was sent the 1979 encoding of a horizontal line and dropped
it on the floor.

Two faults, and both needed fixing:

- **`Glyphs.dividerChar` was an unconditional `─`** -- the one glyph in that object not gated on
  `Glyphs.unicode`, so the file broke its own documented rule. It falls back to ASCII `-` now
  (not `_`, which reads as a blank line, and not `=`, which reads as a heading underline).
- **JLine's rewrite is switched off** via `org.jline.utils.disableAlternateCharset`, set by
  `util/JLineTuning`. We already decide for ourselves whether the terminal can render these
  glyphs; a second, coarser downgrade underneath ours can only disagree with it, and it
  disagrees by emitting a form some terminals discard.

**`JLineTuning.init()` must run before `AttributedCharSequence` is initialised**, because JLine
reads the flag once with `Boolean.getBoolean` in a static initialiser. Hence the first line of
`Main.main` *and* the `Chrome` constructor: whichever reaches a terminal first has already
called it. It does not overwrite an explicit setting, so `-Dorg.jline.utils.disableAlternateCharset=false`
still reproduces the old behaviour -- which is how the fix was A/B'd.

Verified on a real pty, both ways, because this is layout and bytes rather than a string:

| | `ESC(0` | `q` runs | `─` runs |
| --- | --- | --- | --- |
| forced `false` (the bug) | 2 | 18 | 0 |
| as shipped | **0** | **0** | **27** |

**`isFramed` replaced `contains(Glyphs.dividerChar)`.** `ReplLineReader` identified the framed
prompt on resize by looking for the divider *character*, which was safe only while it was a
box-drawing glyph nothing else used. With an ASCII fallback it becomes `contains("-")`, and the
plain prompt is the model name -- `databricks-qwen35-122b-a10b>` is full of hyphens.
`Prompt.isFramed` looks for three in a row, which only a rule has.

**Two things this was not.** The rule and footer appeared on one line in the report; the pty
capture shows them on rows 22 and 23 with the cursor inside the scroll region, so that was a
copy-paste join rather than a second bug. And `ChromeSpec` had two assertions reading
`should (include(dividerChar) or include("qqq"))` -- it had *seen* the `q`s in an earlier capture
and tolerated them instead of asking why. Both are exact now, which is what keeps the fix.

### Docking, and why `cursor_address` was the wrong tool

The footer is pinned to the bottom of the screen but the prompt is drawn wherever the cursor
happens to be, so a shell whose output ends halfway up the screen used to get rules bracketing
half a screen of somebody else's text. `Chrome.dockBottom` previously addressed the cursor
directly, which *moves over* what is already there -- the prompt was drawn on top of the shell's
last line and the banner landed in the middle of it.

It now prints a screenful of newlines first, which scrolls what was there into the scrollback and
leaves every row below blank, and only then addresses the row. Nothing is overwritten because
there is nothing left to overwrite, and the target needs no cursor query -- which matters,
because `AbstractWindowsTerminal` does not implement `getCursorPosition` at all and the POSIX
implementation blocks on a DSR reply.

`Repl.run` therefore orders startup as **reserve, dock, banner**: docking scrolls, so anything
printed before it is gone, and `dockBottom(rowsAbove = bannerRows)` leaves exactly the rows the
banner needs -- measured, since a missing tool key adds a second line and either line can wrap.

**`Status.suspend` does not release the scroll region**; it only stops Status painting. That is
load-bearing and worth knowing: printed output can never scroll into the reserved rows, so the
cursor always ends inside the region and the next prompt always lands directly above the footer.

**Gotcha that cost a crash-on-exit bug:** `Status.getStatus(terminal)` happily constructs a
Status on a terminal that cannot support one, leaving its internal display null and then
dereferencing it in `close()` -- which the terminal calls on shutdown whether or not we do. So
piping kotllm's output produced an NPE on exit. `Chrome` therefore tests the four terminal
capabilities Status itself requires (`change_scroll_region`, `save_cursor`, `restore_cursor`,
`cursor_address`) *before* creating one, and degrades to a plain single-line prompt otherwise.
Never construct a `Status` without that gate.

### The overlay that handed back a black screen

`/status` painted its report by growing the pinned status region to `height - 4` rows and
calling `Status.update`. Reported as: dismiss the overlay and the whole screen is black.

Growing the region **scrolls the transcript off the top to make room**, which is correct and
unavoidable -- the rows have to come from somewhere. Shrinking it back to two rows afterwards
does not scroll anything back in, because scrolled-off content is in the scrollback buffer, not
in a region a terminal can restore. So the cost of the overlay was the entire visible screen,
and it was paid in proportion to the report's length: three lines were unnoticeable, thirty
were fatal. `/status` is thirty.

The fix is the mechanism every full-screen pager already uses: `enter_ca_mode` /
`exit_ca_mode`. The alternate screen is a separate buffer, and leaving it restores the original
one byte for byte. The pinned-region path was deleted rather than kept as a fallback -- every
terminal that can host the pinned region can also swap screens, so the fallback was a branch
nothing would ever take.


## Completion

### The emoji candidates JLine threw away, and the bell that said so

Reported as: "I typed `:smil<tab>` and it made a DING sound, like I clicked something I
shouldn't." Emoji shortcode completion offered seven perfectly good candidates for `:smil`
and the terminal beeped at all of them.

**JLine filters candidates by matching each candidate's `value` against the word being
completed.** The first version put the emoji there -- the reasoning being that picking
`:fire:` should insert `🔥`, the way Slack's picker does -- and `🔥` does not start with
`:fir`. Every candidate failed the match, the match set came back empty, and an empty match
set is a bell. The completer was working; nothing downstream of it agreed.

The value has to be the shortcode text. The emoji still arrives, one step later: submit-time
`Emoji.expand` converts the inserted `:fire:` when the line is sent, which is the mechanism
that already existed. The glyph moved to the candidate's *display* string, where showing it
was the point anyway.

**The test gap is the part worth keeping.** `CompleterSpec` constructs a `ReplCompleter`,
calls `complete`, and asserts on the candidates it collected -- and four such tests passed
while the feature beeped, because that call never reaches the matching layer that discarded
them. "The completer offers the right candidates" and "Tab completes" are different claims,
and only the first was ever tested.

| | direct `complete` call | real `LineReader` + `\t` |
| --- | --- | --- |
| candidate value `🔥` (the bug) | **passes** | fails: line comes back unchanged |
| candidate value `:fire:` | passes | passes |

`EmojiCompletionSpec` drives a real `LineReader` over a scripted stream with a real tab
byte, which is the only version of this test that can fail -- confirmed by reintroducing the
bug and watching it go red. Two traps in writing it: a **dumb** terminal does not complete at
all, so the terminal type has to be `xterm-256color` or the suite passes by saying nothing;
and the listing colours the matched prefix, so `:smiley:` reaches the screen as
`ESC[36m:smil ESC[0mey:` and a plain substring search finds nothing. Both of those produced a
false negative on the way to the diagnosis -- including against a real pty capture, which
*had* drawn the list correctly all along.

### The list that ran without error and drew nothing

Fixing the bell exposed the next two, reported together as "it doesn't really work, and once
done, the next time you restart the whole terminal state seems smashed."

**`:s` listed nothing.** 219 shortcodes start with `s`, JLine's `list-max` is 100, and its
answer to a list past that is to print nothing and ask nothing. Seven candidates listed fine
and 219 were silent, which is a hard failure to reason about from the outside. The offer is
capped below `list-max` now: the list exists to find a name, and nobody reads to entry 219.

**Suspending the pinned region hid the list.** The REPL's own rule, written on
`Chrome.whilePrinting`, is that anything writing to stdout suspends the reserved rows first --
so wrapping the completion call in it looked like simply obeying the documented invariant. It
is the one place the rule does not apply: JLine draws the list through its own `Display`,
which already accounts for the `Status` region sharing the screen, and suspending underneath
means the restore repaints over what was just drawn. The symptom is maximally unhelpful --
the widget runs, `callWidget` returns normally, nothing is thrown, and the screen is
unchanged. Confirmed by removing only the wrapper and re-running the same capture.

### The arrow keys that walked out of the menu

Reported as "I still can't use arrow keys to navigate up and down / left right to actually
select the choice. (Same problem for `/` menu items.)" -- and the `/` menu had been like that
since it was built, so this is a pre-existing bug the emoji list only made visible.

JLine's `menu` keymap binds Tab to `menu-complete`, Shift-Tab to `reverse-menu-complete` and
Enter to `accept-line`. It does **not** bind the arrows to anything menu-related: `ESC O A`
and friends are on ordinary cursor movement and `ESC [ A` is not bound at all. So an arrow
press was not a navigation that failed, it was an exit -- the menu ended, `ESC` was consumed,
and the rest of the sequence self-inserted. The `B` left sitting in the line after a
selection is the `B` of `ESC [ B`.

Both encodings have to be bound. A terminal sends `ESC [ A` normally and `ESC O A` once the
keypad is in application mode, and JLine puts it there for the duration of a read -- bind one
and it works half the time, which is worse than not working.

Getting the menu to open at all is a separate option, and the obvious one is wrong.
`MENU_COMPLETE` makes the *first* Tab select the first candidate, which is the "silently
inserting the first one" that `bindSlashMenu` had already rejected for commands. `AUTO_MENU`
is the one that wanted: the first completion still only lists, the second opens the menu. That
was measured before flipping it globally, because the first-Tab behaviour was the reason the
option had been off.

| keys | before | after |
| --- | --- | --- |
| `:smil` TAB TAB ENTER | `:smile:` | `:smile:` |
| `:smil` TAB TAB ↓ ENTER | `:smile: B` | **`:smile_cat:`** |
| `:smil` TAB TAB ↓↓↑ ENTER | - | **`:smile_cat:`** |

### Three callers, three slightly different questions

Two bugs with one cause, reported together: ":ha shows list, :hai the list disappears, even
though :haircut and :haircut_man is there" and "/a -> does not narrow to /agent", plus
"pressing up/down clears it" on the `/` menu.

The list was drawn by one key, stepped into by another, and filled by a third, and each of
them decided for itself what counted as completable:

| | as-you-type key | arrow keys | completer |
| --- | --- | --- | --- |
| `:hai` | only at exactly 3 chars | shortcode, any length | shortcode, any length |
| `/a` | never -- shortcodes only | `/` + 2 or more | any `/word` |
| bare `/` | never | **no** (needed 2 chars) | yes |

Every gap in that table is one of the reports. Listing only at the threshold meant the next
character wiped the list and nothing redrew it. The as-you-type key not knowing about commands
meant the `/` list never narrowed. And the arrows requiring two characters after the `/` meant
bare `/` -- which had just drawn a list -- fell through to history navigation, which *replaces
the line*, so the arrow appeared to clear the menu.

`ReplCompleter.wantsList` is now the single answer, and all three ask it. The fix is smaller
than the diagnosis: the bug was never in any one of the three, it was in their disagreeing.

**Re-listing per keystroke turned out to be free.** It had been avoided on the theory that each
list is printed and would stack up the screen -- true of a plain `AUTO_LIST`, but the menu list
is drawn by JLine's display and redraws in place. Checked on a real pty before relying on it.

### The scroll region that outlived the process

A pinned region is a DECSTBM, and **a scroll region belongs to the terminal, not to the
program that set it**. Nothing resets it when a process dies, so every command run afterwards
is confined to the rows kotllm was using -- which is why this is reported as the *next* run
being broken, when the damage was done by the previous one.

`Chrome.close()` only runs on the way out of a clean exit. An exception escaping the loop, the
interpreter's `System.exit` trap, or a SIGTERM all skip it. And JLine's own teardown is not
quite a reset either: it emits `ESC[1;Nr` for the N it last measured, which only restores the
full screen if that N still matches the window.

So the release is `ESC[r` -- the parameterless form, meaning "the whole screen", whatever the
screen currently is, which is the only form that cannot be stale -- plus show-cursor and
leave-alternate-screen, from a shutdown hook as well as from `close()`. Armed only when the
chrome is framed, so nothing is ever written into a pipe.

| | end of capture |
| --- | --- |
| before | `ESC[1;30r` (a reset only at exactly 30 rows) |
| after | `ESC[1;30r` … **`ESC[r`** `ESC[?25h` `ESC[?1049l` |

### List-as-you-type used a widget that completes, and rewrote every command

Reported as three faults: `/prompt` offered no completions, the screen was corrupted and did not
recover, and `/prompt full` did nothing.
One cause.

`bindListAsYouType` ran on every alphanumeric keystroke and called `expand-or-complete`.
That widget is a *completion*, not a listing: when the prefix narrows to exactly one candidate it
inserts it.
So the keystroke that made `/p` unambiguous inserted the whole of `/prompt `, and the `r`, `o`,
`m`, `p`, `t` still on their way landed after it.

Driving a real `LineReader` and reading the buffer back, against stock JLine as the control:

| typed | buffer | stock JLine |
| --- | --- | --- |
| `/prompt` | `/prompt rompt ` | `/prompt` |
| `/rag` | `/rag g` | `/rag` |
| `/think` | `/think ink` | `/think` |
| `/set` | `/set t` | `/set` |

The damage was not cosmetic, which is why it arrived as three reports rather than one.
The parser saw a first argument of `rompt`, so `argumentsFor` was asked for position 2 and
`/prompt` correctly offered nothing.
And `/prompt full` reached the dispatcher as the text `rompt full`, missed the `full` and
`default` cases, fell through to `case Some(text)`, and was **stored as the system prompt**.
A completion bug silently overwrote user state.

`list-choices` draws the same list and leaves the buffer alone.
Tab keeps `expand-or-complete`, because there the insertion is what was asked for -- the
distinction is that Tab is a request and a keystroke is not.

**Every existing test drove the list-as-you-type path and none pressed Tab**, which is how this
survived: `drawn(":ha")` and `drawn("/a")` assert what reaches the screen, and the screen was
fine. The buffer was not. The tests now assert the buffer.

## Pricing

### The lookup rule failed twice before it settled

Both failures came from real configs, which is the only reason the rule is what it is.

- **Exact keys** miss immediately. The endpoint is `databricks-qwen35-122b-a10b`; the published
  price is for "Qwen 3.5 122B".
- **Longest prefix** fixes that and then misses `databricks-deepseek-v4-1-flash` against
  "DeepSeek V4 Flash" -- the point release is spliced into the *middle* of the name, where no
  prefix can reach it.

So a key matches when its dash-separated segments appear as an **ordered subsequence** of the
model id's. `[databricks, deepseek, v4, flash]` is found inside
`[databricks, deepseek, v4, 1, flash]`, and the most specific match wins (most segments, then
longest key), which is what makes a user override reliable.

It stays strict where looseness would be wrong, because segments compare **whole**:
`gpt-oss-20b` does not match `gpt-oss-120b`, and `glm-5-2` does not match `glm-5-3`. The
deliberate cost is that a partial segment is not a match either -- `databricks-qwen` does *not*
cover `databricks-qwen3-80b`, so a family-wide key has to be spelled as a real segment.

Costing a matched *variant* is still a guess: `v4-1-flash` is priced at `v4-flash`'s rate on the
assumption that a point release costs the same. Better than `unpriced`, and another reason the
number is labelled.


## Compaction

### Three faults the first live run exposed

Compaction passed its unit tests and was actively harmful against the real endpoint. Prompt
tokens went **1229 -> 1433** across a compaction, and it then compacted on every single turn.

- **A summary can be larger than what it replaced.** Asked to summarise a handful of short
  exchanges, the model produced 1100+ chars of prose in place of ~300. Now: a hard
  `compact_summary_max_chars`, a prompt that says outright that a summary longer than its input
  is worse than none, and -- the real guard -- **any compaction that does not shrink the context
  is rejected** and the history left alone.
- **Nothing verified the result.** `compact` now compares total characters before and after and
  refuses to commit a rewrite that is not smaller. Trimming already done is still kept, since it
  is independently valid.
- **Nothing stopped it retrying.** Once over the threshold it compacted every turn. Now guarded
  by the message count at the last attempt, so nothing is retried until the history has grown.


## User tools

### Compiling properly deleted three reflection hacks

Switching from `IMain` to `scala.tools.nsc.Global` was for the cache, and the
larger payoff was elsewhere. An `object` compiled as ordinary source is
top-level with a static `MODULE$`. The same object defined through `IMain` is
nested inside a synthetic `$iw`, which is what made its case class an inner
class with an invisible `$outer` constructor parameter, no `$outer` on the
tool object to borrow one from, and a generic signature one shorter than the
erased one. All three are gone; the guards stay in `ToolArgs` as no-ops
because they cost nothing and a future nesting would need them.

**The cost, and it is the one behaviour change: a tool file is a real
compilation unit.** A bare top-level `def` worked under `IMain` and does not
compile here -- put helpers in an object. That is the ordinary Scala rule
instead of a REPL dialect, and the error says so plainly.

### Three things reflection got wrong before it worked

All three were found by probing the actual compiled shape rather than
reasoning about it, which is the only reason this works at all.

1. **`IMain.valueOfTerm` answers `None` for an `object`.** Reading the
   instance straight out of the interpreter -- the obvious design -- returns
   nothing for exactly the shape a tool file defines. The mechanism that works
   is the one `write_from_eval` already uses: bind a `ToolSink` we own into the
   session, then ask the interpreter to push each freshly defined name into it.
2. **A case class in a tool file is an *inner* class.** The interpreter wraps
   each file in a synthetic `$iw`, so `case class GreetArgs(who: String, times:
   Int)` compiles to fields `who, times, $outer` and a **three**-argument
   constructor `($iw, String, int)` -- the outer reference is last among the
   fields and first among the parameters. Matching them positionally without
   that offset fails on every tool anyone writes. And the tool object carries
   no `$outer` of its own to borrow, so the enclosing instance is found from
   the other end: `$line3.$read$` has a static `MODULE$` and a declared
   `INSTANCE`, and `$read` holds the `$iw`. `ToolArgs.findEnclosing` does a
   bounded search from there.
3. **`getGenericParameterTypes` omits synthetic parameters.** It can be one
   shorter than `getParameterTypes`, so dropping the outer slot from both
   shifts the generics by one and `Seq[String]` gets read with
   `Option[String]`'s signature. `genericsAligned` compares the lengths
   instead of assuming.

Surface: `/tools` lists what each file produced and the cache key, `/tools
reload` forces a recompile ignoring the cache, `user_tools: false` turns the
whole thing off. `/tools` and `--list-tools` wait for a compile in flight,
because both answer "what exists" and an answer missing a tool because a
thread has not finished is wrong rather than stale; the status footer does
not wait, because blocking a prompt redraw on a compiler would be a worse bug
than the slow startup this fixes.

Verified live end to end: `examples/tools/word_count.scala` copied into a
config dir, and *"count the words in this sentence ... then count the distinct
words"* produced two `word_count` calls -- one with the required argument, one
adding `unique: true` -- and the right answers, with nothing in the prompt
about how to use it.


### The manifest described tools that were not offered

Found while building `tools_exclude`, but it had been there since the manifest was written.
`Manifest.systemPromptSection` opened with "kotllm gives you a live Scala REPL via
`scala_eval`, and shell access via `exec`" and then listed the whole bundled classpath --
unconditionally, whatever was actually on offer. Only the `read_to_var` paragraph was gated.

So `use_tools: read_to_context, grep` produced a system prompt promising an interpreter the
model had no schema for, and the classpath list is the largest thing in that prompt. Nothing
had exercised a short `use_tools` list, so nothing caught it; `tools_exclude` turned a config
nobody wrote into the obvious one (`tools_exclude: "*_eval"`).

Every paragraph is now gated on its own tool, and the section returns `""` when neither
`scala_eval` nor `exec` survives -- `Runner.toolSystemPrompt` drops it rather than sending a
heading with nothing under it. The `activeTools` parameter lost its `Set.empty` default in the
same change: once empty means "say nothing", a caller that forgets the argument gets silence
instead of a compile error.


## Session persistence

### `ujson` writes a bare `Long` as a JSON *string*

Worth its own note, because it cost a real bug and the symptom pointed nowhere near the cause.
`ujson.Obj("at" -> someLong)` produces `"at":"1758000000000"` -- quoted -- because a Long exceeds
Double's exact integer range and ujson chooses lossless over lossy. The reader only tried
`numOpt`, so **every timestamp loaded as `0`** and the only visible symptom was sessions coming
back in the wrong order.

Timestamps are now written as an explicit `ujson.Num` (epoch millis sit far inside 2^53), and the
reader accepts **either** shape, so files written by the build that had the bug still load. Both
halves are tested, including a `ujson.write` assertion that pins the surprising behaviour itself.


### The test suite wrote into the developer's own conversation history

`save` defaults to `true`.
`TestIsolation.isolate` turned off memories, RAG, skills and user tools -- everything that could
leak *into* a test -- and left `save` alone, which leaked the other way.
Every spec that built a `Runner` and sent a turn opened a real JSONL in `Paths.sessionsDir`, and
with `KOTLLM_CONFIG_DIR` set (which `runit.bat` sets) that is the user's own history.

Found only by counting files in `config/sessions` before and after a run while verifying
something else: 222 before, and a scatter of three-line `stub-model` conversations after, which
`search_chats` had been retrieving alongside real ones.
Nothing failed, which is why it survived -- the cost was silent corpus pollution, and the
detector was arithmetic rather than an assertion.

Fixed by adding `save = false` to the isolation object, verified the same way: the file count is
now identical either side of `mvn test`.

## The token estimator

### The estimator must be fed the serialised request, not the message text

`prompt_tokens` counts everything the endpoint receives, including the tool JSON schemas --
around 700 tokens once three tools are declared. Calibrating from message text alone meant
dividing a small character count by a large token count, and the estimator concluded **1.37
chars/token**. That inflated every estimate, which would have made the rate limiter throttle far
too early exactly when prompts are largest. `sendOnce` now bases both the estimate and the
calibration on `ujson.write(request.encode).length`, and the learned ratio settled at a
plausible 2.96 on the live endpoint. The test stub counts the same way, or it teaches the
estimator a fiction and the throttle tests stop meaning anything.

### The context counter that survived its own context

Reported alongside the Ollama zero: `/empty` clears the conversation and the footer goes on
saying `18.4k/261k ctx (7%)` until the next turn.

Both the footer and `/status` read `Session.lastUsage`, which is the last row of the ledger,
and `/empty` deliberately leaves the ledger alone -- the tokens *were* spent, and the prompt
sparkline would be worthless if a clear erased them. Correct for billing, wrong for the
question the footer is actually answering, which is "how full am I *now*". The same staleness
hit `/compact`, `/prompt`, `/regenerate`, a `/memory` edit and a `/rag` toggle: every one of
them rewrites the next request without a turn happening.

Rather than invalidate from each of those call sites -- a list that is wrong the moment someone
adds a command -- `Runner.contextSize` derives the figure: the endpoint's own `prompt_tokens`
for the last request, plus the estimated token value of every character added or removed since.
Nothing has to remember to notify it. It is exact when the payload is unchanged, and the
estimator it borrows is the calibrated one, so the correction is within a few percent.

The footer does not mark the estimate, because after any reply the answer is always "projected"
and a marker that is always on says nothing. `/status` has room for the word and uses it.


## Clients

### The Ollama token counter that never left zero

Reported against `gemma4:26b`: every turn recorded 0 in / 0 out, so the footer showed no
context pressure, `/status` showed no tokens, and the rate limiter had nothing to correct its
estimate with.

Nothing was wrong with the decoding. `Usage.decode` is fine, `Chunk.decode` reads `usage` off a
chunk whose `choices` array is empty, and `StreamAssembler` replaces rather than accumulates it.
The fault was one absent key in the *request*: **an OpenAI-compatible server sends no `usage` on
a streamed response unless asked**, via `stream_options: {"include_usage": true}`. Databricks
sends it unasked on every chunk, which is exactly why the gap went unnoticed -- the primary
endpoint had been papering over a missing request field since the streaming path was written,
and the Azure client, which does set the key, looked like an Azure quirk rather than the rule.

Asked for on the Ollama and generic-OpenAI providers only. Databricks does not need it and an
unknown body key is a 400 on a strict gateway; the live-tested path is not the place to find
out. Pinned by a test that reads the bytes off a real socket, because every layer below the
request body was already correct and a unit test of any of them would have passed.


## RAG: the embedding service that was never built

### Verified endpoint facts (historical -- nothing uses these)

Probed against the live gateway, 2026-09-15, before the user ruled out external embedding
models. Kept because it cost real calls to learn and because the "option to use that in the
future" was explicitly left open:

- `POST {api_base}/embeddings` works on the same route pattern and auth as chat.
- `databricks-gte-large-en` returns **1024 dimensions**, reporting itself as `gte-large-en-v1.5`.
- **Batch limit is 150.** A 256-item batch is rejected with
  `BAD_REQUEST: Input embeddings size is too large, exceeding 150 limit`. 32 works.
- The workspace exposes 74 serving endpoints, several of them embedding models
  (`huggingface_embeddings_v4/v5`, `huggingface_med_embed`).

Sizing at 1024 dims as float32: 1k chunks = 3.9MB, 10k = 39MB, 100k = 391MB. Brute-force cosine
over tens of thousands of vectors is single-digit milliseconds, which is why no approximate
nearest-neighbour index is warranted at this scale.

### Cost, and why indexing needs care

This applies to **Phase 2 only**. Phase 1 indexing is local and free, which is most of why it
went first.

Embedding a large tree is thousands of real billed calls. Required: batching at the model limit,
routing through the existing rate limiter, a **cost estimate before starting**, and progress
reporting. **Resumability was dropped on the user's instruction** -- they do not intend to index
trees large enough for an interrupted run to matter, and re-indexing is incremental anyway.

## The Kotlin port

Everything above this section is Scala-era history and is left as it happened; none of it is
rewritten for the port.
This section is new.
It records the defects the Scala->Kotlin port itself introduced, and that a test caught before
they shipped.
None of these are algorithm bugs -- every one is a mechanical mistranslation of a construct that
means something different in the two languages, which is exactly the failure mode `CONVENTIONS.md`
exists to close off one entry at a time.

### `private[web]` becoming `private` instead of `internal`

Scala's `private[web]` grants access to the whole `web` package, including its own test sources
in a separate compilation root.
The literal-minded translation is Kotlin `private`, which is narrower: it is member- or file-scoped
and a test in `kotllm.web` cannot see it at all.
Four declarations in `kotllm.web.Search` -- `ResultLink`, `hrefAndText`, `ResultSnippet`, and
`Coordinates` -- were unreachable from their own package's tests until this was caught and the
modifier corrected to `internal`, which is Kotlin's actual module-wide equivalent.

### `Memory`'s `apply` colliding with the Kotlin stdlib

Scala's `object Memory { def apply(config, dir): Memory }` beside `class Memory` is the ordinary
factory-method idiom, and `Memory(config, dir)` at the call site binds to it without ambiguity.
A literal `fun apply` on the Kotlin companion object compiles -- and loses, silently, to
`kotlin.apply`, the stdlib scope function every object already has.
The call site still parses; it just runs the wrong function and fails on argument count instead of
on the name.
The fix is `operator fun invoke`, which is what makes `Memory(config, dir)` resolve as a call
rather than as `apply` with a lambda.

### Class-initialisation cycles in `TaskStatus.all` and `SearchSource.all`

Both are a sealed type whose nested objects are its own cases, plus a companion `val` that lists
them: `sealed class TaskStatus { data object Pending : TaskStatus; companion object { val all =
listOf(Pending, ...) } }`.
It compiles, and `all` holds nulls.
Initialising `TaskStatus` runs the companion initialiser first, which reads `Pending`, whose own
initialisation needs `TaskStatus` -- already in progress on the same thread, so the JVM hands back
null rather than deadlocking, and the first `all.joinToString { it.wire }` is a `NullPointerException`
several calls away from the actual cause.
Scala had no equivalent bug: `object TaskStatus` there initialises independently of the sealed
trait's cases.
Only a test surfaced this in Kotlin, twice -- once in `kotllm.task.Checklist`'s `TaskStatus`, once
in `kotllm.web.Search`'s `SearchSource`.
Both are fixed with `by lazy`, which defers evaluating `all` until after both types have finished
initialising.

### `Matcher.quoteReplacement` carried into a Kotlin lambda-form `Regex.replace`

Scala's `Regex.replaceAllIn(s, m => repl)` and the `String.replace(Regex, String)` overload
both feed the replacement through `Matcher.appendReplacement`, where `$` and `\` are group
references -- which is why a glyph or emoji table wrapped its replacement text in
`Matcher.quoteReplacement` before handing it over.
Kotlin's `Regex.replace(input) { m -> ... }` lambda form does not do this: whatever the lambda
returns is inserted **literally**, with no group-reference interpretation to escape in the first
place.
Carrying the quoting across anyway put the escaping artifact into the output instead of removing
one: "It costs $5" rendered as `It costs \$5`.
`kotllm.render.Latex` and `kotllm.render.Emoji` keep `Matcher.quoteReplacement` only around the
`String.replace(Regex, String)` overload, which does still read `$` as a backreference, and the
fix is recorded in a comment at the call site so it cannot be "tidied" back out by a future edit.

### `convert(v!!)` in the YAML loader

Scala's `case other => other` in the old config value converter passed a `null` straight through,
because a YAML key written `foo:` with nothing after it is a legal, meaningful value: "present,
empty."
`kotllm.util.Yaml`'s first Kotlin draft added a `!!` to make a signature line up, which is exactly
the case CONVENTIONS.md warns against: a `!!` that exists only to satisfy the compiler is a sign
the signature is wrong, not the value.
The shipped example config has keys written exactly that way, so the first run of `kotllm` against
it failed with `cannot parse: NullPointerException`, reported by the catch-all above the loader as
a malformed file when the file was fine.
`convert` is nullable in and nullable out now, with no `!!` anywhere in the path.

### The hex literal that stopped an avalanche mix from wrapping

`kotllm.rag.Embedder`'s mixing constants -- `0x9e3779b9`, `0x85ebca6b`, `0xc2b2ae35` -- are all
above `0x7fffffff`, the boundary where a hex literal is a Scala `Int` (wrapping, 32-bit) but a
Kotlin `Long` (not wrapping at that width) unless told otherwise.
The Scala source relies on the wraparound: it is what makes the avalanche step spread bits across
the whole 32-bit hash instead of concentrating them in the high end.
The direct Kotlin transcription of the same literals compiled, ran, and produced a hash -- just a
different one from the Scala encoder, for every bucket, silently, with no exception to catch it.
Because RAG indexes are written to disk and read back later, this is the one defect on this list
that would have corrupted data already committed to an index rather than merely misbehaving in
memory.
The fix is `0x85ebca6b.toInt()` and its siblings: force the truncation the JVM would have done for
free in Scala.

