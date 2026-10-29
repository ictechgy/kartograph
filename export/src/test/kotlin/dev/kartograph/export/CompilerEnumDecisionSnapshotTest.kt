package dev.kartograph.export

import dev.kartograph.core.BuildWitness
import dev.kartograph.core.CodeGraph
import dev.kartograph.core.CompilerEnumDecisionEvidence
import dev.kartograph.core.CompilerEnumDecisionImportCounts
import dev.kartograph.core.CompilerEnumDecisionProducerCounts
import dev.kartograph.core.CompilerEnumDecisionRoot
import dev.kartograph.core.CompilerEnumDecisionSite
import dev.kartograph.core.CompilerEnumDecisionUnit
import dev.kartograph.core.CompilerEvidenceSource
import dev.kartograph.core.CompilerSourceCoordinateBasis
import dev.kartograph.core.CompilerSourceUseClaim
import dev.kartograph.core.EnumDecisionAttribution
import dev.kartograph.core.EnumDecisionKind
import dev.kartograph.core.GraphNode
import dev.kartograph.core.InputFingerprint
import dev.kartograph.core.NodeId
import dev.kartograph.core.NodeKind
import dev.kartograph.core.SnapshotProvenance
import java.security.MessageDigest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class CompilerEnumDecisionSnapshotTest {
    @Test
    fun `plain and compact snapshots preserve all original evidence and inventories`() {
        val snapshot = fixture()
        for (compact in listOf(false, true)) {
            val text = QuerySnapshotCodec.render(snapshot, compact)
            val decoded = QuerySnapshotCodec.parse(text)
            assertEquals(snapshot.enumDecisionEvidence, decoded.enumDecisionEvidence)
            assertEquals(listOf(A, B), decoded.graph.node(OWNER)!!.enumConstants)
            assertEquals(text, QuerySnapshotCodec.render(decoded, compact))
            assertEquals(3, decoded.enumDecisionEvidence!!.sites.size)
            assertEquals(EnumDecisionKind.entries.toSet(), decoded.enumDecisionEvidence!!.sites.map { it.kind }.toSet())
            assertTrue(decoded.enumDecisionEvidence!!.sites.all { it.lexicalClass == null })
        }
    }

    @Test
    fun `legacy absence differs from captured empty and explicit null is invalid`() {
        val empty = fixture(empty = true)
        val legacy = empty.copy(enumDecisionEvidence = null)
        val oldText = QuerySnapshotCodec.render(legacy)
        assertFalse("enumDecisions" in oldText)
        assertNull(QuerySnapshotCodec.parse(oldText).enumDecisionEvidence)
        val captured = QuerySnapshotCodec.parse(QuerySnapshotCodec.render(empty))
        assertTrue(captured.enumDecisionEvidence!!.sites.isEmpty())
        assertEquals(0, captured.enumDecisionEvidence!!.units.single().producer.emitted)
        assertFailsWith<IllegalArgumentException> { QuerySnapshotCodec.parse(rewrite(oldText) { it["enumDecisions"] = null }) }
    }

    @Test
    fun `pruning preserves full root lists sites and capture counts without recreating graph nodes`() {
        val snapshot = fixture()
        for (graph in listOf(CodeGraph(emptyList(), emptyList()),
            CodeGraph(listOf(GraphNode(OWNER, "Mode", NodeKind.ENUM)), emptyList()))) {
            for (compact in listOf(false, true)) {
                val decoded = QuerySnapshotCodec.parse(QuerySnapshotCodec.render(snapshot.copy(graph = graph), compact))
                assertEquals(snapshot.enumDecisionEvidence, decoded.enumDecisionEvidence)
                assertEquals(graph.nodeIds, decoded.graph.nodeIds)
                assertEquals(listOf(OWNER), decoded.enumDecisionEvidence!!.roots.single().capturedClassOwners)
                assertEquals(2, decoded.enumDecisionEvidence!!.units.single().importCounts.resolved)
            }
        }
    }

    @Test
    fun `malformed extension identities canonical order and field shapes are rejected`() {
        val text = QuerySnapshotCodec.render(fixture())
        val mutations: List<(MutableMap<String, Any?>) -> Unit> = listOf(
            { it["version"] = 2L },
            { it["extra"] = true },
            { rows(it["roots"]).add(rows(it["roots"]).first()) },
            { obj(rows(it["roots"]).first())["id"] = "0".repeat(64) },
            { obj(rows(it["roots"]).first())["capturedClassOwners"] = listOf(OWNER.value, OWNER.value) },
            { obj(rows(it["units"]).first())["inputToken"] = "0".repeat(64) },
            { obj(obj(rows(it["units"]).first())["import"])["resolved"] = 0L },
            { obj(rows(it["sites"]).first())["offsetUtf16"] = -1L },
            { obj(rows(it["sites"]).first())["generated"] = "false" },
            { obj(rows(it["sites"]).first())["kind"] = "unknown" },
            { obj(rows(it["sites"]).first())["constants"] = listOf(B.value, A.value) },
            { obj(rows(it["sites"]).first())["lexicalClass"] = "method:p/Use#run()V" },
            { obj(rows(it["sites"]).first())["sourceSha256"] = "not-a-hash" },
            { rows(it["sites"]).reverse() },
            { rows(it["sites"]).add(rows(it["sites"]).first()) },
        )
        mutations.forEach { mutation ->
            assertFailsWith<IllegalArgumentException> { QuerySnapshotCodec.parse(rewrite(text) { mutation(obj(it["enumDecisions"])) }) }
        }
    }

    @Test
    fun `receipt scope option token source roots and retained inventory contradictions fail`() {
        val snapshot = fixture()
        val text = QuerySnapshotCodec.render(snapshot)
        for (mutation in listOf<(MutableMap<String, Any?>) -> Unit>(
            { it.remove("provenance") },
            { it["scope"] = "other:main" },
            { obj(rows(obj(it["provenance"])["witnesses"]).first())["evidenceToken"] = "0".repeat(64) },
        )) assertFailsWith<IllegalArgumentException> { QuerySnapshotCodec.parse(rewrite(text, mutation)) }
        val contradictory = CodeGraph(listOf(GraphNode(OWNER, "Mode", NodeKind.ENUM, enumConstants = emptyList())), emptyList())
        assertFailsWith<IllegalArgumentException> { snapshot.copy(graph = contradictory) }
        val witness = snapshot.provenance!!.witnesses.single()
        val badInputs = witness.inputs.map { if (it.path == "compiler-enum-decisions") it.copy(sha256 = "0".repeat(64)) else it }
        assertFailsWith<IllegalArgumentException> { snapshot.copy(provenance = snapshot.provenance!!.copy(witnesses = listOf(witness.copy(inputs = badInputs)))) }
        for (compact in listOf(false, true)) {
            val encoded = QuerySnapshotCodec.render(snapshot, compact)
            val bytes = encoded.toByteArray(Charsets.UTF_8).size
            assertEquals(encoded, QuerySnapshotCodec.render(snapshot, compact, bytes))
            assertFailsWith<QuerySnapshotSizeException> { QuerySnapshotCodec.parse(encoded, bytes - 1) }
        }
    }

    private fun fixture(empty: Boolean = false): QuerySnapshot {
        val inputs = listOf(InputFingerprint("sources", "src/p/Use.kt", "a".repeat(64)),
            InputFingerprint("buildConfig", "build.gradle.kts", "b".repeat(64)),
            InputFingerprint("compiler", "compiler/kotlin.jar", ARTIFACT),
            InputFingerprint("options", "compiler-enum-decisions", digest(listOf("enumDecisions=true"))))
        val token = digest(listOf("kartograph-compiler-inputs-v1", SCOPE, "kotlin", ":compileKotlin") +
            inputs.flatMap { listOf(it.role, it.path, it.sha256) })
        val root = CompilerEnumDecisionRoot(0, CLASSES, listOf(OWNER))
        val unit = CompilerEnumDecisionUnit(RECEIPT, "kotlin-constants", "2.4.10", ARTIFACT, token,
            listOf(CompilerSourceUseClaim(SCOPE, "kotlin", ":compileKotlin", listOf(CLASSES))),
            CompilerEnumDecisionProducerCounts(if (empty) 0 else 5, if (empty) 0 else 3, if (empty) 0 else 1, if (empty) 0 else 1),
            CompilerEnumDecisionImportCounts(if (empty) 0 else 2, if (empty) 0 else 1))
        val sites = if (empty) emptyList() else EnumDecisionKind.entries.mapIndexed { index, kind ->
            CompilerEnumDecisionSite(unit.id, OWNER, listOf(A), kind, null, index == 1,
                CompilerEvidenceSource("src/p/Use.kt", "a".repeat(64)), CompilerSourceCoordinateBasis.KOTLIN_UTF16_NORMALIZED_SOURCE,
                10 * index, 10 * index + 5, 1, 10 * index + 1, false,
                if (index == 2) null else root.id, root.id,
                if (index == 2) EnumDecisionAttribution.SOURCE_ROOT_UNRESOLVED else EnumDecisionAttribution.RESOLVED_SAME_OUTPUT_ROOT)
        }
        val evidence = CompilerEnumDecisionEvidence(listOf(root), listOf(unit), sites)
        val witness = BuildWitness(SCOPE, "kotlin", ":compileKotlin", inputs, listOf(CLASSES), listOf(RECEIPT), token)
        return QuerySnapshot(CodeGraph(listOf(GraphNode(OWNER, "Mode", NodeKind.ENUM, enumConstants = listOf(A, B))), emptyList()),
            emptyList(), emptyList(), scope = SCOPE, provenance = SnapshotProvenance(listOf(CLASSES, RECEIPT), listOf(witness)),
            enumDecisionEvidence = evidence)
    }

    private fun digest(fields: List<String>): String {
        val digest = MessageDigest.getInstance("SHA-256")
        fields.forEach { field ->
            val bytes = field.toByteArray(Charsets.UTF_8)
            digest.update(byteArrayOf((bytes.size ushr 24).toByte(), (bytes.size ushr 16).toByte(), (bytes.size ushr 8).toByte(), bytes.size.toByte()))
            digest.update(bytes)
        }
        return digest.digest().joinToString("") { "%02x".format(it.toInt() and 0xff) }
    }
    private fun rewrite(text: String, mutation: (MutableMap<String, Any?>) -> Unit): String =
        obj(SnapshotJsonParser(text).parse()).also(mutation).let(::jsonValue)
    @Suppress("UNCHECKED_CAST")
    private fun obj(value: Any?): MutableMap<String, Any?> = value as MutableMap<String, Any?>
    @Suppress("UNCHECKED_CAST")
    private fun rows(value: Any?): MutableList<Any?> = value as MutableList<Any?>

    private companion object {
        const val SCOPE = "sample:main"
        val OWNER = NodeId("class:p/Mode")
        val A = NodeId("field:p/Mode#A:Lp/Mode;")
        val B = NodeId("field:p/Mode#B:Lp/Mode;")
        val CLASSES = InputFingerprint("classes", "build/classes", "1".repeat(64))
        val RECEIPT = InputFingerprint("compilerEvidence", "build/enum.tsv", "2".repeat(64))
        val ARTIFACT = "3".repeat(64)
    }
}
