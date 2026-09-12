package dev.kartograph.index

import java.nio.file.Files
import java.nio.file.Path
import javax.tools.ToolProvider
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertContains
import org.junit.jupiter.api.io.TempDir

class CompilerRootSelectionTest {
    @Test
    fun `root declaration inventory preserves class precedence without another parse`(@TempDir root: Path) {
        val source = root.resolve("Choice.java")
        val roots = listOf("first", "second").map { Files.createDirectories(root.resolve(it)) }
        listOf("first", "second").forEachIndexed { index, method ->
            Files.writeString(source, "public class Choice { public void $method() {} }")
            assertEquals(0, ToolProvider.getSystemJavaCompiler().run(null, null, null, "-d", roots[index].toString(), source.toString()))
        }
        val result = ClassFileIndexer().indexWithObservations(roots)
        val first = JvmNodeId.methodId("Choice", "first", "()V")
        val second = JvmNodeId.methodId("Choice", "second", "()V")
        assertEquals(0, result.selectedRootByNode[first])
        assertEquals(0, result.selectedRootByNode[JvmNodeId.classId("Choice")])
        assertFalse(second in result.graph.nodes)
        assertContains(result.declarationsByRoot[1], second)
        assertEquals(result.selectedRootByNode, result.withHierarchy(result.hierarchy).selectedRootByNode)
        assertEquals(result.declarationsByRoot, result.withHierarchy(result.hierarchy).declarationsByRoot)
    }
}
