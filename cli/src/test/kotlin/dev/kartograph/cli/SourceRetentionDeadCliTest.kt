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

/**
 * SOURCE retention 어노테이션은 사용처가 class 파일에 남지 않아 도달 불가여도 보고하지 않는다.
 * 그 사실을 `dead`의 모든 출력이 한계로 알리고, `--explain`도 같은 판단을 밝혀야 보고와 설명이 어긋나지 않는다.
 */
class SourceRetentionDeadCliTest {
    @Test
    fun `dead hides SOURCE retention annotations and states the limitation`(@TempDir root: Path) {
        val classes = compile(root)

        val report = execute(*deadArguments(root, classes, "--strict"))
        assertFalse(report.output.contains("unreachable\tclass:sample/Marker"), report.output)
        assertContains(report.output, "unreachable\tclass:sample/Unused")
        assertContains(report.output, "limitation\tSOURCE_RETENTION_ANNOTATIONS\t")

        val json = execute(*deadArguments(root, classes, "--report-format", "json"))
        // JSON은 한계를 이름이 아니라 설명 문장으로 싣는다.
        assertContains(json.output, dev.kartograph.core.AnalysisLimitation.SOURCE_RETENTION_ANNOTATIONS.description)

        val explained = execute(*deadArguments(root, classes, "--explain", "class:sample/Marker"))
        assertEquals(ExitStatus.SUCCESS.code, explained.status, explained.error)
        assertContains(explained.output, "unreachable\tclass:sample/Marker")
        assertContains(explained.output, "not-reported\tclass:sample/Marker\tSOURCE_RETENTION_ANNOTATIONS\n")

        // 정적 한계 목록은 모든 설명에 붙지만, 노드별 미보고 줄은 해당 노드에만 나온다.
        val ordinary = execute(*deadArguments(root, classes, "--explain", "class:sample/Unused"))
        assertFalse(ordinary.output.contains("not-reported"), ordinary.output)
    }

    private fun compile(root: Path): Path {
        val classes = root.resolve("classes").createDirectories()
        val sources = mapOf(
            "Marker" to "@java.lang.annotation.Retention(java.lang.annotation.RetentionPolicy.SOURCE) public @interface Marker {}",
            "Uses" to "@Marker public class Uses {}",
            "Unused" to "public class Unused {}",
        ).map { (name, body) ->
            val file = root.resolve("sources/sample/$name.java")
            file.parent.createDirectories()
            file.writeText("package sample; $body\n")
            file.toString()
        }
        val compiler = requireNotNull(ToolProvider.getSystemJavaCompiler())
        check(compiler.run(null, null, null, "-d", classes.toString(), *sources.toTypedArray()) == 0)
        return classes
    }

    private fun deadArguments(root: Path, classes: Path, vararg extra: String): Array<String> {
        root.resolve("AndroidManifest.xml").writeText("<manifest />")
        root.resolve("res").createDirectories()
        return arrayOf(
            "dead", "--classes", classes.toString(), "--project", root.toString(), "--manifest", "AndroidManifest.xml",
            "--resources", "res", "--namespace", "sample", *extra,
        )
    }

    private fun execute(vararg arguments: String): Execution {
        val output = ByteArrayOutputStream()
        val error = ByteArrayOutputStream()
        val status = KartographCli.run(arguments = arguments, output = PrintStream(output), error = PrintStream(error))
        return Execution(status, output.toString(), error.toString())
    }

    private data class Execution(val status: Int, val output: String, val error: String)
}
