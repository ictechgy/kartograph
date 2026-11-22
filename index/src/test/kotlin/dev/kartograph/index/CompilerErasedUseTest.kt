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

    @Test fun `JvmOverloads keeps the trailing continuation of suspend functions`(@TempDir root: Path) {
        val owner = "dev/kartograph/index/fixture/jvmoverloadssuspend/SuspendJvmOverloadsFixturesKt"
        val classes = copyFixturePackage(root, "dev/kartograph/index/fixture/jvmoverloadssuspend", "SuspendJvmOverloadsFixturesKt")
        val graph = ClassFileIndexer().indexWithObservations(listOf(classes), emptyList()).graph
        fun node(signature: String) = requireNotNull(graph.node(JvmNodeId.methodId(owner, signature.substringBefore('('), "(" + signature.substringAfter('(')))) { signature }
        val continuation = "Lkotlin/coroutines/Continuation;"
        assertTrue(node("fetch(I$continuation)Ljava/lang/Object;").synthesized, "generated suspend overload keeps the continuation")
        assertTrue(!node("fetch(II$continuation)Ljava/lang/Object;").synthesized, "suspend original")
        // continuation을 앞쪽 인자로 보면 이 직접 작성 확장 함수의 원본이 생성물로 잘못 표시된다.
        assertTrue(!node("fetch(II)Ljava/lang/Object;").synthesized, "authored extension original")
        assertTrue(node("fetch(I)Ljava/lang/Object;").synthesized, "generated extension overload")

        val roots = listOf(RetentionEvidence(JvmNodeId.methodId(owner, "callFetch", "($continuation)Ljava/lang/Object;"), RetentionReason.KEEP_RULE, null))
        assertEquals(
            listOf("method:$owner#fetch(II)Ljava/lang/Object;"),
            DeadFindings.collect(graph, ReachabilityAnalyzer.analyze(graph, roots)).map { it.nodeId.value }.sorted(),
        )
    }

    @Test fun `JvmOverloads does not derive overload names from a mangled original`(@TempDir root: Path) {
        val owner = "dev/kartograph/index/fixture/jvmoverloadsmangled/MangledJvmOverloadsFixturesKt"
        val classes = copyFixturePackage(root, "dev/kartograph/index/fixture/jvmoverloadsmangled", "MangledJvmOverloadsFixturesKt")
        val graph = ClassFileIndexer().indexWithObservations(listOf(classes), emptyList()).graph
        fun node(signature: String) = requireNotNull(graph.node(JvmNodeId.methodId(owner, signature.substringBefore('('), "(" + signature.substringAfter('(')))) { signature }
        val continuation = "Lkotlin/coroutines/Continuation;"
        // 전제: value class 매개변수 때문에 원본 이름이 hash로 바뀌었고, 직접 작성 함수가 @JvmName으로 그 이름을 쓴다.
        assertTrue(!node("load-DBCjkj4(III$continuation)Ljava/lang/Object;").synthesized, "mangled suspend original")
        assertTrue(!node("read-rdVXx4g(III)I").synthesized, "mangled original")
        // 원본 이름으로 계산한 생성 overload와 시그니처가 같은 직접 작성 원본은 생성물로 숨기지 않는다.
        assertTrue(!node("load-DBCjkj4(II$continuation)Ljava/lang/Object;").synthesized, "authored suspend original named like the mangled original")
        assertTrue(!node("read-rdVXx4g(II)I").synthesized, "authored original named like the mangled original")
        // @JvmName 함수의 생성 overload는 같은 이름을 쓰므로 그대로 생성물이다.
        assertTrue(node("load-DBCjkj4(I$continuation)Ljava/lang/Object;").synthesized, "generated overload of the authored suspend function")
        assertTrue(node("read-rdVXx4g(I)I").synthesized, "generated overload of the authored function")
        // mangled 원본의 생성 overload는 각자 다른 hash 이름이라 알아보지 못한다(LIMITATIONS에 적은 보수적 경계).
        val mangledGenerated = listOf(
            "load-rdVXx4g(II$continuation)Ljava/lang/Object;", "load-NUMGiXg(I$continuation)Ljava/lang/Object;",
            "read-NUMGiXg(II)I", "read-hMQyODE(I)I",
        )
        mangledGenerated.forEach { signature -> assertTrue(!node(signature).synthesized, "unrecognised generated overload $signature") }
        // value class 멤버의 `-impl`은 생성 overload도 같은 이름이라 그대로 알아본다.
        val token = "dev/kartograph/index/fixture/jvmoverloadsmangled/Token"
        assertTrue(requireNotNull(graph.node(JvmNodeId.methodId(token, "scaled-impl", "(II)I"))).synthesized, "generated value class member overload")
        assertTrue(!requireNotNull(graph.node(JvmNodeId.methodId(token, "scaled-impl", "(III)I"))).synthesized, "value class member original")

        val roots = listOf(RetentionEvidence(JvmNodeId.methodId(owner, "callLoadAndRead", "($continuation)Ljava/lang/Object;"), RetentionReason.KEEP_RULE, null))
        assertEquals(
            (listOf("load-DBCjkj4(II$continuation)Ljava/lang/Object;", "read-rdVXx4g(II)I") + mangledGenerated).map { "method:$owner#$it" }.sorted(),
            DeadFindings.collect(graph, ReachabilityAnalyzer.analyze(graph, roots)).map { it.nodeId.value }.sorted(),
        )
    }

    @Test fun `JvmOverloads marks only parameter-dropping overloads of the same owner's declaration`(@TempDir root: Path) {
        val owner = "dev/kartograph/index/fixture/jvmstaticoverloads/JvmStaticOverloads"
        val classes = copyFixturePackage(root, "dev/kartograph/index/fixture/jvmstaticoverloads", "JvmStaticOverloads")
        val graph = ClassFileIndexer().indexWithObservations(listOf(classes), emptyList()).graph
        fun synthesized(type: String, name: String, descriptor: String) =
            requireNotNull(graph.node(JvmNodeId.methodId(type, name, descriptor))) { "$type#$name$descriptor" }.synthesized
        // companion metadata가 h(II)I를 선언하므로 매개변수를 뺀 h(I)I만 생성 overload다.
        assertTrue(synthesized("$owner\$Companion", "h", "(I)I"), "companion generated overload")
        assertTrue(!synthesized("$owner\$Companion", "h", "(II)I"), "companion original")
        // 바깥 class metadata에는 companion 함수가 없다. 같은 이름 h(String)이나 다른 이름 other(II)I를 근거로 bridge를 숨기지 않는다.
        assertTrue(!synthesized(owner, "h", "(II)I"), "static bridge of the original")
        assertTrue(!synthesized(owner, "h", "(I)I"), "static bridge of the generated overload")
        assertTrue(!synthesized(owner, "h", "(Ljava/lang/String;)I"), "authored same-name function")
        // 바깥 class가 직접 선언한 h(III)I는 @JvmOverloads가 없으므로 bridge h(II)I·h(I)I의 원본 근거가 아니다.
        assertTrue(!synthesized(owner, "h", "(III)I"), "authored longer same-name function")
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
