package dev.kartograph.index

import dev.kartograph.core.NodeAttribute
import dev.kartograph.core.NodeId
import dev.kartograph.core.NodeKind
import dev.kartograph.index.fixture.EmptyEnumInventoryKotlinFixture
import dev.kartograph.index.fixture.EnumInventoryJavaFixture
import dev.kartograph.index.fixture.EnumInventoryKotlinFixture
import java.nio.ByteBuffer
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.createDirectories
import kotlin.io.path.writeBytes
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import org.junit.jupiter.api.io.TempDir
import org.objectweb.asm.ClassReader
import org.objectweb.asm.ClassVisitor
import org.objectweb.asm.ClassWriter
import org.objectweb.asm.FieldVisitor
import org.objectweb.asm.Opcodes

class EnumInventoryTest {
    @Test
    fun `javac and Kotlin inventories use actual enum fields rather than static aliases`() {
        listOf(EnumInventoryJavaFixture.State::class.java, EnumInventoryKotlinFixture::class.java).forEach { type ->
            val facts = facts(classBytes(type))
            val owner = facts.nodes.single { it.id == JvmNodeId.classId(facts.internalName) }
            val expected = listOf("ALPHA", "BETA").map { constant(facts.internalName, it) }
            assertEquals(NodeKind.ENUM, owner.kind)
            assertEquals(expected, owner.enumConstants)
            assertEquals(expected.toSet(), facts.nodes.filter { NodeAttribute.ENUM_CONSTANT in it.attributes }.map { it.id }.toSet())
            val alias = facts.nodes.single { it.id == constant(facts.internalName, "ALIAS") }
            assertFalse(NodeAttribute.ENUM_CONSTANT in alias.attributes)
            assertTrue(facts.nodes.filter { it.id in expected }.all { it.kind == NodeKind.FIELD })
        }
    }

    @Test
    fun `empty enums are captured while constant subclasses remain unavailable`() {
        listOf(EnumInventoryJavaFixture.Empty::class.java, EmptyEnumInventoryKotlinFixture::class.java).forEach { type ->
            val facts = facts(classBytes(type))
            assertEquals(emptyList(), facts.nodes.single { it.id == JvmNodeId.classId(facts.internalName) }.enumConstants)
        }
        listOf(EnumInventoryJavaFixture.State.ALPHA.javaClass, EnumInventoryKotlinFixture.ALPHA.javaClass).forEach { type ->
            val facts = facts(classBytes(type))
            assertNull(facts.nodes.single { it.id == JvmNodeId.classId(facts.internalName) }.enumConstants)
        }
    }

    @Test
    fun `metadata enum kind without classfile flags does not fabricate capture`() {
        val writer = ClassWriter(0)
        ClassReader(classBytes(EnumInventoryKotlinFixture::class.java)).accept(object : ClassVisitor(Opcodes.ASM9, writer) {
            override fun visit(version: Int, access: Int, name: String, signature: String?, superName: String?, interfaces: Array<out String>?) {
                super.visit(version, access and Opcodes.ACC_ENUM.inv(), name, signature, superName, interfaces)
            }
            override fun visitField(access: Int, name: String, descriptor: String, signature: String?, value: Any?): FieldVisitor? =
                super.visitField(access and Opcodes.ACC_ENUM.inv(), name, descriptor, signature, value)
            override fun visitInnerClass(name: String, outerName: String?, innerName: String?, access: Int) {
                super.visitInnerClass(name, outerName, innerName, access and Opcodes.ACC_ENUM.inv())
            }
        }, 0)
        val facts = facts(writer.toByteArray())
        val owner = facts.nodes.single { it.id == JvmNodeId.classId(facts.internalName) }
        assertEquals(NodeKind.ENUM, owner.kind)
        assertNull(owner.enumConstants)
        assertTrue(facts.nodes.none { NodeAttribute.ENUM_CONSTANT in it.attributes })
    }

    @Test
    fun `malformed enum fields fail instead of becoming captured empty`() {
        val owner = "probe/State"
        listOf(
            enumBytes(owner, listOf("ALPHA" to "I")),
            enumBytes(owner, listOf("ALPHA" to "Lprobe/Other;")),
            enumBytes(owner, listOf("ALPHA" to "L$owner;"), enumOwner = false),
            enumBytes(owner, listOf("ALPHA" to "L$owner;", "ALPHA" to "L$owner;")),
        ).forEach { bytes -> assertFailsWith<IllegalArgumentException> { facts(bytes) } }
    }

    @Test
    fun `cache roundtrip preserves complete empty and unavailable facts and rejects version nine`(@TempDir root: Path) {
        val classes = root.resolve("classes").createDirectories()
        val types = listOf(EnumInventoryJavaFixture.State::class.java, EnumInventoryJavaFixture.Empty::class.java,
            EnumInventoryKotlinFixture::class.java, EmptyEnumInventoryKotlinFixture::class.java)
        types.forEach { type -> write(classes, type.name.replace('.', '/'), classBytes(type)) }
        write(classes, "probe/Ordinary", enumBytes("probe/Ordinary", emptyList(), enumOwner = false))
        val cache = ClassIndexCache(root.resolve("cache"), "enum-inventories")
        val cold = ClassFileIndexer(cache).indexWithObservations(listOf(classes))
        val warm = ClassFileIndexer(cache).indexWithObservations(listOf(classes))
        assertEquals(cold.graph.nodes, warm.graph.nodes)
        assertEquals(cold.graph.edges, warm.graph.edges)
        assertEquals(5, warm.statistics.cacheHits)
        assertEquals(0, warm.statistics.parsedClasses)
        assertNull(warm.graph.node(JvmNodeId.classId("probe/Ordinary"))!!.enumConstants)
        Files.list(cache.directory).use { entries -> entries.filter { it.toString().endsWith(".cix") }.forEach { entry ->
            val bytes = Files.readAllBytes(entry)
            ByteBuffer.wrap(bytes).putInt(4, 9)
            Files.write(entry, bytes)
        } }
        val migrated = ClassFileIndexer(cache).indexWithObservations(listOf(classes))
        assertEquals(5, migrated.statistics.invalidEntries)
        assertEquals(5, migrated.statistics.parsedClasses)
        assertEquals(0, migrated.statistics.cacheHits)
        assertEquals(cold.graph.nodes, migrated.graph.nodes)
        assertEquals(cold.graph.edges, migrated.graph.edges)
    }

    @Test
    fun `shadowed roots keep first implementation but cannot claim an unambiguous inventory`(@TempDir root: Path) {
        val first = root.resolve("first").createDirectories()
        val second = root.resolve("second").createDirectories()
        val owner = "probe/State"
        write(first, owner, enumBytes(owner, listOf("ALPHA" to "L$owner;")))
        for (other in listOf(listOf("BETA" to "L$owner;"), listOf("ALPHA" to "L$owner;"), emptyList(), null)) {
            write(second, owner, enumBytes(owner, other.orEmpty(), enumOwner = other != null))
            for (roots in listOf(listOf(first, second), listOf(second, first))) {
                val graph = ClassFileIndexer().index(roots)
                val oracle = ClassFileIndexer().index(listOf(roots.first()))
                assertNull(graph.node(JvmNodeId.classId(owner))!!.enumConstants)
                assertEquals(oracle.nodes.mapValues { it.value.copy(enumConstants = null) }, graph.nodes)
                assertEquals(oracle.edges, graph.edges)
            }
        }
    }

    private fun facts(bytes: ByteArray): ClassFacts =
        FactsVisitor().also { ClassReader(bytes).accept(it, 0) }.facts()

    private fun classBytes(type: Class<*>): ByteArray =
        requireNotNull(type.getResourceAsStream("/" + type.name.replace('.', '/') + ".class")).use { it.readBytes() }

    private fun constant(owner: String, name: String): NodeId = JvmNodeId.fieldId(owner, name, "L$owner;")

    private fun write(root: Path, name: String, bytes: ByteArray) {
        root.resolve("$name.class").also { it.parent.createDirectories() }.writeBytes(bytes)
    }

    private fun enumBytes(owner: String, fields: List<Pair<String, String>>, enumOwner: Boolean = true): ByteArray =
        ClassWriter(0).apply {
            visit(Opcodes.V17, Opcodes.ACC_PUBLIC or if (enumOwner) Opcodes.ACC_ENUM else 0,
                owner, null, if (enumOwner) "java/lang/Enum" else "java/lang/Object", null)
            fields.forEach { (name, descriptor) ->
                visitField(Opcodes.ACC_PUBLIC or Opcodes.ACC_STATIC or Opcodes.ACC_FINAL or Opcodes.ACC_ENUM,
                    name, descriptor, null, null).visitEnd()
            }
            visitEnd()
        }.toByteArray()
}
