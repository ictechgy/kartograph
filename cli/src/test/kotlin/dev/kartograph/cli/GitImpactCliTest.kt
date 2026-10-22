package dev.kartograph.cli

import dev.kartograph.core.*
import dev.kartograph.export.McpJsonCodec
import dev.kartograph.export.QuerySnapshot
import dev.kartograph.export.QuerySnapshotCodec
import dev.kartograph.export.BuildWitnessCodec
import dev.kartograph.index.ContentFingerprint
import java.io.ByteArrayOutputStream
import java.io.PrintStream
import java.nio.file.Files
import java.nio.file.Path
import javax.tools.ToolProvider
import kotlin.test.*
import org.junit.jupiter.api.io.TempDir

class GitImpactCliTest {
    @Test fun `witnessed inputs match while working tree null revision and MCP scope remain explicit`(@TempDir root: Path) {
        val project = repository(root)
        Files.writeString(project.resolve(".gitignore"), "classes/\n.capture/\n")
        val source = Files.createDirectories(project.resolve("src"))
        Files.writeString(source.resolve("Target.java"), "class Target { static int value() { return 1; } }")
        Files.writeString(project.resolve("compile.args"), "-g -d classes src/Target.java\n")
        git(project, "add", "."); git(project, "commit", "-qm", "before")
        val head = git(project, "rev-parse", "HEAD").trim()
        val compiler = Path.of(System.getProperty("java.home"), "bin", if (System.getProperty("os.name").startsWith("Windows")) "javac.exe" else "javac")
        val compilerInput = ContentFingerprint.capture(project, compiler, "compiler", "javac")
        // 성공한 실제 javac 호출 전 입력과 호출 후 출력으로 이 fixture의 빌드 기록을 만든다.
        val inputs = listOf(ContentFingerprint.capture(project, source, "sources", "sources"),
            ContentFingerprint.capture(project, project.resolve("compile.args"), "buildConfig", "build-config"), compilerInput,
            InputFingerprint("options", "compile-options", ContentFingerprint.values(listOf("-g"))))
        capture(project, root.resolve("unwitnessed.json"), head)
        val output = ContentFingerprint.capture(project, project.resolve("classes"), "classes", "classes")
        val witness = Files.createDirectories(project.resolve(".capture")).resolve("witness.json")
        Files.writeString(witness, BuildWitnessCodec.render(BuildWitness("app:main", "javac", ":compileJava", inputs, listOf(output))))
        val captured = run("snapshot", "--classes", project.resolve("classes").toString(), "--project", project.toString(),
            "--source-root", source.toString(), "--build-input", "compile.args", "--build-witness", witness.toString(),
            "--include-paths", "--scope", "app:main", "--revision", head)
        assertEquals(0, captured.first, captured.third)
        val old = Files.writeString(root.resolve("base.json"), captured.second)
        val current = Files.writeString(root.resolve("current.json"), QuerySnapshotCodec.render(QuerySnapshotCodec.parse(captured.second).copy(revision = null)))
        val options = arrayOf("--graph-file", current.toString(), "--base-graph", old.toString(), "--project", project.toString(),
            "--base-project", project.toString(), "--input", "${compilerInput.path}=$compiler", "--base-input", "${compilerInput.path}=$compiler")
        val result = run("impact", "--git-base", head, "--working-tree", *options)
        assertEquals(0, result.first, result.third)
        val freshness = (McpJsonCodec.parse(result.second) as Map<*, *>)["freshness"] as Map<*, *>
        assertEquals("matched", (freshness["base"] as Map<*, *>)["status"])
        assertEquals("matched", (freshness["current"] as Map<*, *>)["status"])
        assertEquals(2, run("impact", "--git-base", head, *options).first)
        McpTestClient { input, stdout, error -> KartographCli.runWithInput(arrayOf("mcp", *options, "--scope", "different:main"), stdout, error, input) }.use { client ->
            client.initialize()
            val response = client.call("change_impact", mapOf("base" to head, "workingTree" to true))
            assertEquals(false, response["isError"], response.toString())
            val document = (response["structuredContent"] as Map<*, *>)["document"] as Map<*, *>
            val checked = document["freshness"] as Map<*, *>
            for (side in listOf("base", "current")) {
                assertEquals("stale", (checked[side] as Map<*, *>)["status"])
                assertContains((checked[side] as Map<*, *>)["reasons"] as List<*>, "snapshot-scope-mismatch")
            }
        }
    }

    @Test fun `git rename selects both captured paths and preserves historical caller`(@TempDir root: Path) {
        val project = repository(root)
        val source = Files.createDirectories(project.resolve("src"))
        Files.writeString(source.resolve("Old.java"), "class Target { static void run() {} }")
        Files.writeString(source.resolve("Caller.java"), "class Caller { void call() { Target.run(); } }")
        git(project, "add", "src"); git(project, "commit", "-qm", "before")
        val before = git(project, "rev-parse", "HEAD").trim()
        val old = capture(project, root.resolve("base.json"), before)
        Files.move(source.resolve("Old.java"), source.resolve("New.java"))
        git(project, "add", "-A", "src"); git(project, "commit", "-qm", "rename")
        val head = git(project, "rev-parse", "HEAD").trim()
        val current = capture(project, root.resolve("current.json"), head)
        val result = run("impact", "--git-base", before, "--git-head", head, "--project", project.toString(),
            "--graph-file", current.toString(), "--base-graph", old.toString())
        assertEquals(0, result.first, result.third)
        val document = McpJsonCodec.parse(result.second) as Map<*, *>
        assertEquals("kartograph-change-impact", document["format"])
        val change = document["change"] as Map<*, *>
        assertEquals(listOf("src/New.java", "src/Old.java"), change["files"])
        assertEquals(before, change["baseRevision"])
        assertEquals(head, change["headRevision"])
        assertContains(result.second, "method:Caller#call()V")
        assertContains(result.second, "base-checkout-not-configured")
        assertContains(result.second, "missing-build-witness")
        assertFalse(result.second.contains(project.toString()))
    }

    @Test fun `working tree edits cannot inherit freshness from HEAD label`(@TempDir root: Path) {
        val project = repository(root)
        val source = Files.createDirectories(project.resolve("src"))
        Files.writeString(source.resolve("Target.java"), "class Target { static int value() { return 1; } }")
        git(project, "add", "src"); git(project, "commit", "-qm", "before")
        val head = git(project, "rev-parse", "HEAD").trim()
        val snapshot = capture(project, root.resolve("graph.json"), head)
        Files.writeString(source.resolve("Target.java"), "class Target { static int value() { return 2; } }")
        Files.writeString(source.resolve("New.java"), "class New {}")
        val result = run("impact", "--git-base", head, "--working-tree", "--project", project.toString(),
            "--graph-file", snapshot.toString(), "--base-graph", snapshot.toString())
        assertEquals(64, result.first, result.third) // 아직 빌드하지 않은 New.java는 unresolved다.
        assertContains(result.second, "workingTree")
        assertContains(result.second, "src/New.java")
        assertContains(result.second, "changed-source")
        val document = McpJsonCodec.parse(result.second) as Map<*, *>
        assertEquals("stale", (((document["freshness"] as Map<*, *>)["current"]) as Map<*, *>)["status"])
        assertEquals(64, run("impact", "--git-base", head, "--working-tree", "--git-head", head,
            "--project", project.toString(), "--graph-file", snapshot.toString(), "--base-graph", snapshot.toString()).first)
    }

    @Test fun `MCP change impact preserves CLI context and rechecks source bytes on every call`(@TempDir root: Path) {
        val project = repository(root)
        val source = Files.createDirectories(project.resolve("src"))
        Files.writeString(source.resolve("Target.java"), "class Target { static int value() { return 1; } }")
        git(project, "add", "src"); git(project, "commit", "-qm", "before")
        val head = git(project, "rev-parse", "HEAD").trim()
        val snapshot = capture(project, root.resolve("graph.json"), head)
        val options = arrayOf("--graph-file", snapshot.toString(), "--base-graph", snapshot.toString(),
            "--project", project.toString(), "--base-project", project.toString())
        val cli = run("impact", "--git-base", head, "--working-tree", *options,
            "--depth", "2", "--limit", "5", "--path-limit", "100", "--summary-limit", "5")
        assertEquals(0, cli.first, cli.third)
        McpTestClient { input, output, error -> KartographCli.runWithInput(arrayOf("mcp", *options), output, error, input) }.use { client ->
            client.initialize()
            fun call(): Map<*, *> {
                val response = client.call("change_impact", mapOf("base" to head, "workingTree" to true))
                assertEquals(false, response["isError"], response.toString())
                return (response["structuredContent"] as Map<*, *>)["document"] as Map<*, *>
            }
            val first = call()
            val expected = McpJsonCodec.parse(cli.second) as Map<*, *>
            assertEquals(expected["change"], first["change"])
            assertEquals(expected["freshness"], first["freshness"])
            assertEquals(expected["graphImpact"], first["graphImpact"])
            Files.writeString(source.resolve("Target.java"), "class Target { static int value() { return 9; } }")
            val fresh = call()["freshness"] as Map<*, *>
            assertEquals("stale", (fresh["current"] as Map<*, *>)["status"])
            assertEquals("stale", (fresh["base"] as Map<*, *>)["status"])
            assertEquals(true, client.call("change_impact", mapOf("base" to head, "workingTree" to true, "head" to head))["isError"])
        }
    }

    @Test fun `deleted file maps to base without inventing current edges`(@TempDir root: Path) {
        val project = repository(root)
        val source = Files.createDirectories(project.resolve("src"))
        Files.writeString(source.resolve("Target.java"), "class Target {}")
        git(project, "add", "src"); git(project, "commit", "-qm", "before")
        val base = git(project, "rev-parse", "HEAD").trim()
        Files.delete(source.resolve("Target.java"))
        git(project, "add", "-u"); git(project, "commit", "-qm", "delete")
        val head = git(project, "rev-parse", "HEAD").trim()
        val target = GraphNode(NodeId("class:Target"), "Target", NodeKind.CLASS, location = SourceLocation("src/Target.java"))
        val caller = GraphNode(NodeId("class:Caller"), "Caller", NodeKind.CLASS)
        val old = root.resolve("base.json")
        Files.writeString(old, QuerySnapshotCodec.render(QuerySnapshot(CodeGraph(listOf(target, caller),
            listOf(GraphEdge(caller.id, target.id, EdgeKind.CALL))), emptyList(), emptyList(), revision = base, scope = "app:main")))
        val current = root.resolve("current.json")
        Files.writeString(current, QuerySnapshotCodec.render(QuerySnapshot(CodeGraph(emptyList(), emptyList()),
            emptyList(), emptyList(), revision = head, scope = "app:main")))
        val result = run("impact", "--git-base", base, "--project", project.toString(),
            "--graph-file", current.toString(), "--base-graph", old.toString())
        assertEquals(0, result.first, result.third)
        assertContains(result.second, "class:Caller")
        assertContains(result.second, "\"revision\": \"base\"")
        assertContains(result.second, "src/Target.java")
        assertEquals(2, run("impact", "--git-base", head, "--project", project.toString(),
            "--graph-file", current.toString(), "--base-graph", old.toString()).first)
    }

    private fun repository(root: Path): Path = Files.createDirectories(root.resolve("project")).also {
        git(it, "init", "-q"); git(it, "config", "user.email", "fixture@example.invalid")
        git(it, "config", "user.name", "Fixture")
        Files.writeString(it.resolve(".gitignore"), "classes/\n")
        git(it, "add", ".gitignore")
    }

    private fun capture(project: Path, output: Path, revision: String): Path {
        val classes = Files.createDirectories(project.resolve("classes"))
        val sources = Files.list(project.resolve("src")).use { paths -> paths.map(Path::toString).toList() }
        assertEquals(0, ToolProvider.getSystemJavaCompiler().run(null, null, null, "-g", "-d", classes.toString(), *sources.toTypedArray()))
        val result = run("snapshot", "--classes", classes.toString(), "--project", project.toString(),
            "--source-root", project.resolve("src").toString(), "--include-paths", "--scope", "app:main", "--revision", revision)
        assertEquals(0, result.first, result.third)
        Files.writeString(output, result.second)
        return output
    }

    private fun git(project: Path, vararg arguments: String): String {
        val process = ProcessBuilder(listOf("git", "-C", project.toString()) + arguments).redirectErrorStream(true).start()
        val output = process.inputStream.bufferedReader().readText()
        assertEquals(0, process.waitFor(), output)
        return output
    }

    private fun run(vararg arguments: String): Triple<Int, String, String> {
        val output = ByteArrayOutputStream(); val error = ByteArrayOutputStream()
        val status = KartographCli.run(arguments, PrintStream(output), PrintStream(error))
        return Triple(status, output.toString(Charsets.UTF_8), error.toString(Charsets.UTF_8))
    }
}
