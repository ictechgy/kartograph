package dev.kartograph.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

class CompilerEnumDecisionTest {
    private val owner = NodeId("class:p/Mode")
    private val lexical = NodeId("class:p/Use")
    private val a = NodeId("field:p/Mode#A:Lp/Mode;")
    private val output = InputFingerprint("classes", "build/classes", "1".repeat(64))
    private val receipt = InputFingerprint("compilerEvidence", "build/enum.tsv", "2".repeat(64))
    private val artifact = "3".repeat(64)
    private val claim = CompilerSourceUseClaim("sample:main", "kotlin", ":compileKotlin", listOf(output))
    private val root = CompilerEnumDecisionRoot(0, output, listOf(owner, lexical))

    @Test
    fun `capture lists are defensive immutable and inspected empty is real evidence`() {
        val owners = mutableListOf(owner, lexical)
        val captured = CompilerEnumDecisionRoot(0, output, owners)
        val constants = mutableListOf(a)
        val unit = unit()
        val row = site(unit, constants = constants)
        val rows = mutableListOf(row)
        val evidence = CompilerEnumDecisionEvidence(listOf(captured), listOf(unit), rows)
        owners.clear(); constants.clear(); rows.clear()
        assertEquals(listOf(owner, lexical).sorted(), captured.capturedClassOwners)
        assertEquals(listOf(a), row.constants)
        assertEquals(1, evidence.sites.size)
        for (list in listOf(captured.capturedClassOwners, row.constants, evidence.roots, evidence.units, evidence.sites, unit.claims)) {
            assertFailsWith<UnsupportedOperationException> { (list as MutableList<*>).clear() }
        }
        val empty = CompilerEnumDecisionEvidence(listOf(root), listOf(unit(0)), emptyList())
        assertTrue(empty.sites.isEmpty())
        assertNotEquals(evidence, empty)
    }

    @Test
    fun `top level resolved sites use one claimed root and unresolved shadowed rows survive`() {
        val unit = unit()
        CompilerEnumDecisionEvidence(listOf(root), listOf(unit), listOf(site(unit)))
        assertFailsWith<IllegalArgumentException> {
            CompilerEnumDecisionEvidence(listOf(root), listOf(unit), listOf(site(unit, lexicalClass = NodeId("class:p/Other"))))
        }
        val unresolved = unit(resolved = 0, unresolved = 1)
        val row = site(unresolved, lexicalClass = NodeId("class:p/Shadowed"), attribution = EnumDecisionAttribution.SHADOWED_SOURCE)
        assertEquals(row, CompilerEnumDecisionEvidence(listOf(root), listOf(unresolved), listOf(row)).sites.single())
        val otherRoot = CompilerEnumDecisionRoot(1, output.copy(path = "other/classes"), emptyList())
        assertFailsWith<IllegalArgumentException> { site(unit, sourceRoot = otherRoot.id) }
        assertFailsWith<IllegalArgumentException> { site(unit, sourceRoot = null) }
    }

    @Test
    fun `duplicate roots selectors and conflicting cross unit facts are rejected`() {
        val unit = unit()
        val row = site(unit)
        assertFailsWith<IllegalArgumentException> { CompilerEnumDecisionRoot(0, output, listOf(owner, owner)) }
        assertFailsWith<IllegalArgumentException> {
            CompilerEnumDecisionEvidence(listOf(root, CompilerEnumDecisionRoot(1, output.copy(path = "other/classes"), listOf(owner))), listOf(unit), listOf(row))
        }
        assertFailsWith<IllegalArgumentException> { CompilerEnumDecisionEvidence(listOf(root), listOf(unit, unit), listOf(row)) }
        val two = unit(resolved = 2)
        assertFailsWith<IllegalArgumentException> { CompilerEnumDecisionEvidence(listOf(root), listOf(two), listOf(site(two), site(two, constants = emptyList()))) }
        val other = unit(receipt = receipt.copy(path = "build/other.tsv"))
        CompilerEnumDecisionEvidence(listOf(root), listOf(unit, other), listOf(row, site(other)))
        assertFailsWith<IllegalArgumentException> { CompilerEnumDecisionEvidence(listOf(root), listOf(unit, other),
            listOf(row, site(other, kind = EnumDecisionKind.FINITE_MEMBERSHIP))) }
    }

    @Test
    fun `partitions identities constants and coordinates are bounded`() {
        assertFailsWith<IllegalArgumentException> { CompilerEnumDecisionProducerCounts(Int.MAX_VALUE, Int.MAX_VALUE, 1, 0) }
        assertFailsWith<IllegalArgumentException> { CompilerEnumDecisionImportCounts(-1, 0) }
        assertFailsWith<IllegalArgumentException> { CompilerEnumDecisionEvidence(listOf(root), listOf(unit(2)), listOf(site(unit(2)))) }
        assertFailsWith<IllegalArgumentException> { site(unit(), constants = listOf(a, a)) }
        assertFailsWith<IllegalArgumentException> { site(unit(), constants = listOf(NodeId("field:p/Mode#ALIAS:Ljava/lang/Object;"))) }
        assertFailsWith<IllegalArgumentException> { CompilerEnumDecisionRoot(0, output, listOf(NodeId("class:p/Bad\uD800"))) }
    }

    @Test
    fun `view pruning never fabricates inventory and retained contradictions fail`() {
        val evidence = CompilerEnumDecisionEvidence(listOf(root), listOf(unit()), listOf(site(unit())))
        evidence.requireMatchesGraph(CodeGraph(emptyList(), emptyList()))
        evidence.requireMatchesGraph(CodeGraph(listOf(GraphNode(owner, "Mode", NodeKind.ENUM)), emptyList()))
        evidence.requireMatchesGraph(CodeGraph(listOf(GraphNode(owner, "Mode", NodeKind.ENUM, enumConstants = listOf(a))), emptyList()))
        assertFailsWith<IllegalArgumentException> { evidence.requireMatchesGraph(CodeGraph(
            listOf(GraphNode(owner, "Mode", NodeKind.ENUM, enumConstants = emptyList())), emptyList())) }
        assertFailsWith<IllegalArgumentException> { evidence.requireMatchesGraph(CodeGraph(listOf(GraphNode(owner, "Mode", NodeKind.CLASS)), emptyList())) }
        assertEquals(1, evidence.units.single().importCounts.resolved)
        assertEquals(listOf(owner, lexical).sorted(), evidence.roots.single().capturedClassOwners)
    }

    @Test
    fun `completed witness requires option token artifact receipt scope and selected roots`() {
        val inputs = listOf(InputFingerprint("sources", "src", "a".repeat(64)),
            InputFingerprint("buildConfig", "build.gradle.kts", "b".repeat(64)),
            InputFingerprint("compiler", "compiler/kotlin.jar", artifact),
            InputFingerprint("options", "compiler-enum-decisions", SourceUseCanonical.hash(sequenceOf("enumDecisions=true"))))
        val token = SourceUseCanonical.hash(sequenceOf("kartograph-compiler-inputs-v1", claim.scope, claim.compiler, claim.task) +
            inputs.asSequence().flatMap { SourceUseCanonical.fields(it).asSequence() })
        val unit = unit(token = token)
        val evidence = CompilerEnumDecisionEvidence(listOf(root), listOf(unit), listOf(site(unit)))
        val witness = BuildWitness(claim.scope, claim.compiler, claim.task, inputs, listOf(output), listOf(receipt), token)
        val provenance = SnapshotProvenance(listOf(output, receipt), listOf(witness))
        evidence.requireMatchesProvenance(provenance, claim.scope)
        val badInputs = inputs.map { if (it.role == "options") it.copy(sha256 = "0".repeat(64)) else it }
        for (bad in listOf(
            provenance.copy(inputs = listOf(output.copy(sha256 = "0".repeat(64)))),
            provenance.copy(witnesses = listOf(witness.copy(inputs = badInputs))),
            provenance.copy(witnesses = listOf(witness.copy(evidenceToken = "0".repeat(64)))),
            provenance.copy(witnesses = listOf(witness.copy(compilerEvidence = listOf(receipt.copy(sha256 = "0".repeat(64)))))),
            provenance.copy(memberScopes = listOf(claim.scope)),
        )) assertFailsWith<IllegalArgumentException> { evidence.requireMatchesProvenance(bad, claim.scope) }
        assertFailsWith<IllegalArgumentException> { evidence.requireMatchesProvenance(provenance, "other:main") }
    }

    private fun unit(resolved: Int = 1, unresolved: Int = 0, receipt: InputFingerprint = this.receipt, token: String = "4".repeat(64)) =
        CompilerEnumDecisionUnit(receipt, "kotlin-constants", "2.4.10", artifact, token, listOf(claim),
            CompilerEnumDecisionProducerCounts(resolved + unresolved, resolved + unresolved, 0, 0),
            CompilerEnumDecisionImportCounts(resolved, unresolved))

    private fun site(unit: CompilerEnumDecisionUnit, constants: List<NodeId> = listOf(a),
        kind: EnumDecisionKind = EnumDecisionKind.EXPLICIT_FALLBACK, lexicalClass: NodeId? = null,
        sourceRoot: String? = root.id, attribution: EnumDecisionAttribution = EnumDecisionAttribution.RESOLVED_SAME_OUTPUT_ROOT) =
        CompilerEnumDecisionSite(unit.id, owner, constants, kind, lexicalClass, false,
            CompilerEvidenceSource("src/p/Use.kt", "a".repeat(64)), CompilerSourceCoordinateBasis.KOTLIN_UTF16_NORMALIZED_SOURCE,
            1, 10, 1, 2, false, sourceRoot, root.id, attribution)
}
