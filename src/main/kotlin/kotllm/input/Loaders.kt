package kotllm.input

import java.nio.charset.StandardCharsets
import java.nio.file.Path
import kotllm.*

object Loaders {

    private val TimeoutSeconds = 60L

    fun convert(file: Path, extension: String, loaders: Map<String, String>): Outcome<String> {
        return loaders[extension.lowercase()]?.let { command ->
            run(command.replace("$1", quote(file)))
        } ?: err("no document loader configured for .$extension")
    }

    private fun quote(file: Path): String = "\"${file.toAbsolutePath()}\""

    fun run(command: String): Outcome<String> = runCatching {
        val shell = if (System.getProperty("os.name", "").lowercase().contains("win")) {
            listOf("cmd.exe", "/d", "/s", "/c", command)
        } else {
            listOf("sh", "-c", command)
        }

        val process = ProcessBuilder(shell).redirectErrorStream(true).start()
        process.outputStream.close()

        val sink = java.io.ByteArrayOutputStream()
        val drain = Thread(
            {
                try {
                    process.inputStream.transferTo(sink)
                } catch (e: Exception) {
                    // Ignore
                }
            },
            "kotllm-loader"
        )
        drain.isDaemon = true
        drain.start()

        val finished = process.waitFor(TimeoutSeconds, java.util.concurrent.TimeUnit.SECONDS)
        if (!finished) {
            process.destroyForcibly()
            err("timed out after ${TimeoutSeconds}s")
        } else {
            drain.join(2000L)
            val out = String(sink.toByteArray(), StandardCharsets.UTF_8)
            if (process.exitValue() != 0) {
                err("exit code ${process.exitValue()}: ${out.trim().take(200)}")
            } else {
                ok(out)
            }
        }
    }.fold(
        onSuccess = { it },
        onFailure = { e -> err(e.message ?: e.javaClass.simpleName) }
    )
}
