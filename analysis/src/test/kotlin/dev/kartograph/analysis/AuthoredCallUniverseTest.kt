package dev.kartograph.analysis

import dev.kartograph.core.*
import kotlin.test.*

class AuthoredCallUniverseTest {
    private val zero = NodeId("method:Calls#zero()V")
    private val constructor = NodeId("method:Calls#<init>()V")
    private val file = CompilerEvidenceSource("src/Calls.java", "a".repeat(64))
    private val noClass = CompilerEvidenceSource("src/NoClass.java", "b".repeat(64))
    private val ordinary = AuthoredCallerScope(mapOf("main" to setOf(file.path, noClass.path)), setOf(CompilerSourceBodyKind.ORDINARY_METHOD))
    private val whole = ordinary.copy(bodyKinds = CompilerSourceBodyKind.entries.toSet(), wholeProject = true)
    private fun inventory() = CompilerCallerInventory("main", "compileJava", "classes", listOf(file, noClass), listOf(
        CompilerSourceBody("b0", file, 0, 20, CompilerSourceBodyKind.ORDINARY_METHOD, true, "JAVAC_EXPLICIT", listOf(zero)),
        CompilerSourceBody("b1", file, null, null, CompilerSourceBodyKind.IMPLICIT_CONSTRUCTOR, true, "JAVAC_MANDATED", listOf(constructor)),
    ), listOf(CompilerInventoryOutput("Calls.class", "b".repeat(64), "Calls")), listOf(
        CompilerInventoryMethod(zero, 1, true, CompilerBackendOrigin.SOURCE_BOUND, "b0", "JAVAC_EXPLICIT"),
        CompilerInventoryMethod(constructor, 1, true, CompilerBackendOrigin.SOURCE_BOUND, "b1", "JAVAC_MANDATED"),
    ), CompilerSourceCoordinateBasis.JAVAC_UTF16_CHAR_SEQUENCE, "17.0.20", "c".repeat(64), "d".repeat(64), "e".repeat(64), "f".repeat(64))
    private fun graph(withCoverage: Boolean = true): CodeGraph {
        val base = CodeGraph(listOf(GraphNode(NodeId("class:Calls"), "Calls", NodeKind.CLASS), GraphNode(zero, "zero", NodeKind.METHOD), GraphNode(constructor, "Calls", NodeKind.CONSTRUCTOR)), emptyList())
        val row = CompilerAuthoredCaller(zero, file, 0, 20, CompilerSourceCoordinateBasis.JAVAC_UTF16_CHAR_SEQUENCE, "17.0.20", 1, 0,
            AuthoredCallCoverage.COMPLETE, emptyList(), emptyList(), "c".repeat(64), "d".repeat(64), "f".repeat(64))
        return base.withCompilerAuthoredCalls(if (withCoverage) listOf(row) else emptyList()).withCompilerCallerInventories(listOf(inventory()))
    }
    @Test fun `explicit ordinary complete-zero scope differs from whole project`() {
        val result = AuthoredCallUniverse.examine(graph(), ordinary)
        assertEquals(AuthoredUniverseStatus.COMPLETE, result.status)
        assertEquals(setOf(zero), result.callers)
        assertEquals(mapOf(zero to emptyList()), result.calls)
        assertEquals(AuthoredUniverseStatus.INCOMPLETE, AuthoredCallUniverse.examine(graph(), whole).status)
    }
    @Test fun `missing zero-bytecode-call caller and no-class source remain unknown`() {
        assertEquals(AuthoredUniverseStatus.UNKNOWN, AuthoredCallUniverse.examine(graph(false), ordinary).status)
        val missingFile = graph().withCompilerCallerInventories(listOf(inventory().copy(sources = listOf(file), sourceBodyCounts = mapOf(file.path to 2))))
        assertEquals(AuthoredUniverseStatus.UNKNOWN, AuthoredCallUniverse.examine(missingFile, ordinary).status)
        val missingModule = ordinary.copy(filesByModule = mapOf("other" to setOf(file.path)))
        assertEquals(AuthoredUniverseStatus.UNKNOWN, AuthoredCallUniverse.examine(graph(), missingModule).status)
        val emptyGraph = CodeGraph(emptyList(), emptyList())
        assertEquals(AuthoredUniverseStatus.UNKNOWN, AuthoredCallUniverse.examine(emptyGraph, whole).status)
    }
    @Test fun `unknown generated method cannot be excluded from whole project`() {
        val old = graph()
        val generated = NodeId("method:Calls#bridge()V")
        val graph = CodeGraph(old.nodes.values + GraphNode(generated, "bridge", NodeKind.METHOD, jvmModifiers = setOf(JvmModifier.SYNTHETIC)), old.edges)
            .withCompilerAuthoredCalls(old.compilerAuthoredCallers).withCompilerCallerInventories(listOf(inventory().let {
                it.copy(methods = it.methods + CompilerInventoryMethod(generated, 0x1041, true, CompilerBackendOrigin.UNKNOWN, null, "UNOBSERVED"))
            }))
        assertEquals(AuthoredUniverseStatus.UNKNOWN, AuthoredCallUniverse.examine(graph, whole).status)
        assertEquals(AuthoredUniverseStatus.COMPLETE, AuthoredCallUniverse.examine(graph, ordinary).status)
    }
    @Test fun `changed coverage identity and projected outputs do not certify scoped negatives`() {
        val old = graph()
        val changed = old.withCompilerAuthoredCalls(old.compilerAuthoredCallers.map { it.copy(evidenceSha256 = "0".repeat(64)) })
        assertEquals(AuthoredUniverseStatus.UNKNOWN, AuthoredCallUniverse.examine(changed, ordinary).status)
        val missingBody = old.withCompilerCallerInventories(listOf(inventory().let { it.copy(bodies = it.bodies.map { body -> body.copy(bodyPresent = false) }) }))
        assertEquals(AuthoredUniverseStatus.UNKNOWN, AuthoredCallUniverse.examine(missingBody, ordinary).status)
        val unprovedOrigin = old.withCompilerCallerInventories(listOf(inventory().let { it.copy(bodies = it.bodies.map { body -> body.copy(compilerOrigin = "UNOBSERVED") }) }))
        assertEquals(AuthoredUniverseStatus.UNKNOWN, AuthoredCallUniverse.examine(unprovedOrigin, ordinary).status)
        val projected = CodeGraph(old.nodes.values.filter { it.id != constructor }, emptyList()).withCompilerAuthoredCalls(old.compilerAuthoredCallers)
            .withCompilerCallerInventories(old.compilerCallerInventories)
        assertEquals(AuthoredUniverseStatus.UNKNOWN, AuthoredCallUniverse.examine(projected, ordinary).status)
    }
    @Test fun `positive abstract no-body declarations are zero but unproved origins remain incomplete`() {
        val method = CompilerInventoryMethod(zero, 0x0401, false, CompilerBackendOrigin.SOURCE_BOUND, "b0", "JAVAC_EXPLICIT")
        val body = CompilerSourceBody("b0", file, 0, 20, CompilerSourceBodyKind.ABSTRACT_METHOD, false, "JAVAC_EXPLICIT", listOf(zero))
        val inventory = inventory().copy(bodies = listOf(body), methods = listOf(method), sourceBodyCounts = mapOf(file.path to 1, noClass.path to 0))
        val base = CodeGraph(listOf(GraphNode(NodeId("class:Calls"), "Calls", NodeKind.INTERFACE), GraphNode(zero, "zero", NodeKind.METHOD, jvmModifiers = setOf(JvmModifier.ABSTRACT))), emptyList())
        val result = AuthoredCallUniverse.examine(base.withCompilerCallerInventories(listOf(inventory)), whole)
        assertEquals(AuthoredUniverseStatus.COMPLETE, result.status)
        assertEquals(mapOf(zero to emptyList()), result.calls)
        for (unknown in listOf(body.copy(compilerOrigin = "UNOBSERVED"), body.copy(bodyPresent = true), body.copy(offsetUtf16 = null, endOffsetUtf16 = null))) {
            val graph = base.withCompilerCallerInventories(listOf(inventory.copy(bodies = listOf(unknown))))
            assertEquals(AuthoredUniverseStatus.INCOMPLETE, AuthoredCallUniverse.examine(graph, whole).status)
        }
    }
    @Test fun `abstract zero coverage cannot contradict represented graph modifiers`() {
        val method = CompilerInventoryMethod(zero, 0x0401, false, CompilerBackendOrigin.SOURCE_BOUND, "b0", "JAVAC_EXPLICIT")
        val body = CompilerSourceBody("b0", file, 0, 20, CompilerSourceBodyKind.ABSTRACT_METHOD, false, "JAVAC_EXPLICIT", listOf(zero))
        val inventory = inventory().copy(bodies = listOf(body), methods = listOf(method), sourceBodyCounts = mapOf(file.path to 1, noClass.path to 0))
        for (modifiers in listOf(emptySet(), setOf(JvmModifier.NATIVE))) {
            val graph = CodeGraph(listOf(GraphNode(NodeId("class:Calls"), "Calls", NodeKind.INTERFACE),
                GraphNode(zero, "zero", NodeKind.METHOD, jvmModifiers = modifiers)), emptyList()).withCompilerCallerInventories(listOf(inventory))
            assertEquals(AuthoredUniverseStatus.UNKNOWN, AuthoredCallUniverse.examine(graph, whole).status)
        }
    }
    @Test fun `whole project cannot omit a projected-away retained module inventory`() {
        val a = inventory().copy(bodies = emptyList(), methods = emptyList(), sourceBodyCounts = mapOf(file.path to 0, noClass.path to 0))
        val bFile = CompilerEvidenceSource("other/Empty.java", "1".repeat(64))
        val b = a.copy(scope = "other", outputRoot = "other-classes", sources = listOf(bFile),
            outputs = listOf(CompilerInventoryOutput("Empty.class", "2".repeat(64), "Empty")), sourceBodyCounts = mapOf(bFile.path to 0))
        val nodeA = GraphNode(NodeId("class:Calls"), "Calls", NodeKind.INTERFACE)
        val nodeB = GraphNode(NodeId("class:Empty"), "Empty", NodeKind.INTERFACE)
        val both = CodeGraph(listOf(nodeA, nodeB), emptyList()).withCompilerCallerInventories(listOf(a, b))
        val projected = CodeGraph(listOf(nodeA), emptyList()).withCompilerSourceFactsFrom(both)
        assertEquals(setOf("main", "other"), projected.compilerCallerInventories.map { it.scope }.toSet())
        assertFalse(projected.compilerCallerInventories.single { it.scope == "other" }.projectionComplete)
        assertEquals(AuthoredUniverseStatus.COMPLETE, AuthoredCallUniverse.examine(projected, ordinary).status)
        val omitted = AuthoredCallUniverse.examine(projected, whole)
        assertEquals(AuthoredUniverseStatus.UNKNOWN, omitted.status)
        assertTrue("whole-project-source-scope-omission" in omitted.limitations)
        val all = whole.copy(filesByModule = whole.filesByModule + ("other" to setOf(bFile.path)))
        assertEquals(AuthoredUniverseStatus.UNKNOWN, AuthoredCallUniverse.examine(projected, all).status)
    }
    @Test fun `mutated scope containers are revalidated before examining`() {
        for (change in 0..5) {
            val files = mutableSetOf(file.path, noClass.path)
            val modules = mutableMapOf("main" to files as Set<String>)
            val kinds = CompilerSourceBodyKind.entries.toMutableSet()
            val requested = AuthoredCallerScope(modules, kinds, wholeProject = true)
            when (change) {
                0 -> kinds.clear()
                1 -> modules.clear()
                2 -> files.clear()
                3 -> kinds.retainAll(setOf(CompilerSourceBodyKind.ORDINARY_METHOD))
                4 -> { files.clear(); files += "a".repeat(16_385) }
                5 -> { files.clear(); files += "../outside.java" }
            }
            assertFailsWith<IllegalArgumentException> { AuthoredCallUniverse.examine(graph(), requested) }
        }
    }
    @Test fun `grown scope size is rejected before source traversal`() {
        var oversized = false
        val files = object : AbstractSet<String>() {
            override val size: Int get() = if (oversized) 200_001 else 1
            override fun iterator(): Iterator<String> {
                check(!oversized) { "oversized request was traversed before checking bounds" }
                return setOf(file.path).iterator()
            }
        }
        val requested = AuthoredCallerScope(mapOf("main" to files), ordinary.bodyKinds)
        oversized = true
        assertFailsWith<IllegalArgumentException> { AuthoredCallUniverse.examine(graph(), requested) }
    }
    @Test fun `results own immutable deep scope snapshots independent of request collections`() {
        val files = mutableSetOf(file.path, noClass.path)
        val modules = mutableMapOf("main" to files as Set<String>)
        val kinds = mutableSetOf(CompilerSourceBodyKind.ORDINARY_METHOD)
        val requested = AuthoredCallerScope(modules, kinds)
        val result = AuthoredCallUniverse.examine(graph(), requested)
        assertEquals(AuthoredUniverseStatus.COMPLETE, result.status)
        files.clear(); modules.clear(); kinds.clear()
        assertEquals(ordinary, result.scope)
        assertFailsWith<UnsupportedOperationException> { (result.scope.filesByModule as MutableMap<String, Set<String>>).clear() }
        assertFailsWith<UnsupportedOperationException> { (result.scope.filesByModule.getValue("main") as MutableSet<String>).clear() }
        assertFailsWith<UnsupportedOperationException> { (result.scope.bodyKinds as MutableSet<CompilerSourceBodyKind>).clear() }
    }
    @Test fun `positive zero-method and no-class files can establish explicit empty scope`() {
        val inventory = inventory().copy(bodies = emptyList(), methods = emptyList(), sourceBodyCounts = mapOf(file.path to 0, noClass.path to 0))
        val graph = CodeGraph(listOf(GraphNode(NodeId("class:Calls"), "Calls", NodeKind.INTERFACE)), emptyList()).withCompilerCallerInventories(listOf(inventory))
        val result = AuthoredCallUniverse.examine(graph, whole)
        assertEquals(AuthoredUniverseStatus.COMPLETE, result.status)
        assertEquals(0, result.sourceBodies); assertTrue(result.callers.isEmpty())
        val omitted = whole.copy(filesByModule = mapOf("main" to setOf(file.path)))
        assertEquals(AuthoredUniverseStatus.UNKNOWN, AuthoredCallUniverse.examine(graph, omitted).status)
        assertFailsWith<IllegalArgumentException> { ordinary.copy(wholeProject = true) }
        assertFailsWith<IllegalArgumentException> { ordinary.copy(bodyKinds = emptySet()) }
    }

}
