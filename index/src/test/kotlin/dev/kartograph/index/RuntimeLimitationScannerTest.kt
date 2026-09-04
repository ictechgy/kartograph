package dev.kartograph.index

import java.nio.file.Files
import java.nio.file.Path
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
    fun `counts reflection JNI dynamic registration and stale sources`(@TempDir root: Path) {
        val classes = root.resolve("classes").createDirectories()
        val writer = ClassWriter(0)
        writer.visit(Opcodes.V17, Opcodes.ACC_PUBLIC, "app/RuntimeUse", null, "java/lang/Object", null)
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
        classFile.toFile().setLastModified(1_000)
        root.resolve("RuntimeUse.kt").toFile().setLastModified(2_000)

        val limitations = RuntimeLimitationScanner.scan(listOf(classes), root)

        assertEquals(
            listOf(
                "dynamic-registration: 1 runtime component registration call(s) are absent from the manifest graph",
                "index-staleness: 1 of 1 source file(s) changed after the newest class file",
                "jni-methods: 1 native method(s) may be called outside the JVM graph",
                "reflection-strings: 1 Class.forName call(s) use runtime names",
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
