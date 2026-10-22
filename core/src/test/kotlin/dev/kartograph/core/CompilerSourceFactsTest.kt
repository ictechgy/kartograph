package dev.kartograph.core

import kotlin.test.*

class CompilerSourceFactsTest {
    private val id = NodeId("method:Calls#zero()V")
    private val owner = NodeId("class:Calls")
    private val file = CompilerEvidenceSource("src/Calls.java", "a".repeat(64))
    private fun graph(): CodeGraph {
        val base = CodeGraph(listOf(GraphNode(owner, "Calls", NodeKind.CLASS), GraphNode(id, "zero", NodeKind.METHOD, jvmModifiers = setOf(JvmModifier.STATIC))), emptyList())
        val caller = CompilerAuthoredCaller(id, file, 0, 20, CompilerSourceCoordinateBasis.JAVAC_UTF16_CHAR_SEQUENCE, "17.0.20", 1, 0,
            AuthoredCallCoverage.COMPLETE, emptyList(), emptyList(), "c".repeat(64), "d".repeat(64), "f".repeat(64))
        val inventory = CompilerCallerInventory("main", "compileJava", "classes", listOf(file),
            listOf(CompilerSourceBody("b0", file, 0, 20, CompilerSourceBodyKind.ORDINARY_METHOD, true, "JAVAC_EXPLICIT", listOf(id))),
            listOf(CompilerInventoryOutput("Calls.class", "b".repeat(64), "Calls")),
            listOf(CompilerInventoryMethod(id, 9, true, CompilerBackendOrigin.SOURCE_BOUND, "b0", "JAVAC_EXPLICIT")),
            CompilerSourceCoordinateBasis.JAVAC_UTF16_CHAR_SEQUENCE, "17.0.20", "c".repeat(64), "d".repeat(64), "e".repeat(64), "f".repeat(64))
        return base.withCompilerAuthoredCalls(listOf(caller)).withCompilerCallerInventories(listOf(inventory))
    }
    @Test fun `source uses authored calls and inventory survive every attachment and projection order`() {
        val authored = graph()
        val output = InputFingerprint("classes", "classes", "1".repeat(64))
        val sourceRoot = CompilerSourceUseRoot(0, output, listOf(id))
        val claim = CompilerSourceUseClaim("main", "javac", "compileJava", listOf(output))
        val unit = CompilerSourceUseUnit(InputFingerprint("compilerEvidence", "calls.tsv", "2".repeat(64)),
            "javac-constants", "17.0.20", "c".repeat(64), "d".repeat(64), listOf(claim),
            CompilerSourceUseProducerCounts(0, 0, 0, 0), CompilerSourceUseImportCounts(0, 0, 0, 0, 0, 0), 0)
        val evidence = CompilerSourceUseEvidence(listOf(sourceRoot), listOf(unit), emptyList())
        val first = authored.withCompilerSourceUses(evidence).withCompilerCallPositions(emptyList())
        val second = CodeGraph(authored.nodes.values, authored.edges).withCompilerSourceUses(evidence)
            .withCompilerCallerInventories(authored.compilerCallerInventories)
            .withCompilerAuthoredCalls(authored.compilerAuthoredCallers).withCompilerCallPositions(emptyList())
        for (captured in listOf(first, second, first.enrichedWith(emptyList()),
            first.enrichedWith(listOf(GraphEdge(id, owner, EdgeKind.REFERENCE))))) {
            assertEquals(evidence, captured.compilerSourceUseEvidence)
            assertEquals(authored.compilerAuthoredCallers, captured.compilerAuthoredCallers)
            assertEquals(authored.compilerCallerInventories, captured.compilerCallerInventories)
            assertTrue(captured.compilerCallPositionsCaptured)
        }
        for (sourceUsesFirst in listOf(false, true)) {
            val selected = CodeGraph(listOf(authored.nodes.getValue(owner)), emptyList())
            val projected = if (sourceUsesFirst) selected.preserveCompilerSourceUsesFrom(first).withCompilerSourceFactsFrom(first)
                else selected.withCompilerSourceFactsFrom(first).preserveCompilerSourceUsesFrom(first)
            assertEquals(evidence.select(setOf(owner)), projected.compilerSourceUseEvidence)
            assertTrue(projected.compilerAuthoredCallers.isEmpty())
            assertFalse(projected.compilerCallerInventories.single().projectionComplete)
            assertEquals(authored.compilerCallerInventories.single().methods, projected.compilerCallerInventories.single().methods)
        }
    }
    @Test fun `presentation changes retain capture identity but cannot replace another capture`() {
        val original = graph()
        val displayed = CodeGraph(original.nodes.values.map { it.copy(moduleName = "display", location = SourceLocation("display/Calls.java")) }, original.edges)
        val preserved = displayed.withCompilerSourceFactsFrom(original)
        assertEquals(original.compilerAuthoredCallers, preserved.compilerAuthoredCallers)
        assertEquals(original.compilerCallerInventories, preserved.compilerCallerInventories)
        assertFailsWith<IllegalArgumentException> { preserved.withCompilerSourceFactsFrom(original) }
        val legacy = CodeGraph(emptyList(), emptyList())
        assertSame(displayed, displayed.withCompilerSourceFactsFrom(legacy))
    }
    @Test fun `removed zero-call method remains accountable and incomplete`() {
        val original = graph()
        val projected = CodeGraph(listOf(original.nodes.getValue(owner)), emptyList()).withCompilerSourceFactsFrom(original)
        assertTrue(projected.compilerAuthoredCallers.isEmpty())
        assertFalse(projected.compilerCallerInventories.single().projectionComplete)
        assertEquals(id, projected.compilerCallerInventories.single().methods.single().id)
        val rebuilt = CodeGraph(projected.nodes.values, projected.edges).withCompilerSourceFactsFrom(projected)
        assertFalse(rebuilt.compilerCallerInventories.single().projectionComplete)
        // 새 identity를 다시 붙여 projection을 자동으로 복구하는 호출은 허용하지 않는다.
        assertFailsWith<IllegalArgumentException> { CodeGraph(original.nodes.values, original.edges).withCompilerSourceFactsFrom(projected) }
    }
    @Test fun `changed caller kind origin bytecode flags or source cannot retain complete coverage`() {
        val original = graph()
        val caller = original.nodes.getValue(id)
        val alternatives = listOf(caller.copy(kind = NodeKind.CONSTRUCTOR), caller.copy(attributes = setOf(NodeAttribute.EXTERNAL_STUB)),
            caller.copy(jvmModifiers = setOf(JvmModifier.ABSTRACT)), caller.copy(synthesized = true),
            caller.copy(sourceDeclaration = SourceDeclarationEvidence(file.path, "0".repeat(64), 0, 1, 1)),
            caller.copy(sourceDeclaration = SourceDeclarationEvidence("other/Calls.java", file.sha256, 0, 1, 1)))
        for (changed in alternatives) {
            val graph = CodeGraph(listOf(original.nodes.getValue(owner), changed), emptyList()).withCompilerSourceFactsFrom(original)
            assertTrue(graph.compilerAuthoredCallers.isEmpty())
            assertFalse(graph.compilerCallerInventories.single().projectionComplete)
        }
        val classChanged = CodeGraph(original.nodes.values.map { if (it.id == owner) it.copy(kind = NodeKind.INTERFACE) else it }, emptyList())
            .withCompilerSourceFactsFrom(original)
        assertFalse(classChanged.compilerCallerInventories.single().projectionComplete)
    }
    @Test fun `renaming aggregation and changed relations cannot borrow compiler completion`() {
        val original = graph()
        assertFailsWith<IllegalArgumentException> {
            CodeGraph(listOf(GraphNode(NodeId("unit:main"), "main", NodeKind.CLASS)), emptyList()).withCompilerSourceFactsFrom(original)
        }
        assertFailsWith<IllegalArgumentException> {
            CodeGraph(original.nodes.values, listOf(GraphEdge(id, id, EdgeKind.CALL))).withCompilerSourceFactsFrom(original)
        }
    }
    @Test fun `target-only source declaration contradiction invalidates project call`() {
        val base = graph()
        val target = NodeId("method:Target#run()V")
        val original = CodeGraph(base.nodes.values + GraphNode(target, "run", NodeKind.METHOD), emptyList())
            .withCompilerAuthoredCalls(base.compilerAuthoredCallers.map { it.copy(explicitCalls = 1,
                calls = listOf(CompilerAuthoredCall(target, AuthoredCallOrigin.PROJECT, "src/Target.java", 1, 2))) })
        val rebuilt = CodeGraph(original.nodes.values.map { if (it.id == target) it.copy(sourceDeclaration =
            SourceDeclarationEvidence("src/Other.java", "0".repeat(64), 0, 1, 1)) else it }, emptyList()).withCompilerSourceFactsFrom(original)
        val row = rebuilt.compilerAuthoredCallers.single()
        assertEquals(AuthoredCallCoverage.INCOMPLETE, row.coverage)
        assertTrue(row.calls.isEmpty())
    }
    @Test fun `conflicting source hash bindings never select a complete capture`() {
        val base = graph()
        val other = NodeId("method:Calls#other()V")
        val original = CodeGraph(base.nodes.values + GraphNode(other, "other", NodeKind.METHOD), emptyList())
            .withCompilerAuthoredCalls(base.compilerAuthoredCallers + base.compilerAuthoredCallers.single().copy(caller = other,
                file = file.copy(sha256 = "0".repeat(64)))).withCompilerCallerInventories(base.compilerCallerInventories)
        val rebuilt = CodeGraph(original.nodes.values, emptyList()).withCompilerSourceFactsFrom(original)
        assertTrue(rebuilt.compilerAuthoredCallers.isEmpty())
        assertFalse(rebuilt.compilerCallerInventories.single().projectionComplete)
    }
    @Test fun `conflicting project target source bindings invalidate both authored calls`() {
        val base = graph()
        val target = NodeId("method:Target#run()V")
        val row = base.compilerAuthoredCallers.single().copy(classifiedNodes = 2, explicitCalls = 2,
            calls = listOf(CompilerAuthoredCall(target, AuthoredCallOrigin.PROJECT, "src/Target.java", 1, 2),
                CompilerAuthoredCall(target, AuthoredCallOrigin.PROJECT, "src/Other.java", 3, 4)))
        val original = CodeGraph(base.nodes.values + GraphNode(target, "run", NodeKind.METHOD), emptyList()).withCompilerAuthoredCalls(listOf(row))
        val rebuilt = CodeGraph(original.nodes.values, emptyList()).withCompilerSourceFactsFrom(original)
        assertEquals(AuthoredCallCoverage.INCOMPLETE, rebuilt.compilerAuthoredCallers.single().coverage)
        assertTrue(rebuilt.compilerAuthoredCallers.single().calls.isEmpty())
    }
    @Test fun `removed or reclassified project target downgrades retained caller`() {
        val zero = graph()
        val target = NodeId("method:Calls#target()V")
        val original = CodeGraph(zero.nodes.values + GraphNode(target, "target", NodeKind.METHOD), emptyList()).withCompilerAuthoredCalls(
            zero.compilerAuthoredCallers.map { it.copy(explicitCalls = 1, calls = listOf(CompilerAuthoredCall(target, AuthoredCallOrigin.PROJECT, file.path, 1, 2))) })
            .withCompilerCallerInventories(zero.compilerCallerInventories)
        for (nodes in listOf(zero.nodes.values, original.nodes.values.map { if (it.id == target) it.copy(attributes = setOf(NodeAttribute.EXTERNAL_STUB)) else it })) {
            val projected = CodeGraph(nodes, emptyList()).withCompilerSourceFactsFrom(original)
            val row = projected.compilerAuthoredCallers.single()
            assertEquals(AuthoredCallCoverage.INCOMPLETE, row.coverage)
            assertEquals(listOf(AuthoredCallReason.FILTERED_TARGET), row.reasons)
            assertTrue(row.calls.isEmpty())
        }
    }
}
