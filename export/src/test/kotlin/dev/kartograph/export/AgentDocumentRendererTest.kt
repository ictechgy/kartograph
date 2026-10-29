package dev.kartograph.export

import dev.kartograph.analysis.SymbolQuery
import dev.kartograph.analysis.ReachabilityAnalyzer
import dev.kartograph.core.BridgeFact
import dev.kartograph.core.BridgeFactsDocument
import dev.kartograph.core.BridgeLocation
import dev.kartograph.core.CodeGraph
import dev.kartograph.core.EdgeKind
import dev.kartograph.core.EdgeOrigin
import dev.kartograph.core.GraphEdge
import dev.kartograph.core.GraphNode
import dev.kartograph.core.NodeId
import dev.kartograph.core.NodeKind
import dev.kartograph.core.RouteCallEvidence
import dev.kartograph.core.RouteDeclEvidence
import dev.kartograph.core.RouteLimitationScope
import dev.kartograph.core.RouteParamConstraint
import dev.kartograph.core.SourceLocation
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse

class AgentDocumentRendererTest {
    @Test
    fun `query renders additive direct call references`() {
        val caller = GraphNode(NodeId("method:app/Caller#calls()V"), "calls", NodeKind.METHOD,
            location = SourceLocation("Caller.java", 3))
        val target = GraphNode(NodeId("method:app/Target#run()V"), "run", NodeKind.METHOD,
            location = SourceLocation("Target.java", 1))
        val graph = CodeGraph(listOf(caller, target), listOf(
            GraphEdge(caller.id, target.id, EdgeKind.CALL, weight = 2, origin = EdgeOrigin.BYTECODE, callSiteLines = listOf(5, 7)),
        ))

        val json = AgentDocumentRenderer.query(SymbolQuery.query(
            graph, ReachabilityAnalyzer.analyze(graph, emptyList()), target.id.value, emptyList(),
        ))

        assertContains(json, "\"references\": [{\"kind\": \"call\", \"location\": {\"line\": 5, \"path\": \"Caller.java\"}, \"origin\": \"bytecode\"},")
        assertContains(json, "\"line\": 7")
        assertFalse(json.contains("\"column\""))
    }

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

    @Test
    fun `bridge JSON serializes mechanism only when present`() {
        val document = BridgeFactsDocument(
            generatedAt = "2026-09-04T00:00:00Z",
            target = "react-native",
            project = "/project",
            facts = listOf(
                BridgeFact("module-export", "Sensor", dynamic = false,
                    location = BridgeLocation("Sensor.kt", 3, 7), target = "react-native",
                    mechanism = "expo"),
                BridgeFact("method-handle", "Sensor", method = "ping", dynamic = false,
                    location = BridgeLocation("Sensor.kt", 6, 5), target = "react-native"),
            ),
            limitations = emptyList(),
        )

        val json = AgentDocumentRenderer.bridges(document)

        assertContains(json, "\"mechanism\": \"expo\"")
        assertEquals(1, Regex("\"mechanism\"").findAll(json).count())
    }

    @Test
    fun `http documents carry roles, source sets and only true route markers`() {
        val document = BridgeFactsDocument(
            generatedAt = "2026-09-04T00:00:00Z",
            target = "http",
            project = "/project",
            facts = listOf(
                BridgeFact("route-call", "/v1/items/{}", method = "GET", dynamic = false,
                    location = BridgeLocation("Api.kt", 4, 9), target = "http",
                    route = RouteCallEvidence("root", authority = "api.example.com", service = "mobile",
                        queryTailStripped = true, maskedSegments = 1, testSource = true)),
                BridgeFact("route-call", null, dynamic = true, location = BridgeLocation("Api.kt", 8, 5), target = "http",
                    channelPrefix = "/files/", route = RouteCallEvidence("base", methodDynamic = true)),
            ),
            limitations = emptyList(),
            roles = listOf("client"),
            testSources = "included",
            service = "mobile",
        )

        val json = AgentDocumentRenderer.bridges(document)

        assertContains(json, "\"roles\": [\"client\"]")
        assertContains(json, "\"sourceSets\": {\"tests\": \"included\"}")
        assertContains(json, "\"authority\": \"api.example.com\", \"channel\": \"/v1/items/{}\"")
        assertContains(json, "\"maskedSegments\": 1")
        assertContains(json, "\"methodDynamic\": true, \"pathAnchor\": \"base\"")
        assertEquals(1, Regex("\"queryTailStripped\"").findAll(json).count())
        assertEquals(1, Regex("\"testSource\"").findAll(json).count())
        assertFalse(json.contains("false, \"pathAnchor\""))
    }

    @Test
    fun `server documents carry dispatch and route-decl evidence in contract field names`() {
        val document = BridgeFactsDocument(
            generatedAt = "2026-09-04T00:00:00Z",
            target = "http",
            project = "/project",
            facts = listOf(
                BridgeFact("route-decl", "/files/{}/{**}", method = "GET", dynamic = false, location = BridgeLocation("Web.kt", 3, 5),
                    target = "http", routeDecl = RouteDeclEvidence("root", "strict", narrowed = true,
                        paramConstraints = listOf(RouteParamConstraint(2, "regex", "[a-z]+"), RouteParamConstraint(1, "int")),
                        configDefault = true, testSource = true)),
                BridgeFact("route-decl", "/files", method = "ANY", dynamic = false, location = BridgeLocation("Web.kt", 3, 5),
                    target = "http", routeDecl = RouteDeclEvidence("base", catchAllPrefix = true)),
            ),
            limitations = emptyList(),
            roles = listOf("server"),
            testSources = "included",
            dispatch = "specificity",
        )

        val json = AgentDocumentRenderer.bridges(document)

        assertContains(json, "{\"dispatch\": \"specificity\", \"facts\": [")
        assertContains(json, "\"narrowed\": true, \"paramConstraints\": [{\"kind\": \"int\", \"segment\": 1}, " +
            "{\"kind\": \"regex\", \"pattern\": \"[a-z]+\", \"segment\": 2}], \"pathAnchor\": \"root\"")
        assertContains(json, "\"configDefault\": true")
        assertContains(json, "\"testSource\": true, \"trailingSlash\": \"strict\"")
        assertContains(json, "\"catchAllPrefix\": true, \"channel\": \"/files\"")
        assertFalse(json.contains("\"narrowed\": false"))
        assertFalse(json.contains("\"trailingSlash\": null"))
    }

    @Test
    fun `limitation scopes point at the sorted limitation index with normalized elements`() {
        val error = "framework-provided-routes: error endpoint"
        val static = "framework-provided-routes: static resources"
        val document = BridgeFactsDocument(
            generatedAt = "2026-09-04T00:00:00Z", target = "http", project = "/project", facts = emptyList(),
            limitations = listOf(static, "route-coverage: 1 path", error),
            roles = listOf("server"), dispatch = "specificity",
            limitationScopes = listOf(
                RouteLimitationScope(static, templatePrefixes = listOf("/"), methods = listOf("HEAD", "GET", "GET")),
                RouteLimitationScope(error, templates = listOf("/error", "/api/error", "/error"), templateSuffixes = listOf("/error")),
            ),
        )

        val json = AgentDocumentRenderer.bridges(document)

        assertContains(json, "\"limitationScopes\": [{\"limitationIndex\": 0, \"templateSuffixes\": [\"/error\"], " +
            "\"templates\": [\"/api/error\", \"/error\"]}, {\"limitationIndex\": 1, \"methods\": [\"GET\", \"HEAD\"], \"templatePrefixes\": [\"/\"]}]")
        assertFalse(AgentDocumentRenderer.bridges(document.copy(limitationScopes = emptyList())).contains("limitationScopes"))
    }

    @Test
    fun `a scope must name exactly one limitation`() {
        val document = BridgeFactsDocument(
            generatedAt = "2026-09-04T00:00:00Z", target = "http", project = "/project", facts = emptyList(),
            limitations = listOf("a", "a"), roles = listOf("server"),
            limitationScopes = listOf(RouteLimitationScope("a", templates = listOf("/a"))),
        )
        assertFailsWith<IllegalStateException> { AgentDocumentRenderer.bridges(document) }
        assertFailsWith<IllegalStateException> {
            AgentDocumentRenderer.bridges(document.copy(limitations = listOf("b"), limitationScopes = listOf(RouteLimitationScope("c", templates = listOf("/c")))))
        }
        assertFailsWith<IllegalStateException> {
            AgentDocumentRenderer.bridges(document.copy(limitations = listOf("a"),
                limitationScopes = listOf(RouteLimitationScope("a", templates = listOf("/a")), RouteLimitationScope("a", templates = listOf("/b")))))
        }
    }
}
