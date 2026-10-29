package dev.kartograph.index

import java.nio.file.Files
import java.nio.file.Path
import java.util.jar.JarEntry
import java.util.jar.JarOutputStream
import javax.tools.ToolProvider
import kotlin.test.*
import org.junit.jupiter.api.io.TempDir
import org.objectweb.asm.ClassReader

class UncachedClassParsingTest {
    @Test
    fun `compiler facts match sequential oracle and directory jar cache graphs agree`(@TempDir root: Path) {
        val classes = compile(root.resolve("original"), "first")
        val paths = Files.list(classes).use { it.sorted().toList() }
        val inputs = paths.map { ClassInput(Files.readAllBytes(it), Files.getLastModifiedTime(it)) }
        for (callbacks in listOf(false, true)) {
            val indexer = ClassFileIndexer(callbackFacts = callbacks)
            val read = ClassFileIndexer::class.java.getDeclaredMethod("readFacts", ClassReader::class.java).apply { isAccessible = true }
            val parse: (ByteArray) -> ClassFacts = { read.invoke(indexer, ClassReader(it)) as ClassFacts }
            val expected = inputs.map { input -> parse(input.bytes).let { it.copy(runtime = it.runtime.copy(modified = input.modified)) } }
            UncachedClassBatchLoader(parse).use { loader ->
                val actual = loader.load(inputs.map { { it } }).facts
                assertEquals(expected.size, actual.size)
                expected.zip(actual).forEach { (before, after) ->
                    assertEquals(before.runtime, after.runtime)
                    // ASM MethodNode 객체 신원이 아닌 모든 직렬화 가능한 raw fact를 대조한다.
                    assertContentEquals(ClassFactsCodec.encode(before, "oracle", "0".repeat(64)),
                        ClassFactsCodec.encode(after, "oracle", "0".repeat(64)))
                }
            }
        }
        val jar = archive(root.resolve("input.jar"), paths)
        val directory = ClassFileIndexer().indexWithObservations(listOf(classes))
        val fromJar = ClassFileIndexer().indexWithObservations(listOf(jar))
        val cache = ClassIndexCache(root.resolve("cache"), "uncached-parsing-regression")
        val cold = ClassFileIndexer(cache).indexWithObservations(listOf(classes))
        val warm = ClassFileIndexer(cache).indexWithObservations(listOf(classes))
        for (other in listOf(fromJar, cold, warm)) {
            assertEquals(directory.graph.nodes, other.graph.nodes)
            assertEquals(directory.graph.edges, other.graph.edges)
            assertEquals(directory.graph.externalCalls, other.graph.externalCalls)
        }
        assertEquals(paths.size, directory.statistics.parsedClasses)
        assertEquals(0, directory.statistics.cacheHits)
        assertEquals(paths.size, warm.statistics.cacheHits)
        // 반환 뒤에도 archive handle이 남으면 Windows 이동이 실패한다.
        Files.move(jar, root.resolve("closed.jar"))
    }

    @Test
    fun `first root still wins duplicate classes and shadowed malformed class still fails`(@TempDir root: Path) {
        val first = compile(root.resolve("first"), "first")
        val second = compile(root.resolve("second"), "second")
        val firstOnly = ClassFileIndexer().index(listOf(first))
        val combined = ClassFileIndexer().index(listOf(first, second, first))
        assertEquals(firstOnly.nodes, combined.nodes)
        assertEquals(firstOnly.edges, combined.edges)
        assertEquals(firstOnly.externalCalls, combined.externalCalls)
        val reversed = ClassFileIndexer().index(listOf(second, first))
        assertTrue(JvmNodeId.methodId("Fixture", "second", "()Ljava/lang/String;") in reversed.nodes)
        assertFalse(JvmNodeId.methodId("Fixture", "first", "()Ljava/lang/String;") in reversed.nodes)
        Files.write(second.resolve("Fixture.class"), byteArrayOf(1, 2, 3))
        assertEquals("invalid class file", assertFailsWith<ClassIndexingException> {
            ClassFileIndexer().index(listOf(first, second))
        }.message)
        val jar = archive(root.resolve("bad.jar"), Files.list(second).use { it.sorted().toList() })
        assertFailsWith<ClassIndexingException> { ClassFileIndexer().index(listOf(first, jar)) }
        Files.move(jar, root.resolve("bad-closed.jar"))
        val corrupt = root.resolve("corrupt.jar")
        Files.write(corrupt, byteArrayOf(1, 2, 3))
        assertFailsWith<ClassIndexingException> { ClassFileIndexer().index(listOf(corrupt)) }
    }

    private fun compile(root: Path, name: String): Path {
        Files.createDirectories(root)
        val source = root.resolve("Fixture.java")
        val nested = (1..20).joinToString("\n") { "public static class Nested$it { public String call() { return $name(); } }" }
        Files.writeString(source, "public class Fixture { public static String $name() { return \"$name\"; } $nested }")
        val classes = Files.createDirectories(root.resolve("classes"))
        assertEquals(0, requireNotNull(ToolProvider.getSystemJavaCompiler()).run(null, null, null,
            "--release", "17", "-g", "-d", classes.toString(), source.toString()))
        return classes
    }

    private fun archive(path: Path, files: List<Path>): Path {
        JarOutputStream(Files.newOutputStream(path)).use { jar ->
            // 물리 entry 순서와 상관없이 기존 이름 정렬을 유지한다.
            for (file in files.reversed()) {
                jar.putNextEntry(JarEntry(file.fileName.toString()))
                jar.write(Files.readAllBytes(file))
                jar.closeEntry()
            }
        }
        return path
    }
}
