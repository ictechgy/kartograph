package dev.kartograph.index

import dev.kartograph.core.*
import java.nio.file.Path
import javax.tools.ToolProvider
import kotlin.io.path.*
import kotlin.test.*
import org.junit.jupiter.api.io.TempDir

class SourceFieldFeedbackTest {
    @Test fun `actual javac inline annotated and uninitialized multiple fields have source evidence`(@TempDir root: Path) {
        val text = """
            package sample;
            public class Fields {
              public int first; public String second;
              @Deprecated public long annotated;
              public int one, two;
              public void run() { int local = 0; }
            }
        """.trimIndent()
        val file = root.resolve("src/Fields.java").also { it.parent.createDirectories(); it.writeText(text) }
        val classes = root.resolve("classes").createDirectories()
        assertEquals(0, ToolProvider.getSystemJavaCompiler().run(null, null, null, "-g", "-d", classes.toString(), file.toString()))
        val graph = ClassFileIndexer(callbackFacts = false).index(listOf(classes))
        val enriched = SourceDeclarationLocations.enrich(graph, root, SourcePathIndex.resolve(graph, root).byNodeId)
        val fields = enriched.nodes.values.filter { it.kind == NodeKind.FIELD }
        assertEquals(5, fields.size)
        fields.forEach { node ->
            val evidence = assertNotNull(node.sourceDeclaration, node.name)
            assertEquals(text.indexOf(node.name, text.indexOf("class Fields") + "class Fields".length), evidence.offsetUtf16)
            assertEquals("src/Fields.java", evidence.path)
        }
        assertFalse(fields.any { it.name == "local" })
        file.writeText(text.replace("public String second", "public String renamed"))
        val changed = SourceDeclarationLocations.enrich(graph, root, SourcePathIndex.resolve(graph, root).byNodeId)
        assertNull(changed.nodes.values.single { it.name == "second" }.sourceDeclaration)
    }
}
