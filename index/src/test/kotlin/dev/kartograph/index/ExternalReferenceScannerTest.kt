package dev.kartograph.index

import java.io.File
import java.nio.file.Path
import javax.tools.ToolProvider
import kotlin.io.path.createDirectories
import kotlin.io.path.writeBytes
import kotlin.io.path.writeText
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertTrue
import org.junit.jupiter.api.io.TempDir
import org.objectweb.asm.ClassWriter
import org.objectweb.asm.ConstantDynamic
import org.objectweb.asm.Handle
import org.objectweb.asm.Label
import org.objectweb.asm.Opcodes
import org.objectweb.asm.Type
import org.objectweb.asm.TypeReference

class ExternalReferenceScannerTest {
    @Test
    fun `scanner collects descriptors indy type annotations and constants`(@TempDir root: Path) {
        val classes = root.resolve("classes").createDirectories()
        classes.resolve("app/App.class").also { it.parent.createDirectories() }.writeBytes(referencingClass())

        val referenced = ExternalReferenceScanner().scan(listOf(classes))

        val expected = setOf(
            "lib/SuperType", "lib/InterfaceType", "lib/AnnotationType", "lib/TypeAnnotation",
            "lib/FieldType", "lib/FieldTypeAnnotation", "lib/ParamType", "lib/ReturnType", "lib/ThrownType",
            "lib/MethodTypeAnnotation", "lib/ParamAnnotationType", "lib/AnnotationValueType", "lib/EnumType",
            "lib/CallOwner", "lib/CallParam", "lib/FieldOwner", "lib/FieldValue", "lib/NewType",
            "lib/CatchType", "lib/CatchAnnotationType", "lib/InsnAnnotationType",
            "lib/LocalType", "lib/LocalAnnotationType", "lib/MultiType", "lib/LiteralType",
            "lib/LambdaType", "lib/BootstrapOwner", "lib/IndyArgType",
            "lib/DynamicType", "lib/DynamicArgType",
            "lib/NestType", "lib/PermittedType", "lib/RecordType",
        )
        assertTrue(expected.all(referenced::contains), "missing references: ${expected - referenced}")
    }

    @Test
    fun `javac lambda interface referenced only through invokedynamic descriptor is collected`(@TempDir root: Path) {
        val libClasses = root.resolve("lib-classes").createDirectories()
        compileJava(
            listOf(
                source(root, "lib-src", "com/example/lib/Fn.java", "package com.example.lib; public interface Fn { void run(); }\n"),
                source(
                    root, "lib-src", "com/example/lib/Api.java",
                    "package com.example.lib; public class Api { public static void register(Fn fn) { fn.run(); } }\n",
                ),
            ),
            libClasses,
        )
        val appClasses = root.resolve("app-classes").createDirectories()
        compileJava(
            listOf(
                source(
                    root, "app-src", "com/example/app/App.java",
                    "package com.example.app; public class App { public static void main(String[] args) { com.example.lib.Api.register(() -> {}); } }\n",
                ),
            ),
            appClasses,
            classpath = listOf(libClasses),
        )

        val referenced = ExternalReferenceScanner().scan(listOf(appClasses))

        assertContains(referenced, "com/example/lib/Fn")
        assertContains(referenced, "com/example/lib/Api")
    }

    private fun source(root: Path, directory: String, relative: String, content: String): Path =
        root.resolve(directory).resolve(relative).also { path ->
            path.parent.createDirectories()
            path.writeText(content)
        }

    private fun compileJava(sources: List<Path>, classes: Path, classpath: List<Path> = emptyList()) {
        val arguments = buildList {
            add("-g")
            add("-d")
            add(classes.toString())
            if (classpath.isNotEmpty()) {
                add("-cp")
                add(classpath.joinToString(File.pathSeparator))
            }
            sources.forEach { add(it.toString()) }
        }
        check(requireNotNull(ToolProvider.getSystemJavaCompiler()).run(null, null, null, *arguments.toTypedArray()) == 0)
    }

    private fun referencingClass(): ByteArray {
        val writer = ClassWriter(0)
        writer.visit(Opcodes.V17, Opcodes.ACC_PUBLIC, "app/App", null, "lib/SuperType", arrayOf("lib/InterfaceType"))
        writer.visitAnnotation("Llib/AnnotationType;", true).visitEnd()
        writer.visitTypeAnnotation(
            TypeReference.newTypeReference(TypeReference.CLASS_EXTENDS).value, null, "Llib/TypeAnnotation;", true,
        ).visitEnd()
        writer.visitNestMember("lib/NestType")
        writer.visitPermittedSubclass("lib/PermittedType")

        val component = writer.visitRecordComponent("component", "Llib/RecordType;", null)
        component.visitAnnotation("Llib/RecordAnnotationType;", true).visitEnd()
        component.visitEnd()

        val field = writer.visitField(Opcodes.ACC_PRIVATE, "field", "Llib/FieldType;", null, null)
        field.visitAnnotation("Llib/FieldAnnotationType;", true).visitEnd()
        field.visitTypeAnnotation(
            TypeReference.newTypeReference(TypeReference.FIELD).value, null, "Llib/FieldTypeAnnotation;", true,
        ).visitEnd()
        field.visitEnd()

        val method = writer.visitMethod(
            Opcodes.ACC_PUBLIC, "run", "(Llib/ParamType;)Llib/ReturnType;", null, arrayOf("lib/ThrownType"),
        )
        method.visitAnnotation("Llib/MethodAnnotationType;", true).apply {
            visit("value", Type.getObjectType("lib/AnnotationValueType"))
            visitEnum("kind", "Llib/EnumType;", "VALUE")
            visitEnd()
        }
        method.visitTypeAnnotation(
            TypeReference.newTypeReference(TypeReference.METHOD_RETURN).value, null, "Llib/MethodTypeAnnotation;", true,
        ).visitEnd()
        method.visitParameterAnnotation(
            0, "Llib/ParamAnnotationType;", true,
        ).visitEnd()
        method.visitCode()
        val start = Label()
        val end = Label()
        val handler = Label()
        method.visitLabel(start)
        method.visitMethodInsn(Opcodes.INVOKESTATIC, "lib/CallOwner", "call", "(Llib/CallParam;)V", false)
        method.visitFieldInsn(Opcodes.GETSTATIC, "lib/FieldOwner", "VALUE", "Llib/FieldValue;")
        method.visitTypeInsn(Opcodes.NEW, "lib/NewType")
        method.visitInsn(Opcodes.POP)
        method.visitLdcInsn(Type.getObjectType("lib/LiteralType"))
        method.visitInsn(Opcodes.POP)
        method.visitMultiANewArrayInsn("[[Llib/MultiType;", 2)
        method.visitInsn(Opcodes.POP)
        val bootstrap = Handle(
            Opcodes.H_INVOKESTATIC,
            "lib/BootstrapOwner",
            "bootstrap",
            "(Ljava/lang/invoke/MethodHandles\$Lookup;Ljava/lang/String;Ljava/lang/invoke/MethodType;)Ljava/lang/invoke/CallSite;",
            false,
        )
        method.visitInvokeDynamicInsn("lambda", "()Llib/LambdaType;", bootstrap, Type.getObjectType("lib/IndyArgType"))
        method.visitInsn(Opcodes.POP)
        method.visitLdcInsn(
            ConstantDynamic("constant", "Llib/DynamicType;", bootstrap, Type.getObjectType("lib/DynamicArgType")),
        )
        method.visitInsn(Opcodes.POP)
        method.visitLabel(end)
        method.visitTryCatchBlock(start, end, handler, "lib/CatchType")
        method.visitTryCatchAnnotation(
            TypeReference.newTypeReference(TypeReference.EXCEPTION_PARAMETER).value, null, "Llib/CatchAnnotationType;", true,
        ).visitEnd()
        method.visitInsnAnnotation(
            TypeReference.newTypeReference(TypeReference.NEW).value, null, "Llib/InsnAnnotationType;", true,
        ).visitEnd()
        method.visitLocalVariable("local", "Llib/LocalType;", null, start, end, 0)
        method.visitLocalVariableAnnotation(
            TypeReference.newTypeReference(TypeReference.LOCAL_VARIABLE).value, null,
            arrayOf(start), arrayOf(end), intArrayOf(0), "Llib/LocalAnnotationType;", true,
        ).visitEnd()
        method.visitInsn(Opcodes.RETURN)
        method.visitMaxs(2, 2)
        method.visitEnd()
        writer.visitEnd()
        return writer.toByteArray()
    }
}
