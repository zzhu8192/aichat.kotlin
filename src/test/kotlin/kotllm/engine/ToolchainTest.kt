package kotllm.engine

import kotlin.test.Test
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import io.kotest.matchers.string.shouldStartWith
import io.kotest.matchers.string.shouldNotStartWith
import io.kotest.matchers.collections.shouldBeEmpty

import kotllm.util.BuildInfo

/** What the build says about itself, and whether the system prompt agrees.
  *
  * Both of these exist because of real drift rather than as a matter of
  * principle: a Scala patch bump left `Manifest` telling the model it had a
  * "2.13.17 REPL" while the jar shipped 2.13.18, and the dependency manifest
  * injected into every request is derived from the *runtime* scope, which is
  * the mechanism that keeps a `provided` dependency out of it.
  */
class ToolchainTest {

  @Test fun `the prompt names the Groovy version the jar actually runs`() {
    // The literal in Manifest was a second source of truth for a number the
    // pom already owned, so a patch bump silently made the prompt wrong about
    // the one thing it exists to describe.
    // The prompt is `agents/chat.md` now, so the guard moves with the text:
    // the template must interpolate the version rather than spell one out.
    val real     = groovy.lang.GroovySystem.getVersion()
    val template = kotllm.prompt.ChatAgent.packaged

    template shouldContain "prompt.groovyVersion"
    // A literal would pass the line above and still be wrong, which is
    // exactly how this broke the first time.
    template shouldNotContain real
  }

  @Test fun `the prompt names the running JVM rather than the one it was built for`() {
    // Same defect, second instance: `Java 17` was spelled out in the template
    // and would have been a lie the moment anyone ran it on a newer JDK.
    val template = kotllm.prompt.ChatAgent.packaged

    template shouldContain "prompt.javaVersion"
    template shouldNotContain "Java 17"
  }

  @Test fun `BuildInfo carries the Kotlin version, or admits it is unfiltered`() {
    // The *build* language is still Kotlin, and this is still the guard that
    // the stamp was filtered. It is no longer what the prompt quotes.
    val v = BuildInfo.kotlinVersion
    v shouldNotStartWith "\${"
    val prefix = v.split('.').take(2).joinToString(".")
    KotlinVersion.CURRENT.toString() shouldStartWith prefix
  }

  @Test fun `Spark Connect is provided, so it is not in the shipped manifest`() {
    // The closure is 58.8MB net of what we already ship and ~48 extra GAVs in
    // a block that rides on every request. `provided` is what keeps both out,
    // and the dependency plugin's runtime scope is the mechanism -- if that
    // ever changed to `compile` this is the test that would say so.
    Manifest.dependencies.filter { it.contains("spark") }.shouldBeEmpty()
    Manifest.dependencies.filter { it.contains("log4j") }.shouldBeEmpty()
    Manifest.dependencies.filter { it.contains("icu4j") }.shouldBeEmpty()
  }

  @Test fun `exactly one jline is bundled, after a Scala bump as before one`() {
    // The documented shade hazard: scala-compiler depends on the jline *uber*
    // jar with the jdk8 classifier, and spark-connect 4.2.0 depends on the
    // identical coordinates. Declaring the modular artifacts instead would put
    // every org.jline class in the jar twice at skewed versions.
    val jline = Manifest.dependencies.filter { it.contains("jline") }
    // Empty when running from a thin classpath with no generated manifest.
    if (jline.isNotEmpty()) jline.size shouldBe 1
  }
}
