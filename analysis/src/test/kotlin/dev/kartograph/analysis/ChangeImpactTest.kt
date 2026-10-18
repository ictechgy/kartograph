package dev.kartograph.analysis

import dev.kartograph.core.*
import java.lang.management.ManagementFactory
import kotlin.random.Random
import kotlin.test.*
import org.junit.jupiter.api.Assumptions.assumeTrue

class ChangeImpactTest {
    private fun node(name: String, file: String = "$name.kt") = GraphNode(NodeId("class:p/$name"), name, NodeKind.CLASS,
        location = SourceLocation(file))
    private fun edge(from: String, to: String, origin: EdgeOrigin = EdgeOrigin.BYTECODE) =
        GraphEdge(NodeId("class:p/$from"), NodeId("class:p/$to"), EdgeKind.CALL, origin = origin)
    private fun input(vararg edges: GraphEdge): ImpactInput = ImpactInput(CodeGraph(
        listOf(node("A"), node("B"), node("C"), node("Unused")), edges.toList()))

    @Test fun `reverse closure carries actual dependency paths and runtime origins`() {
        val result = ChangeImpact.analyze(input(edge("A", "B"), edge("B", "C", EdgeOrigin.RUNTIME_MODEL)), listOf("C"))
        assertEquals(listOf("A", "B"), result.affected.map { it.node.name })
        val path = result.affected.first().paths.single()
        assertEquals(listOf("class:p/A", "class:p/B"), path.edges.map { it.source.value })
        assertEquals(listOf(EdgeOrigin.BYTECODE, EdgeOrigin.RUNTIME_MODEL), path.edges.map { it.origin })
        assertEquals(NodeId("class:p/C"), path.changed)
        assertTrue(result.limitations.any { it.startsWith("potential-impact:") })
    }

    @Test fun `base and current never form a path that existed in neither revision`() {
        val result = ChangeImpact.analyze(input(edge("A", "B")), listOf("C"), base = input(edge("B", "C")))
        assertEquals(listOf("B"), result.affected.map { it.node.name })
        assertEquals(ImpactRevision.BASE, result.affected.single().paths.single().revision)
    }

    @Test fun `deleted declarations and unchanged callers remain visible`() {
        val current = ImpactInput(CodeGraph(listOf(node("A")), emptyList()))
        val base = input(edge("A", "C"))
        val result = ChangeImpact.analyze(current, files = listOf("src/p/C.kt"), base = base)
        assertEquals(listOf("C"), result.changed.map { it.node.name })
        assertEquals(setOf(ImpactRevision.BASE), result.changed.single().presentIn)
        assertEquals(listOf("A"), result.affected.map { it.node.name })
        assertTrue(result.limitations.any { it.startsWith("source-file-candidates:") })
    }

    @Test fun `ambiguity unknown files and truncation cannot look like an empty successful check`() {
        val nodes = listOf(node("A"), node("B"), node("C"), node("C").copy(id = NodeId("class:other/C")))
        val input = ImpactInput(CodeGraph(nodes, listOf(edge("A", "B"), edge("B", "C"))))
        val unresolved = ChangeImpact.analyze(input, listOf("C", "Missing"), listOf("settings.gradle.kts"))
        assertEquals(3, unresolved.unresolved.size)
        assertEquals(2, unresolved.unresolved.first { it.reason == "ambiguous" }.candidates.size)
        val limited = ChangeImpact.analyze(input, listOf("class:p/C"), depth = 1)
        assertEquals(listOf("B"), limited.affected.map { it.node.name })
        assertTrue(limited.truncated.depth)
        val capped = ChangeImpact.analyze(input, listOf("class:p/C"), limit = 1)
        assertTrue(capped.truncated.results)
        assertEquals(2, capped.totalAffected)
    }

    @Test fun `cycles duplicate selectors and node order have deterministic results`() {
        val original = input(edge("A", "B"), edge("B", "A"), edge("B", "C"))
        val a = ChangeImpact.analyze(original, listOf("C", "C"))
        val b = ChangeImpact.analyze(original.copy(graph = CodeGraph(original.graph.nodes.values.reversed(), original.graph.edges.reversed())), listOf("C"))
        assertEquals(a, b)
        assertEquals(2, a.totalAffected)
        assertFalse(a.truncated.depth)
        assertTrue(ChangeImpact.analyze(original, listOf("C"), visitLimit = 1).truncated.budget)
    }

    @Test fun `visit budget reports the revision without inventing an affected candidate`() {
        val result = ChangeImpact.analyze(input(edge("A", "C")), listOf("C"), visitLimit = 1)

        assertTrue(result.affected.isEmpty())
        assertTrue(result.truncated.budget)
        assertEquals(1, result.budgets.visited[ImpactRevision.CURRENT])
        assertEquals(setOf(ImpactRevision.CURRENT), result.budgets.visitLimitReached)
        assertTrue(result.limitations.any { it.startsWith("visit-budget:") })
    }

    @Test fun `configuration selection uses retention evidence but not invented runtime callers`() {
        val target = NodeId("class:p/C")
        val evidence = RetentionEvidence(target, RetentionReason.MANIFEST_COMPONENT, SourceLocation("AndroidManifest.xml", 3))
        val data = input(edge("A", "C")).copy(retention = listOf(evidence))
        val result = ChangeImpact.analyze(data, files = listOf("AndroidManifest.xml"))
        assertEquals(target, result.changed.single().node.id)
        assertEquals(listOf(ImpactRetention(ImpactRevision.CURRENT, evidence)), result.changed.single().retention)
        assertEquals(listOf("A"), result.affected.map { it.node.name })
    }

    @Test fun `changing an interface contract includes implementing declarations`() {
        val data = input(GraphEdge(NodeId("class:p/A"), NodeId("class:p/B"), EdgeKind.OVERRIDE))
        val result = ChangeImpact.analyze(data, listOf("A"))
        assertEquals(listOf("B"), result.affected.map { it.node.name })
        assertEquals(listOf(NodeId("class:p/B"), NodeId("class:p/A")), result.affected.single().paths.single().nodes)
        val siblings = input(GraphEdge(NodeId("class:p/A"), NodeId("class:p/B"), EdgeKind.OVERRIDE),
            GraphEdge(NodeId("class:p/A"), NodeId("class:p/C"), EdgeKind.OVERRIDE))
        assertFalse(ChangeImpact.analyze(siblings, listOf("B")).affected.any { it.node.name == "C" })
    }

    @Test fun `path budget preserves observed count and never returns a broken witness`() {
        val result = ChangeImpact.analyze(input(edge("A", "B"), edge("B", "C")), listOf("C"), pathLimit = 1)
        assertTrue(result.truncated.budget)
        assertEquals(2, result.totalAffected)
        assertEquals(listOf("A", "B"), result.affected.map { it.node.name })
        assertEquals(ImpactPathStatus.UNAVAILABLE, result.affected.first().pathStatus)
        assertEquals(1, result.affected.first().pathOmissions.size)
        assertEquals(1, result.affected.last().paths.single().edges.size)
    }

    @Test fun `overlapping revisions retain independent paths and observed revisions`() {
        val base = input(edge("A", "C"), edge("B", "C"))
        val current = input(edge("A", "C"))
        val result = ChangeImpact.analyze(current, listOf("C"), base = base)

        assertEquals(listOf("A", "B"), result.affected.map { it.node.name })
        assertEquals(setOf(ImpactRevision.BASE, ImpactRevision.CURRENT), result.affected.first().observedIn)
        assertEquals(listOf(ImpactRevision.BASE, ImpactRevision.CURRENT), result.affected.first().paths.map { it.revision })
        assertEquals(setOf(ImpactRevision.BASE), result.affected.last().observedIn)
        assertEquals(listOf(ImpactRevision.BASE), result.affected.last().paths.map { it.revision })
    }

    @Test fun `navigation filters and pages the complete observed set`() {
        val target = node("Target", "src/main/kotlin/p/Target.kt").copy(moduleName = "app")
        val direct = node("Direct", "src/main/kotlin/p/Direct.kt").copy(moduleName = "app")
        val test = node("TestCaller", "src/test/kotlin/p/TestCaller.kt").copy(moduleName = "tests")
        val structural = node("Impl", "src/main/kotlin/p/Impl.kt").copy(moduleName = "app", kind = NodeKind.OBJECT)
        val graph = CodeGraph(
            listOf(target, direct, test, structural),
            listOf(
                GraphEdge(direct.id, target.id, EdgeKind.CALL),
                GraphEdge(test.id, target.id, EdgeKind.REFERENCE),
                GraphEdge(target.id, structural.id, EdgeKind.OVERRIDE),
            ),
        )
        val result = ChangeImpact.analyze(
            ImpactInput(graph),
            symbols = listOf(target.id.value),
            offset = 1,
            limit = 1,
            filters = ImpactFilter(modules = setOf("app")),
            sort = ImpactSort.FILE,
        )

        assertEquals(3, result.navigation.observed.candidates)
        assertEquals(2, result.navigation.filtered.candidates)
        assertEquals(1, result.navigation.returned)
        assertTrue(result.truncated.results)
        assertEquals(listOf("Impl"), result.affected.map { it.node.name })
        assertEquals(1, result.navigation.observed.byTestStatus.single { it.value == "test" }.count)
        assertEquals(ImpactRelation.DIRECT, ChangeImpact.analyze(ImpactInput(graph), listOf(target.id.value), filters = ImpactFilter(testStatus = ImpactTestStatus.TEST)).affected.single().relation)

        val firstPage = ChangeImpact.analyze(ImpactInput(graph), listOf(target.id.value), offset = 0, limit = 1)
        val secondPage = ChangeImpact.analyze(ImpactInput(graph), listOf(target.id.value), offset = 1, limit = 1)
        assertEquals(3, firstPage.navigation.filtered.candidates)
        assertEquals(3, secondPage.navigation.observed.candidates)
        assertEquals(1, firstPage.affected.size)
        assertEquals(1, secondPage.affected.size)
        assertNotEquals(firstPage.affected.single().node.id, secondPage.affected.single().node.id)
        assertTrue(firstPage.navigation.hasNext)
        assertTrue(secondPage.navigation.hasPrevious)
    }

    @Test fun `navigation exposes every sort and focused filter without shrinking observation`() {
        val target = node("Target", "src/main/kotlin/p/Target.kt")
        val direct = node("Direct", "src/main/kotlin/p/Direct.kt")
        val test = node("TestCaller", "src/test/kotlin/p/TestCaller.kt")
        val structural = node("Impl", "src/main/kotlin/p/Impl.kt").copy(kind = NodeKind.OBJECT)
        val graph = CodeGraph(
            listOf(target, direct, test, structural),
            listOf(
                GraphEdge(direct.id, target.id, EdgeKind.CALL),
                GraphEdge(test.id, target.id, EdgeKind.REFERENCE),
                GraphEdge(target.id, structural.id, EdgeKind.OVERRIDE),
            ),
        )

        ImpactSort.entries.forEach { ordering ->
            val result = ChangeImpact.analyze(ImpactInput(graph), listOf(target.id.value), sort = ordering)
            assertEquals(3, result.navigation.observed.candidates, ordering.name)
            assertEquals(3, result.navigation.filtered.candidates, ordering.name)
            assertEquals(3, result.affected.size, ordering.name)
        }
        assertEquals(listOf("TestCaller"), ChangeImpact.analyze(
            ImpactInput(graph), listOf(target.id.value), filters = ImpactFilter(affectedFiles = setOf("src/test/kotlin/p/TestCaller.kt")),
        ).affected.map { it.node.name })
        assertEquals(listOf("Impl"), ChangeImpact.analyze(
            ImpactInput(graph), listOf(target.id.value), filters = ImpactFilter(kinds = setOf(NodeKind.OBJECT), relation = ImpactRelation.STRUCTURAL),
        ).affected.map { it.node.name })
        assertEquals(listOf("Direct", "Impl", "TestCaller"), ChangeImpact.analyze(
            ImpactInput(graph), listOf(target.id.value), filters = ImpactFilter(pathStatus = ImpactPathStatus.COMPLETE),
        ).affected.map { it.node.name })
    }

    @Test fun `review sort keeps observations witnesses and omitted paths while ordering stable pages`() {
        val target = node("Target")
        val direct = node("MDirect")
        val near = node("ZNear")
        val deep = node("ADeep")
        val structural = node("AStructural")
        val graph = CodeGraph(listOf(target, direct, near, deep, structural), listOf(
            GraphEdge(direct.id, target.id, EdgeKind.CALL),
            GraphEdge(near.id, direct.id, EdgeKind.CALL),
            GraphEdge(deep.id, near.id, EdgeKind.CALL),
            GraphEdge(structural.id, target.id, EdgeKind.INHERITANCE),
        ))
        val input = ImpactInput(graph, limitations = listOf("fixture boundary"))

        for (pathLimit in listOf(100, 1)) {
            val legacy = ChangeImpact.analyze(input, listOf(target.id.value), pathLimit = pathLimit)
            val review = ChangeImpact.analyze(input, listOf(target.id.value), pathLimit = pathLimit, sort = ImpactSort.REVIEW)
            assertEquals(ImpactSort.USR, legacy.navigation.sort)
            assertEquals(listOf("MDirect", "ZNear", "ADeep", "AStructural"), review.affected.map { it.node.name })
            assertEquals(legacy.affected.associateBy { it.node.id }, review.affected.associateBy { it.node.id })
            assertEquals(legacy.navigation.observed, review.navigation.observed)
            assertEquals(legacy.navigation.filtered, review.navigation.filtered)
            assertEquals(legacy.budgets, review.budgets)
            assertEquals(legacy.truncated, review.truncated)
            assertEquals(legacy.limitations, review.limitations)
            assertEquals(legacy.unresolved, review.unresolved)

            val pages = (0..1).flatMap { page ->
                ChangeImpact.analyze(input, listOf(target.id.value), offset = page * 2, limit = 2,
                    pathLimit = pathLimit, sort = ImpactSort.REVIEW).affected
            }
            assertEquals(review.affected, pages)
            val reversed = input.copy(graph = CodeGraph(graph.nodes.values.reversed(), graph.edges.reversed()))
            assertEquals(review, ChangeImpact.analyze(reversed, listOf(target.id.value), pathLimit = pathLimit, sort = ImpactSort.REVIEW))
        }
    }

    @Test fun `shared candidate reports partial path evidence instead of disappearing`() {
        val base = input(edge("A", "C"))
        val current = input(edge("A", "B"), edge("B", "C"))
        val result = ChangeImpact.analyze(current, listOf("C"), base = base, pathLimit = 1)

        val shared = result.affected.single { it.node.name == "A" }
        assertEquals(ImpactPathStatus.PARTIAL, shared.pathStatus)
        assertEquals(listOf(ImpactRevision.BASE), shared.paths.map { it.revision })
        assertEquals(listOf(ImpactRevision.CURRENT), shared.pathOmissions.map { it.revision })
        assertEquals(ImpactPathStatus.UNAVAILABLE, result.affected.single { it.node.name == "B" }.pathStatus)
        assertEquals(2, result.navigation.observed.candidates)
    }

    @Test fun `unknown source metadata remains unknown in facts and summaries`() {
        val target = GraphNode(NodeId("class:p/Target"), "Target", NodeKind.CLASS)
        val caller = GraphNode(NodeId("class:p/Caller"), "Caller", NodeKind.CLASS)
        val result = ChangeImpact.analyze(
            ImpactInput(CodeGraph(listOf(target, caller), listOf(GraphEdge(caller.id, target.id, EdgeKind.CALL)))),
            listOf(target.id.value),
        )

        val affected = result.affected.single()
        assertEquals(ImpactTestStatus.UNKNOWN, affected.testStatus)
        assertEquals(ImpactTestStatus.UNKNOWN, affected.facts.single().testStatus)
        assertEquals(null, affected.facts.single().module)
        assertEquals(null, result.navigation.observed.byModule.single().value)
        assertEquals("unknown", result.navigation.observed.byTestStatus.single().value)
    }

    @Test fun `source aliases in the base still select a stable JVM identity`() {
        val original = input()
        val current = ImpactInput(CodeGraph(original.graph.nodes.values.map { if (it.name == "C") it.copy(name = "Renamed") else it }, original.graph.edges))
        val result = ChangeImpact.analyze(current, listOf("C"), base = original)
        assertTrue(result.unresolved.isEmpty())
        assertEquals(NodeId("class:p/C"), result.changed.single().node.id)
        assertEquals(setOf(ImpactRevision.BASE, ImpactRevision.CURRENT), result.changed.single().presentIn)
    }

    @Test fun `dispatch model candidates are not treated as overrides of a changed method`() {
        // save가 인터페이스 iterator()를 호출하면 dispatch 모델은 save→구현 후보로 OVERRIDE(DISPATCH_MODEL) 간선을 만든다.
        // 그 간선은 "save의 하위 override"가 아니므로 계약 확장에 쓰이면 안 되고, 실제 호출 사슬 createOrUpdate→write→save가 경로여야 한다.
        val save = NodeId("method:p/Format#save()V")
        val write = NodeId("method:p/Format#write()V")
        val createOrUpdate = NodeId("method:p/Mapping#createOrUpdate()V")
        val iterator = NodeId("method:p/Values#iterator()Ljava/util/Iterator;")
        fun method(id: NodeId) = GraphNode(id, id.value.substringAfter('#').substringBefore('('), NodeKind.METHOD, location = SourceLocation("p.kt"))
        val graph = CodeGraph(listOf(save, write, createOrUpdate, iterator).map(::method), listOf(
            GraphEdge(write, save, EdgeKind.CALL),
            GraphEdge(createOrUpdate, write, EdgeKind.CALL),
            GraphEdge(save, iterator, EdgeKind.OVERRIDE, origin = EdgeOrigin.DISPATCH_MODEL),
            GraphEdge(createOrUpdate, iterator, EdgeKind.OVERRIDE, origin = EdgeOrigin.DISPATCH_MODEL),
        ))
        val result = ChangeImpact.analyze(ImpactInput(graph), listOf(save.value))
        assertEquals(listOf(write, createOrUpdate).sorted(), result.affected.map { it.node.id }.sorted())
        val caller = result.affected.single { it.node.id == createOrUpdate }
        assertEquals(ImpactRelation.TRANSITIVE, caller.relation)
        assertEquals(listOf(createOrUpdate, write, save), caller.paths.single().nodes)
    }

    @Test fun `members of the changed class owner are not affected through their own owner reference`() {
        // 모든 멤버는 소유 class를 REFERENCE로 참조하고 class는 <clinit>을 REFERENCE로 참조한다.
        // <clinit> 변경이 class를 거쳐 자기 멤버 전부로 퍼지면 안 되고, class를 참조하는 외부 사용자는 남아야 한다.
        val owner = NodeId("class:p/Locator")
        val clinit = NodeId("method:p/Locator#<clinit>()V")
        val field = NodeId("field:p/Locator#hints:Ljava/util/List;")
        val member = NodeId("method:p/Locator#find()V")
        val external = NodeId("method:p/Context#run()V")
        val nested = NodeId("class:p/Locator\$Companion")
        val reader = NodeId("method:p/Locator#readHints()V")
        val nodes = listOf(
            GraphNode(owner, "Locator", NodeKind.CLASS, location = SourceLocation("Locator.kt")),
            GraphNode(clinit, "<clinit>", NodeKind.METHOD, location = SourceLocation("Locator.kt")),
            GraphNode(field, "hints", NodeKind.FIELD, location = SourceLocation("Locator.kt")),
            GraphNode(member, "find", NodeKind.METHOD, location = SourceLocation("Locator.kt")),
            GraphNode(external, "run", NodeKind.METHOD, location = SourceLocation("Context.kt")),
            GraphNode(nested, "Companion", NodeKind.CLASS, location = SourceLocation("Locator.kt")),
            GraphNode(reader, "readHints", NodeKind.METHOD, location = SourceLocation("Locator.kt")),
        )
        val graph = CodeGraph(nodes, listOf(
            GraphEdge(owner, clinit, EdgeKind.MEMBER), GraphEdge(owner, field, EdgeKind.MEMBER), GraphEdge(owner, member, EdgeKind.MEMBER),
            GraphEdge(owner, reader, EdgeKind.MEMBER),
            GraphEdge(owner, clinit, EdgeKind.REFERENCE),
            GraphEdge(clinit, owner, EdgeKind.REFERENCE), GraphEdge(field, owner, EdgeKind.REFERENCE), GraphEdge(member, owner, EdgeKind.REFERENCE),
            GraphEdge(reader, owner, EdgeKind.REFERENCE),
            GraphEdge(external, owner, EdgeKind.REFERENCE),
            // 중첩 class가 바깥 class를 참조하는 간선은 소유 참조가 아니므로 유지된다.
            GraphEdge(nested, owner, EdgeKind.REFERENCE),
            // 같은 class의 멤버라도 <clinit>이 초기화하는 static field를 실제로 읽으면 field_access 사슬로 남는다.
            GraphEdge(clinit, field, EdgeKind.FIELD_ACCESS), GraphEdge(reader, field, EdgeKind.FIELD_ACCESS),
        ))
        val result = ChangeImpact.analyze(ImpactInput(graph), listOf(clinit.value))
        assertEquals(listOf(owner, external, nested).sorted(), result.affected.map { it.node.id }.sorted())
        assertEquals(ImpactRelation.TRANSITIVE, result.affected.single { it.node.id == external }.relation)
        assertFalse(result.affected.any { it.node.id == member || it.node.id == reader })

        // 변경 대상이 static field라면 그 field를 실제로 읽는 같은 class의 멤버는 field_access 사슬로 직접 후보가 되고,
        // 읽지 않는 멤버는 소유 참조만으로는 후보가 되지 않는다. clinit→class→외부 사용자 전파는 기존대로 유지된다.
        val fieldChange = ChangeImpact.analyze(ImpactInput(graph), listOf(field.value))
        assertEquals(ImpactRelation.DIRECT, fieldChange.affected.single { it.node.id == reader }.relation)
        assertFalse(fieldChange.affected.any { it.node.id == member })
        assertTrue(fieldChange.affected.any { it.node.id == external })
    }

    @Test fun `changed file and symbol selection keeps exact fallback retention and unresolved results`() {
        // 정확한 경로, 파일 이름만 남은 옛 위치(대체 매칭), 보존 근거, 그래프 밖 선언의 보존 근거, 대응 없는 파일을 섞는다.
        val base = ImpactInput(
            CodeGraph(listOf(node("A", "src/main/kotlin/p/A.kt"), node("B", "B.kt"), node("Gone", "src/main/kotlin/p/Gone.kt"),
                node("C").copy(id = NodeId("class:other/C"))), emptyList()),
            retention = listOf(
                RetentionEvidence(NodeId("class:p/A"), RetentionReason.MANIFEST_COMPONENT, SourceLocation("AndroidManifest.xml", 3)),
                RetentionEvidence(NodeId("class:p/Absent"), RetentionReason.MANIFEST_COMPONENT, SourceLocation("res/layout/main.xml", 1)),
            ),
        )
        val current = ImpactInput(CodeGraph(listOf(node("A", "src/main/kotlin/p/A.kt"), node("B", "B.kt"), node("B2", "B.kt"), node("C")), emptyList()))

        val result = ChangeImpact.analyze(
            current,
            symbols = listOf("p.Gone", "C", "class:p/A", "Nope", "B"),
            files = listOf("src/main/kotlin/q/B.kt", "B.kt", "AndroidManifest.xml", "res/layout/main.xml", "src/main/kotlin/p/A.kt", "src/main/kotlin/q/B.kt"),
            base = base,
        )

        assertEquals(listOf("class:p/A", "class:p/B", "class:p/B2", "class:p/Gone"), result.changed.map { it.node.id.value })
        assertEquals(listOf(
            ImpactUnresolved("C", "ambiguous", listOf(NodeId("class:other/C"), NodeId("class:p/C"))),
            ImpactUnresolved("Nope", "notFound"),
            ImpactUnresolved("res/layout/main.xml", "unmappedFile"),
        ), result.unresolved)
        // q/B.kt는 base의 B와 current의 B·B2를 파일 이름으로만 찾는다(3건). 디렉터리가 없는 B.kt 요청은 정확한 일치라 세지 않는다.
        assertEquals(3, fallbackCount(result))
    }

    @Test fun `indexed selection agrees with the previous linear scan on random revisions`() {
        // 색인 도입 전 선형 필터를 기준 구현으로 두고 선택 집합·미해결 순서·대체 매칭 수가 같은지 무작위 두 시점에서 대조한다.
        val exercised = mutableSetOf<String>()
        repeat(400) { seed ->
            val random = Random(70_000 + seed)
            val base = randomImpactInput(random)
            val current = randomImpactInput(random)
            val symbols = List(random.nextInt(0, 6)) { SYMBOL_POOL.random(random) }
            val files = List(random.nextInt(0, 8)) { FILE_POOL.random(random) }
            val expected = legacySelection(listOf(base, current), symbols, files)
            val result = ChangeImpact.analyze(current, symbols, files, base = base)
            assertEquals(expected.selected, result.changed.map { it.node.id }.toSet(), "seed $seed")
            assertEquals(expected.unresolved, result.unresolved, "seed $seed")
            assertEquals(expected.fallbackMatches, fallbackCount(result), "seed $seed")
            if (expected.fallbackMatches > 0) exercised += "fallback"
            if (expected.retainedFiles > 0) exercised += "retained"
            expected.unresolved.forEach { exercised += it.reason }
        }
        // 표본이 모든 선택 분기를 지나야 대조가 의미 있다.
        assertEquals(setOf("fallback", "retained", "notFound", "ambiguous", "unmappedFile"), exercised)
    }

    @Test fun `changed files read retention evidence once per revision rather than once per file`() {
        // 리뷰 지적 재현(perf#2): 파일마다 두 시점의 보존 근거 전체를 다시 걸러 읽기 횟수가 (파일 수 × 보존 근거 수)로 늘었다.
        val evidence = List(RETENTION_ENTRIES) {
            RetentionEvidence(NodeId("class:p/A"), RetentionReason.MANIFEST_COMPONENT, SourceLocation("res/layout/screen$it.xml", 1))
        }
        fun reads(fileCount: Int): Int {
            val counted = CountingList(evidence)
            val data = input(edge("A", "C")).copy(retention = counted)
            val result = ChangeImpact.analyze(data, files = List(fileCount) { "res/layout/screen$it.xml" }, base = data)
            assertEquals(listOf(NodeId("class:p/A")), result.changed.map { it.node.id })
            return counted.reads
        }
        assertEquals(reads(1), reads(RETENTION_ENTRIES))
    }

    @Test fun `file and symbol requests do not rescan every declaration`() {
        // 리뷰 지적 재현(perf#2): 변경 파일·symbol마다 두 시점의 전체 정점을 다시 걸러 비용이 (요청 수 × 정점 수)로 늘었다.
        // 파일 이름만 남은 bytecode 위치에서는 정점마다 요청의 파일 이름 문자열을, symbol마다 정점의 qualifiedName을 새로 만들었다.
        // 시간 대신 같은 그래프에서 요청 수만 바꾼 스레드 할당량 차이로 요청 하나의 비용이 정점 수와 무관한지 본다.
        val threads = ManagementFactory.getThreadMXBean() as? com.sun.management.ThreadMXBean
        assumeTrue(threads != null && threads.isThreadAllocatedMemorySupported && threads.isThreadAllocatedMemoryEnabled,
            "thread allocation accounting is unavailable on this JVM")
        val data = ImpactInput(CodeGraph(List(DECLARATIONS) { node("N$it", "N$it.kt") }, emptyList()))
        fun allocated(requests: Int, files: Boolean): Long {
            val requested = List(requests) { if (files) "src/main/kotlin/p/Missing$it.kt" else "Missing$it" }
            fun select() = if (files) ChangeImpact.analyze(data, files = requested, base = data) else ChangeImpact.analyze(data, requested, base = data)
            // 첫 실행의 클래스 적재 할당을 빼려고 한 번 먼저 돌린다.
            select()
            val before = threads!!.currentThreadAllocatedBytes
            val result = select()
            val bytes = threads.currentThreadAllocatedBytes - before
            assertEquals(requests, result.unresolved.size)
            return bytes
        }
        for (files in listOf(true, false)) {
            val perRequest = (allocated(MANY_REQUESTS, files) - allocated(FEW_REQUESTS, files)) / (MANY_REQUESTS - FEW_REQUESTS)
            assertTrue(perRequest < PER_REQUEST_BYTES,
                "${if (files) "file" else "symbol"} selection allocated $perRequest bytes per request over $DECLARATIONS declarations")
        }
    }

    private fun fallbackCount(report: ImpactReport): Int = report.limitations.singleOrNull { it.startsWith("source-file-candidates:") }
        ?.removePrefix("source-file-candidates: ")?.substringBefore(' ')?.toInt() ?: 0

    private fun randomImpactInput(random: Random): ImpactInput {
        val nodes = (0 until 12).filter { random.nextInt(3) != 0 }.map { index ->
            GraphNode(NodeId("class:p/N$index"), NAME_POOL.random(random), NodeKind.CLASS,
                moduleName = if (random.nextInt(4) == 0) "app" else null, location = PATH_POOL.random(random)?.let(::SourceLocation))
        }
        // 일부 보존 근거는 이 시점 그래프에 없는 선언을 가리킨다.
        val retention = List(random.nextInt(0, 5)) {
            RetentionEvidence(NodeId("class:p/N${random.nextInt(14)}"), RetentionReason.MANIFEST_COMPONENT,
                PATH_POOL.random(random)?.let { SourceLocation(it, 1) })
        }
        return ImpactInput(CodeGraph(nodes, emptyList()), retention)
    }

    /** 색인 도입 전 analyze의 symbol·파일 선택 규칙을 그대로 옮긴 기준 결과다. */
    private data class LegacySelection(val selected: Set<NodeId>, val unresolved: List<ImpactUnresolved>, val fallbackMatches: Int, val retainedFiles: Int)

    private fun legacySelection(inputs: List<ImpactInput>, symbols: List<String>, files: List<String>): LegacySelection {
        val selected = sortedSetOf<NodeId>()
        val unresolved = mutableListOf<ImpactUnresolved>()
        for (symbol in symbols.distinct().sorted()) {
            val matches = inputs.flatMap { it.graph.nodes.values }
                .filter { it.id.value == symbol || it.name == symbol || it.qualifiedName == symbol }
                .map { it.id }.distinct().sorted()
            when (matches.size) {
                0 -> unresolved += ImpactUnresolved(symbol, "notFound")
                1 -> selected += matches.single()
                else -> unresolved += ImpactUnresolved(symbol, "ambiguous", matches)
            }
        }
        var fallbackMatches = 0
        var retainedFiles = 0
        for (file in files.distinct().sorted()) {
            var matched = false
            for (input in inputs) {
                val nodes = input.graph.nodes.values.filter { node ->
                    val path = node.location?.path ?: return@filter false
                    if (path == file) true else if ('/' !in path && path == file.substringAfterLast('/')) {
                        fallbackMatches++
                        true
                    } else false
                }.map { it.id }
                val retained = input.retention.filter { it.location?.path == file && input.graph.contains(it.nodeId) }.map { it.nodeId }
                if (retained.isNotEmpty()) retainedFiles++
                selected += nodes + retained
                if (nodes.isNotEmpty() || retained.isNotEmpty()) matched = true
            }
            if (!matched) unresolved += ImpactUnresolved(file, "unmappedFile")
        }
        return LegacySelection(selected, unresolved, fallbackMatches, retainedFiles)
    }

    /** 원소를 읽은 횟수를 세는 목록이다. 보존 근거를 몇 번 훑는지 시간 대신 결정적으로 확인한다. */
    private class CountingList<T>(private val items: List<T>) : AbstractList<T>() {
        var reads = 0
            private set

        override val size: Int get() = items.size

        override fun get(index: Int): T {
            reads++
            return items[index]
        }
    }

    private companion object {
        /** 무작위 그래프의 위치 후보다. null은 위치 없는 선언, 디렉터리 없는 이름은 bytecode의 옛 위치다. */
        val PATH_POOL = listOf(null, "A.kt", "B.kt", "AndroidManifest.xml", "src/main/kotlin/p/A.kt", "src/test/kotlin/p/A.kt", "x/B.kt", "res/layout/main.xml")
        val NAME_POOL = listOf("A", "B", "N1", "app", "Shared")
        val FILE_POOL = listOf("A.kt", "B.kt", "C.kt", "AndroidManifest.xml", "src/main/kotlin/p/A.kt", "src/main/kotlin/q/A.kt",
            "src/test/kotlin/p/B.kt", "x/B.kt", "res/layout/main.xml", "res/layout/other.xml", "src/")
        val SYMBOL_POOL = listOf("A", "B", "N1", "app", "Shared", "Missing", "class:p/N1", "class:p/N7", "p.N3", "app.p.N3", "p.N13")

        /** 보존 근거 읽기 횟수 비교에 쓰는 근거 수다. 요청 파일 수와 같게 둔다. */
        const val RETENTION_ENTRIES = 40

        /** 할당량 비교 그래프의 선언 수와 두 요청 수, 요청 하나에 허용하는 할당 상한이다. */
        const val DECLARATIONS = 2_000
        const val FEW_REQUESTS = 4
        const val MANY_REQUESTS = 68
        const val PER_REQUEST_BYTES = 8_192L
    }
}
