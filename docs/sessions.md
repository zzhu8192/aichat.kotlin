# Sessions

Persistence, search over stored conversations, context compaction, and
background turns.

## Persisted sessions, and search over them

Three tools taken from aichat: `ask_user`, `search_chats` / `view_chat`, and `get_sys_dt`.

### `save: true` was a dead key, and that is why chat search did not exist

It was parsed, printed by `/info`, and read by nothing. `Paths.sessionsDir` was created at
startup and never written to. That is the same over-promise `remember` made when it wrote a
`notes.md` nothing loaded -- and it is exactly what made `search_chats` impossible: a search tool
over an empty corpus. `/info` now prints the file being written rather than a bare boolean.

**JSONL, one file per session, appended per turn.** The reason is the failure mode: a crash or a
kill keeps everything up to the last completed reply, where a single JSON document rewritten on
exit loses the whole session to the event most likely to happen during a long task. It is also
greppable and diffable without kotllm, the same argument that made memories plain files.

- **Only the new tail is written.** `record` compares the history length to what it has already
  stored, rather than hooking every `append` -- because **compaction replaces the history
  wholesale**, and a store mirroring every mutation would either lose the original exchanges or
  duplicate the summary. `resyncAfterCompaction` moves the cursor, so the file keeps the full
  conversation even after the live history has been compacted down to a recap.
- **Reasoning parts are already stripped** by `Session.appendResponse`, so what is stored is what
  was replayed. No second filter to drift.
- **Forked runners get `SessionStore.disabled`.** A background turn and an agent both carry
  `save = false`: their replies land in their own timeline and are not the conversation the user
  is having, so they must not open a second file beside the real one.
- Messages with no text are skipped -- tool-call plumbing carries nothing a later search could
  match, and the arguments are already in the assistant message that requested them.
- Failures are swallowed. Logging must never break a turn.

### The assembled system prompt, recorded only while `/prompt full` is on

A `{"kind":"prompt","i":<first message it governed>,"full":...,"user":...}` row, written ahead of
the messages it applied to.
It exists because the prompt is the one part of a request that never entered the history: it is
assembled per turn by `Runner.systemPrompt` and prepended by `messagesToSend`, so a stored
conversation recorded what was said and nothing about how the model was configured to say it.

- **Written on every turn, not only when it changes.** The first version deduplicated on content,
  which is cheaper and was wrong for the use: answering "what was the prompt at turn nine" then
  means scanning backwards for the last row at or before it, and getting a straight answer is the
  whole reason the mode exists. The near-duplicate bytes are affordable precisely because this is
  a debugging view nobody leaves on.
- **Both layers, separately.** `user` is always the tail of `full`, so it is redundant -- and the
  question actually asked of this record is "which of that did I write", which is not a thing to
  work out by eye across eight concatenated sections.
- **A prompt row carries no `role` and no `text`.** That is what makes the format compatible in
  both directions: the reader that predates it skips the line rather than failing the file, and
  `ChatSearch` never sees it. Indexing kotllm's own prompt text would make every session match
  every harness-flavoured query.
- **Message numbering counts message rows only.** A prompt row partway through a file must not
  shift the index of everything after it, or every citation `search_chats` ever emitted breaks.
- **`view_chat` takes `prompt: true` to return it.** Written and never read is the failure this
  codebase has shipped three times; the tool is the reader.

### Chat search reuses the RAG machinery but not the RAG index

`Bm25`, `HashingEmbedder` and `Fusion` are reused verbatim, so a typo or a different word ending
still lands. `RagIndex` is **not**, for two concrete reasons:

- **Citations differ in kind.** `RagIndex` cites `path:startLine-endLine`, which for a
  conversation is a JSONL line number -- useless. A chat needs a session id, a message number and
  a timestamp, and `view_chat` needs the id to resolve.
- **There is one active RAG index and it belongs to the user.** Chat search must not occupy that
  slot or be switched off by `/rag off`.

Other decisions:

- **Chunked per message, not per N characters.** A conversation already has natural units, and a
  boundary drawn mid-reply would cite a message number matching only part of what was found.
- **Messages under 24 characters are not indexed.** "ok", "yes", "thanks" match everything and
  mean nothing.
- **The model name is indexed alongside the text**, the same reasoning as indexing the path with
  a RAG chunk: "what did I ask about qwen" should find a conversation held on that model even if
  the word never appears in it.
- **The current session is excluded.** Searching the conversation in progress returns what was
  said a moment ago, which is never the question, and would crowd out the older material.
- **Rebuilt on demand, never persisted.** A few hundred messages index in milliseconds, and a
  stored index would need invalidation rules against a directory that grows every turn -- the same
  trade that made `HashingEmbedder` stateless.
- **Ids resolve from a prefix or the four-character suffix**, because
  `2026-09-18T18-06-11-3577` is not something anyone retypes. Verified live: `view_chat` found it
  from `3577`.
- **Registered only once something is stored.** A search over an empty corpus is a schema the
  model can only waste a call on -- the same rule as `search` with no active index.
- "Nothing saved" and "nothing matched" are **different results**, and the first says that
  `save: true` is what enables it. A configuration fact is not an empty result to retry.

Verified live end to end, across two separate processes: one session answered a question about
ssh, a second session was asked *"have I asked you this before?"*, called `search_chats` once,
and came back with the session id and a quotation of the earlier reply.

### `ask_user`

Extends the existing `Prompter` SPI rather than inventing a second interaction channel, so
`ReplPrompter` grows one method and `Prompter.unavailable` answers `None`.

**The free-text row is appended by the prompter, never supplied by the model.** A fixed list is a
guess about what the answer might be, and the one time it is wrong the user needs a way to say
so. The description says outright not to add its own "Other", or there would be two.

The refusals are the substance, and there are three:

- **Nobody attached** -- withdrawn from the schema entirely rather than registered and refused
  per call. Returning the first option would fabricate a decision the user never made, and the
  model would then build on it as though they had.
- **Cancelled at the prompt** -- reported as unanswered, with "state your assumption and
  continue", so the model proceeds rather than asking again in a loop.
- **No options** -- refused, because an open question invites an essay and the choices are the
  entire feature.

Number parsing is all-or-nothing: every token must be an in-range number, or the whole input is
free text. Otherwise "2 maybe" silently becomes option 2. Choosing the free-text row asks a
follow-up rather than returning that row's own label as an answer.

Note on verification: the interactive gate is tested directly rather than through the pty
harness, which feeds stdin from a file and therefore reports no console at all -- structurally
unable to exercise the path.

### `get_sys_dt`

Epoch seconds and millis, ISO-8601 UTC, local time with its zone, and the weekday -- every form
from one call, because a `format` argument would guarantee a second call whenever the first guess
was wrong. The model has no clock, which makes this one of very few facts it genuinely cannot
derive.

**Deliberately not injected into the system prompt.** A value that changes every turn sits in
front of ~3,080 tokens of tool schemas and would bust prompt caching for all of it. Paying that
on every request to save an occasional round trip is the wrong trade.

## Context compaction

Automatic, and checked against the **projected** size of the request about to be sent, not the
last measured one -- reacting to the last prompt is always a turn late, and one tool result is
enough to overshoot in the gap. `Runner.autoCompactIfNeeded` runs before the payload is measured
for the limiter.

Two mechanisms, cheapest first:

1. **Trim old tool results** (`Compactor.trimOldToolResults`). No request at all, and it attacks
   the actual source of growth. Only results, and only outside the protected tail: the model has
   already acted on an old one, while a recent one may still be what it is reasoning about.
2. **Summarise the head.** A side request turns older exchanges into one recap message; recent
   exchanges stay verbatim. The head gives the model history, the tail gives it state, and
   without state it cannot continue mid-task.

### The invariant that matters most

The history is **never split inside an exchange**. `Compactor.exchanges` groups from each user
message to just before the next, so an assistant message carrying `tool_calls` and the tool
messages answering it always move or vanish together. The endpoint requires exactly one tool
result per `tool_call_id`, so a split between them produces a malformed request -- the same class
of bug that shipped with `max_tool_iterations`. `CompactorTest` asserts the balance of requested
against answered ids on both sides of every split.

### Compaction cannot fix fixed overhead

The system prompt, tool schemas and memories were ~1140 tokens in that run -- against a
1400-token window the threshold was already crossed with an empty history, so no amount of
history rewriting helps. `fixedOverheadTokens` measures a request with no history, and when that
alone is over the threshold kotllm says so **once**, with the numbers, and stops trying:

> the system prompt and tool schemas alone are ~1139 tokens, over the compaction threshold for a
> 1400-token window. Compaction cannot help; reduce tools, memories, or use a larger model.

### Accounting and failure

The summarising request goes through the rate limiter and lands in the ledger like any other,
because it *is* one: a large prompt and real tokens. Leaving it out would make the accounting lie
and corrupt the estimator, which calibrates on observed usage. It is never streamed and never
tool-enabled -- offering tools would invite the model to go exploring instead of summarising.

Failure is never destructive: a failed or empty summary returns the original history untouched.
`Session.replaceHistory` counts compactions, surfaced in `/status` and `/info`, because summaries
of summaries degrade and a session on its fifth has lost most of its detail.

`/compact` forces one regardless of the threshold.

## Background turns

`/bg <prompt>` runs a turn without blocking the prompt; `/bg` lists them. Notices are printed
between prompts with the reply itself, so an answer is never lost.

What makes them safe rather than merely concurrent:

- **Output is captured, never streamed.** The fork forces `stream: false` and suppresses all
  user-facing output (`Runner.quiet`). A background reply interleaving with foreground output,
  or with the line editor, would corrupt both.
- **Notices drain between prompts**, never mid-keystroke, through a queue rather than a poll --
  so a notice cannot print twice and cannot land inside the input line.
- **The session is forked, not shared.** The fork copies the history so it has context, but
  replies land in its own timeline and never mutate the foreground conversation.
- **The rate limiter *is* shared.** A private window per turn would let two concurrent turns
  together exceed the provider limit.
- **The interpreter is *not* shared.** A background `groovy_eval` assigning into the same
  session binding would silently alter what the foreground REPL sees. Isolation is worth a second
  compiler, and one is only started if the background turn actually calls a tool.
- **There is nobody to approve a tool call**, so the fork gets `Prompter.unavailable`: under the
  default `confirm` a background tool call is refused, with that reason returned to the model.
  `auto` or an allowlist entry is required for unattended tool use, which is the honest default.
  `/bg` says so when it starts a turn that will hit this.
- Threads are daemons and `cancelAll` runs on exit, so a forgotten turn can never hold the
  process open.

Deliberately small: no attach, no tail, no per-task kill. Those were out of scope, and a
half-built job-control surface is worse than none.

