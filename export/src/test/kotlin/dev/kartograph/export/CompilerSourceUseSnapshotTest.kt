package dev.kartograph.export

import dev.kartograph.analysis.ChangeImpact
import dev.kartograph.analysis.ImpactInput
import dev.kartograph.analysis.ReachabilityAnalyzer
import dev.kartograph.analysis.SymbolQuery
import dev.kartograph.core.BuildWitness
import dev.kartograph.core.CodeGraph
import dev.kartograph.core.CompilerEvidenceSource
import dev.kartograph.core.CompilerSourceCoordinateBasis
import dev.kartograph.core.CompilerSourceUse
import dev.kartograph.core.CompilerSourceUseClaim
import dev.kartograph.core.CompilerSourceUseEvidence
import dev.kartograph.core.CompilerSourceUseImportCounts
import dev.kartograph.core.CompilerSourceUseKind
import dev.kartograph.core.CompilerSourceUseProducerCounts
import dev.kartograph.core.CompilerSourceUseRoot
import dev.kartograph.core.CompilerSourceUseUnit
import dev.kartograph.core.EdgeKind
import dev.kartograph.core.GraphEdge
import dev.kartograph.core.GraphNode
import dev.kartograph.core.InputFingerprint
import dev.kartograph.core.LocatedCompilerReference
import dev.kartograph.core.NodeId
import dev.kartograph.core.NodeKind
import dev.kartograph.core.RetentionEvidence
import dev.kartograph.core.RetentionReason
import dev.kartograph.core.SnapshotProvenance
import java.security.MessageDigest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class CompilerSourceUseSnapshotTest {
    @Test
    fun `absent captured empty populated and partial observations round trip in both encodings`() {
        for (compact in listOf(false, true)) {
            val absent = fixture().let { it.copy(graph = baseGraph()) }
            val absentText = QuerySnapshotCodec.render(absent, compact)
            assertFalse(absentText.contains("\"compilerSourceUses\""))
            assertNull(QuerySnapshotCodec.parse(absentText).graph.compilerSourceUseEvidence)

            for (snapshot in listOf(fixture(empty = true), fixture(), narrow(fixture(), setOf(CONTROL)),
                narrow(fixture(), setOf(ERASED)), narrow(fixture(), setOf(CONTROL, ERASED)))) {
                val text = QuerySnapshotCodec.render(snapshot, compact)
                val restored = QuerySnapshotCodec.parse(text)
                assertEquals(snapshot.graph.compilerSourceUseEvidence, restored.graph.compilerSourceUseEvidence)
                assertEquals(snapshot.graph.edges, restored.graph.edges)
                assertEquals(snapshot.graph.locatedCompilerReferences, restored.graph.locatedCompilerReferences)
                assertEquals(snapshot.provenance, restored.provenance)
                assertEquals(text, QuerySnapshotCodec.render(restored, compact))
                val extension = extension(document(text))
                assertEquals(setOf("version", "roots", "units", "uses"), extension.keys)
                assertEquals(1L, extension["version"])
                assertTrue(rows(extension["uses"]).all { it is Map<*, *> }, "v2 uses the same plain extension")
            }
        }
    }

    @Test
    fun `existing constructor endpoints retain exact ownership through both snapshot encodings`() {
        val original = fixture()
        val evidence = original.graph.compilerSourceUseEvidence!!
        val caller = NodeId("method:demo/Caller#<init>(I)V")
        val target = NodeId("method:demo/Caller#<init>()V")
        fun remap(id: NodeId): NodeId = when (id) { ERASED -> caller; WRAPPER -> target; else -> id }
        val roots = evidence.roots.map { CompilerSourceUseRoot(it.ordinal, it.input, it.capturedCallableOwners.map(::remap)) }
        val rootIds = evidence.roots.zip(roots).associate { it.first.id to it.second.id }
        val uses = evidence.uses.map { it.copy(source = remap(it.source), target = remap(it.target),
            sourceRootId = rootIds.getValue(it.sourceRootId), targetRootId = rootIds.getValue(it.targetRootId)) }
        val graph = CodeGraph(original.graph.nodes.values.map { node ->
            if (node.id in setOf(ERASED, WRAPPER)) node.copy(id = remap(node.id), name = "<init>", kind = NodeKind.CONSTRUCTOR) else node
        }, original.graph.edges).withCompilerCallPositions(original.graph.locatedCompilerReferences)
            .withCompilerSourceUses(CompilerSourceUseEvidence(roots, evidence.units, uses))
        for (compact in listOf(false, true)) {
            val restored = QuerySnapshotCodec.parse(QuerySnapshotCodec.render(original.copy(graph = graph), compact))
            assertEquals(graph.compilerSourceUseEvidence, restored.graph.compilerSourceUseEvidence)
            assertEquals(NodeKind.CONSTRUCTOR, restored.graph.node(caller)?.kind)
            assertEquals(NodeKind.CONSTRUCTOR, restored.graph.node(target)?.kind)
            assertTrue(restored.graph.compilerSourceUseEvidence!!.uses.any { it.source == caller && it.target == target })
        }
    }

    @Test
    fun `parsed views preserve baseline counts and never resurrect omitted rows`() {
        val full = fixture()
        for (compact in listOf(false, true)) {
            val first = narrow(full, setOf(CONTROL, UNCOVERED))
            val restored = QuerySnapshotCodec.parse(QuerySnapshotCodec.render(first, compact))
            val same = narrow(restored, setOf(CONTROL, UNCOVERED))
            val firstEvidence = assertNotNull(same.graph.compilerSourceUseEvidence)
            assertEquals(listOf(ERASED), firstEvidence.uses.map { it.source })
            assertEquals(1, firstEvidence.units.single().viewOmitted)
            assertEquals(2, firstEvidence.units.single().importCounts.admitted)
            assertEquals(1, firstEvidence.uncoveredRoots.size, "capture coverage must not improve after pruning")
            val second = narrow(same, setOf(ERASED))
            val direct = narrow(full, setOf(CONTROL, UNCOVERED, ERASED))
            assertEquals(direct.graph.compilerSourceUseEvidence, second.graph.compilerSourceUseEvidence)
            assertEquals(2, second.graph.compilerSourceUseEvidence!!.units.single().viewOmitted)
            val expanded = CodeGraph(full.graph.nodes.values, full.graph.edges).preserveCompilerSourceUsesFrom(second.graph)
            assertTrue(expanded.compilerSourceUseEvidence!!.uses.isEmpty())
            assertEquals(2, expanded.compilerSourceUseEvidence!!.units.single().viewOmitted)
        }
    }

    @Test
    fun `source channel does not require or add a bytecode call`() {
        val original = fixture()
        assertFalse(original.graph.edges.any { it.source == ERASED && it.target == WRAPPER && it.kind == EdgeKind.CALL })
        assertEquals(baseGraph().edges, original.graph.edges)
        assertEquals(listOf(CONTROL), original.graph.locatedCompilerReferences.map { it.source })
        val edgeFree = CodeGraph(original.graph.nodes.values, emptyList()).preserveCompilerSourceUsesFrom(original.graph)
        assertEquals(original.graph.compilerSourceUseEvidence, edgeFree.compilerSourceUseEvidence)
        assertTrue(edgeFree.edges.isEmpty())
    }

    @Test
    fun `ordinary query reachability and impact ignore captured source selections`() {
        val original = baseGraph()
        val captured = fixture().graph
        val retention = listOf(RetentionEvidence(ERASED, RetentionReason.KEEP_RULE, null))
        val before = ReachabilityAnalyzer.analyze(original, retention)
        val after = ReachabilityAnalyzer.analyze(captured, retention)
        assertEquals(before.reachableNodeIds, after.reachableNodeIds)
        assertEquals(before.unreachableNodeIds, after.unreachableNodeIds)
        assertFalse(WRAPPER in after.reachableNodeIds)
        assertEquals(SymbolQuery.query(original, before, WRAPPER.value, emptyList()),
            SymbolQuery.query(captured, after, WRAPPER.value, emptyList()))
        assertEquals(ChangeImpact.analyze(ImpactInput(original, retention), symbols = listOf(WRAPPER.value)),
            ChangeImpact.analyze(ImpactInput(captured, retention), symbols = listOf(WRAPPER.value)))
    }

    @Test
    fun `wire presentation order canonicalizes without changing capture precedence`() {
        for (compact in listOf(false, true)) {
            val original = QuerySnapshotCodec.render(fixture(), compact)
            val reordered = rewrite(original) { document ->
                val extension = extension(document)
                rows(extension["roots"]).reverse()
                rows(extension["roots"]).forEach { rows(obj(it)["capturedCallableOwners"]).reverse() }
                rows(extension["uses"]).reverse()
            }
            assertEquals(original, QuerySnapshotCodec.render(QuerySnapshotCodec.parse(reordered), compact))
        }
    }

    @Test
    fun `extension rejects exact key identity type count and Unicode corruption`() {
        val mutations: List<(MutableMap<String, Any?>) -> Unit> = listOf(
            { it["extra"] = 1 },
            { it.remove("uses") },
            { it["version"] = 2 },
            { it["units"] = emptyList<Any>() },
            { it["roots"] = null },
            { root(it)["id"] = "0".repeat(64) },
            { unit(it)["id"] = "0".repeat(64) },
            { use(it)["id"] = "0".repeat(64) },
            { root(it)["ordinal"] = -1 },
            { root(it)["capturedCallableOwners"] = listOf(ERASED.value, ERASED.value) },
            { obj(root(it)["input"])["extra"] = "unknown" },
            { unit(it).remove("viewOmitted") },
            { unit(it)["viewOmitted"] = 1 },
            { obj(unit(it)["producer"])["observed"] = 7 },
            { obj(unit(it)["producer"])["extra"] = 0 },
            { obj(unit(it)["import"])["admitted"] = 1 },
            { obj(unit(it)["import"]).remove("outsideGraph") },
            { obj(unit(it)["producer"])["unmapped"] = 2_147_483_648L },
            { obj(unit(it)["producer"])["observed"] = Int.MAX_VALUE },
            { obj(unit(it)["import"])["outsideGraph"] = -1 },
            { obj(unit(it)["receipt"])["path"] = "../receipt.tsv" },
            { use(it)["kind"] = "inlineCall" },
            { use(it)["generated"] = null },
            { use(it)["generated"] = "false" },
            { use(it)["offsetUtf16"] = "207" },
            { use(it)["offsetUtf16"] = -1 },
            { use(it)["endOffsetUtf16"] = 0 },
            { use(it)["line"] = 0 },
            { use(it)["column"] = 0 },
            { use(it)["coordinateBasis"] = "javacUtf16CharSequence" },
            { use(it)["path"] = "src/./Probe.kt" },
            { use(it)["path"] = "src/\uD800.kt" },
            { use(it)["path"] = "src/\uDC00.kt" },
            { use(it)["path"] = "src/" + "é".repeat(8_193) + ".kt" },
            { use(it)["source"] = "method:demo/\uD800#call()I" },
            { use(it)["sourceSha256"] = "A".repeat(64) },
            { rows(it["roots"]).add(root(it)) },
            { rows(it["units"]).add(unit(it)) },
            { rows(it["uses"]).add(use(it)) },
            { rows(unit(it)["claims"]).add(obj(rows(unit(it)["claims"]).first())) },
        )
        for (compact in listOf(false, true)) {
            val text = QuerySnapshotCodec.render(fixture(), compact)
            mutations.forEachIndexed { index, mutation ->
                assertFailsWith<IllegalArgumentException>("mutation $index, compact=$compact") {
                    QuerySnapshotCodec.parse(rewrite(text) { mutation(extension(it)) })
                }
            }
            assertFailsWith<IllegalArgumentException> {
                QuerySnapshotCodec.parse(rewrite(text) { obj(it["graph"])["compilerSourceUses"] = null })
            }
            // escape를 거쳐도 같은 scalar 검사를 적용한다.
            val escaped = text.replace("src/demo/Probe.kt", "src/demo/\\ud800.kt")
            assertFailsWith<IllegalArgumentException> { QuerySnapshotCodec.parse(escaped) }
        }
    }

    @Test
    fun `snapshot verifies stored provenance on render and parse without reading paths`() {
        val valid = fixture()
        val provenance = assertNotNull(valid.provenance)
        val witness = provenance.witnesses.single()
        fun withWitness(value: BuildWitness) = valid.copy(provenance = provenance.copy(witnesses = listOf(value)))
        val invalid = listOf(
            valid.copy(scope = null), valid.copy(scope = "other"), valid.copy(provenance = null),
            valid.copy(provenance = provenance.copy(inputs = provenance.inputs.reversed())),
            valid.copy(provenance = provenance.copy(witnesses = emptyList())),
            withWitness(witness.copy(artifact = ":other")),
            withWitness(witness.copy(evidenceToken = "0".repeat(64))),
            withWitness(witness.copy(compilerEvidence = listOf(RECEIPT.copy(path = "build/other.tsv")))),
            withWitness(witness.copy(inputs = witness.inputs.filterNot { it.path == "compiler-call-positions" })),
        )
        invalid.forEach { snapshot ->
            assertFailsWith<IllegalArgumentException> { QuerySnapshotCodec.render(snapshot) }
            assertFailsWith<IllegalArgumentException> { QuerySnapshotCodec.render(snapshot, compact = true) }
        }
        val corruptions: List<(MutableMap<String, Any?>) -> Unit> = listOf(
            { it.remove("scope") }, { it["scope"] = "other" }, { it.remove("provenance") },
            { obj(it["provenance"])["witnesses"] = emptyList<Any>() },
            { obj(rows(obj(it["provenance"])["witnesses"]).single())["artifact"] = ":other" },
            { obj(rows(obj(it["provenance"])["witnesses"]).single())["evidenceToken"] = "0".repeat(64) },
        )
        for (compact in listOf(false, true)) {
            val text = QuerySnapshotCodec.render(valid, compact)
            corruptions.forEach { corruption ->
                assertFailsWith<IllegalArgumentException> { QuerySnapshotCodec.parse(rewrite(text, corruption)) }
            }
        }
    }

    @Test
    fun `recomputed identity cannot bypass graph ownership or receipt validation`() {
        val valid = fixture()
        val evidence = valid.graph.compilerSourceUseEvidence!!
        val first = evidence.uses.first()
        val wrongOwner = first.copy(sourceRootId = evidence.roots.last().id)
        val missingNode = first.copy(source = NodeId("method:demo/Missing#run()I"))
        val wrongUnit = first.copy(unitId = "0".repeat(64))
        val wrongCompiler = first.copy(compilerVersion = "2.4.11")
        for (compact in listOf(false, true)) {
            val text = QuerySnapshotCodec.render(valid, compact)
            listOf(wrongOwner, missingNode, wrongUnit, wrongCompiler).forEach { replacement ->
                assertFailsWith<IllegalArgumentException> { QuerySnapshotCodec.parse(rewrite(text) {
                    val row = use(extension(it))
                    row["id"] = replacement.id; row["source"] = replacement.source.value; row["sourceRootId"] = replacement.sourceRootId
                    row["unitId"] = replacement.unitId; row["compilerVersion"] = replacement.compilerVersion
                }) }
            }
            assertFailsWith<IllegalArgumentException> { QuerySnapshotCodec.parse(rewrite(text) {
                val graph = obj(it["graph"])
                rows(graph["nodes"]).removeIf { raw ->
                    if (compact) rows(graph["stringTable"])[(rows(raw).first() as Long).toInt()] == ERASED.value
                    else obj(raw)["usr"] == ERASED.value
                }
            }) }
        }
    }

    @Test
    fun `extension collection and existing snapshot byte limits reject without truncating`() {
        val text = QuerySnapshotCodec.render(fixture())
        val oversized: List<(MutableMap<String, Any?>) -> Unit> = listOf(
            { it["roots"] = List(4_097) { null } },
            { it["units"] = List(257) { null } },
            { it["uses"] = List(200_001) { null } },
            { root(it)["capturedCallableOwners"] = List(1_000_001) { null } },
            { unit(it)["claims"] = List(4_097) { null } },
            { obj(rows(unit(it)["claims"]).single())["outputs"] = List(16_385) { null } },
            { extension -> rows(extension["roots"]).forEach { obj(it)["capturedCallableOwners"] = List(500_001) { null } } },
            { extension ->
                val raw = unit(extension)
                raw["claims"] = List(2_049) { mapOf("scope" to SCOPE, "compiler" to "kotlin", "task" to ":compileKotlin",
                    "outputs" to emptyList<Any>()) }
                extension["units"] = listOf(raw, raw)
            },
            { extension ->
                val claim = obj(rows(unit(extension)["claims"]).single())
                claim["outputs"] = List(8_193) { null }
                unit(extension)["claims"] = listOf(claim, claim)
            },
        )
        oversized.forEach { mutation ->
            assertFailsWith<IllegalArgumentException> { QuerySnapshotCodec.parse(rewrite(text) { mutation(extension(it)) }) }
        }
        for (compact in listOf(false, true)) {
            val snapshot = fixture()
            val encoded = QuerySnapshotCodec.render(snapshot, compact)
            val bytes = encoded.toByteArray(Charsets.UTF_8).size
            assertEquals(encoded, QuerySnapshotCodec.render(snapshot, compact, bytes))
            assertFailsWith<QuerySnapshotSizeException> { QuerySnapshotCodec.render(snapshot, compact, bytes - 1) }
            assertFailsWith<QuerySnapshotSizeException> { QuerySnapshotCodec.parse(encoded, bytes - 1) }
        }
    }

    private fun fixture(empty: Boolean = false): QuerySnapshot {
        val inputs = listOf(InputFingerprint("sources", "src/demo/Probe.kt", "a".repeat(64)),
            InputFingerprint("buildConfig", "build.gradle.kts", "e".repeat(64)),
            InputFingerprint("compiler", "compiler/kotlin.jar", "f".repeat(64)),
            InputFingerprint("processor", "compiler/collector.jar", ARTIFACT),
            InputFingerprint("options", "compile-options", "8".repeat(64)),
            InputFingerprint("options", "compiler-call-positions", digest(listOf("callPositions=true"))))
        val token = digest(listOf("kartograph-compiler-inputs-v1", SCOPE, "kotlin", ":compileKotlin") +
            inputs.flatMap { listOf(it.role, it.path, it.sha256) })
        val witness = BuildWitness(SCOPE, "kotlin", ":compileKotlin", inputs, listOf(CLASSES), listOf(RECEIPT), token)
        val selected = CompilerSourceUseRoot(0, CLASSES, listOf(WRAPPER, CONTROL, ERASED, ORDINARY))
        val uncovered = CompilerSourceUseRoot(1, UNCOVERED_CLASSES, listOf(UNCOVERED))
        val count = if (empty) 0 else 2
        val unit = CompilerSourceUseUnit(RECEIPT, "kotlin-constants", "2.4.10", ARTIFACT, token,
            listOf(CompilerSourceUseClaim(SCOPE, "kotlin", ":compileKotlin", listOf(CLASSES))),
            CompilerSourceUseProducerCounts(if (empty) 0 else 6, count, if (empty) 0 else 4, 0),
            CompilerSourceUseImportCounts(count, 0, 0, 0, 0, 0), 0)
        fun use(source: NodeId, target: NodeId, start: Int, end: Int, line: Int, column: Int) = CompilerSourceUse(
            unit.id, source, target, selected.id, selected.id, CompilerSourceUseKind.INVOCATION_SELECTION,
            SOURCE, "kotlin-constants", "2.4.10", CompilerSourceCoordinateBasis.KOTLIN_UTF16_NORMALIZED_SOURCE,
            start, end, line, column, false)
        val uses = if (empty) emptyList() else listOf(use(ERASED, WRAPPER, 207, 211, 7, 27),
            use(CONTROL, ORDINARY, 285, 300, 9, 30))
        val evidence = CompilerSourceUseEvidence(listOf(uncovered, selected), listOf(unit), uses)
        val positioned = baseGraph().withCompilerCallPositions(if (empty) emptyList() else listOf(LocatedCompilerReference(
            CONTROL, ORDINARY, SOURCE, "kotlin-constants", "2.4.10", CompilerSourceCoordinateBasis.KOTLIN_UTF16_NORMALIZED_SOURCE,
            285, 300, 9, 30)))
        return QuerySnapshot(positioned.withCompilerSourceUses(evidence), emptyList(), emptyList(), scope = SCOPE,
            provenance = SnapshotProvenance(listOf(CLASSES, UNCOVERED_CLASSES, RECEIPT), listOf(witness)))
    }

    private fun baseGraph(): CodeGraph = CodeGraph(listOf(ERASED, WRAPPER, CONTROL, ORDINARY, UNCOVERED).map {
        GraphNode(it, it.value.substringAfter('#').substringBefore('('), NodeKind.METHOD)
    }, listOf(GraphEdge(CONTROL, ORDINARY, EdgeKind.CALL)))

    private fun narrow(snapshot: QuerySnapshot, removed: Set<NodeId>): QuerySnapshot {
        val original = snapshot.graph
        val narrowed = CodeGraph(original.nodes.values.filter { it.id !in removed }, original.edges)
            .preserveCompilerSourceUsesFrom(original)
        val graph = if (original.compilerCallPositionsCaptured) narrowed.withCompilerCallPositions(
            original.locatedCompilerReferences.filter { it.source !in removed && it.target !in removed }) else narrowed
        return snapshot.copy(graph = graph)
    }

    private fun digest(fields: List<String>): String {
        val digest = MessageDigest.getInstance("SHA-256")
        fields.forEach { field ->
            val bytes = field.toByteArray(Charsets.UTF_8)
            digest.update(byteArrayOf((bytes.size ushr 24).toByte(), (bytes.size ushr 16).toByte(),
                (bytes.size ushr 8).toByte(), bytes.size.toByte()))
            digest.update(bytes)
        }
        return digest.digest().joinToString("") { "%02x".format(it.toInt() and 0xff) }
    }

    private fun rewrite(text: String, mutation: (MutableMap<String, Any?>) -> Unit): String =
        document(text).also(mutation).let(::jsonValue)
    private fun document(text: String): MutableMap<String, Any?> = obj(SnapshotJsonParser(text).parse())
    private fun extension(document: MutableMap<String, Any?>): MutableMap<String, Any?> = obj(obj(document["graph"])["compilerSourceUses"])
    private fun root(extension: MutableMap<String, Any?>): MutableMap<String, Any?> = obj(rows(extension["roots"]).first())
    private fun unit(extension: MutableMap<String, Any?>): MutableMap<String, Any?> = obj(rows(extension["units"]).first())
    private fun use(extension: MutableMap<String, Any?>): MutableMap<String, Any?> = obj(rows(extension["uses"]).first())
    @Suppress("UNCHECKED_CAST")
    private fun obj(value: Any?): MutableMap<String, Any?> = value as MutableMap<String, Any?>
    @Suppress("UNCHECKED_CAST")
    private fun rows(value: Any?): MutableList<Any?> = value as MutableList<Any?>

    private companion object {
        const val SCOPE = ":demo:jvm"
        val ERASED = NodeId("method:demo/ProbeKt#kotlinCaller()I")
        val WRAPPER = NodeId("method:demo/ProbeKt#wrap(I)I")
        val CONTROL = NodeId("method:demo/ProbeKt#positionControl()I")
        val ORDINARY = NodeId("method:demo/ProbeKt#ordinaryControl()I")
        val UNCOVERED = NodeId("method:other/Leaf#javaDirect()I")
        val CLASSES = InputFingerprint("classes", "build/classes", "b".repeat(64))
        val UNCOVERED_CLASSES = InputFingerprint("classes", "build/uncovered", "c".repeat(64))
        val RECEIPT = InputFingerprint("compilerEvidence", "build/evidence.tsv", "9".repeat(64))
        val ARTIFACT = "d".repeat(64)
        val SOURCE = CompilerEvidenceSource("src/demo/Probe.kt", "a".repeat(64))
    }
}
