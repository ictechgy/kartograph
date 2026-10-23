package dev.kartograph.cli

import dev.kartograph.core.CodeGraph
import dev.kartograph.core.InputFingerprint
import dev.kartograph.core.NodeId
import dev.kartograph.core.RetentionEvidence
import dev.kartograph.core.RetentionReason
import dev.kartograph.core.SnapshotProvenance
import dev.kartograph.core.SourceLocation
import dev.kartograph.export.QuerySnapshot
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals

/** 구성원 경로·슬롯·보존 근거·한계를 aggregate project 기준으로 옮기는 규칙을 고정한다. */
class SnapshotAggregationTest {
    @Test
    fun `transformation observations keep member scope and separately rebase original and selected artifacts`() {
        val member = member("feature", 2, ":feature:debug", emptyList())
        val original = InputFingerprint("classes", "external/raw", "a".repeat(64))
        val selected = InputFingerprint("classes", "build/transformed", "b".repeat(64))
        val observed = dev.kartograph.core.ClassTransformObservation(":feature:debug", ":feature:kartographSnapshotDebug",
            "hilt-superclass-v1", listOf(original), listOf(selected),
            listOf(dev.kartograph.core.TransformedClass("p/App", "a".repeat(64), "b".repeat(64))))
        val prepared = member.copy(snapshot = member.snapshot.copy(provenance = member.snapshot.provenance!!.copy(
            transformations = listOf(observed))), bindings = mapOf("external/raw" to Path.of("/cache/raw")))
        val result = SnapshotAggregation.provenance(listOf(prepared)).transformations.single()
        assertEquals(observed.scope, result.scope)
        assertEquals(observed.observerTask, result.observerTask)
        assertEquals(listOf("external/member-2/raw"), result.originalOutputs.map { it.path })
        assertEquals(listOf("feature/build/transformed"), result.selectedOutputs.map { it.path })
        assertEquals(observed.changedClasses, result.changedClasses)
        assertEquals(mapOf("external/member-2/raw" to Path.of("/cache/raw")), SnapshotAggregation.bindings(listOf(prepared)))
    }

    private fun member(prefix: String, index: Int, scope: String, limitations: List<String>): AggregateMember {
        val provenance = SnapshotProvenance(listOf(
            InputFingerprint("classes", "build/classes", "a".repeat(64)),
            InputFingerprint("classpath", "external/classpath-1", "b".repeat(64)),
        ), emptyList())
        val retention = listOf(
            RetentionEvidence(NodeId("class:p/Main"), RetentionReason.MANIFEST_COMPONENT, SourceLocation("build/intermediates/AndroidManifest.xml", 3)),
            RetentionEvidence(NodeId("class:p/Kept"), RetentionReason.KEEP_RULE, SourceLocation("external/rules/proguard.pro", 1)),
        )
        val snapshot = QuerySnapshot(CodeGraph(emptyList(), emptyList()), retention, limitations, scope = scope, provenance = provenance)
        return AggregateMember(Path.of("/work", prefix), prefix, "member-$index", snapshot,
            mapOf("external/classpath-1" to Path.of("/cache/$index.jar")))
    }

    @Test
    fun `members rebase project paths and namespace external slots`() {
        val root = member("", 1, "::debug", listOf("inlined-constant-references: 1"))
        val feature = member("feature/jobs", 2, ":feature:jobs:debug", listOf("missing-generated-keep-files: 1"))
        val provenance = SnapshotAggregation.provenance(listOf(root, feature))
        assertEquals(listOf("build/classes", "external/member-1/classpath-1", "feature/jobs/build/classes", "external/member-2/classpath-1"),
            provenance.inputs.map { it.path })
        assertEquals(listOf("::debug", ":feature:jobs:debug"), provenance.memberScopes)
        assertEquals(mapOf("external/member-1/classpath-1" to Path.of("/cache/1.jar"), "external/member-2/classpath-1" to Path.of("/cache/2.jar")),
            SnapshotAggregation.bindings(listOf(root, feature)))
        val paths = SnapshotAggregation.retention(listOf(feature)).map { it.location!!.path }
        assertEquals(listOf("feature/jobs/build/intermediates/AndroidManifest.xml", "external/rules/proguard.pro"), paths)
        assertEquals(listOf("inlined-constant-references: 9", ":feature:jobs:debug missing-generated-keep-files: 1", SnapshotAggregation.RETENTION_LIMITATION),
            SnapshotAggregation.limitations(listOf(root, feature), listOf("inlined-constant-references: 9")))
    }
    @Test
    fun `project pruning and rebase cannot erase or rewrite external document context`() {
        val parsed = dev.kartograph.export.ExternalRetentionCodec.parseDocument("""{
            "format":"external-retentions","version":0,"producedBy":{"name":"isthmus","version":"test"},
            "generatedAt":"2026-10-12T12:00:00Z","retentions":[{"symbol":{"qualifiedName":"Main","usr":"class:p/Main"},
            "reason":"bridge","evidence":{"channel":"x","caller":{"platform":"dart","path":"lib/main.dart","line":2},
            "callers":[{"platform":"dart","path":"lib/main.dart","line":2},{"platform":"js","path":"web/main.js","line":8}]}}],
            "provenance":{"version":2,"project":"/original/other-language","opaque":{"origin":"future"}}}""")
        val original = member("feature/jobs", 2, ":feature:jobs:debug", emptyList())
        val live = original.copy(snapshot = original.snapshot.copy(retention = parsed.retentions,
            externalRetentionContexts = listOf(parsed.context)))
        assertEquals("lib/main.dart", SnapshotAggregation.retention(listOf(live)).single().location!!.path)
        assertEquals(parsed.retentions, SnapshotAggregation.retention(listOf(live)))
        val graph = CodeGraph(listOf(dev.kartograph.core.GraphNode(NodeId("class:p/Main"), "p.Main", dev.kartograph.core.NodeKind.CLASS)), emptyList())
        for (compact in listOf(false, true)) {
            val saved = live.snapshot.copy(graph = graph, retention = SnapshotAggregation.retention(listOf(live)))
            val restored = dev.kartograph.export.QuerySnapshotCodec.parse(dev.kartograph.export.QuerySnapshotCodec.render(saved, compact))
            assertEquals(parsed.retentions, restored.retention)
            assertEquals(listOf("lib/main.dart", "web/main.js"), restored.retention.single().externalBridge!!.callers.map { it.path })
        }
        val pruned = live.copy(snapshot = live.snapshot.copy(retention = emptyList()))
        val contexts = SnapshotAggregation.externalRetentionContexts(listOf(live, pruned))
        assertEquals(listOf(parsed.context), contexts)
        val merged = pruned.snapshot.copy(externalRetentionContexts = contexts)
        for (compact in listOf(false, true)) {
            val restored = dev.kartograph.export.QuerySnapshotCodec.parse(dev.kartograph.export.QuerySnapshotCodec.render(merged, compact))
            assertEquals(parsed.context.canonicalDocument, restored.externalRetentionContexts.single().canonicalDocument)
            assertEquals(pruned.snapshot.provenance, restored.provenance)
        }
    }

    @Test
    fun `aggregation preserves known array order and multiplicity independently of root pruning`() {
        val source = """{"format":"bridge-facts","version":2,"transport":"event-channel","tool":{"name":"z","version":"1"},
            "platform":"kotlin","project":"/original/source","target":null,"generatedAt":"2024-02-29t00:00:00.123456789012z"}"""
        val limitation = """{"platform":"kotlin","target":null,"tool":"z","message":"capture limitation"}"""
        val sourceA = source.replace("\"name\":\"z\"", "\"name\":\"a\"")
        val limitationA = limitation.replace("\"tool\":\"z\"", "\"tool\":\"a\"")
        val contexts = listOf(
            """{"version":1,"sources":[$source,$sourceA,$source],"limitations":[$limitation,$limitationA,$limitation]}""",
            """{"version":1,"sources":[$sourceA,$source,$source],"limitations":[$limitationA,$limitation,$limitation]}""",
        ).map { provenance -> dev.kartograph.export.ExternalRetentionCodec.parseDocument("""{
            "format":"external-retentions","version":0,"producedBy":{"name":"isthmus","version":"test"},
            "generatedAt":"2026-10-14T00:00:00Z","retentions":[],"provenance":$provenance}""").context }
        assertEquals(2, contexts.toSet().size)
        val original = member("feature/jobs", 2, ":feature:jobs:debug", emptyList())
        for (compact in listOf(false, true)) {
            val members = contexts.map { context ->
                val captured = original.snapshot.copy(retention = emptyList(), externalRetentionContexts = listOf(context))
                original.copy(snapshot = dev.kartograph.export.QuerySnapshotCodec.parse(dev.kartograph.export.QuerySnapshotCodec.render(captured, compact)))
            }
            val merged = members.first().snapshot.copy(externalRetentionContexts = SnapshotAggregation.externalRetentionContexts(members))
            val restored = dev.kartograph.export.QuerySnapshotCodec.parse(dev.kartograph.export.QuerySnapshotCodec.render(merged, compact))
            assertEquals(contexts.toSet(), restored.externalRetentionContexts.toSet())
            assertEquals(contexts.map { it.provenanceJson }.toSet(), restored.externalRetentionContexts.map { it.provenanceJson }.toSet())
        }
    }

}
