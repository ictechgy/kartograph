package dev.kartograph.index

import dev.kartograph.analysis.DeadFindings
import dev.kartograph.analysis.DefaultRetention
import dev.kartograph.analysis.ReachabilityAnalyzer
import dev.kartograph.core.ClassHierarchy
import dev.kartograph.core.CodeGraph
import dev.kartograph.core.EdgeOrigin
import dev.kartograph.core.RetentionEvidence
import dev.kartograph.core.RetentionReason
import java.nio.file.Files
import java.nio.file.Path
import javax.tools.ToolProvider
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import org.junit.jupiter.api.io.TempDir

/**
 * 라이브러리 body가 부르는 재정의(`Runnable.run`, `Comparator.compare`, 외부 abstract callback)는 소유 class가 도달할 때만
 * 도달해야 한다. 기본(class-only)과 private 모드의 class finding이 같아야 하고, 쓰이지 않는 구현과 재정의가 아닌 overload는
 * 계속 보고된다.
 */
class LibraryOverrideCallbackTest {
    @Test fun `indexing defers library callback edges into the dispatch rebuild without changing the graph`(@TempDir root: Path) {
        val dependency = compile(root, "dependency", mapOf(
            "lib/Callback" to "public abstract class Callback { public abstract void onEvent(); }",
        ))
        val classes = compile(root, "app", mapOf(
            "fixture/Listener" to "public class Listener extends lib.Callback { public void onEvent() { new Handled(); } " +
                "public String toString() { return \"listener\"; } }",
            "fixture/Job" to "public class Job implements Runnable { public void run() { lib.Callback c = new Listener(); c.onEvent(); } }",
            "fixture/Handled" to "public class Handled {}",
        ), dependency)
        // 인덱싱 경로는 라이브러리 callback 간선을 dispatch 재구성에 함께 넣고, withHierarchy 경로는 먼저 적용한 뒤 dispatch를 돈다.
        val deferred = ClassFileIndexer().indexWithObservations(listOf(classes), listOf(dependency))
        val applied = ClassFileIndexer().indexWithObservations(listOf(classes), emptyList()).withHierarchy(deferred.hierarchy)
        assertTrue(deferred.graph.edges.any { it.origin == EdgeOrigin.LIBRARY_CALLBACK_MODEL })
        assertTrue(deferred.graph.edges.any { it.origin == EdgeOrigin.DISPATCH_MODEL })
        assertEquals(applied.graph.edges, deferred.graph.edges)
        assertEquals(applied.graph.externalCalls, deferred.graph.externalCalls)
    }

    @Test fun `overrides of library methods are reached only through reached owners in both modes`(@TempDir root: Path) {
        val dependency = compile(root, "dependency", mapOf(
            "lib/Callback" to "public abstract class Callback { public abstract void onEvent(); protected void onDone(String value) {} " +
                "public final void fire() {} public static void helper() {} void hidden() {} }",
        ))
        val classes = compile(root, "app", mapOf(
            "fixture/Starter" to "public class Starter { public void start() { new Thread(new Job()).start(); " +
                "java.util.Collections.sort(new java.util.ArrayList<Integer>(), new ByValue()); register(new Listener()); } " +
                "static void register(lib.Callback callback) {} }",
            "fixture/Job" to "public class Job implements Runnable { public void run() { new Worker(); } public void run(int wrong) { new Wrong(); } }",
            "fixture/ByValue" to "public class ByValue implements java.util.Comparator<Integer> { " +
                "public int compare(Integer left, Integer right) { new Comparison(); return 0; } }",
            "fixture/Listener" to "public class Listener extends lib.Callback { public void onEvent() { new Handled(); } " +
                "protected void onDone(String value) { new Done(); } void hidden() { new Wrong(); } }",
            "fixture/UnusedJob" to "public class UnusedJob implements Runnable { public void run() { new OnlyFromUnused(); } }",
            "fixture/Worker" to "public class Worker {}",
            "fixture/Comparison" to "public class Comparison {}",
            "fixture/Handled" to "public class Handled {}",
            "fixture/Done" to "public class Done {}",
            "fixture/Wrong" to "public class Wrong {}",
            "fixture/OnlyFromUnused" to "public class OnlyFromUnused {}",
        ), dependency)
        val indexed = ClassFileIndexer().indexWithObservations(listOf(classes), listOf(dependency))
        val roots = listOf(
            RetentionEvidence(JvmNodeId.classId("fixture/Starter"), RetentionReason.KEEP_RULE, null),
            RetentionEvidence(JvmNodeId.methodId("fixture/Starter", "start", "()V"), RetentionReason.KEEP_RULE, null),
        )
        // 재정의가 아닌 overload·다른 package의 package-private 메서드는 기본 모드에서 진입점이 아니다. private 모드는 원래
        // 도달한 owner의 비private member 전부를 root로 삼으므로 Wrong도 살린다(기존 계약).
        assertEquals(listOf("fixture/OnlyFromUnused", "fixture/UnusedJob", "fixture/Wrong"),
            reportedClasses(indexed.graph, indexed.hierarchy, roots, privateMembers = false))
        assertEquals(listOf("fixture/OnlyFromUnused", "fixture/UnusedJob"),
            reportedClasses(indexed.graph, indexed.hierarchy, roots, privateMembers = true))

        // header가 없으면 잇지 않는다(정확한 header 요구). 대신 한계가 그 타입 수를 센다.
        val missing = indexed.withHierarchy(ClassHierarchy.EMPTY)
        val reachedWithoutHeaders = ReachabilityAnalyzer.analyze(missing.graph, roots).reachableNodeIds
        assertFalse(JvmNodeId.classId("fixture/Worker") in reachedWithoutHeaders)
        assertTrue(AndroidFrameworkCallbacks.missingLibraryHeaderTypes(missing.graph, ClassHierarchy.EMPTY) > 0)
        assertEquals(0, AndroidFrameworkCallbacks.missingLibraryHeaderTypes(indexed.graph, indexed.hierarchy))
        assertEquals(indexed.graph.edges, missing.withHierarchy(indexed.hierarchy).graph.edges)
    }

    @Test fun `object contract overrides are reached through their reached owner`(@TempDir root: Path) {
        // 그래프의 supertypes는 java/lang/Object를 생략하므로 Object 계약은 외부 상위 타입 대조로는 잇지 못한다.
        val classes = compile(root, "app", mapOf(
            "fixture/Starter" to "public class Starter { public void start() { new Money(); } }",
            "fixture/Money" to "public class Money { public boolean equals(Object other) { new EqualsHelper(); return false; } " +
                "public int hashCode() { new HashHelper(); return 0; } public String toString() { new TextHelper(); return \"\"; } " +
                "public boolean equals(Money other) { new Wrong(); return false; } }",
            "fixture/EqualsHelper" to "public class EqualsHelper {}",
            "fixture/HashHelper" to "public class HashHelper {}",
            "fixture/TextHelper" to "public class TextHelper {}",
            "fixture/Wrong" to "public class Wrong {}",
        ))
        val indexed = ClassFileIndexer().indexWithObservations(listOf(classes), emptyList())
        val roots = listOf(
            RetentionEvidence(JvmNodeId.classId("fixture/Starter"), RetentionReason.KEEP_RULE, null),
            RetentionEvidence(JvmNodeId.methodId("fixture/Starter", "start", "()V"), RetentionReason.KEEP_RULE, null),
        )
        // Object 계약이 아닌 overload(equals(Money))는 진입점이 아니다.
        assertEquals(listOf("fixture/Wrong"), reportedClasses(indexed.graph, indexed.hierarchy, roots, privateMembers = false))
        val money = JvmNodeId.classId("fixture/Money")
        val library = indexed.graph.edges.filter { it.source == money && it.origin == EdgeOrigin.LIBRARY_CALLBACK_MODEL }
            .map { it.target.value.substringAfter('#') }.sorted()
        assertEquals(listOf("equals(Ljava/lang/Object;)Z", "hashCode()I", "toString()Ljava/lang/String;"), library)
        assertTrue(indexed.graph.edges.none { it.source == money && it.origin == EdgeOrigin.RUNTIME_MODEL })
    }

    @Test fun `library overrides that are also framework callbacks keep only the framework edge`(@TempDir root: Path) {
        // 명시 framework 콜백(Activity.onCreate)은 framework가 부른다는 모델이다. 같은 재정의를 라이브러리 후보로 한 번 더 잇지 않는다.
        val dependency = compile(root, "dependency", mapOf(
            "android/app/Activity" to "public class Activity { protected void onCreate(android.os.Bundle state) {} public void onEvent() {} }",
            "android/os/Bundle" to "public class Bundle {}",
        ))
        val classes = compile(root, "app", mapOf(
            "fixture/Screen" to "public class Screen extends android.app.Activity { protected void onCreate(android.os.Bundle state) {} " +
                "public void onEvent() {} }",
        ), dependency)
        val indexed = ClassFileIndexer().indexWithObservations(listOf(classes), listOf(dependency))
        val screen = JvmNodeId.classId("fixture/Screen")
        val onCreate = JvmNodeId.methodId("fixture/Screen", "onCreate", "(Landroid/os/Bundle;)V")
        val onEvent = JvmNodeId.methodId("fixture/Screen", "onEvent", "()V")
        // class → 자기 member bytecode 간선은 별개다. 모델 간선의 출처만 본다.
        val models = setOf(EdgeOrigin.RUNTIME_MODEL, EdgeOrigin.LIBRARY_CALLBACK_MODEL)
        val origins = indexed.graph.edges.filter { it.source == screen && it.target in setOf(onCreate, onEvent) && it.origin in models }
            .map { it.target to it.origin }.toSet()
        assertEquals(setOf(onCreate to EdgeOrigin.RUNTIME_MODEL, onEvent to EdgeOrigin.LIBRARY_CALLBACK_MODEL), origins)
    }

    @Test fun `library header limitation names the uncovered types`(@TempDir root: Path) {
        val dependency = compile(root, "dependency", mapOf("lib/Base" to "public abstract class Base { public abstract void call(); }"))
        val classes = compile(root, "app", mapOf("fixture/Impl" to "public class Impl extends lib.Base { public void call() {} }"), dependency)
        val withoutHeaders = ClassFileIndexer().indexWithObservations(listOf(classes), emptyList())
        assertTrue(RuntimeLimitationScanner.scan(withoutHeaders, emptyList<Path>()).any { it.startsWith("library-callback-headers: 1 project type(s)") })
        val withHeaders = ClassFileIndexer().indexWithObservations(listOf(classes), listOf(dependency))
        assertTrue(RuntimeLimitationScanner.scan(withHeaders, emptyList<Path>()).none { it.startsWith("library-callback-headers:") })
    }

    private fun reportedClasses(graph: CodeGraph, hierarchy: ClassHierarchy, roots: List<RetentionEvidence>, privateMembers: Boolean): List<String> {
        val retention = DefaultRetention.find(graph, roots, emptyList(), hierarchy, privateMembers)
        val reachability = ReachabilityAnalyzer.analyze(graph, retention)
        return DeadFindings.collect(graph, reachability, privateMembers).map { it.nodeId.value }
            .filter { it.startsWith("class:") }.map { it.removePrefix("class:") }.sorted()
    }

    private fun compile(root: Path, name: String, sources: Map<String, String>, classpath: Path? = null): Path {
        val output = Files.createDirectories(root.resolve("$name-classes"))
        val files = sources.map { (source, body) ->
            val file = root.resolve("$name-src/$source.java")
            Files.createDirectories(file.parent)
            Files.writeString(file, "package ${source.substringBeforeLast('/').replace('/', '.')}; $body")
            file.toString()
        }
        val options = listOf("-g", "-d", output.toString()) +
            (classpath?.let { listOf("-classpath", it.toString()) } ?: emptyList()) + files
        assertEquals(0, ToolProvider.getSystemJavaCompiler().run(null, null, null, *options.toTypedArray()))
        return output
    }
}
