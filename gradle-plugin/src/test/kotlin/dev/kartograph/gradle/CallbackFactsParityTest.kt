package dev.kartograph.gradle

import dev.kartograph.export.ExternalInputBindingsCodec
import dev.kartograph.export.QuerySnapshot
import dev.kartograph.export.QuerySnapshotCodec
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import org.gradle.testkit.runner.GradleRunner
import org.junit.jupiter.api.io.TempDir

/**
 * plugin snapshot과 이를 합친 snapshot이 CLI `snapshot --classes`와 같은 콜백 사실을 싣는지 확인한다.
 *
 * `includeSourcePaths`를 켜면 plugin은 source 경로를 붙이려고 그래프를 다시 조립한다. 이때 콜백 사실을 빠뜨리면
 * 캡처 표식은 참인데 사실이 비어 `language-traversal`의 콜백 간선이 조용히 사라진다. 같은 합성 프로젝트를 plugin으로
 * 빌드한 뒤 같은 class root를 실제 CLI(별도 JVM)에 넘겨 세 사실 목록을 비교한다.
 */
class CallbackFactsParityTest {
    @Test
    fun `plugin snapshots and their merge carry the same callback facts as the CLI`(@TempDir root: Path) {
        fixture(root)
        GradleRunner.create().withProjectDir(root.toFile()).withPluginClasspath()
            .withArguments(":core:kartographSnapshot", ":app:kartographSnapshot", "--offline", "--configuration-cache", "--stacktrace")
            .build()

        for (module in listOf("core", "app")) {
            val directory = root.resolve(module)
            val plugin = QuerySnapshotCodec.parse(Files.readString(directory.resolve(SNAPSHOT)))
            assertTrue(plugin.callbackFactsCaptured, module)
            assertTrue(plugin.graph.callbackArguments.isNotEmpty(), "$module plugin snapshot lost callbackArguments")
            assertSameFacts(module, QuerySnapshotCodec.parse(cli(*captureArguments(directory, plugin))), plugin)
        }

        // 합친 snapshot은 구성원 class root를 다시 인덱싱한다. 두 모듈을 한 번에 캡처한 CLI 결과와 같아야 한다.
        val merged = QuerySnapshotCodec.parse(cli("snapshot", "merge", "--project", root.toString(), "--include-paths",
            *member(root, "core"), *member(root, "app"),
            "--input-bindings-output", root.resolve("out/merged-bindings.json").toString()))
        val direct = QuerySnapshotCodec.parse(cli("snapshot", "--project", root.toString(), "--include-paths",
            "--classes", root.resolve("core/$CLASSES").toString(), "--classes", root.resolve("app/$CLASSES").toString()))
        assertTrue(merged.callbackFactsCaptured)
        assertTrue(listOf(merged.graph.callbackArguments, merged.graph.parameterUses, merged.graph.lambdaEscapes).all { it.isNotEmpty() })
        assertSameFacts("merge", direct, merged)
    }

    /** 세 콜백 사실 목록이 모두 같은지 비교한다. 목록 하나만 달라도 bound 판정이 달라지기 때문이다. */
    private fun assertSameFacts(label: String, expected: QuerySnapshot, actual: QuerySnapshot) {
        assertEquals(expected.graph.callbackArguments, actual.graph.callbackArguments, "$label callbackArguments")
        assertEquals(expected.graph.parameterUses, actual.graph.parameterUses, "$label parameterUses")
        assertEquals(expected.graph.lambdaEscapes, actual.graph.lambdaEscapes, "$label lambdaEscapes")
    }

    /**
     * plugin이 provenance에 기록한 class root·classpath를 같은 순서로 CLI 인자로 옮긴다.
     * 모듈 밖 입력은 plugin이 남긴 로컬 연결로 되찾는다. 순서가 중복 class 선택을 바꾸므로 그대로 지킨다.
     */
    private fun captureArguments(directory: Path, plugin: QuerySnapshot): Array<String> {
        val bindings = bindings(directory)
        val options = mapOf("classes" to "--classes", "classpath" to "--classpath")
        val inputs = requireNotNull(plugin.provenance).inputs.filter { it.role in options }.flatMap { input ->
            val path = if (input.path.startsWith("external/")) bindings.getValue(input.path) else directory.resolve(input.path)
            if (Files.exists(path)) listOf(options.getValue(input.role), path.toString()) else emptyList()
        }
        return (listOf("snapshot", "--project", directory.toString(), "--include-paths") + inputs).toTypedArray()
    }

    private fun member(root: Path, module: String): Array<String> = arrayOf("--module", module,
        "--graph-file", root.resolve("$module/$SNAPSHOT").toString(),
        "--input-bindings", root.resolve("$module/$BINDINGS").toString())

    private fun bindings(directory: Path): Map<String, Path> =
        ExternalInputBindingsCodec.parse(Files.readString(directory.resolve(BINDINGS))).mapValues { Path.of(it.value) }

    /**
     * 테스트 classpath에 있는 CLI를 별도 JVM으로 실행해 표준 출력을 돌려준다.
     * CLI 진입점은 종료 코드로 `exitProcess`를 부르므로 테스트 JVM 안에서 부를 수 없다.
     */
    private fun cli(vararg arguments: String): String {
        val output = Files.createTempFile("kartograph-cli", ".out")
        val error = Files.createTempFile("kartograph-cli", ".err")
        try {
            val java = Path.of(System.getProperty("java.home"), "bin", "java").toString()
            val status = ProcessBuilder(listOf(java, "-cp", System.getProperty("java.class.path"), "dev.kartograph.cli.MainKt") + arguments)
                .redirectOutput(output.toFile()).redirectError(error.toFile()).start().waitFor()
            assertEquals(0, status, "kartograph ${arguments.take(2)} failed: ${Files.readString(error)}")
            return Files.readString(output)
        } finally {
            Files.deleteIfExists(output)
            Files.deleteIfExists(error)
        }
    }

    /**
     * 모듈마다 plugin을 적용한 두 Java 모듈이다. `core`는 받은 람다를 실행·전달·저장하고, `app`은 람다를 넘기고
     * 반환한다. 세 사실 목록이 모두 비지 않아야 비교가 의미를 가진다.
     */
    private fun fixture(root: Path) {
        Files.writeString(root.resolve("settings.gradle"), "rootProject.name='callback-parity'\ninclude 'core', 'app'\n")
        Files.writeString(root.resolve("build.gradle"), """
            plugins { id 'io.github.ictechgy.kartograph' apply false }
            subprojects {
                apply plugin: 'java-library'
                apply plugin: 'io.github.ictechgy.kartograph'
                kartograph { snapshotsEnabled = true; includeSourcePaths = true }
            }
            project(':app') { dependencies { implementation project(':core') } }
        """.trimIndent())
        source(root, "core/src/main/java/core/Ui.java", """
            package core;
            public class Ui {
                private static Runnable stored;
                public static void button(Runnable action) { action.run(); }
                public static void forward(Runnable action) { button(action); }
                public static void remember(Runnable action) { stored = action; }
            }
        """.trimIndent())
        source(root, "core/src/main/java/core/Panel.java", """
            package core;
            public class Panel { public void show() { Ui.forward(() -> System.out.println("panel")); } }
        """.trimIndent())
        source(root, "app/src/main/java/app/Screen.java", """
            package app;
            import java.util.function.IntSupplier;
            public class Screen {
                public void render() {
                    core.Ui.button(() -> load());
                    Runnable later = () -> load();
                    core.Ui.remember(later);
                    compute(() -> 1);
                }
                public IntSupplier escape() { return () -> 2; }
                int load() { return 1; }
                static int compute(IntSupplier supplier) { return supplier.getAsInt(); }
            }
        """.trimIndent())
    }

    private fun source(root: Path, path: String, text: String) {
        val file = root.resolve(path)
        Files.createDirectories(file.parent)
        Files.writeString(file, text)
    }

    private companion object {
        const val SNAPSHOT = "build/reports/kartograph/jvm-snapshot.json"
        const val BINDINGS = "build/kartograph/jvm-input-bindings.json"
        const val CLASSES = "build/classes/java/main"
    }
}
