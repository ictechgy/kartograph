package dev.kartograph.index

import dev.kartograph.analysis.DeadFindings
import dev.kartograph.analysis.DefaultRetention
import dev.kartograph.analysis.ReachabilityAnalyzer
import dev.kartograph.core.ClassHierarchy
import dev.kartograph.core.CodeGraph
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
