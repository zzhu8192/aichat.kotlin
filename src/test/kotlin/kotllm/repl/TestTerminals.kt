package kotllm.repl

import java.io.InputStream
import java.io.OutputStream
import java.nio.charset.StandardCharsets

import org.jline.terminal.Size
import org.jline.terminal.Terminal
import org.jline.terminal.impl.DumbTerminal
import org.jline.terminal.impl.ExternalTerminal

/** A real, cursor-addressable JLine terminal over byte streams.
 *
 * Constructed directly rather than through `TerminalBuilder`, which enumerates
 * the OS terminal providers even when the caller supplied its own streams and
 * wants nothing from the console. On this machine that enumeration reaches
 * JLine's `exec` provider, which shells out to a child process to decide
 * whether stdin is a tty -- and under surefire that child never returns, so
 * `build()` blocks forever. It took the whole suite with it: `mvn test` stalled
 * in this package and had never once run to completion.
 *
 * `ExternalTerminal` is the class the builder would have chosen anyway, so the
 * terminal these tests get is the same one, minus the probing.
 */
internal fun testTerminal(
    input: InputStream,
    output: OutputStream,
    type: String = "xterm-256color",
    size: Size = Size(80, 24)
): Terminal =
    ExternalTerminal("kotllm-test", type, input, output, StandardCharsets.UTF_8)
        .also { it.size = size }

/** A dumb terminal: no cursor addressing, so no pinned area and no overlay.
 *
 * Exactly what `TerminalBuilder.dumb(true)` builds, constructed directly for
 * the same reason as [testTerminal] -- the builder probes the OS before it
 * gets as far as honouring `dumb`.
 */
internal fun dumbTestTerminal(input: InputStream, output: OutputStream): Terminal =
    DumbTerminal("kotllm-test", Terminal.TYPE_DUMB, input, output, StandardCharsets.UTF_8)
