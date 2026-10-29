package dev.kartograph.index

import dev.kartograph.core.*
import dev.kartograph.index.fixture.CallbackFlowFixture
import dev.kartograph.index.fixture.defaultbridges.Receiver
import java.io.ByteArrayInputStream
import java.io.DataInputStream
import java.nio.ByteBuffer
import java.nio.file.Files
import java.nio.file.Path
import java.util.zip.CRC32C
import kotlin.test.*
import org.junit.jupiter.api.io.TempDir

class GraphExportSelectionIntegrationTest {
    @Test fun `compiler fixtures match old selection across roots callbacks stubs and cache states`(@TempDir root: Path) {
        val (java, library) = GraphExportIndexTest().fixture(root.resolve("java"))
        val kotlin = copyCompilerFixtures(root.resolve("kotlin"))
        val duplicate = Files.createDirectories(root.resolve("duplicate/sample"))
        Files.copy(java.resolve("sample/Consumer.class"), duplicate.resolve("Consumer.class"))
        val roots = listOf(java, kotlin, duplicate.parent)
        val generated = listOf(kotlin, duplicate.parent)
        val resources = root.resolve("resources")
        Files.createDirectories(resources.resolve("META-INF/services"))
        Files.writeString(resources.resolve("META-INF/services/sample.ServicePort"), "sample.Handler0\n")
        var run = 0
        for (callbacks in listOf(false, true)) for (stubs in listOf(false, true)) for (excluded in exclusionCases) {
            val options = GraphExportIndexOptions(excluded, maximumDispatchCandidates = 2, includeExternalStubs = stubs)
            for (state in listOf("none", "cold", "warm")) {
                val pair = List(2) { side -> if (state == "none") null else
                    ClassIndexCache(root.resolve("cache-${run}-$side"), "export-selection") }
                run++
                fun invoke(side: Int, old: Boolean) = index(roots, options, pair[side], callbacks, listOf(library),
                    listOf(resources), generated, old)
                if (state == "warm") { invoke(0, true); invoke(1, false) }
                val expected = invoke(0, true)
                val actual = invoke(1, false)
                assertExportIndex(expected, actual)
                val graph = actual.indexed.graph
                assertTrue(graph.edges.none { it.origin in excluded })
                assertEquals(0, actual.indexed.selectedRootByNode[JvmNodeId.classId("sample/Consumer")])
                assertFalse(graph.nodes.getValue(JvmNodeId.classId("sample/Consumer")).synthesized)
                assertTrue(graph.nodes.getValue(JvmNodeId.classId("dev/kartograph/index/fixture/defaultbridges/Receiver")).synthesized)
                assertTrue(graph.serviceProviders.isNotEmpty())
                assertTrue(graph.enclosures.isNotEmpty())
                assertEquals(callbacks || state != "none", graph.callbackArguments.isNotEmpty())
                assertEquals(callbacks || state != "none", graph.parameterUses.isNotEmpty())
                assertEquals(callbacks || state != "none", graph.lambdaEscapes.isNotEmpty())
                assertEquals(EdgeOrigin.BYTECODE !in excluded, graph.nodes.values.any { it.kotlinDefaultBridge != null })
                assertEquals(EdgeOrigin.DISPATCH_MODEL !in excluded, actual.limitations.isNotEmpty())
                when (state) {
                    "none" -> assertEquals(0, actual.indexed.statistics.cacheHits)
                    "cold" -> assertEquals(1, actual.indexed.statistics.cacheHits, "later duplicate root reuses the first parse")
                    "warm" -> assertEquals(actual.indexed.statistics.classFiles, actual.indexed.statistics.cacheHits)
                }
                assertEquals(state != "warm", actual.indexed.statistics.parsedClasses > 0)
            }
        }
        val expanded = ClassFileIndexer().indexWithObservations(roots).graph
        val noOp = GraphExportIndexOptions(setOf(EdgeOrigin.COMPILER_REFERENCE))
        assertTrue(expanded.nodes.values.any { it.kotlinDefaultBridge != null })
        assertSame(expanded, selectGraphForExport(expanded, noOp), "real default-bridge witnesses survive graph reuse")
        assertExportGraph(fullRebuildGraphExportSelection(expanded, noOp), expanded)
        val runtime = expanded.edges.filter { it.origin == EdgeOrigin.RUNTIME_MODEL && it.kind == EdgeKind.REFERENCE }
        assertTrue(runtime.isNotEmpty(), "real compiler fixture must exercise runtime REFERENCE facts")
        for (excluded in listOf(setOf(EdgeOrigin.DISPATCH_MODEL), setOf(EdgeOrigin.RUNTIME_MODEL))) {
            val result = ClassFileIndexer().indexForGraphExport(roots, options = GraphExportIndexOptions(excluded)).indexed.graph
            assertEquals(if (EdgeOrigin.RUNTIME_MODEL in excluded) emptyList() else runtime,
                result.edges.filter { it.origin == EdgeOrigin.RUNTIME_MODEL && it.kind == EdgeKind.REFERENCE })
        }
    }

    @Test fun `accepted cached origins and stub restored edges are filtered after expansion`(@TempDir root: Path) {
        val (input, _) = GraphExportIndexTest().fixture(root)
        val file = input.resolve("sample/Consumer.class")
        val cache = ClassIndexCache(root.resolve("cache"), "cached-origins")
        ClassFileIndexer(cache).index(listOf(input))
        val hash = sha256(Files.readAllBytes(file))
        val original = (cache.read(hash) as CacheReadResult.Hit).facts
        val caller = JvmNodeId.methodId("sample/Consumer", "use", "(Lsample/ServicePort;)V")
        val internal = JvmNodeId.methodId("sample/Consumer", "<init>", "()V")
        val external = JvmNodeId.classId("outside/Restored")
        for (excluded in exclusionCases) for (stubs in listOf(false, true)) {
            val injected = excluded.flatMap { origin -> listOf(internal, external).map { GraphEdge(caller, it, EdgeKind.REFERENCE, origin = origin) } }
            assertEquals(CacheWriteResult.Written, cache.write(hash, original.copy(edges = original.edges + injected)))
            assertTrue((cache.read(hash) as CacheReadResult.Hit).facts.edges.containsAll(injected))
            val options = GraphExportIndexOptions(excluded, includeExternalStubs = stubs)
            val expanded = indexExpanded(listOf(input), options, cache)
            assertTrue(expanded.indexed.graph.edges.containsAll(injected.filter { it.target == internal || stubs }))
            assertEquals(stubs, external in expanded.indexed.graph.nodes)
            val actual = index(listOf(input), options, cache)
            assertExportIndex(withOldSelection(expanded, options), actual)
            assertTrue(actual.indexed.statistics.cacheHits > 0)
            assertEquals(0, actual.indexed.statistics.parsedClasses)
            assertTrue(actual.indexed.graph.edges.none { it.origin in excluded })
            if (stubs) assertContains(actual.indexed.graph.nodes.getValue(external).attributes, NodeAttribute.EXTERNAL_STUB)
        }
    }

    @Test fun `full index preserves caller exclusion calls and throwing membership`(@TempDir root: Path) {
        val (input, _) = GraphExportIndexTest().fixture(root)
        for (throwAt in listOf<Int?>(null, 1, 3)) {
            fun exclusions() = TracedExclusions(throwAt = throwAt) { call, _ -> call == 1 || call % 3 == 0 }
            val prior = exclusions()
            val current = exclusions()
            if (throwAt == null) {
                assertExportIndex(index(listOf(input), GraphExportIndexOptions(prior), old = true),
                    index(listOf(input), GraphExportIndexOptions(current)))
            } else {
                val expected = assertFailsWith<MembershipFailure> { index(listOf(input), GraphExportIndexOptions(prior), old = true) }
                val actual = assertFailsWith<MembershipFailure> { index(listOf(input), GraphExportIndexOptions(current)) }
                assertEquals(expected.message, actual.message)
            }
            assertEquals(prior.calls, current.calls)
            assertEquals("contains:DISPATCH_MODEL", current.calls.first())
            if (throwAt != 1) assertEquals("isEmpty", current.calls[1])
        }
    }

    @Test fun `malformed class and jar still fail before final selection`(@TempDir root: Path) {
        val classes = Files.createDirectories(root.resolve("broken"))
        Files.write(classes.resolve("Broken.class"), byteArrayOf(0, 1, 2))
        val jar = Files.write(root.resolve("broken.jar"), byteArrayOf(0, 1, 2))
        for (input in listOf(classes, jar)) for (stubs in listOf(false, true)) for (cached in listOf(false, true)) {
            val options = GraphExportIndexOptions(setOf(EdgeOrigin.DISPATCH_MODEL), includeExternalStubs = stubs)
            val cache = if (cached) ClassIndexCache(root.resolve("cache"), "malformed") else null
            val expected = assertFailsWith<ClassIndexingException> { index(listOf(input), options, cache, old = true) }
            val actual = assertFailsWith<ClassIndexingException> { index(listOf(input), options, cache) }
            assertEquals(expected.message, actual.message)
            assertEquals(expected.cause?.javaClass, actual.cause?.javaClass)
        }
    }

    @Test fun `invalid cache bytes enums weights and lines reparse identically`(@TempDir root: Path) {
        val (input, _) = GraphExportIndexTest().fixture(root)
        val hash = sha256(Files.readAllBytes(input.resolve("sample/Consumer.class")))
        val marker = GraphEdge(NodeId("method:marker/Source#run()V"), NodeId("method:marker/Target#hit()V"),
            EdgeKind.CALL, 2, EdgeOrigin.BYTECODE, listOf(11, 17))
        val valid = ClassFactsCodec.encode(ClassFacts("sample/Consumer", emptyList(), listOf(marker)), "invalid-fields", hash)
        val corrupt = listOf(byteArrayOf(1, 2, 3), valid.copyOf().also { it[it.lastIndex] = (it.last().toInt() xor 1).toByte() },
            corruptEdgeField(valid, 2, -1), corruptEdgeField(valid, 1, 0), corruptEdgeField(valid, 4, 0))
        for ((case, bytes) in corrupt.withIndex()) for (stubs in listOf(false, true)) {
            val caches = List(2) { side -> ClassIndexCache(root.resolve("bad-$case-$stubs-$side"), "invalid-fields") }
            caches.forEach { cache ->
                ClassFileIndexer(cache).index(listOf(input))
                Files.write(cache.directory.resolve("$hash-${cache.namespace}.cix"), bytes)
                assertEquals(CacheReadResult.Invalid, cache.read(hash))
            }
            val options = GraphExportIndexOptions(setOf(EdgeOrigin.DISPATCH_MODEL), includeExternalStubs = stubs)
            val expected = index(listOf(input), options, caches[0], old = true)
            val actual = index(listOf(input), options, caches[1])
            assertExportIndex(expected, actual)
            assertEquals(1, actual.indexed.statistics.invalidEntries)
            assertEquals(1, actual.indexed.statistics.parsedClasses)
            assertEquals(1, actual.indexed.statistics.cacheMisses)
        }
    }

    @Test fun `assembly and restored stub overflow still fail before exclusions can remove them`(@TempDir root: Path) {
        val (input, _) = GraphExportIndexTest().fixture(root)
        val file = input.resolve("sample/Consumer.class")
        val hash = sha256(Files.readAllBytes(file))
        val cache = ClassIndexCache(root.resolve("cache"), "overflow")
        ClassFileIndexer(cache).index(listOf(input))
        val original = (cache.read(hash) as CacheReadResult.Hit).facts
        val source = JvmNodeId.methodId("sample/Consumer", "use", "(Lsample/ServicePort;)V")
        for (external in listOf(false, true)) {
            val target = if (external) JvmNodeId.classId("outside/Overflow") else JvmNodeId.classId("sample/Consumer")
            val edges = listOf(Int.MAX_VALUE, 1).map { GraphEdge(source, target, EdgeKind.REFERENCE, it, EdgeOrigin.DISPATCH_MODEL) }
            assertEquals(CacheWriteResult.Written, cache.write(hash, original.copy(edges = original.edges + edges)))
            assertIs<CacheReadResult.Hit>(cache.read(hash))
            for (stubs in listOf(false, true)) {
                val options = GraphExportIndexOptions(setOf(EdgeOrigin.DISPATCH_MODEL), includeExternalStubs = stubs)
                if (external && !stubs) {
                    assertExportIndex(index(listOf(input), options, cache, old = true), index(listOf(input), options, cache))
                } else {
                    val expected = assertFailsWith<IllegalArgumentException> { index(listOf(input), options, cache, old = true) }
                    val actual = assertFailsWith<IllegalArgumentException> { index(listOf(input), options, cache) }
                    assertEquals("edge weight must be positive", actual.message)
                    assertEquals(expected.message, actual.message)
                }
            }
        }
    }

    private fun index(
        roots: List<Path>, options: GraphExportIndexOptions, cache: ClassIndexCache? = null, callbacks: Boolean = true,
        classpath: List<Path>? = null, services: List<Path> = emptyList(), generated: List<Path> = emptyList(), old: Boolean = false,
    ): GraphExportIndexResult = if (old) withOldSelection(indexExpanded(roots, options, cache, callbacks, classpath, services, generated), options)
        else ClassFileIndexer(cache, callbacks).indexForGraphExport(roots, classpath, services, generated, options)

    // dispatch의 contains는 그대로 위임하고 마지막 isEmpty만 우회해 실제 expanded 입력을 얻는다.
    private fun indexExpanded(
        roots: List<Path>, options: GraphExportIndexOptions, cache: ClassIndexCache? = null, callbacks: Boolean = true,
        classpath: List<Path>? = null, services: List<Path> = emptyList(), generated: List<Path> = emptyList(),
    ): GraphExportIndexResult = ClassFileIndexer(cache, callbacks).indexForGraphExport(roots, classpath, services, generated,
        options.copy(excludedOrigins = object : Set<EdgeOrigin> by options.excludedOrigins { override fun isEmpty() = true }))

    private fun withOldSelection(expanded: GraphExportIndexResult, options: GraphExportIndexOptions): GraphExportIndexResult {
        val indexed = expanded.indexed
        return GraphExportIndexResult(IndexedClasses(fullRebuildGraphExportSelection(indexed.graph, options), indexed.observations,
            indexed.hierarchy, indexed.declarationsByRoot, indexed.selectedRootByNode, indexed.statistics), expanded.limitations)
    }

    private fun copyCompilerFixtures(target: Path): Path {
        val source = Path.of(CallbackFlowFixture::class.java.protectionDomain.codeSource.location.toURI())
        val common = "dev/kartograph/index/fixture"
        Files.list(source.resolve(common)).use { files -> files.filter { path ->
            path.fileName.toString().endsWith(".class") && listOf("CallbackFlowFixture", "Listener", "KotlinFieldFixture", "RuntimeObservationFixture")
                .any { path.fileName.toString().startsWith(it) }
        }.forEach { path ->
            val output = target.resolve(common).resolve(path.fileName)
            Files.createDirectories(output.parent); Files.copy(path, output)
        } }
        val bridges = Path.of(Receiver::class.java.protectionDomain.codeSource.location.toURI()).resolve("$common/defaultbridges")
        Files.list(bridges).use { files -> files.filter { it.toString().endsWith(".class") }.forEach { path ->
            val output = target.resolve("$common/defaultbridges").resolve(path.fileName)
            Files.createDirectories(output.parent); Files.copy(path, output)
        } }
        return target
    }

    private fun corruptEdgeField(bytes: ByteArray, field: Int, value: Int): ByteArray {
        val pattern = ByteBuffer.allocate(24).apply { listOf(EdgeKind.CALL.ordinal, 2, EdgeOrigin.BYTECODE.ordinal, 2, 11, 17).forEach { putInt(it) } }.array()
        val offsets = (0..bytes.size - pattern.size).filter { start -> pattern.indices.all { bytes[start + it] == pattern[it] } }
        assertEquals(1, offsets.size, "unique serialized edge marker")
        return bytes.copyOf().also { changed ->
            ByteBuffer.wrap(changed).putInt(offsets.single() + 4 * field, value)
            val header = DataInputStream(ByteArrayInputStream(changed))
            header.readInt(); header.readInt(); header.readUTF(); header.readUTF()
            val length = header.readInt()
            val start = changed.size - header.available()
            val crc = CRC32C().also { it.update(changed, start, length) }.value.toInt()
            ByteBuffer.wrap(changed).putInt(start + length, crc)
        }
    }
}

private fun assertExportIndex(expected: GraphExportIndexResult, actual: GraphExportIndexResult) {
    assertExportGraph(expected.indexed.graph, actual.indexed.graph)
    assertEquals(expected.limitations, actual.limitations)
    assertEquals(expected.indexed.observations, actual.indexed.observations)
    assertEquals(expected.indexed.declarationsByRoot, actual.indexed.declarationsByRoot)
    assertEquals(expected.indexed.selectedRootByNode, actual.indexed.selectedRootByNode)
    // 공개 조회에서 이름 inventory를 주지 않는 hierarchy도 내부의 네 map 전체를 비교한다.
    for (name in listOf("entries", "methods", "annotationTypes", "interfaceKinds")) {
        val field = ClassHierarchy::class.java.getDeclaredField(name).apply { isAccessible = true }
        assertEquals(field.get(expected.indexed.hierarchy), field.get(actual.indexed.hierarchy), "hierarchy $name")
    }
    fun counts(statistics: IndexingStatistics) = statistics.copy(readNanos = 0, cacheReadNanos = 0, parseNanos = 0,
        assemblyNanos = 0, hierarchyNanos = 0, runtimeNanos = 0, cacheWriteNanos = 0, totalNanos = 0, dispatchNanos = 0)
    assertEquals(counts(expected.indexed.statistics), counts(actual.indexed.statistics))
}
