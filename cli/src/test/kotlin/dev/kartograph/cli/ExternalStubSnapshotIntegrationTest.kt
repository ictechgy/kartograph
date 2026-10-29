package dev.kartograph.cli

import dev.kartograph.core.*
import dev.kartograph.export.QuerySnapshot
import dev.kartograph.export.QuerySnapshotCodec
import dev.kartograph.index.ClassFileIndexer
import dev.kartograph.index.GraphExportIndexOptions
import java.nio.file.Files
import java.nio.file.Path
import javax.tools.ToolProvider
import org.junit.jupiter.api.io.TempDir
import kotlin.test.*

class ExternalStubSnapshotIntegrationTest {
    @Test
    fun `plain snapshot roundtrips javac graph export with external stubs`(@TempDir root: Path) = roundtrip(root, false)

    @Test
    fun `compact snapshot roundtrips javac graph export with external stubs`(@TempDir root: Path) = roundtrip(root, true)

    private fun roundtrip(root: Path, compact: Boolean) {
        val source = Files.writeString(root.resolve("Entry.java"), """
            package sample;
            public class Entry {
                public interface Contract { int apply(); }
                public static class ProjectBase { public int inherited() { return 5; } }
                public static class Base extends ProjectBase {
                    public int value() { return 1; }
                    public static int utility() { return 2; }
                }
                public static class Implementation extends Base implements Contract {
                    public int value() { return 3; }
                    public int apply() { return 4; }
                }
                public static int run(Base base, Contract contract) {
                    new Base();
                    return base.value() + contract.apply() + Base.utility() + base.inherited();
                }
            }
        """.trimIndent())
        val classes = Files.createDirectory(root.resolve("classes"))
        assertEquals(0, assertNotNull(ToolProvider.getSystemJavaCompiler()).run(null, null, null,
            "--release", "17", "-g", "-d", classes.toString(), source.toString()))
        val dependency = Files.createDirectories(root.resolve("dependency/sample"))
        for (name in listOf("Base", "Contract")) {
            Files.move(classes.resolve("sample/Entry\$$name.class"), dependency.resolve("Entry\$$name.class"))
        }
        val indexer = ClassFileIndexer()
        val withoutStubs = indexer.indexForGraphExport(listOf(classes), listOf(dependency.parent)).indexed.graph
        val result = indexer.indexForGraphExport(listOf(classes), listOf(dependency.parent),
            options = GraphExportIndexOptions(includeExternalStubs = true))
        val graph = result.indexed.graph
        assertEquals(withoutStubs.externalCalls, graph.externalCalls)
        assertTrue(graph.externalCalls.isNotEmpty())
        assertTrue(withoutStubs.externalCalls.all { it.target !in withoutStubs.nodes })
        assertTrue(graph.externalCalls.all { NodeAttribute.EXTERNAL_STUB in graph.nodes.getValue(it.target).attributes })
        val caller = NodeId("method:sample/Entry#run(Lsample/Entry\$Base;Lsample/Entry\$Contract;)I")
        val calls = graph.externalCalls.filter { it.caller == caller }
        assertEquals(setOf(InvocationKind.SPECIAL, InvocationKind.VIRTUAL, InvocationKind.INTERFACE, InvocationKind.STATIC),
            calls.map { it.kind }.toSet())
        assertEquals(5, calls.size)
        val constructor = calls.single { it.kind == InvocationKind.SPECIAL }
        assertEquals("<init>", constructor.name)
        assertEquals(NodeKind.CONSTRUCTOR, graph.nodes.getValue(constructor.target).kind)
        assertNull(constructor.declaration)
        for (kind in listOf(InvocationKind.VIRTUAL, InvocationKind.INTERFACE, InvocationKind.STATIC)) {
            val call = calls.single { it.kind == kind && it.name != "inherited" }
            assertEquals(CallDeclaration(call.target, CallDeclarationScope.EXTERNAL, listOf(call.owner)), call.declaration)
        }
        for ((kind, name) in listOf(InvocationKind.VIRTUAL to "value", InvocationKind.INTERFACE to "apply")) {
            val call = calls.single { it.kind == kind && it.name != "inherited" }
            assertEquals(CallResolution.PROJECT_CANDIDATES, call.resolution)
            val body = NodeId("method:sample/Entry\$Implementation#$name()I")
            assertEquals(listOf(body), call.resolvedTargets)
            assertTrue(graph.edges.any { it.source == caller && it.target == body && it.origin == EdgeOrigin.DISPATCH_MODEL })
        }
        val inherited = calls.single { it.name == "inherited" }
        assertEquals(CallDeclaration(NodeId("method:sample/Entry\$ProjectBase#inherited()I"),
            CallDeclarationScope.PROJECT, listOf("sample/Entry\$Base", "sample/Entry\$ProjectBase")), inherited.declaration)
        for (original in listOf(withoutStubs, graph)) {
            val snapshot = QuerySnapshot(original, emptyList(), result.limitations)
            val encoded = QuerySnapshotCodec.render(snapshot, compact)
            val restored = QuerySnapshotCodec.parse(encoded)
            assertEquals(original.nodes, restored.graph.nodes)
            assertEquals(original.externalCalls, restored.graph.externalCalls)
            assertEquals(original.externalCalls.map { it.declaration }, restored.graph.externalCalls.map { it.declaration })
            assertEquals(original.edges, restored.graph.edges)
            assertEquals(encoded, QuerySnapshotCodec.render(restored, compact))
        }
    }
}
