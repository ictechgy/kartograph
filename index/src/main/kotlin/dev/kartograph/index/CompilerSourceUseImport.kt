package dev.kartograph.index

import dev.kartograph.core.BuildWitness
import dev.kartograph.core.CodeGraph
import dev.kartograph.core.CompilerSourceCoordinateBasis
import dev.kartograph.core.CompilerSourceUse
import dev.kartograph.core.CompilerSourceUseClaim
import dev.kartograph.core.CompilerSourceUseEvidence
import dev.kartograph.core.CompilerSourceUseImportCounts
import dev.kartograph.core.CompilerSourceUseKind
import dev.kartograph.core.CompilerSourceUseProducerCounts
import dev.kartograph.core.CompilerSourceUseRoot
import dev.kartograph.core.CompilerSourceUseUnit
import dev.kartograph.core.InputFingerprint
import dev.kartograph.core.NodeId
import dev.kartograph.core.NodeKind
import java.nio.file.Files
import java.nio.file.Path

/** 기존 admission pass의 호출 한 번에만 존재하는 별도 sink다. cache/전역 상태를 사용하지 않는다. */
internal class CompilerSourceUseImport(
    private val indexed: IndexedClasses,
    classRoots: List<Path>,
    explicit: List<Path>,
    private val context: CompilerEvidenceContext,
) {
    private val receiptPass = CompilerSourceUseReceiptPass()
    private val project = context.project.toAbsolutePath().normalize()
    private val physicalRoots: List<Path>
    private val selectedInputs: List<InputFingerprint>
    private val rootsByPath: Map<Path, List<Int>>
    private val documents = ArrayList<CapturedDocument>()
    private var claimCount = 0L
    private var outputCount = 0L
    private val receiptIdentities = HashSet<Pair<InputFingerprint, List<CompilerSourceUseClaim>>>()

    init {
        require(classRoots.size in 1..4_096) { "compiler source use root limit exceeded" }
        // 초기 pass는 명시 입력과 provenance의 모든 raw receipt를 합쳐 한 번씩 제한해 읽는다.
        explicit.forEach(receiptPass::observe)
        val provenance = context.provenance
        (provenance.inputs.asSequence() + provenance.witnesses.asSequence().flatMap {
            (it.inputs + it.outputs + it.compilerEvidence).asSequence()
        }).filter { it.role == "compilerEvidence" }.forEach { receiptPass.observe(locateRoute(it)) }
        selectedInputs = context.provenance.inputs.filter { it.role == "classes" }
        require(selectedInputs.size == classRoots.size) { "compiler source use roots do not match selected provenance" }
        physicalRoots = classRoots.map { it.toRealPath() }
        require(selectedInputs.indices.all { locate(selectedInputs[it]) == physicalRoots[it] }) {
            "compiler source use root order does not match selected provenance"
        }
        rootsByPath = physicalRoots.indices.groupBy { physicalRoots[it] }
    }

    fun read(physical: Path): BoundedCompilerEvidenceInput = receiptPass.byPhysical.getValue(physical)

    /** 최종 pass는 모든 receipt를 새 예산으로 다시 읽고 초기 route/내용과 비교한다. */
    fun verifyFreshness(recheckReceipts: Boolean): ProvenanceVerifier.Result {
        val pass = if (recheckReceipts) receiptPass.reobserve() else receiptPass
        return ProvenanceVerifier.verifySourceUses(context.provenance, context.project, context.scope,
            context.externalInputs, pass::fingerprint)
    }

    fun record(path: Path, envelope: CompilerEvidenceEnvelope, witnesses: List<BuildWitness>, positions: Set<Int>,
        sourcePaths: Map<String, Path>, generatedPaths: Set<Path>) {
        val stats = envelope.callStats ?: return
        require(documents.size < 256) { "compiler source use unit limit exceeded" }
        val document = envelope.evidence
        require(witnesses.all { it.compiler == if (document.collector == "kotlin-constants") "kotlin" else "javac" }) {
            "compiler source use collector does not match completed compiler"
        }
        val claims = ArrayList<CompilerSourceUseClaim>()
        for (witness in witnesses) {
            require(claimCount < 4_096 && outputCount + witness.outputs.size.toLong() <= 16_384) {
                "compiler source use claim limit exceeded"
            }
            // 정당한 외부 slot alias도 이 새 진입점에서는 지원하지 않는다고 명시적으로 실패한다.
            witness.outputs.forEach { output -> rootsByPath[locate(output)].orEmpty().forEach { ordinal ->
                require(output == selectedInputs[ordinal]) {
                    "compiler source uses do not support differing portable identities for the same class root"
                }
            } }
            val claim = CompilerSourceUseClaim(witness.scope, witness.compiler, witness.artifact, witness.outputs)
            require(claim !in claims) { "duplicate compiler source use claim" }
            claims += claim
            claimCount++
            outputCount += witness.outputs.size.toLong()
        }
        val receipts = witnesses.flatMap { witness -> witness.compilerEvidence.filter {
            it.role == "compilerEvidence" && it.sha256 == receiptPass.byPhysical.getValue(path).fingerprint && locate(it) == path
        } }.distinct()
        require(receipts.size == 1) { "compiler source use document requires one exact receipt identity" }
        // 동일 물리 파일은 이미 한 번만 읽었다. 다른 파일의 동일 receipt/claim 재사용은 계수 전에 거부한다.
        require(receiptIdentities.add(receipts.single() to claims.sortedWith(compareBy({ it.scope }, { it.compiler }, { it.task })))) {
            "duplicate compiler source use unit input"
        }
        val selectors = HashSet<List<Any>>()
        document.sources.forEach { source -> boundedText(source.path) }
        envelope.callPositions.forEach { position ->
            listOf(position.source.value, position.target.value, position.file.path).forEach(::boundedText)
            listOf(position.source, position.target).forEach { id ->
                require(id.value.startsWith("method:") && id.value.substringAfter("method:").let { '#' in it && '(' in it && ')' in it } &&
                    id.value.none { it.code < 32 }) { "compiler source use requires an exact JVM method identity" }
            }
            require(selectors.add(listOf(position.source, position.file.path, position.offsetUtf16))) {
                "duplicate or conflicting compiler source use selector"
            }
        }
        documents += CapturedDocument(envelope, receipts.single(), claims, positions, sourcePaths, generatedPaths,
            document.sources.groupingBy { it.path.substringAfterLast('/') }.eachCount())
    }

    fun attach(graph: CodeGraph): CodeGraph {
        require(documents.isNotEmpty()) { "compiler source uses require a completed v3 document" }
        // 모든 document gate와 portable alias 검사가 끝난 뒤 새 channel을 구성한다.
        val owners = Array(physicalRoots.size) { ArrayList<NodeId>() }
        var ownerCount = 0
        for (node in indexed.graph.nodes.values) {
            if (node.kind !in setOf(NodeKind.METHOD, NodeKind.FUNCTION, NodeKind.CONSTRUCTOR)) continue
            val ordinal = requireNotNull(indexed.selectedRootByNode[node.id]) { "compiler source use callable ownership is unavailable" }
            require(ordinal in owners.indices && node.id in indexed.declarationsByRoot[ordinal]) { "invalid compiler source use callable ownership" }
            require(ownerCount < 1_000_000) { "compiler source use owner limit exceeded" }
            owners[ordinal].add(node.id)
            ownerCount++
        }
        val roots = selectedInputs.mapIndexed { ordinal, input -> CompilerSourceUseRoot(ordinal, input, owners[ordinal]) }
        val units = ArrayList<CompilerSourceUseUnit>()
        val uses = ArrayList<CompilerSourceUse>()
        val compiledOwners = indexed.declarationsByRoot.asSequence().flatMap { it.asSequence() }.filter { it.value.startsWith("class:") }.toHashSet()
        val assertions = HashMap<SourceAssertionKey, SourceAssertion>()
        val maximumAssertions = documents.sumOf { it.envelope.callPositions.size.toLong() }
        require(maximumAssertions <= 256L * CompilerEvidenceReader.MAX_ROWS) { "compiler source use raw row limit exceeded" }
        for (captured in documents) {
            val envelope = captured.envelope
            val document = envelope.evidence
            val stats = requireNotNull(envelope.callStats)
            val admitted = ArrayList<UnverifiedCompilerCallPosition>()
            var outside = 0; var shadowedCaller = 0; var shadowedTarget = 0; var outsideOutputs = 0; var sourceUnverified = 0
            val declared = captured.positions.asSequence().flatMap { indexed.declarationsByRoot[it].asSequence() }.toHashSet()
            for (position in envelope.callPositions) {
                require(position.source in declared) { "compiler source use source declaration is absent from its compiler output" }
                if (indexed.selectedRootByNode[position.source] !in captured.positions) {
                    shadowedCaller++
                    continue
                }
                // 다른 root에서 이긴 caller의 SourceFile은 위에서 제외한 shadowed 행의 근거가 아니다.
                val fileName = position.file.path.substringAfterLast('/')
                val callerName = indexed.graph.node(position.source)?.location?.path?.replace('\\', '/')?.substringAfterLast('/')
                require(callerName == null || callerName == fileName) { "compiler source use source contradicts the caller bytecode source" }
                val sourceRoot = roots[indexed.selectedRootByNode.getValue(position.source)]
                val targetRoot = indexed.selectedRootByNode[position.target]?.let { roots[it] }
                val basis = if (document.collector == "kotlin-constants") CompilerSourceCoordinateBasis.KOTLIN_UTF16_NORMALIZED_SOURCE
                    else CompilerSourceCoordinateBasis.JAVAC_UTF16_CHAR_SEQUENCE
                val assertionKey = SourceAssertionKey(sourceRoot.id, position.source, position.file.path,
                    position.file.sha256, basis, position.offsetUtf16)
                val assertion = SourceAssertion(position.target, targetRoot?.id, position.endOffsetUtf16, position.line, position.column)
                val previous = assertions[assertionKey]
                if (previous == null) {
                    require(assertions.size.toLong() < maximumAssertions) { "compiler source use assertion limit exceeded" }
                    assertions[assertionKey] = assertion
                } else require(previous == assertion) { "conflicting compiler source use assertions across units" }
                if (position.target !in indexed.graph.nodes) {
                    val owner = position.target.value.substringAfter(':').substringBefore('#')
                    require(JvmNodeId.classId(owner) !in compiledOwners) { "compiler source use target is absent from its compiled owner" }
                    outside++
                    continue
                }
                if (position.target in declared && indexed.selectedRootByNode[position.target] !in captured.positions) {
                    shadowedTarget++
                    continue
                }
                if (position.target !in declared) {
                    outsideOutputs++
                    continue
                }
                if (callerName == null || captured.basenameCounts.getValue(fileName) != 1) {
                    sourceUnverified++
                    continue
                }
                require(uses.size.toLong() + admitted.size.toLong() < 200_000) { "compiler source use retained row limit exceeded" }
                admitted += position
            }
            val producer = CompilerSourceUseProducerCounts(stats.observed, stats.emitted, stats.unmapped, stats.ambiguous)
            val counts = CompilerSourceUseImportCounts(admitted.size, outside, shadowedCaller, shadowedTarget, outsideOutputs, sourceUnverified)
            val unit = CompilerSourceUseUnit(captured.receipt, document.collector, document.compilerVersion,
                document.artifactSha256, document.inputToken, captured.claims, producer, counts, 0)
            units += unit
            val basis = if (document.collector == "kotlin-constants") CompilerSourceCoordinateBasis.KOTLIN_UTF16_NORMALIZED_SOURCE
                else CompilerSourceCoordinateBasis.JAVAC_UTF16_CHAR_SEQUENCE
            admitted.forEach { position ->
                uses += CompilerSourceUse(unit.id, position.source, position.target,
                    roots[indexed.selectedRootByNode.getValue(position.source)].id,
                    roots[indexed.selectedRootByNode.getValue(position.target)].id,
                    CompilerSourceUseKind.INVOCATION_SELECTION, position.file, document.collector, document.compilerVersion,
                    basis, position.offsetUtf16, position.endOffsetUtf16, position.line, position.column,
                    captured.sourcePaths.getValue(position.file.path) in captured.generatedPaths)
            }
        }
        val evidence = CompilerSourceUseEvidence(roots, units, uses)
        evidence.requireMatchesProvenance(context.provenance, context.scope)
        // 매 호출 앞뒤의 내용 대응을 확인한다. class cache hit여도 source 재검사를 생략하지 않는다.
        require(verifyFreshness(recheckReceipts = true).status == "matched") {
            "compiler source use inputs changed during capture"
        }
        return graph.withCompilerSourceUses(evidence)
    }

    private fun locateRoute(input: InputFingerprint): Path =
        (if (input.path.startsWith("external/")) context.externalInputs[input.path] else project.resolve(input.path))
            ?: throw IllegalArgumentException("compiler source use input binding is missing")

    private fun locate(input: InputFingerprint): Path = locateRoute(input).toRealPath()

    private fun boundedText(value: String) {
        require(value.length <= 16_384 && value.toByteArray(Charsets.UTF_8).size <= 16_384) { "compiler source use string limit exceeded" }
    }

    private data class SourceAssertionKey(val rootId: String, val source: NodeId, val path: String, val sourceHash: String,
        val basis: CompilerSourceCoordinateBasis, val offset: Int)
    private data class SourceAssertion(val target: NodeId, val targetRootId: String?, val end: Int, val line: Int, val column: Int)

    private class CapturedDocument(
        val envelope: CompilerEvidenceEnvelope,
        val receipt: InputFingerprint,
        val claims: List<CompilerSourceUseClaim>,
        val positions: Set<Int>,
        val sourcePaths: Map<String, Path>,
        val generatedPaths: Set<Path>,
        val basenameCounts: Map<String, Int>,
    )
}

/** 최대 256개/각 16 MiB/합계 64 MiB를 raw bytes로 적용하는 한 currentness 관찰 pass다. */
internal class CompilerSourceUseReceiptPass {
    val byPhysical = LinkedHashMap<Path, BoundedCompilerEvidenceInput>()
    private val routes = LinkedHashMap<Path, Path>()
    private var acceptedBytes = 0L

    fun observe(file: Path) {
        val route = file.toAbsolutePath()
        require(!Files.isSymbolicLink(route)) { "symbolic compiler evidence inputs are not supported" }
        val physical = route.toRealPath()
        val prior = routes[route]
        require(prior == null || prior == physical) { "compiler source use receipt route changed during capture" }
        if (physical !in byPhysical) {
            require(byPhysical.size < 256) { "compiler source use document limit exceeded" }
            val remaining = 64L * 1024 * 1024 - acceptedBytes
            val bounded = BoundedCompilerEvidenceReader.read(route, minOf(CompilerEvidenceReader.MAX_BYTES.toLong(), remaining).toInt())
            acceptedBytes = Math.addExact(acceptedBytes, bounded.byteCount.toLong())
            byPhysical[physical] = bounded
        }
        routes[route] = physical
    }

    fun fingerprint(file: Path): String {
        val route = file.toAbsolutePath()
        require(!Files.isSymbolicLink(route) && routes[route] == route.toRealPath()) {
            "compiler source use receipt route changed during capture"
        }
        return byPhysical.getValue(routes.getValue(route)).fingerprint
    }

    fun reobserve(): CompilerSourceUseReceiptPass {
        val current = CompilerSourceUseReceiptPass()
        for ((route, physical) in routes) {
            current.observe(route)
            require(current.routes[route] == physical && current.byPhysical.getValue(physical).fingerprint == byPhysical.getValue(physical).fingerprint) {
                "compiler source use receipt changed during capture"
            }
        }
        return current
    }
}
