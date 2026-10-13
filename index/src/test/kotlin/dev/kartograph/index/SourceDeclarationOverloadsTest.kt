package dev.kartograph.index

import dev.kartograph.core.*
import dev.kartograph.index.fixture.SourceDeclarationOverloadFixture
import dev.kartograph.index.fixture.aliases.SourceDeclarationAliasedOverloadFixture
import java.nio.file.Files
import java.nio.file.Path
import javax.tools.ToolProvider
import org.junit.jupiter.api.io.TempDir
import kotlin.test.*

class SourceDeclarationOverloadsTest {
    @Test fun `actual javac same arity overloads retain compiler lines and gain independent columns`(@TempDir root: Path) {
        val text = """
            package sample;
            class Overloads {
                int leaf(int value) {
                    return value;
                }
                String leaf(String value) {
                    return value;
                }
                int qualified(int value) { return value; }
                java.lang.String qualified(java.lang.String value) { return value; }
            }
        """.trimIndent()
        val source = Files.writeString(root.resolve("Overloads.java"), text)
        val classes = Files.createDirectory(root.resolve("classes"))
        assertEquals(0, assertNotNull(ToolProvider.getSystemJavaCompiler()).run(null, null, null,
            "-g", "-d", classes.toString(), source.toString()))
        val graph = ClassFileIndexer(callbackFacts = false).index(listOf(classes))
        val result = SourceDeclarationLocations.enrich(graph, root, graph.nodeIds.associateWith { "Overloads.java" })
        listOf("leaf", "qualified").forEach { name ->
            listOf("(I)I", "(Ljava/lang/String;)Ljava/lang/String;").forEach { descriptor ->
                val id = JvmNodeId.methodId("sample/Overloads", name, descriptor)
                val spelling = if (descriptor == "(I)I") "int" else if (name == "leaf") "String" else "java.lang.String"
                assertCoordinate(graph, result, id, text, "$name($spelling value)")
            }
        }
        assertEquals(4, result.nodes.getValue(JvmNodeId.methodId("sample/Overloads", "leaf", "(I)I")).location?.line)
        assertEquals(3, result.nodes.getValue(JvmNodeId.methodId("sample/Overloads", "leaf", "(I)I")).sourceDeclaration?.line)
    }

    @Test fun `actual Kotlin same arity overloads map plain and qualified builtin types`(@TempDir root: Path) {
        val owner = "dev/kartograph/index/fixture/SourceDeclarationOverloadFixture"
        val classes = Files.createDirectory(root.resolve("classes"))
        val compiled = Path.of(SourceDeclarationOverloadFixture::class.java.protectionDomain.codeSource.location.toURI())
        val output = classes.resolve("$owner.class")
        Files.createDirectories(output.parent)
        Files.copy(compiled.resolve("$owner.class"), output)
        val repository = generateSequence(Path.of("").toRealPath(), Path::getParent)
            .first { Files.isRegularFile(it.resolve("settings.gradle.kts")) }
        val relative = "index/src/test/kotlin/dev/kartograph/index/fixture/SourceDeclarationOverloadFixture.kt"
        val text = Files.readString(repository.resolve(relative))
        val graph = ClassFileIndexer(callbackFacts = false).index(listOf(classes))
        val result = SourceDeclarationLocations.enrich(graph, repository, graph.nodeIds.associateWith { relative })
        listOf("leaf", "qualified").forEach { name ->
            listOf("Int" to "(I)I", "String" to "(Ljava/lang/String;)Ljava/lang/String;").forEach { (type, descriptor) ->
                val spelling = if (name == "qualified") "kotlin.$type" else type
                assertCoordinate(graph, result, JvmNodeId.methodId(owner, name, descriptor), text, "$name(value: $spelling)")
            }
        }
    }

    @Test fun `actual Kotlin cross file aliases cannot swap builtin overload coordinates`(@TempDir root: Path) {
        val owner = "dev/kartograph/index/fixture/aliases/SourceDeclarationAliasedOverloadFixture"
        val classes = Files.createDirectory(root.resolve("classes"))
        val compiled = Path.of(SourceDeclarationAliasedOverloadFixture::class.java.protectionDomain.codeSource.location.toURI())
        val output = classes.resolve("$owner.class")
        Files.createDirectories(output.parent)
        Files.copy(compiled.resolve("$owner.class"), output)
        val repository = generateSequence(Path.of("").toRealPath(), Path::getParent)
            .first { Files.isRegularFile(it.resolve("settings.gradle.kts")) }
        val relative = "index/src/test/kotlin/dev/kartograph/index/fixture/aliases/SourceDeclarationAliasedOverloadFixture.kt"
        val graph = ClassFileIndexer(callbackFacts = false).index(listOf(classes))
        val result = SourceDeclarationLocations.enrich(graph, repository, graph.nodeIds.associateWith { relative })
        listOf("(I)I", "(Ljava/lang/String;)Ljava/lang/String;").forEach { descriptor ->
            val id = JvmNodeId.methodId(owner, "leaf", descriptor)
            assertNull(result.nodes.getValue(id).sourceDeclaration)
            assertEquals(graph.nodes.getValue(id).location, result.nodes.getValue(id).location)
        }
    }

    @Test fun `missing corroboration overlapping extents and descriptor permutations remain absent`(@TempDir root: Path) {
        val source = "package sample\nclass Overloads {\n fun leaf(value: Int) {}\n fun leaf(value: String) {}\n}\n"
        val graph = kotlinGraph()
        listOf(
            graph.nodes.values.map { if (it.kind == NodeKind.METHOD) it.copy(location = SourceLocation("Overloads.kt")) else it },
            graph.nodes.values.map { if (it.kind == NodeKind.METHOD) it.copy(location = SourceLocation("Overloads.kt", 2)) else it },
            graph.nodes.values.map { if (it.kind == NodeKind.METHOD) it.copy(location = SourceLocation("Overloads.kt", 7 - it.location!!.line!!)) else it },
            graph.nodes.values.map { if (it.kind == NodeKind.METHOD) it.copy(attributes = setOf(NodeAttribute.SOURCE_DECLARATION_LOCATION)) else it },
        ).forEach { nodes -> assertAbsent(root, source, CodeGraph(nodes, emptyList())) }
        val sameLine = source.replace("{}\n fun leaf", "{} fun leaf")
        assertAbsent(root, sameLine, CodeGraph(graph.nodes.values.map {
            if (it.kind == NodeKind.METHOD) it.copy(location = SourceLocation("Overloads.kt", 3)) else it
        }, emptyList()))
    }

    @Test fun `unresolved ambiguous shadowed and lowered Kotlin source types remain absent`(@TempDir root: Path) {
        val source = "package sample\nclass Overloads {\n fun leaf(value: Int) {}\n fun leaf(value: String) {}\n}\n"
        listOf(
            source.replace("value: String", "value: Unknown"),
            source.replace("value: String", "value: Int"),
            source.replace("value: Int", "value: Int?"),
            source.replace("value: Int", "value: List<Int>"),
            source.replace("value: Int", "vararg value: Int"),
            source.replace("value: Int", "value: Int = 0"),
            source.replace("fun leaf(value: Int)", "fun <Int> leaf(value: Int)"),
            source.replace("class Overloads", "class Overloads<Int>"),
            source.replace("class Overloads", "class Overloads : Parent()"),
            source + "class Int\n",
            source + "typealias Int = kotlin.String\n",
            source.replace("class Overloads", "import other.Int\nclass Overloads"),
            source.replace("class Overloads", "import other.Value as Int\nclass Overloads"),
            source.replace("class Overloads", "import other.*\nclass Overloads"),
            source.replace("fun leaf(value: Int)", "suspend fun leaf(value: Int)"),
            source.replace("fun leaf(value: Int)", "context(value: Int) fun leaf(value: Int)"),
        ).forEach { text -> assertAbsent(root, text, kotlinGraph(text)) }
        val graph = kotlinGraph(source)
        assertAbsent(root, source, CodeGraph(graph.nodes.values + GraphNode(
            JvmNodeId.classId("sample/Int"), "Int", NodeKind.CLASS,
        ), emptyList()))
    }

    @Test fun `unknown Java references arrays generic owners and builtin shadowing remain absent`(@TempDir root: Path) {
        val source = "package sample;\nclass Overloads {\n void leaf(int value) {}\n void leaf(String value) {}\n}\n"
        listOf(
            source.replace("String value", "Unknown value"),
            source.replace("String value", "String[] value"),
            source.replace("String value", "String... value"),
            source.replace("class Overloads", "class Overloads<String>"),
            source.replace("class Overloads", "class Overloads extends Parent"),
            source + "class String {}\n",
            source.replace("class Overloads", "import other.String;\nclass Overloads"),
            source.replace("class Overloads", "import other.*;\nclass Overloads"),
        ).forEach { text ->
            val graph = kotlinGraph(text).let { original -> CodeGraph(original.nodes.values.map {
                it.copy(location = it.location?.copy(path = "Overloads.java"), kotlinSourceName = null)
            }, emptyList()) }
            assertAbsent(root, text, graph, "Overloads.java")
        }
    }

    private fun kotlinGraph(source: String? = null): CodeGraph {
        val owner = "sample/Overloads"
        return CodeGraph(listOf(
            GraphNode(JvmNodeId.classId(owner), "Overloads", NodeKind.CLASS, location = SourceLocation("Overloads.kt", 2)),
            *listOf("(I)V", "(Ljava/lang/String;)V").mapIndexed { index, descriptor ->
                val line = source?.lineSequence()?.withIndex()?.filter { "leaf(" in it.value }?.toList()?.getOrNull(index)?.index?.plus(1)
                    ?: index + 3
                GraphNode(JvmNodeId.methodId(owner, "leaf", descriptor), "leaf", NodeKind.METHOD,
                    location = SourceLocation("Overloads.kt", line), kotlinSourceName = "leaf")
            }.toTypedArray(),
        ), emptyList())
    }

    private fun assertAbsent(root: Path, source: String, graph: CodeGraph, relative: String = "Overloads.kt") {
        Files.writeString(root.resolve(relative), source)
        val result = SourceDeclarationLocations.enrich(graph, root, graph.nodeIds.associateWith { relative })
        graph.nodes.values.filter { it.kind == NodeKind.METHOD }.forEach { node ->
            assertNull(result.nodes.getValue(node.id).sourceDeclaration, source)
            assertEquals(node.location, result.nodes.getValue(node.id).location)
        }
    }

    private fun assertCoordinate(graph: CodeGraph, result: CodeGraph, id: NodeId, source: String, marker: String) {
        val original = graph.nodes.getValue(id)
        val node = result.nodes.getValue(id)
        val evidence = assertNotNull(node.sourceDeclaration, id.value)
        val offset = source.indexOf(marker).also { assertTrue(it >= 0) }
        assertEquals(offset, evidence.offsetUtf16)
        assertEquals(source.take(offset).count { it == '\n' } + 1, evidence.line)
        assertEquals(offset - source.lastIndexOf('\n', offset), evidence.column)
        assertEquals(original.location, node.location)
        assertNotNull(node.location?.line)
        assertNull(node.location?.column)
        assertFalse(NodeAttribute.SOURCE_DECLARATION_LOCATION in node.attributes)
    }
}
