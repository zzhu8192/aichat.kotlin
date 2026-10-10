# Interpreters

Three engines behind one SPI: Groovy in-process on `GroovyShell`, Java on JShell in its
own JVM, Python in a child process. Plus the data-plane tools that let the model
work on files larger than its context window.

### The interpreter as a data plane

Four tools that together let the model work on things **larger than its context window**, by
keeping the data in the JVM and moving only answers through the conversation.

| Tool | Direction | Contents in context |
| --- | --- | --- |
| `read_to_context` | file -> conversation | yes, and on every later request |
| `read_to_var` | file -> a Groovy `String` | no |
| `write_from_eval` | an expression in any session -> file | no |
| `session_vars` | what is in scope: names, types, sizes | no |
| `session_transfer` | one session -> another, as structure | no |

Verified live end to end on an 803,462-character log, which does not fit in the 261k window:
*"write a CSV of how many requests per service took over 800ms, sorted descending, then tell me
the top one"*. Six calls -- `read_to_var`, four evals, one `write_from_eval` -- and the
resulting file matched an independent count exactly, all five rows. Neither the input nor the
output ever appeared in the conversation; the only thing that crossed was `wrote
target/analysis/slow.csv: 68 chars`.

**The names carry the cost.** `read` became `read_to_context` in the same change, which is the
point: the old pair was `read` and `read_into`, so the cheap-looking default was the expensive
one, and a prompt hint was doing the work a name should do. Now the destination is in the token
the model has to emit. The cost is that `Skills.ClaudeCodeNames` maps `Read -> read_to_context`
rather than to something identically spelled, which is a slightly longer jump for a skill written
elsewhere; worth it, and the mapping is arguably more accurate anyway, since a skill saying "use
Read" does mean "put it in front of me".

`write_from_eval` is deliberately *not* `write_from_var`, breaking the symmetry on purpose: it
takes an expression, and a name implying a bound variable would push the model to write
`val tmp = ...` first -- which echoes 800 characters of its own value, measured.

It was `write_from_scala` for exactly one commit, back when Scala was the only session. Once a
Java session existed, a tool named for
one language that serves two is the sort of thing this file warns about elsewhere, and one rename
beat a third near-identical schema arriving with Python. **All three data-plane tools now take a
required `language`** with no default -- the same principle as `read_to_context`: the choice
belongs in the token the model emits, not in a default nobody sees. `Tools.defaults` builds the
enum from the sessions that actually exist, so `java` is simply absent when `jdk.jshell` is not
there.

The shared shape lives in `engine/Interpreter`, extracted when the second language arrived. It
deliberately has **no `bind(name, value)`**: that works in-process and cannot work across a
process boundary, so the SPI asks for the *outcome* (`bindFile(name, path)`) and lets each engine
reach it however it can. For JShell that is "the child opens the file itself", which copies
nothing -- and is the only workable route anyway, since a string literal in a class file cannot
exceed 64KB.

**`session_vars` is not a convenience; it is what makes this safe over a long task.**
Interpreter state outlives the conversation that created it, but the model's *knowledge* of that
state does not -- compaction trims old tool results, so the message saying "bound `log`" can
be summarised away while `log` is still in scope. Without a way to ask, the model re-reads a file
it already holds, or guesses a name and gets a compile error. It reads the live `groovy.lang.Binding`
rather than keeping a parallel registry, which is why it also lists whatever the model defined
itself in `groovy_eval` -- a separate hashtable would be a second source of truth that could
disagree, and would miss most of what is actually there. Names, types and sizes; **never values**,
or the listing would undo the feature it serves.

Reading the binding is also the reason this engine is shorter than a Kotlin-scripting one would
be. An undeclared assignment in a Groovy script *is* a binding entry, so `bind` is a map put, the
listing is a map read, and nothing has to track declarations by hand.

**`write_from_eval` moves the value through `FileSink`, not the transcript.** `GroovyEngine` can
put values *in* (`bind`) but only hands them *out* as transcript text, which is the round trip
being avoided. So the tool arms a bound sink with a target, evaluates the expression through
`evalToString`, and reads the outcome off the object it still holds. The contract is that the
expression must *be* a `String`, checked against the live object and deliberately not against its
rendering: every value has a `toString`, so taking the transcript would have written `[a, b, c]`
to a file and called it a success. What goes back instead is *"type mismatch: the expression is a
ArrayList, not a String. Add .toString() or build the text you want written"*, which is something
the model fixes itself.

**The `write` guard is shared, or it would not be a guard.** `FileMemo` was extracted from
`FileTools` so `write_from_eval` consults the same memo -- otherwise "cannot overwrite a file
you have not read" was one tool call away from being false. With `file_tools: false` there is no
`read_to_context` to satisfy it with, so there is deliberately no guard: one nothing can satisfy
is a deadlock, not a safety feature.

**Binding now counts as having seen a file**, reversing a decision from one commit earlier. The
original reasoning -- the content went to the JVM rather than to the model -- was wrong about what
the guard is for. It prevents *blind* replacement, and a model holding the whole file as a String
is not blind; it has `.length` and `.linesIterator` and everything else. Meanwhile the friction
was real and immediate: read in, transform, write back to the same path is the normal shape of
this work, and it was being refused with advice to go and spend 40k characters on a read.

### The Java session, and why JShell rather than a second embedded compiler

`java_eval` runs Java in a `jdk.jshell` session. It sits beside `groovy_eval` rather than
replacing it, for two reasons that are both measurements rather than preferences:

- **The model is markedly more fluent in Java than in the language this harness embeds.**
  Measured against Scala 2.13, which the in-process session was at the time: live runs produced a
  `mapValues` deprecation and a `?=>` written into a `java.util.regex` pattern. It recovered from
  both -- diagnostics go back verbatim, which is the design working -- but each recovery was a
  round trip spent on dialect trivia. The gap should be narrower against Groovy, whose syntax is
  Java's; that has not been re-measured, so treat the size of it as unverified rather than as
  carried over.
- **JShell executes snippets in a separate JVM by default** (the JDI execution control), so it
  gets the two things in-process embedding fundamentally cannot give. A crash in evaluated code
  costs the child, not the session. And `stop()` *actually works*: `JShellEngineTest` asserts
  that `while (true) { }` is stopped within a 2s budget, which is exactly the loop `GroovyEngine`'s
  cooperative `Thread.interrupt` is documented as unable to break.

**And it costs no dependency.** `jdk.jshell` ships with the JDK. That is the whole reason this
was worth doing now: it is a strictly better engine on two axes for zero additions to the fat
jar. Registered only when the module is actually present -- `Interpreters.jshellAvailable` probes
by name from an object that never mentions JShell, so a trimmed runtime answers "no" rather than
throwing `NoClassDefFoundError`.

**It is off by default as of 1.33.0, and that is a token decision, not a reversal of anything
above.** The two findings still hold: the model is more fluent in Java than in whatever this
harness embeds, and the
JShell child JVM is the only session here that can hard-stop `while (true) {}`, which
`GroovyEngine`'s cooperative interrupt cannot. What changed is that the cost got counted. The
schema is ~200 tokens on *every* request, and it pulls `java` into the `language` enum of
`read_to_var`, `write_from_eval` and `session_vars` as well. A tool offering three
interpreters by default charges that to everyone to serve a few. `java_eval: true` turns it back
on, and nothing else changes when it does.

Things that are load-bearing:

- **Multi-statement input must be split.** `JShell.eval` takes exactly one snippet, and the model
  sends several. Splitting on `;` would break a `for` header or a string containing one, so the
  engine loops on `sourceCodeAnalysis().analyzeCompletion`, which is what the `jshell` tool uses.
  `DEFINITELY_INCOMPLETE` is reported as incomplete rather than guessed at.
- **The default imports are installed by us.** The `jshell` command-line tool loads them from a
  startup script; the `JShell` *API* does not. Without them a model writing `List.of(...)` or
  `Files.readString(...)` -- precisely what it expects to work in a Java REPL -- fails on a bare
  session. There is a test.
- **Values are truncated on our side.** **JShell does
  not cap at all**, so `var log = <800KB>` would return all of it. `bindFile` therefore evaluates
  with echoing off entirely, and everything else is capped. The in-process engine caps too, in
  `GroovyEngine.clamp`.
- **Output capture needs no global redirect.** JShell pipes the child's stdout and stderr to the
  `PrintStream` we hand the builder -- unlike an in-process engine, where capture means swapping
  the process-wide `System.out` for the duration and synchronising every evaluation around it.
- **Evaluation stops at the first failed snippet**, as a REPL would. Running the rest against a
  broken definition only compounds the error.
- **`__KotllmIO` is declared into the session, not put on the classpath.** A ten-line Java class
  with `read` and `write`, fully qualified so it survives a reset, evaluated once at startup. That
  avoids shipping a class to the child VM, and `write(String, String)` is what makes
  `write_from_eval` reject a `List` at compile time instead of writing `[a, b]`.
- **Paths are escaped into Java string literals** (`JShellEngine.stringLiteral`). Windows paths
  are full of backslashes, so this is not optional.

Verified live end to end: *"Using Java, not the default session: load the log and write a CSV of
ERROR counts per service, sorted descending"* over the same 803,462-character file. Six calls, and
all five rows matched an independent count exactly.

### The Python session, and a bridge that is not py4j

`python_eval` runs Python in a persistent child process. It is the third engine and the one the
model is most fluent in, which is the honest reason it exists -- and the only one with a real
library ecosystem for data work, since pandas and numpy have no counterpart on the classpath.

**Nothing has to be installed in advance.** On first use it creates a venv in `./pyenv` (config
`python_env`), writes the bootstrap into it, and starts. A `ModuleNotFoundError` triggers a
`pip install` of the missing top-level package and **one** retry. Both are stated in the tool
result -- convenience must not also mean invisible, the same rule as agent auto-approval.

Verified live: *"Use python. Load the log, use pandas to count ERRORs per service, write a CSV."*
**Two calls**, all five rows exact, and the transcript carries
`[installed pandas into .../pyenv]`. That is the whole requirement demonstrated -- the machine had
no pandas and no venv when the call started.

**Out of process, and not for want of alternatives.** Jep embeds CPython over JNI; rejected
because a segfault in a C extension takes the JVM *and the conversation* with it, because
`pip install jep` needs a compiler and an ABI-matched CPython, and because sub-interpreters are
flakiest with numpy -- which is the entire reason to want Python. GraalPy is pure-JVM but its
footprint dwarfs the whole fat jar. An FFM rewrite of Jep would remove the JNI boilerplate and
none of the crash exposure, since FFM's spatial and temporal guarantees protect *Java's* access
to foreign memory, not CPython crashing inside its own.

A child process gives three things no embedding can:

| | in-process | child process |
| --- | --- | --- |
| a crash in evaluated code | takes the session | takes the child; we restart and say so |
| timeout | CPython cannot interrupt a running `exec()` | `destroyForcibly`, always |
| `sys.exit` | needs a static check on the snippet, as `GroovyEngine` has | ends the child, which we notice |

The cost is IPC, and the design spends none of it on payloads: for `read_to_var` the child opens
the file, for `write_from_eval` the child writes it. Only code and answers cross.

**The bridge is JSON, which is py4j's job at a fraction of the size.** py4j runs a gateway
server and marshals live object references so Python can call arbitrary Java methods -- necessary
for Spark's API surface, and enormously more than this needs. Here `bindJson` sends a value and
`getJson` fetches one, over the same protocol, using `json` (stdlib) on one side and
kotlinx.serialization (already bundled) on the other. Nothing reflective, nothing to escape, no
third dependency. A JSON object arrives as a Python `dict`; a list comprehension comes back as a
parsed `JsonArray`.

Things that are load-bearing:

- **Newline-delimited JSON, not length prefixes.** `json.dumps` never emits a literal newline, so
  one object per line is unambiguous *and* a captured stream is readable by eye. Length framing
  would be marginally faster and much harder to debug.
- **Three file descriptors, and the arrangement matters.** A dup of fd 1 taken before anything
  else carries the protocol; fd 1 is then pointed at fd 2, so a native write from a C extension
  -- which `contextlib.redirect_stdout` cannot intercept, because it only rebinds `sys.stdout` --
  lands on stderr instead of corrupting the protocol. stderr is drained continuously on its own
  thread, so a full pipe can never wedge the child.
- **Values and paths travel as data, never interpolated into source.** There is nothing to
  escape: a Windows path full of backslashes, a string with quotes, a nested dict, all of it
  crosses as JSON. `JShellEngine` needs `stringLiteral` for exactly this; this needs nothing.
- **REPL semantics come from an `ast` split**, not from try-`eval`-then-`exec`. The trailing
  expression is popped and evaluated separately, so a block whose last line happens to be an
  expression still runs its earlier statements exactly once.
- **The str check is explicit, because Python cannot refuse it at compile time.** Java refuses a
  `List` where a `String` is wanted and Groovy is checked against the live object; here
  `write_from_eval` checks `isinstance` and names
  the type it got, or a list would be written as `['a', 'b']` and reported as a success.
- **`os.replace` is the atomic primitive** on both POSIX and Windows, so the Python side mirrors
  `FileTools.atomicWrite` in five lines rather than forty.
- **A killed session is reported, loudly.** The timeout costs the namespace, and a variable
  vanishing silently would be miserable, so the message says the variables are gone and suggests
  smaller steps.
- **The bootstrap is a resource, written into the env every time it differs.** Readable,
  syntax-highlightable, separately testable -- and a kotllm upgrade cannot leave a stale copy in
  an environment the previous version created.
- **`pyenv` and `site-packages` joined `util/Skips`.** A venv is tens of thousands of files, none
  of them the user's, and a `grep` over site-packages is never what anyone meant. `pyenv/` is
  also gitignored: 94MB after pandas, and entirely reproducible.
- **Registered only when a launcher answers `--version`**, cached for the session. A machine with
  no Python is never offered a `python` language it cannot use.

### `session_transfer`, and a bridge that had no caller for an increment

`PythonEngine.bindJson` / `getJson` shipped, were documented here as "py4j's job at a fraction of
the size", were covered by tests -- and **were never exposed to the model**. So the only route it
actually had between sessions was `write_from_eval` to a temp file and `read_to_var` back out of
it. The user noticed by watching it do exactly that.

That is the third time this codebase has grown a mechanism nothing reads: `remember` wrote a
`notes.md` nothing loaded, `save: true` was parsed and ignored, and then a working IPC bridge sat
behind a private method. **A test is not a caller.** The lesson that keeps not sticking is that
"built" has to mean "reachable from the surface the model sees", and the check is one grep:
whether anything outside the engine and its spec calls it.

`session_transfer{from, to, expression, variable}` closes it. Beyond saving two calls and a file,
it buys two things the file route structurally could not:

- **Structure survives.** A file carries text, so a Python `dict` arrived in the JVM session as a
  `String` to be re-parsed. Now it arrives as a native Groovy `Map`/`List`, and a Groovy value
  arrives in Python as a real `dict`.
- **Nothing reaches disk that the model can see** -- no path to invent, name, or forget.

Verified live: *"in python build a dict of squares, move it into the groovy session, sum the
values, no files"* -- three calls (`python_eval`, `session_transfer`, `groovy_eval`) and the right
answer.

The SPI grew two methods, and each engine answers differently because the boundaries differ:

| | out (`evalToString`) | in (`bindJson`) |
| --- | --- | --- |
| groovy | the live value, checked to be a `String` | a real `Map`/`List` via `JsonSlurper` |
| python | the JSON protocol | a real `dict`/`list` |
| java | a temp file, ours, deleted | a `String` of JSON |

**The contract is uniform: the source expression must produce JSON text** (`json.dumps(obj)`,
`JsonOutput.toJson(x)`). Per-language magic -- Python auto-serialising while Groovy did not --
would be one more thing to remember per call, and getting it wrong would fail silently.

**The Groovy side binds a native `Map`/`List`, not the parsed `JsonElement`.** kotlinx's
accessors -- `jsonObject`, `jsonPrimitive`, `int` -- are Kotlin extension functions, which compile
to static methods Groovy cannot see as properties. Handing the element over would give the model
an object it can hold but not read, so `JsonSlurper` reparses it into the same shape Python gets.

**Java goes through a temp file on both legs, and that is not laziness.** JShell runs the snippet
in another JVM, so the only channels are the snippet source and the transcript -- and the
transcript reports a value's *Java source representation*, so a String comes back quoted and
escaped, which is a parser nobody should write. On the way in, a string literal cannot exceed
64KB in a class file. The file is ours, never named to the model, and deleted before the call
returns; that is the whole difference from the model managing one.

Registered only with **two or more** sessions: with one there is nowhere to transfer to, and the
tool could only ever refuse.

### The Python session had no stdlib in scope

Found by `session_transfer` failing on its own documented happy path: `_exec` runs with
`globals=_NS`, and `_NS` held only `__name__` and `__builtins__` -- so `json.dumps(x)`, which the
tool description tells the model to write, raised `NameError`.

**Exactly the JShell default-imports bug, in a second language**, and found the same way: the
documented path failed rather than a test catching it. `_NS` is now seeded with `json`, `os`,
`sys`, `re`, `math`, `csv`, `random`, `pathlib`, `datetime`, `collections`, `itertools`,
`functools` and `statistics`, and the tool description says so, or the model spends a call
importing what it already has.

`session_vars` filters seeded modules **by type** (`types.ModuleType`) rather than by a name
list, which would have drifted the moment one was added and started listing plumbing as the
model's working set.

### Writes replace, they do not truncate

`FileTools.atomicWrite` is what `write`, `edit` and the JVM-side `write_from_eval` go through
(JShell's `__KotllmIO.write` mirrors it in Java, which `os.replace`-style is a handful of lines). `Files.write`
opens the target with `TRUNCATE_EXISTING`, so a failure between the truncate and the last byte --
an exception, a full disk, the eval timeout firing -- left the file short or empty. For a tool
whose job is editing the user's source that is the worst bug available, and it is the one case the
read-before-overwrite rule does not cover.

So: a temp file **in the target's own directory** (a rename across filesystems is not atomic and
would fail), then `ATOMIC_MOVE` over the target. A reader sees the old file or the new one, never
a half-written one.

Two details that are easy to get wrong:

- **POSIX permissions are copied from the target before the move.** A fresh temp file is 0600, so
  editing a mode-755 script would silently strip its execute bit. Skipped on filesystems with no
  POSIX attributes rather than failing.
- **`ATOMIC_MOVE` is not universally supported** -- some network and FUSE filesystems refuse it --
  so a plain replacing move is the fallback. That is no longer atomic, which is worth knowing, and
  is still strictly better than truncating in place.

Scope, honestly: this is atomic with respect to readers and to a mid-write failure. It is **not**
durability against power loss, which would need an fsync of the file and its directory. Different
and much rarer failure.

### Capturing output in-process is a global act

`GroovyEngine.evaluate` swaps `System.out` and `System.err` for the duration of a snippet, which
is **process-wide** -- so the whole method is synchronized, and the streams are restored in a
`finally`. Two consequences that are not obvious:

- **The shell is resolved on the calling thread, never inside the worker.** `evaluate` holds the
  monitor while it waits on `join`, so a worker that called `shell()` would block on the same
  monitor and could only ever time out.
- **A timed-out worker is given time to unwind before the streams go back.** Interruption is
  cooperative, so the worker may still be running; restoring `System.out` first would let it
  write into the real console afterwards.

The Scala engine this replaced had a sharper version of the same hazard, kept here because it is
the kind of thing that gets reintroduced. `scala.Console` held its streams in `DynamicVariable`s
whose default is captured on **first touch**. If that first touch happened inside the redirect --
which is exactly what `kotllm --dry-run` with a template did, since nothing had printed yet --
Console kept the throwaway capture stream as its permanent default, `withValue` restored *that*,
and every subsequent `println` wrote into a dead buffer. The symptom was a command that exited 0
and printed absolutely nothing. Groovy has no equivalent indirection, which removes the trap
rather than managing it.

