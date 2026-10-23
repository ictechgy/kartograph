package dev.kartograph.index

import dev.kartograph.core.*
import kotlin.test.*

class DispatchExpansionEquivalenceTest {
    private val caller = JvmNodeId.methodId("p/Entry", "run", "()V")
    private val proof = JvmNodeId.methodId("p/Proof", "run", "()V")
    private val target = JvmNodeId.methodId("p/Aa", "run", "()V")
    private val hierarchy = ClassHierarchy(
        mapOf("ext/Child" to setOf("ext/Parent"), "ext/Parent" to emptySet(),
            "ext/Final" to emptySet(), "java/lang/Object" to emptySet()),
        mapOf("ext/Parent" to listOf(HierarchyMethod("run", "()V", Visibility.PUBLIC)),
            "ext/Child" to emptyList(), "ext/Final" to listOf(HierarchyMethod("run", "()V", Visibility.PUBLIC, isFinal = true))),
        emptyMap(), mapOf("ext/Child" to true, "ext/Parent" to true, "ext/Final" to false, "java/lang/Object" to false))

    @Test fun repeatedOrdinalsOverlappingTargetsAndBudgetsMatchPerCallExpansion() {
        assertEquals(JvmNodeId.methodId("p/Aa", "run", "()V").hashCode(),
            JvmNodeId.methodId("p/BB", "run", "()V").hashCode())
        for (repeat in listOf(0, 1, 17, 97)) for (limit in listOf(null, 0, 1, 2, 3, 4)) {
            val (graph, internal) = fixture(repeat)
            val reference = LegacyDispatchExpansion.enrichBounded(graph, hierarchy, limit, internal)
            val actual = ExternalDispatchIndexer.enrichBounded(graph, hierarchy, limit, internal)
            assertEquals(reference.overLimitCalls, actual.overLimitCalls)
            assertGraph(reference.graph, actual.graph)
            assertEquals(graph.compilerAuthoredCallers, actual.graph.compilerAuthoredCallers)
            assertEquals(graph.compilerCallerInventories, actual.graph.compilerCallerInventories)
            assertEquals(graph.compilerSourceUseEvidence, actual.graph.compilerSourceUseEvidence)
            assertEquals(graph.locatedCompilerReferences, actual.graph.locatedCompilerReferences)
            assertEquals(graph.compilerCallPositionsCaptured, actual.graph.compilerCallPositionsCaptured)
            val again = ExternalDispatchIndexer.enrichBounded(actual.graph, hierarchy, limit, internal)
            assertGraph(actual.graph, again.graph)
            if (limit == null || limit >= 3) {
                assertEquals(0, actual.overLimitCalls)
                val weights = actual.graph.edges.filter { it.source == caller && it.origin == EdgeOrigin.DISPATCH_MODEL }
                    .associate { it.target to it.weight }
                assertEquals(if (repeat == 0) emptyMap() else mapOf(
                    JvmNodeId.methodId("p/Base", "run", "()V") to repeat * 2,
                    target to repeat * 3,
                    JvmNodeId.methodId("p/BB", "run", "()V") to repeat * 3), weights)
            } else {
                assertEquals(repeat * 3, actual.overLimitCalls)
                assertFalse(actual.graph.edges.any { it.source == caller && it.origin == EdgeOrigin.DISPATCH_MODEL })
            }
            val reordered = CodeGraph(graph.nodes.values.reversed(), graph.edges.reversed(), graph.externalCalls.reversed())
                .withCompilerSourceFactsFrom(graph).preserveCompilerSourceUsesFrom(graph)
                .withCompilerCallPositions(graph.locatedCompilerReferences)
            assertGraph(actual.graph, ExternalDispatchIndexer.enrichBounded(reordered, hierarchy, limit, internal.reversed()).graph)
        }
    }

    @Test fun excludedInvocationKindsAndRuntimeEvidenceDoNotGainDerivedEdges() {
        val (graph, _) = fixture(3)
        val excluded = listOf(InvocationKind.STATIC, InvocationKind.SPECIAL, InvocationKind.BOOTSTRAP).mapIndexed { i, kind ->
            ExternalCall(caller, "ext/Parent", "run", "()V", kind, ordinal = i)
        } + ExternalCall(caller, "ext/Parent", "run", "()V", InvocationKind.INTERFACE,
            resolvedTargets = listOf(target), resolution = CallResolution.RUNTIME_MODEL, ordinal = 8)
        val input = CodeGraph(graph.nodes.values, graph.edges, excluded)
        val result = ExternalDispatchIndexer.enrichBounded(input, hierarchy, null)
        assertEquals(0, result.overLimitCalls)
        assertFalse(result.graph.edges.any { it.origin == EdgeOrigin.DISPATCH_MODEL })
        assertEquals(input.externalCalls, result.graph.externalCalls)
        assertTrue(result.graph.edges.any { it.origin == EdgeOrigin.RUNTIME_MODEL && it.weight == 7 })
    }

    @Test fun collidingCallersAndDuplicateProjectCallsKeepIndependentWeights() {
        val (base, _) = fixture(0)
        val left = target
        val right = JvmNodeId.methodId("p/BB", "run", "()V")
        assertEquals(left.hashCode(), right.hashCode())
        val graph = CodeGraph(base.nodes.values, base.edges)
            .withCompilerSourceFactsFrom(base).preserveCompilerSourceUsesFrom(base)
            .withCompilerCallPositions(base.locatedCompilerReferences)
        val leftCall = ExternalCall(left, "p/Base", "run", "()V", InvocationKind.VIRTUAL, ordinal = 3)
        val rightCall = leftCall.copy(caller = right)
        val calls = listOf(leftCall, rightCall, leftCall, rightCall, rightCall)
        val expected = LegacyDispatchExpansion.enrichBounded(graph, hierarchy, null, calls)
        val actual = ExternalDispatchIndexer.enrichBounded(graph, hierarchy, null, calls)
        assertGraph(expected.graph, actual.graph)
        assertEquals(2, actual.graph.edges.single { it.source == left && it.target == right && it.origin == EdgeOrigin.DISPATCH_MODEL }.weight)
        assertEquals(3, actual.graph.edges.single { it.source == right && it.target == left && it.origin == EdgeOrigin.DISPATCH_MODEL }.weight)
        assertEquals(2, actual.graph.edges.single { it.source == left && it.target == left && it.origin == EdgeOrigin.DISPATCH_MODEL }.weight)
        assertGraph(actual.graph, ExternalDispatchIndexer.enrichBounded(graph, hierarchy, null, calls.reversed()).graph)
    }

    private fun fixture(repeat: Int): Pair<CodeGraph, List<ExternalCall>> {
        val nodes = listOf("p/Base", "p/Aa", "p/BB", "p/Entry", "p/Proof").flatMap { owner ->
            listOf(GraphNode(JvmNodeId.classId(owner), owner, NodeKind.CLASS, jvmSignature = owner,
                supertypes = when (owner) { "p/Base" -> setOf("ext/Child"); "p/Aa", "p/BB" -> setOf("p/Base"); else -> emptySet() }),
                GraphNode(JvmNodeId.methodId(owner, "run", "()V"), "run", NodeKind.METHOD,
                    jvmVisibility = Visibility.PUBLIC, jvmModifiers = if (owner == "p/Proof") setOf(JvmModifier.STATIC) else emptySet()))
        }
        fun calls(owner: String, kind: InvocationKind) = List(repeat) { ordinal ->
            ExternalCall(caller, owner, "run", "()V", kind, SourceLocation("Entry.java", ordinal + 1), ordinal = ordinal)
        }
        val external = calls("ext/Parent", InvocationKind.INTERFACE) + calls("ext/Child", InvocationKind.INTERFACE) +
            calls("missing/Type", InvocationKind.VIRTUAL) + calls("ext/Final", InvocationKind.VIRTUAL)
        val internal = calls("p/Base", InvocationKind.VIRTUAL)
        val base = CodeGraph(nodes, listOf(
            GraphEdge(proof, target, EdgeKind.CALL),
            GraphEdge(caller, JvmNodeId.methodId("p/Base", "run", "()V"), EdgeKind.CALL),
            GraphEdge(caller, target, EdgeKind.OVERRIDE, 9, EdgeOrigin.DISPATCH_MODEL),
            GraphEdge(caller, target, EdgeKind.OVERRIDE, 7, EdgeOrigin.RUNTIME_MODEL)), external)
        return withCompilerFacts(base) to internal
    }

    private fun withCompilerFacts(graph: CodeGraph): CodeGraph {
        val file = CompilerEvidenceSource("src/Proof.java", "a".repeat(64))
        val authored = CompilerAuthoredCaller(proof, file, 0, 20, CompilerSourceCoordinateBasis.JAVAC_UTF16_CHAR_SEQUENCE,
            "17.0.20", 1, 1, AuthoredCallCoverage.COMPLETE,
            emptyList(), listOf(CompilerAuthoredCall(target, AuthoredCallOrigin.PROJECT, "src/Aa.java", 1, 2)),
            "c".repeat(64), "d".repeat(64), "f".repeat(64))
        val inventory = CompilerCallerInventory("main", "compileJava", "classes", listOf(file),
            listOf(CompilerSourceBody("b0", file, 0, 20, CompilerSourceBodyKind.ORDINARY_METHOD, true, "JAVAC_EXPLICIT", listOf(proof))),
            listOf(CompilerInventoryOutput("p/Proof.class", "b".repeat(64), "p/Proof")),
            listOf(CompilerInventoryMethod(proof, 9, true, CompilerBackendOrigin.SOURCE_BOUND, "b0", "JAVAC_EXPLICIT")),
            CompilerSourceCoordinateBasis.JAVAC_UTF16_CHAR_SEQUENCE, "17.0.20", "c".repeat(64), "d".repeat(64), "e".repeat(64), "f".repeat(64))
        val output = InputFingerprint("classes", "classes", "1".repeat(64))
        val root = CompilerSourceUseRoot(0, output, listOf(proof, target))
        val unit = CompilerSourceUseUnit(InputFingerprint("compilerEvidence", "calls.tsv", "2".repeat(64)),
            "javac-constants", "17.0.20", "c".repeat(64), "d".repeat(64),
            listOf(CompilerSourceUseClaim("main", "javac", "compileJava", listOf(output))),
            CompilerSourceUseProducerCounts(1, 1, 0, 0), CompilerSourceUseImportCounts(1, 0, 0, 0, 0, 0), 0)
        val use = CompilerSourceUse(unit.id, proof, target, root.id, root.id, CompilerSourceUseKind.INVOCATION_SELECTION,
            file, "javac-constants", "17.0.20", CompilerSourceCoordinateBasis.JAVAC_UTF16_CHAR_SEQUENCE, 1, 2, 1, 2, false)
        val position = LocatedCompilerReference(proof, target, file, "javac-constants", "17.0.20",
            CompilerSourceCoordinateBasis.JAVAC_UTF16_CHAR_SEQUENCE, 1, 2, 1, 2)
        return graph.withCompilerAuthoredCalls(listOf(authored)).withCompilerCallerInventories(listOf(inventory))
            .withCompilerSourceUses(CompilerSourceUseEvidence(listOf(root), listOf(unit), listOf(use)))
            .withCompilerCallPositions(listOf(position))
    }

    private fun assertGraph(expected: CodeGraph, actual: CodeGraph) {
        assertEquals(expected.nodes, actual.nodes)
        assertEquals(expected.edges, actual.edges)
        assertEquals(expected.externalCalls, actual.externalCalls)
        assertEquals(expected.serviceProviders, actual.serviceProviders)
        assertEquals(expected.enclosures, actual.enclosures)
        assertEquals(expected.callbackArguments, actual.callbackArguments)
        assertEquals(expected.parameterUses, actual.parameterUses)
        assertEquals(expected.lambdaEscapes, actual.lambdaEscapes)
        assertEquals(expected.compilerAuthoredCallers, actual.compilerAuthoredCallers)
        assertEquals(expected.compilerCallerInventories, actual.compilerCallerInventories)
        assertEquals(expected.compilerSourceUseEvidence, actual.compilerSourceUseEvidence)
        assertEquals(expected.locatedCompilerReferences, actual.locatedCompilerReferences)
        assertEquals(expected.compilerCallPositionsCaptured, actual.compilerCallPositionsCaptured)
    }
}
