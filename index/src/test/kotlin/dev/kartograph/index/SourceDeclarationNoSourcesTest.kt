package dev.kartograph.index

import dev.kartograph.core.*
import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path
import javax.tools.ToolProvider
import org.junit.jupiter.api.io.TempDir
import kotlin.test.*

class SourceDeclarationNoSourcesTest {
    @Test fun `소스 대응이 없으면 정규화 그래프와 compiler 사실을 재사용한다`(@TempDir root: Path) {
        val graph = compiled(root).withCompilerCallPositions(emptyList())
        val result = SourceDeclarationLocations.enrich(graph, root, emptyMap(), false)
        // source 후보가 없는 입력에서 전체 간선 정렬과 인접 목록을 다시 만드는 비용을 제한한다.
        assertSame(graph, result)
        assertTrue(result.compilerCallPositionsCaptured)
        assertTrue(result.edges.any { it.kind == EdgeKind.CALL })
        assertTrue(result.externalCalls.isNotEmpty())
    }

    @Test fun `빈 대응도 오래된 source 선언을 지우며 다음 대응은 다시 관측한다`(@TempDir root: Path) {
        val graph = compiled(root).withCompilerCallPositions(emptyList())
        val paths = graph.nodeIds.associateWith { "Example.java" }
        val located = SourceDeclarationLocations.enrich(graph, root, paths, false)
        val method = JvmNodeId.methodId("sample/Example", "work", "()V")
        val original = assertNotNull(located.nodes.getValue(method).sourceDeclaration)
        val cleared = SourceDeclarationLocations.enrich(located, root, emptyMap(), false)
        assertNull(cleared.nodes.getValue(method).sourceDeclaration)
        assertEquals(graph.nodes, cleared.nodes)
        assertEquals(graph.edges, cleared.edges)
        assertEquals(graph.externalCalls, cleared.externalCalls)
        assertTrue(cleared.compilerCallPositionsCaptured)
        Files.writeString(root.resolve("Example.java"), "\n" + Files.readString(root.resolve("Example.java")))
        val remapped = SourceDeclarationLocations.enrich(cleared, root, paths, false)
        val updated = assertNotNull(remapped.nodes.getValue(method).sourceDeclaration)
        assertEquals(original.line + 1, updated.line)
        assertNotEquals(original.sourceSha256, updated.sourceSha256)
        assertEquals(graph.nodes.getValue(method).location, remapped.nodes.getValue(method).location)
    }

    @Test fun `소스 대응이 없어도 존재하지 않는 project root를 승인하지 않는다`(@TempDir root: Path) {
        val graph = compiled(root)
        assertFailsWith<IOException> {
            SourceDeclarationLocations.enrich(graph, root.resolve("missing"), emptyMap(), false)
        }
    }

    @Test fun `소스 대응이 없어도 충돌하는 compiler capture를 완전하다고 유지하지 않는다`(@TempDir root: Path) {
        val id = NodeId("method:Calls#zero()V")
        val other = NodeId("method:Calls#other()V")
        val owner = NodeId("class:Calls")
        val file = CompilerEvidenceSource("src/Calls.java", "a".repeat(64))
        val caller = CompilerAuthoredCaller(id, file, 0, 20,
            CompilerSourceCoordinateBasis.JAVAC_UTF16_CHAR_SEQUENCE, "17.0.20", 1, 0,
            AuthoredCallCoverage.COMPLETE, emptyList(), emptyList(), "c".repeat(64), "d".repeat(64), "f".repeat(64))
        val inventory = CompilerCallerInventory("main", "compileJava", "classes", listOf(file),
            listOf(CompilerSourceBody("b0", file, 0, 20, CompilerSourceBodyKind.ORDINARY_METHOD, true, "JAVAC_EXPLICIT", listOf(id))),
            listOf(CompilerInventoryOutput("Calls.class", "b".repeat(64), "Calls")),
            listOf(CompilerInventoryMethod(id, 9, true, CompilerBackendOrigin.SOURCE_BOUND, "b0", "JAVAC_EXPLICIT")),
            CompilerSourceCoordinateBasis.JAVAC_UTF16_CHAR_SEQUENCE, "17.0.20", "c".repeat(64), "d".repeat(64), "e".repeat(64), "f".repeat(64))
        val nodes = listOf(GraphNode(owner, "Calls", NodeKind.CLASS),
            GraphNode(id, "zero", NodeKind.METHOD, jvmModifiers = setOf(JvmModifier.STATIC)), GraphNode(other, "other", NodeKind.METHOD))
        for (contradiction in listOf(false, true)) {
            val callers = if (contradiction) listOf(caller, caller.copy(caller = other, file = file.copy(sha256 = "0".repeat(64))))
                else listOf(caller)
            val captured = CodeGraph(nodes, emptyList()).withCompilerAuthoredCalls(callers).withCompilerCallerInventories(listOf(inventory))
            val result = SourceDeclarationLocations.enrich(captured, root, emptyMap(), false)
            if (contradiction) {
                assertTrue(result.compilerAuthoredCallers.isEmpty())
                assertFalse(result.compilerCallerInventories.single().projectionComplete)
            } else {
                assertEquals(callers, result.compilerAuthoredCallers)
                assertEquals(listOf(inventory), result.compilerCallerInventories)
            }
        }
    }

    private fun compiled(root: Path): CodeGraph {
        val source = Files.writeString(root.resolve("Example.java"),
            "package sample;\nclass Example {\n void work() { helper(); System.out.println(1); }\n void helper() {}\n}\n")
        val classes = Files.createDirectory(root.resolve("classes"))
        assertEquals(0, assertNotNull(ToolProvider.getSystemJavaCompiler()).run(null, null, null,
            "-g", "-d", classes.toString(), source.toString()))
        return ClassFileIndexer(callbackFacts = false).index(listOf(classes))
    }
}
