package dev.kartograph.core

/** 같은 layout 키라도 보유 필드와 선택된 산출물 근거가 없으면 연결하지 않는다. */
public enum class AndroidResourceFieldUnavailable {
    FIELD_MISSING, NOT_STATIC_INT, FINAL_FIELD, EXTERNAL_DECLARATION,
    OUTPUT_NOT_SELECTED, OWNERSHIP_UNAVAILABLE,
}

/** 정의된 키 하나의 결과다. bound는 저장 당시 정확한 R 필드 소유권 관측이며 현재 신선도 판정이 아니다. */
public data class AndroidResourceFieldOutcome(
    val key: AndroidResourceKey,
    val field: NodeId? = null,
    val unavailable: AndroidResourceFieldUnavailable? = null,
) {
    public val owner: NodeId get() = NodeId("class:" + key.namespace.replace('.', '/') + "/R\$layout")
    public val expectedField: NodeId get() = NodeId("field:" + key.namespace.replace('.', '/') + "/R\$layout#" + key.name + ":I")
    init {
        require((field != null) != (unavailable != null)) { "resource key requires exactly one binding outcome" }
        require(field == null || field == expectedField) { "resource binding has a different JVM field identity" }
    }
}

/**
 * 전체 main resource inventory와 생산자·선택 산출물에 대응한 저장 당시 소유권 관측이다.
 * VERIFIED 상태나 현재 filesystem 신선도를 직렬화하지 않는다. 일반 legacy graph는 이 채널을 만들 수 없다.
 */
public class AndroidResourceBindingEvidence(
    public val scope: String,
    public val namespace: String,
    public val resourceFactsSha256: String,
    public val producerArtifact: String,
    public val producerSha256: String,
    public val namespaceOptions: InputFingerprint,
    public val output: InputFingerprint,
    mainRoots: Iterable<InputFingerprint>,
    outcomes: Iterable<AndroidResourceFieldOutcome>,
) {
    public val mainRoots: List<InputFingerprint> = SourceUseCanonical.list(mainRoots, AndroidResourceEvidence.MAX_ROOTS)
        .sortedBy { it.path }.let(SourceUseCanonical::freeze)
    public val outcomes: List<AndroidResourceFieldOutcome> = SourceUseCanonical.list(outcomes, AndroidResourceEvidence.MAX_DEFINITIONS)
        .sortedBy { it.key.name }.let(SourceUseCanonical::freeze)

    init {
        require(scope.length in 1..200 && Regex("[A-Za-z0-9_.:-]+").matches(scope)) { "invalid resource binding scope" }
        require(producerArtifact.length in 1..200 && Regex("[A-Za-z0-9_.:-]+").matches(producerArtifact)) { "invalid resource producer identity" }
        AndroidResourceKey(namespace, "layout", "validation")
        SourceUseCanonical.hashText(resourceFactsSha256)
        SourceUseCanonical.hashText(producerSha256)
        SourceUseCanonical.input(namespaceOptions, "options")
        SourceUseCanonical.input(output, "classes")
        require(namespaceOptions.sha256 == AndroidResourceBindingIdentity.namespaceOptions(namespace)) { "resource namespace option mismatch" }
        this.mainRoots.forEach { SourceUseCanonical.input(it, "android-resources") }
        require((this.mainRoots + listOf(namespaceOptions, output)).all { it.path.length <= 4_096 }) { "resource binding path exceeds limit" }
        require(this.mainRoots.isNotEmpty() && this.mainRoots.map { it.path }.distinct().size == this.mainRoots.size) { "duplicate main resource root" }
        var textBytes = 0L
        (this.mainRoots.flatMap(SourceUseCanonical::fields) + SourceUseCanonical.fields(namespaceOptions) +
            SourceUseCanonical.fields(output) + this.outcomes.flatMap { it.key.fields + listOfNotNull(it.field?.value, it.unavailable?.name) }).forEach {
            SourceUseCanonical.text(it)
            textBytes += it.toByteArray(Charsets.UTF_8).size
            require(textBytes <= AndroidResourceEvidence.MAX_TEXT_BYTES) { "resource bindings exceed aggregate text limit" }
        }
        require(this.outcomes.all { it.key.namespace == namespace } &&
            this.outcomes.map { it.key }.distinct().size == this.outcomes.size) { "duplicate or foreign resource binding key" }
    }

    override fun equals(other: Any?): Boolean = other is AndroidResourceBindingEvidence &&
        scope == other.scope && namespace == other.namespace && resourceFactsSha256 == other.resourceFactsSha256 &&
        producerArtifact == other.producerArtifact && producerSha256 == other.producerSha256 &&
        namespaceOptions == other.namespaceOptions && output == other.output && mainRoots == other.mainRoots && outcomes == other.outcomes

    override fun hashCode(): Int = listOf(scope, namespace, resourceFactsSha256, producerArtifact,
        producerSha256, namespaceOptions, output, mainRoots, outcomes).hashCode()

    /** 별도 문맥의 사실을 섞거나 그래프에서 사라진 소유권을 저장하지 않는다. 현재 파일은 읽지 않는다. */
    public fun requireMatches(facts: AndroidResourceEvidence, provenance: SnapshotProvenance, graph: CodeGraph, snapshotScope: String) {
        require(scope == snapshotScope && scope == facts.scope && namespace == facts.namespace &&
            provenance.memberScopes.isEmpty()) { "resource binding scope mismatch" }
        require(resourceFactsSha256 == AndroidResourceBindingIdentity.facts(facts)) { "resource binding facts changed" }
        require(mainRoots == facts.roots.map { it.input }.sortedBy { it.path }) { "resource main-root inventory mismatch" }
        require(outcomes.map { it.key }.toSet() == facts.definitions.map { it.key }.toSet()) { "resource binding key inventory is incomplete" }
        val producers = provenance.witnesses.filter {
            it.scope == scope && it.compiler == "agp-process-resources" &&
                it.inputs.filter { input -> input.role == "android-resources" }.sortedBy { input -> input.path } == mainRoots &&
                it.inputs.count { input -> input == namespaceOptions } == 1
        }
        require(producers.size == 1) { "resource producer identity is absent or ambiguous" }
        val producer = producers.single()
        require(producer.artifact == producerArtifact && AndroidResourceBindingIdentity.producer(producer) == producerSha256 &&
            producer.outputs == listOf(output) &&
            provenance.inputs.count { it.role == "classes" && it.path == output.path } == 1 &&
            output in provenance.inputs) { "resource producer input/output mismatch" }
        val ownerId = NodeId("class:" + namespace.replace('.', '/') + "/R\$layout")
        val members = graph.outgoingEdgesFrom(ownerId).filter { it.kind == EdgeKind.MEMBER }.mapTo(hashSetOf()) { it.target }
        outcomes.filter { it.field != null }.forEach { outcome ->
            val owner = requireNotNull(graph.nodes[outcome.owner]) { "resource owner is absent from graph" }
            val field = requireNotNull(graph.nodes[requireNotNull(outcome.field)]) { "resource field is absent from graph" }
            require(owner.kind == NodeKind.CLASS && field.kind == NodeKind.FIELD &&
                NodeAttribute.EXTERNAL_STUB !in owner.attributes && NodeAttribute.EXTERNAL_STUB !in field.attributes &&
                JvmModifier.STATIC in field.jvmModifiers && JvmModifier.FINAL !in field.jvmModifiers &&
                field.jvmSignature == outcome.expectedField.value.removePrefix("field:") &&
                field.id in members) { "resource field flags or identity mismatch" }
        }
    }
}

/** 전체 payload를 길이 구분해 해시한다. 로컬 row ID만으로 namespace·scope·include 의미를 대체하지 않는다. */
public object AndroidResourceBindingIdentity {
    /** include target과 qualifier를 포함한 모든 사실 필드를 고정한다. */
    public fun facts(value: AndroidResourceEvidence): String = SourceUseCanonical.hash(sequence {
        yield("kartograph-android-resource-facts-v1")
        yield(value.scope); yield(value.namespace); yield(value.admission)
        yield(value.roots.size.toString())
        value.roots.forEach { root -> yield(root.id); yieldAll(SourceUseCanonical.fields(root.input)) }
        yield(value.definitions.size.toString())
        value.definitions.forEach { definition ->
            yield(definition.id); yieldAll(definition.key.fields); yield(definition.rootId)
            yield(definition.file.path); yield(definition.file.sha256); yield(definition.relativePath); yield(definition.qualifier)
        }
        yield(value.includes.size.toString())
        value.includes.forEach { include ->
            yield(include.id); yield(include.sourceDefinitionId); yield(include.ordinal.toString()); yield(include.kind.name)
            yield(if (include.target == null) "absent" else "present")
            include.target?.let { yieldAll(it.fields) }
        }
    })

    /** 생산자의 순서 있는 입력·출력과 compiler receipt를 고정한다. */
    public fun producer(value: BuildWitness): String = SourceUseCanonical.hash(sequence {
        yield("kartograph-android-resource-producer-v1")
        yield(value.scope); yield(value.compiler); yield(value.artifact)
        for (rows in listOf(value.inputs, value.outputs, value.compilerEvidence)) {
            yield(rows.size.toString())
            rows.forEach { yieldAll(SourceUseCanonical.fields(it)) }
        }
        yield(if (value.evidenceToken == null) "absent" else "present")
        value.evidenceToken?.let { yield(it) }
    })

    /** AGP 생산자가 사용한 namespace 옵션 지문과 같은 길이 구분 방식을 쓴다. */
    public fun namespaceOptions(namespace: String): String =
        SourceUseCanonical.hash(sequenceOf("namespace=$namespace"))
}
