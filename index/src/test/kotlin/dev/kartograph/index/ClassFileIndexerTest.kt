package dev.kartograph.index

import dev.kartograph.core.EdgeKind
import dev.kartograph.core.JvmModifier
import dev.kartograph.core.NodeId
import dev.kartograph.core.NodeKind
import dev.kartograph.core.Visibility
import dev.kartograph.index.fixture.Caller
import dev.kartograph.index.fixture.JavaFixture
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardOpenOption
import java.nio.file.attribute.FileTime
import java.io.IOException
import java.util.jar.JarEntry
import java.util.jar.JarOutputStream
import kotlin.io.path.createDirectories
import kotlin.io.path.writeBytes
import kotlin.io.path.writeText
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertFalse
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlin.test.assertNotEquals
import java.util.concurrent.Executors
import org.junit.jupiter.api.io.TempDir
import org.objectweb.asm.ClassWriter
import org.objectweb.asm.ConstantDynamic
import org.objectweb.asm.Handle
import org.objectweb.asm.Label
import org.objectweb.asm.Opcodes
import org.objectweb.asm.Type

class ClassFileIndexerTest {
    @Test
    fun `cache batches preserve root precedence and recover after malformed input`(@TempDir directory: Path) {
        val first = directory.resolve("first").createDirectories()
        val second = directory.resolve("second").createDirectories()
        repeat(40) { index ->
            first.resolve("Type$index.class").writeBytes(duplicateClass("First.java", Opcodes.ACC_PUBLIC, "Type$index"))
            second.resolve("Type$index.class").writeBytes(duplicateClass("Second.java", Opcodes.ACC_PUBLIC or Opcodes.ACC_FINAL, "Type$index"))
        }
        val cache = ClassIndexCache(directory.resolve("cache"), "batches")
        val roots = listOf(first, second)
        val oracle = ClassFileIndexer().indexWithObservations(roots)
        val cold = ClassFileIndexer(cache).indexWithObservations(roots)
        assertSameSemantics(oracle, cold)
        assertEquals(80, cold.statistics.parsedClasses)
        val reversed = roots.reversed()
        val warm = ClassFileIndexer(cache).indexWithObservations(reversed)
        assertSameSemantics(ClassFileIndexer().indexWithObservations(reversed), warm)
        assertEquals(80, warm.statistics.cacheHits)
        assertEquals(0, warm.statistics.parsedClasses)

        val broken = first.resolve("Type20.class")
        val original = Files.readAllBytes(broken)
        broken.writeBytes(byteArrayOf(0, 1, 2))
        assertFailsWith<ClassIndexingException> { ClassFileIndexer(cache).indexWithObservations(roots) }
        broken.writeBytes(original)
        val recovered = ClassFileIndexer(cache).indexWithObservations(roots)
        assertSameSemantics(ClassFileIndexer().indexWithObservations(roots), recovered)
        assertEquals(80, recovered.statistics.cacheHits)
    }

    @Test
    fun `content keyed cache reuses unchanged facts and rejects same timestamp edits`(@TempDir directory: Path) {
        val classes = directory.resolve("classes").createDirectories()
        val cache = ClassIndexCache(directory.resolve("cache"))
        val classFile = classes.resolve("dev/fixture/Cached.class").also { it.parent.createDirectories() }
        classFile.writeBytes(duplicateClass("Cached.java", Opcodes.ACC_PUBLIC, "dev/fixture/Cached"))

        val cold = ClassFileIndexer(cache).indexWithObservations(listOf(classes))
        val warm = ClassFileIndexer(cache).indexWithObservations(listOf(classes))
        assertEquals(1, cold.statistics.cacheMisses)
        assertEquals(1, warm.statistics.cacheHits)
        assertSameSemantics(cold, warm)

        val timestamp = Files.getLastModifiedTime(classFile)
        classFile.writeBytes(duplicateClass("Cached.java", Opcodes.ACC_PUBLIC or Opcodes.ACC_FINAL, "dev/fixture/Cached"))
        Files.setLastModifiedTime(classFile, timestamp)
        val changed = ClassFileIndexer(cache).indexWithObservations(listOf(classes))
        assertEquals(0, changed.statistics.cacheHits)
        assertEquals(1, changed.statistics.cacheMisses)
        assertEquals(1, changed.statistics.parsedClasses)
        assertNotEquals(cold.graph, changed.graph)
    }

    @Test
    fun `corrupt cache entry is a counted miss and authoritative parse still succeeds`(@TempDir directory: Path) {
        val classes = directory.resolve("classes").createDirectories()
        val cacheDir = directory.resolve("cache")
        val file = classes.resolve("Cached.class")
        file.writeBytes(duplicateClass("Cached.java", Opcodes.ACC_PUBLIC, "Cached"))
        val cache = ClassIndexCache(cacheDir)
        ClassFileIndexer(cache).index(listOf(classes))
        Files.list(cacheDir).use { stream -> Files.write(stream.findFirst().orElseThrow(), byteArrayOf(1, 2, 3)) }
        val result = ClassFileIndexer(cache).indexWithObservations(listOf(classes))
        assertEquals(1, result.statistics.cacheMisses)
        assertEquals(1, result.statistics.invalidEntries)
        assertEquals(1, result.statistics.parsedClasses)
    }

    @Test
    fun `cached Java and Kotlin method bodies preserve runtime-enriched graph`(@TempDir directory: Path) {
        val cache = ClassIndexCache(directory.resolve("cache"))
        val oracle = ClassFileIndexer().indexWithObservations(listOf(testClassesRoot))
        ClassFileIndexer(cache).indexWithObservations(listOf(testClassesRoot))
        val cached = ClassFileIndexer(cache).indexWithObservations(listOf(testClassesRoot))
        assertSameSemantics(oracle, cached)
        assertTrue(cached.statistics.cacheHits > 0)
        assertEquals(0, cached.statistics.parsedClasses)
    }

    @Test
    fun `ASM reduced body round trips branches handlers wide locals handles indy and condy`(@TempDir directory: Path) {
        val classes = directory.resolve("classes").createDirectories()
        classes.resolve("Shapes.class").writeBytes(runtimeBodyShapes())
        val oracle = ClassFileIndexer().indexWithObservations(listOf(classes))
        val cache = ClassIndexCache(directory.resolve("cache"), "body-shapes")
        ClassFileIndexer(cache).indexWithObservations(listOf(classes))
        val warm = ClassFileIndexer(cache).indexWithObservations(listOf(classes))

        assertEquals(1, warm.statistics.cacheHits)
        assertEquals(0, warm.statistics.parsedClasses)
        assertSameSemantics(oracle, warm)
    }

    @Test
    fun `cached project facts still recompute changed dependency hierarchy`(@TempDir directory: Path) {
        val classes = directory.resolve("classes").createDirectories()
        val dependencies = directory.resolve("dependencies").createDirectories()
        classes.resolve("Project.class").writeBytes(classWithSuper("dev/fixture/Project", "lib/Child"))
        dependencies.resolve("Child.class").writeBytes(classWithSuper("lib/Child", "lib/First"))
        val cache = ClassIndexCache(directory.resolve("cache"), "hierarchy")
        ClassFileIndexer(cache).indexWithObservations(listOf(classes), listOf(dependencies))

        dependencies.resolve("Child.class").writeBytes(classWithSuper("lib/Child", "lib/Second"))
        val oracle = ClassFileIndexer().indexWithObservations(listOf(classes), listOf(dependencies))
        val warm = ClassFileIndexer(cache).indexWithObservations(listOf(classes), listOf(dependencies))
        assertEquals(1, warm.statistics.cacheHits)
        assertEquals(oracle.hierarchy.directSupertypesOf("lib/Child"), warm.hierarchy.directSupertypesOf("lib/Child"))
        assertEquals(setOf("lib/Second"), warm.hierarchy.directSupertypesOf("lib/Child"))
        assertSameSemantics(oracle, warm)
    }

    @Test
    fun `cache observes additions deletions renames and fresh timestamps`(@TempDir directory: Path) {
        val classes = directory.resolve("classes").createDirectories()
        val cache = ClassIndexCache(directory.resolve("cache"), "lifecycle")
        val first = classes.resolve("First.class")
        first.writeBytes(duplicateClass("First.java", 0, "dev/fixture/First"))
        ClassFileIndexer(cache).indexWithObservations(listOf(classes))

        val changedTime = FileTime.fromMillis(Files.getLastModifiedTime(first).toMillis() + 10_000)
        Files.setLastModifiedTime(first, changedTime)
        val timestampOracle = ClassFileIndexer().indexWithObservations(listOf(classes))
        val timestampCached = ClassFileIndexer(cache).indexWithObservations(listOf(classes))
        assertEquals(1, timestampCached.statistics.cacheHits)
        assertEquals(timestampOracle.observations, timestampCached.observations)

        val second = classes.resolve("Second.class")
        Files.move(first, second)
        second.writeBytes(duplicateClass("Second.java", 0, "dev/fixture/Second"))
        val renamed = ClassFileIndexer(cache).indexWithObservations(listOf(classes))
        assertEquals(1, renamed.statistics.cacheMisses)
        assertTrue(JvmNodeId.classId("dev/fixture/First") !in renamed.graph.nodes)
        assertTrue(JvmNodeId.classId("dev/fixture/Second") in renamed.graph.nodes)

        classes.resolve("Added.class").writeBytes(duplicateClass("Added.java", 0, "dev/fixture/Added"))
        val added = ClassFileIndexer(cache).indexWithObservations(listOf(classes))
        assertEquals(1, added.statistics.cacheHits)
        assertEquals(1, added.statistics.cacheMisses)
        Files.delete(second)
        val deleted = ClassFileIndexer(cache).indexWithObservations(listOf(classes))
        assertEquals(1, deleted.statistics.classFiles)
        assertTrue(JvmNodeId.classId("dev/fixture/Second") !in deleted.graph.nodes)
    }

    @Test
    fun `cache recomputes duplicate root generated jar and service selection`(@TempDir directory: Path) {
        val first = directory.resolve("first").createDirectories()
        val second = directory.resolve("second").createDirectories()
        first.resolve("Duplicate.class").writeBytes(duplicateClass("First.java", Opcodes.ACC_FINAL))
        second.resolve("Duplicate.class").writeBytes(duplicateClass("Second.java", Opcodes.ACC_ABSTRACT))
        val services = directory.resolve("resources/META-INF/services").createDirectories()
        services.resolve("dev.fixture.Service").writeText("dev.fixture.Duplicate\n")
        val cache = ClassIndexCache(directory.resolve("cache"), "global")
        ClassFileIndexer(cache).indexWithObservations(listOf(first, second), null, listOf(directory.resolve("resources")), listOf(first))

        services.resolve("dev.fixture.Service").writeText("dev.fixture.Other\n")
        val oracle = ClassFileIndexer().indexWithObservations(listOf(second, first), null, listOf(directory.resolve("resources")), emptyList())
        val warm = ClassFileIndexer(cache).indexWithObservations(listOf(second, first), null, listOf(directory.resolve("resources")), emptyList())
        assertEquals(2, warm.statistics.cacheHits)
        assertSameSemantics(oracle, warm)
        assertEquals("Second.java", warm.graph.node(JvmNodeId.classId("dev/fixture/Duplicate"))?.location?.path)
        assertFalse(warm.graph.nodes.getValue(JvmNodeId.classId("dev/fixture/Duplicate")).synthesized)
        assertEquals("dev/fixture/Other", warm.graph.serviceProviders.single().provider.value.removePrefix("class:"))
    }

    @Test
    fun `cache rejects trailing oversized symlinked and other-engine entries`(@TempDir directory: Path) {
        val classes = directory.resolve("classes").createDirectories()
        classes.resolve("Cached.class").writeBytes(duplicateClass("Cached.java", 0, "dev/fixture/Cached"))
        val cacheDir = directory.resolve("cache")
        val first = ClassIndexCache(cacheDir, "engine-a")
        ClassFileIndexer(first).indexWithObservations(listOf(classes))
        val entry = Files.list(cacheDir).use { it.findFirst().orElseThrow() }

        Files.write(entry, byteArrayOf(1), StandardOpenOption.APPEND)
        assertEquals(1, ClassFileIndexer(first).indexWithObservations(listOf(classes)).statistics.invalidEntries)
        Files.write(entry, ByteArray(ClassIndexCache.MAX_ENTRY_BYTES + 1))
        assertEquals(1, ClassFileIndexer(first).indexWithObservations(listOf(classes)).statistics.invalidEntries)

        Files.delete(entry)
        val target = directory.resolve("target").also { it.writeText("not cache data") }
        try {
            Files.createSymbolicLink(entry, target)
            val symlinked = ClassFileIndexer(first).indexWithObservations(listOf(classes))
            assertEquals(1, symlinked.statistics.cacheMisses)
            assertEquals(0, symlinked.statistics.cacheHits)
        } catch (_: UnsupportedOperationException) {
            // 이 파일시스템이 symlink를 지원하지 않으면 나머지 손상 경계를 계속 검증한다.
        } catch (_: IOException) {
            // 이 파일시스템이 symlink 생성을 허용하지 않아도 손상/engine 경계는 검증한다.
        }
        val changedEngine = ClassFileIndexer(ClassIndexCache(cacheDir, "engine-b")).indexWithObservations(listOf(classes))
        assertEquals(1, changedEngine.statistics.cacheMisses)
        assertEquals(0, changedEngine.statistics.cacheHits)
    }

    @Test
    fun `simultaneous writers leave a reusable complete entry`(@TempDir directory: Path) {
        val classes = directory.resolve("classes").createDirectories()
        classes.resolve("Cached.class").writeBytes(duplicateClass("Cached.java", 0, "dev/fixture/Cached"))
        val cache = ClassIndexCache(directory.resolve("cache"), "concurrent")
        val pool = Executors.newFixedThreadPool(4)
        try {
            val tasks = List(12) { pool.submit<IndexedClasses> { ClassFileIndexer(cache).indexWithObservations(listOf(classes)) } }
            tasks.forEach { assertTrue(it.get().graph.nodes.isNotEmpty()) }
        } finally {
            pool.shutdownNow()
        }
        val warm = ClassFileIndexer(cache).indexWithObservations(listOf(classes))
        assertEquals(1, warm.statistics.cacheHits)
        assertEquals(0, warm.statistics.invalidEntries)
    }

    @Test
    fun `unavailable automatic identity disables reuse without partial output`(@TempDir directory: Path) {
        val classes = directory.resolve("classes").createDirectories()
        classes.resolve("Cached.class").writeBytes(duplicateClass("Cached.java", 0, "dev/fixture/Cached"))
        val cacheDirectory = directory.resolve("cache")
        val unavailable = ClassIndexCache(cacheDirectory, null)

        val first = ClassFileIndexer(unavailable).indexWithObservations(listOf(classes))
        val second = ClassFileIndexer(unavailable).indexWithObservations(listOf(classes))

        assertEquals(0, first.statistics.cacheHits)
        assertEquals(1, first.statistics.parsedClasses)
        assertTrue(first.statistics.unavailableEntries >= 1)
        assertSameSemantics(first, second)
        assertFalse(Files.exists(cacheDirectory))
    }

    @Test
    fun `cached facts preserve isolated UTF16 source code units`(@TempDir directory: Path) {
        val classes = directory.resolve("classes").createDirectories()
        classes.resolve("Cached.class").writeBytes(duplicateClass("\uD800.java", 0, "dev/fixture/Cached"))
        val cache = ClassIndexCache(directory.resolve("cache"))
        val original = ClassFileIndexer(cache).indexWithObservations(listOf(classes))
        val cached = ClassFileIndexer(cache).indexWithObservations(listOf(classes))
        assertEquals(1, cached.statistics.cacheHits)
        assertSameSemantics(original, cached)
    }

    @Test
    fun `engine identity includes separate helpers and binds dependency roles`(@TempDir directory: Path) {
        val module = directory.resolve("module").createDirectories()
        val helper = module.resolve("SeparateHelper.class")
        module.resolve("Parser.class").writeBytes(byteArrayOf(1, 2, 3))
        helper.writeBytes(byteArrayOf(4, 5, 6))
        val dependency = directory.resolve("dependency.jar")
        dependency.writeBytes(byteArrayOf(7, 8, 9))
        val original = CacheIdentity.fromArtifacts(mapOf("parser" to module, "library" to dependency))
        assertTrue(original != null)
        assertEquals(original, CacheIdentity.fromArtifacts(linkedMapOf("library" to dependency, "parser" to module)))
        helper.writeBytes(byteArrayOf(4, 5, 7))
        assertTrue(original != CacheIdentity.fromArtifacts(mapOf("parser" to module, "library" to dependency)))
        helper.writeBytes(byteArrayOf(4, 5, 6))
        assertTrue(original != CacheIdentity.fromArtifacts(mapOf("parser" to dependency, "library" to module)))
        assertEquals(null, CacheIdentity.fromArtifacts(mapOf("parser" to module, "missing" to directory.resolve("absent.jar"))))
    }

    @Test
    fun `marks Dagger generated markers and enclosed classes but not name lookalikes`(@TempDir directory: Path) {
        directory.resolve("Factory.class").writeBytes(annotatedClass(
            "Factory.java", "dev/fixture/Factory", "Ldagger/internal/DaggerGenerated;",
        ))
        directory.resolve("Nested.class").writeBytes(ClassWriter(0).apply {
            visit(Opcodes.V17, Opcodes.ACC_PUBLIC, "dev/fixture/Factory${'$'}Nested", null, "java/lang/Object", null)
            visitInnerClass("dev/fixture/Factory${'$'}Nested", "dev/fixture/Factory", "Nested", Opcodes.ACC_PUBLIC)
            visitEnd()
        }.toByteArray())
        directory.resolve("Lookalike.class").writeBytes(duplicateClass(
            "Lookalike.java", Opcodes.ACC_PUBLIC, "dev/fixture/Factory${'$'}Lookalike",
        ))
        val graph = ClassFileIndexer().index(listOf(directory))
        assertTrue(graph.nodes.getValue(JvmNodeId.classId("dev/fixture/Factory")).synthesized)
        assertTrue(graph.nodes.getValue(JvmNodeId.classId("dev/fixture/Factory${'$'}Nested")).synthesized)
        assertFalse(graph.nodes.getValue(JvmNodeId.classId("dev/fixture/Factory${'$'}Lookalike")).synthesized)
    }

    @Test
    fun `marks Hilt generation metadata without hiding an unannotated factory`(@TempDir directory: Path) {
        val markers = listOf(
            "dagger/hilt/codegen/OriginatingElement",
            "dagger/hilt/processor/internal/aggregateddeps/AggregatedDeps",
            "dagger/hilt/internal/componenttreedeps/ComponentTreeDeps",
            "dagger/hilt/internal/aggregatedroot/AggregatedRoot",
            "dagger/hilt/internal/processedrootsentinel/ProcessedRootSentinel",
        )
        markers.forEachIndexed { index, annotation ->
            directory.resolve("Generated$index.class").writeBytes(annotatedClass(
                "Generated$index.java", "dev/fixture/Generated$index", "L$annotation;",
            ))
        }
        directory.resolve("User_Factory.class").writeBytes(duplicateClass(
            "User_Factory.java", Opcodes.ACC_PUBLIC, "dev/fixture/User_Factory",
        ))
        val graph = ClassFileIndexer().index(listOf(directory))
        markers.indices.forEach { index ->
            assertTrue(graph.nodes.getValue(JvmNodeId.classId("dev/fixture/Generated$index")).synthesized)
        }
        assertFalse(graph.nodes.getValue(JvmNodeId.classId("dev/fixture/User_Factory")).synthesized)
    }

    @Test
    fun `indexes structural facts from real Kotlin class files`() {
        val graph = ClassFileIndexer().index(listOf(testClassesRoot))
        val callerClass = JvmNodeId.classId("dev/kartograph/index/fixture/Caller")
        val baseClass = JvmNodeId.classId("dev/kartograph/index/fixture/Base")
        val markerClass = JvmNodeId.classId("dev/kartograph/index/fixture/Marker")
        val callerMethod = JvmNodeId.methodId(
            "dev/kartograph/index/fixture/Caller",
            "callTwice",
            "()Ljava/lang/String;",
        )
        val dependencyMethod = JvmNodeId.methodId(
            "dev/kartograph/index/fixture/Dependency",
            "touch",
            "()V",
        )
        val dependencyClass = JvmNodeId.classId("dev/kartograph/index/fixture/Dependency")
        val dependencyField = JvmNodeId.fieldId(
            "dev/kartograph/index/fixture/Caller",
            "dependency",
            "Ldev/kartograph/index/fixture/Dependency;",
        )

        assertEquals(NodeKind.CLASS, graph.node(callerClass)?.kind)
        assertEquals(NodeKind.INTERFACE, graph.node(baseClass)?.kind)
        assertEquals(setOf(JvmModifier.FINAL), graph.node(callerClass)?.jvmModifiers)
        assertTrue(JvmModifier.ABSTRACT in graph.node(baseClass)?.jvmModifiers.orEmpty())
        assertEquals(setOf("dev/kartograph/index/fixture/Base"), graph.node(callerClass)?.supertypes)
        assertTrue(graph.edges.any {
            it.source == callerClass && it.target == baseClass && it.kind == EdgeKind.INHERITANCE
        })
        assertTrue(graph.edges.any {
            it.source == callerClass && it.target == markerClass && it.kind == EdgeKind.ANNOTATION
        })
        assertTrue(graph.edges.any {
            it.source == callerClass && it.target == callerMethod && it.kind == EdgeKind.MEMBER
        })
        assertTrue(graph.edges.any {
            it.source == callerMethod && it.target == dependencyField && it.kind == EdgeKind.FIELD_ACCESS
        })
        val call = graph.edges.single {
            it.source == callerMethod && it.target == dependencyMethod && it.kind == EdgeKind.CALL
        }
        assertEquals(2, call.weight)
        assertTrue(graph.edges.any {
            it.source == callerMethod && it.target == dependencyClass && it.kind == EdgeKind.REFERENCE
        })
    }

    @Test
    fun `records source file and first executable line`() {
        val graph = ClassFileIndexer().index(listOf(testClassesRoot))
        val callerMethod = JvmNodeId.methodId(
            "dev/kartograph/index/fixture/Caller",
            "callTwice",
            "()Ljava/lang/String;",
        )

        val location = assertNotNull(graph.node(callerMethod)?.location)
        assertEquals("ProbeFixtures.kt", location.path)
        assertTrue(assertNotNull(location.line) > 0)
    }

    @Test
    fun `members reference their owning class for reverse reachability`() {
        val graph = ClassFileIndexer().index(listOf(testClassesRoot))
        val owner = JvmNodeId.classId("dev/kartograph/index/fixture/Caller")
        val method = JvmNodeId.methodId(
            "dev/kartograph/index/fixture/Caller",
            "callTwice",
            "()Ljava/lang/String;",
        )
        val field = JvmNodeId.fieldId(
            "dev/kartograph/index/fixture/Caller",
            "dependency",
            "Ldev/kartograph/index/fixture/Dependency;",
        )

        assertTrue(graph.edges.any { edge ->
            edge.source == method && edge.target == owner && edge.kind == EdgeKind.REFERENCE
        })
        assertTrue(graph.edges.any { edge ->
            edge.source == field && edge.target == owner && edge.kind == EdgeKind.REFERENCE
        })
    }

    @Test
    fun `treats compiler line zero as an unknown source line`(@TempDir directory: Path) {
        directory.resolve("SyntheticCoroutine.class").writeBytes(classWithLineZero())

        val graph = ClassFileIndexer().index(listOf(directory))
        val method = graph.node(JvmNodeId.methodId("dev/fixture/SyntheticCoroutine", "resume", "()V"))

        assertEquals("SyntheticCoroutine.kt", method?.location?.path)
        assertEquals(null, method?.location?.line)
    }

    @Test
    fun `method descriptors retain project types without executable instructions`() {
        val graph = ClassFileIndexer().index(listOf(testClassesRoot))
        val consumer = JvmNodeId.methodId(
            "dev/kartograph/index/fixture/SignatureConsumer",
            "consume",
            "(Ldev/kartograph/index/fixture/SignatureOnly;)Ldev/kartograph/index/fixture/SignatureOnly;",
        )
        val signatureType = JvmNodeId.classId("dev/kartograph/index/fixture/SignatureOnly")

        assertTrue(graph.edges.any { edge ->
            edge.source == consumer && edge.target == signatureType && edge.kind == EdgeKind.REFERENCE
        })
    }

    @Test
    fun `uses InnerClasses access flags for nested class visibility`() {
        val graph = ClassFileIndexer().index(listOf(testClassesRoot))
        val privateNested = JvmNodeId.classId("dev/kartograph/index/fixture/NestedOwner\$PrivateNested")

        assertEquals(Visibility.PRIVATE, graph.node(privateNested)?.jvmVisibility)
    }

    @Test
    fun `records method handles referenced by invokedynamic call sites`() {
        val javaClassesRoot = Path.of(requireNotNull(JavaFixture::class.java.protectionDomain.codeSource).location.toURI())
        val graph = ClassFileIndexer().index(listOf(javaClassesRoot))
        val owner = "dev/kartograph/index/fixture/JavaFixture"
        val factory = JvmNodeId.methodId(owner, "methodReference", "()Ljava/lang/Runnable;")
        val target = JvmNodeId.methodId(owner, "hiddenStatic", "()V")

        assertTrue(graph.edges.any { edge ->
            edge.source == factory && edge.target == target && edge.kind == EdgeKind.CALL
        })
    }

    @Test
    fun `records constructed and class literal reference types`() {
        val javaClassesRoot = Path.of(requireNotNull(JavaFixture::class.java.protectionDomain.codeSource).location.toURI())
        val graph = ClassFileIndexer().index(listOf(javaClassesRoot))
        val owner = "dev/kartograph/index/fixture/JavaFixture"
        val expectedTargets = mapOf(
            "constructedType" to "dev/kartograph/index/fixture/ConstructedOnly",
            "classLiteral" to "dev/kartograph/index/fixture/LiteralOnly",
            "arrayClassLiteral" to "dev/kartograph/index/fixture/ArrayLiteralOnly",
        )

        expectedTargets.forEach { (methodName, targetName) ->
            val source = graph.nodeIds.single { nodeId ->
                nodeId.value.startsWith("method:$owner#$methodName(")
            }
            assertTrue(graph.edges.any { edge ->
                edge.source == source && edge.target == JvmNodeId.classId(targetName) && edge.kind == EdgeKind.REFERENCE
            })
        }
    }

    @Test
    fun `connects interface methods to project implementations`() {
        val javaClassesRoot = Path.of(requireNotNull(JavaFixture::class.java.protectionDomain.codeSource).location.toURI())
        val graph = ClassFileIndexer().index(listOf(javaClassesRoot))
        val contractMethod = JvmNodeId.methodId(
            "dev/kartograph/index/fixture/DispatchContract",
            "invoke",
            "()Ljava/lang/Object;",
        )
        val implementationMethod = JvmNodeId.methodId(
            "dev/kartograph/index/fixture/DispatchImplementation",
            "invoke",
            "()Ljava/lang/Object;",
        )

        assertTrue(graph.edges.any { edge ->
            edge.source == contractMethod && edge.target == implementationMethod && edge.kind == EdgeKind.OVERRIDE
        })
    }

    @Test
    fun `framework callback edges exclude private and static helpers`(@TempDir directory: Path) {
        directory.resolve("Callback.class").writeBytes(frameworkCallbackClass())

        val graph = ClassFileIndexer().index(listOf(directory))
        val owner = JvmNodeId.classId("dev/fixture/Callback")
        val targets = graph.outgoingEdgesFrom(owner)
            .filter { edge -> edge.kind == EdgeKind.REFERENCE }
            .map { edge -> edge.target }

        assertTrue(JvmNodeId.methodId("dev/fixture/Callback", "<init>", "()V") in targets)
        assertTrue(JvmNodeId.methodId("dev/fixture/Callback", "onPageFinished", "()V") in targets)
        assertFalse(JvmNodeId.methodId("dev/fixture/Callback", "helper", "()V") in targets)
        assertFalse(JvmNodeId.methodId("dev/fixture/Callback", "utility", "()V") in targets)
    }

    @Test
    fun `ASM visitor subclasses expose externally invoked callback members`(@TempDir directory: Path) {
        directory.resolve("AsmCallback.class").writeBytes(
            frameworkCallbackClass("dev/fixture/AsmCallback", "org/objectweb/asm/ClassVisitor", "visit"),
        )

        val graph = ClassFileIndexer().index(listOf(directory))
        val owner = JvmNodeId.classId("dev/fixture/AsmCallback")

        assertTrue(
            graph.outgoingEdgesFrom(owner).any { edge ->
                edge.kind == EdgeKind.REFERENCE &&
                    edge.target == JvmNodeId.methodId("dev/fixture/AsmCallback", "visit", "()V")
            },
        )
    }

    @Test
    fun `duplicate class roots do not double edge weights`() {
        val graph = ClassFileIndexer().index(listOf(testClassesRoot, testClassesRoot))
        val callerMethod = JvmNodeId.methodId(
            "dev/kartograph/index/fixture/Caller",
            "callTwice",
            "()Ljava/lang/String;",
        )
        val dependencyMethod = JvmNodeId.methodId(
            "dev/kartograph/index/fixture/Dependency",
            "touch",
            "()V",
        )

        val call = graph.edges.single {
            it.source == callerMethod && it.target == dependencyMethod && it.kind == EdgeKind.CALL
        }
        assertEquals(2, call.weight)
    }

    @Test
    fun `duplicate class names use facts from the first root`(@TempDir directory: Path) {
        val firstRoot = directory.resolve("first").createDirectories()
        val secondRoot = directory.resolve("second").createDirectories()
        firstRoot.resolve("Duplicate.class").writeBytes(duplicateClass("First.kt", Opcodes.ACC_FINAL))
        secondRoot.resolve("Duplicate.class").writeBytes(duplicateClass("Second.kt", Opcodes.ACC_ABSTRACT))

        val graph = ClassFileIndexer().index(listOf(firstRoot, secondRoot))
        val duplicate = graph.node(JvmNodeId.classId("dev/fixture/Duplicate"))

        assertEquals("First.kt", duplicate?.location?.path)
        assertEquals(setOf(JvmModifier.FINAL), duplicate?.jvmModifiers)
    }

    @Test
    fun `marks every class from an Android R jar as synthesized`(@TempDir directory: Path) {
        val resourceJar = directory.resolve("R.jar")
        JarOutputStream(Files.newOutputStream(resourceJar)).use { output ->
            output.putNextEntry(JarEntry("dev/fixture/R.class"))
            output.write(duplicateClass("R.java", Opcodes.ACC_FINAL, "dev/fixture/R"))
            output.closeEntry()
        }

        val graph = ClassFileIndexer().index(listOf(resourceJar))

        assertTrue(graph.node(JvmNodeId.classId("dev/fixture/R"))?.synthesized == true)
    }

    @Test
    fun `cached R jar facts apply current jar origin after rename`(@TempDir directory: Path) {
        val resourceJar = directory.resolve("R.jar")
        JarOutputStream(Files.newOutputStream(resourceJar)).use { output ->
            output.putNextEntry(JarEntry("dev/fixture/Widget.class"))
            output.write(duplicateClass("Widget.java", Opcodes.ACC_FINAL, "dev/fixture/Widget"))
            output.closeEntry()
        }
        val cache = ClassIndexCache(directory.resolve("cache"), "r-jar")
        val generated = ClassFileIndexer(cache).indexWithObservations(listOf(resourceJar))
        assertTrue(generated.graph.nodes.getValue(JvmNodeId.classId("dev/fixture/Widget")).synthesized)

        val ordinaryJar = directory.resolve("resources.jar")
        Files.move(resourceJar, ordinaryJar)
        val oracle = ClassFileIndexer().indexWithObservations(listOf(ordinaryJar))
        val warm = ClassFileIndexer(cache).indexWithObservations(listOf(ordinaryJar))
        assertEquals(1, warm.statistics.cacheHits)
        assertFalse(warm.graph.nodes.getValue(JvmNodeId.classId("dev/fixture/Widget")).synthesized)
        assertSameSemantics(oracle, warm)
    }

    @Test
    fun `marks Android generated class names as synthesized outside R jars`(@TempDir directory: Path) {
        val generatedNames = listOf("BuildConfig", "BR", "R", "R${'$'}string", "Manifest", "Manifest${'$'}permission")
        generatedNames.forEach { name ->
            directory.resolve("$name.class").writeBytes(
                duplicateClass("$name.java", Opcodes.ACC_FINAL, "dev/fixture/$name"),
            )
        }

        val graph = ClassFileIndexer().index(listOf(directory))

        assertTrue(generatedNames.all { name ->
            graph.node(JvmNodeId.classId("dev/fixture/$name"))?.synthesized == true
        })
    }

    @Test
    fun `does not classify user classes as generated from name suffix alone`(@TempDir directory: Path) {
        directory.resolve("Repository_Impl.class").writeBytes(
            duplicateClass("Repository_Impl.kt", Opcodes.ACC_FINAL, "dev/fixture/Repository_Impl"),
        )
        directory.resolve("ManualJsonAdapter.class").writeBytes(
            duplicateClass("ManualJsonAdapter.kt", Opcodes.ACC_FINAL, "dev/fixture/ManualJsonAdapter"),
        )

        val graph = ClassFileIndexer().index(listOf(directory))

        assertFalse(graph.nodes.getValue(JvmNodeId.classId("dev/fixture/Repository_Impl")).synthesized)
        assertFalse(graph.nodes.getValue(JvmNodeId.classId("dev/fixture/ManualJsonAdapter")).synthesized)
    }

    @Test
    fun `marks an exact Room sibling of an annotated database as synthesized`(@TempDir directory: Path) {
        directory.resolve("AppDatabase.class").writeBytes(
            annotatedClass(
                "AppDatabase.kt",
                "dev/fixture/AppDatabase",
                "Landroidx/room/Database;",
            ),
        )
        directory.resolve("AppDatabase_Impl.class").writeBytes(
            duplicateClass("AppDatabase_Impl.kt", Opcodes.ACC_FINAL, "dev/fixture/AppDatabase_Impl"),
        )

        val graph = ClassFileIndexer().index(listOf(directory))

        assertTrue(graph.nodes.getValue(JvmNodeId.classId("dev/fixture/AppDatabase_Impl")).synthesized)
    }

    @Test
    fun `missing class root is rejected without echoing its absolute path`(@TempDir directory: Path) {
        val missingRoot = directory.resolve("private-user-segment").resolve("missing")

        val error = assertFailsWith<ClassIndexingException> {
            ClassFileIndexer().index(listOf(missingRoot))
        }

        assertTrue(error.message.orEmpty().contains("class root does not exist"))
        assertFalse(error.message.orEmpty().contains(directory.toString()))
    }

    @Test
    fun `invalid class file fails instead of returning a partial graph`(@TempDir directory: Path) {
        directory.resolve("Broken.class").writeText("not bytecode")

        val error = assertFailsWith<ClassIndexingException> {
            ClassFileIndexer().index(listOf(directory))
        }

        assertTrue(error.message.orEmpty().contains("invalid class file"))
        assertFalse(error.message.orEmpty().contains(directory.toString()))
    }

    @Test
    fun `truncated class file fails through the sanitized indexing exception`(@TempDir directory: Path) {
        directory.resolve("Truncated.class").writeBytes(truncatedClass())

        val error = assertFailsWith<ClassIndexingException> {
            ClassFileIndexer().index(listOf(directory))
        }

        assertEquals("invalid class file", error.message)
        assertFalse(error.message.orEmpty().contains(directory.toString()))
    }

    @Test
    fun `truncated class inside a jar fails through the sanitized indexing exception`(@TempDir directory: Path) {
        val jar = directory.resolve("broken.jar")
        JarOutputStream(Files.newOutputStream(jar)).use { output ->
            output.putNextEntry(JarEntry("dev/fixture/Truncated.class"))
            output.write(truncatedClass())
            output.closeEntry()
        }

        val error = assertFailsWith<ClassIndexingException> {
            ClassFileIndexer().index(listOf(jar))
        }

        assertEquals("invalid class file in class JAR", error.message)
        assertFalse(error.message.orEmpty().contains(directory.toString()))
    }

    @Test
    fun `indexes declared caught and multidimensional array reference types`(@TempDir directory: Path) {
        directory.resolve("InstructionReferences.class").writeBytes(classWithInstructionReferences())
        listOf("DeclaredException", "CaughtOnly", "ArrayOnly").forEach { name ->
            directory.resolve("$name.class").writeBytes(
                duplicateClass("$name.java", Opcodes.ACC_FINAL, "dev/fixture/$name"),
            )
        }

        val graph = ClassFileIndexer().index(listOf(directory))
        val source = JvmNodeId.methodId("dev/fixture/InstructionReferences", "inspect", "()V")

        val expectedTargets = setOf(
            JvmNodeId.classId("dev/fixture/DeclaredException"),
            JvmNodeId.classId("dev/fixture/CaughtOnly"),
            JvmNodeId.classId("dev/fixture/ArrayOnly"),
            JvmNodeId.classId("dev/fixture/InstructionReferences"),
        )
        val actualTargets = graph.edges.filter { edge -> edge.source == source && edge.kind == EdgeKind.REFERENCE }
            .mapTo(mutableSetOf()) { edge -> edge.target }

        assertEquals(expectedTargets, actualTargets)
    }

    @Test
    fun `class references in annotation values become reference edges`(@TempDir directory: Path) {
        directory.resolve("Annotated.class").writeBytes(classWithAnnotationValues())
        directory.resolve("Target.class").writeBytes(
            duplicateClass("Target.java", Opcodes.ACC_PUBLIC, "dev/fixture/Target"),
        )
        directory.resolve("ArrayTarget.class").writeBytes(
            duplicateClass("ArrayTarget.java", Opcodes.ACC_PUBLIC, "dev/fixture/ArrayTarget"),
        )
        directory.resolve("Nested.class").writeBytes(
            duplicateClass("Nested.java", Opcodes.ACC_PUBLIC, "dev/fixture/Nested"),
        )

        val graph = ClassFileIndexer().index(listOf(directory))
        val annotated = JvmNodeId.classId("dev/fixture/Annotated")
        val referencedTargets = graph.outgoingEdgesFrom(annotated)
            .filter { edge -> edge.kind == EdgeKind.REFERENCE }
            .map { edge -> edge.target }
            .toSet()

        assertTrue(JvmNodeId.classId("dev/fixture/Target") in referencedTargets)
        assertTrue(JvmNodeId.classId("dev/fixture/ArrayTarget") in referencedTargets)
        assertTrue(JvmNodeId.classId("dev/fixture/Nested") in referencedTargets)
    }

    @Test
    fun `class references in method and parameter annotations become reference edges`(@TempDir directory: Path) {
        directory.resolve("Holder.class").writeBytes(classWithMethodAnnotationValues())
        directory.resolve("MethodTarget.class").writeBytes(
            duplicateClass("MethodTarget.java", Opcodes.ACC_PUBLIC, "dev/fixture/MethodTarget"),
        )
        directory.resolve("ParameterTarget.class").writeBytes(
            duplicateClass("ParameterTarget.java", Opcodes.ACC_PUBLIC, "dev/fixture/ParameterTarget"),
        )

        val graph = ClassFileIndexer().index(listOf(directory))
        val method = JvmNodeId.methodId("dev/fixture/Holder", "annotated", "(Ljava/lang/Object;)V")
        val referencedTargets = graph.outgoingEdgesFrom(method)
            .filter { edge -> edge.kind == EdgeKind.REFERENCE }
            .map { edge -> edge.target }
            .toSet()

        assertTrue(JvmNodeId.classId("dev/fixture/MethodTarget") in referencedTargets)
        assertTrue(JvmNodeId.classId("dev/fixture/ParameterTarget") in referencedTargets)
    }

    @Test
    fun `enum constants in annotation values reference the enum class`(@TempDir directory: Path) {
        directory.resolve("EnumAnnotated.class").writeBytes(classWithEnumAnnotationValue())
        directory.resolve("Mode.class").writeBytes(
            duplicateClass("Mode.java", Opcodes.ACC_PUBLIC, "dev/fixture/Mode"),
        )

        val graph = ClassFileIndexer().index(listOf(directory))
        val annotated = JvmNodeId.classId("dev/fixture/EnumAnnotated")

        assertTrue(graph.outgoingEdgesFrom(annotated).any { edge ->
            edge.kind == EdgeKind.REFERENCE && edge.target == JvmNodeId.classId("dev/fixture/Mode")
        })
    }

    @Test
    fun `nested classes reference their enclosing container from real compiler output`() {
        val graph = ClassFileIndexer().index(listOf(testClassesRoot))

        assertTrue(graph.edges.any { edge ->
            edge.source == JvmNodeId.classId("dev/kartograph/index/fixture/NestedOwner\$PrivateNested") &&
                edge.target == JvmNodeId.classId("dev/kartograph/index/fixture/NestedOwner") &&
                edge.kind == EdgeKind.REFERENCE
        })
    }

    @Test
    fun `dollar names without enclosing facts get no container edge`(@TempDir directory: Path) {
        directory.resolve("Outer.class").writeBytes(
            duplicateClass("Outer.java", Opcodes.ACC_PUBLIC, "dev/fixture/Outer"),
        )
        directory.resolve("Lookalike.class").writeBytes(
            duplicateClass("Lookalike.java", Opcodes.ACC_PUBLIC, "dev/fixture/Outer\$Lookalike"),
        )

        val graph = ClassFileIndexer().index(listOf(directory))

        assertFalse(graph.edges.any { edge ->
            edge.source == JvmNodeId.classId("dev/fixture/Outer\$Lookalike") &&
                edge.target == JvmNodeId.classId("dev/fixture/Outer")
        })
    }

    @Test
    fun `constant Class forName literals become reference edges`(@TempDir directory: Path) {
        directory.resolve("Lookup.class").writeBytes(classWithReflectionLookups())
        directory.resolve("LiteralTarget.class").writeBytes(
            duplicateClass("LiteralTarget.java", Opcodes.ACC_PUBLIC, "dev/fixture/LiteralTarget"),
        )
        directory.resolve("ArrayLiteralTarget.class").writeBytes(
            duplicateClass("ArrayLiteralTarget.java", Opcodes.ACC_PUBLIC, "dev/fixture/ArrayLiteralTarget"),
        )

        val graph = ClassFileIndexer().index(listOf(directory))
        val constant = JvmNodeId.methodId("dev/fixture/Lookup", "constant", "()V")
        val arrayConstant = JvmNodeId.methodId("dev/fixture/Lookup", "arrayConstant", "()V")
        val indirect = JvmNodeId.methodId("dev/fixture/Lookup", "indirect", "(Ljava/lang/String;)V")

        assertTrue(graph.edges.any { edge ->
            edge.source == constant && edge.kind == EdgeKind.REFERENCE &&
                edge.target == JvmNodeId.classId("dev/fixture/LiteralTarget")
        })
        assertTrue(graph.edges.any { edge ->
            edge.source == arrayConstant && edge.kind == EdgeKind.REFERENCE &&
                edge.target == JvmNodeId.classId("dev/fixture/ArrayLiteralTarget")
        })
        assertFalse(graph.edges.any { edge ->
            edge.source == indirect && edge.kind == EdgeKind.REFERENCE &&
                edge.target == JvmNodeId.classId("dev/fixture/LiteralTarget")
        })
    }

    @Test
    fun `reduces a source file attribute that carries a path to its file name`(@TempDir directory: Path) {
        // SourceFile attribute는 임의 문자열이라 후처리 도구가 절대경로를 남길 수 있다.
        directory.resolve("Absolute.class").writeBytes(
            duplicateClass("/Users/someone/private/Absolute.java", 0, "dev/fixture/Absolute"),
        )
        directory.resolve("Windows.class").writeBytes(
            duplicateClass("""C:\\agent\\work\\Windows.java""", 0, "dev/fixture/Windows"),
        )
        directory.resolve("Nameless.class").writeBytes(duplicateClass("/", 0, "dev/fixture/Nameless"))

        val graph = ClassFileIndexer().index(listOf(directory))

        assertEquals("Absolute.java", graph.node(NodeId("class:dev/fixture/Absolute"))?.location?.path)
        assertEquals("Windows.java", graph.node(NodeId("class:dev/fixture/Windows"))?.location?.path)
        // 남는 파일 이름이 없으면 위치를 만들지 않는다.
        assertNull(graph.node(NodeId("class:dev/fixture/Nameless"))?.location)
    }

    private val testClassesRoot: Path
        get() = Path.of(requireNotNull(Caller::class.java.protectionDomain.codeSource).location.toURI())

    private fun assertSameSemantics(expected: IndexedClasses, actual: IndexedClasses) {
        assertEquals(expected.graph.nodes, actual.graph.nodes)
        assertEquals(expected.graph.edges, actual.graph.edges)
        assertEquals(expected.graph.externalCalls, actual.graph.externalCalls)
        assertEquals(expected.graph.serviceProviders, actual.graph.serviceProviders)
        assertEquals(expected.observations, actual.observations)
        assertEquals(expected.declarationsByRoot, actual.declarationsByRoot)
        assertEquals(expected.selectedRootByNode, actual.selectedRootByNode)
    }

    private fun duplicateClass(
        sourceFile: String,
        modifier: Int,
        internalName: String = "dev/fixture/Duplicate",
    ): ByteArray = ClassWriter(0).apply {
        visit(Opcodes.V17, Opcodes.ACC_PUBLIC or modifier, internalName, null, "java/lang/Object", null)
        visitSource(sourceFile, null)
        visitEnd()
    }.toByteArray()

    private fun classWithSuper(internalName: String, superName: String): ByteArray = ClassWriter(0).apply {
        visit(Opcodes.V17, Opcodes.ACC_PUBLIC, internalName, null, superName, null)
        visitEnd()
    }.toByteArray()

    private fun runtimeBodyShapes(): ByteArray = ClassWriter(ClassWriter.COMPUTE_MAXS).apply {
        visit(Opcodes.V17, Opcodes.ACC_PUBLIC, "dev/fixture/Shapes", null, "java/lang/Object", null)
        visitMethod(Opcodes.ACC_PUBLIC or Opcodes.ACC_STATIC, "inspect", "(I)V", null, null).apply {
            val zero = Label(); val one = Label(); val afterSwitch = Label(); val handler = Label(); val end = Label()
            visitCode()
            visitVarInsn(Opcodes.ILOAD, 0)
            visitTableSwitchInsn(0, 1, afterSwitch, zero, one)
            visitLabel(zero); visitInsn(Opcodes.ICONST_0); visitVarInsn(Opcodes.ISTORE, 300); visitJumpInsn(Opcodes.GOTO, afterSwitch)
            visitLabel(one); visitInsn(Opcodes.ICONST_1); visitVarInsn(Opcodes.ISTORE, 300)
            visitLabel(afterSwitch)
            visitTryCatchBlock(afterSwitch, end, handler, "java/lang/Exception")
            visitLdcInsn(ConstantDynamic("constant", "Ljava/lang/Object;", Handle(Opcodes.H_INVOKESTATIC,
                "java/lang/invoke/ConstantBootstraps", "nullConstant",
                "(Ljava/lang/invoke/MethodHandles\$Lookup;Ljava/lang/String;Ljava/lang/Class;)Ljava/lang/Object;", false)))
            visitInsn(Opcodes.POP)
            visitVarInsn(Opcodes.ILOAD, 300)
            visitInvokeDynamicInsn("makeConcatWithConstants", "(I)Ljava/lang/String;", Handle(Opcodes.H_INVOKESTATIC,
                "java/lang/invoke/StringConcatFactory", "makeConcatWithConstants",
                "(Ljava/lang/invoke/MethodHandles\$Lookup;Ljava/lang/String;Ljava/lang/invoke/MethodType;Ljava/lang/String;[Ljava/lang/Object;)Ljava/lang/invoke/CallSite;", false), "value=\u0001")
            visitInsn(Opcodes.POP)
            visitLdcInsn("java.lang.String")
            visitMethodInsn(Opcodes.INVOKESTATIC, "java/lang/Class", "forName", "(Ljava/lang/String;)Ljava/lang/Class;", false)
            visitInsn(Opcodes.POP)
            visitMethodInsn(Opcodes.INVOKESTATIC, "dev/fixture/Shapes", "recursive", "()V", false)
            visitLabel(end); visitInsn(Opcodes.RETURN)
            visitLabel(handler); visitInsn(Opcodes.POP); visitInsn(Opcodes.RETURN)
            visitMaxs(0, 0); visitEnd()
        }
        visitMethod(Opcodes.ACC_PRIVATE or Opcodes.ACC_STATIC or Opcodes.ACC_SYNCHRONIZED, "recursive", "()V", null, null).apply {
            visitCode(); visitInsn(Opcodes.RETURN); visitMaxs(0, 0); visitEnd()
        }
        visitEnd()
    }.toByteArray()

    private fun annotatedClass(sourceFile: String, internalName: String, annotationDescriptor: String): ByteArray =
        ClassWriter(0).apply {
            visit(Opcodes.V17, Opcodes.ACC_PUBLIC, internalName, null, "java/lang/Object", null)
            visitSource(sourceFile, null)
            visitAnnotation(annotationDescriptor, false).visitEnd()
            visitEnd()
        }.toByteArray()

    private fun truncatedClass(): ByteArray = byteArrayOf(
        0xCA.toByte(), 0xFE.toByte(), 0xBA.toByte(), 0xBE.toByte(),
        0x00, 0x00, 0x00, 0x3D, 0x00,
    )

    private fun classWithInstructionReferences(): ByteArray = ClassWriter(0).apply {
        visit(Opcodes.V17, Opcodes.ACC_PUBLIC, "dev/fixture/InstructionReferences", null, "java/lang/Object", null)
        visitSource("InstructionReferences.java", null)
        visitMethod(
            Opcodes.ACC_PUBLIC or Opcodes.ACC_STATIC,
            "inspect",
            "()V",
            null,
            arrayOf("dev/fixture/DeclaredException"),
        ).apply {
            val start = Label()
            val end = Label()
            val handler = Label()
            val done = Label()
            visitCode()
            visitTryCatchBlock(start, end, handler, "dev/fixture/CaughtOnly")
            visitLabel(start)
            visitInsn(Opcodes.NOP)
            visitLabel(end)
            visitJumpInsn(Opcodes.GOTO, done)
            visitLabel(handler)
            visitInsn(Opcodes.POP)
            visitLabel(done)
            visitInsn(Opcodes.ICONST_1)
            visitInsn(Opcodes.ICONST_1)
            visitMultiANewArrayInsn("[[Ldev/fixture/ArrayOnly;", 2)
            visitInsn(Opcodes.POP)
            visitInsn(Opcodes.RETURN)
            visitMaxs(2, 0)
            visitEnd()
        }
        visitEnd()
    }.toByteArray()

    private fun classWithLineZero(): ByteArray = ClassWriter(0).apply {
        visit(Opcodes.V17, Opcodes.ACC_PUBLIC, "dev/fixture/SyntheticCoroutine", null, "java/lang/Object", null)
        visitSource("SyntheticCoroutine.kt", null)
        visitMethod(Opcodes.ACC_PUBLIC, "resume", "()V", null, null).apply {
            val start = Label()
            visitCode()
            visitLabel(start)
            visitLineNumber(0, start)
            visitInsn(Opcodes.RETURN)
            visitMaxs(0, 1)
            visitEnd()
        }
        visitEnd()
    }.toByteArray()

    private fun frameworkCallbackClass(
        internalName: String = "dev/fixture/Callback",
        superName: String = "android/webkit/WebViewClient",
        callbackName: String = "onPageFinished",
    ): ByteArray = ClassWriter(0).apply {
        visit(Opcodes.V17, Opcodes.ACC_PUBLIC, internalName, null, superName, null)
        visitMethod(Opcodes.ACC_PUBLIC, "<init>", "()V", null, null).visitEnd()
        visitMethod(Opcodes.ACC_PUBLIC, callbackName, "()V", null, null).visitEnd()
        visitMethod(Opcodes.ACC_PRIVATE, "helper", "()V", null, null).visitEnd()
        visitMethod(Opcodes.ACC_PUBLIC or Opcodes.ACC_STATIC, "utility", "()V", null, null).visitEnd()
        visitEnd()
    }.toByteArray()

    private fun classWithAnnotationValues(): ByteArray = ClassWriter(0).apply {
        visit(Opcodes.V17, Opcodes.ACC_PUBLIC, "dev/fixture/Annotated", null, "java/lang/Object", null)
        visitSource("Annotated.java", null)
        visitAnnotation("Ldev/fixture/Marker;", true).apply {
            visit("single", Type.getObjectType("dev/fixture/Target"))
            visitArray("many").apply {
                visit(null, Type.getObjectType("dev/fixture/ArrayTarget"))
                visitEnd()
            }
            visitAnnotation("nested", "Ldev/fixture/Marker;").apply {
                visit("value", Type.getObjectType("dev/fixture/Nested"))
                visitEnd()
            }
            visitEnd()
        }
        visitEnd()
    }.toByteArray()

    private fun classWithMethodAnnotationValues(): ByteArray = ClassWriter(0).apply {
        visit(Opcodes.V17, Opcodes.ACC_PUBLIC, "dev/fixture/Holder", null, "java/lang/Object", null)
        visitSource("Holder.java", null)
        visitMethod(Opcodes.ACC_PUBLIC, "annotated", "(Ljava/lang/Object;)V", null, null).apply {
            visitAnnotation("Ldev/fixture/Marker;", true).apply {
                visit("value", Type.getObjectType("dev/fixture/MethodTarget"))
                visitEnd()
            }
            visitParameterAnnotation(0, "Ldev/fixture/Marker;", true).apply {
                visit("value", Type.getObjectType("dev/fixture/ParameterTarget"))
                visitEnd()
            }
            visitEnd()
        }
        visitEnd()
    }.toByteArray()
    private fun classWithEnumAnnotationValue(): ByteArray = ClassWriter(0).apply {
        visit(Opcodes.V17, Opcodes.ACC_PUBLIC, "dev/fixture/EnumAnnotated", null, "java/lang/Object", null)
        visitSource("EnumAnnotated.java", null)
        visitAnnotation("Ldev/fixture/Marker;", true).apply {
            visitEnum("mode", "Ldev/fixture/Mode;", "FAST")
            visitEnd()
        }
        visitEnd()
    }.toByteArray()

    private fun classWithReflectionLookups(): ByteArray = ClassWriter(0).apply {
        visit(Opcodes.V17, Opcodes.ACC_PUBLIC, "dev/fixture/Lookup", null, "java/lang/Object", null)
        visitSource("Lookup.java", null)
        visitMethod(Opcodes.ACC_PUBLIC or Opcodes.ACC_STATIC, "constant", "()V", null, null).apply {
            visitCode()
            visitLdcInsn("dev.fixture.LiteralTarget")
            visitMethodInsn(
                Opcodes.INVOKESTATIC, "java/lang/Class", "forName",
                "(Ljava/lang/String;)Ljava/lang/Class;", false,
            )
            visitInsn(Opcodes.POP)
            visitInsn(Opcodes.RETURN)
            visitMaxs(1, 0)
            visitEnd()
        }
        visitMethod(Opcodes.ACC_PUBLIC or Opcodes.ACC_STATIC, "arrayConstant", "()V", null, null).apply {
            visitCode()
            visitLdcInsn("[Ldev.fixture.ArrayLiteralTarget;")
            visitMethodInsn(
                Opcodes.INVOKESTATIC, "java/lang/Class", "forName",
                "(Ljava/lang/String;)Ljava/lang/Class;", false,
            )
            visitInsn(Opcodes.POP)
            visitInsn(Opcodes.RETURN)
            visitMaxs(1, 0)
            visitEnd()
        }
        visitMethod(
            Opcodes.ACC_PUBLIC or Opcodes.ACC_STATIC, "indirect", "(Ljava/lang/String;)V", null, null,
        ).apply {
            visitCode()
            visitVarInsn(Opcodes.ALOAD, 0)
            visitMethodInsn(
                Opcodes.INVOKESTATIC, "java/lang/Class", "forName",
                "(Ljava/lang/String;)Ljava/lang/Class;", false,
            )
            visitInsn(Opcodes.POP)
            visitInsn(Opcodes.RETURN)
            visitMaxs(1, 1)
            visitEnd()
        }
        visitEnd()
    }.toByteArray()
}
