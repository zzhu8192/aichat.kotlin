# Building

## Build and run

```sh
mvn -q clean package                      # fat jar -> target/kotllm.jar
java -jar target/kotllm.jar               # REPL mode
java -jar target/kotllm.jar "one shot"    # CMD mode (single hit, no session)
mvn -q test                               # JUnit 5 + Kotest suites
mvn -q -Plive test                        # including tests tagged @Tag("Live")
```

`KOTLLM_CONFIG_DIR` overrides the config directory (default `~/.config/kotllm`, or
`%APPDATA%\kotllm` on Windows). Config file: `config.yaml`. None of this changed with the port.

## Maven and the network

**Maven is online here, and `-o` is not required.**
Build normally; new dependencies resolve from Maven Central on demand.
Earlier notes in this repo assumed an offline-only build and pushed people towards `mvn -o`.
That was never a constraint of the machine, and working around it by pinning to whatever happened
to be cached is how a version bump turns into an afternoon.

### Corporate Proxies & Custom CA Certificates

If building behind an intercepting corporate proxy or VPN that terminates TLS with internal CA certificates not present in the standard JDK truststore, you can configure Maven wagon transport options via `MAVEN_OPTS`:

```sh
export MAVEN_OPTS="-Dmaven.wagon.http.ssl.insecure=true -Dmaven.wagon.http.ssl.allowall=true \
  -Dmaven.wagon.http.ssl.ignore.validity.dates=true \
  -Dresolver.transport=wagon -Dmaven.resolver.transport=wagon"
```

A PowerShell footgun that costs a build every time: `-D` arguments must be quoted.
`mvn -Dmaven.test.skip=true package` is parsed by PowerShell into something Maven reads as a
lifecycle phase named `.test.skip=true`; write `mvn "-Dmaven.test.skip=true" package` instead.

**Build and test verification:**
The build compiles and passes all unit and integration test suites (`1,240+` tests) cleanly on Java 21 LTS with Maven 3.9+. Run `mvn test` to execute the full suite. To include live endpoint tests (which require configured API credentials), pass `-Plive`.

### Versioning

**Bump the pom version on every change worth telling apart.** That was the Scala project's
discipline, starting at `1.0.1-SNAPSHOT`; it lapsed while the port was in flight, and it is back.
A screenshot, a bug report or a running binary can then be tied to one build.

The port spent its duration pinned at `0.1.0-SNAPSHOT`, on the argument that sharing the Scala
project's numbering would make two very different binaries claim the same version. That argument
expired when the port did, and the numbering rejoined the line it continues: the build is at
`1.35.x-SNAPSHOT`.

The mechanism is unchanged: the number is written down in exactly one
place, `pom.xml` filters it into `kotllm-build.properties`, `util/BuildInfo.kt` reads it, and
`Cli`'s version output, `--version`, the REPL banner and `/info` all delegate to that one read.

`BuildInfo.full` appends the **build timestamp**, and that is the part that actually does the work
during development: several builds can share one `-SNAPSHOT` version, and the timestamp is what
says which of them is on screen. Put the version in the commit message too, so the git history and
a running binary can be lined up from either end.

Unfiltered placeholders mean the class is running from a source tree rather than a built jar, and
`BuildInfo` reports `dev` rather than printing `${project.version}` at a user.

**The interpreter's version travels a different channel, and deliberately so.** `groovy_eval`
drives `groovy.lang.GroovyShell`, so the version string the model sees in the system prompt has to
be the version actually loaded. `chat.md` interpolates `<%= prompt.groovyVersion %>`, which asks
`GroovySystem.getVersion()` at render time -- no build stamp involved, so it cannot go stale even
in a jar assembled against a different Groovy. `ToolchainTest` asserts the template interpolates
it *and* that the literal version does not appear anywhere in the file, because a literal would
pass the first check and still be wrong. That is exactly how it broke the first time, when
`Manifest` spelled out "a live Scala 2.13.17 REPL".

`BuildInfo.kotlinVersion` remains, and is now only about the *host* language: it is the guard
that the stamp was filtered at all.

**Never rebuild into a jar a session is running from.** `java -jar` holds the jar open, maps its
directory and loads classes lazily, so `mvn package` mid-session leaves the JVM executing stale
code until the next first-time class load, which then fails. It surfaces as a
`NoClassDefFoundError` for whatever happened to be touched first and therefore looks random --
`LineReaderImpl$BellType` is the usual one, because JLine only loads it on the first terminal
bell, which can be many minutes into a session. The class is in the jar; the *old* jar is what
the JVM is still reading. This hazard is a JVM classloading fact, so it applies here exactly as
it did before.

Two mitigations, both cheap, both verified still present in this tree: `runit.bat`/`runit.sh`
copy the jar to a temp directory and run the copy, so a build cannot pull the file out from under
a session (nothing depends on the jar's location -- the interpreter classpath comes from
`java.class.path` and the dependency manifest from a resource inside the jar); and `Main.kt`
catches `LinkageError` (see `staleJar`) to say so in one sentence instead of printing a stack
trace that reads like a shading bug.

---
