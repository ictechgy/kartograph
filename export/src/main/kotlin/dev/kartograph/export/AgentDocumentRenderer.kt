package dev.kartograph.export

import dev.kartograph.analysis.SymbolQueryDocument
import dev.kartograph.analysis.SymbolQueryNeighbor
import dev.kartograph.analysis.SymbolQueryResult
import dev.kartograph.analysis.SymbolQuerySubject
import dev.kartograph.core.BridgeFact
import dev.kartograph.core.BridgeFactsDocument
import dev.kartograph.core.CodeGraph
import dev.kartograph.core.LocatedCompilerReference
import dev.kartograph.core.RouteCallEvidence
import dev.kartograph.core.RouteDeclEvidence
import dev.kartograph.core.RouteLimitationScope
import dev.kartograph.core.SourceLocation

/** 에이전트 교환 문서를 결정적인 키 순서의 JSON으로 렌더링한다. */
public object AgentDocumentRenderer {
    /** SymbolQueryDocument의 optional 필드 생략 의미를 보존한 JSON을 만든다. */
    public fun query(document: SymbolQueryDocument): String = jsonValue(document.queryValue()) + "\n"

    /** released query model을 바꾸지 않고 직접 이웃에 compiler selector 위치를 덧붙인다. */
    public fun query(document: SymbolQueryDocument, graph: CodeGraph): String {
        if (document.result == null || graph.locatedCompilerReferences.isEmpty()) return query(document)
        val positions = graph.locatedCompilerReferences.groupBy { it.source.value to it.target.value }
            .mapValues { (_, values) -> values.sortedWith(COMPILER_REFERENCE_COMPARATOR) }
        return jsonValue(document.queryValue(positions)) + "\n"
    }

    private fun SymbolQueryDocument.queryValue(
        compilerReferences: Map<Pair<String, String>, List<LocatedCompilerReference>> = emptyMap(),
    ): Map<String, Any?> =
        buildMap<String, Any?> {
            candidates?.let { candidates ->
                put(
                    "candidates",
                    candidates.map { candidate ->
                        buildMap<String, Any?> {
                            put("qualifiedName", candidate.qualifiedName)
                            candidate.usr?.let { put("usr", it) }
                        }.toSortedMap()
                    },
                )
            }
            put("level", level)
            put("limitations", limitations.sorted())
            put("requested", requested)
            result?.let { put("result", it.toJsonValue(compilerReferences)) }
            put("status", status)
        }.toSortedMap()

    /** isthmus bridge-facts v1의 public 필드만 포함한 JSON을 만든다. */
    public fun bridges(document: BridgeFactsDocument): String = jsonValue(buildMap<String, Any?> {
        document.dispatch?.let { put("dispatch", it) }
        put("facts", document.facts.map { it.toJsonValue() })
        put("format", document.format)
        put("generatedAt", document.generatedAt)
        document.sourceModifiedAt?.let { put("sourceModifiedAt", it) }
        val limitations = document.limitations.sorted()
        put("limitations", limitations)
        limitationScopes(limitations, document.limitationScopes).takeIf { it.isNotEmpty() }?.let { put("limitationScopes", it) }
        put("platform", document.platform)
        put("project", document.project)
        document.roles?.let { put("roles", it) }
        document.service?.let { put("service", it) }
        document.testSources?.let { put("sourceSets", sortedMapOf("tests" to it)) }
        put("target", document.target)
        put("tool", sortedMapOf("name" to document.tool.name, "version" to document.tool.version))
        put("version", document.version)
        document.transport?.let { put("transport", it) }
    }.toSortedMap()) + "\n"

    /**
     * 문구로 가리킨 스코프를 정렬된 `limitations`의 인덱스로 바꾼다. 원소는 중복을 빼고 문자열 순으로 정규화한다(isthmus도
     * 같은 정규화를 한다). 스코프는 한계 하나를 좁히므로, 가리킨 문구가 없거나 둘 이상이면 어느 공백을 좁히는지 정할 수 없어
     * 조립 오류로 실패한다 — 다른 한계를 잘못 좁혀 거짓 error를 만드는 것보다 낫다.
     */
    private fun limitationScopes(limitations: List<String>, scopes: List<RouteLimitationScope>): List<Map<String, Any?>> =
        scopes.map { scope ->
            val index = limitations.indexOf(scope.limitation)
            check(index >= 0 && limitations.lastIndexOf(scope.limitation) == index) {
                "a limitation scope must name exactly one limitation of the document"
            }
            buildMap<String, Any?> {
                put("limitationIndex", index)
                scope.templates.takeIf { it.isNotEmpty() }?.let { put("templates", it.distinct().sorted()) }
                scope.templatePrefixes.takeIf { it.isNotEmpty() }?.let { put("templatePrefixes", it.distinct().sorted()) }
                scope.templateSuffixes.takeIf { it.isNotEmpty() }?.let { put("templateSuffixes", it.distinct().sorted()) }
                scope.methods.takeIf { it.isNotEmpty() }?.let { put("methods", it.distinct().sorted()) }
            }.toSortedMap()
        }.also { rendered -> check(rendered.map { it["limitationIndex"] }.distinct().size == rendered.size) { "one scope per limitation" } }
            .sortedBy { it["limitationIndex"] as Int }

    private fun SymbolQueryResult.toJsonValue(
        compilerReferences: Map<Pair<String, String>, List<LocatedCompilerReference>> = emptyMap(),
    ): Map<String, Any?> = buildMap<String, Any?> {
        declaredIn?.let { put("declaredIn", it.toJsonValue()) }
        put("dependsOn", dependsOn.map { neighbor ->
            neighbor.toJsonValue(compilerReferences.direct(subject.usr, neighbor.usr, neighbor.depth))
        })
        put("members", members.map { it.toJsonValue() })
        put(
            "reachability",
            buildMap<String, Any?> {
                reachability.path?.let { put("path", it) }
                reachability.reason?.let { put("reason", it.name.lowerCamel()) }
                put("state", reachability.state)
                put("suppressedByBaseline", reachability.suppressedByBaseline)
            }.toSortedMap(),
        )
        put("subject", subject.toJsonValue())
        put(
            "truncated",
            sortedMapOf(
                "dependsOn" to truncated.dependsOn,
                "members" to truncated.members,
                "usedBy" to truncated.usedBy,
            ),
        )
        put("usedBy", usedBy.map { neighbor ->
            neighbor.toJsonValue(compilerReferences.direct(neighbor.usr, subject.usr, neighbor.depth))
        })
    }.toSortedMap()

    private fun SymbolQuerySubject.toJsonValue(): Map<String, Any?> = buildMap<String, Any?> {
        put("accessibility", accessibility)
        put("kind", kind)
        location?.let { put("location", it.toJsonValue()) }
        module?.let { put("module", it) }
        put("name", name)
        put("qualifiedName", qualifiedName)
        usr?.let { put("usr", it) }
    }.toSortedMap()

    private fun SymbolQueryNeighbor.toJsonValue(
        compilerReferences: List<LocatedCompilerReference> = emptyList(),
    ): Map<String, Any?> = buildMap<String, Any?> {
        put("depth", depth)
        put("edges", edges)
        put("kind", kind)
        location?.let { put("location", it.toJsonValue()) }
        module?.let { put("module", it) }
        put("name", name)
        put("qualifiedName", qualifiedName)
        val renderedReferences = references.map { reference ->
            sortedMapOf(
                "kind" to reference.kind,
                "location" to reference.location.toJsonValue(),
                "origin" to reference.origin,
            )
        } + compilerReferences.map { it.toJsonValue() }
        renderedReferences.takeIf { it.isNotEmpty() }?.let { values ->
            put("references", values)
        }
        usr?.let { put("usr", it) }
    }.toSortedMap()

    private fun SourceLocation.toJsonValue(): Map<String, Any?> = buildMap<String, Any?> {
        column?.let { put("column", it) }
        line?.let { put("line", it) }
        put("path", path)
    }.toSortedMap()

    private fun LocatedCompilerReference.toJsonValue(): Map<String, Any?> = sortedMapOf(
        "kind" to "call",
        "origin" to "compiler",
        "location" to SourceLocation(file.path, line, column).toJsonValue(),
        "offset" to offsetUtf16,
        "endOffset" to endOffsetUtf16,
        "offsetEncoding" to "utf16-compiler-source",
        "coordinateBasis" to coordinateBasis.name.lowerCamel(),
        "sourceSha256" to file.sha256,
        "generated" to generated,
        "collector" to collector,
        "compilerVersion" to compilerVersion,
    )

    private fun Map<Pair<String, String>, List<LocatedCompilerReference>>.direct(
        source: String?,
        target: String?,
        depth: Int,
    ): List<LocatedCompilerReference> =
        if (depth == 1 && source != null && target != null) this[source to target].orEmpty() else emptyList()

    private fun BridgeFact.toJsonValue(): Map<String, Any?> = buildMap<String, Any?> {
        put("channel", channel)
        put("dynamic", dynamic)
        put("kind", kind)
        put("location", sortedMapOf("column" to location.column, "line" to location.line, "path" to location.path))
        if (method != null) put("method", method)
        if (channelPrefix != null) put("channelPrefix", channelPrefix)
        if (mechanism != null) put("mechanism", mechanism)
        route?.let { putRouteEvidence(it) }
        routeDecl?.let { putRouteDeclEvidence(it) }
        symbol?.let { value ->
            put(
                "symbol",
                buildMap<String, Any?> {
                    put("qualifiedName", value.qualifiedName)
                    value.usr?.let { put("usr", it) }
                }.toSortedMap(),
            )
        }
    }.toSortedMap()

    /** route-call 증거를 계약 필드 이름으로 싣는다 — 존재 자체가 증거인 표식은 참일 때만 싣는다. */
    private fun MutableMap<String, Any?>.putRouteEvidence(route: RouteCallEvidence) {
        put("pathAnchor", route.pathAnchor)
        if (route.methodDynamic) put("methodDynamic", true)
        route.authority?.let { put("authority", it) }
        route.service?.let { put("service", it) }
        route.baseRef?.let { put("baseRef", it) }
        if (route.queryTailStripped) put("queryTailStripped", true)
        route.maskedSegments?.let { put("maskedSegments", it) }
        if (route.testSource) put("testSource", true)
    }

    /** route-decl 증거를 계약 필드 이름으로 싣는다 — 모르는 끝 슬래시는 생략하고, 표식은 참일 때만 싣는다. */
    private fun MutableMap<String, Any?>.putRouteDeclEvidence(route: RouteDeclEvidence) {
        put("pathAnchor", route.pathAnchor)
        route.trailingSlash?.let { put("trailingSlash", it) }
        if (route.narrowed) put("narrowed", true)
        if (route.paramConstraints.isNotEmpty()) put("paramConstraints", route.paramConstraints.sortedBy { it.segment }.map { constraint ->
            buildMap<String, Any?> {
                put("kind", constraint.kind)
                constraint.pattern?.let { put("pattern", it) }
                put("segment", constraint.segment)
            }.toSortedMap()
        })
        if (route.configDefault) put("configDefault", true)
        if (route.catchAllPrefix) put("catchAllPrefix", true)
        if (route.testSource) put("testSource", true)
    }

    private val COMPILER_REFERENCE_COMPARATOR = compareBy<LocatedCompilerReference>(
        { it.file.path }, { it.offsetUtf16 }, { it.endOffsetUtf16 }, { it.file.sha256 },
        { it.coordinateBasis.name }, { it.collector }, { it.compilerVersion }, { it.generated },
        { it.line }, { it.column },
    )
}
