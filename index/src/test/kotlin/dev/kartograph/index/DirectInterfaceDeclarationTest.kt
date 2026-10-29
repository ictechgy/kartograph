package dev.kartograph.index

import dev.kartograph.core.*
import kotlin.test.*

/** 직접 interface header만 사용하고 inherited/default/Object/dispatch 추측을 거부한다. */
class DirectInterfaceDeclarationTest {
    private val caller = GraphNode(NodeId("method:p/Caller#run()V"), "run", NodeKind.METHOD)
    private val callerType = GraphNode(NodeId("class:p/Caller"), "Caller", NodeKind.CLASS, jvmSignature = "p/Caller")
    private val method = HierarchyMethod("m", "()V", Visibility.PUBLIC)

    @Test
    fun `public direct interface declaration is exact and independent of unknown ancestors and CHA`() {
        val implementation = GraphNode(NodeId("method:p/Implementation#m()V"), "m", NodeKind.METHOD)
        val raw = call().copy(resolvedTargets = listOf(implementation.id), resolution = CallResolution.PROJECT_CANDIDATES)
        val graph = CodeGraph(listOf(caller, callerType, implementation), emptyList(), listOf(raw))
        val hierarchy = header(parents = setOf("missing/Ancestor"))
        val enriched = CallDeclarationIndexer(graph, hierarchy).enrich()
        val declared = enriched.externalCalls.single()
        assertEquals(CallDeclaration(raw.target, CallDeclarationScope.EXTERNAL, listOf("lib/I")), declared.declaration)
        assertEquals(raw, declared.copy(declaration = null))
        assertEquals(graph.nodes, enriched.nodes)
        assertEquals(graph.edges, enriched.edges)
        assertEquals(declared, CallDeclarationIndexer(enriched, hierarchy).enrich().externalCalls.single())
        assertNull(CallDeclarationIndexer(enriched, ClassHierarchy.EMPTY).enrich().externalCalls.single().declaration)
    }

    @Test
    fun `incomplete wrong kind private static duplicate and wrong descriptor headers remain unknown`() {
        val negatives = listOf(
            ClassHierarchy.EMPTY,
            ClassHierarchy(mapOf("lib/I" to emptySet()), mapOf("lib/I" to listOf(method))),
            ClassHierarchy(emptyMap(), mapOf("lib/I" to listOf(method)), emptyMap(), mapOf("lib/I" to true)),
            ClassHierarchy(mapOf("lib/I" to emptySet()), emptyMap(), emptyMap(), mapOf("lib/I" to true)),
            header(kind = false), header(methods = emptyList()), header(methods = listOf(method, method)),
            header(methods = listOf(method.copy(isStatic = true))),
            header(methods = listOf(method.copy(descriptor = "(I)V"))),
        ) + Visibility.entries.filter { it != Visibility.PUBLIC }.map { header(methods = listOf(method.copy(visibility = it))) }
        negatives.forEach { hierarchy -> assertNull(resolve(call(), hierarchy)) }
        InvocationKind.entries.filter { it != InvocationKind.INTERFACE }.forEach { kind ->
            assertNull(resolve(call().copy(kind = kind), header()), kind.name)
        }
        assertNull(resolve(call(), header(), listOf(callerType)))
        assertNull(resolve(call(), header(), listOf(caller.copy(kind = NodeKind.FIELD), callerType)))
        assertNull(resolve(call(), header(), listOf(caller)))
        val malformedCaller = caller.copy(id = NodeId("method:p/Caller"))
        assertNull(resolve(call().copy(caller = malformedCaller.id), header(), listOf(malformedCaller, callerType)))
    }

    @Test
    fun `malformed method operands never gain declaration evidence`() {
        val descriptors = listOf("", "I", "(V)V", "()", "()VV", "()Vjunk", "(L;)V", "(Lbad.name;)V", "([V)V", "(I", "()Lx", "(I))V", "([" + "[".repeat(255) + "I)V", "(" + "J".repeat(128) + ")V")
        descriptors.forEach { descriptor ->
            assertNull(resolve(call().copy(descriptor = descriptor), header(methods = listOf(method.copy(descriptor = descriptor)))), descriptor)
        }
        listOf("", "<init>", "<clinit>", "bad/name", "bad.name", "bad;name", "bad[name", "bad>name").forEach { name ->
            assertNull(resolve(call().copy(name = name), header(methods = listOf(method.copy(name = name)))), name)
        }
        listOf("()V", "(JDLjava/lang/Object;[[I)[Ljava/lang/String;", "(" + "I".repeat(254) + ")V").forEach { descriptor ->
            assertNotNull(resolve(call().copy(descriptor = descriptor), header(methods = listOf(method.copy(descriptor = descriptor)))), descriptor)
        }
    }

    @Test
    fun `inherited Object fallback default diamonds and ambiguous abstract parents stay unknown`() {
        val ancestor = listOf(method)
        for (parents in listOf(setOf("lib/A"), setOf("lib/A", "lib/B"))) {
            val hierarchy = ClassHierarchy(mapOf("lib/I" to parents, "lib/A" to emptySet(), "lib/B" to setOf("lib/A")),
                mapOf("lib/I" to emptyList(), "lib/A" to ancestor, "lib/B" to ancestor), emptyMap(),
                mapOf("lib/I" to true, "lib/A" to true, "lib/B" to true))
            assertNull(resolve(call(), hierarchy))
        }
        val objectMethod = HierarchyMethod("toString", "()Ljava/lang/String;", Visibility.PUBLIC)
        val fallback = ClassHierarchy(mapOf("lib/I" to setOf("java/lang/Object"), "java/lang/Object" to emptySet()),
            mapOf("lib/I" to emptyList(), "java/lang/Object" to listOf(objectMethod)), emptyMap(),
            mapOf("lib/I" to true, "java/lang/Object" to false))
        val objectCall = call().copy(name = objectMethod.name, descriptor = objectMethod.descriptor)
        assertNull(resolve(objectCall, fallback))
        assertEquals(objectCall.target, resolve(objectCall, header(methods = listOf(objectMethod)))?.target)
    }

    @Test
    fun `selected project owner shadows dependency header without inventing an external declaration`() {
        val projectType = GraphNode(NodeId("class:lib/I"), "I", NodeKind.INTERFACE, jvmSignature = "lib/I")
        assertNull(resolve(call(), header(), listOf(caller, callerType, projectType)))
        assertNull(resolve(call(), header(), listOf(caller, callerType, projectType.copy(kind = NodeKind.CLASS))))
        val target = GraphNode(call().target, "m", NodeKind.METHOD, jvmVisibility = Visibility.PUBLIC)
        val graph = CodeGraph(listOf(caller, callerType, projectType, target), emptyList(), listOf(call()))
        assertTrue(CallDeclarationIndexer(graph, header()).enrich().externalCalls.isEmpty())
    }

    @Test
    fun `same owner caller eligibility cannot poison declaration cache in either order`() {
        for (invalidFirst in listOf(false, true)) {
            for (invalid in listOf(caller.copy(kind = NodeKind.FIELD), caller.copy(attributes = setOf(NodeAttribute.EXTERNAL_STUB)))) {
                val validName = if (invalidFirst) "b" else "a"
                val invalidName = if (invalidFirst) "a" else "b"
                val valid = caller.copy(id = NodeId("method:p/Caller#$validName()V"), name = validName)
                val rejected = invalid.copy(id = NodeId("method:p/Caller#$invalidName()V"), name = invalidName)
                val graph = CodeGraph(listOf(callerType, valid, rejected), emptyList(),
                    listOf(call().copy(caller = valid.id), call().copy(caller = rejected.id)))
                val enriched = CallDeclarationIndexer(graph, header()).enrich()
                assertNotNull(enriched.externalCalls.single { it.caller == valid.id }.declaration, "valid caller; invalidFirst=$invalidFirst")
                assertNull(enriched.externalCalls.single { it.caller == rejected.id }.declaration, "invalid caller; invalidFirst=$invalidFirst")
                assertEquals(graph.externalCalls, enriched.externalCalls.map { it.copy(declaration = null) })
                assertEquals(graph.edges, enriched.edges)
                assertEquals(graph.nodes, enriched.nodes)
                val filtered = CodeGraph(listOf(callerType, valid), graph.edges, graph.externalCalls)
                assertEquals(valid.id, CallDeclarationIndexer(filtered, header()).enrich().externalCalls.single().caller)
                assertNotNull(CallDeclarationIndexer(filtered, header()).enrich().externalCalls.single().declaration)
            }
        }
    }

    private fun call() = ExternalCall(caller.id, "lib/I", "m", "()V", InvocationKind.INTERFACE)
    private fun header(methods: List<HierarchyMethod> = listOf(method), kind: Boolean = true, parents: Set<String> = emptySet()) =
        ClassHierarchy(mapOf("lib/I" to parents), mapOf("lib/I" to methods), emptyMap(), mapOf("lib/I" to kind))
    private fun resolve(call: ExternalCall, hierarchy: ClassHierarchy, nodes: List<GraphNode> = listOf(caller, callerType)): CallDeclaration? =
        CallDeclarationIndexer(CodeGraph(nodes, emptyList(), listOf(call)), hierarchy).enrich().externalCalls.singleOrNull()?.declaration
}
