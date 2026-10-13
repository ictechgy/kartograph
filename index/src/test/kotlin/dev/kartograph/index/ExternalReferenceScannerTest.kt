package dev.kartograph.index

import java.io.File
import java.nio.file.Path
import java.util.jar.JarEntry
import java.util.jar.JarOutputStream
import javax.tools.ToolProvider
import kotlin.io.path.createDirectories
import kotlin.io.path.readBytes
import kotlin.io.path.writeBytes
import kotlin.io.path.writeText
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import org.junit.jupiter.api.io.TempDir
import org.objectweb.asm.ClassReader
import org.objectweb.asm.ClassVisitor
import org.objectweb.asm.ClassWriter
import org.objectweb.asm.MethodVisitor
import org.objectweb.asm.ConstantDynamic
import org.objectweb.asm.Handle
import org.objectweb.asm.Label
import org.objectweb.asm.Opcodes
import org.objectweb.asm.Type
import org.objectweb.asm.TypeReference

class ExternalReferenceScannerTest {
    @Test
    fun `generic signature types remain dependency uses after descriptor erasure`(@TempDir root: Path) {
        val library = root.resolve("library").createDirectories()
        compileJava(listOf(source(root, "lib-src", "lib/GenericOnly.java",
            "package lib; public class GenericOnly {}")), library)
        val app = root.resolve("classes").createDirectories()
        compileJava(listOf(source(root, "app-src", "app/Api.java", """
            package app;
            public class Api {
              public java.util.List<lib.GenericOnly> values;
              private String ignored = "lib.Decoy";
            }
        """.trimIndent())), app, listOf(library))
        val referenced = ExternalReferenceScanner().scan(listOf(app))
        assertContains(referenced, "lib/GenericOnly")
        assertTrue("lib/Decoy" !in referenced)
    }

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
            "lib/NestType", "lib/NestHostType", "lib/PermittedType", "lib/RecordType",
            "lib/HandleOwner", "lib/HandleDescType", "lib/LdcHandleOwner", "lib/LdcHandleDescType",
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

    @Test
    fun `javac generic method lambda retains erased bounds and bootstrap references`(@TempDir root: Path) {
        val library = root.resolve("library").createDirectories()
        compileJava(listOf(
            source(root, "lib-src", "lib/Bound.java", "package lib; public class Bound {}"),
            source(root, "lib-src", "lib/SignatureOnly.java", "package lib; public interface SignatureOnly {}"),
            source(root, "lib-src", "lib/Fn.java", "package lib; public interface Fn { void run(); }"),
            source(root, "lib-src", "lib/Api.java", "package lib; public class Api { public static void use(Bound value) {} }"),
            source(root, "lib-src", "lib/Decoy.java", "package lib; public class Decoy {}"),
        ), library)
        val app = root.resolve("classes").createDirectories()
        compileJava(listOf(source(root, "app-src", "app/GenericFactory.java", """
            package app;
            public class GenericFactory {
              private static final String DECOY = "lib.Decoy";
              public static <T extends lib.Bound & lib.SignatureOnly> lib.Fn create(T value) {
                return () -> lib.Api.use(value);
              }
            }
        """.trimIndent())), app, listOf(library))
        val bytes = app.resolve("app/GenericFactory.class").readBytes()
        var methodSignature: String? = null
        var dynamicCalls = 0
        ClassReader(bytes).accept(object : ClassVisitor(Opcodes.ASM9) {
            override fun visitMethod(access: Int, name: String, descriptor: String, signature: String?,
                exceptions: Array<out String>?): MethodVisitor? {
                if (name != "create") return null
                methodSignature = signature
                assertEquals("(Llib/Bound;)Llib/Fn;", descriptor)
                return object : MethodVisitor(Opcodes.ASM9) {
                    override fun visitInvokeDynamicInsn(name: String, descriptor: String,
                        bootstrapMethodHandle: Handle, vararg bootstrapMethodArguments: Any) {
                        dynamicCalls++
                        assertEquals("run", name)
                        assertEquals("(Llib/Bound;)Llib/Fn;", descriptor)
                        assertEquals("java/lang/invoke/LambdaMetafactory", bootstrapMethodHandle.owner)
                    }
                }
            }
        }, ClassReader.SKIP_FRAMES)
        assertEquals("<T:Llib/Bound;:Llib/SignatureOnly;>(TT;)Llib/Fn;", methodSignature)
        assertEquals(1, dynamicCalls)
        val expected = setOf("lib/Bound", "lib/SignatureOnly", "lib/Fn", "lib/Api")
        val scanner = ExternalReferenceScanner()
        assertEquals(expected, scanner.scan(listOf(app)).filter { it.startsWith("lib/") }.toSet())
        val jar = root.resolve("app.jar")
        JarOutputStream(jar.toFile().outputStream()).use { output ->
            output.putNextEntry(JarEntry("app/GenericFactory.class"))
            output.write(bytes)
            output.closeEntry()
        }
        assertEquals(expected, scanner.scan(listOf(jar)).filter { it.startsWith("lib/") }.toSet())
        // CLI/Gradle 의존성 입력도 같은 visitor를 사용한다. 구현 참조와 공개 ABI를 각각 확인한다.
        for (input in listOf(app, jar)) {
            val usage = DependencyUsageScanner().scan(listOf(input))
            assertEquals(expected, usage.referencedClasses.filter { it.startsWith("lib/") }.toSet())
            assertEquals(setOf("lib/Bound", "lib/SignatureOnly", "lib/Fn"),
                usage.apiClasses.filter { it.startsWith("lib/") }.toSet())
            assertEquals(setOf("app/GenericFactory"), usage.declaredClasses)
            assertTrue(usage.apiComplete)
        }
    }

    @Test
    fun `malformed and excessive method signatures still fail before instruction scanning`(@TempDir root: Path) {
        val signatures = listOf("(", "()" + "[".repeat(514) + "Ljava/lang/String;")
        signatures.forEachIndexed { index, signature ->
            val classes = root.resolve("broken-$index").createDirectories()
            classes.resolve("Broken.class").writeBytes(ClassWriter(0).apply {
                visit(Opcodes.V17, Opcodes.ACC_PUBLIC, "app/Broken", null, "java/lang/Object", null)
                visitMethod(Opcodes.ACC_PUBLIC or Opcodes.ACC_STATIC, "run", "()V", signature, null).apply {
                    visitCode()
                    visitInsn(Opcodes.RETURN)
                    visitMaxs(0, 0)
                    visitEnd()
                }
                visitEnd()
            }.toByteArray())
            val failure = assertFailsWith<ClassIndexingException> { ExternalReferenceScanner().scan(listOf(classes)) }
            assertEquals("invalid class file", failure.message)
        }
    }

    @Test
    fun `javac method reference type only in the handle descriptor is collected`(@TempDir root: Path) {
        val libClasses = root.resolve("lib-classes").createDirectories()
        compileJava(
            listOf(
                source(root, "lib-src", "com/example/lib/Parent.java", "package com.example.lib; public class Parent {}\n"),
                source(
                    root, "lib-src", "com/example/lib/Api.java",
                    "package com.example.lib; public class Api { public static void consume(Parent parent) {} }\n",
                ),
            ),
            libClasses,
        )
        val appClasses = root.resolve("app-classes").createDirectories()
        compileJava(
            listOf(
                source(
                    root, "app-src", "com/example/app/App.java",
                    "package com.example.app;\n" +
                        "import com.example.lib.Api;\n" +
                        "import com.example.lib.Parent;\n" +
                        "import java.util.function.Consumer;\n" +
                        "public class App { public static void main(String[] args) { Consumer<Parent> consumer = Api::consume; consumer.accept(null); } }\n",
                ),
            ),
            appClasses,
            classpath = listOf(libClasses),
        )

        val referenced = ExternalReferenceScanner().scan(listOf(appClasses))

        // Parent는 generic erasure로 사라지고 method handle descriptor에만 남는다.
        assertContains(referenced, "com/example/lib/Parent")
        assertContains(referenced, "com/example/lib/Api")
    }

    @Test
    fun `module descriptors contribute uses provides and main class`(@TempDir root: Path) {
        val classes = root.resolve("classes").createDirectories()
        val writer = ClassWriter(0)
        writer.visit(Opcodes.V17, Opcodes.ACC_MODULE, "module-info", null, null, null)
        val module = writer.visitModule("app.module", 0, null)
        module.visitUse("lib/ServiceType")
        module.visitProvide("lib/ServiceType", "lib/ProviderType")
        module.visitMainClass("lib/MainType")
        module.visitEnd()
        writer.visitEnd()
        classes.resolve("module-info.class").writeBytes(writer.toByteArray())

        val referenced = ExternalReferenceScanner().scan(listOf(classes))

        assertTrue(
            setOf("lib/ServiceType", "lib/ProviderType", "lib/MainType").all(referenced::contains),
            "missing references: $referenced",
        )
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
        writer.visitNestHost("lib/NestHostType")
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
        method.visitInvokeDynamicInsn(
            "lambda", "()Llib/LambdaType;", bootstrap, Type.getObjectType("lib/IndyArgType"),
            Handle(Opcodes.H_INVOKESTATIC, "lib/HandleOwner", "call", "(Llib/HandleDescType;)V", false),
        )
        method.visitInsn(Opcodes.POP)
        method.visitLdcInsn(
            ConstantDynamic("constant", "Llib/DynamicType;", bootstrap, Type.getObjectType("lib/DynamicArgType")),
        )
        method.visitInsn(Opcodes.POP)
        method.visitLdcInsn(
            Handle(Opcodes.H_INVOKESTATIC, "lib/LdcHandleOwner", "call", "(Llib/LdcHandleDescType;)V", false),
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
