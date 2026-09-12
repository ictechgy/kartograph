package dev.kartograph.index

import dev.kartograph.core.CodeGraph
import dev.kartograph.core.EdgeKind
import dev.kartograph.core.EdgeOrigin
import dev.kartograph.core.GraphEdge
import dev.kartograph.core.InputFingerprint
import dev.kartograph.core.NodeId
import dev.kartograph.core.SnapshotProvenance
import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest

/** 현재 입력과 완료된 수집 결과의 지문을 함께 제공한다. 경로는 결과 문서에 직렬화하지 않는다. */
public data class CompilerEvidenceContext(
    val project: Path,
    val scope: String,
    val provenance: SnapshotProvenance,
    val externalInputs: Map<String, Path> = emptyMap(),
)

/** 포함한 참조와 그래프 범위 밖/미매핑/가려진 선언의 실제 개수를 함께 반환한다. */
public data class CompilerEvidenceResult(
    val graph: CodeGraph,
    val outsideGraphReferences: Int = 0,
    val shadowedReferences: Int = 0,
    val unmappedReferences: Int = 0,
)

/** 명시적으로 요청하고 성공한 compiler 증거에 묶인 참조만 그래프에 추가한다. */
public object CompilerEvidenceIndexer {
    /** 원래 class 방문의 정점 소유권을 재사용하며 class root의 첫 입력 우선 계약을 유지한다. */
    public fun enrich(indexed: IndexedClasses, classRoots: List<Path>, explicit: List<Path>,
        context: CompilerEvidenceContext? = null): CompilerEvidenceResult {
        if (explicit.isEmpty()) return CompilerEvidenceResult(indexed.graph)
        val verified = requireNotNull(context) { "compiler evidence requires a completed compiler receipt context" }
        require(indexed.declarationsByRoot.size == classRoots.size) { "compiler evidence requires original class root observations" }
        val freshness = ProvenanceVerifier.verify(verified.provenance, verified.project, verified.scope, verified.externalInputs)
        require(freshness.status == "matched") { "compiler evidence requires matched build inputs" }
        val root = verified.project.toRealPath()
        fun locate(input: InputFingerprint): Path = (if (input.path.startsWith("external/"))
            verified.externalInputs[input.path] else root.resolve(input.path))
            ?.toRealPath() ?: throw IllegalArgumentException("compiler evidence input binding is missing")
        val physicalRoots = classRoots.map { it.toRealPath() }
        val references = mutableSetOf<GraphEdge>()
        var outside = 0
        var shadowed = 0
        var unmapped = 0
        val seen = mutableSetOf<Path>()
        for (file in explicit) {
            require(!Files.isSymbolicLink(file)) { "symbolic compiler evidence inputs are not supported" }
            if (!seen.add(file.toRealPath())) continue
            val fingerprint = ContentFingerprint.hash(file)
            val claims = verified.provenance.witnesses.filter { witness -> witness.compilerEvidence.any {
                it.role == "compilerEvidence" && it.sha256 == fingerprint && locate(it) == file.toRealPath()
            } }
            require(claims.isNotEmpty()) { "compiler evidence is not recorded by a completed compiler receipt" }
            val document = CompilerEvidenceReader.read(file)
            require(claims.all { CompilerEvidenceToken.matches(it) && it.evidenceToken == document.inputToken }) {
                "compiler evidence token does not match the recorded build"
            }
            require(claims.all { witness -> witness.inputs.any { input ->
                input.role in setOf("processor", "compiler") && input.sha256 == document.artifactSha256
            } }) { "compiler evidence collector is not a recorded compiler input" }
            val claimRoots = claims.flatMap { witness -> witness.outputs.map(::locate) }.toSet()
            val positions = physicalRoots.indices.filter { physicalRoots[it] in claimRoots }.toSet()
            require(positions.isNotEmpty()) { "compiler evidence output is outside the selected class roots" }
            val sources = claims.flatMap { witness ->
                (witness.inputs.filter { it.role == "sources" } + witness.compilerEvidence.filter { it.role == "compilerGeneratedSource" }).map(::locate)
            }
            document.sources.forEach { source ->
                val path = root.resolve(source.path).toRealPath()
                require(path.startsWith(root) && sources.any { path == it || Files.isDirectory(it) && path.startsWith(it) }) {
                    "compiler evidence source is outside the recorded source inputs"
                }
                require(sourceHash(path) == source.sha256) { "compiler evidence source bytes have changed" }
            }
            require(document.unmapped <= Int.MAX_VALUE - unmapped) { "compiler evidence count exceeds the supported range" }
            unmapped += document.unmapped
            for (reference in document.references) {
                require(positions.any { reference.source in indexed.declarationsByRoot[it] }) {
                    "compiler evidence source declaration is absent from its compiler output"
                }
                if (indexed.selectedRootByNode[reference.source] !in positions) {
                    shadowed++
                    continue
                }
                if (reference.target !in indexed.graph.nodes) {
                    require(!hasOwner(indexed.graph, reference.target)) { "compiler evidence target is absent from its compiled owner" }
                    outside++
                    continue
                }
                references += GraphEdge(reference.source, reference.target, EdgeKind.REFERENCE, origin = EdgeOrigin.COMPILER_REFERENCE)
            }
        }
        val graph = indexed.graph
        return CompilerEvidenceResult(CodeGraph(graph.nodes.values, graph.edges + references.sorted(), graph.externalCalls, graph.serviceProviders),
            outside, shadowed, unmapped)
    }

    /** compiler가 읽은 원본의 원시 SHA-256을 비교한다. 원문은 결과로 내보내지 않는다. */
    public fun sourceHash(path: Path): String {
        val digest = MessageDigest.getInstance("SHA-256")
        Files.newInputStream(path).use { stream ->
            val buffer = ByteArray(65536)
            while (true) {
                val size = stream.read(buffer)
                if (size < 0) break
                digest.update(buffer, 0, size)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    private fun hasOwner(graph: CodeGraph, id: NodeId): Boolean {
        val value = id.value.substringAfter(':')
        return value.indices.filter { value[it] == '#' }.any { index ->
            JvmNodeId.classId(value.substring(0, index)) in graph.nodes
        }
    }
}
