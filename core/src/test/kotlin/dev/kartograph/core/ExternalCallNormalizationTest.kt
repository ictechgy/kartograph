package dev.kartograph.core

import kotlin.test.Test
import kotlin.test.assertFailsWith
import kotlin.test.assertEquals
import kotlin.test.assertNotSame
import kotlin.test.assertSame
import kotlin.test.assertTrue

class ExternalCallNormalizationTest {
    private val caller = NodeId("method:app/Caller#run()V")
    private val first = NodeId("method:app/A#run()V")
    private val second = NodeId("method:app/B#run()V")
    private val missing = NodeId("method:missing/Target#run()V")
    private val nodes = listOf(caller, first, second).map { GraphNode(it, "run", NodeKind.METHOD) }

    @Test
    fun `shared candidate input is normalized once to an isolated canonical snapshot`() {
        val mutable = mutableListOf(second, missing, first, second)
        val shared = CountingList(mutable)
        val graph = CodeGraph(nodes, emptyList(), listOf(call(shared, 1), call(shared, 0), call(shared, 0)))
        val canonical = graph.externalCalls.first().resolvedTargets

        assertEquals(listOf(first, second), canonical)
        assertEquals(2, graph.externalCalls.size)
        assertSame(canonical, graph.externalCalls.last().resolvedTargets)
        assertNotSame(mutable, canonical)
        assertNotSame(shared, canonical)
        assertEquals(mutable.size, shared.reads)
        val hash = canonical.hashCode()
        mutable.clear()
        assertEquals(listOf(first, second), canonical)
        assertEquals(hash, canonical.hashCode())
    }

    @Test
    fun `replacement snapshots mutated inputs again and never changes the previous graph`() {
        val candidates = mutableListOf(second, first, second, missing)
        val raw = call(candidates)
        val original = CodeGraph(nodes, emptyList(), listOf(raw))
        candidates.clear()
        candidates += second
        var iterations = 0
        val replacement = object : Iterable<ExternalCall> {
            override fun iterator(): Iterator<ExternalCall> {
                check(++iterations == 1)
                return listOf(raw.copy(model = "replacement"), raw.copy(model = "replacement")).iterator()
            }
        }
        val changed = original.enrichedWith(emptyList(), replacement)
        candidates.clear()

        assertEquals(1, iterations)
        assertEquals(listOf(first, second), original.externalCalls.single().resolvedTargets)
        assertEquals(listOf(second), changed.externalCalls.single().resolvedTargets)
        assertEquals("replacement", changed.externalCalls.single().model)
        assertTrue(changed.enrichedWith(emptyList(), emptyList()).externalCalls.isEmpty())
        assertEquals(original.externalCalls, original.enrichedWith(emptyList()).externalCalls)
    }

    @Test
    fun `candidate reuse is local to the current graph and revalidates pruning`() {
        val source = CodeGraph(nodes, emptyList(), listOf(call(listOf(second, first), 0), call(listOf(second, first), 1)))
        val pruned = CodeGraph(nodes.filter { it.id != second }, emptyList(), source.externalCalls)
        val noCandidates = CodeGraph(nodes.filter { it.id == caller }, emptyList(), pruned.externalCalls)
        val noCaller = CodeGraph(nodes.filter { it.id != caller }, emptyList(), source.externalCalls)

        assertTrue(source.externalCalls.all { it.resolvedTargets == listOf(first, second) })
        assertTrue(pruned.externalCalls.all { it.resolvedTargets == listOf(first) })
        assertTrue(noCandidates.externalCalls.all { it.resolvedTargets.isEmpty() })
        assertTrue(noCaller.externalCalls.isEmpty())
    }

    @Test
    fun `canonical candidate lists retain ordinary list equality hash and access contracts`() {
        val graph = CodeGraph(nodes, emptyList(), listOf(call(listOf(second, first))))
        val canonical = graph.externalCalls.single().resolvedTargets
        val ordinary = arrayListOf(first, second)

        assertEquals(ordinary, canonical)
        assertEquals(canonical, ordinary)
        assertEquals(ordinary.hashCode(), canonical.hashCode())
        assertEquals(ordinary, canonical.toList())
        assertEquals(listOf(second), canonical.subList(1, 2))
        assertEquals(second, canonical.listIterator(2).previous())
        assertEquals(call(ordinary), graph.externalCalls.single())
        assertEquals(call(ordinary).hashCode(), graph.externalCalls.single().hashCode())
        assertEquals(1, setOf(call(ordinary), graph.externalCalls.single()).size)
        @Suppress("PLATFORM_CLASS_MAPPED_TO_KOTLIN")
        val mutation = assertFailsWith<java.lang.reflect.InvocationTargetException> {
            java.util.List::class.java.getMethod("set", Int::class.javaPrimitiveType, Any::class.java)
                .invoke(canonical, 0, missing)
        }
        assertTrue(mutation.cause is UnsupportedOperationException)
        assertEquals(ordinary, canonical)
        assertEquals(ordinary.hashCode(), canonical.hashCode())
    }

    @Test
    fun `replacement iterable is consumed before candidate snapshots just as in the constructor`() {
        val candidates = mutableListOf(first)
        val replacements = sequence {
            yield(call(candidates, 0))
            candidates.clear()
            candidates += second
            yield(call(candidates, 1))
        }.asIterable()
        val graph = CodeGraph(nodes, emptyList()).enrichedWith(emptyList(), replacements)

        assertEquals(2, graph.externalCalls.size)
        assertTrue(graph.externalCalls.all { it.resolvedTargets == listOf(second) })
    }

    @Test
    fun `normalization preserves raw operands resolution model and external stub rules`() {
        val raw = call(listOf(second, missing, first, second)).copy(
            location = SourceLocation("Caller.kt", 7), model = "runtime-model", resolution = CallResolution.RUNTIME_MODEL,
        )
        val internal = raw.copy(owner = "app/A", resolvedTargets = raw.resolvedTargets)
        val stub = raw.copy(owner = "library/Stub", resolvedTargets = raw.resolvedTargets)
        val withStub = nodes + GraphNode(stub.target, "run", NodeKind.METHOD, attributes = setOf(NodeAttribute.EXTERNAL_STUB))
        val graph = CodeGraph(withStub, emptyList(), listOf(raw, raw.copy(resolvedTargets = listOf(first, second)), internal, stub))

        assertEquals(listOf(raw.copy(resolvedTargets = listOf(first, second)), stub.copy(resolvedTargets = listOf(first, second))).sorted(),
            graph.externalCalls)
        assertEquals(raw.target, graph.externalCalls.single { it.owner == raw.owner }.target)
    }

    private fun call(targets: List<NodeId>, ordinal: Int = 0): ExternalCall = ExternalCall(
        caller, "library/Api", "run", "()V", InvocationKind.INTERFACE,
        ordinal = ordinal, resolvedTargets = targets, resolution = CallResolution.PROJECT_CANDIDATES,
    )

    private class CountingList(private val source: List<NodeId>) : AbstractList<NodeId>() {
        var reads: Int = 0
            private set
        override val size: Int get() = source.size
        override fun get(index: Int): NodeId = source[index].also { reads++ }
    }
}
