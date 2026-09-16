package dev.kartograph.index

import dev.kartograph.core.JvmModifier
import java.io.ByteArrayOutputStream
import java.nio.file.Files
import java.nio.file.Path
import javax.tools.ToolProvider
import kotlin.io.path.createDirectories
import kotlin.io.path.writeBytes
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import org.junit.jupiter.api.io.TempDir
import org.objectweb.asm.ClassWriter
import org.objectweb.asm.Opcodes

class ExactDispatchRegressionTest {
    @Test
    fun `actual class final access overrides conflicting InnerClasses final metadata`(@TempDir root: Path) {
        val classes = root.resolve("classes").createDirectories()
        val spoofedFinal = "probe/Outer\$SpoofedFinal"
        val actualFinal = "probe/Outer\$ActualFinal"
        writeClass(classes, spoofedFinal, helperClass(spoofedFinal, 0, Opcodes.ACC_FINAL))
        writeClass(classes, actualFinal, helperClass(actualFinal, Opcodes.ACC_FINAL, 0))
        writeClass(classes, "probe/Entry", callerClass(spoofedFinal, actualFinal))
        writeClass(classes, "probe/Used", emptyClass("probe/Used"))

        val indexed = ClassFileIndexer().indexWithObservations(listOf(classes))

        assertFalse(JvmModifier.FINAL in requireNotNull(indexed.graph.node(JvmNodeId.classId(spoofedFinal))).jvmModifiers)
        assertTrue(JvmModifier.FINAL in requireNotNull(indexed.graph.node(JvmNodeId.classId(actualFinal))).jvmModifiers)
        assertEquals(
            emptyList(),
            indexed.graph.externalCalls.single { it.caller == JvmNodeId.methodId("probe/Entry", "spoofed", "()V") }
                .resolvedTargets.map { it.value },
        )
        assertEquals(
            listOf("class:probe/Used"),
            indexed.graph.externalCalls.single { it.caller == JvmNodeId.methodId("probe/Entry", "actual", "()V") }
                .resolvedTargets.map { it.value },
        )
    }

    @Test
    fun `discarded final getters do not consume return context budget`(@TempDir root: Path) {
        val getters = (0 until 129).joinToString("\n") { index ->
            "final String value$index() { return \"unused.$index\"; }"
        }
        val calls = (0 until 129).joinToString("\n") { index -> "entry.value$index();" }
        val classes = compileJava(root, """
            package probe;
            public final class Entry {
                $getters
                static void read() throws Exception {
                    Entry entry = new Entry();
                    $calls
                    Class.forName("probe.Used");
                }
            }
            class Used {}
        """.trimIndent())

        val indexed = ClassFileIndexer().indexWithObservations(listOf(classes))
        val call = indexed.graph.externalCalls.single { it.name == "forName" }

        assertEquals(listOf("class:probe/Used"), call.resolvedTargets.map { it.value })
        assertEquals(0, indexed.observations.sumOf { it.valueAnalysisLimits })
    }

    private fun helperClass(internalName: String, classFinal: Int, innerFinal: Int): ByteArray =
        ClassWriter(ClassWriter.COMPUTE_MAXS).apply {
            visit(Opcodes.V17, Opcodes.ACC_PUBLIC or classFinal, internalName, null, "java/lang/Object", null)
            visitInnerClass(
                internalName,
                "probe/Outer",
                internalName.substringAfterLast('$'),
                Opcodes.ACC_PUBLIC or Opcodes.ACC_STATIC or innerFinal,
            )
            visitMethod(Opcodes.ACC_PUBLIC, "<init>", "()V", null, null).apply {
                visitCode()
                visitVarInsn(Opcodes.ALOAD, 0)
                visitMethodInsn(Opcodes.INVOKESPECIAL, "java/lang/Object", "<init>", "()V", false)
                visitInsn(Opcodes.RETURN)
                visitMaxs(0, 0)
                visitEnd()
            }
            visitMethod(Opcodes.ACC_PUBLIC, "value", "()Ljava/lang/String;", null, null).apply {
                visitCode()
                visitLdcInsn("probe.Used")
                visitInsn(Opcodes.ARETURN)
                visitMaxs(0, 0)
                visitEnd()
            }
            visitEnd()
        }.toByteArray()

    private fun callerClass(spoofedFinal: String, actualFinal: String): ByteArray =
        ClassWriter(ClassWriter.COMPUTE_MAXS).apply {
            visit(Opcodes.V17, Opcodes.ACC_PUBLIC or Opcodes.ACC_FINAL, "probe/Entry", null, "java/lang/Object", null)
            callMethod("spoofed", spoofedFinal)
            callMethod("actual", actualFinal)
            visitEnd()
        }.toByteArray()

    private fun ClassWriter.callMethod(name: String, helper: String) {
        visitMethod(Opcodes.ACC_PUBLIC or Opcodes.ACC_STATIC, name, "()V", null, null).apply {
            visitCode()
            visitTypeInsn(Opcodes.NEW, helper)
            visitInsn(Opcodes.DUP)
            visitMethodInsn(Opcodes.INVOKESPECIAL, helper, "<init>", "()V", false)
            visitMethodInsn(Opcodes.INVOKEVIRTUAL, helper, "value", "()Ljava/lang/String;", false)
            visitMethodInsn(
                Opcodes.INVOKESTATIC,
                "java/lang/Class",
                "forName",
                "(Ljava/lang/String;)Ljava/lang/Class;",
                false,
            )
            visitInsn(Opcodes.POP)
            visitInsn(Opcodes.RETURN)
            visitMaxs(0, 0)
            visitEnd()
        }
    }

    private fun emptyClass(internalName: String): ByteArray = ClassWriter(0).apply {
        visit(Opcodes.V17, Opcodes.ACC_PUBLIC, internalName, null, "java/lang/Object", null)
        visitEnd()
    }.toByteArray()

    private fun writeClass(root: Path, internalName: String, bytes: ByteArray) {
        root.resolve("$internalName.class").also { it.parent.createDirectories() }.writeBytes(bytes)
    }

    private fun compileJava(root: Path, source: String): Path {
        val sourceFile = root.resolve("Entry.java")
        Files.writeString(sourceFile, source)
        val classes = root.resolve("javac-classes").createDirectories()
        val errors = ByteArrayOutputStream()
        val compiler = requireNotNull(ToolProvider.getSystemJavaCompiler())
        assertEquals(
            0,
            compiler.run(null, null, errors, "--release", "17", "-g", "-d", classes.toString(), sourceFile.toString()),
            errors.toString(),
        )
        return classes
    }
}
