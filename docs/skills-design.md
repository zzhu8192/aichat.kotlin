# File tools and skills

Two features, one motivation. A skill is prose that tells the model *what to do*; almost every
skill worth having then tells it *to read, edit and search files*. Claude Code skills say so in
Claude Code's vocabulary -- "use the Read tool", "use Grep" -- and kotllm had no such tools. It
had `exec` and `groovy_eval`, which can do all of it, badly: the model writes a shell one-liner or
an interpreter snippet, quoting rules bite, output arrives through an interpreter transcript, and a
three-line edit becomes a program.

So: first-order file tools, then skills on top of them.

---

## Part 1: file tools

Six tools, all doing one thing that the model would otherwise write code for -- plus
`read_into`, which arrived later and lives in `Tools` because it needs the interpreter.

| Tool | What it does | Read-only |
| --- | --- | --- |
| `read_to_context` | one file, line-numbered, into the conversation | yes |
| `read_to_var` | one file into a session `String`; contents never enter context | yes |
| `write_from_eval` | an expression out to a file; result never enters context | no |
| `session_vars` | what is bound in the session: names, types, sizes | yes |
| `write` | create or replace a whole file | no |
| `edit` | exact string replacement inside a file | no |
| `glob` | path patterns, newest first | yes |
| `grep` | regex over file contents, three output modes | yes |
| `ls` | one directory, with sizes | yes |

### Why these and not a general `file` tool with a `mode` argument

A tool's description is the only documentation the model gets, and a schema with six modes has
one description covering six jobs. Six schemas cost more tokens and buy a precise description
and a precise argument list each. The model is choosing from a list; the list should be made of
distinguishable things.

### `read` numbers its lines, and `edit` forgives the numbers

Line numbers are what make a windowed read (`offset`/`limit`) meaningful, let `grep` output and
`read` output line up, and let the model cite `file:line` -- which the terminal renders as a
clickable link and the user can verify.

They are also a trap: having just read `42\tval x = 1`, a model will paste the prefix into
`edit`'s `old_string` and the match fails. So `edit` retries once with a `^\s*\d+\t` prefix
stripped from every line of the search string. That is three lines of code against a failure
that would otherwise cost a round trip and read as a broken tool.

### `edit` refuses an ambiguous match, and that is the whole safety property

String replacement is only trustworthy if a search string that appears twice is an error rather
than a coin flip. So:

- **0 matches** -> error, with the first line of the search string quoted back, because the usual
  cause is whitespace.
- **more than 1 match** -> error naming the count, telling the model to add surrounding context
  or pass `replace_all`.
- **exactly 1** -> replaced, and the result reports the line it landed on.

`replace_all` exists for renames, where every occurrence genuinely is the target.

### `write` will not blindly clobber a file nobody looked at

Not a security rule -- the user has explicitly deferred security -- a **data-loss** rule. The
worst thing a file tool can do is replace 800 lines it never read with the 20 the model was
thinking about. So overwriting an existing file requires that the same session has already
`read` or `write`ten it; creating a new file needs nothing. The error names `read` as the fix.

The memo is a set of canonicalised paths in the tool closure, so it is per-process and per-run,
which is the right scope: a file that changed under us between sessions should be re-read.

### `grep` is implemented here rather than shelling out to ripgrep

`exec` could run `rg`, and on this machine it would work. It is not installed everywhere, its
absence surfaces as a confusing shell error rather than a missing tool, and the flags differ
between `rg`, `grep` and `findstr`. A walk plus `java.util.regex` is about eighty lines, behaves
identically on every platform, and can report *why* a file was skipped.

Three output modes, matching how the model actually uses search:

- `files_with_matches` (default) -- cheapest, and the right first call.
- `content` -- matching lines with numbers, and `-C` context.
- `count` -- per-file counts, for "how widespread is this".

### The skip list is now shared, because a third caller appeared

`input/Input.kt` and `rag/Rag.kt` each carried a set of directories never to walk
(`target`, `node_modules`, `.git`, ...). CLAUDE.md recorded that this was a real drift risk and
worth unifying *if a third caller appeared*. `grep` and `glob` are that caller -- and the two
sets had in fact already drifted, Rag's being a strict superset.

`util/Skips` now owns the directory set and the binary-extension set. Both old call sites
delegate. What deliberately stays unshared is the *extension* handling: Input maps extensions to
markdown fence languages, Rag maps them to an indexing allowlist, and those look alike but do
different jobs.

### Everything is capped, and says what it withheld

Same discipline as `search`: `read` truncates at a character cap and says so with the total;
`glob` and `grep` cap result counts and report the number withheld; `grep` caps the files it
scans. One tool call must not be able to spend the window. A cap that silently drops results is
worse than a small cap that admits it, because the model draws conclusions from an empty tail.

### Read-only, so they do not prompt

`read`, `glob`, `grep` and `ls` are `readOnly = true` and skip the confirm prompt, like `search`
and `fetch`. `write` and `edit` mutate the disk and prompt like `exec` does.

---

## Part 2: skills

A skill is a directory with a `SKILL.md`: YAML frontmatter plus markdown instructions. Loaded in
three tiers, which is the entire idea -- many skills can be *available* for the price of a line
each, and only the relevant one costs a body.

| Tier | When | Cost | Mechanism here |
| --- | --- | --- | --- |
| 1 metadata | every turn | ~1 line per skill | `Skills.systemPromptSection` |
| 2 instructions | when the model asks | the body | the `skill` tool |
| 3 bundled files | as needed | nothing until read | `read` / `exec` on the skill directory |

### What we implement of the format, and what we skip

Anthropic's own documentation puts the **portable** frontmatter at six fields. Everything else in
Claude Code -- `context: fork`, `agent`, `hooks`, `effort`, `model`, `paths`, `background`,
`disable-model-invocation` -- is a Claude Code extension that fails validation on other surfaces.
We read the six and ignore the rest (silently, since a hub skill will carry them and refusing to
load it would be useless):

| Field | Required | Use here |
| --- | --- | --- |
| `name` | yes | the identifier the model passes to the `skill` tool |
| `description` | yes | the trigger text, and the whole of tier 1 |
| `license` | no | shown by `/skills`, never sent to the model |
| `compatibility` | no | shown by `/skills` |
| `metadata` | no | shown by `/skills` |
| `allowed-tools` | no | reported as **encouraged**, not enforced |

Unknown fields are collected and reported by `/skills` so nothing is invisible, but they are not
injected -- a field we do not implement must not reach the prompt and read as a promise.

### `allowed-tools` is advice, not a gate

Deliberate, on the user's instruction: "I'll be happy if the LLM actually decides to use the
right tools." Enforcement would mean narrowing `activeTools` for the duration of a skill, which
means tracking when a skill is in effect -- real state in `Runner`, for a constraint the model is
going to honour or not honour regardless. So the tier-2 result says
`encouraged tools: read, edit, exec` and stops there. If the model reaches for something else,
that is a prompt problem, visible in the transcript, and fixable in the skill file.

### The tool-vocabulary gap, and the two lines that close it

A hub skill says "use the Read tool" and "run this with Bash". We have `read` and `exec`. The
mapping is trivial for a model that has both schemas in context, but only if it is told, so the
tier-1 section carries one line of translation:

    Claude Code names map to kotllm's: Read->read_to_context, Write->write, Edit->edit,
    Glob->glob, Grep->grep, Bash->exec, WebFetch->fetch, WebSearch->web_search.

This is why the file tools came first. Without them the mapping has nowhere to land and half of
every skill is unexecutable.

### Discovery

Three roots, nearest last so the most specific wins on a name collision:

1. `<config dir>/skills/*/SKILL.md` -- kotllm's own.
2. `~/.claude/skills/*/SKILL.md` -- personal Claude Code skills, so an existing install works
   with no copying.
3. `.claude/skills/**/SKILL.md`, walking up from the working directory -- project skills.

Nested directories are walked, because plugin and hub layouts nest. A directory without a
`SKILL.md` is not a skill and is skipped in silence; a `SKILL.md` whose frontmatter is invalid is
**reported**, with the reason, rather than dropped -- the same rule as an over-budget memory. A
skill that is present but ignored is worse than one visibly absent.

### Tier 1 is budgeted, and names what it dropped

`skills_max_chars` (4000) caps the injected block. Over budget, skills are dropped from the tail
and the block says how many and their names. Twenty skills at a line each is fine; two hundred
from a hub install is not, and the failure has to be legible.

### Tier 2 tells the model where it is standing

The `skill` tool returns the body with frontmatter stripped, and prepends the **absolute skill
directory** plus a listing of the bundled files. Both matter: a skill body says
"run `scripts/fill.py`" or "see `REFERENCE.md`", and those paths are relative to a directory the
model has otherwise never been told. Without the listing it would have to guess that tier 3
exists at all.

`$ARGUMENTS` in the body is substituted when `/skill <name> <args>` supplies them, empty
otherwise. That is one string replace and it is what hub skills use; the `arguments:` frontmatter
field that declares named placeholders is a Claude Code extension and is skipped.

The tool is `readOnly` -- it reads a file the user installed -- so it does not prompt. Every call
is still printed with the skill name, like every other tool call.

### Surface

- `skill` tool -- model-invoked, tier 2.
- `/skills` -- what is discovered, with scope, size, license and any unknown fields.
- `/skill <name> [args]` -- load a skill deliberately and send it as the turn's input, for when
  you know which one you want rather than hoping the description fires.
- `--list-skills`, and `--skill <name>` for the same thing non-interactively.

### How this relates to agents

They sit on opposite sides of the same line and both are worth having.

- A **skill** is context the model pulls in and then *interprets*. No control flow, no
  determinism, cheap to write, portable to other harnesses.
- An **agent** is a program in `<% %>` that *calls* the model. Exact control flow, typed results,
  kotllm-only.

A skill makes the model better at a task. An agent makes the task happen the same way twice. An
agent file can load a skill body to specialise one step; a skill can tell the model to run an
agent.

### Not built

No enforcement of `allowed-tools`. No sandboxing or provenance checking of installed skills --
the user has deferred security, and the honest statement is that a skill is arbitrary
instructions plus arbitrary scripts, exactly as Anthropic's own documentation says. No
`context: fork`, no hooks, no bundled-script runtime management: if a skill needs `python3` and
three packages, it needs them.
