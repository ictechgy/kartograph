package dev.kartograph.index

import dev.kartograph.core.EdgeKind
import dev.kartograph.core.EdgeOrigin
import dev.kartograph.core.GraphEdge
import dev.kartograph.core.NodeId
import org.objectweb.asm.Type

/**
 * property 대상 어노테이션을 그 property에서 직접 도달하게 한다.
 *
 * compiler는 property 대상 어노테이션을 호출되지 않는 합성 method(`get<Name>$annotations`)에 기록한다. 그 method로 가는 간선을
 * 두면 method를 통해 owner class까지 도달한다. interface의 `DefaultImpls` owner는 Kotlin 합성 class라 모든 method를 잇기 때문에,
 * 그 길로는 쓰이지 않는 default 본문 사본과 다른 property의 합성 method까지 도달해 버린다. 그래서 합성 method 정점은 건드리지
 * 않고, 그 method가 class 파일에 남긴 어노테이션 간선과 어노테이션 값의 class 참조만 `kotlinMetadata` 출처로 property 정점에
 * 옮긴다. method의 owner class와 descriptor 형식(extension receiver 등, property 접근자에도 같은 형식이 있다)으로 가는 참조는
 * 옮기지 않는다.
 *
 * 합성 method는 다른 class(`DefaultImpls`)에 있을 수 있어 class 하나를 읽는 metadata 보강 단계에서는 풀 수 없다. 보강 단계는
 * property 정점과 합성 method의 짝을 [link] 간선(`kotlinMetadata` 출처, 대상이 method인 ANNOTATION 간선. 다른 생산자는 ANNOTATION
 * 간선을 class로만 낸다)으로 class 사실에 남기고, 모든 class를 모은 조립 단계의 [attach]가 그 간선을 옮긴 간선으로 바꾼다.
 * 짝 간선은 그래프 밖으로 나가지 않는다(cache에는 class 사실의 일반 간선으로 저장된다). 짝의 property 정점(multi-file facade의
 * `const` field처럼 다른 class일 수 있다)이나 합성 method가 입력에 없으면 그 짝은 아무 간선도 남기지 않고 버려진다. 따로 세지 않으며,
 * 그 어노테이션 class는 이 보강이 없던 때처럼 다른 근거가 없으면 보고된다.
 */
internal object PropertyAnnotationHolders {
    /** property 정점 [member]와 그 어노테이션을 담은 합성 method [holder]의 짝을 class 사실에 남기는 간선이다. */
    fun link(member: NodeId, holder: NodeId): GraphEdge = GraphEdge(member, holder, EdgeKind.ANNOTATION, origin = EdgeOrigin.KOTLIN_METADATA)

    /** [link]로 만든 짝 간선인지 본다. */
    private fun isLink(edge: GraphEdge): Boolean =
        edge.kind == EdgeKind.ANNOTATION && edge.origin == EdgeOrigin.KOTLIN_METADATA && edge.target.value.startsWith("method:")

    /** [classFacts] 전체에서 짝 간선을 풀어 옮긴 간선으로 바꾼다. 짝이 없으면 입력을 그대로 돌려준다. */
    fun attach(classFacts: List<ClassFacts>): List<ClassFacts> {
        val holders = classFacts.flatMapTo(mutableSetOf()) { facts -> facts.edges.filter(::isLink).map(GraphEdge::target) }
        if (holders.isEmpty()) return classFacts
        val declared = classFacts.flatMapTo(HashSet()) { facts -> facts.nodes.map { node -> node.id } }
        val holderEdges = classFacts.asSequence().flatMap { facts -> facts.edges.asSequence() }
            .filter { edge -> edge.source in holders && isCarried(edge) }
            .groupBy(GraphEdge::source)
        return classFacts.map { facts ->
            val links = facts.edges.filter(::isLink)
            if (links.isEmpty()) return@map facts
            val carried = links.filter { link -> link.source in declared }.flatMap { link ->
                holderEdges[link.target].orEmpty().map { edge -> GraphEdge(link.source, edge.target, edge.kind, origin = EdgeOrigin.KOTLIN_METADATA) }
            }
            facts.copy(edges = facts.edges.filterNot(::isLink) + carried)
        }
    }

    /** 합성 method의 간선 중 property 어노테이션에서 온 것만 고른다. */
    private fun isCarried(edge: GraphEdge): Boolean = when {
        isLink(edge) -> false
        edge.kind == EdgeKind.ANNOTATION -> true
        edge.kind == EdgeKind.REFERENCE -> edge.target != JvmNodeId.classId(ownerOf(edge.source)) && edge.target !in descriptorTypes(edge.source)
        else -> false
    }

    private fun ownerOf(method: NodeId): String = method.value.substringAfter(':').substringBefore('#')

    /** method 정점 ID의 descriptor가 언급하는 object 형식 class 정점이다(배열은 원소 형식). */
    private fun descriptorTypes(method: NodeId): Set<NodeId> {
        val descriptor = method.value.substringAfter('#', "").let { member -> member.substring(member.indexOf('(').coerceAtLeast(0)) }
        if (!descriptor.startsWith("(")) return emptySet()
        val types = Type.getArgumentTypes(descriptor) + Type.getReturnType(descriptor)
        return types.map { type -> if (type.sort == Type.ARRAY) type.elementType else type }
            .filter { type -> type.sort == Type.OBJECT }
            .mapTo(mutableSetOf()) { type -> JvmNodeId.classId(type.internalName) }
    }
}
