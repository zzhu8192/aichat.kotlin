# Testing

`mvn -q test` runs the unit suites, JUnit 5 with Kotest assertions.
Tests that hit a live endpoint are tagged `@Tag("Live")` and excluded from the default run
(verified in `pom.xml`: `excludedGroups=${excluded.test.tags}`, defaulting to `Live`);
`mvn -Plive test` clears that exclusion and includes them.
The mechanism changed with the port -- ScalaTest's tag objects became a JUnit annotation -- but
the command line a developer types is exactly what it was.

Two rules below are not style preferences.
Both were found by a green suite that passed on CI and failed on a real machine, and both are
about the test *infrastructure* rather than the language, so the port carried them unchanged.

### Tests must be detached from the real config directory

`Memory` and `RagStore` both resolve their locations through `config/Paths`, which follows
`KOTLLM_CONFIG_DIR`.
A test that builds a `Runner` therefore inherits whatever the developer has saved -- a memory
note, an active index -- into its system prompt.

Running `mvn -Plive test` against a populated config dir failed three specs this way on the
Scala side: one asserting an exact system prompt, and two rate-limit specs whose deliberately
tiny budgets were pushed over threshold by a few hundred injected characters (`a single request
needs 247 input tokens but the budget is only 200`).
None of the three had anything to do with memories.

`TestIsolation.isolate` disables both, and every `Runner` construction that goes through the
ported test tree does too -- verified at `src/test/kotlin/kotllm/TestIsolation.kt`, which carries
the same rationale across essentially unchanged: it turns off `memory`, `rag`, `skills` and
`userToolsEnabled`, and turns off `save` for the same reason in the other direction (below).
Tests that *are* about memories or retrieval point `Paths` at a temp directory instead.
**A new `Runner` construction in a test should be wrapped the same way**, or it will pass on CI
and fail on a real machine.

The same object also turns `save` off, and that one leaked the other way -- out of the tests and
into the developer's data rather than in.
`save` defaults to **true**, so every spec that built a `Runner` and sent a turn wrote a real
JSONL into `Paths.sessionsDir`.
With `KOTLLM_CONFIG_DIR` set, which `runit.bat`/`runit.sh` do, that is the developer's own
conversation history: a single `mvn test` scattered `stub-model` conversations through it,
`search_chats` retrieved them alongside real ones, and nothing in the test output said so.
It is the failure mode this whole section is about, with the direction reversed, and the
isolation object was the obvious place it was missing.

### Corpus contamination bites any test that searches this repository

`RagLiveTest` indexes the repo it lives in, so **a query string written as a literal in a test
becomes part of the corpus and is then found.** This happened twice on the Scala side:

- A test asserting the top hit for `declineToolCalls` was `Runner.scala` failed because the
  spec's own file matched. Fixed by asserting membership rather than rank -- which is also the
  honest contract, since retrieval promises reachability, not position.
- A test asserting gibberish matches nothing failed with a BM25 score of 4.4, because
  `zzzznomatchquery` was a literal in that very file. Then failed *again* at 12.3 after the
  first fix, because a throwaway diagnostic spec still contained the word.

Assembling the literal at runtime was not enough either: the third failure came from quoting
the nonsense word in README.md and CLAUDE.md as an illustration, which put it back in the
corpus.
**Prose about the code is part of the corpus too.**
Nonsense queries are therefore *generated* from a seeded RNG, so no literal exists anywhere to be
matched.
Any new test that searches this repository has the same problem, in either language.

**`RagLiveTest.kt` is `@Disabled`, and the reason on file is stale.**
Its class comment gives two reasons: no Kotlin equivalent of the `Live` tag/`-Plive` exclusion,
and several assertions hardcoding the Scala tree's layout (`src/main/scala/kotllm/Runner.scala`,
a root `README.md` that supposedly does not exist, `Runner.kt` supposedly not yet ported).
Both are out of date, verified directly: `pom.xml` already wires `@Tag("Live")` through
`excludedGroups`/the `live` profile exactly as this doc describes above, this repository has a
root `README.md`, and `Runner.kt` is fully ported (`declineToolCalls` and `fixedOverheadTokens`
both exist in it).
Re-enabling the test still needs its path literals updated to the Kotlin tree
(`Compactor.kt` lives at `src/main/kotlin/kotllm/compact/Compactor.kt`, not where the comment
says to look), but the two structural blockers it cites no longer apply.

## The chrome cannot be tested by asserting on strings

Escapes are *positions*.
A test that greps for `ESC[2K` cannot say where the cursor ended up, and three chrome bugs in a
row passed the whole suite.
The two dev tools in `scripts/` are the real check -- see
[repl.md](repl.md#the-chrome-cannot-be-tested-by-asserting-on-strings).
`scripts/` changed with the interpreter, not with the port: `vt.py` and `wslrun.sh` are
untouched, and `smoke.sc` became `smoke.groovy`, run with `groovy.ui.GroovyMain` off the fat jar.
It had to: the thing it smoke-tests is the network stack, and it was invoked through
`scala.tools.nsc.MainGenericRunner`, which is no longer on the classpath.

## Forcing `KOTLLM_UNICODE` both ways

`LatexTest` runs with Unicode forced on *and* off, and that caught a wrong assertion the first
time it ran on the Scala side.
A default test environment that cannot encode Unicode would never have said so.
Derive expectations from `util/Glyphs` rather than hard-coding a glyph, or the test fails on
exactly the terminals the fallback exists for.

## Verified live, end to end

Most subsystem docs carry the live run that proved the feature -- `engines.md` has the
803,462-character log, `rag.md` the BM25 question answered from this repository, `tools.md`
the `create_tasks` transitions.
Two general ones lived in the old status section and belong here instead.
Both are Scala-project results, and they have **not** been re-run since the interpreter changed.
Treat them as history. The first in particular no longer describes this build at all:

Verified against the live model, on the Scala build: asked for `fib(20)`, it wrote Scala, IMain
compiled and ran it (`res0: BigInt = 6765`), and it answered with the computed value rather than
from memory.
The interpreter is Groovy now, so there is no "no reason to expect otherwise" argument to make
here -- the language, the transcript format and the diagnostics are all different. This wants
re-running.

Verified live end to end for skills, on the Scala build: "Please review
src/main/scala/kotllm/util/Skips.scala" matched the `scala-review` description alone, the model
called `skill` for the body, read the bundled `CHECKLIST.md`, then ran one `grep` per checklist
item -- including recovering from a regex of its own (`?=>`, "Dangling meta character '?' at
index 24") off the error text, which is the argument for returning diagnostics verbatim, arriving
twice now.
That one is mostly about skill matching and file tools rather than the interpreter, so it should
carry over; a rerun would ask about `src/main/kotlin/kotllm/util/Skips.kt` instead. Still a
description of what the next run should look like, not a report that it happened.

One thing *has* been re-verified against this build: `scripts/smoke.groovy` runs the whole
network stack -- scraped DDG, the four JSON APIs, and HTML -> markdown on a live page -- through
the fat jar, and all seven sources come back populated.

## The test tree is at parity

1,216 tests, green, across every package. The gap this section used to describe -- 59 files
against 85, with `repl`, `agent` and `background` missing entirely -- is closed, and the
`.kt.deferred` stubs are gone.

What is still outstanding is narrower and worth naming rather than implying:

- `RagLiveTest.kt` is `@Disabled` on stale path literals, as described above.
- The typed-output tests went with `llmAs`/`enumOf` when upickle left, which is a removal rather
  than a gap.
