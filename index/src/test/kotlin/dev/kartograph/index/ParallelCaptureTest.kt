package dev.kartograph.index

import dev.kartograph.core.InputFingerprint
import java.nio.file.Files
import java.nio.file.Path
import java.util.jar.JarEntry
import java.util.jar.JarOutputStream
import kotlin.io.path.createDirectories
import kotlin.io.path.writeBytes
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import org.junit.jupiter.api.io.TempDir
import org.objectweb.asm.ClassWriter
import org.objectweb.asm.Opcodes

/** 일괄(병렬) fingerprint가 순차 캡처와 값·순서·오류·spool 예산에서 동일한지 고정한다. */
class ParallelCaptureTest {
    @Test
    fun `batch capture matches sequential capture and preserves input order`(@TempDir root: Path) {
        val inputs = sampleInputs(root)
        val sequentialScope = VerifiedCaptureScope.open(null)
        val sequential = try {
            inputs.map { sequentialScope.capture(root, it.path, it.role, it.externalSlot) }
        } finally {
            sequentialScope.close()
        }
        val direct = inputs.map { ContentFingerprint.capture(root, it.path, it.role, it.externalSlot) }

        val batchScope = VerifiedCaptureScope.open(null)
        val batch = try {
            batchScope.captureAll(root, inputs)
        } finally {
            batchScope.close()
        }

        assertEquals(sequential, batch)
        assertEquals(direct, batch)
        assertEquals(inputs.map { it.role }, batch.map(InputFingerprint::role))
    }

    @Test
    fun `batch capture reports the first failing input in order without spool residue`(@TempDir root: Path) {
        val classes = writeClassRoot(root.resolve("classes"), "example/App")
        val jar = root.resolve("dependency.jar")
        writeJar(jar, "example/Dependency")
        val cache = ClassIndexCache(root.resolve("cache"), "engine-a")
        val spoolDirectory = root.resolve("spools").createDirectories()
        val inputs = listOf(
            CaptureInput(classes, "classes", "classes-0"),
            CaptureInput(jar, "classpath", "classpath-0"),
            CaptureInput(root.resolve("missing-classes"), "classes", "classes-1"),
            CaptureInput(root.resolve("missing-jar.jar"), "classpath", "classpath-1"),
        )
        val scope = VerifiedCaptureScope.open(cache, spoolDirectory = spoolDirectory)

        val error = assertFailsWith<IllegalArgumentException> { scope.captureAll(root, inputs) }

        assertEquals("fingerprint input is missing", error.message)
        assertEquals(0, spoolFiles(spoolDirectory))
        scope.close()
        assertEquals(0, spoolFiles(spoolDirectory))
    }

    @Test
    fun `batch spool budget is allocated in input order`(@TempDir root: Path) {
        val classes = writeClassRoot(root.resolve("classes"), "example/App")
        val jars = (0 until 3).map { index ->
            root.resolve("dependency-$index.jar").also { writeJar(it, "example/Dependency$index") }
        }
        val jarBytes = jars.map(Files::size)
        val budget = jarBytes[0] + jarBytes[1]
        val cache = ClassIndexCache(root.resolve("cache"), "engine-a")
        val spoolDirectory = root.resolve("spools").createDirectories()
        val inputs = listOf(CaptureInput(classes, "classes", "classes-0")) +
            jars.mapIndexed { index, jar -> CaptureInput(jar, "classpath", "classpath-$index") }
        val scope = VerifiedCaptureScope.open(cache, maximumSpoolBytes = budget, spoolDirectory = spoolDirectory)
        try {
            scope.captureAll(root, inputs)

            assertEquals(2, spoolFiles(spoolDirectory))
            scope.beginAction()
            val indexed = scope.indexWithObservations(listOf(classes), jars, emptyList(), emptyList(), cache)
            assertEquals(3, indexed.statistics.hierarchyParsedJars)
        } finally {
            scope.close()
        }
        assertEquals(0, spoolFiles(spoolDirectory))
    }

    private fun sampleInputs(root: Path): List<CaptureInput> {
        val classes = root.resolve("classes")
        repeat(40) { index -> writeClassRoot(classes, "example/Class$index") }
        val jar = root.resolve("dependency.jar")
        writeJar(jar, "example/Dependency")
        val sources = root.resolve("src").createDirectories()
        Files.writeString(sources.resolve("A.kt"), "class A")
        Files.writeString(sources.resolve("README.md"), "ignored by sources role")
        val buildLogic = root.resolve("build-logic").createDirectories()
        Files.writeString(buildLogic.resolve("build.gradle.kts"), "plugins {}")
        val keepRules = root.resolve("proguard-rules.pro")
        Files.writeString(keepRules, "-keep class example.App")
        return listOf(
            CaptureInput(classes, "classes", "classes-0"),
            CaptureInput(jar, "classpath", "classpath-0"),
            CaptureInput(sources, "sources", "sources-0"),
            CaptureInput(root.resolve("missing.properties"), "file-watch", "file-watch-0"),
            CaptureInput(root.resolve("missing-resources"), "directory-watch", "directory-watch-0"),
            CaptureInput(buildLogic, "build-logic-watch", "build-logic-watch-0"),
            CaptureInput(keepRules, "keepRules", "keepRules-0"),
            CaptureInput(classes, "classes", "classes-1"),
        )
    }

    private fun writeClassRoot(root: Path, internalName: String): Path {
        val file = root.resolve("$internalName.class")
        file.parent.createDirectories()
        file.writeBytes(classBytes(internalName))
        return root
    }

    private fun writeJar(jar: Path, internalName: String) {
        JarOutputStream(Files.newOutputStream(jar)).use { output ->
            output.putNextEntry(JarEntry("$internalName.class").apply { time = 1_700_000_000_000L })
            output.write(classBytes(internalName))
            output.closeEntry()
        }
    }

    private fun classBytes(internalName: String): ByteArray = ClassWriter(0).apply {
        visit(Opcodes.V17, Opcodes.ACC_PUBLIC, internalName, null, "java/lang/Object", emptyArray())
        visitEnd()
    }.toByteArray()

    private fun spoolFiles(directory: Path): Int = Files.list(directory).use { it.count().toInt() }
}
