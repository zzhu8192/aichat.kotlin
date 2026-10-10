package kotllm.engine

import java.nio.charset.StandardCharsets

/** The library manifest shown to the model.
  *
  * Generated code is only useful if the model knows what it may import. The
  * authoritative source is `kotllm-dependencies.txt`, written into the jar at
  * build time, so the GAVs are exact rather than inferred from filenames.
  *
  * Deliberately terse: this is prepended to every request in a tool-enabled
  * session, so every line is paid for on every turn. The usage hints exist
  * because naming an artifact does not tell the model the package to import.
  */
object Manifest {

    private val ResourceName = "/kotllm-dependencies.txt"

    /** `group:artifact:version` for each bundled runtime dependency. */
    val dependencies: List<String> by lazy { load() }

    private fun load(): List<String> {
        val stream = javaClass.getResourceAsStream(ResourceName) ?: return emptyList()
        return stream.bufferedReader(StandardCharsets.UTF_8).use { reader ->
            reader.readLines()
                .mapNotNull { line -> parse(line) }
                .distinct()
                .sorted()
        }
    }

    /** Lines look like `   com.lihaoyi:os-lib_2.13:jar:0.11.4:compile`. */
    private fun parse(line: String): String? {
        val parts = line.trim().split(':')
        return if (parts.size >= 5 && parts[2] == "jar") {
            val artifact = parts[1].replace(Regex("_2\\.13$"), "")
            "${parts[0]}:$artifact:${parts[3]}"
        } else null
    }

    /** Packages worth spelling out, because the artifact name does not imply the
      * import. Ordered by how often a model will reach for them, and exposed so
      * the prompt template can lay the table out itself.
      *
      * The call shapes only. Each hint used to open by restating the library it
      * was already keyed by - `os -- os-lib:`, `requests -- requests-scala:` -
      * which is the artifact name, not something the model can type. What stops
      * it guessing at the API is the signatures, so that is all that is left.
      *
      * Three separate doc comments had accumulated here, one per edit. Which of
      * them reached the index was decided by `mutable.Map` iteration order in
      * `Strip`, so the answer was arbitrary and could change between runs. Found
      * by diffing this indexer's output against the Kotlin port's, which is
      * ordered and therefore disagreed. */
    val hints: List<Pair<String, String>> = listOf(
        "groovy.json" to "new JsonSlurper().parseText(str), JsonOutput.toJson(value)",
        "kotlinx.serialization.json" to
            "Json.parseToJsonElement(str), element.toString()",
        "org.yaml.snakeyaml" to "new Yaml().load(text)"
    )
}
