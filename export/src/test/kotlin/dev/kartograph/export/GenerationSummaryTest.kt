package dev.kartograph.export

import dev.kartograph.analysis.*
import dev.kartograph.core.*
import kotlin.test.*

class GenerationSummaryTest {
    @Test
    fun `summary is bounded but preserves every state count and omitted declaration count`() {
        val snapshot = generatedSnapshot(100)
        val summary = requireNotNull(GenerationSummary.from(snapshot))
        val value = summary.value()
        val rows = value["receipts"] as List<*>
        assertTrue(rows.size in 1..GenerationSummary.MAX_ROWS)
        assertTrue(jsonValue(value).toByteArray().size <= GenerationSummary.MAX_BYTES)
        assertTrue(rows.size < 100)
        assertEquals(100, value["totalReceipts"])
        assertEquals(100 - rows.size, value["omittedReceipts"])
        assertEquals(100, value["totalDeclarations"])
        assertEquals(100 - rows.size, value["declarationsInOmittedReceipts"])
        assertEquals(true, value["truncated"])
        val states = (value["stateCounts"] as List<*>).map { it as Map<*, *> }
        assertEquals(setOf("SUCCEEDED", "UP_TO_DATE"), states.map { it["currentGenerationStatus"] }.toSet())
        states.forEach { assertEquals(50, it["receiptCount"]); assertEquals(50, it["declarationCount"]) }
        val reordered = snapshot.copy(generationEvidence = snapshot.generationEvidence!!.let {
            it.copy(receipts = it.receipts.reversed(), observations = it.observations.reversed(), declarations = it.declarations.reversed())
        })
        assertEquals(value, requireNotNull(GenerationSummary.from(reordered)).value())
        assertEquals("entireLoadedSnapshot", value["summaryScope"])
        assertEquals(false, value["resultSelectionApplied"])
        assertEquals("snapshotCapture", value["observationTime"])
        assertEquals(false, value["liveRevalidated"])
        assertContains(value["meaning"].toString(), "not runtime reachability")
        assertFalse(jsonValue(value).contains("providerBindings"))
        assertFalse(jsonValue(value).contains("classSha256"))
    }

    @Test
    fun `byte cap omits whole long rows and zero declaration receipts remain observed`() {
        val snapshot = generatedSnapshot(10, longNames = true, emptyDeclarations = true)
        val value = requireNotNull(GenerationSummary.from(snapshot)).value()
        val rows = value["receipts"] as List<*>
        assertTrue(rows.size < 10)
        assertTrue(jsonValue(value).toByteArray().size <= GenerationSummary.MAX_BYTES)
        rows.forEach { assertTrue(((it as Map<*, *>)["task"] as String).length > 900) }
        assertEquals(0, value["totalDeclarations"])
        assertEquals(0, value["declarationsInOmittedReceipts"])
        assertEquals(10, value["totalReceipts"])
    }

    @Test
    fun `query statuses retain snapshot summary and unchanged ordinary document and no evidence bytes`() {
        val snapshot = generatedSnapshot(2)
        val summary = GenerationSummary.from(snapshot)
        for (requested in listOf("class:demo/F0", "Foo", "Missing")) {
            val document = SymbolQuery.query(snapshot.graph, ReachabilityAnalyzer.analyze(snapshot.graph, snapshot.retention), requested, snapshot.limitations, 1, 1)
            val original = AgentDocumentRenderer.query(document, snapshot.graph)
            val rendered = parse(AgentDocumentRenderer.query(document, snapshot.graph, summary))
            assertEquals(parse(original), rendered - "generationSummary")
            assertEquals(summary!!.value(), rendered["generationSummary"].normalizeNumbers())
            assertEquals(original, AgentDocumentRenderer.query(document, snapshot.graph, null))
            val inspected = if (document.status == "found") SourceUseQuery.inspect(snapshot.graph, NodeId(document.result!!.subject.usr!!), SourceUsePageRequest()) else null
            val wrapper = SourceUseQueryDocument(document, SourceSnapshotInput(snapshot.toolVersion, snapshot.scope, snapshot.revision, HASH),
                inspected?.coverage ?: SourceUseCoverage.from(snapshot.graph), inspected)
            val source = parse(SourceUseDocumentRenderer.query(wrapper, snapshot.graph, summary))
            assertEquals(rendered, source["query"])
            val oldBudget = SourceUseDocumentRenderer.query(wrapper, snapshot.graph).toByteArray().size
            assertFailsWith<SourceUseDocumentSizeException> {
                SourceUseDocumentRenderer.query(wrapper, snapshot.graph, oldBudget, summary)
            }
        }
        val ordinary = QuerySnapshot(CodeGraph(emptyList(), emptyList()), emptyList(), emptyList())
        assertNull(GenerationSummary.from(ordinary))
    }

    @Test
    fun `impact keeps each revision summary when result page is empty and source wrapper matches`() {
        val current = generatedSnapshot(2, revision = "2".repeat(40))
        val base = generatedSnapshot(3, revision = "1".repeat(40), warmAll = true)
        val snapshots = mapOf("current" to current, "base" to base)
        val report = ChangeImpact.analyze(ImpactInput(current.graph, current.retention, current.limitations),
            symbols = emptyList(), files = emptyList(), base = ImpactInput(base.graph, base.retention, base.limitations))
        val json = parse(ImpactReportCodec.render(report, snapshots, 1))
        assertEquals("noChanges", json["status"])
        val inputs = json["inputs"] as Map<*, *>
        assertEquals(2L, ((inputs["current"] as Map<*, *>)["generationSummary"] as Map<*, *>)["totalReceipts"])
        assertEquals(3L, ((inputs["base"] as Map<*, *>)["generationSummary"] as Map<*, *>)["totalReceipts"])
        assertContains(json["limitations"] as List<*>, "base: generation-unknown: unsupported producer remains unclassified")
        assertContains(json["limitations"] as List<*>, "current: generation-unknown: unsupported producer remains unclassified")
        val sourceInputs = snapshots.mapValues { (_, snapshot) -> SourceSnapshotInput(snapshot.toolVersion, snapshot.scope, snapshot.revision, HASH) }
        val revisions = snapshots.mapValues { (_, snapshot) ->
            val inspection = SourceEditImpact.analyze(snapshot.graph, emptySet(), SourceUsePageRequest())
            SourceEditImpactRevision("noChanges", emptyList(), inspection.coverage, inspection)
        }
        val document = SourceEditImpactDocument(report, snapshots, sourceInputs, revisions, 1)
        assertEquals(json, parse(SourceUseDocumentRenderer.impact(document))["graphImpact"])
        val ordinary = snapshots.mapValues { (_, value) -> value.copy(graph = CodeGraph(emptyList(), emptyList()), generationEvidence = null) }
        val without = parse(ImpactReportCodec.render(report, ordinary, 1))
        assertEquals(without, json + ("inputs" to (inputs.mapValues { (_, raw) -> (raw as Map<*, *>) - "generationSummary" })))
    }

    @Test
    fun `summary freezes scalar rows without retaining mutable receipt inventories`() {
        val snapshot = generatedSnapshot(1)
        val original = snapshot.generationEvidence!!
        val receipts = original.receipts.toMutableList()
        val mutable = snapshot.copy(generationEvidence = original.copy(receipts = receipts))
        val summary = requireNotNull(GenerationSummary.from(mutable))
        val before = jsonValue(summary.value())
        receipts.clear()
        assertEquals(before, jsonValue(summary.value()))
        assertFailsWith<IllegalArgumentException> { GenerationSummary.from(mutable) }
    }

    private fun parse(value: String) = McpJsonCodec.parse(value) as Map<*, *>
    private fun Any?.normalizeNumbers(): Any? = when (this) {
        is Long -> toInt()
        is Map<*, *> -> mapValues { it.value.normalizeNumbers() }
        is List<*> -> map { it.normalizeNumbers() }
        else -> this
    }

    companion object {
        private val HASH = "a".repeat(64)
        internal fun generatedSnapshot(count: Int, revision: String = "1".repeat(40), warmAll: Boolean = false, longNames: Boolean = false, emptyDeclarations: Boolean = false): QuerySnapshot {
            fun input(role: String, path: String) = InputFingerprint(role, path, HASH)
            val provenance = SnapshotProvenance(listOf(input("classes", "build/classes")), listOf(BuildWitness("app:debug", "javac", ":app:compileDebugJavaWithJavac",
                listOf(input("sources", "build/generated"), input("buildConfig", "build.gradle"), input("compiler", "external/compiler"), input("options", "config/javac")),
                listOf(input("classes", "build/classes")))))
            val receipts = (0 until count).map { index ->
                val root = "build/generated$index"
                val nav = "res/navigation/nav$index.xml"
                GenerationReceipt("app:debug", ":app:" + (if (longNames) "g".repeat(950) else "generate") + index.toString().padStart(3, '0'), GenerationReceipt.POLICY,
                    index.toString(16).padStart(64, '0'), root,
                    listOf(input("navigation", nav), input("generatorArtifact", "external/generator"), input("generatorActionArtifact", "external/action"), input("generatorOptions", "generation/options")),
                    input("generationMapping", "build/mapping$index.json"), listOf(input("generatedSource", "$root/demo/F$index.java")),
                    listOf(GenerationSource(nav, "$root/demo/F$index.java", "demo.F$index", HASH)))
            }
            val declarations = if (emptyDeclarations) emptyList() else receipts.mapIndexed { index, receipt -> GeneratedDeclaration(receipt.scope, receipt.task, receipt.attempt,
                ":app:compileDebugJavaWithJavac", receipt.sources.single().path, HASH, NodeId("class:demo/F$index"), listOf(NodeId("class:demo/F$index")),
                "build/classes", "build/classes", "demo/F$index.class", HASH) }
            val observations = receipts.mapIndexed { index, receipt ->
                val context = GenerationOriginContext(GenerationDigests.receipt(receipt), receipt.scope, receipt.task, "debug", receipt.outputRoot, receipt.mapping.path,
                    listOf(GenerationProviderBinding("navigationFiles", receipt.inputs.filter { it.role == "navigation" }.map { it.path }),
                        GenerationProviderBinding("outputDir", listOf(receipt.outputRoot)), GenerationProviderBinding("incrementalFolder", listOf("build"))),
                    GenerationOriginContext.ACTION_POLICY, receipt.inputs.filter { it.role == "generatorActionArtifact" })
                val warm = warmAll || index % 2 == 1
                GenerationConsumptionObservation(context, context, HASH, if (warm) null else receipt.attempt,
                    if (warm) "historical-byte-equivalence" else "current-cold-success", if (warm) "UP_TO_DATE" else "SUCCEEDED",
                    GenerationDigests.inventory(receipt), GenerationDigests.declarations(declarations.filter { it.generatorTask == receipt.task }, provenance))
            }
            val graph = CodeGraph(declarations.map { GraphNode(it.owner, "Foo", NodeKind.CLASS, synthesized = true,
                attributes = setOf(NodeAttribute.GENERATED_SOURCE_EVIDENCE)) }, emptyList())
            return QuerySnapshot(graph, emptyList(), listOf("generation-unknown: unsupported producer remains unclassified"), revision = revision,
                scope = "app:debug", provenance = provenance, generationEvidence = GenerationEvidence(receipts, declarations, observations))
        }
    }
}
