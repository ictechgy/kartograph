package dev.kartograph.cli

import dev.kartograph.core.BuildWitness
import dev.kartograph.core.InputFingerprint
import dev.kartograph.export.BuildWitnessCodec
import dev.kartograph.index.ContentFingerprint
import java.io.ByteArrayOutputStream
import java.io.PrintStream
import java.nio.file.Files
import java.nio.file.Path
import java.util.jar.JarEntry
import java.util.jar.JarOutputStream
import javax.tools.ToolProvider
import kotlin.io.path.createDirectories
import kotlin.io.path.readBytes
import kotlin.io.path.writeBytes
import kotlin.io.path.writeText
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import org.junit.jupiter.api.io.TempDir

/**
 * docs/APP-MODULE-EVIDENCE.md 비교 실험의 CLI 절반이다. AGP application 모듈에서 자동 캡처가
 * 거부되는 원인(processResources 산출 R.jar가 compiler witness output에 없음)을 producer
 * 증거 계약을 약화하지 않고 재현하고, resource producer witness 추가(설계 후보 A)가
 * `matched`를 회복하는지 확인한다. 실제 AGP 빌드 절반은 Android SDK 환경에서 남는다.
 */
class AppModuleRJarCliTest {
    @Test
    fun `an R jar root outside compiler witness outputs reproduces the unwitnessed rejection`(@TempDir projectRoot: Path) {
        val scenario = scenario(projectRoot, resourceWitness = false)

        val execution = execute("verify-snapshot", "--graph-file", scenario.snapshot.toString(),
            "--project", projectRoot.toString(), "--scope", "app:debug")

        assertEquals(ExitStatus.FINDINGS.code, execution.status)
        assertContains(execution.output, "\"status\":\"unverified\"")
        assertContains(execution.output, "unwitnessed-class-root")
    }

    @Test
    fun `a resource producer witness covering the R jar restores matched evidence`(@TempDir projectRoot: Path) {
        val scenario = scenario(projectRoot, resourceWitness = true)

        val execution = execute("verify-snapshot", "--graph-file", scenario.snapshot.toString(),
            "--project", projectRoot.toString(), "--scope", "app:debug")

        assertEquals(ExitStatus.SUCCESS.code, execution.status)
        assertContains(execution.output, "\"status\":\"matched\"")
        // 추가 witness도 producer 증거 계약 안에 있다: R.jar 내용이 바뀌면 stale로 실패한다.
        val stamp = Files.getLastModifiedTime(scenario.rJar)
        scenario.rJar.writeBytes(scenario.rJar.readBytes() + byteArrayOf(9))
        Files.setLastModifiedTime(scenario.rJar, stamp)
        val changed = execute("verify-snapshot", "--graph-file", scenario.snapshot.toString(),
            "--project", projectRoot.toString(), "--scope", "app:debug")
        assertEquals(ExitStatus.FINDINGS.code, changed.status)
        assertContains(changed.output, "changed-classes")
    }

    private data class Scenario(val snapshot: Path, val rJar: Path)

    /** AGP 8 application의 class root 구성(javac 산출 + processResources R.jar)을 재현한다. */
    private fun scenario(projectRoot: Path, resourceWitness: Boolean): Scenario {
        projectRoot.resolve("res").createDirectories()
        val sources = projectRoot.resolve("src/dev/kartograph/cli/Entry.java")
        sources.parent.createDirectories()
        sources.writeText("package dev.kartograph.cli; public class Entry { public void run() {} }")
        val classes = projectRoot.resolve("classes").createDirectories()
        val compiler = requireNotNull(ToolProvider.getSystemJavaCompiler())
        check(compiler.run(null, null, null, "-g", "-d", classes.toString(), sources.toString()) == 0)
        // witness 계약은 sources·buildConfig·compiler·options 입력을 요구한다.
        projectRoot.resolve("build.gradle.kts").writeText("plugins { id(\"com.android.application\") }\n")
        projectRoot.resolve("compiler.jar").writeText("compiler artifact")

        val rJar = buildRJar(projectRoot)

        fun fingerprint(role: String, path: String) =
            ContentFingerprint.capture(projectRoot, projectRoot.resolve(path), role, role)
        val javacInputs = listOf(
            fingerprint("sources", "src"),
            fingerprint("buildConfig", "build.gradle.kts"),
            fingerprint("compiler", "compiler.jar"),
            InputFingerprint("options", "compileDebugJavaWithJavac-options", ContentFingerprint.values(listOf("-g"))),
        )
        val javacWitness = BuildWitnessCodec.render(
            BuildWitness("app:debug", "javac", "compileDebugJavaWithJavac",
                javacInputs, listOf(fingerprint("classes", "classes"))),
        )
        val witnessFile = projectRoot.resolve("javac-witness.json")
        witnessFile.writeText(javacWitness)

        val witnessArguments = if (resourceWitness) {
            val resource = BuildWitnessCodec.render(
                BuildWitness("app:debug", "javac", "processDebugResources",
                    listOf(
                        fingerprint("sources", "res"),
                        fingerprint("buildConfig", "build.gradle.kts"),
                        fingerprint("compiler", "compiler.jar"),
                        InputFingerprint("options", "processDebugResources-options", ContentFingerprint.values(listOf("-g"))),
                    ),
                    listOf(fingerprint("classes", "generated/R.jar"))),
            )
            val resourceFile = projectRoot.resolve("resource-witness.json")
            resourceFile.writeText(resource)
            arrayOf("--build-witness", resourceFile.toString())
        } else {
            arrayOf<String>()
        }

        val output = ByteArrayOutputStream()
        val error = ByteArrayOutputStream()
        val captureStatus = KartographCli.run(
            arguments = arrayOf(
                "snapshot",
                "--classes", classes.toString(),
                "--classes", rJar.toString(),
                "--project", projectRoot.toString(),
                "--scope", "app:debug",
                "--build-witness", witnessFile.toString(),
                *witnessArguments,
            ),
            output = PrintStream(output),
            error = PrintStream(error),
        )
        check(captureStatus == ExitStatus.SUCCESS.code) { "snapshot capture failed: ${error.toString(Charsets.UTF_8)}" }
        val snapshot = projectRoot.resolve("snapshot.json")
        snapshot.writeText(output.toString(Charsets.UTF_8))
        return Scenario(snapshot, rJar)
    }

    /** AGP processDebugResources 산출물과 같은 형태의 class root JAR를 만든다. */
    private fun buildRJar(projectRoot: Path): Path {
        val compiler = requireNotNull(ToolProvider.getSystemJavaCompiler())
        val rSource = projectRoot.resolve("r-sources/dev/kartograph/cli/R.java")
        rSource.parent.createDirectories()
        rSource.writeText(
            "package dev.kartograph.cli; public final class R { public static final class layout { public static final int main = 1; } }",
        )
        val rClasses = projectRoot.resolve("r-classes").createDirectories()
        check(compiler.run(null, null, null, "-d", rClasses.toString(), rSource.toString()) == 0)
        val rJar = projectRoot.resolve("generated/R.jar")
        rJar.parent.createDirectories()
        JarOutputStream(Files.newOutputStream(rJar)).use { output ->
            output.putNextEntry(JarEntry("dev/kartograph/cli/R.class"))
            output.write(Files.readAllBytes(rClasses.resolve("dev/kartograph/cli/R.class")))
            output.closeEntry()
        }
        return rJar
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

    private data class Execution(val status: Int, val output: String, val error: String)
}
