# kotllm control flow

Sequence and call-flow diagrams for every high-level feature, drawn from the code rather than
from the design notes. `CLAUDE.md` says *why* things are the way they are; this says *what calls
what*. Written against the Kotlin port (`aichat.kotlin`), which began as a file-for-file mirror of
a Scala tree (`aichat.scala`, 1.34.3-SNAPSHOT) under `src/main/kotlin/kotllm/` -- every class and
method named below was checked against the source, not assumed from the original.

The one exception, called out where it matters: code the LLM's own turns run through --
`groovy_eval`, agent files, user tool files, skills, `<% %>` template bodies -- is compiled
and executed by `groovy.lang.GroovyShell`. Everything else in this document is kotllm's own
driving code, and is Kotlin.

## How to read this

```
Caller              Callee                  Notes
  |                   |
  |---- method() ---->|                     a call
  |<--- result -------|                     a return worth naming
  |                   |
  |--[ condition ]--->|                     a branch
  |    ...            |                     omitted detail
  +-- loop -----------+                     repeats
```

Participants are classes, not files. Names are exactly what is in the source, so
`grep -rn "methodName" src/main` lands on them. Packages are dropped except where two classes
share a name (`repl/Status` is JLine-adjacent chrome; `util/TokenLedger` holds the metrics). In
diagrams below, a bare `Status.xxx` is always JLine's `org.jline.utils.Status`, never kotllm's own
`repl/Status.kt` (the `/status` report text) -- `Chrome.kt` imports the JLine class under the
alias `JLineStatus` to keep the two apart, since both are visible unqualified in the same package.

---

## The cast

| Class | File | Job |
| --- | --- | --- |
| `Main` (top-level `fun main` delegates to `object Main`) | `Main.kt` | Process entry; decides single-hit vs REPL vs one-shot report |
| `Cli` | `Cli.kt` | Hand-rolled argument parser, stdin pickup, usage text |
| `Runner` | `Runner.kt`, `RunnerEngines.kt` | **Executes one turn**: request assembly, tool loop, metrics, rendering |
| `Config` | `config/Config.kt` | YAML load, defaults, model resolution |
| `Session` | `config/Session.kt` | Message history, token ledger, compaction bookkeeping |
| `Client` / `DatabricksClient` / `AzureFoundryClient` | `client/` | HTTP transport, blocking and SSE |
| `ChatRequest` / `ChatResponse` / `Message` / `ToolDef` | `client/Wire.kt` | Request and response codecs (no `Wire` class; the file holds these) |
| `Content` / `Part` / `Chunk` / `StreamAssembler` | `client/` | Typed content parts, chunk decoding, delta assembly |
| `Sse` / `Retry` | `client/` | Event framing; backoff for cold starts and 429s |
| `Repl` | `repl/Repl.kt` | JLine loop, fences, Ctrl-C, transcript framing |
| `Chrome` / `Prompt` | `repl/` | Pinned rows, rules, footer, erase and overlay mechanics |
| `Commands` (mixes in `CommandReports`) | `repl/Commands.kt`, `repl/CommandReports.kt` | Slash-command table and dispatch, plus the `/info` / `/help` / `/vars` / `--list-*` report text |
| `Status` | `repl/Status.kt` | The `/status` report text |
| `ToolRegistry` / `Tool` / `Tools` | `tools/` | Descriptors, dispatch, the built-ins |
| `FileTools` | `tools/FileTools.kt` | `read`, `write`, `edit`, `glob`, `grep`, `ls` |
| `Skill` / `Skills` | `skill/` | `SKILL.md` parsing, discovery, the three tiers |
| `Skips` | `util/Skips.kt` | The one skip-dir and binary-extension set |
| `Approval` / `Prompter` / `ReplPrompter` | `tools/`, `repl/` | Confirm policy and the terminal prompt |
| `GroovyEngine` | `engine/GroovyEngine.kt` | In-process `GroovyShell`, the shared `Binding` that is the session, output capture, timeout |
| `Manifest` / `Classpath` | `engine/` | The library list injected into the system prompt |
| `Compactor` | `compact/Compactor.kt` | Trim tool results, summarise the head |
| `Memory` | `memory/Memory.kt` | Markdown notes in, notes out |
| `PromptApi` / `ChatAgent` | `prompt/` | The `prompt.*` surface an agent file sees, and the default `agents/chat.md` it renders (see section 6) |
| `RagStore` / `RagIndex` / `Rag` | `rag/` | Named indexes, persistence, indexing rules |
| `Bm25` / `Fusion` / `HashingEmbedder` / `VectorIndex` / `Chunker` | `rag/` | Retrieval internals |
| `InputLoader` / `Loaders` | `input/` | `-f` sources, `document_loaders` conversion |
| `Fetch` / `Page` | `input/Fetch.kt` | One GET or local read: gzip, content-type, HTML conversion |
| `Html` | `input/Html.kt` | HTML to markdown; `plainText` for snippets |
| `Search` / `SearchSource` / `Engine` | `web/Search.kt` | Keyless web and domain search |
| `Tls` | `util/Tls.kt` | Union trust manager, for TLS-inspecting proxies |
| `Template` / `TemplateEngine` / `Vars` / `HarnessVars` | `template/` | `<% %>` and `{{ }}` expansion |
| `StreamRender` / `Turn` / `Gutter` / `ToolRender` / `Markdown` / `Spinner` / `Throttle` | `render/` | Everything printed |
| `BackgroundRegistry` / `Spawner` | `background/` | Background turns |
| `TokenLedger` / `TokenEstimator` / `RateLimiter` / `Glyphs` | `util/` | Metrics, estimation, throttling, glyph fallbacks |
| `BuildInfo` | `util/BuildInfo.kt` | Version and build timestamp from the filtered resource |

`task/Checklist.kt` is unrelated to the background-turn machinery above -- it is the visible
to-do-list tool (`TaskStatus`, `ChecklistItem`), not a registry of in-flight turns.

---

## 1. Startup and dispatch

```
Main            Cli        Config      Runner       Client        RagStore     Repl
 |               |           |            |            |              |          |
 |--parse(args)->|           |            |            |              |          |
 |               |-- reads piped stdin, rejects not-yet-implemented flags        |
 |<--Cli---------|           |            |            |              |          |
 |                           |            |            |              |          |
 |--load(configPath)-------->|            |            |              |          |
 |                           |-- Yaml.loadFile, Conf accessors, sourcePath       |
 |<--Outcome<Config>---------|            |            |              |          |
 |                                        |            |              |          |
 |--applyOverrides(cli) ------ --no-stream, --reasoning win for this run only    |
 |                                        |            |              |          |
 |--build(config, model, prompt, prompter, interactive)>|              |          |
 |                           |<--resolveModel(id)------|              |          |
 |                           |--ModelTarget----------->|              |          |
 |                                        |--forTarget(target)------->|          |
 |                                        |    Provider.infer(kind, api_base)    |
 |                                        |<--DatabricksClient | AzureFoundry----|
 |<--Runner-------------------------------|            |              |          |
 |                                                                    |          |
 |--dispatch(runner, cli)                                             |          |
 |   |--[ --info ]------> Commands.infoText(runner)   -> stdout, exit 0          |
 |   |--[ --list-models ]-> Config.listModels         -> stdout, exit 0          |
 |   |--applyRagFlags ------------------------------->|--indexFolder / activate  |
 |   |                                               |    / setDescription      |
 |   |--composeInput -----> InputLoader.load(files)   (only when -f was given)   |
 |   |--[ --dry-run ]----> runner.expand + runner.dryRun -> request JSON, exit   |
 |   |--[ TEXT ]---------> singleHit(runner, text)                               |
 |   |--[ indexed only ]-> exit 0   (a setup flag must not open a prompt)        |
 |   +--[ nothing ]------> new Repl(runner).run() ------------------------------>|
 |                                                                              |
 +--catch LinkageError -> staleJar(e)   "the jar was rebuilt under this session"
```

`Runner.build` is the only place a `Runner` is constructed in production; tests go through
`TestIsolation.isolate` first so the real config directory cannot leak in.

---

## 2. The REPL loop

```
Repl                    Chrome              ReplLineReader/JLine     Commands
 |                        |                        |                    |
 |--refresh(runner)------>|--Status.update([rule, footer])              |
 |--dockBottom(rowsAbove)>|--screenful of newlines, then cursor_address  |
 |--whilePrinting(notices)|--suspend, print, restore                    |
 |                        |                        |                    |
+-- loop ----------------------------------------------------------------------+
 |                        |                        |                    |
 |--reportFinishedTurns-->|  (background notices, see 10)               |
 |--refresh(runner)------>|--Prompt.footer(runner, width) -> Status      |
 |--readInput()-------------------------------->readLine(Prompt.input)   |
 |<--Input.Typed(text, echoRows) | Input.Fenced(text, echoRows)---------|
 |--eraseRows(input.echoRows)---->|   clears the editor's echo only      |
 |                        |                        |                    |
 |--[ blank ]--> nothing (the erase already removed the empty prompt)    |
 |--[ Fenced ]-> send(body)              never reinterpreted as a command
 |--[ Typed ]--> Commands.parse(line)----------------------------------->|
 |                  |--[ non-null ]--> runCommand(line, name, args) --->|
 |                  +--[ null ]------> send(line)                        |
+------------------------------------------------------------------------------+
 |
 +--close(): bgTurns.cancelAll -> chrome.close -> runner.close -> history.save -> terminal.close
```

**Chrome is the only thing allowed to touch the bottom rows.** Every print site is inside
`chrome.suspend()/restore()` or `chrome.whilePrinting { }`.

---

## 3. One turn, streaming (the core path)

```
Repl        Runner       Compactor  RateLimiter  DatabricksClient  Sse/Chunk  StreamRender  TokenLedger
 |            |              |           |              |             |            |            |
 |--suspend (Chrome)         |           |              |             |            |            |
 |--println(Turn.question)   |           |              |             |            |            |
 |--expand(raw)->|  Vars.expand -> Template.hasTags -> TemplateEngine.render        |            |
 |<--text--------|           |           |              |             |            |            |
 |--ask(text, abort)-------->|           |              |             |            |            |
 |            |--session.append(Message.user(text))     |             |            |            |
 |            |--converse(abort, 0)                     |             |            |            |
 |            |   |--sendOnce(abort)                    |             |            |            |
 |            |   |    |--autoCompactIfNeeded---------->| (see 5)     |            |            |
 |            |   |    |--buildRequest(null, stream)    |             |            |            |
 |            |   |    |     +--messagesToSend -> systemPrompt(...)   |  (see 6)   |            |
 |            |   |    |--estimator.estimate(json.length)             |            |            |
 |            |   |    |--admit(estimated, abort)------>|  verdict/Wait loop (see 4)            |
 |            |   |    |--limiter.reserve(estimated)--->|             |            |            |
 |            |   |    |--askStreaming(abort)                         |            |            |
 |            |   |    |     |--Spinner.start                         |            |            |
 |            |   |    |     |--chatStream(req, continue)------------>|            |            |
 |            |   |    |     |                     Retry { http.send(ofLines) }     |            |
 |            |   |    |     |                          |--foreach--->|            |            |
 |            |   |    |     |                          |   Chunk.decode(payload)  |            |
 |            |   |    |     |<--StreamEvent------------|            |            |            |
 |            |   |    |     |--StreamAssembler.accept(e)             |            |            |
 |            |   |    |     |--onEvent(e)------------------------------------------>|          |
 |            |   |    |     |        TextDelta -> MarkdownLineRenderer -> gutter -> stdout     |
 |            |   |    |     |        ReasoningDelta -> collapsed "thinking" line on stderr    |
 |            |   |    |     |        UsageUpdate -> replaces (cumulative per chunk)           |
 |            |   |    |     |--render.finish()                       |            |            |
 |            |   |    |<--ChatResponse, ttft ------------------------|            |            |
 |            |   |    |--estimator.calibrate(chars, promptTokens)    |            |            |
 |            |   |    |--limiter.settle(id, actualIn, actualOut)---->|            |            |
 |            |   |    |--recordMetrics(TurnMetrics)------------------------------------------->|
 |            |   |    |--warnIfDegenerate(resp)   empty text + reasoning, or finish=length    |
 |            |   |--session.appendResponse(resp)   strips reasoning parts                      |
 |            |   +--[ resp.toolCalls.isEmpty ]--> done                |            |            |
 |            |   +--[ else ]--------------------> tool loop (see 7)   |            |            |
 |<--Outcome<ChatResponse>--------|              |              |     |            |            |
 |--println(Turn.processed(elapsed, ledgerDelta))  |              |     |            |            |
 |--restore (Chrome), reinstate SIGINT handler     |              |     |            |            |
```

Failure paths inside `sendOnce`:

```
ChatFailure  -> limiter.settle(id, estimated, 0)   the provider counted it
IOException  -> limiter.release(id)                it never arrived
Outcome.Err  -> ledger.recordError(); Runner.ask -> session.truncateTo(mark)
```

---

## 4. Rate limiting

```
Runner                        RateLimiter                Throttle
 |                                 |                        |
 |--verdict(estimated)------------>|  prune(now), sum window
 |<--Admit ------------------------|
 |<--Wait(millis, reason)----------|
 |     |--show(millis, reason)------------------------------>|  countdown line
 |     +--sleep(min(millis, 250)); re-check   (a background turn may take the room)
 |<--Oversized(reason)-------------|  sent anyway; waiting cannot help
 |<--Deny(reason)------------------|  on_limit: error
 |                                 |
 |--reserve(estimated)------------>|  id; charged at the estimate
 |--settle(id, actualIn, actualOut)|  window corrected to the truth
 |--release(id)------------------->|  request never reached the provider
 |--snapshot---------------------->|  WindowSnapshot -> Prompt.footer "rate 41%"
```

The estimator is fed `req.encode().toString().length` (minus inline image data URLs), never the
message text, because `prompt_tokens` includes the tool schemas.

---

## 5. Automatic compaction

```
Runner                              Compactor                    Client
 |                                      |                          |
 |--shouldCompact(projected, window)--->|  percent or absolute threshold
 |--[ messages.size <= lastCompactedAt ]--> return   (nothing new to compact)
 |--shouldCompact(fixedOverhead, window)->  [ true ] -> warn once, stop trying
 |--compactNow()                         |                          |
 |     |--compact(messages, summarize)-->|                          |
 |     |        |--trimOldToolResults    |  free, attacks the real growth
 |     |        |--split(messages)       |  never inside an exchange
 |     |        |--summarizeRequest(head)|                          |
 |     |        |--summarize(head)------------> Runner.summarizeForCompaction
 |     |        |                                    |--admit/reserve
 |     |        |                                    |--chat(request)---------->|
 |     |        |                                    |--calibrate/settle/record
 |     |        |<--summary text ------------------- |
 |     |        |--capSummary, summaryMessage +: tail
 |     |        +--[ not smaller than before ]--> reject, keep trimming only
 |     |<--(rewritten, CompactionOutcome)|                          |
 |     +--session.replaceHistory(rewritten)   counts compactions     |
```

`/compact` calls `compactNow()` directly, bypassing the threshold.

---

## 6. System prompt assembly (rebuilt every request)

**This is not the old `List(...)` of hardcoded sections** -- that shape is gone. The system prompt
is `agents/chat.md`, an ordinary agent template the
user owns, re-read and re-rendered on every request. `Runner.systemPrompt` only binds the data and
renders it; the wording and the order of sections live in the file, not in `Runner`.

```
Runner.buildRequest
   |
   +--messagesToSend(pendingUser)
        |
        +--systemPrompt(retrievalQuery(pendingUser))
        |     |--engine.bind("prompt", PromptApi(this, retrievalQuery))   into the interpreter
        |     |--template = configRef.chatAgent ?: ChatAgent.source       user's file, or packaged
        |     |--render(template) -> templates.render(template)           agents/chat.md, Groovy <% %>
        |     |     |--<% %> in the template calls prompt.* --------------------------------->|
        |     |     |     |--prompt.toolNames / prompt.hasTool(name)      which sections apply
        |     |     |     |--prompt.skillList / .skillsDropped            tier 1, one line per skill
        |     |     |     |--prompt.memoryList / .memoryIndex / .memoryDropped
        |     |     |     |--prompt.userToolList / .taskList / .tasksOpen
        |     |     |     |--prompt.libraries / .artifacts / .extraClasspath   Manifest.hints/dependencies
        |     |     |     |--prompt.rag()          -> RagStore.systemPromptSection, while an index is active
        |     |     |     |--prompt.ragContext()   -> RagStore.injection(retrievalQuery), only if /rag inject on
        |     |     |     +--prompt.systemPrompt() -> Session.systemPrompt, the user's own, last word
        |     |--ChatAgent.tidy(rendered)      collapses the blank lines a missing section leaves
        |     +--[ render fails ] -> fall back to ChatAgent.packaged, never a silently empty prompt
        |
        +--Session.messages() + pendingUser
   |
   +--tools = activeTools.map { it.definition }
        +--ToolRegistry.selected(use_tools), minus `search` when no index is active
```

Nothing here is ever written into the history: memories and retrieved context are rebuilt per
request, so editing a file takes effect on the next message. `PromptApi`'s data members are Kotlin
properties, and Groovy reads a `getX()` as the property `x`, so `agents/chat.md` calls
`prompt.memoryList` with no parens.

---

## 7. The agentic tool loop

```
Runner            ToolRegistry      Approval      ReplPrompter     Tools        GroovyEngine
 |                     |               |               |             |              |
 |--[ resp.toolCalls.nonEmpty ]                        |             |              |
 |--[ abort ] -----> declineToolCalls(reason)  one tool message per id, always      |
 |--[ iteration+1 >= max_tool_iterations ] -> declineToolCalls + warn               |
 |                     |               |               |             |              |
+-- per call ------------------------------------------------------------------------+
 |--get(call.name)---->|               |               |             |              |
 |--parseArgs + tool.preview           |               |             |              |
 |--ToolRender.announce(name, preview) |               |             |              |
 |--decide(name, preview)------------->|               |             |              |
 |                     |    [ Auto ]---> Allow          |             |              |
 |                     |    [ Deny ]---> Deny(reason)    |             |              |
 |                     |    [ Confirm ]                  |             |              |
 |                     |      |--allowlisted? approveAll?              |             |
 |                     |      |--[ no terminal ]--> Deny  (never auto-approve)       |
 |                     |      +--confirm(name, preview)-->|  y/n/a via the LineReader |
 |--[ Allow ]--dispatch(call)---------->|               |             |              |
 |                     |--tool.run(args)-------------------------->|                 |
 |                     |     groovy_eval -------------------------->|--eval(code)---->|
 |                     |     exec      ---> ProcessBuilder + drain thread + timeout  |
 |                     |     remember  ---> Memory.append(note)                      |
 |                     |     search    ---> RagStore.search (see 8)                  |
 |                     |<--ToolResult--------------------------------|                |
 |--ToolRender.result(content, isError, ms)             |             |              |
 |--session.append(Message.tool(call.id, content))      |             |              |
 |--ledger.recordToolRun(ToolCallMetrics)               |             |              |
+------------------------------------------------------------------------------------+
 |
 +--converse(abort, iteration + 1)    next request carries the results
```

**Invariant:** every requested `tool_call_id` gets exactly one tool message, whether it ran,
was refused, failed, or was abandoned. `RunnerToolLoopTest` asserts it directly.

---

## 8. Retrieval

### Indexing (`/rag index <folder>`, `--rag-index`)

```
Commands/Main       RagStore           Rag            RagIndex        Chunker    Embedder/VectorIndex
 |                     |                |                |               |               |
 |--indexFolder(dir)-->|                |                |               |               |
 |                     |--nameFor(root), resolve(base, name, embedder)-->|               |
 |                     |--load()  (a basename collision is refused, not merged)          |
 |                     |--index(idx, root, config, loaders, read, onProgress)->|         |
 |                     |                |--RagIndex.scan(root, eligible, known)          |
 |                     |                |     added / changed / removed by size+mtime    |
 |                     |                |--[ per file ] read -> Loaders.convert or readText
 |                     |                |--chunk(rel, text, lang, ext)-->|               |
 |                     |                |     prose: recursive separators + hard fallback |
 |                     |                |     code:  declaration keywords + brace balance |
 |                     |                |--idx.save(meta, chunks)------->|               |
 |                     |                |        |--chunks.jsonl, meta.json              |
 |                     |                |        +--encode each chunk --->|--save sidecar |
 |                     |<--IndexReport--|                |               |  float32 rows |
 |                     |--mark(name) on disk; injectThisSession = false   |               |
```

### Searching (the `search` tool, `/rag search`)

```
Tools.search        RagStore        RagIndex          Bm25       VectorIndex     Fusion
 |                     |               |                |             |             |
 |--search(q, k, path)>|               |                |             |             |
 |                     |--search(...)->|                |             |             |
 |                     |               |--over = max(k*10, 100)       |             |
 |                     |               |--search(q, over)------------>|             |
 |                     |               |--encode(q) + search(v, over, minSimilarity)>|
 |                     |               |--reciprocalRank([lexical, dense], k=60)---->|
 |                     |               |--path filter, then diversify(maxPerFile)    |
 |                     |<--List<Pair<Chunk, Double>>----|             |             |
 |<--hits--------------|               |                |             |             |
 |--renderHits: "path:startLine-endLine (score)" per passage, total size capped
```

`RagIndex.load` refuses vectors whose encoder id, dimensions or row count do not match, reports
why through `vectorProblem`, and degrades to BM25 - `/rag` then says `bm25 only`.

---

## 8b. Skills, in three tiers

Tier 1 rides on every request for the price of a line per skill. Tiers 2 and 3 are pulled only
when the model decides a skill applies -- which is the entire point of the arrangement.

```
                                        Runner            Skills          FileTools
per request:                              |                 |                 |
  agents/chat.md -> prompt.skillList ---->|--listed()------>| scan() three roots
                                          |<--(name, desc)  |  nearest root wins a collision
                                          |                 |
model reads the line, decides it fits     |                 |
  tool_call: skill{name} --------------->|--render(name)--->| find + frontmatter strip
                                          |                 |  + absolute dir
                                          |<----------------|  + bundled file listing (tier 3)
                                          |                 |
body says "read CHECKLIST.md"             |                 |
  tool_call: read{skill dir/CHECKLIST.md}-|----------------->|-------------->| numbered lines
  tool_call: grep{pattern, path} ---------|----------------->|-------------->| walk + regex
```

```
/skill <name> <args>            Commands.dispatch -> Skills.render -> ReplOutcome.Send(body)
--skill <name> TEXT             Main.runSkill     -> Skills.render -> singleHit
/skills, --list-skills          Skills.report / Commands.listing("skills")
```

Where the registration decision is made:

```
Runner.registry (lazy)
   +--Tools.defaults(..., fileTools = configRef.fileToolsEnabled,
                          skills    = if (skills.enabled && skills.load().isNotEmpty()) skills else null)
```

A `skill` schema with no skill installed is pure cost and an invitation to a call that can only
fail -- the same rule as `search` with no active index.

---

## 9. Multi-form input (`-f` / `/file`)

```
Commands/Main      InputLoader                    Loaders          Repl
 |                     |                             |               |
 |--splitMessage(rest)->|  splits sources from the message at `--`   |
 |--tokenise(specs)---->|  respects backticks and quotes             |
 |--load(tokens)------->|                             |               |
 |      |--classify: LocalFile | Directory | Url | Command | %% last reply
 |      |--[ Directory ]-> walk, skipping target/node_modules/.git/dot-dirs
 |      |--[ binary ]----> skip (NUL byte in the first 8KB)          |
 |      |--[ loader ]----> convert(file, ext, document_loaders)------>|
 |      |--caps: input_max_file_chars / _total_chars / _files         |
 |<--Loaded(blocks, skipped)-------------------------|               |
 |--report every skip (never swallowed)              |               |
 +--Loaded.render(message) -> ReplOutcome.Send ---------------------->|--send(text, echo=false)
```

---

## 10. Templating and harness variables

```
Repl/Main        Runner            Vars           Template      TemplateEngine    GroovyEngine
 |                  |                |               |                |               |
 |--expand(raw)---->|                |               |                |               |
 |                  |--[ template: false ]--> raw unchanged            |               |
 |                  |--expand(text, HarnessVars.asMap(runner))-------->|               |
 |                  |      |--{{name}} -> value, `<%` neutralised to `<%%`             |
 |                  |      +--unknown but reference-shaped -> error + suggestion        |
 |                  |--hasTags(substituted)-------->|                |               |
 |                  |      [ no tags ] -> return    |  (no compiler start)            |
 |                  |--render(substituted)---------------------------->|               |
 |                  |                |               |--parse -> List<Part>            |
 |                  |                |               |--emit  -> Emitted(code, lineMap)|
 |                  |                |               |                |--bind(Sink)-->|
 |                  |                |               |                |--eval(code)-->|
 |                  |                |               |<--Sink.result / diagnostics ----|
 |                  |<--Outcome<String>  errors mapped back to template lines          |
 |<--expanded-------|                |               |                |               |
```

Order is deliberate: variables first, so `<%= "{{model}}".toUpperCase %>` works. Expansion
happens at the **input boundary**, so `/regenerate` replays expanded text.

---

## 11. Background turns

```
Repl            Spawner        BackgroundRegistry  Runner(fork)     Repl (next prompt)
 |                 |                |                 |                    |
 |--/bg <prompt>   |                |                 |                    |
 |--expand(prompt) (in the foreground, so a template error is immediate)   |
 |--spawn(text)--->|                |                 |                    |
 |                 |--start(prompt)>|  (id, abort flag)                    |
 |                 |--daemon thread |                 |                    |
 |                 |     |--fork()--------> forkBackground()               |
 |                 |     |        copies history; quiet=true; stream=false |
 |                 |     |        shares RateLimiter; own GroovyEngine       |
 |                 |     |        Prompter.unavailable -> tool calls refused
 |                 |     |--ask(prompt, abort)-------->|                    |
 |                 |     +--complete(id, reply, in, out) | fail(id, error)  |
 |<--"[id] started in the background"                    |                  |
 |                                                       |                  |
 |--reportFinishedTurns() ---- drainFinished() --------------------------->|
 |     +--chrome.whilePrinting { notice + Markdown.render(reply) }          |
 |
 +--close(): bgTurns.cancelAll()   daemons cannot hold the process open
```

---

## 12. Commands

```
Repl.runCommand                     Chrome                Commands            Status/RagStore/...
 |                                     |                     |                      |
 |--wrapRows(caret + line)             |                     |                      |
 |--whilePrinting(println(Turn.question(line)))              |                      |
 |--[ /bg ]--> backgroundCommand(args) (needs the spawner; see 11)                  |
 |--suspend()------------------------->|                     |                      |
 |--dispatch(runner, name, args, overlay)------------------->|                      |
 |      /help /info /vars /memory /rag /status /model /prompt /set /file            |
 |      /compact /empty /regenerate /exit                    |                      |
 |                                     |                     |--Status.render------>|
 |                                     |                     |--RagStore.* ------- >|
 |                                     |                     |--Memory.append ---- >|
 |                                     |                     |--Runner.switchModel >|
 |                                     |                     |--Runner.compactNow ->|
 |<--overlay(lines) callback -----------|                     |                      |
 |      |--restore(); overlay(lines, footer) -> paints into the reserved rows       |
 |      +--awaitKeypress(); [ declined ] -> suspend() so the caller prints instead  |
 |--[ overlaid ]--> eraseRows(echoRows)   leaves no trace at all                    |
 |--[ printed ]---> println()             blank line between blocks                 |
 |--restore()------------------------->|                     |                      |
 |
 +--ReplOutcome.Continue | ReplOutcome.Exit | ReplOutcome.Send(text) -> send(text, echo = false)
```

---

## 13. Chrome mechanics

```
Chrome
 |
 |--framed = capable && !forcedPlain
 |     capable: type != dumb && change_scroll_region, save_cursor, restore_cursor,
 |              cursor_address all present  (tested BEFORE constructing Status)
 |     forcedPlain: KOTLLM_CHROME=plain | -Dkotllm.chrome=plain
 |
 |--prompt(runner)   framed -> Prompt.input(width)   (rule + "\n" + caret)
 |                   plain  -> Prompt.plain(runner)  (model name + caret)
 |
 |--refresh(runner)  Status.update([Prompt.divider, Prompt.footer(runner, width)])
 |--busy(label)      swaps in the in-flight footer
 |
 |--dockBottom(rowsAbove)   screenful of newlines, then cursor_address(rows-4-rowsAbove)
 |                          newlines scroll; addressing alone overwrites the shell's output
 |
 |--eraseRows(n)     CUU n, then per row: EL 2 + CUD, then CUU n + CR
 |                   never ED 0: that clears past the scroll region into the pinned rows
 |
 |--suspend/restore  Status stops painting; the scroll region STAYS set, so printed
 |                   output can never scroll into the reserved rows
 |--whilePrinting    suspend if not already suspended, run, restore
 |--overlay(lines)   paints a panel INTO the reserved rows, waits for a keypress
```

`Prompt.footer` assembles elements with drop orders and `fit` sheds the expendable ones until
the line fits: `/help` first, then the version, then session totals. Model, tool state, context
pressure, rate window and failures are never dropped.

---

## 14. Single hit and `--dry-run`

```
Main.singleHit                    Runner
 |                                   |
 |--toolConfigHint -> stderr (so it cannot corrupt piped stdout)
 |--expand(text)-------------------->|   same expansion as the REPL
 |--ask(expanded)------------------->|   Session is empty: context-free by construction
 |                                   |--gutter = Gutter.none: output is byte-for-byte
 |                                   |  what the model sent, safe to pipe
 |<--exit 0 | 1 with the error on stderr

Main dispatch --dry-run
 |--expand(text) then dryRun(text) -> PrettyJson.encodeToString(buildRequest(...).encode())
 +--nothing is sent; the tool schemas, memories and retrieval block are all visible
```

---

## 15. Images

```
Main/Commands        InputLoader         Runner              Message/Content
 |                       |                  |                     |
 |--load(specs)--------->|                  |                     |
 |        |--[ .png/.jpg/.webp/.gif ]-> readImage(path, ext)      |
 |        |     |--size > input_max_image_bytes -> skip, naming the key
 |        |     +--base64 -> Media(label, "data:image/png;base64,...", bytes)
 |<--Loaded(blocks, medias, skipped)--------|                     |
 |--report each attachment (never silent)   |                     |
 |--ask(text, abort, images)--------------->|                     |
 |                                          |--attachable(images) |
 |                                          |    [ !supports_vision ] -> drop + say why
 |                                          |--session.append(Message.user(text, images))
 |                                          |                     |
 |                                          |--buildRequest -> Message.encode
 |                                          |     [ images ]-> content = [text, image_url parts]
 |                                          |     [ none ]---> content = "plain string"
 |                                          |--measure(req): chars MINUS data-url length
 |                                          |     estimate = ratio * chars + 800 per image
 |                                          |     calibrate ONLY when imageCount == 0
 |                                          +--dryRun -> Runner.elideImageData
```

## 16. HTML to markdown, and `fetch`

```
-f URL / -f page.html        Fetch              Html          Tools.fetch (the model)
 |                             |                  |                    |
 |--load(specs)                |                  |                    |
 |    +--[ Url ]--> get(url, convertHtml)         |                    |
 |                  |--raw(url): GET, gzip decode, content-type        |
 |                  |--render(source, body, contentType, convert)      |
 |                  |     content-type says html, or looksLikeHtml     |
 |                  |--toMarkdown(body)---------->|                    |
 |                  |     drop script/style/svg/head                   |
 |                  |     h1-h6, ul/ol, pre, code, a href, img alt     |
 |                  |     entities decoded, whitespace collapsed       |
 |                  |<--markdown------------------|                    |
 |<--Page(text, rawChars, convertedFromHtml)      |                    |
 |--label carries savingSummary: "47k -> 11k chars (76% smaller)"      |
 |                             |                  |                    |
 |                             |<--get / local ------------------------|
 |                             |   truncated to max_chars, header names the source
```

## 17. Web search

```
Tools.webSearch        Search              Fetch                 provider
 |                        |                  |                      |
 |--query(q, source, site, results)          |                      |
 |                        |--[ Web ]         |                      |
 |                        |   engine is Engine.None -> err("set provider: ddg-lite ...")
 |                        |   scrape(lite.duckduckgo.com/lite/?q=)-->|--raw()-->DDG
 |                        |      ResultLink  -> href + title (either attribute order)
 |                        |      unwrap(/l/?uddg=...) -> the real URL
 |                        |      ResultSnippet -> Html.plainText
 |                        |      no matches -> "scraped HTML, layout may have changed"
 |                        |--[ StackOverflow ]--> api.stackexchange.com  (gzip)
 |                        |--[ Wikipedia ]------> en.wikipedia.org/w/api.php
 |                        |--[ Maven ]----------> coordinates -> repo1 maven-metadata.xml
 |                        |                       keywords    -> search.maven.org solr
 |                        |--[ GitHub ]---------> api.github.com/search/repositories
 |<--List<Hit>(title, url, snippet, extra)---|                      |
 |--renderHits: "[1] title (extra)", then the url, then the snippet
 |
 +--Approval.decide(name, preview, readOnly = true) -> Allow under confirm
```

## 18. `--list-*`

```
Main.dispatch            Commands.listing              Runner
 |                            |                          |
 |--[ cli.list ]-> listItems(runner, kind)               |
 |                            |--"models"---> config.listModels
 |                            |--"tools"----> toolRegistry.all.map { it.name }
 |                            |--"rags"-----> rag?.names        | err("rag_enabled")
 |                            |--"memories"-> memoryFiles       | err("memory_enabled")
 |                            |--"vars"-----> harnessVars.map { it.name }
 |                            |--"commands"-> Commands.all.map { it.name }
 |<--Outcome<List<String>>-------------------|
 |--[ Ok(items) ]-----> one per line on stdout, exit 0
 |--[ Ok(empty) ]-----> reason on stderr, exit 1   (empty != switched off)
 +--[ Err(message) ]--> error on stderr, exit 1
```

## 19. Who may call whom

```
                 Main
                  |
        +---------+----------+
        |                    |
      Repl ---------------> Runner <---------- Spawner (forked Runner)
        |                  /  |  \  \
     Chrome            ...    |   \  +--------------------+
     Prompt         Client    |    +---- Compactor        |
     Commands       (Wire,    |          Memory           |
     Status          Sse,     |          RagStore --- RagIndex -- Bm25 / VectorIndex
     Completer       Retry)   |          InputLoader -- Loaders
     ReplPrompter             |          TemplateEngine - Template / Vars / HarnessVars
                              |          ToolRegistry --- Tools --- GroovyEngine
                              |          RateLimiter / TokenEstimator / TokenLedger
                              +--------- render/* (StreamRender, Turn, ToolRender, Markdown)
```

Rules the layering depends on:

- **`Runner` never imports `repl`.** The REPL supplies a `Prompter` and a `Gutter`; that is the
  whole coupling, which is what lets single-hit mode and background turns reuse it untouched.
- **Only `repl` prints chrome.** `Runner` prints through `render/*` and `say()`, which is a no-op
  for a quiet (background) runner.
- **`client` knows nothing about tools, memories or retrieval** - it takes a `ChatRequest`.
- **`rag`, `memory` and `input` never talk to each other.** `Loaders` is the one shared piece,
  extracted so `document_loaders` has a single implementation.
- **`web` depends on `input`, not the reverse.** `Fetch` is the single HTTP-GET-with-timeout path
  and `Html` the single converter, so search reuses both rather than opening its own connections.

---

## 20. Invariants, and the class that enforces each

| Invariant | Enforced by |
| --- | --- |
| One tool message per `tool_call_id`, however the loop ends | `Runner.declineToolCalls`, `runToolCalls` |
| History never split inside an exchange | `Compactor.exchanges`, `split` |
| Reasoning parts never replayed to the endpoint | `Session.appendResponse`, `Message.encode` |
| A compaction that does not shrink is rejected | `Compactor.compact` |
| Nothing prints under the pinned rows | `Chrome.whilePrinting`, `Repl.send`, `Repl.runCommand` |
| The two rules bracket only the input line | `Repl.run` (erase) + `Turn` (reprint) |
| Erase never reaches the reserved rows | `Chrome.eraseRows` |
| `search` offered only while an index is active | `Runner.activeTools` |
| Injection is session-scoped and cannot be persisted | `RagStore.injectThisSession` (no config key) |
| Tool state is never silent | `Prompt.footer`, `Runner.toolConfigHint` |
| No terminal under `confirm` means refuse | `Approval.decide` |
| Estimator is fed the serialised request | `Runner.sendOnce`, `summarizeForCompaction` |
| A rejected request is settled; an unsent one is released | `Runner.sendOnce` catch blocks |
| Glyphs degrade to ASCII | `util.Glyphs` (and tests derive expectations from it) |
| The version is written down once | `pom.xml` -> `kotllm-build.properties` -> `BuildInfo` |
| An image never becomes prompt text | `Media`, `Message.encode`, `Loaded.render` |
| Base64 never reaches the estimator | `Runner.measure`, `elideImageData` |
| `supports_vision` decides, and says so | `Runner.attachable` |
| Scraper breakage is reported as breakage | `Search.scrape` |
| General web search is opt-in | `SearchConfig.engine` default `Engine.None` |
| `deny` outranks the read-only exemption | `Approval.decide` |
| A `--list` prints bare names only | `Commands.listing` |
