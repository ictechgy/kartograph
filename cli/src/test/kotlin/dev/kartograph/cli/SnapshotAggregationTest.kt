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
}
