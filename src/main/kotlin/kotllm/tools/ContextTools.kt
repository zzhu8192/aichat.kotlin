package kotllm.tools

import kotlinx.serialization.json.*

import kotllm.*

/** Scala's `String.stripLineEnd`: removes exactly one trailing line terminator
  * (`\r\n`, `\r` or `\n`), not every trailing newline. File-private because
  * `Tools.kt` and `FileTools.kt` need the identical behaviour and keep their
  * own copy rather than sharing one across files neither owns. */
private fun String.stripLineEnd(): String {
    val len = length
    if (len == 0) return this
    return when (this[len - 1]) {
        '\n' -> if (len >= 2 && this[len - 2] == '\r') substring(0, len - 2) else substring(0, len - 1)
        '\r' -> substring(0, len - 1)
        else -> this
    }
}

/** Tools that bring context in: memories, retrieval over the active index,
  * the web, and skill bodies.
  *
  * Mixed into [[Tools]]; split out only for file size.
  */
interface ContextTools {
    val AddMemoryName: String get() = "add_or_update_memory"
    val DeleteMemoryName: String get() = "delete_memory"

    /** Creates or replaces one named memory.
      *
      * Replaced `remember`, which could only append unnamed dated lines to a
      * shared `notes.md` -- so a memory recorded wrong was permanent, and went on
      * being injected into every request. An upsert keyed by name is the whole
      * difference; keeping both would have left the cheap-looking tool being the
      * one that cannot be undone.
      *
      * One fact per memory, and the description says so, because the name is the
      * unit of editing and deletion. */
    fun addOrUpdateMemory(memory: kotllm.memory.Memory): Tool = Tool(
        name = AddMemoryName,
        description =
            "Save a durable fact for future sessions. The same name replaces that memory entirely, " +
                "so read it first to amend. One fact per memory, named for what it holds " +
                "('build-commands'), first line a summary. Stable facts only.",
        parameters = buildJsonObject {
            put("type", "object")
            putJsonObject("properties") {
                putJsonObject("name") {
                    put("type", "string")
                    put("description", "Kebab-case name. Reusing one replaces it.")
                }
                putJsonObject("content") {
                    put("type", "string")
                    put("description", "Markdown; first line summarises it.")
                }
            }
            putJsonArray("required") { add("name"); add("content") }
        },
        preview = { args ->
            "${Tool.optStr(args, "name") ?: "?"}: ${(Tool.optStr(args, "content") ?: "").take(120)}"
        },
        run = { args ->
            Tool.str(args, "name").fold(
                onErr = { e -> ToolResult(e, isError = true) },
                onOk = { name ->
                    Tool.str(args, "content").fold(
                        onErr = { e -> ToolResult(e, isError = true) },
                        onOk = { content ->
                            val existed = memory.byName(name).isOk
                            memory.upsert(name, content).fold(
                                onErr = { e -> ToolResult("could not save the memory: $e", isError = true) },
                                onOk = { file ->
                                    val verb = if (existed) "replaced" else "saved"
                                    ToolResult(
                                        "$verb memory '${file.fileName.toString().removeSuffix(".md")}' " +
                                            "(${content.trim().length} chars). It is in the system prompt from the next " +
                                            "request on."
                                    )
                                }
                            )
                        }
                    )
                }
            )
        }
    )

    /** Removes one named memory.
      *
      * Not read-only, and it prompts under `confirm` like `write` and `edit`:
      * deleting a memory destroys something the user may have written by hand
      * and cannot be undone from here. The read-only exemption exists for
      * outbound reads, not for data loss.
      */
    fun deleteMemory(memory: kotllm.memory.Memory): Tool = Tool(
        name = DeleteMemoryName,
        description =
            "Delete a saved memory by name. To change one, use add_or_update_memory.",
        parameters = buildJsonObject {
            put("type", "object")
            putJsonObject("properties") {
                putJsonObject("name") {
                    put("type", "string")
                    put("description", "Name of the memory to remove.")
                }
            }
            putJsonArray("required") { add("name") }
        },
        preview = { args -> Tool.optStr(args, "name") ?: "" },
        run = { args ->
            Tool.str(args, "name").fold(
                onErr = { e -> ToolResult(e, isError = true) },
                onOk = { name ->
                    memory.delete(name).fold(
                        onErr = { e -> ToolResult(e, isError = true) },
                        onOk = { file -> ToolResult("deleted memory '${file.fileName.toString().removeSuffix(".md")}'") }
                    )
                }
            )
        }
    )

    /** Reads one memory in full: the tier-2 fetch.
      *
      * Offered only when at least one memory is over the injection budget, which
      * is the only condition under which it can do anything the system prompt has
      * not -- the same rule as `search` with no active index.
      *
      * Discoverable without prompting, because `Memory.systemPromptSection` lists
      * the overflow by name and points at this tool. A schema announces that a
      * tool exists; only the index announces there is something worth reading. */
    fun readMemory(memory: kotllm.memory.Memory): Tool = Tool(
        name = kotllm.memory.Memory.ReadName,
        description =
            "Read one saved memory in full. Only needed for those listed as over budget.",
        parameters = buildJsonObject {
            put("type", "object")
            putJsonObject("properties") {
                putJsonObject("name") {
                    put("type", "string")
                    put("description", "Name of the memory, as listed in the system prompt.")
                }
            }
            putJsonArray("required") { add("name") }
        },
        preview = { args -> Tool.optStr(args, "name") ?: "" },
        readOnly = true,
        run = { args ->
            Tool.str(args, "name").fold(
                onErr = { e -> ToolResult(e, isError = true) },
                onOk = { name ->
                    memory.byName(name).fold(
                        onErr = { e -> ToolResult(e, isError = true) },
                        onOk = { f -> ToolResult(f.content) }
                    )
                }
            )
        }
    )

    /** The name the model sees. Fixed deliberately: it survived dense vectors
      * being added alongside BM25, and must survive whatever comes next, so the
      * contract never changes underneath a conversation or a saved role. */
    val SearchName: String get() = "search"

    /** Retrieval over the active RAG index.
      *
      * Returns citations, not just text. A retrieved passage the user cannot
      * trace back to a file and a line range is not verifiable, and an
      * unverifiable quotation from their own codebase is worse than none.
      */
    fun search(store: kotllm.rag.RagStore, maxOutputChars: Int = 24_000): Tool = Tool(
        name = SearchName,
        description =
            "Search the user's indexed files and return passages with 'path:startLine-endLine' " +
                "citations. Use for questions about their project rather than general knowledge, and " +
                "cite what you used. Matching is lexical, not semantic: prefer distinctive identifiers " +
                "and error strings, and run several narrow searches rather than one broad one.",
        parameters = buildJsonObject {
            put("type", "object")
            putJsonObject("properties") {
                putJsonObject("query") {
                    put("type", "string")
                    put("description", "Words, identifiers or a phrase to look for.")
                }
                putJsonObject("top_k") {
                    put("type", "integer")
                    put("description", "Passages to return. Default: rag_top_k.")
                }
                putJsonObject("path") {
                    put("type", "string")
                    put(
                        "description",
                        "Restrict results to paths starting with this prefix, relative to the " +
                            "indexed folder, e.g. 'src/main/' or 'docs/'."
                    )
                }
            }
            putJsonArray("required") { add("query") }
        },
        preview = { args ->
            val q = Tool.optStr(args, "query") ?: ""
            Tool.optStr(args, "path")?.let { p -> "$q  (under $p)" } ?: q
        },
        run = { args ->
            Tool.str(args, "query").fold(
                onErr = { e -> ToolResult(e, isError = true) },
                onOk = { query ->
                    val k = Tool.optInt(args, "top_k")?.takeIf { it > 0 } ?: store.defaultTopK
                    store.search(query, k, Tool.optStr(args, "path")).fold(
                        onErr = { e -> ToolResult(e, isError = true) },
                        onOk = { hits ->
                            if (hits.isEmpty())
                                // Not an error: an honest empty result is information, and
                                // flagging it would push the model to retry the same query.
                                ToolResult(
                                    "No passages matched '$query'" +
                                        (Tool.optStr(args, "path")?.let { p -> " under '$p'" } ?: "") +
                                        " in ${store.describeActive}. Try different or fewer keywords."
                                )
                            else ToolResult(renderHits(query, hits, store, maxOutputChars))
                        }
                    )
                }
            )
        }
    )

    private fun renderHits(
        query: String,
        hits: List<Pair<kotllm.rag.Chunk, Double>>,
        store: kotllm.rag.RagStore,
        maxOutputChars: Int
    ): String {
        val header = "${hits.size} passage(s) for '$query' in ${store.describeActive}:"
        val body = StringBuilder(header)
        var budget = maxOutputChars - header.length
        var shown = 0

        hits.forEachIndexed { i, (chunk, score) ->
            val block = "\n\n[${i + 1}] ${chunk.citation}  (score ${"%.4f".format(score)})\n${chunk.text.stripLineEnd()}"
            // Stop on the first passage that does not fit rather than skipping it and
            // continuing: the results are ranked, so a gap in the middle would be
            // more confusing than a truncation at the end.
            if (block.length <= budget) {
                body.append(block)
                budget -= block.length
                shown += 1
            }
        }

        if (shown < hits.size)
            body.append(
                "\n\n[${hits.size - shown} further passage(s) omitted to stay within " +
                    "the result size limit; narrow the query or lower top_k]"
            )
        return body.toString()
    }

    /** The name the model sees for retrieval over the web. Fixed for the same
      * reason as `SearchName`: it is a contract the model learns. */
    val FetchName: String get() = "fetch"

    /** Retrieves one URL or local file as markdown.
      *
      * Exists rather than leaving this to `groovy_eval` and a raw HTTP call because
      * the conversion is where the value is: a documentation page is 80-95%
      * markup, and the raw version would arrive through the interpreter transcript
      * and its output cap. */
    fun fetch(convertHtml: Boolean, maxOutputChars: Int = 24_000): Tool = Tool(
        name = FetchName,
        description =
            "Web page or local file as text; HTML becomes markdown. Cannot log in, run JavaScript, " +
                "or reach a private network.",
        parameters = buildJsonObject {
            put("type", "object")
            putJsonObject("properties") {
                putJsonObject("url") {
                    put("type", "string")
                    put("description", "An http(s) URL, or a path to a local file.")
                }
                putJsonObject("max_chars") {
                    put("type", "integer")
                    put("description", "Truncate the result to this many characters.")
                }
            }
            putJsonArray("required") { add("url") }
        },
        preview = { args -> Tool.optStr(args, "url") ?: "" },
        readOnly = true,
        run = { args ->
            Tool.str(args, "url").fold(
                onErr = { e -> ToolResult(e, isError = true) },
                onOk = { url ->
                    val cap = Tool.optInt(args, "max_chars")?.takeIf { it > 0 } ?: maxOutputChars
                    val page =
                        if (kotllm.input.Fetch.isUrl(url)) kotllm.input.Fetch.get(url, convertHtml)
                        else kotllm.input.Fetch.local(java.nio.file.Paths.get(url), convertHtml)

                    page.fold(
                        onErr = { e -> ToolResult("could not fetch $url: $e", isError = true) },
                        onOk = { p ->
                            val header = "${p.source}${p.savingSummary?.let { s -> "  ($s)" } ?: ""}"
                            val body = if (p.text.length <= cap) p.text else p.text.take(cap)
                            val note =
                                if (p.text.length > cap)
                                    "\n\n[truncated at $cap of ${p.text.length} chars; ask for a larger max_chars " +
                                        "or a more specific page]"
                                else ""
                            if (body.trim().isEmpty())
                                ToolResult(
                                    "$header returned no text. The page may be JavaScript-rendered, which this " +
                                        "cannot run."
                                )
                            else ToolResult("$header\n\n${body.stripLineEnd()}$note")
                        }
                    )
                }
            )
        }
    )

    val WebSearchName: String get() = "web_search"

    /** Keyless web and domain search.
      *
      * The `source` argument matters more than it looks: general web search is
      * scraped HTML that can break, while the four named sources are documented
      * JSON APIs that will not -- and for a question about a library or an error
      * message they are cheaper and better ranked. */
    fun webSearch(search: kotllm.web.Search, config: kotllm.web.SearchConfig): Tool {
        val sources =
            if (config.webEnabled) kotllm.web.SearchSource.all
            else kotllm.web.SearchSource.all.filterNot { it == kotllm.web.Web }

        val siteHint =
            if (config.sites.isEmpty()) ""
            else
                " Domains the user treats as authoritative, worth passing as 'site': " +
                    config.sites.joinToString(", ") + "."

        val webHint =
            if (config.webEnabled) ""
            else
                " General web search is not configured on this machine, so 'source' must be one of the " +
                    "specific sources."

        return Tool(
            name = WebSearchName,
            description =
                "Search the public internet: titles, URLs, snippets. Prefer a specific 'source' where " +
                    "one fits -- they are structured APIs, general web results are ranked pages. " +
                    "Snippets are short: fetch a promising URL." + siteHint + webHint,
            parameters = buildJsonObject {
                put("type", "object")
                putJsonObject("properties") {
                    putJsonObject("query") {
                        put("type", "string")
                        put("description", "What to search for, as keywords rather than a sentence.")
                    }
                    putJsonObject("source") {
                        put("type", "string")
                        putJsonArray("enum") { sources.forEach { add(kotllm.web.SearchSource.name(it)) } }
                        put("description", "Which corpus to search. Defaults to web when available.")
                    }
                    putJsonObject("site") {
                        put("type", "string")
                        put("description", "Restrict a web search to one domain.")
                    }
                    putJsonObject("results") {
                        put("type", "integer")
                        put("description", "How many results to return.")
                    }
                }
                putJsonArray("required") { add("query") }
            },
            preview = { args ->
                val q = Tool.optStr(args, "query") ?: ""
                val src = Tool.optStr(args, "source")?.takeIf { it != "web" }?.let { " [$it]" } ?: ""
                val st = Tool.optStr(args, "site")?.let { " site:$it" } ?: ""
                "$q$src$st"
            },
            readOnly = true,
            run = { args ->
                Tool.str(args, "query").fold(
                    onErr = { e -> ToolResult(e, isError = true) },
                    onOk = { query ->
                        val requested = Tool.optStr(args, "source") ?: "web"
                        val source = kotllm.web.SearchSource.parse(requested)
                        if (source == null)
                            ToolResult(
                                "unknown source '$requested'; use one of: " +
                                    sources.joinToString(", ") { kotllm.web.SearchSource.name(it) },
                                isError = true
                            )
                        else
                            search.query(query, source, Tool.optStr(args, "site"), Tool.optInt(args, "results")).fold(
                                onErr = { e -> ToolResult(e, isError = true) },
                                onOk = { hits ->
                                    if (hits.isEmpty())
                                        // An honest empty result, not an error: flagging it would
                                        // push the model to retry the same query.
                                        ToolResult("No results for '$query' via ${search.describe(source)}.")
                                    else ToolResult(renderHits(query, source, hits, search))
                                }
                            )
                    }
                )
            }
        )
    }

    private fun renderHits(
        query: String,
        source: kotllm.web.SearchSource,
        hits: List<kotllm.web.Hit>,
        search: kotllm.web.Search
    ): String {
        val header = "${hits.size} result(s) for '$query' via ${search.describe(source)}:"
        val body = hits.mapIndexed { i, h ->
            val extra = h.extra?.takeIf { it.isNotEmpty() }?.let { e -> "  ($e)" } ?: ""
            val snippet = h.snippet.trim().takeIf { it.isNotEmpty() }?.let { s -> "\n    ${s.take(300)}" } ?: ""
            "\n\n[${i + 1}] ${h.title}$extra\n    ${h.url}$snippet"
        }.joinToString("")
        return header + body
    }

    val SkillName: String get() = kotllm.skill.Skills.ToolName

    /** Tier 2 of the skills mechanism: the full instructions for one skill.
      *
      * Registered only when skills are enabled and at least one exists, since a
      * tool the model can only fail to use still costs schema tokens on every
      * request. Read-only -- it reads a file the user installed -- so it does not
      * stop to ask; the call is still printed with the skill name.
      */
    fun skill(skills: kotllm.skill.Skills): Tool = Tool(
        name = SkillName,
        description =
            "Load the full instructions for a skill listed in your context: the procedure, its " +
                "directory, and the files bundled with it. Call once per skill you follow.",
        parameters = buildJsonObject {
            put("type", "object")
            putJsonObject("properties") {
                putJsonObject("name") {
                    put("type", "string")
                    put("description", "The skill name, exactly as listed in your context.")
                }
                putJsonObject("arguments") {
                    put("type", "string")
                        put("description", "Optional input for skills referring to \$ARGUMENTS.")
                }
            }
            putJsonArray("required") { add("name") }
        },
        preview = { args -> Tool.optStr(args, "name") ?: "" },
        readOnly = true,
        run = { args ->
            Tool.str(args, "name").fold(
                onErr = { e -> ToolResult(e, isError = true) },
                onOk = { name ->
                    skills.render(name, Tool.optStr(args, "arguments")).fold(
                        onErr = { e -> ToolResult(e, isError = true) },
                        onOk = { text -> ToolResult(text) }
                    )
                }
            )
        }
    )
}
