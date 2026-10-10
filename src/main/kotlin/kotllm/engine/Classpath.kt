package kotllm.engine

import java.io.File
import java.nio.file.Files
import java.nio.file.Paths as JPaths

/** Resolves the classpath that LLM-authored code compiles against.
  *
  * Two sources, in order: whatever this JVM was launched with - under
  * `java -jar` that is the single fat jar, which already contains every bundled
  * library - then entries appended at runtime, so a dependency can be added to
  * a running session without rebuilding (`classpath_append`,
  * `KOTLLM_CLASSPATH`, or `.classpath add <path>`).
  */
object Classpath {

    /** Entries this JVM started with. */
    fun runtimeEntries(): List<String> =
        System.getProperty("java.class.path", "")
            .split(File.pathSeparatorChar)
            .map { it.trim() }
            .filter { it.isNotEmpty() }

    /** Additional entries from configuration and the environment.
      *
      * A directory entry is passed through as-is; a `*` wildcard is expanded,
      * because a trailing-star classpath entry is a shell and JVM convention
      * that the Scala compiler settings do not honour themselves.
      */
    fun extraEntries(configured: List<String>): List<String> {
        val fromEnv = (System.getenv("KOTLLM_CLASSPATH") ?: System.getenv("SCALLM_CLASSPATH"))
            ?.split(File.pathSeparatorChar) ?: emptyList()
        return (configured + fromEnv)
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .flatMap { expand(it) }
            .distinct()
    }

    private fun expand(entry: String): List<String> =
        if (!entry.endsWith("*")) listOf(entry)
        else {
            val dir = JPaths.get(entry.dropLast(1))
            if (!Files.isDirectory(dir)) emptyList()
            else {
                try {
                    Files.list(dir).use { stream ->
                        stream
                            .filter { p -> p.fileName.toString().endsWith(".jar") }
                            .map { p -> p.toAbsolutePath().toString() }
                            .sorted()
                            .toList()
                    }
                } catch (e: Exception) {
                    emptyList()
                }
            }
        }

    /** Splits entries into those that exist and those that do not.
      *
      * A missing entry is reported rather than silently dropped: a typo in
      * `classpath_append` would otherwise surface much later as a puzzling
      * "object X is not a member of package Y" from the compiler.
      */
    fun partitionExisting(entries: List<String>): Pair<List<String>, List<String>> =
        entries.partition { e -> Files.exists(JPaths.get(e)) }

    fun join(entries: List<String>): String = entries.joinToString(File.pathSeparator)
}
