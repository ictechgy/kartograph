package dev.kartograph.index

import dev.kartograph.core.CallDeclaration
import dev.kartograph.core.CallDeclarationScope
import dev.kartograph.core.ClassHierarchy
import dev.kartograph.core.CodeGraph
import dev.kartograph.core.ExternalCall
import dev.kartograph.core.HierarchyMethod
import dev.kartograph.core.InvocationKind
import dev.kartograph.core.JvmModifier
import dev.kartograph.core.NodeAttribute
import dev.kartograph.core.NodeKind
import dev.kartograph.core.Visibility

/** dispatch 후보를 참조하지 않고 class 사슬의 첫 선언 또는 interface의 public instance 직접 선언만 확인한다. */
internal class CallDeclarationIndexer(private val graph: CodeGraph, private val hierarchy: ClassHierarchy) {
    private val types = graph.nodes.values.filter {
        it.kind in TYPE_KINDS && it.jvmSignature != null && NodeAttribute.EXTERNAL_STUB !in it.attributes
    }.associateBy { requireNotNull(it.jvmSignature) }
    private val headers = mutableMapOf<String, Header?>()
    private val declarations = mutableMapOf<Key, CallDeclaration?>()

    /** 이전 보강 결과를 재사용하지 않아 dependency 교체와 withHierarchy에도 동일한 경계를 적용한다. */
    fun enrich(): CodeGraph = graph.enrichedWith(emptyList(), graph.externalCalls.map { call ->
        val caller = call.caller.value.takeIf { it.startsWith("method:") }?.removePrefix("method:")?.substringBefore('#')
        // caller owner만 공유하는 cache에 개별 node의 kind/stub 자격을 저장하지 않는다.
        if (call.kind == InvocationKind.INTERFACE && !knownInterfaceCaller(call, caller)) return@map call.copy(declaration = null)
        val key = Key(caller, call.owner, call.name, call.descriptor, call.kind)
        if (key !in declarations) declarations[key] = resolve(call, caller)
        call.copy(declaration = declarations[key])
    })

    private fun resolve(call: ExternalCall, caller: String?): CallDeclaration? {
        if (call.kind == InvocationKind.INTERFACE) return directInterface(call)
        if (caller == null || call.kind !in SUPPORTED_KINDS || call.name.startsWith('<')) return null
        if (call.kind == InvocationKind.SPECIAL && header(caller)?.parent != call.owner) return null
        val chain = linkedSetOf<String>()
        var owner = call.owner
        while (chain.size < CallDeclaration.MAX_OWNER_CHAIN && chain.add(owner)) {
            if (owner in SIGNATURE_POLYMORPHIC_OWNERS) return null
            val current = header(owner) ?: return null
            if (current.parent in chain) return null
            val matches = if (owner in types) {
                graph.node(JvmNodeId.methodId(owner, call.name, call.descriptor))?.let { node ->
                    if (node.kind !in METHOD_KINDS || NodeAttribute.EXTERNAL_STUB in node.attributes) return null
                    listOf(HierarchyMethod(call.name, call.descriptor, node.jvmVisibility,
                        JvmModifier.STATIC in node.jvmModifiers, JvmModifier.FINAL in node.jvmModifiers))
                }.orEmpty()
            } else current.methods.filter { it.name == call.name && it.descriptor == call.descriptor }
            if (matches.isNotEmpty()) {
                val declaration = matches.singleOrNull() ?: return null
                if (declaration.isStatic != (call.kind == InvocationKind.STATIC) ||
                    !accessible(declaration.visibility, owner, caller, call)) return null
                return CallDeclaration(JvmNodeId.methodId(owner, call.name, call.descriptor),
                    if (owner in types) CallDeclarationScope.PROJECT else CallDeclarationScope.EXTERNAL, chain.toList())
            }
            owner = current.parent ?: return null
        }
        return null
    }

    private fun knownInterfaceCaller(call: ExternalCall, caller: String?): Boolean =
        caller in types && call.caller.value.startsWith("method:$caller#") && graph.node(call.caller)?.let {
            it.kind in CALLER_KINDS && NodeAttribute.EXTERNAL_STUB !in it.attributes
        } == true

    private fun directInterface(call: ExternalCall): CallDeclaration? {
        if (!CallDeclaration.isValidInterfaceSignature(call.name, call.descriptor) || isInterface(call.owner) != true) return null
        // 프로젝트 직접 operand는 이미 내부 CALL이다. 선택된 프로젝트 owner를 dependency header로 대체하지 않는다.
        if (call.owner in types) return null
        if (hierarchy.directSupertypesOf(call.owner) == null) return null
        val methods = hierarchy.declaredMethodsOf(call.owner) ?: return null
        val method = methods.singleOrNull { it.name == call.name && it.descriptor == call.descriptor } ?: return null
        if (method.visibility != Visibility.PUBLIC || method.isStatic) return null
        return CallDeclaration(call.target, CallDeclarationScope.EXTERNAL, listOf(call.owner))
    }

    private fun accessible(visibility: Visibility, owner: String, caller: String, call: ExternalCall): Boolean = when (visibility) {
        Visibility.PUBLIC -> true
        Visibility.PRIVATE, Visibility.PRIVATE_TO_THIS -> owner == caller
        Visibility.PACKAGE_PRIVATE -> samePackage(owner, caller)
        Visibility.PROTECTED -> samePackage(owner, caller) ||
            (subclass(caller, owner) && (call.kind != InvocationKind.VIRTUAL || subclass(call.owner, caller)))
        else -> false
    }

    private fun subclass(child: String, parent: String): Boolean {
        var owner = child
        val seen = mutableSetOf<String>()
        while (seen.size < CallDeclaration.MAX_OWNER_CHAIN && seen.add(owner)) {
            val current = header(owner) ?: return false
            if (owner == parent) return true
            owner = current.parent ?: return false
        }
        return false
    }

    private fun header(owner: String): Header? {
        if (owner in headers) return headers[owner]
        fun read(): Header? {
            if (isInterface(owner) != false) return null
            val parents = types[owner]?.supertypes ?: hierarchy.directSupertypesOf(owner) ?: return null
            if (parents.size > CallDeclaration.MAX_OWNER_CHAIN || parents.any { isInterface(it) == null }) return null
            val classes = parents.filter { isInterface(it) == false }
            if (classes.size > 1) return null
            val methods = if (owner in types) emptyList() else hierarchy.declaredMethodsOf(owner) ?: return null
            return Header(classes.singleOrNull(), methods)
        }
        return read().also { headers[owner] = it }
    }

    private fun isInterface(owner: String): Boolean? = types[owner]?.let { it.kind in INTERFACE_KINDS } ?: hierarchy.isInterface(owner)
    private fun samePackage(a: String, b: String): Boolean = a.substringBeforeLast('/', "") == b.substringBeforeLast('/', "")
    private data class Header(val parent: String?, val methods: List<HierarchyMethod>)
    private data class Key(val caller: String?, val owner: String, val name: String, val descriptor: String, val kind: InvocationKind)

    private companion object {
        val INTERFACE_KINDS = setOf(NodeKind.INTERFACE, NodeKind.ANNOTATION_CLASS)
        val TYPE_KINDS = setOf(NodeKind.CLASS, NodeKind.INTERFACE, NodeKind.OBJECT, NodeKind.ENUM, NodeKind.ANNOTATION_CLASS)
        val METHOD_KINDS = setOf(NodeKind.METHOD, NodeKind.FUNCTION)
        val CALLER_KINDS = METHOD_KINDS + NodeKind.CONSTRUCTOR
        val SUPPORTED_KINDS = setOf(InvocationKind.VIRTUAL, InvocationKind.STATIC, InvocationKind.SPECIAL)
        val SIGNATURE_POLYMORPHIC_OWNERS = setOf("java/lang/invoke/MethodHandle", "java/lang/invoke/VarHandle")
    }
}
