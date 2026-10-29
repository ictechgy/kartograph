package dev.kartograph.analysis

import dev.kartograph.core.*

/** 실제 compiler 실행을 흉내 내지 않는 Stage A typed admission용 합성 입력이다. */
internal object SourceUseAnalysisFixture {
    val a = id("A")
    val b = id("B")
    val c = id("C")
    val d = id("D")
    val x = id("X")
    val contract = id("Contract")
    val implementation = id("Implementation")
    val defaultIds = listOf(a, b, c, d, x, contract, implementation)
    val output = InputFingerprint("classes", "build/classes", "4".repeat(64))

    fun id(name: String): NodeId = NodeId("method:p/$name#f()V")
    fun node(id: NodeId): GraphNode = GraphNode(id, id.value, NodeKind.METHOD)
    fun counts(admitted: Int): CompilerSourceUseImportCounts = CompilerSourceUseImportCounts(admitted, 0, 0, 0, 0, 0)

    data class Spec(val source: NodeId, val target: NodeId, val offset: Int, val path: String = "src/p/F.kt")

    fun graph(
        pairs: List<Pair<NodeId, NodeId>> = emptyList(),
        edges: List<GraphEdge> = emptyList(),
        ids: List<NodeId> = defaultIds,
        specs: List<Spec> = pairs.mapIndexed { index, pair -> Spec(pair.first, pair.second, index * 10) },
        unmapped: Int = 0,
        ambiguous: Int = 0,
        importCounts: CompilerSourceUseImportCounts = counts(specs.size),
        viewOmitted: Int = importCounts.admitted - specs.size,
        uncovered: List<NodeId> = emptyList(),
        emptyRoot: Boolean = false,
        extraClaim: Boolean = false,
        collector: String = "kotlin-constants",
    ): CodeGraph {
        val root = CompilerSourceUseRoot(0, output, ids.filterNot { it in uncovered })
        val roots = mutableListOf(root)
        for (owner in uncovered) roots.add(CompilerSourceUseRoot(roots.size,
            output.copy(path = "build/uncovered-${roots.size}"), listOf(owner)))
        if (emptyRoot) roots.add(CompilerSourceUseRoot(roots.size, output.copy(path = "build/no-callables"), emptyList()))
        val compiler = if (collector == "kotlin-constants") "kotlin" else "javac"
        val claims = mutableListOf(CompilerSourceUseClaim("sample:main", compiler, ":compile", listOf(output)))
        if (extraClaim) claims.add(CompilerSourceUseClaim("sample:main", compiler, ":compileOther", listOf(output)))
        val emitted = importCounts.admitted + importCounts.outsideGraph + importCounts.shadowedCaller +
            importCounts.shadowedTarget + importCounts.outsideClaimedOutputs + importCounts.sourceAssociationUnverified
        val unit = CompilerSourceUseUnit(InputFingerprint("compilerEvidence", "evidence.tsv", "1".repeat(64)),
            collector, if (compiler == "kotlin") "2.4.10" else "21", "3".repeat(64), "2".repeat(64), claims,
            CompilerSourceUseProducerCounts(emitted + unmapped + ambiguous, emitted, unmapped, ambiguous), importCounts, viewOmitted)
        val uses = specs.map { spec -> CompilerSourceUse(unit.id, spec.source, spec.target, root.id, root.id,
            CompilerSourceUseKind.INVOCATION_SELECTION, CompilerEvidenceSource(spec.path, "5".repeat(64)), collector,
            unit.compilerVersion, if (compiler == "kotlin") CompilerSourceCoordinateBasis.KOTLIN_UTF16_NORMALIZED_SOURCE
            else CompilerSourceCoordinateBasis.JAVAC_UTF16_CHAR_SEQUENCE, spec.offset, spec.offset + 1, 1, 1, false) }
        return CodeGraph(ids.map(::node), edges).withCompilerSourceUses(CompilerSourceUseEvidence(roots, listOf(unit), uses))
    }
}
