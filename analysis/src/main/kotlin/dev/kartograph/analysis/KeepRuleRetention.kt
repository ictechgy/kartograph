package dev.kartograph.analysis

import dev.kartograph.core.ClassHierarchy
import dev.kartograph.core.CodeGraph
import dev.kartograph.core.EdgeKind
import dev.kartograph.core.GraphNode
import dev.kartograph.core.KeepDeclarationKind
import dev.kartograph.core.KeepMemberCondition
import dev.kartograph.core.KeepMemberKind
import dev.kartograph.core.KeepRule
import dev.kartograph.core.NodeId
import dev.kartograph.core.NodeKind
import dev.kartograph.core.RetentionEvidence
import dev.kartograph.core.RetentionReason

/** 파싱된 ProGuard/R8 class specification을 실제 JVM 정점에 적용한다. */
public object KeepRuleRetention {
    /**
     * 정점·규칙 순서로 근거를 반환하며
     * 외부 전이 상속을 확인할 수 없으면 판정을 거부한다.
     */
    public fun find(
        graph: CodeGraph,
        rules: Iterable<KeepRule>,
        dependencyHierarchy: ClassHierarchy = ClassHierarchy.EMPTY,
    ): List<RetentionEvidence> {
        val compiledRules = rules.map(::CompiledKeepRule)
        return graph.nodeIds.flatMap { nodeId ->
            val node = graph.nodes.getValue(nodeId)
            compiledRules.flatMap { rule ->
                rule.matchingNodeIds(node, graph, dependencyHierarchy).map { retainedNodeId ->
                    RetentionEvidence(retainedNodeId, RetentionReason.KEEP_RULE, rule.source.location)
                }
            }
        }.distinct()
    }

    private data class CompiledKeepRule(
        val source: KeepRule,
        val classNamePattern: Regex = source.classNamePattern.toJvmNameRegex(),
        val extendsPattern: Regex? = source.extendsPattern?.toJvmNameRegex(),
        val annotationPattern: Regex? = source.requiredAnnotationPattern?.toJvmNameRegex(),
        val memberConditions: List<CompiledMemberCondition> = source.memberConditions.map(::CompiledMemberCondition),
        val keptMembers: List<CompiledMemberCondition> = source.keptMembers.map(::CompiledMemberCondition),
    ) {
        fun matchingNodeIds(
            node: GraphNode,
            graph: CodeGraph,
            dependencyHierarchy: ClassHierarchy,
        ): List<NodeId> {
            val internalName = node.classInternalName ?: return emptyList()
            if (!source.declarationKind.matches(node.kind) || !classNamePattern.matches(internalName)) {
                return emptyList()
            }
            if (source.requiredJvmVisibilities.isNotEmpty() &&
                node.jvmVisibility !in source.requiredJvmVisibilities
            ) {
                return emptyList()
            }
            if (node.jvmVisibility in source.forbiddenJvmVisibilities) return emptyList()
            if (!node.jvmModifiers.containsAll(source.requiredJvmModifiers)) return emptyList()
            if (node.jvmModifiers.any(source.forbiddenJvmModifiers::contains)) return emptyList()
            if (annotationPattern != null && node.annotations.none(annotationPattern::matches)) return emptyList()
            val declaredMembers = if (source.keepAllMembers || memberConditions.isNotEmpty() || keptMembers.isNotEmpty()) {
                graph.outgoingEdgesFrom(node.id)
                    .filter { edge -> edge.kind == EdgeKind.MEMBER }
                    .mapNotNull { edge -> graph.node(edge.target) }
            } else {
                emptyList()
            }
            if (memberConditions.any { condition -> declaredMembers.none(condition::matches) }) return emptyList()
            val retainedMembers = when {
                source.keepAllMembers -> declaredMembers
                else -> declaredMembers.filter { member ->
                    memberConditions.any { condition -> condition.matches(member) } ||
                        keptMembers.any { specification -> specification.matches(member) }
                }
            }
            if (extendsPattern != null) when (node.supertypeMatch(extendsPattern, graph, dependencyHierarchy)) {
                SupertypeMatch.MATCH -> Unit
                SupertypeMatch.NO_MATCH -> return emptyList()
                SupertypeMatch.INCOMPLETE -> throw IncompleteKeepRuleHierarchyException(source)
            }
            return listOf(node.id) + retainedMembers.map(GraphNode::id)
        }
    }

    private data class CompiledMemberCondition(
        val source: KeepMemberCondition,
        val annotationPattern: Regex? = source.requiredAnnotationPattern?.toJvmNameRegex(),
        val namePattern: Regex? = source.namePattern?.toMemberNameRegex(),
    ) {
        fun matches(node: GraphNode): Boolean = source.kind.matches(node.kind) &&
            (annotationPattern == null || node.annotations.any(annotationPattern::matches)) &&
            (source.requiredJvmVisibilities.isEmpty() || node.jvmVisibility in source.requiredJvmVisibilities) &&
            node.jvmVisibility !in source.forbiddenJvmVisibilities &&
            source.requiredJvmModifiers.all(node.jvmModifiers::contains) &&
            source.forbiddenJvmModifiers.none(node.jvmModifiers::contains) &&
            (namePattern == null || namePattern.matches(node.name)) &&
            (source.jvmDescriptor == null || node.jvmDescriptor == source.jvmDescriptor)
    }

    private fun GraphNode.supertypeMatch(
        pattern: Regex,
        graph: CodeGraph,
        dependencyHierarchy: ClassHierarchy,
    ): SupertypeMatch {
        val visited = mutableSetOf<String>()
        val queue = ArrayDeque(supertypes.sorted())
        var incomplete = false
        while (queue.isNotEmpty()) {
            val supertype = queue.removeFirst()
            if (!visited.add(supertype)) continue
            if (pattern.matches(supertype)) return SupertypeMatch.MATCH
            val supertypeNode = graph.node(NodeId("class:$supertype"))
            val dependencySupertypes = dependencyHierarchy.directSupertypesOf(supertype)
            if (supertypeNode != null) {
                supertypeNode.supertypes.sorted().forEach(queue::addLast)
            } else if (dependencySupertypes != null) {
                dependencySupertypes.sorted().forEach(queue::addLast)
            } else {
                incomplete = true
            }
        }
        return if (incomplete) SupertypeMatch.INCOMPLETE else SupertypeMatch.NO_MATCH
    }

    private val GraphNode.classInternalName: String?
        get() = id.value.removePrefix("class:").takeIf { value -> value != id.value && kind.isType }

    private val NodeKind.isType: Boolean
        get() = this in TYPE_KINDS

    private fun KeepDeclarationKind.matches(kind: NodeKind): Boolean = when (this) {
        KeepDeclarationKind.CLASS -> kind in TYPE_KINDS
        KeepDeclarationKind.INTERFACE -> kind == NodeKind.INTERFACE || kind == NodeKind.ANNOTATION_CLASS
        KeepDeclarationKind.ENUM -> kind == NodeKind.ENUM
    }

    private fun KeepMemberKind.matches(kind: NodeKind): Boolean = when (this) {
        KeepMemberKind.METHODS -> kind == NodeKind.METHOD || kind == NodeKind.FUNCTION
        KeepMemberKind.FIELDS -> kind == NodeKind.FIELD || kind == NodeKind.PROPERTY
        KeepMemberKind.CONSTRUCTORS -> kind == NodeKind.CONSTRUCTOR
    }

    private fun String.toJvmNameRegex(): Regex {
        if (this == "*") return Regex("^.*$")
        val expression = StringBuilder("^")
        var index = 0
        while (index < length) {
            when {
                startsWith("**", index) -> {
                    expression.append(".*")
                    index++
                }
                this[index] == '*' -> expression.append("[^/]*")
                this[index] == '?' -> expression.append("[^/]")
                this[index] == '.' -> expression.append('/')
                else -> expression.append(Regex.escape(this[index].toString()))
            }
            index++
        }
        return Regex(expression.append('$').toString())
    }

    private fun String.toMemberNameRegex(): Regex = Regex(
        buildString {
            append('^')
            this@toMemberNameRegex.forEach { character ->
                when (character) {
                    '*' -> append(".*")
                    '?' -> append('.')
                    else -> append(Regex.escape(character.toString()))
                }
            }
            append('$')
        },
    )

    private val GraphNode.jvmDescriptor: String?
        get() = jvmSignature?.substringAfter('#', missingDelimiterValue = "")?.let { memberSignature ->
            when (kind) {
                NodeKind.FIELD, NodeKind.PROPERTY -> memberSignature.substringAfter(':', missingDelimiterValue = "")
                else -> memberSignature.removePrefix(name)
            }.takeIf(String::isNotEmpty)
        }

    private val TYPE_KINDS = setOf(
        NodeKind.CLASS,
        NodeKind.INTERFACE,
        NodeKind.OBJECT,
        NodeKind.ENUM,
        NodeKind.ANNOTATION_CLASS,
    )

    private enum class SupertypeMatch { MATCH, NO_MATCH, INCOMPLETE }
}

/** 외부 classpath 없이는 상속 keep 규칙의 일치 여부를 확정할 수 없음을 나타낸다. */
public class IncompleteKeepRuleHierarchyException(rule: KeepRule) : IllegalStateException(
    "dependency hierarchy is incomplete for keep rule at ${rule.location.path}:${rule.location.line}; " +
        "pass every dependency directory or JAR with --classpath",
)
