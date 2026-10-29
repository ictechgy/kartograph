package dev.kartograph.index

import dev.kartograph.core.*
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertSame

/** 불완전하거나 모순된 header에서 후보를 선언으로 승격하지 않는 경계다. */
class CallDeclarationSafetyTest {
    @Test
    fun `unknown declarations retain the immutable graph without copying every call`() {
        val caller = method("p/Caller", name = "run")
        val graph = CodeGraph(listOf(type("p/Caller"), caller), emptyList(), (0 until 256).map { ordinal ->
            ExternalCall(caller.id, "missing/Boundary", "m", "()V", InvocationKind.VIRTUAL, ordinal = ordinal)
        }).withCompilerCallPositions(emptyList())
        val enriched = CallDeclarationIndexer(graph, ClassHierarchy.EMPTY).enrich()
        assertSame(graph, enriched)
        assertEquals(256, enriched.externalCalls.size)
        assertEquals(true, enriched.compilerCallPositionsCaptured)
        enriched.externalCalls.forEach { assertNull(it.declaration) }
    }

    @Test
    fun `unchanged graph reuse still clears stale declarations and preserves confirmed declarations`() {
        val caller = method("p/Caller", name = "run")
        val stale = ExternalCall(caller.id, "missing/Boundary", "m", "()V", InvocationKind.VIRTUAL,
            resolvedTargets = listOf(caller.id), resolution = CallResolution.UNRESOLVED,
            declaration = CallDeclaration(NodeId("method:missing/Boundary#m()V"), CallDeclarationScope.EXTERNAL,
                listOf("missing/Boundary")))
        val known = ExternalCall(caller.id, "lib/Known", "m", "()V", InvocationKind.VIRTUAL)
        val graph = CodeGraph(listOf(type("p/Caller"), caller), emptyList(), listOf(stale, known))
            .withCompilerCallPositions(emptyList())
        val headers = hierarchy(mapOf("lib/Known" to emptySet()),
            mapOf("lib/Known" to listOf(HierarchyMethod("m", "()V", Visibility.PUBLIC))))
        val enriched = CallDeclarationIndexer(graph, headers).enrich()
        assertNull(enriched.externalCalls.single { it.owner == "missing/Boundary" }.declaration)
        assertEquals(stale.copy(declaration = null), enriched.externalCalls.single { it.owner == "missing/Boundary" })
        assertEquals("method:lib/Known#m()V", enriched.externalCalls.single { it.owner == "lib/Known" }.declaration?.target?.value)
        assertEquals(graph.nodes, enriched.nodes)
        assertEquals(graph.edges, enriched.edges)
        assertEquals(true, enriched.compilerCallPositionsCaptured)
        assertSame(enriched, CallDeclarationIndexer(enriched, headers).enrich())
        assertNull(CallDeclarationIndexer(enriched, ClassHierarchy.EMPTY).enrich()
            .externalCalls.single { it.owner == "lib/Known" }.declaration)
    }

    @Test
    fun `abstract project declaration differs from concrete dispatch candidates`() {
        val declaration = resolve(
            types = listOf(type("p/Base")),
            methods = listOf(method("p/Base", modifiers = setOf(JvmModifier.ABSTRACT))),
            hierarchy = hierarchy(mapOf("lib/Boundary" to setOf("p/Base"))),
            candidates = listOf(NodeId("method:p/Caller#run()V")),
        )
        assertEquals(CallDeclaration(NodeId("method:p/Base#m()V"), CallDeclarationScope.PROJECT,
            listOf("lib/Boundary", "p/Base")), declaration)
    }

    @Test
    fun `first external declaration wins even when final and older project declaration exists`() {
        val hierarchy = hierarchy(mapOf("lib/Boundary" to setOf("p/Base")),
            mapOf("lib/Boundary" to listOf(HierarchyMethod("m", "()V", Visibility.PUBLIC, isFinal = true))))
        assertEquals(CallDeclaration(NodeId("method:lib/Boundary#m()V"), CallDeclarationScope.EXTERNAL, listOf("lib/Boundary")),
            resolve(listOf(type("p/Base")), listOf(method("p/Base")), hierarchy))
    }

    @Test
    fun `static mismatch cannot skip a nearer declaration`() {
        val entries = mapOf("lib/Boundary" to setOf("p/Base"))
        val shadow = hierarchy(entries, mapOf("lib/Boundary" to listOf(HierarchyMethod("m", "()V", Visibility.PUBLIC, isStatic = true))))
        assertNull(resolve(listOf(type("p/Base")), listOf(method("p/Base")), shadow))
        assertNull(resolve(listOf(type("p/Base")), listOf(method("p/Base")), hierarchy(entries), kind = InvocationKind.STATIC))
        assertEquals("method:p/Base#m()V", resolve(listOf(type("p/Base")),
            listOf(method("p/Base", modifiers = setOf(JvmModifier.STATIC))), hierarchy(entries), kind = InvocationKind.STATIC)?.target?.value)
    }

    @Test
    fun `missing headers missing method facts and unknown kinds stay unknown`() {
        val types = listOf(type("p/Base"))
        val methods = listOf(method("p/Base"))
        assertNull(resolve(types, methods, ClassHierarchy.EMPTY))
        assertNull(resolve(types, methods, ClassHierarchy(mapOf("lib/Boundary" to setOf("p/Base")),
            mapOf("lib/Boundary" to emptyList()), emptyMap())))
        assertNull(resolve(types, methods, ClassHierarchy(mapOf("lib/Boundary" to setOf("p/Base")),
            emptyMap(), emptyMap(), mapOf("lib/Boundary" to false))))
    }

    @Test
    fun `unknown siblings duplicate class parents and cycles stay unknown`() {
        val types = listOf(type("p/Base"), type("p/Other"))
        val methods = listOf(method("p/Base"))
        assertNull(resolve(types, methods, hierarchy(mapOf("lib/Boundary" to setOf("p/Base", "missing/I")))))
        assertNull(resolve(types, methods, hierarchy(mapOf("lib/Boundary" to setOf("p/Base", "p/Other")))))
        assertNull(resolve(types, methods, hierarchy(mapOf("lib/Boundary" to setOf("lib/Loop"), "lib/Loop" to setOf("lib/Boundary")))))
        assertNull(resolve(types, methods, hierarchy(mapOf("lib/Boundary" to setOf("lib/Boundary")),
            mapOf("lib/Boundary" to listOf(HierarchyMethod("m", "()V", Visibility.PUBLIC))))))
        val duplicate = HierarchyMethod("m", "()V", Visibility.PUBLIC)
        assertNull(resolve(types, methods, hierarchy(mapOf("lib/Boundary" to setOf("p/Base")),
            mapOf("lib/Boundary" to listOf(duplicate, duplicate)))))
        val chain = (0..CallDeclaration.MAX_OWNER_CHAIN).associate { "lib/C$it" to
            if (it == CallDeclaration.MAX_OWNER_CHAIN) setOf("p/Base") else setOf("lib/C${it + 1}") }
        assertNull(resolve(types, methods, hierarchy(chain), owner = "lib/C0"))
    }

    @Test
    fun `known interfaces are siblings but interface resolution and special calls stay unknown`() {
        val types = listOf(type("p/Base"), type("p/I", kind = NodeKind.INTERFACE))
        val methods = listOf(method("p/Base"))
        val hierarchy = hierarchy(mapOf("lib/Boundary" to setOf("p/Base", "p/I")))
        assertEquals("method:p/Base#m()V", resolve(types, methods, hierarchy)?.target?.value)
        assertNull(resolve(types, methods, hierarchy, kind = InvocationKind.INTERFACE))
        assertNull(resolve(types, methods, hierarchy, owner = "p/I"))
        assertNull(resolve(types, methods, hierarchy, owner = "p/I", kind = InvocationKind.SPECIAL, callerSupers = setOf("p/I")))
        assertNull(resolve(types, methods, hierarchy, kind = InvocationKind.SPECIAL))
        assertEquals("method:p/Base#m()V", resolve(types, methods, hierarchy, kind = InvocationKind.SPECIAL,
            callerSupers = setOf("lib/Boundary"))?.target?.value)
    }

    @Test
    fun `package protected and private access is checked per caller and receiver`() {
        val base = type("p/Base")
        val hierarchy = hierarchy(mapOf("lib/Boundary" to setOf("p/Base"), "lib/Receiver" to setOf("q/Caller")))
        assertEquals("method:p/Base#m()V", resolve(listOf(base), listOf(method("p/Base", Visibility.PACKAGE_PRIVATE)), hierarchy)?.target?.value)
        assertNull(resolve(listOf(base), listOf(method("p/Base", Visibility.PACKAGE_PRIVATE)), hierarchy, caller = "q/Caller"))
        assertNull(resolve(listOf(base), listOf(method("p/Base", Visibility.PROTECTED)), hierarchy,
            caller = "q/Caller", callerSupers = setOf("lib/Boundary")))
        assertEquals("method:p/Base#m()V", resolve(listOf(base), listOf(method("p/Base", Visibility.PROTECTED)), hierarchy,
            owner = "lib/Receiver", caller = "q/Caller", callerSupers = setOf("lib/Boundary"))?.target?.value)
        assertEquals("method:p/Base#m()V", resolve(listOf(base), listOf(method("p/Base", Visibility.PROTECTED)), hierarchy,
            kind = InvocationKind.SPECIAL, caller = "q/Caller", callerSupers = setOf("lib/Boundary"))?.target?.value)
        assertEquals("method:p/Base#m()V", resolve(listOf(base), listOf(method("p/Base", Visibility.PROTECTED, setOf(JvmModifier.STATIC))), hierarchy,
            kind = InvocationKind.STATIC, caller = "q/Caller", callerSupers = setOf("lib/Boundary"))?.target?.value)
        assertNull(resolve(listOf(base), listOf(method("p/Base", Visibility.PRIVATE)), hierarchy, caller = "p/Base\$Nested"))
        assertEquals("method:p/Base#m()V", resolve(emptyList(), listOf(method("p/Base", Visibility.PRIVATE)), hierarchy,
            caller = "p/Base")?.target?.value)
        assertNull(resolve(listOf(base), listOf(method("p/Base", Visibility.UNKNOWN)), hierarchy))
    }

    @Test
    fun `same operand with different callers cannot share access proof`() {
        val same = method("p/Caller", name = "run")
        val other = method("q/Caller", name = "run")
        val graph = CodeGraph(listOf(type("p/Base"), method("p/Base", Visibility.PACKAGE_PRIVATE),
            type("p/Caller"), type("q/Caller"), same, other), emptyList(), listOf(same, other).map {
            ExternalCall(it.id, "lib/Boundary", "m", "()V", InvocationKind.VIRTUAL)
        })
        val calls = CallDeclarationIndexer(graph, hierarchy(mapOf("lib/Boundary" to setOf("p/Base")))).enrich().externalCalls
        assertEquals("method:p/Base#m()V", calls.single { it.caller == same.id }.declaration?.target?.value)
        assertNull(calls.single { it.caller == other.id }.declaration)
    }

    @Test
    fun `interface defaults and inaccessible external shadows never become project declarations`() {
        val entries = mapOf("lib/Boundary" to setOf("lib/IA", "lib/IB"), "lib/IA" to emptySet(), "lib/IB" to emptySet())
        val methods = entries.keys.associateWith { if (it == "lib/Boundary") emptyList() else listOf(HierarchyMethod("m", "()V", Visibility.PUBLIC)) }
        val interfaces = ClassHierarchy(entries, methods, emptyMap(), mapOf("lib/Boundary" to false, "lib/IA" to true, "lib/IB" to true))
        assertNull(resolve(emptyList(), emptyList(), interfaces))
        val shadow = hierarchy(mapOf("lib/Boundary" to setOf("p/Base")),
            mapOf("lib/Boundary" to listOf(HierarchyMethod("m", "()V", Visibility.PRIVATE))))
        assertNull(resolve(listOf(type("p/Base")), listOf(method("p/Base")), shadow))
    }

    @Test
    fun `signature polymorphic bootstrap and constructors remain unknown`() {
        val entries = mapOf("java/lang/invoke/MethodHandle" to emptySet<String>())
        val hierarchy = hierarchy(entries, mapOf("java/lang/invoke/MethodHandle" to listOf(HierarchyMethod("m", "()V", Visibility.PUBLIC))))
        assertNull(resolve(emptyList(), emptyList(), hierarchy, owner = "java/lang/invoke/MethodHandle"))
        assertNull(resolve(listOf(type("p/Base")), listOf(method("p/Base")),
            hierarchy(mapOf("lib/Boundary" to setOf("p/Base"))), kind = InvocationKind.BOOTSTRAP))
        val caller = method("p/Caller", name = "run")
        val graph = CodeGraph(listOf(type("p/Caller"), caller), emptyList(),
            listOf(ExternalCall(caller.id, "lib/Boundary", "<init>", "()V", InvocationKind.SPECIAL)))
        assertNull(CallDeclarationIndexer(graph, hierarchy).enrich().externalCalls.single().declaration)
    }

    private fun resolve(types: List<GraphNode>, methods: List<GraphNode>, hierarchy: ClassHierarchy,
        owner: String = "lib/Boundary", kind: InvocationKind = InvocationKind.VIRTUAL,
        caller: String = "p/Caller", callerSupers: Set<String> = emptySet(), candidates: List<NodeId> = emptyList()): CallDeclaration? {
        val callerMethod = method(caller, name = "run")
        val call = ExternalCall(callerMethod.id, owner, "m", "()V", kind, resolvedTargets = candidates)
        val graph = CodeGraph(types + methods + type(caller, callerSupers) + callerMethod, emptyList(), listOf(call))
        val enriched = CallDeclarationIndexer(graph, hierarchy).enrich()
        assertEquals(graph.edges, enriched.edges)
        assertEquals(graph.nodes, enriched.nodes)
        assertEquals(graph.externalCalls.single(), enriched.externalCalls.single().copy(declaration = null))
        return enriched.externalCalls.single().declaration
    }

    private fun type(owner: String, supers: Set<String> = emptySet(), kind: NodeKind = NodeKind.CLASS) =
        GraphNode(NodeId("class:$owner"), owner.substringAfterLast('/'), kind, jvmSignature = owner, supertypes = supers)
    private fun method(owner: String, visibility: Visibility = Visibility.PUBLIC, modifiers: Set<JvmModifier> = emptySet(), name: String = "m") =
        GraphNode(NodeId("method:$owner#$name()V"), name, NodeKind.METHOD, jvmSignature = "$owner#$name()V", jvmVisibility = visibility, jvmModifiers = modifiers)
    private fun hierarchy(entries: Map<String, Set<String>>, methods: Map<String, List<HierarchyMethod>> = emptyMap()) =
        ClassHierarchy(entries, entries.keys.associateWith { methods[it].orEmpty() }, emptyMap(), entries.keys.associateWith { false })
}
