package dev.kartograph.cli

import java.io.ByteArrayOutputStream
import java.io.PrintStream
import java.nio.file.Files
import java.nio.file.Path
import javax.tools.ToolProvider
import dev.kartograph.export.McpJsonCodec
import dev.kartograph.export.QuerySnapshotCodec
import kotlin.test.*
import org.junit.jupiter.api.io.TempDir

class DataBindingFeedbackCliTest {
    @Test
    fun `why preserves unresolved same element adapter candidate without rooting it`(@TempDir root: Path) {
        val classes = fixture(root)
        layout(root, """<fixture.Widget app:first="@{value}" app:second="@{value}" />""")
        val result = run("why", "method:fixture/Adapters#both(Lfixture/Widget;II)V", *inputs(root, classes))
        assertEquals(0, result.first, result.third)
        assertContains(result.second, "state\tunreachable")
        assertContains(result.second, "data-binding-xml-candidate:")
        assertContains(result.second, "method:fixture/Adapters#both(Lfixture/Widget;II)V")
        assertContains(result.second, "res/layout/screen.xml")
        assertContains(result.second, "confidence\tneeds-runtime-review")
    }

    @Test
    fun `requireAll never joins attributes across XML elements and normal values are not expressions`(@TempDir root: Path) {
        val classes = fixture(root)
        layout(root, """<fixture.Widget app:first="@{value}" /><fixture.Widget app:second="@{value}" /><fixture.Widget app:first="@{value}" app:second="5" />""")
        val result = run("bindings", *inputs(root, classes))
        assertEquals(0, result.first, result.third)
        val rows = candidates(result.second)
        assertEquals(3, rows.count { "#both(" in it["adapter"].toString() })
        assertTrue(rows.filter { "#both(" in it["adapter"].toString() }.all { it["attributeMatch"] == "missingRequiredAttributes" })
        assertTrue(rows.filter { "#optional(" in it["adapter"].toString() }.all { it["attributeMatch"] == "someAttributesPresent" })
        assertFalse(rows.any { "#unused(" in it["adapter"].toString() || "#fake(" in it["adapter"].toString() })
    }

    @Test
    fun `same attribute overloads retain ambiguity and union ignores root order and duplicates`(@TempDir root: Path) {
        val classes = fixture(root)
        layout(root, """<fixture.Widget app:overloaded="@{value}" />""")
        val second = root.resolve("other/layout/extra.xml")
        Files.createDirectories(second.parent)
        Files.copy(root.resolve("res/layout/screen.xml"), second)
        val one = run("bindings", *inputs(root, classes), "--resources", "other", "--resources", "res")
        val two = run("bindings", *inputs(root, classes), "--resources", "res", "--resources", "other")
        assertEquals(0, one.first, one.third)
        assertEquals(one.second, two.second)
        assertEquals(4, candidates(one.second).size)
        assertTrue(candidates(one.second).all { (it["overloadCount"] as Number).toInt() == 2 && it["runtimeSelection"] == "unknown" })
        assertEquals("unverified", (McpJsonCodec.parse(one.second) as Map<*, *>)["buildFreshness"])
    }

    @Test
    fun `explicit overlay shadows even empty binding layout and qualifiers remain alternatives`(@TempDir root: Path) {
        val classes = fixture(root)
        layout(root, """<fixture.Widget app:first="@{value}" app:second="@{value}" />""")
        val override = root.resolve("debug/layout/screen.xml")
        Files.createDirectories(override.parent)
        Files.writeString(override, "<fixture.Widget />")
        val landscape = root.resolve("res/layout-land/screen.xml")
        Files.createDirectories(landscape.parent)
        Files.copy(root.resolve("res/layout/screen.xml"), landscape)
        val result = run("bindings", *inputs(root, classes), "--resources", "debug", "--resource-overlay", "res", "--resource-overlay", "debug", "--variant", "debug")
        assertEquals(0, result.first, result.third)
        assertTrue(candidates(result.second).isNotEmpty())
        assertTrue(candidates(result.second).all { (it["layout"] as Map<*, *>)["resource"] == "layout-land/screen.xml" })
        assertContains(result.second, "inputAssertion")
        assertContains(result.second, "inputPriorityAssertion")
        assertEquals(64, run("bindings", *inputs(root, classes), "--resource-overlay", "missing").first)
    }

    @Test
    fun `all supported dead formats and saved snapshot keep unresolved XML evidence`(@TempDir root: Path) {
        val classes = fixture(root)
        layout(root, """<fixture.Widget app:first="@{value}" app:second="@{value}" />""")
        for (format in listOf("text", "json", "sarif", "markdown", "gradle", "github-actions")) {
            val result = run("dead", *inputs(root, classes), "--report-format", format)
            assertEquals(0, result.first, result.third)
            assertContains(result.second + result.third, "data-binding-xml-candidate")
        }
        val snapshot = run("snapshot", *inputs(root, classes))
        assertEquals(0, snapshot.first, snapshot.third)
        assertTrue(QuerySnapshotCodec.parse(snapshot.second).limitations.any { "data-binding-xml-candidate:" in it })
        val saved = Files.writeString(root.resolve("snapshot.json"), snapshot.second)
        McpTestClient { input, output, error ->
            KartographCli.runWithInput(arrayOf("mcp", "--graph-file", saved.toString()), output, error, input)
        }.use { client ->
            client.initialize()
            val response = client.call("query_symbol", mapOf("symbol" to "method:fixture/Adapters#both(Lfixture/Widget;II)V"))
            assertEquals(false, response["isError"])
            assertContains(response.toString(), "data-binding-xml-candidate:")
        }
        val owner = run("why", "class:fixture/UncalledAdapters", *inputs(root, classes))
        assertContains(owner.second, "confidence\tneeds-runtime-review")
        Files.writeString(root.resolve("runtime.txt"), "fixture.UncalledAdapters\n")
        val observed = run("dead", *inputs(root, classes), "--runtime-classes", "runtime.txt", "--report-format", "json")
        assertEquals(0, observed.first, observed.third)
        assertContains(observed.second, "runtime-observed")
        assertContains(observed.second, "data-binding-unresolved")
    }

    @Test
    fun `bounded row report states truncation without dropping unknown adapters`(@TempDir root: Path) {
        val classes = fixture(root)
        layout(root, """<fixture.Widget app:first="@{value}" app:second="@{value}" />""")
        val result = run("bindings", *inputs(root, classes), "--limit", "1")
        assertEquals(0, result.first, result.third)
        val document = McpJsonCodec.parse(result.second) as Map<*, *>
        assertEquals(true, document["truncated"])
        assertEquals(1, candidates(result.second).size)
        assertContains(result.second, "data-binding-evidence-truncated:")
    }

    @Test
    fun `actual compiled overload path requires reached binding owner and does not root unused owner`(@TempDir root: Path) {
        val classes = fixture(root)
        layout(root, """<fixture.Widget app:overloaded="@{value}" />""")
        val used = run("why", "method:fixture/Adapters#overloaded(Lfixture/Widget;I)V", *inputs(root, classes))
        assertEquals(0, used.first, used.third)
        assertContains(used.second, "state\treachable")
        assertContains(used.second, "fixture.BindingImpl.executeBindings")
        for (method in listOf("method:fixture/Adapters#overloaded(Lfixture/Widget;Ljava/lang/String;)V", "method:fixture/Adapters#unused(Lfixture/Widget;I)V")) {
            val unused = run("why", method, *inputs(root, classes))
            assertEquals(0, unused.first, unused.third)
            assertContains(unused.second, "state\tunreachable")
        }
        val result = run("bindings", *inputs(root, classes))
        val rows = candidates(result.second)
        assertEquals(2, rows.size)
        val called = rows.single { it["adapter"].toString().endsWith("Widget;I)V") }
        val other = rows.single { it["adapter"].toString().endsWith("String;)V") }
        assertTrue((called["reachedCompiledCallers"] as List<*>).isNotEmpty())
        assertTrue((other["reachedCompiledCallers"] as List<*>).isEmpty())
        assertEquals("unverified", called["xmlToCompiledLink"])
    }

    @Test
    fun `oversized binding XML remains unknown and corrupt XML is rejected`(@TempDir root: Path) {
        val classes = fixture(root)
        layout(root, """<fixture.Widget app:first="@{value}" app:second="@{value}" />""")
        val xml = root.resolve("res/layout/screen.xml")
        Files.writeString(xml, Files.readString(xml).replace("</layout>", "<!--" + "x".repeat(2 * 1024 * 1024) + "--></layout>"))
        val result = run("why", "method:fixture/Adapters#both(Lfixture/Widget;II)V", *inputs(root, classes))
        assertEquals(0, result.first, result.third)
        assertContains(result.second, "binding-input-unavailable-or-budget-exceeded")
        assertContains(result.second, "confidence\tneeds-runtime-review")
        Files.writeString(xml, "<!DOCTYPE layout SYSTEM 'https://invalid.example/private'><layout>&outside;</layout>")
        val prohibited = run("bindings", *inputs(root, classes))
        assertEquals(0, prohibited.first, prohibited.third)
        assertEquals("incomplete", (McpJsonCodec.parse(prohibited.second) as Map<*, *>)["status"])
        assertContains(prohibited.second, "binding-input-unavailable-or-budget-exceeded")
        Files.writeString(xml, "<layout><unclosed>")
        assertEquals(2, run("bindings", *inputs(root, classes)).first)
    }

    @Test
    fun `empty resource scope and observed unused attributes preserve different unknown evidence`(@TempDir root: Path) {
        val classes = fixture(root)
        Files.createDirectories(root.resolve("res"))
        val empty = run("why", "method:fixture/Adapters#both(Lfixture/Widget;II)V", *inputs(root, classes))
        assertEquals(0, empty.first, empty.third)
        assertContains(empty.second, "data-binding-layouts-unobserved")
        assertContains(empty.second, "confidence\tneeds-runtime-review")
        layout(root, """<fixture.Widget app:other="@{value}" />""")
        val unused = run("bindings", *inputs(root, classes))
        assertEquals(0, unused.first, unused.third)
        assertTrue(candidates(unused.second).isEmpty())
        assertContains(unused.second, "data-binding-no-matching-expression-observed")
        assertFalse(unused.second.contains("data-binding-layouts-unobserved"))
        val absent = run("snapshot", "--classes", classes.toString(), "--project", root.toString())
        assertEquals(0, absent.first, absent.third)
        assertTrue(QuerySnapshotCodec.parse(absent.second).limitations.any { "data-binding-resource-scope-unobserved" in it })
    }

    private fun fixture(root: Path): Path {
        val classes = Files.createDirectories(root.resolve("classes"))
        val definitions = mapOf(
            "androidx/databinding/BindingAdapter" to "@java.lang.annotation.Retention(java.lang.annotation.RetentionPolicy.CLASS) public @interface BindingAdapter { String[] value(); boolean requireAll() default true; }",
            "androidx/annotation/Keep" to "@java.lang.annotation.Retention(java.lang.annotation.RetentionPolicy.CLASS) public @interface Keep {}",
            "androidx/databinding/ViewDataBinding" to "public abstract class ViewDataBinding { protected abstract void executeBindings(); }",
            "fixture/BindingImpl" to "public class BindingImpl extends androidx.databinding.ViewDataBinding { protected void executeBindings() { Adapters.overloaded(new Widget(), 1); } }",
            "fixture/UnusedBindingImpl" to "public class UnusedBindingImpl extends androidx.databinding.ViewDataBinding { protected void executeBindings() { Adapters.unused(new Widget(), 1); } }",
            "fixture/Entry" to "public class Entry { @androidx.annotation.Keep public static void run() { new BindingImpl(); } }",
            "fixture/Widget" to "public class Widget {}",
            "fixture/UncalledAdapters" to "public class UncalledAdapters { @androidx.databinding.BindingAdapter(\"unobserved\") public static void unobserved(Widget view, int value) {} }",
            "fixture/BindingAdapter" to "@java.lang.annotation.Retention(java.lang.annotation.RetentionPolicy.CLASS) public @interface BindingAdapter { String[] value(); }",
            "fixture/Adapters" to """public class Adapters {
                @androidx.databinding.BindingAdapter({"first", "second"}) public static void both(Widget view, int first, int second) {}
                @androidx.databinding.BindingAdapter(value={"first", "second"}, requireAll=false) public static void optional(Widget view, int first, int second) {}
                @androidx.databinding.BindingAdapter("overloaded") public static void overloaded(Widget view, int value) {}
                @androidx.databinding.BindingAdapter("overloaded") public static void overloaded(Widget view, String value) {}
                @androidx.databinding.BindingAdapter("unused") public static void unused(Widget view, int value) {}
                @fixture.BindingAdapter("first") public static void fake(Widget view, int value) {}
            }""",
        )
        val sources = definitions.map { (name, body) ->
            val file = root.resolve("src/$name.java")
            Files.createDirectories(file.parent)
            Files.writeString(file, "package ${name.substringBeforeLast('/').replace('/', '.')}; $body")
            file.toString()
        }
        assertEquals(0, ToolProvider.getSystemJavaCompiler().run(null, null, null,
            "-g", "-d", classes.toString(), *sources.toTypedArray()))
        Files.writeString(root.resolve("AndroidManifest.xml"), "<manifest package=\"fixture\"><application /></manifest>")
        return classes
    }

    private fun layout(root: Path, views: String) {
        val file = root.resolve("res/layout/screen.xml")
        Files.createDirectories(file.parent)
        Files.writeString(file, """<layout xmlns:app="http://schemas.android.com/apk/res-auto"><data><variable name="value" type="int" /></data>$views</layout>""")
    }

    private fun inputs(root: Path, classes: Path) = arrayOf("--project", root.toString(), "--classes", classes.toString(),
        "--manifest", "AndroidManifest.xml", "--resources", "res", "--namespace", "fixture")

    private fun run(vararg args: String): Triple<Int, String, String> {
        val output = ByteArrayOutputStream()
        val error = ByteArrayOutputStream()
        val code = KartographCli.run(args, PrintStream(output), PrintStream(error))
        return Triple(code, output.toString(Charsets.UTF_8), error.toString(Charsets.UTF_8))
    }

    private fun candidates(text: String): List<Map<*, *>> = ((McpJsonCodec.parse(text) as Map<*, *>)["candidates"] as List<*>).map { it as Map<*, *> }
}
