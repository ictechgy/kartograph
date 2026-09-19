package dev.kartograph.cli

import java.io.ByteArrayOutputStream
import java.io.File
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

class DependenciesCommandTest {
    @Test
    fun `dependencies reports only unreferenced declarations and keeps exit codes`(@TempDir root: Path) {
        val usedClasses = root.resolve("used-classes").createDirectories()
        compileJava(
            listOf(source(root, "used-src", "com/example/used/UsedApi.java", "package com.example.used; public class UsedApi { public static void call() {} public static int FIELD = 1; }\n")),
            usedClasses,
        )
        val signatureClasses = root.resolve("signature-classes").createDirectories()
        compileJava(
            listOf(source(root, "signature-src", "com/example/signature/SignatureApi.java", "package com.example.signature; public class SignatureApi {}\n")),
            signatureClasses,
        )
        val unusedClasses = root.resolve("unused-classes").createDirectories()
        compileJava(
            listOf(source(root, "unused-src", "com/example/unused/UnusedHelper.java", "package com.example.unused; public class UnusedHelper {}\n")),
            unusedClasses,
        )
        val appClasses = root.resolve("app-classes").createDirectories()
        compileJava(
            listOf(
                source(
                    root, "app-src", "com/example/app/App.java",
                    "package com.example.app;\n" +
                        "public class App {\n" +
                        "  public static void accept(com.example.signature.SignatureApi api) {}\n" +
                        "  public static void main(String[] args) { com.example.used.UsedApi.call(); int value = com.example.used.UsedApi.FIELD; }\n" +
                        "}\n",
                ),
            ),
            appClasses,
            classpath = listOf(usedClasses, signatureClasses),
        )
        root.resolve("dependencies.tsv").writeText(
            "# declared for the debug variant\n" +
                "com.example:used:1.0\timplementation\tused-classes\n" +
                "com.example:signature:1.0\tcompileOnly\tsignature-classes\n" +
                "com.example:unused:1.0\timplementation\tunused-classes\n" +
                "com.example:processor:1.0\tkapt\tunused-classes\n",
        )
        val arguments = arrayOf(
            "dependencies", "--classes", appClasses.toString(), "--project", root.toString(),
            "--dependencies", "dependencies.tsv",
        )

        val text = execute(*arguments)
        assertEquals(ExitStatus.SUCCESS.code, text.status)
        assertContains(text.output, "unused-dependency\tcom.example:unused:1.0\timplementation\tunused-classes")
        assertFalse(text.output.contains("unused-dependency\tcom.example:used:1.0"))
        assertFalse(text.output.contains("unused-dependency\tcom.example:signature:1.0"))
        assertContains(text.output, "limitation\t1 declared dependencies use processor or runtime-only scopes and were not judged")
        assertContains(text.output, "limitation\ttest class roots were not supplied; usage from tests is not measured")

        val json = execute(*arguments, "--report-format", "json")
        assertEquals(ExitStatus.SUCCESS.code, json.status)
        assertContains(json.output, "\"command\": \"dependencies\"")
        assertContains(json.output, "\"coordinate\": \"com.example:unused:1.0\"")
        assertContains(json.output, "\"state\": \"unused\"")
        assertContains(json.output, "\"analyzedDependencies\": 3")
        assertContains(json.output, "\"skippedDependencies\": 1")

        assertEquals(ExitStatus.FINDINGS.code, execute(*arguments, "--strict").status)

        val withTests = execute(*arguments, "--test-classes", appClasses.toString())
        assertEquals(ExitStatus.SUCCESS.code, withTests.status)
        assertFalse(withTests.output.contains("test class roots were not supplied"))
    }

    @Test
    fun `dependencies fails closed on malformed lists and missing artifacts`(@TempDir root: Path) {
        val classes = root.resolve("classes").createDirectories()
        compileJava(
            listOf(source(root, "src", "com/example/App.java", "package com.example; public class App {}\n")),
            classes,
        )
        val base = arrayOf(
            "dependencies", "--classes", classes.toString(), "--project", root.toString(),
            "--dependencies", "dependencies.tsv",
        )

        root.resolve("dependencies.tsv").writeText("com.example:lib:1.0\tsomethingElse\tlibs/lib.jar\n")
        val unknownScope = execute(*base)
        assertEquals(ExitStatus.FAILURE.code, unknownScope.status)
        assertContains(unknownScope.error, "unknown scope at line 1")
        assertFalse(unknownScope.error.contains(root.toString()))

        root.resolve("dependencies.tsv").writeText("com.example:lib:1.0\timplementation\tmissing/lib.jar\n")
        val missing = execute(*base)
        assertEquals(ExitStatus.FAILURE.code, missing.status)
        assertContains(missing.error, "dependency artifact for com.example:lib:1.0 does not exist")
        assertFalse(missing.error.contains(root.toString()))

        root.resolve("dependencies.tsv").writeText("")
        assertEquals(ExitStatus.FAILURE.code, execute(*base).status)

        assertEquals(ExitStatus.USAGE.code, execute(*base, "--report-format", "sarif").status)
        assertEquals(ExitStatus.USAGE.code, execute("dependencies", "--unknown").status)
        assertEquals(ExitStatus.USAGE.code, execute("dependencies").status)
        assertEquals(ExitStatus.SUCCESS.code, execute("dependencies", "--help").status)
        assertContains(execute("dependencies", "--help").output, "coordinate<TAB>scope<TAB>artifact")
    }

    private fun source(root: Path, directory: String, relative: String, content: String): Path =
        root.resolve(directory).resolve(relative).also { path ->
            path.parent.createDirectories()
            path.writeText(content)
        }

    private fun compileJava(sources: List<Path>, classes: Path, classpath: List<Path> = emptyList()) {
        val arguments = buildList {
            add("-g")
            add("-d")
            add(classes.toString())
            if (classpath.isNotEmpty()) {
                add("-cp")
                add(classpath.joinToString(File.pathSeparator))
            }
            sources.forEach { add(it.toString()) }
        }
        check(requireNotNull(ToolProvider.getSystemJavaCompiler()).run(null, null, null, *arguments.toTypedArray()) == 0)
    }

    private fun execute(vararg arguments: String): Execution {
        val output = ByteArrayOutputStream()
        val error = ByteArrayOutputStream()
        val status = KartographCli.run(arguments = arguments, output = PrintStream(output), error = PrintStream(error))
        return Execution(status, output.toString(), error.toString())
    }

    private data class Execution(val status: Int, val output: String, val error: String)
}
