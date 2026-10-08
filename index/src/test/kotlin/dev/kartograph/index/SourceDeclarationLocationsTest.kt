package dev.kartograph.index

import dev.kartograph.core.*
import java.nio.file.Files
import java.nio.file.Path
import javax.tools.ToolProvider
import org.junit.jupiter.api.io.TempDir
import kotlin.test.*

class SourceDeclarationLocationsTest {
    @Test fun `different declared package cannot supply a source declaration line`(@TempDir root: Path) {
        Files.writeString(root.resolve("Container.kt"), "package unrelated\nclass Container\n")
        val node = GraphNode(
            NodeId("class:sample/Container"), "Container", NodeKind.CLASS,
            location = SourceLocation("Container.kt"),
        )
        val graph = CodeGraph(listOf(node), emptyList())
        val result = SourceDeclarationLocations.enrich(graph, root, mapOf(node.id to "Container.kt"))
        assertEquals(null, result.nodes.getValue(node.id).location?.line)
    }

    @Test fun `authoritative local enclosure prevents a guessed type declaration line`(@TempDir root: Path) {
        Files.writeString(root.resolve("Outer.kt"), "package sample\nclass Outer { fun f() { class Inner } }\n")
        val outer = GraphNode(
            NodeId("class:sample/Outer"), "Outer", NodeKind.CLASS, location = SourceLocation("Outer.kt"),
        )
        val local = GraphNode(
            NodeId("class:sample/Outer\$Inner"), "Inner", NodeKind.CLASS, location = SourceLocation("Outer.kt"),
        )
        val graph = CodeGraph(
            listOf(outer, local), emptyList(), enclosures = listOf(LexicalEnclosure(local.id, outer.id)),
        )
        val result = SourceDeclarationLocations.enrich(graph, root, graph.nodeIds.associateWith { "Outer.kt" })
        assertEquals(null, result.nodes.getValue(local.id).location?.line)
        assertEquals(2, result.nodes.getValue(outer.id).location?.line)
    }

    @Test fun `unique owner matched declarations supply type lines while ambiguous and observed lines stay intact`(
        @TempDir root: Path,
    ) {
        val file = root.resolve("Container.kt")
        Files.writeString(file, "package sample\n\nclass Container {\n    class Nested\n}\n")
        val outer = GraphNode(
            NodeId("class:sample/Container"), "Container", NodeKind.CLASS,
            location = SourceLocation("Container.kt"),
        )
        val inner = GraphNode(
            NodeId("class:sample/Container\$Nested"), "Nested", NodeKind.CLASS,
            location = SourceLocation("Container.kt"),
        )
        val seen = GraphNode(
            NodeId("class:sample/Observed"), "Observed", NodeKind.CLASS,
            location = SourceLocation("Container.kt", 17),
        )
        val graph = CodeGraph(listOf(outer, inner, seen), emptyList()).withCompilerCallPositions(emptyList())
        val result = SourceDeclarationLocations.enrich(graph, root, graph.nodeIds.associateWith { "Container.kt" })
        assertEquals(3, result.nodes.getValue(outer.id).location?.line)
        assertEquals(4, result.nodes.getValue(inner.id).location?.line)
        assertEquals(17, result.nodes.getValue(seen.id).location?.line)
        assertTrue(result.compilerCallPositionsCaptured)
        assertContains(result.nodes.getValue(outer.id).attributes, NodeAttribute.SOURCE_DECLARATION_LOCATION)
        assertFalse(NodeAttribute.SOURCE_DECLARATION_LOCATION in result.nodes.getValue(seen.id).attributes)
    }

    @Test fun `resolved subdirectory source must retain the node location basename`(@TempDir root: Path) {
        val relative = "nested/Container.java"
        Files.createDirectories(root.resolve("nested"))
        Files.writeString(root.resolve(relative), """
            package sample;
            class Container {
                int good;
                int mismatched;
                int missingLocation;
            }
        """.trimIndent())
        val owner = GraphNode(
            JvmNodeId.classId("sample/Container"), "Container", NodeKind.CLASS,
            location = SourceLocation("Container.java", 2),
        )
        val good = GraphNode(
            JvmNodeId.fieldId("sample/Container", "good", "I"), "good", NodeKind.FIELD,
            location = SourceLocation("Container.java"),
        )
        val mismatched = GraphNode(
            JvmNodeId.fieldId("sample/Container", "mismatched", "I"), "mismatched", NodeKind.FIELD,
            location = SourceLocation("Debug.java"),
        )
        val missing = GraphNode(
            JvmNodeId.fieldId("sample/Container", "missingLocation", "I"),
            "missingLocation", NodeKind.FIELD,
        )
        val graph = CodeGraph(listOf(owner, good, mismatched, missing), emptyList())
        val result = SourceDeclarationLocations.enrich(
            graph, root, listOf(good, mismatched, missing).associate { it.id to relative },
        )
        assertEquals(3, result.nodes.getValue(good.id).location?.line)
        assertEquals(relative, result.nodes.getValue(good.id).location?.path)
        listOf(mismatched, missing).forEach { node ->
            assertEquals(null, result.nodes.getValue(node.id).location?.line)
            assertFalse(NodeAttribute.SOURCE_DECLARATION_LOCATION in result.nodes.getValue(node.id).attributes)
        }
    }

    @Test fun `authoritative local ancestry and file facade types are never source declarations`(@TempDir root: Path) {
        val source = """
            package sample
            class Outer { class Local { class Nested } class Locality }
            class FacadeCollision
        """.trimIndent()
        Files.writeString(root.resolve("Outer.kt"), source)
        val outer = GraphNode(
            JvmNodeId.classId("sample/Outer"), "Outer", NodeKind.CLASS,
            location = SourceLocation("Outer.kt", 2),
        )
        val local = GraphNode(
            JvmNodeId.classId("sample/Outer\$Local"), "Local", NodeKind.CLASS,
            location = SourceLocation("Outer.kt"),
        )
        val nested = GraphNode(
            JvmNodeId.classId("sample/Outer\$Local\$Nested"), "Nested", NodeKind.CLASS,
            location = SourceLocation("Outer.kt"),
        )
        val locality = GraphNode(
            JvmNodeId.classId("sample/Outer\$Locality"), "Locality", NodeKind.CLASS,
            location = SourceLocation("Outer.kt"),
        )
        val facade = GraphNode(
            JvmNodeId.classId("sample/FacadeCollision"), "FacadeCollision", NodeKind.CLASS,
            attributes = setOf(NodeAttribute.FILE_FACADE),
            location = SourceLocation("Outer.kt"),
        )
        val graph = CodeGraph(
            listOf(outer, local, nested, locality, facade), emptyList(),
            enclosures = listOf(LexicalEnclosure(local.id, outer.id)),
        )
        val result = SourceDeclarationLocations.enrich(
            graph, root, listOf(local, nested, locality, facade).associate { it.id to "Outer.kt" },
        )
        listOf(local, nested, facade).forEach { node ->
            assertEquals(null, result.nodes.getValue(node.id).location?.line, node.id.value)
            assertFalse(NodeAttribute.SOURCE_DECLARATION_LOCATION in result.nodes.getValue(node.id).attributes)
        }
        assertEquals(2, result.nodes.getValue(locality.id).location?.line)
        assertContains(result.nodes.getValue(locality.id).attributes, NodeAttribute.SOURCE_DECLARATION_LOCATION)
    }

    @Test fun `Java constructor candidate must have the active source owner name`(@TempDir root: Path) {
        val source = """
            package sample;
            class Container {
                Wrong() {}
                Container(int value) {}
            }
        """.trimIndent()
        Files.writeString(root.resolve("Container.java"), source)
        val owner = GraphNode(
            JvmNodeId.classId("sample/Container"), "Container", NodeKind.CLASS,
            location = SourceLocation("Container.java", 2),
        )
        val stale = GraphNode(
            JvmNodeId.methodId("sample/Container", "<init>", "()V"),
            "<init>", NodeKind.CONSTRUCTOR, location = SourceLocation("Container.java"),
        )
        val explicit = GraphNode(
            JvmNodeId.methodId("sample/Container", "<init>", "(I)V"),
            "<init>", NodeKind.CONSTRUCTOR, location = SourceLocation("Container.java"),
        )
        val graph = CodeGraph(listOf(owner, stale, explicit), emptyList())
        val result = SourceDeclarationLocations.enrich(
            graph, root, listOf(stale, explicit).associate { it.id to "Container.java" },
        )
        assertEquals(null, result.nodes.getValue(stale.id).location?.line)
        assertEquals(4, result.nodes.getValue(explicit.id).location?.line)
    }

    @Test fun `same-line same-arity overload occurrences remain ambiguous`(@TempDir root: Path) {
        Files.writeString(root.resolve("Container.kt"), """
            package sample
            class Container { fun same(value: String) {} fun same(value: Int) {} }
        """.trimIndent())
        val owner = GraphNode(
            JvmNodeId.classId("sample/Container"), "Container", NodeKind.CLASS,
            location = SourceLocation("Container.kt", 2),
        )
        val stringOverload = GraphNode(
            JvmNodeId.methodId("sample/Container", "same", "(Ljava/lang/String;)V"),
            "same", NodeKind.METHOD, location = SourceLocation("Container.kt"),
        )
        val intOverload = GraphNode(
            JvmNodeId.methodId("sample/Container", "same", "(I)V"),
            "same", NodeKind.METHOD, location = SourceLocation("Container.kt"),
        )
        val graph = CodeGraph(listOf(owner, stringOverload, intOverload), emptyList())
        val result = SourceDeclarationLocations.enrich(
            graph, root, listOf(stringOverload, intOverload).associate { it.id to "Container.kt" },
        )
        listOf(stringOverload, intOverload).forEach { node ->
            assertEquals(null, result.nodes.getValue(node.id).location?.line)
        }
    }

    @Test fun `actual javac source headers add only unique direct member lines`(@TempDir root: Path) {
        val sourceText = """
            package sample;
            public class Container {
                public int field;
                public Container() {}
                public Container(String value) {}
                public void unique(String value) {}
                public void overloaded(String value) {}
                public void overloaded(Integer value) {}
                public void holder(String parameter) {
                    class Local {
                        int localField;
                        void localMethod() {}
                    }
                }
                public static class Nested {
                    public long nestedField;
                    public void nestedMethod() {}
                }
                public static class Empty {}
            }
        """.trimIndent()
        val source = root.resolve("Container.java")
        val classes = Files.createDirectories(root.resolve("classes"))
        Files.writeString(source, sourceText)
        assertEquals(0, requireNotNull(ToolProvider.getSystemJavaCompiler()).run(
            null, null, null, "-g:source", "-d", classes.toString(), source.toString(),
        ))
        val indexed = ClassFileIndexer(callbackFacts = false).index(listOf(classes))
        val owner = "sample/Container"
        val selected = setOf(
            JvmNodeId.classId(owner),
            JvmNodeId.fieldId(owner, "field", "I"),
            JvmNodeId.methodId(owner, "<init>", "()V"),
            JvmNodeId.methodId(owner, "<init>", "(Ljava/lang/String;)V"),
            JvmNodeId.methodId(owner, "unique", "(Ljava/lang/String;)V"),
            JvmNodeId.methodId(owner, "overloaded", "(Ljava/lang/String;)V"),
            JvmNodeId.methodId(owner, "overloaded", "(Ljava/lang/Integer;)V"),
            JvmNodeId.fieldId("$owner\$Nested", "nestedField", "J"),
            JvmNodeId.methodId("$owner\$Nested", "nestedMethod", "()V"),
            JvmNodeId.classId("$owner\$Empty"),
        )
        selected.forEach { assertEquals(null, indexed.nodes.getValue(it).location?.line, it.value) }
        val ghosts = listOf(
            GraphNode(JvmNodeId.fieldId(owner, "value", "Ljava/lang/String;"), "value", NodeKind.FIELD,
                location = SourceLocation("Container.java")),
            GraphNode(JvmNodeId.fieldId(owner, "parameter", "Ljava/lang/String;"), "parameter", NodeKind.FIELD,
                location = SourceLocation("Container.java")),
        )
        val withoutLines = replaceNodes(indexed, indexed.nodes.values.map { node ->
            if (node.id in selected) node.copy(location = SourceLocation("Container.java")) else node
        } + ghosts)
        val result = SourceDeclarationLocations.enrich(
            withoutLines, root, withoutLines.nodeIds.associateWith { "Container.java" },
        )
        fun line(marker: String) = sourceText.lineSequence().indexOfFirst { marker in it } + 1
        assertEquals(line("class Container"), result.nodes.getValue(JvmNodeId.classId(owner)).location?.line)
        assertEquals(line("int field"), result.nodes.getValue(JvmNodeId.fieldId(owner, "field", "I")).location?.line)
        assertEquals(
            line("Container()"),
            result.nodes.getValue(JvmNodeId.methodId(owner, "<init>", "()V")).location?.line,
        )
        assertEquals(line("Container(String"), result.nodes.getValue(
            JvmNodeId.methodId(owner, "<init>", "(Ljava/lang/String;)V")).location?.line)
        assertEquals(line("void unique"), result.nodes.getValue(
            JvmNodeId.methodId(owner, "unique", "(Ljava/lang/String;)V")).location?.line)
        assertEquals(line("long nestedField"), result.nodes.getValue(
            JvmNodeId.fieldId("$owner\$Nested", "nestedField", "J")).location?.line)
        assertEquals(line("void nestedMethod"), result.nodes.getValue(
            JvmNodeId.methodId("$owner\$Nested", "nestedMethod", "()V")).location?.line)
        assertEquals(line("class Empty"), result.nodes.getValue(JvmNodeId.classId("$owner\$Empty")).location?.line)
        assertEquals(null, result.nodes.getValue(JvmNodeId.methodId(
            owner, "overloaded", "(Ljava/lang/String;)V")).location?.line)
        assertEquals(null, result.nodes.getValue(JvmNodeId.methodId(
            owner, "overloaded", "(Ljava/lang/Integer;)V")).location?.line)
        ghosts.forEach { assertEquals(null, result.nodes.getValue(it.id).location?.line) }
        val localOwners = indexed.enclosures.mapTo(mutableSetOf()) { it.localClass.value.removePrefix("class:") }
        result.nodes.values.filter { node -> node.jvmOwner() in localOwners }.forEach { node ->
            assertEquals(null, node.location?.line, node.id.value)
            assertFalse(NodeAttribute.SOURCE_DECLARATION_LOCATION in node.attributes)
        }
        selected.filterNot { it.value.contains("overloaded") }.forEach { id ->
            assertContains(result.nodes.getValue(id).attributes, NodeAttribute.SOURCE_DECLARATION_LOCATION)
        }
    }

    @Test fun `compiled Kotlin fixture adds property and unique function lines without guessing overloads or accessors`(
        @TempDir root: Path,
    ) {
        val repository = generateSequence(Path.of("").toRealPath(), Path::getParent)
            .first { Files.isRegularFile(it.resolve("settings.gradle.kts")) }
        val relative = "index/src/test/kotlin/dev/kartograph/index/SourceDeclarationLocationsTest.kt"
        val source = repository.resolve(relative)
        assertTrue(Files.isRegularFile(source))
        val codeSource = requireNotNull(SourceDeclarationKotlinFixture::class.java.protectionDomain.codeSource)
        val compiledRoot = Path.of(codeSource.location.toURI())
        val classRelative = Path.of("dev/kartograph/index/SourceDeclarationKotlinFixture.class")
        val isolated = Files.createDirectories(root.resolve("classes"))
        Files.createDirectories(isolated.resolve(classRelative).parent)
        Files.copy(compiledRoot.resolve(classRelative), isolated.resolve(classRelative))
        val indexed = ClassFileIndexer(callbackFacts = false).index(listOf(isolated))
        val owner = "dev/kartograph/index/SourceDeclarationKotlinFixture"
        val property = JvmNodeId.fieldId(owner, "directProperty", "Ljava/lang/String;")
        val unique = JvmNodeId.methodId(owner, "directMethod", "(Ljava/lang/String;)Ljava/lang/String;")
        val stringOverload = JvmNodeId.methodId(owner, "sameArity", "(Ljava/lang/String;)V")
        val intOverload = JvmNodeId.methodId(owner, "sameArity", "(I)V")
        val getter = JvmNodeId.methodId(owner, "getDirectProperty", "()Ljava/lang/String;")
        val constructor = JvmNodeId.methodId(owner, "<init>", "()V")
        val selected = setOf(property, unique, stringOverload, intOverload, getter, constructor)
        assertNotNull(indexed.nodes.getValue(unique).location?.line)
        val withoutLines = replaceNodes(indexed, indexed.nodes.values.map { node ->
            if (node.id in selected) node.copy(location = SourceLocation(relative)) else node
        })
        val result = SourceDeclarationLocations.enrich(
            withoutLines, repository, selected.associateWith { relative },
        )
        val lines = Files.readAllLines(source)
        fun line(marker: String) = lines.indexOfFirst { it.trim() == marker } + 1
        assertEquals(line("val directProperty: String = \"value\""), result.nodes.getValue(property).location?.line)
        assertEquals(
            line("fun directMethod(value: String): String = value"),
            result.nodes.getValue(unique).location?.line,
        )
        listOf(stringOverload, intOverload, getter, constructor).forEach { id ->
            assertEquals(null, result.nodes.getValue(id).location?.line, id.value)
            assertFalse(NodeAttribute.SOURCE_DECLARATION_LOCATION in result.nodes.getValue(id).attributes)
        }
        listOf(property, unique).forEach { id ->
            assertContains(result.nodes.getValue(id).attributes, NodeAttribute.SOURCE_DECLARATION_LOCATION)
        }
    }

    @Test fun `actual javac default package field keeps the empty package match`(@TempDir root: Path) {
        val sourceText = """
            public class DefaultContainer {
                public int field;
            }
        """.trimIndent()
        val source = root.resolve("DefaultContainer.java")
        val classes = Files.createDirectories(root.resolve("classes"))
        Files.writeString(source, sourceText)
        assertEquals(0, requireNotNull(ToolProvider.getSystemJavaCompiler()).run(
            null, null, null, "-g:source", "-d", classes.toString(), source.toString(),
        ))
        val indexed = ClassFileIndexer(callbackFacts = false).index(listOf(classes))
        val field = JvmNodeId.fieldId("DefaultContainer", "field", "I")
        val withoutLine = replaceNodes(indexed, indexed.nodes.values.map { node ->
            if (node.id == field) node.copy(location = SourceLocation("DefaultContainer.java")) else node
        })
        val result = SourceDeclarationLocations.enrich(
            withoutLine, root, mapOf(field to "DefaultContainer.java"),
        )
        assertEquals(2, result.nodes.getValue(field).location?.line)
        assertContains(result.nodes.getValue(field).attributes, NodeAttribute.SOURCE_DECLARATION_LOCATION)
    }

    private fun replaceNodes(graph: CodeGraph, nodes: Iterable<GraphNode>): CodeGraph {
        val result = CodeGraph(nodes, graph.edges, graph.externalCalls, graph.serviceProviders, graph.enclosures,
            graph.callbackArguments, graph.parameterUses, graph.lambdaEscapes)
        return if (graph.compilerCallPositionsCaptured) {
            result.withCompilerCallPositions(graph.locatedCompilerReferences)
        } else result
    }

    private fun GraphNode.jvmOwner(): String? =
        id.value.substringAfter(':', "").substringBefore('#').takeIf { '/' in it || it.isNotEmpty() }
}

private class SourceDeclarationKotlinFixture {
    val directProperty: String = "value"
    fun directMethod(value: String): String = value
    fun sameArity(value: String) { check(value.isNotEmpty()) }
    fun sameArity(value: Int) { check(value >= 0) }
}
