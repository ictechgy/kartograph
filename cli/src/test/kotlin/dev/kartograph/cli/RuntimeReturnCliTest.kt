package dev.kartograph.cli

import dev.kartograph.core.EdgeOrigin
import dev.kartograph.index.ClassFileIndexer
import java.io.ByteArrayOutputStream
import java.io.PrintStream
import java.net.URLClassLoader
import java.nio.file.Files
import java.nio.file.Path
import javax.tools.ToolProvider
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import org.junit.jupiter.api.io.TempDir

class RuntimeReturnCliTest {
    @Test
    fun `static helper in another class restores an executed reflective constructor`(@TempDir root: Path) {
        compile(root, """
            public static boolean observed;
            public static class Used { public Used(){observed=true;} }
            public static class Unused {}
            public static void main(String[] args) throws Exception {
                Class.forName(Names.name()).getDeclaredConstructor().newInstance();
            }
        """, "class Names { static String name(){return \"probe.Entry${'$'}Used\";} }")
        URLClassLoader(arrayOf(root.resolve("classes").toUri().toURL())).use { loader ->
            val entry = loader.loadClass("probe.Entry")
            entry.getMethod("main", Array<String>::class.java).invoke(null, emptyArray<String>())
            assertEquals(true, entry.getField("observed").get(null))
        }
        assertContains(query(root, "Used"), "\"state\": \"reachable\"")
        assertFalse(query(root, "Used").contains("reflection-strings:"))
        assertContains(query(root, "Unused"), "\"state\": \"unreachable\"")
        val graph = ClassFileIndexer().index(listOf(root.resolve("classes")))
        val loading = graph.externalCalls.single { it.owner == "java/lang/Class" && it.name == "forName" }
        assertEquals(listOf("class:probe/Entry${'$'}Used"), loading.resolvedTargets.map { it.value })
        assertEquals("jdk.class-loading.v1", loading.model)
        assertEquals(EdgeOrigin.RUNTIME_MODEL, graph.edges.single {
            it.source == loading.caller && it.target == loading.resolvedTargets.single()
        }.origin)
    }

    @Test
    fun `helper return joins retain all alternatives without retaining unused controls`(@TempDir root: Path) {
        compile(root, """
            public static class A {} public static class B {} public static class Unused {}
            static String name(){ if(System.nanoTime() == 0) return "probe.Entry${'$'}A"; return "probe.Entry${'$'}B"; }
            public static void main(String[] args) throws Exception { Class.forName(name()); }
        """)
        for (name in listOf("A", "B")) assertContains(query(root, name), "\"state\": \"reachable\"")
        assertContains(query(root, "Unused"), "\"state\": \"unreachable\"")
    }

    @Test
    fun `mutable String field returns retain observed candidates and unknown possibilities`(@TempDir root: Path) {
        compile(root, """
            public static boolean observed;
            public static class Used {static {observed=true;}}
            public static class Unused {}
            static String mutable="probe.Entry${'$'}Used";
            static String name(){ if(System.nanoTime() == 0) return "probe.Entry${'$'}Used"; return mutable; }
            public static void main(String[] args) throws Exception { Class.forName(name()); }
        """)
        URLClassLoader(arrayOf(root.resolve("classes").toUri().toURL())).use { loader ->
            val entry = loader.loadClass("probe.Entry")
            entry.getMethod("main", Array<String>::class.java).invoke(null, emptyArray<String>())
            assertEquals(true, entry.getField("observed").get(null))
        }
        val result = query(root, "Used")
        assertContains(result, "reflection-strings:")
        assertContains(result, "\"state\": \"reachable\"")
        assertContains(query(root, "Unused"), "\"state\": \"unreachable\"")
    }

    @Test
    fun `nested static helpers preserve arguments and distinguish call contexts and overloads`(@TempDir root: Path) {
        compile(root, """
            public static class A {} public static class B {} public static class Unused {}
            static String wrap(String suffix){return Names.name(4L, suffix);}
            public static void main(String[] args) throws Exception {
                Class.forName(wrap("A")); Class.forName(wrap("B")); Class.forName(wrap(args[0]));
            }
        """, """
            class Names {
                static String name(long ignored, String suffix){return "probe.Entry${'$'}" + suffix;}
                static String name(int ignored){return "probe.Entry${'$'}Unused";}
            }
        """)
        for (name in listOf("A", "B")) assertContains(query(root, name), "\"state\": \"reachable\"")
        assertContains(query(root, "Unused"), "\"state\": \"unreachable\"")
        assertContains(query(root, "A"), "reflection-strings: 1 Class.forName")
        val calls = ClassFileIndexer().index(listOf(root.resolve("classes"))).externalCalls
            .filter { it.name == "forName" }.sortedBy { it.ordinal }
        assertEquals(listOf(listOf("class:probe/Entry${'$'}A"), listOf("class:probe/Entry${'$'}B"), emptyList()),
            calls.map { call -> call.resolvedTargets.map { it.value } })
    }

    @Test
    fun `recursive summaries stop with a measured limit instead of keeping a partial return`(@TempDir root: Path) {
        compile(root, """
            public static class Used {}
            static String name(){if(System.nanoTime()==0) return "probe.Entry${'$'}Used"; return name();}
            public static void main(String[] args) throws Exception {Class.forName(name());}
        """)
        val result = query(root, "Used")
        assertContains(result, "runtime-analysis-limits:")
        assertContains(result, "reflection-strings:")
        assertContains(result, "\"state\": \"unreachable\"")
    }

    @Test
    fun `virtual helper returns are not inferred from a single declaration`(@TempDir root: Path) {
        compile(root, """
            public static class Used {}
            public static class Names {String name(){return "probe.Entry${'$'}Used";}}
            public static void main(String[] args) throws Exception {Class.forName(new Names().name());}
        """)
        assertContains(query(root, "Used"), "reflection-strings:")
        assertContains(query(root, "Used"), "\"state\": \"unreachable\"")
    }

    @Test
    fun `actual Kotlin helper chains retain runtime targets with JVM identities`(@TempDir root: Path) {
        val classes = Files.createDirectories(root.resolve("classes"))
        for (name in listOf("KotlinReturnEntry", "KotlinReturnUsed", "KotlinReturnUnused", "RuntimeReturnFixtureKt")) {
            val resource = "dev/kartograph/cli/fixture/$name.class"
            val target = classes.resolve(resource)
            Files.createDirectories(target.parent)
            Files.write(target, requireNotNull(javaClass.getResourceAsStream("/$resource")).use { it.readAllBytes() })
        }
        dev.kartograph.cli.fixture.KotlinReturnEntry.main(emptyArray())
        assertEquals(true, dev.kartograph.cli.fixture.KotlinReturnEntry.observed)
        Files.writeString(root.resolve("keep.pro"), "-keep class dev.kartograph.cli.fixture.KotlinReturnEntry { *; }\n")
        for ((name, state) in listOf("KotlinReturnUsed" to "reachable", "KotlinReturnUnused" to "unreachable")) {
            assertContains(querySymbol(root, "class:dev/kartograph/cli/fixture/$name"), "\"state\": \"$state\"")
        }
    }

    @Test
    fun `class valued helpers preserve reflective constructor bodies`(@TempDir root: Path) {
        compile(root, """
            public static class Target { public Target(){new Used();} }
            public static class Used {} public static class Unused {}
            static Class<?> type(Class<?> value){return value;}
            public static void main(String[] args) throws Exception {type(Target.class).getDeclaredConstructor().newInstance();}
        """)
        assertContains(query(root, "Used"), "\"state\": \"reachable\"")
        assertContains(query(root, "Unused"), "\"state\": \"unreachable\"")
    }

    @Test
    fun `summary depth and value set budgets do not emit partial targets`(@TempDir root: Path) {
        val chain = (0..9).joinToString("\n") { index ->
            "static String n$index(){return " + (if (index == 9) "\"probe.Entry${'$'}Used\"" else "n${index + 1}()") + ";}"
        }
        val alternatives = (0..16).joinToString("\n") { "case $it: return \"probe.Entry${'$'}T$it\";" }
        compile(root, """
            public static class Used {}
            $chain
            static String many(int choice){switch(choice){$alternatives default: return "probe.Entry${'$'}Used";}}
            public static void main(String[] args) throws Exception {Class.forName(n0()); Class.forName(many(args.length));}
        """)
        val result = query(root, "Used")
        assertContains(result, "runtime-analysis-limits:")
        assertContains(result, "reflection-strings: 2 Class.forName")
        assertContains(result, "\"state\": \"unreachable\"")
    }

    @Test
    fun `duplicate input roots select return bodies from the same winning class`(@TempDir root: Path) {
        val roots = listOf("Used", "Unused").map { target ->
            val project = Files.createDirectories(root.resolve(target))
            compile(project, """
                public static class Used {} public static class Unused {}
                public static void main(String[] args) throws Exception {Class.forName(Names.name());}
            """, "class Names {static String name(){return \"probe.Entry${'$'}$target\";}}")
            project.resolve("classes")
        }
        for ((selected, expected) in listOf(roots to "Used", roots.reversed() to "Unused")) {
            val call = ClassFileIndexer().index(selected).externalCalls.single { it.name == "forName" }
            assertEquals(listOf("class:probe/Entry${'$'}$expected"), call.resolvedTargets.map { it.value })
        }
    }

    @Test
    fun `summary work budget stops many distinct contexts and still accounts for unknown names`(@TempDir root: Path) {
        val calls = (0..130).joinToString("\n") { "Class.forName(name(\"T$it\"));" }
        compile(root, """
            public static class Used {}
            static String name(String suffix){return "probe.Entry${'$'}" + suffix;}
            public static void main(String[] args) throws Exception {$calls Class.forName(name("Used"));}
        """)
        val result = query(root, "Used")
        assertContains(result, "runtime-analysis-limits:")
        assertContains(result, "reflection-strings:")
        assertContains(result, "\"state\": \"unreachable\"")
    }

    private fun compile(root: Path, body: String, extra: String = "") {
        val source = root.resolve("Entry.java")
        Files.writeString(source, "package probe; public class Entry {" + body + "}" + extra)
        val classes = Files.createDirectories(root.resolve("classes"))
        val errors = ByteArrayOutputStream()
        assertEquals(0, ToolProvider.getSystemJavaCompiler().run(null, null, errors, "-g", "-d", classes.toString(), source.toString()), errors.toString())
        Files.writeString(root.resolve("keep.pro"), "-keep class probe.Entry { *; }\n")
    }

    private fun query(root: Path, name: String): String = querySymbol(root, "class:probe/Entry${'$'}$name")

    private fun querySymbol(root: Path, symbol: String): String {
        val output = ByteArrayOutputStream()
        val errors = ByteArrayOutputStream()
        assertEquals(0, KartographCli.run(arrayOf("query", symbol, "--classes", root.resolve("classes").toString(),
            "--project", root.toString(), "--keep-rules", "keep.pro"), PrintStream(output), PrintStream(errors)), errors.toString())
        return output.toString(Charsets.UTF_8)
    }
}
