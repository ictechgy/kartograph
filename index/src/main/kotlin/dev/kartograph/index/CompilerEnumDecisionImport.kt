package dev.kartograph.index

import dev.kartograph.core.*
import java.nio.file.Path

/** 캡처별 수집기다. 같은 출력 루트로 귀속할 수 없어도 타입이 확인된 원본 위치를 보존한다. */
internal class CompilerEnumDecisionImport(
    private val indexed: IndexedClasses,
    classRoots: List<Path>,
    explicit: List<Path>,
    private val context: CompilerEvidenceContext,
) {
    val receiptPass = CompilerSourceUseReceiptPass()
    private val project = context.project.toAbsolutePath().normalize()
    private val physicalRoots: List<Path>
    private val selectedInputs: List<InputFingerprint>
    private val rootsByPath: Map<Path, List<Int>>
    private val documents = ArrayList<CapturedDocument>()
    private var totalRows = 0L
    private var claimCount = 0L
    private var outputCount = 0L
    private val receiptIdentities = HashSet<Pair<InputFingerprint, List<CompilerSourceUseClaim>>>()

    init {
        require(classRoots.size in 1..4_096) { "compiler enum decision root limit exceeded" }
        explicit.forEach(receiptPass::observe)
        (context.provenance.inputs.asSequence() + context.provenance.witnesses.asSequence().flatMap {
            (it.inputs + it.outputs + it.compilerEvidence).asSequence()
        }).filter { it.role == "compilerEvidence" }.forEach { receiptPass.observe(locateRoute(it)) }
        selectedInputs = context.provenance.inputs.filter { it.role == "classes" }
        require(selectedInputs.size == classRoots.size) { "compiler enum decision roots do not match selected provenance" }
        physicalRoots = classRoots.map { it.toRealPath() }
        require(selectedInputs.indices.all { locate(selectedInputs[it]) == physicalRoots[it] }) {
            "compiler enum decision root order does not match selected provenance"
        }
        rootsByPath = physicalRoots.indices.groupBy { physicalRoots[it] }
    }

    fun verifyFreshness(recheckReceipts: Boolean): ProvenanceVerifier.Result {
        val pass = if (recheckReceipts) receiptPass.reobserve() else receiptPass
        return ProvenanceVerifier.verifySourceUses(context.provenance, context.project, context.scope,
            context.externalInputs, pass::fingerprint)
    }

    fun record(path: Path, envelope: CompilerEvidenceEnvelope, witnesses: List<BuildWitness>, positions: Set<Int>,
        sourcePaths: Map<String, Path>, generatedPaths: Set<Path>) {
        requireNotNull(envelope.enumStats) { "enum decision capture requires a completed v4 document for every explicit receipt" }
        require(documents.size < 256 && totalRows + envelope.enumDecisions.size <= 200_000) {
            "compiler enum decision capture limit exceeded"
        }
        val document = envelope.evidence
        require(witnesses.all { it.compiler == if (document.collector == "kotlin-constants") "kotlin" else "javac" }) {
            "compiler enum decision collector does not match completed compiler"
        }
        val claims = ArrayList<CompilerSourceUseClaim>()
        for (witness in witnesses) {
            require(claimCount < 4_096 && outputCount + witness.outputs.size <= 16_384) {
                "compiler enum decision claim limit exceeded"
            }
            witness.outputs.forEach { output -> rootsByPath[locate(output)].orEmpty().forEach { ordinal ->
                require(output == selectedInputs[ordinal]) {
                    "compiler enum decisions do not support differing portable identities for one class root"
                }
            } }
            val claim = CompilerSourceUseClaim(witness.scope, witness.compiler, witness.artifact, witness.outputs)
            require(claim !in claims) { "duplicate compiler enum decision claim" }
            claims += claim
            claimCount++
            outputCount += witness.outputs.size
        }
        val receipts = witnesses.flatMap { witness -> witness.compilerEvidence.filter {
            it.role == "compilerEvidence" && it.sha256 == receiptPass.byPhysical.getValue(path).fingerprint && locate(it) == path
        } }.distinct()
        require(receipts.size == 1) { "compiler enum decision document requires one exact receipt identity" }
        require(receiptIdentities.add(receipts.single() to claims.sortedWith(compareBy({ it.scope }, { it.compiler }, { it.task })))) {
            "duplicate compiler enum decision unit input"
        }
        totalRows += envelope.enumDecisions.size
        documents += CapturedDocument(envelope, receipts.single(), claims, positions, sourcePaths, generatedPaths,
            document.sources.groupingBy { it.path.substringAfterLast('/') }.eachCount())
    }

    fun finish(graph: CodeGraph): CompilerEnumDecisionEvidence {
        require(documents.isNotEmpty()) { "compiler enum decisions require a completed v4 document" }
        val owners = Array(physicalRoots.size) { ArrayList<NodeId>() }
        var ownerCount = 0
        for (node in indexed.graph.nodes.values) {
            if (!node.id.value.startsWith("class:") || NodeAttribute.EXTERNAL_STUB in node.attributes) continue
            val ordinal = indexed.selectedRootByNode[node.id] ?: continue
            require(ordinal in owners.indices && node.id in indexed.declarationsByRoot[ordinal]) {
                "invalid compiler enum decision class ownership"
            }
            require(++ownerCount <= 1_000_000) { "compiler enum decision owner limit exceeded" }
            owners[ordinal].add(node.id)
        }
        val roots = selectedInputs.mapIndexed { ordinal, input -> CompilerEnumDecisionRoot(ordinal, input, owners[ordinal]) }
        val units = ArrayList<CompilerEnumDecisionUnit>()
        val sites = ArrayList<CompilerEnumDecisionSite>()
        for (captured in documents) {
            val document = captured.envelope.evidence
            val rawStats = requireNotNull(captured.envelope.enumStats)
            val resolved = ArrayList<ResolvedSite>()
            for (site in captured.envelope.enumDecisions) resolved += resolve(site, captured)
            val resolvedCount = resolved.count { it.attribution == EnumDecisionAttribution.RESOLVED_SAME_OUTPUT_ROOT }
            val unit = CompilerEnumDecisionUnit(captured.receipt, document.collector, document.compilerVersion,
                document.artifactSha256, document.inputToken, captured.claims,
                CompilerEnumDecisionProducerCounts(rawStats.observed, rawStats.emitted, rawStats.unsupported, rawStats.ignored),
                CompilerEnumDecisionImportCounts(resolvedCount, resolved.size - resolvedCount))
            units += unit
            val basis = if (document.collector == "kotlin-constants") CompilerSourceCoordinateBasis.KOTLIN_UTF16_NORMALIZED_SOURCE
                else CompilerSourceCoordinateBasis.JAVAC_UTF16_CHAR_SEQUENCE
            for (resolvedSite in resolved) {
                val raw = resolvedSite.raw
                sites += CompilerEnumDecisionSite(unit.id, raw.enumOwner, raw.constants, raw.kind, raw.lexicalClass,
                    raw.lexicalLambda, raw.file, basis, raw.offsetUtf16, raw.endOffsetUtf16, raw.line, raw.column,
                    captured.sourcePaths.getValue(raw.file.path) in captured.generatedPaths,
                    resolvedSite.sourceRoot?.let { roots[it].id }, resolvedSite.enumRoot?.let { roots[it].id },
                    resolvedSite.attribution)
            }
        }
        val evidence = CompilerEnumDecisionEvidence(roots, units, sites)
        evidence.requireMatchesGraph(graph)
        evidence.requireMatchesProvenance(context.provenance, context.scope)
        require(verifyFreshness(recheckReceipts = true).status == "matched") { "compiler enum decision inputs changed during capture" }
        return evidence
    }

    private fun resolve(raw: UnverifiedCompilerEnumDecision, captured: CapturedDocument): ResolvedSite {
        val positions = captured.positions
        val lexical = raw.lexicalClass
        val sourceRoot = if (lexical == null) positions.singleOrNull() else indexed.selectedRootByNode[lexical]
        val enumRoot = indexed.selectedRootByNode[raw.enumOwner]
        fun result(reason: EnumDecisionAttribution) = ResolvedSite(raw, sourceRoot, enumRoot, reason)
        if (sourceRoot == null) return result(EnumDecisionAttribution.SOURCE_ROOT_UNRESOLVED)
        if (lexical != null) {
            val declaringRoots = positions.filter { lexical in indexed.declarationsByRoot[it] }
            if (declaringRoots.size != 1) return result(EnumDecisionAttribution.SOURCE_ROOT_UNRESOLVED)
            if (declaringRoots.single() != sourceRoot) return result(EnumDecisionAttribution.SHADOWED_SOURCE)
            val name = indexed.graph.node(lexical)?.location?.path?.replace('\\', '/')?.substringAfterLast('/')
            val sourceName = raw.file.path.substringAfterLast('/')
            if (name == null || name != sourceName || captured.basenameCounts[sourceName] != 1) {
                return result(EnumDecisionAttribution.SOURCE_ROOT_UNRESOLVED)
            }
        }
        if (enumRoot == null) return result(EnumDecisionAttribution.ENUM_ROOT_UNRESOLVED)
        val declaredEnum = positions.any { raw.enumOwner in indexed.declarationsByRoot[it] }
        if (declaredEnum && enumRoot !in positions) return result(EnumDecisionAttribution.SHADOWED_ENUM)
        if (!declaredEnum || enumRoot !in positions) return result(EnumDecisionAttribution.OUTSIDE_CLAIMED_OUTPUTS)
        if (enumRoot != sourceRoot) return result(EnumDecisionAttribution.DIFFERENT_OUTPUT_ROOT)
        val node = indexed.graph.node(raw.enumOwner)
        val inventory = node?.enumConstants
        if (node == null || node.kind != NodeKind.ENUM || NodeAttribute.EXTERNAL_STUB in node.attributes || inventory == null) {
            return result(EnumDecisionAttribution.ENUM_INVENTORY_UNAVAILABLE)
        }
        if (!inventory.containsAll(raw.constants)) return result(EnumDecisionAttribution.CONSTANTS_OUTSIDE_INVENTORY)
        return result(EnumDecisionAttribution.RESOLVED_SAME_OUTPUT_ROOT)
    }

    private fun locateRoute(input: InputFingerprint): Path =
        (if (input.path.startsWith("external/")) context.externalInputs[input.path] else project.resolve(input.path))
            ?: throw IllegalArgumentException("compiler enum decision input binding is missing")

    private fun locate(input: InputFingerprint): Path = locateRoute(input).toRealPath()

    private data class ResolvedSite(val raw: UnverifiedCompilerEnumDecision, val sourceRoot: Int?, val enumRoot: Int?,
        val attribution: EnumDecisionAttribution)

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
