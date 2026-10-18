package dev.kartograph.gradle

import dev.kartograph.core.*
import dev.kartograph.export.QuerySnapshot
import dev.kartograph.export.QuerySnapshotCodec
import dev.kartograph.export.QuerySnapshotSizeException
import dev.kartograph.index.ClassFileIndexer
import java.nio.file.Path
import java.nio.file.Files
import javax.tools.ToolProvider
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertNotNull
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import org.junit.jupiter.api.io.TempDir

class AndroidResourceSnapshotEvidenceTest {
    @Test
    fun `external declared roots leave ordinary snapshot available with a fixed limitation`(@TempDir temporary: Path) {
        val project = temporary.toRealPath()
        val indexed = compiledFixture(project)
        val result = AndroidResourceSnapshotEvidence.capture(project, ":app:debug", "dev.example",
            listOf(project.parent.resolve("outside")), ":app:processDebugResources",
            SnapshotProvenance(emptyList(), emptyList()), indexed, CodeGraph(emptyList(), emptyList()))
        assertNull(result.facts)
        assertNull(result.bindings)
        assertEquals(listOf("android-resource-bindings-unavailable: external-or-project-root"), result.limitations)
    }

    @Test
    fun `missing producer keeps facts and reports refusal instead of empty impact success`(@TempDir temporary: Path) {
        val project = temporary.toRealPath()
        val indexed = compiledFixture(project)
        val result = AndroidResourceSnapshotEvidence.capture(project, ":app:debug", "dev.example",
            listOf(project.resolve("src/main/res")), ":app:processDebugResources",
            SnapshotProvenance(emptyList(), emptyList()), indexed, indexed.graph)
        assertNull(result.bindings)
        if (result.facts == null) {
            // descriptor provider가 없는 플랫폼은 명시적으로 미지원이며 일반 snapshot을 막지 않는다.
            assertEquals(listOf("android-resource-bindings-unavailable: capture-unsupported_provider"), result.limitations)
        } else {
            assertEquals(1, result.facts.roots.size)
            assertEquals(listOf("android-resource-bindings-unavailable: admission-producer_unavailable"), result.limitations)
        }
    }
    @Test
    fun `optional records fit exactly or fall back without hiding ordinary overflow`() {
        val root = AndroidResourceRoot(InputFingerprint("android-resources", "src/main/res", "1".repeat(64)))
        val facts = AndroidResourceEvidence(":app:debug", "dev.example", listOf(root), (0 until 20).map { index ->
            AndroidLayoutDefinition(AndroidResourceKey("dev.example", "layout", "screen$index"), root.id,
                CompilerEvidenceSource("src/main/res/layout/screen$index.xml", "2".repeat(64)), "layout/screen$index.xml")
        }, emptyList())
        val options = InputFingerprint("options", "app-processDebugResources-options",
            AndroidResourceBindingIdentity.namespaceOptions("dev.example"))
        val input = listOf("sources", "buildConfig", "compiler").map {
            InputFingerprint(it, it, "3".repeat(64))
        } + root.input + options
        val witness = BuildWitness(":app:debug", "agp-process-resources", ":app:processDebugResources",
            input, listOf(InputFingerprint("classes", "build/R.jar", "4".repeat(64))))
        val owner = NodeId("class:dev/example/R\$layout")
        val outcomes = facts.definitions.map {
            AndroidResourceFieldOutcome(it.key, field = NodeId("field:dev/example/R\$layout#" + it.key.name + ":I"))
        }
        val graph = CodeGraph(listOf(GraphNode(owner, "R.layout", NodeKind.CLASS)) + outcomes.map {
            GraphNode(requireNotNull(it.field), it.key.name, NodeKind.FIELD,
                jvmSignature = requireNotNull(it.field).value.removePrefix("field:"), jvmModifiers = setOf(JvmModifier.STATIC))
        }, outcomes.map { GraphEdge(owner, requireNotNull(it.field), EdgeKind.MEMBER) })
        val binding = AndroidResourceBindingEvidence(":app:debug", "dev.example", AndroidResourceBindingIdentity.facts(facts),
            witness.artifact, AndroidResourceBindingIdentity.producer(witness), options, witness.outputs.single(), listOf(root.input), outcomes)
        val snapshot = QuerySnapshot(graph, emptyList(),
            listOf("android-resource-bindings-captured: fixture"), scope = ":app:debug",
            provenance = SnapshotProvenance(witness.outputs, listOf(witness)), androidResourceEvidence = facts, androidResourceBindings = binding)
        val full = QuerySnapshotCodec.render(snapshot, compact = true)
        val exact = AndroidResourceSnapshotEvidence.render(snapshot, full.toByteArray(Charsets.UTF_8).size)
        assertEquals(full, exact)
        assertNotNull(QuerySnapshotCodec.parse(exact).androidResourceEvidence)
        assertNotNull(QuerySnapshotCodec.parse(exact).androidResourceBindings)
        val fallback = QuerySnapshotCodec.parse(AndroidResourceSnapshotEvidence.render(snapshot, full.toByteArray(Charsets.UTF_8).size - 1))
        assertNull(fallback.androidResourceEvidence)
        assertNull(fallback.androidResourceBindings)
        assertTrue("android-resource-bindings-unavailable: snapshot-size-limit" in fallback.limitations)
        assertTrue(ResourceProcessWitnesses.INVENTORY_UNVERIFIED in fallback.limitations)
        assertTrue(fallback.limitations.none { it.startsWith("android-resource-bindings-captured:") })
        assertEquals(snapshot.graph.nodeIds, fallback.graph.nodeIds)
        assertEquals(snapshot.provenance, fallback.provenance)
        assertFailsWith<QuerySnapshotSizeException> { AndroidResourceSnapshotEvidence.render(snapshot, 1) }
        assertFailsWith<QuerySnapshotSizeException> {
            AndroidResourceSnapshotEvidence.render(snapshot.copy(androidResourceEvidence = null, androidResourceBindings = null), 1)
        }
    }

    private fun compiledFixture(project: Path): dev.kartograph.index.IndexedClasses {
        val source = project.resolve("CaptureFixture.java")
        Files.writeString(source, "public class CaptureFixture {}")
        val classes = Files.createDirectories(project.resolve("classes"))
        assertEquals(0, requireNotNull(ToolProvider.getSystemJavaCompiler()).run(null, null, null,
            "-d", classes.toString(), source.toString()))
        return ClassFileIndexer().indexWithObservations(listOf(classes))
    }
}
