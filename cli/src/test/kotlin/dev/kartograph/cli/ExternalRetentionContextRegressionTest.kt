package dev.kartograph.cli

import dev.kartograph.core.CodeGraph
import dev.kartograph.export.ExternalRetentionCodec
import dev.kartograph.export.QuerySnapshot
import java.io.ByteArrayOutputStream
import java.io.PrintStream
import java.nio.file.Files
import java.nio.file.Path
import javax.tools.ToolProvider
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import org.junit.jupiter.api.io.TempDir

/** 새 API 없이 현재 main에서도 컴파일되는 문서 문맥 유실의 수정 전 재현이다. */
class ExternalRetentionContextRegressionTest {
    @Test
    fun `known malformed provenance is not accepted as a legacy document`() {
        val baseline = resource("kartograph-baseline-retentions.json")
        val withoutEnvelope = baseline.substringBefore("  \"provenance\":") +
            "  \"provenance\":null,\n  \"retentions\":[],\"version\":0}"
        assertFailsWith<IllegalArgumentException> { ExternalRetentionCodec.parse(withoutEnvelope) }
    }

    @Test
    fun `current producer stale matched context is visible in dead`(@TempDir root: Path) {
        assertDeadContext(root, "kartograph-stale-retentions.json")
    }

    @Test
    fun `current producer zero retention context is visible in dead`(@TempDir root: Path) {
        assertDeadContext(root, "kartograph-zero-retentions.json")
    }

    @Test
    fun `aggregation preserves the original external bridge evidence location`() {
        val evidence = ExternalRetentionCodec.parse(resource("kartograph-stale-retentions.json"))
        val snapshot = QuerySnapshot(CodeGraph(emptyList(), emptyList()), evidence, emptyList())
        val member = AggregateMember(Path.of("/fixture/feature"), "feature", "member-1", snapshot, emptyMap())
        assertEquals(evidence, SnapshotAggregation.retention(listOf(member)))
    }

    private fun assertDeadContext(root: Path, file: String) {
        val source = root.resolve("src/sample/Camera.java")
        Files.createDirectories(source.parent)
        Files.writeString(source, "package sample; public class Camera { public void handle() {} public void unused() {} }")
        val classes = Files.createDirectories(root.resolve("classes"))
        assertEquals(0, ToolProvider.getSystemJavaCompiler().run(null, null, null,
            "-g", "-d", classes.toString(), source.toString()))
        Files.writeString(root.resolve("manifest.xml"), "<manifest />")
        Files.createDirectories(root.resolve("res"))
        Files.writeString(root.resolve("retentions.json"), resource(file))
        val output = ByteArrayOutputStream()
        val error = ByteArrayOutputStream()
        val status = DeadCommand.run(listOf("--classes", classes.toString(), "--project", root.toString(),
            "--manifest", "manifest.xml", "--resources", "res", "--namespace", "sample",
            "--external-retentions", "retentions.json"), PrintStream(output), PrintStream(error))
        assertEquals(0, status, error.toString(Charsets.UTF_8))
        assertContains(output.toString(Charsets.UTF_8), "index-freshness: source changed after index capture")
    }

    private fun resource(name: String): String = checkNotNull(javaClass.getResourceAsStream(
        "/external-retention-current-producer/$name")).bufferedReader(Charsets.UTF_8).use { it.readText() }
}
