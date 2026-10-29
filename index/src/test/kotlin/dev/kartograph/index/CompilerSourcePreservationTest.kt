package dev.kartograph.index

import dev.kartograph.core.*
import kotlin.test.*
import java.nio.file.Files
import java.nio.file.Path
import org.junit.jupiter.api.io.TempDir

class CompilerSourcePreservationTest {
    @Test fun `actual source header enrichment preserves compiler facts`(@TempDir root: Path) {
        val file = Files.createDirectories(root.resolve("src/main/java")).resolve("Calls.java")
        Files.writeString(file, "public class Calls { public static void zero() {} }")
        val original = graph(hash = CompilerEvidenceIndexer.sourceHash(file))
        val enriched = SourceDeclarationLocations.enrich(original, root, original.nodes.keys.associateWith { "src/main/java/Calls.java" })
        assertEquals(listOf(1, 1), listOf(enriched.compilerAuthoredCallers.size, enriched.compilerCallerInventories.size))
        assertEquals(original.compilerAuthoredCallers, enriched.compilerAuthoredCallers)
        assertEquals(original.compilerCallerInventories, enriched.compilerCallerInventories)
        assertTrue(enriched.nodes.values.any { it.sourceDeclaration != null })
    }
    @Test fun `observed changed source hash cannot retain compiler completion`(@TempDir root: Path) {
        val file = Files.createDirectories(root.resolve("src/main/java")).resolve("Calls.java")
        Files.writeString(file, "public class Calls { public static void zero() {} }")
        val original = graph(hash = CompilerEvidenceIndexer.sourceHash(file))
        Files.writeString(file, Files.readString(file) + "\n// source-only edit\n")
        val changed = SourceDeclarationLocations.enrich(original, root, original.nodes.keys.associateWith { "src/main/java/Calls.java" })
        assertTrue(changed.compilerAuthoredCallers.isEmpty())
        assertFalse(changed.compilerCallerInventories.single().projectionComplete)
        assertEquals(original.compilerCallerInventories.single().sources, changed.compilerCallerInventories.single().sources)
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
