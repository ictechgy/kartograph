package dev.kartograph.index

import dev.kartograph.analysis.DeadFindings
import dev.kartograph.analysis.DefaultRetention
import dev.kartograph.analysis.PrivateMemberRetention
import dev.kartograph.analysis.ReachabilityAnalyzer
import dev.kartograph.core.CodeGraph
import dev.kartograph.core.EdgeKind
import dev.kartograph.core.EdgeOrigin
import dev.kartograph.core.NodeAttribute
import dev.kartograph.core.NodeId
import dev.kartograph.core.RetentionEvidence
import dev.kartograph.core.RetentionReason
import java.nio.file.Files
import java.nio.file.Path
import javax.tools.ToolProvider
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable
import org.junit.jupiter.api.io.TempDir

/**
 * 사용 흔적이 compiler 산출물에서 지워지거나 다른 곳으로 옮겨지는 형태다. SOURCE retention 어노테이션은 사용처가 class 파일에
 * 없고, `@JvmOverloads` 생성 overload는 Kotlin 호출이 `$default`로 가서 직접 호출이 없다. suspend inline 함수의 inliner용
 * 사본은 호출되지 않고, property 대상 어노테이션은 호출되지 않는 합성 method에 기록된다. 모두 실제 compiler 산출물로 확인한다.
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

    @Test fun `suspend inline copies for the inliner are compiler output while authored lookalikes stay reportable`(@TempDir root: Path) {
        val owner = "dev/kartograph/index/fixture/suspendinlinecopy/SuspendInlineCopyFixturesKt"
        val classes = copyFixturePackage(root, "dev/kartograph/index/fixture/suspendinlinecopy", "SuspendInlineCopyFixturesKt")
        // metadata가 없는 Java class의 같은 꼴 이름은 근거가 없어 그대로 둔다.
        compileJava(root, classes, mapOf(
            "fixture/JavaCopyLookalike" to "public class JavaCopyLookalike { public static Object doubled(int value, Object continuation) " +
                "{ return value; } private static Object doubled\$\$forInline(int value, Object continuation) { return value; } }",
        ))
        val graph = ClassFileIndexer().indexWithObservations(listOf(classes), emptyList()).graph
        fun synthesized(type: String, signature: String) =
            requireNotNull(graph.node(JvmNodeId.methodId(type, signature.substringBefore('('), "(" + signature.substringAfter('(')))) { signature }.synthesized
        val continuation = "Lkotlin/coroutines/Continuation;"
        assertTrue(synthesized(owner, "doubled\$\$forInline(I$continuation)Ljava/lang/Object;"), "top-level copy")
        assertTrue(synthesized(owner, "renamedTriple\$\$forInline(I$continuation)Ljava/lang/Object;"), "copy of a @JvmName function")
        val doubler = "dev/kartograph/index/fixture/suspendinlinecopy/Doubler"
        assertTrue(synthesized(doubler, "twice\$\$forInline(I$continuation)Ljava/lang/Object;"), "member copy")
        assertTrue(!synthesized(owner, "doubled(I$continuation)Ljava/lang/Object;"), "suspend inline original")
        assertTrue(!synthesized(owner, "quadrupled\$\$forInline(I)I"), "authored lookalike of a non-suspend inline function")
        assertTrue(!synthesized(owner, "plainSuspend\$\$forInline(I$continuation)Ljava/lang/Object;"), "authored lookalike of a non-inline suspend function")
        assertTrue(!synthesized("fixture/JavaCopyLookalike", "doubled\$\$forInline(ILjava/lang/Object;)Ljava/lang/Object;"), "Java lookalike")
        // private suspend inline 함수에는 사본이 없어, 같은 descriptor의 직접 작성 함수가 compile된다. metadata 선언만이 그 근거다.
        assertTrue(!synthesized(owner, "quintupled\$\$forInline(I$continuation)Ljava/lang/Object;"),
            "authored function named like the copy of a private suspend inline function")
        // inline 함수 안 익명 object의 public `run$$forInline`은 원본 `run`이 inline이 아니어서 규칙 밖이다(public이라 보고되지도 않는다).
        assertTrue(!synthesized("$owner\$task\$1", "run\$\$forInline($continuation)Ljava/lang/Object;"), "anonymous object copy outside the rule")

        // CLI `--include-private-members`와 같이 reachable owner의 비-private member를 root로 넓힌다. private suspend lookalike는
        // compiler가 만든 public `access$plainSuspend$$forInline`으로 도달하므로 보고되지 않는다.
        val roots = PrivateMemberRetention.expand(graph,
            listOf(RetentionEvidence(JvmNodeId.methodId(owner, "callAll", "($continuation)Ljava/lang/Object;"), RetentionReason.KEEP_RULE, null)))
        assertEquals(
            listOf("method:$owner#quadrupled\$\$forInline(I)I"),
            DeadFindings.collect(graph, ReachabilityAnalyzer.analyze(graph, roots), includePrivateMembers = true)
                .map { it.nodeId.value }.filter { it.startsWith("method:dev/") }.sorted(),
        )
    }

    @Test fun `annotations of a used Kotlin property are reachable from the property, not through its synthetic holder`(@TempDir root: Path) {
        val pkg = "dev/kartograph/index/fixture/propertyannotations"
        val facade = "$pkg/PropertyAnnotationFixturesKt"
        val holder = "$pkg/Holder"
        val classes = copyFixturePackage(root, pkg, "PropertyAnnotationFixturesKt")
        val graph = ClassFileIndexer().indexWithObservations(listOf(classes), emptyList()).graph
        fun carried(source: NodeId, annotation: String, kind: EdgeKind = EdgeKind.ANNOTATION) = graph.outgoingEdgesFrom(source).any {
            it.target == JvmNodeId.classId("$pkg/$annotation") && it.kind == kind && it.origin == EdgeOrigin.KOTLIN_METADATA
        }
        assertTrue(carried(JvmNodeId.methodId(holder, "getComputed", "()I"), "OnGetterOnly"), "getter")
        assertTrue(carried(JvmNodeId.methodId(holder, "getComputed", "()I"), "OnlyInPropertyValue", EdgeKind.REFERENCE), "annotation value")
        assertTrue(carried(JvmNodeId.methodId(holder, "setSink", "(I)V"), "OnWriteOnly"), "setter")
        assertTrue(carried(JvmNodeId.fieldId(holder, "secret", "I"), "OnPrivateField"), "backing field")
        // 이 build의 기본 `-jvm-default` 설정에서 interface property 합성 method는 DefaultImpls에, `@JvmDefaultWithoutCompatibility`
        // interface는 interface 자신에 있다.
        assertTrue(carried(JvmNodeId.methodId("$pkg/Sized", "getSize", "()I"), "OnInterface"), "interface through DefaultImpls")
        assertTrue(carried(JvmNodeId.methodId("$pkg/Plain", "getPlainUsed", "()I"), "OnPlainUsed"), "interface without DefaultImpls")
        // 어노테이션 class의 `@property:` 매개변수 합성 method는 늘 DefaultImpls에, multi-file `const val` field는 facade에 있다.
        assertTrue(carried(JvmNodeId.methodId("$pkg/Tagged", "level", "()I"), "OnAnnotationParameter"), "annotation class parameter")
        assertTrue(carried(JvmNodeId.fieldId("$pkg/PropertyAnnotationConstants", "MULTIFILE_CONST", "I"), "OnMultifileConst"),
            "multi-file const field in the facade")
        // 합성 method와 그 owner로 가는 간선, 합성 method descriptor의 receiver 형식은 옮기지 않는다.
        val twice = JvmNodeId.methodId(facade, "getTwice", "(L$holder;)I")
        assertTrue(carried(twice, "OnExtension"), "extension getter")
        assertTrue(graph.outgoingEdgesFrom(twice).none { it.origin == EdgeOrigin.KOTLIN_METADATA && it.target == JvmNodeId.classId(holder) },
            "receiver type of the holder descriptor is not carried")
        // DefaultImpls 같은 Kotlin 합성 class가 자기 method를 잇는 기존 간선(class → method)만 남고 property 쪽 간선은 없다.
        assertTrue(graph.edges.none { edge ->
            edge.origin == EdgeOrigin.KOTLIN_METADATA && !edge.source.value.startsWith("class:") &&
                edge.target.value.substringBefore('(').endsWith("\$annotations")
        }, "no property edge reaches a synthetic holder")

        val roots = listOf(RetentionEvidence(JvmNodeId.methodId(facade, "useProperties", "(L$holder;L$pkg/Gauge;L$pkg/Plain;L$pkg/Tagged;)I"),
            RetentionReason.KEEP_RULE, null))
        val result = ReachabilityAnalyzer.analyze(graph, roots)
        // 읽은 property 하나 때문에 DefaultImpls class와 그 안의 다른 합성 method·default 본문 사본까지 도달하면 안 된다.
        assertTrue(JvmNodeId.classId("$pkg/Gauge\$DefaultImpls") !in result.reachableNodeIds, "DefaultImpls stays unreached")
        // CLI처럼 상수 owner 보존 등 기본 보존 정책을 더한다(상수 field가 root가 된다).
        val retained = DefaultRetention.find(graph, roots, emptyList())
        assertEquals(
            listOf("OnGaugeUnusedSibling", "OnPlainUnusedSibling", "OnUnreachableOwner", "OnUnreadGetter", "OnlyInUnusedGaugeDefault",
                "OnlyInUnusedPlainDefault", "Unreachable").map { "class:$pkg/$it" },
            reportedTypes(graph, retained).sorted(),
        )
        // 짝은 class 사실에 저장되고 조립 단계에서 풀리므로 cache miss와 hit가 같은 그래프를 만든다.
        val cache = ClassIndexCache(root.resolve("cache"), "property-annotation-holders")
        val missed = ClassFileIndexer(cache).indexWithObservations(listOf(classes), emptyList())
        val hit = ClassFileIndexer(cache).indexWithObservations(listOf(classes), emptyList())
        assertTrue(missed.statistics.cacheMisses > 0 && hit.statistics.cacheMisses == 0 && hit.statistics.cacheHits > 0, hit.statistics.toString())
        assertEquals(graph.edges, missed.graph.edges, "cache miss")
        assertEquals(graph.edges, hit.graph.edges, "cache hit")
    }

    @Test
    @EnabledIfEnvironmentVariable(named = "KARTOGRAPH_KOTLIN_COMPILER_LIB", matches = ".+")
    fun `property annotations follow the holder owner of every jvm-default mode`(@TempDir root: Path) {
        val source = root.resolve("src/A.kt")
        Files.createDirectories(source.parent)
        Files.writeString(source, """
            package p6
            annotation class AUsed
            annotation class AUnusedSibling
            class OnlyInUnusedDefault { fun go() = 7 }
            interface I {
                @AUsed val used: Int
                @AUnusedSibling val unusedSibling: Int get() = 3
                fun unusedDefault(): Int = OnlyInUnusedDefault().go()
            }
            class Impl : I { override val used = 1 }
            fun use(i: I) = i.used
            annotation class AParam
            annotation class Tagged(@property:AParam val v: Int)
            fun useTag(t: Tagged) = t.v
        """.trimIndent())
        val compilerLib = Path.of(requireNotNull(System.getenv("KARTOGRAPH_KOTLIN_COMPILER_LIB")))
        val javaHome = System.getenv("KARTOGRAPH_JAVA_HOME") ?: System.getProperty("java.home")
        val stdlib = Files.list(compilerLib).use { files -> files.filter { it.fileName.toString().matches(Regex("kotlin-stdlib(-[0-9.]+)?\\.jar")) }.findFirst().orElseThrow() }
        // 세 mode가 interface 합성 method를 DefaultImpls(disable·enable) 또는 interface 자신(no-compatibility)에 두고, 어노테이션 class
        // 매개변수의 합성 method는 늘 DefaultImpls에 둔다.
        listOf("disable", "enable", "no-compatibility").forEach { mode ->
            val classes = Files.createDirectories(root.resolve("classes-$mode"))
            val process = ProcessBuilder("$javaHome/bin/java", "-cp", "$compilerLib/*", "org.jetbrains.kotlin.cli.jvm.K2JVMCompiler",
                "-no-stdlib", "-no-reflect", "-classpath", stdlib.toString(), "-jvm-default=$mode", "-d", classes.toString(), source.toString())
                .redirectErrorStream(true).start()
            val output = process.inputStream.readAllBytes().toString(Charsets.UTF_8)
            assertEquals(0, process.waitFor(), output)
            val graph = ClassFileIndexer().indexWithObservations(listOf(classes), emptyList()).graph
            val roots = listOf("use" to "(Lp6/I;)I", "useTag" to "(Lp6/Tagged;)I").map { (name, descriptor) ->
                RetentionEvidence(JvmNodeId.methodId("p6/AKt", name, descriptor), RetentionReason.KEEP_RULE, null)
            }
            assertEquals(listOf("class:p6/AUnusedSibling", "class:p6/OnlyInUnusedDefault"), reportedTypes(graph, roots).sorted(), mode)
        }
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
