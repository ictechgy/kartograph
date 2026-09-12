package dev.kartograph.cli

import java.io.ByteArrayOutputStream
import java.io.PrintStream
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.TimeUnit
import javax.tools.ToolProvider
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import org.junit.jupiter.api.io.TempDir

class RuntimeFieldCliTest {
    @Test
    fun `unchanged reflective field fixture executes and restores impact through target constructor`(@TempDir root: Path) {
        val fixture = listOf(Path.of("fixtures"), Path.of("../fixtures")).map {
            it.resolve("runtime-contracts/reflective_field/Entry.java")
        }.first(Files::exists)
        val source = root.resolve("Entry.java")
        Files.copy(fixture, source)
        val classes = Files.createDirectories(root.resolve("classes"))
        val errors = ByteArrayOutputStream()
        assertEquals(0, ToolProvider.getSystemJavaCompiler().run(null, null, errors, "-g", "-d", classes.toString(), source.toString()), errors.toString())
        val output = root.resolve("execution.txt")
        val process = ProcessBuilder(Path.of(System.getProperty("java.home"), "bin", "java").toString(),
            "-cp", classes.toString(), "probe.Entry").redirectErrorStream(true).redirectOutput(output.toFile()).start()
        try {
            assertTrue(process.waitFor(30, TimeUnit.SECONDS), "fixture execution timed out")
            assertEquals(0, process.exitValue())
            assertEquals("USED", Files.readString(output).trim())
        } finally { if (process.isAlive) process.destroyForcibly() }
        val snapshot = root.resolve("graph.json")
        Files.writeString(snapshot, run("snapshot", "--classes", classes.toString(), "--project", root.toString()))
        val used = run("impact", "class:probe/Entry${'$'}Used", "--graph-file", snapshot.toString())
        assertContains(used, "method:probe/Entry#main([Ljava/lang/String;)V")
        assertContains(used, "method:probe/Entry${'$'}Target#<init>()V")
        assertContains(used, "runtimeModel")
        assertContains(used, "reflective-construction:")
        val unused = run("impact", "class:probe/Entry${'$'}Unused", "--graph-file", snapshot.toString())
        assertFalse(unused.contains("method:probe/Entry#main([Ljava/lang/String;)V"))
    }

    private fun run(vararg arguments: String): String {
        val output = ByteArrayOutputStream()
        val errors = ByteArrayOutputStream()
        assertEquals(0, KartographCli.run(arrayOf(*arguments), PrintStream(output), PrintStream(errors)), errors.toString())
        return output.toString(Charsets.UTF_8)
    }
}
