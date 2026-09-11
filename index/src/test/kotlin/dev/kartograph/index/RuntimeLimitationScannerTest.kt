package dev.kartograph.index

import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.FileTime
import java.util.jar.JarEntry
import java.util.jar.JarOutputStream
import kotlin.io.path.createDirectories
import kotlin.io.path.writeBytes
import kotlin.io.path.writeText
import kotlin.test.Test
import kotlin.test.assertEquals
import org.junit.jupiter.api.io.TempDir
import org.objectweb.asm.ClassWriter
import org.objectweb.asm.Opcodes

class RuntimeLimitationScannerTest {
    @Test
    fun `DOS timestamp rounding is unknown rather than stale and later changes remain stale`(@TempDir root: Path) {
        val writer = ClassWriter(0)
        writer.visit(Opcodes.V17, Opcodes.ACC_PUBLIC, "A", null, "java/lang/Object", null)
        writer.visitSource("A.java", null)
        writer.visitEnd()
        val source = root.resolve("A.java").apply { writeText("class A {}") }
        val jar = root.resolve("classes.jar")
        // DOS 시각은 2초 단위이며 extra timestamp가 없는 일반 ZIP의 손실을 재현한다.
        val timestamp = 1_700_000_000_000L
        JarOutputStream(Files.newOutputStream(jar)).use { output ->
            output.putNextEntry(JarEntry("A.class").apply { time = timestamp })
            output.write(writer.toByteArray())
            output.closeEntry()
        }
        Files.setLastModifiedTime(source, FileTime.fromMillis(timestamp + 900))
        assertEquals(
            listOf("index-freshness-unknown: 1 of 1 source file(s) could not be matched unambiguously to compiled source metadata"),
            RuntimeLimitationScanner.scan(listOf(jar), root),
        )
        Files.setLastModifiedTime(source, FileTime.fromMillis(timestamp + 2_000))
        assertEquals(listOf("index-staleness: 1 of 1 source file(s) changed after a matching class file"),
            RuntimeLimitationScanner.scan(listOf(jar), root))
        Files.setLastModifiedTime(source, FileTime.fromMillis(timestamp - 1))
        assertEquals(emptyList(), RuntimeLimitationScanner.scan(listOf(jar), root))
    }

    @Test
    fun `unresolved indirect external supertypes remain measured`(@TempDir root: Path) {
        val owner = dev.kartograph.core.GraphNode(JvmNodeId.classId("app/Abstract"), "Abstract",
            dev.kartograph.core.NodeKind.CLASS, jvmSignature = "app/Abstract", supertypes = setOf("lib/Child"))
        val caller = dev.kartograph.core.GraphNode(JvmNodeId.methodId("app/Entry", "run", "()V"), "run",
            dev.kartograph.core.NodeKind.METHOD)
        val call = dev.kartograph.core.ExternalCall(caller.id, "lib/Parent", "run", "()V", dev.kartograph.core.InvocationKind.INTERFACE)
        val graph = dev.kartograph.core.CodeGraph(listOf(owner, caller), emptyList(), listOf(call))
        val indexed = IndexedClasses(graph, emptyList()).withHierarchy(
            dev.kartograph.core.ClassHierarchy(mapOf("lib/Child" to setOf("lib/Parent"))))
        assertEquals(listOf("external-dispatch: 1 external virtual call(s) have no project implementation target"),
            RuntimeLimitationScanner.scan(indexed, root))
    }

    @Test
    fun `real Kotlin metadata preserves runtime observations`(@TempDir root: Path) {
        val type = dev.kartograph.index.fixture.RuntimeObservationFixture::class.java
        val bytes = requireNotNull(type.getResourceAsStream("RuntimeObservationFixture.class")).use { it.readBytes() }
        val classes = root.resolve("classes").createDirectories()
        classes.resolve("RuntimeObservationFixture.class").writeBytes(bytes)
        val indexed = ClassFileIndexer().indexWithObservations(listOf(classes))
        val observation = indexed.observations.single()
        assertEquals("RuntimeObservationFixture.kt", observation.sourceFile)
        assertEquals(1, observation.nativeMethods)
        assertEquals(1, observation.reflectionCalls)
        assertEquals(2, RuntimeLimitationScanner.scan(indexed, root).size)
    }

    @Test
    fun `reuses observations after class input is no longer available`(@TempDir root: Path) {
        val classes = root.resolve("classes").createDirectories()
        val writer = ClassWriter(0)
        writer.visit(Opcodes.V17, Opcodes.ACC_PUBLIC, "A", null, "java/lang/Object", null)
        writer.visitMethod(Opcodes.ACC_PUBLIC or Opcodes.ACC_NATIVE, "call", "()V", null, null).visitEnd()
        writer.visitEnd()
        val file = classes.resolve("A.class")
        file.writeBytes(writer.toByteArray())
        val indexed = ClassFileIndexer().indexWithObservations(listOf(classes))
        Files.delete(file)
        assertEquals(listOf("jni-methods: 1 native method(s) may be called outside the JVM graph"),
            RuntimeLimitationScanner.scan(indexed, root))
    }

    @Test
    fun `reports unmatched and ambiguous source freshness as unknown`(@TempDir root: Path) {
        val classes = root.resolve("classes").createDirectories()
        val writer = ClassWriter(0)
        writer.visit(Opcodes.V17, Opcodes.ACC_PUBLIC, "A", null, "java/lang/Object", null)
        writer.visitSource("A.kt", null)
        writer.visitEnd()
        classes.resolve("A.class").writeBytes(writer.toByteArray())
        root.resolve("A.kt").writeText("class A")
        root.resolve("other").createDirectories().resolve("A.kt").writeText("class A")
        root.resolve("New.kt").writeText("class New")
        assertEquals(listOf("index-freshness-unknown: 3 of 3 source file(s) could not be matched unambiguously to compiled source metadata"),
            RuntimeLimitationScanner.scan(listOf(classes), root))
    }

    @Test
    fun `unrelated fresh class cannot hide stale source`(@TempDir root: Path) {
        val classes = root.resolve("classes").createDirectories()
        for ((name, modified) in listOf("A" to 1_000L, "B" to 3_000L)) {
            val writer = ClassWriter(0)
            writer.visit(Opcodes.V17, Opcodes.ACC_PUBLIC, name, null, "java/lang/Object", null)
            writer.visitSource("$name.java", null)
            writer.visitEnd()
            classes.resolve("$name.class").writeBytes(writer.toByteArray())
            classes.resolve("$name.class").toFile().setLastModified(modified)
        }
        root.resolve("A.java").writeText("class A {}")
        root.resolve("A.java").toFile().setLastModified(2_000L)
        val limitations = RuntimeLimitationScanner.scan(listOf(classes), root)
        kotlin.test.assertTrue(limitations.any { it.startsWith("index-staleness: 1 of 1") })
    }

    @Test
    fun `counts reflection JNI dynamic registration and stale sources`(@TempDir root: Path) {
        val classes = root.resolve("classes").createDirectories()
        val writer = ClassWriter(0)
        writer.visit(Opcodes.V17, Opcodes.ACC_PUBLIC, "app/RuntimeUse", null, "java/lang/Object", null)
        writer.visitSource("RuntimeUse.kt", null)
        writer.visitMethod(Opcodes.ACC_PUBLIC or Opcodes.ACC_NATIVE, "nativeCall", "()V", null, null).visitEnd()
        writer.visitMethod(Opcodes.ACC_PUBLIC, "run", "()V", null, null).also { method ->
            method.visitCode()
            method.visitLdcInsn("app.Plugin")
            method.visitMethodInsn(Opcodes.INVOKESTATIC, "java/lang/Class", "forName", "(Ljava/lang/String;)Ljava/lang/Class;", false)
            method.visitInsn(Opcodes.POP)
            method.visitInsn(Opcodes.ACONST_NULL)
            method.visitInsn(Opcodes.ACONST_NULL)
            method.visitMethodInsn(Opcodes.INVOKEVIRTUAL, "android/content/Context", "registerReceiver", "(Ljava/lang/Object;)V", false)
            method.visitInsn(Opcodes.RETURN)
            method.visitMaxs(2, 1)
            method.visitEnd()
        }
        writer.visitEnd()
        val classFile = classes.resolve("RuntimeUse.class")
        classFile.writeBytes(writer.toByteArray())
        root.resolve("RuntimeUse.kt").writeText("class RuntimeUse")
        // 어시스턴트 디렉터리의 파일은 project source 집계에서 제외한다.
        root.resolve(".claude/Later.kt").also { source ->
            source.parent.createDirectories()
            source.writeText("class Later")
            source.toFile().setLastModified(3_000)
        }
        root.resolve(".worktrees/copy/Later.kt").also { source ->
            source.parent.createDirectories()
            source.writeText("class LaterCopy")
            source.toFile().setLastModified(4_000)
        }
        classFile.toFile().setLastModified(1_000)
        root.resolve("RuntimeUse.kt").toFile().setLastModified(2_000)

        val limitations = RuntimeLimitationScanner.scan(listOf(classes), root)

        assertEquals(
            listOf(
                "dynamic-registration: 1 runtime component registration call(s) are absent from the manifest graph",
                "index-staleness: 1 of 1 source file(s) changed after a matching class file",
                "jni-methods: 1 native method(s) may be called outside the JVM graph",
                "runtime-targets-outside-graph: 1 resolved runtime target site(s) have no matching project declaration",
            ),
            limitations,
        )
    }

    @Test
    fun `scans JAR class roots and ignores unrelated registration names`(@TempDir root: Path) {
        val writer = ClassWriter(0)
        writer.visit(Opcodes.V17, Opcodes.ACC_PUBLIC, "app/JarRuntime", null, "java/lang/Object", null)
        writer.visitMethod(Opcodes.ACC_PUBLIC or Opcodes.ACC_NATIVE, "nativeCall", "()V", null, null).visitEnd()
        writer.visitMethod(Opcodes.ACC_PUBLIC, "run", "()V", null, null).also { method ->
            method.visitCode()
            method.visitMethodInsn(Opcodes.INVOKESTATIC, "app/Fake", "registerReceiver", "()V", false)
            method.visitInsn(Opcodes.RETURN)
            method.visitMaxs(0, 1)
            method.visitEnd()
        }
        writer.visitEnd()
        val jar = root.resolve("classes.jar")
        JarOutputStream(Files.newOutputStream(jar)).use { output ->
            output.putNextEntry(JarEntry("app/JarRuntime.class").apply { time = 1_000 })
            output.write(writer.toByteArray())
            output.closeEntry()
        }
        root.resolve("src/test/Test.kt").also { source ->
            source.parent.createDirectories()
            source.writeText("class Test")
            source.toFile().setLastModified(2_000)
        }

        val limitations = RuntimeLimitationScanner.scan(listOf(jar), root)

        assertEquals(listOf("jni-methods: 1 native method(s) may be called outside the JVM graph"), limitations)
    }
}
