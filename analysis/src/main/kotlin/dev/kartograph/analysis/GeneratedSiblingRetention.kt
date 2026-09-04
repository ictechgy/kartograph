package dev.kartograph.analysis

import dev.kartograph.core.CodeGraph
import dev.kartograph.core.GeneratedSiblingNaming
import dev.kartograph.core.GraphNode
import dev.kartograph.core.RetentionEvidence
import dev.kartograph.core.RetentionReason

/** Framework code generators가 원본 이름에서 파생해 runtime에 제공하는 sibling class를 보존한다. */
public object GeneratedSiblingRetention {
    /** 지원하는 원본 annotation과 정확한 생성 이름이 함께 존재할 때만 생성 class 근거를 반환한다. */
    public fun find(graph: CodeGraph): List<RetentionEvidence> {
        val nodesByInternalName = graph.nodes.values.mapNotNull { node ->
            node.internalName?.let { internalName -> internalName to node }
        }.toMap()
        return graph.nodes.values
            .flatMap { source -> source.generatedSiblingNames().map { name -> source to name } }
            .mapNotNull { (source, siblingName) ->
                nodesByInternalName[siblingName]
                    ?.let { sibling -> RetentionEvidence(sibling.id, RetentionReason.GENERATED_CODE, source.location) }
            }
            .distinct()
            .sortedBy(RetentionEvidence::nodeId)
    }

    private fun GraphNode.generatedSiblingNames(): List<String> {
        val internalName = internalName ?: return emptyList()
        return GeneratedSiblingNaming.candidatesFor(internalName, annotations).toList()
    }

    private val GraphNode.internalName: String?
        get() = jvmSignature?.takeIf { signature -> '#' !in signature }
}
