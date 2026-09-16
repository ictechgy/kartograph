package dev.kartograph.index

import dev.kartograph.core.InputFingerprint
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.file.attribute.PosixFilePermissions
import java.security.MessageDigest
import java.util.HexFormat
import java.util.concurrent.ConcurrentLinkedQueue
import java.nio.file.Files
import java.nio.file.Path
import java.util.jar.JarEntry
import java.util.jar.JarOutputStream
import kotlin.io.path.createDirectories
import kotlin.io.path.writeBytes
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import org.junit.jupiter.api.Assumptions
import org.junit.jupiter.api.io.TempDir
import org.objectweb.asm.ClassWriter
import org.objectweb.asm.Opcodes

/** 일괄(병렬) fingerprint가 순차 캡처와 값·순서·오류·spool 예산에서 동일한지 고정한다. */
class ParallelCaptureTest {
    @Test
    fun `batch capture matches sequential capture and preserves input order`(@TempDir root: Path) {
        val inputs = sampleInputs(root)
        // capture()는 captureAll에 위임하므로 perInput은 "입력 하나씩" 경로다. 순차 기준선은 direct(ContentFingerprint.capture)다.
        val perInputScope = VerifiedCaptureScope.open(null)
        val perInput = try {
            inputs.map { perInputScope.capture(root, it.path, it.role, it.externalSlot) }
        } finally {
            perInputScope.close()
        }
        val direct = inputs.map { ContentFingerprint.capture(root, it.path, it.role, it.externalSlot) }

        val batchScope = VerifiedCaptureScope.open(null)
        val batch = try {
            batchScope.captureAll(root, inputs)
        } finally {
            batchScope.close()
        }

        assertEquals(perInput, batch)
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
        val linked = Files.createSymbolicLink(root.resolve("linked-classes"), classes)
        val inputs = listOf(
            CaptureInput(classes, "classes", "classes-0"),
            CaptureInput(jar, "classpath", "classpath-0"),
            CaptureInput(linked, "classes", "classes-1"),
            CaptureInput(root.resolve("missing-classes"), "classes", "classes-2"),
        )
        val scope = VerifiedCaptureScope.open(cache, spoolDirectory = spoolDirectory)

        val error = assertFailsWith<IllegalArgumentException> { scope.captureAll(root, inputs) }

        // index 2(symlink)와 index 3(missing)은 메시지가 다르므로 입력 순서상 첫 계획 오류가 보고됨을 구분해 확인한다.
        assertEquals("symbolic fingerprint inputs are not supported", error.message)
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

    @Test
    fun `digest failure after a spooled jar closes the spool and reports the failing job`(@TempDir root: Path) {
        val jar = root.resolve("dependency.jar")
        writeJar(jar, "example/Dependency")
        val unreadable = root.resolve("rules.pro")
        Files.writeString(unreadable, "-keep class example.App")
        assumeUnreadable(unreadable)
        val cache = ClassIndexCache(root.resolve("cache"), "engine-a")
        val spoolDirectory = root.resolve("spools").createDirectories()
        val scope = VerifiedCaptureScope.open(cache, spoolDirectory = spoolDirectory)
        try {
            assertFailsWith<IOException> {
                scope.captureAll(root, listOf(CaptureInput(jar, "classpath", "classpath-0"), CaptureInput(unreadable, "keepRules", "keepRules-0")))
            }
            assertEquals(0, spoolFiles(spoolDirectory))
        } finally {
            scope.close()
            Files.setPosixFilePermissions(unreadable, PosixFilePermissions.fromString("rw-r--r--"))
        }
        assertEquals(0, spoolFiles(spoolDirectory))
    }

    @Test
    fun `planning errors are reported before digest errors and no file is read`(@TempDir root: Path) {
        val jar = root.resolve("dependency.jar")
        writeJar(jar, "example/Dependency")
        val unreadable = root.resolve("rules.pro")
        Files.writeString(unreadable, "-keep class example.App")
        assumeUnreadable(unreadable)
        val cache = ClassIndexCache(root.resolve("cache"), "engine-a")
        val spoolDirectory = root.resolve("spools").createDirectories()
        val scope = VerifiedCaptureScope.open(cache, spoolDirectory = spoolDirectory)
        try {
            val error = assertFailsWith<IllegalArgumentException> {
                scope.captureAll(root, listOf(
                    CaptureInput(jar, "classpath", "classpath-0"),
                    CaptureInput(unreadable, "keepRules", "keepRules-0"),
                    CaptureInput(root.resolve("missing-classes"), "classes", "classes-0"),
                ))
            }
            assertEquals("fingerprint input is missing", error.message)
            assertEquals(0, spoolFiles(spoolDirectory))
        } finally {
            scope.close()
            Files.setPosixFilePermissions(unreadable, PosixFilePermissions.fromString("rw-r--r--"))
        }
    }

    @Test
    fun `digests match an independent statement of the fingerprint format`(@TempDir root: Path) {
        val directory = root.resolve("classes").createDirectories()
        Files.writeString(directory.resolve("b.txt"), "second")
        Files.writeString(directory.resolve("a.txt"), "first")
        val file = root.resolve("single.jar")
        Files.writeString(file, "jar bytes")
        val scope = VerifiedCaptureScope.open(null)
        val captured = try {
            scope.captureAll(root, listOf(CaptureInput(directory, "classes", "classes-0"), CaptureInput(file, "classpath", "classpath-0")))
        } finally {
            scope.close()
        }

        val expectedDirectory = lengthPrefixed("directory", "a.txt", sha256("first"), "b.txt", sha256("second"))
        val expectedFile = lengthPrefixed("file", sha256("jar bytes"))
        assertEquals(listOf(expectedDirectory, expectedFile), captured.map(InputFingerprint::sha256))
    }

    @Test
    fun `interrupted caller leaves no spool residue and reports an interruption`(@TempDir root: Path) {
        val classes = root.resolve("classes")
        repeat(8) { index -> writeClassRoot(classes, "example/Class$index") }
        val jars = (0 until 4).map { index -> root.resolve("dependency-$index.jar").also { writeJar(it, "example/Dependency$index") } }
        val cache = ClassIndexCache(root.resolve("cache"), "engine-a")
        val spoolDirectory = root.resolve("spools").createDirectories()
        val scope = VerifiedCaptureScope.open(cache, spoolDirectory = spoolDirectory)
        val inputs = listOf(CaptureInput(classes, "classes", "classes-0")) +
            jars.mapIndexed { index, jar -> CaptureInput(jar, "classpath", "classpath-$index") }
        try {
            Thread.currentThread().interrupt()
            assertFailsWith<ClassIndexingException> { scope.captureAll(root, inputs) }
            assertTrue(Thread.interrupted(), "interrupt status is restored for the caller")
        } finally {
            Thread.interrupted()
            scope.close()
        }
        // close()가 worker 종료를 기다린 뒤 registry를 비우므로 폴링 없이 즉시 0이어야 한다.
        assertEquals(0, spoolFiles(spoolDirectory))
    }

    @Test
    fun `size ordered digest returns results in input order for many distinct jars`(@TempDir root: Path) {
        val classes = writeClassRoot(root.resolve("classes"), "example/App")
        val jars = (0 until 6).map { index ->
            root.resolve("dependency-$index.jar").also { jar ->
                JarOutputStream(Files.newOutputStream(jar)).use { output ->
                    output.putNextEntry(JarEntry("example/Dependency$index.class").apply { time = 1_700_000_000_000L })
                    output.write(classBytes("example/Dependency$index"))
                    output.closeEntry()
                    output.putNextEntry(JarEntry("padding.bin").apply { time = 1_700_000_000_000L })
                    output.write(ByteArray((6 - index) * 40_000) { (it % 251).toByte() })
                    output.closeEntry()
                }
            }
        }
        val inputs = listOf(CaptureInput(classes, "classes", "classes-0")) +
            (jars + listOf(jars[2])).mapIndexed { index, jar -> CaptureInput(jar, "classpath", "classpath-$index") }
        val direct = inputs.map { ContentFingerprint.capture(root, it.path, it.role, it.externalSlot) }
        val batchScope = VerifiedCaptureScope.open(null)
        val batch = try {
            batchScope.captureAll(root, inputs)
        } finally {
            batchScope.close()
        }

        assertEquals(direct, batch)
        assertEquals(batch[3], batch[7])
        assertEquals(7, batch.map(InputFingerprint::sha256).toSet().size)
    }

    @Test
    fun `file replaced by a symlink after planning is rejected at digest time`(@TempDir root: Path) {
        val rules = root.resolve("rules.pro")
        Files.writeString(rules, "-keep class example.App")
        val decoy = root.resolve("decoy.pro")
        Files.writeString(decoy, "-keep class example.Decoy")
        val request = ObservationRequest(rules, "keepRules", "keepRules-0", maximumSpoolBytes = 0, spoolDirectory = null, sizeHint = 0)

        // 계획(symlink 검사)은 통과시킨 뒤 digest 직전에 파일을 symlink로 바꾼다. O_NOFOLLOW 열기가 이를 거부해야 한다.
        assertFailsWith<IOException> {
            ContentFingerprint.captureObservedAll(root, listOf(request), ConcurrentLinkedQueue()) { jobs, digest ->
                Files.delete(rules)
                Files.createSymbolicLink(rules, decoy)
                jobs.map(digest)
            }
        }
    }

    @Test
    fun `spool failure returns its reservation only after the batch`(@TempDir root: Path) {
        val jars = (0 until 2).map { index -> root.resolve("dependency-$index.jar").also { writeJar(it, "example/Dependency$index") } }
        val budget = Files.size(jars[0])
        val cache = ClassIndexCache(root.resolve("cache"), "engine-a")
        val spoolDirectory = root.resolve("spools").createDirectories()
        val scope = VerifiedCaptureScope.open(cache, maximumSpoolBytes = budget, spoolDirectory = spoolDirectory)
        try {
            // 첫 묶음: spool 디렉터리를 쓰기 불가로 만들어 예약은 되지만 spool은 만들어지지 않게 한다.
            Files.setPosixFilePermissions(spoolDirectory, PosixFilePermissions.fromString("r-x------"))
            Assumptions.assumeFalse(Files.isWritable(spoolDirectory), "directory permissions are not enforced for this user")
            scope.captureAll(root, listOf(CaptureInput(jars[0], "classpath", "classpath-0")))
            assertEquals(0, spoolFiles(spoolDirectory))

            // 다음 묶음: 정산으로 예산이 돌아와 있어야 두 번째 JAR이 spool된다.
            Files.setPosixFilePermissions(spoolDirectory, PosixFilePermissions.fromString("rwx------"))
            scope.captureAll(root, listOf(CaptureInput(jars[1], "classpath", "classpath-1")))
            assertEquals(1, spoolFiles(spoolDirectory))
        } finally {
            Files.setPosixFilePermissions(spoolDirectory, PosixFilePermissions.fromString("rwx------"))
            scope.close()
        }
        assertEquals(0, spoolFiles(spoolDirectory))
    }

    private fun assumeUnreadable(file: Path) {
        Files.setPosixFilePermissions(file, PosixFilePermissions.fromString("---------"))
        Assumptions.assumeFalse(Files.isReadable(file), "file permissions are not enforced for this user")
    }

    private fun sha256(text: String): String = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(text.toByteArray()))

    /** ContentFingerprint와 독립적으로 "4바이트 길이 + UTF-8" 연쇄의 SHA-256을 계산한다. */
    private fun lengthPrefixed(vararg values: String): String {
        val digest = MessageDigest.getInstance("SHA-256")
        values.forEach { value ->
            val bytes = value.toByteArray()
            digest.update(ByteBuffer.allocate(4).putInt(bytes.size).array())
            digest.update(bytes)
        }
        return HexFormat.of().formatHex(digest.digest())
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
