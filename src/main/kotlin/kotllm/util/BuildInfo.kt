package kotllm.util

import java.util.Properties

/**
 * The version and build time of this jar.
 *
 * Read from `kotllm-build.properties`, which Maven filters at build time, so
 * the pom is the only place a version number is written down. The version was
 * previously a constant in `Cli`, which meant two sources of truth and a
 * banner that could name a version the jar was not -- exactly the confusion
 * the version bump is meant to end.
 *
 * The build time matters as much as the number during development: several
 * builds share a `-SNAPSHOT` version, and it is the timestamp that says which
 * one is on screen.
 */
object BuildInfo {

    private const val ResourceName = "/kotllm-build.properties"

    private val props: Properties = run {
        val p = Properties()
        try {
            this::class.java.getResourceAsStream(ResourceName)?.use { p.load(it) }
        } catch (e: Exception) {
            // Ignore
        }
        p
    }

    /**
     * Unfiltered placeholders mean the class is running from a raw source tree
     * rather than a built jar, which is a fact worth saying rather than
     * printing `${project.version}` at a user.
     */
    private fun read(key: String): String? {
        val value = props.getProperty(key)?.trim() ?: return null
        return if (value.isEmpty() || value.startsWith("\${")) null else value
    }

    val version: String = read("version") ?: "dev"

    val built: String? = read("built")

    /**
     * The Kotlin version this jar was built against -- the *host* language, not
     * the one the model writes. That is Groovy, and `PromptApi.groovyVersion`
     * asks `GroovySystem` for it at render time rather than reading a stamp.
     *
     * Here because `Manifest` once told the model "a live Scala 2.13.17 REPL" as
     * a string literal, so a patch bump silently made the system prompt wrong
     * about the one thing it is describing. Same two-sources-of-truth problem
     * the version property above was introduced to end.
     */
    val kotlinVersion: String = read("kotlin") ?: KotlinVersion.CURRENT.toString()

    /**
     * `1.0.1-SNAPSHOT (built 2026-09-16 23:41:02Z)`, for the banner and
     * `--version`.
     */
    fun full(): String = built?.let { "$version (built $it)" } ?: version
}
