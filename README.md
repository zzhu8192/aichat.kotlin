# kotllm

A command-line LLM REPL written in **Kotlin 2.1** on **Java 17**, built with Maven, targeting
models served by ** Azure AI Foundry **, **Databricks AI Gateway** and any **OpenAI-compatible** (e.g. Ollama) endpoint .

What makes it different from a generic chat CLI:

- **A live Groovy 5 interpreter as a tool.** The model writes Groovy, kotllm compiles and runs
  it in-process against the fat jar's classpath, and returns the real REPL output.
- **Two client-side escape mechanisms.** `<% %>` runs Groovy to build your prompt; `{{ }}`
  interpolates facts about the harness. Both resolve locally - the model never sees a tag.
- **Honest accounting.** Per-request and per-tool-call tokens, timings, context pressure and
  rate-limit state, all measured rather than estimated where the endpoint reports them.
- **It survives long sessions.** Automatic context compaction trims old tool results and
  summarises older turns, keeping recent ones verbatim, so a conversation does not simply hit the
  window and stop.

Configuration format is a simplified subset of [aichat](https://github.com/sigoden/aichat), so
an existing `config.yaml` largely drops in.

---

## Contents

- [Build](#build) · [Configure](#configure) · [Two modes](#two-modes)
- [Tools: running code](#tools-running-code)
- [Templating with `<% %>`](#templating-with---)
- [Harness variables with `{{ }}`](#harness-variables-with--)
- [Multi-form input](#multi-form-input)
- [Memories](#memories)
- [Retrieval over a folder](#retrieval-over-a-folder): [hybrid search](#hybrid-search-bm25-plus-a-local-encoder) · [how the model knows](#how-the-model-knows-to-search)
- [Background turns](#background-turns)
- [Context compaction](#context-compaction)
- [Metrics](#metrics)
- [Rate limiting](#rate-limiting)
- [Reasoning models](#reasoning-models)
- [Reference](#reference): [commands](#repl-commands) · [CLI](#command-line-options) · [variables](#harness-variable-reference) · [config](#configuration-reference)
- [Not implemented yet](#not-implemented-yet) · [Development](#development)

This file is the user manual. For *why* any of it is built the way it is, see
[`docs/`](docs/).

---

## Build

Requires JDK 17 and Maven 3.9+.

```sh
mvn clean package          # produces target/kotllm.jar (a fat jar)
java -jar target/kotllm.jar
```

The jar is self-contained: the Groovy runtime ships inside it, which is what lets the
`groovy_eval` tool compile code with no Groovy installation on the machine.
kotllm itself is written in Kotlin; the language it runs *for the model* is Groovy, so
`org.apache.groovy:groovy` is a runtime dependency of this build rather than a build-time one.

### Windows launcher

```bat
@echo off
chcp 65001 >nul
set KOTLLM_CONFIG_DIR=d:\path\to\config
java -Dfile.encoding=UTF-8 -jar target\kotllm.jar %1 %2 %3 %4 %5 %6 %7 %8 %9
```

`chcp 65001` matters: Java 17 reports `Cp1252` for a Windows console, which cannot encode the
box-drawing characters, so kotllm falls back to ASCII rules and bars. With UTF-8 you get the
real glyphs. Everything works either way.

---

## Configure

kotllm looks for `config.yaml` in:

| Platform | Location |
|---|---|
| Linux / macOS | `~/.config/kotllm/config.yaml` |
| Windows | `%APPDATA%\kotllm\config.yaml` |
| Any | `$KOTLLM_CONFIG_DIR/config.yaml`, or `--config <path>` |

A minimal working config for a Databricks serving endpoint:

```yaml
model: my-endpoint-name
function_calling: true
use_tools: all

clients:
  - type: openai-compatible
    name: my-endpoint-name
    api_base: https://<workspace>.azuredatabricks.net/serving-endpoints
    api_key: dapi...
    models:
      - name: my-endpoint-name
        max_input_tokens: 261000
        supports_function_calling: true
```

`api_base` is the **collection** route. kotllm posts to `{api_base}/chat/completions` with
`model` in the body, so no per-endpoint `/invocations` URL is needed.

**One config file holds as many models as you like** - `clients:` is a list and each entry has
its own `models:`, so a Databricks endpoint and a local Ollama server sit side by side. Give
them short names with `aliases:` and every place that takes a model id takes the alias:

```yaml
model: qwen                        # the default; may itself be an alias

aliases:
  qwen:  databricks-qwen35-122b-a10b
  gemma: local:gemma4:26b
```

The names are yours and mean nothing to kotllm - it looks the string up and resolves whatever
it finds. An alias is a spelling, not a category.

```sh
kotllm -m gemma "summarise this"   # one-off
```
```groovy
<%= llm("summarise this", false, "gemma") %>   <%-- inside an agent file --%>
```
```
> /model gemma                     # for the rest of the session
> /model                           # current model, then every alias
```

An alias may point at another alias; a cycle is an error naming the loop. An alias that points
at nothing, carries a colon or a space, or shadows a real client or model name is reported at
startup rather than failing the load - `--list-models` lists aliases alongside models, because
the question that flag answers is "what can I pass to `-m`".

> **Both `function_calling: true` and `use_tools: all` are required** to enable tools.
> `supports_function_calling` under a model only declares what the *model* can do - it does not
> switch anything on. The REPL banner always states the current tool status, and warns if your
> model supports tools but none are enabled.

See [`examples/config.example.yaml`](examples/config.example.yaml) for every key with commentary.

Check your setup:

```sh
kotllm --info          # resolved endpoint, model, tools, limits
kotllm --list-models   # configured models
```

---

## Two modes

**Single-hit** - no conversation context, answers once and exits:

```sh
kotllm "explain Groovy's delegate resolution strategy"
git diff | kotllm "review this"
```

**REPL** - every turn carries the accumulated context:

```
$ kotllm
kotllm 1.35.1-SNAPSHOT | databricks-qwen35-122b-a10b | tools: groovy_eval, exec, remember | /help for commands
──────────────────────────────────────────────────────────────────
> what is 2+2?
──────────────────────────────────────────────────────────────────
  qwen35-122b · 1.2k/261k ctx (0%) · ↓ 284 tokens · 1 reqs
```

Output scrolls above the upper rule; the footer below shows model, context pressure, session
output tokens and request count. Context and rate percentages turn amber past two thirds and
red past ninety.

**Context pressure is what the *next* request will carry**, not what the last one cost. It is
anchored on the endpoint's own `prompt_tokens` and corrected for everything that has changed
since, so `/empty`, `/compact`, `/prompt` and a memory edit all move it at the next prompt
rather than a turn later.

- Commands start with `/` (`/status`, `/help`). Typing `/` at the start of a line
  pops the command menu.
- Wrap input in `:::` fences for multi-line messages.
- **Ctrl-C** stops a generation in progress; **Ctrl-D** exits.

---

## Tools: running code

With tools enabled the model gets three:

| Tool | What it does |
|---|---|
| `groovy_eval` | Compiles and runs Groovy 5 in a live in-process session. Session state persists between calls. |
| `exec` | Runs a shell command through the system shell, so pipes and redirection work. |
| `remember` | Appends a durable note to your memory directory. |

```
> calculate fib(20) using groovy

* groovy_eval
  │ fib = { n -> n < 2 ? (n as BigInteger) : fib(n-1) + fib(n-2) }
  │ fib(20)
  │ 6765
  │ 0.3s

fib(20) = 6765
```

**State persists across calls**, so the model can explore in steps. The rule is Groovy's own:
an assignment without `def` lands in the session binding and is in scope next call, while a
`def` is local to its own snippet and is gone. Everything bundled in the fat jar is importable
with no build step: `groovy.json` (`JsonSlurper`, `JsonOutput`), kotlinx serialization,
SnakeYAML, plus all of `groovy.*` and the Java 17 standard library.

Add more at runtime with no rebuild:

```yaml
classpath_append:
  - d:/libs/mylib.jar
  - d:/libs/*          # trailing star expands to the jars in that directory
```

or `KOTLLM_CLASSPATH=...`.

### Approval

`groovy_eval` and `exec` are arbitrary code execution on your machine, so the default is to ask:

```
* exec
  │ git status --short
  run exec? [y]es / [n]o / [a]ll >
```

`a` approves for the rest of the session. Three modes via `tool_approval`:

| Mode | Behaviour |
|---|---|
| `confirm` | Prompt before each call; `a` = yes-to-all (default) |
| `auto` | Never prompt - fast agentic loops, no safety net |
| `deny` | Refuse every call |

Commands you never want to be asked about, as **anchored** regexes:

```yaml
exec_allowlist:
  - git (status|diff|log|show)( .*)?
  - ls( .*)?
```

Anchored deliberately: allowing `git status` must not also allow `git status && rm -rf /`.

Two behaviours worth knowing rather than discovering:

- **With `confirm` and no terminal attached** (piped input, single-hit mode) tool calls are
  **refused**, not silently auto-approved. The refusal reason goes back to the model, which
  adapts. Use `auto` for unattended runs.
- `System.exit` / `sys.exit` in evaluated code is **defused**: the call site is rewritten before
  compiling, so it prints a note and the session carries on. Java 17 deprecated `SecurityManager`
  and the call cannot be trapped at runtime, so this used to be a flat refusal -- which worked,
  but had to be explained in the system prompt on every request to make sense. A rule the model
  cannot break beats one it has to be told about. A *mention* of the call, in a string or a
  comment, is left alone. Long-running code is interrupted after `eval_timeout` seconds.

### The tool loop

kotllm relays tool results and lets the model decide when it's finished - it stops when the
model replies with prose instead of another tool call. `max_tool_iterations` (default 12) is a
safety net, not the stop condition. If you hit it:

```
kotllm: stopping after 12 tool round trips (max_tool_iterations). The model may not have
finished -- say 'continue' to resume, or raise the cap with '/set max_tool_iterations <n>'.
```

Saying `continue` resumes correctly; the conversation is left in a valid state.

---

## Templating with `<% %>`

JSP-style Groovy templating, resolved **on the client before the request is built**. The model
receives only the resulting text.

| Tag | Meaning |
|---|---|
| `<% statements %>` | Groovy statements, emitting nothing themselves |
| `<%= expression %>` | Evaluated and interpolated |
| `<%-- comment --%>` | Dropped |
| `<%%` | A literal `<%` |

```
> <% def fs = Files.walk(Paths.get("src")).filter { it.toString().endsWith(".kt") }.toList(); def big = fs.max { Files.size(it) } %>Review these <%= fs.size() %> files. Largest is <%= big.fileName %> at <%= Files.size(big) %> bytes.
```

The model sees: *"Review these 178 files. Largest is Runner.kt at 54981 bytes."*

`java.nio.file`, `java.time` and `groovy.json` are star-imported into the session, which is why
`Files` and `Paths` are there without an import.

Templates share the interpreter session with `groovy_eval`, so values cross between them. Input
with no `<%` in it never starts the compiler. A compile error is reported against **your**
template line, with the generated program annotated:

```
template failed to compile or run:
startup failed:
template line 2: The current scope already contains a variable of the name x
 on template line 2.
    def x = 1; def x = 2
                   ^

generated program (left column is your template line):
   1 | __kotllm_out.append("line one\n")
   2 |  def x = 1; def x = 2
   3 | __kotllm_out.append("\n")
```

Use `--dry-run` to see exactly what would be sent:

```sh
kotllm --dry-run '<%= 2 * 21 %> files'
```

Inside the REPL the equivalent is [`/prompt full`](#seeing-the-assembled-system-prompt).

---

## Harness variables with `{{ }}`

A lighter mechanism for facts about the harness - no compiler, can't fail in interesting ways.

```
> I'm on {{model}} at {{endpoint}}, in {{pwd}} on {{os}}. Session up {{uptime}}, {{tokens.total}} tokens used.
```

Around thirty variables. `/vars` lists them all with live values. Variables resolve **before**
`<% %>`, so a fact can be used inside template code:

```
<%= "{{model}}".toUpperCase %>      →  DATABRICKS-QWEN35-122B-A10B
```

An unknown name is an error with a suggestion, rather than silently leaving braces in your
prompt:

```
kotllm: unknown harness variable {{modle}}; did you mean {{model}}?
```

Braces around anything that isn't a variable reference pass through untouched, so prose and
JSON are safe. Escape a literal with `\{{name}}`.

---

## Multi-form input

`-f` on the command line, `/file` in the REPL. Accepts files, directories, URLs, shell commands
in backticks, and `%%` for the previous reply.

```sh
kotllm -f src/main/kotlin -f README.md "review the input layer"
kotllm -f '`git diff`' "what changed?"
kotllm -f https://example.com/spec.txt "summarise this"
```

```
> /file src/main/kotlin/kotllm/Runner.kt pom.xml -- why is the tool loop shaped this way?
included 2 block(s), 31402 chars
```

`--` separates sources from your message. Each source becomes a fenced, labelled block so the
model can refer to files by name.

Guards, because this can spend a whole context window in one keystroke:

- Three caps - `input_max_file_chars`, `input_max_total_chars`, `input_max_files`. Hitting one
  says which.
- Binary files are detected and skipped.
- Directory walks skip `target`, `node_modules`, `.git` and other build output.
- **Anything skipped is always reported.** Believing the model saw a file it never received is
  worse than sending nothing.

Convert formats that aren't text (aichat-compatible):

```yaml
document_loaders:
  pdf: pdftotext $1 -
  docx: pandoc --to plain $1
```

---

## Memories

Durable markdown notes injected into the system prompt every turn. Two scopes, both plain files
you can edit or grep without kotllm:

| Scope | Location |
|---|---|
| global | every `*.md` in `<config dir>/memory/` |
| project | `KOTLLM.md` in the working directory or any ancestor |

```
> /memory add I prefer Maven over Gradle, and Groovy over Kotlin DSL for build files
remembered, in .../memory/notes.md

> /memory
2 memory file(s), 412 chars injected into the system prompt
budget 8000 chars; global dir .../config/memory
      74 chars  [global ]  .../memory/notes.md
     128 chars  [project]  .../KOTLLM.md
```

The model can also write one itself via the `remember` tool.

- Global first, then project, **nearest last** - later text wins when two notes disagree, so the
  most specific file has the final word. Your own system prompt comes after all of them.
- Framed explicitly as *background context, not instructions*, so a stale note can't override
  what you're asking now.
- **Re-read every turn**, so editing a file in another window takes effect on the next message.
- Files over `memory_max_chars` are **named**, not silently dropped.

---

## Retrieval over a folder

Point kotllm at a folder, and the model gains a `search` tool over it that answers with
`path:startLine-endLine` citations. Useful when the question is about *your* files - a codebase,
a docs tree, a directory of notes - and the alternative is pasting half of it into the prompt.

```
> /rag index D:\work\myproject
  indexing 200/1184 files
  ...
index 'myproject': 1184 added, 0 changed, 0 removed, 0 unchanged; 4732 chunks total
  (+4732, -0) over 1184 file(s)
the search tool is now available to the model

> where do we validate the callback signature?

I'll look that up.

  search  callback signature validate
  ✓ 8 passages (0.3s)

Validation happens in two places:

- `src/main/kotlin/auth/Callback.kt:88-131` checks the HMAC before anything else...
- `src/main/kotlin/auth/Routes.kt:44-60` then rejects an unknown `state`...
```

The index lives in `<config dir>/rag/<name>/` as `meta.json` plus `chunks.jsonl`, so it is
inspectable and greppable without kotllm.

### Indexing

```
/rag index <folder>     Index a folder and make it active
/rag                    What is active: folder, counts, profile, injection
/rag reindex            Re-scan the active folder for changes
/rag use <name>         Switch to another index
/rag off                Deactivate; the search tool is withdrawn
/rag drop <name>        Delete an index from disk
/rag search <query>     Search from the REPL, to check what the model would see
/rag describe <text>    Tell the model what this corpus is (see below)
/rag inject on|off      Add passages to every request (see below)
```

`--rag <name>` activates an index for one run, and `--rag-index <folder>` builds one without
entering the REPL - so setup can be scripted:

```sh
kotllm --rag-index ./docs --rag-describe "our API docs"   # build an index and exit
kotllm --rag docs "how is auth configured?"

kotllm --rag-index . "what is this project?"   # index, then ask, in one go
```

`--rag-index` **exits when you give it nothing else to do**, rather than dropping into the REPL,
because the case it exists for is a script - and a script that opens an interactive prompt hangs.
Give it TEXT and it indexes first, then answers. It reports what it skipped and exits non-zero if
the folder cannot be indexed.

Both flags compose, applied in that order: `--rag-index` activates the folder it built, so a
later `--rag` can still point retrieval somewhere else.

One thing to know for unattended use: tool approval defaults to `confirm`, and with no terminal
to ask at, a `search` call is **refused** rather than auto-approved. So a scripted
`--rag-index . "question"` needs `tool_approval: auto` in config, or an `exec_allowlist` entry.
The refusal message says exactly that when you hit it.

**Re-indexing only re-reads what changed.** The metastore records each file's size and
modification time, so a second run over an unchanged tree does no work, an edited file is
re-chunked, and a deleted file has its chunks removed. That last case matters more than it
sounds: an index that keeps answering with text no longer on disk is worse than no index.
A rename is handled as a delete plus an add.

**What gets indexed is decided by extension, explicitly.** Source, prose, config and build
files are in; formats that are not text at all - `pdf`, `docx`, `class`, `jar`, `zip`, `tar`,
`bz2`, `exe`, `parquet`, `safetensors`, images - are refused *by name*, with the reason.
`target/`, `node_modules/`, `.git/`, `__pycache__/` and dot-directories are skipped. Add more
extensions with `rag_extra_extensions`. A `document_loaders` entry makes its format eligible,
so with `pdf: pdftotext $1 -` configured, PDFs are indexed through the converter.

Nothing is skipped silently. Anything refused is listed, because believing the model can see a
file it never received is the failure that wastes an afternoon.

### Chunking

Retrieval quality is mostly chunking quality, so the splitter is configurable and language-aware.

| Profile | Size / overlap | For |
|---|---|---|
| `faq` | 256 / 40 | Short self-contained entries |
| `docs` | 512 / 80 | Standard documentation |
| `longform` | 1024 / 150 | Books, specs, long reports |
| `code` | 400 / 0 | Declaration blocks |
| `auto` | per file | Default: prose profile for prose, code for code |

Sizes are in tokens, converted to characters at `rag_chars_per_token_prose` (4.0) and
`rag_chars_per_token_code` (3.0) - code is denser than prose.

Prose is split recursively on `\n\n`, then `\n`, then `". "`, then `" "`, taking the largest
separator that fits, so a chunk boundary lands between paragraphs rather than mid-sentence.
Overlap of 10–20% is the default because an answer that straddles a boundary would otherwise be
half-present in two chunks and retrievable from neither.

Code is split on declaration boundaries instead - top-level and once-nested `def`, `class`,
`object`, `function`, `fn`, `struct`, and so on - so a function arrives whole. Adjacent small
declarations are packed together rather than each becoming a tiny chunk, and a single
enormous one falls back to prose splitting. Overlap is zero for code: repeating the tail of one
function at the head of the next adds noise, not context.

### Search

Retrieval is hybrid: BM25 for exact tokens, plus a local dense encoder for morphology and
typos, fused by reciprocal rank. This section covers the lexical half and the parts common to
both; see [hybrid search](#hybrid-search-bm25-plus-a-local-encoder) for the dense half.

Ranking is BM25 over an inverted index: term frequency saturated by `k1`, document length
normalised by `b`, terms weighted by inverse document frequency.

Deliberate choices in the tokenizer, because the default ones are wrong for code:

- **Identifiers survive.** `ERR_4032`, `parse_request` and `utf8` are kept whole *and* emitted
  as their underscore-separated parts, so both `parse_request` and `request` find them.
- **`if`, `case` and `not` are not stopwords.** In a codebase they are content. IDF already
  de-weights a term that appears everywhere, so a hand-written stoplist mostly does damage.
- **The file path is indexed alongside its text**, so `AuthController` finds
  `AuthController.java` even when the body never names the class.

**No single file monopolises the results.** BM25 ranks chunks, not files, so a large file that
mentions a term in every chunk floods the head of the result set. Measured on this repository,
searching `Compactor.kt` returned five consecutive chunks of `CompactorTest.kt` and pushed
`Compactor.kt` itself to rank six - outside a top-five window, when the definition is almost
always what was wanted. `rag_max_per_file` (3) caps how many passages one file contributes.
It is a cap on *position*, not a filter: held-back passages are appended in score order, so a
question whose answer really does live in one file still gets that file's other chunks, just
after one passage from each rival.

The model can pass `path` to restrict a search to a subtree, and `top_k` to ask for more or
fewer than `rag_top_k` (8). Results are capped in total size - one tool call must not be able to
spend the whole context window - and say how many passages were withheld.

`/rag search <query>` runs the same search from the REPL, which is the quickest way to tell a
bad answer apart from a bad index.

### How the model knows to search

Two things reach the model, and the distinction matters.

**The tool schema** says `search` exists. It is added to the request's `tools` array only while
an index is active, so it appears the moment `/rag index` finishes and is withdrawn by
`/rag off` - the model is never offered a tool it can only fail to use. Its description tells
it what the tool is good at and, deliberately, what it is not:

> Matching is lexical: it finds words that actually appear, and tolerates a different word
> ending or a small misspelling, but it does NOT understand synonyms - searching
> `authentication` will not find text that only ever says `login`.

**A standing notice** in the system prompt says a corpus *exists*, which is the part a schema
cannot convey. Without it the model knows a searchable folder exists somewhere, but not that
your own material on the subject is one call away - so a question that could plausibly be
answered from general knowledge gets answered that way.

```
## Indexed material

A collection of the user's own files is indexed on this machine and searchable with
the `search` tool: 7 file(s), 80 passages.
It contains: the retrieval subsystem of kotllm: chunking, BM25, the local embedding
encoder and hybrid search.

Search it rather than answering from general knowledge whenever the question could
concern this material, and cite the paths the results give you. If a search returns
nothing relevant, say so instead of filling the gap from memory.
```

The notice costs a couple of dozen tokens per request, only while an index is active. It is
**not** the passages - that is `/rag inject on`, which costs far more.

#### `/rag describe` is what makes it work

```
> /rag describe the retrieval subsystem: chunking, BM25 and hybrid search
description set: the retrieval subsystem: chunking, BM25 and hybrid search

> /rag describe          # show it
> /rag describe -        # clear it
```

Or at setup time, so a scripted install can supply it:

```sh
kotllm --rag-index ./docs --rag-describe "our internal API documentation"
```

Without a description the notice says so plainly rather than inventing one, and the model has to
judge relevance from the results alone. With one, it can decide *before* spending a round trip.

**The description replaces the folder path, which is never sent.** An absolute path is noise the
model cannot act on - citations are relative to the index root anyway - and putting the local
filesystem layout into every request and every tool result buys nothing. Tool results name the
corpus by its description, falling back to the index name.

The description persists with the index, unlike the injection switch. That is not an
inconsistency: this is a label describing the material, so re-typing it every session would be
absurd, whereas injection spends context on every request and so has to be a deliberate
per-session act. Setting a description goes through a metadata-only write, so it does not
re-encode the corpus or rewrite a sidecar that can be tens of megabytes.

### Automatic injection, for this session only

`/rag inject on` prepends the top `rag_inject_top_k` (4) passages for the current message to
every request, as background context in the system prompt.

```
> /rag inject on
injection on for this session: up to 4 passage(s) will be added to every request.
Off again on exit, or on '/rag use', or with '/rag inject off'.
```

It is **off by default and cannot be turned on permanently.** Injection spends context on every
single request, and on a long conversation that is most of the window - so it is not a decision a
session from three weeks ago gets to make for this one. There is no config key for it and nothing
on disk records it. (An earlier version stored it per index; that key is now written by nothing
and read by nothing, so hand-editing it back in has no effect.)

The tool is the default path: the model retrieves when it decides it needs to, and pays only
then. Turn injection on while you are working through a small docs folder; it goes off by itself.

It switches off when:

- the session ends
- the active index changes (`/rag index`, `/rag use`, `/rag off`) - switching corpus is a
  deliberate change of context, and silently injecting from a newly activated folder is the
  unasked spend the default exists to prevent
- you run `/rag inject off`

`/rag reindex` keeps it on, because re-scanning the same folder is not a change of corpus.
`/rag` always reports the current state.

When it is on:

- The block is framed as **background, not instruction**, for the same reason memories are. A
  passage that merely shares keywords with the question is not an answer the user endorsed.
- Retrieval runs against the **last message the user actually wrote**, not the tool results, so
  the injected context cannot drift away from the question over a long tool loop.
- It is **never written into history** - it is rebuilt per request, like memories, so editing
  a file takes effect on the next message and the conversation does not accumulate stale copies.
- `rag_inject_max_chars` (8000) caps it, dropping whole passages rather than truncating one.
  A passage cut mid-sentence reads as if the file ended there.

### Hybrid search: BM25 plus a local encoder

Retrieval runs two rankers and fuses them. Both are built from code - there is no model file, no
service call, no API key, and no dependency.

| | Lexical half | Dense half |
|---|---|---|
| What it is | BM25 over an inverted index | character n-grams hashed into 2048 buckets, cosine |
| Good at | exact identifiers, error codes, rare terms | morphology, typos, fragments |
| Weighting | term frequency saturated by `k1`, length by `b`, terms by IDF | sub-linear term frequency, L2 normalised |
| Blind to | anything not spelled exactly | nothing lexical, but also nothing semantic |

They are fused by **reciprocal rank** - position only, never scores. A BM25 score of 5.4 and a
cosine of 0.31 share no scale, so adding or averaging them would require a normalisation that is
arbitrary and corpus-dependent. Ranks need none. A chunk both rankers like outranks one only a
single ranker loved.

**Why character n-grams and not hashed words.** A hashed bag of *words* would be a worse
reimplementation of BM25, and fusing two rankers that agree by construction adds nothing.
Character n-grams earn their place by matching what exact-token search structurally cannot.
Measured on this repository:

```
query "compaction"
  bm25 : examples/config.example.yaml, README.md, Runner.kt   <- misses the actual file
  dense: compact/Compactor.kt, examples/config.example.yaml   <- finds it, ranks it first

query "declineToolCals"   (a typo)
  bm25 : (nothing usable)
  dense: Runner.kt                                      <- reaches the definition
```

**It is not semantic, and does not pretend to be.** It knows nothing of synonyms: `car` will not
retrieve `automobile`, and no amount of hashing will make it. That needs co-occurrence
statistics over the corpus or a trained model, neither of which is here. There is a test
asserting `car`/`automobile` stay far apart, so nobody can later make the tool description
promise otherwise.

**Deliberately stateless.** No IDF table and no fitted state: `encode` is a pure function of the
text and the configured parameters. Document and query vectors are therefore consistent by
construction, where a persisted IDF table that drifted as files were added would silently encode
queries in a different space than the documents. It also makes incremental indexing correct with
no invalidation rules, and keeps the dense half complementary to the IDF-weighted lexical half
rather than nearly duplicate.

#### The similarity floor is what lets retrieval say "nothing"

Cosine between any two real texts is essentially never zero - `flibbertigibbet wombat` genuinely
shares `ibb`, `ert` and `omb` with real code. Without a floor the dense half returns its nearest
neighbours for a query of pure gibberish, and fusion then hands them confident ranks, which
invites the model to synthesise an answer from noise.

`rag_min_similarity` (0.20) is the floor, and it was measured rather than guessed. Over this
repository, nonsense queries peak at 0.17 cosine while wanted matches bottom out at 0.24:

```
wanted    declineToolCalls 0.33   Compactor.kt    0.37   compaction 0.33   tokenizer 0.24
nonsense  (gibberish)      0.17   (gibberish)     0.14   (gibberish) 0.15
```

That is a margin of about 0.07, which is why it is a configurable knob and not a constant. It
fails safe in both directions: too high loses dense recall on typos, too low admits weak
matches, and BM25 answers on its own either way. A live test asserts the separation still holds,
so an encoder change that quietly erodes it fails the build.

Raising `rag_embedding_dims` does *not* widen the margin - the sweep put it at +0.056 (512),
+0.067 (2048), +0.047 (8192). The overlap is real shared character n-grams, not hash collisions,
which is why 2048 is the default rather than something larger.

#### Index size and speed

One vector costs `rag_embedding_dims * 4` bytes, so 8KB per chunk at the default. Vectors live
in a binary `vectors.f32` sidecar rather than JSON, which would be roughly five times larger and
slower to parse than the search it enables.

Search is a brute-force scan, which is the right algorithm at this scale rather than a shortcut:
**1.5ms per hybrid query over 742 chunks** of this repository, measured by a live test that fails
if it regresses past 150ms. An approximate nearest-neighbour index would add a dependency, a
tuning surface and approximation to save time that is not being spent.

#### Staleness is detected, never tolerated

Vectors from a different encoder have the right shape and no meaning - they search successfully
and rank nonsense, which is the one failure here nobody could diagnose from the output. So the
encoder's identity and parameters are recorded (`hashing-v1:d2048:g3-5`) and checked on load,
along with the dimension count and the row count against the chunk count. Any mismatch refuses
the vectors, says why, and tells you to reindex:

```
> /rag
rag: 'aichat.kotlin' active
  retrieval  bm25 only
  vectors    unavailable -- built by 'hashing-v1:d256:g3-5' but the configured
             encoder is 'hashing-v1:d2048:g3-5'; run '/rag reindex' to rebuild
```

Retrieval degrades to BM25 alone rather than failing, and `/rag` says so - silent degradation
would look exactly like a quality regression with no visible cause. Setting
`rag_embedding: none` removes the sidecar on the next index rather than leaving it to be misread
later.

### What is not built

**Semantic retrieval.** Synonyms and paraphrase need distributional statistics (LSA over the
corpus co-occurrence matrix, computable locally) or a trained model. Neither is here.

There is no reranker stage, and no approximate nearest-neighbour index.

---

## Background turns

Ask something slow without blocking the prompt.

```
> /bg summarise every Kotlin file under src/main and list the three most complex
[1] started in the background; /bg to check

> what is 2+2?
4

  · [1] done in 47.2s, 1204 out: summarise every Kotlin file under src/main...
  The three most complex files are Runner.kt, ...

> /bg
[1] done in 47.2s, 1204 out: summarise every Kotlin file under src/main...
```

`/bg <prompt>` starts one; `/bg` lists them. Notices print between prompts with the reply, so an
answer is never lost.

- The session is **forked**: the background turn gets a copy of your history for context, but its
  reply lands in its own timeline and never mutates your conversation.
- The **rate limiter is shared**, so two concurrent turns can't jointly exceed your provider
  limit.
- The **interpreter is not shared**, so a background `groovy_eval` can't alter what your
  foreground REPL sees.
- **Tool calls in a background turn are refused under `tool_approval: confirm`**, because there's
  nobody to ask. `/bg` tells you when it starts a turn that will hit this. Use `auto` or an
  allowlist entry.

No attach, tail, or per-task kill - background turns are deliberately minimal.

---

## Context compaction

A long conversation eventually outgrows the model window, because every turn re-sends the whole
history and tool results are large. kotllm compacts automatically, and tells you when it does:

```
> ...twenty turns later...
⋯ compacted context: 3 exchange(s) summarised into 447 chars (9 -> 4 messages)
```

The check runs against the **projected** size of the request about to be sent, not the last one
measured - reacting to the last prompt is always a turn late, and a single tool result can
overshoot in the gap.

Two mechanisms, cheapest first:

1. **Old tool results are trimmed** to `compact_tool_result_max_chars`. No request at all, and
    it attacks the real source of growth. Recent results are never touched - the model may still
    be reasoning about them.
2. **Older exchanges are summarised** into one recap message by a side call, with the most recent
    `compact_keep_recent` exchanges kept verbatim. The summary gives the model history; the
    verbatim tail gives it state, and without state it cannot continue mid-task.

```yaml
compact_enabled: true
compact_threshold_percent: 70     # of the model window
compact_keep_recent: 3            # exchanges kept verbatim
compact_tool_result_max_chars: 2000
compact_summary_max_chars: 2000
compress_threshold: null          # aichat-compatible absolute trigger, in tokens
```

`/compact` forces one immediately, regardless of the threshold.

### Why 70% and not 95%

The compaction call itself sends the history and needs output room for the summary. Wait until
the window is nearly full and you can no longer *afford* the one request that would fix it. 70%
also sits below where a chat-only tool would put it, because one tool result can add thousands of
tokens between checks.

### Things it is careful about

- **The history is never split inside an exchange.** An assistant message carrying `tool_calls`
  and the tool results answering it always move or vanish together, because the endpoint requires
  exactly one result per `tool_call_id` and a split between them produces a malformed request.
- **A compaction that does not shrink the context is rejected.** Asked to summarise a few short
  exchanges, a model will cheerfully produce more prose than it replaced; that is checked rather
  than assumed, and the history is left alone when it happens.
- **Failure is never destructive.** A failed or empty summary returns the original history
  untouched. Any trimming already done is kept, being independently valid.
- **It will not thrash.** Once over the threshold, nothing is retried until the history has
  actually grown.
- **The summarising request is accounted for.** It passes through the rate limiter and lands in
  the ledger, because it is a real request with real tokens; leaving it out would make your
  token numbers lie.
- **Compactions are counted**, and shown in `/status` and `/info`. Summaries of summaries
  degrade, so a session on its fifth compaction has lost real detail.

### A note on small windows

This is designed for realistic windows - 64k and up, and a Databricks Qwen 3.5 endpoint declares
261k. On a deliberately tiny window it cannot help, because the fixed overhead that compaction
*cannot* touch (system prompt, tool schemas, memories) is around 1,100–1,500 tokens on its own.
kotllm detects that case and says so once rather than compacting pointlessly:

```
kotllm: the system prompt and tool schemas alone are ~1139 tokens, over the compaction
        threshold for a 1400-token window. Compaction cannot help; reduce tools,
        memories, or use a larger model.
```

So don't tune `max_input_tokens` down to a small number to "test" compaction - you'll hit that
path instead. Lower `compress_threshold` if you want it to engage sooner on a real window.

## Metrics

`/status` shows a transient overlay on the alternate screen - the one `less` and `vim` use.
Any keypress dismisses it and your screen comes back exactly as it was, transcript and
scrollback included. Where the terminal has no alternate screen the report is printed
normally.

```
session         14s, 3 requests
model           databricks-qwen35-122b-a10b

tokens          3,004 in / 329 out / 3,333 total
context         1,828 / 261,000  [░░░░░░░░░░░░░░░░░░░░] 1%
prompt growth   ▃▅█  (1,176 → 1,828)
output per turn █▂▄

latency         avg 1.7s, last 0.8s
first token     avg 0.8s
throughput      364 tok/s output (last turn)

rate window     rolling 10s
  input         76 / 100  [███████████████░░░░░] 76%
  frees up in   1.0s
  throttled     1x, 7.0s waited
  estimator     2.96 chars/token (3 samples)

tool calls      1 in 3.4s
tool             n    total      avg  slowest    ~tok  notes
groovy_eval      1     3.4s     3.4s     3.4s     263  -
  fed back      726 input tokens (measured)
  results       560 chars, ~263 tokens (estimated)

reasoning       13,919 chars across 3 requests

  req        in      out    think     time  tools
    1     1,176      321      550     2.7s  groovy_eval
    2     1,828        8        0     0.8s  -
```

Notes on reading it honestly:

- **Context is what the next prompt will cost**, not the session total. Every turn re-sends the
  history, so the total passes your window size long before the window is actually full. The
  line is marked `(projected)` whenever the endpoint has not counted that exact payload - which
  after any reply, `/empty` or `/compact` it has not.
- **Per-tool-call token figures are estimates** (`~tok`) - the endpoint reports no per-call
  counts. `fed back` *is* exact: it's the real growth in `prompt_tokens` across the request that
  followed a tool call.
- **Reasoning is reported in characters**, because `usage` gives no reasoning breakdown. On
  reasoning models it often dominates.
- Approval wait is tracked separately from tool execution time - a slow human isn't a slow tool.
- `requests`, not "turns": one message can produce several requests once tools run.

---

## Rate limiting

Client-side throttling over a rolling window, so hitting a provider limit becomes a visible
pause rather than a 429 that wasted a round trip.

```yaml
rate_limit:
  input_tokens_per_minute: 1000000
  window_seconds: 60
  on_limit: wait        # or `error` to fail fast
```

Shorthand for the common case: `rate_limit: 1000000`. Per-client overrides work too, and are
usually what you want, since a limit belongs to an endpoint rather than to kotllm.

When it engages you see a countdown rather than silence:

```
⋯ rate limit: waiting 6.9s (94/100 input tokens used in the last 10s)
```

How input and output differ, because it's inherent and not a shortcut:

- **Input is checked before sending**, from a self-calibrating estimate - true `prompt_tokens`
  only arrives with the response, so kotllm estimates, then corrects the window with the real
  number. The estimator learns your endpoint's chars-per-token ratio (it settles around 2.96 on
  Qwen 3.5-122b).
- **Output can only be counted afterwards**, so `output_tokens_per_minute` and
  `total_tokens_per_minute` throttle the *next* request, never the current one.
- A request whose estimate exceeds the entire budget is **sent anyway** - waiting cannot help -
  with a warning.
- `Retry-After` from the provider overrides kotllm's own backoff.

---

## Reasoning models

Reasoning output is handled as a separate channel:

```yaml
reasoning: collapse   # show | collapse | hide
```

| Mode | Behaviour |
|---|---|
| `show` | Print it dimmed, inline before the answer |
| `collapse` | A live `⋯ thinking (N chars)` line, then erased (default) |
| `hide` | Discard it |

Reasoning is **never replayed** to the endpoint on later turns - it would burn context for no
benefit.

> **`max_tokens` defaults to unset, deliberately.** On a reasoning model a small cap gets
> consumed by the reasoning block and the reply comes back *empty* with `finish_reason=length`.
> Measured: "Count from 1 to 3, digits only." consumed a full 2048-token budget and produced
> zero visible text. kotllm detects this and says so:
>
> ```
> kotllm: the model spent its whole output budget reasoning and returned no answer.
>         Raise max_tokens, or re-ask more narrowly.
> ```

---

## Reference

### REPL commands

Commands start with `/`.

| Command | Description |
|---|---|
| `/help` | Show the command list |
| `/info` | Endpoint, model, tools and runtime settings |
| `/status` | Usage metrics as a transient overlay |
| `/vars` | The `{{ }}` harness variables with live values |
| `/memory` | Show injected memories; `/memory add <note>` to append |
| `/rag` | Index a folder and search it; `/rag` alone shows what is active |
| `/bg` | List background turns; `/bg <prompt>` to start one |
| `/model` | Show or switch model (`/model client:name`) |
| `/prompt` | Show, set or clear the system prompt (`/prompt -` clears); `/prompt full` shows the injected sections too |
| `/set` | Change a runtime setting |
| `/file` | Send files, directories, URLs or `` `commands` `` |
| `/compact` | Summarise older context now, keeping recent turns |
| `/empty` | Clear the conversation context |
| `/regenerate` | Re-send the last input |
| `/exit` | Leave |

`/set` accepts: `stream`, `highlight`, `reasoning`, `temperature`, `top_p`, `max_tokens`,
`save`, `max_tool_iterations`, `tool_approval`.

### Seeing the assembled system prompt

`/prompt` reports the prompt *you* set.
That is a small part of what is actually sent.
Eight sections are concatenated on every request, and seven of them come from the harness:

1. the tool manifest -- what the model may run and import
2. the skills listing
3. your memories
4. your own tools, when `user_tools` is on
5. the standing notice that a RAG index exists
6. the retrieved passages, only while `/rag inject on`
7. the task checklist
8. the system prompt you set, last, so it has the final word

`/prompt full` switches `/prompt` to reporting all of it, verbatim, with a footer giving the
split between what the harness injected and what you wrote.
`/prompt default` switches back.

```
> /prompt full
## Tools
...
be terse
view: full -- 6114 chars: 6106 injected, 8 yours. Recorded to the session file every turn.
```

Two things to know:

- **`full` and `default` are reserved words after `/prompt`.** A system prompt of exactly one of
  those two words has to be set with `--prompt` at startup instead. Anything longer -- including
  `/prompt full sentences only` -- is still treated as a prompt.
- **While the view is on, the assembled prompt is written to the session file** on every turn, as
  a `{"kind":"prompt", ...}` row carrying both layers. It is not recorded when `save: false`, and
  nothing on disk turns the view on -- it is session-scoped, the same rule as `/rag inject`. See
  [docs/sessions.md](docs/sessions.md).

`--dry-run` remains the way to see the whole request, tool schemas and history included, rather
than the system prompt alone.

### Command-line options

```
-f, --file <SRC>      Include a file, directory, URL or `command`. Repeatable.
                      Also accepts %% for the last reply.
-m, --model <ID>      Model to use, as `model` or `client:model`
    --prompt <TEXT>   System prompt for this run
    --reasoning <M>   Reasoning display: show, collapse, hide
    --config <PATH>   Config file to load
    --rag <NAME>      Retrieve from this index (see /rag index)
    --rag-index <DIR> Index a folder, then exit unless TEXT is given
    --rag-describe <T> What the indexed corpus is, told to the model
-S, --no-stream       Wait for the whole reply instead of streaming
    --dry-run         Print the request JSON without sending it
    --info            Show resolved configuration and exit
    --list-models     List configured models and exit
-h, --help            Print help
-V, --version         Print version
```

### Harness variable reference

| Group | Variables |
|---|---|
| Endpoint | `model`, `model.id`, `endpoint`, `client`, `client.type` |
| Context | `context.limit`, `context.used`, `context.percent` |
| Usage | `tokens.in`, `tokens.out`, `tokens.total`, `requests`, `stats` |
| Session | `uptime`, `session.started`, `tools`, `memory.files`, `memory.chars` |
| Settings | `reasoning`, `max_tokens`, `temperature`, `config.path` |
| Machine | `pwd`, `home`, `user`, `host`, `os`, `arch`, `shell`, `cmdline` |
| Versions | `kotllm`, `groovy`, `java` |
| Time | `now`, `date`, `time`, `epoch_ms` |
| Rate limit | `rate.window`, `rate.used`, `rate.percent` - only when one is configured |

### Configuration reference

[`examples/config.example.yaml`](examples/config.example.yaml) documents every key. Summary:

| Key | Default | Purpose |
|---|---|---|
| `model` | - | Model id, `model` or `client:model` |
| `stream` | `true` | Stream tokens as they arrive |
| `max_tokens` | unset | Output cap; leaving unset is recommended |
| `temperature`, `top_p` | unset | Sampling |
| `reasoning` | `collapse` | `show` / `collapse` / `hide` |
| `highlight` | `true` | Markdown highlighting (false = raw token streaming) |
| `template` | `true` | Enables both `{{ }}` and `<% %>` |
| `function_calling` | `false` | Master tool switch |
| `aliases` | `{}` | Short names for models: `gemma: local:gemma4:26b` |
| `use_tools` | unset | `all`, `none`, or a comma-separated list |
| `tools_exclude` | `[]` | Names or `*` patterns withheld from what `use_tools` picked |
| `tool_approval` | `confirm` | `confirm` / `auto` / `deny` |
| `exec_allowlist` | `[]` | Anchored regexes never prompted for |
| `eval_timeout` | `30` | `groovy_eval` interrupt, seconds |
| `exec_timeout` | `120` | `exec` kill, seconds |
| `max_tool_iterations` | `12` | Tool round-trip safety net |
| `classpath_append` | `[]` | Extra jars for `groovy_eval` |
| `compact_enabled` | `true` | Automatic context compaction |
| `compact_threshold_percent` | `70` | Trigger, as a share of the model window |
| `compress_threshold` | unset | aichat-compatible absolute trigger, in tokens |
| `compact_keep_recent` | `3` | Exchanges kept verbatim |
| `compact_min_head` | `2` | Below this, summarising is not worth a request |
| `compact_trim_tool_results` | `true` | Trim old tool results first |
| `compact_tool_result_max_chars` | `2000` | Trim target |
| `compact_summary_max_chars` | `2000` | Hard cap on the recap |
| `summarize_prompt` | built-in | Instruction for the summarising call |
| `summary_prompt` | built-in | Prefix when the recap re-enters the chat |
| `memory_enabled` | `true` | Inject memories |
| `memory_max_chars` | `8000` | Memory budget |
| `memory_project_file` | `KOTLLM.md` | Project memory filename |
| `input_max_file_chars` | `100000` | Per-file cap for `-f` |
| `input_max_total_chars` | `400000` | Per-invocation cap |
| `input_max_files` | `200` | Directory walk cap |
| `document_loaders` | `{}` | ext → conversion command |
| `rag_enabled` | `true` | Enable retrieval |
| `rag_profile` | `auto` | `faq`, `docs`, `longform`, `code`, `auto` |
| `rag_chunk_size` | profile | Chunk size in tokens |
| `rag_chunk_overlap` | profile | Overlap in tokens |
| `rag_top_k` | `8` | Passages per `search` call |
| `rag_max_per_file` | `3` | Cap on passages from one file (0 = off) |
| `rag_inject_top_k` | `4` | Passages when injection is on (`/rag inject on`; no key enables it) |
| `rag_inject_max_chars` | `8000` | Cap on the injected block |
| `rag_bm25_k1` | `1.2` | Term-frequency saturation |
| `rag_bm25_b` | `0.75` | Length normalisation |
| `rag_rrf_k` | `60` | Rank-fusion constant |
| `rag_embedding` | `hashing` | Dense encoder: `hashing` or `none` |
| `rag_embedding_dims` | `2048` | Vector size; 4 bytes each per chunk |
| `rag_min_similarity` | `0.20` | Cosine floor for a dense hit |
| `rag_char_ngram_min` | `3` | Shortest character n-gram |
| `rag_char_ngram_max` | `5` | Longest character n-gram |
| `rag_max_file_chars` | `400000` | Per-file cap while indexing |
| `rag_extra_extensions` | `[]` | Extra extensions to index |
| `rag_chars_per_token_prose` | `4.0` | Token-to-char ratio, prose |
| `rag_chars_per_token_code` | `3.0` | Token-to-char ratio, code |
| `connect_timeout` | `15` | Seconds |
| `max_retries` | `3` | Retries for 408/429/5xx |
| `rate_limit` | unset | See [Rate limiting](#rate-limiting) |
| `pricing_enabled` | `true` | Cost estimates in `/status` and `/info` |
| `dbu_rate` | `0.07` | USD per DBU-hour, for the Databricks half of the table |
| `pricing` | unset | Per-model overrides, layered over the shipped table |
| `clients` | - | Endpoint list |

Environment: `KOTLLM_CONFIG_DIR`, `KOTLLM_CLASSPATH`, `KOTLLM_UNICODE`.

---

## Not implemented yet

Stated plainly so you don't go looking. These flags are rejected with a message rather than
silently ignored:

| Feature | Flag |
|---|---|
| Roles | `-r`, `--role` |
| Named, persisted sessions | `-s`, `--session` |
| Macros | `--macro` |
| Shell assistant / code-only output | `-e`, `-c` |
| Agents | `--agent` |
| HTTP server, playground | `--serve` |

**Semantic retrieval.** `/rag` is hybrid lexical search - BM25 plus a local character n-gram
encoder - with no model file or service call. It handles morphology and typos but not synonyms;
see [what is not built](#what-is-not-built).

**Azure AI Foundry** is declared in config (`type: azure-foundry`) but not implemented - it
reports a clear error rather than half-working. The client SPI is clean, so it's a small
addition.

---

## Development

```sh
mvn clean package      # build + unit tests + fat jar
mvn test               # unit tests only
mvn -Plive test        # also runs tests that call a live endpoint
```

**Bump the version in `pom.xml` on every change worth telling apart.** One place, filtered into
the jar, read back by `--version`, the banner and `/info`. Put it in the commit message too.

### The prompt budget is a hard rule

**Every character in a tool `description`, in a parameter `description`, and in
`src/main/resources/chat.md` is paid for on every single request, forever.** Not once per
session -- once per turn, for the life of the conversation, whether or not the tool is ever
called. This is the most expensive text in the project and it is the easiest to write carelessly.

The rules, in order of how often they are broken:

1. **Do not restate what a schema already says.** If the model is holding an `exec` schema, a
   sentence telling it there is a shell is paid for twice. The system prompt is for what the
   schemas cannot express: state that persists, the classpath, the line-number trap.
2. **Do not explain, justify, or motivate.** "They are as real as the built-in tools" is arguing
   with a reader who was not objecting. "Use to orient yourself in an unfamiliar tree" is a
   sentence about a tool called `ls`.
3. **Do not name a thing twice.** A parameter called `file_path` does not need the description
   "Path to the file to count, absolute or relative to the working directory."
4. **Keep only what is surprising.** `edit` refuses an ambiguous match; `read_to_context` numbers
   its lines and that prefix must be stripped; a bare assignment persists and `def` does not.
   That is the content worth paying for. Everything else is the tool's name.
5. **Enums and defaults are already in the schema.** Do not restate them in prose.

Measure before and after, do not estimate — **and measure against the defaults**:

```sh
mkdir -p /tmp/probe && cat > /tmp/probe/config.yaml <<'EOF'
model: probe-model
save: false
stream: false
function_calling: true
use_tools: all
clients:
- type: openai-compatible
  name: probe
  api_base: http://127.0.0.1:9/v1
  api_key: none
  models:
  - name: probe-model
    max_input_tokens: 128000
    supports_function_calling: true
EOF
KOTLLM_CONFIG_DIR=/tmp/probe java -jar target/kotllm.jar --dry-run "hi" > /tmp/req.json
python3 -c "
import json; d=json.load(open('/tmp/req.json'))
s=[m for m in d['messages'] if m['role']=='system'][0]['content']
t=json.dumps(d.get('tools',[]),separators=(',',':'))
print('prompt %d chars, schemas %d chars, total %d' % (len(s), len(t), len(s)+len(t)))"
```

**Do not add tool-family keys to that config.** Five families are off by default and must stay
off when measuring, or the number is not the number a user sees:

| Key | Default | Costs when on |
|---|---|---|
| `java_eval` | `false` | `java_eval`, plus `java` in three `language` enums |
| `session_transfer` | `false` | `session_transfer` |
| `task_tools` | `false` | `create_tasks`, `update_task` |
| `memory_tools` | `false` | `add_or_update_memory`, `delete_memory`, `read_memory` |
| `web_tools` | `false` | `fetch`, `web_search` |

Only `file_tools` and `user_tools` default to `true`. Turning all five on took a 12-tool request
from 6,584 to 11,964 characters — switching them on to "measure the worst case" produces a
figure that describes nobody's configuration and invites cuts to the wrong text.

Some tools are also absent from a `--dry-run` for structural reasons, and that is correct rather
than a bug: `ask_user` needs a terminal, `search_chats`/`view_chat` need prior sessions,
`read_memory` is offered only while something is over the memory budget, and `search` only while
an index is active. A tool whose every call would be refused still costs schema tokens.

Inside the REPL, `/prompt full` weighs both halves and `/status` reports what was actually
billed. Adding a paragraph is a decision to spend tokens on every future turn; make it
deliberately.

### Status

This tree is a Kotlin port of an earlier Scala implementation. The port is complete and the
suite is green: 1,227 tests across every package.

Live tests carry `@Tag("Live")`, which is what keeps them out of the default run.
They read credentials from the config that `KOTLLM_CONFIG_DIR` points at, never from the
repository, and cancel rather than fail when no usable config is present.

Architecture, design decisions and endpoint wire-format findings are documented in
[`docs/`](docs/) -- one file per subsystem, indexed in [`docs/README.md`](docs/README.md).
[`CONVENTIONS.md`](CONVENTIONS.md) is the Scala-to-Kotlin rule set the port was held to, and
[`PORTING.md`](PORTING.md) records what the port measured and what it cost. Both describe the
host language; the language the *model* writes is Groovy, and has been since the interpreter
was swapped.

**Never commit an `api_key`.** Keep your live config outside the repo, or in a gitignored
directory.
