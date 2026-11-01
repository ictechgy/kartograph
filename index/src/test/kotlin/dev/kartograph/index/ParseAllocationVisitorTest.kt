package dev.kartograph.index

import dev.kartograph.core.EdgeKind
import dev.kartograph.core.InvocationKind
import dev.kartograph.core.NodeId
import dev.kartograph.index.fixture.Caller
import dev.kartograph.index.fixture.JavaFixture
import java.nio.file.Files
import java.nio.file.Path
import java.util.jar.JarEntry
import java.util.jar.JarOutputStream
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import org.junit.jupiter.api.io.TempDir
import org.objectweb.asm.AnnotationVisitor
import org.objectweb.asm.ClassReader
import org.objectweb.asm.ClassVisitor
import org.objectweb.asm.ClassWriter
import org.objectweb.asm.Handle
import org.objectweb.asm.Label
import org.objectweb.asm.Opcodes
import org.objectweb.asm.Type

/** 이전 raw visitor와 순서·중복·위치·관측을 비교하며 실제 compiler 입력도 통과시킨다. */
class ParseAllocationVisitorTest {
    @Test
    fun `real Kotlin Java and production class bytes retain all raw facts`() {
        for (type in listOf(Caller::class.java, JavaFixture::class.java, ClassFileIndexer::class.java)) {
            val bytes = requireNotNull(type.getResourceAsStream("/" + type.name.replace('.', '/') + ".class")).use { it.readBytes() }
            same(bytes)
        }
    }

    @Test
    fun `all invocation and handle kinds preserve order ordinals and repeated edges`() {
        val facts = same(invocations())
        assertEquals(listOf(0, 1, 2, 3, 4, 5, 5, 5, 5, 5, 5), facts.calls.map { it.ordinal })
        assertEquals(listOf(InvocationKind.STATIC, InvocationKind.STATIC, InvocationKind.SPECIAL,
            InvocationKind.INTERFACE, InvocationKind.VIRTUAL, InvocationKind.BOOTSTRAP,
            InvocationKind.VIRTUAL, InvocationKind.STATIC, InvocationKind.SPECIAL,
            InvocationKind.SPECIAL, InvocationKind.INTERFACE), facts.calls.map { it.kind })
        val direct = facts.edges.filter { it.kind == EdgeKind.CALL && it.callSiteLines.isNotEmpty() }
        assertEquals(5, direct.size)
        assertEquals(direct[0], direct[1])
        assertTrue(direct.all { it.callSiteLines == listOf(7) })
        assertEquals(4, facts.edges.count { it.kind == EdgeKind.FIELD_ACCESS })
    }

    @Test
    fun `declaration references retain descriptor signature exception order and self removal`() {
        val bytes = ClassWriter(0).apply {
            visit(Opcodes.V17, Opcodes.ACC_PUBLIC, "probe/Owner", null, "java/lang/Object", null)
            visitField(Opcodes.ACC_PUBLIC, "field", "[[Lprobe/Descriptor;",
                "Ljava/util/Map<Lprobe/Zeta;Lprobe/Alpha;>;", null).visitEnd()
            visitMethod(Opcodes.ACC_PUBLIC or Opcodes.ACC_ABSTRACT, "method",
                "(Lprobe/Descriptor;[Lprobe/Owner;Lprobe/Descriptor;I)[[Lprobe/Return;",
                "(Ljava/util/List<Lprobe/Zeta;>;Lprobe/Owner;)Lprobe/Alpha;",
                arrayOf("probe/Zeta", "probe/FailureZ", "probe/FailureA", "probe/FailureZ", "probe/Owner")).visitEnd()
            visitEnd()
        }.toByteArray()
        val facts = same(bytes)
        for ((name, expected) in listOf(
            "field" to listOf("probe/Owner", "probe/Descriptor", "java/util/Map", "probe/Alpha", "probe/Zeta"),
            "method" to listOf("probe/Owner", "probe/Descriptor", "probe/Return", "java/util/List",
                "probe/Alpha", "probe/Zeta", "probe/FailureZ", "probe/FailureA"),
        )) {
            val id = facts.nodes.single { it.name == name }.id
            assertEquals(expected.map(JvmNodeId::classId),
                facts.edges.filter { it.source == id && it.kind == EdgeKind.REFERENCE }.map { it.target })
        }
    }

    @Test
    fun `ordinary repeated nested parameter and default annotations retain all raw facts`() {
        val bytes = ClassWriter(0).apply {
            visit(Opcodes.V17, Opcodes.ACC_PUBLIC, "probe/Annotated", null, "java/lang/Object", null)
            annotationValues(visitAnnotation("Lprobe/Anno;", true))
            annotationValues(visitAnnotation("Lprobe/Anno;", false))
            visitField(Opcodes.ACC_PUBLIC, "field", "Ljava/lang/Object;", null, null).apply {
                annotationValues(visitAnnotation("Lprobe/FieldAnno;", true))
                visitEnd()
            }
            visitMethod(Opcodes.ACC_PUBLIC, "method", "(Ljava/lang/Object;)V", null, null).apply {
                annotationValues(visitAnnotation("Lprobe/MethodAnno;", false))
                annotationValues(visitParameterAnnotation(0, "Lprobe/ParameterAnno;", true))
                visitCode()
                visitInsn(Opcodes.RETURN)
                visitMaxs(0, 2)
                visitEnd()
            }
            visitEnd()
        }.toByteArray()
        val facts = same(bytes)
        assertEquals(listOf("probe/Anno", "probe/Anno", "probe/FieldAnno", "probe/MethodAnno", "probe/ParameterAnno")
            .map(JvmNodeId::classId), facts.edges.filter { it.kind == EdgeKind.ANNOTATION }.map { it.target })
        assertEquals(setOf("probe/Anno"), facts.nodes.single { it.id == JvmNodeId.classId("probe/Annotated") }.annotations)
        assertEquals(setOf("probe/FieldAnno"), facts.nodes.single { it.name == "field" }.annotations)
        assertEquals(setOf("probe/MethodAnno"), facts.nodes.single { it.name == "method" }.annotations)

        val defaults = ClassWriter(0).apply {
            visit(Opcodes.V17, Opcodes.ACC_PUBLIC or Opcodes.ACC_ANNOTATION or Opcodes.ACC_INTERFACE or Opcodes.ACC_ABSTRACT,
                "probe/Defaults", null, "java/lang/Object", arrayOf("java/lang/annotation/Annotation"))
            visitMethod(Opcodes.ACC_PUBLIC or Opcodes.ACC_ABSTRACT, "value", "()[Ljava/lang/Class;", null, null).apply {
                visitAnnotationDefault().apply {
                    visitArray(null).apply {
                        visit(null, Type.getType("Lprobe/DefaultValue;"))
                        visit(null, Type.getType("[[Lprobe/DefaultArray;"))
                        visitEnd()
                    }
                    visitEnd()
                }
                visitEnd()
            }
            visitEnd()
        }.toByteArray()
        val defaultFacts = same(defaults)
        for (target in listOf("probe/DefaultValue", "probe/DefaultArray")) {
            assertEquals(2, defaultFacts.edges.count { it.kind == EdgeKind.REFERENCE && it.target == JvmNodeId.classId(target) })
        }
    }

    @Test
    fun `malformed declaration descriptors and signatures retain failure type order and partial facts`() {
        val descriptors = listOf("", "L", "[", "[[", "Q", "()", "(I", "(Lprobe/A;)", "(I)V",
            "(Ljava/lang/String;)[[Lprobe/Return;", "Ljava/lang/String;", "[[I", "[Lprobe/Owner;", "Lprobe/Owner;")
        var failed = 0
        var succeeded = 0
        for (method in listOf(false, true)) for (descriptor in descriptors) {
            val validSignature = if (method) "(Lprobe/Generic;)V" else "Ljava/util/List<Lprobe/Generic;>;"
            for (signature in listOf(null, validSignature, "L", "[", "<")) {
                val result = sameCallbacks { visitor ->
                    if (method) visitor.visitMethod(Opcodes.ACC_PUBLIC, "method", descriptor, signature,
                        arrayOf("probe/FailureZ", "probe/FailureA")).visitEnd()
                    else visitor.visitField(Opcodes.ACC_PUBLIC, "field", descriptor, signature, null).visitEnd()
                    visitor.visitEnd()
                }
                if (result.isFailure) failed++ else succeeded++
            }
        }
        assertTrue(failed > 0)
        assertTrue(succeeded > 0)
    }

    @Test
    fun `annotation descriptors retain legacy acceptance failures and partial facts at every declaration`() {
        var failed = 0
        var succeeded = 0
        for (descriptor in listOf("", "L", "[", "Q", "I", "V", "Ljava/lang/String;", "[Lprobe/Anno;", "Lprobe/Anno;trailing", "L;", "()V")) {
            for (declaration in 0..3) {
                val result = sameCallbacks { visitor ->
                    when (declaration) {
                        0 -> visitor.visitAnnotation(descriptor, true)?.visitEnd()
                        1 -> visitor.visitField(Opcodes.ACC_PUBLIC, "field", "I", null, null).apply {
                            visitAnnotation(descriptor, false)?.visitEnd()
                            visitEnd()
                        }
                        else -> visitor.visitMethod(Opcodes.ACC_PUBLIC, "method", "(I)V", null, null).apply {
                            if (declaration == 2) visitAnnotation(descriptor, false)?.visitEnd()
                            else visitParameterAnnotation(0, descriptor, true)?.visitEnd()
                            visitEnd()
                        }
                    }
                    visitor.visitEnd()
                }
                if (result.isFailure) failed++ else succeeded++
            }
        }
        assertTrue(failed > 0)
        assertTrue(succeeded > 0)
    }

    @Test
    fun `basename sanitation and primary SMAP line decisions match legacy exactly`() {
        val paths = listOf<String?>(null, "", "  ", "Source.kt", "/private/Source.kt", "C:\\private\\Source.kt",
            "/private/", "C:\\private\\", "mixed/path\\Source.kt", "Ω.kt", "\uD800.kt")
        val debugs = listOf<String?>(null, "not SMAP", "SMAP\nmalformed", smap(),
            smap().replace("1#1,2:1", "1#1,2:100"), smap().replace("Source.kt", "Other.kt"),
            "SMAP\nSource.kt\nKotlin\n*F\n+ 1 Source.kt\none/Source.kt\n+ 2 Source.kt\ntwo/Source.kt\n*L\n1#1:1\n*E\n")
        for (path in paths) for (debug in debugs) for (line in listOf(0, 1, 2, 65535)) {
            same(simple(path, debug, line))
        }
        for (path in listOf("/private/Source.kt", "C:\\private\\Source.kt", "mixed/path\\Source.kt")) {
            val facts = same(simple(path, null, 1))
            assertTrue(facts.nodes.all { it.location?.path == "Source.kt" })
            assertEquals(listOf(1), facts.edges.single { it.kind == EdgeKind.CALL }.callSiteLines)
        }
        assertTrue(same(simple("/private/", null, 1)).nodes.all { it.location == null })
        assertTrue(same(simple("Source.kt", smap().replace("1#1,2:1", "1#1,2:100"), 100))
            .edges.single { it.kind == EdgeKind.CALL }.callSiteLines.isEmpty())
    }

    @Test
    fun `repeated null source visits and out of range direct line callbacks retain legacy semantics`() {
        for (line in listOf(-1, 0, 1, 65535, 65536, Int.MAX_VALUE)) {
            for (last in listOf<String?>(null, "", "/private/Source.kt")) {
                val old = LegacyParseFactsVisitor()
                val current = FactsVisitor()
                for (visitor in listOf<ClassVisitor>(old, current)) {
                    visitor.visit(Opcodes.V17, Opcodes.ACC_PUBLIC, "probe/Calls", null, "java/lang/Object", emptyArray())
                    visitor.visitSource("Earlier.kt", smap())
                    visitor.visitSource(last, null)
                    val method = visitor.visitMethod(Opcodes.ACC_PUBLIC or Opcodes.ACC_STATIC, "run", "()V", null, null)
                    method.visitLineNumber(line, Label())
                    method.visitMethodInsn(Opcodes.INVOKESTATIC, "probe/Target", "hit", "()V", false)
                    method.visitEnd()
                    visitor.visitEnd()
                }
                assertEquals(old.facts(), current.facts())
            }
        }
    }

    @Test
    fun `malformed class and metadata have the same raw failure boundary`() {
        val bytes = simple("Source.kt", null, 1)
        for (broken in listOf(byteArrayOf(), bytes.copyOf(4), bytes.copyOf(bytes.size / 2), bytes.copyOf(bytes.size - 1))) {
            val expected = runCatching { read(broken, true) }
            val actual = runCatching { read(broken, false) }
            assertTrue(expected.isFailure)
            assertEquals(expected.exceptionOrNull()?.javaClass, actual.exceptionOrNull()?.javaClass)
        }
        val brokenMetadata = ClassWriter(0).apply {
            visit(Opcodes.V17, Opcodes.ACC_PUBLIC, "probe/BadMetadata", null, "java/lang/Object", null)
            visitAnnotation("Lkotlin/Metadata;", true).apply {
                visit("k", "not-an-integer")
                visitEnd()
            }
            visitEnd()
        }.toByteArray()
        val expected = runCatching { read(brokenMetadata, true) }
        assertTrue(expected.isFailure)
        assertEquals(expected.exceptionOrNull()?.javaClass, runCatching { read(brokenMetadata, false) }.exceptionOrNull()?.javaClass)
    }

    @Test
    fun `truncated directory and jar inputs remain sanitized tool failures`(@TempDir root: Path) {
        val classes = Files.createDirectory(root.resolve("classes"))
        val broken = simple("Source.kt", null, 1).copyOf(4)
        Files.write(classes.resolve("Calls.class"), broken)
        val directoryFailure = assertFailsWith<ClassIndexingException> {
            ClassFileIndexer(callbackFacts = false).index(listOf(classes))
        }
        assertEquals("invalid class file", directoryFailure.message)
        val jar = root.resolve("broken.jar")
        JarOutputStream(Files.newOutputStream(jar)).use {
            it.putNextEntry(JarEntry("Calls.class"))
            it.write(broken)
            it.closeEntry()
        }
        val jarFailure = assertFailsWith<ClassIndexingException> {
            ClassFileIndexer(callbackFacts = false).index(listOf(jar))
        }
        assertTrue(jarFailure.message in listOf("invalid class file", "invalid class file in class JAR"))
    }

    private fun same(bytes: ByteArray): ClassFacts {
        val expected = read(bytes, true)
        val actual = read(bytes, false)
        assertEquals(expected, actual)
        return actual
    }

    private fun sameCallbacks(callbacks: (ClassVisitor) -> Unit): Result<Unit> {
        val old = LegacyParseFactsVisitor()
        val current = FactsVisitor()
        for (visitor in listOf<ClassVisitor>(old, current)) {
            visitor.visit(Opcodes.V17, Opcodes.ACC_PUBLIC, "probe/Owner", null, "java/lang/Object", emptyArray())
        }
        val expected = runCatching { callbacks(old) }
        val actual = runCatching { callbacks(current) }
        assertEquals(expected.isFailure, actual.isFailure)
        assertEquals(expected.exceptionOrNull()?.javaClass, actual.exceptionOrNull()?.javaClass)
        assertEquals(expected.exceptionOrNull()?.message, actual.exceptionOrNull()?.message)
        assertEquals(old.facts(), current.facts())
        return actual
    }

    private fun annotationValues(visitor: AnnotationVisitor) {
        visitor.visit("type", Type.getType("[[Lprobe/Value;"))
        visitor.visitEnum("enum", "Lprobe/Enum;", "VALUE")
        visitor.visitAnnotation("nested", "Lprobe/Nested;").apply {
            visit("type", Type.getType("Lprobe/NestedValue;"))
            visitEnd()
        }
        visitor.visitArray("array").apply {
            visit(null, Type.getType("Lprobe/ArrayValue;"))
            visitEnd()
        }
        visitor.visitEnd()
    }

    private fun read(bytes: ByteArray, legacy: Boolean): ClassFacts {
        val reader = ClassReader(bytes)
        return if (legacy) LegacyParseFactsVisitor().also { reader.accept(it, 0) }.facts()
            else FactsVisitor().also { reader.accept(it, 0) }.facts()
    }

    private fun simple(source: String?, debug: String?, line: Int): ByteArray = ClassWriter(0).apply {
        visit(Opcodes.V17, Opcodes.ACC_PUBLIC, "probe/Calls", null, "java/lang/Object", null)
        if (source != null || debug != null) visitSource(source, debug)
        visitMethod(Opcodes.ACC_PUBLIC or Opcodes.ACC_STATIC, "run", "()V", null, null).apply {
            visitCode()
            val label = Label()
            visitLabel(label)
            visitLineNumber(line, label)
            visitMethodInsn(Opcodes.INVOKESTATIC, "probe/Target", "hit", "()V", false)
            visitInsn(Opcodes.RETURN)
            visitMaxs(0, 0)
            visitEnd()
        }
        visitEnd()
    }.toByteArray()

    private fun invocations(): ByteArray = ClassWriter(0).apply {
        visit(Opcodes.V17, Opcodes.ACC_PUBLIC, "probe/Calls", null, "java/lang/Object", null)
        visitSource("Calls.java", null)
        visitMethod(Opcodes.ACC_PUBLIC or Opcodes.ACC_STATIC, "run", "()V", null, null).apply {
            visitCode()
            val label = Label()
            visitLabel(label)
            visitLineNumber(7, label)
            for (opcode in listOf(Opcodes.INVOKESTATIC, Opcodes.INVOKESTATIC, Opcodes.INVOKESPECIAL,
                Opcodes.INVOKEINTERFACE, Opcodes.INVOKEVIRTUAL)) {
                visitMethodInsn(opcode, "probe/Target", "hit", "()V", opcode == Opcodes.INVOKEINTERFACE)
            }
            val handles = listOf(Opcodes.H_GETFIELD, Opcodes.H_GETSTATIC, Opcodes.H_PUTFIELD, Opcodes.H_PUTSTATIC,
                Opcodes.H_INVOKEVIRTUAL, Opcodes.H_INVOKESTATIC, Opcodes.H_INVOKESPECIAL,
                Opcodes.H_NEWINVOKESPECIAL, Opcodes.H_INVOKEINTERFACE).map { tag ->
                Handle(tag, "probe/Target", if (tag <= Opcodes.H_PUTSTATIC) "field" else if (tag == Opcodes.H_NEWINVOKESPECIAL) "<init>" else "hit",
                    if (tag <= Opcodes.H_PUTSTATIC) "I" else "()V", tag == Opcodes.H_INVOKEINTERFACE)
            }
            visitInvokeDynamicInsn("run", "()V", Handle(Opcodes.H_INVOKESTATIC, "probe/Bootstrap", "link", "()V", false),
                *handles.toTypedArray())
            visitInsn(Opcodes.RETURN)
            visitMaxs(1, 0)
            visitEnd()
        }
        visitEnd()
    }.toByteArray()

    private fun smap(): String = "SMAP\nSource.kt\nKotlin\n*S Kotlin\n*F\n+ 1 Source.kt\nprobe/Source.kt\n*L\n1#1,2:1\n*E\n"
}
