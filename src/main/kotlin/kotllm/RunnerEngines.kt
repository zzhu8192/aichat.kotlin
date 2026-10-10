package kotllm

import kotllm.config.Config
import kotllm.engine.Classpath
import kotllm.engine.Interpreter
import kotllm.engine.Interpreters
import kotllm.engine.JShellEngine
import kotllm.engine.GroovyEngine
import kotllm.engine.PythonEngine

/** The three interpreter sessions a runner can offer, and the classpath they
  * see.
  *
  * An `abstract class` rather than an `interface`, because the state below is
  * genuinely this type's own: `engineOpt`, `javaEngineOpt`, `pythonProbe` and
  * `pythonEngineOpt` are backing fields a Kotlin interface cannot hold, and
  * `extraClasspath` is computed once, not recomputed on every read. [[Runner]]
  * extends this directly -- there is no self-type in Kotlin, so instead of
  * reaching back into `Runner` for `config` and `say` the way the Scala trait
  * did, the constructor takes a one-time config/quiet snapshot for the eager
  * `extraClasspath` check, and `liveConfig()` is a hook `Runner` overrides for
  * everything computed lazily. That split matters: a superclass's own field
  * initializers run *before* a subclass's constructor-parameter properties are
  * assigned, so reading `Runner.configRef` through an overridden method from
  * here during construction would observe it before it is ever set. `engine`,
  * `javaEngine`, `pythonEngine` and `close` are all created lazily, well after
  * construction finishes, so calling `liveConfig()` from them is safe and keeps
  * `/set` able to retune the eval timeout or turn Python on. Split out only for
  * file size.
  *
  * All three sessions are created on first use. Starting a compiler costs a
  * second or two, JShell launches a second JVM and Python builds a venv, and a
  * session that never calls a tool must not pay for any of it.
  */
abstract class RunnerEngines(initialConfig: Config, initialQuiet: Boolean) {

    /** Reads the config live, so a value set after construction (`/set`) is
      * still honoured by the lazily-created engines below. Overridden by
      * [[Runner]] to return its own live `config`. */
    protected abstract fun liveConfig(): Config

    /** Jars appended beyond the bundled set; the prompt template lists them.
      *
      * Computed once, from the config as it was at construction time -- the
      * same snapshot Scala's `val extraClasspath` captured, since nothing could
      * have called `/set` before a runner exists to receive the command. */
    val extraClasspath: List<String> = run {
        val (existing, missing) = Classpath.partitionExisting(Classpath.extraEntries(initialConfig.classpathAppend))
        if (!initialQuiet) {
            missing.forEach { m -> System.err.println("kotllm: classpath entry not found, ignoring: $m") }
        }
        existing
    }

    /** The interpreter everything else goes through: `groovy_eval`, templates,
      * and the agent API.
      *
      * Created on first use, not at construction: starting a compiler costs a
      * second or two and a session that never calls a tool should not pay for
      * it. An explicit nullable rather than a `by lazy`, so `close` can avoid
      * instantiating the very thing it is trying to release. */
    private var engineOpt: GroovyEngine? = null

    val engine: GroovyEngine
        get() = synchronized(this) {
            engineOpt ?: GroovyEngine(
                // Only the appended jars: this class's own loader is the
                // shell's parent and already provides everything bundled.
                extraClasspath,
                liveConfig().evalTimeoutSeconds.toLong() * 1000L
            ).also { engineOpt = it }
        }

    /** Starts the interpreter off the critical path.
      *
      * Lazy construction was right while only `groovy_eval` needed a compiler: a
      * session that never called a tool never paid for one. Making the system
      * prompt a template changed that -- every turn renders `agents/chat.md`
      * through the interpreter, so the first turn of every session now waits on
      * compiler startup whether or not any tool is used.
      *
      * **Measured at 5.7 seconds**, not the 0.65 that was claimed when the
      * template was introduced; that figure came from a benchmark whose engine
      * had an empty classpath and was failing. `RunnerThrottleSpec` caught it,
      * asserting a request within budget completes in under 500ms.
      *
      * Same trick `UserTools` already uses for the same reason: the work
      * overlaps with the user reading the banner and typing, so the wall clock
      * they experience is unchanged. Nothing waits on this thread -- `engine`
      * is synchronized, so a turn arriving early simply blocks until it is
      * ready, which is what it did before.
      */
    fun warmEngineInBackground() {
        val t = Thread(
            {
                try {
                    engine
                } catch (e: Exception) {
                    // A failed warm-up is not fatal: the next real call to
                    // `engine` will try again and report the failure properly.
                }
            },
            "kotllm-engine-warmup"
        )
        t.isDaemon = true
        t.start()
    }

    /** The Java session, when the JDK carries `jdk.jshell` and config allows it.
      *
      * Lazy for the same reason as the Scala engine: JShell launches a second JVM
      * on first use, and a session that never calls it should not pay for one.
      * `Interpreters.jshellAvailable` is probed without touching `JShellEngine`,
      * so a trimmed runtime answers "no" instead of throwing.
      */
    private var javaEngineOpt: JShellEngine? = null

    val javaEngine: Interpreter?
        get() = synchronized(this) {
            val cfg = liveConfig()
            if (!cfg.javaEval || !Interpreters.jshellAvailable()) null
            else {
                if (javaEngineOpt == null) {
                    javaEngineOpt = JShellEngine(
                        Classpath.runtimeEntries() + extraClasspath,
                        cfg.evalTimeoutSeconds.toLong() * 1000L
                    )
                }
                javaEngineOpt
            }
        }

    /** The session templates render through, which is [engine].
      *
      * Its own name because it must be available whether or not the model is
      * offered `groovy_eval`: the system prompt is a template and renders
      * either way. Same instance, so a value a template computes stays visible
      * to the tool calls that follow it.
      */
    val templateEngine: GroovyEngine get() = engine

    /** The same session, withdrawn when `groovy_eval` is off so the model is
      * never offered a tool the config disabled. */
    val groovyEngine: Interpreter?
        get() = synchronized(this) {
            if (!liveConfig().groovyEval) null else engine
        }

    /** The Python session.
      *
      * Probed with `python --version` rather than assumed: a machine without a
      * launcher must never be offered a `python` language it cannot use, the same
      * rule as `search` with no index. The probe is cached, since it costs a
      * process and the answer does not change mid-session.
      */
    private var pythonProbe: Boolean? = null
    private var pythonEngineOpt: PythonEngine? = null

    val pythonEngine: PythonEngine?
        get() = synchronized(this) {
            val cfg = liveConfig()
            if (!cfg.python.enabled) null
            else {
                if (pythonProbe == null) {
                    pythonProbe = PythonEngine.launcherWorks(
                        cfg.python.launcher,
                        java.nio.file.Paths.get(System.getProperty("user.dir", ".")).toAbsolutePath()
                    )
                }
                if (pythonProbe != true) null
                else {
                    if (pythonEngineOpt == null) {
                        pythonEngineOpt = PythonEngine(cfg.python) {
                            java.nio.file.Paths.get(System.getProperty("user.dir", ".")).toAbsolutePath()
                        }
                    }
                    pythonEngineOpt
                }
            }
        }

    /** Releases the interpreter if one was ever started. */
    fun close() {
        synchronized(this) {
            engineOpt?.close()
            engineOpt = null
        }
    }
}
