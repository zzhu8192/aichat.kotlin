# AGENTS.md — Guidance for Working on kotllm

Concise instructions and hard-learned rules for AI coding agents working on this codebase.

---

## 1. Mandatory Workflow Rules

### Patch Version Bump on Every Fix
* **Always bump the minor patch version in [`pom.xml`](pom.xml)** on every bugfix or meaningful change (e.g., `1.36.0-SNAPSHOT` → `1.36.1-SNAPSHOT`).
* This ensures that binaries, logs, bug reports, and screenshots are unambiguously tied to an exact build.

### Commit Messages
* **Always commit fixes with thorough, structured commit messages** matching the repository's convention:
  ```text
  <version>: <one-line concise summary>

  <Explanation of the symptom and root cause>

  Fixes:
  - <Specific code change / mechanism 1>
  - <Specific code change / mechanism 2>

  Suite: <test count> tests run, 0 failures.
  ```

---

## 2. Architecture & Core Stack

* **Language & Build**: Kotlin 2.4.x targeting JVM 17. Built with Maven (`mvn clean package` produces `target/kotllm.jar`).
* **Terminal UI**: [JLine 3](https://github.com/jline/jline3) (`LineReaderImpl`, `Display`, `Status`, `Terminal`). Custom framing and docking in `kotllm.repl.Chrome` and `kotllm.repl.ReplLineReader`.
* **Scripting Engine**: **Groovy (`groovy.lang.GroovyShell`)**. 
  * The embedded script interpreter is Groovy (not Scala, not Kotlin scripting).
  * Session variables live in `groovy.lang.Binding`. An undeclared assignment (`x = 42`) persists across turns into the session binding.
  * Model code execution (`groovy_eval`), user tools (`config/tools/*.groovy`), and `<% %>` templates all execute via Groovy.

---

## 3. Terminal & REPL (JLine 3) Nuances

* **Docked Prompt & Terminal Scrolling**:
  * In framed mode, the input prompt is docked at `rows - Chrome.ReservedRows - Chrome.PromptRows` (typically `height - 4`), immediately above the 2 pinned status lines.
  * When completions or candidate menus open (e.g., typing `/`), JLine scrolls the terminal buffer up to display items beneath the prompt.
  * When menus close (e.g., user hits Backspace), JLine clears candidate lines via `Display.update()`, but **cannot scroll the terminal buffer back down**.
  * **Solution**: `ReplLineReader` overrides `redisplay(flush)`. When candidate state transitions from open to closed (`menuWasOpen && post == null`), trigger `Repl.redraw()`, call `display.reset()` (to clear JLine's `oldLines` cache), and invoke `redrawLine()`.
* **Status Bar Repaints**:
  * JLine's `Status` caches rendered lines. To force a full redraw without destructive screen clearing, call `chrome.clearStatus()` (updating status with `emptyList()`) before `chrome.refresh()`.

---

## 4. Groovy Interop Gotchas

* **No Named Arguments in Groovy Closures**:
  * Agent functions exposed in the binding (`llm`, `agent`, `system`, etc.) are Groovy closures, not Java/Kotlin methods.
  * Passing `llm("prompt", alias = "fast")` does **not** pass a named argument; it assigns `"fast"` to a binding variable named `alias` and calls `llm(prompt, "fast")` positionally!
  * Always use positional arguments with `null` defaults: `llm(prompt, fresh, alias, tools)`.
* **Calling Kotlin from Groovy**:
  * Kotlin `companion object` methods must be annotated with `@JvmStatic` to be callable as methods from Groovy.
  * Kotlin extension functions compile to static methods with the receiver as the first argument, making them uncallable as member methods from Groovy.
* **Regex and Slashy Strings**:
  * In Groovy slashy strings (`/.../`), backslashes are literal. A Kotlin string literal constructing Groovy source needs `"(\\d+)"`, not `"(\\\\d+)"`.
* **Closures vs Properties**:
  * `args` is a closure, so access it as `args()["key"]`, not `args["key"]`.

---

## 5. Testing & Verification

* **Run Tests**:
  ```powershell
  mvn test
  ```
  The full test suite (~1,239 tests) runs in under 45 seconds. Keep all tests green.
* **Inspect Assembled Prompts**:
  ```powershell
  java -jar target/kotllm.jar --dry-run "hello"
  ```
  `--dry-run` prints the exact assembled system prompt and tool schemas, allowing you to diff prompt token costs.
* **Do Not Assume Dependency Availability**:
  * Maven repository is online; dependencies should be declared in `pom.xml`, not searched blindly across the filesystem.
