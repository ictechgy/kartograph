package dev.kartograph.cli

import dev.kartograph.core.BuildWitness
import dev.kartograph.core.InputFingerprint
import dev.kartograph.export.BuildWitnessCodec
import dev.kartograph.index.ContentFingerprint
import java.io.ByteArrayOutputStream
import java.io.PrintStream
import java.nio.file.Files
import java.nio.file.Path
import javax.tools.ToolProvider
import kotlin.io.path.createDirectories
import kotlin.io.path.readBytes
import kotlin.io.path.readText
import kotlin.io.path.writeBytes
import kotlin.io.path.writeText
import kotlin.streams.asSequence
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.io.TempDir

/**
 * docs/APP-MODULE-EVIDENCE.md 1단계의 실물 산출물 실험이다. 합성 픽스처(AppModuleRJarCliTest)와 달리
 * 실제 AGP 8.7 빌드의 R.jar와 Hilt/KSP 생성 class가 포함된 모듈 디렉터리를 입력으로 쓴다.
 * 보존된 Now in Android 빌드 산출물이 있는 환경에서만 실행되고, 없으면 skip한다.
 * Gradle 네트워크가 막힌 샌드박스에서도 CLI만으로 수행 가능하다.
 */
class RealAgpRJarCliTest {
    private val niaRoot: Path? = sequenceOf(
        Path.of("../build/reports/android-tool-comparison-20260911/work/nowinandroid"),
        Path.of("build/reports/android-tool-comparison-20260911/work/nowinandroid"),
    ).firstOrNull(Files::isDirectory)

    @Test
    fun `real AGP R jar root outside witness coverage reproduces the unwitnessed rejection`(@TempDir projectRoot: Path) {
        assumeTrue(niaRoot != null, "preserved Now in Android build outputs are unavailable")
        val nia = requireNotNull(niaRoot)

        val scenario = scenario(projectRoot, nia, resourceWitness = false)

        val execution = execute("verify-snapshot", "--graph-file", scenario.snapshot.toString(),
            "--project", projectRoot.toString(), "--scope", "nia:demoDebug")

        assertEquals(ExitStatus.FINDINGS.code, execution.status)
        assertContains(execution.output, "\"status\":\"unverified\"")
        assertContains(execution.output, "unwitnessed-class-root")
        // 실물 산출물 fidelity: 스냅샷 그래프에 실제 앱 패키지의 선언이 들어있다.
        assertContains(scenario.snapshot.readText(), "nowinandroid")
    }

    @Test
    fun `a resource producer witness covering the real R jar restores matched evidence`(@TempDir projectRoot: Path) {
        assumeTrue(niaRoot != null, "preserved Now in Android build outputs are unavailable")
        val nia = requireNotNull(niaRoot)

        val scenario = scenario(projectRoot, nia, resourceWitness = true)

        val execution = execute("verify-snapshot", "--graph-file", scenario.snapshot.toString(),
            "--project", projectRoot.toString(), "--scope", "nia:demoDebug")

        assertEquals(ExitStatus.SUCCESS.code, execution.status)
        assertContains(execution.output, "\"status\":\"matched\"")
        // 추가 witness도 producer 증거 계약 안에 있다: 실물 R.jar 내용이 바뀌면 stale로 실패한다.
        val stamp = Files.getLastModifiedTime(scenario.rJar)
        scenario.rJar.writeBytes(scenario.rJar.readBytes() + byteArrayOf(9))
        Files.setLastModifiedTime(scenario.rJar, stamp)
        val changed = execute("verify-snapshot", "--graph-file", scenario.snapshot.toString(),
            "--project", projectRoot.toString(), "--scope", "nia:demoDebug")
        assertEquals(ExitStatus.FINDINGS.code, changed.status)
        assertContains(changed.output, "changed-classes")
    }

    private data class Scenario(val snapshot: Path, val rJar: Path)

    /** 보존된 실물 class 디렉터리들과 R.jar를 project root 아래로 모아 자동 캡처 입력을 구성한다. */
    private fun scenario(projectRoot: Path, nia: Path, resourceWitness: Boolean): Scenario {
        projectRoot.resolve("res").createDirectories()
        projectRoot.resolve("src/dev/kartograph/cli/Entry.java").let { source ->
            source.parent.createDirectories()
            source.writeText("package dev.kartograph.cli; public class Entry { public void run() {} }")
        }
        projectRoot.resolve("build.gradle.kts").writeText("plugins { id(\"com.android.application\") }\n")
        projectRoot.resolve("compiler.jar").writeText("compiler artifact")

        val transformDirs = Files.walk(nia).use { stream ->
            stream.asSequence()
                .filter { path -> path.fileName.toString() == "dirs" &&
                    path.parent?.fileName.toString() == "transformDemoDebugClassesWithAsm" }
                .sorted()
                .toList()
        }
        assumeTrue(transformDirs.isNotEmpty(), "no transformed class directories are preserved")

        val rJar = projectRoot.resolve("generated/R.jar")
        rJar.parent.createDirectories()
        rJar.writeBytes(pickRealRJar(nia).readBytes())

        fun fingerprint(role: String, path: String) =
            ContentFingerprint.capture(projectRoot, projectRoot.resolve(path), role, role)

        val copiedRoots = transformDirs.mapIndexed { index, dir ->
            val target = projectRoot.resolve("classes-$index")
            dir.toFile().copyRecursively(target.toFile())
            target
        }
        val javacInputs = listOf(
            fingerprint("sources", "src"),
            fingerprint("buildConfig", "build.gradle.kts"),
            fingerprint("compiler", "compiler.jar"),
            InputFingerprint("options", "compileDebugJavaWithJavac-options", ContentFingerprint.values(listOf("-g"))),
        )
        val javacWitnessFile = projectRoot.resolve("javac-witness.json")
        javacWitnessFile.writeText(
            BuildWitnessCodec.render(
                BuildWitness("nia:demoDebug", "javac", "compileDemoDebugJavaWithJavac",
                    javacInputs, copiedRoots.map { root -> fingerprint("classes", root.fileName.toString()) }),
            ),
        )

        val witnessArguments = if (resourceWitness) {
            val resourceFile = projectRoot.resolve("resource-witness.json")
            resourceFile.writeText(
                BuildWitnessCodec.render(
                    BuildWitness("nia:demoDebug", "javac", "processDemoDebugResources",
                        listOf(
                            fingerprint("sources", "res"),
                            fingerprint("buildConfig", "build.gradle.kts"),
                            fingerprint("compiler", "compiler.jar"),
                            InputFingerprint("options", "processDemoDebugResources-options",
                                ContentFingerprint.values(listOf("-g"))),
                        ),
                        listOf(fingerprint("classes", "generated/R.jar"))),
                ),
            )
            arrayOf("--build-witness", resourceFile.toString())
        } else {
            arrayOf<String>()
        }

        val output = ByteArrayOutputStream()
        val error = ByteArrayOutputStream()
        val captureStatus = KartographCli.run(
            arguments = buildList {
                add("snapshot")
                add("--classes"); add(rJar.toString())
                copiedRoots.forEach { root -> add("--classes"); add(root.toString()) }
                add("--project"); add(projectRoot.toString())
                add("--scope"); add("nia:demoDebug")
                add("--snapshot-max-mib"); add("128")
                add("--build-witness"); add(javacWitnessFile.toString())
                addAll(witnessArguments)
            }.toTypedArray(),
            output = PrintStream(output),
            error = PrintStream(error),
        )
        check(captureStatus == ExitStatus.SUCCESS.code) { "snapshot capture failed: ${error.toString(Charsets.UTF_8)}" }
        val snapshot = projectRoot.resolve("snapshot.json")
        snapshot.writeText(output.toString(Charsets.UTF_8))
        return Scenario(snapshot, rJar)
    }

    private fun pickRealRJar(nia: Path): Path = Files.walk(nia).use { stream ->
        stream.asSequence()
            .filter { path -> path.fileName.toString() == "R.jar" &&
                path.toString().contains("compile_r_class_jar") }
            .sorted()
            .firstOrNull()
            ?: throw IllegalStateException("no preserved R.jar found")
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
