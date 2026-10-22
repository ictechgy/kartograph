package dev.kartograph.export

import dev.kartograph.analysis.*
import dev.kartograph.core.*
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class SourceUseDocumentRendererTest {
    @Test
    fun `query nested values preserve ordinary optional fields compiler references and default isolation`() {
        val graph = fixture(listOf("A", "B"), partial = true)
        val ordinary = query(graph)
        val originalBytes = AgentDocumentRenderer.query(ordinary, graph)
        val inspection = SourceUseQuery.inspect(graph, C)
        val document = SourceUseQueryDocument(ordinary, identity(), inspection.coverage, inspection)
        val wrapper = parse(SourceUseDocumentRenderer.query(document, graph))
        assertEquals(parse(originalBytes), wrapper["query"])
        assertEquals(originalBytes, AgentDocumentRenderer.query(ordinary, graph))
        assertEquals("kartograph-source-use-query", wrapper["format"])
        assertEquals(1L, wrapper["version"])
        assertEquals(WARNINGS, wrapper["warnings"])
        assertEquals(setOf("format", "version", "inputs", "warnings", "query", "sourceUses"), wrapper.keys)
        assertEquals("found", obj(wrapper["sourceUses"])["selectionStatus"])
        assertEquals(C.value, obj(wrapper["sourceUses"])["subject"])
        val ordinaryValue = obj(wrapper["query"])
        val references = list(obj(ordinaryValue["result"])["usedBy"]).flatMap { list(obj(it)["references"]) }
        assertTrue(references.any { obj(it)["origin"] == "compiler" })
        assertTrue(references.any { obj(it)["origin"] == "bytecode" })
        val withoutGraph = parse(SourceUseDocumentRenderer.query(document))
        assertEquals(parse(AgentDocumentRenderer.query(ordinary)), withoutGraph["query"])

        val nullNeighbor = SymbolQueryNeighbor("missing-metadata", "missing-metadata", "method", null, null,
            emptyList(), 2, SourceLocation("OnlyPath.kt"))
        val rich = ordinary.copy(result = ordinary.result!!.copy(
            members = listOf(nullNeighbor), declaredIn = nullNeighbor,
            dependsOn = listOf(nullNeighbor),
            reachability = SymbolReachability("reachable", RetentionReason.KEEP_RULE, listOf("root", "leaf"), true),
        ))
        val richWrapper = parse(SourceUseDocumentRenderer.query(SourceUseQueryDocument(rich, identity(), inspection.coverage, inspection), graph))
        assertEquals(parse(AgentDocumentRenderer.query(rich, graph)), richWrapper["query"])
        val renderedNeighbor = obj(list(obj(obj(richWrapper["query"])["result"])["members"]).single())
        assertFalse(renderedNeighbor.containsKey("usr"))
        assertFalse(renderedNeighbor.containsKey("module"))
        assertFalse(renderedNeighbor.containsKey("references"))
        assertEquals(mapOf("path" to "OnlyPath.kt"), renderedNeighbor["location"])
    }

    @Test
    fun `ambiguous and missing selections preserve full candidates and global coverage with null page`() {
        val graph = fixture(listOf("A"), partial = true)
        val coverage = SourceUseCoverage.from(graph)
        val queries = listOf(
            SymbolQueryDocument("notFound", "missing", "symbol", listOf("z", "a")),
            SymbolQueryDocument("ambiguous", "run", "symbol", listOf("z", "a"), candidates = listOf(
                SymbolQueryCandidate("candidate-without-usr", null), SymbolQueryCandidate("candidate", C.value),
            )),
        )
        for (query in queries) {
            val text = SourceUseDocumentRenderer.query(SourceUseQueryDocument(query, identity(), coverage, null), graph)
            val wrapper = parse(text)
            assertEquals(parse(AgentDocumentRenderer.query(query, graph)), wrapper["query"])
            val section = obj(wrapper["sourceUses"])
            assertEquals(query.status, section["selectionStatus"])
            assertNull(section["subject"])
            assertNull(section["page"])
            assertEquals("partial", obj(section["coverage"])["coverage"])
            assertEquals(2L, obj(section["coverage"])["selectedRootCount"])
            assertFailsWith<IllegalArgumentException> { QuerySnapshotCodec.parse(text) }
        }
    }

    @Test
    fun `absent old reserialized captured empty and partial states remain distinct`() {
        val captured = fixture(emptyList())
        val old = CodeGraph(captured.nodes.values.map { it.copy(sourceDeclaration = null) }, captured.edges)
        val oldReserialized = QuerySnapshotCodec.parse(QuerySnapshotCodec.render(QuerySnapshot(old, emptyList(), emptyList()))).graph
        for (graph in listOf(old, oldReserialized, captured, fixture(emptyList(), partial = true))) {
            val inspection = SourceUseQuery.inspect(graph, C)
            val section = obj(parse(SourceUseDocumentRenderer.query(
                SourceUseQueryDocument(query(graph), identity(), inspection.coverage, inspection), graph))["sourceUses"])
            val coverage = obj(section["coverage"])
            if (graph.compilerSourceUseEvidence == null) {
                assertEquals("unavailable", coverage["availability"])
                assertEquals("unknown", coverage["coverage"])
                assertNull(coverage["units"])
                assertNull(coverage["counts"])
                assertNull(coverage["selectedRootCount"])
                assertNull(coverage["uncoveredRootIds"])
                assertNull(section["page"])
            } else {
                assertEquals("captured", coverage["availability"])
                assertEquals(if (graph.compilerSourceUseEvidence!!.uncoveredRoots.isEmpty()) "unknown" else "partial", coverage["coverage"])
                assertEquals(1, list(coverage["units"]).size)
                val page = obj(section["page"])
                assertEquals(0L, page["observedUses"])
                assertEquals(0L, page["returnedUses"])
                assertEquals(emptyList<Any?>(), page["observations"])
                assertEquals(emptyList<Any?>(), page["bindings"])
            }
        }
    }

    @Test
    fun `source use fields equal Stage A and provenance binds only returned units`() {
        val graph = fixture(listOf("A", "B"), partial = true)
        val evidence = graph.compilerSourceUseEvidence!!
        val inspection = SourceUseQuery.inspect(graph, C, SourceUsePageRequest(limit = 1, offset = 1))
        val wrapper = parse(SourceUseDocumentRenderer.query(SourceUseQueryDocument(query(graph), identity(), inspection.coverage, inspection), graph))
        val section = obj(wrapper["sourceUses"])
        val page = obj(section["page"])
        val observation = obj(list(page["observations"]).single())
        val stageA = obj(parse(jsonValue(CompilerSourceUseSnapshotCodec.value(evidence)))["uses"].let { list(it) }
            .single { obj(it)["id"] == obj(observation["use"])["id"] })
        assertEquals(stageA, observation["use"])
        assertEquals(listOf("incoming"), observation["directions"])
        assertEquals(false, observation["matchingBytecodeCall"])
        assertEquals(setOf("usr", "module", "location"), obj(observation["caller"]).keys)
        assertNull(obj(observation["target"])["module"])
        assertNull(obj(observation["target"])["location"])
        assertEquals(mapOf("path" to "src/DeclaredB.kt", "line" to 9L, "column" to null), obj(observation["caller"])["location"])
        val binding = obj(list(page["bindings"]).single())
        val unit = evidence.units.single()
        assertEquals(unit.id, binding["id"])
        assertEquals(mapOf("role" to unit.receipt.role, "path" to unit.receipt.path, "sha256" to unit.receipt.sha256), binding["receipt"])
        assertEquals(unit.collectorArtifactFingerprint, binding["collectorArtifactFingerprint"])
        assertEquals(unit.inputToken, binding["inputToken"])
        assertEquals(1L, binding["claimCount"])
        assertEquals(2L, page["observedUses"])
        assertEquals(1L, page["returnedUses"])
        assertEquals(1L, page["omittedBefore"])
        assertEquals(0L, page["omittedAfter"])
        val totals = obj(obj(section["coverage"])["counts"])
        assertEquals(6L, obj(totals["producer"])["observed"])
        assertEquals(4L, obj(totals["producer"])["unmapped"])
        assertEquals(2L, obj(totals["import"])["admitted"])
        assertEquals(2L, totals["retainedUses"])
    }

    @Test
    fun `input descriptors retain raw identities and explicitly decline currentness assertions`() {
        val graph = fixture(emptyList())
        val inspection = SourceUseQuery.inspect(graph, C)
        val inputs = listOf(identity(), identity(hash = "f".repeat(64), scope = null, revision = null))
        for (input in inputs) {
            val wrapper = parse(SourceUseDocumentRenderer.query(SourceUseQueryDocument(query(graph), input, inspection.coverage, inspection)))
            val descriptor = obj(obj(wrapper["inputs"])["current"])
            assertEquals(setOf("toolVersion", "scope", "revision", "snapshotSha256", "currentness"), descriptor.keys)
            assertEquals(input.snapshotSha256, descriptor["snapshotSha256"])
            assertEquals(input.scope, descriptor["scope"])
            assertEquals(input.revision, descriptor["revision"])
            assertEquals("notChecked", descriptor["currentness"])
        }
        assertFailsWith<IllegalArgumentException> { identity(hash = "not-a-hash") }
    }

    @Test
    fun `base and current pages offsets coverage counts and bindings are independent`() {
        val base = snapshot(fixture(listOf("A", "B", "D"), partial = true), "b")
        val current = snapshot(fixture(listOf("X")), "c")
        val report = ChangeImpact.analyze(ImpactInput(current.graph), symbols = listOf(C.value), base = ImpactInput(base.graph))
        val snapshots = linkedMapOf("current" to current, "base" to base)
        val document = impactDocument(report, snapshots, SourceUsePageRequest(limit = 1, offset = 1))
        val text = SourceUseDocumentRenderer.impact(document)
        val wrapper = parse(text)
        assertEquals(parse(ImpactReportCodec.render(report, snapshots)), wrapper["graphImpact"])
        assertEquals(WARNINGS, wrapper["warnings"])
        val section = obj(wrapper["sourceEdit"])
        assertEquals("directObservedSelections", section["policy"])
        assertEquals("perRevision", section["pageScope"])
        val revisions = obj(section["revisions"])
        val baseSection = obj(revisions["base"])
        val currentSection = obj(revisions["current"])
        val basePage = obj(baseSection["page"])
        val currentPage = obj(currentSection["page"])
        assertEquals(listOf(3L, 3L, 1L, 1L, 1L, 1L, true, true), metrics(basePage))
        assertEquals(listOf(1L, 1L, 0L, 0L, 1L, 0L, true, false), metrics(currentPage))
        for (page in listOf(basePage, currentPage)) {
            assertEquals(1L, page["offset"])
            assertEquals(1L, page["limit"])
        }
        assertEquals(id("B").value, obj(obj(list(basePage["observations"]).single())["caller"])["usr"])
        assertEquals(1, list(basePage["bindings"]).size)
        assertEquals(emptyList<Any?>(), currentPage["observations"])
        assertEquals(emptyList<Any?>(), currentPage["bindings"])
        assertEquals("partial", obj(baseSection["coverage"])["coverage"])
        assertEquals("unknown", obj(currentSection["coverage"])["coverage"])
        assertEquals(7L, obj(obj(obj(baseSection["coverage"])["counts"])["producer"])["observed"])
        assertEquals(1L, obj(obj(obj(currentSection["coverage"])["counts"])["producer"])["observed"])
        assertEquals(identityFor(base).snapshotSha256, obj(obj(wrapper["inputs"])["base"])["snapshotSha256"])
        assertEquals(identityFor(current).snapshotSha256, obj(obj(wrapper["inputs"])["current"])["snapshotSha256"])
        assertFailsWith<IllegalArgumentException> { QuerySnapshotCodec.parse(text) }
        snapshots.clear()
        assertEquals(setOf("base", "current"), document.snapshots.keys)
        assertEquals(text, SourceUseDocumentRenderer.impact(document))
    }

    @Test
    fun `impact lazy nested view preserves summaries paths budgets nullable facts and statuses`() {
        val snapshot = snapshot(fixture(listOf("A", "B")), "c")
        val graph = snapshot.graph
        val retention = listOf(RetentionEvidence(id("A"), RetentionReason.KEEP_RULE, SourceLocation("keep.pro", 3)))
        val initial = ChangeImpact.analyze(ImpactInput(graph, retention), symbols = listOf(C.value, "missing"),
            depth = 1, limit = 1, pathLimit = 1)
        val summary = ImpactSummary(12,
            listOf(ImpactBucket(null, Int.MAX_VALUE), ImpactBucket("module", Int.MAX_VALUE)),
            listOf(ImpactBucket("a", 1), ImpactBucket("b", 2)),
            listOf(ImpactBucket("unknown", 3), ImpactBucket("test", 4)),
            listOf(ImpactBucket("direct", 5), ImpactBucket("unknown", 6)),
            listOf(ImpactBucket("partial", 7), ImpactBucket("complete", 8)))
        val rich = initial.copy(navigation = initial.navigation.copy(observed = summary, filtered = summary,
            filters = ImpactFilter(modules = setOf("z", "a"), affectedFiles = setOf("z.kt", "a.kt"),
                kinds = setOf(NodeKind.METHOD, NodeKind.CLASS), testStatus = ImpactTestStatus.UNKNOWN,
                relation = ImpactRelation.DIRECT, pathStatus = ImpactPathStatus.PARTIAL)))
        for (report in listOf(initial, rich,
            ChangeImpact.analyze(ImpactInput(graph)), ChangeImpact.analyze(ImpactInput(graph), symbols = listOf("missing")))) {
            for (summaryLimit in listOf(null, 1)) {
                val snapshots = mapOf("current" to snapshot)
                val before = ImpactReportCodec.render(report, snapshots, summaryLimit)
                val wrapper = parse(SourceUseDocumentRenderer.impact(impactDocument(report, snapshots, summaryLimit = summaryLimit)))
                assertEquals(parse(before), wrapper["graphImpact"])
                assertEquals(before, ImpactReportCodec.render(report, snapshots, summaryLimit))
                assertEquals(summaryLimit != null, obj(wrapper["graphImpact"]).containsKey("summaryNavigation"))
            }
        }
    }

    @Test
    fun `deleted targets unresolved selections and no changes do not conflate empty source pages`() {
        val base = snapshot(fixture(listOf("A")), "b")
        val currentGraph = CodeGraph(base.graph.nodes.values.filter { it.id != C }, emptyList()).preserveCompilerSourceUsesFrom(base.graph)
        val current = snapshot(currentGraph, "c")
        val snapshots = mapOf("base" to base, "current" to current)
        val partial = ChangeImpact.analyze(ImpactInput(currentGraph), listOf(C.value, "missing"), base = ImpactInput(base.graph))
        val wrapper = parse(SourceUseDocumentRenderer.impact(impactDocument(partial, snapshots)))
        val revisions = obj(obj(wrapper["sourceEdit"])["revisions"])
        assertEquals("partial", obj(revisions["base"])["selectionStatus"])
        assertEquals("noTargetsInRevision", obj(revisions["current"])["selectionStatus"])
        assertEquals(0L, obj(obj(revisions["current"])["page"])["observedUses"])
        assertEquals(1L, obj(obj(obj(obj(revisions["current"])["coverage"])["counts"])["import"])["admitted"])
        assertEquals(1L, obj(obj(obj(revisions["current"])["coverage"])["counts"])["viewOmitted"])
        for (report in listOf(
            ChangeImpact.analyze(ImpactInput(currentGraph), listOf("missing"), base = ImpactInput(base.graph)),
            ChangeImpact.analyze(ImpactInput(currentGraph), base = ImpactInput(base.graph)),
        )) {
            val values = obj(obj(parse(SourceUseDocumentRenderer.impact(impactDocument(report, snapshots)))["sourceEdit"])["revisions"])
            for (key in listOf("base", "current")) {
                val section = obj(values[key])
                if (report.unresolved.isEmpty()) {
                    assertEquals("noChanges", section["selectionStatus"])
                    assertEquals(0L, obj(section["page"])["observedUses"])
                } else {
                    assertEquals("unresolved", section["selectionStatus"])
                    assertNull(section["page"])
                }
            }
        }
    }

    @Test
    fun `source changed IDs retain legacy UTF16 order and copy caller lists`() {
        val supplementary = NodeId("method:p/\ud800\udc00#run()V")
        val privateUse = NodeId("method:p/\ue000#run()V")
        val graph = CodeGraph(listOf(supplementary, privateUse).map { GraphNode(it, "run", NodeKind.METHOD) }, emptyList())
        val snapshot = snapshot(graph, "c")
        val report = ChangeImpact.analyze(ImpactInput(graph), symbols = listOf(privateUse.value, supplementary.value))
        val changed = report.changed.map { it.node.id }.toMutableList()
        assertEquals(listOf(supplementary, privateUse), changed)
        val inspection = SourceEditImpact.analyze(graph, changed.toSet())
        val revision = SourceEditImpactRevision("resolved", changed, inspection.coverage, inspection)
        val document = SourceEditImpactDocument(report, mapOf("current" to snapshot),
            mapOf("current" to identityFor(snapshot)), mapOf("current" to revision))
        changed.clear()
        assertEquals(listOf(supplementary, privateUse), revision.changed)
        val section = obj(obj(obj(parse(SourceUseDocumentRenderer.impact(document))["sourceEdit"])["revisions"])["current"])
        assertEquals(listOf(supplementary.value, privateUse.value), section["changed"])
        assertNull(section["page"])
        assertFailsWith<UnsupportedOperationException> { (revision.changed as MutableList<NodeId>).clear() }
    }

    @Test
    fun `document adapters reject inconsistent coverage target status and raw identity metadata`() {
        val captured = fixture(emptyList())
        val old = CodeGraph(captured.nodes.values, emptyList())
        val inspection = SourceUseQuery.inspect(captured, C)
        assertFailsWith<IllegalArgumentException> {
            SourceUseQueryDocument(query(captured), identity(), SourceUseCoverage.from(old), inspection)
        }
        assertFailsWith<IllegalArgumentException> {
            SourceUseQueryDocument(SymbolQueryDocument("notFound", "missing", "symbol", emptyList()), identity(), inspection.coverage, inspection)
        }
        val sourceEdit = SourceEditImpact.analyze(captured, setOf(C))
        assertFailsWith<IllegalArgumentException> {
            SourceEditImpactRevision("resolved", listOf(C), SourceUseCoverage.from(old), sourceEdit)
        }
        assertFailsWith<IllegalArgumentException> {
            SourceEditImpactRevision("noTargetsInRevision", listOf(C), sourceEdit.coverage, sourceEdit)
        }
        val snapshot = snapshot(captured, "c")
        val report = ChangeImpact.analyze(ImpactInput(captured), symbols = listOf(C.value))
        val revision = SourceEditImpactRevision("resolved", listOf(C), sourceEdit.coverage, sourceEdit)
        assertFailsWith<IllegalArgumentException> {
            SourceEditImpactDocument(report, mapOf("current" to snapshot), mapOf("current" to identity()), mapOf("current" to revision))
        }
    }

    @Test
    fun `all public nested document collections reject mutation and detach caller owned values`() {
        val graph = fixture(listOf("A", "B"), partial = true)
        val query = query(graph)
        val result = requireNotNull(query.result)
        val queryPath = mutableListOf("root", "subject")
        val queryEdges = mutableListOf("call")
        val queryReferences = result.usedBy.single().references.toMutableList()
        val neighbor = result.usedBy.single().copy(edges = queryEdges, references = queryReferences)
        val queryNeighbors = mutableListOf(neighbor)
        val queryLimitations = mutableListOf("captured")
        val queryCandidates = mutableListOf(SymbolQueryCandidate("candidate", C.value))
        val queryValue = query.copy(limitations = queryLimitations, candidates = queryCandidates,
            result = result.copy(usedBy = queryNeighbors, dependsOn = queryNeighbors, members = queryNeighbors,
                declaredIn = neighbor, reachability = result.reachability.copy(path = queryPath)))
        val queryInspection = SourceUseQuery.inspect(graph, C)
        val queryDocument = SourceUseQueryDocument(queryValue, identity(), queryInspection.coverage, queryInspection)
        val queryState = publicState(queryDocument)
        queryPath.clear(); queryEdges.clear(); queryReferences.clear(); queryNeighbors.clear()
        queryLimitations.clear(); queryCandidates.clear()
        assertEquals(queryState, publicState(queryDocument))
        publicState(queryDocument, checkWrites = true)

        val bridgeCaller = ExternalBridgeCaller("dart", "lib/caller.dart", 12)
        val bridgeCallers = mutableListOf(bridgeCaller)
        val evidence = RetentionEvidence(C, RetentionReason.EXTERNAL_BRIDGE, SourceLocation("bridge.json", 1),
            ExternalBridgeEvidence("channel", "run", bridgeCaller, bridgeCallers))
        val snapshotRetention = mutableListOf(evidence)
        val snapshotLimitations = mutableListOf("snapshot limitation")
        val suppressed = mutableSetOf(C)
        val snapshot = QuerySnapshot(graph, snapshotRetention, snapshotLimitations, suppressed,
            toolVersion = "fixture-tool", scope = SCOPE, revision = "c".repeat(40))
        val initial = ChangeImpact.analyze(ImpactInput(graph, snapshotRetention), symbols = listOf(C.value))
        val modifiers = mutableSetOf(JvmModifier.FINAL)
        val attributes = mutableSetOf(NodeAttribute.INLINE_FUNCTION)
        val annotations = mutableSetOf("Annotation")
        val supertypes = mutableSetOf("Supertype")
        val revisions = mutableSetOf(ImpactRevision.CURRENT)
        val changedRetention = initial.changed.single().retention.toMutableList()
        val changed = mutableListOf(initial.changed.single().copy(
            node = initial.changed.single().node.copy(jvmModifiers = modifiers, attributes = attributes,
                annotations = annotations, supertypes = supertypes), presentIn = revisions, observedIn = revisions,
            retention = changedRetention))
        val path = initial.affected.single().paths.single()
        val pathNodes = path.nodes.toMutableList()
        val callLines = path.edges.single().callSiteLines.toMutableList()
        val pathEdges = mutableListOf(path.edges.single().copy(callSiteLines = callLines))
        val paths = mutableListOf(path.copy(nodes = pathNodes, edges = pathEdges))
        val omissionKinds = mutableListOf(EdgeKind.CALL)
        val omissions = mutableListOf(ImpactPathOmission(ImpactRevision.CURRENT, ImpactPathOmissionReason.PATH_BUDGET, 1, omissionKinds))
        val facts = initial.affected.single().facts.toMutableList()
        val affected = mutableListOf(initial.affected.single().copy(paths = paths, pathOmissions = omissions, facts = facts))
        val unresolvedCandidates = mutableListOf(C)
        val unresolved = mutableListOf(ImpactUnresolved("uncertain", "ambiguous", unresolvedCandidates))
        val limitations = mutableListOf("report limitation")
        val buckets = mutableListOf(ImpactBucket("bucket", 1))
        val summary = ImpactSummary(1, buckets, buckets, buckets, buckets, buckets)
        val modules = mutableSetOf(":module")
        val files = mutableSetOf("src/A.kt")
        val kinds = mutableSetOf(NodeKind.METHOD)
        val visited = mutableMapOf(ImpactRevision.CURRENT to 1)
        val reached = mutableSetOf(ImpactRevision.CURRENT)
        val report = initial.copy(changed = changed, affected = affected, unresolved = unresolved, limitations = limitations,
            navigation = initial.navigation.copy(observed = summary, filtered = summary,
                filters = ImpactFilter(modules, files, kinds)),
            budgets = initial.budgets.copy(visited = visited, visitLimitReached = reached))
        val document = impactDocument(report, mutableMapOf("current" to snapshot))
        val impactState = publicState(document)
        val output = SourceUseDocumentRenderer.impact(document)
        bridgeCallers.clear(); snapshotRetention.clear(); snapshotLimitations.clear(); suppressed.clear()
        modifiers.clear(); attributes.clear(); annotations.clear(); supertypes.clear(); revisions.clear()
        changedRetention.clear(); changed.clear(); pathNodes.clear(); callLines.clear(); pathEdges.clear(); paths.clear()
        omissionKinds.clear(); omissions.clear(); facts.clear(); affected.clear(); unresolvedCandidates.clear()
        unresolved.clear(); limitations.clear(); buckets.clear(); modules.clear(); files.clear(); kinds.clear()
        visited.clear(); reached.clear()
        assertEquals(impactState, publicState(document))
        assertEquals(listOf(bridgeCaller), document.graphImpact.changed.single().retention.single().evidence.externalBridge!!.callers)
        assertEquals(output, SourceUseDocumentRenderer.impact(document))
        publicState(document, checkWrites = true)
    }

    @Test
    fun `public snapshot inputs expose immutable scalar metadata without retaining legacy snapshot collections`() {
        val graph = fixture(emptyList())
        val retention = mutableListOf(RetentionEvidence(C, RetentionReason.KEEP_RULE, null))
        val limitations = mutableListOf("legacy")
        val suppressed = mutableSetOf(C)
        val snapshot = QuerySnapshot(graph, retention, limitations, suppressed,
            toolVersion = "fixture-tool", scope = SCOPE, revision = "c".repeat(40))
        val snapshots = mutableMapOf("current" to snapshot)
        val document = impactDocument(ChangeImpact.analyze(ImpactInput(graph)), snapshots)
        val metadata = document.snapshots.getValue("current")
        assertEquals(SourceSnapshotMetadata(snapshot.toolVersion, snapshot.scope, snapshot.revision), metadata)
        assertEquals(setOf("getToolVersion", "getScope", "getRevision"), metadata.javaClass.methods
            .filter { it.parameterCount == 0 && it.name.startsWith("get") && it.name != "getClass" }.map { it.name }.toSet())
        val state = publicState(document)
        retention.clear(); limitations.clear(); suppressed.clear(); snapshots.clear()
        assertEquals(state, publicState(document))
        publicState(document, checkWrites = true)
    }

    /** JSON에 없는 공개 getter까지 읽고 모든 노출 collection의 쓰기 거부를 검사한다. */
    @Suppress("UNCHECKED_CAST")
    private fun publicState(value: Any?, path: String = "document", checkWrites: Boolean = false): Any? = when (value) {
        null -> null
        is Enum<*> -> value.name
        is Map<*, *> -> {
            if (checkWrites) (value as? MutableMap<Any?, Any?>)?.let {
                assertFailsWith<UnsupportedOperationException>(path) { it["mutation-probe"] = null }
            }
            value.entries.associate { publicState(it.key, "$path.key", checkWrites) to publicState(it.value, "$path[${it.key}]", checkWrites) }
        }
        is Collection<*> -> {
            if (checkWrites) (value as? MutableCollection<Any?>)?.let {
                assertFailsWith<UnsupportedOperationException>(path) { it.add(null) }
            }
            value.mapIndexed { index, item -> publicState(item, "$path[$index]", checkWrites) }
        }
        else -> if (!value.javaClass.name.startsWith("dev.kartograph.")) value else value.javaClass.methods
            .filter { it.parameterCount == 0 && it.name.startsWith("get") && it.name != "getClass" &&
                !java.lang.reflect.Modifier.isStatic(it.modifiers) }
            .sortedBy { it.name }.associate { it.name to publicState(it.invoke(value), "$path.${it.name}", checkWrites) }
    }

    private fun metrics(page: Map<*, *>): List<Any?> = listOf("observedUses", "observedDistinctCallers", "returnedUses",
        "returnedDistinctCallers", "omittedBefore", "omittedAfter", "hasPrevious", "hasNext").map { page[it] }

    private fun impactDocument(
        report: ImpactReport,
        snapshots: Map<String, QuerySnapshot>,
        request: SourceUsePageRequest = SourceUsePageRequest(),
        summaryLimit: Int? = null,
    ): SourceEditImpactDocument {
        val revisions = snapshots.mapValues { (_, snapshot) ->
            val changed = report.changed.map { it.node.id }.filter { it in snapshot.graph.nodes }
            val status = when {
                report.changed.isEmpty() && report.unresolved.isNotEmpty() -> "unresolved"
                report.changed.isEmpty() -> "noChanges"
                changed.isEmpty() -> "noTargetsInRevision"
                report.unresolved.isNotEmpty() -> "partial"
                else -> "resolved"
            }
            SourceEditImpactRevision(status, changed, SourceUseCoverage.from(snapshot.graph),
                if (status == "unresolved") null else SourceEditImpact.analyze(snapshot.graph, changed.toSet(), request))
        }
        return SourceEditImpactDocument(report, snapshots, snapshots.mapValues { identityFor(it.value) }, revisions, summaryLimit)
    }

    private fun fixture(callers: List<String>, partial: Boolean = false): CodeGraph {
        val ids = (callers.map(::id) + C).distinct()
        val nodes = ids.map { value -> GraphNode(value, value.value.substringAfter("#").substringBefore("("), NodeKind.METHOD,
            moduleName = if (value == C) null else ":module", location = if (value == C) null else SourceLocation("src/Declared${value.value.substringAfter("p/").substringBefore("#")}.kt", 9),
            sourceDeclaration = if (value == C) SourceDeclarationEvidence("src/C.kt", "a".repeat(64), 20, 2, 3) else null) }
        val classes = InputFingerprint("classes", "build/classes", "b".repeat(64))
        val root = CompilerSourceUseRoot(0, classes, ids)
        val roots = if (!partial) listOf(root) else listOf(root,
            CompilerSourceUseRoot(1, InputFingerprint("classes", "build/uncovered", "c".repeat(64)), listOf(id("Uncovered"))))
        val unit = CompilerSourceUseUnit(InputFingerprint("compilerEvidence", "build/source.tsv", "9".repeat(64)),
            "kotlin-constants", "2.4.10", "d".repeat(64), "e".repeat(64),
            listOf(CompilerSourceUseClaim(SCOPE, "kotlin", ":compileKotlin", listOf(classes))),
            CompilerSourceUseProducerCounts(callers.size + if (partial) 4 else 0, callers.size, if (partial) 4 else 0, 0),
            CompilerSourceUseImportCounts(callers.size, 0, 0, 0, 0, 0), 0)
        val source = CompilerEvidenceSource("src/Selectors.kt", "a".repeat(64))
        val uses = callers.mapIndexed { index, name -> CompilerSourceUse(unit.id, id(name), C, root.id, root.id,
            CompilerSourceUseKind.INVOCATION_SELECTION, source, "kotlin-constants", "2.4.10",
            CompilerSourceCoordinateBasis.KOTLIN_UTF16_NORMALIZED_SOURCE, 100 + index * 20, 105 + index * 20, 5 + index, 4, false) }
        val edges = if ("A" in callers) listOf(GraphEdge(id("A"), C, EdgeKind.CALL, callSiteLines = listOf(12))) else emptyList()
        val positions = if ("A" in callers) listOf(LocatedCompilerReference(id("A"), C, source,
            "kotlin-constants", "2.4.10", CompilerSourceCoordinateBasis.KOTLIN_UTF16_NORMALIZED_SOURCE, 100, 105, 5, 4)) else emptyList()
        return CodeGraph(nodes, edges).withCompilerCallPositions(positions).withCompilerSourceUses(CompilerSourceUseEvidence(roots, listOf(unit), uses))
    }

    private fun query(graph: CodeGraph): SymbolQueryDocument = SymbolQuery.query(graph,
        ReachabilityAnalyzer.analyze(graph, emptyList()), C.value, listOf("z limitation", "a limitation"))
    private fun snapshot(graph: CodeGraph, revision: String): QuerySnapshot = QuerySnapshot(graph, emptyList(), listOf("historical"),
        toolVersion = "fixture-tool", scope = SCOPE, revision = revision.repeat(40))
    private fun identityFor(snapshot: QuerySnapshot): SourceSnapshotInput = SourceSnapshotInput(snapshot.toolVersion, snapshot.scope,
        snapshot.revision, if (snapshot.revision!!.startsWith("b")) "b".repeat(64) else "c".repeat(64))
    private fun identity(hash: String = "0".repeat(64), scope: String? = SCOPE, revision: String? = "1".repeat(40)): SourceSnapshotInput =
        SourceSnapshotInput("fixture-tool", scope, revision, hash)
    private fun parse(text: String): Map<*, *> = obj(SnapshotJsonParser(text).parse())
    private fun obj(value: Any?): Map<*, *> = value as Map<*, *>
    private fun list(value: Any?): List<*> = value as List<*>
    private fun id(name: String): NodeId = NodeId("method:p/$name#run()V")

    private companion object {
        const val SCOPE = ":fixture:jvm"
        val C = NodeId("method:p/C#run()V")
        val WARNINGS = listOf(
            "Compiler selections are historical source-dependency observations, not runtime reachability or proof of behavior changes.",
            "Direct source selections do not cover transitive edit impact, unobserved syntax, unbuilt variants or external consumers.",
            "Saved snapshot live freshness is unknown; source positions refer to captured bytes.",
            "Empty or paged results do not approve deletion or skipping tests.",
        )
    }
}
