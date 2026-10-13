package dev.kartograph.core

import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

class ExternalCallOrderingTest {
    private val base = ExternalCall(NodeId("method:app/Caller#run()V"), "lib/Api", "value", "()I", InvocationKind.VIRTUAL)

    // 최적화 전 comparator를 별도 oracle로 남겨 키 순서·null·동률 계약을 고정한다.
    private fun original(a: ExternalCall, b: ExternalCall): Int = compareValuesBy(a, b,
        { it.caller.value }, { it.target.value }, { it.kind.name },
        { it.ordinal }, { it.location?.path.orEmpty() }, { it.location?.line ?: 0 })

    @Test
    fun `all kind name location line and ordinal combinations match original exact comparisons`() {
        val locations = listOf(null, SourceLocation("A.kt"), SourceLocation("A.kt", 1),
            SourceLocation("A.kt", Int.MAX_VALUE), SourceLocation("a.kt", 1), SourceLocation("한글.kt", 2))
        val values = InvocationKind.entries.flatMap { kind ->
            listOf("<init>", "value", "value\$default", "값").flatMap { name ->
                listOf(Int.MIN_VALUE, 0, Int.MAX_VALUE).flatMap { ordinal ->
                    locations.map { base.copy(kind = kind, name = name, ordinal = ordinal, location = it) }
                }
            }
        }
        for (a in values) for (b in values) assertEquals(original(a, b), a.compareTo(b))
        assertEquals(values.sortedWith(::original), values.sorted())
    }

    @Test
    fun `target concatenation and enum names retain lexical rather than component or ordinal order`() {
        val targets = listOf("a/A", "a/A!", "a/A\$Nested", "a/AA", "a/A_", "한/클래스")
            .flatMap { owner -> listOf("a", "a!", "a_", "z", "값").flatMap { name ->
                listOf("()I", "(I)V", "(Ljava/lang/String;)V").map { base.copy(owner = owner, name = name, descriptor = it) }
            } }
        for (a in targets) for (b in targets) assertEquals(original(a, b), a.compareTo(b))
        assertTrue(base.copy(owner = "a/A!") < base.copy(owner = "a/A"))
        assertTrue(base.copy(kind = InvocationKind.BOOTSTRAP) < base.copy(kind = InvocationKind.STATIC))
        assertTrue(base.copy(kind = InvocationKind.SPECIAL) < base.copy(kind = InvocationKind.STATIC))
    }

    @Test
    fun `nonordering evidence fields remain stable ties and equality still implies zero`() {
        val call = base.copy(location = SourceLocation("A.kt", 1))
        val values = listOf(call,
            call.copy(location = SourceLocation("A.kt", 1, 99)),
            call.copy(model = "model"),
            call.copy(resolution = CallResolution.NO_PROJECT_TARGET),
            call.copy(resolvedTargets = listOf(NodeId("method:app/Target#value()I"))),
            call.copy(declaration = CallDeclaration(call.target, CallDeclarationScope.EXTERNAL, listOf(call.owner))))
        assertNotEquals(values.first(), values.last())
        for (a in values) for (b in values) {
            assertEquals(0, original(a, b))
            assertEquals(0, a.compareTo(b))
            assertEquals(0, a.compareTo(a.copy()))
        }
        assertEquals(values, values.sorted())
        assertEquals(values.reversed(), values.reversed().sorted())
    }

    @Test
    fun `fixed randomized pairs triples and stable sorting retain original ordering`() {
        val random = Random(0x4b415254)
        val values = List(256) {
            base.copy(caller = NodeId("method:app/C${random.nextInt(6)}#m${random.nextInt(4)}()V"),
                owner = listOf("a/A", "a/A!", "a/A_", "lib/Api", "한/클래스").random(random),
                name = listOf("<init>", "value", "value\$default", "값").random(random),
                descriptor = listOf("()I", "()V", "(I)V").random(random),
                kind = InvocationKind.entries.random(random), ordinal = random.nextInt(),
                location = if (random.nextBoolean()) null else SourceLocation(
                    listOf("A.kt", "a.kt", "dir/File.java", "한글.kt").random(random),
                    if (random.nextBoolean()) null else random.nextInt(1, Int.MAX_VALUE)),
                model = if (random.nextBoolean()) null else "model${random.nextInt(3)}")
        }
        repeat(4096) {
            val a = values.random(random); val b = values.random(random); val c = values.random(random)
            assertEquals(original(a, b), a.compareTo(b))
            assertEquals(original(b, c), b.compareTo(c))
            assertEquals(a.compareTo(b).sign(), -b.compareTo(a).sign())
            if (a <= b && b <= c) assertTrue(a <= c)
            if (a == b) assertEquals(0, a.compareTo(b))
        }
        repeat(8) {
            val shuffled = values.shuffled(random)
            assertEquals(shuffled.sortedWith(::original), shuffled.sorted())
        }
    }

    private fun Int.sign(): Int = compareTo(0)
}
