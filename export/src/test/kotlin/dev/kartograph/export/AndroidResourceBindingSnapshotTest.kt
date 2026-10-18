package dev.kartograph.export

import dev.kartograph.core.*
import kotlin.test.*

class AndroidResourceBindingSnapshotTest {
    @Test
    fun `legacy and facts only remain pure optional round trips`() {
        val legacy = fixture().copy(androidResourceBindings = null, androidResourceEvidence = null)
        val factsOnly = fixture().copy(androidResourceBindings = null)
        for (snapshot in listOf(legacy, factsOnly)) {
            val text = QuerySnapshotCodec.render(snapshot)
            val decoded = QuerySnapshotCodec.parse(text)
            assertNull(decoded.androidResourceBindings)
            assertEquals(snapshot.limitations, decoded.limitations)
            assertEquals(text, QuerySnapshotCodec.render(decoded))
        }
    }

    @Test
    fun `binding record round trips with exact full facts and producer identities`() {
        val snapshot = fixture()
        for (compact in listOf(false, true)) {
            val text = QuerySnapshotCodec.render(snapshot, compact)
            val decoded = QuerySnapshotCodec.parse(text)
            assertEquals(snapshot.androidResourceBindings, decoded.androidResourceBindings)
            assertEquals(text, QuerySnapshotCodec.render(decoded, compact))
            assertEquals(2, decoded.androidResourceBindings!!.outcomes.size)
        }
    }

    @Test
    fun `strict optional decoder rejects null extra fields wrong identities and incomplete keys`() {
        val text = QuerySnapshotCodec.render(fixture())
        val mutations: List<(MutableMap<String, Any?>) -> Unit> = listOf(
            { it["androidResourceBindings"] = null },
            { binding(it)["version"] = 2L },
            { binding(it)["extra"] = true },
            { binding(it)["resourceFactsSha256"] = "0".repeat(64) },
            { binding(it)["producerSha256"] = "0".repeat(64) },
            { binding(it)["scope"] = ":other:debug" },
            { binding(it)["namespace"] = "dev.other" },
            { obj(binding(it)["output"])["sha256"] = "0".repeat(64) },
            { obj(binding(it)["namespaceOptions"])["extra"] = true },
            { rows(binding(it)["mainRoots"]).clear() },
            { rows(binding(it)["outcomes"]).removeAt(0) },
            { rows(binding(it)["outcomes"]).add(rows(binding(it)["outcomes"]).first()) },
            { obj(rows(binding(it)["outcomes"]).first())["field"] = "field:dev/other/R\$layout#screen:I" },
            { obj(rows(binding(it)["outcomes"]).first())["unavailable"] = "fieldMissing" },
        )
        for (mutate in mutations) {
            assertFailsWith<IllegalArgumentException> { QuerySnapshotCodec.parse(rewrite(text, mutate)) }
        }
    }

    @Test
    fun `facts payload mutation and dangling graph cannot retain bindings`() {
        val snapshot = fixture()
        val facts = snapshot.androidResourceEvidence!!
        val changed = AndroidResourceEvidence(facts.scope, facts.namespace, facts.roots, facts.definitions,
            listOf(AndroidLayoutInclude(facts.definitions.first().id, 0, AndroidLayoutIncludeKind.DYNAMIC)))
        assertNotEquals(AndroidResourceBindingIdentity.facts(facts), AndroidResourceBindingIdentity.facts(changed))
        assertFailsWith<IllegalArgumentException> { snapshot.copy(androidResourceEvidence = changed) }
        assertFailsWith<IllegalArgumentException> { snapshot.copy(androidResourceEvidence = null) }
        assertFailsWith<IllegalArgumentException> { snapshot.copy(provenance = null) }
        val pruned = CodeGraph(snapshot.graph.nodes.values.filterNot { it.id.value.endsWith("#other:I") }, snapshot.graph.edges)
        assertFailsWith<IllegalArgumentException> { snapshot.copy(graph = pruned) }
        val text = QuerySnapshotCodec.render(snapshot)
        assertFailsWith<IllegalArgumentException> { QuerySnapshotCodec.parse(rewrite(text) { document ->
            rows(obj(document["graph"])["nodes"]).removeIf { obj(it)["usr"] == "field:dev/app/R\$layout#other:I" }
        }) }
        val enriched = CodeGraph(snapshot.graph.nodes.values.map { it.copy(location = SourceLocation("src/R.java", 1)) }, snapshot.graph.edges)
        assertEquals(snapshot.androidResourceBindings, snapshot.copy(graph = enriched).androidResourceBindings)
    }

    @Test
    fun `missing static flags and final fields cannot acquire recorded bindings`() {
        val snapshot = fixture()
        for (flags in listOf(emptySet(), setOf(JvmModifier.STATIC, JvmModifier.FINAL))) {
            val graph = CodeGraph(snapshot.graph.nodes.values.map {
                if (it.kind == NodeKind.FIELD) it.copy(jvmModifiers = flags) else it
            }, snapshot.graph.edges)
            assertFailsWith<IllegalArgumentException> { snapshot.copy(graph = graph) }
        }
        assertFailsWith<IllegalArgumentException> { snapshot.copy(provenance = snapshot.provenance!!.copy(memberScopes = listOf(SCOPE))) }
    }

    private fun fixture(): QuerySnapshot {
        val namespace = "dev.app"
        val root = AndroidResourceRoot(InputFingerprint("android-resources", "src/main/res", "1".repeat(64)))
        val definitions = listOf("screen", "other").map { name ->
            AndroidLayoutDefinition(AndroidResourceKey(namespace, "layout", name), root.id,
                CompilerEvidenceSource("src/main/res/layout/$name.xml", "2".repeat(64)), "layout/$name.xml")
        }
        val facts = AndroidResourceEvidence(SCOPE, namespace, listOf(root), definitions, emptyList())
        val outcomes = definitions.map { definition ->
            val id = NodeId("field:dev/app/R\$layout#" + definition.key.name + ":I")
            AndroidResourceFieldOutcome(definition.key, field = id)
        }
        val owner = NodeId("class:dev/app/R\$layout")
        val graph = CodeGraph(listOf(GraphNode(owner, "R.layout", NodeKind.CLASS)) + outcomes.map {
            GraphNode(it.field!!, it.key.name, NodeKind.FIELD, jvmSignature = requireNotNull(it.field).value.removePrefix("field:"),
                jvmModifiers = setOf(JvmModifier.STATIC))
        }, outcomes.map { GraphEdge(owner, it.field!!, EdgeKind.MEMBER) })
        val options = InputFingerprint("options", "processDebugResources-options", AndroidResourceBindingIdentity.namespaceOptions(namespace))
        val output = InputFingerprint("classes", "build/R.jar", "3".repeat(64))
        val inputs = listOf("sources", "buildConfig", "compiler").map { InputFingerprint(it, it, "4".repeat(64)) } + options + root.input
        val witness = BuildWitness(SCOPE, "agp-process-resources", ":processDebugResources", inputs, listOf(output))
        val provenance = SnapshotProvenance(listOf(output), listOf(witness))
        val binding = AndroidResourceBindingEvidence(SCOPE, namespace, AndroidResourceBindingIdentity.facts(facts),
            witness.artifact, AndroidResourceBindingIdentity.producer(witness), options, output, listOf(root.input), outcomes)
        return QuerySnapshot(graph, emptyList(), emptyList(), scope = SCOPE, provenance = provenance,
            androidResourceEvidence = facts, androidResourceBindings = binding)
    }

    @Suppress("UNCHECKED_CAST")
    private fun obj(value: Any?): MutableMap<String, Any?> = value as MutableMap<String, Any?>
    @Suppress("UNCHECKED_CAST")
    private fun rows(value: Any?): MutableList<Any?> = value as MutableList<Any?>
    private fun binding(value: MutableMap<String, Any?>) = obj(value["androidResourceBindings"])
    private fun rewrite(text: String, mutate: (MutableMap<String, Any?>) -> Unit): String =
        obj(SnapshotJsonParser(text).parse()).also(mutate).let(::jsonValue)

    private companion object { const val SCOPE = ":app:debug" }
}
