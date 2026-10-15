package dev.kartograph.core

import java.security.MessageDigest
import java.util.Collections

/** 실행 간선을 뜻하지 않는 compiler의 원본 selector 선택 종류다. */
public enum class CompilerSourceUseKind { INVOCATION_SELECTION }

/** 처음 선택한 class root와 그 root가 소유한 callable 목록을 view에서도 그대로 보존한다. */
public class CompilerSourceUseRoot(
    public val ordinal: Int,
    public val input: InputFingerprint,
    capturedCallableOwners: Iterable<NodeId>,
) {
    public val capturedCallableOwners: List<NodeId> = SourceUseCanonical.list(capturedCallableOwners, 1_000_000)
        .also { values ->
            values.forEach(SourceUseCanonical::method)
            require(values.toSet().size == values.size) { "duplicate compiler source use owner" }
        }.sortedWith { left, right -> SourceUseCanonical.compareText(left.value, right.value) }
        .let(SourceUseCanonical::freeze)
    private val owners: Set<NodeId> = this.capturedCallableOwners.toSet()
    public val id: String

    init {
        require(ordinal >= 0) { "invalid compiler source use root ordinal" }
        SourceUseCanonical.input(input, "classes")
        id = SourceUseCanonical.hash(sequenceOf("kartograph-compiler-source-use-root-v1", ordinal.toString()) +
            SourceUseCanonical.fields(input).asSequence() + sequenceOf(this.capturedCallableOwners.size.toString()) +
            this.capturedCallableOwners.asSequence().map { it.value })
    }

    internal fun owns(id: NodeId): Boolean = id in owners
    override fun equals(other: Any?): Boolean = other is CompilerSourceUseRoot &&
        ordinal == other.ordinal && input == other.input && capturedCallableOwners == other.capturedCallableOwners
    override fun hashCode(): Int = listOf(ordinal, input, capturedCallableOwners).hashCode()
}

/** 한 완료 witness의 순서 있는 출력 주장이다. task는 BuildWitness.artifact와 대응한다. */
public class CompilerSourceUseClaim(
    public val scope: String,
    public val compiler: String,
    public val task: String,
    outputs: Iterable<InputFingerprint>,
) {
    public val outputs: List<InputFingerprint> = SourceUseCanonical.list(outputs, 16_384)
    internal val identityFields: List<String>

    init {
        listOf(scope, compiler, task).forEach { value ->
            SourceUseCanonical.text(value)
            require(value.length in 1..200 && Regex("[A-Za-z0-9_.:-]+").matches(value)) { "invalid compiler source use claim" }
        }
        require(compiler in setOf("kotlin", "javac") && this.outputs.isNotEmpty()) { "invalid compiler source use claim" }
        this.outputs.forEach { SourceUseCanonical.input(it, "classes") }
        identityFields = SourceUseCanonical.freeze(listOf(scope, compiler, task, this.outputs.size.toString()) +
            this.outputs.flatMap(SourceUseCanonical::fields))
    }

    override fun equals(other: Any?): Boolean = other is CompilerSourceUseClaim &&
        scope == other.scope && compiler == other.compiler && task == other.task && outputs == other.outputs
    override fun hashCode(): Int = identityFields.hashCode()
}

/** producer의 원래 관찰 분류이며 emitted 행의 importer 분류와 섞지 않는다. */
public data class CompilerSourceUseProducerCounts(val observed: Int, val emitted: Int, val unmapped: Int, val ambiguous: Int) {
    init {
        require(listOf(observed, emitted, unmapped, ambiguous).all { it >= 0 } &&
            observed.toLong() == emitted.toLong() + unmapped.toLong() + ambiguous.toLong()) { "invalid compiler source use producer counts" }
    }
}

/** 각 emitted 행을 정확히 한 번 분류한 capture 시점의 계수다. */
public data class CompilerSourceUseImportCounts(
    val admitted: Int,
    val outsideGraph: Int,
    val shadowedCaller: Int,
    val shadowedTarget: Int,
    val outsideClaimedOutputs: Int,
    val sourceAssociationUnverified: Int,
) {
    init {
        require(values().all { it >= 0 }) { "invalid compiler source use import counts" }
    }
    internal fun values(): List<Int> = listOf(admitted, outsideGraph, shadowedCaller, shadowedTarget,
        outsideClaimedOutputs, sourceAssociationUnverified)
}

/** 원본 receipt 한 개의 불변 관찰이다. id는 graph 선택에 따른 import/view 계수를 포함하지 않는다. */
public class CompilerSourceUseUnit(
    public val receipt: InputFingerprint,
    public val collector: String,
    public val compilerVersion: String,
    public val collectorArtifactFingerprint: String,
    public val inputToken: String,
    claims: Iterable<CompilerSourceUseClaim>,
    public val producer: CompilerSourceUseProducerCounts,
    public val importCounts: CompilerSourceUseImportCounts,
    public val viewOmitted: Int,
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
        require(this.claims.isNotEmpty() && this.claims.toSet().size == this.claims.size) { "missing or duplicate compiler source use claim" }
        require(this.claims.sumOf { it.outputs.size.toLong() } <= 16_384) { "compiler source use output limit exceeded" }
        require(this.claims.all { it.compiler == if (collector == "kotlin-constants") "kotlin" else "javac" }) {
            "compiler source use claim does not match collector"
        }
        require(viewOmitted >= 0 && viewOmitted <= importCounts.admitted &&
            producer.emitted.toLong() == importCounts.values().sumOf { it.toLong() }) { "invalid compiler source use import partition" }
        id = SourceUseCanonical.hash(sequenceOf("kartograph-compiler-source-use-unit-v1") +
            SourceUseCanonical.fields(receipt).asSequence() +
            sequenceOf(collector, compilerVersion, collectorArtifactFingerprint, inputToken, this.claims.size.toString()) +
            this.claims.asSequence().flatMap { it.identityFields.asSequence() } +
            sequenceOf(producer.observed.toString(), producer.emitted.toString(), producer.unmapped.toString(), producer.ambiguous.toString()))
    }

    internal fun withViewOmitted(value: Int): CompilerSourceUseUnit = if (value == viewOmitted) this else
        CompilerSourceUseUnit(receipt, collector, compilerVersion, collectorArtifactFingerprint, inputToken,
            claims, producer, importCounts, value)

    override fun equals(other: Any?): Boolean = other is CompilerSourceUseUnit && receipt == other.receipt &&
        collector == other.collector && compilerVersion == other.compilerVersion &&
        collectorArtifactFingerprint == other.collectorArtifactFingerprint && inputToken == other.inputToken &&
        claims == other.claims && producer == other.producer && importCounts == other.importCounts && viewOmitted == other.viewOmitted
    override fun hashCode(): Int = listOf(receipt, collector, compilerVersion, collectorArtifactFingerprint,
        inputToken, claims, producer, importCounts, viewOmitted).hashCode()
}

/** 원본 selector와 선택된 두 JVM method의 관계다. BYTECODE CALL의 존재나 inline 원인을 주장하지 않는다. */
public data class CompilerSourceUse(
    val unitId: String,
    val source: NodeId,
    val target: NodeId,
    val sourceRootId: String,
    val targetRootId: String,
    val kind: CompilerSourceUseKind,
    val file: CompilerEvidenceSource,
    val collector: String,
    val compilerVersion: String,
    val coordinateBasis: CompilerSourceCoordinateBasis,
    val offsetUtf16: Int,
    val endOffsetUtf16: Int,
    val line: Int,
    val column: Int,
    val generated: Boolean,
) {
    public val id: String
    init {
        listOf(unitId, sourceRootId, targetRootId).forEach(SourceUseCanonical::hashText)
        SourceUseCanonical.method(source)
        SourceUseCanonical.method(target)
        SourceUseCanonical.input(InputFingerprint("sourceFile", file.path, file.sha256), "sourceFile")
        SourceUseCanonical.compiler(collector, compilerVersion)
        require(collector == "kotlin-constants" && coordinateBasis == CompilerSourceCoordinateBasis.KOTLIN_UTF16_NORMALIZED_SOURCE ||
            collector == "javac-constants" && coordinateBasis == CompilerSourceCoordinateBasis.JAVAC_UTF16_CHAR_SEQUENCE) {
            "compiler source use coordinate basis does not match collector"
        }
        require(offsetUtf16 >= 0 && endOffsetUtf16 > offsetUtf16 && line > 0 && column > 0) { "invalid compiler source use coordinates" }
        id = SourceUseCanonical.hash(sequenceOf("kartograph-compiler-source-use-v1", unitId, source.value, target.value,
            sourceRootId, targetRootId, "invocationSelection", file.path, file.sha256, collector, compilerVersion,
            if (coordinateBasis == CompilerSourceCoordinateBasis.KOTLIN_UTF16_NORMALIZED_SOURCE) "kotlinUtf16NormalizedSource" else "javacUtf16CharSequence",
            offsetUtf16.toString(), endOffsetUtf16.toString(), line.toString(), column.toString(), generated.toString()))
    }
}

/** capture 결과와 단조롭게 좁힌 view다. null 채널과 실제 zero-emission 관찰은 구분한다. */
public class CompilerSourceUseEvidence(
    roots: Iterable<CompilerSourceUseRoot>,
    units: Iterable<CompilerSourceUseUnit>,
    uses: Iterable<CompilerSourceUse>,
) {
    public val roots: List<CompilerSourceUseRoot> = SourceUseCanonical.list(roots, 4_096).sortedBy { it.ordinal }.let(SourceUseCanonical::freeze)
    public val units: List<CompilerSourceUseUnit> = SourceUseCanonical.list(units, 256).sortedBy { it.id }.let(SourceUseCanonical::freeze)
    public val uses: List<CompilerSourceUse> = SourceUseCanonical.list(uses, 200_000).sortedWith { left, right ->
        var result = SourceUseCanonical.compareText(left.unitId, right.unitId)
        if (result == 0) result = SourceUseCanonical.compareText(left.source.value, right.source.value)
        if (result == 0) result = SourceUseCanonical.compareText(left.target.value, right.target.value)
        if (result == 0) result = SourceUseCanonical.compareText(left.file.path, right.file.path)
        if (result == 0) result = left.offsetUtf16.compareTo(right.offsetUtf16)
        if (result == 0) result = left.id.compareTo(right.id)
        result
    }.let(SourceUseCanonical::freeze)
    /** callable이 없는 root도 목록에는 남으며 해당 관찰 coverage 분모에서만 제외한다. */
    public val uncoveredRoots: List<CompilerSourceUseRoot>

    init {
        require(this.roots.isNotEmpty() && this.units.isNotEmpty()) { "compiler source uses require selected roots and a validated v3 unit" }
        require(this.roots.withIndex().all { (index, root) -> root.ordinal == index } &&
            this.roots.map { it.id }.toSet().size == this.roots.size) { "invalid compiler source use root order" }
        require(this.roots.sumOf { it.capturedCallableOwners.size.toLong() } <= 1_000_000) { "compiler source use owner limit exceeded" }
        require(this.units.sumOf { it.claims.size.toLong() } <= 4_096 &&
            this.units.sumOf { unit -> unit.claims.sumOf { it.outputs.size.toLong() } } <= 16_384) { "compiler source use claim limit exceeded" }
        require(this.units.map { it.id }.toSet().size == this.units.size) { "duplicate compiler source use unit" }
        require(this.uses.map { it.id }.toSet().size == this.uses.size) { "duplicate compiler source use" }
        val owners = HashSet<NodeId>()
        this.roots.forEach { root -> root.capturedCallableOwners.forEach { require(owners.add(it)) { "compiler source use owner belongs to multiple roots" } } }
        val byRoot = this.roots.associateBy { it.id }
        val byUnit = this.units.associateBy { it.id }
        val outputsByUnit = this.units.associate { unit -> unit.id to unit.claims.flatMap { it.outputs }.toSet() }
        val selectedInputs = this.roots.map { it.input }.toSet()
        require(outputsByUnit.values.all { outputs -> outputs.any(selectedInputs::contains) }) { "compiler source use unit has no selected output" }
        val selectors = HashSet<List<Any>>()
        val assertions = HashMap<List<Any>, List<Any>>()
        val retained = HashMap<String, Int>()
        this.uses.forEach { use ->
            val unit = requireNotNull(byUnit[use.unitId]) { "compiler source use unit is absent" }
            val sourceRoot = requireNotNull(byRoot[use.sourceRootId]) { "compiler source use source root is absent" }
            val targetRoot = requireNotNull(byRoot[use.targetRootId]) { "compiler source use target root is absent" }
            require(sourceRoot.owns(use.source) && targetRoot.owns(use.target)) { "compiler source use ownership does not match root" }
            val outputs = outputsByUnit.getValue(unit.id)
            require(sourceRoot.input in outputs && targetRoot.input in outputs) { "compiler source use root is outside claimed outputs" }
            require(use.collector == unit.collector && use.compilerVersion == unit.compilerVersion) { "compiler source use unit identity mismatch" }
            require(selectors.add(listOf(use.unitId, use.source, use.file.path, use.offsetUtf16))) { "duplicate or conflicting compiler source use selector" }
            val key = listOf(use.sourceRootId, use.source, use.file.path, use.file.sha256, use.coordinateBasis, use.offsetUtf16)
            val value = listOf(use.target, use.targetRootId, use.endOffsetUtf16, use.line, use.column)
            val previous = assertions.putIfAbsent(key, value)
            require(previous == null || previous == value) { "conflicting compiler source use assertions across units" }
            retained[unit.id] = (retained[unit.id] ?: 0) + 1
        }
        this.units.forEach { unit ->
            require(unit.importCounts.admitted.toLong() == (retained[unit.id] ?: 0).toLong() + unit.viewOmitted.toLong()) {
                "compiler source use retained count does not match capture"
            }
        }
        val covered = outputsByUnit.values.flatten().toSet()
        uncoveredRoots = SourceUseCanonical.freeze(this.roots.filter { it.capturedCallableOwners.isNotEmpty() && it.input !in covered })
    }

    /** 현재 정점만 검사한다. root의 historical 소유 목록에 있던 정점을 다시 만들지 않는다. */
    public fun requireMatchesGraph(graph: CodeGraph) {
        uses.forEach { use ->
            require(listOf(use.source, use.target).all { graph.node(it)?.kind in setOf(NodeKind.METHOD, NodeKind.FUNCTION, NodeKind.CONSTRUCTOR) }) {
                "compiler source use endpoint is absent from graph or is not callable"
            }
        }
    }

    /** 저장된 provenance의 일관성만 확인한다. 파일 currentness나 producer 인증을 재증명하지 않는다. */
    public fun requireMatchesProvenance(provenance: SnapshotProvenance, scope: String) {
        requireMatchesProvenance(provenance, scope, null)
    }

    /** 테스트의 작업 계수는 해시 발생만 관찰하며 입력/검증 결과를 바꾸지 않는다. */
    internal fun requireMatchesProvenance(provenance: SnapshotProvenance, scope: String, onTokenHash: (() -> Unit)?) {
        require(provenance.memberScopes.isEmpty()) { "compiler source use aggregation is not supported" }
        require(roots.map { it.input } == provenance.inputs.filter { it.role == "classes" }) { "compiler source use roots do not match selected provenance" }
        val bindings = HashMap<SourceUseWitnessKey, SourceUseWitnessBinding>()
        for (unit in units) for (claim in unit.claims) {
            require(claim.scope == scope) { "compiler source use scope mismatch" }
            val key = SourceUseWitnessKey(claim.scope, claim.compiler, claim.task, claim.outputs, unit.inputToken)
            val binding = bindings.getOrPut(key) { SourceUseWitnessBinding() }
            binding.requestedReceipts.add(unit.receipt)
            binding.requestedArtifacts.add(unit.collectorArtifactFingerprint)
        }
        val enabled = InputFingerprint("options", "compiler-call-positions", SourceUseCanonical.hash(sequenceOf("callPositions=true")))
        // claim × witness × inputs를 순회하지 않는다. 필요한 binding만 보관하고 witness는 한 번 방문한다.
        for (witness in provenance.witnesses) {
            val token = witness.evidenceToken ?: continue
            val key = SourceUseWitnessKey(witness.scope, witness.compiler, witness.artifact, witness.outputs, token)
            val binding = bindings[key] ?: continue
            val previousInputs = binding.inputs
            if (previousInputs != null) {
                require(previousInputs == witness.inputs) { "conflicting compiler source use witness token inputs" }
            } else {
                val inputs = SourceUseCanonical.freeze(witness.inputs)
                inputs.forEach { SourceUseCanonical.input(it, it.role) }
                require(inputs.filter { it.role == "options" && it.path == "compiler-call-positions" }.singleOrNull() == enabled) {
                    "compiler source use witness requires the enabled option"
                }
                onTokenHash?.invoke()
                require(SourceUseCanonical.hash(sequenceOf("kartograph-compiler-inputs-v1", witness.scope, witness.compiler, witness.artifact) +
                    inputs.asSequence().flatMap { SourceUseCanonical.fields(it).asSequence() }) == token) {
                    "compiler source use witness token mismatch"
                }
                binding.inputs = inputs
                for (input in inputs) if (input.role in setOf("compiler", "processor") && input.sha256 in binding.requestedArtifacts) {
                    binding.artifacts.add(input.sha256)
                }
            }
            for (receipt in witness.compilerEvidence) if (receipt in binding.requestedReceipts) binding.receipts.add(receipt)
        }
        for (unit in units) for (claim in unit.claims) {
            val binding = bindings.getValue(SourceUseWitnessKey(claim.scope, claim.compiler, claim.task, claim.outputs, unit.inputToken))
            require(binding.inputs != null && unit.receipt in binding.receipts && unit.collectorArtifactFingerprint in binding.artifacts) {
                "compiler source use claim does not match a completed witness"
            }
        }
    }

    /** 제거한 행은 재생성하지 않으며 admitted에서 현재 retained 수를 빼 view 계수를 다시 계산한다. */
    public fun select(nodeIds: Set<NodeId>): CompilerSourceUseEvidence {
        val selected = uses.filter { it.source in nodeIds && it.target in nodeIds }
        if (selected.size == uses.size) return this
        val retained = selected.groupingBy { it.unitId }.eachCount()
        return CompilerSourceUseEvidence(roots, units.map { it.withViewOmitted(it.importCounts.admitted - (retained[it.id] ?: 0)) }, selected)
    }

    override fun equals(other: Any?): Boolean = other is CompilerSourceUseEvidence && roots == other.roots && units == other.units && uses == other.uses
    override fun hashCode(): Int = listOf(roots, units, uses).hashCode()
}

private data class SourceUseWitnessKey(
    val scope: String, val compiler: String, val task: String, val outputs: List<InputFingerprint>, val token: String,
)

private class SourceUseWitnessBinding {
    val requestedReceipts = HashSet<InputFingerprint>()
    val requestedArtifacts = HashSet<String>()
    val receipts = HashSet<InputFingerprint>()
    val artifacts = HashSet<String>()
    var inputs: List<InputFingerprint>? = null
}

/** 모든 새 문자열/수집 경계에서 같은 순수 canonical encoding을 사용한다. */
internal object SourceUseCanonical {
    fun text(value: String) {
        require(value.length <= 16_384) { "compiler source use string limit exceeded" }
        var index = 0
        while (index < value.length) {
            val char = value[index++]
            if (char.isHighSurrogate()) {
                require(index < value.length && value[index].isLowSurrogate()) { "invalid compiler source use Unicode" }
                index++
            } else require(!char.isLowSurrogate()) { "invalid compiler source use Unicode" }
        }
        require(value.toByteArray(Charsets.UTF_8).size <= 16_384) { "compiler source use string limit exceeded" }
    }
    fun hashText(value: String) {
        require(Regex("[0-9a-f]{64}").matches(value)) { "invalid compiler source use hash" }
    }
    fun input(value: InputFingerprint, role: String) {
        listOf(value.role, value.path, value.sha256).forEach(::text)
        require(value.role == role && value.path.split('/').none { it == "." } && value.path.none { it.code < 32 }) {
            "invalid compiler source use input identity"
        }
    }
    fun method(value: NodeId) {
        text(value.value)
        require(value.value.startsWith("method:") && value.value.substringAfter("method:").let { '#' in it && '(' in it && ')' in it } &&
            value.value.none { it.code < 32 }) { "compiler source use requires an exact JVM method identity" }
    }
    fun compiler(collector: String, version: String) {
        text(collector); text(version)
        require(collector in setOf("javac-constants", "kotlin-constants") && version.length in 1..128 &&
            Regex("[A-Za-z0-9._+\\-]+").matches(version)) { "invalid compiler source use compiler identity" }
    }
    fun fields(input: InputFingerprint): List<String> = listOf(input.role, input.path, input.sha256)
    fun <T> freeze(values: List<T>): List<T> = Collections.unmodifiableList(ArrayList(values))
    fun <T> list(values: Iterable<T>, maximum: Int): List<T> {
        if (values is Collection<*>) require(values.size <= maximum) { "compiler source use collection limit exceeded" }
        val result = ArrayList<T>()
        for (value in values) {
            require(result.size < maximum) { "compiler source use collection limit exceeded" }
            result.add(value)
        }
        return Collections.unmodifiableList(result)
    }
    // 올바른 scalar sequence의 UTF-8 순서는 code point 순서와 같다.
    fun compareText(left: String, right: String): Int {
        var a = 0; var b = 0
        while (a < left.length && b < right.length) {
            val x = left.codePointAt(a); val y = right.codePointAt(b)
            if (x != y) return x.compareTo(y)
            a += Character.charCount(x); b += Character.charCount(y)
        }
        return (left.length - a).compareTo(right.length - b)
    }
    fun compareEncoding(left: List<String>, right: List<String>): Int {
        for (index in 0 until minOf(left.size, right.size)) {
            val size = left[index].toByteArray(Charsets.UTF_8).size.compareTo(right[index].toByteArray(Charsets.UTF_8).size)
            if (size != 0) return size
            val text = compareText(left[index], right[index])
            if (text != 0) return text
        }
        return left.size.compareTo(right.size)
    }
    fun hash(values: Sequence<String>): String {
        val digest = MessageDigest.getInstance("SHA-256")
        values.forEach { value ->
            text(value)
            val bytes = value.toByteArray(Charsets.UTF_8)
            val size = bytes.size
            digest.update(byteArrayOf((size ushr 24).toByte(), (size ushr 16).toByte(), (size ushr 8).toByte(), size.toByte()))
            digest.update(bytes)
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }
}
