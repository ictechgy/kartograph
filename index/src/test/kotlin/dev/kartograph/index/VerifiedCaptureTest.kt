package dev.kartograph.index

import dev.kartograph.core.InputFingerprint
import dev.kartograph.core.SnapshotProvenance
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import java.util.jar.JarEntry
import java.util.jar.JarOutputStream
import kotlin.io.path.createDirectories
import kotlin.io.path.deleteExisting
import kotlin.io.path.writeBytes
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import org.junit.jupiter.api.io.TempDir
import org.objectweb.asm.ClassWriter
import org.objectweb.asm.Opcodes

class VerifiedCaptureTest {
    @Test
    fun `verified capture shares observed jar digests with warm hierarchy hits`(@TempDir root: Path) {
        val classes = writeClassRoot(root.resolve("classes"), "example/App", "example/Dependency")
        val jar = root.resolve("dependency.jar")
        writeJar(jar, "example/Dependency", "java/lang/Object")
        val cache = ClassIndexCache(root.resolve("cache"), "engine-a")

        val cold = verifiedIndex(root, listOf(classes), listOf(jar), cache)
        val warm = verifiedIndex(root, listOf(classes), listOf(jar), cache)

        assertEquals(1, cold.statistics.hierarchyParsedJars)
        assertEquals(0, cold.statistics.hierarchyCacheHits)
        assertEquals(0, warm.statistics.hierarchyParsedJars)
        assertEquals(1, warm.statistics.hierarchyCacheHits)
        assertEquals(emptySet(), warm.hierarchy.directSupertypesOf("example/Dependency"))
    }

    @Test
    fun `parallel cold population completes one marker after all jars`(@TempDir root: Path) {
        val classes = writeClassRoot(root.resolve("classes"), "example/App", "example/Dependency0")
        val jars = (0 until 32).map { index ->
            root.resolve("dependency-$index.jar").also { jar ->
                writeJar(jar, "example/Dependency$index", "java/lang/Object")
            }
        }
        val cache = ClassIndexCache(root.resolve("cache"), "engine-a")

        val cold = verifiedIndex(root, listOf(classes), jars, cache)
        val warm = verifiedIndex(root, listOf(classes), jars, cache)

        assertEquals(32, cold.statistics.hierarchyParsedJars)
        assertEquals(0, cold.statistics.hierarchyCacheHits)
        assertTrue(HierarchyIndexCache(cache).isPopulated())
        assertEquals(0, warm.statistics.hierarchyParsedJars)
        assertEquals(32, warm.statistics.hierarchyCacheHits)
    }

    @Test
    fun `same mtime mutation after a hinted hit fails final verification`(@TempDir root: Path) {
        val classes = writeClassRoot(root.resolve("classes"), "example/App", "example/Dependency")
        val jar = root.resolve("dependency.jar")
        writeJar(jar, "example/Dependency", "example/First")
        val cache = ClassIndexCache(root.resolve("cache"), "engine-a")
        verifiedIndex(root, listOf(classes), listOf(jar), cache)
        val modified = Files.getLastModifiedTime(jar)
        var capturePasses = 0

        val error = assertFailsWith<IllegalStateException> {
            ContentFingerprint.withVerifiedCapture(
                capture = { scope ->
                    capturePasses++
                    provenance(scope, root, listOf(classes), listOf(jar))
                },
                cache = cache,
                action = { scope, _ ->
                    writeJar(jar, "example/Dependency", "example/Other")
                    Files.setLastModifiedTime(jar, modified)
                    scope.indexWithObservations(listOf(classes), listOf(jar), emptyList(), emptyList(), cache)
                },
            )
        }

        assertEquals("snapshot inputs changed during verified capture", error.message)
        assertEquals(2, capturePasses)
        assertEquals(1, hierarchyEntries(cache.directory))
        assertFalse(error.message.orEmpty().contains(root.toString()))
    }

    @Test
    fun `transient miss uses exact captured bytes under the observed digest key`(@TempDir root: Path) {
        val classes = writeClassRoot(root.resolve("classes"), "example/App", "example/Dependency")
        val jar = root.resolve("dependency.jar")
        writeJar(jar, "example/Dependency", "example/First")
        val original = Files.readAllBytes(jar)
        val modified = Files.getLastModifiedTime(jar)
        val cache = ClassIndexCache(root.resolve("cache"), "engine-a")

        val result = ContentFingerprint.withVerifiedCapture(
            capture = { scope -> provenance(scope, root, listOf(classes), listOf(jar)) },
            cache = cache,
            action = { scope, _ ->
                writeJar(jar, "example/Dependency", "example/Other")
                Files.setLastModifiedTime(jar, modified)
                val indexed = scope.indexWithObservations(listOf(classes), listOf(jar), emptyList(), emptyList(), cache)
                assertEquals(setOf("example/First"), indexed.hierarchy.directSupertypesOf("example/Dependency"))
                assertEquals(1, hierarchyEntries(cache.directory))
                Files.write(jar, original)
                Files.setLastModifiedTime(jar, modified)
                indexed
            },
        )

        assertEquals(setOf("example/First"), result.hierarchy.directSupertypesOf("example/Dependency"))
        assertTrue(HierarchyIndexCache(cache).isPopulated())
        val warm = verifiedIndex(root, listOf(classes), listOf(jar), cache)
        assertEquals(1, warm.statistics.hierarchyCacheHits)
        assertEquals(setOf("example/First"), warm.hierarchy.directSupertypesOf("example/Dependency"))
    }

    @Test
    fun `cold miss parses the exact before spool and unlinks it after consumption`(@TempDir root: Path) {
        val classes = writeClassRoot(root.resolve("classes"), "example/App", "example/Dependency")
        val jar = root.resolve("dependency.jar")
        writeJar(jar, "example/Dependency", "example/First")
        val original = Files.readAllBytes(jar)
        val cache = ClassIndexCache(root.resolve("cache"), "engine-a")
        val spoolDirectory = root.resolve("spools").createDirectories()
        val scope = VerifiedCaptureScope.open(cache, spoolDirectory = spoolDirectory)
        try {
            val before = provenance(scope, root, listOf(classes), listOf(jar))
            assertEquals(1, spoolFiles(spoolDirectory))
            scope.beginAction()
            writeJar(jar, "example/Dependency", "example/Other")

            val indexed = scope.indexWithObservations(listOf(classes), listOf(jar), emptyList(), emptyList(), cache)

            assertEquals(setOf("example/First"), indexed.hierarchy.directSupertypesOf("example/Dependency"))
            assertEquals(0, spoolFiles(spoolDirectory))
            Files.write(jar, original)
            scope.beginAfterCapture()
            assertEquals(before, provenance(scope, root, listOf(classes), listOf(jar)))
            scope.markPopulatedIfComplete()
        } finally {
            scope.close()
        }
        assertEquals(0, spoolFiles(spoolDirectory))
    }

    @Test
    fun `cache hit and early scope close unlink owned spools`(@TempDir root: Path) {
        val classes = writeClassRoot(root.resolve("classes"), "example/App", "example/Dependency")
        val jar = root.resolve("dependency.jar")
        writeJar(jar, "example/Dependency", "java/lang/Object")
        val cache = ClassIndexCache(root.resolve("cache"), "engine-a")
        ClassHierarchyIndexer(cache).index(listOf(jar))
        val spoolDirectory = root.resolve("spools").createDirectories()
        val hitScope = VerifiedCaptureScope.open(cache, spoolDirectory = spoolDirectory)
        try {
            provenance(hitScope, root, listOf(classes), listOf(jar))
            assertEquals(1, spoolFiles(spoolDirectory))
            hitScope.beginAction()
            val indexed = hitScope.indexWithObservations(listOf(classes), listOf(jar), emptyList(), emptyList(), cache)
            assertEquals(1, indexed.statistics.hierarchyCacheHits)
            assertEquals(0, spoolFiles(spoolDirectory))
        } finally {
            hitScope.close()
        }

        val abandonedScope = VerifiedCaptureScope.open(cache, spoolDirectory = spoolDirectory)
        provenance(abandonedScope, root, listOf(classes), listOf(jar))
        assertEquals(1, spoolFiles(spoolDirectory))
        abandonedScope.close()
        assertEquals(0, spoolFiles(spoolDirectory))
    }

    @Test
    fun `spool writer abandons growth and unavailable directories without residue`(@TempDir root: Path) {
        val spoolDirectory = root.resolve("spools").createDirectories()
        val writer = requireNotNull(JarSpoolWriter.open(spoolDirectory, maximumBytes = 4))

        assertFalse(writer.write(byteArrayOf(1, 2, 3, 4, 5), 0, 5))
        assertEquals(null, writer.finish())
        writer.close()
        assertEquals(0, spoolFiles(spoolDirectory))

        val unavailable = root.resolve("not-a-directory")
        Files.writeString(unavailable, "occupied")
        assertEquals(null, JarSpoolWriter.open(unavailable, maximumBytes = 4))
    }

    @Test
    fun `malformed captured jar failure unlinks its spool`(@TempDir root: Path) {
        val classes = writeClassRoot(root.resolve("classes"), "example/App")
        val jar = root.resolve("malformed.jar")
        Files.writeString(jar, "not a jar")
        val cache = ClassIndexCache(root.resolve("cache"), "engine-a")
        val spoolDirectory = root.resolve("spools").createDirectories()
        val scope = VerifiedCaptureScope.open(cache, spoolDirectory = spoolDirectory)
        try {
            provenance(scope, root, listOf(classes), listOf(jar))
            assertEquals(1, spoolFiles(spoolDirectory))
            scope.beginAction()

            val error = assertFailsWith<ClassHierarchyIndexingException> {
                scope.indexWithObservations(listOf(classes), listOf(jar), emptyList(), emptyList(), cache)
            }

            assertEquals("classpath JAR cannot be read", error.message)
            assertFalse(error.message.orEmpty().contains(root.toString()))
            assertEquals(0, spoolFiles(spoolDirectory))
        } finally {
            scope.close()
        }
        assertEquals(0, spoolFiles(spoolDirectory))
    }

    @Test
    fun `capture resolution failure before ownership transfer unlinks its spool`(@TempDir root: Path) {
        val jar = root.resolve("dependency.jar")
        writeJar(jar, "example/Dependency", "java/lang/Object")
        val cache = ClassIndexCache(root.resolve("cache"), "engine-a")
        val spoolDirectory = root.resolve("spools").createDirectories()
        val scope = VerifiedCaptureScope.open(cache, spoolDirectory = spoolDirectory)
        try {
            assertFailsWith<java.io.IOException> {
                scope.capture(root.resolve("missing-project"), jar, "classpath", "classpath-0")
            }
            assertEquals(0, spoolFiles(spoolDirectory))
        } finally {
            scope.close()
        }
    }

    @Test
    fun `failed final verification does not publish the populated marker`(@TempDir root: Path) {
        val classes = writeClassRoot(root.resolve("classes"), "example/App", "example/Dependency")
        val jar = root.resolve("dependency.jar")
        writeJar(jar, "example/Dependency", "example/First")
        val cache = ClassIndexCache(root.resolve("cache"), "engine-a")

        assertFailsWith<IllegalStateException> {
            ContentFingerprint.withVerifiedCapture(
                capture = { scope -> provenance(scope, root, listOf(classes), listOf(jar)) },
                cache = cache,
                action = { scope, _ ->
                    val indexed = scope.indexWithObservations(listOf(classes), listOf(jar), emptyList(), emptyList(), cache)
                    assertEquals(setOf("example/First"), indexed.hierarchy.directSupertypesOf("example/Dependency"))
                    writeJar(jar, "example/Dependency", "example/Other")
                },
            )
        }

        assertEquals(1, hierarchyEntries(cache.directory))
        assertFalse(HierarchyIndexCache(cache).isPopulated())
    }

    @Test
    fun `zero spool budget retains exact mismatch fallback`(@TempDir root: Path) {
        val classes = writeClassRoot(root.resolve("classes"), "example/App", "example/Dependency")
        val jar = root.resolve("dependency.jar")
        writeJar(jar, "example/Dependency", "example/First")
        val cache = ClassIndexCache(root.resolve("cache"), "engine-a")
        val scope = VerifiedCaptureScope.open(cache, maximumSpoolBytes = 0)
        try {
            provenance(scope, root, listOf(classes), listOf(jar))
            scope.beginAction()
            writeJar(jar, "example/Dependency", "example/Other")

            val error = assertFailsWith<ClassHierarchyIndexingException> {
                scope.indexWithObservations(listOf(classes), listOf(jar), emptyList(), emptyList(), cache)
            }

            assertEquals("classpath input changed during verified capture", error.message)
            assertEquals(0, hierarchyEntries(cache.directory))
        } finally {
            scope.close()
        }
    }

    @Test
    fun `scope rejects uncaptured partial-role and reordered inputs`(@TempDir root: Path) {
        val first = writeClassRoot(root.resolve("first"), "example/First")
        val second = writeClassRoot(root.resolve("second"), "example/Second")
        val jar = root.resolve("dependency.jar")
        writeJar(jar, "example/Dependency", "java/lang/Object")

        val reordered = assertFailsWith<IllegalStateException> {
            ContentFingerprint.withVerifiedCapture(
                capture = { scope -> provenance(scope, root, listOf(first, second), emptyList()) },
                action = { scope, _ ->
                    scope.indexWithObservations(listOf(second, first), null, emptyList(), emptyList(), null)
                },
            )
        }
        assertEquals("indexed classes inputs do not match verified capture order", reordered.message)

        val wrongRole = assertFailsWith<IllegalStateException> {
            ContentFingerprint.withVerifiedCapture(
                capture = { scope ->
                    SnapshotProvenance(
                        listOf(
                            scope.capture(root, first, "classes", "classes-0"),
                            scope.capture(root, jar, "sources", "sources-0"),
                        ),
                        emptyList(),
                    )
                },
                action = { scope, _ ->
                    scope.indexWithObservations(listOf(first), listOf(jar), emptyList(), emptyList(), null)
                },
            )
        }
        assertEquals("indexed classpath inputs do not match verified capture order", wrongRole.message)
    }

    @Test
    fun `captured duplicate roots retain their order and remain valid`(@TempDir root: Path) {
        val classes = writeClassRoot(root.resolve("classes"), "example/App")

        val indexed = ContentFingerprint.withVerifiedCapture(
            capture = { scope -> provenance(scope, root, listOf(classes, classes), emptyList()) },
            action = { scope, _ ->
                scope.indexWithObservations(listOf(classes, classes), null, emptyList(), emptyList(), null)
            },
        )

        assertEquals(2, indexed.declarationsByRoot.size)
        assertEquals(indexed.declarationsByRoot[0], indexed.declarationsByRoot[1])
    }

    @Test
    fun `all indexed input groups require their full capture roles`(@TempDir root: Path) {
        val classes = writeClassRoot(root.resolve("classes"), "example/App")
        val services = root.resolve("services").createDirectories()

        val indexed = ContentFingerprint.withVerifiedCapture(
            capture = { scope ->
                SnapshotProvenance(
                    listOf(
                        scope.capture(root, classes, "classes", "classes-0"),
                        scope.capture(root, services, "service-resources", "services-0"),
                        scope.capture(root, classes, "generated-classes", "generated-0"),
                    ),
                    emptyList(),
                )
            },
            action = { scope, _ ->
                scope.indexWithObservations(listOf(classes), null, listOf(services), listOf(classes), null)
            },
        )

        assertEquals(1, indexed.declarationsByRoot.size)
        assertTrue(indexed.graph.nodes.values.any { it.synthesized })
    }

    @Test
    fun `deleted captured input fails before indexing without exposing its path`(@TempDir root: Path) {
        val classes = writeClassRoot(root.resolve("classes"), "example/App", "example/Dependency")
        val jar = root.resolve("dependency.jar")
        writeJar(jar, "example/Dependency", "java/lang/Object")

        val error = assertFailsWith<IllegalStateException> {
            ContentFingerprint.withVerifiedCapture(
                capture = { scope -> provenance(scope, root, listOf(classes), listOf(jar)) },
                action = { scope, _ ->
                    jar.deleteExisting()
                    scope.indexWithObservations(listOf(classes), listOf(jar), emptyList(), emptyList(), null)
                },
            )
        }

        assertEquals("indexed classpath input changed after initial capture", error.message)
        assertFalse(error.message.orEmpty().contains(root.toString()))
    }

    @Test
    fun `scope expires after success and action exceptions`(@TempDir root: Path) {
        val classes = writeClassRoot(root.resolve("classes"), "example/App")
        lateinit var successfulScope: VerifiedCaptureScope
        ContentFingerprint.withVerifiedCapture(
            capture = { scope -> provenance(scope, root, listOf(classes), emptyList()) },
            action = { scope, _ -> successfulScope = scope; "done" },
        )
        assertFailsWith<IllegalStateException> {
            successfulScope.indexWithObservations(listOf(classes), null, emptyList(), emptyList(), null)
        }
        assertFailsWith<IllegalStateException> {
            successfulScope.capture(root, classes, "classes", "late")
        }

        lateinit var failedScope: VerifiedCaptureScope
        assertFailsWith<ExpectedFailure> {
            ContentFingerprint.withVerifiedCapture(
                capture = { scope -> provenance(scope, root, listOf(classes), emptyList()) },
                action = { scope, _ -> failedScope = scope; throw ExpectedFailure() },
            )
        }
        assertFailsWith<IllegalStateException> {
            failedScope.indexWithObservations(listOf(classes), null, emptyList(), emptyList(), null)
        }
    }

    @Test
    fun `cache preparation overlaps non classpath capture and joins at the first jar`(@TempDir root: Path) {
        val classes = writeClassRoot(root.resolve("classes"), "example/App")
        val jar = root.resolve("dependency.jar")
        writeJar(jar, "example/Dependency", "java/lang/Object")
        val cache = ClassIndexCache(root.resolve("cache"), "engine-a")
        val started = CountDownLatch(1)
        val release = CountDownLatch(1)
        val preparations = AtomicInteger()
        val scope = VerifiedCaptureScope.open(cache, prepareCache = {
            preparations.incrementAndGet()
            started.countDown()
            release.await()
            true
        })
        val executor = Executors.newSingleThreadExecutor()
        try {
            assertTrue(started.await(1, TimeUnit.SECONDS))
            executor.submit {
                scope.capture(root, classes, "classes", "classes-0")
            }.get(1, TimeUnit.SECONDS)

            val jarCapture = executor.submit {
                scope.capture(root, jar, "classpath", "classpath-0")
            }
            assertFailsWith<TimeoutException> { jarCapture.get(100, TimeUnit.MILLISECONDS) }
            release.countDown()
            jarCapture.get(1, TimeUnit.SECONDS)
            scope.beginAction()
            assertEquals(1, preparations.get())
        } finally {
            release.countDown()
            scope.close()
            executor.shutdownNow()
        }
    }

    @Test
    fun `action entry and early close join owned cache preparation`(@TempDir root: Path) {
        val classes = writeClassRoot(root.resolve("classes"), "example/App")
        val cache = ClassIndexCache(root.resolve("cache"), "engine-a")
        fun blockedScope(started: CountDownLatch, release: CountDownLatch): VerifiedCaptureScope =
            VerifiedCaptureScope.open(cache, prepareCache = {
                started.countDown()
                release.await()
                true
            })
        val executor = Executors.newFixedThreadPool(2)

        val actionStarted = CountDownLatch(1)
        val actionRelease = CountDownLatch(1)
        val actionScope = blockedScope(actionStarted, actionRelease)
        actionScope.capture(root, classes, "classes", "classes-0")
        assertTrue(actionStarted.await(1, TimeUnit.SECONDS))
        val actionEntry = executor.submit { actionScope.beginAction() }
        assertFailsWith<TimeoutException> { actionEntry.get(100, TimeUnit.MILLISECONDS) }
        actionRelease.countDown()
        actionEntry.get(1, TimeUnit.SECONDS)
        actionScope.close()

        val closeStarted = CountDownLatch(1)
        val closeRelease = CountDownLatch(1)
        val closingScope = blockedScope(closeStarted, closeRelease)
        assertTrue(closeStarted.await(1, TimeUnit.SECONDS))
        val closing = executor.submit { closingScope.close() }
        assertFailsWith<TimeoutException> { closing.get(100, TimeUnit.MILLISECONDS) }
        closeRelease.countDown()
        closing.get(1, TimeUnit.SECONDS)
        executor.shutdownNow()
    }

    @Test
    fun `cache preparation failure is joined and sanitized`(@TempDir root: Path) {
        val cache = ClassIndexCache(root.resolve("cache"), "engine-a")
        val scope = VerifiedCaptureScope.open(cache, prepareCache = {
            throw IllegalStateException(root.resolve("private-input.jar").toString())
        })

        val actionError = assertFailsWith<IllegalStateException> { scope.beginAction() }
        val closeError = assertFailsWith<IllegalStateException> { scope.close() }

        assertEquals("index cache preparation failed", actionError.message)
        assertEquals("index cache preparation failed", closeError.message)
        assertFalse(actionError.toString().contains(root.toString()))
        assertFalse(closeError.toString().contains(root.toString()))
    }

    @Test
    fun `interrupted preparation join finishes and restores interrupt status`(@TempDir root: Path) {
        val cache = ClassIndexCache(root.resolve("cache"), "engine-a")
        val preparationStarted = CountDownLatch(1)
        val releasePreparation = CountDownLatch(1)
        val preparationFinished = CountDownLatch(1)
        val scope = VerifiedCaptureScope.open(cache, prepareCache = {
            preparationStarted.countDown()
            try {
                releasePreparation.await()
                true
            } finally {
                preparationFinished.countDown()
            }
        })
        assertTrue(preparationStarted.await(1, TimeUnit.SECONDS))
        val failure = AtomicReference<Throwable?>()
        val interruptRestored = AtomicBoolean()
        val caller = Thread {
            Thread.currentThread().interrupt()
            try {
                scope.beginAction()
            } catch (error: Throwable) {
                failure.set(error)
                interruptRestored.set(Thread.currentThread().isInterrupted)
            }
        }
        caller.start()
        val waitDeadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(1)
        while (caller.state !in setOf(Thread.State.WAITING, Thread.State.TIMED_WAITING) && System.nanoTime() < waitDeadline) {
            Thread.yield()
        }
        val joinedPreparation = caller.state in setOf(Thread.State.WAITING, Thread.State.TIMED_WAITING)
        releasePreparation.countDown()
        caller.join(1_000)

        assertTrue(joinedPreparation)
        assertFalse(caller.isAlive)
        assertTrue(preparationFinished.await(1, TimeUnit.SECONDS))
        assertEquals("index cache preparation was interrupted", failure.get()?.message)
        assertTrue(interruptRestored.get())
        scope.close()
    }

    @Test
    fun `full provenance returned by capture callback is compared`(@TempDir root: Path) {
        val classes = writeClassRoot(root.resolve("classes"), "example/App")
        var pass = 0

        val error = assertFailsWith<IllegalStateException> {
            ContentFingerprint.withVerifiedCapture(
                capture = { scope ->
                    val inputs = provenance(scope, root, listOf(classes), emptyList()).inputs
                    SnapshotProvenance(
                        inputs + InputFingerprint("options", "snapshot-options", ContentFingerprint.values(listOf((pass++).toString()))),
                        emptyList(),
                    )
                },
                action = { _, _ -> "candidate" },
            )
        }

        assertEquals("snapshot inputs changed during verified capture", error.message)
        assertEquals(2, pass)
    }

    private fun verifiedIndex(
        project: Path,
        classRoots: List<Path>,
        classpath: List<Path>,
        cache: ClassIndexCache,
    ): IndexedClasses = ContentFingerprint.withVerifiedCapture(
        capture = { scope -> provenance(scope, project, classRoots, classpath) },
        cache = cache,
        action = { scope, _ ->
            scope.indexWithObservations(classRoots, classpath, emptyList(), emptyList(), cache)
        },
    )

    private fun provenance(
        scope: VerifiedCaptureScope,
        project: Path,
        classRoots: List<Path>,
        classpath: List<Path>,
    ): SnapshotProvenance = SnapshotProvenance(
        classRoots.mapIndexed { index, path -> scope.capture(project, path, "classes", "classes-$index") } +
            classpath.mapIndexed { index, path -> scope.capture(project, path, "classpath", "classpath-$index") },
        emptyList(),
    )

    private fun writeClassRoot(root: Path, internalName: String, superName: String = "java/lang/Object"): Path {
        val file = root.resolve("$internalName.class")
        file.parent.createDirectories()
        file.writeBytes(classBytes(internalName, superName))
        return root
    }

    private fun writeJar(path: Path, internalName: String, superName: String) {
        JarOutputStream(Files.newOutputStream(path)).use { output ->
            output.putNextEntry(JarEntry("dependency/Observed.class").apply { time = 1_700_000_000_000L })
            output.write(classBytes(internalName, superName))
            output.closeEntry()
        }
    }

    private fun classBytes(internalName: String, superName: String): ByteArray = ClassWriter(0).apply {
        visit(Opcodes.V17, Opcodes.ACC_PUBLIC, internalName, null, superName, emptyArray())
        visitEnd()
    }.toByteArray()

    private fun hierarchyEntries(directory: Path): Long = if (!Files.isDirectory(directory)) 0 else {
        Files.list(directory).use { entries -> entries.filter { it.fileName.toString().endsWith(".hix") }.count() }
    }

    private fun spoolFiles(directory: Path): Long =
        Files.list(directory).use { entries -> entries.count() }

    private class ExpectedFailure : RuntimeException()
}
