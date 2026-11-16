package dev.kartograph.cli

import dev.kartograph.core.NodeId
import dev.kartograph.export.QuerySnapshotCodec
import java.io.ByteArrayOutputStream
import java.io.PrintStream
import java.nio.file.Files
import java.nio.file.Path
import javax.tools.ToolProvider
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import org.junit.jupiter.api.io.TempDir

/**
 * 콜백 값 흐름을 모으는 snapshot·query가 긴 javac 메서드에서도 끝나는지 본다. ASM 9.11 기본 계산 한도로는 같은 class가
 * "invalid class file"(종료 코드 2)로 실패했고, ASM 9.10.1은 같은 사실로 성공했다.
 */
class AnalyzerComputeLimitCliTest {
    @Test
    fun `snapshot and query finish on a long lambda cascade with the 9_10_1 callback facts`(@TempDir root: Path) {
        val classes = compileCascade(root)
        val snapshot = run("snapshot", "--classes", classes.toString(), "--project", root.toString())
        assertEquals(0, snapshot.first, snapshot.second)
        assertFalse(snapshot.second.contains("invalid class file"), snapshot.second)
        val graph = QuerySnapshotCodec.parse(snapshot.third).graph
        val runMethod = NodeId("method:Cascade#run(Ljava/lang/Runnable;I)Ljava/lang/Runnable;")
        assertEquals(80, graph.lambdaEscapes.count { it.caller == runMethod })
        assertEquals(3, graph.parameterUses.count { it.method == runMethod })
        val query = run("query", runMethod.value, "--classes", classes.toString(), "--project", root.toString())
        assertEquals(0, query.first, query.second)
    }

    private fun run(vararg args: String): Triple<Int, String, String> {
        val out = ByteArrayOutputStream()
        val err = ByteArrayOutputStream()
        val status = KartographCli.run(arrayOf(*args), PrintStream(out), PrintStream(err))
        return Triple(status, err.toString(Charsets.UTF_8), out.toString(Charsets.UTF_8))
    }

    /** index의 AnalyzerComputeLimitTest와 같은 모양이다: 분기 40개가 람다를 고르고 그 사이에 산술 문장 80개씩이 있다. */
    private fun compileCascade(root: Path): Path {
        val source = buildString {
            appendLine("public class Cascade {")
            appendLine("  static void sink(int k) {}")
            appendLine("  public static Runnable run(Runnable p, int x) {")
            appendLine("    Runnable r = p;")
            appendLine("    int a = 0;")
            var n = 0
            repeat(40) { k ->
                appendLine("    if (x == $k) r = () -> sink($k);")
                repeat(80) { appendLine("    a += x * ${n++};") }
            }
            appendLine("    if (a == 42) r.run();")
            appendLine("    return r;")
            appendLine("  }")
            appendLine("}")
        }
        val file = Files.writeString(root.resolve("Cascade.java"), source)
        val classes = Files.createDirectories(root.resolve("classes"))
        val errors = ByteArrayOutputStream()
        assertEquals(0, ToolProvider.getSystemJavaCompiler().run(null, null, errors, "--release", "17", "-g", "-d",
            classes.toString(), file.toString()), errors.toString())
        return classes
    }
}
