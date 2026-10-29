package dev.kartograph.cli

import dev.kartograph.core.*
import dev.kartograph.export.QuerySnapshot
import dev.kartograph.export.QuerySnapshotCodec
import java.io.ByteArrayOutputStream
import java.io.PrintStream
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.*
import org.junit.jupiter.api.io.TempDir

class AndroidResourceImpactCliTest {
    @Test
    fun `layout file selects exact namespace fields and include parents with independent tests`(@TempDir root: Path) {
        val snapshot = fixture("Current", listOf("child", "parent", "unrelated"))
        val file = save(root, "current", snapshot)
        val result = run("impact", "--file", "src/main/res/layout/child.xml", "--graph-file", file.toString(), "--all")
        assertEquals(0, result.first, result.third)
        assertContains(result.second, field("dev.left", "child").value)
        assertContains(result.second, field("dev.left", "parent").value)
        for (name in listOf("child", "parent")) {
            assertContains(result.second, caller("dev.left", "Current", name).value)
            assertContains(result.second, test("dev.left", "Current", name).value)
        }
        assertFalse(result.second.contains(caller("dev.left", "Current", "unrelated").value))
        assertFalse(result.second.contains("method:dev/right/"))
        assertContains(result.second, "\"resourceSelections\"")
        assertContains(result.second, "\"sourceDefinitionId\"")
        assertContains(result.second, "\"sourceSha256\"")
        assertContains(result.second, "\"resourceFactsSha256\"")
        assertContains(result.second, "\"origin\": \"bytecode\"")
        assertContains(result.second, "\"coverage\": \"partial\"")
        assertFalse(result.second.contains(root.toString()))
    }

    @Test
    fun `deleted layout retains base tests without replaying its field in admitted empty current`(@TempDir root: Path) {
        val base = save(root, "base", fixture("Base", listOf("child")))
        val current = save(root, "current", fixture("Current", emptyList()))
        val result = run("impact", "--file", "src/main/res/layout/child.xml",
            "--graph-file", current.toString(), "--base-graph", base.toString(), "--all")
        assertEquals(0, result.first, result.third)
        assertContains(result.second, caller("dev.left", "Base", "child").value)
        assertFalse(result.second.contains("method:dev/left/Current"))
        assertContains(selectionMap(result.second), "\"current\": []")
        assertContains(result.second, "\"status\": \"absent\"")
    }

    @Test
    fun `renamed XML paths select their own revision keys`(@TempDir root: Path) {
        val base = save(root, "base", fixture("Base", listOf("child")))
        val current = save(root, "current", fixture("Current", listOf("renamed")))
        val result = run("impact", "--file", "src/main/res/layout/child.xml",
            "--file", "src/main/res/layout/renamed.xml", "--graph-file", current.toString(),
            "--base-graph", base.toString(), "--all")
        assertEquals(0, result.first, result.third)
        assertContains(result.second, caller("dev.left", "Base", "child").value)
        assertContains(result.second, caller("dev.left", "Current", "renamed").value)
        assertFalse(result.second.contains(caller("dev.left", "Current", "child").value))
        assertFalse(result.second.contains(caller("dev.left", "Base", "renamed").value))
    }

    @Test
    fun `legacy current channel stays unresolved even when the same field exists`(@TempDir root: Path) {
        val base = save(root, "base", fixture("Base", listOf("child")))
        val currentSnapshot = fixture("Current", listOf("child")).copy(androidResourceEvidence = null, androidResourceBindings = null)
        val current = save(root, "current", currentSnapshot)
        for (extra in listOf(emptyArray<String>(), arrayOf("--source-edits"))) {
            val result = run("impact", "--file", "src/main/res/layout/child.xml",
                "--graph-file", current.toString(), "--base-graph", base.toString(), *extra)
            assertEquals(64, result.first, result.third)
            assertContains(result.second, "resourceEvidenceUnavailable")
            assertContains(selectionMap(result.second), "\"current\": []")
            assertFalse(result.second.contains("method:dev/left/Current"))
        }
    }

    @Test
    fun `scope mismatch cannot mix independently valid resource records`(@TempDir root: Path) {
        val base = save(root, "base", fixture("Base", listOf("child"), scope = ":other:debug"))
        val current = save(root, "current", fixture("Current", listOf("child")))
        assertEquals(2, run("impact", "--file", "src/main/res/layout/child.xml",
            "--graph-file", current.toString(), "--base-graph", base.toString()).first)
    }

    private fun fixture(prefix: String, names: List<String>, scope: String = ":app:debug"): QuerySnapshot {
        val namespace = "dev.left"
        val root = AndroidResourceRoot(InputFingerprint("android-resources", "src/main/res", "1".repeat(64)))
        val definitions = names.map { name ->
            AndroidLayoutDefinition(AndroidResourceKey(namespace, "layout", name), root.id,
                CompilerEvidenceSource("src/main/res/layout/$name.xml", "2".repeat(64)), "layout/$name.xml")
        }
        val includes = if ("parent" in names && "child" in names) listOf(AndroidLayoutInclude(
            definitions.single { it.key.name == "parent" }.id, 0, AndroidLayoutIncludeKind.LITERAL,
            AndroidResourceKey(namespace, "layout", "child"))) else emptyList()
        val facts = AndroidResourceEvidence(scope, namespace, listOf(root), definitions, includes)
        val nodes = mutableListOf<GraphNode>()
        val edges = mutableListOf<GraphEdge>()
        for ((module, moduleLabel) in listOf("dev.left" to "FeatureOne", "dev.right" to "FeatureTwo")) {
            val owner = NodeId("class:" + module.replace('.', '/') + "/R\$layout")
            nodes += GraphNode(owner, "R.layout", NodeKind.CLASS, moduleName = moduleLabel)
            for (name in listOf("child", "parent", "unrelated", "renamed")) {
                val id = field(module, name)
                nodes += GraphNode(id, name, NodeKind.FIELD, moduleName = moduleLabel,
                    jvmSignature = id.value.removePrefix("field:"), jvmModifiers = setOf(JvmModifier.STATIC))
                nodes += GraphNode(caller(module, prefix, name), name, NodeKind.METHOD, moduleName = moduleLabel,
                    location = SourceLocation("src/main/java/" + module.replace('.', '/') + "/$prefix.java", 1))
                nodes += GraphNode(test(module, prefix, name), name, NodeKind.METHOD, moduleName = moduleLabel,
                    location = SourceLocation("src/test/java/" + module.replace('.', '/') + "/${prefix}Test.java", 1))
                edges += GraphEdge(owner, id, EdgeKind.MEMBER)
                edges += GraphEdge(caller(module, prefix, name), id, EdgeKind.FIELD_ACCESS)
                edges += GraphEdge(test(module, prefix, name), caller(module, prefix, name), EdgeKind.CALL)
            }
        }
        val graph = CodeGraph(nodes, edges)
        val options = InputFingerprint("options", "app-processDebugResources-options",
            AndroidResourceBindingIdentity.namespaceOptions(namespace))
        val output = InputFingerprint("classes", "build/R.jar", "3".repeat(64))
        val inputs = listOf("sources", "buildConfig", "compiler").map { InputFingerprint(it, it, "4".repeat(64)) } + options + root.input
        val witness = BuildWitness(scope, "agp-process-resources", ":app:processDebugResources", inputs, listOf(output))
        val provenance = SnapshotProvenance(listOf(output), listOf(witness))
        val binding = AndroidResourceBindingEvidence(scope, namespace, AndroidResourceBindingIdentity.facts(facts),
            witness.artifact, AndroidResourceBindingIdentity.producer(witness), options, output, listOf(root.input),
            definitions.map { AndroidResourceFieldOutcome(it.key, field = field(namespace, it.key.name)) })
        return QuerySnapshot(graph, emptyList(), emptyList(), scope = scope, provenance = provenance,
            androidResourceEvidence = facts, androidResourceBindings = binding)
    }

    private fun field(namespace: String, name: String) = NodeId("field:" + namespace.replace('.', '/') + "/R\$layout#$name:I")
    private fun caller(namespace: String, prefix: String, name: String) = NodeId("method:" + namespace.replace('.', '/') + "/$prefix#$name()I")
    private fun test(namespace: String, prefix: String, name: String) = NodeId("method:" + namespace.replace('.', '/') + "/${prefix}Test#$name()V")
    private fun save(root: Path, name: String, snapshot: QuerySnapshot) =
        root.resolve("$name.json").also { Files.writeString(it, QuerySnapshotCodec.render(snapshot)) }

    private fun selectionMap(json: String): String = json.substringAfter("\"selectedByRevision\":").substringBefore("}")
    private fun run(vararg args: String): Triple<Int, String, String> {
        val output = ByteArrayOutputStream()
        val error = ByteArrayOutputStream()
        val status = KartographCli.run(args, PrintStream(output), PrintStream(error))
        return Triple(status, output.toString(Charsets.UTF_8), error.toString(Charsets.UTF_8))
    }
}
