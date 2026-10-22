package dev.kartograph.cli

import dev.kartograph.analysis.ChangeImpact
import dev.kartograph.analysis.ImpactFilter
import dev.kartograph.analysis.AndroidResourceImpactContext
import dev.kartograph.analysis.ImpactRevision
import dev.kartograph.analysis.ImpactInput
import dev.kartograph.analysis.ImpactSort
import dev.kartograph.analysis.ReachabilityAnalyzer
import dev.kartograph.analysis.SymbolQuery
import dev.kartograph.analysis.SymbolQueryDocument
import dev.kartograph.analysis.ImpactReport
import dev.kartograph.analysis.SourceUseCoverage
import dev.kartograph.analysis.SourceUseQuery
import dev.kartograph.analysis.SourceEditImpact
import dev.kartograph.analysis.SourceUsePageRequest
import dev.kartograph.core.NodeId
import dev.kartograph.export.SourceSnapshotInput
import dev.kartograph.export.SourceUseQueryDocument
import dev.kartograph.export.SourceEditImpactDocument
import dev.kartograph.export.SourceEditImpactRevision
import dev.kartograph.export.SourceUseDocumentRenderer
import dev.kartograph.export.AgentDocumentRenderer
import dev.kartograph.export.ImpactReportCodec
import dev.kartograph.export.QuerySnapshot
import dev.kartograph.index.ProvenanceVerifier
import java.nio.file.Path

/** CLI와 MCP가 이미 로드한 snapshot에 같은 질의·보고·신선도 의미를 적용한다. */
internal object SavedSnapshotOperations {
    data class Document(val json: String, val status: Int)

    /** 한 번 로드한 snapshot에만 묶인 반복 질의 문맥이다. live 파일이나 다른 generation과 공유하지 않는다. */
    class QuerySession(private val snapshot: QuerySnapshot) {
        private val reachability by lazy { ReachabilityAnalyzer.analyze(snapshot.graph, snapshot.retention) }
        fun query(requested: String, depth: Int, limit: Int): Document =
            SavedSnapshotOperations.query(snapshot, requested, depth, limit, reachability)
    }

    fun query(snapshot: QuerySnapshot, requested: String, depth: Int, limit: Int): Document =
        query(snapshot, requested, depth, limit, ReachabilityAnalyzer.analyze(snapshot.graph, snapshot.retention))

    private fun query(snapshot: QuerySnapshot, requested: String, depth: Int, limit: Int,
        reachability: dev.kartograph.analysis.ReachabilityResult): Document {
        val document = queryDocument(snapshot, requested, depth, limit, reachability)
        return Document(AgentDocumentRenderer.query(document, snapshot.graph), if (document.status == "found") 0 else 64)
    }

    private fun queryDocument(snapshot: QuerySnapshot, requested: String, depth: Int, limit: Int,
        reachability: dev.kartograph.analysis.ReachabilityResult): SymbolQueryDocument {
        val provenanceLimitation = if (snapshot.provenance?.witnesses.isNullOrEmpty())
            "build-provenance-unverified: no compiler-task evidence was captured"
            else "build-provenance: captured compiler-task evidence has not been rechecked"
        val callPositionLimitation = if (snapshot.graph.compilerCallPositionsCaptured) emptyList() else listOf(
            "compiler-call-positions: saved graph has no captured compiler selector positions; exact offsets and columns are unavailable",
        )
        return SymbolQuery.query(snapshot.graph, reachability,
            requested, (snapshot.limitations +
                "saved-graph: using captured graph and retention evidence; live inputs and freshness are not rechecked" +
                provenanceLimitation + callPositionLimitation + dev.kartograph.export.ExternalRetentionCaveats.messages(snapshot.externalRetentionContexts)).distinct().sorted(), depth, limit, snapshot.suppressed,
            callSiteLinesCaptured = snapshot.callSiteLinesCaptured)
    }

    /** saved 입력 identity는 같은 파싱 bytes에 묶이며 현재 파일을 재확인하지 않는다. */
    fun sourceQuery(loaded: SnapshotFiles.Loaded, requested: String, depth: Int, limit: Int,
        request: SourceUsePageRequest): Document {
        val snapshot = loaded.snapshot
        val ordinary = queryDocument(snapshot, requested, depth, limit,
            ReachabilityAnalyzer.analyze(snapshot.graph, snapshot.retention))
        val inspection = if (ordinary.status == "found") SourceUseQuery.inspect(snapshot.graph,
            NodeId(requireNotNull(ordinary.result?.subject?.usr)), request) else null
        val document = SourceUseQueryDocument(ordinary, loaded.sourceInput(),
            inspection?.coverage ?: SourceUseCoverage.from(snapshot.graph), inspection)
        return Document(SourceUseDocumentRenderer.query(document, snapshot.graph), if (ordinary.status == "found") 0 else 64)
    }

    private fun SnapshotFiles.Loaded.sourceInput(): SourceSnapshotInput = SourceSnapshotInput(
        snapshot.toolVersion, snapshot.scope, snapshot.revision, sha256)

    fun compatible(current: QuerySnapshot, base: QuerySnapshot?) {
        require(base == null || current.toolVersion == base.toolVersion) {
            "snapshot analyzer versions differ; recapture both with the same version"
        }
        require(base == null || current.scope == base.scope) {
            "snapshot scopes differ; capture the same project and variant"
        }
    }

    fun impact(current: QuerySnapshot, base: QuerySnapshot?, symbols: List<String>, files: List<String>,
        depth: Int, limit: Int, visitLimit: Int, pathLimit: Int, offset: Int,
        filters: ImpactFilter, sort: ImpactSort, summaryLimit: Int? = null): Document {
        val report = impactReport(current, base, symbols, files, depth, limit, visitLimit, pathLimit, offset, filters, sort)
        return Document(ImpactReportCodec.render(report,
            buildMap { put("current", current); base?.let { put("base", it) } }, summaryLimit), if (report.unresolved.isEmpty()) 0 else 64)
    }

    private fun impactReport(current: QuerySnapshot, base: QuerySnapshot?, symbols: List<String>, files: List<String>,
        depth: Int, limit: Int, visitLimit: Int, pathLimit: Int, offset: Int, filters: ImpactFilter, sort: ImpactSort): ImpactReport {
        compatible(current, base)
        val report = ChangeImpact.analyze(
            current = ImpactInput(current.graph, current.retention, current.limitations + dev.kartograph.export.ExternalRetentionCaveats.messages(current.externalRetentionContexts),
                androidResources = resourceContext(current)), symbols = symbols, files = files,
            base = base?.let { ImpactInput(it.graph, it.retention, it.limitations + dev.kartograph.export.ExternalRetentionCaveats.messages(it.externalRetentionContexts),
                androidResources = resourceContext(it)) },
            depth = depth, limit = limit, visitLimit = visitLimit, pathLimit = pathLimit, offset = offset, filters = filters, sort = sort)
        val limitations = report.limitations + "saved-graph: impact uses captured inputs; revision and scope labels do not prove build freshness" +
            if (current.scope == null) listOf("snapshot-scope: project and variant labels were not provided") else emptyList()
        return report.copy(limitations = limitations.sorted())
    }

    private fun resourceContext(snapshot: QuerySnapshot) = AndroidResourceImpactContext(
        snapshot.androidResourceEvidence, snapshot.androidResourceBindings, snapshot.provenance, snapshot.scope)

    /** 원래의 unpaged 변경 seed만 revision별로 투영한다. graph 영향 후보는 seed가 아니다. */
    fun sourceImpact(current: SnapshotFiles.Loaded, base: SnapshotFiles.Loaded?, symbols: List<String>, files: List<String>,
        depth: Int, limit: Int, visitLimit: Int, pathLimit: Int, offset: Int, summaryLimit: Int?,
        request: SourceUsePageRequest): Document {
        val ordinary = impactReport(current.snapshot, base?.snapshot, symbols, files, depth, limit,
            visitLimit, pathLimit, offset, ImpactFilter(), ImpactSort.REVIEW)
        val loaded = buildMap { put("current", current); base?.let { put("base", it) } }
        val revisions = loaded.mapValues { (revision, input) ->
            val graph = input.snapshot.graph
            val selectedRevision = if (revision == "base") ImpactRevision.BASE else ImpactRevision.CURRENT
            val changed = ordinary.selectedByRevision[selectedRevision].orEmpty().sorted()
            val status = when {
                ordinary.changed.isEmpty() && ordinary.unresolved.isNotEmpty() -> "unresolved"
                symbols.isEmpty() && files.isEmpty() -> "noChanges"
                changed.isEmpty() && ordinary.unresolved.any { it.revision == selectedRevision } -> "unresolved"
                changed.isEmpty() -> "noTargetsInRevision"
                ordinary.unresolved.isNotEmpty() -> "partial"
                else -> "resolved"
            }
            val inspection = if (status == "unresolved") null else SourceEditImpact.analyze(graph, changed.toSet(), request)
            SourceEditImpactRevision(status, changed, inspection?.coverage ?: SourceUseCoverage.from(graph), inspection)
        }
        val document = SourceEditImpactDocument(ordinary, loaded.mapValues { it.value.snapshot },
            loaded.mapValues { it.value.sourceInput() }, revisions, summaryLimit)
        return Document(SourceUseDocumentRenderer.impact(document), if (ordinary.unresolved.isEmpty()) 0 else 64)
    }

    /**
     * 신선도 결과를 문서 한계 문구로 바꾼다. matched는 확인된 사실이지 한계가 아니므로 null이다. unverified·stale은
     * 원인을 `;`로 이어 `graph-file-freshness-<status>: <reasons>`로 낸다.
     */
    fun freshnessLimitation(result: ProvenanceVerifier.Result): String? =
        if (result.status == "matched") null else "graph-file-freshness-${result.status}: " + result.reasons.joinToString(";")

    fun freshness(snapshot: QuerySnapshot, project: Path?, expectedScope: String?, external: Map<String, Path>): ProvenanceVerifier.Result {
        val result = if (project == null) ProvenanceVerifier.Result("unverified", listOf("project-not-configured: restart with --project to verify live inputs"))
            else ProvenanceVerifier.verify(snapshot.provenance, project, snapshot.scope, external)
        val mismatch = expectedScope != null && expectedScope != snapshot.scope
        return ProvenanceVerifier.Result(if (mismatch) "stale" else result.status,
            (result.reasons + if (mismatch) listOf("snapshot-scope-mismatch") else emptyList()).distinct().sorted())
    }
}
