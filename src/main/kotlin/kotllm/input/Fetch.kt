package kotllm.input

import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.time.Duration
import kotllm.*

data class Page(
    val source: String,
    val text: String,
    val rawChars: Int,
    val contentType: String?,
    val convertedFromHtml: Boolean
) {
    val chars: Int get() = text.length

    val savingSummary: String? get() {
        if (!convertedFromHtml || rawChars <= 0) return null
        val pct = kotlin.math.max(0, 100 - (chars.toDouble() / rawChars * 100).toInt())
        return "converted from HTML, $rawChars -> $chars chars ($pct% smaller)"
    }
}

object Fetch {

    private val userAgent: String get() = "kotllm/${kotllm.util.BuildInfo.version}"

    private val client: HttpClient by lazy {
        HttpClient
            .newBuilder()
            .connectTimeout(Duration.ofSeconds(15))
            .followRedirects(HttpClient.Redirect.NORMAL)
            .sslContext(kotllm.util.Tls.context)
            .build()
    }

    fun isUrl(spec: String): Boolean {
        val s = spec.trim().lowercase()
        return s.startsWith("http://") || s.startsWith("https://")
    }

    fun get(
        url: String,
        convertHtml: Boolean = true,
        timeoutSeconds: Int = 60
    ): Outcome<Page> {
        return raw(url, timeoutSeconds).map { (body, contentType) ->
            render(url, body, contentType, convertHtml)
        }
    }

    fun raw(
        url: String,
        timeoutSeconds: Int = 60,
        headers: List<Pair<String, String>> = emptyList()
    ): Outcome<Pair<String, String?>> = runCatching {
        val builder = HttpRequest
            .newBuilder(URI.create(url))
            .timeout(Duration.ofSeconds(kotlin.math.max(1L, timeoutSeconds.toLong())))
            .header("User-Agent", userAgent)
            .header("Accept", "text/html,application/xhtml+xml,text/plain,application/json;q=0.9,*/*;q=0.5")
            .header("Accept-Encoding", "gzip")
            .GET()
        headers.forEach { (k, v) -> builder.header(k, v) }

        val response = client.send(builder.build(), HttpResponse.BodyHandlers.ofByteArray())
        if (response.statusCode() / 100 != 2) {
            err("HTTP ${response.statusCode()}")
        } else {
            val gzipped = response.headers().firstValue("content-encoding").orElse(null)?.lowercase()?.contains("gzip") == true ||
                looksGzipped(response.body())
            val bytes = if (gzipped) gunzip(response.body()) else response.body()
            ok(Pair(String(bytes, StandardCharsets.UTF_8), response.headers().firstValue("content-type").orElse(null)))
        }
    }.fold(
        onSuccess = { it },
        onFailure = { e ->
            val raw = e.message ?: e.javaClass.simpleName
            kotllm.util.Tls.explain(raw)?.let { hint -> err("$raw -- $hint") } ?: err(raw)
        }
    )

    private fun looksGzipped(bytes: ByteArray): Boolean =
        bytes.size > 2 && (bytes[0].toInt() and 0xff) == 0x1f && (bytes[1].toInt() and 0xff) == 0x8b

    private fun gunzip(bytes: ByteArray): ByteArray = runCatching {
        java.util.zip.GZIPInputStream(java.io.ByteArrayInputStream(bytes)).use { it.readAllBytes() }
    }.getOrElse { bytes }

    fun local(path: Path, convertHtml: Boolean = true): Outcome<Page> = runCatching {
        when {
            !Files.exists(path) -> err("no such file")
            Files.isDirectory(path) -> err("is a directory")
            else -> {
                val bytes = Files.readAllBytes(path)
                if (bytes.take(8000).contains(0.toByte())) {
                    err("looks binary")
                } else {
                    val body = String(bytes, StandardCharsets.UTF_8)
                    val hint = extensionOf(path).takeIf { it == "html" || it == "htm" || it == "xhtml" }?.let { "text/html" }
                    ok(render(path.toString(), body, hint, convertHtml))
                }
            }
        }
    }.fold(
        onSuccess = { it },
        onFailure = { e -> err(e.message ?: e.javaClass.simpleName) }
    )

    internal fun render(
        source: String,
        body: String,
        contentType: String?,
        convertHtml: Boolean
    ): Page {
        val declared = contentType?.lowercase()
        val isHtml = when {
            declared?.contains("html") == true || declared?.contains("xml+html") == true -> true
            declared?.contains("text/plain") == true || declared?.contains("json") == true || declared?.contains("markdown") == true -> false
            else -> Html.looksLikeHtml(body)
        }

        return if (convertHtml && isHtml) {
            Page(source, Html.toMarkdown(body), body.length, contentType, convertedFromHtml = true)
        } else {
            Page(source, body, body.length, contentType, convertedFromHtml = false)
        }
    }

    private fun extensionOf(path: Path): String {
        val name = path.fileName.toString()
        val i = name.lastIndexOf('.')
        return if (i <= 0) "" else name.substring(i + 1).lowercase()
    }
}
