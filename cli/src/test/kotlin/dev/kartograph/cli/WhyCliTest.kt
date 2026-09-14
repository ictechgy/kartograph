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

class WhyCliTest {
    @Test
    fun `why prints manifest retention evidence for a retained class`(@TempDir projectRoot: Path) {
        val manifest = """
            <manifest xmlns:android="http://schemas.android.com/apk/res/android">
              <application>
                <activity android:name="dev.kartograph.cli.KartographCli" />
              </application>
            </manifest>
        """.trimIndent()

        val execution = execute(*whyArguments(projectRoot, manifest, "KartographCli"))

        assertEquals(ExitStatus.SUCCESS.code, execution.status)
        assertContains(execution.output, "subject\tclass:dev/kartograph/cli/KartographCli")
        assertContains(execution.output, "state\tretained")
        assertContains(execution.output, "reason\tMANIFEST_COMPONENT\tAndroidManifest.xml:3")
        assertContains(execution.output, "limitation\tREFLECTION_STRINGS")
        // 보존된 선언에는 신뢰도 등급 대신 보존 근거가 답이다.
        assertFalse(execution.output.contains("confidence\t"))
    }

    @Test
    fun `why reports an unreachable class with confidence and limitations`(@TempDir projectRoot: Path) {
        val execution = execute(*whyArguments(projectRoot, "<manifest />", "KartographCli"))

        assertEquals(ExitStatus.SUCCESS.code, execution.status)
        assertContains(execution.output, "state\tunreachable")
        assertContains(execution.output, "usedBy\t")
        assertContains(execution.output, "confidence\t")
        assertContains(execution.output, "limitation\tDYNAMIC_REGISTRATION")
        assertFalse(execution.output.contains("deletable"))
    }

    @Test
    fun `why prints the representative path and caller of a reachable class`(@TempDir projectRoot: Path) {
        val extraRoot = compileReachablePair(projectRoot)
        val manifest = """
            <manifest xmlns:android="http://schemas.android.com/apk/res/android">
              <application>
                <activity android:name="dev.kartograph.cli.Entry" />
              </application>
            </manifest>
        """.trimIndent()

        val execution = execute(*whyArguments(projectRoot, manifest, "Helper", "--classes", extraRoot.toString()))

        assertEquals(ExitStatus.SUCCESS.code, execution.status)
        assertContains(execution.output, "state\treachable")
        assertContains(execution.output, "path\tdev.kartograph.cli.Entry.run -> dev.kartograph.cli.Helper")
        assertContains(execution.output, "caller\tdev.kartograph.cli.Entry.run\treference")
    }

    @Test
    fun `why reports a missing symbol as a usage error`(@TempDir projectRoot: Path) {
        val execution = execute(*whyArguments(projectRoot, "<manifest />", "ThereIsNoSuchSymbol"))

        assertEquals(ExitStatus.USAGE.code, execution.status)
        assertContains(execution.output, "status\tnotFound")
        assertContains(execution.output, "requested\tThereIsNoSuchSymbol")
    }

    @Test
    fun `why reports an ambiguous simple name with qualified candidates`(@TempDir projectRoot: Path) {
        val extraRoot = compileSharedPair(projectRoot)

        val execution = execute(*whyArguments(projectRoot, "<manifest />", "Shared", "--classes", extraRoot.toString()))

        assertEquals(ExitStatus.USAGE.code, execution.status)
        assertContains(execution.output, "status\tambiguous")
        assertContains(execution.output, "candidate\ta.Shared\tclass:a/Shared")
        assertContains(execution.output, "candidate\tb.Shared\tclass:b/Shared")
    }

    @Test
    fun `why marks a production class reached only by tests like dead does`(@TempDir projectRoot: Path) {
        val compiler = requireNotNull(ToolProvider.getSystemJavaCompiler())
        val production = projectRoot.resolve("testtarget-classes").createDirectories()
        val productionSource = projectRoot.resolve("testtarget-sources/dev/kartograph/cli/TestOnlyTarget.java")
        productionSource.parent.createDirectories()
        productionSource.writeText("package dev.kartograph.cli; public class TestOnlyTarget { public void run() {} }")
        check(compiler.run(null, null, null, "-d", production.toString(), productionSource.toString()) == 0)
        val test = projectRoot.resolve("testusage-classes").createDirectories()
        val testSource = projectRoot.resolve("testusage-sources/dev/kartograph/cli/TestOnlyUsage.java")
        testSource.parent.createDirectories()
        testSource.writeText("package dev.kartograph.cli; public class TestOnlyUsage { void use() { new TestOnlyTarget(); } }")
        check(
            compiler.run(
                null, null, null, "-cp", production.toString(),
                "-d", test.toString(), testSource.toString(),
            ) == 0,
        )

        val execution = execute(
            *whyArguments(
                projectRoot,
                "<manifest />",
                "TestOnlyTarget",
                "--classes",
                production.toString(),
                "--test-classes",
                test.toString(),
            ),
        )

        assertEquals(ExitStatus.SUCCESS.code, execution.status)
        assertContains(execution.output, "state\tunreachable")
        assertContains(execution.output, "used-by-tests\tclass:dev/kartograph/cli/TestOnlyTarget")
    }

    @Test
    fun `why help prints usage and succeeds`() {
        val execution = execute("why", "--help")

        assertEquals(ExitStatus.SUCCESS.code, execution.status)
        assertContains(execution.output, "why <symbol>")
        assertContains(execution.output, "never approves a deletion")
    }

    @Test
    fun `why requires a symbol`() {
        val execution = execute("why")

        assertEquals(ExitStatus.USAGE.code, execution.status)
        assertContains(execution.error, "why requires a symbol")
    }

    @Test
    fun `why rejects options belonging to another command`(@TempDir projectRoot: Path) {
        val execution = execute(*whyArguments(projectRoot, "<manifest />", "KartographCli", "--baseline", "ignored"))

        assertEquals(ExitStatus.USAGE.code, execution.status)
        assertContains(execution.error, "unknown why option: --baseline")
    }

    private fun execute(vararg arguments: String): Execution {
        val output = ByteArrayOutputStream()
        val error = ByteArrayOutputStream()
        val status = KartographCli.run(
            arguments = arguments,
            output = PrintStream(output),
            error = PrintStream(error),
        )
        return Execution(status, output.toString(), error.toString())
    }

    private fun whyArguments(projectRoot: Path, manifest: String, symbol: String, vararg extra: String): Array<String> {
        projectRoot.resolve("AndroidManifest.xml").writeText(manifest)
        projectRoot.resolve("res").createDirectories()
        return arrayOf(
            "why",
            symbol,
            "--classes",
            classRoot.toString(),
            "--project",
            projectRoot.toString(),
            "--manifest",
            "AndroidManifest.xml",
            "--resources",
            "res",
            "--namespace",
            "dev.kartograph.cli",
            *extra,
        )
    }

    private val classRoot: Path
        get() = Path.of(requireNotNull(KartographCli::class.java.protectionDomain.codeSource).location.toURI())

    private fun compileSharedPair(root: Path): Path = compile(
        root.resolve("shared-sources"),
        root.resolve("shared-classes"),
        "a/Shared.java" to "package a; public class Shared { public void run() {} }",
        "b/Shared.java" to "package b; public class Shared { public void run() {} }",
    )

    private fun compileReachablePair(root: Path): Path = compile(
        root.resolve("reachable-sources"),
        root.resolve("reachable-classes"),
        // manifest component의 member까지 root로 확장되어 Entry.run에서 호출한 Helper까지 경로가 생긴다.
        "dev/kartograph/cli/Entry.java" to
            "package dev.kartograph.cli; public class Entry { public void run() { new Helper().run(); } }",
        "dev/kartograph/cli/Helper.java" to
            "package dev.kartograph.cli; public class Helper { public void run() {} }",
    )

    private fun compile(sourceRoot: Path, classes: Path, vararg sources: Pair<String, String>): Path {
        classes.createDirectories()
        val files = sources.map { (relative, content) ->
            val file = sourceRoot.resolve(relative)
            file.parent.createDirectories()
            file.writeText(content)
            file
        }
        val compiler = requireNotNull(ToolProvider.getSystemJavaCompiler())
        check(compiler.run(null, null, null, "-d", classes.toString(), *files.map(Path::toString).toTypedArray()) == 0)
        return classes
    }

    private data class Execution(
        val status: Int,
        val output: String,
        val error: String,
    )
}
