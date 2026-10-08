package dev.kartograph.export

import dev.kartograph.core.CodeGraph
import dev.kartograph.core.CompilerEvidenceSource
import dev.kartograph.core.CompilerSourceCoordinateBasis
import dev.kartograph.core.EdgeKind
import dev.kartograph.core.EdgeOrigin
import dev.kartograph.core.GraphEdge
import dev.kartograph.core.GraphNode
import dev.kartograph.core.LocatedCompilerReference
import dev.kartograph.core.NodeId
import dev.kartograph.core.NodeKind
import dev.kartograph.core.SourceLocation
import java.nio.file.Files
import java.nio.file.Path
import javax.tools.ToolProvider
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import kotlin.test.assertEquals

class CompilerCallEvidenceOldReaderTest {
    @Test
    fun `actual 019 distribution ignores additive evidence and preserves old graph facts`(@TempDir root: Path) {
        val oldHomeValue = System.getenv("KARTOGRAPH_019_HOME")
        assumeTrue(!oldHomeValue.isNullOrBlank(), "actual 0.19.0 distribution is required")
        val oldHome = Path.of(oldHomeValue).toRealPath()
        val executable = oldHome.resolve("bin/kartograph")
        assumeTrue(Files.isRegularFile(executable), "actual 0.19.0 CLI is unavailable")

        val caller = GraphNode(NodeId("method:demo/Caller#use()V"), "use", NodeKind.METHOD,
            location = SourceLocation("Caller.java", 1))
        val target = GraphNode(NodeId("method:demo/Target#hit()V"), "hit", NodeKind.METHOD,
            location = SourceLocation("Target.java", 1))
        val baseGraph = CodeGraph(listOf(caller, target), listOf(
            GraphEdge(caller.id, target.id, EdgeKind.CALL, origin = EdgeOrigin.BYTECODE, callSiteLines = listOf(7)),
        ))
        val graph = baseGraph.withCompilerCallPositions(listOf(
            LocatedCompilerReference(caller.id, target.id,
                CompilerEvidenceSource("src/main/java/demo/Caller.java", "a".repeat(64)),
                "javac-constants", "17.0.20+0", CompilerSourceCoordinateBasis.JAVAC_UTF16_CHAR_SEQUENCE,
                63, 66, 1, 64),
        ))
        for ((capture, capturedGraph) in listOf("positions" to graph,
            "empty" to baseGraph.withCompilerCallPositions(emptyList()))) {
            for (compact in listOf(false, true)) {
                val snapshot = QuerySnapshot(capturedGraph, emptyList(), listOf("fixture-limit"), toolVersion = "0.19.0")
                val file = root.resolve("snapshot-$capture-$compact.json")
                Files.writeString(file, QuerySnapshotCodec.render(snapshot, compact))
                val process = ProcessBuilder(
                    "/bin/bash", executable.toString(), "query", target.id.value,
                    "--graph-file", file.toString(), "--depth", "1", "--limit", "10",
                ).redirectErrorStream(true).start()
                val output = process.inputStream.readAllBytes().toString(Charsets.UTF_8)
                assertEquals(0, process.waitFor(), output)
                val document = McpJsonCodec.parse(output) as Map<*, *>
                assertEquals("found", document["status"])
                val result = document["result"] as Map<*, *>
                assertEquals(target.id.value, (result["subject"] as Map<*, *>)["usr"])
                val neighbor = (result["usedBy"] as List<*>).single() as Map<*, *>
                assertEquals(caller.id.value, neighbor["usr"])
                val references = neighbor["references"] as List<*>
                assertEquals(listOf("bytecode"), references.map { (it as Map<*, *>)["origin"] })
                assertEquals(7L, (((references.single() as Map<*, *>)["location"] as Map<*, *>)["line"]))
            }
        }
    }

    @Test
    fun `actual 019 nonempty uncaptured snapshots rerender byte identically`(@TempDir root: Path) {
        val oldHomeValue = System.getenv("KARTOGRAPH_019_HOME")
        assumeTrue(!oldHomeValue.isNullOrBlank(), "actual 0.19.0 distribution is required")
        val executable = Path.of(oldHomeValue).toRealPath().resolve("bin/kartograph")
        assumeTrue(Files.isRegularFile(executable), "actual 0.19.0 CLI is unavailable")
        val source = root.resolve("Caller.java")
        Files.writeString(source, "public class Caller { public void call() { Target.hit(); } } class Target { static void hit() {} }")
        val classes = Files.createDirectories(root.resolve("classes"))
        assertEquals(0, ToolProvider.getSystemJavaCompiler().run(
            null, null, null, "-g", "-d", classes.toString(), source.toString(),
        ))

        for (compact in listOf(false, true)) {
            val command = mutableListOf(
                "/bin/bash", executable.toString(), "snapshot", "--classes", classes.toString(), "--project", root.toString(),
            )
            if (compact) command += "--compact"
            val process = ProcessBuilder(command).start()
            val output = process.inputStream.readAllBytes().toString(Charsets.UTF_8)
            val error = process.errorStream.readAllBytes().toString(Charsets.UTF_8)
            assertEquals(0, process.waitFor(), error)
            val parsed = QuerySnapshotCodec.parse(output)
            assertEquals(false, parsed.graph.compilerCallPositionsCaptured)
            assertEquals(output, QuerySnapshotCodec.render(parsed, compact))
        }
    }
}
