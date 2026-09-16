package dev.kartograph.index

import dev.kartograph.core.ClassHierarchy
import dev.kartograph.core.HierarchyMethod
import dev.kartograph.core.Visibility
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.channels.FileChannel
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.nio.file.StandardOpenOption
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger
import java.util.jar.Attributes
import java.util.jar.JarEntry
import java.util.jar.JarOutputStream
import java.util.jar.Manifest
import kotlin.io.path.createDirectories
import kotlin.io.path.deleteExisting
import kotlin.io.path.fileSize
import kotlin.io.path.writeBytes
import kotlin.io.path.writeText
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertSame
import kotlin.test.assertTrue
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.io.TempDir
import org.objectweb.asm.ClassWriter
import org.objectweb.asm.Opcodes

class HierarchyIndexCacheTest {
    @Test
    fun `cold and warm cache preserve parents methods and annotation headers`(@TempDir root: Path) {
        val jar = root.resolve("dependency.jar")
        writeJar(
            jar,
            "example/Parent.class" to classBytes("example/Parent"),
            "example/Child.class" to classBytes(
                "example/Child",
                superName = "example/Parent",
                methods = listOf(MethodSpec("work", "()V", Opcodes.ACC_PROTECTED or Opcodes.ACC_FINAL)),
            ),
            "example/Marker.class" to annotationBytes("example/Marker", "example/Meta"),
        )
        val expected = observe(ClassHierarchyIndexer().index(listOf(jar)))
        val cache = ClassIndexCache(root.resolve("cache"), "engine-a")

        val coldIndexer = ClassHierarchyIndexer(cache)
        val cold = observe(coldIndexer.index(listOf(jar)))
        val warmIndexer = ClassHierarchyIndexer(cache)
        val warm = observe(warmIndexer.index(listOf(jar)))

        assertEquals(expected, cold)
        assertEquals(expected, warm)
        assertEquals(HierarchyIndexingStatistics(hierarchyJars = 1, hierarchyParsedJars = 1), coldIndexer.statistics)
        assertEquals(HierarchyIndexingStatistics(hierarchyJars = 1, hierarchyCacheHits = 1), warmIndexer.statistics)
        assertEquals(
            listOf(HierarchyMethod("work", "()V", Visibility.PROTECTED, isFinal = true)),
            warm.methods,
        )
        assertEquals(setOf("example/Meta"), warm.annotations)
    }

    @Test
    fun `cached headers preserve isolated UTF16 code units in JVM strings`(@TempDir root: Path) {
        val internalName = "example/Surrogate\uD800"
        val methodName = "work\uDC00"
        val descriptor = "(Lexample/Argument\uD800;)V"
        val jar = root.resolve("dependency.jar")
        writeJar(
            jar,
            "example/Surrogate.class" to classBytes(
                internalName,
                methods = listOf(MethodSpec(methodName, descriptor, Opcodes.ACC_PUBLIC)),
            ),
        )
        val cache = ClassIndexCache(root.resolve("cache"), "engine-a")

        val cold = ClassHierarchyIndexer(cache).index(listOf(jar))
        val warmIndexer = ClassHierarchyIndexer(cache)
        val warm = warmIndexer.index(listOf(jar))

        assertEquals(emptySet(), cold.directSupertypesOf(internalName))
        assertEquals(cold.directSupertypesOf(internalName), warm.directSupertypesOf(internalName))
        assertEquals(cold.declaredMethodsOf(internalName), warm.declaredMethodsOf(internalName))
        assertEquals(methodName, warm.declaredMethodsOf(internalName)?.single()?.name)
        assertEquals(descriptor, warm.declaredMethodsOf(internalName)?.single()?.descriptor)
        assertEquals(1, warmIndexer.statistics.hierarchyCacheHits)
    }

    @Test
    fun `same mtime jar replacement uses its current bytes`(@TempDir root: Path) {
        val jar = root.resolve("dependency.jar")
        writeJar(jar, "example/Child.class" to classBytes("example/Child", "example/First"))
        val modified = Files.getLastModifiedTime(jar)
        val cache = ClassIndexCache(root.resolve("cache"), "engine-a")
        ClassHierarchyIndexer(cache).index(listOf(jar))

        writeJar(jar, "example/Child.class" to classBytes("example/Child", "example/Second"))
        Files.setLastModifiedTime(jar, modified)
        val changedIndexer = ClassHierarchyIndexer(cache)
        val changed = changedIndexer.index(listOf(jar))

        assertEquals(setOf("example/Second"), changed.directSupertypesOf("example/Child"))
        assertEquals(0, changedIndexer.statistics.hierarchyCacheHits)
        assertEquals(1, changedIndexer.statistics.hierarchyParsedJars)
        val warmIndexer = ClassHierarchyIndexer(cache)
        assertEquals(setOf("example/Second"), warmIndexer.index(listOf(jar)).directSupertypesOf("example/Child"))
        assertEquals(1, warmIndexer.statistics.hierarchyCacheHits)
    }

    @Test
    fun `class file indexer shares the cache and publishes hierarchy counters`(@TempDir root: Path) {
        val classes = root.resolve("classes")
        val applicationClass = classes.resolve("example/App.class")
        applicationClass.parent.createDirectories()
        applicationClass.writeBytes(classBytes("example/App", "example/Dependency"))
        val jar = root.resolve("dependency.jar")
        writeJar(jar, "example/Dependency.class" to classBytes("example/Dependency"))
        val cache = ClassIndexCache(root.resolve("cache"), "engine-a")

        val cold = ClassFileIndexer(cache).indexWithObservations(listOf(classes), listOf(jar))
        val warm = ClassFileIndexer(cache).indexWithObservations(listOf(classes), listOf(jar))

        assertEquals(1, cold.statistics.hierarchyJars)
        assertEquals(1, cold.statistics.hierarchyParsedJars)
        assertEquals(0, cold.statistics.hierarchyCacheHits)
        assertEquals(1, warm.statistics.hierarchyJars)
        assertEquals(0, warm.statistics.hierarchyParsedJars)
        assertEquals(1, warm.statistics.hierarchyCacheHits)
        assertEquals(emptySet(), warm.hierarchy.directSupertypesOf("example/Dependency"))
    }

    @Test
    fun `cache keeps current classpath order and duplicate selection after deletion`(@TempDir root: Path) {
        val first = root.resolve("first.jar")
        val second = root.resolve("second.jar")
        writeJar(first, "example/Duplicate.class" to classBytes("example/Duplicate", "example/First"))
        writeJar(second, "example/Duplicate.class" to classBytes("example/Duplicate", "example/Second"))
        val cache = ClassIndexCache(root.resolve("cache"), "engine-a")
        ClassHierarchyIndexer(cache).index(listOf(first, second))

        val reversed = ClassHierarchyIndexer(cache)
        assertEquals(
            setOf("example/Second"),
            reversed.index(listOf(second, first)).directSupertypesOf("example/Duplicate"),
        )
        assertEquals(2, reversed.statistics.hierarchyCacheHits)

        first.deleteExisting()
        val afterDeletion = ClassHierarchyIndexer(cache)
        assertEquals(
            setOf("example/Second"),
            afterDeletion.index(listOf(second)).directSupertypesOf("example/Duplicate"),
        )
        assertEquals(1, afterDeletion.statistics.hierarchyJars)
        assertEquals(1, afterDeletion.statistics.hierarchyCacheHits)
    }

    @Test
    fun `duplicate path observations are assigned on the leader before parallel work`(@TempDir root: Path) {
        val jar = root.resolve("dependency.jar")
        writeJar(jar, "example/Duplicate.class" to classBytes("example/Duplicate", "example/First"))
        val first = Files.readAllBytes(jar)
        writeJar(jar, "example/Duplicate.class" to classBytes("example/Duplicate", "example/Second"))
        val second = Files.readAllBytes(jar)
        val spoolDirectory = root.resolve("spools").createDirectories()
        val completed = AtomicInteger()
        val observations = ArrayDeque<ObservedJarInput>().apply {
            addLast(ObservedJarInput(sha256(first), spool(spoolDirectory, first)) { ready -> if (ready) completed.incrementAndGet() })
            addLast(ObservedJarInput(sha256(second), spool(spoolDirectory, second)) { ready -> if (ready) completed.incrementAndGet() })
        }
        val leader = Thread.currentThread()
        val lookup = ObservedJarDigestLookup {
            assertSame(leader, Thread.currentThread())
            observations.removeFirst()
        }
        val indexer = ClassHierarchyIndexer(ClassIndexCache(root.resolve("cache"), "engine-a"), lookup)

        val hierarchy = indexer.index(listOf(jar, jar))

        assertEquals(setOf("example/First"), hierarchy.directSupertypesOf("example/Duplicate"))
        assertEquals(2, completed.get())
        assertEquals(2, indexer.statistics.hierarchyParsedJars)
        assertTrue(observations.isEmpty())
        assertEquals(0, Files.list(spoolDirectory).use { it.count() })
    }

    @Test
    fun `multi release selection and ignored resources are unchanged on hits`(@TempDir root: Path) {
        val runtime = Runtime.version().feature()
        val versioned = root.resolve("versioned.jar")
        writeJar(
            versioned,
            "example/Versioned.class" to classBytes("example/Versioned", "example/Base"),
            "META-INF/versions/9/example/Versioned.class" to classBytes("example/Versioned", "example/Nine"),
            "META-INF/versions/$runtime/example/Versioned.class" to classBytes("example/Versioned", "example/Runtime"),
            "META-INF/versions/${runtime + 1}/example/Versioned.class" to classBytes("example/Versioned", "example/Future"),
            "META-INF/versions/$runtime/example/Only.class" to classBytes("example/Only", "example/OnlyBase"),
            "META-INF/data.txt" to byteArrayOf(1, 2, 3),
            multiRelease = true,
            manifestLast = true,
        )
        val ordinary = root.resolve("ordinary.jar")
        writeJar(
            ordinary,
            "example/Versioned.class" to classBytes("example/Versioned", "example/Base"),
            "META-INF/versions/$runtime/example/Versioned.class" to classBytes("example/Versioned", "example/Runtime"),
            "META-INF/versions/$runtime/example/Only.class" to classBytes("example/Only", "example/OnlyBase"),
            multiRelease = false,
        )
        val cache = ClassIndexCache(root.resolve("cache"), "engine-a")

        ClassHierarchyIndexer(cache).index(listOf(versioned, ordinary))
        val versionedWarm = ClassHierarchyIndexer(cache)
        val selected = versionedWarm.index(listOf(versioned))
        val ordinaryWarm = ClassHierarchyIndexer(cache)
        val base = ordinaryWarm.index(listOf(ordinary))

        assertEquals(setOf("example/Runtime"), selected.directSupertypesOf("example/Versioned"))
        assertEquals(setOf("example/OnlyBase"), selected.directSupertypesOf("example/Only"))
        assertEquals(setOf("example/Base"), base.directSupertypesOf("example/Versioned"))
        assertEquals(null, base.directSupertypesOf("example/Only"))
        assertEquals(1, versionedWarm.statistics.hierarchyCacheHits)
        assertEquals(1, ordinaryWarm.statistics.hierarchyCacheHits)
    }

    @Test
    fun `invalid truncated and oversized entries reparse authoritatively`(@TempDir root: Path) {
        val jar = root.resolve("dependency.jar")
        writeJar(jar, "example/Child.class" to classBytes("example/Child", "example/Base"))
        val cache = ClassIndexCache(root.resolve("cache"), "engine-a")
        ClassHierarchyIndexer(cache).index(listOf(jar))
        val entry = onlyCacheEntry(cache.directory)

        entry.writeBytes(byteArrayOf(0x01, 0x02, 0x03))
        assertInvalidReparse(cache, jar)

        ClassHierarchyIndexer(cache).index(listOf(jar))
        val valid = Files.readAllBytes(entry)
        entry.writeBytes(valid.copyOf(valid.size / 2))
        assertInvalidReparse(cache, jar)

        FileChannel.open(entry, StandardOpenOption.WRITE, StandardOpenOption.TRUNCATE_EXISTING).use { channel ->
            channel.position(HierarchyIndexCache.MAX_ENTRY_BYTES.toLong())
            channel.write(ByteBuffer.wrap(byteArrayOf(0)))
        }
        assertInvalidReparse(cache, jar)
    }

    @Test
    fun `cache entry symlink is not followed`(@TempDir root: Path) {
        val jar = root.resolve("dependency.jar")
        writeJar(jar, "example/Child.class" to classBytes("example/Child", "example/Base"))
        val cache = ClassIndexCache(root.resolve("cache"), "engine-a")
        ClassHierarchyIndexer(cache).index(listOf(jar))
        val entry = onlyCacheEntry(cache.directory)
        val target = cache.directory.resolve("target.bin")
        Files.move(entry, target, StandardCopyOption.REPLACE_EXISTING)
        try {
            Files.createSymbolicLink(entry, target.fileName)
        } catch (_: UnsupportedOperationException) {
            assumeTrue(false, "symbolic links are unavailable")
        } catch (_: java.io.IOException) {
            assumeTrue(false, "symbolic links are unavailable")
        }

        val indexer = ClassHierarchyIndexer(cache)
        val hierarchy = indexer.index(listOf(jar))

        assertEquals(setOf("example/Base"), hierarchy.directSupertypesOf("example/Child"))
        assertEquals(0, indexer.statistics.hierarchyCacheHits)
        assertEquals(1, indexer.statistics.hierarchyParsedJars)
        assertTrue(Files.isSymbolicLink(target).not())
    }

    @Test
    fun `concurrent writers publish one decodable entry`(@TempDir root: Path) {
        val jar = root.resolve("dependency.jar")
        writeJar(jar, "example/Child.class" to classBytes("example/Child", "example/Base"))
        val cache = ClassIndexCache(root.resolve("cache"), "engine-a")
        val executor = Executors.newFixedThreadPool(4)
        try {
            val results = (1..8).map {
                executor.submit<ClassHierarchy> { ClassHierarchyIndexer(cache).index(listOf(jar)) }
            }.map { it.get() }
            results.forEach { hierarchy ->
                assertEquals(setOf("example/Base"), hierarchy.directSupertypesOf("example/Child"))
            }
        } finally {
            executor.shutdownNow()
        }

        val warm = ClassHierarchyIndexer(cache)
        assertEquals(setOf("example/Base"), warm.index(listOf(jar)).directSupertypesOf("example/Child"))
        assertEquals(1, warm.statistics.hierarchyCacheHits)
    }

    @Test
    fun `engine identity changes do not reuse an older entry`(@TempDir root: Path) {
        val jar = root.resolve("dependency.jar")
        writeJar(jar, "example/Child.class" to classBytes("example/Child", "example/Base"))
        ClassHierarchyIndexer(ClassIndexCache(root.resolve("cache"), "engine-a")).index(listOf(jar))

        val changed = ClassHierarchyIndexer(ClassIndexCache(root.resolve("cache"), "engine-b"))
        changed.index(listOf(jar))
        val warm = ClassHierarchyIndexer(ClassIndexCache(root.resolve("cache"), "engine-b"))
        warm.index(listOf(jar))

        assertEquals(0, changed.statistics.hierarchyCacheHits)
        assertEquals(1, changed.statistics.hierarchyParsedJars)
        assertEquals(1, warm.statistics.hierarchyCacheHits)
        assertEquals(2, Files.list(root.resolve("cache")).use { files ->
            files.filter { it.fileName.toString().endsWith(".hix") }.count()
        })
    }

    @Test
    fun `oversized snapshot and unavailable cache parse without storing facts`(@TempDir root: Path) {
        val jar = root.resolve("dependency.jar")
        writeJar(jar, "example/Child.class" to classBytes("example/Child", "example/Base"))
        val oversizedCache = ClassIndexCache(root.resolve("oversized-cache"), "engine-a")
        val oversized = ClassHierarchyIndexer(oversizedCache, jar.fileSize().toInt() - 1)
        assertEquals(setOf("example/Base"), oversized.index(listOf(jar)).directSupertypesOf("example/Child"))
        assertEquals(1, oversized.statistics.hierarchyUnavailableEntries)
        assertFalse(Files.exists(oversizedCache.directory))

        val unavailable = ClassHierarchyIndexer(ClassIndexCache(root.resolve("unavailable-cache"), null))
        assertEquals(setOf("example/Base"), unavailable.index(listOf(jar)).directSupertypesOf("example/Child"))
        assertEquals(1, unavailable.statistics.hierarchyUnavailableEntries)
        assertEquals(1, unavailable.statistics.hierarchyParsedJars)
    }

    @Test
    fun `unwritable cache falls back and records the write failure`(@TempDir root: Path) {
        val jar = root.resolve("dependency.jar")
        writeJar(jar, "example/Child.class" to classBytes("example/Child", "example/Base"))
        val cachePath = root.resolve("cache")
        cachePath.writeText("not a directory")
        val indexer = ClassHierarchyIndexer(ClassIndexCache(cachePath, "engine-a"))

        assertEquals(setOf("example/Base"), indexer.index(listOf(jar)).directSupertypesOf("example/Child"))
        assertEquals(1, indexer.statistics.hierarchyWriteFailures)
        assertEquals(1, indexer.statistics.hierarchyParsedJars)
    }

    @Test
    fun `malformed original jar still fails on a cache miss`(@TempDir root: Path) {
        val jar = root.resolve("malformed.jar")
        jar.writeText("not a jar")

        val error = assertFailsWith<ClassHierarchyIndexingException> {
            ClassHierarchyIndexer(ClassIndexCache(root.resolve("cache"), "engine-a")).index(listOf(jar))
        }

        assertEquals("classpath JAR cannot be read", error.message)
        assertFalse(error.message.orEmpty().contains(root.toString()))
    }

    private fun assertInvalidReparse(cache: ClassIndexCache, jar: Path) {
        val indexer = ClassHierarchyIndexer(cache)
        val hierarchy = indexer.index(listOf(jar))
        assertEquals(setOf("example/Base"), hierarchy.directSupertypesOf("example/Child"))
        assertEquals(1, indexer.statistics.hierarchyInvalidEntries)
        assertEquals(1, indexer.statistics.hierarchyParsedJars)
    }

    private fun observe(hierarchy: ClassHierarchy): Observation = Observation(
        hierarchy.directSupertypesOf("example/Child"),
        hierarchy.declaredMethodsOf("example/Child"),
        hierarchy.annotationTypes["example/Marker"],
    )

    private fun onlyCacheEntry(directory: Path): Path = Files.list(directory).use { files ->
        files.filter { it.fileName.toString().endsWith(".hix") }.toList().single()
    }

    private fun spool(directory: Path, bytes: ByteArray): OwnedJarSpool {
        val writer = requireNotNull(JarSpoolWriter.open(directory, bytes.size.toLong()))
        assertTrue(writer.write(bytes, 0, bytes.size))
        return requireNotNull(writer.finish())
    }

    private fun classBytes(
        internalName: String,
        superName: String = "java/lang/Object",
        methods: List<MethodSpec> = emptyList(),
    ): ByteArray = ClassWriter(0).apply {
        visit(Opcodes.V17, Opcodes.ACC_PUBLIC, internalName, null, superName, emptyArray())
        methods.forEach { method ->
            visitMethod(method.access, method.name, method.descriptor, null, null).visitEnd()
        }
        visitEnd()
    }.toByteArray()

    private fun annotationBytes(internalName: String, metaAnnotation: String): ByteArray = ClassWriter(0).apply {
        visit(
            Opcodes.V17,
            Opcodes.ACC_PUBLIC or Opcodes.ACC_ABSTRACT or Opcodes.ACC_INTERFACE or Opcodes.ACC_ANNOTATION,
            internalName,
            null,
            "java/lang/Object",
            arrayOf("java/lang/annotation/Annotation"),
        )
        visitAnnotation("L$metaAnnotation;", true).visitEnd()
        visitEnd()
    }.toByteArray()

    private fun writeJar(
        jar: Path,
        vararg entries: Pair<String, ByteArray>,
        multiRelease: Boolean = false,
        manifestLast: Boolean = false,
    ) {
        jar.parent.createDirectories()
        val manifest = Manifest().apply {
            mainAttributes[Attributes.Name.MANIFEST_VERSION] = "1.0"
            if (multiRelease) mainAttributes.putValue("Multi-Release", "true")
        }
        val manifestBytes = ByteArrayOutputStream().also(manifest::write).toByteArray()
        JarOutputStream(Files.newOutputStream(jar)).use { output ->
            if (!manifestLast) output.writeEntry("META-INF/MANIFEST.MF", manifestBytes)
            entries.forEach { (name, bytes) -> output.writeEntry(name, bytes) }
            if (manifestLast) output.writeEntry("META-INF/MANIFEST.MF", manifestBytes)
        }
    }

    private fun JarOutputStream.writeEntry(name: String, bytes: ByteArray) {
        putNextEntry(JarEntry(name).apply { time = 1_700_000_000_000L })
        write(bytes)
        closeEntry()
    }

    private data class MethodSpec(val name: String, val descriptor: String, val access: Int)
    private data class Observation(
        val parents: Set<String>?,
        val methods: List<HierarchyMethod>?,
        val annotations: Set<String>?,
    )
}
