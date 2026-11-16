package dev.kartograph.index

import dev.kartograph.core.InvocationKind
import dev.kartograph.core.LambdaEscape
import dev.kartograph.core.NodeId
import dev.kartograph.core.ParameterUse
import dev.kartograph.core.ParameterUseKind
import java.io.ByteArrayOutputStream
import java.nio.file.Files
import java.nio.file.Path
import javax.tools.ToolProvider
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue
import org.junit.jupiter.api.io.TempDir
import org.objectweb.asm.LimitExceededException

/**
 * ASM 9.11의 `Analyzer`는 기본 계산 한도(연산 5×10¹⁰)를 두고, 처리한 명령마다 그 위치에 비례한 연산을 센다. 분기마다
 * 람다를 고르는 긴 javac 메서드는 9.10.1에서 1초 남짓에 끝나지만 기본 한도로는 `LimitExceededException`이 나서 인덱싱
 * 전체가 "invalid class file"로 실패했다. 콜백 사실은 9.10.1과 같아야 한다.
 */
class AnalyzerComputeLimitTest {
    private val run = NodeId("method:$CASCADE#run(Ljava/lang/Runnable;I)Ljava/lang/Runnable;")

    @Test
    fun `cascade method keeps the callback facts ASM 9_10_1 produced`(@TempDir root: Path) {
        val graph = ClassFileIndexer().index(listOf(compileCascade(root)))
        assertEquals(emptyList(), graph.callbackArguments.filter { it.caller == run })
        // ASM 9.10.1 빌드가 같은 class에서 남긴 사실이다: 파라미터 0의 표식·인터페이스 수신·반환과 람다 40개의 수신·반환.
        assertEquals(
            setOf(
                ParameterUse(run, 0, ParameterUseKind.DECLARED),
                ParameterUse(run, 0, ParameterUseKind.RECEIVER, NodeId("method:java/lang/Runnable#run()V"), InvocationKind.INTERFACE),
                ParameterUse(run, 0, ParameterUseKind.RETURN),
            ),
            graph.parameterUses.filter { it.method == run }.toSet(),
        )
        val expected = (0 until LAMBDAS).flatMap { k ->
            val lambda = NodeId("method:$CASCADE#lambda\$run\$$k()V")
            listOf(LambdaEscape(run, lambda, ParameterUseKind.RECEIVER), LambdaEscape(run, lambda, ParameterUseKind.RETURN))
        }.toSet()
        val escapes = graph.lambdaEscapes.filter { it.caller == run }
        assertEquals(expected, escapes.toSet())
        assertEquals(expected.size, escapes.size)
    }

    @Test
    fun `cascade method also passes runtime value analysis without a counted limit`(@TempDir root: Path) {
        val indexed = ClassFileIndexer().indexWithObservations(listOf(compileCascade(root)))
        assertTrue(indexed.graph.nodes.containsKey(run))
        assertEquals(0, indexed.observations.sumOf { it.valueAnalysisLimits })
    }

    @Test
    fun `an exceeded analysis limit names the method instead of an invalid class file`(@TempDir root: Path) {
        // API 최대치(UNBOUNDED)에서도 남는 추적 할당 한도에 닿은 경우를 낮은 연산 한도로 재현한다.
        val indexer = ClassFileIndexer().apply { callbackLimits = AnalysisLimits(Int.MAX_VALUE, 1_000_000L) }
        val failure = assertFailsWith<ClassIndexingException> { indexer.index(listOf(compileCascade(root))) }
        val message = failure.message.orEmpty()
        assertFalse("invalid class file" in message, message)
        assertContains(message, "ASM compute limit (Too many operations)")
        assertContains(message, run.value)
        assertIs<LimitExceededException>(failure.cause)
    }

    companion object {
        const val CASCADE = "Cascade"
        const val LAMBDAS = 40
        private const val STATEMENTS_PER_BRANCH = 80

        /** 분기마다 람다를 고르고 그 사이에 산술 문장을 끼운 javac 메서드다(약 2만 명령, 43KB). */
        fun cascadeSource(): String = buildString {
            appendLine("public class $CASCADE {")
            appendLine("  static void sink(int k) {}")
            appendLine("  public static Runnable run(Runnable p, int x) {")
            appendLine("    Runnable r = p;")
            appendLine("    int a = 0;")
            var n = 0
            repeat(LAMBDAS) { k ->
                appendLine("    if (x == $k) r = () -> sink($k);")
                repeat(STATEMENTS_PER_BRANCH) { appendLine("    a += x * ${n++};") }
            }
            appendLine("    if (a == 42) r.run();")
            appendLine("    return r;")
            appendLine("  }")
            appendLine("}")
        }

        fun compileCascade(root: Path): Path {
            val source = Files.writeString(root.resolve("$CASCADE.java"), cascadeSource())
            val classes = Files.createDirectories(root.resolve("classes"))
            val errors = ByteArrayOutputStream()
            assertEquals(0, ToolProvider.getSystemJavaCompiler().run(null, null, errors, "--release", "17", "-g", "-d",
                classes.toString(), source.toString()), errors.toString())
            return classes
        }
    }
}
