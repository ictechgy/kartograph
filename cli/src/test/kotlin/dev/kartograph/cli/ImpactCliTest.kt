package dev.kartograph.cli

import dev.kartograph.core.*
import dev.kartograph.export.QuerySnapshot
import dev.kartograph.export.QuerySnapshotCodec
import java.io.ByteArrayOutputStream
import java.io.PrintStream
import java.nio.file.Files
import java.nio.file.Path
import javax.tools.ToolProvider
import kotlin.test.*
import org.junit.jupiter.api.io.TempDir

class ImpactCliTest {
    @Test fun `real compiler snapshots retain a deleted callee and untouched caller`(@TempDir root: Path) {
        val source = Files.createDirectories(root.resolve("src/p"))
        source.resolve("Entry.java").toFile().writeText("package p; public class Entry { public static void main(String[] a){Target.run();} }")
        source.resolve("Target.java").toFile().writeText("package p; public class Target {public static void run(){}}")
        source.resolve("Unused.java").toFile().writeText("package p; public class Unused {}")
        val classes = Files.createDirectories(root.resolve("classes"))
        assertEquals(0, ToolProvider.getSystemJavaCompiler().run(null,null,null,"-g","-d",classes.toString(),
            *source.toFile().listFiles()!!.map { it.path }.toTypedArray()))
        val capture = run("snapshot","--classes",classes.toString(),"--project",root.toString(),"--include-paths")
        assertEquals(0,capture.first,capture.third)
        val before = root.resolve("before.json").also { Files.writeString(it,capture.second) }
        val current = root.resolve("current.json").also { Files.writeString(it, QuerySnapshotCodec.render(QuerySnapshot(
            CodeGraph(emptyList(),emptyList()), emptyList(),emptyList()))) }
        val result = run("impact","--file","src/p/Target.java","--graph-file",current.toString(),"--base-graph",before.toString())
        assertEquals(0,result.first,result.third)
        assertContains(result.second,"method:p/Entry#main([Ljava/lang/String;)V")
        assertContains(result.second,"\"revision\": \"base\"")
        assertContains(result.second,"\"origin\": \"bytecode\"")
        assertFalse(result.second.contains("class:p/Unused"))
        assertFalse(result.second.contains(root.toString()))
    }

    @Test fun `ambiguous missing truncated and stale inputs cannot approve a change`(@TempDir root: Path) {
        val nodes=listOf(GraphNode(NodeId("class:a/A"),"A",NodeKind.CLASS),GraphNode(NodeId("class:b/A"),"A",NodeKind.CLASS))
        val snapshot=root.resolve("graph.json").also { Files.writeString(it,QuerySnapshotCodec.render(QuerySnapshot(
            CodeGraph(nodes,emptyList()),emptyList(),listOf("index-staleness: 1 source changed")))) }
        val ambiguous=run("impact","A","--graph-file",snapshot.toString())
        assertEquals(64,ambiguous.first)
        assertContains(ambiguous.second,"ambiguous")
        assertContains(ambiguous.second,"index-staleness")
        assertContains(ambiguous.second,"potential-impact:")
        assertEquals(64,run("impact","Missing","--graph-file",snapshot.toString()).first)
        assertEquals(64,run("impact","A","--graph-file",snapshot.toString(),"--classes","invalid").first)
        assertEquals(64,run("impact","--file","../secret","--graph-file",snapshot.toString()).first)
        assertEquals(64,run("impact","--graph-file",snapshot.toString()).first)
        assertEquals(2,run("impact","A","--graph-file",root.resolve("absent").toString()).first)
        val files=root.resolve("files.json").also { Files.writeString(it,"[]") }
        val empty=run("impact","--files-from",files.toString(),"--graph-file",snapshot.toString())
        assertEquals(0,empty.first)
        assertContains(empty.second,"\"status\": \"noChanges\"")
    }

    @Test fun `different analyzer versions cannot silently become code impact`(@TempDir root: Path) {
        val graph=CodeGraph(listOf(GraphNode(NodeId("class:A"),"A",NodeKind.CLASS)),emptyList())
        val current=root.resolve("current.json").also { Files.writeString(it,QuerySnapshotCodec.render(QuerySnapshot(graph,emptyList(),emptyList()))) }
        val base=root.resolve("base.json").also { Files.writeString(it,QuerySnapshotCodec.render(QuerySnapshot(graph,emptyList(),emptyList(),toolVersion="other"))) }
        assertEquals(2,run("impact","A","--graph-file",current.toString(),"--base-graph",base.toString()).first)
    }

    @Test fun `snapshot context and changed file schemas reject incorrect inputs`(@TempDir root: Path) {
        val graph=CodeGraph(listOf(GraphNode(NodeId("class:A"),"A",NodeKind.CLASS)),emptyList())
        val revision="1".repeat(40)
        val current=root.resolve("current.json").also { Files.writeString(it,QuerySnapshotCodec.render(QuerySnapshot(
            graph,emptyList(),emptyList(),revision=revision,scope="app:debug"))) }
        val base=root.resolve("base.json").also { Files.writeString(it,QuerySnapshotCodec.render(QuerySnapshot(
            graph,emptyList(),emptyList(),revision=revision,scope="app:release"))) }
        assertEquals(2,run("impact","A","--graph-file",current.toString(),"--base-graph",base.toString()).first)
        assertEquals(0,run("impact","A","--graph-file",current.toString(),"--revision",revision).first)
        assertEquals(2,run("impact","A","--graph-file",current.toString(),"--revision","2".repeat(40)).first)
        val list=root.resolve("files.json").also { Files.writeString(it,"[3]") }
        assertEquals(2,run("impact","--files-from",list.toString(),"--graph-file",current.toString()).first)
        assertEquals(64,run("impact","A","--graph-file",current.toString(),"--limit","0").first)
        assertEquals(64,run("impact","A","--graph-file",current.toString(),"--depth","1001").first)
        assertEquals(64,run("impact","A","--graph-file",current.toString(),"--graph-file",base.toString()).first)
        assertEquals(64,run("impact","A","--graph-file",current.toString(),"--revision","invalid").first)
        assertEquals(64,run("snapshot","--classes","absent","--project",root.toString(),"--revision","invalid").first)
        assertEquals(64,run("snapshot","--classes","absent","--project",root.toString(),"--scope","../private").first)
    }

    @Test fun `actual interface bytecode exposes implementation impact`(@TempDir root: Path) {
        val source=root.resolve("Api.java")
        Files.writeString(source,"interface Api {void work();} class Impl implements Api {public void work(){}}")
        val classes=Files.createDirectories(root.resolve("classes"))
        assertEquals(0,ToolProvider.getSystemJavaCompiler().run(null,null,null,"-g","-d",classes.toString(),source.toString()))
        val capture=run("snapshot","--classes",classes.toString(),"--project",root.toString())
        assertEquals(0,capture.first,capture.third)
        val snapshot=root.resolve("graph.json").also { Files.writeString(it,capture.second) }
        val result=run("impact","method:Api#work()V","--graph-file",snapshot.toString())
        assertEquals(0,result.first,result.third)
        assertContains(result.second,"method:Impl#work()V")
        assertContains(result.second,"overrideContract")
    }

    @Test fun `navigation filters are separate from changed file selection`(@TempDir root: Path) {
        val target = GraphNode(NodeId("class:p/Target"), "Target", NodeKind.CLASS, moduleName = "app",
            location = SourceLocation("src/main/kotlin/p/Target.kt"))
        val caller = GraphNode(NodeId("class:p/Caller"), "Caller", NodeKind.CLASS, moduleName = "app",
            location = SourceLocation("src/test/kotlin/p/Caller.kt"))
        val other = GraphNode(NodeId("class:p/Other"), "Other", NodeKind.CLASS, moduleName = "other",
            location = SourceLocation("src/main/kotlin/p/Other.kt"))
        val snapshot = root.resolve("graph.json").also {
            Files.writeString(it, QuerySnapshotCodec.render(QuerySnapshot(
                CodeGraph(listOf(target, caller, other), listOf(GraphEdge(caller.id, target.id, EdgeKind.CALL),
                    GraphEdge(other.id, target.id, EdgeKind.CALL))), emptyList(), emptyList())))
        }

        val result = run("impact", "--file", "src/main/kotlin/p/Target.kt", "--affected-file", "src/test/kotlin/p/Caller.kt",
            "--graph-file", snapshot.toString(), "--all", "--sort", "file")
        assertEquals(0, result.first, result.third)
        assertContains(result.second, "\"observedAffected\": 2")
        assertContains(result.second, "\"usr\": \"class:p/Caller\"")
        assertFalse(result.second.contains("class:p/Other"))
        assertContains(result.second, "\"affectedFiles\": [\"src/test/kotlin/p/Caller.kt\"]")
        assertContains(result.second, "\"value\": \"test\"")
    }

    @Test fun `all export retains candidates whose paths exceed the path budget`(@TempDir root: Path) {
        val target = GraphNode(NodeId("class:p/Target"), "Target", NodeKind.CLASS,
            location = SourceLocation("src/main/kotlin/p/Target.kt"))
        val middle = GraphNode(NodeId("class:p/Middle"), "Middle", NodeKind.CLASS,
            location = SourceLocation("src/main/kotlin/p/Middle.kt"))
        val caller = GraphNode(NodeId("class:p/Caller"), "Caller", NodeKind.CLASS,
            location = SourceLocation("src/main/kotlin/p/Caller.kt"))
        val snapshot = root.resolve("graph.json").also {
            Files.writeString(it, QuerySnapshotCodec.render(QuerySnapshot(
                CodeGraph(listOf(target, middle, caller), listOf(GraphEdge(middle.id, target.id, EdgeKind.CALL),
                    GraphEdge(caller.id, middle.id, EdgeKind.CALL))), emptyList(), emptyList())))
        }

        val result = run("impact", target.id.value, "--graph-file", snapshot.toString(), "--all", "--path-limit", "1")
        assertEquals(0, result.first, result.third)
        assertContains(result.second, "\"observedAffected\": 2")
        assertContains(result.second, "class:p/Middle")
        assertContains(result.second, "class:p/Caller")
        assertContains(result.second, "\"pathOmissions\"")
        assertContains(result.second, "\"pathStatus\": \"unavailable\"")
        assertContains(result.second, "\"pathOmissions\": 1")
    }

    private fun run(vararg args: String): Triple<Int,String,String> {
        val output=ByteArrayOutputStream(); val error=ByteArrayOutputStream()
        val status=KartographCli.run(args,PrintStream(output),PrintStream(error))
        return Triple(status,output.toString(Charsets.UTF_8),error.toString(Charsets.UTF_8))
    }
}
