package dev.kartograph.export

import dev.kartograph.analysis.SymbolQuery
import dev.kartograph.analysis.ReachabilityAnalyzer
import dev.kartograph.core.BridgeFact
import dev.kartograph.core.BridgeFactsDocument
import dev.kartograph.core.BridgeLocation
import dev.kartograph.core.CodeGraph
import dev.kartograph.core.GraphNode
import dev.kartograph.core.NodeId
import dev.kartograph.core.NodeKind
import dev.kartograph.core.SourceLocation
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFalse

class AgentDocumentRendererTest {
    @Test
    fun `notFound query keeps nullable sibling fields and limitations`() {
        val graph = CodeGraph(emptyList(), emptyList())
        val document = SymbolQuery.query(graph, ReachabilityAnalyzer.analyze(graph, emptyList()), "Missing", listOf("jni-methods: 1"))

        val json = AgentDocumentRenderer.query(document)

        assertEquals(
            "{\"level\": \"symbol\", \"limitations\": [\"jni-methods: 1\"], " +
                "\"requested\": \"Missing\", \"status\": \"notFound\"}\n",
            json,
        )
    }

    @Test
    fun `bridge JSON omits internal target and null optional symbol`() {
        val document = BridgeFactsDocument(
            generatedAt = "2026-09-04T00:00:00Z",
            target = "flutter",
            project = "/project",
            facts = listOf(
                BridgeFact("channel-register", "camera", dynamic = false, location = BridgeLocation("Plugin.kt", 2, 3), target = "flutter"),
            ),
            limitations = emptyList(),
        )

        val json = AgentDocumentRenderer.bridges(document)

        assertContains(json, "\"format\": \"bridge-facts\"")
        assertContains(json, "\"platform\": \"kotlin\"")
        assertContains(json, "\"path\": \"Plugin.kt\"")
        assertFalse(json.contains("\"symbol\""))
        val facts = json.substring(json.indexOf("\"facts\""), json.indexOf("\"format\""))
        assertFalse(facts.contains("\"target\": \"flutter\""))
    }

    @Test
    fun `query omits unavailable optional location coordinates`() {
        val node = GraphNode(
            NodeId("class:fixture/Subject"),
            "Subject",
            NodeKind.CLASS,
            location = SourceLocation("Subject.kt"),
        )
        val graph = CodeGraph(listOf(node), emptyList())
        val document = SymbolQuery.query(
            graph,
            ReachabilityAnalyzer.analyze(graph, emptyList()),
            "Subject",
            emptyList(),
        )

        val json = AgentDocumentRenderer.query(document)

        assertContains(json, "\"location\": {\"path\": \"Subject.kt\"}")
        assertFalse(json.contains("\"line\": null"))
        assertFalse(json.contains("\"column\": null"))
    }

    @Test
    fun `bridge symbol omits an unavailable usr`() {
        val document = BridgeFactsDocument(
            generatedAt = "2026-09-04T00:00:00Z",
            target = "flutter",
            project = "/project",
            facts = listOf(
                BridgeFact(
                    "method-handle",
                    "camera",
                    "takePhoto",
                    false,
                    BridgeLocation("Plugin.kt", 2, 3),
                    symbol = dev.kartograph.core.BridgeSymbol("Plugin.register"),
                    target = "flutter",
                ),
            ),
            limitations = emptyList(),
        )

        val json = AgentDocumentRenderer.bridges(document)

        assertContains(json, "\"symbol\": {\"qualifiedName\": \"Plugin.register\"}")
        assertFalse(json.contains("\"usr\": null"))
    }
}
