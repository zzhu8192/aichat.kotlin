// Live smoke test for the network paths: DuckDuckGo scraping, the domain APIs,
// and HTML -> markdown on a real page. Run with:
//
//   java -cp target/kotllm.jar groovy.ui.GroovyMain scripts/smoke.groovy
//
// Not a unit test on purpose -- every assertion here depends on somebody else's
// website being up, which is not a reason for the build to fail. Behind a
// TLS-inspecting proxy, note what `kotllm.util.Tls.describe` reports: without
// the system trust store most of these fail with an opaque PKIX error.
//
// Kotlin interop, and it is the whole reason this file reads the way it does:
// an `object` is reached through `INSTANCE`, a `data object` likewise, and
// Kotlin default arguments do not exist in the classfile -- so every parameter
// is passed positionally here even where the Kotlin call site omits it.
import kotllm.web.Search
import kotllm.web.SearchConfig
import kotllm.web.DdgLite
import kotllm.web.None
import kotllm.web.Web
import kotllm.web.StackOverflow
import kotllm.web.Wikipedia
import kotllm.web.Maven
import kotllm.web.GitHub
import kotllm.input.Fetch
import kotllm.util.Tls

println("trust: ${Tls.INSTANCE.describe}")

def show = { String label, outcome ->
    if (outcome instanceof kotllm.Outcome.Err) {
        println("$label: FAILED -- ${outcome.message.take(160)}")
    } else {
        def hits = outcome.value
        println("$label: ${hits.size()} hit(s)")
        hits.take(2).each { h ->
            println("   ${h.title.take(70)}")
            println("     ${h.url.take(90)}")
            if (h.snippet) println("     ${h.snippet.take(80)}")
            if (h.extra) println("     [${h.extra}]")
        }
    }
}

// engine, apiBase, results, timeoutSeconds, sites, stackSite, enabled
def ddg = new Search(new SearchConfig(DdgLite.INSTANCE, null, 3, 15, [], "stackoverflow", true))
def apis = new Search(new SearchConfig(None.INSTANCE, null, 3, 15, [], "stackoverflow", true))

// text, source, site, limit
show("ddg-lite", ddg.query("groovy 5 closures delegate", Web.INSTANCE, null, null))
show("ddg site:", ddg.query("closures", Web.INSTANCE, "groovy-lang.org", null))
show("stackoverflow", apis.query("groovy closure delegate", StackOverflow.INSTANCE, null, null))
show("wikipedia", apis.query("Apache Groovy", Wikipedia.INSTANCE, null, null))
show("maven coords", apis.query("org.apache.groovy:groovy", Maven.INSTANCE, null, null))
show("maven keyword", apis.query("groovy-json", Maven.INSTANCE, null, null))
show("github", apis.query("groovy repl language:groovy", GitHub.INSTANCE, null, null))

// url, convertHtml, timeoutSeconds
def page = Fetch.INSTANCE.get("https://groovy-lang.org/closures.html", true, 60)
if (page instanceof kotllm.Outcome.Err) {
    println("fetch: FAILED -- ${page.message.take(160)}")
} else {
    println("fetch: ${page.value.savingSummary ?: 'no conversion'}")
}
