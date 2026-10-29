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
import org.objectweb.asm.ClassReader
import org.objectweb.asm.ClassVisitor
import org.objectweb.asm.ClassWriter
import org.objectweb.asm.Handle
import org.objectweb.asm.Label
import org.objectweb.asm.Opcodes

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
