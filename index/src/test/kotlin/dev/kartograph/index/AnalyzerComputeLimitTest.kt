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
        // 이 테스트는 계산 한도 API가 있는 ASM(9.11 이상)에서 한도를 푸는 경로를 본다. 이전 ASM 경로는 LegacyAsmRuntimeTest가 본다.
        assertTrue(AnalysisLimits.computeLimitsSupported, "the test classpath must carry ASM 9.11 or later")
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
    fun `runtime value analysis resolves reflective targets without a counted limit`(@TempDir root: Path) {
        val indexed = ClassFileIndexer().indexWithObservations(listOf(compileReflective(root)))
        assertEquals(setOf("direct", "viaHelper", "viaField"), resolvedForName(indexed.graph))
        assertEquals(0, indexed.observations.sumOf { it.valueAnalysisLimits })
    }

    @Test
    fun `an exceeded runtime value analysis limit is counted instead of failing the run`(@TempDir root: Path) {
        // 본문·반환값 요약(helper)·정적 필드 쓰기(<clinit>) 세 분석이 각각 낮은 연산 한도에 닿는다. field 수요 분석도 같은 한도다.
        val indexer = ClassFileIndexer().apply { runtimeLimits = AnalysisLimits(Int.MAX_VALUE, 20_000L) }
        val indexed = indexer.indexWithObservations(listOf(compileReflective(root)))
        assertEquals(emptySet(), resolvedForName(indexed.graph))
        assertEquals(3, indexed.observations.sumOf { it.valueAnalysisLimits })
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

    @Test
    fun `the remaining tracked allocation limit also names the method`(@TempDir root: Path) {
        // maxBytes는 int라 끌 수 없다(UNBOUNDED도 약 2GiB 누적). 그 한도에 닿는 경우를 낮은 할당 한도로 재현한다.
        val indexer = ClassFileIndexer().apply { callbackLimits = AnalysisLimits(100_000, Long.MAX_VALUE) }
        val message = assertFailsWith<ClassIndexingException> { indexer.index(listOf(compileCascade(root))) }.message.orEmpty()
        assertContains(message, "ASM compute limit (Too many allocated bytes)")
        assertContains(message, run.value)
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

        /** 본문, 정적 helper 반환값, `<clinit>`이 쓴 정적 필드에서 온 이름으로 `Class.forName`을 부르는 class다. */
        fun reflectiveSource(): String = buildString {
            fun statements() = (0 until 30).joinToString("\n") { "    a += a * $it;" }
            appendLine("public class Reflective {")
            appendLine("  private static String field;")
            appendLine("  static {\n    int a = 1;\n${statements()}\n    field = \"Target\";\n  }")
            appendLine("  private static String name() {\n    int a = 1;\n${statements()}\n    return \"Target\";\n  }")
            appendLine("  public static Object direct() throws Exception {\n    int a = 1;\n${statements()}\n    return Class.forName(\"Target\");\n  }")
            appendLine("  public static Object viaHelper() throws Exception { return Class.forName(name()); }")
            appendLine("  public static Object viaField() throws Exception { return Class.forName(field); }")
            appendLine("}")
            appendLine("class Target {}")
        }

        fun compileReflective(root: Path): Path = compile(root, "Reflective", reflectiveSource())

        /** `Class.forName`이 `Target`으로 풀린 호출 메서드 이름이다. */
        fun resolvedForName(graph: dev.kartograph.core.CodeGraph): Set<String> = graph.externalCalls
            .filter { it.name == "forName" && NodeId("class:Target") in it.resolvedTargets }
            .map { it.caller.value.substringAfter('#').substringBefore('(') }.toSet()

        fun compileCascade(root: Path): Path = compile(root, CASCADE, cascadeSource())

        private fun compile(root: Path, name: String, text: String): Path {
            val source = Files.writeString(Files.createDirectories(root.resolve("src-$name")).resolve("$name.java"), text)
            val classes = Files.createDirectories(root.resolve("classes-$name"))
            val errors = ByteArrayOutputStream()
            assertEquals(0, ToolProvider.getSystemJavaCompiler().run(null, null, errors, "--release", "17", "-g", "-d",
                classes.toString(), source.toString()), errors.toString())
            return classes
        }
    }
}
