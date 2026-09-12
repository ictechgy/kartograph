package dev.kartograph.analysis

import dev.kartograph.core.CodeGraph
import dev.kartograph.core.EdgeKind
import dev.kartograph.core.GraphEdge
import dev.kartograph.core.GraphNode
import dev.kartograph.core.NodeId
import dev.kartograph.core.NodeKind
import dev.kartograph.core.SourceLocation
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ChangeImpactResourceTest {
    @Test fun `moved source facts do not create a dependency in the other revision`() {
        val target = GraphNode(NodeId("class:p/Target"), "Target", NodeKind.CLASS)
        val before = GraphNode(NodeId("class:p/Caller"), "Caller", NodeKind.CLASS, moduleName = "feature",
            location = SourceLocation("feature/src/test/kotlin/p/Caller.kt"))
        val after = before.copy(moduleName = "app", location = SourceLocation("app/src/main/kotlin/p/Caller.kt"))
        val base = ImpactInput(CodeGraph(listOf(target, before), listOf(GraphEdge(before.id, target.id, EdgeKind.CALL))))
        val current = ImpactInput(CodeGraph(listOf(target, after), emptyList()))
        val report = ChangeImpact.analyze(current, listOf(target.id.value), base = base,
            filters = ImpactFilter(modules = setOf("feature"), affectedFiles = setOf(after.location!!.path),
                testStatus = ImpactTestStatus.TEST))

        val caller = report.affected.single()
        assertEquals(setOf(ImpactRevision.BASE), caller.observedIn)
        assertEquals(setOf(ImpactRevision.BASE, ImpactRevision.CURRENT), caller.presentIn)
        assertEquals(listOf(ImpactRevision.BASE), caller.paths.map { it.revision })
        assertEquals(ImpactTestStatus.UNKNOWN, caller.testStatus)
        assertEquals(listOf(ImpactTestStatus.TEST, ImpactTestStatus.PRODUCTION), caller.facts.map { it.testStatus })
        assertEquals(1, report.navigation.filtered.candidates)
        assertEquals(listOf(ImpactBucket("app", 1), ImpactBucket("feature", 1)), report.navigation.observed.byModule)
        assertEquals(listOf(ImpactBucket("production", 1), ImpactBucket("test", 1)), report.navigation.observed.byTestStatus)
    }

    @Test fun `exhausted path budget retains bounded facts for long paths`() {
        val nodes = (0 until 2000).map { number ->
            GraphNode(NodeId("class:p/N${number.toString().padStart(4, '0')}"), "N$number", NodeKind.CLASS)
        }
        val edges = (1 until nodes.size).map { index ->
            GraphEdge(nodes[index].id, nodes[index - 1].id,
                if (index == 20) EdgeKind.INHERITANCE else EdgeKind.CALL)
        }
        val report = ChangeImpact.analyze(
            ImpactInput(CodeGraph(nodes, edges)), listOf(nodes.first().id.value),
            depth = 2000, limit = 1, offset = 1998, pathLimit = 1,
        )

        assertEquals(1999, report.totalAffected)
        assertEquals(1, report.budgets.pathEdgesUsed)
        assertEquals(1998, report.budgets.pathOmissions)
        val last = report.affected.single()
        assertEquals(nodes.last().id, last.node.id)
        assertTrue(last.paths.isEmpty())
        assertEquals(ImpactRelation.STRUCTURAL, last.relation)
        val omission = last.pathOmissions.single()
        assertEquals(1999, omission.requiredEdges)
        assertEquals(2, omission.edgeKinds.size)
        assertEquals(setOf(EdgeKind.CALL, EdgeKind.INHERITANCE), omission.edgeKinds.toSet())
    }

    @Test fun `directory names without a source root do not prove production status`() {
        val target = GraphNode(NodeId("class:p/Target"), "Target", NodeKind.CLASS)
        val cases = mapOf(
            "main/p/Caller.kt" to ImpactTestStatus.UNKNOWN,
            "debug/p/Caller.kt" to ImpactTestStatus.UNKNOWN,
            "release/p/Caller.kt" to ImpactTestStatus.UNKNOWN,
            "app/src/main/kotlin/p/Caller.kt" to ImpactTestStatus.PRODUCTION,
            "app/src/test/kotlin/p/Caller.kt" to ImpactTestStatus.TEST,
        )
        for ((path, expected) in cases) {
            val caller = GraphNode(NodeId("class:p/Caller"), "Caller", NodeKind.CLASS,
                location = SourceLocation(path))
            val result = ChangeImpact.analyze(
                ImpactInput(CodeGraph(listOf(target, caller), listOf(GraphEdge(caller.id, target.id, EdgeKind.CALL)))),
                listOf(target.id.value),
            )
            assertEquals(expected, result.affected.single().testStatus, path)
        }
    }
}
