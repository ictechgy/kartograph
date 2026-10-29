package dev.kartograph.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotEquals

class GenerationObservationTest {
    @Test
    fun `only current cold success and actual up to date historical reuse are admitted`() {
        val cold = observation()
        val warm = cold.copy(originBasis = "historical-byte-equivalence", currentGenerationStatus = "UP_TO_DATE", currentAttempt = null)
        for (admitted in listOf(cold, warm)) evidence(admitted).requireMatches(graph(), provenance(), SCOPE)
        assertEquals("SUCCEEDED", cold.currentGenerationStatus)
        assertEquals("UP_TO_DATE", warm.currentGenerationStatus)
        for (status in listOf("FAILED", "PENDING", "UNKNOWN", "FROM_CACHE", "SKIPPED", "EXCLUDED", "CONFIGURATION_CACHE_UNKNOWN", "")) {
            for (basis in listOf("current-cold-success", "historical-byte-equivalence")) {
                assertFailsWith<IllegalArgumentException> { cold.copy(originBasis = basis, currentGenerationStatus = status) }
            }
        }
        for (bad in listOf<() -> Unit>(
            { cold.copy(currentAttempt = null) }, { warm.copy(currentAttempt = receipt().attempt) },
            { cold.copy(currentGenerationStatus = "UP_TO_DATE") }, { warm.copy(currentGenerationStatus = "SUCCEEDED") },
            { cold.copy(originBasis = "inferred-success") }, { cold.copy(invocation = "not-a-nonce") },
            { evidence(cold.copy(currentAttempt = OTHER)) },
        )) assertFailsWith<IllegalArgumentException> { bad() }
    }

    @Test
    fun `mandatory contexts and observations cannot be omitted duplicated orphaned or reused for changed inputs`() {
        val value = evidence()
        for (bad in listOf<() -> Unit>(
            { value.copy(observations = emptyList()) }, { value.copy(observations = value.observations + value.observations) },
            { value.copy(receipts = listOf(receipt().copy(attempt = OTHER))) },
            { value.copy(receipts = listOf(receipt().copy(inputs = receipt().inputs.map { if (it.role == "generatorOptions") it.copy(sha256 = OTHER) else it }))) },
            { value.copy(receipts = listOf(receipt().copy(outputs = receipt().outputs + input("generatedSource", "build/generated/Extra.java")))) },
            { value.copy(receipts = listOf(receipt().copy(outputs = emptyList(), sources = emptyList()))) },
            { value.copy(receipts = listOf(receipt().copy(mapping = receipt().mapping.copy(sha256 = OTHER)))) },
            { value.copy(observations = listOf(observation().copy(currentInventorySha256 = OTHER))) },
            { value.copy(observations = listOf(observation(context().copy(task = ":app:other")))) },
            { value.copy(observations = listOf(observation(context().copy(actionArtifacts = listOf(input("generatorActionArtifact", "external/action", OTHER)))))) },
            { value.copy(observations = listOf(observation(context().copy(providerBindings = context().providerBindings.map {
                if (it.property == "navigationFiles") it.copy(paths = listOf("res/navigation/other.xml")) else it
            })))) },
        )) assertFailsWith<IllegalArgumentException> { bad() }
        val second = receipt().copy(task = ":app:second")
        val secondContext = context(second)
        val secondObservation = observation(secondContext).copy(invocation = OTHER)
        assertFailsWith<IllegalArgumentException> { GenerationEvidence(listOf(receipt(), second), listOf(declaration()), listOf(observation(), secondObservation)) }
    }

    @Test
    fun `provider variant and action changes remain unverified even when source bytes match`() {
        val value = observation()
        val context = context()
        val changed = listOf(
            context.copy(variant = "release"), context.copy(scope = "app:release"), context.copy(task = ":app:other"),
            context.copy(receiptSha256 = OTHER), context.copy(actionArtifacts = listOf(input("generatorActionArtifact", "external/action", OTHER))),
            context.copy(providerBindings = context.providerBindings.map { if (it.property == "navigationFiles") it.copy(paths = listOf("other.xml")) else it }),
        )
        changed.forEach { assertFailsWith<IllegalArgumentException> { value.copy(currentContext = it) } }
        for (bad in listOf<() -> Unit>(
            { context.copy(actionPolicy = "same-wrapper-class") }, { context.copy(variant = "bad variant") },
            { context.copy(providerBindings = context.providerBindings.drop(1)) },
            { context.copy(providerBindings = listOf(context.providerBindings.first(), context.providerBindings.first(), context.providerBindings.last())) },
            { context.copy(outputRoot = "another/root") }, { context.copy(mappingPath = "other/map.json") },
            { context.copy(actionArtifacts = emptyList()) }, { context.copy(actionArtifacts = context.actionArtifacts + context.actionArtifacts) },
            { context.copy(actionArtifacts = listOf(input("generatorArtifact", "external/action"))) },
            { GenerationProviderBinding("unknown", listOf("x")) }, { GenerationProviderBinding("outputDir", listOf("a", "b")) },
            { GenerationProviderBinding("navigationFiles", listOf("a", "a")) }, { GenerationProviderBinding("navigationFiles", listOf("../a")) },
        )) assertFailsWith<IllegalArgumentException> { bad() }
    }

    @Test
    fun `current declaration digest binds compiler sources options evidence and selected artifact bytes`() {
        val value = evidence()
        val p = provenance()
        val witness = p.witnesses.single()
        val changes = witness.inputs.map { changed -> p.copy(witnesses = listOf(witness.copy(inputs = witness.inputs.map { if (it == changed) it.copy(sha256 = OTHER) else it }))) } + listOf(
            p.copy(inputs = listOf(input("classes", "build/classes", OTHER))),
            p.copy(witnesses = listOf(witness.copy(outputs = listOf(input("classes", "build/classes", OTHER))))),
            p.copy(witnesses = listOf(witness.copy(compilerEvidence = listOf(input("compilerEvidence", "build/calls.json")), evidenceToken = OTHER))),
        )
        changes.forEach { assertFailsWith<IllegalArgumentException> { value.requireMatches(graph(), it, SCOPE) } }
        assertFailsWith<IllegalArgumentException> {
            value.copy(declarations = listOf(declaration().copy(classSha256 = OTHER))).requireMatches(graph(), p, SCOPE)
        }
        assertFailsWith<IllegalArgumentException> {
            value.copy(observations = listOf(observation().copy(currentDeclarationsSha256 = OTHER))).requireMatches(graph(), p, SCOPE)
        }
    }

    @Test
    fun `canonical digests preserve exact identities but ignore inventory ordering`() {
        val r = receipt()
        assertEquals(GenerationDigests.receipt(r), GenerationDigests.receipt(r.copy(inputs = r.inputs.reversed())))
        assertEquals(GenerationDigests.inventory(r), GenerationDigests.inventory(r.copy(attempt = OTHER)))
        assertNotEquals(GenerationDigests.receipt(r), GenerationDigests.receipt(r.copy(attempt = OTHER)))
        val c = context()
        assertEquals(GenerationDigests.context(c), GenerationDigests.context(c.copy(providerBindings = c.providerBindings.reversed())))
        val p = provenance()
        assertEquals(GenerationDigests.declarations(listOf(declaration()), p), GenerationDigests.declarations(listOf(declaration()),
            p.copy(witnesses = p.witnesses.map { it.copy(inputs = it.inputs.reversed()) })))
        assertNotEquals(GenerationDigests.context(c), GenerationDigests.context(c.copy(variant = "Debug")))
        val mutated = r.copy(inputs = r.inputs.map { if (it.role == "navigation") it.copy(path = "res/navigation/Main.xml") else it },
            sources = r.sources.map { it.copy(input = "res/navigation/Main.xml") })
        assertNotEquals(GenerationDigests.inventory(r), GenerationDigests.inventory(mutated))
        assertFailsWith<IllegalArgumentException> {
            GenerationDigests.declarations(listOf(declaration()), p.copy(inputs = listOf(input("classes", "build/\uD800"))))
        }
    }

    @Test
    fun `mutable context collections are revalidated at evidence boundary`() {
        val paths = mutableListOf("res/navigation/main.xml")
        val c = context().copy(providerBindings = context().providerBindings.map { if (it.property == "navigationFiles") it.copy(paths = paths) else it })
        val value = evidence(observation(c))
        paths += "res/navigation/extra.xml"
        assertFailsWith<IllegalArgumentException> { value.requireValid() }
    }

    companion object {
        private const val SCOPE = "app:debug"
        private val HASH = "a".repeat(64)
        private val OTHER = "b".repeat(64)
        private val OWNER = NodeId("class:demo/Foo")
        private fun input(role: String, path: String, hash: String = HASH) = InputFingerprint(role, path, hash)
        private fun receipt() = GenerationReceipt(SCOPE, ":app:generateSafeArgsDebug", GenerationReceipt.POLICY, HASH, "build/generated",
            listOf(input("navigation", "res/navigation/main.xml"), input("generatorArtifact", "external/generator"),
                input("generatorActionArtifact", "external/action"), input("generatorOptions", "config/options")),
            input("generationMapping", "build/mapping.json"), listOf(input("generatedSource", "build/generated/demo/Foo.java")),
            listOf(GenerationSource("res/navigation/main.xml", "build/generated/demo/Foo.java", "demo.Foo", HASH)))
        private fun context(receipt: GenerationReceipt = receipt()) = GenerationOriginContext(GenerationDigests.receipt(receipt), receipt.scope, receipt.task, "debug", receipt.outputRoot, receipt.mapping.path,
            listOf(GenerationProviderBinding("navigationFiles", listOf("res/navigation/main.xml")), GenerationProviderBinding("outputDir", listOf(receipt.outputRoot)),
                GenerationProviderBinding("incrementalFolder", listOf("build"))), GenerationOriginContext.ACTION_POLICY, receipt.inputs.filter { it.role == "generatorActionArtifact" })
        private fun observation(context: GenerationOriginContext = context()) = GenerationConsumptionObservation(context, context, HASH, HASH, "current-cold-success", "SUCCEEDED",
            GenerationDigests.inventory(receipt()), GenerationDigests.declarations(listOf(declaration()), provenance()))
        private fun evidence(observation: GenerationConsumptionObservation = observation()) = GenerationEvidence(listOf(receipt()), listOf(declaration()), listOf(observation))
        private fun declaration() = GeneratedDeclaration(SCOPE, ":app:generateSafeArgsDebug", HASH, ":app:compileDebugJavaWithJavac",
            "build/generated/demo/Foo.java", HASH, OWNER, listOf(OWNER), "build/classes", "build/classes", "demo/Foo.class", HASH)
        private fun graph() = CodeGraph(listOf(GraphNode(OWNER, "Foo", NodeKind.CLASS, synthesized = true, attributes = setOf(NodeAttribute.GENERATED_SOURCE_EVIDENCE))), emptyList())
        private fun provenance() = SnapshotProvenance(listOf(input("classes", "build/classes")), listOf(BuildWitness(SCOPE, "javac", ":app:compileDebugJavaWithJavac",
            listOf(input("sources", "build/generated"), input("buildConfig", "build.gradle"), input("compiler", "external/compiler"), input("options", "config/javac")), listOf(input("classes", "build/classes")))))
    }
}
