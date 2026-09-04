package dev.kartograph.analysis

import dev.kartograph.core.ClassHierarchy
import dev.kartograph.core.CodeGraph
import dev.kartograph.core.JvmModifier
import dev.kartograph.core.RetentionEvidence
import dev.kartograph.core.RetentionReason

/** Annotation 밖의 JVM 사실으로 식별되는 Android runtime 진입점을 보존한다. */
public object AndroidEntryPointRetention {
    /** JNI native member와 WorkManager worker subclass에 대한 근거를 반환한다. */
    public fun find(
        graph: CodeGraph,
        classHierarchy: ClassHierarchy = ClassHierarchy.EMPTY,
    ): List<RetentionEvidence> = buildList {
        graph.nodes.values.filter { node -> JvmModifier.NATIVE in node.jvmModifiers }.forEach { node ->
            add(RetentionEvidence(node.id, RetentionReason.RUNTIME_ENTRY_POINT, node.location))
            graph.incomingEdgesTo(node.id)
                .filter { edge -> edge.kind == dev.kartograph.core.EdgeKind.MEMBER }
                .forEach { edge ->
                    add(RetentionEvidence(edge.source, RetentionReason.RUNTIME_ENTRY_POINT, node.location))
                }
        }
        val projectSupertypes = graph.nodes.values.mapNotNull { node ->
            node.jvmSignature?.takeIf { signature -> '#' !in signature }?.let { internalName ->
                internalName to node.supertypes
            }
        }.toMap()
        graph.nodes.values.filter { node -> node.isWorkerSubclass(projectSupertypes, classHierarchy) }
            .forEach { node -> add(RetentionEvidence(node.id, RetentionReason.RUNTIME_ENTRY_POINT, node.location)) }
    }.distinct().sortedBy(RetentionEvidence::nodeId)

    private fun dev.kartograph.core.GraphNode.isWorkerSubclass(
        projectSupertypes: Map<String, Set<String>>,
        classHierarchy: ClassHierarchy,
    ): Boolean {
        val pending = ArrayDeque(supertypes)
        val visited = mutableSetOf<String>()
        while (pending.isNotEmpty()) {
            val type = pending.removeFirst()
            if (!visited.add(type)) continue
            if (type in WORKER_TYPES) return true
            pending.addAll(projectSupertypes[type].orEmpty())
            pending.addAll(classHierarchy.directSupertypesOf(type).orEmpty())
        }
        return false
    }

    private val WORKER_TYPES = setOf(
        "androidx/work/ListenableWorker",
        "androidx/work/Worker",
        "androidx/work/CoroutineWorker",
        "androidx/work/RxWorker",
    )
}
