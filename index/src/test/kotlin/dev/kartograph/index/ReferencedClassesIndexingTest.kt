package dev.kartograph.index

import java.nio.file.Path
import kotlin.io.path.createDirectories
import kotlin.io.path.writeBytes
import kotlin.test.Test
import kotlin.test.assertTrue
import org.junit.jupiter.api.io.TempDir
import org.objectweb.asm.ClassWriter
import org.objectweb.asm.Opcodes
import org.objectweb.asm.Type

class ReferencedClassesIndexingTest {
    @Test
    fun `indexed classes collect call field type annotation and signature owners`(@TempDir root: Path) {
        val classes = root.resolve("classes").createDirectories()
        classes.resolve("app/App.class").also { it.parent.createDirectories() }.writeBytes(referencingClass())

        val referenced = ClassFileIndexer().indexWithObservations(listOf(classes)).referencedClasses

        assertTrue(
            setOf(
                "lib/SuperType",
                "lib/InterfaceType",
                "lib/AnnotationType",
                "lib/FieldType",
                "lib/ParamType",
                "lib/ReturnType",
                "lib/ThrownType",
                "lib/CallTarget",
                "lib/FieldTarget",
                "lib/TypeTarget",
                "lib/ConstantTarget",
            ).all(referenced::contains),
            "missing references: ${referenced - setOf("app/App")}",
        )
    }

    private fun referencingClass(): ByteArray {
        val writer = ClassWriter(0)
        writer.visit(Opcodes.V17, Opcodes.ACC_PUBLIC, "app/App", null, "lib/SuperType", arrayOf("lib/InterfaceType"))
        writer.visitAnnotation("Llib/AnnotationType;", true).visitEnd()
        writer.visitField(Opcodes.ACC_PRIVATE, "field", "Llib/FieldType;", null, null).visitEnd()
        val method = writer.visitMethod(
            Opcodes.ACC_PUBLIC,
            "run",
            "(Llib/ParamType;)Llib/ReturnType;",
            null,
            arrayOf("lib/ThrownType"),
        )
        method.visitCode()
        method.visitMethodInsn(Opcodes.INVOKESTATIC, "lib/CallTarget", "call", "()V", false)
        method.visitFieldInsn(Opcodes.GETSTATIC, "lib/FieldTarget", "VALUE", "I")
        method.visitInsn(Opcodes.POP)
        method.visitTypeInsn(Opcodes.NEW, "lib/TypeTarget")
        method.visitInsn(Opcodes.POP)
        method.visitLdcInsn(Type.getObjectType("lib/ConstantTarget"))
        method.visitInsn(Opcodes.POP)
        method.visitInsn(Opcodes.ACONST_NULL)
        method.visitInsn(Opcodes.ARETURN)
        method.visitMaxs(1, 2)
        method.visitEnd()
        writer.visitEnd()
        return writer.toByteArray()
    }
}
