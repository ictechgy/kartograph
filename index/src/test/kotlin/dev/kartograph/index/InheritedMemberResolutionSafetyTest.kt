package dev.kartograph.index

import dev.kartograph.core.EdgeKind
import dev.kartograph.core.ExternalCall
import dev.kartograph.core.GraphEdge
import dev.kartograph.core.GraphNode
import dev.kartograph.core.InvocationKind
import dev.kartograph.core.JvmModifier
import dev.kartograph.core.NodeKind
import dev.kartograph.core.Visibility
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** javac가 거부하거나 만들지 않는 descriptor·불완전 hierarchy의 안전 경계를 직접 고정한다. */
class InheritedMemberResolutionSafetyTest {
    @Test
    fun `field descriptor is part of lookup identity`() {
        val base = type("Base", fields = listOf(field("Base", "I")))
        val middle = type("Middle", setOf("Base"), fields = listOf(field("Middle", "J")))
        val child = type("Child", setOf("Middle"))
        assertEquals(listOf(field("Base", "I").id), fieldEdges(listOf(base, middle, child), "Child").map { it.target })
    }

    @Test
    fun `interface fields precede superclass fields`() {
        val base = type("Base", fields = listOf(field("Base")))
        val contract = type("Contract", fields = listOf(field("Contract")), isInterface = true)
        val child = type("Child", setOf("Base", "Contract"))
        assertEquals(listOf(field("Contract").id), fieldEdges(listOf(base, contract, child), "Child").map { it.target })
    }

    @Test
    fun `ambiguous or unavailable interfaces do not select superclass field`() {
        val base = type("Base", fields = listOf(field("Base")))
        val left = type("Left", fields = listOf(field("Left")), isInterface = true)
        val right = type("Right", fields = listOf(field("Right")), isInterface = true)
        val child = type("Child", setOf("Base", "Left", "Right"))
        assertTrue(fieldEdges(listOf(base, left, right, child), "Child").isEmpty())
        assertTrue(fieldEdges(listOf(base, left, child), "Child").isEmpty())
    }

    @Test
    fun `cycles terminate and deep hierarchies do not use recursion`() {
        assertTrue(fieldEdges(listOf(type("A", setOf("B")), type("B", setOf("A"))), "A").isEmpty())
        val types = listOf(type("T0", fields = listOf(field("T0")))) + (1..2000).map { type("T$it", setOf("T${it - 1}")) }
        assertEquals(listOf(field("T0").id), fieldEdges(types, "T2000").map { it.target })
        assertEquals(fieldEdges(types, "T2000"), fieldEdges(types.reversed(), "T2000"))
    }

    @Test
    fun `private fields and incompatible nearest methods never fall through`() {
        val base = type("Base", methods = listOf(method("Base", setOf(JvmModifier.STATIC))))
        val middle = type("Middle", setOf("Base"), methods = listOf(method("Middle")))
        val child = type("Child", setOf("Middle"))
        assertTrue(methodReferences(listOf(base, middle, child), "Child", InvocationKind.STATIC).edges.isEmpty())
        val privateField = field("Base").copy(jvmVisibility = Visibility.PRIVATE)
        assertTrue(fieldEdges(listOf(type("Base", fields = listOf(privateField)), type("Child", setOf("Base"))), "Child").isEmpty())
    }

    @Test
    fun `special call eligibility is checked for every caller despite shared target cache`() {
        val base = type("Base", methods = listOf(method("Base")))
        val middle = type("Middle", setOf("Base"))
        val first = caller("First", setOf("Middle"), "Middle", InvocationKind.SPECIAL)
        val second = caller("Second", emptySet(), "Middle", InvocationKind.SPECIAL)
        val result = InheritedMemberReferences(listOf(base, middle, first, second))
        assertEquals(listOf(method("First").id), result.edges.map { it.source })
        assertTrue(result.calls.single { it.caller == method("Second").id }.resolvedTargets.isEmpty())
    }

    @Test
    fun `static interface methods and missing methods are not inherited`() {
        val contract = type("Contract", methods = listOf(method("Contract", setOf(JvmModifier.STATIC))), isInterface = true)
        val child = type("Child", setOf("Contract"))
        assertTrue(methodReferences(listOf(contract, child), "Child", InvocationKind.STATIC).edges.isEmpty())
        assertTrue(methodReferences(listOf(type("Child", setOf("Missing"))), "Child", InvocationKind.STATIC).edges.isEmpty())
    }

    @Test
    fun `unaligned raw edge observations never invent exact call site lines`() {
        val base = type("Base", methods = listOf(method("Base", setOf(JvmModifier.STATIC))))
        val child = type("Child", setOf("Base"))
        val entry = caller("Entry", emptySet(), "Child", InvocationKind.STATIC)
        val first = entry.calls.single()
        val observations = entry.copy(calls = listOf(first, first.copy(ordinal = 1)),
            edges = listOf(entry.edges.single().copy(weight = 2, callSiteLines = listOf(3, 7))))
        val result = InheritedMemberReferences(listOf(base, child, observations))
        assertEquals(2, result.edges.sumOf { it.weight })
        assertTrue(result.edges.all { it.callSiteLines.isEmpty() })
    }

    private fun fieldEdges(facts: List<ClassFacts>, owner: String): List<GraphEdge> {
        val caller = method("Entry")
        val entry = type("Entry", methods = listOf(caller)).copy(edges = listOf(GraphEdge(caller.id, field(owner).id, EdgeKind.FIELD_ACCESS)))
        return InheritedMemberReferences(facts + entry).edges
    }

    private fun methodReferences(facts: List<ClassFacts>, owner: String, kind: InvocationKind) =
        InheritedMemberReferences(facts + caller("Entry", emptySet(), owner, kind))

    private fun caller(owner: String, parents: Set<String>, target: String, kind: InvocationKind): ClassFacts {
        val call = ExternalCall(method(owner).id, target, "run", "()V", kind)
        return type(owner, parents, methods = listOf(method(owner))).copy(
            calls = listOf(call), edges = listOf(GraphEdge(call.caller, call.target, EdgeKind.CALL)),
        )
    }

    private fun field(owner: String, descriptor: String = "I") = GraphNode(
        JvmNodeId.fieldId(owner, "value", descriptor), "value", NodeKind.FIELD, visibility = Visibility.PUBLIC,
    )

    private fun method(owner: String, modifiers: Set<JvmModifier> = emptySet()) = GraphNode(
        JvmNodeId.methodId(owner, "run", "()V"), "run", NodeKind.METHOD, visibility = Visibility.PUBLIC, jvmModifiers = modifiers,
    )

    private fun type(
        owner: String, parents: Set<String> = emptySet(), fields: List<GraphNode> = emptyList(),
        methods: List<GraphNode> = emptyList(), isInterface: Boolean = false,
    ) = ClassFacts(owner, listOf(GraphNode(
        JvmNodeId.classId(owner), owner, if (isInterface) NodeKind.INTERFACE else NodeKind.CLASS,
        jvmSignature = owner, supertypes = parents,
    )) + fields + methods, emptyList())
}
