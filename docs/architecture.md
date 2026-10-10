# Architecture

Module layout, the dependency policy, and the config format.

## Module layout

Files marked **[built]** exist and are tested; the rest are planned and
deliberately absent rather than stubbed, so tab completion and `--help` never
advertise something that does not work.

This began as a straight port of a Scala tree, and most `[built]` files below still have a
same-named counterpart at the same package path. Three do not, and the reasons differ:
`Outcome.kt` (the `Either[String, A]` replacement) and `render/Ansi.kt` (which replaced fansi)
are new to the port, while `engine/GroovyEngine.kt` replaced `ScalaEngine.kt` outright when the
language the model writes changed. `KotllmTool.kt` went with it.

```
src/main/kotlin/kotllm/
  Main.kt                 [built] Entry point; single-hit vs REPL dispatch.
                                  The JVM looks for a top-level `fun main`,
                                  which does nothing but forward to `Main.main`
                                  so every other file that names `Main.xxx`
                                  still resolves against one object.
  Cli.kt                  [built] Argument model and parser
  Outcome.kt              [built] Either[String, A] replacement: ok()/err(),
                                  plus map/flatMap/fold/getOrElse/orNull/isOk
                                  as top-level extension functions
  Runner.kt               [built] Executes and renders one turn; shared by both
                                  modes. Single-hit is just a Runner over an
                                  empty Session, so it is context-free by
                                  construction rather than by convention.
  config/
    Config.kt             [built] Root config, YAML load, model resolution
    ClientConfig.kt       [built] clients[] / models[] (aichat-compatible subset)
    Role.kt               Prompt + model config; `---` frontmatter .md format
    Session.kt            [built] Message history + token ledger, history
                                  replacement for compaction
    SessionStore.kt       [built] Conversations as JSONL, appended per turn
    ChatSearch.kt         [built] Hybrid retrieval over stored conversations
    Macro.kt              Named sequences of REPL commands with variables
    RateLimit.kt          [built] rate_limit config block, OnLimit policy
    Paths.kt              [built] Config dir and subdirectory resolution
  client/
    Client.kt             [built] Client SPI, factory, StreamAssembler
    Wire.kt               [built] Request/response/chunk codecs, ToolDef
    Content.kt            [built] Part ADT + lenient String-or-array codec
    Databricks.kt         [built] Gateway client on java.net.http; also serves
                                  any OpenAI-compatible server, incl. Ollama
    AzureFoundry.kt       [built] Azure AI Foundry client on java.net.http
    Models.kt             [built] GET /v1/models, for --discover
    Sse.kt                [built] SSE framing, `data:` parsing, [DONE], abort
    Retry.kt              [built] Backoff for cold starts, 429s and 5xx
  engine/
    Interpreter.kt        [built] The SPI all three engines implement;
                                  EvalResult, Binding, availability probes
    GroovyEngine.kt       [built] GroovyShell lifecycle, timeout, output capture,
                                  the shared Binding that is the session;
                                  see docs/engines.md
    ExitGuard.kt          [built] Rewrites System.exit in a submitted snippet
    JShellEngine.kt        [built] Java in a separate JVM; snippet splitting
    PythonEngine.kt        [built] Python in a child process; venv bootstrap,
                                  auto-install, the JSON bridge
    FileSink.kt            [built] Receives a computed String from a session
    Classpath.kt            [built] Runtime classpath + append override
    Manifest.kt             [built] Classpath -> library manifest for the prompt
  template/
    Template.kt            [built] <% %> parser -> emitted Groovy + line map
    TemplateEngine.kt       [built] Runs the emitted program, Sink, error mapping
    Vars.kt                 [built] {{ }} substitution, escaping, suggestions
    HarnessVars.kt          [built] The variable table
  tools/
    Tool.kt                 [built] Descriptor, registry, dispatch, arg parsing
    Tools.kt                [built] groovy_eval, java_eval, python_eval, read_to_var,
                                  write_from_eval, session_vars, exec,
                                  add_or_update_memory, delete_memory, read_memory,
                                  search, fetch, web_search, skill, ask_user,
                                  search_chats, view_chat, get_sys_dt,
                                  session_transfer, create_tasks, update_task
    FileTools.kt            [built] read_to_context, write, edit, glob, grep, ls,
                                  atomicWrite, FileMemo
    Approval.kt             [built] Confirm prompt, yes-to-all, allowlist
    UserTools.kt            [built] Evaluates config/tools/*.groovy in the live
                                  session; the file's value must be a Tool
  compact/
    Compactor.kt            [built] Trim tool results, summarise the head
  rag/                      [built] Hybrid lexical retrieval over a folder
    Chunker.kt              [built] Recursive + structure-aware splitting, profiles
    Bm25.kt                 [built] Inverted index, BM25 scoring, RRF fusion
    Index.kt                [built] Chunk store, metastore, incremental scan, persistence
    Rag.kt                  [built] Eligibility rules, indexing, config
    RagStore.kt             [built] Named indexes, the active one, injection
    Embedder.kt             [built] Local character n-gram encoder, no deps
    VectorIndex.kt          [built] Flat float32 store, cosine, binary sidecar
  agent/
    Agent.kt                [built] Load and run an agent file; mode switch
    AgentApi.kt             [built] The five calls, bound as `agent`
    AgentRun.kt             [built] Run directory, budgets, verdict, log
  skill/
    Skill.kt                [built] SKILL.md frontmatter + body, the portable six
    Skills.kt               [built] Discovery across roots, tier-1 injection, tier 2
  memory/
    Memory.kt               [built] Load/inject markdown memories, named upsert and
                                  delete, budget-driven tier-1/tier-2 split
  input/
    Input.kt                [built] -f/.file: files, dirs, URLs, `cmd`, %% last reply,
                                  images as data URLs
    Fetch.kt                [built] One HTTP GET or local read, gzip, HTML -> md
    Html.kt                 [built] Dependency-free HTML -> markdown tokeniser
  web/
    Search.kt               [built] Keyless search: scraped DDG plus four
                                  documented JSON APIs
  render/
    Ansi.kt                 [built] Colour and attribute spans, overlay,
                                  escape sanitising -- see docs/rendering.md.
                                  No counterpart in the Scala tree, which used
                                  fansi directly
    Markdown.kt              [built] Terminal markdown + code-block highlighting
    Latex.kt                 [built] LaTeX -> readable text, with ASCII fallbacks
    StreamRender.kt           [built] Line-buffered render; reasoning channel,
                                  gutter prefixes at line starts
    Turn.kt                   [built] Transcript layout: echoed question, reply
                                  gutter, the grey cost line
    Spinner.kt                [built] Pre-first-token spinner
    Throttle.kt                [built] Rate-limit countdown line (non-REPL paths)
    ToolRender.kt               [built] Shows each tool call and its result
  repl/
    Repl.kt                    [built] jline3 loop, ::: fences, history, Ctrl-C
    ReplPrompter.kt             [built] Tool approval prompt via the line reader
    Chrome.kt                   [built] also: transient /status overlay
    ReaderHolder.kt              [built] Breaks the runner/REPL construction cycle
    Commands.kt                  [built] Slash-command table and dispatch
    Completer.kt                  [built] Tab completion for commands and arguments
    Prompt.kt                     [built] Chrome layout: rules, caret, status footer
    Status.kt                     [built] `/status` report: usage over time + metrics
  background/
    Background.kt                 [built] BackgroundRegistry: BackgroundTurn, TurnState,
                                  notices, cancellation
    Spawner.kt                    [built] Launches a forked turn on a daemon thread
  task/
    Checklist.kt                  [built] The visible task list: ids, statuses, injection
  util/
    Yaml.kt                       [built] YAML load + forgiving Conf accessors
    Skips.kt                      [built] The one skip-dir and binary-extension set
    AtomicWrite.kt                 [built] Replace-not-truncate, shared by files and memories
    Cancellation.kt                 [built] Kill hooks, so Ctrl-C reaches a child process
    Pricing.kt                      [built] Token prices, DBU conversion, cost breakdown
    TokenLedger.kt                  [built] Per-turn metrics history, sparkline, bar
    Glyphs.kt                       [built] Unicode glyphs with ASCII fallbacks
    RateLimiter.kt                  [built] Rolling-window limiter, reserve/settle
    TokenEstimator.kt               [built] Self-calibrating pre-flight token estimate
    BuildInfo.kt                    [built] Version and build time from the filtered resource
    Tls.kt                          [built] Trusts the OS store too, for TLS-inspecting proxies
    Shell.kt               Planned: the shell assistant (-e / -c). No Shell.kt or Shell.scala
                                  exists in either tree yet; see CLAUDE.md's deferred list.
src/test/kotlin/kotllm/     [built] 1,216 tests across every package, green.
                           JUnit 5 with Kotest assertions. Live-tagged suites
                           (`@Tag("Live")`) are excluded from the default run
                           and enabled with -Plive.
```

Note: several packages and files present in the actual source tree on both
sides -- `prompt/` (`ChatAgent`, `PromptApi`, `PromptItems`), `render/Emoji`
and `render/EmojiUsage`, `tools/ContextTools`, `tools/InterpreterTools`,
`tools/SessionTools`, `repl/ReplLineReader` and `repl/MenuKeys`,
`input/Loaders`, and `client/Retry` -- are not reflected in the table above.
That gap predates this port: the Scala project's own `docs/architecture.md`
had already drifted behind its `src/`, and this port copied the table as
written rather than silently rewriting it into a truer but different
document. Worth fixing on its own, independent of the Kotlin port.

### Dependency policy

The fat jar classpath *is* the standard library available to the LLM, so every dependency added
here widens what generated code may import - and inflates the manifest that
`engine/Manifest.kt` injects into the system prompt. Add deps deliberately. Current direct
set, verified against `pom.xml`: `kotlin-stdlib`, `kotlinx-serialization-json` (kotllm's own
JSON), `groovy` and `groovy-json` (the interpreter behind `groovy_eval`, and therefore a
*runtime* dependency rather than a build one -- `groovy-json` is not optional, because the
session preamble star-imports it and `bindJson` uses `JsonSlurper`), `jline` (terminal),
`snakeyaml`, and for tests only
`kotlin-test-junit5`, `junit-jupiter`, `kotest-assertions-core-jvm` (assertions only, not the
Kotest test runner -- chosen because `x shouldBe y` is close enough to ScalaTest's spelling that
existing assertions port rather than rewrite).

**Everything Scala is gone**, and that was the point of the swap: `scala-library`,
`scala-compiler` and `scala-reflect` at 2.13.18, plus `os-lib`, `requests`, `upickle` and
`fansi`, which were kept only because the model's own code ran against them. Groovy is about
10MB against the ~56MB a Kotlin scripting host would have cost via
`kotlin-compiler-embeddable`, which is recorded in `pom.xml` next to the dependency.

**`maven-dependency-plugin` writes the library manifest, and for a while it was missing.**
`dependency:list` runs in `process-resources` and writes `kotllm-dependencies.txt` into the jar,
which `engine/Manifest.kt` reads to build the library list injected into the system prompt.
The port shipped without that execution for several waves.
Nothing failed: `Manifest.load()` returns an empty list when the resource is absent, so the model
was simply told the classpath was bare, on every tool-enabled turn, with nothing reporting it.
A docs pass found it by reading the two files against each other, which is the only way it could
have been found short of reading the prompt the model actually received.

**`spark-connect-client-jvm` is deliberately absent from this build.** The Scala project carries
it as a `provided`-scope dependency (see [spark-connect-design.md](spark-connect-design.md)); the
Kotlin build carries no Spark dependency of any kind, by the project owner's decision when the
port reached the point of asking. Nothing in the code refers to it. Re-adding it later would mean
re-running the same `provided`-scope, jline-dedupe and log4j-exclusion checks against this
`pom.xml`, rather than trusting measurements taken against the Scala build.

### What `provided` bought the Scala build, kept for whenever this is revisited

This subsection records what was true of the *Scala* project's `pom.xml`. It is kept for
continuity with `spark-connect-design.md`, and because the reasoning (measure the shaded delta,
do not guess it) still applies whenever someone re-adds the dependency to the Kotlin build. None
of the specific numbers below have been re-measured against the Kotlin `pom.xml`, because the
dependency is not currently declared there at all.

`spark-connect-client-jvm_2.13:4.2.0` was on the Scala project's compile and test classpath and
**not in its fat jar**. The design doc said to measure the *shaded delta* rather than guess it,
the way the DuckDB jar was measured at 73MB instead of assumed cheap. Measured, on the Scala
build:

| | |
| --- | --- |
| whole closure | **81.2 MB across 52 jars** |
| net of what kotllm already ships | **58.8 MB** |
| fat jar if it were `compile` | 27 MB -> ~86 MB |
| fat jar as shipped (`provided`) | **27.2 MB, unchanged** |
| GAVs added to the injected manifest | 0 (still 16) |

The two biggest single items are `icu4j` at 14.5MB and `zstd-jni` at 7.1MB, neither of which
anything here would call. `provided` was not a hedge there: **"extra jars attachable at runtime
via classpath append" is the locked decision** covering exactly this case, so a user who wants
Spark points `classpath_append` at a client distribution and `groovy_eval` can import it.

Three things were checked against `mvn dependency:tree` before it landed in the Scala build,
because all three are hazards that pom already documented and that apply just as much to the
Kotlin one if the dependency returns:

- **The jline hazard must not reappear.** spark-connect depends on the *identical*
  `org.jline:jline:jdk8:3.29.0` this pom declares (still true of the Kotlin pom.xml, which
  deliberately matches `scala-compiler`'s own jline coordinate), so Maven should dedupe to one
  jar -- verify with `dependency:tree`, do not assume it from the Scala result.
- **There must be no loose protobuf, netty or grpc in the tree at all** -- the client jar shades
  its own. That was the stated kill criterion on the Scala side.
- **The log4j backends need excluding, and that is a correctness fix rather than slimming.**
  `log4j-core`, `log4j-slf4j2-impl`, `jul-to-slf4j` and `jcl-over-slf4j` all arrive by default;
  log4j initialises itself and writes to stderr, and the REPL pins its bottom rows through
  JLine's `Status`, where any unsequenced write corrupts the chrome. `slf4j-api` should stay so
  the client links, and with no backend bound it is a no-op.

**`--` cannot appear inside an XML comment.** The house prose style uses it as an em dash, which
is fine in Scala and Kotlin source and a fatal parse error in a pom. Worth knowing before writing
a long comment in `pom.xml`, in either project.

---

## Config format

aichat-compatible, simplified - no `patch`, no RAG keys, no non-Databricks/Azure clients.
The reference file the user supplied (`\lang\aichat\config\config.yaml.dbx-qwen35-122b`) must
continue to parse as-is:

```yaml
model: databricks-qwen35-122b-a10b
save: true
function_calling: true
use_tools: all

clients:
- type: openai-compatible          # also: databricks, azure-foundry, ollama
  name: databricks-qwen35-122b-a10b
  api_base: https://adb-7193300288064453.13.azuredatabricks.net/serving-endpoints
  api_key: dapi...
  models:
  - name: databricks-qwen35-122b-a10b
    max_input_tokens: 261000
    supports_vision: false
    supports_function_calling: true
```

**Several models live in one file already** -- `clients:` is a list and each entry carries its
own `models:`, so a hosted endpoint and a local Ollama server coexist without anything new.
`aliases:` adds short names on top:

```yaml
model: qwen
aliases:
  qwen:  databricks-qwen35-122b-a10b
  gemma: local:gemma4:26b
```

The keys are arbitrary. Nothing in the code knows or cares what an alias is called -- `aliases`
is a `Map<String, String>` and `resolveModel` looks up the string it was given. There is
deliberately **no notion of a weak or strong tier**: the model cannot judge which of two models
a task needs, so kotllm does not offer it a vocabulary for pretending to. The arithmetic and
the reasoning are in [roadmap.md](roadmap.md#weakstrong-model-tiers-and-a-use_llm-tool-the-model-routes-to).

Two decisions there worth keeping:

- **A top-level map, not an `alias:` key on each model entry.** A model does not have to be
  declared to be reachable -- an Ollama client passes undeclared names through on purpose -- so
  for `local:gemma4:26b` there would be no entry to hang the alias off.
- **Expanded in `Config.resolveModel` and nowhere else.** That one point is what `-m`, `/model`,
  the top-level `model:` key and `llm(model = ...)` all go through, so they understood aliases
  the moment it landed. Putting the lookup at any call site would have meant four of them and a
  fifth missed.

One honest limit on the reporting: **with an Ollama client configured, an alias target can
never be "unresolvable".** The undeclared-name passthrough exists so `ollama pull` needs no
config edit, so `gemma: databricks-typo` resolves to `local:databricks-typo` and the check
cannot fire -- the 404 arrives from the local server at call time instead. Warning about it
anyway would fire on every correct config that relies on the passthrough, which is the
documented way to use Ollama, so the check stays quiet and this paragraph is the warning.
Qualify an alias target (`local:gemma4:26b`) when you want the client pinned.

Reading `config.*` from the config directory was considered and rejected: multiple models
already fit in one file, and merging would have needed precedence rules, an owner for the global
settings, and an answer for what `sourcePath` reports -- all of it new surface, visible only when
it goes wrong.

kotllm-specific keys layered on top: `aliases`, `tools_exclude`, `reasoning`, `max_tokens`, `eval_timeout`,
`reasoning_effort`, `classpath_append`, `tool_approval`, `exec_allowlist`, `memory_enabled`,
`user_tools`,
`file_tools`,
`file_read_max_chars`, `grep_max_results`, `read_to_var_max_chars`, `java_eval`, `python_eval`, `python_env`, `python_exe`,
`python_packages`, `python_auto_install`, `python_timeout`, `pricing`, `pricing_enabled`,
`dbu_rate`, `show_cost`, `skills_enabled`, `skills_max_chars`,
`skills_body_max_chars`, `skills_claude_dirs`, `skills_dirs`.

**The reference config contains live credentials and is used for integration testing.** Never
commit an api_key into this repository.

---
