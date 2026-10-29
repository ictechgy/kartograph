package dev.kartograph.cli

import java.io.ByteArrayOutputStream
import java.io.PrintStream
import java.nio.file.Path
import javax.tools.ToolProvider
import kotlin.io.path.*
import kotlin.test.*
import org.junit.jupiter.api.io.TempDir

class FeedbackKeepCliTest {
    @Test fun `unknown keep inputs are visible in empty JSON and cannot pass strict`(@TempDir root: Path) {
        val classes = root.resolve("classes").createDirectories()
        val source = root.resolve("Example.java").also { it.writeText("public class Example { public void work() {} }") }
        assertEquals(0, ToolProvider.getSystemJavaCompiler().run(null, null, null, "-g", "-d", classes.toString(), source.toString()))
        root.resolve("AndroidManifest.xml").writeText("<manifest package=\"example\"/>")
        root.resolve("res").createDirectories()
        val rules = root.resolve("keep.pro").also { it.writeText("-unsupported\n-keep,includecode class Example\n") }
        val args = listOf("dead", "--classes", classes.toString(), "--project", root.toString(), "--manifest", "AndroidManifest.xml",
            "--resources", "res", "--namespace", "example", "--keep-rules", rules.toString(), "--report-format", "json")
        val strict = run(args)
        assertEquals(2, strict.first)
        assertContains(strict.third, "keep.pro:1")
        assertContains(strict.third, "keep.pro:2")
        val lenient = run(args + listOf("--keep-rule-mode", "lenient"))
        assertEquals(0, lenient.first)
        assertContains(lenient.second, "unknown-keep-rules")
        assertContains(lenient.second, "\"diagnostics\": []")
        assertEquals(2, run(args + listOf("--keep-rule-mode", "lenient", "--strict")).first)
        val explainArgs = args.dropLast(2) + listOf("--keep-rule-mode", "lenient", "--explain", "class:Example")
        val explained = run(explainArgs)
        assertEquals(0, explained.first, explained.third)
        assertContains(explained.second, "unknown\tclass:Example\tUNKNOWN_KEEP_RULE")
        assertContains(explained.second, "unknown-keep-rules")
        assertEquals(64, run(explainArgs + "--strict").first)
        rules.writeText("-dontshrink\n")
        val why = run(listOf("why", "method:Example#work()V") + args.drop(1).filterIndexed { i, _ -> i < args.drop(1).size - 2 })
        assertEquals(0, why.first, why.third)
        assertContains(why.second, "SHRINKING_DISABLED")
    }

    private fun run(args: List<String>): Triple<Int, String, String> {
        val out = ByteArrayOutputStream(); val err = ByteArrayOutputStream()
        val status = KartographCli.run(args.toTypedArray(), PrintStream(out), PrintStream(err))
        return Triple(status, out.toString(Charsets.UTF_8), err.toString(Charsets.UTF_8))
    }
}
