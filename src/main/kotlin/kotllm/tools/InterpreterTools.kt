package kotllm.tools

import java.nio.file.Path

import kotlinx.serialization.json.*

import kotllm.*
import kotllm.engine.Interpreter

/** Tools that run code, and the data-plane tools that move values between
  * interpreter sessions without routing them through the conversation.
  *
  * Mixed into [[Tools]]; split out only for file size.
  */
interface InterpreterTools {

    val JavaEvalName: String get() = "java_eval"

    /** Java, in a JShell session.
      *
      * Beside `groovy_eval` because JShell runs snippets in a separate JVM, so a
      * crash or a runaway loop costs the child rather than the session -- the one
      * engine here that can hard-stop model code. */
    fun javaEval(engine: Interpreter): Tool = Tool(
        name = JavaEvalName,
        description =
            "Java in a live JShell session; state persists. Common java.* packages imported. " +
                "Separate JVM, so a crash or infinite loop costs only this session.",
        parameters = buildJsonObject {
            put("type", "object")
            putJsonObject("properties") {
                putJsonObject("code") {
                    put("type", "string")
                    put("description", "Java code.")
                }
            }
            putJsonArray("required") { add("code") }
        },
        preview = { args -> Tool.optStr(args, "code") ?: "" },
        run = { args ->
            Tool.str(args, "code").fold(
                onErr = { e -> ToolResult(e, isError = true) },
                onOk = { code ->
                    val result = engine.eval(code)
                    ToolResult(result.toToolOutput(), isError = !result.ok)
                }
            )
        }
    )

    val GroovyEvalName: String get() = "groovy_eval"

    /** Groovy, in a live `GroovyShell` session.
      *
      * The default interpreter: it runs in-process, so a value it computes can
      * be handed to the harness as the object itself rather than as text, which
      * is what `read_to_var` and `write_from_eval` ride on.
      *
      * In-process also means a runaway loop is interrupted cooperatively rather
      * than hard-stopped -- `java_eval` remains the one that can kill a child
      * JVM. */
    fun groovyEval(engine: Interpreter): Tool = Tool(
        name = GroovyEvalName,
        description =
            "Groovy in a live session; state persists. Bare assignment persists, `def` does not. " +
                "Last expression is the result.",
        parameters = buildJsonObject {
            put("type", "object")
            putJsonObject("properties") {
                putJsonObject("code") {
                    put("type", "string")
                    put("description", "Groovy code; may span statements.")
                }
            }
            putJsonArray("required") { add("code") }
        },
        preview = { args -> Tool.optStr(args, "code") ?: "" },
        run = { args ->
            Tool.str(args, "code").fold(
                onErr = { e -> ToolResult(e, isError = true) },
                onOk = { code ->
                    val result = engine.eval(code)
                    ToolResult(result.toToolOutput(), isError = !result.ok)
                }
            )
        }
    )

    val PythonEvalName: String get() = "python_eval"

    /** Python, in a persistent child process.
      *
      * The engine the model is most fluent in, and the only one with a real
      * library ecosystem for data work -- pandas and numpy have no counterpart on
      * the classpath.
      *
      * The environment builds itself on first use (a venv in `./pyenv`), and a
      * missing module is fetched and the call retried once. Both are reported in
      * the result: convenience must not also mean invisible. */
    fun pythonEval(engine: kotllm.engine.PythonEngine): Tool = Tool(
        name = PythonEvalName,
        description =
            "Python 3 in a live session; state persists. Trailing expression returned. Stdlib " +
                "preloaded; missing packages install on demand. Best for data work.",
        parameters = buildJsonObject {
            put("type", "object")
            putJsonObject("properties") {
                putJsonObject("code") {
                    put("type", "string")
                    put("description", "Python 3 code.")
                }
            }
            putJsonArray("required") { add("code") }
        },
        preview = { args -> Tool.optStr(args, "code") ?: "" },
        run = { args ->
            Tool.str(args, "code").fold(
                onErr = { e -> ToolResult(e, isError = true) },
                onOk = { code ->
                    val result = engine.eval(code)
                    ToolResult(result.toToolOutput(), isError = !result.ok)
                }
            )
        }
    )

    val ReadToVarName: String get() = "read_to_var"
    val WriteFromName: String get() = "write_from_eval"
    val SessionVarsName: String get() = "session_vars"

    /** The interpreter a `language` argument selects.
      *
      * A required enum with no default, deliberately. Defaulting would make one
      * language the invisible norm, which is the mistake `read` / `read_into`
      * made before they became `read_to_context` / `read_to_var`: the choice
      * belongs in the token the model emits.
      */
    private fun interpreterArg(
        args: JsonElement,
        engines: List<Interpreter>
    ): Outcome<Interpreter> =
        Tool.str(args, "language").flatMap { wanted ->
            val e = engines.find { it.id == wanted.trim().lowercase() }
            if (e != null) ok(e)
            else err("unknown language '$wanted'; available: ${engines.joinToString(", ") { it.id }}")
        }

    private fun languageProperty(engines: List<Interpreter>): JsonElement = buildJsonObject {
        put("type", "string")
        putJsonArray("enum") { engines.forEach { add(it.id) } }
        // The enum beside this already lists the values, and what the model has
        // to emit is the value, not the engine behind it. Spelling out "scala
        // (Scala 2.13 in-process (IMain)), python (Python 3 in a child
        // process)" cost 110 characters in each of three schemas to say what
        // `enum: [scala, python]` says for free.
        put("description", "Which session.")
    }

    /** Reads a file into a named `String` in a live interpreter session.
      *
      * The content goes into the JVM, not the conversation. A 500KB log becomes a
      * `String` the model can run regex and arithmetic over for the price of the
      * code it writes, rather than 150k tokens every later request must carry.
      *
      * The model could already write `val log = os.read(...)`, and that works --
      * IMain truncates an echoed value at 800 characters, so it does not dump the
      * file into the transcript. What the tool adds is discoverability, a result
      * worth its tokens, and the guards below. */
    fun readToVar(
        engines: List<Interpreter>,
        limits: FileToolLimits,
        cwd: () -> Path,
        /** Shared with `write`; binding counts as having seen the file. */
        memo: FileMemo?
    ): Tool =
        Tool(
            name = ReadToVarName,
            description =
                "File -> String variable in a session, NOT into this conversation. Only what you " +
                    "print costs tokens.",
            parameters = buildJsonObject {
                put("type", "object")
                putJsonObject("properties") {
                    putJsonObject("file_path") {
                        put("type", "string")
                        put("description", "File path.")
                    }
                    putJsonObject("variable") {
                        put("type", "string")
                        put("description", "Variable name. Rebinding replaces.")
                    }
                    put("language", languageProperty(engines))
                }
                putJsonArray("required") { add("file_path"); add("variable"); add("language") }
            },
            preview = { args ->
                val f = Tool.optStr(args, "file_path") ?: ""
                val v = Tool.optStr(args, "variable") ?: ""
                val l = Tool.optStr(args, "language") ?: ""
                "$f -> $v ($l)"
            },
            // Reads a file that read_to_context could already have read unprompted
            // and binds a string. It cannot run model-authored code.
            readOnly = true,
            run = { args ->
                Tool.str(args, "file_path").fold(
                    onErr = { e -> ToolResult(e, isError = true) },
                    onOk = { rawPath ->
                        Tool.str(args, "variable").fold(
                            onErr = { e -> ToolResult(e, isError = true) },
                            onOk = { rawName ->
                                interpreterArg(args, engines).fold(
                                    onErr = { e -> ToolResult(e, isError = true) },
                                    onOk = { engine ->
                                        FileTools.validIdentifier(rawName).fold(
                                            onErr = { e -> ToolResult(e, isError = true) },
                                            onOk = { name ->
                                                FileTools.checkText(rawPath, cwd(), limits.bindMaxChars).fold(
                                                    onErr = { e -> ToolResult(e, isError = true) },
                                                    onOk = { checked ->
                                                        val bound = engine.bindFile(name, checked.path)
                                                        if (!bound.ok)
                                                            ToolResult(
                                                                "could not bind '$name' in the ${engine.id} session:\n" +
                                                                    bound.toToolOutput(),
                                                                isError = true
                                                            )
                                                        else {
                                                            // Binding counts as having seen the file: the guard stops
                                                            // *blind* replacement, and a session holding the whole
                                                            // text is not blind.
                                                            memo?.mark(checked.path)
                                                            ToolResult(
                                                                bindReport(
                                                                    name,
                                                                    checked.path,
                                                                    engine,
                                                                    checked.chars,
                                                                    checked.lines,
                                                                    checked.head,
                                                                    cwd()
                                                                )
                                                            )
                                                        }
                                                    }
                                                )
                                            }
                                        )
                                    }
                                )
                            }
                        )
                    }
                )
            }
        )

    /** What comes back: the handle and the shape, never the content.
      *
      * The head preview is deliberately tiny. The model writes code against a
      * file it cannot see, so whether it is JSON, tab-separated or a timestamped
      * log is worth more per token than anything else the budget could buy. */
    private fun bindReport(
        name: String,
        path: Path,
        engine: Interpreter,
        chars: Int,
        lines: Int,
        head: List<String>,
        cwd: Path
    ): String {
        val shown = head.take(3).map { it.take(120) }.joinToString("\n") { "  $it" }
        val more = if (lines > 3) "\n  ..." else ""

        return "bound `$name` as a String in the ${engine.id} session from " +
            "${FileTools.display(path, cwd)}: " + "${"%,d".format(lines)} line(s), ${"%,d".format(chars)} chars. " +
            "Its contents are NOT in this conversation: use " +
            "${engine.id}_eval to compute over `$name` and print or return only the answer." +
            "\n\nFirst lines, for shape only:\n" + shown + more
    }

    /** Writes a value computed in a session straight to a file.
      *
      * The mirror of `read_to_var`: a transformed 800KB file or a filtered CSV
      * reaches disk without being serialised into the conversation and back.
      *
      * Takes an *expression*, not a variable name. `write_from_var` would push
      * the model to write `val tmp = <expr>` first, and a top-level val echoes
      * its own value -- measured. Renamed from `write_from_scala` when Java
      * arrived: a tool named for one language that serves three does not ship. */
    fun writeFromEval(
        engines: List<Interpreter>,
        memo: FileMemo?,
        cwd: () -> Path
    ): Tool =
        Tool(
            name = WriteFromName,
            description =
                "Session expression -> file, not via this conversation. Must yield a String.",
            parameters = buildJsonObject {
                put("type", "object")
                putJsonObject("properties") {
                    putJsonObject("file_path") {
                        put("type", "string")
                        put("description", "Path to write.")
                    }
                    putJsonObject("expression") {
                        put("type", "string")
                        put("description", "Expression yielding a String.")
                    }
                    put("language", languageProperty(engines))
                }
                putJsonArray("required") { add("file_path"); add("expression"); add("language") }
            },
            preview = { args ->
                val f = Tool.optStr(args, "file_path") ?: ""
                val e = Tool.optStr(args, "expression") ?: ""
                val l = Tool.optStr(args, "language") ?: ""
                "$f <- ${(e.lineSequence().firstOrNull() ?: "").take(50)} ($l)"
            },
            run = { args ->
                Tool.str(args, "file_path").fold(
                    onErr = { e -> ToolResult(e, isError = true) },
                    onOk = { rawPath ->
                        Tool.str(args, "expression").fold(
                            onErr = { e -> ToolResult(e, isError = true) },
                            onOk = { expression ->
                                interpreterArg(args, engines).fold(
                                    onErr = { e -> ToolResult(e, isError = true) },
                                    onOk = { engine ->
                                        FileTools.resolve(rawPath, cwd()).fold(
                                            onErr = { e -> ToolResult(e, isError = true) },
                                            onOk = { path ->
                                                val shown = FileTools.display(path, cwd())
                                                if (java.nio.file.Files.isDirectory(path))
                                                    ToolResult("$shown is a directory", isError = true)
                                                // The same data-loss guard `write` applies, over the same memo.
                                                // Without it, "cannot overwrite a file you have not read" would
                                                // have had a one-call bypass, which is not a guard.
                                                else if (java.nio.file.Files.isRegularFile(path) &&
                                                    memo?.let { !it.hasSeen(path) } == true
                                                )
                                                    ToolResult(
                                                        "refusing to overwrite $shown sight unseen: read it first with " +
                                                            "read_to_context or read_to_var, or write to a new path",
                                                        isError = true
                                                    )
                                                else
                                                    engine.writeExpression(path, expression).fold(
                                                        onErr = { e -> ToolResult(e, isError = true) },
                                                        onOk = { chars ->
                                                            memo?.mark(path)
                                                            ToolResult("wrote $shown: ${"%,d".format(chars)} chars from the ${engine.id} session")
                                                        }
                                                    )
                                            }
                                        )
                                    }
                                )
                            }
                        )
                    }
                )
            }
        )

    /** Lists what is currently bound in a session.
      *
      * Not a convenience -- this is what makes holding data in the JVM safe over a
      * long task. Interpreter state outlives the conversation, but the model's
      * *knowledge* of it does not: compaction trims old tool results, so the
      * message saying "bound `log`" can be summarised away while `log` is still in
      * scope, and the model then re-reads a file it already holds.
      *
      * Names, types and sizes; never values, or the listing would undo the
      * feature it serves. */
    fun sessionVars(engines: List<Interpreter>): Tool = Tool(
        name = SessionVarsName,
        description =
            "Variables in a session: name, type and size, not contents. Use when unsure what is " +
                "already loaded.",
        parameters = buildJsonObject {
            put("type", "object")
            putJsonObject("properties") { put("language", languageProperty(engines)) }
            putJsonArray("required") { add("language") }
        },
        preview = { args -> "session variables (${Tool.optStr(args, "language") ?: ""})" },
        readOnly = true,
        run = { args ->
            interpreterArg(args, engines).fold(
                onErr = { e -> ToolResult(e, isError = true) },
                onOk = { engine ->
                    val found = engine.bindings()
                    if (found.isEmpty())
                        // Not an error: an empty session is a fact, and flagging it would
                        // invite a retry that cannot go differently.
                        ToolResult(
                            "nothing is bound in the ${engine.id} session yet. read_to_var binds a file to " +
                                "a variable; anything you define in ${engine.id}_eval also stays in scope."
                        )
                    else {
                        val width = found.maxOf { it.name.length }
                        val rows = found.joinToString("\n") { b -> "  ${b.name.padEnd(width)}  ${b.tpe}  (${b.summary})" }
                        ToolResult("${found.size} name(s) in scope in the ${engine.id} session:\n$rows")
                    }
                }
            )
        }
    )

    val TransferName: String get() = "session_transfer"

    /** Moves a value from one interpreter session to another, without a file and
      * without the conversation.
      *
      * Two things the write-then-read-a-file route could not do, beyond the two
      * wasted calls:
      *
      *  - **Structure survives.** A file carries text, so a Python `dict` arrived
      *    in Groovy as a `String` to be re-parsed. Here it arrives as a native
      *    `Map`/`List`, and a Groovy value arrives in Python as a real `dict`.
      *  - **Nothing reaches disk the model can see.** No temp path to invent.
      *
      * The contract is uniform on purpose: **the source expression must produce
      * JSON text.** Per-language magic -- Python auto-serialising while Groovy did
      * not -- would be one more thing to remember per call, and would fail
      * silently. See docs/engines.md. */
    fun sessionTransfer(engines: List<Interpreter>): Tool = Tool(
        name = TransferName,
        description =
            "Move a value between sessions, with no file and nothing in this conversation. " +
                "expression must produce JSON text: json.dumps(obj) in python, JsonOutput.toJson(x) " +
                "in groovy. Only type and size are reported back.",
        parameters = buildJsonObject {
            put("type", "object")
            putJsonObject("properties") {
                putJsonObject("from") {
                    put("type", "string")
                    putJsonArray("enum") { engines.forEach { add(it.id) } }
                    put("description", "Source session.")
                }
                putJsonObject("to") {
                    put("type", "string")
                    putJsonArray("enum") { engines.forEach { add(it.id) } }
                    put("description", "Target session.")
                }
                putJsonObject("expression") {
                    put("type", "string")
                    put("description", "Evaluated in `from`; must produce JSON text.")
                }
                putJsonObject("variable") {
                    put("type", "string")
                    put("description", "Name to bind in `to`.")
                }
            }
            putJsonArray("required") { add("from"); add("to"); add("expression"); add("variable") }
        },
        preview = { args ->
            val f = Tool.optStr(args, "from") ?: "?"
            val t = Tool.optStr(args, "to") ?: "?"
            val v = Tool.optStr(args, "variable") ?: ""
            "$f -> $t as $v"
        },
        run = { args ->
            fun engineFor(key: String): Outcome<Interpreter> =
                Tool.str(args, key).flatMap { wanted ->
                    val e = engines.find { it.id == wanted.trim().lowercase() }
                    if (e != null) ok(e)
                    else err("unknown session '$wanted'; available: ${engines.joinToString(", ") { it.id }}")
                }

            engineFor("from").fold(
                onErr = { e -> ToolResult(e, isError = true) },
                onOk = { src ->
                    engineFor("to").fold(
                        onErr = { e -> ToolResult(e, isError = true) },
                        onOk = { dst ->
                            Tool.str(args, "expression").fold(
                                onErr = { e -> ToolResult(e, isError = true) },
                                onOk = { expression ->
                                    Tool.str(args, "variable").fold(
                                        onErr = { e -> ToolResult(e, isError = true) },
                                        onOk = { rawName ->
                                            FileTools.validIdentifier(rawName).fold(
                                                onErr = { e -> ToolResult(e, isError = true) },
                                                onOk = { name ->
                                                    if (src.id == dst.id)
                                                        // Not an error worth failing on, but worth naming: a transfer to
                                                        // the same session is a no-op the model should not have spent a
                                                        // call on.
                                                        ToolResult(
                                                            "`from` and `to` are both ${src.id}; the value is already there. Assign it " +
                                                                "with ${src.id}_eval instead.",
                                                            isError = true
                                                        )
                                                    else
                                                        src.evalToString(expression).fold(
                                                            onErr = { e -> ToolResult("could not read from the ${src.id} session:\n$e", isError = true) },
                                                            onOk = { text ->
                                                                val parsed = try {
                                                                    ok(Json.parseToJsonElement(text))
                                                                } catch (e: Exception) {
                                                                    err(
                                                                        "the expression produced ${text.length} chars that are not JSON " +
                                                                            "(${(e.message ?: "").take(120)}). It must produce " +
                                                                            "JSON text -- json.dumps(obj) in python, JsonOutput.toJson(x) in groovy."
                                                                    )
                                                                }

                                                                parsed.fold(
                                                                    onErr = { e -> ToolResult(e, isError = true) },
                                                                    onOk = { value ->
                                                                        dst.bindJson(name, value).fold(
                                                                            onErr = { e -> ToolResult("could not bind in the ${dst.id} session:\n$e", isError = true) },
                                                                            onOk = { shape ->
                                                                                ToolResult(
                                                                                    "moved ${src.id} -> ${dst.id} as `$name`: " +
                                                                                        "$shape, ${"%,d".format(text.length)} chars of JSON. " +
                                                                                        "Nothing was written to disk and the value is not in this " +
                                                                                        "conversation."
                                                                                )
                                                                            }
                                                                        )
                                                                    }
                                                                )
                                                            }
                                                        )
                                                }
                                            )
                                        }
                                    )
                                }
                            )
                        }
                    )
                }
            )
        }
    )
}
