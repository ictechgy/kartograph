package dev.kartograph.index

import dev.kartograph.analysis.ReachabilityAnalyzer
import dev.kartograph.core.*
import java.nio.file.Files
import java.nio.file.Path
import javax.tools.ToolProvider
import kotlin.test.*
import org.junit.jupiter.api.io.TempDir

class AndroidAdditionalCallbackTest {
    @Test fun `activity adapter and click callbacks require exact headers and reached owners`(@TempDir root: Path) {
        val dependency = compile(root, "dependency", mapOf(
            "android/os/Bundle" to "public class Bundle {}",
            "android/app/Activity" to "public class Activity { protected void onCreate(android.os.Bundle state) {} public static void onCreate(long ignored) {} }",
            "android/view/View" to "public class View { public interface OnClickListener { void onClick(View view); } }",
            "androidx/recyclerview/widget/RecyclerView" to "public class RecyclerView { public static class ViewHolder {} public abstract static class Adapter<VH extends ViewHolder> { public abstract void onBindViewHolder(VH holder, int position); } }",
        ))
        val classes = compile(root, "app", mapOf(
            "fixture/Screen" to "public class Screen extends android.app.Activity { protected void onCreate(android.os.Bundle state) { Used.activity(); } public void onCreate(int wrong) { Used.wrong(); } public static void onCreate(long ignored) { Used.wrong(); } }",
            "fixture/Rows" to "public class Rows extends androidx.recyclerview.widget.RecyclerView.Adapter<androidx.recyclerview.widget.RecyclerView.ViewHolder> { public void onBindViewHolder(androidx.recyclerview.widget.RecyclerView.ViewHolder holder, int position) { Used.adapter(); } public void onBindViewHolder(int wrong) { Used.wrong(); } }",
            "fixture/Click" to "public class Click implements android.view.View.OnClickListener { public void onClick(android.view.View view) { Used.click(); } public void onClick(int wrong) { Used.wrong(); } }",
            "fixture/NotListener" to "public class NotListener { public void onClick(android.view.View view) { Used.wrong(); } }",
            "fixture/UnusedClick" to "public class UnusedClick implements android.view.View.OnClickListener { public void onClick(android.view.View view) { Used.unused(); } }",
            "fixture/Used" to "public class Used { public static void activity() {} public static void adapter() {} public static void click() {} public static void wrong() {} public static void unused() {} }",
        ), dependency)
        val indexed = ClassFileIndexer().indexWithObservations(listOf(classes), listOf(dependency))
        val roots = listOf("Screen", "Rows", "Click", "NotListener").map {
            RetentionEvidence(JvmNodeId.classId("fixture/$it"), RetentionReason.KEEP_RULE, null)
        }
        fun reached(graph: CodeGraph) = ReachabilityAnalyzer.analyze(graph, roots).reachableNodeIds
        val used = listOf("activity", "adapter", "click").map { JvmNodeId.methodId("fixture/Used", it, "()V") }
        assertTrue(used.all { it in reached(indexed.graph) })
        assertFalse(JvmNodeId.methodId("fixture/Used", "wrong", "()V") in reached(indexed.graph))
        assertFalse(JvmNodeId.methodId("fixture/Used", "unused", "()V") in reached(indexed.graph))
        assertTrue(ReachabilityAnalyzer.analyze(indexed.graph, emptyList()).reachableNodeIds.isEmpty())
        val missing = indexed.withHierarchy(ClassHierarchy.EMPTY)
        assertTrue(used.none { it in reached(missing.graph) })
        assertEquals(indexed.graph.edges, missing.withHierarchy(indexed.hierarchy).graph.edges)
        val finalContracts = ClassHierarchy(mapOf("android/app/Activity" to emptySet()), mapOf(
            "android/app/Activity" to listOf(HierarchyMethod("onCreate", "(Landroid/os/Bundle;)V", Visibility.PROTECTED, isFinal = true)),
        ))
        assertTrue(used.none { it in reached(indexed.withHierarchy(finalContracts).graph) })
    }

    private fun compile(root: Path, name: String, sources: Map<String, String>, classpath: Path? = null): Path {
        val output = Files.createDirectories(root.resolve("$name-classes"))
        val files = sources.map { (name, body) ->
            val file = root.resolve("$name.java")
            Files.createDirectories(file.parent)
            Files.writeString(file, "package ${name.substringBeforeLast('/').replace('/', '.')}; $body")
            file.toString()
        }
        val options = listOf("-g", "-d", output.toString()) +
            (classpath?.let { listOf("-classpath", it.toString()) } ?: emptyList()) + files
        assertEquals(0, ToolProvider.getSystemJavaCompiler().run(null, null, null, *options.toTypedArray()))
        return output
    }
}
