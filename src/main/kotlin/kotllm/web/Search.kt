package kotllm.web

import java.net.URLEncoder
import java.nio.charset.StandardCharsets
import kotlinx.serialization.json.*
import kotllm.*
import kotllm.input.Fetch
import kotllm.input.Html

data class Hit(val title: String, val url: String, val snippet: String, val extra: String? = null)

sealed interface SearchSource {
    companion object {
        fun name(s: SearchSource): String = when (s) {
            is Web -> "web"
            is StackOverflow -> "stackoverflow"
            is Wikipedia -> "wikipedia"
            is Maven -> "maven"
            is GitHub -> "github"
        }

        fun parse(s: String): SearchSource? = when (s.trim().lowercase()) {
            "web", "general", "" -> Web
            "stackoverflow", "so", "stack" -> StackOverflow
            "wikipedia", "wiki" -> Wikipedia
            "maven", "mvn", "maven-central" -> Maven
            "github", "gh" -> GitHub
            else -> null
        }

        // `by lazy`: see TaskStatus.all. Initialising SearchSource runs the
        // companion, which reads nested objects whose own initialisers need
        // SearchSource back, and the JVM answers null rather than deadlocking.
        val all: List<SearchSource> by lazy { listOf(Web, StackOverflow, Wikipedia, Maven, GitHub) }
    }
}

data object Web : SearchSource
data object StackOverflow : SearchSource
data object Wikipedia : SearchSource
data object Maven : SearchSource
data object GitHub : SearchSource

sealed interface Engine {
    companion object {
        fun name(e: Engine): String = when (e) {
            is None -> "none"
            is DdgLite -> "ddg-lite"
            is DdgHtml -> "ddg-html"
            is Searxng -> "searxng"
        }

        fun parse(s: String): Engine? = when (s.trim().lowercase()) {
            "none", "off", "false" -> None
            "ddg-lite", "ddglite", "duckduckgo" -> DdgLite
            "ddg-html", "ddghtml" -> DdgHtml
            "searxng", "searx" -> Searxng
            else -> null
        }
    }
}

data object None : Engine
data object DdgLite : Engine
data object DdgHtml : Engine
data object Searxng : Engine

data class SearchConfig(
    val engine: Engine = None,
    val apiBase: String? = null,
    val results: Int = 5,
    val timeoutSeconds: Int = 15,
    val sites: List<String> = emptyList(),
    val stackSite: String = "stackoverflow",
    val enabled: Boolean = true
) {
    companion object {
        val default: SearchConfig = SearchConfig()
    }

    // True when general web search is available. The named JSON sources work
    // regardless, which is why this is not "is search enabled".
    val webEnabled: Boolean get() = engine != None
}

// Keyless web and domain search.
//
// No account, no key, no signup - that was the requirement, and the cost is a
// split personality: the general engines are scraped HTML and will break
// when the markup changes, while Stack Exchange, Wikipedia, Maven Central and
// GitHub are documented JSON APIs that will not.
//
// Breakage is therefore reported as breakage rather than as an empty result
// set, because those mean very different things to whoever has to fix it.
class Search(val config: SearchConfig) {

    fun query(
        text: String,
        source: SearchSource = Web,
        site: String? = null,
        limit: Int? = null
    ): Outcome<List<Hit>> {
        val n = kotlin.math.max(1, kotlin.math.min(limit ?: config.results, 25))
        val q = site?.takeIf { it.trim().isNotEmpty() }?.let { "$text site:${it.trim()}" } ?: text

        return when (source) {
            is Web -> web(q, n)
            is StackOverflow -> stackExchange(text, n)
            is Wikipedia -> wikipedia(text, n)
            is Maven -> maven(text, n)
            is GitHub -> github(text, n)
        }
    }

    // What produced the results, for the tool output header.
    fun describe(source: SearchSource): String = when (source) {
        is Web -> Engine.name(config.engine)
        is StackOverflow -> "stackexchange:${config.stackSite}"
        is Wikipedia -> "wikipedia"
        is Maven -> "maven central"
        is GitHub -> "github"
    }

    // ---- general web -------------------------------------------------------

    private fun web(q: String, n: Int): Outcome<List<Hit>> = when (config.engine) {
        is None -> err("general web search is not configured. Set 'web_search: { provider: ddg-lite }' in config.yaml, or search a specific source instead (stackoverflow, wikipedia, maven, github).")
        is DdgLite -> scrape("https://lite.duckduckgo.com/lite/?q=${enc(q)}", n)
        is DdgHtml -> scrape("https://html.duckduckgo.com/html/?q=${enc(q)}", n)
        is Searxng -> {
            val base = config.apiBase?.removeSuffix("/")
            if (base == null) {
                err("searxng needs 'web_search: { api_base: <instance url> }'")
            } else {
                searxng("$base/search?q=${enc(q)}&format=json", n)
            }
        }
    }

    // DuckDuckGo's server-rendered pages.
    //
    // Both endpoints wrap outbound links in a redirect (`/l/?uddg=<encoded>`),
    // which is unwrapped here: the model needs the real URL to `fetch` it, and
    // a redirect through DDG would also be a second request they can rate
    // limit.
    private fun scrape(url: String, n: Int): Outcome<List<Hit>> =
        Fetch.raw(url, config.timeoutSeconds).fold(
            onOk = { (body, _) ->
                val links = ResultLink
                    .findAll(body)
                    .map { m ->
                        val (href, text) = hrefAndText(m)
                        (unwrap(href) to Html.plainText(text))
                    }
                    .filter { (u, t) -> u.startsWith("http") && t.isNotEmpty() }
                    .toList()

                val snippets = ResultSnippet.findAll(body).map { m -> Html.plainText(m.groupValues[2]) }.toList()

                if (links.isEmpty()) {
                    err("the search engine returned no parseable results. This provider is scraped HTML, so the page layout may have changed; try 'web_search: { provider: ddg-html }' or a specific source.")
                } else {
                    ok(
                        links.zip(snippets).take(n).mapIndexed { i, (urlTitle, snippet) ->
                            Hit(urlTitle.second, urlTitle.first, snippet)
                        }
                    )
                }
            },
            onErr = { err(it) }
        )

    private fun searxng(url: String, n: Int): Outcome<List<Hit>> =
        json(url, emptyList()).fold(
            onOk = { v ->
                val results = try {
                    arr(v, "results").take(n).map { r ->
                        Hit(str(r, "title"), str(r, "url"), str(r, "content"))
                    }
                } catch (e: Exception) {
                    emptyList()
                }
                if (results.isEmpty()) {
                    err("the searxng instance returned no results; it may have JSON output disabled")
                } else {
                    ok(results)
                }
            },
            onErr = { err(it) }
        )

    // ---- documented JSON APIs ---------------------------------------------

    // Stack Exchange. 300 requests a day unauthenticated, which is plenty, and
    // the score and accepted-answer flag are what make a result worth reading
    // before fetching it.
    private fun stackExchange(q: String, n: Int): Outcome<List<Hit>> {
        val url =
            "https://api.stackexchange.com/2.3/search/advanced" +
                "?order=desc&sort=relevance&pagesize=$n&site=${enc(config.stackSite)}&q=${enc(q)}"
        return json(url, emptyList()).fold(
            onOk = { v ->
                ok(
                    arr(v, "items").take(n).map { item ->
                        val score = num(item, "score")
                        val answers = num(item, "answer_count")
                        val accepted = bool(item, "is_answered")
                        Hit(
                            Html.plainText(str(item, "title")),
                            str(item, "link"),
                            "",
                            "score $score, $answers answer(s)${if (accepted) ", answered" else ""}"
                        )
                    }
                )
            },
            onErr = { err(it) }
        )
    }

    private fun wikipedia(q: String, n: Int): Outcome<List<Hit>> {
        val url =
            "https://en.wikipedia.org/w/api.php?action=query&format=json&utf8=1&list=search" +
                "&srlimit=$n&srsearch=${enc(q)}"
        return json(url, emptyList()).fold(
            onOk = { v ->
                ok(
                    try {
                        val results = if (v is JsonObject) {
                            (v["query"] as? JsonObject)?.let { arr(it, "search") } ?: emptyList()
                        } else {
                            emptyList()
                        }
                        results.take(n).map { r ->
                            val title = str(r, "title")
                            Hit(
                                title,
                                "https://en.wikipedia.org/wiki/${enc(title.replace(' ', '_'))}",
                                Html.plainText(str(r, "snippet"))
                            )
                        }
                    } catch (e: Exception) {
                        emptyList()
                    }
                )
            },
            onErr = { err(it) }
        )
    }

    // Maven Central, which answers what a build file actually asks: the current
    // version of an artifact, and whether it exists.
    //
    // Two routes, because one is not always reachable. `search.maven.org` is the
    // searchable index and times out from behind some corporate proxies - it did
    // from this machine - while `repo1.maven.org` is static file hosting,
    // reachable wherever Maven itself works. Coordinates therefore go straight
    // to the metadata file, which is faster and exact.
    private fun maven(q: String, n: Int): Outcome<List<Hit>> {
        val coordinates = Coordinates.find(q.trim())
        return when {
            coordinates != null -> {
                val m = metadata(coordinates.groupValues[1], coordinates.groupValues[2])
                m.fold(
                    onOk = { ok(it) },
                    onErr = { solr(q, n) }
                )
            }
            else -> {
                val solrResult = solr(q, n)
                solrResult.fold(
                    onOk = { ok(it) },
                    onErr = { coordinateHint(q) }
                )
            }
        }
    }

    // `<latest>` and `<release>` out of maven-metadata.xml.
    //
    // Regex rather than an XML parser: the file has exactly three fields worth
    // reading, and pulling in a parser to read them would widen the classpath
    // handed to the LLM for nothing.
    private fun metadata(group: String, artifact: String): Outcome<List<Hit>> {
        fun lookup(name: String): Outcome<List<Hit>> {
            val path = group.replace('.', '/')
            val url = "https://repo1.maven.org/maven2/$path/$name/maven-metadata.xml"
            return Fetch.raw(url, config.timeoutSeconds).fold(
                onOk = { (body, _) ->
                    val release = tagValue(body, "release") ?: tagValue(body, "latest")
                    val versions = VersionTag.findAll(body).map { it.groupValues[1] }.toList()
                    if (release == null) {
                        err("no versions listed for $group:$name")
                    } else {
                        ok(
                            listOf(
                                Hit(
                                    "$group:$name:$release",
                                    "https://central.sonatype.com/artifact/$group/$name",
                                    "${versions.size} version(s) published, most recent ${versions.takeLast(4).asReversed().joinToString(", ")}",
                                    "from maven-metadata.xml"
                                )
                            )
                        )
                    }
                },
                onErr = { err(it) }
            )
        }

        // A Scala library is published per binary version, so the artifact a user
        // types is usually one suffix short of what exists.
        val r1 = lookup(artifact)
        return r1.fold(
            onOk = { ok(it) },
            onErr = { lookup("${artifact}_2.13") }
        )
    }

    private fun coordinateHint(q: String): Outcome<List<Hit>> =
        err("the Maven Central search index did not answer for '$q'. It is unreachable from some networks; pass coordinates instead ('com.lihaoyi:os-lib') and the version is read straight from repo1.maven.org.")

    private fun solr(q: String, n: Int): Outcome<List<Hit>> {
        val url = "https://search.maven.org/solrsearch/select?wt=json&rows=$n&q=${enc(q)}"
        return json(url, emptyList()).fold(
            onOk = { v ->
                ok(
                    try {
                        val docs = if (v is JsonObject) {
                            (v["response"] as? JsonObject)?.let { arr(it, "docs") } ?: emptyList()
                        } else {
                            emptyList()
                        }
                        docs.take(n).map { d ->
                            val g = str(d, "g")
                            val a = str(d, "a")
                            val version = str(d, "latestVersion").takeIf { it.isNotEmpty() } ?: str(d, "v")
                            val packaging = str(d, "p").takeIf { it.isNotEmpty() }?.let { "packaging $it" } ?: ""
                            Hit(
                                "$g:$a:$version",
                                "https://central.sonatype.com/artifact/$g/$a",
                                "",
                                packaging.takeIf { it.isNotEmpty() }
                            )
                        }
                    } catch (e: Exception) {
                        emptyList()
                    }
                )
            },
            onErr = { err(it) }
        )
    }

    // GitHub repositories. Code search needs authentication, so it is not
    // offered rather than returning a 401 the model cannot act on; a
    // GITHUB_TOKEN in the environment is used when present, purely to raise
    // the unauthenticated rate limit.
    private fun github(q: String, n: Int): Outcome<List<Hit>> {
        val url = "https://api.github.com/search/repositories?per_page=$n&q=${enc(q)}"
        val token = System.getenv("GITHUB_TOKEN")?.takeIf { it.trim().isNotEmpty() }
            ?: System.getenv("GH_TOKEN")?.takeIf { it.trim().isNotEmpty() }
        val headers = listOf("Accept" to "application/vnd.github+json") +
            (token?.let { listOf("Authorization" to "Bearer $it") } ?: emptyList())

        return json(url, headers).fold(
            onOk = { v ->
                ok(
                    try {
                        arr(v, "items").take(n).map { r ->
                            Hit(
                                str(r, "full_name"),
                                str(r, "html_url"),
                                str(r, "description"),
                                "${num(r, "stargazers_count")} stars" + str(r, "language").takeIf { it.isNotEmpty() }?.let { ", $it" }.orEmpty()
                            )
                        }
                    } catch (e: Exception) {
                        emptyList()
                    }
                )
            },
            onErr = { err(it) }
        )
    }

    // ---- plumbing ----------------------------------------------------------

    private fun json(url: String, headers: List<Pair<String, String>>): Outcome<JsonElement> =
        Fetch.raw(url, config.timeoutSeconds, headers + listOf("Accept" to "application/json")).fold(
            onOk = { (body, _) ->
                try {
                    ok(Json.parseToJsonElement(body))
                } catch (e: Exception) {
                    err("the response was not JSON: ${body.take(120).replace(Regex("\\s+"), " ")}")
                }
            },
            onErr = { err(it) }
        )

    private fun enc(s: String): String = URLEncoder.encode(s, StandardCharsets.UTF_8.name())

    private fun arr(v: JsonElement, key: String): List<JsonElement> = when (v) {
        is JsonObject -> {
            when (val item = v[key]) {
                is JsonArray -> item.toList()
                else -> emptyList()
            }
        }
        else -> emptyList()
    }

    private fun str(v: JsonElement, key: String): String = when (v) {
        is JsonObject -> {
            when (val item = v[key]) {
                is JsonPrimitive -> item.contentOrNull ?: ""
                else -> ""
            }
        }
        else -> ""
    }

    private fun num(v: JsonElement, key: String): Long = when (v) {
        is JsonObject -> {
            when (val item = v[key]) {
                is JsonPrimitive -> item.longOrNull ?: 0L
                else -> 0L
            }
        }
        else -> 0L
    }

    private fun bool(v: JsonElement, key: String): Boolean = when (v) {
        is JsonObject -> {
            when (val item = v[key]) {
                is JsonPrimitive -> item.booleanOrNull ?: false
                else -> false
            }
        }
        else -> false
    }

    companion object {
        // An anchor carrying DuckDuckGo's result-link class, in either attribute
        // order. Written as two alternatives rather than one clever pattern because
        // the failure mode of clever here is silently matching nothing.
        internal val ResultLink =
            ("""(?is)(?:<a[^>]*class=["']?[^"'>]*result-link[^"'>]*["']?[^>]*href=["']([^"']+)["'][^>]*>(.*?)</a>""" +
                """|<a[^>]*href=["']([^"']+)["'][^>]*class=["']?[^"'>]*result-link[^"'>]*["']?[^>]*>(.*?)</a>)""").toRegex()

        // Either attribute order matched, so the captures land in one pair or the
        // other.
        internal fun hrefAndText(m: MatchResult): Pair<String, String> =
            (m.groupValues.getOrNull(1).orEmpty().takeIf { it.isNotEmpty() } ?: m.groupValues.getOrNull(3).orEmpty()) to
                (m.groupValues.getOrNull(2).orEmpty().takeIf { it.isNotEmpty() } ?: m.groupValues.getOrNull(4).orEmpty())

        // The snippet cell. Closes on a backreference to its own tag name, because
        // `(.*?)</[a-z]+>` stops at the first nested `</b>` - DuckDuckGo bolds the
        // query terms inside the snippet, so that truncated every result at the
        // first highlighted word.
        internal val ResultSnippet =
            """(?is)<([a-z]+)[^>]*class=["']?[^"'>]*result-snippet[^"'>]*["']?[^>]*>(.*?)</\1>""".toRegex()

        // `group:artifact`, or `group/artifact`, which is how coordinates get typed
        // in practice.
        internal val Coordinates =
            """^([A-Za-z0-9_.\-]+)[:/]([A-Za-z0-9_.\-]+)$""".toRegex()

        internal val VersionTag = """<version>([^<]+)</version>""".toRegex()

        internal fun tagValue(xml: String, tag: String): String? =
            """<$tag>([^<]+)</$tag>""".toRegex().find(xml)?.groupValues?.get(1)?.trim()

        // Unwraps `//duckduckgo.com/l/?uddg=<percent-encoded>&rut=...` and
        // protocol-relative URLs.
        internal fun unwrap(href: String): String {
            val direct = if (href.startsWith("//")) "https:$href" else href
            val marker = "uddg="
            val at = direct.indexOf(marker)
            return if (at < 0) {
                direct
            } else {
                val tail = direct.substring(at + marker.length)
                val end = tail.indexOf('&')
                val enc = if (end < 0) tail else tail.substring(0, end)
                try {
                    java.net.URLDecoder.decode(enc, StandardCharsets.UTF_8.name())
                } catch (e: Exception) {
                    direct
                }
            }
        }
    }
}
