package kotllm.util

import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import java.nio.file.StandardCopyOption

/**
 * Replacing a file rather than truncating it.
 *
 * Lifted out of `tools/FileTools` when the second caller appeared -- memories
 * are written by name now, and they are injected into *every* request, so a
 * half-written one is the last thing this should be able to produce. The
 * alternative was `kotllm.memory` reaching into `kotllm.tools`, which is the
 * dependency backwards, or a second copy of the logic. `util/Skips` was
 * extracted on exactly this trigger, and the copies there had already drifted
 * by the time anyone looked.
 */
object AtomicWrite {

    /**
     * Writes content to path by replacing it, not by truncating it.
     *
     * Files.write opens the target with TRUNCATE_EXISTING, so a failure between
     * the truncate and the last byte - an exception, a full disk, the eval
     * timeout firing - leaves the file short or empty. For a tool whose job is
     * editing the user's source that is the worst bug available, and the one case
     * write's read-before-overwrite rule does not cover.
     *
     * So: a temp file **in the target's own directory** (a rename across
     * filesystems is not atomic), then ATOMIC_MOVE over the target.
     *
     * Two details that are easy to get wrong:
     *
     * - POSIX permissions are copied from the target first. A fresh temp
     *   file is 0600, so editing a mode-755 script would strip its execute bit.
     * - ATOMIC_MOVE is not universally supported - some network and FUSE
     *   filesystems refuse it - so a plain replacing move is the fallback.
     *
     * Scope, honestly: atomic with respect to readers and to a mid-write failure.
     * **Not** durability against power loss, which would need an fsync of the
     * file and its directory.
     *
     * Scala spelled this `AtomicWrite(path, text)` through `apply`. Named
     * rather than an `operator fun invoke`, because at a call site
     * `AtomicWrite(path, text)` reads like a constructor for a value nobody
     * keeps.
     */
    fun write(path: Path, content: String) {
        val parent = path.parent ?: Paths.get(".")
        Files.createDirectories(parent)

        val tmp = Files.createTempFile(parent, ".kotllm-", ".tmp")
        try {
            Files.write(tmp, content.toByteArray(StandardCharsets.UTF_8))

            if (Files.exists(path)) {
                try {
                    Files.setPosixFilePermissions(tmp, Files.getPosixFilePermissions(path))
                } catch (e: UnsupportedOperationException) {
                    // Windows
                }
            }

            try {
                Files.move(tmp, path, StandardCopyOption.ATOMIC_MOVE)
            } catch (e: java.nio.file.AtomicMoveNotSupportedException) {
                Files.move(tmp, path, StandardCopyOption.REPLACE_EXISTING)
            }
        } finally {
            // The move consumed it on success; on failure this is what stops a
            // failed edit from littering the user's source directory.
            try {
                Files.deleteIfExists(tmp)
            } catch (e: Exception) {
                // Ignore any exceptions during cleanup
            }
        }
    }
}
