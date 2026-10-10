package kotllm.engine

import java.io.BufferedReader
import java.io.BufferedWriter
import java.io.ByteArrayOutputStream
import java.io.InputStreamReader
import java.io.OutputStreamWriter
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths as JPaths
import java.util.concurrent.TimeUnit
import kotlinx.serialization.json.*
import kotllm.*

data class PythonConfig(
    val enabled: Boolean,
    /** The interpreter used to *create* the venv. Only needs to exist on PATH. */
    val launcher: String,
    /** Where the venv lives. Relative paths resolve against the working
      * directory, so `./pyenv` is per-project, like `node_modules`. */
    val env: String,
    /** Installed when the venv is first created. Empty by default: every entry
      * is seconds added to a first call that the user did not ask for. */
    val packages: List<String>,
    /** Install a missing module on `ModuleNotFoundError` and retry once. */
    val autoInstall: Boolean,
    val timeoutSeconds: Int
) {
    companion object {
        val default: PythonConfig = PythonConfig(
            enabled = true,
            launcher = "python",
            env = "./pyenv",
            packages = emptyList(),
            autoInstall = true,
            timeoutSeconds = 60
        )
    }
}

/** A persistent CPython session in a child process.
  *
  * **Out of process, deliberately.** Jep embeds CPython through JNI and was
  * rejected because a segfault in a C extension takes the JVM -- and the
  * conversation -- with it, because `pip install jep` needs a compiler and an
  * ABI-matched CPython, and because sub-interpreters are flakiest with numpy,
  * which is the whole reason to want Python. GraalPy's footprint dwarfs the
  * entire fat jar.
  *
  * A child process gives three things no embedding can: a crash kills the child
  * and we restart and say so; `destroyForcibly` is a timeout that always works,
  * where interrupting `exec()` is impossible in CPython; and `sys.exit` needs
  * no static check, since it ends the child and we notice.
  *
  * The cost is IPC, and the design spends none of it on payloads -- the child
  * opens files for `read_to_var` and writes them for `write_from_eval`, so only
  * code and answers cross.
  *
  * **The bridge is JSON, which is py4j's job at a hundredth of the size.**
  * `bindJson` sends a value and `getJson` fetches one over the same
  * newline-delimited protocol, using `json` (stdlib) on one side and
  * kotlinx.serialization (already bundled) on the other. Nothing reflective,
  * no third dependency. See docs/engines.md.
  */
class PythonEngine(private val config: PythonConfig, private val workingDir: () -> Path) : Interpreter {

    override val id: String = Id
    override val describe: String = "Python 3 in a child process"

    private var proc: Child? = null
    private var lastSetupNote: String = ""

    private class Child(val process: Process, val exe: Path) {
        val out: BufferedWriter =
            BufferedWriter(OutputStreamWriter(process.outputStream, StandardCharsets.UTF_8))

        // Renamed from Scala's "in": "in" is a hard keyword in Kotlin.
        val inp: BufferedReader =
            BufferedReader(InputStreamReader(process.inputStream, StandardCharsets.UTF_8))

        /** fd 2 is where the child sends anything that escaped Python-level
          * capture -- a native write from a C extension, or an interpreter crash
          * message. Drained continuously so a full pipe can never wedge the child. */
        val stderr = StringBuilder()

        private val drain = Thread(
            {
                try {
                    val reader = BufferedReader(InputStreamReader(process.errorStream, StandardCharsets.UTF_8))
                    var line = reader.readLine()
                    while (line != null) {
                        synchronized(stderr) {
                            if (stderr.length < 8000) {
                                stderr.append(line)
                                stderr.append('\n')
                            }
                        }
                        line = reader.readLine()
                    }
                } catch (e: Exception) {
                }
            },
            "kotllm-python-stderr"
        )

        init {
            drain.isDaemon = true
            drain.start()
        }

        fun takeStderr(): String = synchronized(stderr) {
            val text = stderr.toString()
            stderr.setLength(0)
            text
        }
    }

    override val started: Boolean get() = synchronized(this) { proc?.process?.isAlive ?: false }

    // ---- venv setup --------------------------------------------------------

    private val envDir: Path
        get() {
            val p = JPaths.get(config.env)
            return (if (p.isAbsolute) p else workingDir().resolve(p)).normalize()
        }

    /** The venv's own interpreter. Windows puts it in `Scripts`, everyone else in
      * `bin`, and there is no portable API for it. */
    private fun venvPython(root: Path): Path =
        if (isWindows) root.resolve("Scripts").resolve("python.exe")
        else root.resolve("bin").resolve("python")

    /** Creates the venv if it is missing, stocks it, and drops the bootstrap in.
      *
      * "I do not want to install anything ahead of time" is the requirement, so
      * this runs itself on first use rather than asking. It is reported, because
      * a first call that silently takes fifteen seconds looks like a hang.
      */
    private fun ensureEnv(): Outcome<Path> {
        val root = envDir
        val exe = venvPython(root)
        val notes = StringBuilder()

        if (!Files.isRegularFile(exe)) {
            notes.append("created a Python environment in $root")
            val created = run(listOf(config.launcher, "-m", "venv", root.toString()), workingDir(), 180)
            if (created is Outcome.Err)
                return err(
                    "could not create a Python environment in $root with " +
                        "'${config.launcher} -m venv': ${created.message}"
                )

            if (!Files.isRegularFile(exe))
                return err("'${config.launcher} -m venv' reported success but $exe is missing")

            if (config.packages.isNotEmpty()) {
                notes.append("; installing ${config.packages.joinToString(", ")}")
                val installed = install(exe, config.packages)
                if (installed is Outcome.Err) notes.append(" (failed: ${installed.message})")
            }
        }

        // Written every time: a kotllm upgrade must not leave a stale bootstrap in
        // an environment created by the previous version.
        try {
            val script = bootstrapSource
            val target = root.resolve("kotllm_bootstrap.py")
            val fresh = !Files.exists(target) ||
                String(Files.readAllBytes(target), StandardCharsets.UTF_8) != script
            if (fresh) Files.write(target, script.toByteArray(StandardCharsets.UTF_8))
        } catch (e: Exception) {
            return err("could not write the bootstrap: ${e.message}")
        }

        lastSetupNote = notes.toString()
        return ok(exe)
    }

    private fun install(exe: Path, packages: List<String>): Outcome<String> =
        run(
            listOf(exe.toString(), "-m", "pip", "install", "--disable-pip-version-check", "-q") + packages,
            workingDir(),
            600
        )

    private fun child(): Outcome<Child> = synchronized(this) {
        val existing = proc?.takeIf { it.process.isAlive }
        if (existing != null) return@synchronized ok(existing)

        ensureEnv().flatMap { exe ->
            try {
                val builder = ProcessBuilder(
                    listOf(exe.toString(), "-u", envDir.resolve("kotllm_bootstrap.py").toString())
                )
                builder.directory(workingDir().toFile())
                val started = builder.start()
                val fresh = Child(started, exe)
                proc = fresh
                ok(fresh)
            } catch (e: Exception) {
                err("could not start the Python session: ${e.message}")
            }
        }
    }

    // ---- the protocol ------------------------------------------------------

    /** One request, one reply line. Serialised, because a session is a single
      * conversation and two overlapping requests would interleave frames. */
    private fun request(payload: JsonObject, timeoutSeconds: Int): Outcome<JsonObject> = synchronized(this) {
        child().flatMap { c ->
            try {
                c.out.write(payload.toString())
                c.out.write("\n")
                c.out.flush()
            } catch (e: Exception) {
                crashed()
                return@synchronized err("the Python session went away while sending: ${e.message}")
            }

            // readLine on the pipe is the wait. A worker plus join is the only way
            // to bound it, since there is no interruptible read here.
            var line: String? = null
            var failure: String? = null
            val worker = Thread(
                {
                    try {
                        line = c.inp.readLine()
                    } catch (e: Exception) {
                        failure = e.message
                    }
                },
                "kotllm-python-read"
            )
            worker.isDaemon = true
            worker.start()
            worker.join(maxOf(1L, timeoutSeconds.toLong() * 1000L))

            if (worker.isAlive) {
                // The reason this engine is a process: a timeout is always
                // enforceable. It costs the session, which is reported rather than
                // hidden, because a variable vanishing silently would be worse.
                val leftover = c.takeStderr()
                crashed()
                err(
                    "[timed out after ${timeoutSeconds}s; the Python session was killed and its " +
                        "variables are gone. Re-bind what you need and try smaller steps.]" +
                        (if (leftover.trim().isEmpty()) "" else "\n$leftover")
                )
            } else if (failure != null) {
                crashed()
                err("could not read from Python: $failure")
            } else if (line == null) {
                val leftover = c.takeStderr()
                crashed()
                err(
                    "the Python session exited" +
                        (if (leftover.trim().isEmpty()) " without a message" else ":\n$leftover")
                )
            } else {
                try {
                    val parsed = Json.parseToJsonElement(line)
                    if (parsed is JsonObject) ok(parsed)
                    else err("unexpected reply from Python: ${parsed.javaClass.simpleName}")
                } catch (e: Exception) {
                    err("could not parse the reply from Python: ${e.message}")
                }
            }
        }
    }

    /** Kills the child from outside, for a forceful Ctrl-C. The namespace goes
      * with it, which the caller is expected to say out loud -- a variable
      * vanishing silently is the failure this engine already guards against on
      * timeout. */
    fun killChild(): Unit = crashed()

    private fun crashed() {
        synchronized(this) {
            proc?.let { c ->
                try {
                    c.process.destroyForcibly()
                } catch (e: Exception) {
                }
            }
            proc = null
        }
    }

    private fun bool(o: JsonObject, key: String): Boolean =
        (o[key] as? JsonPrimitive)?.booleanOrNull ?: false

    private fun str(o: JsonObject, key: String): String? {
        val v = o[key] as? JsonPrimitive
        return if (v != null && v.isString && v.content.isNotEmpty()) v.content else null
    }

    // ---- Interpreter -------------------------------------------------------

    override fun eval(code: String): EvalResult {
        val began = System.currentTimeMillis()
        val result = evalOnce(code)

        val retried: Outcome<Pair<String, String>> =
            if (result is Outcome.Err && config.autoInstall) {
                val moduleName = missingModule(result.message)
                if (moduleName != null) {
                    // "Do not install anything ahead of time", taken to its conclusion:
                    // the module the code needs is fetched and the call retried once.
                    // Reported in the result, never silent.
                    val exe = synchronized(this) { proc?.exe }
                    if (exe != null) {
                        val installed = install(exe, listOf(moduleName))
                        if (installed is Outcome.Ok) {
                            evalOnce(code).map { r ->
                                Pair("[installed $moduleName into $envDir]\n" + r.first, r.second)
                            }
                        } else {
                            val pipErr = (installed as Outcome.Err).message
                            err("${result.message}\n[tried to install $moduleName and pip failed: ${pipErr.take(400)}]")
                        }
                    } else {
                        result
                    }
                } else {
                    result
                }
            } else {
                result
            }

        return when (retried) {
            is Outcome.Ok -> {
                val (transcript, stdout) = retried.value
                EvalResult(
                    ok = true, transcript = transcript, stdout = stdout,
                    elapsedMillis = System.currentTimeMillis() - began
                )
            }
            is Outcome.Err -> EvalResult(
                ok = false, transcript = retried.message, stdout = "",
                elapsedMillis = System.currentTimeMillis() - began,
                timedOut = retried.message.contains("timed out after")
            )
        }
    }

    /** Returns (transcript, stdout). */
    private fun evalOnce(code: String): Outcome<Pair<String, String>> =
        request(buildJsonObject { put("op", "eval"); put("code", code) }, config.timeoutSeconds).flatMap { reply ->
            val note = lastSetupNote.takeIf { it.isNotEmpty() }?.let { "[$it]\n" } ?: ""
            lastSetupNote = ""
            if (bool(reply, "ok"))
                ok(Pair(note + (str(reply, "value")?.let { "==> $it" } ?: ""), str(reply, "stdout") ?: ""))
            else
                err(note + (str(reply, "error") ?: "the Python session reported a failure"))
        }

    override fun bindFile(name: String, path: Path): EvalResult {
        val began = System.currentTimeMillis()
        val reply = request(
            buildJsonObject {
                put("op", "bind_file")
                put("name", name)
                put("path", path.toAbsolutePath().normalize().toString())
            },
            config.timeoutSeconds
        )
        return when (reply) {
            is Outcome.Ok ->
                if (bool(reply.value, "ok"))
                    EvalResult(
                        ok = true, transcript = "", stdout = "",
                        elapsedMillis = System.currentTimeMillis() - began
                    )
                else
                    EvalResult(
                        ok = false, transcript = str(reply.value, "error") ?: "bind failed",
                        stdout = "", elapsedMillis = System.currentTimeMillis() - began
                    )
            is Outcome.Err ->
                EvalResult(
                    ok = false, transcript = reply.message, stdout = "",
                    elapsedMillis = System.currentTimeMillis() - began
                )
        }
    }

    override fun writeExpression(path: Path, expression: String): Outcome<Long> =
        request(
            buildJsonObject {
                put("op", "write")
                put("code", expression)
                put("path", path.toAbsolutePath().normalize().toString())
            },
            config.timeoutSeconds
        ).flatMap { reply ->
            if (bool(reply, "ok")) {
                val written = (reply["written"] as? JsonPrimitive)?.longOrNull
                if (written != null) ok(written) else err("the write reported no size")
            } else {
                err(str(reply, "error") ?: "the write failed")
            }
        }

    /** Straight over the protocol: no file, no transcript, no escaping. This is
      * the leg the bridge was built for, and the one that had no caller until
      * `session_transfer` existed. */
    override fun evalToString(expression: String): Outcome<String> =
        request(buildJsonObject { put("op", "text"); put("code", expression) }, config.timeoutSeconds)
            .flatMap { reply ->
                if (bool(reply, "ok")) {
                    val text = str(reply, "text")
                    if (text != null) ok(text) else err("the expression produced no text")
                } else {
                    err(str(reply, "error") ?: "the expression failed")
                }
            }

    override fun bindJson(name: String, value: JsonElement): Outcome<String> =
        bindJsonValue(name, value).map { summary -> "native ($summary)" }

    private fun bindJsonValue(name: String, value: JsonElement): Outcome<String> =
        request(
            buildJsonObject { put("op", "bind"); put("name", name); put("value", value) },
            config.timeoutSeconds
        ).flatMap { reply ->
            if (bool(reply, "ok")) ok(str(reply, "summary") ?: "")
            else err(str(reply, "error") ?: "bind failed")
        }

    override fun bindings(): List<Binding> {
        if (!started) return emptyList()
        val reply = request(buildJsonObject { put("op", "vars") }, 20)
        return when (reply) {
            is Outcome.Ok ->
                if (bool(reply.value, "ok")) {
                    val varsArr = reply.value["vars"] as? JsonArray ?: JsonArray(emptyList())
                    varsArr.mapNotNull { entry ->
                        val eo = entry as? JsonObject ?: return@mapNotNull null
                        val n = (eo["name"] as? JsonPrimitive)?.content
                        val t = (eo["type"] as? JsonPrimitive)?.content
                        if (n != null && t != null)
                            Binding(n, t, (eo["summary"] as? JsonPrimitive)?.content ?: "?")
                        else null
                    }
                } else emptyList()
            is Outcome.Err -> emptyList()
        }
    }

    // ---- the JSON bridge ---------------------------------------------------

    /** JVM -> Python, as a summary of what arrived. Anything kotlinx.serialization
      * can hold becomes a real Python value. */
    fun bindJsonSummary(name: String, value: JsonElement): Outcome<String> =
        bindJsonValue(name, value)

    /** Python -> JVM. The expression's value, as JSON, so the JVM gets data
      * rather than a printed representation. Non-serialisable objects are
      * refused with their type named, rather than silently becoming a repr. */
    fun getJson(expression: String): Outcome<JsonElement> =
        request(buildJsonObject { put("op", "get"); put("code", expression) }, config.timeoutSeconds)
            .flatMap { reply ->
                if (!bool(reply, "ok")) {
                    err(str(reply, "error") ?: "the expression failed")
                } else {
                    val text = str(reply, "json")
                    if (text == null) {
                        ok(JsonNull)
                    } else {
                        try {
                            ok(Json.parseToJsonElement(text))
                        } catch (e: Exception) {
                            err("could not parse the value as JSON: ${e.message}")
                        }
                    }
                }
            }

    /** Version and path of the interpreter actually in use, for `/info`. */
    fun probe(): Outcome<String> =
        request(buildJsonObject { put("op", "ping") }, 60).flatMap { reply ->
            if (bool(reply, "ok"))
                ok("Python ${str(reply, "version") ?: "?"} (${str(reply, "executable") ?: "?"})")
            else
                err(str(reply, "error") ?: "ping failed")
        }

    override fun close() {
        crashed()
    }

    companion object {
        const val Id = "python"

        val isWindows: Boolean
            get() = System.getProperty("os.name", "").lowercase().contains("win")

        /** The child program, from the jar. A resource rather than a Kotlin string so
          * it stays readable, syntax-highlightable and separately testable. */
        val bootstrapSource: String by lazy {
            val stream = javaClass.getResourceAsStream("/python/bootstrap.py")
                ?: throw IllegalStateException("python/bootstrap.py is missing from the jar")
            try {
                String(stream.readAllBytes(), StandardCharsets.UTF_8)
            } finally {
                stream.close()
            }
        }

        private val Missing = Regex("""ModuleNotFoundError: No module named '([A-Za-z0-9_.\-]+)'""")

        /** The module name out of a traceback, for the auto-install retry. Only the
          * top-level package: `No module named 'sklearn.tree'` installs `sklearn`,
          * since that is what pip understands. */
        fun missingModule(error: String): String? =
            Missing.find(error)?.groupValues?.get(1)?.split('.')?.first()

        /** Whether a launcher exists at all, without building anything. Used to
          * decide registration, so a machine with no Python is never offered a
          * `python` language it cannot use. */
        fun launcherWorks(launcher: String, cwd: Path): Boolean =
            run(listOf(launcher, "--version"), cwd, 30).isOk

        /** A one-shot subprocess, drained on a separate thread -- reading to
          * completion before `waitFor` would block until exit and silently defeat
          * the timeout, which is a mistake this project has already made once in
          * `exec`. */
        fun run(command: List<String>, cwd: Path, timeoutSeconds: Int): Outcome<String> =
            try {
                val builder = ProcessBuilder(command)
                builder.directory(cwd.toFile())
                builder.redirectErrorStream(true)
                val process = builder.start()
                process.outputStream.close()

                val sink = ByteArrayOutputStream()
                val drain = Thread(
                    {
                        try {
                            process.inputStream.transferTo(sink)
                        } catch (e: Exception) {
                        }
                    },
                    "kotllm-python-setup"
                )
                drain.isDaemon = true
                drain.start()

                if (!process.waitFor(timeoutSeconds.toLong(), TimeUnit.SECONDS)) {
                    process.destroyForcibly()
                    err("'${command.first()}' did not finish within ${timeoutSeconds}s")
                } else {
                    drain.join(2000L)
                    val output = String(sink.toByteArray(), StandardCharsets.UTF_8).trim()
                    if (process.exitValue() == 0) ok(output)
                    else err("exit ${process.exitValue()}: ${output.takeLast(600)}")
                }
            } catch (e: Exception) {
                err("${e.javaClass.simpleName}: ${e.message ?: ""}")
            }
    }
}
