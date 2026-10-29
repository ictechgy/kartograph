package dev.kartograph.export

import dev.kartograph.core.*
import kotlin.test.*

class CompilerCallerInventorySnapshotTest {
    private val id = NodeId("method:Entry#zero()V")
    private fun graph(): CodeGraph {
        val source = CompilerEvidenceSource("src/Entry.java", "a".repeat(64))
        val inventory = CompilerCallerInventory("main", "compileJava", "classes", listOf(source),
            listOf(CompilerSourceBody("b0", source, 0, 20, CompilerSourceBodyKind.ORDINARY_METHOD, true, "JAVAC_EXPLICIT", listOf(id))),
            listOf(CompilerInventoryOutput("Entry.class", "b".repeat(64), "Entry")),
            listOf(CompilerInventoryMethod(id, 9, true, CompilerBackendOrigin.SOURCE_BOUND, "b0", "JAVAC_EXPLICIT")),
            CompilerSourceCoordinateBasis.JAVAC_UTF16_CHAR_SEQUENCE, "17.0.20", "c".repeat(64), "d".repeat(64), "e".repeat(64), "f".repeat(64))
        return CodeGraph(listOf(GraphNode(NodeId("class:Entry"), "Entry", NodeKind.CLASS), GraphNode(id, "zero", NodeKind.METHOD, jvmModifiers = setOf(JvmModifier.STATIC))), emptyList()).withCompilerCallerInventories(listOf(inventory))
    }
    @Test fun `plain and compact replay retain full source and backend accounting`() {
        for (compact in listOf(false, true)) {
            val graph = graph()
            val json = QuerySnapshotCodec.render(QuerySnapshot(graph, emptyList(), emptyList()), compact)
            val read = QuerySnapshotCodec.parse(json)
            assertEquals(graph.compilerCallerInventories, read.graph.compilerCallerInventories)
            assertEquals(json, QuerySnapshotCodec.render(read, compact))
            assertEquals(graph.edges, read.graph.edges)
            assertFalse(read.graph.compilerCallPositionsCaptured)
        }
    }
    @Test fun `node kind contradictions fail closed in plain and compact replay`() {
        val original = graph()
        val changed = CodeGraph(original.nodes.values.map { if (it.id == id) it.copy(kind = NodeKind.CONSTRUCTOR) else it }, emptyList())
            .withCompilerCallerInventories(original.compilerCallerInventories)
        for (compact in listOf(false, true)) {
            val json = QuerySnapshotCodec.render(QuerySnapshot(changed, emptyList(), emptyList()), compact)
            val replay = QuerySnapshotCodec.parse(json).graph.compilerCallerInventories.single()
            assertFalse(replay.projectionComplete)
            assertEquals(original.compilerCallerInventories.single().methods, replay.methods)
        }
    }
    @Test fun `abstract native and concrete contradictions remain incomplete in both snapshots`() {
        val original = graph()
        for ((access, modifiers) in listOf(0x0401 to emptySet(), 0x0101 to emptySet(),
            1 to setOf(JvmModifier.ABSTRACT), 1 to setOf(JvmModifier.NATIVE))) {
            val inventory = original.compilerCallerInventories.single().let { row -> row.copy(methods = row.methods.map {
                it.copy(access = access, hasCode = access and (0x0400 or 0x0100) == 0) }) }
            val changed = CodeGraph(original.nodes.values.map { if (it.id == id) it.copy(jvmModifiers = modifiers) else it }, emptyList())
                .withCompilerCallerInventories(listOf(inventory))
            for (compact in listOf(false, true)) {
                val replay = QuerySnapshotCodec.parse(QuerySnapshotCodec.render(QuerySnapshot(changed, emptyList(), emptyList()), compact)).graph
                assertFalse(replay.compilerCallerInventories.single().projectionComplete)
                assertEquals(inventory.methods, replay.compilerCallerInventories.single().methods)
            }
        }
    }
    @Test fun `preservation downgrade and complete missing inventory survive both snapshots`() {
        val original = graph()
        val projected = CodeGraph(emptyList(), emptyList()).withCompilerSourceFactsFrom(original)
        for (compact in listOf(false, true)) {
            val replay = QuerySnapshotCodec.parse(QuerySnapshotCodec.render(QuerySnapshot(projected, emptyList(), emptyList()), compact)).graph
            assertFalse(replay.compilerCallerInventories.single().projectionComplete)
            assertEquals(original.compilerCallerInventories.single().methods, replay.compilerCallerInventories.single().methods)
            assertEquals(original.compilerCallerInventories.single().outputs, replay.compilerCallerInventories.single().outputs)
        }
    }
    @Test fun `unsupported versions omitted body rows and duplicate tasks fail strict replay`() {
        for (compact in listOf(false, true)) {
            val value = SnapshotJsonParser(QuerySnapshotCodec.render(QuerySnapshot(graph(), emptyList(), emptyList()), compact)).parse() as Map<*, *>
            val graph = value["graph"] as Map<*, *>
            val table = graph["compilerCallerInventories"] as Map<*, *>
            val inventories = table["inventories"] as List<*>
            val first = inventories.single() as Map<*, *>
            val variants = listOf(
                table.toMutableMap().apply { put("version", 2L) },
                table.toMutableMap().apply { put("inventories", listOf(first.toMutableMap().apply { put("bodies", emptyList<Any>()) })) },
                table.toMutableMap().apply { put("inventories", inventories + inventories) },
                table.toMutableMap().apply { put("inventories", listOf(first.toMutableMap().apply { put("bodyCounts", mapOf("src/Entry.java" to 0L)) })) },
            )
            for (changed in variants) {
                val raw = value.toMutableMap().apply { put("graph", graph.toMutableMap().apply { put("compilerCallerInventories", changed) }) }
                assertFailsWith<IllegalArgumentException> { QuerySnapshotCodec.parse(jsonValue(raw)) }
            }
        }
    }
    @Test fun `legacy omission remains byte stable and projected inventory stays incomplete`() {
        val legacy = QuerySnapshot(CodeGraph(emptyList(), emptyList()), emptyList(), emptyList())
        for (compact in listOf(false, true)) {
            val json = QuerySnapshotCodec.render(legacy, compact)
            assertFalse(json.contains("compilerCallerInventories"))
            assertEquals(json, QuerySnapshotCodec.render(QuerySnapshotCodec.parse(json), compact))
            val projected = CodeGraph(emptyList(), emptyList()).withCompilerCallerInventories(graph().compilerCallerInventories)
            val replay = QuerySnapshotCodec.parse(QuerySnapshotCodec.render(QuerySnapshot(projected, emptyList(), emptyList()), compact))
            assertFalse(replay.graph.compilerCallerInventories.single().projectionComplete)
            assertEquals(id, replay.graph.compilerCallerInventories.single().methods.single().id)
        }
    }
}
