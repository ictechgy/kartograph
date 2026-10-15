package dev.kartograph.export

import dev.kartograph.analysis.ImpactReport
import dev.kartograph.analysis.SourceEditImpactReport
import dev.kartograph.analysis.SourceEditObservation
import dev.kartograph.analysis.SourceUseCoverage
import dev.kartograph.analysis.SourceUseImportTotals
import dev.kartograph.analysis.SourceUseInspection
import dev.kartograph.analysis.SourceUseNodeFact
import dev.kartograph.analysis.SourceUseObservation
import dev.kartograph.analysis.SourceUsePage
import dev.kartograph.analysis.SourceUseProducerTotals
import dev.kartograph.analysis.SourceUseUnitBinding
import dev.kartograph.analysis.SymbolQueryDocument
import dev.kartograph.core.CodeGraph
import dev.kartograph.core.CompilerSourceUse
import dev.kartograph.core.InputFingerprint
import dev.kartograph.core.NodeId
import java.util.Collections

/** 파싱에 실제 사용한 snapshot raw bytes의 identity다. 현재 빌드 신선도는 주장하지 않는다. */
public data class SourceSnapshotInput(
    val toolVersion: String,
    val scope: String?,
    val revision: String?,
    val snapshotSha256: String,
) {
    init { require(Regex("[0-9a-f]{64}").matches(snapshotSha256)) { "source report requires a raw snapshot SHA-256" } }
    /** 역사적 입력만 보고하며 별도 verifier 결과를 암묵적으로 가져오지 않는다. */
    public val currentness: String get() = "notChecked"
}

/** 기존 query와 전역 capture coverage·선택 관찰을 분리한 추가 문서다. */
public class SourceUseQueryDocument(
    query: SymbolQueryDocument,
    public val input: SourceSnapshotInput,
    public val coverage: SourceUseCoverage,
    public val inspection: SourceUseInspection?,
) {
    public val query: SymbolQueryDocument = sourceFrozenQuery(query)
    init {
        require(inspection == null || coverage == inspection.coverage) { "source query coverage must match its inspection" }
        require(query.status in setOf("found", "notFound", "ambiguous")) { "unsupported source query selection status" }
        if (query.status == "found") {
            require(inspection != null && query.result?.subject?.usr == inspection.subject.value) {
                "source query inspection must match the uniquely resolved subject"
            }
        } else require(inspection == null) { "unresolved source query cannot have an inspection" }
    }
}

/** 하나의 revision에만 속하는 ordered seed와 source page다. 다른 revision과 합치지 않는다. */
public class SourceEditImpactRevision(
    public val selectionStatus: String,
    changed: List<NodeId>,
    public val coverage: SourceUseCoverage,
    public val inspection: SourceEditImpactReport?,
) {
    public val changed: List<NodeId> = Collections.unmodifiableList(ArrayList(changed))
    init {
        require(inspection == null || coverage == inspection.coverage) { "source edit coverage must match its inspection" }
        require(selectionStatus in setOf("resolved", "partial", "unresolved", "noChanges", "noTargetsInRevision")) {
            "unsupported source edit selection status"
        }
        require(this.changed.toSet().size == this.changed.size) { "source edit changed IDs must be unique" }
        require((selectionStatus in setOf("resolved", "partial")) == this.changed.isNotEmpty()) {
            "source edit selection status must match revision targets"
        }
        if (selectionStatus == "unresolved") require(inspection == null) { "unresolved source edits cannot have an inspection" }
        else require(inspection != null && inspection.changed.toSet() == this.changed.toSet()) {
            "source edit inspection must match revision targets"
        }
    }
}

/** 일반 impact 입력에 필요한 불변 scalar metadata만 보존하며 원본 snapshot을 노출하지 않는다. */
public data class SourceSnapshotMetadata(val toolVersion: String, val scope: String?, val revision: String?)

/** 일반 graph impact를 유지하며 각 저장 입력의 direct source 관찰을 별도로 싣는다. */
public class SourceEditImpactDocument(
    graphImpact: ImpactReport,
    snapshots: Map<String, QuerySnapshot>,
    inputs: Map<String, SourceSnapshotInput>,
    revisions: Map<String, SourceEditImpactRevision>,
    public val summaryLimit: Int? = null,
) {
    public val graphImpact: ImpactReport = sourceFrozenImpact(graphImpact)
    public val inputs: Map<String, SourceSnapshotInput> = Collections.unmodifiableMap(LinkedHashMap(inputs))
    public val revisions: Map<String, SourceEditImpactRevision> = Collections.unmodifiableMap(LinkedHashMap(revisions))
    public val snapshots: Map<String, SourceSnapshotMetadata> = sourceSnapshotMetadata(
        snapshots, this.graphImpact, this.inputs, this.revisions, summaryLimit,
    )
}

/** 원본 graph는 생성 시 seed 검증에만 쓰고 반환 값에는 scalar metadata만 남긴다. */
private fun sourceSnapshotMetadata(
    originals: Map<String, QuerySnapshot>,
    report: ImpactReport,
    identities: Map<String, SourceSnapshotInput>,
    sections: Map<String, SourceEditImpactRevision>,
    summaryLimit: Int?,
): Map<String, SourceSnapshotMetadata> {
    require(identities.keys == setOf("current") || identities.keys == setOf("base", "current")) {
        "source edit inputs require current and optional base"
    }
    require(originals.keys == identities.keys && sections.keys == identities.keys) {
        "source edit snapshots, identities and revisions must match"
    }
    require(summaryLimit == null || summaryLimit in 1..100_000) { "impact summary limit must be 1..100000" }
    identities.forEach { (key, input) ->
        val snapshot = originals.getValue(key)
        require(input.toolVersion == snapshot.toolVersion && input.scope == snapshot.scope && input.revision == snapshot.revision) {
            "source edit identity metadata must match its snapshot"
        }
        require(sections.getValue(key).changed == report.changed.asSequence().map { it.node.id }
            .filter { it in snapshot.graph.nodes }.toList()) { "source edit changed IDs must preserve ordinary report order" }
    }
    return Collections.unmodifiableMap(originals.mapValues { (_, snapshot) ->
        SourceSnapshotMetadata(snapshot.toolVersion, snapshot.scope, snapshot.revision)
    })
}

/** 원자적 새 wrapper가 LF 포함 상한을 넘겼으며 stdout에 부분 문서를 쓰면 안 된다. */
public class SourceUseDocumentSizeException : IllegalArgumentException(
    "source report exceeds 16 MiB; reduce the graph/source page or use a narrower snapshot",
)

/** 기존 serializer의 예산 검사 안에서만 새 JSON descriptor를 물질화한다. */
public object SourceUseDocumentRenderer {
    /** 마지막 LF와 실제 escape bytes를 포함한 새 문서의 고정 상한이다. */
    public const val MAX_BYTES: Int = 16 * 1024 * 1024

    /** 기존 query 값과 선택 위치를 유지하며 전체 결과를 제한된 버퍼에서 완성한다. */
    public fun query(document: SourceUseQueryDocument, graph: CodeGraph? = null): String = query(document, graph, MAX_BYTES)

    /** revision별 page와 provenance를 보존하며 기존 영향 JSON을 지연 투영한다. */
    public fun impact(document: SourceEditImpactDocument): String = impact(document, MAX_BYTES)

    internal fun query(document: SourceUseQueryDocument, graph: CodeGraph?, maximumBytes: Int): String = sourceBoundedDocument(
        sourceJsonObject(
            "format" to { "kartograph-source-use-query" }, "version" to { 1 },
            "inputs" to { sourceJsonObject("current" to { input(document.input) }) },
            "warnings" to { SOURCE_WARNINGS }, "query" to { sourceOrdinaryQueryValue(document.query, graph) },
            "sourceUses" to { sourceJsonObject(
                "selectionStatus" to { document.query.status }, "subject" to { document.inspection?.subject?.value },
                "coverage" to { coverage(document.coverage) },
                "page" to { document.inspection?.page?.let { page(it, ::queryRow) } },
            ) },
        ), maximumBytes,
    )

    internal fun impact(document: SourceEditImpactDocument, maximumBytes: Int): String = sourceBoundedDocument(
        sourceJsonObject(
            "format" to { "kartograph-source-edit-impact" }, "version" to { 1 },
            "inputs" to { sourceJsonObject(*document.inputs.entries.map { (key, value) -> key to { input(value) } }.toTypedArray()) },
            "warnings" to { SOURCE_WARNINGS },
            "graphImpact" to { sourceOrdinaryImpactValue(document.graphImpact, document.snapshots, document.summaryLimit) },
            "sourceEdit" to { sourceJsonObject(
                "policy" to { "directObservedSelections" }, "pageScope" to { "perRevision" },
                "revisions" to { sourceJsonObject(*document.revisions.entries.map { (key, revision) -> key to { sourceJsonObject(
                    "selectionStatus" to { revision.selectionStatus }, "changed" to { sourceJsonItems(revision.changed) { it.value } },
                    "coverage" to { coverage(revision.coverage) }, "page" to { revision.inspection?.page?.let { page(it, ::impactRow) } },
                ) } }.toTypedArray()) },
            ) },
        ), maximumBytes,
    )
}

/** 기존 상한 예외를 새 view의 오류로 바꾸되 serializer 자체는 변경하지 않는다. */
internal fun sourceBoundedDocument(value: Any?, maximumBytes: Int): String {
    require(maximumBytes in 1..SourceUseDocumentRenderer.MAX_BYTES) { "source report byte maximum must be 1..16777216" }
    return try { boundedJsonValue(value, maximumBytes, "\n") }
    catch (_: QuerySnapshotSizeException) { throw SourceUseDocumentSizeException() }
}

private fun input(value: SourceSnapshotInput): Map<String, Any?> = sourceJsonObject(
    "toolVersion" to { value.toolVersion }, "scope" to { value.scope }, "revision" to { value.revision },
    "snapshotSha256" to { value.snapshotSha256 }, "currentness" to { value.currentness },
)

private fun coverage(value: SourceUseCoverage): Map<String, Any?> = sourceJsonObject(
    "availability" to { value.availability }, "coverage" to { value.coverage },
    "selectedRootCount" to { value.selectedRootCount }, "uncoveredRootIds" to { value.uncoveredRootIds },
    "counts" to { value.counts?.let { totals -> sourceJsonObject(
        "producer" to { producer(totals.producer) }, "import" to { imported(totals.importCounts) },
        "viewOmitted" to { totals.viewOmitted }, "retainedUses" to { totals.retainedUses },
    ) } },
    "units" to { value.units?.let { units -> sourceJsonItems(units) { unit -> sourceJsonObject(
        "id" to { unit.id }, "producer" to { producer(unit.producer) }, "import" to { imported(unit.importCounts) },
        "viewOmitted" to { unit.viewOmitted }, "retainedUses" to { unit.retainedUses },
    ) } } },
)

private fun producer(value: SourceUseProducerTotals): Map<String, Any?> = sourceJsonObject(
    "observed" to { value.observed }, "emitted" to { value.emitted }, "unmapped" to { value.unmapped }, "ambiguous" to { value.ambiguous },
)

private fun imported(value: SourceUseImportTotals): Map<String, Any?> = sourceJsonObject(
    "admitted" to { value.admitted }, "outsideGraph" to { value.outsideGraph }, "shadowedCaller" to { value.shadowedCaller },
    "shadowedTarget" to { value.shadowedTarget }, "outsideClaimedOutputs" to { value.outsideClaimedOutputs },
    "sourceAssociationUnverified" to { value.sourceAssociationUnverified },
)

private fun <T> page(value: SourceUsePage<T>, row: (T) -> Any?): Map<String, Any?> = sourceJsonObject(
    "observedUses" to { value.observedUses }, "observedDistinctCallers" to { value.observedDistinctCallers },
    "returnedUses" to { value.returnedUses }, "returnedDistinctCallers" to { value.returnedDistinctCallers },
    "offset" to { value.offset }, "limit" to { value.limit }, "hasPrevious" to { value.hasPrevious }, "hasNext" to { value.hasNext },
    "omittedBefore" to { value.omittedBefore }, "omittedAfter" to { value.omittedAfter },
    "bindings" to { sourceJsonItems(value.bindings, ::binding) }, "observations" to { sourceJsonItems(value.observations, row) },
)

private fun binding(value: SourceUseUnitBinding): Map<String, Any?> = sourceJsonObject(
    "id" to { value.id }, "receipt" to { fingerprint(value.receipt) }, "collector" to { value.collector },
    "compilerVersion" to { value.compilerVersion }, "collectorArtifactFingerprint" to { value.collectorArtifactFingerprint },
    "inputToken" to { value.inputToken }, "claimCount" to { value.claimCount },
)

private fun fingerprint(value: InputFingerprint): Map<String, Any?> = sourceJsonObject(
    "role" to { value.role }, "path" to { value.path }, "sha256" to { value.sha256 },
)

private fun queryRow(value: SourceUseObservation): Map<String, Any?> = sourceJsonObject(
    "use" to { use(value.use) }, "caller" to { fact(value.caller) }, "target" to { fact(value.target) },
    "matchingBytecodeCall" to { value.matchingBytecodeCall }, "directions" to { value.directions },
)

private fun impactRow(value: SourceEditObservation): Map<String, Any?> = sourceJsonObject(
    "use" to { use(value.use) }, "caller" to { fact(value.caller) }, "target" to { fact(value.target) },
    "matchingBytecodeCall" to { value.matchingBytecodeCall },
)

private fun fact(value: SourceUseNodeFact): Map<String, Any?> = sourceJsonObject(
    "usr" to { value.usr.value }, "module" to { value.module }, "location" to { sourceRequiredLocation(value.location) },
)

private fun use(value: CompilerSourceUse): Map<String, Any?> = sourceJsonObject(
    "id" to { value.id }, "unitId" to { value.unitId }, "source" to { value.source.value }, "target" to { value.target.value },
    "sourceRootId" to { value.sourceRootId }, "targetRootId" to { value.targetRootId }, "kind" to { value.kind.name.lowerCamel() },
    "path" to { value.file.path }, "sourceSha256" to { value.file.sha256 }, "collector" to { value.collector },
    "compilerVersion" to { value.compilerVersion }, "coordinateBasis" to { value.coordinateBasis.name.lowerCamel() },
    "offsetUtf16" to { value.offsetUtf16 }, "endOffsetUtf16" to { value.endOffsetUtf16 }, "line" to { value.line },
    "column" to { value.column }, "generated" to { value.generated },
)

private val SOURCE_WARNINGS = listOf(
    "Compiler selections are historical source-dependency observations, not runtime reachability or proof of behavior changes.",
    "Direct source selections do not cover transitive edit impact, unobserved syntax, unbuilt variants or external consumers.",
    "Saved snapshot live freshness is unknown; source positions refer to captured bytes.",
    "Empty or paged results do not approve deletion or skipping tests.",
)
