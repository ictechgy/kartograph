package dev.kartograph.index

import dev.kartograph.core.EdgeOrigin
import java.io.ByteArrayOutputStream
import java.net.URLClassLoader
import java.nio.file.Files
import java.nio.file.Path
import javax.tools.ToolProvider
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class RuntimeInstanceHelperTest {
    @Test fun `final Java helper preserves wide argument slots`(@TempDir root: Path) = javaCase(root, "java-final", true)
    @Test fun `private Java helper has exact dispatch`(@TempDir root: Path) = javaCase(root, "java-private", true)
    @Test fun `Java 8 private invokespecial helper has exact dispatch`(@TempDir root: Path) = javaCase(root, "java-private", true, "8")
    @Test fun `overridden Java helper remains unknown`(@TempDir root: Path) = javaCase(root, "java-override", false)
    @Test fun `Java receiver state remains unknown`(@TempDir root: Path) = javaCase(root, "java-state", false)

    @Test fun `Kotlin object helper has exact dispatch`(@TempDir root: Path) = kotlinCase(root, "helperobject", listOf("Names"))
    @Test fun `Kotlin companion helper has exact dispatch`(@TempDir root: Path) = kotlinCase(root, "helpercompanion", listOf("Names", "Names\$Companion"))

    @Test fun `final methods Class returns overloads and receiver local zero stay distinct`(@TempDir root: Path) {
        val classes = compile(root, """
            package probe;
            public class Entry {
                final Class<?> value(long ignored, double wide, Class<?> target) { return target; }
                final Class<?> value(String ignored) { return Unused.class; }
                final String receiver(long ignored, String target) { return (String)(Object)this; }
                public static void main(String[] args) throws Exception {
                    new Entry().value(7L, 2.0, Used.class).getDeclaredConstructor().newInstance();
                }
                static void unknown() throws Exception { Class.forName(new Entry().receiver(7L, "probe.Unused")); }
            }
            class Used { public Used() {} }
            class Unused { public Unused() {} }
        """.trimIndent())
        runMain(classes, "probe.Entry")
        val indexed = ClassFileIndexer().indexWithObservations(listOf(classes))
        assertTrue(indexed.graph.externalCalls.single { it.name == "newInstance" }.resolvedTargets.any { it.value == "method:probe/Used#<init>()V" })
        assertTrue(indexed.graph.externalCalls.single { it.name == "forName" }.resolvedTargets.isEmpty())
        assertEquals(1, indexed.observations.sumOf { it.reflectionCalls })
    }

    @Test fun `instance recursion and depth bounds remain disclosed`(@TempDir root: Path) {
        val chain = (0..9).joinToString("\n") { "final String depth$it() { return " + (if (it == 9) "\"probe.Unused\"" else "depth${it + 1}()") + "; }" }
        val classes = compile(root, """
            package probe;
            public final class Entry {
                String recursive() { return recursive(); }
                $chain
                static void recursiveRead() throws Exception { Class.forName(new Entry().recursive()); }
                static void deepRead() throws Exception { Class.forName(new Entry().depth0()); }
            }
            class Unused {}
        """.trimIndent())
        val indexed = ClassFileIndexer().indexWithObservations(listOf(classes))
        assertTrue(indexed.graph.externalCalls.filter { it.name == "forName" }.all { it.resolvedTargets.isEmpty() })
        assertEquals(2, indexed.observations.sumOf { it.reflectionCalls })
        assertEquals(2, indexed.observations.sumOf { it.valueAnalysisLimits })
    }

    @Test fun `unknown instance return branch drops partial candidates and retains diagnostics`(@TempDir root: Path) {
        val classes = compile(root, """
            package probe;
            public final class Entry {
                String value(boolean flag, String unknown) { return flag ? "probe.Used" : unknown; }
                static void read(boolean flag, String unknown) throws Exception {
                    Class.forName(new Entry().value(flag, unknown)).getDeclaredConstructor().newInstance();
                }
            }
            class Used { public Used() {} }
            class Unused {}
        """.trimIndent())
        val indexed = ClassFileIndexer().indexWithObservations(listOf(classes))
        assertTrue(indexed.graph.externalCalls.single { it.name == "forName" }.resolvedTargets.isEmpty())
        assertEquals(1, indexed.observations.sumOf { it.reflectionCalls })
        assertEquals(1, indexed.observations.sumOf { it.reflectiveConstructions })
        assertTrue(indexed.graph.edges.filter { it.origin == EdgeOrigin.RUNTIME_MODEL }.none { "Unused" in it.target.value })
    }

    private fun javaCase(root: Path, case: String, resolved: Boolean, release: String = "17") {
        val source = requireNotNull(javaClass.getResourceAsStream("/runtime-helpers/$case/Entry.java")).use { String(it.readAllBytes()) }
        val classes = compile(root, source, release)
        runMain(classes, "probe.Entry", if (case == "java-state") arrayOf("probe.Used") else emptyArray())
        assertModel(classes, "probe", resolved)
    }

    private fun kotlinCase(root: Path, fixture: String, names: List<String>) {
        val classes = Files.createDirectories(root.resolve("classes"))
        val packageName = "probe.$fixture"
        for (name in listOf("Entry", "Used", "Unused") + names) {
            val resource = "${packageName.replace('.', '/')}/$name.class"
            val target = classes.resolve(resource)
            Files.createDirectories(target.parent)
            Files.write(target, requireNotNull(javaClass.getResourceAsStream("/$resource")).use { it.readAllBytes() })
        }
        runMain(classes, "$packageName.Entry")
        assertModel(classes, packageName, true)
    }

    private fun assertModel(classes: Path, packageName: String, resolved: Boolean) {
        val indexed = ClassFileIndexer().indexWithObservations(listOf(classes))
        val owner = packageName.replace('.', '/')
        val call = indexed.graph.externalCalls.single { it.name == "forName" }
        assertEquals("jdk.class-loading.v1", call.model)
        assertEquals(if (resolved) listOf("class:$owner/Used") else emptyList(), call.resolvedTargets.map { it.value })
        val construction = indexed.graph.externalCalls.single { it.name == "newInstance" }
        assertEquals(if (resolved) listOf("method:$owner/Used#<init>()V") else emptyList(), construction.resolvedTargets.map { it.value })
        val edges = indexed.graph.edges.filter { it.origin == EdgeOrigin.RUNTIME_MODEL }
        assertTrue(edges.none { "Unused" in it.target.value })
        if (resolved) assertTrue(edges.any { it.source == call.caller && it.target in construction.resolvedTargets })
        assertEquals(if (resolved) 0 else 1, indexed.observations.sumOf { it.reflectionCalls })
        assertEquals(if (resolved) 0 else 1, indexed.observations.sumOf { it.reflectiveConstructions })
        assertEquals(0, indexed.observations.sumOf { it.valueAnalysisLimits })
    }

    private fun runMain(classes: Path, name: String, arguments: Array<String> = emptyArray()) {
        URLClassLoader(arrayOf(classes.toUri().toURL()), javaClass.classLoader).use { loader ->
            loader.loadClass(name).getMethod("main", Array<String>::class.java).invoke(null, arguments as Any)
        }
    }

    private fun compile(root: Path, source: String, release: String = "17"): Path {
        val file = root.resolve("Entry.java")
        Files.writeString(file, source)
        val classes = Files.createDirectories(root.resolve("classes"))
        val errors = ByteArrayOutputStream()
        assertEquals(0, ToolProvider.getSystemJavaCompiler().run(null, null, errors, "--release", release, "-g", "-d", classes.toString(), file.toString()), errors.toString())
        return classes
    }
}
