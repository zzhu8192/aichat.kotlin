package kotllm.util

import java.nio.file.Path

/**
 * Directories never walked and extensions never read as text.
 *
 * Both sets lived in two places -- `input/Input.scala` for `/file` input and
 * `rag/Rag.scala` for indexing -- and had already drifted, Rag's directory set
 * being a strict superset of Input's. That was recorded as a drift risk worth
 * unifying once a third caller appeared; `glob` and `grep` are that caller.
 *
 * Deliberately *not* shared: the extension-to-language and extension-to-
 * indexable mappings. Those look like the same data and do different jobs --
 * one picks a markdown fence language for display, the other decides what is
 * worth putting in an index.
 */
object Skips {

    /**
     * Build output, vendored trees and editor state. Never what someone means to
     * search or send, and large enough to dominate a walk if included.
     */
    val dirs: Set<String> = setOf(
        "target", "node_modules", ".git", ".svn", ".hg", ".idea", ".vscode", "build", "dist", "out",
        ".metals", ".bloop", "__pycache__", ".venv", "venv", ".tox", ".gradle", ".mvn",
        "vendor", "bin", "obj", ".next", ".nuxt", ".cache", ".pytest_cache", ".mypy_cache",
        // kotllm's own Python environment. Thousands of files, none of them the
        // user's, and `grep` over site-packages is never what anyone meant.
        "pyenv", "site-packages"
    )

    /**
     * Formats that are not text. Listed explicitly so a refusal is predictable
     * and reportable rather than depending on a byte sniff -- though the sniff
     * exists too, for the extensionless and the mislabelled.
     */
    val binaryExtensions: Set<String> = setOf(
        "pdf", "docx", "doc", "xlsx", "xls", "pptx", "ppt", "odt", "ods", "rtf",
        "class", "jar", "war", "ear", "dll", "so", "dylib", "exe", "o", "a", "lib", "pdb",
        "zip", "gz", "bz2", "xz", "7z", "rar", "tar", "tgz", "zst", "lz4",
        "png", "jpg", "jpeg", "gif", "bmp", "ico", "webp", "svgz", "tiff", "psd",
        "mp3", "mp4", "wav", "flac", "ogg", "avi", "mkv", "mov", "webm", "m4a",
        "ttf", "otf", "woff", "woff2", "eot",
        "db", "sqlite", "sqlite3", "mdb", "bin", "dat", "pyc", "pyo", "beam", "wasm",
        "parquet", "avro", "orc", "npy", "npz", "pkl", "onnx", "safetensors", "gguf", "pt", "pth"
    )

    fun extensionOf(path: Path): String {
        val name = path.fileName.toString()
        val i = name.lastIndexOf('.')
        return if (i <= 0) "" else name.substring(i + 1).lowercase()
    }

    fun isBinaryExtension(path: Path): Boolean = binaryExtensions.contains(extensionOf(path))

    /**
     * True when any path segment is a skipped directory. Checked per segment
     * rather than on the leaf, so a walk that started inside one still stops.
     */
    fun containsSkipped(path: Path): Boolean {
        var i = 0
        val count = path.nameCount
        var hit = false
        while (i < count && !hit) {
            if (dirs.contains(path.getName(i).toString())) hit = true
            i += 1
        }
        return hit
    }
}
