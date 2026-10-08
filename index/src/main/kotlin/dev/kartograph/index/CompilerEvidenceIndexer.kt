package dev.kartograph.index

import dev.kartograph.core.CodeGraph
import dev.kartograph.core.EdgeKind
import dev.kartograph.core.EdgeOrigin
import dev.kartograph.core.GraphEdge
import dev.kartograph.core.InputFingerprint
import dev.kartograph.core.CompilerSourceCoordinateBasis
import dev.kartograph.core.LocatedCompilerReference
import dev.kartograph.core.NodeId
import dev.kartograph.core.SnapshotProvenance
import dev.kartograph.core.ProcessorGeneration
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
    val processorGenerations: List<ProcessorGeneration> = emptyList(),
)

/** released 결과와 additive v3 위치 제한을 data-class ABI 변경 없이 함께 반환한다. */
public data class CompilerEvidenceEnrichment(
    val result: CompilerEvidenceResult,
    val limitations: List<String>,
)

/** 명시적으로 요청하고 성공한 compiler 증거에 묶인 참조만 그래프에 추가한다. */
public object CompilerEvidenceIndexer {
    /** 원래 class 방문의 정점 소유권을 재사용하며 class root의 첫 입력 우선 계약을 유지한다. */
    public fun enrich(indexed: IndexedClasses, classRoots: List<Path>, explicit: List<Path>,
        context: CompilerEvidenceContext? = null): CompilerEvidenceResult =
        enrichInternal(indexed, classRoots, explicit, context, callPositions = false).result

    /** 완료된 v3 receipt만 별도 위치 사실로 붙이고 omission 계수를 정규화해 반환한다. */
    public fun enrichWithCallPositions(indexed: IndexedClasses, classRoots: List<Path>, explicit: List<Path>,
        context: CompilerEvidenceContext? = null): CompilerEvidenceEnrichment =
        enrichInternal(indexed, classRoots, explicit, context, callPositions = true)

    private fun enrichInternal(indexed: IndexedClasses, classRoots: List<Path>, explicit: List<Path>,
        context: CompilerEvidenceContext?, callPositions: Boolean): CompilerEvidenceEnrichment {
        if (explicit.isEmpty()) return CompilerEvidenceEnrichment(CompilerEvidenceResult(indexed.graph), emptyList())
        val verified = requireNotNull(context) { "compiler evidence requires a completed compiler receipt context" }
        require(indexed.declarationsByRoot.size == classRoots.size) { "compiler evidence requires original class root observations" }
        val freshness = ProvenanceVerifier.verify(verified.provenance, verified.project, verified.scope, verified.externalInputs)
        require(freshness.status == "matched") { "compiler evidence requires matched build inputs" }
        val root = verified.project.toRealPath()
        val locatedInputs = mutableMapOf<InputFingerprint, Path>()
        fun locate(input: InputFingerprint): Path = locatedInputs.getOrPut(input) {
            (if (input.path.startsWith("external/")) verified.externalInputs[input.path] else root.resolve(input.path))
                ?.toRealPath() ?: throw IllegalArgumentException("compiler evidence input binding is missing")
        }
        val physicalRoots = classRoots.map { it.toRealPath() }
        val exactBytecodeCalls by lazy(LazyThreadSafetyMode.NONE) {
            indexed.graph.edges.asSequence()
                .filter { it.kind == EdgeKind.CALL && it.origin == EdgeOrigin.BYTECODE }
                .map { it.source to it.target }
                .toSet()
        }
        val selectedCallerRoots by lazy(LazyThreadSafetyMode.NONE) {
            exactBytecodeCalls.mapNotNullTo(mutableSetOf()) { (source, _) -> indexed.selectedRootByNode[source] }
        }
        val compiledOwnerPresence = mutableMapOf<NodeId, Boolean>()
        fun hasCompiledOwner(id: NodeId): Boolean =
            compiledOwnerPresence.getOrPut(id) { hasOwner(indexed.graph, id) }
        val references = mutableSetOf<GraphEdge>()
        var outside = 0
        var shadowed = 0
        var unmapped = 0
        var positionUnmapped = 0L
        var positionAmbiguous = 0L
        var positionOutside = 0L
        var positionShadowed = 0L
        var positionUnmatched = 0L
        var positionSourceUnverified = 0L
        var callPositionsCaptured = false
        val v3CoveredRoots = mutableSetOf<Int>()
        val legacyDocumentRoots = mutableListOf<Set<Int>>()
        val seen = mutableSetOf<Path>()
        val generations = mutableListOf<ProcessorGeneration>()
        val locatedReferences = mutableListOf<LocatedCompilerReference>()
        for (file in explicit) {
            require(!Files.isSymbolicLink(file)) { "symbolic compiler evidence inputs are not supported" }
            val evidencePath = file.toRealPath()
            if (!seen.add(evidencePath)) continue
            val bounded = BoundedCompilerEvidenceReader.read(file)
            val fingerprint = bounded.fingerprint
            val claims = verified.provenance.witnesses.filter { witness -> witness.compilerEvidence.any {
                it.role == "compilerEvidence" && it.sha256 == fingerprint && locate(it) == evidencePath
            } }
            require(claims.isNotEmpty()) { "compiler evidence is not recorded by a completed compiler receipt" }
            val envelope = if (callPositions) CompilerEvidenceReader.parseEnvelope(bounded.text)
                else CompilerEvidenceEnvelope(CompilerEvidenceReader.parse(bounded.text), null, emptyList())
            val document = envelope.evidence
            require(claims.all { CompilerEvidenceToken.matches(it) && it.evidenceToken == document.inputToken }) {
                "compiler evidence token does not match the recorded build"
            }
            require(claims.all { witness -> witness.inputs.any { input ->
                input.role in setOf("processor", "compiler") && input.sha256 == document.artifactSha256
            } }) { "compiler evidence collector is not a recorded compiler input" }
            if (envelope.callStats != null) {
                require(claims.all { CompilerCallPositionOptions.isEnabled(it.inputs) }) {
                    "v3 compiler evidence requires a recorded call positions option"
                }
                callPositionsCaptured = true
                positionUnmapped += envelope.callStats.unmapped.toLong()
                positionAmbiguous += envelope.callStats.ambiguous.toLong()
            }
            document.processorGeneration?.let { generation ->
                require(claims.all { witness -> witness.inputs.any { it.role == "processor" && it.sha256 == generation.artifactSha256 } }) {
                    "generating processor is not a recorded compiler input"
                }
                generation.sources.forEach { source ->
                    require(claims.any { witness -> witness.compilerEvidence.any {
                        it.role == "compilerGeneratedSource" && locate(it) == root.resolve(source.path).toRealPath()
                    } }) { "processor output is missing a completed generated source receipt" }
                }
                generations += generation
            }
            val claimRoots = claims.flatMap { witness -> witness.outputs.map(::locate) }.toSet()
            val positions = physicalRoots.indices.filter { physicalRoots[it] in claimRoots }.toSet()
            require(positions.isNotEmpty()) { "compiler evidence output is outside the selected class roots" }
            if (callPositions) {
                val claimedCallerRoots = positions.intersect(selectedCallerRoots)
                if (envelope.callStats == null) legacyDocumentRoots += claimedCallerRoots
                else v3CoveredRoots += claimedCallerRoots
            }
            val sourceInputs = claims.flatMap { witness ->
                (witness.inputs.filter { it.role == "sources" } + witness.compilerEvidence.filter { it.role == "compilerGeneratedSource" }).map(::locate)
            }.distinct().map { it to Files.isDirectory(it) }
            val sourcePaths = document.sources.associate { source ->
                val path = root.resolve(source.path).toRealPath()
                require(path.startsWith(root) && sourceInputs.any { (input, directory) ->
                    path == input || directory && path.startsWith(input)
                }) {
                    "compiler evidence source is outside the recorded source inputs"
                }
                require(sourceHash(path) == source.sha256) { "compiler evidence source bytes have changed" }
                source.path to path
            }
            val sourceBasenameCounts = document.sources.groupingBy { it.path.substringAfterLast('/') }.eachCount()
            val generatedSourcePaths by lazy(LazyThreadSafetyMode.NONE) {
                claims.asSequence()
                    .flatMap { witness -> witness.compilerEvidence.asSequence() }
                    .filter { it.role == "compilerGeneratedSource" }
                    .map(::locate)
                    .toSet()
            }
            val sourceInCompilerOutput = mutableMapOf<NodeId, Boolean>()
            fun belongsToCompilerOutput(source: NodeId): Boolean =
                sourceInCompilerOutput.getOrPut(source) { positions.any { source in indexed.declarationsByRoot[it] } }
            require(document.unmapped <= Int.MAX_VALUE - unmapped) { "compiler evidence count exceeds the supported range" }
            unmapped += document.unmapped
            for (reference in document.references) {
                require(belongsToCompilerOutput(reference.source)) {
                    "compiler evidence source declaration is absent from its compiler output"
                }
                if (indexed.selectedRootByNode[reference.source] !in positions) {
                    shadowed++
                    continue
                }
                if (reference.target !in indexed.graph.nodes) {
                    require(!hasCompiledOwner(reference.target)) { "compiler evidence target is absent from its compiled owner" }
                    outside++
                    continue
                }
                references += GraphEdge(reference.source, reference.target, EdgeKind.REFERENCE, origin = EdgeOrigin.COMPILER_REFERENCE)
            }
            for (position in envelope.callPositions) {
                require(belongsToCompilerOutput(position.source)) {
                    "compiler call position source declaration is absent from its compiler output"
                }
                if (indexed.selectedRootByNode[position.source] !in positions) {
                    positionShadowed++
                    continue
                }
                val positionFileName = position.file.path.substringAfterLast('/')
                val callerFileName = indexed.graph.node(position.source)?.location?.path?.let { callerFile ->
                    val callerFileName = callerFile.replace('\\', '/').substringAfterLast('/')
                    require(callerFileName == positionFileName) {
                        "compiler call position source contradicts the caller bytecode source"
                    }
                    callerFileName
                }
                if (position.target !in indexed.graph.nodes) {
                    require(!hasCompiledOwner(position.target)) {
                        "compiler call position target is absent from its compiled owner"
                    }
                    positionOutside++
                    continue
                }
                if ((position.source to position.target) !in exactBytecodeCalls) {
                    positionUnmatched++
                    continue
                }
                val sourcePath = sourcePaths.getValue(position.file.path)
                val generated = sourcePath in generatedSourcePaths
                if (callerFileName == null || sourceBasenameCounts.getValue(positionFileName) != 1) {
                    positionSourceUnverified++
                }
                val basis = when (document.collector) {
                    "javac-constants" -> CompilerSourceCoordinateBasis.JAVAC_UTF16_CHAR_SEQUENCE
                    "kotlin-constants" -> CompilerSourceCoordinateBasis.KOTLIN_UTF16_NORMALIZED_SOURCE
                    else -> error("v3 envelope collector was not validated")
                }
                locatedReferences += LocatedCompilerReference(
                    position.source, position.target, position.file, document.collector, document.compilerVersion,
                    basis, position.offsetUtf16, position.endOffsetUtf16, position.line, position.column, generated,
                )
            }
        }
        val uncoveredRoots = if (callPositionsCaptured) selectedCallerRoots - v3CoveredRoots else emptySet()
        val legacyDocumentsOnUncoveredRoots = if (callPositionsCaptured) legacyDocumentRoots.count { roots ->
            roots.any(uncoveredRoots::contains)
        } else 0
        var graph = indexed.graph.enrichedWith(references.sorted(), indexed.graph.externalCalls)
        if (callPositionsCaptured) graph = graph.withCompilerCallPositions(locatedReferences)
        val result = CompilerEvidenceResult(graph, outside, shadowed, unmapped,
            generations.distinct().sortedWith(compareBy({ it.processor }, { it.artifactSha256 })))
        val limitations = buildList {
            if (positionUnmapped > 0) add("compiler-call-positions-unmapped: $positionUnmapped")
            if (positionAmbiguous > 0) add("compiler-call-positions-ambiguous: $positionAmbiguous")
            if (positionOutside > 0) add("compiler-call-positions-outside-graph: $positionOutside")
            if (positionShadowed > 0) add("compiler-call-positions-shadowed: $positionShadowed")
            if (positionUnmatched > 0) add("compiler-call-positions-unmatched-bytecode: $positionUnmatched")
            if (uncoveredRoots.isNotEmpty()) add("compiler-call-positions-uncovered-roots: ${uncoveredRoots.size}")
            if (legacyDocumentsOnUncoveredRoots > 0) add("compiler-call-positions-legacy-documents: $legacyDocumentsOnUncoveredRoots")
            if (positionSourceUnverified > 0) add("compiler-call-positions-source-unverified: $positionSourceUnverified")
        }
        return CompilerEvidenceEnrichment(result, limitations)
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
