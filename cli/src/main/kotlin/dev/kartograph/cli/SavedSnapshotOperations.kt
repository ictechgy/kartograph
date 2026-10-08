package dev.kartograph.cli

import dev.kartograph.analysis.ChangeImpact
import dev.kartograph.analysis.ImpactFilter
import dev.kartograph.analysis.ImpactInput
import dev.kartograph.analysis.ImpactSort
import dev.kartograph.analysis.ReachabilityAnalyzer
import dev.kartograph.analysis.SymbolQuery
import dev.kartograph.export.AgentDocumentRenderer
import dev.kartograph.export.ImpactReportCodec
import dev.kartograph.export.QuerySnapshot
import dev.kartograph.index.ProvenanceVerifier
import java.nio.file.Path

/** CLI와 MCP가 이미 로드한 snapshot에 같은 질의·보고·신선도 의미를 적용한다. */
internal object SavedSnapshotOperations {
    data class Document(val json: String, val status: Int)

    fun query(snapshot: QuerySnapshot, requested: String, depth: Int, limit: Int): Document {
        val provenanceLimitation = if (snapshot.provenance?.witnesses.isNullOrEmpty())
            "build-provenance-unverified: no compiler-task evidence was captured"
            else "build-provenance: captured compiler-task evidence has not been rechecked"
        val callPositionLimitation = if (snapshot.graph.compilerCallPositionsCaptured) emptyList() else listOf(
            "compiler-call-positions: saved graph has no captured compiler selector positions; exact offsets and columns are unavailable",
        )
        val document = SymbolQuery.query(snapshot.graph, ReachabilityAnalyzer.analyze(snapshot.graph, snapshot.retention),
            requested, (snapshot.limitations +
                "saved-graph: using captured graph and retention evidence; live inputs and freshness are not rechecked" +
                provenanceLimitation + callPositionLimitation).distinct().sorted(), depth, limit, snapshot.suppressed,
            callSiteLinesCaptured = snapshot.callSiteLinesCaptured)
        return Document(AgentDocumentRenderer.query(document, snapshot.graph), if (document.status == "found") 0 else 64)
    }

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
        compatible(current, base)
        val report = ChangeImpact.analyze(
            current = ImpactInput(current.graph, current.retention, current.limitations), symbols = symbols, files = files,
            base = base?.let { ImpactInput(it.graph, it.retention, it.limitations) },
            depth = depth, limit = limit, visitLimit = visitLimit, pathLimit = pathLimit, offset = offset, filters = filters, sort = sort)
        val limitations = report.limitations + "saved-graph: impact uses captured inputs; revision and scope labels do not prove build freshness" +
            if (current.scope == null) listOf("snapshot-scope: project and variant labels were not provided") else emptyList()
        return Document(ImpactReportCodec.render(report.copy(limitations = limitations.sorted()),
            buildMap { put("current", current); base?.let { put("base", it) } }, summaryLimit), if (report.unresolved.isEmpty()) 0 else 64)
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
