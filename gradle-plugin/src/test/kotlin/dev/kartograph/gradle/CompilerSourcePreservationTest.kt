package dev.kartograph.gradle

import dev.kartograph.core.*
import kotlin.test.*

class CompilerSourcePreservationTest {
    @Test fun `actual snapshot relocation preserves captured compiler facts`() {
        val original = graph()
        val relocated = relocateSnapshotSourcePaths(original, original.nodes.keys.associateWith { "src/main/java/Calls.java" })
        assertEquals(listOf(1, 1), listOf(relocated.compilerAuthoredCallers.size, relocated.compilerCallerInventories.size))
        assertEquals(original.compilerAuthoredCallers, relocated.compilerAuthoredCallers)
        assertEquals(original.compilerCallerInventories, relocated.compilerCallerInventories)
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
