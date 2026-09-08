package dev.kartograph.cli

import java.io.ByteArrayOutputStream
import java.io.PrintStream
import java.nio.file.Files
import java.nio.file.Path
import javax.tools.ToolProvider
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import org.junit.jupiter.api.io.TempDir

class RuntimeResolutionCliTest {
    @Test
    fun `real bytecode restores reflection and external dispatch targets`(@TempDir root: Path) {
        for ((case, target) in listOf(
            "forname_local" to "Target", "forname_three_args" to "Target", "load_class" to "Target",
            "reflective_constructor" to "ConstructorBody", "external_dispatch" to "CallbackBody",
            "annotation_default" to "DefaultTarget",
        )) {
            val project = root.resolve(case)
            val text = requireNotNull(javaClass.getResourceAsStream("/$case/src/probe/Entry.java")).bufferedReader().use { it.readText() }
            compile(project, text)
            val result = query(project, "class:probe/Entry${'$'}$target")
            assertContains(result, "\"state\": \"reachable\"")
            if (case == "external_dispatch") assertContains(result, "dispatch-candidates:")
        }
    }

    @Test
    fun `separate service resource roots retain provider and report their evidence`(@TempDir root: Path) {
        val source = requireNotNull(javaClass.getResourceAsStream("/service_loader/src/probe/Entry.java")).bufferedReader().use { it.readText() }
        compile(root, source)
        val registry = root.resolve("resources/META-INF/services/java.lang.Runnable")
        Files.createDirectories(registry.parent)
        Files.writeString(registry, "# provider\nprobe.Entry${'$'}Provider\n")
        val result = query(root, "class:probe/Entry${'$'}Provider", "--service-resources", "resources")
        assertContains(result, "\"reason\": \"serviceProvider\"")
        assertFalse(result.contains("service-loading:"))
    }

    @Test
    fun `branch joins and string concatenation preserve all possible names`(@TempDir root: Path) {
        compile(root, """
            package probe;
            public class Entry {
              public static class A {} public static class B {} public static class Unused {}
              public static void main(String[] args) throws Exception {
                String suffix;
                if(args.length == 0) suffix="A"; else suffix="B";
                String prefix="probe.Entry${'$'}";
                Class.forName(prefix + suffix);
              }
            }
        """.trimIndent())
        for (name in listOf("A", "B")) assertContains(query(root, "class:probe/Entry${'$'}$name"), "\"state\": \"reachable\"")
        assertContains(query(root, "class:probe/Entry${'$'}Unused"), "\"state\": \"unreachable\"")
    }

    @Test
    fun `unknown values never inherit stale constants from another branch`(@TempDir root: Path) {
        compile(root, """
            package probe;
            public class Entry {
              public static class A {}
              public static void main(String[] args) throws Exception {
                String name="probe.Entry${'$'}A";
                if(args.length > 0) name=args[0];
                Class.forName(name);
              }
            }
        """.trimIndent())
        val result = query(root, "class:probe/Entry${'$'}A")
        assertContains(result, "reflection-strings:")
        assertContains(result, "\"state\": \"unreachable\"")
    }

    @Test
    fun `value set limits remain visible instead of returning partial certainty`(@TempDir root: Path) {
        val cases = (0..16).joinToString("\n") { "case $it: name=\"probe.Entry${'$'}T$it\"; break;" }
        compile(root, """
            package probe;
            public class Entry {
              public static void main(String[] args) throws Exception {
                String name;
                switch(args.length) { $cases default: name="probe.Entry${'$'}Other"; }
                Class.forName(name);
              }
            }
        """.trimIndent())
        val result = query(root, "Missing", expected = 64)
        assertContains(result, "runtime-analysis-limits:")
        assertContains(result, "reflection-strings:")
    }

    @Test
    fun `primitive string conversions do not confuse boolean char and integer names`(@TempDir root: Path) {
        compile(root, """
            package probe;
            public class Entry {
              public static class Targetfalse {} public static class TargetX {} public static class Target3 {}
              public static class Wrong0 {}
              public static void main(String[] args) throws Exception {
                boolean b=false; char c='X'; int n=1; n=n+2;
                Class.forName("probe.Entry${'$'}Target"+b);
                Class.forName("probe.Entry${'$'}Target".concat(String.valueOf(c)));
                Class.forName("probe.Entry${'$'}Target"+n);
              }
            }
        """.trimIndent())
        for (name in listOf("Targetfalse", "TargetX", "Target3")) assertContains(query(root, "class:probe/Entry${'$'}$name"), "\"state\": \"reachable\"")
        assertContains(query(root, "class:probe/Entry${'$'}Wrong0"), "\"state\": \"unreachable\"")
    }

    @Test
    fun `constructor arity avoids unrelated overload bodies`(@TempDir root: Path) {
        compile(root, """
            package probe;
            public class Entry {
              public static class Target { public Target(){new Unused();} public Target(int value){new Used();} }
              public static class Used {} public static class Unused {}
              public static void main(String[] args) throws Exception {
                Target.class.getConstructor(int.class).newInstance(1);
              }
            }
        """.trimIndent())
        assertContains(query(root, "class:probe/Entry${'$'}Used"), "\"state\": \"reachable\"")
        assertContains(query(root, "class:probe/Entry${'$'}Unused"), "\"state\": \"unreachable\"")
    }

    @Test
    fun `class newInstance can invoke a package accessible constructor`(@TempDir root: Path) {
        compile(root, """
            package probe;
            public class Entry {
              public static class Used {}
              public static void main(String[] args) throws Exception { Target.class.newInstance(); }
            }
            class Target { Target(){ new Entry.Used(); } }
        """.trimIndent())
        java.net.URLClassLoader(arrayOf(root.resolve("classes").toUri().toURL())).use { loader ->
            loader.loadClass("probe.Entry").getMethod("main", Array<String>::class.java).invoke(null, emptyArray<String>())
        }
        assertContains(query(root, "class:probe/Entry${'$'}Used"), "\"state\": \"reachable\"")
    }

    private fun compile(root: Path, text: String) {
        val source = root.resolve("src/probe/Entry.java")
        Files.createDirectories(source.parent)
        Files.writeString(source, text)
        val classes = Files.createDirectories(root.resolve("classes"))
        val errors = ByteArrayOutputStream()
        assertEquals(0, ToolProvider.getSystemJavaCompiler().run(null, null, errors, "-g", "-d", classes.toString(), source.toString()), errors.toString())
        Files.writeString(root.resolve("keep.pro"), "-keep class probe.Entry { *; }\n")
    }

    private fun query(root: Path, symbol: String, vararg extra: String, expected: Int = 0): String {
        val output = ByteArrayOutputStream()
        val errors = ByteArrayOutputStream()
        val code = KartographCli.run(arrayOf("query", symbol, "--classes", root.resolve("classes").toString(),
            "--project", root.toString(), "--keep-rules", "keep.pro", *extra), PrintStream(output), PrintStream(errors))
        assertEquals(expected, code, errors.toString())
        return output.toString(Charsets.UTF_8)
    }
}
