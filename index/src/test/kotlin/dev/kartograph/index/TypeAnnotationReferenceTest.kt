package dev.kartograph.index

import dev.kartograph.analysis.DeadFindings
import dev.kartograph.analysis.PrivateMemberRetention
import dev.kartograph.analysis.ReachabilityAnalyzer
import dev.kartograph.core.CodeGraph
import dev.kartograph.core.EdgeKind
import dev.kartograph.core.EdgeOrigin
import dev.kartograph.core.NodeId
import dev.kartograph.core.RetentionEvidence
import dev.kartograph.core.RetentionReason
import java.io.ByteArrayOutputStream
import java.nio.file.Files
import java.nio.file.Path
import javax.tools.ToolProvider
import kotlin.io.path.createDirectories
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import org.junit.jupiter.api.io.TempDir

/**
 * `TYPE_USE`·`TYPE_PARAMETER` 어노테이션과 record component 어노테이션은 선언 어노테이션과 다른 attribute에 남는다.
 * class 파일이 기록한 이 사용을 어노테이션 class까지 잇는지 실제 javac·kotlinc 산출물로 확인한다.
 */
class TypeAnnotationReferenceTest {
    @Test
    fun `javac type and record component annotations reach their annotation classes from the annotated declaration`(@TempDir root: Path) {
        val classes = compile(root)
        val cache = ClassIndexCache(root.resolve("cache"), "type-annotation-regression")
        val uncached = ClassFileIndexer().indexWithObservations(listOf(classes))
        val cold = ClassFileIndexer(cache).indexWithObservations(listOf(classes))
        val warm = ClassFileIndexer(cache).indexWithObservations(listOf(classes))
        assertEquals(uncached.graph.edges, cold.graph.edges)
        assertEquals(cold.graph.edges, warm.graph.edges)
        assertEquals(cold.statistics.classFiles, warm.statistics.cacheHits)
        val graph = warm.graph

        val holder = JvmNodeId.classId("probe/Holder")
        val body = method("body", "(Ljava/lang/Object;)I")
        for ((source, annotation) in listOf(
            holder to "OnClassTypeParameter",
            holder to "OnBound",
            holder to "OnSupertype",
            JvmNodeId.fieldId("probe/Holder", "names", "Ljava/util/List;") to "OnField",
            JvmNodeId.fieldId("probe/Holder", "valued", "Ljava/util/List;") to "Valued",
            method("first", "(Ljava/util/List;)Ljava/lang/String;") to "OnReturn",
            method("first", "(Ljava/util/List;)Ljava/lang/String;") to "OnParameter",
            method("id", "(Ljava/lang/Object;)Ljava/lang/Object;") to "OnMethodTypeParameter",
            method("risky", "()I") to "OnThrows",
            body to "OnLocal",
            body to "OnResource",
            body to "OnCast",
            body to "OnNew",
            body to "OnInstanceof",
            body to "OnCatch",
            JvmNodeId.classId("probe/Point") to "OnComponentOnly",
        )) {
            assertTrue(edge(graph, source, annotation, EdgeKind.ANNOTATION), "$source -> $annotation")
        }
        assertTrue(edge(graph, JvmNodeId.fieldId("probe/Holder", "valued", "Ljava/util/List;"), "OnlyInTypeAnnotationValue",
            EdgeKind.REFERENCE), "class value of a type annotation")
        // 타입 어노테이션은 선언 어노테이션 목록(keep 규칙의 `@Annotation` 조건이 읽는다)에 들어가지 않는다.
        for (id in listOf(holder, JvmNodeId.classId("probe/Point"), JvmNodeId.fieldId("probe/Holder", "names", "Ljava/util/List;"),
            method("first", "(Ljava/util/List;)Ljava/lang/String;"), body)) {
            assertEquals(emptySet(), requireNotNull(graph.node(id)).annotations, "$id")
        }

        val roots = listOf(RetentionEvidence(JvmNodeId.methodId("probe/Entry", "run", "()I"), RetentionReason.KEEP_RULE, null))
        // 도달하지 않는 member의 어노테이션은 선언 어노테이션(`DeclarationOnUnusedMember`)과 같이 보고된다.
        assertEquals(listOf("DeclarationOnUnusedMember", "NeverUsed", "OnUnusedMember"),
            reported(graph, roots, includePrivateMembers = false))
        // private 모드는 reachable owner의 비-private member를 진입점으로 보존하므로 둘 다 도달한다.
        assertEquals(listOf("NeverUsed"), reported(graph, PrivateMemberRetention.expand(graph, roots), includePrivateMembers = true))
    }

    @Test
    fun `Kotlin type parameter annotations reach their class while metadata-only type annotations stay reported`(@TempDir root: Path) {
        val pkg = "dev/kartograph/index/fixture/typeannotations"
        val anchor = Class.forName(pkg.replace('/', '.') + ".TypeAnnotationFixturesKt")
        val source = Path.of(anchor.protectionDomain.codeSource.location.toURI()).resolve(pkg)
        val classes = root.resolve("classes")
        val target = Files.createDirectories(classes.resolve(pkg))
        Files.list(source).use { files -> files.filter { it.toString().endsWith(".class") }.forEach { Files.copy(it, target.resolve(it.fileName)) } }
        val graph = ClassFileIndexer().indexWithObservations(listOf(classes), emptyList()).graph
        val identity = JvmNodeId.methodId("$pkg/TypeAnnotationHolder", "identity", "(Ljava/lang/Object;)Ljava/lang/Object;")
        assertTrue(graph.outgoingEdgesFrom(identity).any { edge ->
            edge.target == JvmNodeId.classId("$pkg/OnKotlinTypeParameter") && edge.kind == EdgeKind.ANNOTATION && edge.origin == EdgeOrigin.BYTECODE
        }, "kotlinc records type parameter annotations as JVM type annotations")
        val roots = listOf(RetentionEvidence(JvmNodeId.methodId("$pkg/TypeAnnotationFixturesKt", "useTypeAnnotations",
            "(L$pkg/TypeAnnotationHolder;)I"), RetentionReason.KEEP_RULE, null))
        // 타입 사용 위치 어노테이션은 이 build의 kotlinc 설정에서 metadata에만 남아 잇지 못한다(LIMITATIONS의 남은 경계).
        assertEquals(listOf("class:$pkg/OnKotlinType"),
            DeadFindings.collect(graph, ReachabilityAnalyzer.analyze(graph, roots)).map { it.nodeId.value }
                .filter { it.startsWith("class:$pkg/On") })
    }

    private fun method(name: String, descriptor: String): NodeId = JvmNodeId.methodId("probe/Holder", name, descriptor)

    private fun edge(graph: CodeGraph, source: NodeId, target: String, kind: EdgeKind): Boolean =
        graph.outgoingEdgesFrom(source).any { it.target == JvmNodeId.classId("probe/$target") && it.kind == kind }

    private fun reported(graph: CodeGraph, roots: List<RetentionEvidence>, includePrivateMembers: Boolean): List<String> =
        DeadFindings.collect(graph, ReachabilityAnalyzer.analyze(graph, roots), includePrivateMembers)
            .map { it.nodeId.value }.filter { it.startsWith("class:probe/") }.map { it.removePrefix("class:probe/") }.sorted()

    private fun compile(root: Path): Path {
        val source = root.resolve("src/probe/Entry.java")
        Files.createDirectories(source.parent)
        Files.writeString(source, """
            package probe;
            import java.io.IOException;
            import java.io.StringReader;
            import java.lang.annotation.*;
            import java.util.*;
            import static java.lang.annotation.ElementType.*;
            import static java.lang.annotation.RetentionPolicy.*;

            @Target(TYPE_PARAMETER) @Retention(RUNTIME) @interface OnClassTypeParameter {}
            @Target(TYPE_USE) @Retention(RUNTIME) @interface OnBound {}
            @Target(TYPE_USE) @Retention(RUNTIME) @interface OnSupertype {}
            @Target(TYPE_USE) @Retention(RUNTIME) @interface OnField {}
            @Target(TYPE_USE) @Retention(CLASS) @interface OnReturn {}
            @Target(TYPE_USE) @Retention(RUNTIME) @interface OnParameter {}
            @Target(TYPE_PARAMETER) @Retention(RUNTIME) @interface OnMethodTypeParameter {}
            @Target(TYPE_USE) @Retention(RUNTIME) @interface OnThrows {}
            @Target(TYPE_USE) @Retention(RUNTIME) @interface OnLocal {}
            @Target(TYPE_USE) @Retention(RUNTIME) @interface OnResource {}
            @Target(TYPE_USE) @Retention(RUNTIME) @interface OnCast {}
            @Target(TYPE_USE) @Retention(RUNTIME) @interface OnNew {}
            @Target(TYPE_USE) @Retention(RUNTIME) @interface OnInstanceof {}
            @Target(TYPE_USE) @Retention(RUNTIME) @interface OnCatch {}
            @Target(RECORD_COMPONENT) @Retention(RUNTIME) @interface OnComponentOnly {}
            @Target(TYPE_USE) @Retention(RUNTIME) @interface Valued { Class<?> value(); }
            class OnlyInTypeAnnotationValue {}
            @Target(TYPE_USE) @Retention(RUNTIME) @interface OnUnusedMember {}
            @Retention(RUNTIME) @interface DeclarationOnUnusedMember {}
            @Target(TYPE_USE) @Retention(RUNTIME) @interface NeverUsed {}

            public class Entry {
                public int run() throws Exception {
                    Holder<String> holder = new Holder<>();
                    return holder.names.size() + holder.valued.size() + holder.first(List.of("a")).length()
                        + holder.<String>id("b").length() + holder.body("c") + holder.risky() + new Point(1, "d").a();
                }
            }

            class Holder<@OnClassTypeParameter T extends @OnBound Object> extends @OnSupertype Object {
                List<@OnField String> names = new ArrayList<>();
                List<@Valued(OnlyInTypeAnnotationValue.class) String> valued = new ArrayList<>();
                @OnReturn String first(List<@OnParameter String> values) { return values.get(0); }
                <@OnMethodTypeParameter M> M id(M value) { return value; }
                int risky() throws @OnThrows Exception { return 0; }
                int body(Object value) throws IOException {
                    @OnLocal String text = (@OnCast String) value;
                    Object created = new @OnNew Object();
                    boolean string = value instanceof @OnInstanceof String;
                    try (@OnResource StringReader reader = new StringReader(text)) {
                        return reader.read() + created.hashCode() + (string ? 1 : 0);
                    } catch (@OnCatch IllegalStateException failure) {
                        return -1;
                    }
                }
                void unusedMember(@OnUnusedMember String value, @DeclarationOnUnusedMember String other) {}
            }

            record Point(@OnComponentOnly int a, String b) {}
        """.trimIndent())
        val classes = root.resolve("classes").createDirectories()
        val errors = ByteArrayOutputStream()
        assertEquals(0, requireNotNull(ToolProvider.getSystemJavaCompiler()).run(null, null, errors,
            "--release", "17", "-g", "-d", classes.toString(), source.toString()), errors.toString())
        return classes
    }
}
