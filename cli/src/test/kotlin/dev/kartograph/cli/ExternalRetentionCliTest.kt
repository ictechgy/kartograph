package dev.kartograph.cli

import java.io.ByteArrayOutputStream
import java.io.PrintStream
import java.nio.file.Path
import javax.tools.ToolProvider
import kotlin.io.path.createDirectories
import kotlin.io.path.writeText
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import org.junit.jupiter.api.io.TempDir

class ExternalRetentionCliTest {
    @Test
    fun `external caller suppresses the compiled owner finding and explains the original location`(@TempDir root: Path) {
        val arguments = fixture(root)
        assertContains(run(arguments).output, "unreachable\tclass:sample/Camera")
        val retained = run(arguments + listOf("--external-retentions", "retentions.json"))
        assertEquals(0, retained.status, retained.error)
        assertFalse(retained.output.contains("unreachable\tclass:sample/Camera"))
        assertContains(retained.output, "unreachable\tclass:sample/Unused")
        val explanation = run(arguments + listOf(
            "--external-retentions", "retentions.json", "--explain", "method:sample/Camera#handle()V",
        ))
        assertEquals(0, explanation.status, explanation.error)
        assertContains(explanation.output, "EXTERNAL_BRIDGE")
        assertContains(explanation.output, "dart:lib/camera.dart:7")
        assertContains(explanation.output, "channel camera")
        assertContains(explanation.output, "method capture")
        val privateMode = run(arguments + listOf("--include-private-members",
            "--external-retentions", "retentions.json", "--explain", "method:sample/Camera#handle()V"))
        assertEquals(0, privateMode.status, privateMode.error)
        assertContains(privateMode.output, "dart:lib/camera.dart:7")
        assertContains(privateMode.output, "EXTERNAL_BRIDGE")
        val owner = run(arguments + listOf(
            "--external-retentions", "retentions.json", "--explain", "class:sample/Camera",
        ))
        assertContains(owner.output, "EXTERNAL_BRIDGE")
        val sibling = run(arguments + listOf(
            "--external-retentions", "retentions.json", "--explain", "method:sample/Camera#unused()V",
        ))
        assertContains(sibling.output, "unreachable\tmethod:sample/Camera#unused()V")
    }

    @Test
    fun `unmatched and malformed external documents do not produce partial findings`(@TempDir root: Path) {
        val arguments = fixture(root)
        for (document in listOf(retentions.replace("#handle()V", "#absent()V"), "{}")) {
            root.resolve("retentions.json").writeText(document)
            val result = run(arguments + listOf("--external-retentions", "retentions.json"))
            assertEquals(2, result.status)
            assertEquals("", result.output)
            assertContains(result.error, "external")
        }
        assertEquals(64, run(arguments + "--external-retentions").status)
        assertEquals(2, run(arguments + listOf("--external-retentions", "absent.json")).status)
    }

    private fun fixture(root: Path): List<String> {
        val sources = root.resolve("src/sample").createDirectories()
        val classes = root.resolve("classes").createDirectories()
        val camera = sources.resolve("Camera.java")
        camera.writeText("package sample; public class Camera { public void handle() {} public void unused() {} }")
        val unused = sources.resolve("Unused.java")
        unused.writeText("package sample; public class Unused {}")
        assertEquals(0, ToolProvider.getSystemJavaCompiler().run(
            null, null, null, "-g", "-d", classes.toString(), camera.toString(), unused.toString(),
        ))
        root.resolve("manifest.xml").writeText("<manifest />")
        root.resolve("res").createDirectories()
        root.resolve("retentions.json").writeText(retentions)
        return listOf("--classes", classes.toString(), "--project", root.toString(), "--manifest", "manifest.xml",
            "--resources", "res", "--namespace", "sample")
    }

    private fun run(arguments: List<String>): Execution {
        val output = ByteArrayOutputStream()
        val error = ByteArrayOutputStream()
        val status = DeadCommand.run(arguments, PrintStream(output), PrintStream(error))
        return Execution(status, output.toString(Charsets.UTF_8), error.toString(Charsets.UTF_8))
    }

    private data class Execution(val status: Int, val output: String, val error: String)

    private val retentions = """{
        "format":"external-retentions","version":0,"producedBy":{"name":"isthmus","version":"test"},
        "generatedAt":"2026-09-19T00:00:00Z","retentions":[{
        "symbol":{"qualifiedName":"Camera.handle","usr":"method:sample/Camera#handle()V"},
        "reason":"bridge","evidence":{"channel":"camera","method":"capture",
        "caller":{"platform":"dart","path":"lib/camera.dart","line":7}}}]}
    """.trimIndent()
}
