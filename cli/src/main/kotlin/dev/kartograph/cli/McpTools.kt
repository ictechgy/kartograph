package dev.kartograph.cli

import dev.kartograph.analysis.ImpactFilter
import dev.kartograph.analysis.ImpactSort
import dev.kartograph.analysis.SymbolDiscovery
import dev.kartograph.analysis.SymbolSuggestionPage
import dev.kartograph.core.NodeKind
import dev.kartograph.core.NodeId
import dev.kartograph.export.McpJsonCodec
import dev.kartograph.export.QuerySnapshot
import java.nio.file.Path

/** 요청은 논리적인 그래프 선택자만 받으며 파일 접근 문맥은 시작 시 고정한다. */
internal class McpTools(
    private val current: QuerySnapshot,
    private val base: QuerySnapshot?,
    private val metadata: Map<String, Any?>,
    private val project: Path?,
    private val expectedScope: String?,
    private val external: Map<String, Path>,
) {
    fun call(name: String, arguments: Map<String, Any?>): Map<String, Any?> {
        val definition = definitions.firstOrNull { it["name"] == name }
            ?: throw IllegalArgumentException("Unknown tool; use tools/list")
        validate(arguments, definition.getValue("inputSchema") as Map<*, *>)
        fun number(key: String, default: Int) = (arguments[key] as? Number)?.toInt() ?: default
        fun strings(key: String) = (arguments[key] as? List<*>)?.map { it as String }.orEmpty()
        return when (name) {
            "query_symbol" -> {
                val symbol = arguments.getValue("symbol") as String
                val depth = number("depth", 2)
                val requestedLimit = number("limit", QUERY_DEFAULT_LIMIT)
                val attempts = listOf(requestedLimit, minOf(requestedLimit, 5), 1)
                var previousLimit: Int? = null
                var document: Any? = null
                var recovery: List<SymbolSuggestionPage>? = null
                attempts.forEachIndexed { index, effectiveLimit ->
                    if (previousLimit != effectiveLimit) {
                        document = McpJsonCodec.parse(SavedSnapshotOperations.query(current, symbol, depth, effectiveLimit).json)
                        previousLimit = effectiveLimit
                    }
                    val pages = recovery ?: (if ((document as? Map<*, *>)?.get("status") == "found") emptyList()
                        else suggestions(listOf(symbol), listOf(current.graph))).also { recovery = it }
                    val suggestionLimit = SUGGESTION_LIMITS[index]
                    bounded(document, pages, suggestionLimit,
                        mapOf("depth" to depth, "limit" to requestedLimit, "suggestionLimit" to SUGGESTION_LIMIT),
                        mapOf("depth" to depth, "limit" to effectiveLimit, "suggestionLimit" to suggestionLimit), index + 1)?.let { return it }
                }
                tooLarge(name)
            }
            "impact" -> {
                val symbols = strings("symbols")
                val files = strings("files")
                require("symbols" in arguments || "files" in arguments) { "Provide symbols or files (an empty files array represents no changes)" }
                require((files + strings("affectedFiles")).all(ImpactCommand::portable)) { "Files must be portable project-relative graph selectors" }
                require(symbols.all { !it.startsWith('/') }) { "Use a symbol name or exact USR" }
                val filter = ImpactFilter(strings("modules").toSet(), strings("affectedFiles").toSet(),
                    strings("kinds").map { value -> NodeKind.entries.first { lowerCamel(it.name) == value } }.toSet(),
                    (arguments["testStatus"] as? String)?.let(ImpactCommand::parseTestStatus),
                    (arguments["relation"] as? String)?.let(ImpactCommand::parseRelation),
                    (arguments["pathStatus"] as? String)?.let(ImpactCommand::parsePathStatus))
                val depth = number("depth", IMPACT_DEFAULT_DEPTH)
                val requestedLimit = number("limit", IMPACT_DEFAULT_LIMIT)
                val visitLimit = number("visitLimit", 100_000)
                val requestedPathLimit = number("pathLimit", IMPACT_DEFAULT_PATH_LIMIT)
                val requestedSummaryLimit = number("summaryLimit", IMPACT_DEFAULT_SUMMARY_LIMIT)
                val offset = number("offset", 0)
                val sort = (arguments["sort"] as? String)?.let(ImpactCommand::parseSort) ?: ImpactSort.REVIEW
                val attempts = listOf(
                    Triple(requestedLimit, requestedPathLimit, requestedSummaryLimit),
                    Triple(minOf(requestedLimit, IMPACT_DEFAULT_LIMIT), minOf(requestedPathLimit, IMPACT_DEFAULT_PATH_LIMIT), minOf(requestedSummaryLimit, IMPACT_DEFAULT_SUMMARY_LIMIT)),
                    Triple(1, 1, 1),
                )
                var previousLimits: Triple<Int, Int, Int>? = null
                var document: Any? = null
                var recovery: List<SymbolSuggestionPage>? = null
                attempts.forEachIndexed { index, (effectiveLimit, effectivePathLimit, effectiveSummaryLimit) ->
                    val limits = Triple(effectiveLimit, effectivePathLimit, effectiveSummaryLimit)
                    if (previousLimits != limits) {
                        document = McpJsonCodec.parse(SavedSnapshotOperations.impact(current, base, symbols, files,
                            depth, effectiveLimit, visitLimit, effectivePathLimit, offset, filter, sort, effectiveSummaryLimit).json)
                        previousLimits = limits
                    }
                    val suggestionLimit = SUGGESTION_LIMITS[index]
                    val requested = mapOf("depth" to depth, "limit" to requestedLimit, "visitLimit" to visitLimit,
                        "pathLimit" to requestedPathLimit, "summaryLimit" to requestedSummaryLimit,
                        "suggestionLimit" to SUGGESTION_LIMIT, "offset" to offset)
                    val effective = mapOf("depth" to depth, "limit" to effectiveLimit, "visitLimit" to visitLimit,
                        "pathLimit" to effectivePathLimit, "summaryLimit" to effectiveSummaryLimit,
                        "suggestionLimit" to suggestionLimit, "offset" to offset)
                    val pages = recovery ?: run {
                        val unresolved = (document as? Map<*, *>)?.get("unresolved") as? List<*>
                        val unresolvedSymbols = unresolved.orEmpty().mapNotNull { (it as? Map<*, *>)?.get("requested") as? String }
                            .filter { it in symbols }
                        suggestions(unresolvedSymbols, listOfNotNull(current.graph, base?.graph)).also { recovery = it }
                    }
                    bounded(document, pages, suggestionLimit, requested, effective, index + 1)?.let { return it }
                }
                tooLarge(name)
            }
            else -> {
                val start = System.nanoTime()
                val result = SavedSnapshotOperations.freshness(current, project, expectedScope, external)
                val document = mapOf("format" to "kartograph-freshness", "version" to 1, "status" to result.status,
                    "hashNanos" to System.nanoTime() - start, "reasons" to result.reasons)
                bounded(document, emptyList(), 0, emptyMap(), emptyMap(), 1) ?: tooLarge(name)
            }
        }
    }

    private fun bounded(document: Any?, suggestions: List<SymbolSuggestionPage>, suggestionLimit: Int, requested: Map<String, Int>,
        effective: Map<String, Int>, attempts: Int): Map<String, Any?>? {
        check(attempts in 1..MAX_ADAPTATION_ATTEMPTS)
        val wrapper = buildMap<String, Any?> {
            put("snapshot", metadata)
            put("freshness", if ((document as? Map<*, *>)?.get("format") == "kartograph-freshness")
                "Checked live configured inputs for the loaded current snapshot in this call; base freshness is not checked."
                else "Not checked by this call. Invoke freshness separately; restart the server to load a new snapshot.")
            put("document", document)
            if (suggestions.isNotEmpty()) put("suggestions", suggestions.map { suggestionJson(it, suggestionLimit) })
            put("response", mapOf("contentBudgetBytes" to INTERACTIVE_CONTENT_BYTES, "attempts" to attempts,
                "adapted" to (requested != effective), "requested" to requested, "effective" to effective))
        }
        val text = try { McpJsonCodec.render(wrapper, INTERACTIVE_CONTENT_BYTES) } catch (_: IllegalArgumentException) { return null }
        if (text.toByteArray(Charsets.UTF_8).size > INTERACTIVE_CONTENT_BYTES) return null
        return mapOf("content" to listOf(mapOf("type" to "text", "text" to text)), "structuredContent" to wrapper, "isError" to false)
    }

    private fun suggestions(symbols: List<String>, graphs: List<dev.kartograph.core.CodeGraph>): List<SymbolSuggestionPage> =
        symbols.distinct().sorted().map { SymbolDiscovery.suggest(graphs, it, SUGGESTION_LIMIT) }
            .filter { it.total > 0 }

    private fun suggestionJson(page: SymbolSuggestionPage, limit: Int): Map<String, Any?> = mapOf(
        "requested" to page.requested, "total" to page.total, "returned" to minOf(page.candidates.size, limit),
        "truncated" to (page.total > minOf(page.candidates.size, limit)),
        "candidates" to page.candidates.take(limit).map { candidate -> buildMap<String, Any?> {
            put("qualifiedName", candidate.qualifiedName); put("usr", candidate.usr)
            val id = NodeId(candidate.usr)
            put("presentIn", buildList {
                if (current.graph.contains(id)) add("current")
                if (base?.graph?.contains(id) == true) add("base")
            })
            candidate.location?.let { location -> put("location", buildMap<String, Any?> {
                put("path", location.path); location.line?.let { put("line", it) }; location.column?.let { put("column", it) }
            }) }
        } })

    private fun tooLarge(tool: String): Map<String, Any?> = error(when (tool) {
        "impact" -> "Interactive result exceeds 16 KiB after bounded page, path, summary and suggestion reduction. Use fewer exact USRs; files and symbols are union selectors. CLI impact without --summary-limit provides the full summary."
        "query_symbol" -> "Interactive result exceeds 16 KiB after bounded page and suggestion reduction. Select a smaller scope with an exact USR or inspect the full saved query with the CLI."
        else -> "Freshness evidence exceeds 16 KiB; use CLI verify-snapshot to inspect all reasons."
    })

    companion object {
        const val INTERACTIVE_CONTENT_BYTES = 16 * 1024
        const val MAX_ADAPTATION_ATTEMPTS = 3
        private const val QUERY_DEFAULT_LIMIT = 10
        private const val IMPACT_DEFAULT_DEPTH = 2
        private const val IMPACT_DEFAULT_LIMIT = 5
        private const val IMPACT_DEFAULT_PATH_LIMIT = 100
        private const val IMPACT_DEFAULT_SUMMARY_LIMIT = 5
        private const val SUGGESTION_LIMIT = 10
        // 마지막 시도도 원래 후보 수와 잘림을 남기며 후보 본문만 생략한다.
        private val SUGGESTION_LIMITS = listOf(SUGGESTION_LIMIT, 3, 0)

        fun error(message: String): Map<String, Any?> = mapOf("content" to listOf(mapOf("type" to "text", "text" to message)), "isError" to true)
        private fun lowerCamel(value: String) = value.lowercase().split('_').let { it.first() + it.drop(1).joinToString("") { word -> word.replaceFirstChar(Char::titlecase) } }
        private fun string() = mapOf("type" to "string", "minLength" to 1, "maxLength" to 2048)
        private fun array(items: Map<String, Any?> = string()) = mapOf("type" to "array", "maxItems" to 20, "items" to items)
        private fun integer(min: Int, max: Int, default: Int) = mapOf("type" to "integer", "minimum" to min, "maximum" to max, "default" to default)
        private fun enum(vararg values: String) = mapOf("type" to "string", "enum" to values.toList())
        private fun tool(name: String, description: String, properties: Map<String, Any?>, required: List<String> = emptyList()) = mapOf(
            "name" to name, "description" to description,
            "inputSchema" to mapOf("type" to "object", "properties" to properties, "required" to required, "additionalProperties" to false),
            "annotations" to mapOf("readOnlyHint" to true, "destructiveHint" to false, "idempotentHint" to true, "openWorldHint" to false))
        val definitions = listOf(
            tool("query_symbol", "Inspect before editing. Select with exact USR (method:p/C#m(I)V) or qualified source name (p.C.m); source-looking p.C.m(int) is discovery-only and preserves every overload. Missing/ambiguous results include exact recovery candidates. Captured graph only; call freshness separately.",
                mapOf("symbol" to string(), "depth" to integer(1, 10, 2), "limit" to integer(1, 100, QUERY_DEFAULT_LIMIT)), listOf("symbol")),
            tool("impact", "Inspect potential callers for exact USRs, qualified symbols, or portable changed files (for example src/main/A.kt). Files and symbols are union selectors. Follow navigation and exact recovery candidates; omissions and unknowns remain explicit. Does not prove safe edits or skipped tests.",
                mapOf("symbols" to array(), "files" to array(), "depth" to integer(1, 100, IMPACT_DEFAULT_DEPTH), "limit" to integer(1, 100, IMPACT_DEFAULT_LIMIT),
                    "offset" to integer(0, 100_000, 0), "visitLimit" to integer(1, 100_000, 100_000), "pathLimit" to integer(1, 100_000, IMPACT_DEFAULT_PATH_LIMIT),
                    "summaryLimit" to integer(1, 100, IMPACT_DEFAULT_SUMMARY_LIMIT),
                    "modules" to array(), "affectedFiles" to array(), "kinds" to array(enum(*NodeKind.entries.map { lowerCamel(it.name) }.toTypedArray())),
                    "testStatus" to enum("test", "production", "unknown"), "relation" to enum("direct", "structural", "transitive", "unknown"),
                    "pathStatus" to enum("complete", "partial", "unavailable"),
                    "sort" to enum("review", "usr", "module", "file", "test", "test-status", "relation", "path", "path-depth", "path-status"))),
            tool("freshness", "Rehash only startup-configured project and input bindings against the loaded current snapshot. Reports matched, stale or unverified. No project means unverified. Does not verify the base snapshot. Labels alone are not freshness evidence.", emptyMap()))

        private fun validate(value: Any?, schema: Map<*, *>) {
            when (schema["type"]) {
                "object" -> {
                    require(value is Map<*, *>)
                    val properties = schema["properties"] as Map<*, *>
                    require(value.keys.all { it in properties })
                    require((schema["required"] as List<*>).all { it in value })
                    value.forEach { (key, item) -> validate(item, properties[key] as Map<*, *>) }
                }
                "string" -> {
                    require(value is String && value.isNotBlank() && value.length <= 2048 && !value.any(Char::isISOControl))
                    (schema["enum"] as? List<*>)?.let { require(value in it) }
                }
                "integer" -> {
                    require(value is Long || value is Int || value is java.math.BigInteger)
                    val number = value.toString().toLongOrNull()
                    require(number != null && number >= (schema["minimum"] as Int) && number <= (schema["maximum"] as Int))
                }
                "array" -> {
                    require(value is List<*> && value.size <= 20)
                    value.forEach { validate(it, schema["items"] as Map<*, *>) }
                }
            }
        }
    }
}
