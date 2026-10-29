package dev.kartograph.gradle

import dev.kartograph.core.CodeGraph
import dev.kartograph.core.CompilerEvidenceSource
import dev.kartograph.core.CompilerSourceCoordinateBasis
import dev.kartograph.core.CompilerSourceUse
import dev.kartograph.core.CompilerSourceUseClaim
import dev.kartograph.core.CompilerSourceUseEvidence
import dev.kartograph.core.CompilerSourceUseImportCounts
import dev.kartograph.core.CompilerSourceUseKind
import dev.kartograph.core.CompilerSourceUseProducerCounts
import dev.kartograph.core.CompilerSourceUseRoot
import dev.kartograph.core.CompilerSourceUseUnit
import dev.kartograph.core.EdgeKind
import dev.kartograph.core.GraphEdge
import dev.kartograph.core.GraphNode
import dev.kartograph.core.InputFingerprint
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

    @Test
    fun `relocation preserves populated and captured empty compiler source observations`() {
        val source = NodeId("method:demo/Caller#use()V")
        val target = NodeId("method:demo/Target#hit()V")
        val base = CodeGraph(listOf(
            GraphNode(source, "use", NodeKind.METHOD, location = SourceLocation("Caller.java")),
            GraphNode(target, "hit", NodeKind.METHOD, location = SourceLocation("Target.java")),
        ), emptyList())
        val input = InputFingerprint("classes", "build/classes", "a".repeat(64))
        val root = CompilerSourceUseRoot(0, input, listOf(source, target))
        for (count in listOf(0, 1)) {
            val unit = CompilerSourceUseUnit(InputFingerprint("compilerEvidence", "build/evidence.tsv", "b".repeat(64)),
                "javac-constants", "17.0.20+8", "c".repeat(64), "d".repeat(64),
                listOf(CompilerSourceUseClaim(":sample:jvm", "javac", ":compileJava", listOf(input))),
                CompilerSourceUseProducerCounts(count, count, 0, 0), CompilerSourceUseImportCounts(count, 0, 0, 0, 0, 0), 0)
            val uses = if (count == 0) emptyList() else listOf(CompilerSourceUse(unit.id, source, target, root.id, root.id,
                CompilerSourceUseKind.INVOCATION_SELECTION, CompilerEvidenceSource("src/main/java/demo/Caller.java", "e".repeat(64)),
                "javac-constants", "17.0.20+8", CompilerSourceCoordinateBasis.JAVAC_UTF16_CHAR_SEQUENCE, 10, 13, 2, 5, false))
            val evidence = CompilerSourceUseEvidence(listOf(root), listOf(unit), uses)
            val relocated = relocateSnapshotSourcePaths(base.withCompilerSourceUses(evidence),
                mapOf(source to "relocated/Caller.java", target to "relocated/Target.java"))
            assertEquals("relocated/Caller.java", relocated.nodes.getValue(source).location?.path)
            assertEquals(evidence, relocated.compilerSourceUseEvidence)
            assertEquals(base.edges, relocated.edges)
        }
    }
}
