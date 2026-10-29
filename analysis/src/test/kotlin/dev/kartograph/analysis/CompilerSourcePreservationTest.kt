package dev.kartograph.analysis

import dev.kartograph.core.*
import kotlin.test.*

class CompilerSourcePreservationTest {
    @Test fun `test scope keeps zero-call coverage and removed zero-method class inventory`() {
        val old = graph()
        val test = CompilerEvidenceSource("src/test/java/Test.java", "b".repeat(64))
        val mixed = CodeGraph(old.nodes.values + GraphNode(NodeId("class:Test"), "Test", NodeKind.INTERFACE, location = SourceLocation(test.path)), old.edges)
            .withCompilerAuthoredCalls(old.compilerAuthoredCallers).withCompilerCallerInventories(old.compilerCallerInventories.map {
                it.copy(sources = it.sources + test, outputs = it.outputs + CompilerInventoryOutput("Test.class", "b".repeat(64), "Test"),
                    sourceBodyCounts = it.sourceBodyCounts + (test.path to 0))
            })
        val selected = TestSourceScope.select(mixed, emptyList(), false).graph
        assertEquals(listOf(1, 1), listOf(selected.compilerAuthoredCallers.size, selected.compilerCallerInventories.size))
        val inventory = selected.compilerCallerInventories.single()
        assertFalse(inventory.projectionComplete)
        assertTrue(inventory.outputs.any { it.owner == "Test" })
        val scope = AuthoredCallerScope(mapOf("main" to inventory.sources.map { it.path }.toSet()), setOf(CompilerSourceBodyKind.ORDINARY_METHOD))
        assertEquals(AuthoredUniverseStatus.UNKNOWN, AuthoredCallUniverse.examine(selected, scope).status)
    }
    @Test fun `architecture aggregation never receives source completion assertions`() {
        val aggregated = ArchitectureGraph.aggregate(graph())
        assertTrue(aggregated.compilerAuthoredCallers.isEmpty())
        assertTrue(aggregated.compilerCallerInventories.isEmpty())
        val scope = AuthoredCallerScope(mapOf("main" to setOf("src/main/java/Calls.java")), setOf(CompilerSourceBodyKind.ORDINARY_METHOD))
        assertEquals(AuthoredUniverseStatus.UNKNOWN, AuthoredCallUniverse.examine(aggregated, scope).status)
    }
    private fun graph(path: String = "src/main/java/Calls.java", hash: String = "a".repeat(64)): CodeGraph {
        val file = CompilerEvidenceSource(path, hash)
        val id = NodeId("method:Calls#zero()V")
        val original = CodeGraph(listOf(GraphNode(NodeId("class:Calls"), "Calls", NodeKind.CLASS, location = SourceLocation(path)),
            GraphNode(id, "zero", NodeKind.METHOD, location = SourceLocation(path), jvmSignature = "()V", jvmModifiers = setOf(JvmModifier.STATIC))), emptyList())
        val caller = CompilerAuthoredCaller(id, file, 0, 20, CompilerSourceCoordinateBasis.JAVAC_UTF16_CHAR_SEQUENCE, "17.0.20", 1, 0,
            AuthoredCallCoverage.COMPLETE, emptyList(), emptyList(), "c".repeat(64), "d".repeat(64), "f".repeat(64))
        val inventory = CompilerCallerInventory("main", "compileJava", "classes", listOf(file),
            listOf(CompilerSourceBody("b0", file, 0, 20, CompilerSourceBodyKind.ORDINARY_METHOD, true, "JAVAC_EXPLICIT", listOf(id))),
            listOf(CompilerInventoryOutput("Calls.class", "b".repeat(64), "Calls")),
            listOf(CompilerInventoryMethod(id, 9, true, CompilerBackendOrigin.SOURCE_BOUND, "b0", "JAVAC_EXPLICIT")),
            CompilerSourceCoordinateBasis.JAVAC_UTF16_CHAR_SEQUENCE, "17.0.20", "c".repeat(64), "d".repeat(64), "e".repeat(64), "f".repeat(64))
        return original.withCompilerAuthoredCalls(listOf(caller)).withCompilerCallerInventories(listOf(inventory))
    }
}
