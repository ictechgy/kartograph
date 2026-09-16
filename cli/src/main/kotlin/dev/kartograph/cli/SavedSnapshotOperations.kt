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
        val document = SymbolQuery.query(snapshot.graph, ReachabilityAnalyzer.analyze(snapshot.graph, snapshot.retention),
            requested, (snapshot.limitations +
                "saved-graph: using captured graph and retention evidence; live inputs and freshness are not rechecked" +
                provenanceLimitation).distinct().sorted(), depth, limit, snapshot.suppressed)
        return Document(AgentDocumentRenderer.query(document), if (document.status == "found") 0 else 64)
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

    fun freshness(snapshot: QuerySnapshot, project: Path?, expectedScope: String?, external: Map<String, Path>): ProvenanceVerifier.Result {
        val result = if (project == null) ProvenanceVerifier.Result("unverified", listOf("project-not-configured: restart with --project to verify live inputs"))
            else ProvenanceVerifier.verify(snapshot.provenance, project, snapshot.scope, external)
        val mismatch = expectedScope != null && expectedScope != snapshot.scope
        return ProvenanceVerifier.Result(if (mismatch) "stale" else result.status,
            (result.reasons + if (mismatch) listOf("snapshot-scope-mismatch") else emptyList()).distinct().sorted())
    }
}
