package dev.kartograph.index

import dev.kartograph.analysis.ReachabilityAnalyzer
import dev.kartograph.analysis.DefaultRetention
import dev.kartograph.analysis.InputHints
import dev.kartograph.core.InputHint
import dev.kartograph.core.EdgeOrigin
import dev.kartograph.core.ClassHierarchy
import dev.kartograph.core.HierarchyMethod
import dev.kartograph.core.Visibility
import dev.kartograph.core.GraphEdge
import dev.kartograph.core.EdgeKind
import dev.kartograph.core.ExternalCall
import dev.kartograph.core.InvocationKind
import dev.kartograph.core.CallResolution
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

class AndroidFrameworkFeedbackTest {
    @Test
    fun `private mapper constant retains callback closure independently of inflation call`(@TempDir root: Path) {
        val dependency = compile(root, "dependency", mapOf(
            "android/view/View" to "public class View {}",
            "androidx/databinding/ViewDataBinding" to "public abstract class ViewDataBinding { protected abstract void executeBindings(); }",
            "androidx/databinding/DataBindingComponent" to "public interface DataBindingComponent {}",
            "androidx/databinding/DataBinderMapper" to "public abstract class DataBinderMapper { public abstract ViewDataBinding getDataBinder(DataBindingComponent c, android.view.View v, int id); }",
        ))
        val classes = compile(root, "app", mapOf(
            "fixture/AppMapper" to "public class AppMapper extends androidx.databinding.DataBinderMapper { private static final int LAYOUT_SCREEN = 1; public androidx.databinding.ViewDataBinding getDataBinder(androidx.databinding.DataBindingComponent c, android.view.View v, int id) { return id == LAYOUT_SCREEN ? new ScreenBindingImpl() : null; } }",
            "fixture/ScreenBindingImpl" to "public class ScreenBindingImpl extends androidx.databinding.ViewDataBinding { protected void executeBindings() { Adapters.used(); } }",
            "fixture/OrphanBinding" to "public class OrphanBinding extends androidx.databinding.ViewDataBinding { protected void executeBindings() { Adapters.orphan(); } }",
            "fixture/Adapters" to "public class Adapters { public static void used() {} public static void orphan() {} }",
            "fixture/Entry" to "public class Entry { public static void run() {} }",
        ), dependency)
        val graph = ClassFileIndexer().indexWithObservations(listOf(classes), listOf(dependency)).graph
        val retention = DefaultRetention.find(graph, emptyList(), emptyList())
        val reached = ReachabilityAnalyzer.analyze(graph, retention)
        val binding = JvmNodeId.classId("fixture/ScreenBindingImpl")
        val mapper = JvmNodeId.classId("fixture/AppMapper")
        assertTrue(retention.any { it.nodeId == mapper && it.reason == RetentionReason.INLINE_CONSTANT })
        assertEquals(listOf(mapper, JvmNodeId.methodId("fixture/AppMapper", "getDataBinder",
            "(Landroidx/databinding/DataBindingComponent;Landroid/view/View;I)Landroidx/databinding/ViewDataBinding;"), binding),
            reached.pathFromRootTo(binding))
        assertTrue(JvmNodeId.methodId("fixture/Adapters", "used", "()V") in reached.reachableNodeIds)
        assertFalse(JvmNodeId.classId("fixture/OrphanBinding") in reached.reachableNodeIds)
        assertFalse(JvmNodeId.methodId("fixture/Adapters", "orphan", "()V") in reached.reachableNodeIds)
        // 전체 보존 집합과 특정 Activity 호출에서 시작하는 closure는 다른 질문이다.
        val entry = JvmNodeId.methodId("fixture/Entry", "run", "()V")
        val entryOnly = ReachabilityAnalyzer.analyze(graph, listOf(RetentionEvidence(entry, RetentionReason.KEEP_RULE, null)))
        assertFalse(binding in entryOnly.reachableNodeIds)
        assertFalse(graph.externalCalls.any { it.owner == "androidx/databinding/DataBindingUtil" })
    }

    @Test
    fun `changing callback hierarchy removes old derivations and restores fresh results`(@TempDir root: Path) {
        val dependency = compile(root, "dependency", mapOf(
            "androidx/fragment/app/Fragment" to "public class Fragment { public void onStart() {} }",
        ))
        val classes = compile(root, "app", mapOf(
            "fixture/Screen" to "public class Screen extends androidx.fragment.app.Fragment { public void onStart() {} }",
        ), dependency)
        val indexer = ClassFileIndexer(ClassIndexCache(root.resolve("cache"), "android-hierarchy-regression"))
        val warm = indexer.indexWithObservations(listOf(classes), listOf(dependency))
        val bare = indexer.indexWithObservations(listOf(classes))
        assertTrue(bare.statistics.cacheHits > 0, "raw class cache hits must recompute Android derivation state")
        val entries = mapOf("androidx/fragment/app/Fragment" to setOf("java/lang/Object"))
        val absent = ClassHierarchy(entries, mapOf("androidx/fragment/app/Fragment" to emptyList()))
        val final = ClassHierarchy(entries, mapOf("androidx/fragment/app/Fragment" to listOf(
            HierarchyMethod("onStart", "()V", Visibility.PUBLIC, isFinal = true),
        )))
        for (replacement in listOf(ClassHierarchy.EMPTY, absent, final)) {
            val replaced = warm.withHierarchy(replacement)
            val fresh = bare.withHierarchy(replacement)
            assertEquals(fresh.graph.edges, replaced.graph.edges)
            assertEquals(fresh.graph.externalCalls, replaced.graph.externalCalls)
            val restored = replaced.withHierarchy(warm.hierarchy)
            assertEquals(warm.graph.edges, restored.graph.edges)
            assertEquals(warm.graph.externalCalls, restored.graph.externalCalls)
        }
        assertEquals(warm.graph.edges, warm.withHierarchy(warm.hierarchy).graph.edges,
            "repeating the same hierarchy must not multiply callback edge weights")
        val cachedWarm = indexer.indexWithObservations(listOf(classes), listOf(dependency))
        assertTrue(cachedWarm.statistics.cacheHits > 0)
        assertEquals(bare.graph.edges, cachedWarm.withHierarchy(ClassHierarchy.EMPTY).graph.edges)

        val owner = JvmNodeId.classId("fixture/Screen")
        val callback = JvmNodeId.methodId("fixture/Screen", "onStart", "()V")
        val otherEdges = listOf(
            GraphEdge(owner, callback, EdgeKind.REFERENCE, weight = 4, origin = EdgeOrigin.RUNTIME_MODEL),
            GraphEdge(callback, owner, EdgeKind.REFERENCE, weight = 2, origin = EdgeOrigin.RUNTIME_MODEL),
        )
        val otherCall = ExternalCall(callback, "library/Runtime", "invoke", "()V", InvocationKind.STATIC,
            resolvedTargets = listOf(callback), resolution = CallResolution.RUNTIME_MODEL, model = "other-runtime-model")
        val seed = bare.graph.enrichedWith(otherEdges, bare.graph.externalCalls + otherCall)
        val enriched = AndroidFrameworkCallbacks.enrich(seed, warm.hierarchy)
        val cleared = enriched.derivation.removeFrom(enriched.graph)
        assertEquals(seed.edges, cleared.edges, "same-signature weights from another runtime model must survive")
        assertEquals(seed.externalCalls, cleared.externalCalls, "unrelated runtime call facts must survive")
    }

    @Test
    fun `generic binding entry follows captured root mapper and concrete binding callback`(@TempDir root: Path) {
        val dependency = compile(root, "dependency", mapOf(
            "android/app/Activity" to "public class Activity {}",
            "android/view/View" to "public class View {}",
            "androidx/databinding/ViewDataBinding" to "public abstract class ViewDataBinding { protected abstract void executeBindings(); }",
            "androidx/databinding/DataBindingComponent" to "public interface DataBindingComponent {}",
            "androidx/databinding/DataBinderMapper" to "public abstract class DataBinderMapper { public abstract ViewDataBinding getDataBinder(DataBindingComponent c, android.view.View v, int id); }",
            "androidx/databinding/MergedDataBinderMapper" to "public class MergedDataBinderMapper extends DataBinderMapper { public void addMapper(DataBinderMapper m) {} public ViewDataBinding getDataBinder(DataBindingComponent c, android.view.View v, int id) { return null; } }",
            "androidx/databinding/DataBindingUtil" to "public class DataBindingUtil { public static <T extends ViewDataBinding> T setContentView(android.app.Activity a, int layout) { return null; } }",
        ))
        val classes = compile(root, "app", mapOf(
            "androidx/databinding/DataBinderMapperImpl" to "public class DataBinderMapperImpl extends MergedDataBinderMapper { public DataBinderMapperImpl() { addMapper(new fixture.AppMapper()); } }",
            "fixture/AppMapper" to "public class AppMapper extends androidx.databinding.DataBinderMapper { public androidx.databinding.ViewDataBinding getDataBinder(androidx.databinding.DataBindingComponent c, android.view.View v, int id) { return internalGetViewDataBinding(); } private androidx.databinding.ViewDataBinding internalGetViewDataBinding() { return new ScreenBindingImpl(); } }",
            "fixture/ScreenBindingImpl" to "public class ScreenBindingImpl extends androidx.databinding.ViewDataBinding { protected void executeBindings() { Adapters.used(); } }",
            "fixture/Adapters" to "public class Adapters { public static void used() {} public static void unused() {} }",
            "fixture/Entry" to "public class Entry { public static void run(android.app.Activity a) { androidx.databinding.DataBindingUtil.setContentView(a, 1); } }",
        ), dependency)
        val indexed = ClassFileIndexer().indexWithObservations(listOf(classes), listOf(dependency))
        val graph = indexed.graph
        val entry = JvmNodeId.methodId("fixture/Entry", "run", "(Landroid/app/Activity;)V")
        val used = JvmNodeId.methodId("fixture/Adapters", "used", "()V")
        val reached = ReachabilityAnalyzer.analyze(graph, listOf(RetentionEvidence(entry, RetentionReason.KEEP_RULE, null)))
        assertTrue(used in reached.reachableNodeIds)
        assertFalse(JvmNodeId.methodId("fixture/Adapters", "unused", "()V") in reached.reachableNodeIds)
        assertFalse(used in ReachabilityAnalyzer.analyze(graph, emptyList()).reachableNodeIds)
        assertTrue(graph.externalCalls.any { it.caller == entry && it.model == "android-data-binding-mapper" })
        val limitations = RuntimeLimitationScanner.scan(ClassFileIndexer().indexWithObservations(listOf(classes), listOf(dependency)), emptyList<Path>())
        assertTrue(limitations.any { it.startsWith("data-binding-selection-partial:") && "1 with a captured root mapper" in it })
        val replaced = indexed.withHierarchy(ClassHierarchy.EMPTY)
        val bare = ClassFileIndexer().indexWithObservations(listOf(classes))
        assertEquals(bare.graph.edges, replaced.graph.edges)
        assertEquals(bare.graph.externalCalls, replaced.graph.externalCalls,
            "removed mapper hierarchy must clear its modeled call metadata")
        val restored = replaced.withHierarchy(indexed.hierarchy)
        assertEquals(graph.edges, restored.graph.edges)
        assertEquals(graph.externalCalls, restored.graph.externalCalls)
        val otherCalls = bare.graph.externalCalls.map { call ->
            if (call.owner == "androidx/databinding/DataBindingUtil") call.copy(
                resolvedTargets = listOf(used), resolution = CallResolution.RUNTIME_MODEL, model = "other-binding-model",
            ) else call
        }
        val protected = bare.graph.enrichedWith(emptyList(), otherCalls)
        val protectedEnrichment = AndroidFrameworkCallbacks.enrich(protected, indexed.hierarchy)
        assertEquals(otherCalls, protectedEnrichment.graph.externalCalls,
            "an existing independent model must not be overwritten by the Android mapper model")
        assertEquals(protected.edges, protectedEnrichment.derivation.removeFrom(protectedEnrichment.graph).edges)
        Files.delete(classes.resolve("androidx/databinding/DataBinderMapperImpl.class"))
        val noMapper = ClassFileIndexer().indexWithObservations(listOf(classes), listOf(dependency))
        assertFalse(used in ReachabilityAnalyzer.analyze(noMapper.graph, listOf(RetentionEvidence(entry, RetentionReason.KEEP_RULE, null))).reachableNodeIds)
        assertTrue(RuntimeLimitationScanner.scan(noMapper, emptyList<Path>()).any {
            it.startsWith("data-binding-selection-partial:") && "0 with a captured root mapper" in it
        })
    }

    @Test
    fun `Hilt entry annotation identifies exact generated sibling and pre transform input`(@TempDir root: Path) {
        val dependency = compile(root, "dependency", mapOf(
            "dagger/hilt/android/AndroidEntryPoint" to "@java.lang.annotation.Retention(java.lang.annotation.RetentionPolicy.CLASS) public @interface AndroidEntryPoint {}",
        ))
        val classes = compile(root, "app", mapOf(
            "fixture/Screen" to "@dagger.hilt.android.AndroidEntryPoint public class Screen {}",
            "fixture/Hilt_Screen" to "public class Hilt_Screen {}",
            "fixture/Hilt_Unrelated" to "public class Hilt_Unrelated {}",
        ), dependency)
        val graph = ClassFileIndexer().index(listOf(classes))
        assertTrue(graph.nodes.getValue(JvmNodeId.classId("fixture/Hilt_Screen")).synthesized)
        assertFalse(graph.nodes.getValue(JvmNodeId.classId("fixture/Hilt_Unrelated")).synthesized)
        assertTrue(InputHint.HILT_TRANSFORM_NOT_OBSERVED in InputHints.detect(1, 1, 1, graph))
        val transformed = compile(root, "post", mapOf(
            "fixture/Screen" to "@dagger.hilt.android.AndroidEntryPoint public class Screen extends Hilt_Screen {}",
            "fixture/Hilt_Screen" to "public class Hilt_Screen {}",
        ), dependency)
        assertFalse(InputHint.HILT_TRANSFORM_NOT_OBSERVED in InputHints.detect(1, 1, 1, ClassFileIndexer().index(listOf(transformed))))
    }

    @Test
    fun `unmarked binding and navigation contracts require generated root provenance`(@TempDir root: Path) {
        val dependency = compile(root, "dependency", mapOf("androidx/navigation/NavArgs" to "public interface NavArgs {}"))
        val classes = compile(root, "app", mapOf(
            "fixture/ScreenArgs" to "public class ScreenArgs implements androidx.navigation.NavArgs {}",
            "fixture/UserBindingImpl" to "public class UserBindingImpl {}",
            "fixture/UserDirections" to "public class UserDirections {}",
        ), dependency)
        val graph = ClassFileIndexer().index(listOf(classes))
        assertFalse(graph.nodes.getValue(JvmNodeId.classId("fixture/ScreenArgs")).synthesized)
        assertFalse(graph.nodes.getValue(JvmNodeId.classId("fixture/UserBindingImpl")).synthesized)
        assertFalse(graph.nodes.getValue(JvmNodeId.classId("fixture/UserDirections")).synthesized)
        assertTrue(InputHint.ANDROID_GENERATED_PROVENANCE_UNVERIFIED in InputHints.detect(1, 1, 1, graph))
        val marked = ClassFileIndexer().indexWithObservations(listOf(classes), null, emptyList(), listOf(classes)).graph
        assertTrue(marked.nodes.values.all { it.synthesized })
        assertFalse(InputHint.ANDROID_GENERATED_PROVENANCE_UNVERIFIED in InputHints.detect(1, 1, 1, marked))
    }

    @Test
    fun `reachable Fragment owner reaches only proven library callbacks`(@TempDir root: Path) {
        val dependency = compile(root, "dependency", mapOf(
            "android/view/View" to "public class View {}",
            "android/os/Bundle" to "public class Bundle {}",
            "androidx/fragment/app/Fragment" to "public class Fragment { public void onViewCreated(android.view.View view, android.os.Bundle state) {} }",
            "library/BaseFragment" to "public class BaseFragment extends androidx.fragment.app.Fragment {}",
        ))
        val classes = compile(root, "app", mapOf(
            "fixture/Screen" to "public class Screen extends library.BaseFragment { public void onViewCreated(android.view.View view, android.os.Bundle state) { Helper.used(); } public void onViewCreated(int other) { Helper.unused(); } public void unrelated() { Helper.unused(); } }",
            "fixture/UnusedScreen" to "public class UnusedScreen extends library.BaseFragment { public void onViewCreated(android.view.View view, android.os.Bundle state) { Helper.unused(); } }",
            "fixture/Helper" to "public class Helper { public static void used() {} public static void unused() {} }",
        ), dependency)
        val graph = ClassFileIndexer().indexWithObservations(listOf(classes), listOf(dependency)).graph
        val owner = JvmNodeId.classId("fixture/Screen")
        val reached = ReachabilityAnalyzer.analyze(graph, listOf(RetentionEvidence(owner, RetentionReason.KEEP_RULE, null)))
        val callback = JvmNodeId.methodId("fixture/Screen", "onViewCreated", "(Landroid/view/View;Landroid/os/Bundle;)V")
        assertTrue(callback in reached.reachableNodeIds)
        assertTrue(JvmNodeId.methodId("fixture/Helper", "used", "()V") in reached.reachableNodeIds)
        assertFalse(JvmNodeId.methodId("fixture/Helper", "unused", "()V") in reached.reachableNodeIds)
        assertTrue(graph.edges.any { it.source == owner && it.target == callback && it.origin == EdgeOrigin.RUNTIME_MODEL })
        val withoutHeaders = ClassFileIndexer().index(listOf(classes))
        assertFalse(withoutHeaders.edges.any { it.source == owner && it.target == callback && it.origin == EdgeOrigin.RUNTIME_MODEL })
        assertTrue(ClassFileIndexer().indexWithObservations(listOf(classes)).withHierarchy(
            ClassFileIndexer().indexWithObservations(listOf(classes), listOf(dependency)).hierarchy,
        ).graph.edges.any { it.source == owner && it.target == callback && it.origin == EdgeOrigin.RUNTIME_MODEL })
    }

    @Test
    fun `binding execute callback restores compiled selected adapter but not overload or unused adapter`(@TempDir root: Path) {
        val dependency = compile(root, "dependency", mapOf(
            "androidx/databinding/ViewDataBinding" to "public abstract class ViewDataBinding { protected abstract void executeBindings(); }",
        ))
        val classes = compile(root, "app", mapOf(
            "fixture/ScreenBinding" to "public abstract class ScreenBinding extends androidx.databinding.ViewDataBinding {}",
            "fixture/ScreenBindingImpl" to "public class ScreenBindingImpl extends ScreenBinding { protected void executeBindings() { Adapters.bind(1); } public void unrelated() { Adapters.bind(\"unused\"); } }",
            "fixture/UnusedBindingImpl" to "public class UnusedBindingImpl extends ScreenBinding { protected void executeBindings() { Adapters.unused(); } }",
            "fixture/Adapters" to "public class Adapters { public static void bind(int value) {} public static void bind(String value) {} public static void unused() {} }",
        ), dependency)
        val graph = ClassFileIndexer().indexWithObservations(listOf(classes), listOf(dependency)).graph
        val owner = JvmNodeId.classId("fixture/ScreenBindingImpl")
        val reached = ReachabilityAnalyzer.analyze(graph, listOf(RetentionEvidence(owner, RetentionReason.KEEP_RULE, null)))
        assertTrue(JvmNodeId.methodId("fixture/ScreenBindingImpl", "executeBindings", "()V") in reached.reachableNodeIds)
        assertTrue(JvmNodeId.methodId("fixture/Adapters", "bind", "(I)V") in reached.reachableNodeIds)
        assertFalse(JvmNodeId.methodId("fixture/Adapters", "bind", "(Ljava/lang/String;)V") in reached.reachableNodeIds)
        assertFalse(JvmNodeId.methodId("fixture/Adapters", "unused", "()V") in reached.reachableNodeIds)
        assertFalse(graph.nodes.getValue(owner).synthesized, "inheritance and a generated-looking name do not prove generated provenance")
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
