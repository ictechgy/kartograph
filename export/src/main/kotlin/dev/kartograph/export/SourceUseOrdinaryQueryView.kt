package dev.kartograph.export

import dev.kartograph.analysis.SymbolQueryDocument
import dev.kartograph.analysis.SymbolQueryNeighbor
import dev.kartograph.analysis.SymbolQueryResult
import dev.kartograph.analysis.SymbolQuerySubject
import dev.kartograph.core.CodeGraph
import dev.kartograph.core.LocatedCompilerReference
import dev.kartograph.core.SourceLocation

/** 기존 query JSON 값·생략 규칙을 새 bounded wrapper에만 투영한다. */
internal fun sourceOrdinaryQueryValue(document: SymbolQueryDocument, graph: CodeGraph?): Map<String, Any?> {
    val references by lazy {
        if (document.result == null) emptyMap() else graph?.locatedCompilerReferences.orEmpty()
            .groupBy { it.source.value to it.target.value }
            .mapValues { (_, values) -> values.sortedWith(SOURCE_QUERY_REFERENCE_ORDER) }
    }
    return sourceJsonObject(*buildList<Pair<String, () -> Any?>> {
        document.candidates?.let { candidates -> add("candidates" to {
            sourceJsonItems(candidates) { candidate -> sourceJsonObject(*buildList<Pair<String, () -> Any?>> {
                add("qualifiedName" to { candidate.qualifiedName })
                candidate.usr?.let { add("usr" to { it }) }
            }.toTypedArray()) }
        }) }
        add("level" to { document.level })
        add("limitations" to { document.limitations.sorted() })
        add("requested" to { document.requested })
        document.result?.let { result -> add("result" to { result.value { source, target, depth ->
            if (depth == 1 && source != null && target != null) references[source to target].orEmpty() else emptyList()
        } }) }
        add("status" to { document.status })
    }.toTypedArray())
}

private fun SymbolQueryResult.value(
    references: (String?, String?, Int) -> List<LocatedCompilerReference>,
): Map<String, Any?> = sourceJsonObject(*buildList<Pair<String, () -> Any?>> {
    declaredIn?.let { add("declaredIn" to { it.value() }) }
    add("dependsOn" to { sourceJsonItems(dependsOn) { it.value(references(subject.usr, it.usr, it.depth)) } })
    add("members" to { sourceJsonItems(members) { it.value() } })
    add("reachability" to { sourceJsonObject(*buildList<Pair<String, () -> Any?>> {
        reachability.path?.let { add("path" to { it }) }
        reachability.reason?.let { add("reason" to { it.name.lowerCamel() }) }
        add("state" to { reachability.state })
        add("suppressedByBaseline" to { reachability.suppressedByBaseline })
    }.toTypedArray()) })
    add("subject" to { subject.value() })
    add("truncated" to { sourceJsonObject("dependsOn" to { truncated.dependsOn },
        "members" to { truncated.members }, "usedBy" to { truncated.usedBy }) })
    add("usedBy" to { sourceJsonItems(usedBy) { it.value(references(it.usr, subject.usr, it.depth)) } })
}.toTypedArray())

private fun SymbolQuerySubject.value(): Map<String, Any?> = sourceJsonObject(*buildList<Pair<String, () -> Any?>> {
    sourceDeclaration?.let { add("sourceDeclaration" to { it.toJsonValue() }) }
    add("accessibility" to { accessibility })
    add("kind" to { kind })
    location?.let { add("location" to { it.queryValue() }) }
    module?.let { add("module" to { it }) }
    add("name" to { name })
    add("qualifiedName" to { qualifiedName })
    usr?.let { add("usr" to { it }) }
}.toTypedArray())

private fun SymbolQueryNeighbor.value(compilerReferences: List<LocatedCompilerReference> = emptyList()): Map<String, Any?> =
    sourceJsonObject(*buildList<Pair<String, () -> Any?>> {
        sourceDeclaration?.let { add("sourceDeclaration" to { it.toJsonValue() }) }
        add("depth" to { depth })
        add("edges" to { edges })
        add("kind" to { kind })
        location?.let { add("location" to { it.queryValue() }) }
        module?.let { add("module" to { it }) }
        add("name" to { name })
        add("qualifiedName" to { qualifiedName })
        if (references.isNotEmpty() || compilerReferences.isNotEmpty()) add("references" to {
            sourceJsonConcat(sourceJsonItems(references) { reference -> sourceJsonObject(
                "kind" to { reference.kind }, "location" to { reference.location.queryValue() }, "origin" to { reference.origin },
            ) }, sourceJsonItems(compilerReferences) { it.value() })
        })
        usr?.let { add("usr" to { it }) }
    }.toTypedArray())

private fun SourceLocation.queryValue(): Map<String, Any?> = sourceJsonObject(*buildList<Pair<String, () -> Any?>> {
    column?.let { add("column" to { it }) }
    line?.let { add("line" to { it }) }
    add("path" to { path })
}.toTypedArray())

private fun LocatedCompilerReference.value(): Map<String, Any?> = sourceJsonObject(
    "kind" to { "call" }, "origin" to { "compiler" },
    "location" to { SourceLocation(file.path, line, column).queryValue() },
    "offset" to { offsetUtf16 }, "endOffset" to { endOffsetUtf16 }, "offsetEncoding" to { "utf16-compiler-source" },
    "coordinateBasis" to { coordinateBasis.name.lowerCamel() }, "sourceSha256" to { file.sha256 },
    "generated" to { generated }, "collector" to { collector }, "compilerVersion" to { compilerVersion },
)

private val SOURCE_QUERY_REFERENCE_ORDER = compareBy<LocatedCompilerReference>(
    { it.file.path }, { it.offsetUtf16 }, { it.endOffsetUtf16 }, { it.file.sha256 },
    { it.coordinateBasis.name }, { it.collector }, { it.compilerVersion }, { it.generated }, { it.line }, { it.column },
)
