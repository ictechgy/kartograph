package dev.kartograph.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

class CompilerSourceUseTest {
    private val caller = NodeId("method:p/C#caller()V")
    private val target = NodeId("method:p/C#target()V")
    private val output = InputFingerprint("classes", "build/classes", "4".repeat(64))
    private val root = CompilerSourceUseRoot(0, output, listOf(caller, target))
    private val claim = CompilerSourceUseClaim("sample:main", "kotlin", ":compileKotlin", listOf(output))

    @Test
    fun `frozen length prefixed UTF8 root unit and use golden identities`() {
        assertEquals("c9cf1c4c047c44444a8c94c23cfa7510aef75c44f73cbc9e7d87d954f6a79ded",
            SourceUseCanonical.hash(sequenceOf("file", "adb722472d18f8c7eee79708e0a9de90ce877d1a5d21428d45d3e3737f4bc153")))
        assertEquals("27820bfb841b30e3616450d967d23515321a79ce5a089c9d85841948e135d12e", root.id)
        assertEquals("377d89ec48c4a4e98bd7927cc2be86346636912705b7effe30a6a36e51259910",
            CompilerSourceUseRoot(0, output.copy(path = "build/other-classes"), listOf(caller, target)).id)
        val unit = unit()
        assertEquals("0fc1de944f306cb34f1720a88afc626d68db87999606dc0c10faebd61c0ff346", unit.id)
        assertEquals("8994f40ebbeb8f0ea2379b873ac48e3a4c6ec070974a8e939af460fc91d0ef09", use(unit).id)
    }

    @Test
    fun `encoded method identity requires structural delimiters and preserves legal parentheses`() {
        for (id in listOf("method:p/C", "method:p/C#x", "method:p/C#x(", "method:p/C#x()V\n")) {
            assertFailsWith<IllegalArgumentException> { CompilerSourceUseRoot(0, output, listOf(NodeId(id))) }
        }
        for (id in listOf("method:p/C#<init>([Ljava/lang/String;)V", "method:p/C(D)#call()V",
            "method:p/C#call(odd)()V", "method:p/C#call(Lp/A(B);)[Lp/C(D);",
            "method:p/C#" + "(".repeat(10_000) + "()V")) {
            CompilerSourceUseRoot(0, output, listOf(NodeId(id)))
        }
    }

    @Test
    fun `all mutable collection boundaries are defensive and unmodifiable`() {
        val owners = mutableListOf(target, caller)
        val owned = CompilerSourceUseRoot(0, output, owners)
        val outputs = mutableListOf(output)
        val scoped = CompilerSourceUseClaim("sample:main", "kotlin", ":compileKotlin", outputs)
        val claims = mutableListOf(scoped)
        val unit = CompilerSourceUseUnit(unit().receipt, "kotlin-constants", "2.4.10", "3".repeat(64), "2".repeat(64),
            claims, CompilerSourceUseProducerCounts(6, 2, 4, 0), counts(2), 0)
        val roots = mutableListOf(owned); val units = mutableListOf(unit)
        val rows = mutableListOf(use(unit), use(unit).copy(offsetUtf16 = 30, endOffsetUtf16 = 36))
        val evidence = CompilerSourceUseEvidence(roots, units, rows)
        owners.clear(); outputs.clear(); claims.clear(); roots.clear(); units.clear(); rows.clear()
        assertEquals(listOf(caller, target), evidence.roots.single().capturedCallableOwners)
        assertEquals(listOf(output), evidence.units.single().claims.single().outputs)
        assertEquals(2, evidence.uses.size)
        for (list in listOf(evidence.roots, evidence.units, evidence.uses, owned.capturedCallableOwners, scoped.outputs, unit.claims)) {
            assertFailsWith<UnsupportedOperationException> { (list as MutableList<*>).clear() }
        }
    }

    @Test
    fun `narrowing is idempotent monotone and preserves historical baseline counts`() {
        val unit = unit()
        val one = use(unit)
        val two = one.copy(source = target, target = caller, offsetUtf16 = 30, endOffsetUtf16 = 36)
        val evidence = CompilerSourceUseEvidence(listOf(root), listOf(unit), listOf(one, two))
        val empty = evidence.select(setOf(caller))
        assertEquals(2, empty.units.single().viewOmitted)
        assertEquals(2, empty.units.single().importCounts.admitted)
        assertSame(empty, empty.select(setOf(caller)))
        assertSame(empty, empty.select(setOf(caller, target)))
        assertEquals(evidence.select(emptySet()), empty.select(emptySet()))
        assertEquals(evidence.roots, empty.roots)
        assertEquals(evidence.units.single().id, empty.units.single().id)
        assertNotEquals(evidence.units, empty.units)
    }

    @Test
    fun `removing CALLs keeps source facts but removes the old located facts`() {
        val unit = unit(emitted = 1)
        val use = use(unit)
        val evidence = CompilerSourceUseEvidence(listOf(root), listOf(unit), listOf(use))
        val graph = CodeGraph(listOf(node(caller), node(target)), listOf(GraphEdge(caller, target, EdgeKind.CALL)))
        val withPositions = graph.withCompilerCallPositions(listOf(LocatedCompilerReference(caller, target, use.file,
            use.collector, use.compilerVersion, use.coordinateBasis, 10, 16, 1, 11)))
        val captured = withPositions.withCompilerSourceUses(evidence)
        assertNull(graph.compilerSourceUseEvidence)
        assertEquals(graph.edges, captured.edges)
        assertEquals(graph.outgoingEdgesFrom(caller), captured.outgoingEdgesFrom(caller))
        assertSame(captured, captured.withCompilerSourceUses(evidence))
        assertSame(evidence, captured.enrichedWith(emptyList()).compilerSourceUseEvidence)
        val noCalls = captured.enrichedWith(emptyList(), replacingOrigin = EdgeOrigin.BYTECODE)
        assertTrue(noCalls.locatedCompilerReferences.isEmpty())
        assertSame(evidence, noCalls.compilerSourceUseEvidence)
        val narrowed = CodeGraph(listOf(node(caller)), emptyList()).preserveCompilerSourceUsesFrom(captured)
        assertTrue(narrowed.compilerSourceUseEvidence!!.uses.isEmpty())
        assertEquals(1, narrowed.compilerSourceUseEvidence!!.units.single().viewOmitted)
        assertFailsWith<IllegalArgumentException> { captured.withCompilerSourceUses(emptyEvidence()) }
    }

    @Test
    fun `captured zero emission and unavailable and filtered empty remain distinct`() {
        val graph = CodeGraph(listOf(node(caller), node(target)), emptyList())
        assertNull(graph.compilerSourceUseEvidence)
        val empty = graph.withCompilerSourceUses(emptyEvidence())
        assertEquals(0, empty.compilerSourceUseEvidence!!.units.single().viewOmitted)
        assertTrue(empty.compilerSourceUseEvidence!!.uses.isEmpty())
        val unit = unit(emitted = 1)
        val filtered = CompilerSourceUseEvidence(listOf(root), listOf(unit), listOf(use(unit))).select(emptySet())
        assertNotEquals(empty.compilerSourceUseEvidence, filtered)
    }

    @Test
    fun `duplicate IDs selectors owners and unequal cross unit assertions fail`() {
        val unit = unit(emitted = 1); val use = use(unit)
        assertFailsWith<IllegalArgumentException> { CompilerSourceUseRoot(0, output, listOf(caller, caller)) }
        assertFailsWith<IllegalArgumentException> { CompilerSourceUseEvidence(listOf(root), listOf(unit, unit), listOf(use)) }
        assertFailsWith<IllegalArgumentException> { CompilerSourceUseEvidence(listOf(root), listOf(unit), listOf(use, use)) }
        val two = unit()
        assertFailsWith<IllegalArgumentException> { CompilerSourceUseEvidence(listOf(root), listOf(two),
            listOf(use(two), use(two).copy(target = caller))) }
        val other = CompilerSourceUseUnit(unit.receipt.copy(path = "other.tsv"), unit.collector, unit.compilerVersion,
            unit.collectorArtifactFingerprint, unit.inputToken, unit.claims, unit.producer, unit.importCounts, 0)
        val matching = use.copy(unitId = other.id)
        assertEquals(2, CompilerSourceUseEvidence(listOf(root), listOf(other, unit), listOf(matching, use)).uses.size)
        assertFailsWith<IllegalArgumentException> { CompilerSourceUseEvidence(listOf(root), listOf(other, unit),
            listOf(matching.copy(endOffsetUtf16 = 17), use)) }
        val duplicateRoot = CompilerSourceUseRoot(1, output.copy(path = "other-classes"), listOf(caller))
        assertFailsWith<IllegalArgumentException> { CompilerSourceUseEvidence(listOf(root, duplicateRoot), listOf(unit), listOf(use)) }
    }

    @Test
    fun `integer equations are checked without overflow or invented observations`() {
        assertFailsWith<IllegalArgumentException> { CompilerSourceUseProducerCounts(Int.MAX_VALUE, Int.MAX_VALUE, Int.MAX_VALUE, 1) }
        assertFailsWith<IllegalArgumentException> { CompilerSourceUseImportCounts(-1, 0, 0, 0, 0, 0) }
        assertFailsWith<IllegalArgumentException> { CompilerSourceUseUnit(unit().receipt, "kotlin-constants", "2.4.10",
            "3".repeat(64), "2".repeat(64), listOf(claim), CompilerSourceUseProducerCounts(2, 2, 0, 0), counts(1), 0) }
        val unit = unit()
        assertFailsWith<IllegalArgumentException> { CompilerSourceUseEvidence(listOf(root), listOf(unit), listOf(use(unit))) }
        assertFailsWith<IllegalArgumentException> { CompilerSourceUseEvidence(listOf(root), emptyList(), emptyList()) }
    }

    @Test
    fun `UTF8 order paths scalars and strict numeric coordinate bounds are preserved`() {
        val bmp = NodeId("method:p/\uE000#x()V")
        val astral = NodeId("method:p/\uD800\uDC00#x()V")
        assertEquals(listOf(bmp, astral), CompilerSourceUseRoot(0, output, listOf(astral, bmp)).capturedCallableOwners)
        assertFailsWith<IllegalArgumentException> { CompilerSourceUseRoot(0, output, listOf(NodeId("method:p/\uD800#x()V"))) }
        assertFailsWith<IllegalArgumentException> { CompilerSourceUseRoot(0, output.copy(path = "./classes"), emptyList()) }
        assertFailsWith<IllegalArgumentException> { CompilerSourceUseRoot(0, output.copy(path = "x".repeat(16_385)), emptyList()) }
        val row = use(unit())
        for (bad in listOf<() -> CompilerSourceUse>({ row.copy(offsetUtf16 = -1) }, { row.copy(endOffsetUtf16 = 10) },
            { row.copy(line = 0) }, { row.copy(column = 0) },
            { row.copy(coordinateBasis = CompilerSourceCoordinateBasis.JAVAC_UTF16_CHAR_SEQUENCE) })) {
            assertFailsWith<IllegalArgumentException> { bad() }
        }
        // 이 단계는 source extent나 line mapping을 다시 해석하지 않는다.
        assertEquals(Int.MAX_VALUE, row.copy(endOffsetUtf16 = Int.MAX_VALUE).endOffsetUtf16)
    }

    @Test
    fun `equal bytes at different paths remain uncovered even after owner pruning`() {
        val extra = NodeId("method:p/D#unused()V")
        val uncovered = CompilerSourceUseRoot(1, output.copy(path = "other/classes"), listOf(extra))
        val emptyRoot = CompilerSourceUseRoot(2, output.copy(path = "empty/classes"), emptyList())
        val unit = unit(emitted = 1)
        val evidence = CompilerSourceUseEvidence(listOf(emptyRoot, uncovered, root), listOf(unit), listOf(use(unit)))
        assertEquals(listOf(uncovered), evidence.uncoveredRoots)
        assertEquals(listOf(uncovered), evidence.select(emptySet()).uncoveredRoots)
        assertEquals(3, evidence.roots.size)
    }

    @Test
    fun `caps reject bounded iterables before retaining the excess element`() {
        var observed = 0
        val roots = Iterable { generateSequence { observed++; root }.iterator() }
        assertFailsWith<IllegalArgumentException> { CompilerSourceUseEvidence(roots, listOf(unit()), emptyList()) }
        assertEquals(4_097, observed)
        assertFailsWith<IllegalArgumentException> { CompilerSourceUseUnit(unit().receipt, "kotlin-constants", "2.4.10",
            "3".repeat(64), "2".repeat(64), List(4_097) { claim }, unit().producer, counts(2), 0) }
    }

    @Test
    fun `repeated claim and witness bindings hash ordered inputs once per invocation`() {
        val inputs = listOf(InputFingerprint("sources", "src", "a".repeat(64)),
            InputFingerprint("buildConfig", "build.gradle", "b".repeat(64)),
            InputFingerprint("compiler", "compiler.jar", "3".repeat(64)),
            InputFingerprint("options", "compiler-call-positions", SourceUseCanonical.hash(sequenceOf("callPositions=true"))))
        val token = SourceUseCanonical.hash(sequenceOf("kartograph-compiler-inputs-v1", claim.scope, claim.compiler, claim.task) +
            inputs.asSequence().flatMap { SourceUseCanonical.fields(it).asSequence() })
        val receipts = (0 until 64).map { InputFingerprint("compilerEvidence", "evidence-$it.tsv", "1".repeat(64)) }
        val units = receipts.map { CompilerSourceUseUnit(it, "kotlin-constants", "2.4.10", "3".repeat(64), token, listOf(claim),
            CompilerSourceUseProducerCounts(0, 0, 0, 0), counts(0), 0) }
        val witness = BuildWitness(claim.scope, claim.compiler, claim.task, inputs, listOf(output), receipts, token)
        val provenance = SnapshotProvenance(listOf(output), List(64) { witness })
        val evidence = CompilerSourceUseEvidence(listOf(root), units, emptyList())
        var hashes = 0
        evidence.requireMatchesProvenance(provenance, claim.scope) { hashes++ }
        assertEquals(1, hashes)
        evidence.requireMatchesProvenance(provenance, claim.scope) { hashes++ }
        assertEquals(2, hashes, "a second operation must validate independently")
    }

    private fun unit(emitted: Int = 2): CompilerSourceUseUnit = CompilerSourceUseUnit(
        InputFingerprint("compilerEvidence", "evidence.tsv", "1".repeat(64)), "kotlin-constants", "2.4.10",
        "3".repeat(64), "2".repeat(64), listOf(claim), CompilerSourceUseProducerCounts(emitted + 4, emitted, 4, 0), counts(emitted), 0)
    private fun counts(admitted: Int): CompilerSourceUseImportCounts = CompilerSourceUseImportCounts(admitted, 0, 0, 0, 0, 0)
    private fun use(unit: CompilerSourceUseUnit): CompilerSourceUse = CompilerSourceUse(unit.id, caller, target, root.id, root.id,
        CompilerSourceUseKind.INVOCATION_SELECTION, CompilerEvidenceSource("src/C.kt", "5".repeat(64)),
        "kotlin-constants", "2.4.10", CompilerSourceCoordinateBasis.KOTLIN_UTF16_NORMALIZED_SOURCE, 10, 16, 1, 11, false)
    private fun emptyEvidence(): CompilerSourceUseEvidence = CompilerSourceUseEvidence(listOf(root), listOf(unit(0)), emptyList())
    private fun node(id: NodeId): GraphNode = GraphNode(id, id.value, NodeKind.METHOD)
}
