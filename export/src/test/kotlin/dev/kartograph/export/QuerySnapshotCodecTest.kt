package dev.kartograph.export

import dev.kartograph.analysis.ReachabilityAnalyzer
import dev.kartograph.analysis.SymbolQuery
import dev.kartograph.core.CallResolution
import dev.kartograph.core.CodeGraph
import dev.kartograph.core.EdgeKind
import dev.kartograph.core.EdgeOrigin
import dev.kartograph.core.ExternalCall
import dev.kartograph.core.GraphEdge
import dev.kartograph.core.GraphNode
import dev.kartograph.core.InvocationKind
import dev.kartograph.core.JvmModifier
import dev.kartograph.core.NodeAttribute
import dev.kartograph.core.NodeId
import dev.kartograph.core.NodeKind
import dev.kartograph.core.RetentionEvidence
import dev.kartograph.core.RetentionReason
import dev.kartograph.core.ServiceProviderRegistration
import dev.kartograph.core.SourceLocation
import dev.kartograph.core.Visibility
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse

class QuerySnapshotCodecTest {
    @Test
    fun `refuses unrepresentable source locations instead of silently dropping them`() {
        val node = GraphNode(NodeId("class:A"), "A", NodeKind.CLASS)
        for (path in listOf("Bad\nName.kt", "/private/source/", "META-INF/services/")) {
            val location = SourceLocation(path)
            val nodeSnapshot = QuerySnapshot(CodeGraph(listOf(node.copy(location = location)), emptyList()), emptyList(), emptyList())
            assertFailsWith<IllegalArgumentException> { QuerySnapshotCodec.render(nodeSnapshot) }
            val serviceSnapshot = QuerySnapshot(CodeGraph(listOf(node), emptyList(),
                serviceProviders = listOf(ServiceProviderRegistration("service.A", node.id, location))), emptyList(), emptyList())
            assertFailsWith<IllegalArgumentException> { QuerySnapshotCodec.render(serviceSnapshot) }
        }
    }

    @Test
    fun `round trips all graph facts retention and baseline state with deterministic output`() {
        val original = fixture()
        val encoded = QuerySnapshotCodec.render(original)
        val restored = QuerySnapshotCodec.parse(encoded)
        assertEquals(original.graph.nodes, restored.graph.nodes)
        assertEquals(original.graph.edges, restored.graph.edges)
        assertEquals(original.graph.externalCalls, restored.graph.externalCalls)
        assertEquals(original.graph.serviceProviders, restored.graph.serviceProviders)
        assertEquals(original.retention.toSet(), restored.retention.toSet())
        assertEquals(original.limitations.sorted(), restored.limitations)
        assertEquals(original.suppressed, restored.suppressed)
        assertEquals(true, restored.includePrivateMembers)
        assertEquals(original.toolVersion, restored.toolVersion)
        assertEquals(encoded, QuerySnapshotCodec.render(restored))
        val compact = QuerySnapshotCodec.parse(QuerySnapshotCodec.render(original, compact = true))
        assertEquals(original.graph.nodes, compact.graph.nodes)
        assertEquals(original.graph.edges, compact.graph.edges)
        assertEquals(original.graph.externalCalls, compact.graph.externalCalls)
        assertEquals(original.graph.serviceProviders, compact.graph.serviceProviders)
        assertEquals(original.suppressed, compact.suppressed)
        assertEquals(original.retention.toSet(), compact.retention.toSet())
        for (name in listOf("class:app/Entry", "class:app/Unused", "Missing")) {
            fun query(snapshot: QuerySnapshot) = SymbolQuery.query(snapshot.graph,
                ReachabilityAnalyzer.analyze(snapshot.graph, snapshot.retention), name,
                snapshot.limitations.sorted(), 2, 1, snapshot.suppressed)
            assertEquals(query(original), query(restored))
        }
    }

    @Test
    fun `preserves enum fields including unresolved external calls and absent optional values`() {
        for (kind in NodeKind.entries) {
            val node = GraphNode(NodeId("node:$kind"), kind.name, kind, visibility = Visibility.UNKNOWN)
            val restored = QuerySnapshotCodec.parse(QuerySnapshotCodec.render(QuerySnapshot(CodeGraph(listOf(node), emptyList()),
                emptyList(), emptyList())))
            assertEquals(node, restored.graph.node(node.id))
        }
        val empty = QuerySnapshot(CodeGraph(emptyList(), emptyList()), emptyList(), emptyList())
        assertEquals(0, QuerySnapshotCodec.parse(QuerySnapshotCodec.render(empty)).graph.nodeCount)
    }

    @Test
    fun `strips absolute paths on write and refuses unsafe imported locations`() {
        for (path in listOf("/private/build/Entry.kt", "C:\\private\\Entry.kt", "../private/Entry.kt")) {
            val node = GraphNode(NodeId("class:Entry"), "Entry", NodeKind.CLASS, location = SourceLocation(path, 1))
            val snapshot = QuerySnapshot(CodeGraph(listOf(node), emptyList()),
                listOf(RetentionEvidence(node.id, RetentionReason.KEEP_RULE, SourceLocation(path))), emptyList())
            val encoded = QuerySnapshotCodec.render(snapshot)
            assertFalse(encoded.contains("private"))
            assertEquals("Entry.kt", QuerySnapshotCodec.parse(encoded).graph.node(node.id)?.location?.path)
        }
        val valid = QuerySnapshotCodec.render(fixture())
        for (path in listOf("/sensitive/path", "../outside", "C:/private", "line\nsecret")) {
            val invalid = valid.replace("\"src/Entry.kt\"", jsonValue(path))
            val error = assertFailsWith<IllegalArgumentException> { QuerySnapshotCodec.parse(invalid) }
            assertFalse(error.message.orEmpty().contains("sensitive"))
        }
    }

    @Test
    fun `rejects unsupported versions malformed JSON and invalid field types without echoing input`() {
        val valid = QuerySnapshotCodec.render(fixture())
        val invalid = listOf(
            "", "[]", "{}", "{", valid + " false", valid.replace("\"version\": 1", "\"version\": 2"),
            valid.replace("\"version\": 1", "\"version\": 3"),
            valid.replace("\"version\": 1", "\"version\": 1, \"version\": 1"),
            valid.replace("\"version\": 1", "\"version\": 01"),
            valid.replace("\"version\": 1", "\"version\": -"),
            valid.replace("\"version\": 1", "\"version\": 1.5"),
            valid.replace("\"version\": 1", "\"version\": 9223372036854775808"),
            valid.replace("\"version\": 1", "\"version\": 2147483648"),
            valid.replace("\"version\": 1", "\"version\": \"1\""),
            valid.replace("\"includePrivateMembers\": true", "\"includePrivateMembers\": 1"),
            valid.replace("\"nodes\": [", "\"nodes\": [null,"),
            valid.replace("\"limitations\": [", "\"limitations\": [true,"),
            valid.replace("\"accessibility\": \"internal\"", "\"accessibility\": \"do-not-echo\""),
            valid.replace("\"name\": \"Unused\"", "\"name\": null"),
            valid.replace("\"graph\": {", "\"graph\": {\"duplicate\": 0,\"duplicate\": 0,"),
            valid.replace("\"toolVersion\": \"test\"", "\"toolVersion\": \"\\q\""),
            valid.replace("\"toolVersion\": \"test\"", "\"toolVersion\": \"\\uQQQQ\""),
            valid.replace("\"toolVersion\": \"test\"", "\"toolVersion\": \"line\nsecret\""),
            "{\"unterminated", "[1,]", "{\"a\":1,}", "[\"\\u12", "[\"\\", "[falseX]",
            "[".repeat(40) + "0" + "]".repeat(40),
        )
        for (content in invalid) {
            val error = assertFailsWith<IllegalArgumentException> { QuerySnapshotCodec.parse(content) }
            assertFalse(error.message.orEmpty().contains("do-not-echo"))
        }
    }

    @Test
    fun `rejects duplicate nodes dangling edges invalid calls and unknown suppression IDs`() {
        val valid = QuerySnapshotCodec.render(fixture())
        val duplicateEdge = """{"source":"class:app/Entry","target":"method:app/Entry#run()V","kind":"member","origin":"bytecode","weight":1}"""
        val invalid = listOf(
            valid.replace("\"edges\": [", "\"edges\": [$duplicateEdge,"),
            valid.replace("\"usr\": \"class:app/Unused\"", "\"usr\": \"class:app/Entry\""),
            valid.replace("\"target\": \"class:app/Used\"", "\"target\": \"class:Missing\""),
            valid.replace("\"weight\": 3", "\"weight\": 0"),
            valid.replace("\"ordinal\": 2", "\"ordinal\": -1"),
            valid.replace("\"caller\": \"method:app/Entry#run()V\"", "\"caller\": \"method:Missing#run()V\""),
            valid.replace("\"suppressed\": [\"class:app/Unused\"]", "\"suppressed\": [\"class:Missing\"]"),
            valid.replace("\"line\": 7", "\"line\": 0"),
        )
        invalid.forEach { assertFailsWith<IllegalArgumentException> { QuerySnapshotCodec.parse(it) } }
    }

    @Test
    fun `decodes valid whitespace escapes and nonASCII without changing identifiers`() {
        val source = fixture().copy(limitations = listOf("\"\\\b\u000c\n\r\t한글😀/"))
        val encoded = QuerySnapshotCodec.render(source)
        assertContains(encoded, "\\u")
        assertEquals(source.limitations, QuerySnapshotCodec.parse(" \t\r\n$encoded ").limitations)
        assertEquals(source.limitations, QuerySnapshotCodec.parse(encoded.replace("/", "\\/")).limitations)
    }

    private fun fixture(): QuerySnapshot {
        val entry = GraphNode(NodeId("class:app/Entry"), "Entry", NodeKind.CLASS, moduleName = "app", jvmSignature = "app/Entry",
            location = SourceLocation("src/Entry.kt", 7, 2), visibility = Visibility.INTERNAL, jvmVisibility = Visibility.PUBLIC,
            jvmModifiers = setOf(JvmModifier.FINAL), attributes = setOf(NodeAttribute.GENERATED_INPUT),
            annotations = setOf("app/Marker"), supertypes = setOf("java/lang/Object"), synthesized = true)
        val method = GraphNode(NodeId("method:app/Entry#run()V"), "run", NodeKind.FUNCTION,
            attributes = setOf(NodeAttribute.EXTENSION_FUNCTION), extensionReceiverType = "app/Receiver")
        val used = GraphNode(NodeId("class:app/Used"), "Used", NodeKind.CLASS)
        val unused = GraphNode(NodeId("class:app/Unused"), "Unused", NodeKind.CLASS)
        val graph = CodeGraph(listOf(entry, method, used, unused), listOf(
            GraphEdge(entry.id, method.id, EdgeKind.MEMBER),
            GraphEdge(method.id, used.id, EdgeKind.CALL, 3, EdgeOrigin.RUNTIME_MODEL)),
            listOf(ExternalCall(method.id, "lib/External", "invoke", "()V", InvocationKind.STATIC,
                SourceLocation("Entry.kt", 8), 2, listOf(used.id), CallResolution.RUNTIME_MODEL, "model.v1")),
            listOf(ServiceProviderRegistration("lib.Service", used.id, SourceLocation("META-INF/services/lib.Service", 1))))
        return QuerySnapshot(graph, listOf(RetentionEvidence(method.id, RetentionReason.KEEP_RULE, SourceLocation("keep.pro", 1)),
            RetentionEvidence(NodeId("class:Outside"), RetentionReason.MANIFEST_COMPONENT, null)),
            listOf("reflection-strings: 1 unresolved", "class-loading: 1 unresolved"), setOf(unused.id), true, "test")
    }
}
