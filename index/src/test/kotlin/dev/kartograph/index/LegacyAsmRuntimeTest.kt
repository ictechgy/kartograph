package dev.kartograph.index

import dev.kartograph.core.CodeGraph
import java.io.File
import java.net.URLClassLoader
import java.nio.file.Path
import kotlin.metadata.jvm.KotlinClassMetadata
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import org.junit.jupiter.api.io.TempDir

/**
 * Gradle plugin JAR는 ASM을 그대로 내장하지만, Android build에서는 AGP가 먼저 올린 ASM을 부모 classloader에서 쓴다. 그래서
 * kartograph 인덱싱이 컴파일된 ASM(9.11.1)보다 오래된 ASM에서 돌 수 있다. 9.11 이전 ASM에는 `Analyzer.setComputeLimits`와
 * `LimitExceededException`이 없다. 그 ASM만 보이는 격리된 classloader에서 콜백 사실까지 모으는 인덱싱이 끝나는지 본다.
 */
class LegacyAsmRuntimeTest {
    @Test
    fun `indexing with callback facts runs on an ASM without the compute limit API`(@TempDir root: Path) {
        val roots = inputs(root)
        URLClassLoader(urls(legacyAsm() + kartograph()), ClassLoader.getPlatformClassLoader()).use { loader ->
            assertIndexes(loader, roots)
        }
    }

    @Test
    fun `plugin jar with its own ASM still uses the host ASM loaded by a parent classloader`(@TempDir root: Path) {
        // Android build의 모양이다: AGP의 ASM은 root project classloader에, plugin JAR(ASM 9.11.1 내장)는 그 자식에 있다. Gradle
        // classloader는 부모를 먼저 보므로 Analyzer는 host ASM에서, 9.11에만 있는 class는 plugin JAR에서 온다.
        val roots = inputs(root)
        val bundled = listOf(org.objectweb.asm.ClassReader::class.java, org.objectweb.asm.tree.MethodNode::class.java,
            org.objectweb.asm.tree.analysis.Analyzer::class.java).map(::location)
        URLClassLoader(urls(legacyAsm()), ClassLoader.getPlatformClassLoader()).use { host ->
            URLClassLoader(urls(kartograph() + bundled), host).use { plugin ->
                assertTrue(plugin.loadClass("org.objectweb.asm.LimitExceededException").classLoader === plugin)
                assertIndexes(plugin, roots)
            }
        }
    }

    /** 콜백 값 흐름(cascade)과 runtime 값 분석(reflective의 본문·helper 반환값·정적 필드) 입력이다. */
    private fun inputs(root: Path): List<Path> = listOf(
        AnalyzerComputeLimitTest.compileCascade(root.resolve("cascade")),
        AnalyzerComputeLimitTest.compileReflective(root.resolve("reflective")),
    )

    /**
     * 이 loader에서 Analyzer가 한도 API 없는 ASM이고, 콜백 사실과 runtime 관측까지 모으는 인덱싱이 테스트 classpath(ASM 9.11.1)의
     * 결과와 같게 끝나는지 본다. 그 결과는 AnalyzerComputeLimitTest가 9.10.1 기대값과 맞춘다.
     */
    private fun assertIndexes(loader: ClassLoader, roots: List<Path>) {
        val analyzer = loader.loadClass("org.objectweb.asm.tree.analysis.Analyzer")
        assertTrue(analyzer.methods.none { it.name == "setComputeLimits" }, "this test must run on an ASM without compute limits")
        val indexerType = loader.loadClass(ClassFileIndexer::class.java.name)
        val indexed = indexerType.getMethod("indexWithObservations", Iterable::class.java).invoke(indexerType.getConstructor().newInstance(), roots)
        val expected = ClassFileIndexer().indexWithObservations(roots)
        assertEquals(2 * AnalyzerComputeLimitTest.LAMBDAS, expected.graph.lambdaEscapes.size)
        assertEquals(setOf("direct", "viaHelper", "viaField"), AnalyzerComputeLimitTest.resolvedForName(expected.graph))
        val graph = indexed.javaClass.getMethod("getGraph").invoke(indexed)
        for (facts in listOf("getExternalCalls", "getCallbackArguments", "getParameterUses", "getLambdaEscapes", "getEdges")) {
            assertEquals(strings(facts, expected.graph), strings(facts, graph), facts)
        }
        // internal 속성의 getter 이름은 module 이름으로 바뀌므로 접두사로 찾는다.
        val observations = indexed.javaClass.methods.single { it.name.startsWith("getObservations") }.invoke(indexed) as List<*>
        assertEquals(expected.observations.map(Any::toString), observations.map(Any?::toString))
        assertEquals(0, expected.observations.sumOf { it.valueAnalysisLimits })
    }

    private fun strings(getter: String, target: Any): List<String> =
        (target.javaClass.getMethod(getter).invoke(target) as Collection<*>).map(Any?::toString)

    private fun legacyAsm(): List<Path> {
        val jars = checkNotNull(System.getProperty("kartograph.test.legacyAsm")) { "the test task must provide the legacy ASM jars" }
            .split(File.pathSeparator).filter(String::isNotEmpty).map(Path::of)
        assertEquals(3, jars.size, jars.toString())
        return jars
    }

    private fun kartograph(): List<Path> =
        listOf(ClassFileIndexer::class.java, CodeGraph::class.java, Unit::class.java, KotlinClassMetadata::class.java).map(::location).distinct()

    private fun location(type: Class<*>): Path = Path.of(type.protectionDomain.codeSource.location.toURI())

    private fun urls(paths: List<Path>) = paths.map { it.toUri().toURL() }.toTypedArray()
}
