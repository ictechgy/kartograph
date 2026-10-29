package dev.kartograph.core

import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** 선택자 boxing 제거 후에도 기존 비교 순서와 동률 의미가 그대로인지 검증한다. */
class GraphEdgeOrderingTest {
    private val legacyOrder = Comparator<GraphEdge> { first, second ->
        compareValuesBy(first, second, GraphEdge::source, GraphEdge::target, GraphEdge::kind, { it.origin.name })
    }

    @Test
    fun `kind uses enum order while origin uses lexical name order`() {
        val call = GraphEdge(NodeId("a"), NodeId("b"), EdgeKind.CALL)
        val annotation = call.copy(kind = EdgeKind.ANNOTATION)
        assertTrue(call < annotation)
        assertTrue(EdgeKind.CALL.name > EdgeKind.ANNOTATION.name)
        val dispatch = call.copy(origin = EdgeOrigin.DISPATCH_MODEL)
        val metadata = call.copy(origin = EdgeOrigin.KOTLIN_METADATA)
        assertTrue(dispatch < metadata)
        assertTrue(EdgeOrigin.DISPATCH_MODEL > EdgeOrigin.KOTLIN_METADATA)
        val all = EdgeKind.entries.flatMap { kind -> EdgeOrigin.entries.map { origin -> call.copy(kind = kind, origin = origin) } }
        assertEquals(all.sortedWith(legacyOrder), all.sorted())
    }

    @Test
    fun `weight and call site lines remain outside comparison identity`() {
        val first = GraphEdge(NodeId("a"), NodeId("b"), EdgeKind.CALL, 2, callSiteLines = listOf(1, 2))
        val second = first.copy(weight = Int.MAX_VALUE, callSiteLines = listOf(65535))
        assertFalse(first == second)
        assertEquals(0, first.compareTo(second))
        assertEquals(0, second.compareTo(first))
    }

    @Test
    fun `seeded pairs preserve exact legacy comparison results and comparator laws`() {
        val values = edges(512)
        for (first in values) for (second in values) {
            val actual = first.compareTo(second)
            assertEquals(legacyOrder.compare(first, second), actual)
            assertEquals(-actual.compareTo(0), second.compareTo(first).compareTo(0))
        }
        values.forEach { assertEquals(0, it.compareTo(it)) }
        val random = Random(149)
        repeat(20_000) {
            val first = values.random(random)
            val second = values.random(random)
            val third = values.random(random)
            if (first <= second && second <= third) assertTrue(first <= third)
        }
    }

    @Test
    fun `shuffled sorts preserve source target prefixes unicode and stable ties`() {
        val values = edges(512)
        repeat(20) { seed ->
            val shuffled = values.shuffled(Random(seed))
            assertEquals(shuffled.sortedWith(legacyOrder), shuffled.sorted())
        }
    }

    private fun edges(size: Int): List<GraphEdge> {
        val ids = listOf("a", "aa", "a!", "a#", "a$", "a/", "a\u0000", "Ω", "😀", "\uD800", "method:A#x()V", "method:A#xx()V")
        val random = Random(149)
        return List(size) {
            val kind = EdgeKind.entries.random(random)
            val origin = EdgeOrigin.entries.random(random)
            val weight = random.nextInt(1, 4)
            val lines = if (kind == EdgeKind.CALL && origin == EdgeOrigin.BYTECODE && weight > 1) listOf(1, 65535) else emptyList()
            GraphEdge(NodeId(ids.random(random)), NodeId(ids.random(random)), kind, weight, origin, lines)
        }
    }
}
