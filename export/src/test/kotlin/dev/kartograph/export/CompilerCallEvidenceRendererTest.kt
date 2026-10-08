package dev.kartograph.export

import dev.kartograph.analysis.ReachabilityAnalyzer
import dev.kartograph.analysis.SymbolQuery
import dev.kartograph.analysis.SymbolQueryCandidate
import dev.kartograph.analysis.SymbolQueryDocument
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
import dev.kartograph.core.SourceLocation
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class CompilerCallEvidenceRendererTest {
    @Test
    fun `graph aware query appends exact compiler references after bytecode references`() {
        val caller = GraphNode(NodeId("method:app/Caller#calls()V"), "calls", NodeKind.METHOD,
            location = SourceLocation("Caller.java", 3))
        val subject = GraphNode(NodeId("method:app/Target#run()V"), "run", NodeKind.METHOD,
            location = SourceLocation("Target.java", 1))
        val dependency = GraphNode(NodeId("method:app/Dependency#read()V"), "read", NodeKind.METHOD,
            location = SourceLocation("Dependency.kt", 1))
        val member = GraphNode(NodeId("field:app/Target#value:I"), "value", NodeKind.FIELD)
        val graph = CodeGraph(listOf(caller, subject, dependency, member), listOf(
            GraphEdge(caller.id, subject.id, EdgeKind.CALL, weight = 2, origin = EdgeOrigin.BYTECODE, callSiteLines = listOf(5, 7)),
            GraphEdge(subject.id, dependency.id, EdgeKind.CALL, origin = EdgeOrigin.BYTECODE, callSiteLines = listOf(9)),
            GraphEdge(subject.id, member.id, EdgeKind.MEMBER),
        )).withCompilerCallPositions(listOf(
            LocatedCompilerReference(caller.id, subject.id,
                CompilerEvidenceSource("src/main/java/app/Caller.java", "a".repeat(64)),
                "javac-constants", "17.0.20+0", CompilerSourceCoordinateBasis.JAVAC_UTF16_CHAR_SEQUENCE,
                90, 93, 5, 21),
            LocatedCompilerReference(caller.id, subject.id,
                CompilerEvidenceSource("build/generated/app/Caller.kt", "b".repeat(64)),
                "kotlin-constants", "2.4.10", CompilerSourceCoordinateBasis.KOTLIN_UTF16_NORMALIZED_SOURCE,
                52, 58, 4, 21, generated = true),
            LocatedCompilerReference(subject.id, dependency.id,
                CompilerEvidenceSource("src/main/kotlin/app/Target.kt", "c".repeat(64)),
                "kotlin-constants", "2.4.10", CompilerSourceCoordinateBasis.KOTLIN_UTF16_NORMALIZED_SOURCE,
                120, 124, 8, 13),
        ))
        val document = SymbolQuery.query(graph, ReachabilityAnalyzer.analyze(graph, emptyList()), subject.id.value,
            listOf("fixture-limit"), depth = 2)

        val parsed = McpJsonCodec.parse(AgentDocumentRenderer.query(document, graph)) as Map<*, *>
        val result = parsed["result"] as Map<*, *>
        val usedBy = (result["usedBy"] as List<*>).single() as Map<*, *>
        val usedReferences = usedBy["references"] as List<*>
        assertEquals(listOf("bytecode", "bytecode", "compiler", "compiler"),
            usedReferences.map { (it as Map<*, *>)["origin"] })
        val compilerReferences = usedReferences.drop(2).map { it as Map<*, *> }
        assertEquals(listOf("build/generated/app/Caller.kt", "src/main/java/app/Caller.java"),
            compilerReferences.map { (it["location"] as Map<*, *>)["path"] })
        val java = compilerReferences.single { it["collector"] == "javac-constants" }
        assertEquals("utf16-compiler-source", java["offsetEncoding"])
        assertEquals("javacUtf16CharSequence", java["coordinateBasis"])
        assertEquals("javac-constants", java["collector"])
        assertEquals("17.0.20+0", java["compilerVersion"])
        assertEquals("a".repeat(64), java["sourceSha256"])
        assertEquals(false, java["generated"])
        val javaLocation = java["location"] as Map<*, *>
        assertEquals("src/main/java/app/Caller.java", javaLocation["path"])
        assertEquals(5L, javaLocation["line"])
        assertEquals(21L, javaLocation["column"])
        assertEquals(90L, java["offset"])
        assertEquals(93L, java["endOffset"])
        val kotlin = compilerReferences.single { it["collector"] == "kotlin-constants" }
        assertEquals("kotlinUtf16NormalizedSource", kotlin["coordinateBasis"])
        assertEquals(true, kotlin["generated"])

        val dependsOn = (result["dependsOn"] as List<*>).single() as Map<*, *>
        assertEquals(listOf("bytecode", "compiler"),
            (dependsOn["references"] as List<*>).map { (it as Map<*, *>)["origin"] })
        val renderedMember = (result["members"] as List<*>).single() as Map<*, *>
        assertFalse(renderedMember.containsKey("references"))
        assertEquals(listOf("fixture-limit"), parsed["limitations"])
    }

    @Test
    fun `graph aware query does not project positions to transitive or unresolved documents`() {
        val first = GraphNode(NodeId("method:app/A#call()V"), "call", NodeKind.METHOD)
        val middle = GraphNode(NodeId("method:app/B#call()V"), "call", NodeKind.METHOD)
        val last = GraphNode(NodeId("method:app/C#call()V"), "call", NodeKind.METHOD)
        val graph = CodeGraph(listOf(first, middle, last), listOf(
            GraphEdge(first.id, middle.id, EdgeKind.CALL),
            GraphEdge(middle.id, last.id, EdgeKind.CALL),
        )).withCompilerCallPositions(listOf(
            LocatedCompilerReference(first.id, middle.id, CompilerEvidenceSource("A.kt", "d".repeat(64)),
                "kotlin-constants", "2.4.10", CompilerSourceCoordinateBasis.KOTLIN_UTF16_NORMALIZED_SOURCE,
                10, 14, 1, 11),
            LocatedCompilerReference(middle.id, last.id, CompilerEvidenceSource("B.kt", "e".repeat(64)),
                "kotlin-constants", "2.4.10", CompilerSourceCoordinateBasis.KOTLIN_UTF16_NORMALIZED_SOURCE,
                20, 24, 2, 7),
        ))
        val found = SymbolQuery.query(graph, ReachabilityAnalyzer.analyze(graph, emptyList()), last.id.value,
            emptyList(), depth = 2)
        val parsed = McpJsonCodec.parse(AgentDocumentRenderer.query(found, graph)) as Map<*, *>
        val usedBy = ((parsed["result"] as Map<*, *>)["usedBy"] as List<*>).map { it as Map<*, *> }
        assertTrue(usedBy.single { it["depth"] == 1L }.containsKey("references"))
        assertFalse(usedBy.single { it["depth"] == 2L }.containsKey("references"))

        val notFound = SymbolQuery.query(graph, ReachabilityAnalyzer.analyze(graph, emptyList()), "Missing", listOf("same"))
        assertEquals(AgentDocumentRenderer.query(notFound), AgentDocumentRenderer.query(notFound, graph))
        val ambiguous = SymbolQueryDocument("ambiguous", "call", "symbol", listOf("same"), candidates = listOf(
            SymbolQueryCandidate("app.A.call", first.id.value), SymbolQueryCandidate("app.B.call", middle.id.value),
        ))
        assertEquals(AgentDocumentRenderer.query(ambiguous), AgentDocumentRenderer.query(ambiguous, graph))
        val capturedEmpty = CodeGraph(emptyList(), emptyList()).withCompilerCallPositions(emptyList())
        val golden = SymbolQueryDocument("notFound", "Missing", "symbol", listOf("fixture"))
        assertEquals(ONE_ARGUMENT_GOLDEN, AgentDocumentRenderer.query(golden))
        assertEquals(ONE_ARGUMENT_GOLDEN, AgentDocumentRenderer.query(golden, capturedEmpty))
        val oneNode = CodeGraph(listOf(first), emptyList()).withCompilerCallPositions(emptyList())
        val capturedFound = SymbolQuery.query(oneNode, ReachabilityAnalyzer.analyze(oneNode, emptyList()), first.id.value, emptyList())
        assertEquals(AgentDocumentRenderer.query(capturedFound), AgentDocumentRenderer.query(capturedFound, oneNode))
    }

    private companion object {
        const val ONE_ARGUMENT_GOLDEN: String =
            "{\"level\": \"symbol\", \"limitations\": [\"fixture\"], \"requested\": \"Missing\", \"status\": \"notFound\"}\n"
    }
}
