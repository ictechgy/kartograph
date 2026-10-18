package dev.kartograph.index

import dev.kartograph.core.*
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.*
import org.junit.jupiter.api.io.TempDir
import org.objectweb.asm.ClassWriter
import org.objectweb.asm.Opcodes

class AndroidResourceBinderTest {
    @Test
    fun `actual indexed flags and exact selected output bind every defined key`(@TempDir directory: Path) {
        val fixture = fixture(directory)
        val result = assertIs<AndroidResourceBindingResult.Captured>(fixture.bind()).evidence
        assertEquals(setOf("screen", "other"), result.outcomes.map { it.key.name }.toSet())
        assertEquals(setOf("field:dev/one/R\$layout#screen:I", "field:dev/one/R\$layout#other:I"),
            result.outcomes.map { it.field!!.value }.toSet())
        result.requireMatches(fixture.facts, fixture.provenance, fixture.indexed.graph, SCOPE)
    }

    @Test
    fun `final missing nonstatic and wrong descriptor fields remain explicit outcomes`(@TempDir directory: Path) {
        for ((name, access, descriptor, expected) in listOf(
            Case("final", Opcodes.ACC_PUBLIC or Opcodes.ACC_STATIC or Opcodes.ACC_FINAL, "I", AndroidResourceFieldUnavailable.FINAL_FIELD),
            Case("instance", Opcodes.ACC_PUBLIC, "I", AndroidResourceFieldUnavailable.NOT_STATIC_INT),
            Case("long", Opcodes.ACC_PUBLIC or Opcodes.ACC_STATIC, "J", AndroidResourceFieldUnavailable.FIELD_MISSING),
        )) {
            val fixture = fixture(directory.resolve(name), access = access, descriptor = descriptor)
            val result = assertIs<AndroidResourceBindingResult.Captured>(fixture.bind()).evidence
            assertTrue(result.outcomes.all { it.field == null && it.unavailable == expected })
        }
        val fixture = fixture(directory.resolve("missing"), fields = listOf("screen"))
        val result = assertIs<AndroidResourceBindingResult.Captured>(fixture.bind()).evidence
        assertNotNull(result.outcomes.single { it.key.name == "screen" }.field)
        assertEquals(AndroidResourceFieldUnavailable.FIELD_MISSING, result.outcomes.single { it.key.name == "other" }.unavailable)
    }

    @Test
    fun `classpath winner is preserved and another output cannot lend its declarations`(@TempDir directory: Path) {
        val fixture = fixture(directory)
        val shadow = directory.resolve("shadow")
        writeClass(shadow, "dev/one", Opcodes.ACC_PUBLIC or Opcodes.ACC_STATIC, "I", listOf("screen", "other"))
        val shadowInput = ContentFingerprint.capture(directory, shadow, "classes", "shadow")
        val roots = listOf(shadowInput, fixture.output)
        val indexed = ClassFileIndexer().indexWithObservations(listOf(shadow, directory.resolve("classes")))
        val provenance = fixture.provenance.copy(inputs = roots)
        val result = assertIs<AndroidResourceBindingResult.Captured>(fixture.bind(indexed = indexed, provenance = provenance)).evidence
        assertTrue(result.outcomes.all { it.unavailable == AndroidResourceFieldUnavailable.OUTPUT_NOT_SELECTED })
        val reversed = ClassFileIndexer().indexWithObservations(listOf(directory.resolve("classes"), shadow))
        val selected = assertIs<AndroidResourceBindingResult.Captured>(fixture.bind(indexed = reversed,
            provenance = provenance.copy(inputs = roots.reversed()))).evidence
        assertTrue(selected.outcomes.all { it.field != null })
    }

    @Test
    fun `namespace and scope do not borrow same named layout fields`(@TempDir directory: Path) {
        val one = fixture(directory.resolve("one"))
        val two = fixture(directory.resolve("two"), namespace = "dev.two")
        val first = assertIs<AndroidResourceBindingResult.Captured>(one.bind()).evidence
        val second = assertIs<AndroidResourceBindingResult.Captured>(two.bind()).evidence
        assertTrue(first.outcomes.mapNotNull { it.field }.toSet().intersect(second.outcomes.mapNotNull { it.field }.toSet()).isEmpty())
        assertIs<AndroidResourceBindingResult.Unavailable>(one.bind(provenance = one.provenance.copy(
            witnesses = one.provenance.witnesses.map { it.copy(scope = ":other:debug") })))
        val wrongGraph = assertIs<AndroidResourceBindingResult.Captured>(one.bind(indexed = two.indexed)).evidence
        assertTrue(wrongGraph.outcomes.all { it.unavailable == AndroidResourceFieldUnavailable.FIELD_MISSING })
    }

    @Test
    fun `legacy ownership incomplete roots duplicate producers and wrong outputs refuse whole scope`(@TempDir directory: Path) {
        val f = fixture(directory)
        val old = IndexedClasses(f.indexed.graph, emptyList())
        assertEquals(AndroidResourceBindingUnavailable.OWNERSHIP_UNAVAILABLE,
            assertIs<AndroidResourceBindingResult.Unavailable>(f.bind(indexed = old)).reason)
        assertEquals(AndroidResourceBindingUnavailable.INVENTORY_MISMATCH,
            assertIs<AndroidResourceBindingResult.Unavailable>(f.bind(mainRoots = emptyList())).reason)
        assertEquals(AndroidResourceBindingUnavailable.PRODUCER_AMBIGUOUS,
            assertIs<AndroidResourceBindingResult.Unavailable>(f.bind(provenance = f.provenance.copy(witnesses = f.provenance.witnesses + f.provenance.witnesses))).reason)
        assertEquals(AndroidResourceBindingUnavailable.OUTPUT_UNAVAILABLE,
            assertIs<AndroidResourceBindingResult.Unavailable>(f.bind(provenance = f.provenance.copy(inputs = emptyList()))).reason)
        val noRole = f.provenance.copy(witnesses = f.provenance.witnesses.map { w ->
            w.copy(inputs = w.inputs.filter { it.role != "android-resources" }) })
        assertEquals(AndroidResourceBindingUnavailable.PRODUCER_UNAVAILABLE,
            assertIs<AndroidResourceBindingResult.Unavailable>(f.bind(provenance = noRole)).reason)
    }

    @Test
    fun `source location enrichment is harmless but removed bound fields refuse`(@TempDir directory: Path) {
        val f = fixture(directory)
        val enriched = CodeGraph(f.indexed.graph.nodes.values.map { it.copy(location = SourceLocation("src/Resource.java", 1)) }, f.indexed.graph.edges)
        assertIs<AndroidResourceBindingResult.Captured>(f.bind(retained = enriched))
        val pruned = CodeGraph(f.indexed.graph.nodes.values.filterNot { it.id.value.endsWith("#other:I") }, f.indexed.graph.edges)
        assertEquals(AndroidResourceBindingUnavailable.GRAPH_MISMATCH,
            assertIs<AndroidResourceBindingResult.Unavailable>(f.bind(retained = pruned)).reason)
    }

    private data class Case(val name: String, val access: Int, val descriptor: String, val expected: AndroidResourceFieldUnavailable)

    private data class Fixture(val facts: AndroidResourceEvidence, val output: InputFingerprint,
        val options: InputFingerprint, val provenance: SnapshotProvenance, val indexed: IndexedClasses) {
        fun bind(indexed: IndexedClasses = this.indexed, provenance: SnapshotProvenance = this.provenance,
            mainRoots: List<InputFingerprint> = facts.roots.map { it.input }, retained: CodeGraph = indexed.graph) =
            AndroidResourceBinder.bind(facts, mainRoots, options, provenance,
                provenance.inputs.filter { it.role == "classes" }, indexed, retained, SCOPE)
    }

    private fun fixture(directory: Path, namespace: String = "dev.one",
        access: Int = Opcodes.ACC_PUBLIC or Opcodes.ACC_STATIC, descriptor: String = "I",
        fields: List<String> = listOf("screen", "other")): Fixture {
        val classes = directory.resolve("classes")
        writeClass(classes, namespace.replace('.', '/'), access, descriptor, fields)
        val resource = AndroidResourceRoot(InputFingerprint("android-resources", "src/main/res", "1".repeat(64)))
        val facts = AndroidResourceEvidence(SCOPE, namespace, listOf(resource), listOf("screen", "other").map { name ->
            AndroidLayoutDefinition(AndroidResourceKey(namespace, "layout", name), resource.id,
                CompilerEvidenceSource("src/main/res/layout/$name.xml", "2".repeat(64)), "layout/$name.xml")
        }, emptyList())
        val output = ContentFingerprint.capture(directory, classes, "classes", "classes")
        val options = InputFingerprint("options", "processDebugResources-options", AndroidResourceBindingIdentity.namespaceOptions(namespace))
        val inputs = listOf("sources", "buildConfig", "compiler").map { InputFingerprint(it, it, "3".repeat(64)) } + resource.input + options
        val witness = BuildWitness(SCOPE, "agp-process-resources", ":processDebugResources", inputs, listOf(output))
        return Fixture(facts, output, options, SnapshotProvenance(listOf(output), listOf(witness)),
            ClassFileIndexer().indexWithObservations(listOf(classes)))
    }

    private fun writeClass(root: Path, namespace: String, access: Int, descriptor: String, fields: List<String>) {
        val owner = "$namespace/R\$layout"
        val writer = ClassWriter(0)
        writer.visit(Opcodes.V17, Opcodes.ACC_PUBLIC, owner, null, "java/lang/Object", null)
        fields.forEach { writer.visitField(access, it, descriptor, null, null).visitEnd() }
        writer.visitEnd()
        val file = root.resolve("$owner.class")
        Files.createDirectories(file.parent)
        Files.write(file, writer.toByteArray())
    }

    private companion object { const val SCOPE = ":app:debug" }
}
