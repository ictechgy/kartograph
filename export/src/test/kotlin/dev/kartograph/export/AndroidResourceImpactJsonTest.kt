package dev.kartograph.export

import dev.kartograph.analysis.*
import dev.kartograph.core.*
import kotlin.test.*

class AndroidResourceImpactJsonTest {
    @Test
    fun `ordinary and source edit wrappers retain the same resource selection and bounded output`() {
        val snapshot = fixture()
        val facts = requireNotNull(snapshot.androidResourceEvidence)
        val context = AndroidResourceImpactContext(facts, snapshot.androidResourceBindings, snapshot.provenance, snapshot.scope)
        val report = ChangeImpact.analyze(ImpactInput(snapshot.graph, androidResources = context),
            files = listOf(facts.definitions.single().file.path))
        val source = SourceEditImpact.analyze(snapshot.graph, report.selectedByRevision.getValue(ImpactRevision.CURRENT))
        val revision = SourceEditImpactRevision("resolved", source.changed, source.coverage, source)
        val document = SourceEditImpactDocument(report, mapOf("current" to snapshot),
            mapOf("current" to SourceSnapshotInput(snapshot.toolVersion, snapshot.scope, null, "5".repeat(64))),
            mapOf("current" to revision))
        val ordinary = obj(SnapshotJsonParser(ImpactReportCodec.render(report, mapOf("current" to snapshot))).parse())
        val wrapper = obj(SnapshotJsonParser(SourceUseDocumentRenderer.impact(document)).parse())
        val nested = obj(wrapper["graphImpact"])
        assertEquals(ordinary["resourceSelections"], nested["resourceSelections"])
        assertEquals(ordinary["selectedByRevision"], nested["selectedByRevision"])
        assertFailsWith<SourceUseDocumentSizeException> { SourceUseDocumentRenderer.impact(document, 256) }
        assertEquals("partial", obj(obj(ordinary["resourceSelections"])["current"])["coverage"])
    }

    @Test
    fun `source wrapper freezes caller supplied revision maps`() {
        val snapshot = fixture()
        val facts = requireNotNull(snapshot.androidResourceEvidence)
        val report = ChangeImpact.analyze(ImpactInput(snapshot.graph, androidResources =
            AndroidResourceImpactContext(facts, snapshot.androidResourceBindings, snapshot.provenance, snapshot.scope)),
            files = listOf(facts.definitions.single().file.path))
        val ids = report.selectedByRevision.getValue(ImpactRevision.CURRENT).toMutableSet()
        val selections = report.resourceSelections.toMutableMap()
        val selected = mutableMapOf<ImpactRevision, Set<NodeId>>(ImpactRevision.CURRENT to ids)
        val inspection = SourceEditImpact.analyze(snapshot.graph, ids)
        val document = SourceEditImpactDocument(report.copy(selectedByRevision = selected, resourceSelections = selections),
            mapOf("current" to snapshot),
            mapOf("current" to SourceSnapshotInput(snapshot.toolVersion, snapshot.scope, null, "5".repeat(64))),
            mapOf("current" to SourceEditImpactRevision("resolved", inspection.changed, inspection.coverage, inspection)))
        val before = SourceUseDocumentRenderer.impact(document)
        ids.clear(); selected.clear(); selections.clear()
        assertEquals(before, SourceUseDocumentRenderer.impact(document))
    }

    private fun fixture(): QuerySnapshot {
        val scope = ":app:debug"
        val namespace = "dev.example"
        val root = AndroidResourceRoot(InputFingerprint("android-resources", "src/main/res", "1".repeat(64)))
        val definition = AndroidLayoutDefinition(AndroidResourceKey(namespace, "layout", "screen"), root.id,
            CompilerEvidenceSource("src/main/res/layout/screen.xml", "2".repeat(64)), "layout/screen.xml")
        val facts = AndroidResourceEvidence(scope, namespace, listOf(root), listOf(definition), emptyList())
        val owner = NodeId("class:dev/example/R\$layout")
        val field = NodeId("field:dev/example/R\$layout#screen:I")
        val graph = CodeGraph(listOf(GraphNode(owner, "R.layout", NodeKind.CLASS),
            GraphNode(field, "screen", NodeKind.FIELD, jvmSignature = field.value.removePrefix("field:"),
                jvmModifiers = setOf(JvmModifier.STATIC))), listOf(GraphEdge(owner, field, EdgeKind.MEMBER)))
        val options = InputFingerprint("options", "app-processDebugResources-options",
            AndroidResourceBindingIdentity.namespaceOptions(namespace))
        val output = InputFingerprint("classes", "build/R.jar", "3".repeat(64))
        val inputs = listOf("sources", "buildConfig", "compiler").map { InputFingerprint(it, it, "4".repeat(64)) } + options + root.input
        val witness = BuildWitness(scope, "agp-process-resources", ":app:processDebugResources", inputs, listOf(output))
        val provenance = SnapshotProvenance(listOf(output), listOf(witness))
        val binding = AndroidResourceBindingEvidence(scope, namespace, AndroidResourceBindingIdentity.facts(facts),
            witness.artifact, AndroidResourceBindingIdentity.producer(witness), options, output, listOf(root.input),
            listOf(AndroidResourceFieldOutcome(definition.key, field = field)))
        return QuerySnapshot(graph, emptyList(), emptyList(), scope = scope, provenance = provenance,
            androidResourceEvidence = facts, androidResourceBindings = binding)
    }

    private fun obj(value: Any?): Map<*, *> = value as Map<*, *>
}
