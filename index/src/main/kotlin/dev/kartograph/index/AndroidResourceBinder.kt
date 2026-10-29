package dev.kartograph.index

import dev.kartograph.core.*

/** 전체 scope를 거절하면 저장할 binding은 없고, 고정된 사유만 남는다. */
public sealed interface AndroidResourceBindingResult {
    public data class Captured(val evidence: AndroidResourceBindingEvidence) : AndroidResourceBindingResult
    public data class Unavailable(val reason: AndroidResourceBindingUnavailable) : AndroidResourceBindingResult
}

/** 원본 경로나 예외 문자열 대신 제한된 capability 사유를 기록한다. */
public enum class AndroidResourceBindingUnavailable {
    SCOPE_MISMATCH, INVENTORY_MISMATCH, PRODUCER_UNAVAILABLE, PRODUCER_AMBIGUOUS,
    OUTPUT_UNAVAILABLE, OWNERSHIP_UNAVAILABLE, GRAPH_MISMATCH,
}

/**
 * build adapter가 확보한 전체 main provider inventory와 같은 인덱싱 실행의 산출물 소유권을 대응한다.
 * 이 함수는 filesystem을 읽거나 현재 freshness를 인증하지 않는다. 호출자가 별도로 capture 검증을 완료해야 한다.
 */
public object AndroidResourceBinder {
    /** 전체 provider 목록과 선택된 산출물이 일치할 때 정의된 모든 키의 결과를 기록한다. */
    public fun bind(
        facts: AndroidResourceEvidence,
        mainRoots: List<InputFingerprint>,
        namespaceOptions: InputFingerprint,
        provenance: SnapshotProvenance,
        indexedRoots: List<InputFingerprint>,
        indexed: IndexedClasses,
        retainedGraph: CodeGraph,
        scope: String,
    ): AndroidResourceBindingResult {
        fun unavailable(reason: AndroidResourceBindingUnavailable) = AndroidResourceBindingResult.Unavailable(reason)
        if (scope != facts.scope || provenance.memberScopes.isNotEmpty()) return unavailable(AndroidResourceBindingUnavailable.SCOPE_MISMATCH)
        if (mainRoots.isEmpty() || mainRoots.any { it.role != "android-resources" } ||
            mainRoots.map { it.path }.distinct().size != mainRoots.size ||
            mainRoots.sortedBy { it.path } != facts.roots.map { it.input }.sortedBy { it.path }) {
            return unavailable(AndroidResourceBindingUnavailable.INVENTORY_MISMATCH)
        }
        if (namespaceOptions.role != "options" ||
            namespaceOptions.sha256 != AndroidResourceBindingIdentity.namespaceOptions(facts.namespace)) {
            return unavailable(AndroidResourceBindingUnavailable.PRODUCER_UNAVAILABLE)
        }
        val producers = provenance.witnesses.filter { witness ->
            witness.scope == scope && witness.compiler == "agp-process-resources" &&
                witness.inputs.filter { it.role == "android-resources" }.sortedBy { it.path } == mainRoots.sortedBy { it.path } &&
                witness.inputs.count { it == namespaceOptions } == 1
        }
        if (producers.isEmpty()) return unavailable(AndroidResourceBindingUnavailable.PRODUCER_UNAVAILABLE)
        if (producers.size != 1) return unavailable(AndroidResourceBindingUnavailable.PRODUCER_AMBIGUOUS)
        val producer = producers.single()
        if (producer.outputs.size != 1) return unavailable(AndroidResourceBindingUnavailable.OUTPUT_UNAVAILABLE)
        val output = producer.outputs.single()
        if (indexedRoots != provenance.inputs.filter { it.role == "classes" } ||
            indexedRoots.count { it == output } != 1 || indexedRoots.count { it.path == output.path } != 1) return unavailable(AndroidResourceBindingUnavailable.OUTPUT_UNAVAILABLE)
        if (indexed.declarationsByRoot.size != indexedRoots.size || indexed.selectedRootByNode.isEmpty()) {
            return unavailable(AndroidResourceBindingUnavailable.OWNERSHIP_UNAVAILABLE)
        }
        val outputIndex = indexedRoots.indexOf(output)
        val ownerId = NodeId("class:" + facts.namespace.replace('.', '/') + "/R\$layout")
        val indexedMembers = indexed.graph.outgoingEdgesFrom(ownerId).filter { it.kind == EdgeKind.MEMBER }.mapTo(hashSetOf()) { it.target }
        val retainedMembers = retainedGraph.outgoingEdgesFrom(ownerId).filter { it.kind == EdgeKind.MEMBER }.mapTo(hashSetOf()) { it.target }
        val outcomes = facts.definitions.map { it.key }.distinct().map { key ->
            val template = AndroidResourceFieldOutcome(key, unavailable = AndroidResourceFieldUnavailable.FIELD_MISSING)
            val owner = indexed.graph.nodes[template.owner]
            val field = indexed.graph.nodes[template.expectedField]
            val reason = when {
                owner == null || field == null -> AndroidResourceFieldUnavailable.FIELD_MISSING
                NodeAttribute.EXTERNAL_STUB in owner.attributes || NodeAttribute.EXTERNAL_STUB in field.attributes ->
                    AndroidResourceFieldUnavailable.EXTERNAL_DECLARATION
                indexed.selectedRootByNode[owner.id] != outputIndex || indexed.selectedRootByNode[field.id] != outputIndex ->
                    AndroidResourceFieldUnavailable.OUTPUT_NOT_SELECTED
                owner.id !in indexed.declarationsByRoot[outputIndex] || field.id !in indexed.declarationsByRoot[outputIndex] ||
                    field.id !in indexedMembers ->
                    AndroidResourceFieldUnavailable.OWNERSHIP_UNAVAILABLE
                owner.kind != NodeKind.CLASS || field.kind != NodeKind.FIELD || field.jvmSignature != template.expectedField.value.removePrefix("field:") ||
                    JvmModifier.STATIC !in field.jvmModifiers -> AndroidResourceFieldUnavailable.NOT_STATIC_INT
                JvmModifier.FINAL in field.jvmModifiers -> AndroidResourceFieldUnavailable.FINAL_FIELD
                else -> null
            }
            if (reason == null) AndroidResourceFieldOutcome(key, field = template.expectedField)
            else AndroidResourceFieldOutcome(key, unavailable = reason)
        }
        for (outcome in outcomes.filter { it.field != null }) {
            if (retainedGraph.nodes[outcome.owner]?.kind != indexed.graph.nodes[outcome.owner]?.kind ||
                retainedGraph.nodes[outcome.owner]?.attributes?.contains(NodeAttribute.EXTERNAL_STUB) == true ||
                outcome.field !in retainedMembers ||
                retainedGraph.nodes[requireNotNull(outcome.field)]?.let { retained ->
                    val original = requireNotNull(indexed.graph.nodes[outcome.field])
                    retained.kind == original.kind && retained.jvmSignature == original.jvmSignature &&
                        retained.jvmModifiers == original.jvmModifiers && NodeAttribute.EXTERNAL_STUB !in retained.attributes
                } != true) {
                return unavailable(AndroidResourceBindingUnavailable.GRAPH_MISMATCH)
            }
        }
        val evidence = AndroidResourceBindingEvidence(scope, facts.namespace, AndroidResourceBindingIdentity.facts(facts),
            producer.artifact, AndroidResourceBindingIdentity.producer(producer), namespaceOptions, output, mainRoots, outcomes)
        evidence.requireMatches(facts, provenance, retainedGraph, scope)
        return AndroidResourceBindingResult.Captured(evidence)
    }
}
