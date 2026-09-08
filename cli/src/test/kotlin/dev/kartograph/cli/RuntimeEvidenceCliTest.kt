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

class RuntimeEvidenceCliTest {
    @Test
    fun `constant queries preserve declarations but expose missing use sites`(@TempDir root: Path) {
        val fixture = compile(root, "java_constants")
        val result = query(fixture, "field:probe/Entry${'$'}Constants#USED:I")
        assertContains(result, "\"state\": \"retained\"")
        assertContains(result, "\"reason\": \"inlineConstant\"")
        assertContains(result, "inlined-constant-references:")
    }

    @Test
    fun `runtime channels are measured even when the requested symbol is absent`(@TempDir root: Path) {
        for ((case, limitation) in listOf(
            "runtime_unknown" to "class-loading:",
            "runtime_unknown" to "service-loading:",
            "runtime_unknown" to "reflective-construction:",
            "runtime_unknown" to "external-dispatch:",
        )) {
            val fixture = compile(root.resolve(limitation.removeSuffix(":")), case)
            val result = query(fixture, "MissingSymbol", 64)
            assertContains(result, limitation)
            assertContains(result, "\"status\": \"notFound\"")
        }
    }

    @Test
    fun `external calls remain observable without becoming application declarations`(@TempDir root: Path) {
        val fixture = compile(root, "external_dispatch")
        val output = ByteArrayOutputStream()
        val status = KartographCli.run(arrayOf("graph", "--classes", fixture.classes.toString(), "--format", "json"), PrintStream(output), System.err)
        assertEquals(0, status)
        assertContains(output.toString(Charsets.UTF_8), "\"externalCalls\"")
        assertContains(output.toString(Charsets.UTF_8), "method:java/util/function/Supplier#get()Ljava/lang/Object;")
    }

    @Test
    fun `unused injected declarations retain their runtime ownership reason`(@TempDir root: Path) {
        val fixture = compile(root, "inject_unused")
        assertContains(query(fixture, "class:probe/Entry${'$'}DormantService"), "dependencyInjection")
        assertContains(query(fixture, "class:probe/Entry${'$'}DormantDependency"), "\"state\": \"reachable\"")
    }

    @Test
    fun `indy bootstrap and external method handles are distinct observed facts`(@TempDir root: Path) {
        val fixture = compile(root, "indy_external")
        val graph = dev.kartograph.index.ClassFileIndexer().index(listOf(fixture.classes))
        kotlin.test.assertTrue(graph.externalCalls.any { it.kind == dev.kartograph.core.InvocationKind.BOOTSTRAP })
        kotlin.test.assertTrue(graph.externalCalls.any { it.owner == "java/lang/String" && it.name == "trim" })
    }

    @Test
    fun `annotation default counters traverse arrays enums and nested values`(@TempDir root: Path) {
        val fixture = compile(root, "annotation_defaults_nested")
        val graph = dev.kartograph.index.ClassFileIndexer().index(listOf(fixture.classes))
        kotlin.test.assertTrue(graph.edges.any { it.source.value == "class:probe/Entry${'$'}Defaults" && it.target.value == "class:probe/Entry${'$'}A" })
        kotlin.test.assertFalse(query(fixture, "MissingSymbol", 64).contains("annotation-default-values:"))
    }

    private fun query(fixture: Fixture, symbol: String, expected: Int = 0): String {
        val output = ByteArrayOutputStream()
        val error = ByteArrayOutputStream()
        val status = KartographCli.run(arrayOf("query", symbol, "--classes", fixture.classes.toString(),
            "--project", fixture.root.toString(), "--keep-rules", "keep.pro"), PrintStream(output), PrintStream(error))
        assertEquals(expected, status, error.toString(Charsets.UTF_8))
        return output.toString(Charsets.UTF_8)
    }

    private fun compile(root: Path, case: String): Fixture {
        val source = root.resolve("src/probe/Entry.java")
        Files.createDirectories(source.parent)
        val text = requireNotNull(javaClass.getResourceAsStream("/$case/src/probe/Entry.java")).bufferedReader().use { it.readText() }
        Files.writeString(source, text)
        val classes = Files.createDirectories(root.resolve("classes"))
        val error = ByteArrayOutputStream()
        assertEquals(0, ToolProvider.getSystemJavaCompiler().run(null, null, error, "-g", "-classpath", Path.of(javax.inject.Inject::class.java.protectionDomain.codeSource.location.toURI()).toString(), "-d", classes.toString(), source.toString()), error.toString(Charsets.UTF_8))
        Files.writeString(root.resolve("keep.pro"), "-keep class probe.Entry { *; }\n")
        return Fixture(root, classes)
    }

    private data class Fixture(val root: Path, val classes: Path)
}
