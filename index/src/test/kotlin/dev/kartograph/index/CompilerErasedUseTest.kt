package dev.kartograph.index

import dev.kartograph.analysis.DeadFindings
import dev.kartograph.analysis.ReachabilityAnalyzer
import dev.kartograph.core.CodeGraph
import dev.kartograph.core.NodeAttribute
import dev.kartograph.core.RetentionEvidence
import dev.kartograph.core.RetentionReason
import java.nio.file.Files
import java.nio.file.Path
import javax.tools.ToolProvider
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import org.junit.jupiter.api.io.TempDir

/**
 * 사용 흔적이 compiler 산출물에서 지워지는 두 형태다. SOURCE retention 어노테이션은 사용처가 class 파일에 없고,
 * `@JvmOverloads` 생성 overload는 Kotlin 호출이 `$default`로 가서 직접 호출이 없다. 둘 다 실제 compiler 산출물로 확인한다.
 */
class CompilerErasedUseTest {
    @Test fun `SOURCE retention annotation classes are counted instead of reported`(@TempDir root: Path) {
        val classes = copyFixturePackage(root, "dev/kartograph/index/fixture/sourceretention", "SourceRetentionFixturesKt")
        compileJava(root, classes, mapOf(
            "fixture/JavaSourceMode" to "@java.lang.annotation.Retention(java.lang.annotation.RetentionPolicy.SOURCE) public @interface JavaSourceMode {}",
            "fixture/JavaRuntimeMode" to "@java.lang.annotation.Retention(java.lang.annotation.RetentionPolicy.RUNTIME) public @interface JavaRuntimeMode {}",
            "fixture/JavaUser" to "public class JavaUser { public static int use(@JavaSourceMode int mode) { return mode; } }",
        ))
        val indexed = ClassFileIndexer().indexWithObservations(listOf(classes), emptyList())
        val marked = indexed.graph.nodes.values.filter { NodeAttribute.SOURCE_RETENTION_ANNOTATION in it.attributes }.map { it.id.value }.sorted()
        assertEquals(listOf("class:dev/kartograph/index/fixture/sourceretention/KotlinSourceMode", "class:fixture/JavaSourceMode"), marked)

        val reported = reportedTypes(indexed.graph, emptyList())
        assertTrue("class:dev/kartograph/index/fixture/sourceretention/UnusedBinaryMode" in reported, "BINARY annotations stay reportable")
        assertTrue("class:fixture/JavaRuntimeMode" in reported, "RUNTIME annotations stay reportable")
        assertTrue(marked.none { it in reported }, "SOURCE annotations are not reported: $reported")
        assertTrue(RuntimeLimitationScanner.scan(indexed, emptyList<Path>()).any {
            it.startsWith("source-retention-annotations: 2 SOURCE-retention annotation class(es)")
        })
    }

    @Test fun `JvmOverloads generated overloads are compiler output while authored overloads stay reportable`(@TempDir root: Path) {
        val owner = "dev/kartograph/index/fixture/jvmoverloads/JvmOverloadsFixturesKt"
        val classes = copyFixturePackage(root, "dev/kartograph/index/fixture/jvmoverloads", "JvmOverloadsFixturesKt")
        val graph = ClassFileIndexer().indexWithObservations(listOf(classes), emptyList()).graph
        fun node(signature: String) = requireNotNull(graph.node(JvmNodeId.methodId(owner, signature.substringBefore('('), "(" + signature.substringAfter('('))))
        assertTrue(node("greet(Ljava/lang/String;)Ljava/lang/String;").synthesized, "generated overload")
        assertTrue(node("unusedOverloaded(I)I").synthesized, "generated overload of an unused function")
        assertTrue(!node("greet(Ljava/lang/String;Ljava/lang/String;)Ljava/lang/String;").synthesized, "original declaration")
        assertTrue(!node("greet(I)Ljava/lang/String;").synthesized, "authored overload with the same name")

        val roots = listOf(RetentionEvidence(JvmNodeId.methodId(owner, "callGreet", "()Ljava/lang/String;"), RetentionReason.KEEP_RULE, null))
        assertEquals(
            listOf("method:$owner#greet(I)Ljava/lang/String;", "method:$owner#unusedOverloaded(II)I"),
            DeadFindings.collect(graph, ReachabilityAnalyzer.analyze(graph, roots)).map { it.nodeId.value }.sorted(),
        )
    }

    private fun reportedTypes(graph: CodeGraph, roots: List<RetentionEvidence>): List<String> =
        DeadFindings.collect(graph, ReachabilityAnalyzer.analyze(graph, roots)).map { it.nodeId.value }.filter { it.startsWith("class:") }

    /** test source set이 compiler로 만든 fixture package의 class 파일만 복사한다. */
    private fun copyFixturePackage(root: Path, packagePath: String, anchorClass: String): Path {
        val anchor = Class.forName(packagePath.replace('/', '.') + "." + anchorClass)
        val source = Path.of(anchor.protectionDomain.codeSource.location.toURI()).resolve(packagePath)
        val classes = root.resolve("classes")
        val target = Files.createDirectories(classes.resolve(packagePath))
        Files.list(source).use { files -> files.filter { it.toString().endsWith(".class") }.forEach { Files.copy(it, target.resolve(it.fileName)) } }
        return classes
    }

    private fun compileJava(root: Path, output: Path, sources: Map<String, String>) {
        val files = sources.map { (name, body) ->
            val file = root.resolve("java-src/$name.java")
            Files.createDirectories(file.parent)
            Files.writeString(file, "package ${name.substringBeforeLast('/').replace('/', '.')}; $body")
            file.toString()
        }
        assertEquals(0, ToolProvider.getSystemJavaCompiler().run(null, null, null, "-g", "-d", output.toString(), *files.toTypedArray()))
    }
}
