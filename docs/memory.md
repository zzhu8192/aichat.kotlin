# Memories

Durable markdown notes injected into the system prompt each turn. Two scopes, both plain files:
every `*.md` in `<config dir>/memory/` (global), and a `KOTLLM.md` found by walking up from the
working directory (project). Deliberately files rather than a database or embedding store --
greppable and diffable means inspectable and editable without kotllm, which is most of the
value when the model acts on them every turn.

- **Ordering is global, then project, nearest last.** Later text wins when two notes disagree,
  so the most specific file has the final word. The user's own system prompt goes after all of
  them.
- **Framed as background, not instruction.** The section says so explicitly, so a stale note
  cannot read as an override of what the user is asking for now.
- **Re-read every turn, not cached.** Editing a memory file in another window takes effect on
  the next message. Reading a few small files costs nothing next to a request.
- **Over-budget files are named, never silently dropped.** A memory that is present but ignored
  is worse than one visibly absent, because the user assumes it applied.
- **One writer per shape.** `Memory.append` backs `/memory add`; `Memory.upsert` backs
  `add_or_update_memory`. Before an earlier increment `remember` wrote `notes.md` and nothing
  ever read it back -- the only place kotllm actively over-promised.
- **Editable, not just appendable.** Named memories are upserted and deleted by name; see
  "Editable memories" below for why there is no `search_memory`.

`/memory` shows what is being injected with sizes; `/memory add <note>` appends. `{{memory.files}}`
and `{{memory.chars}}` expose the same facts to prompts.

### Editable memories, and the question of whether to search them

`remember` could only **append**, to one shared `notes.md`, as unnamed dated
lines. So a fact recorded wrongly was permanent, and it went on being injected
into every request -- the worst shape available for a store whose whole job is to
be authoritative. `add_or_update_memory` and `delete_memory` replace it:
**identity is the filename**, so writing the same name replaces that memory and
deleting one is possible at all.

`remember` is **gone rather than kept alongside**. Two tools where the
cheap-looking one is the one that cannot be undone is the `read` / `read_into`
mistake again. It is a model-visible rename, so a `use_tools: remember` list
needs editing.

Decisions worth keeping:

- **No format, therefore no parser.** The tier-1 label is the **first non-empty
  line**, stripped of leading `#`/`-`/`*`. Frontmatter was available -- `Skill`
  already parses it -- and was rejected because a heading is what someone writing
  markdown puts first anyway, and this works unchanged on files the user wrote by
  hand and on the dated bullets `notes.md` already contains.
- **Containment falls out of the slug.** `../../etc/passwd` becomes
  `etc-passwd.md`; every separator is collapsed by the same transform that makes
  the name, so there is no separate check beside it to forget.
- **Writes go through `AtomicWrite`.** A memory is injected into every request,
  so a half-written one is the last thing this should be able to produce.
- **Global only.** A project `KOTLLM.md` is a file the user curates and may have
  checked in, and a tool rewriting it wants the read-before-overwrite guard
  `write` has, which there is nothing here to satisfy.
- **`delete_memory` is not read-only** and prompts under `confirm` like `write`.
  The exemption exists for outbound reads, not for destroying the user's data.
- **`/memory add` survives** as the quick unnamed line the user types, which is a
  different act from the model curating a named fact -- and `notes.md` is still
  addressable as `notes` like any other memory.

#### Should memory be searched? Only past the budget, and then it is not search

The obvious next tool is `search_memory`, and the argument against it is
arithmetic. A lookup tool costs its schema on **every** request, plus a round
trip, plus the standing risk the model never calls it. Under a few thousand
characters -- which is every real config so far -- it therefore pays more to
deliver less than injection does, and **injection is what was already built**:
every memory is in the system prompt on every request, so there is nothing to
find and no instruction needed telling the model to look.

The interesting case is only what happens past `memory_max_chars`, and the old
answer there was wrong: the overflow was **named and dropped**, leaving the model
told about a memory it had no way to reach.

So the overflow now **degrades to one line each** (`name -- first line`), which is
the skills tiering arrived at from the other direction:

```
# groovy-version.md (global)
The user prefers Groovy 5. No Groovy 2 idioms.

Over the 8000-char memory budget, so only listed here. Read one with
`read_memory` if it looks relevant to what is being asked:
- deployment-runbook -- How releases go out, step by step
- api-conventions -- Error shapes and pagination rules for the internal API
```

That is what makes the tier-2 read **discoverable**, which is the whole reason
this is not a search tool. `RagStore` already paid for that lesson live: a tool
schema announces that a tool *exists*, never that a corpus worth reading does,
and a textbook question got answered from training data while the index sat
unused. The index block answers "how would it know?" by being in the prompt.

`read_memory` is **registered always and offered only while something is over
budget**, filtered per request in `Runner.activeTools` -- the same split as
`search` and an active index, and for the same reason: deciding it at
registration would freeze the answer, so a memory saved mid-session could not
become readable until a restart. Under the budget it is withdrawn entirely,
because every memory is already in front of the model and the call could only
re-fetch what it is reading.

**Index lines are charged against the same budget**, or the configured number
stops being the truth about what is spent. The pathological tail -- not even a
line fits -- is still reported, but by **name and capped at five**: it listed
every full path, which on a budget too small to hold anything cost several times
the budget it was reporting. A test that asserts the section stays near the
configured size found that.

#### One cache, and the one that was reverted

`load()` is uncached, and there is a test asserting an external edit is visible to
the very next call. Caching it was the first attempt and that test caught it
within the minute -- the contract is per *call*, not per message, whatever the
comment I wrote said.

What survived is narrower: `hasIndexOnly` alone is memoised for a second, because
`Runner.activeTools` asks it to decide whether to offer `read_memory` and the
status footer asks `activeTools` on every refresh -- so drawing a prompt would
otherwise list the memory directory and read every file in it. Safe to stale,
because all a second of staleness can do is offer or withhold a tool whose
absence costs nothing, while the prompt the model reads is always built from a
fresh load. Invalidated on every write, so a tool result never claims something
the next request would contradict.

`util/AtomicWrite` was lifted out of `tools/FileTools` for this, on the `Skips`
precedent: the alternative was `kotllm.memory` depending on `kotllm.tools`, which
is the dependency backwards, or a second copy of the replace-don't-truncate
logic.

