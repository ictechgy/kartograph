package dev.kartograph.analysis

import dev.kartograph.core.*
import kotlin.test.*

class AndroidResourceImpactTest {
    @Test
    fun exactLayoutSelectsOnlyItsFieldAndRecordedCaller() {
        val fixture = fixture()
        val report = ChangeImpact.analyze(fixture.input(), files = listOf(fixture.path("child")))
        assertEquals(setOf(fixture.field("child")), report.changed.map { it.node.id }.toSet())
        assertEquals(setOf(fixture.caller("child")), report.affected.map { it.node.id }.toSet())
        assertEquals(setOf(fixture.field("child")), report.selectedByRevision.getValue(ImpactRevision.CURRENT))
        assertTrue(report.unresolved.isEmpty())
        val selection = report.resourceSelections.getValue(ImpactRevision.CURRENT)
        assertEquals(AndroidResourceImpactStatus.ADMITTED, selection.status)
        assertEquals("partial", selection.coverage)
        assertEquals(listOf(fixture.path("child")), selection.files.single().definitions.map { it.file.path })
        assertEquals(listOf("child"), selection.resources.map { it.key.name })
        assertEquals(fixture.binding.resourceFactsSha256, selection.binding!!.resourceFactsSha256)
        assertEquals(fixture.binding.producerSha256, selection.binding!!.producerSha256)
        assertEquals(fixture.binding.output, selection.binding!!.output)
        assertEquals(listOf(fixture.caller("child"), fixture.field("child")), report.affected.single().paths.single().nodes)
        assertEquals(EdgeKind.REFERENCE, report.affected.single().paths.single().edges.single().kind)
    }

    @Test
    fun sameNamedLayoutInAnotherNamespaceNeverAddsForeignFieldOrCaller() {
        val one = fixture(namespace = "dev.one", rootPath = "one/src/main/res", scope = ":one:debug")
        val two = fixture(namespace = "dev.two", rootPath = "two/src/main/res", scope = ":two:debug")
        val graph = CodeGraph(one.graph.nodes.values + two.graph.nodes.values, one.graph.edges + two.graph.edges)
        val report = ChangeImpact.analyze(one.input(graph), files = listOf(one.path("child")))
        assertEquals(listOf(one.caller("child")), report.affected.map { it.node.id })
        assertEquals(listOf(one.field("child")), report.changed.map { it.node.id })
        val outside = ChangeImpact.analyze(one.input(graph), files = listOf(two.path("child")))
        assertTrue(outside.changed.isEmpty())
        assertEquals("resourceOutsideRoots", outside.unresolved.single().reason)
    }

    @Test
    fun reverseIncludesRetainQualifiersAndTerminateCyclesWithoutUnrelatedLayouts() {
        val fixture = fixture(
            names = listOf("child", "parent", "grand", "other", "unrelated"),
            qualifiers = mapOf("child" to listOf("", "land"), "parent" to listOf("", "night")),
            includes = listOf(
                IncludeSpec("parent", "child"), IncludeSpec("grand", "parent"),
                IncludeSpec("child", "grand"), IncludeSpec("unrelated", "other"),
                IncludeSpec("parent", "child", qualifier = "night"),
            ),
        )
        val report = ChangeImpact.analyze(fixture.input(), files = listOf(fixture.path("child", "land")))
        val selection = report.resourceSelections.getValue(ImpactRevision.CURRENT)
        assertEquals(setOf("child", "parent", "grand"), selection.resources.map { it.key.name }.toSet())
        assertEquals(5, selection.resources.sumOf { it.definitions.size })
        assertEquals(4, selection.includes.size)
        assertEquals(setOf("child", "parent", "grand").map { fixture.caller(it) }.toSet(),
            report.affected.map { it.node.id }.toSet())
        assertEquals(listOf("land"), selection.files.single().definitions.map { it.qualifier })
        assertEquals(setOf("", "land"), selection.resources.single { it.key.name == "child" }.definitions.map { it.qualifier }.toSet())
        assertTrue(report.unresolved.isEmpty())
        assertEquals(fixture.graph.edges.size, fixture.input().graph.edges.size)
    }

    @Test
    fun nonliteralSitesElsewhereKeepCoveragePartialWithoutInventingParents() {
        val fixture = fixture(includes = listOf(
            IncludeSpec("other", null, AndroidLayoutIncludeKind.DYNAMIC),
            IncludeSpec("other", "child", AndroidLayoutIncludeKind.EXTERNAL_NAMESPACE),
            IncludeSpec("other", null, AndroidLayoutIncludeKind.UNSUPPORTED),
        ))
        val report = ChangeImpact.analyze(fixture.input(), files = listOf(fixture.path("child")))
        val selection = report.resourceSelections.getValue(ImpactRevision.CURRENT)
        assertEquals("partial", selection.coverage)
        assertTrue(selection.limitations.any { it.startsWith("resource-impact-include-coverage: 3 ") })
        assertTrue(selection.includes.isEmpty())
        assertEquals(setOf(fixture.field("child")), selection.selectedFields)
        assertEquals(listOf(fixture.caller("child")), report.affected.map { it.node.id })
        assertTrue(report.unresolved.isEmpty())
    }

    @Test
    fun legacyAndUnboundFactsRemainExplicitlyUnavailable() {
        val fixture = fixture()
        val contexts = listOf(
            null to "resourceEvidenceUnavailable",
            AndroidResourceImpactContext(scope = fixture.scope) to "resourceEvidenceUnavailable",
            AndroidResourceImpactContext(fixture.facts, scope = fixture.scope) to "resourceBindingUnavailable",
        )
        for ((context, reason) in contexts) {
            val report = ChangeImpact.analyze(ImpactInput(fixture.graph, androidResources = context),
                files = listOf(fixture.path("child")))
            assertTrue(report.changed.isEmpty())
            assertTrue(report.affected.isEmpty())
            assertEquals(reason, report.unresolved.single().reason)
            assertEquals(ImpactRevision.CURRENT, report.unresolved.single().revision)
            assertEquals(AndroidResourceImpactStatus.UNAVAILABLE, report.resourceSelections.getValue(ImpactRevision.CURRENT).status)
            assertEquals("unknown", report.resourceSelections.getValue(ImpactRevision.CURRENT).coverage)
        }
    }

    @Test
    fun allRecordedUnavailableFieldReasonsStayVisible() {
        for (reason in AndroidResourceFieldUnavailable.values()) {
            val fixture = fixture(unavailable = mapOf("child" to reason))
            val report = ChangeImpact.analyze(fixture.input(), files = listOf(fixture.path("child")))
            assertTrue(report.changed.isEmpty(), reason.name)
            assertTrue(report.affected.isEmpty(), reason.name)
            assertEquals("resourceFieldUnavailable", report.unresolved.single().reason)
            val selection = report.resourceSelections.getValue(ImpactRevision.CURRENT)
            assertEquals(reason, selection.resources.single().outcome.unavailable)
            assertEquals("partial", selection.coverage)
            assertEquals(AndroidResourceImpactFileStatus.MATCHED, selection.files.single().status)
        }
    }

    @Test
    fun unavailableChildFieldStillAllowsIndependentlyBoundLiteralParent() {
        val fixture = fixture(names = listOf("child", "parent", "other"),
            includes = listOf(IncludeSpec("parent", "child")),
            unavailable = mapOf("child" to AndroidResourceFieldUnavailable.FINAL_FIELD))
        val report = ChangeImpact.analyze(fixture.input(), files = listOf(fixture.path("child")))
        assertEquals(listOf(fixture.field("parent")), report.changed.map { it.node.id })
        assertEquals(listOf(fixture.caller("parent")), report.affected.map { it.node.id })
        assertEquals("resourceFieldUnavailable", report.unresolved.single().reason)
        assertEquals(setOf("child", "parent"), report.resourceSelections.getValue(ImpactRevision.CURRENT).resources.map { it.key.name }.toSet())
    }

    @Test
    fun scopeAndNamespaceAreKeptSeparateAcrossRevisionsWithTheSameRelativePath() {
        val base = fixture(namespace = "dev.old", scope = ":old:debug")
        val current = fixture(namespace = "dev.new", scope = ":new:debug")
        val report = ChangeImpact.analyze(current.input(), files = listOf(base.path("child")), base = base.input())
        assertEquals(setOf(base.field("child")), report.selectedByRevision.getValue(ImpactRevision.BASE))
        assertEquals(setOf(current.field("child")), report.selectedByRevision.getValue(ImpactRevision.CURRENT))
        assertEquals(base.scope, report.resourceSelections.getValue(ImpactRevision.BASE).scope)
        assertEquals(current.namespace, report.resourceSelections.getValue(ImpactRevision.CURRENT).namespace)
        assertEquals(setOf(base.caller("child"), current.caller("child")), report.affected.map { it.node.id }.toSet())
        assertTrue(report.unresolved.isEmpty())
    }

    @Test
    fun baseOnlyBindingDoesNotReplayIdenticalFieldToUnavailableCurrentRevision() {
        val base = fixture()
        for (context in listOf(null, AndroidResourceImpactContext(base.facts, scope = base.scope))) {
            val current = ImpactInput(base.graph, androidResources = context)
            val report = ChangeImpact.analyze(current, files = listOf(base.path("child")), base = base.input())
            assertEquals(setOf(base.field("child")), report.selectedByRevision.getValue(ImpactRevision.BASE))
            assertTrue(report.selectedByRevision.getValue(ImpactRevision.CURRENT).isEmpty())
            assertEquals(listOf(ImpactRevision.BASE), report.affected.single().paths.map { it.revision })
            assertEquals(setOf(ImpactRevision.BASE, ImpactRevision.CURRENT), report.changed.single().presentIn)
            assertEquals(ImpactRevision.CURRENT, report.unresolved.single().revision)
        }
    }

    @Test
    fun currentFinalFieldDoesNotBorrowBaseAdmissionEvenWithSameJvmIdentity() {
        val base = fixture()
        val current = fixture(unavailable = mapOf("child" to AndroidResourceFieldUnavailable.FINAL_FIELD))
        val report = ChangeImpact.analyze(current.input(), files = listOf(base.path("child")), base = base.input())
        assertEquals(setOf(base.field("child")), report.selectedByRevision.getValue(ImpactRevision.BASE))
        assertTrue(report.selectedByRevision.getValue(ImpactRevision.CURRENT).isEmpty())
        assertEquals(listOf(ImpactRevision.BASE), report.affected.single().paths.map { it.revision })
        assertEquals("resourceFieldUnavailable", report.unresolved.single().reason)
        assertEquals(ImpactRevision.CURRENT, report.unresolved.single().revision)
    }

    @Test
    fun deletionAndRenameUseExactInventoryAbsenceWithoutUnknownSide() {
        val base = fixture(names = listOf("old", "other"))
        val current = fixture(names = listOf("new", "other"))
        val deleted = ChangeImpact.analyze(current.input(), files = listOf(base.path("old")), base = base.input())
        assertTrue(deleted.unresolved.isEmpty())
        assertEquals(listOf(base.caller("old")), deleted.affected.map { it.node.id })
        assertEquals(AndroidResourceImpactFileStatus.ABSENT,
            deleted.resourceSelections.getValue(ImpactRevision.CURRENT).files.single().status)
        val renamed = ChangeImpact.analyze(current.input(), files = listOf(base.path("old"), current.path("new")), base = base.input())
        assertTrue(renamed.unresolved.isEmpty())
        assertEquals(setOf(base.field("old")), renamed.selectedByRevision.getValue(ImpactRevision.BASE))
        assertEquals(setOf(current.field("new")), renamed.selectedByRevision.getValue(ImpactRevision.CURRENT))
        assertEquals(setOf(base.caller("old"), current.caller("new")), renamed.affected.map { it.node.id }.toSet())
    }

    @Test
    fun knownEmptyInventoryAndBothAbsentRemainUnmapped() {
        val fixture = fixture(names = emptyList())
        val report = ChangeImpact.analyze(fixture.input(), files = listOf(fixture.path("missing")), base = fixture.input())
        assertTrue(report.changed.isEmpty())
        assertEquals("unmappedFile", report.unresolved.single().reason)
        assertNull(report.unresolved.single().revision)
        assertTrue(report.resourceSelections.values.all {
            it.status == AndroidResourceImpactStatus.ADMITTED && it.files.single().status == AndroidResourceImpactFileStatus.ABSENT
        })
    }

    @Test
    fun absenceOutsideDeclaredRootAndInvalidPortablePathsAreNotSuccess() {
        val fixture = fixture()
        val outside = AndroidResourceImpact.select(fixture.graph, fixture.context(), listOf("other/res/layout/child.xml"))
        assertEquals(AndroidResourceImpactFileStatus.OUTSIDE_ROOT, outside.files.single().status)
        for (path in listOf("/src/main/res/layout/child.xml", "src/../res/layout/child.xml",
            "external/res/layout/child.xml", "./src/main/res/layout/child.xml", "src/main/res/layout/Bad.xml",
            "src/main/res//layout/child.xml", "C:/res/layout/child.xml", "src\\main\\res\\layout\\child.xml")) {
            val selection = AndroidResourceImpact.select(fixture.graph, fixture.context(), listOf(path))
            assertEquals(AndroidResourceImpactFileStatus.INVALID_PATH, selection.files.single().status, path)
            assertTrue(selection.selectedFields.isEmpty())
        }
        val basename = ChangeImpact.analyze(fixture.input(), files = listOf("child.xml"))
        assertTrue(basename.resourceSelections.isEmpty())
        assertTrue(basename.changed.isEmpty())
        assertEquals("unmappedFile", basename.unresolved.single().reason)
    }

    @Test
    fun entireBindingContextIsValidatedBeforeAnySelectorOrGraphTraversal() {
        val fixture = fixture()
        val pruned = CodeGraph(fixture.graph.nodes.values.filterNot { it.id == fixture.field("other") }, fixture.graph.edges)
        assertFailsWith<IllegalArgumentException> { fixture.input(pruned) }
        assertFailsWith<IllegalArgumentException> {
            AndroidResourceImpact.select(pruned, fixture.context(), listOf(fixture.path("child")))
        }
        for (context in listOf(
            AndroidResourceImpactContext(fixture.facts, fixture.binding, fixture.provenance, ":wrong:debug"),
            AndroidResourceImpactContext(null, fixture.binding, fixture.provenance, fixture.scope),
            AndroidResourceImpactContext(fixture.facts, fixture.binding, null, fixture.scope),
            AndroidResourceImpactContext(fixture.facts, fixture.binding, fixture.provenance, null),
            AndroidResourceImpactContext(fixture.facts, fixture.binding,
                fixture.provenance.copy(memberScopes = listOf(fixture.scope)), fixture.scope),
        )) {
            assertFailsWith<IllegalArgumentException> { ImpactInput(fixture.graph, androidResources = context) }
        }
        val changedFacts = AndroidResourceEvidence(fixture.scope, fixture.namespace, fixture.facts.roots,
            fixture.facts.definitions, listOf(AndroidLayoutInclude(fixture.facts.definitions.first().id, 0, AndroidLayoutIncludeKind.DYNAMIC)))
        assertFailsWith<IllegalArgumentException> {
            ImpactInput(fixture.graph, androidResources = AndroidResourceImpactContext(
                changedFacts, fixture.binding, fixture.provenance, fixture.scope))
        }
        assertFailsWith<IllegalArgumentException> {
            ImpactInput(fixture.graph, androidResources = AndroidResourceImpactContext(fixture.facts, scope = ":other:debug"))
        }
    }

    @Test
    fun budgetsRefuseAllResourceSeedsAndNeverSliceQualifiers() {
        val fixture = fixture(names = listOf("child", "parent", "grand"),
            qualifiers = mapOf("child" to listOf("", "land")),
            includes = listOf(IncludeSpec("parent", "child"), IncludeSpec("grand", "parent")))
        val cases = listOf(
            AndroidResourceImpactLimits(maxFiles = 1) to listOf(fixture.path("child"), fixture.path("parent")),
            AndroidResourceImpactLimits(maxResources = 1) to listOf(fixture.path("child")),
            AndroidResourceImpactLimits(maxDefinitions = 1) to listOf(fixture.path("child")),
            AndroidResourceImpactLimits(maxIncludes = 1) to listOf(fixture.path("child")),
        )
        for ((limits, files) in cases) {
            val result = AndroidResourceImpact.select(fixture.graph, fixture.context(), files, limits)
            assertEquals(AndroidResourceImpactStatus.REFUSED, result.status)
            assertTrue(result.selectedFields.isEmpty())
            assertTrue(result.resources.isEmpty())
            assertTrue(result.includes.isEmpty())
            assertEquals("partial", result.coverage)
            assertEquals(files.size, result.requestedFileCount)
            assertTrue(result.files.size <= limits.maxFiles)
            assertTrue(result.files.all { it.status == AndroidResourceImpactFileStatus.BUDGET_EXCEEDED })
            assertTrue(result.limitations.any { it.startsWith("resource-impact-budget:") })
        }
        val accepted = AndroidResourceImpact.select(fixture.graph, fixture.context(), listOf(fixture.path("child")),
            AndroidResourceImpactLimits(maxFiles = 1, maxResources = 3, maxDefinitions = 4, maxIncludes = 2))
        assertEquals(AndroidResourceImpactStatus.ADMITTED, accepted.status)
        assertEquals(3, accepted.selectedFields.size)
        for (create in listOf<() -> AndroidResourceImpactLimits>(
            { AndroidResourceImpactLimits(maxFiles = 0) }, { AndroidResourceImpactLimits(maxResources = 10_001) },
            { AndroidResourceImpactLimits(maxDefinitions = 0) }, { AndroidResourceImpactLimits(maxIncludes = 20_001) },
        )) assertFailsWith<IllegalArgumentException> { create() }
    }

    @Test
    fun changeImpactExposesResourceBudgetRefusalWithoutLosingOrdinarySeeds() {
        val fixture = fixture()
        val files = (0..256).map { fixture.path("missing_$it") }
        val report = ChangeImpact.analyze(fixture.input(), symbols = listOf(fixture.field("child").value), files = files)
        assertTrue(report.truncated.budget)
        assertEquals(setOf(fixture.field("child")), report.selectedByRevision.getValue(ImpactRevision.CURRENT))
        assertEquals(listOf(fixture.caller("child")), report.affected.map { it.node.id })
        assertTrue(report.unresolved.all { it.reason == "resourceBudgetExceeded" && it.revision == ImpactRevision.CURRENT })
        assertEquals(256, report.unresolved.size)
    }

    @Test
    fun explicitJvmSymbolKeepsOrdinaryCrossRevisionSelection() {
        val base = fixture()
        val current = ImpactInput(base.graph)
        val report = ChangeImpact.analyze(current, symbols = listOf(base.field("child").value),
            files = listOf(base.path("child")), base = base.input())
        assertEquals(setOf(base.field("child")), report.selectedByRevision.getValue(ImpactRevision.CURRENT))
        assertEquals(setOf(ImpactRevision.BASE, ImpactRevision.CURRENT), report.affected.single().paths.map { it.revision }.toSet())
        assertEquals("resourceEvidenceUnavailable", report.unresolved.single().reason)
    }

    @Test
    fun manifestAndNonlayoutXmlRetentionPreserveExistingSemantics() {
        val fixture = fixture()
        for (path in listOf("src/main/AndroidManifest.xml", "src/main/res/navigation/main.xml")) {
            val evidence = RetentionEvidence(fixture.field("child"), RetentionReason.XML_LAYOUT, SourceLocation(path))
            val base = fixture.input().copy(retention = listOf(evidence))
            val current = ImpactInput(fixture.graph)
            val report = ChangeImpact.analyze(current, files = listOf(path), base = base)
            assertTrue(report.unresolved.isEmpty())
            assertTrue(report.resourceSelections.isEmpty())
            assertEquals(setOf(ImpactRevision.BASE, ImpactRevision.CURRENT), report.affected.single().paths.map { it.revision }.toSet())
        }
    }

    @Test
    fun resultAndContextCollectionsAreDefensivelyFrozen() {
        val fixture = fixture()
        val inputs = fixture.provenance.inputs.toMutableList()
        val witnesses = fixture.provenance.witnesses.toMutableList()
        val context = AndroidResourceImpactContext(fixture.facts, fixture.binding,
            SnapshotProvenance(inputs, witnesses), fixture.scope)
        inputs.clear()
        witnesses.clear()
        val result = AndroidResourceImpact.select(fixture.graph, context, listOf(fixture.path("child")))
        assertEquals(setOf(fixture.field("child")), result.selectedFields)
        assertFailsWith<UnsupportedOperationException> { (result.files as MutableList<*>).clear() }
        assertFailsWith<UnsupportedOperationException> { (result.resources as MutableList<*>).clear() }
        assertFailsWith<UnsupportedOperationException> { (result.resources.single().definitions as MutableList<*>).clear() }
        assertFailsWith<UnsupportedOperationException> { (result.selectedFields as MutableSet<*>).clear() }
        assertFailsWith<UnsupportedOperationException> { (context.provenance!!.inputs as MutableList<*>).clear() }
        val repeated = AndroidResourceImpact.select(fixture.graph, fixture.context(), listOf(fixture.path("child")))
        assertEquals(result, repeated)
        assertEquals(result.hashCode(), repeated.hashCode())
        val report = ChangeImpact.analyze(fixture.input(), files = listOf(fixture.path("child")))
        assertFailsWith<UnsupportedOperationException> { (report.selectedByRevision as MutableMap<*, *>).clear() }
        assertFailsWith<UnsupportedOperationException> { (report.resourceSelections as MutableMap<*, *>).clear() }
        assertFailsWith<UnsupportedOperationException> {
            (report.selectedByRevision.getValue(ImpactRevision.CURRENT) as MutableSet<*>).clear()
        }
    }

    private data class IncludeSpec(
        val source: String,
        val target: String?,
        val kind: AndroidLayoutIncludeKind = AndroidLayoutIncludeKind.LITERAL,
        val qualifier: String = "",
    )

    private data class Fixture(
        val namespace: String,
        val scope: String,
        val rootPath: String,
        val facts: AndroidResourceEvidence,
        val binding: AndroidResourceBindingEvidence,
        val provenance: SnapshotProvenance,
        val graph: CodeGraph,
    ) {
        fun field(name: String) = NodeId("field:" + namespace.replace('.', '/') + "/R\$layout#$name:I")
        fun caller(name: String) = NodeId("method:" + namespace.replace('.', '/') + "/Use#$name()V")
        fun path(name: String, qualifier: String = "") =
            "$rootPath/layout" + (if (qualifier.isEmpty()) "" else "-$qualifier") + "/$name.xml"
        fun context() = AndroidResourceImpactContext(facts, binding, provenance, scope)
        fun input(graph: CodeGraph = this.graph) = ImpactInput(graph, androidResources = context())
    }

    private fun fixture(
        namespace: String = "dev.app",
        scope: String = ":app:debug",
        rootPath: String = "src/main/res",
        names: List<String> = listOf("child", "other"),
        qualifiers: Map<String, List<String>> = emptyMap(),
        includes: List<IncludeSpec> = emptyList(),
        unavailable: Map<String, AndroidResourceFieldUnavailable> = emptyMap(),
    ): Fixture {
        val root = AndroidResourceRoot(InputFingerprint("android-resources", rootPath, "1".repeat(64)))
        val definitions = names.flatMap { name ->
            qualifiers[name].orEmpty().ifEmpty { listOf("") }.map { qualifier ->
                val relative = "layout" + (if (qualifier.isEmpty()) "" else "-$qualifier") + "/$name.xml"
                AndroidLayoutDefinition(AndroidResourceKey(namespace, "layout", name), root.id,
                    CompilerEvidenceSource("$rootPath/$relative", "2".repeat(64)), relative, qualifier)
            }
        }
        val includeRows = includes.mapIndexed { ordinal, spec ->
            val source = definitions.single { it.key.name == spec.source && it.qualifier == spec.qualifier }
            val target = spec.target?.let {
                AndroidResourceKey(if (spec.kind == AndroidLayoutIncludeKind.EXTERNAL_NAMESPACE) "android" else namespace, "layout", it)
            }
            AndroidLayoutInclude(source.id, ordinal, spec.kind, target)
        }
        val facts = AndroidResourceEvidence(scope, namespace, listOf(root), definitions, includeRows)
        val outcomes = names.map { name ->
            val key = AndroidResourceKey(namespace, "layout", name)
            val reason = unavailable[name]
            AndroidResourceFieldOutcome(key,
                field = if (reason == null) NodeId("field:" + namespace.replace('.', '/') + "/R\$layout#$name:I") else null,
                unavailable = reason)
        }
        val owner = NodeId("class:" + namespace.replace('.', '/') + "/R\$layout")
        val nodes = mutableListOf(GraphNode(owner, "R.layout", NodeKind.CLASS))
        val edges = mutableListOf<GraphEdge>()
        for (outcome in outcomes) {
            if (outcome.unavailable != null && outcome.unavailable != AndroidResourceFieldUnavailable.FINAL_FIELD) continue
            val field = outcome.expectedField
            val caller = NodeId("method:" + namespace.replace('.', '/') + "/Use#${outcome.key.name}()V")
            nodes += GraphNode(field, outcome.key.name, NodeKind.FIELD, jvmSignature = field.value.removePrefix("field:"),
                jvmModifiers = if (outcome.unavailable == AndroidResourceFieldUnavailable.FINAL_FIELD)
                    setOf(JvmModifier.STATIC, JvmModifier.FINAL) else setOf(JvmModifier.STATIC))
            nodes += GraphNode(caller, "use_" + outcome.key.name, NodeKind.METHOD)
            edges += GraphEdge(owner, field, EdgeKind.MEMBER)
            edges += GraphEdge(caller, field, EdgeKind.REFERENCE)
        }
        val graph = CodeGraph(nodes, edges)
        val options = InputFingerprint("options", "processDebugResources-options", AndroidResourceBindingIdentity.namespaceOptions(namespace))
        val output = InputFingerprint("classes", "build/R.jar", "3".repeat(64))
        val inputs = listOf("sources", "buildConfig", "compiler").map { InputFingerprint(it, it, "4".repeat(64)) } + options + root.input
        val witness = BuildWitness(scope, "agp-process-resources", ":processDebugResources", inputs, listOf(output))
        val provenance = SnapshotProvenance(listOf(output), listOf(witness))
        val binding = AndroidResourceBindingEvidence(scope, namespace, AndroidResourceBindingIdentity.facts(facts),
            witness.artifact, AndroidResourceBindingIdentity.producer(witness), options, output, listOf(root.input), outcomes)
        return Fixture(namespace, scope, rootPath, facts, binding, provenance, graph)
    }
}
