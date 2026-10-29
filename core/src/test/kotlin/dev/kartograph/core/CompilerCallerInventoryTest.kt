package dev.kartograph.core

import kotlin.test.*

class CompilerCallerInventoryTest {
    private val zero = NodeId("method:Calls#zero()V")
    private val constructor = NodeId("method:Calls#<init>()V")
    private val file = CompilerEvidenceSource("src/Calls.java", "a".repeat(64))
    private fun inventory() = CompilerCallerInventory("main", "compileJava", "classes", listOf(file), listOf(
        CompilerSourceBody("b0", file, 0, 20, CompilerSourceBodyKind.ORDINARY_METHOD, true, "JAVAC_EXPLICIT", listOf(zero)),
        CompilerSourceBody("b1", file, null, null, CompilerSourceBodyKind.IMPLICIT_CONSTRUCTOR, true, "JAVAC_MANDATED", listOf(constructor)),
    ), listOf(CompilerInventoryOutput("Calls.class", "b".repeat(64), "Calls")), listOf(
        CompilerInventoryMethod(zero, 1, true, CompilerBackendOrigin.SOURCE_BOUND, "b0", "JAVAC_EXPLICIT"),
        CompilerInventoryMethod(constructor, 1, true, CompilerBackendOrigin.SOURCE_BOUND, "b1", "JAVAC_MANDATED"),
    ), CompilerSourceCoordinateBasis.JAVAC_UTF16_CHAR_SEQUENCE, "17.0.20", "c".repeat(64), "d".repeat(64), "e".repeat(64), "f".repeat(64))
    private fun graph() = CodeGraph(listOf(GraphNode(NodeId("class:Calls"), "Calls", NodeKind.CLASS), GraphNode(zero, "zero", NodeKind.METHOD), GraphNode(constructor, "Calls", NodeKind.CONSTRUCTOR)), emptyList())

    @Test fun `no-call methods and unsupported constructors survive independent inventory`() {
        val value = inventory()
        val graph = graph().withCompilerCallerInventories(listOf(value))
        assertEquals(value.copy(methods = value.methods.sortedBy { it.id }), graph.compilerCallerInventories.single())
        assertEquals(2, graph.compilerCallerInventories.single().methods.size)
        assertEquals(emptyList(), graph.edges)
        assertEquals(graph.compilerCallerInventories, graph.enrichedWith(emptyList()).compilerCallerInventories)
        assertEquals(graph.compilerCallerInventories, graph.withCompilerAuthoredCalls(emptyList()).compilerCallerInventories)
        assertEquals(graph.compilerCallerInventories, graph.withCompilerCallPositions(emptyList()).compilerCallerInventories)
    }
    @Test fun `projection retains missing identities and invalidates completeness`() {
        val projected = CodeGraph(listOf(GraphNode(zero, "zero", NodeKind.METHOD)), emptyList()).withCompilerCallerInventories(listOf(inventory()))
        val row = projected.compilerCallerInventories.single()
        assertFalse(row.projectionComplete)
        assertTrue(row.methods.any { it.id == constructor })
        val stub = CodeGraph(listOf(GraphNode(zero, "zero", NodeKind.METHOD, attributes = setOf(NodeAttribute.EXTERNAL_STUB))), emptyList())
        assertFalse(stub.withCompilerCallerInventories(listOf(inventory())).compilerCallerInventories.single().projectionComplete)
    }
    @Test fun `constructor and ordinary method identities cannot exchange node kinds`() {
        val original = graph()
        for ((id, kind) in listOf(zero to NodeKind.CONSTRUCTOR, constructor to NodeKind.METHOD)) {
            val changed = CodeGraph(original.nodes.values.map { if (it.id == id) it.copy(kind = kind) else it }, emptyList())
                .withCompilerCallerInventories(listOf(inventory()))
            assertFalse(changed.compilerCallerInventories.single().projectionComplete)
            assertEquals(inventory().methods.map { it.id }.toSet(), changed.compilerCallerInventories.single().methods.map { it.id }.toSet())
        }
    }
    @Test fun `represented JVM method flags must agree with backend access and code`() {
        for ((access, modifiers) in listOf(0x0401 to emptySet(), 0x0101 to emptySet(),
            1 to setOf(JvmModifier.ABSTRACT), 1 to setOf(JvmModifier.NATIVE), 9 to emptySet(), 0x0011 to emptySet(), 0x1001 to emptySet())) {
            val inventory = inventory().let { row -> row.copy(methods = row.methods.map { if (it.id == zero)
                it.copy(access = access, hasCode = access and (0x0400 or 0x0100) == 0) else it }) }
            val original = graph()
            val graph = CodeGraph(original.nodes.values.map { if (it.id == zero) it.copy(jvmModifiers = modifiers) else it }, emptyList())
                .withCompilerCallerInventories(listOf(inventory))
            assertFalse(graph.compilerCallerInventories.single().projectionComplete)
        }
    }
    @Test fun `omitted backend method and conflicting source association are rejected`() {
        val row = inventory()
        assertFailsWith<IllegalArgumentException> { row.copy(methods = row.methods.take(1)) }
        assertFailsWith<IllegalArgumentException> { row.copy(methods = row.methods.map { it.copy(sourceBody = "b0") }) }
        assertFailsWith<IllegalArgumentException> { row.copy(outputs = emptyList()) }
        assertFailsWith<IllegalArgumentException> { row.copy(sources = emptyList()) }
        assertFailsWith<IllegalArgumentException> { row.copy(bodies = emptyList(), methods = emptyList(), outputs = emptyList(), sourceBodyCounts = mapOf(file.path to 0)) }
        assertFailsWith<IllegalArgumentException> { row.copy(compilerVersion = "17.invalid version") }
        assertFailsWith<IllegalArgumentException> { graph().withCompilerCallerInventories(listOf(row, row)) }
        assertFailsWith<IllegalArgumentException> { CompilerInventoryMethod(NodeId("method:Calls#<init>()I"), 1, true, CompilerBackendOrigin.UNKNOWN, null, "UNKNOWN") }
    }
    @Test fun `zero-method classes require selected project ownership and cannot be shadowed`() {
        val base = inventory()
        val empty = base.copy(bodies = emptyList(), methods = emptyList(), sourceBodyCounts = mapOf(file.path to 0))
        val missing = CodeGraph(emptyList(), emptyList()).withCompilerCallerInventories(listOf(empty))
        assertFalse(missing.compilerCallerInventories.single().projectionComplete)
        val present = CodeGraph(listOf(GraphNode(NodeId("class:Calls"), "Calls", NodeKind.INTERFACE)), emptyList())
        assertTrue(present.withCompilerCallerInventories(listOf(empty)).compilerCallerInventories.single().projectionComplete)
        val stub = CodeGraph(listOf(GraphNode(NodeId("class:Calls"), "Calls", NodeKind.INTERFACE, attributes = setOf(NodeAttribute.EXTERNAL_STUB))), emptyList())
        assertFalse(stub.withCompilerCallerInventories(listOf(empty)).compilerCallerInventories.single().projectionComplete)
        assertFailsWith<IllegalArgumentException> {
            present.withCompilerCallerInventories(listOf(empty, empty.copy(scope = "other", artifact = "other", outputRoot = "other")))
        }
    }

}
