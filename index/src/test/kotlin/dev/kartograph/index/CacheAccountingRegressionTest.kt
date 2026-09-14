package dev.kartograph.index

import dev.kartograph.core.SnapshotProvenance
import java.nio.file.Files
import java.nio.file.Path
import java.util.jar.JarEntry
import java.util.jar.JarOutputStream
import kotlin.io.path.createDirectories
import kotlin.io.path.writeBytes
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import org.junit.jupiter.api.io.TempDir
import org.objectweb.asm.ClassWriter
import org.objectweb.asm.Opcodes

class CacheAccountingRegressionTest {
    @Test
    fun `completed population with an uncacheable jar stops later cold spooling`(@TempDir root: Path) {
        val classes = writeClass(root.resolve("classes"), "probe/App", "probe/CachedDependency")
        val cachedJar = root.resolve("cached.jar")
        writeJar(cachedJar, "probe/CachedDependency", "probe/Base")
        val uncacheableJar = root.resolve("uncacheable.jar")
        writeJar(uncacheableJar, "probe/UncacheableDependency", "probe/Base", ByteArray(4_096) { it.toByte() })
        val cacheableLimit = Files.size(cachedJar)
        assertTrue(Files.size(uncacheableJar) > cacheableLimit)
        val cache = ClassIndexCache(root.resolve("cache"), "engine-a")
        val jarHash = sha256(Files.readAllBytes(uncacheableJar))
        val blockedEntry = cache.directory.resolve("$jarHash-${requireNotNull(cache.namespace)}.hix")
        blockedEntry.resolve("occupied").also { it.parent.createDirectories() }.writeBytes(byteArrayOf(1))
        val spoolDirectory = root.resolve("spools").createDirectories()
        val jars = listOf(cachedJar, uncacheableJar)

        val first = capturedIndex(root, classes, jars, cache, spoolDirectory, cacheableLimit)
        val populatedAfterFirst = HierarchyIndexCache(cache).isPopulated()
        val second = capturedIndex(root, classes, jars, cache, spoolDirectory, cacheableLimit)

        assertEquals(setOf("probe/Base"), first.indexed.hierarchy.directSupertypesOf("probe/UncacheableDependency"))
        assertEquals(1, first.indexed.statistics.hierarchyWriteFailures)
        assertEquals(1, first.spoolsBeforeIndex)
        assertTrue(populatedAfterFirst)
        assertEquals(0, second.spoolsBeforeIndex)
        assertEquals(1, second.indexed.statistics.hierarchyCacheHits)
        assertEquals(1, second.indexed.statistics.hierarchyParsedJars)
        assertEquals(1, second.indexed.statistics.hierarchyWriteFailures)
        assertEquals(0, spoolCount(spoolDirectory))
    }

    @Test
    fun `unavailable class cache is counted once per class`(@TempDir root: Path) {
        val classes = root.resolve("classes").createDirectories()
        writeClass(classes, "probe/First")
        writeClass(classes, "probe/Second")
        val cacheDirectory = root.resolve("cache")

        val indexed = ClassFileIndexer(ClassIndexCache(cacheDirectory, null))
            .indexWithObservations(listOf(classes))

        assertEquals(2, indexed.statistics.classFiles)
        assertEquals(2, indexed.statistics.parsedClasses)
        assertEquals(0, indexed.statistics.cacheHits)
        assertEquals(2, indexed.statistics.unavailableEntries)
        assertFalse(Files.exists(cacheDirectory))
    }

    @Test
    fun `cacheable jar write failure keeps cold population retryable`(@TempDir root: Path) {
        val classes = writeClass(root.resolve("classes"), "probe/App", "probe/Dependency")
        val jar = root.resolve("dependency.jar")
        writeJar(jar, "probe/Dependency", "probe/Base")
        val cache = ClassIndexCache(root.resolve("cache"), "engine-a")
        val jarHash = sha256(Files.readAllBytes(jar))
        cache.directory.resolve("$jarHash-${requireNotNull(cache.namespace)}.hix/occupied")
            .also { it.parent.createDirectories() }
            .writeBytes(byteArrayOf(1))
        val spoolDirectory = root.resolve("spools").createDirectories()

        val first = capturedIndex(root, classes, listOf(jar), cache, spoolDirectory, Files.size(jar))
        val second = capturedIndex(root, classes, listOf(jar), cache, spoolDirectory, Files.size(jar))

        assertFalse(HierarchyIndexCache(cache).isPopulated())
        assertEquals(1, first.spoolsBeforeIndex)
        assertEquals(1, second.spoolsBeforeIndex)
        assertEquals(1, first.indexed.statistics.hierarchyWriteFailures)
        assertEquals(1, second.indexed.statistics.hierarchyWriteFailures)
        assertEquals(0, spoolCount(spoolDirectory))
    }

    private fun capturedIndex(
        project: Path,
        classes: Path,
        jars: List<Path>,
        cache: ClassIndexCache,
        spoolDirectory: Path,
        maximumCacheableJarBytes: Long,
    ): CapturedIndex {
        val scope = VerifiedCaptureScope.open(
            cache,
            maximumCacheableJarBytes = maximumCacheableJarBytes,
            spoolDirectory = spoolDirectory,
        )
        try {
            val before = provenance(scope, project, classes, jars)
            val spoolsBeforeIndex = spoolCount(spoolDirectory)
            scope.beginAction()
            val indexed = scope.indexWithObservations(
                listOf(classes),
                jars,
                emptyList(),
                emptyList(),
                cache,
            )
            scope.beginAfterCapture()
            assertEquals(before, provenance(scope, project, classes, jars))
            scope.markPopulatedIfComplete()
            return CapturedIndex(indexed, spoolsBeforeIndex)
        } finally {
            scope.close()
        }
    }

    private fun provenance(
        scope: VerifiedCaptureScope,
        project: Path,
        classes: Path,
        jars: List<Path>,
    ): SnapshotProvenance = SnapshotProvenance(
        listOf(
            scope.capture(project, classes, "classes", "classes-0"),
        ) + jars.mapIndexed { index, jar ->
            scope.capture(project, jar, "classpath", "classpath-$index")
        },
        emptyList(),
    )

    private fun writeClass(root: Path, internalName: String, superName: String = "java/lang/Object"): Path {
        root.resolve("$internalName.class").also { it.parent.createDirectories() }.writeBytes(
            classBytes(internalName, superName),
        )
        return root
    }

    private fun writeJar(path: Path, internalName: String, superName: String, ignored: ByteArray? = null) {
        JarOutputStream(Files.newOutputStream(path)).use { output ->
            output.putNextEntry(JarEntry("dependency/Observed.class"))
            output.write(classBytes(internalName, superName))
            output.closeEntry()
            if (ignored != null) {
                output.putNextEntry(JarEntry("ignored.bin"))
                output.write(ignored)
                output.closeEntry()
            }
        }
    }

    private fun classBytes(internalName: String, superName: String): ByteArray = ClassWriter(0).apply {
        visit(Opcodes.V17, Opcodes.ACC_PUBLIC, internalName, null, superName, null)
        visitEnd()
    }.toByteArray()

    private fun spoolCount(directory: Path): Long = Files.list(directory).use { it.count() }

    private data class CapturedIndex(val indexed: IndexedClasses, val spoolsBeforeIndex: Long)
}
