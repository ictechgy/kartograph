package dev.kartograph.export

import dev.kartograph.core.CodeGraph
import dev.kartograph.core.CompilerEvidenceSource
import dev.kartograph.core.CompilerSourceCoordinateBasis
import dev.kartograph.core.EdgeKind
import dev.kartograph.core.EdgeOrigin
import dev.kartograph.core.GraphEdge
import dev.kartograph.core.GraphNode
import dev.kartograph.core.LocatedCompilerReference
import dev.kartograph.core.NodeId
import dev.kartograph.core.NodeKind
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class CompilerCallEvidenceSnapshotTest {
    @Test
    fun `plain and compact snapshots round trip every compiler coordinate identity`() {
        val snapshot = snapshot(graphWithPositions())
        for (compact in listOf(false, true)) {
            val encoded = QuerySnapshotCodec.render(snapshot, compact)
            val restored = QuerySnapshotCodec.parse(encoded)

            assertTrue(restored.graph.compilerCallPositionsCaptured)
            assertEquals(snapshot.graph.locatedCompilerReferences, restored.graph.locatedCompilerReferences)
            assertEquals(snapshot.graph.edges, restored.graph.edges)
            assertEquals(encoded, QuerySnapshotCodec.render(restored, compact))
            assertContains(encoded, "\"compilerCallEvidence\"")
            assertContains(encoded, "javacUtf16CharSequence")
            assertContains(encoded, "kotlinUtf16NormalizedSource")
            assertContains(encoded, "17.0.20+0")
            assertContains(encoded, "2.4.10")
        }
    }

    @Test
    fun `absent evidence stays uncaptured while present empty stays captured`() {
        val base = baseGraph()
        for (compact in listOf(false, true)) {
            val uncaptured = QuerySnapshotCodec.render(snapshot(base), compact)
            assertFalse(uncaptured.contains("\"compilerCallEvidence\""))
            assertFalse(QuerySnapshotCodec.parse(uncaptured).graph.compilerCallPositionsCaptured)

            val captured = QuerySnapshotCodec.render(snapshot(base.withCompilerCallPositions(emptyList())), compact)
            assertContains(captured, "\"compilerCallEvidence\": []")
            val restored = QuerySnapshotCodec.parse(captured).graph
            assertTrue(restored.compilerCallPositionsCaptured)
            assertEquals(emptyList(), restored.locatedCompilerReferences)
        }
    }

    @Test
    fun `uncaptured empty snapshot bytes remain equal to the released golden`() {
        val empty = snapshot(CodeGraph(emptyList(), emptyList()))
        assertEquals(EMPTY_V1, QuerySnapshotCodec.render(empty, compact = false))
        assertEquals(EMPTY_V2, QuerySnapshotCodec.render(empty, compact = true))
    }

    @Test
    fun `snapshot rejects malformed duplicated conflicting and unattached evidence`() {
        val plain = QuerySnapshotCodec.render(snapshot(graphWithPositions(listOf(javaPosition()))))
        val row = evidenceRows(plain).single()
        val invalidPlain = listOf(
            replaceEvidence(plain, "null"),
            replaceEvidence(plain, "{}"),
            replaceEvidence(plain, "[${row.replace("\"generated\": false, ", "")}]"),
            replaceEvidence(plain, "[$row, $row]"),
            replaceEvidence(plain, "[$row, ${row.replace("\"endOffsetUtf16\": 66", "\"endOffsetUtf16\": 67")}]"),
            replaceEvidence(plain, "[${row.replace("javacUtf16CharSequence", "kotlinUtf16NormalizedSource")}]"),
            replaceEvidence(plain, "[${row.replace(CALLER.value, "method:missing/Caller#use()V")}]"),
            plain.replace("\"kind\": \"call\"", "\"kind\": \"reference\""),
        )
        invalidPlain.forEach { text -> assertFailsWith<IllegalArgumentException> { QuerySnapshotCodec.parse(text) } }

        val compact = QuerySnapshotCodec.render(snapshot(graphWithPositions(listOf(javaPosition()))), compact = true)
        val compactRows = evidenceRows(compact)
        assertEquals(1, compactRows.size)
        val compactRow = compactRows.single()
        val shortened = compactRow.removeSuffix("]").substringBeforeLast(',') + "]"
        val conflictFields = compactRow.removeSurrounding("[", "]").split(", ").toMutableList().also { it[8] = "67" }
        val conflict = conflictFields.joinToString(", ", "[", "]")
        assertFailsWith<IllegalArgumentException> {
            QuerySnapshotCodec.parse(replaceEvidence(compact, "[$shortened]"))
        }
        assertFailsWith<IllegalArgumentException> {
            QuerySnapshotCodec.parse(replaceEvidence(compact, "[$compactRow, $compactRow]"))
        }
        assertFailsWith<IllegalArgumentException> {
            QuerySnapshotCodec.parse(replaceEvidence(compact, "[$compactRow, $conflict]"))
        }
        assertFailsWith<IllegalArgumentException> {
            QuerySnapshotCodec.parse(compact.replace("javacUtf16CharSequence", "unsupportedBasis"))
        }
        assertFailsWith<IllegalArgumentException> { QuerySnapshotCodec.parse(replaceEvidence(compact, "null")) }
    }

    @Test
    fun `plain evidence delegates coordinate hash and compiler metadata invariants to core`() {
        val plain = QuerySnapshotCodec.render(snapshot(graphWithPositions(listOf(javaPosition()))))
        val row = evidenceRows(plain).single()
        val invalidRows = listOf(
            row.replace("\"offsetUtf16\": 63", "\"offsetUtf16\": -1"),
            row.replace("\"endOffsetUtf16\": 66", "\"endOffsetUtf16\": 63"),
            row.replace("\"sourceSha256\": \"${"a".repeat(64)}\"", "\"sourceSha256\": \"invalid\""),
            row.replace("\"compilerVersion\": \"17.0.20+0\"", "\"compilerVersion\": \"bad version\""),
            row.replace("\"collector\": \"javac-constants\"", "\"collector\": \"unsupported\""),
            row.replace("javacUtf16CharSequence", "kotlinUtf16NormalizedSource"),
            row.replace("\"offsetUtf16\": 63", "\"offsetUtf16\": null"),
            row.removeSuffix("}") + ", \"extra\": 1}",
        )
        invalidRows.forEach { invalid ->
            assertFailsWith<IllegalArgumentException> { QuerySnapshotCodec.parse(replaceEvidence(plain, "[$invalid]")) }
        }

        for ((document, expected) in listOf(
            plain.replace("\"kind\": \"call\"", "\"kind\": \"reference\"") to "matching BYTECODE CALL",
            plain.replace("\"origin\": \"bytecode\"", "\"origin\": \"dispatchModel\"") to "matching BYTECODE CALL",
        )) {
            val failure = assertFailsWith<IllegalArgumentException> { QuerySnapshotCodec.parse(document) }
            assertContains(failure.message.orEmpty(), expected)
        }
    }

    @Test
    fun `compact evidence rejects row shapes types and node indices before graph attachment`() {
        val compact = QuerySnapshotCodec.render(snapshot(graphWithPositions(listOf(javaPosition()))), compact = true)
        val row = evidenceRows(compact).single()
        val fields = row.removeSurrounding("[", "]").split(", ")
        fun changed(index: Int, value: String): String = fields.toMutableList().also { it[index] = value }
            .joinToString(", ", "[", "]")
        val invalidRows = listOf(
            fields.dropLast(1).joinToString(", ", "[", "]"),
            (fields + "false").joinToString(", ", "[", "]"),
            changed(0, "-1"),
            changed(0, "2"),
            changed(2, "null"),
            changed(7, "\"63\""),
            changed(11, "null"),
        )
        invalidRows.forEach { invalid ->
            assertFailsWith<IllegalArgumentException> { QuerySnapshotCodec.parse(replaceEvidence(compact, "[$invalid]")) }
        }
        val wrongPair = assertFailsWith<IllegalArgumentException> {
            QuerySnapshotCodec.parse(replaceEvidence(compact, "[${changed(1, "0")}]"))
        }
        assertContains(wrongPair.message.orEmpty(), "matching BYTECODE CALL")
    }

    @Test
    fun `position rows are never truncated and remain inside the existing render bound`() {
        val references = (0 until 1_000).map { index ->
            javaPosition(offset = 100 + index * 4, end = 103 + index * 4, line = index + 1, column = 1)
        }
        val snapshot = snapshot(graphWithPositions(references))
        for (compact in listOf(false, true)) {
            val encoded = QuerySnapshotCodec.render(snapshot, compact)
            val bytes = encoded.toByteArray().size
            assertEquals(1_000, QuerySnapshotCodec.parse(encoded).graph.locatedCompilerReferences.size)
            assertEquals(encoded, QuerySnapshotCodec.render(snapshot, compact, bytes))
            assertFailsWith<QuerySnapshotSizeException> { QuerySnapshotCodec.render(snapshot, compact, bytes - 1) }
        }
    }

    private fun snapshot(graph: CodeGraph): QuerySnapshot = QuerySnapshot(
        graph, emptyList(), emptyList(), toolVersion = "0.19.0",
    )

    private fun baseGraph(): CodeGraph = CodeGraph(
        listOf(
            GraphNode(CALLER, "use", NodeKind.METHOD),
            GraphNode(TARGET, "hit", NodeKind.METHOD),
        ),
        listOf(GraphEdge(CALLER, TARGET, EdgeKind.CALL, origin = EdgeOrigin.BYTECODE)),
    )

    private fun graphWithPositions(references: List<LocatedCompilerReference> = listOf(javaPosition(), kotlinPosition())): CodeGraph =
        baseGraph().withCompilerCallPositions(references)

    private fun javaPosition(
        offset: Int = 63,
        end: Int = 66,
        line: Int = 1,
        column: Int = 64,
    ): LocatedCompilerReference = LocatedCompilerReference(
        CALLER,
        TARGET,
        CompilerEvidenceSource("src/main/java/demo/Caller.java", "a".repeat(64)),
        "javac-constants",
        "17.0.20+0",
        CompilerSourceCoordinateBasis.JAVAC_UTF16_CHAR_SEQUENCE,
        offset,
        end,
        line,
        column,
    )

    private fun kotlinPosition(): LocatedCompilerReference = LocatedCompilerReference(
        CALLER,
        TARGET,
        CompilerEvidenceSource("build/generated/demo/Caller.kt", "b".repeat(64)),
        "kotlin-constants",
        "2.4.10",
        CompilerSourceCoordinateBasis.KOTLIN_UTF16_NORMALIZED_SOURCE,
        52,
        58,
        4,
        21,
        generated = true,
    )

    /** `compilerCallEvidence` array의 top-level rows다. */
    private fun evidenceRows(document: String): List<String> {
        val value = evidenceValue(document)
        require(value.startsWith('[') && value.endsWith(']'))
        val body = value.substring(1, value.length - 1)
        if (body.isBlank()) return emptyList()
        val rows = mutableListOf<String>()
        var depth = 0
        var quoted = false
        var escaped = false
        var start = 0
        body.forEachIndexed { index, character ->
            when {
                escaped -> escaped = false
                character == '\\' && quoted -> escaped = true
                character == '"' -> quoted = !quoted
                !quoted && character in "[{" -> depth++
                !quoted && character in "]}" -> depth--
                !quoted && character == ',' && depth == 0 -> {
                    rows += body.substring(start, index).trim()
                    start = index + 1
                }
            }
        }
        rows += body.substring(start).trim()
        return rows
    }

    private fun evidenceValue(document: String): String {
        val prefix = "\"compilerCallEvidence\": "
        val start = document.indexOf(prefix).let { require(it >= 0); it + prefix.length }
        var depth = 0
        var quoted = false
        var escaped = false
        for (index in start until document.length) {
            val character = document[index]
            when {
                escaped -> escaped = false
                character == '\\' && quoted -> escaped = true
                character == '"' -> quoted = !quoted
                !quoted && character in "[{" -> depth++
                !quoted && character in "]}" -> {
                    depth--
                    if (depth == 0) return document.substring(start, index + 1)
                }
            }
        }
        error("unterminated compilerCallEvidence")
    }

    private fun replaceEvidence(document: String, replacement: String): String {
        val value = evidenceValue(document)
        val prefix = "\"compilerCallEvidence\": "
        val valueStart = document.indexOf(prefix) + prefix.length
        return document.replaceRange(valueStart, valueStart + value.length, replacement)
    }

    private companion object {
        val CALLER: NodeId = NodeId("method:demo/Caller#use()V")
        val TARGET: NodeId = NodeId("method:demo/Target#hit()V")
        const val EMPTY_V1: String = "{\"format\": \"kartograph-query-snapshot\", \"graph\": {\"callSiteEvidence\": [], \"callbackArguments\": [], \"edges\": [], \"enclosures\": [], \"externalCalls\": [], \"lambdaEscapes\": [], \"nodes\": [], \"parameterUses\": [], \"serviceProviders\": []}, \"includePrivateMembers\": false, \"limitations\": [], \"processorGenerations\": [], \"processorOutputs\": [], \"retention\": [], \"suppressed\": [], \"toolVersion\": \"0.19.0\", \"version\": 1}\n"
        const val EMPTY_V2: String = "{\"format\": \"kartograph-query-snapshot\", \"graph\": {\"callSiteEvidence\": [], \"callbackArguments\": [], \"edges\": [], \"enclosures\": [], \"externalCalls\": [], \"lambdaEscapes\": [], \"nodes\": [], \"parameterUses\": [], \"serviceProviders\": [], \"stringTable\": []}, \"includePrivateMembers\": false, \"limitations\": [], \"processorGenerations\": [], \"processorOutputs\": [], \"retention\": [], \"suppressed\": [], \"toolVersion\": \"0.19.0\", \"version\": 2}\n"
    }
}
