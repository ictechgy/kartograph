package dev.kartograph.cli

import java.io.ByteArrayOutputStream
import java.io.PrintStream
import java.nio.file.Files
import java.nio.file.Path
import javax.tools.ToolProvider
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import org.junit.jupiter.api.io.TempDir

class RuntimeLibraryModelsCliTest {
    @Test
    fun `method invocation follows known member and excludes unrelated overload`(@TempDir root: Path) {
        compile(root, """
            public static class Target {
              public static void run(){new Used();}
              public static void run(int value){new Unused();}
            }
            public static class Used {} public static class Unused {}
            public static void main(String[] args) throws Exception { Target.class.getDeclaredMethod("run").invoke(null); }
        """)
        assertContains(query(root, "class:probe/Entry${'$'}Used"), "\"state\": \"reachable\"")
        assertContains(query(root, "class:probe/Entry${'$'}Unused"), "\"state\": \"unreachable\"")
        val call = dev.kartograph.index.ClassFileIndexer().index(listOf(root.resolve("classes"))).externalCalls
            .single { it.owner == "java/lang/reflect/Method" && it.name == "invoke" }
        assertEquals(dev.kartograph.core.CallResolution.RUNTIME_MODEL, call.resolution)
        assertEquals(listOf(dev.kartograph.core.NodeId("method:probe/Entry${'$'}Target#run()V")), call.resolvedTargets)
    }

    @Test
    fun `public inherited field access maps to the declaring owner`(@TempDir root: Path) {
        compile(root, """
            public static class Base { public int value; public int unused; }
            public static class Child extends Base {}
            public static void main(String[] args) throws Exception { Child.class.getField("value").get(new Child()); }
        """)
        assertContains(query(root, "field:probe/Entry${'$'}Base#value:I"), "\"state\": \"reachable\"")
        assertContains(query(root, "field:probe/Entry${'$'}Base#unused:I"), "\"state\": \"unreachable\"")
    }

    @Test
    fun `unknown method names retain a measured limitation`(@TempDir root: Path) {
        compile(root, """
            public static class Target { public static void run(){new Used();} }
            public static class Used {}
            public static void main(String[] args) throws Exception { Target.class.getDeclaredMethod(args[0]).invoke(null); }
        """)
        val result = query(root, "class:probe/Entry${'$'}Used")
        assertContains(result, "reflective-method-invocation:")
        assertContains(result, "\"state\": \"unreachable\"")
    }

    @Test
    fun `declared field lookup excludes inherited fields`(@TempDir root: Path) {
        compile(root, """
            public static class Base { public int value; }
            public static class Child extends Base {}
            public static void main(String[] args) throws Exception { Child.class.getDeclaredField("value").get(new Child()); }
        """)
        val result = query(root, "field:probe/Entry${'$'}Base#value:I")
        assertContains(result, "\"state\": \"unreachable\"")
        assertContains(result, "runtime-member-lookup:")
    }

    @Test
    fun `primitive field setters count as uses and unknown names stay unknown`(@TempDir root: Path) {
        compile(root, """
            public static class Holder { public int value; public int unused; }
            public static void main(String[] args) throws Exception {
              Holder.class.getDeclaredField("value").setInt(new Holder(), 4);
              Holder.class.getDeclaredField(args[0]).getInt(new Holder());
            }
        """)
        val result = query(root, "field:probe/Entry${'$'}Holder#value:I")
        assertContains(result, "\"state\": \"reachable\"")
        assertContains(result, "reflective-field-access:")
        assertContains(query(root, "field:probe/Entry${'$'}Holder#unused:I"), "\"state\": \"unreachable\"")
    }

    @Test
    fun `graph identifies the matched model independently of successful resolution`(@TempDir root: Path) {
        compile(root, """
            public static class Target { public static void run(){} }
            public static void main(String[] args) throws Exception { Target.class.getMethod(args[0]).invoke(null); }
        """)
        val output = ByteArrayOutputStream()
        assertEquals(0, KartographCli.run(arrayOf("graph", "--classes", root.resolve("classes").toString(),
            "--format", "json"), PrintStream(output), PrintStream(ByteArrayOutputStream())))
        assertContains(output.toString(), "\"model\": \"jdk.method-invocation.v1\"")
    }

    @Test
    fun `public inherited method lookup resolves its declaration`(@TempDir root: Path) {
        compile(root, """
            public static class Base { public static void run(){new Used();} }
            public static class Child extends Base {}
            public static class Used {}
            public static void main(String[] args) throws Exception { Child.class.getMethod("run").invoke(null); }
        """)
        assertContains(query(root, "class:probe/Entry${'$'}Used"), "\"state\": \"reachable\"")
    }

    @Test
    fun `method value limits never return partial resolved members`(@TempDir root: Path) {
        val methods = (0..16).joinToString("\n") { "public static void m$it(){new Used();}" }
        val branches = (0..15).joinToString("\n") { "case $it: name=\"m$it\"; break;" }
        compile(root, """
            public static class Target { $methods }
            public static class Used {}
            public static void main(String[] args) throws Exception {
              String name; switch(args.length){ $branches default: name="m16"; }
              Target.class.getDeclaredMethod(name).invoke(null);
            }
        """)
        val result = query(root, "class:probe/Entry${'$'}Used")
        assertContains(result, "runtime-analysis-limits:")
        assertContains(result, "reflective-method-invocation:")
        assertContains(result, "\"state\": \"unreachable\"")
    }

    @Test
    fun `method lookup cannot select constructors`(@TempDir root: Path) {
        compile(root, """
            public static class Target { public Target(){new Used();} }
            public static class Used {}
            public static void main(String[] args) throws Exception { Target.class.getDeclaredMethod("<init>").invoke(null); }
        """)
        assertContains(query(root, "class:probe/Entry${'$'}Used"), "\"state\": \"unreachable\"")
    }

    private fun compile(root: Path, body: String) {
        val source = root.resolve("Entry.java")
        Files.writeString(source, "package probe; public class Entry {" + body + "}")
        val classes = Files.createDirectories(root.resolve("classes"))
        val errors = ByteArrayOutputStream()
        assertEquals(0, ToolProvider.getSystemJavaCompiler().run(null, null, errors, "-g", "-d", classes.toString(), source.toString()), errors.toString())
        Files.writeString(root.resolve("keep.pro"), "-keep class probe.Entry { *; }\n")
    }

    private fun query(root: Path, symbol: String): String {
        val output = ByteArrayOutputStream()
        val errors = ByteArrayOutputStream()
        assertEquals(0, KartographCli.run(arrayOf("query", symbol, "--classes", root.resolve("classes").toString(),
            "--project", root.toString(), "--keep-rules", "keep.pro"), PrintStream(output), PrintStream(errors)), errors.toString())
        return output.toString(Charsets.UTF_8)
    }
}
