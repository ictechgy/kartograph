package dev.kartograph.export

import dev.kartograph.core.CallDeclaration
import dev.kartograph.core.CallDeclarationScope
import dev.kartograph.core.ExternalCall
import dev.kartograph.core.GraphNode
import dev.kartograph.core.InvocationKind
import dev.kartograph.core.JvmModifier
import dev.kartograph.core.NodeAttribute
import dev.kartograph.core.NodeId
import dev.kartograph.core.NodeKind
import dev.kartograph.core.Visibility

/** compact 행 길이를 바꾸지 않는 선택 side table이다. 외부 header 주장은 서명이 아니라 입력 데이터다. */
internal object CallDeclarationCodec {
    fun value(declaration: CallDeclaration): Map<String, Any?> = sortedMapOf(
        "target" to declaration.target.value, "scope" to declaration.scope.name.lowerCamel(), "ownerChain" to declaration.ownerChain,
    )

    fun records(calls: List<ExternalCall>): List<Map<String, Any?>> = calls.mapIndexedNotNull { index, call ->
        call.declaration?.let { value(it) + sortedMapOf(
            "call" to index, "caller" to call.caller.value, "operand" to call.target.value,
            "kind" to call.kind.name.lowerCamel(), "ordinal" to call.ordinal,
        ) }
    }

    fun parse(raw: Any?, calls: List<ExternalCall>, nodes: List<GraphNode>, present: Boolean): List<ExternalCall> {
        if (!present) return calls
        val rows = raw as? List<*> ?: invalid()
        require(rows.size <= calls.size) { "too many call declarations" }
        if (rows.isEmpty()) return calls
        val byId = nodes.associateBy { it.id }
        val seen = mutableSetOf<Int>()
        val declarations = rows.associate { row ->
            val item = row as? Map<*, *> ?: invalid()
            require(item.keys == KEYS) { "invalid call declaration fields" }
            val index = item["call"] as? Long ?: invalid()
            require(index >= 0 && index < calls.size && seen.add(index.toInt())) { "invalid or duplicate call declaration index" }
            val call = calls[index.toInt()]
            require(item["caller"] == call.caller.value && item["operand"] == call.target.value &&
                item["kind"] == call.kind.name.lowerCamel() && item["ordinal"] == call.ordinal.toLong()) {
                "call declaration does not match the observed call"
            }
            val rawChain = item["ownerChain"] as? List<*> ?: invalid()
            require(rawChain.size in 1..CallDeclaration.MAX_OWNER_CHAIN) { "call declaration chain exceeds its bound" }
            val chain = rawChain.map { it as? String ?: invalid() }
            val scope = when (item["scope"]) {
                "project" -> CallDeclarationScope.PROJECT
                "external" -> CallDeclarationScope.EXTERNAL
                else -> invalid()
            }
            val declaration = CallDeclaration(NodeId(item["target"] as? String ?: invalid()), scope, chain)
            // 생성자가 operand, signature, chain endpoint와 지원 opcode를 함께 검사한다.
            call.copy(declaration = declaration)
            validateGraphClaims(call, declaration, byId)
            index.toInt() to declaration
        }
        return calls.mapIndexed { index, call -> call.copy(declaration = declarations[index]) }
    }

    private fun validateGraphClaims(call: ExternalCall, declaration: CallDeclaration, nodes: Map<NodeId, GraphNode>) {
        fun project(id: NodeId): GraphNode? = nodes[id]?.takeUnless { NodeAttribute.EXTERNAL_STUB in it.attributes }
        fun type(owner: String): GraphNode? = project(NodeId("class:$owner"))
        // 남아 있는 프로젝트 header만으로 반증할 수 있을 때 거부한다. 누락된 외부 조상은 데이터의 주장으로 남긴다.
        fun knownSubclass(child: String, parent: String): Boolean? {
            var owner = child
            val seen = mutableSetOf<String>()
            while (seen.size < CallDeclaration.MAX_OWNER_CHAIN && seen.add(owner)) {
                if (owner == parent) return true
                val current = type(owner) ?: return null
                if (current.kind !in CLASS_KINDS) return false
                if (current.supertypes.any { type(it) == null }) return null
                val parents = current.supertypes.filter { type(it)?.kind in CLASS_KINDS }
                if (parents.size != 1) return false
                owner = parents.single()
            }
            return false
        }
        require(call.caller.value.startsWith("method:") && project(call.caller)?.kind in CALLER_KINDS) { "call declaration requires a method caller" }
        val chain = declaration.ownerChain
        chain.forEachIndexed { index, owner ->
            type(owner)?.let { node ->
                require(node.kind in CLASS_KINDS && node.jvmSignature == owner) { "call declaration chain conflicts with a project class" }
                require(node.supertypes.count { type(it)?.kind in CLASS_KINDS } <= 1) { "ambiguous project class parents" }
                if (index < chain.lastIndex) require(chain[index + 1] in node.supertypes &&
                    project(NodeId("method:$owner#${call.name}${call.descriptor}")) == null) { "call declaration skips a project declaration or superclass" }
            }
        }
        val target = project(declaration.target)
        if (declaration.scope == CallDeclarationScope.EXTERNAL) {
            require(target == null && type(chain.last()) == null) { "external call declaration names a project owner" }
        } else {
            require(target != null && target.kind in METHOD_KINDS &&
                (JvmModifier.STATIC in target.jvmModifiers) == (call.kind == InvocationKind.STATIC)) {
                "project call declaration does not match a method"
            }
            val callerOwner = call.caller.value.removePrefix("method:").substringBefore('#')
            val samePackage = callerOwner.substringBeforeLast('/', "") == chain.last().substringBeforeLast('/', "")
            require(when (target.jvmVisibility) {
                Visibility.PUBLIC -> true
                Visibility.PRIVATE, Visibility.PRIVATE_TO_THIS -> callerOwner == chain.last()
                Visibility.PACKAGE_PRIVATE -> samePackage
                Visibility.PROTECTED -> samePackage || knownSubclass(callerOwner, chain.last()) != false &&
                    (call.kind != InvocationKind.VIRTUAL || callerOwner in chain)
                else -> false
            }) { "project call declaration has incompatible access" }
        }
        if (call.kind == InvocationKind.SPECIAL) {
            val caller = type(call.caller.value.removePrefix("method:").substringBefore('#'))
            require(caller == null || caller.kind in CLASS_KINDS && call.owner in caller.supertypes) { "unsupported special call declaration" }
        }
    }

    private fun invalid(): Nothing = throw IllegalArgumentException("invalid call declaration evidence")
    private val CLASS_KINDS = setOf(NodeKind.CLASS, NodeKind.OBJECT, NodeKind.ENUM)
    private val METHOD_KINDS = setOf(NodeKind.METHOD, NodeKind.FUNCTION)
    private val CALLER_KINDS = METHOD_KINDS + NodeKind.CONSTRUCTOR
    private val KEYS = setOf("call", "caller", "operand", "kind", "ordinal", "target", "scope", "ownerChain")
}
