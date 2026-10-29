package dev.kartograph.core

import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/** 최적화 전 selector와 identity 조합을 독립 oracle로 고정한다. */
class ExternalCallOrderingTest {
    private val legacyOrder = Comparator<ExternalCall> { first, second ->
        compareValuesBy(first, second,
            { it.caller.value }, { "method:${it.owner}#${it.name}${it.descriptor}" }, { it.kind.name },
            { it.ordinal }, { it.location?.path.orEmpty() }, { it.location?.line ?: 0 })
    }

    @Test
    fun `seeded pair comparisons and sorts preserve exact legacy ordering`() {
        val values = calls()
        for (first in values) for (second in values) {
            val actual = first.compareTo(second)
            assertEquals(legacyOrder.compare(first, second), actual)
            assertEquals(-actual.compareTo(0), second.compareTo(first).compareTo(0))
        }
        repeat(12) { seed ->
            val shuffled = values.shuffled(Random(seed))
            assertEquals(shuffled.sortedWith(legacyOrder), shuffled.sorted())
        }
        val random = Random(190)
        repeat(10_000) {
            val first = values.random(random)
            val second = values.random(random)
            val third = values.random(random)
            if (first <= second && second <= third) assertTrue(first <= third)
        }
    }

    @Test
    fun `lexical kind concatenated identity and integer extrema retain their old order`() {
        val first = call()
        val kinds = InvocationKind.entries.map { first.copy(kind = it) }
        assertEquals(kinds.sortedBy { it.kind.name }, kinds.sorted())
        val ordinals = listOf(Int.MAX_VALUE, 0, Int.MIN_VALUE, -1, 1).map { first.copy(ordinal = it) }
        assertEquals(ordinals.sortedBy { it.ordinal }, ordinals.sorted())
        // owner를 독립 비교하면 '#'(35)와 '!'(33)의 경계에서 기존 순서가 뒤집힌다.
        val prefix = first.copy(owner = "a")
        val suffix = first.copy(owner = "a!")
        assertTrue(prefix.owner < suffix.owner)
        assertTrue(prefix > suffix)
        // 구분자가 다른 인자에서 와도 합쳐진 identity가 같으면 동률이다.
        val delimiter = first.copy(owner = "a#b", name = "c")
        val sameIdentity = first.copy(owner = "a", name = "b#c")
        assertEquals(0, delimiter.compareTo(sameIdentity))
        assertFalse(delimiter == sameIdentity)
    }

    @Test
    fun `resolution model targets and source column remain outside comparison identity`() {
        val first = call().copy(location = SourceLocation("Caller.kt", 7, 1))
        val ties = listOf(first, first.copy(resolution = CallResolution.RUNTIME_MODEL),
            first.copy(model = "model.v1"), first.copy(resolvedTargets = listOf(first.caller)),
            first.copy(location = SourceLocation("Caller.kt", 7, 2)))
        ties.forEach { assertEquals(0, first.compareTo(it)) }
        assertEquals(ties.reversed(), ties.reversed().sorted())
        val locations = listOf(SourceLocation("Caller.kt", Int.MAX_VALUE), SourceLocation("Caller.kt", 1),
            SourceLocation("Caller.kt"), null)
        val values = locations.map { first.copy(location = it) }
        assertEquals(values.sortedWith(legacyOrder), values.sorted())
        // 빈 경로·0 줄은 public 모델이 허용하지 않으므로 가짜 비교 입력으로 만들지 않는다.
        assertFailsWith<IllegalArgumentException> { SourceLocation("") }
        assertFailsWith<IllegalArgumentException> { SourceLocation("Caller.kt", 0) }
    }

    @Test
    fun `copy recomputes target without changing constructor equality hash or components`() {
        val original = call()
        val copies = listOf(original.copy(), original.copy(owner = "other/Owner"),
            original.copy(name = "renamed"), original.copy(descriptor = "(I)Ljava/lang/String;"),
            original.copy(owner = "Ω/Owner", name = "renamed", descriptor = "()I"))
        for (value in copies) {
            assertEquals(NodeId("method:${value.owner}#${value.name}${value.descriptor}"), value.target)
            val reconstructed = ExternalCall(value.caller, value.owner, value.name, value.descriptor, value.kind,
                value.location, value.ordinal, value.resolvedTargets, value.resolution, value.model, value.declaration)
            assertEquals(reconstructed, value)
            val fields: List<Any?> = listOf(value.caller, value.owner, value.name, value.descriptor, value.kind,
                value.location, value.ordinal, value.resolvedTargets, value.resolution, value.model, value.declaration)
            assertEquals(fields.fold(0) { hash, field -> hash * 31 + (field?.hashCode() ?: 0) }, value.hashCode())
            val (caller, owner, name, descriptor, kind, location, ordinal, targets, resolution, model, declaration) = value
            assertEquals(fields, listOf(caller, owner, name, descriptor, kind, location, ordinal, targets, resolution, model, declaration))
        }
        assertEquals(NodeId("method:lib/Owner#run()V"), original.target)
        assertEquals(1, setOf(original, original.copy()).size)
    }

    @Test
    fun `graph normalization keeps stable distinct calls across repeated enrichment`() {
        val caller = GraphNode(call().caller, "call", NodeKind.METHOD)
        val base = call()
        val tied = base.copy(model = "different")
        val local = base.copy(owner = "app/Caller", name = "call")
        val absent = base.copy(caller = NodeId("method:missing/Caller#call()V"))
        val inputs = listOf(tied, base.copy(ordinal = 2), base, tied, local, absent)
        val expected = listOf(tied, base.copy(ordinal = 2), base).distinct().sortedWith(legacyOrder)
        val graph = CodeGraph(listOf(caller), emptyList(), inputs)
        assertEquals(expected, graph.externalCalls)
        assertEquals(expected, graph.enrichedWith(emptyList()).externalCalls)
        assertEquals(expected, graph.enrichedWith(emptyList(), inputs).externalCalls)
    }

    private fun call(): ExternalCall = ExternalCall(
        NodeId("method:app/Caller#call()V"), "lib/Owner", "run", "()V", InvocationKind.VIRTUAL,
    )

    private fun calls(): List<ExternalCall> {
        val random = Random(190)
        val names = listOf("a", "aa", "a!", "a#", "a$", "a/", "Ω", "😀", "\uD800")
        val descriptors = listOf("()V", "()I", "(I)V", "(Ljava/lang/String;)V")
        val locations = listOf(null, SourceLocation("A.kt"), SourceLocation("A.kt", 1),
            SourceLocation("A.kt", Int.MAX_VALUE), SourceLocation("Ω.kt", 1))
        return List(256) {
            call().copy(caller = NodeId(names.random(random)), owner = names.random(random),
                name = names.random(random), descriptor = descriptors.random(random),
                kind = InvocationKind.entries.random(random), location = locations.random(random),
                ordinal = listOf(Int.MIN_VALUE, -1, 0, 1, Int.MAX_VALUE).random(random))
        } + listOf(call(), call().copy(model = "tie"))
    }
}
