package dev.kartograph.cli

import dev.kartograph.core.*
import dev.kartograph.export.McpJsonCodec
import dev.kartograph.index.ClassFileIndexer
import java.io.ByteArrayOutputStream
import java.io.PrintStream
import java.nio.file.Files
import java.nio.file.Path
import javax.tools.ToolProvider
import kotlin.test.*
import org.junit.jupiter.api.io.TempDir

class AndroidBindingUncertaintyPropagationTest {
    @Test
    fun `native cross file callback calls stay unreachable but carry review provenance to helpers`(@TempDir root: Path) {
        val classes = fixture(root)
        val graph = ClassFileIndexer().index(listOf(classes))
        val adapter = "method:fixture/Adapters#item(Lfixture/Widget;I)V"
        val helper = "method:fixture/Helper#run()V"
        val tail = "method:fixture/Tail#run()V"
        val call = graph.outgoingEdgesFrom(NodeId(adapter)).single { it.target == NodeId(helper) && it.kind == EdgeKind.CALL }
        assertEquals(EdgeOrigin.BYTECODE, call.origin)
        assertTrue(call.callSiteLines.isNotEmpty())
        assertNotEquals(graph.node(NodeId(adapter))?.location?.path, graph.node(NodeId(helper))?.location?.path)
        val inputs = arrayOf("--project", root.toString(), "--classes", classes.toString(),
            "--manifest", "AndroidManifest.xml", "--resources", "res", "--namespace", "fixture")
        for (id in listOf(helper, tail, "class:fixture/Helper", "class:fixture/Tail")) {
            val why = run("why", id, *inputs)
            assertContains(why, "state\tunreachable")
            assertContains(why, "confidence\tneeds-runtime-review")
            assertContains(why, "binding-uncertainty\t$adapter")
            assertContains(why, "binding-uncertainty-call\t$adapter\t$helper\tBYTECODE")
            assertFalse(why.lineSequence().any { it.startsWith("reason\t") || it.startsWith("path\t") })
        }
        val unrelated = run("why", "method:fixture/Unrelated#run()V", *inputs)
        assertContains(unrelated, "state\tunreachable")
        assertContains(unrelated, "confidence\tstatic")
        assertFalse(unrelated.contains("binding-uncertainty\t"))
        val used = run("why", "method:fixture/UsedHelper#run()V", *inputs)
        assertContains(used, "state\treachable")
        assertFalse(used.contains("confidence\tneeds-runtime-review"))
        val dead = document(run("dead", *inputs, "--report-format", "json"))
        val findings = (dead["diagnostics"] as List<*>).map { it as Map<*, *> }.associateBy { it["nodeId"] }
        for (id in listOf("class:fixture/Helper", "class:fixture/Tail")) assertEquals("needs-runtime-review", findings.getValue(id)["confidence"])
        assertEquals("static", findings.getValue("class:fixture/Unrelated")["confidence"])
        assertFalse("class:fixture/UsedHelper" in findings)
        Files.writeString(root.resolve("runtime.txt"), "fixture.Helper\n")
        val observed = document(run("dead", *inputs, "--runtime-classes", "runtime.txt", "--report-format", "json"))
        val observedHelper = (observed["diagnostics"] as List<*>).map { it as Map<*, *> }.single { it["nodeId"] == "class:fixture/Helper" }
        assertEquals("runtime-observed", observedHelper["confidence"])
        val bindings = document(run("bindings", *inputs))
        val uncertainty = bindings["callUncertainty"] as Map<*, *>
        assertEquals(false, uncertainty["truncated"])
        assertEquals("unknown", uncertainty["runtimeSelection"])
        val helperStep = (uncertainty["steps"] as List<*>).map { it as Map<*, *> }.single { it["node"] == helper }
        assertEquals(adapter, helperStep["adapter"])
        assertEquals("BYTECODE", (helperStep["incomingCall"] as Map<*, *>)["origin"])
        val limited = document(run("bindings", *inputs, "--limit", "1"))["callUncertainty"] as Map<*, *>
        assertEquals(true, limited["rowsTruncated"])
        assertEquals(1, (limited["steps"] as List<*>).size)
    }

    private fun fixture(root: Path): Path {
        val classes = Files.createDirectories(root.resolve("classes"))
        val definitions = mapOf(
            "androidx/databinding/BindingAdapter" to "@java.lang.annotation.Retention(java.lang.annotation.RetentionPolicy.CLASS) public @interface BindingAdapter { String[] value(); boolean requireAll() default true; }",
            "androidx/annotation/Keep" to "@java.lang.annotation.Retention(java.lang.annotation.RetentionPolicy.CLASS) public @interface Keep {}",
            "fixture/Widget" to "public class Widget {}",
            "fixture/Adapters" to "public class Adapters { @androidx.databinding.BindingAdapter(\"item\") public static void item(Widget w, int value) { Helper.run(); } }",
            "fixture/Helper" to "public class Helper { public static void run() { Tail.run(); } public static void unrelatedMember() { Unrelated.run(); } }",
            "fixture/Tail" to "public class Tail { public static void run() {} }",
            "fixture/Unrelated" to "public class Unrelated { public static void run() {} }",
            "fixture/UsedAdapter" to "public class UsedAdapter { @androidx.databinding.BindingAdapter(\"used\") public static void used(Widget w, int value) { UsedHelper.run(); } }",
            "fixture/UsedHelper" to "public class UsedHelper { public static void run() {} }",
            "fixture/Entry" to "public class Entry { @androidx.annotation.Keep public static void entry() { UsedAdapter.used(new Widget(), 1); } }",
        )
        val sources = definitions.map { (name, body) ->
            val file = root.resolve("src/$name.java")
            Files.createDirectories(file.parent)
            Files.writeString(file, "package ${name.substringBeforeLast('/').replace('/', '.')}; $body")
            file.toString()
        }
        assertEquals(0, ToolProvider.getSystemJavaCompiler().run(null, null, null, "-g", "-d", classes.toString(), *sources.toTypedArray()))
        Files.writeString(root.resolve("AndroidManifest.xml"), "<manifest package=\"fixture\"><application /></manifest>")
        val xml = root.resolve("res/layout/screen.xml")
        Files.createDirectories(xml.parent)
        Files.writeString(xml, """<layout xmlns:app="http://schemas.android.com/apk/res-auto"><data><variable name="value" type="int" /></data><fixture.Widget app:item="@{value}" /></layout>""")
        return classes
    }

    private fun run(vararg args: String): String {
        val output = ByteArrayOutputStream()
        val error = ByteArrayOutputStream()
        assertEquals(0, KartographCli.run(args, PrintStream(output), PrintStream(error)), error.toString(Charsets.UTF_8))
        return output.toString(Charsets.UTF_8)
    }
    private fun document(text: String): Map<*, *> = McpJsonCodec.parse(text) as Map<*, *>
}
