package dev.kartograph.gradle

import dev.kartograph.core.CodeGraph
import dev.kartograph.core.CompilerEvidenceSource
import dev.kartograph.core.CompilerSourceCoordinateBasis
import dev.kartograph.core.EdgeKind
import dev.kartograph.core.GraphEdge
import dev.kartograph.core.GraphNode
import dev.kartograph.core.LocatedCompilerReference
import dev.kartograph.core.NodeId
import dev.kartograph.core.NodeKind
import dev.kartograph.core.SourceLocation
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import java.nio.file.Path

class SnapshotCompilerEvidenceRelocationTest {
    @Test
    fun `node path relocation preserves compiler source identity and captured empty state`() {
        val source = NodeId("method:demo/Caller#use()V")
        val target = NodeId("method:demo/Target#hit()V")
        val base = CodeGraph(
            listOf(
                GraphNode(source, "use", NodeKind.METHOD, location = SourceLocation("Caller.java")),
                GraphNode(target, "hit", NodeKind.METHOD, location = SourceLocation("Target.java")),
            ),
            listOf(GraphEdge(source, target, EdgeKind.CALL)),
        )
        val reference = LocatedCompilerReference(
            source, target, CompilerEvidenceSource("src/main/java/demo/Caller.java", "a".repeat(64)),
            "javac-constants", "17.0.20+8", CompilerSourceCoordinateBasis.JAVAC_UTF16_CHAR_SEQUENCE,
            10, 13, 2, 5,
        )
        val positioned = relocateSnapshotSourcePaths(base.withCompilerCallPositions(listOf(reference)),
            mapOf(source to "src/main/java/demo/Caller.java", target to "src/main/java/demo/Target.java"))
        assertEquals("src/main/java/demo/Caller.java", positioned.nodes.getValue(source).location?.path)
        assertEquals(listOf(reference), positioned.locatedCompilerReferences)
        assertTrue(positioned.compilerCallPositionsCaptured)

        val capturedEmpty = relocateSnapshotSourcePaths(base.withCompilerCallPositions(emptyList()),
            mapOf(source to "src/main/java/demo/Caller.java"))
        assertTrue(capturedEmpty.compilerCallPositionsCaptured)
        assertTrue(capturedEmpty.locatedCompilerReferences.isEmpty())
    }

    @Test
    fun `managed evidence directory is inferred only from compiler witness layout`() {
        assertEquals(Path.of("/work/build/kartograph/compiler-evidence/compileJava"),
            managedCompilerEvidenceDirectory(Path.of("/work/build/kartograph/witnesses/compileJava/witness.json")))
        assertNull(managedCompilerEvidenceDirectory(Path.of("/work/unrelated/compileJava/witness.json")))
        assertNull(managedCompilerEvidenceDirectory(Path.of("witness.json")))
        assertNull(managedCompilerEvidenceDirectory(Path.of("/work/build/kartograph/witnesses/compileJava/other.json")))
    }
}
