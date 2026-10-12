package dev.kartograph.index

import dev.kartograph.core.*
import dev.kartograph.index.fixture.defaultbridges.Receiver
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.*
import org.junit.jupiter.api.io.TempDir
import org.objectweb.asm.ClassReader
import org.objectweb.asm.ClassWriter
import org.objectweb.asm.ClassVisitor
import org.objectweb.asm.MethodVisitor
import org.objectweb.asm.Opcodes

/** 공식 Kotlin compiler 산출물로 source association과 raw JVM CALL 보존을 함께 검증한다. */
class KotlinDefaultBridgeTest {
    @Test
    fun `exact metadata masks disambiguate overloaded defaults and preserve raw calls`(@TempDir root: Path) {
        val classes = copyFixtures(root)
        val cache = ClassIndexCache(root.resolve("cache"), "default-bridges")
        val cold = ClassFileIndexer(cache).indexWithObservations(listOf(classes))
        val warm = ClassFileIndexer(cache).indexWithObservations(listOf(classes))
        assertEquals(cold.graph.nodes, warm.graph.nodes)
        assertEquals(cold.graph.edges, warm.graph.edges)
        assertEquals(cold.statistics.classFiles, warm.statistics.cacheHits)
        assertEquals(0, warm.statistics.parsedClasses)
        // 이전 node binary 포맷은 부분 association 없이 재파싱한다.
        Files.list(cache.directory).use { entries -> entries.filter { it.toString().endsWith(".cix") }.forEach { entry ->
            val bytes = Files.readAllBytes(entry)
            java.nio.ByteBuffer.wrap(bytes).putInt(4, 8)
            Files.write(entry, bytes)
        } }
        val migrated = ClassFileIndexer(cache).indexWithObservations(listOf(classes))
        assertEquals(cold.statistics.classFiles, migrated.statistics.parsedClasses)
        assertEquals(0, migrated.statistics.cacheHits)
        assertEquals(cold.graph.nodes, migrated.graph.nodes)
        assertEquals(cold.graph.edges, migrated.graph.edges)
        val graph = warm.graph
        val associations = graph.nodes.values.filter { it.kotlinDefaultBridge != null }
        assertEquals(9, associations.size)
        fun bridge(name: String, owner: String = "DefaultBridgeFixtures") = associations.single {
            it.id.value.startsWith("method:$PACKAGE/$owner#$name\$default(")
        }
        val overload = bridge("overload")
        assertEquals("(Ljava/lang/String;I)Ljava/lang/String;", overload.kotlinDefaultBridge!!.target.value.substringAfter("#overload"))
        assertEquals(2, overload.kotlinDefaultBridge!!.valueParameterCount)
        assertEquals(setOf("(Ljava/lang/String;)Ljava/lang/String;", "(Ljava/lang/String;I)Ljava/lang/String;"),
            graph.outgoingEdgesFrom(overload.id).filter { it.kind == EdgeKind.CALL && it.target.value.contains("#overload(") }
                .map { it.target.value.substringAfter("#overload") }.toSet())
        assertEquals(2, bridge("many").kotlinDefaultBridge!!.maskParameterCount)
        assertEquals(33, bridge("many").kotlinDefaultBridge!!.valueParameterCount)
        assertEquals(1, bridge("thirtyTwo").kotlinDefaultBridge!!.maskParameterCount)
        assertEquals(32, bridge("thirtyTwo").kotlinDefaultBridge!!.valueParameterCount)
        assertEquals(1, bridge("decorate").kotlinDefaultBridge!!.valueParameterCount)
        assertEquals(1, bridge("member", "Receiver").kotlinDefaultBridge!!.valueParameterCount)
        assertEquals(1, bridge("memberExtension", "Receiver").kotlinDefaultBridge!!.valueParameterCount)
        assertContains(bridge("suspendLabel").kotlinDefaultBridge!!.target.value, "Lkotlin/coroutines/Continuation;")
        assertEquals("sourceNamed", graph.node(bridge("jvmNamed").kotlinDefaultBridge!!.target)!!.kotlinSourceName)
        assertEquals("authored\$default", graph.node(bridge("authored\$default").kotlinDefaultBridge!!.target)!!.kotlinSourceName)
        associations.forEach { node ->
            assertEquals("kotlinMetadataAndBytecode", node.kotlinDefaultBridge!!.basis)
            assertTrue(graph.outgoingEdgesFrom(node.id).any { it.kind == EdgeKind.CALL && it.target == node.kotlinDefaultBridge!!.target })
        }
        listOf("ordinary\$default", "synthetic\$default", "namedFake\$default", "authored\$default", "ordinary", "synthetic").forEach { name ->
            val node = graph.nodes.values.single { it.id.value.startsWith("method:$PACKAGE/DefaultBridgeFixtures#$name(") }
            assertNotNull(node.kotlinSourceName)
            assertNull(node.kotlinDefaultBridge, "authored or no-default method: $name")
        }
    }

    @Test
    fun `metadata without real bridge target call is not enough`(@TempDir root: Path) {
        val classes = copyFixtures(root)
        val file = classes.resolve("$PACKAGE/DefaultBridgeFixtures.class")
        val writer = ClassWriter(0)
        ClassReader(Files.readAllBytes(file)).accept(object : ClassVisitor(Opcodes.ASM9, writer) {
            override fun visitMethod(access: Int, name: String, descriptor: String, signature: String?, exceptions: Array<out String>?): MethodVisitor {
                val delegate = super.visitMethod(access, name, descriptor, signature, exceptions)
                if (name != "decorate\$default") return delegate
                return object : MethodVisitor(Opcodes.ASM9, delegate) {
                    override fun visitMethodInsn(opcode: Int, owner: String, name: String, descriptor: String, isInterface: Boolean) {
                        if (owner == "$PACKAGE/DefaultBridgeFixtures" && name == "decorate") {
                            // 유효 stack shape를 유지하면서 metadata 대상과 다른 external 호출로 바꾼다.
                            super.visitMethodInsn(opcode, "outside/Unproved", name, descriptor, isInterface)
                        } else super.visitMethodInsn(opcode, owner, name, descriptor, isInterface)
                    }
                }
            }
        }, 0)
        Files.write(file, writer.toByteArray())
        val graph = ClassFileIndexer().index(listOf(classes))
        assertNull(graph.nodes.values.single { it.name == "decorate\$default" }.kotlinDefaultBridge)
        assertEquals(8, graph.nodes.values.count { it.kotlinDefaultBridge != null })
    }

    @Test
    fun `unsupported bridge descriptors remain unknown rather than name inferred`(@TempDir root: Path) {
        val classes = copyFixtures(root)
        val file = classes.resolve("$PACKAGE/DefaultBridgeFixtures.class")
        val writer = ClassWriter(0)
        ClassReader(Files.readAllBytes(file)).accept(object : ClassVisitor(Opcodes.ASM9, writer) {
            override fun visitMethod(access: Int, name: String, descriptor: String, signature: String?, exceptions: Array<out String>?): MethodVisitor {
                // descriptor가 맞지 않는 후처리 산출물을 bytecode 실행이 아닌 index 경계에서 검사한다.
                val changed = if (name == "decorate\$default") descriptor.replace("Ljava/lang/Object;)", "Ljava/lang/String;)") else descriptor
                return super.visitMethod(access, name, changed, signature, exceptions)
            }
        }, 0)
        Files.write(file, writer.toByteArray())
        val graph = ClassFileIndexer().index(listOf(classes))
        assertNull(graph.nodes.values.single { it.name == "decorate\$default" }.kotlinDefaultBridge)
    }

    private fun copyFixtures(root: Path): Path {
        val source = Path.of(Receiver::class.java.protectionDomain.codeSource.location.toURI()).resolve(PACKAGE)
        val classes = root.resolve("classes")
        val target = Files.createDirectories(classes.resolve(PACKAGE))
        Files.list(source).use { files -> files.filter { it.toString().endsWith(".class") }.forEach { Files.copy(it, target.resolve(it.fileName)) } }
        return classes
    }

    private companion object {
        const val PACKAGE = "dev/kartograph/index/fixture/defaultbridges"
    }
}
