package dev.kartograph.cli

import dev.kartograph.core.BuildWitness
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
import dev.kartograph.core.GraphNode
import dev.kartograph.core.InputFingerprint
import dev.kartograph.core.NodeId
import dev.kartograph.core.NodeKind
import dev.kartograph.core.SnapshotProvenance
import dev.kartograph.export.QuerySnapshot
import dev.kartograph.export.QuerySnapshotCodec
import dev.kartograph.index.CompilerCallPositionOptions
import dev.kartograph.index.CompilerEvidenceToken
import java.io.ByteArrayOutputStream
import java.io.PrintStream
import java.nio.file.Files
import java.nio.file.Path
import org.junit.jupiter.api.io.TempDir
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFalse

class CompilerSourceUseMergeTest {
    @Test
    fun `merge refuses populated empty and mixed source evidence before creating outputs`(@TempDir directory: Path) {
        for (empty in listOf(false, true)) for (otherCaptured in listOf(false, true)) for (compact in listOf(false, true)) {
            val root = Files.createDirectories(directory.resolve("$empty-$otherCaptured-$compact"))
            val first = Files.createDirectories(root.resolve("first"))
            val second = Files.createDirectories(root.resolve("second"))
            val firstFile = Files.writeString(first.resolve("snapshot.json"), QuerySnapshotCodec.render(snapshot(":first:jvm", empty), compact))
            val other = if (otherCaptured) snapshot(":second:jvm", empty) else
                QuerySnapshot(CodeGraph(emptyList(), emptyList()), emptyList(), emptyList(), scope = ":second:jvm",
                    provenance = SnapshotProvenance(emptyList(), emptyList()))
            val secondFile = Files.writeString(second.resolve("snapshot.json"), QuerySnapshotCodec.render(other, compact))
            val bindings = root.resolve("not-created/bindings.json")
            val stdout = ByteArrayOutputStream()
            val stderr = ByteArrayOutputStream()
            val status = KartographCli.run(arrayOf("snapshot", "merge", "--project", root.toString(),
                "--module", "first", "--graph-file", firstFile.toString(),
                "--module", "second", "--graph-file", secondFile.toString(),
                "--input-bindings-output", bindings.toString()), PrintStream(stdout), PrintStream(stderr))
            assertEquals(2, status, stderr.toString(Charsets.UTF_8))
            assertContains(stderr.toString(Charsets.UTF_8), "captured compiler source uses")
            assertEquals("", stdout.toString(Charsets.UTF_8))
            assertFalse(Files.exists(bindings))
            assertFalse(Files.exists(bindings.parent))
        }
    }

    /** 파일 currentness를 주장하지 않는 합성 snapshot이다. merge가 검증/재인덱싱 전에 거부해야 한다. */
    private fun snapshot(scope: String, empty: Boolean): QuerySnapshot {
        val source = NodeId("method:demo/Probe#caller()I")
        val target = NodeId("method:demo/Probe#target()I")
        val classes = InputFingerprint("classes", "build/classes", "a".repeat(64))
        val artifact = "b".repeat(64)
        val receipt = InputFingerprint("compilerEvidence", "build/evidence.tsv", "c".repeat(64))
        val inputs = listOf(InputFingerprint("sources", "src/Probe.java", "d".repeat(64)),
            InputFingerprint("buildConfig", "build.gradle", "e".repeat(64)),
            InputFingerprint("compiler", "compiler/javac", "f".repeat(64)),
            InputFingerprint("processor", "compiler/collector.jar", artifact), CompilerCallPositionOptions.enabledInput())
        val token = CompilerEvidenceToken.create(scope, "javac", ":compileJava", inputs)
        val witness = BuildWitness(scope, "javac", ":compileJava", inputs, listOf(classes), listOf(receipt), token)
        val root = CompilerSourceUseRoot(0, classes, listOf(source, target))
        val count = if (empty) 0 else 1
        val unit = CompilerSourceUseUnit(receipt, "javac-constants", "17.0.20+8", artifact, token,
            listOf(CompilerSourceUseClaim(scope, "javac", ":compileJava", listOf(classes))),
            CompilerSourceUseProducerCounts(count, count, 0, 0), CompilerSourceUseImportCounts(count, 0, 0, 0, 0, 0), 0)
        val uses = if (empty) emptyList() else listOf(CompilerSourceUse(unit.id, source, target, root.id, root.id,
            CompilerSourceUseKind.INVOCATION_SELECTION, CompilerEvidenceSource("src/Probe.java", "d".repeat(64)),
            "javac-constants", "17.0.20+8", CompilerSourceCoordinateBasis.JAVAC_UTF16_CHAR_SEQUENCE, 10, 16, 1, 11, false))
        val graph = CodeGraph(listOf(GraphNode(source, "caller", NodeKind.METHOD), GraphNode(target, "target", NodeKind.METHOD)),
            emptyList()).withCompilerSourceUses(CompilerSourceUseEvidence(listOf(root), listOf(unit), uses))
        return QuerySnapshot(graph, emptyList(), emptyList(), scope = scope,
            provenance = SnapshotProvenance(listOf(classes, receipt), listOf(witness)))
    }
}
