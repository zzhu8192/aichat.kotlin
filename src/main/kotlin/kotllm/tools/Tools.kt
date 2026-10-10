package kotllm.tools

import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.nio.charset.StandardCharsets
import java.util.concurrent.TimeUnit

import kotlinx.serialization.json.*

import kotllm.*
import kotllm.engine.Interpreter
import kotllm.util.Cancellation

/** Scala's `String.stripLineEnd`: removes exactly one trailing line terminator
  * (`\r\n`, `\r` or `\n`), not every trailing newline. Kept file-private
  * because `FileTools.kt` needs the identical behaviour and defines its own
  * copy rather than sharing one across files neither owns. */
private fun String.stripLineEnd(): String {
    val len = length
    if (len == 0) return this
    return when (this[len - 1]) {
        '\n' -> if (len >= 2 && this[len - 2] == '\r') substring(0, len - 2) else substring(0, len - 1)
        '\r' -> substring(0, len - 1)
        else -> this
    }
}

/** The built-in tools, and the registry `Runner` builds from config.
  *
  * The tool definitions themselves live in [[InterpreterTools]],
  * [[ContextTools]] and [[SessionTools]]; this file keeps `exec` and
  * `defaults`.
  */
object Tools : InterpreterTools, ContextTools, SessionTools {

    val ExecName = "exec"

    /** Runs a shell command.
      *
      * Goes through the platform shell rather than exec-ing the binary directly,
      * so pipes, redirection and globbing behave the way the model expects when
      * it writes a one-liner.
      */
    fun exec(defaultTimeoutSeconds: Int, maxOutputChars: Int = 16_000): Tool = Tool(
        name = ExecName,
        description =
            "Shell command; stdout+stderr combined. Pipes work. Prefer groovy_eval for computation.",
        parameters = buildJsonObject {
            put("type", "object")
            putJsonObject("properties") {
                putJsonObject("command") {
                    put("type", "string")
                    put("description", "Command line.")
                }
                putJsonObject("cwd") {
                    put("type", "string")
                    put("description", "Working dir. Default: kotllm's cwd.")
                }
                putJsonObject("timeout_seconds") {
                    put("type", "integer")
                    put("description", "Kill after N seconds.")
                }
            }
            putJsonArray("required") { add("command") }
        },
        preview = { args -> Tool.optStr(args, "command") ?: "" },
        run = { args ->
            Tool.str(args, "command").fold(
                onErr = { e -> ToolResult(e, isError = true) },
                onOk = { command ->
                    runShell(
                        command,
                        Tool.optStr(args, "cwd"),
                        Tool.optInt(args, "timeout_seconds") ?: defaultTimeoutSeconds,
                        maxOutputChars
                    )
                }
            )
        }
    )

    private fun shellCommand(command: String): List<String> =
        if (System.getProperty("os.name", "").lowercase().contains("win"))
            listOf("cmd.exe", "/d", "/s", "/c", command)
        else listOf("sh", "-c", command)

    private fun runShell(
        command: String,
        cwd: String?,
        timeoutSeconds: Int,
        maxOutputChars: Int
    ): ToolResult {
        val dir = cwd?.let { File(it) } ?: File(System.getProperty("user.dir", "."))
        if (!dir.isDirectory)
            return ToolResult("cwd '${dir.path}' is not a directory", isError = true)

        val pb = ProcessBuilder(shellCommand(command))
            .directory(dir)
            // Merged, because a model reading the result needs the interleaving to
            // understand a failure, and two separate blocks lose the ordering.
            .redirectErrorStream(true)

        val process = pb.start()
        process.outputStream.close() // no stdin: a prompt would hang the turn

        // Drained on a separate thread. Reading to completion on this thread first
        // would block until the process exits, which silently defeats the timeout
        // below -- waitFor would then always find it already finished.
        val sink = ByteArrayOutputStream()
        val drain = Thread(
            {
                try {
                    process.inputStream.transferTo(sink)
                } catch (e: IOException) {
                    // stream closed by the kill
                }
            },
            "kotllm-exec-drain"
        )
        drain.isDaemon = true
        drain.start()

        // Registered so a forceful Ctrl-C can reach the child. Interrupting this
        // thread would not: `waitFor` would wake, but the command would carry on
        // running with nothing left holding its handle.
        val finished =
            Cancellation.around("exec: ${command.take(40)}", { process.destroyForcibly() }) {
                process.waitFor(maxOf(1, timeoutSeconds).toLong(), TimeUnit.SECONDS)
            }
        if (!finished) {
            process.destroyForcibly()
            // Give the drain a moment to flush what the command managed to print,
            // which is often the most useful part of a hang.
            drain.join(500L)
            val partial = truncate(String(sink.toByteArray(), StandardCharsets.UTF_8), maxOutputChars)
            return ToolResult(
                "[timed out after ${timeoutSeconds}s and was killed]\n$partial".stripLineEnd(),
                isError = true
            )
        }

        drain.join(2000L)
        val output = String(sink.toByteArray(), StandardCharsets.UTF_8)
        val code = process.exitValue()
        val body = truncate(output, maxOutputChars).stripLineEnd()
        val text = if (body.isEmpty()) "(no output)" else body
        return if (code == 0) ToolResult(text) else ToolResult("exit code $code\n$text", isError = true)
    }

    private fun truncate(s: String, max: Int): String =
        if (s.length <= max) s
        else {
            val half = max / 2
            s.take(half) + "\n... [${s.length - max} chars elided] ...\n" + s.takeLast(half)
        }

    /** Everything, in the order the model should prefer them. */
    fun defaults(
        engine: Interpreter,
        execTimeoutSeconds: Int,
        memory: kotllm.memory.Memory,
        /** Registered only when RAG is enabled. Whether it is *offered* on a
          * given request additionally depends on an index being active, which
          * `Runner.activeTools` decides per request so that `/rag index` takes
          * effect immediately. */
        rag: kotllm.rag.RagStore? = null,
        /** Retrieval over the public internet. `web_search` is registered only
          * when a general engine or the named sources are wanted; `fetch` is
          * always registered, because it can only follow a URL that is already
          * in the conversation. */
        webSearchConfig: kotllm.web.SearchConfig = kotllm.web.SearchConfig.default,
        htmlToMarkdown: Boolean = true,
        /** The file family. None disables the lot -- see `file_tools`. Listed
          * first because for anything touching the user's tree these are the
          * right call, and a model works down the list it is given. */
        /** The six-tool file family, per `file_tools`. A Boolean rather than a
          * constructed instance so that `defaults` can own the `FileMemo` and
          * hand the same one to `write_from_eval`, which writes too. */
        fileTools: Boolean = true,
        /** Registered only when a skill actually exists. */
        skills: kotllm.skill.Skills? = null,
        /** Caps for the file family, and `read_to_var`'s bind ceiling. */
        fileToolLimits: FileToolLimits = FileToolLimits.default,
        /** The Java session, when `jdk.jshell` is present and `java_eval` is
          * enabled. `null` withdraws `java` from every `language` enum, so the
          * model is never offered a session that does not exist. */
        java: Interpreter? = null,
        /** The Python session, when a launcher was found. Same rule. */
        python: kotllm.engine.PythonEngine? = null,
        /** Who to put an `ask_user` question to, and whether anyone is there.
          * `null` withdraws the tool entirely. */
        prompter: Prompter? = null,
        interactive: () -> Boolean = { false },
        /** Search over persisted conversations. `null` when `save:` is off or
          * nothing has been stored -- a search tool over an empty corpus is a
          * schema the model can only waste a call on. */
        chats: Pair<kotllm.config.ChatSearch, () -> String?>? = null,
        /** The session's checklist. Always present in a real runner; optional so
          * a test building a bare tool set does not have to construct one. */
        checklist: kotllm.task.Checklist? = null,
        /** Families withheld unless asked for, each on cost alone: see the
          * `taskTools` group in `Config`. Defaulting to false here as well as
          * in the config keeps a bare test tool set small. */
        memoryTools: Boolean = false,
        webTools: Boolean = false,
        transferTool: Boolean = false,
        /** Scala tools compiled from `<config dir>/tools/`. Appended last, and
          * `ToolRegistry` resolves a clash first-wins and reports it, so a user
          * tool cannot silently replace a built-in. */
        userTools: List<Tool> = emptyList()
    ): List<Tool> {
        // One memo across every tool that can replace a file. With the file family
        // switched off there is no `read_to_context` to satisfy a guard with, so
        // there is no guard -- one that nothing can satisfy is a deadlock, not a
        // safety feature.
        val memo = FileMemo()
        val guard: FileMemo? = if (fileTools) memo else null

        // The sessions the data-plane tools can address. Built from what actually
        // exists rather than from what is implemented, the same rule as `search`
        // with no index: a `language` value the model can only fail to use costs
        // schema tokens and invites a pointless call.
        val engines: List<Interpreter> = listOfNotNull(engine, java, python)

        return buildList {
            if (fileTools) addAll(FileTools(fileToolLimits, FileTools.processDir, memo).all)
            add(groovyEval(engine))
            java?.let { add(javaEval(it)) }
            python?.let { add(pythonEval(it)) }
            // Beside the eval tools rather than beside the file family: these three
            // are session tools, and each is half of a pattern whose other half is
            // always an eval.
            add(readToVar(engines, fileToolLimits, FileTools.processDir, guard))
            add(writeFromEval(engines, guard, FileTools.processDir))
            add(exec(execTimeoutSeconds))
            // Only with somewhere to transfer *to*. One session means the tool can
            // only ever refuse, which is schema tokens for nothing.
            if (transferTool && engines.size > 1) add(sessionTransfer(engines))
            skills?.let { add(skill(it)) }
            // `read_memory` is registered unconditionally and *offered* only while
            // something is over the injection budget -- `Runner.activeTools` decides
            // that per request. Deciding it here would freeze the answer at
            // construction, so a memory saved mid-session could not become readable
            // until a restart. Same split as `search` and an active index.
            checklist?.let { c ->
                add(createTasks(c))
                add(updateTask(c))
            }
            // `get_sys_dt` stays on regardless: the model has no clock,
            // and 72 tokens is cheaper than a wrong date nobody notices.
            add(timestamp())
            if (memoryTools) {
                add(addOrUpdateMemory(memory))
                add(deleteMemory(memory))
                add(readMemory(memory))
            }
            // Registered only when a prompter exists at all. A tool whose every call
            // must be refused still costs schema tokens and invites the call.
            prompter?.let { add(askUser(it, interactive)) }
            chats?.let { (cs, current) ->
                add(searchChats(cs, current))
                add(viewChat(cs))
            }
            rag?.let { add(search(it)) }
            if (webTools) {
                add(fetch(htmlToMarkdown))
                add(webSearch(kotllm.web.Search(webSearchConfig), webSearchConfig))
            }
            addAll(userTools)
        }
    }
}
