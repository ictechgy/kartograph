package dev.kartograph.core

/** 현재 소스에 명시된 유한 enum 판단의 종류다. 실행 경로나 도달성을 뜻하지 않는다. */
public enum class EnumDecisionKind { EXPLICIT_FALLBACK, FINITE_MEMBERSHIP, NEGATED_FINITE_MEMBERSHIP }

/** capture 때 완료 출력과 enum 소유권을 연결한 결과다. view는 이 분류를 다시 쓰지 않는다. */
public enum class EnumDecisionAttribution {
    RESOLVED_SAME_OUTPUT_ROOT, SOURCE_ROOT_UNRESOLVED, ENUM_ROOT_UNRESOLVED,
    SHADOWED_SOURCE, SHADOWED_ENUM, OUTSIDE_CLAIMED_OUTPUTS, DIFFERENT_OUTPUT_ROOT,
    ENUM_INVENTORY_UNAVAILABLE, CONSTANTS_OUTSIDE_INVENTORY,
}

/** 선택한 class root의 전체 class 소유 목록이며 pruning 후에도 그대로 남긴다. */
public class CompilerEnumDecisionRoot(
    public val ordinal: Int,
    public val input: InputFingerprint,
    capturedClassOwners: Iterable<NodeId>,
) {
    public val capturedClassOwners: List<NodeId> = SourceUseCanonical.list(capturedClassOwners, 1_000_000)
        .also { ids ->
            ids.forEach(::enumDecisionOwner)
            require(ids.toSet().size == ids.size) { "duplicate enum decision class owner" }
        }.sorted().let(SourceUseCanonical::freeze)
    private val owners: Set<NodeId> = this.capturedClassOwners.toSet()
    public val id: String
    init {
        require(ordinal >= 0) { "invalid enum decision root ordinal" }
        SourceUseCanonical.input(input, "classes")
        id = SourceUseCanonical.hash(sequenceOf("kartograph-compiler-enum-decision-root-v1", ordinal.toString()) +
            SourceUseCanonical.fields(input).asSequence() + sequenceOf(this.capturedClassOwners.size.toString()) +
            this.capturedClassOwners.asSequence().map { it.value })
    }
    internal fun owns(id: NodeId): Boolean = id in owners
    override fun equals(other: Any?): Boolean = other is CompilerEnumDecisionRoot && id == other.id
    override fun hashCode(): Int = id.hashCode()
}

/** producer가 관찰한 모든 판단을 중복 없이 나눈 계수다. */
public data class CompilerEnumDecisionProducerCounts(val observed: Int, val emitted: Int, val unsupported: Int, val ignored: Int) {
    init {
        require(listOf(observed, emitted, unsupported, ignored).all { it >= 0 } &&
            observed.toLong() == emitted.toLong() + unsupported.toLong() + ignored.toLong()) { "invalid enum decision producer counts" }
    }
}

/** emitted 행의 원래 귀속 결과다. paging과 pruning은 이 계수를 바꾸지 않는다. */
public data class CompilerEnumDecisionImportCounts(val resolved: Int, val unresolved: Int) {
    init { require(resolved >= 0 && unresolved >= 0) { "invalid enum decision import counts" } }
}

/** 완료 receipt와 compiler 입력·출력 주장에 묶인 원본 관찰 단위다. */
public class CompilerEnumDecisionUnit(
    public val receipt: InputFingerprint,
    public val collector: String,
    public val compilerVersion: String,
    public val collectorArtifactFingerprint: String,
    public val inputToken: String,
    claims: Iterable<CompilerSourceUseClaim>,
    public val producer: CompilerEnumDecisionProducerCounts,
    public val importCounts: CompilerEnumDecisionImportCounts,
) {
    public val claims: List<CompilerSourceUseClaim> = SourceUseCanonical.list(claims, 4_096)
        .sortedWith { left, right -> SourceUseCanonical.compareEncoding(left.identityFields, right.identityFields) }
        .let(SourceUseCanonical::freeze)
    public val id: String
    init {
        SourceUseCanonical.input(receipt, "compilerEvidence")
        SourceUseCanonical.compiler(collector, compilerVersion)
        SourceUseCanonical.hashText(collectorArtifactFingerprint)
        SourceUseCanonical.hashText(inputToken)
        require(this.claims.isNotEmpty() && this.claims.toSet().size == this.claims.size) { "missing or duplicate enum decision claim" }
        require(this.claims.sumOf { it.outputs.size.toLong() } <= 16_384) { "enum decision output limit exceeded" }
        require(this.claims.all { it.compiler == if (collector == "kotlin-constants") "kotlin" else "javac" }) { "enum decision claim does not match collector" }
        require(producer.emitted.toLong() == importCounts.resolved.toLong() + importCounts.unresolved.toLong()) { "invalid enum decision import partition" }
        id = SourceUseCanonical.hash(sequenceOf("kartograph-compiler-enum-decision-unit-v1") +
            SourceUseCanonical.fields(receipt).asSequence() +
            sequenceOf(collector, compilerVersion, collectorArtifactFingerprint, inputToken, this.claims.size.toString()) +
            this.claims.asSequence().flatMap { it.identityFields.asSequence() } +
            sequenceOf(producer.observed.toString(), producer.emitted.toString(), producer.unsupported.toString(), producer.ignored.toString()))
    }
    override fun equals(other: Any?): Boolean = other is CompilerEnumDecisionUnit && id == other.id && importCounts == other.importCounts
    override fun hashCode(): Int = listOf(id, importCounts).hashCode()
}

/** 현재 원본의 판단 위치다. lexicalClass와 lambda는 설명용 소속이며 call reachability가 아니다. */
public class CompilerEnumDecisionSite(
    public val unitId: String,
    public val enumOwner: NodeId,
    constants: Iterable<NodeId>,
    public val kind: EnumDecisionKind,
    public val lexicalClass: NodeId?,
    public val lexicalLambda: Boolean,
    public val file: CompilerEvidenceSource,
    public val coordinateBasis: CompilerSourceCoordinateBasis,
    public val offsetUtf16: Int,
    public val endOffsetUtf16: Int,
    public val line: Int,
    public val column: Int,
    public val generated: Boolean,
    public val sourceRootId: String?,
    public val enumRootId: String?,
    public val attribution: EnumDecisionAttribution,
) {
    public val constants: List<NodeId> = SourceUseCanonical.list(constants, 65_536).also { values ->
        values.forEach { SourceUseCanonical.text(it.value); require(EnumConstantIds.belongsTo(enumOwner, it)) { "invalid enum decision constant" } }
        require(values.toSet().size == values.size) { "duplicate enum decision constant" }
    }.sorted().let(SourceUseCanonical::freeze)
    public val id: String
    init {
        SourceUseCanonical.hashText(unitId)
        enumDecisionOwner(enumOwner)
        lexicalClass?.let(::enumDecisionOwner)
        sourceRootId?.let(SourceUseCanonical::hashText)
        enumRootId?.let(SourceUseCanonical::hashText)
        SourceUseCanonical.input(InputFingerprint("sourceFile", file.path, file.sha256), "sourceFile")
        require(offsetUtf16 >= 0 && endOffsetUtf16 > offsetUtf16 && line > 0 && column > 0) { "invalid enum decision coordinates" }
        require(attribution != EnumDecisionAttribution.RESOLVED_SAME_OUTPUT_ROOT ||
            sourceRootId != null && sourceRootId == enumRootId) { "resolved enum decision requires one output root" }
        id = SourceUseCanonical.hash(sequenceOf("kartograph-compiler-enum-decision-site-v1", unitId, enumOwner.value, kind.name,
            this.constants.size.toString()) + this.constants.asSequence().map { it.value } +
            sequenceOf(lexicalClass?.value.orEmpty(), lexicalLambda.toString(), file.path, file.sha256, coordinateBasis.name,
                offsetUtf16.toString(), endOffsetUtf16.toString(), line.toString(), column.toString(), generated.toString(),
                sourceRootId.orEmpty(), enumRootId.orEmpty(), attribution.name))
    }
    override fun equals(other: Any?): Boolean = other is CompilerEnumDecisionSite && id == other.id
    override fun hashCode(): Int = id.hashCode()
}

/** null 채널과 검사한 빈 결과를 구분하며 원본 rows와 root 목록을 view에서 삭제하지 않는다. */
public class CompilerEnumDecisionEvidence(
    roots: Iterable<CompilerEnumDecisionRoot>,
    units: Iterable<CompilerEnumDecisionUnit>,
    sites: Iterable<CompilerEnumDecisionSite>,
) {
    public val roots: List<CompilerEnumDecisionRoot> = SourceUseCanonical.list(roots, 4_096).sortedBy { it.ordinal }.let(SourceUseCanonical::freeze)
    public val units: List<CompilerEnumDecisionUnit> = SourceUseCanonical.list(units, 256).sortedBy { it.id }.let(SourceUseCanonical::freeze)
    public val sites: List<CompilerEnumDecisionSite> = SourceUseCanonical.list(sites, 200_000).sortedBy { it.id }.let(SourceUseCanonical::freeze)
    public val uncoveredRoots: List<CompilerEnumDecisionRoot>
    init {
        require(this.roots.isNotEmpty() && this.units.isNotEmpty()) { "enum decisions require selected roots and a completed unit" }
        require(this.roots.withIndex().all { (index, root) -> root.ordinal == index } &&
            this.roots.map { it.id }.toSet().size == this.roots.size) { "invalid enum decision root order" }
        require(this.roots.sumOf { it.capturedClassOwners.size.toLong() } <= 1_000_000) { "enum decision owner limit exceeded" }
        require(this.units.sumOf { it.claims.size.toLong() } <= 4_096 &&
            this.units.sumOf { unit -> unit.claims.sumOf { it.outputs.size.toLong() } } <= 16_384) { "enum decision claim limit exceeded" }
        require(this.sites.sumOf { it.constants.size.toLong() } <= 1_000_000) { "enum decision constant limit exceeded" }
        require(this.units.map { it.id }.toSet().size == this.units.size) { "duplicate enum decision unit" }
        require(this.sites.map { it.id }.toSet().size == this.sites.size) { "duplicate enum decision site" }
        val owners = HashSet<NodeId>()
        this.roots.forEach { root -> root.capturedClassOwners.forEach { require(owners.add(it)) { "enum decision class belongs to multiple roots" } } }
        val byRoot = this.roots.associateBy { it.id }
        val byUnit = this.units.associateBy { it.id }
        val outputs = this.units.associate { unit -> unit.id to unit.claims.flatMap { it.outputs }.toSet() }
        val selected = this.roots.map { it.input }.toSet()
        require(outputs.values.all { claimed -> claimed.any(selected::contains) }) { "enum decision unit has no selected output" }
        val selectors = HashSet<List<Any?>>()
        val assertions = HashMap<List<Any?>, List<Any?>>()
        val resolved = HashMap<String, Int>()
        val unresolved = HashMap<String, Int>()
        this.sites.forEach { site ->
            val unit = requireNotNull(byUnit[site.unitId]) { "enum decision unit is absent" }
            require(unit.collector == "kotlin-constants" && site.coordinateBasis == CompilerSourceCoordinateBasis.KOTLIN_UTF16_NORMALIZED_SOURCE ||
                unit.collector == "javac-constants" && site.coordinateBasis == CompilerSourceCoordinateBasis.JAVAC_UTF16_CHAR_SEQUENCE) { "enum decision coordinate basis does not match collector" }
            val sourceRoot = site.sourceRootId?.let { requireNotNull(byRoot[it]) { "enum decision source root is absent" } }
            val enumRoot = site.enumRootId?.let { requireNotNull(byRoot[it]) { "enum decision enum root is absent" } }
            if (site.attribution == EnumDecisionAttribution.RESOLVED_SAME_OUTPUT_ROOT) {
                require(sourceRoot != null && enumRoot != null && sourceRoot.id == enumRoot.id &&
                    sourceRoot.input in outputs.getValue(unit.id)) { "resolved enum decision is outside claimed output" }
                require(enumRoot.owns(site.enumOwner)) { "enum decision enum ownership mismatch" }
                require(site.lexicalClass == null || sourceRoot.owns(site.lexicalClass)) { "enum decision source ownership mismatch" }
                resolved[unit.id] = (resolved[unit.id] ?: 0) + 1
            } else unresolved[unit.id] = (unresolved[unit.id] ?: 0) + 1
            require(selectors.add(listOf(site.unitId, site.file.path, site.offsetUtf16))) { "duplicate or conflicting enum decision selector" }
            val key = listOf(site.sourceRootId, site.file.path, site.file.sha256, site.coordinateBasis, site.offsetUtf16)
            val value = listOf(site.enumOwner, site.constants, site.kind, site.lexicalClass, site.lexicalLambda,
                site.enumRootId, site.endOffsetUtf16, site.line, site.column, site.generated, site.attribution)
            val previous = assertions.putIfAbsent(key, value)
            require(previous == null || previous == value) { "conflicting enum decision assertions across units" }
        }
        this.units.forEach { unit ->
            require(unit.importCounts.resolved == (resolved[unit.id] ?: 0) &&
                unit.importCounts.unresolved == (unresolved[unit.id] ?: 0)) { "enum decision site count does not match capture" }
        }
        val covered = outputs.values.flatten().toSet()
        uncoveredRoots = SourceUseCanonical.freeze(this.roots.filter { it.capturedClassOwners.isNotEmpty() && it.input !in covered })
    }

    /** 남은 정점의 사실만 검사한다. 사라진 inventory를 root 목록에서 재구성하지 않는다. */
    public fun requireMatchesGraph(graph: CodeGraph) {
        val inventories = HashMap<NodeId, Set<NodeId>?>()
        sites.filter { it.attribution == EnumDecisionAttribution.RESOLVED_SAME_OUTPUT_ROOT }.forEach { site ->
            graph.node(site.enumOwner)?.let { owner ->
                require(owner.kind == NodeKind.ENUM && NodeAttribute.EXTERNAL_STUB !in owner.attributes) { "resolved enum decision owner is not an indexed enum" }
                val inventory = inventories.getOrPut(owner.id) { owner.enumConstants?.toSet() }
                require(inventory == null || site.constants.all { it in inventory }) { "resolved enum decision constants are outside inventory" }
            }
        }
    }

    /** 저장된 provenance의 일관성만 확인한다. 파일 currentness나 producer 인증을 재증명하지 않는다. */
    public fun requireMatchesProvenance(provenance: SnapshotProvenance, scope: String) {
        requireMatchesProvenance(provenance, scope, null)
    }

    /** 테스트의 작업 계수는 해시 발생만 관찰하며 입력/검증 결과를 바꾸지 않는다. */
    internal fun requireMatchesProvenance(provenance: SnapshotProvenance, scope: String, onTokenHash: (() -> Unit)?) {
        require(provenance.memberScopes.isEmpty()) { "compiler enum decision aggregation is not supported" }
        require(roots.map { it.input } == provenance.inputs.filter { it.role == "classes" }) { "compiler enum decision roots do not match selected provenance" }
        val bindings = HashMap<EnumDecisionWitnessKey, EnumDecisionWitnessBinding>()
        for (unit in units) for (claim in unit.claims) {
            require(claim.scope == scope) { "compiler enum decision scope mismatch" }
            val key = EnumDecisionWitnessKey(claim.scope, claim.compiler, claim.task, claim.outputs, unit.inputToken)
            val binding = bindings.getOrPut(key) { EnumDecisionWitnessBinding() }
            binding.requestedReceipts.add(unit.receipt)
            binding.requestedArtifacts.add(unit.collectorArtifactFingerprint)
        }
        val enabled = InputFingerprint("options", "compiler-enum-decisions", SourceUseCanonical.hash(sequenceOf("enumDecisions=true")))
        // claim × witness × inputs를 순회하지 않는다. 필요한 binding만 보관하고 witness는 한 번 방문한다.
        for (witness in provenance.witnesses) {
            val token = witness.evidenceToken ?: continue
            val key = EnumDecisionWitnessKey(witness.scope, witness.compiler, witness.artifact, witness.outputs, token)
            val binding = bindings[key] ?: continue
            val previousInputs = binding.inputs
            if (previousInputs != null) {
                require(previousInputs == witness.inputs) { "conflicting compiler enum decision witness token inputs" }
            } else {
                val inputs = SourceUseCanonical.freeze(witness.inputs)
                inputs.forEach { SourceUseCanonical.input(it, it.role) }
                require(inputs.filter { it.role == "options" && it.path == "compiler-enum-decisions" }.singleOrNull() == enabled) {
                    "compiler enum decision witness requires the enabled option"
                }
                onTokenHash?.invoke()
                require(SourceUseCanonical.hash(sequenceOf("kartograph-compiler-inputs-v1", witness.scope, witness.compiler, witness.artifact) +
                    inputs.asSequence().flatMap { SourceUseCanonical.fields(it).asSequence() }) == token) {
                    "compiler enum decision witness token mismatch"
                }
                binding.inputs = inputs
                for (input in inputs) if (input.role in setOf("compiler", "processor") && input.sha256 in binding.requestedArtifacts) {
                    binding.artifacts.add(input.sha256)
                }
            }
            for (receipt in witness.compilerEvidence) if (receipt in binding.requestedReceipts) binding.receipts.add(receipt)
        }
        for (unit in units) for (claim in unit.claims) {
            val binding = bindings.getValue(EnumDecisionWitnessKey(claim.scope, claim.compiler, claim.task, claim.outputs, unit.inputToken))
            require(binding.inputs != null && unit.receipt in binding.receipts && unit.collectorArtifactFingerprint in binding.artifacts) {
                "compiler enum decision claim does not match a completed witness"
            }
        }
    }


    override fun equals(other: Any?): Boolean = other is CompilerEnumDecisionEvidence && roots == other.roots && units == other.units && sites == other.sites
    override fun hashCode(): Int = listOf(roots, units, sites).hashCode()
}

private data class EnumDecisionWitnessKey(
    val scope: String, val compiler: String, val task: String, val outputs: List<InputFingerprint>, val token: String,
)

private class EnumDecisionWitnessBinding {
    val requestedReceipts = HashSet<InputFingerprint>()
    val requestedArtifacts = HashSet<String>()
    val receipts = HashSet<InputFingerprint>()
    val artifacts = HashSet<String>()
    var inputs: List<InputFingerprint>? = null
}


private fun enumDecisionOwner(id: NodeId) {
    SourceUseCanonical.text(id.value)
    require(EnumConstantIds.isOwner(id) && id.value.none { it.code < 32 }) { "enum decision requires an exact JVM class identity" }
}
