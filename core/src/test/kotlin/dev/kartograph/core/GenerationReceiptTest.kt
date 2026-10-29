package dev.kartograph.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class GenerationReceiptTest {
    @Test
    fun `receipt requires all observed inputs exact output mapping and canonical paths`() {
        val receipt = receipt()
        receipt.requireValid()
        val invalid: List<() -> Unit> = listOf(
            { receipt.copy(policy = "suffix-guess") }, { receipt.copy(scope = "bad scope") },
            { receipt.copy(task = "generate") }, { receipt.copy(attempt = "a") },
            { receipt.copy(outputRoot = "../generated") }, { receipt.copy(mapping = receipt.mapping.copy(role = "options")) },
            { receipt.copy(inputs = receipt.inputs.drop(1)) }, { receipt.copy(inputs = receipt.inputs + receipt.inputs.first()) },
            { receipt.copy(inputs = receipt.inputs + input("unknown", "config/unknown")) },
            { receipt.copy(outputs = receipt.outputs + receipt.outputs.first()) },
            { receipt.copy(outputs = listOf(input("generatedSource", "other/Foo.java"))) },
            { receipt.copy(outputs = listOf(receipt.outputs.single().copy(role = "sources"))) },
            { receipt.copy(mapping = receipt.mapping.copy(path = receipt.outputs.single().path)) },
            { receipt.copy(sources = receipt.sources + receipt.sources.single()) },
            { receipt.copy(sources = listOf(receipt.sources.single().copy(sha256 = "b".repeat(64)))) },
            { receipt.copy(sources = listOf(receipt.sources.single().copy(input = "res/navigation/absent.xml"))) },
            { GenerationSource("nav.xml", "out/Foo.kt", "demo.Foo", HASH) },
            { GenerationSource("nav.xml", "out/Foo.java", "demo..Foo", HASH) },
        )
        invalid.forEach { assertFailsWith<IllegalArgumentException> { it() } }
        for (path in listOf("/root", "a/./b", "a/../b", "a//b", "a\\b", "C:/b", "a\n", "a\u007f", "a/")) {
            assertFailsWith<IllegalArgumentException> { receipt.copy(outputRoot = path) }
        }
        // 생성할 타입이 없는 navigation도 성공한 빈 관측으로 표현한다.
        receipt.copy(outputs = emptyList(), sources = emptyList()).requireValid()
        GenerationSource("res/😀/main.xml", "out/😀/Foo.java", "demo.\uD801\uDC00Foo", HASH)
        assertFailsWith<IllegalArgumentException> { GenerationSource("nav.xml", "out/Foo.java", "demo.\uD800", HASH) }
    }

    @Test
    fun `declarations preserve compiler class identity and mapped source ownership`() {
        val declaration = declaration()
        val evidence = evidence(listOf(receipt()), listOf(declaration))
        val invalid: List<() -> Unit> = listOf(
            { declaration.copy(classPath = "demo/Other.class") }, { declaration.copy(classPath = "demo/Foo.txt") },
            { declaration.copy(symbols = emptyList()) }, { declaration.copy(symbols = listOf(METHOD)) },
            { declaration.copy(symbols = declaration.symbols + METHOD) },
            { declaration.copy(symbols = listOf(OWNER, NodeId("method:demo/Other#run()V"))) },
            { declaration.copy(compilerTask = "compile") }, { declaration.copy(sourceSha256 = "bad") },
            { evidence.copy(receipts = emptyList()) }, { evidence.copy(receipts = listOf(receipt(), receipt())) },
            { evidence.copy(declarations = listOf(declaration, declaration)) },
            { evidence.copy(declarations = listOf(declaration.copy(attempt = "b".repeat(64)))) },
            { evidence.copy(declarations = listOf(declaration.copy(generatorTask = ":app:other"))) },
            { evidence.copy(declarations = listOf(declaration.copy(source = "build/generated/demo/Missing.java"))) },
            { evidence.copy(declarations = listOf(declaration.copy(sourceSha256 = "b".repeat(64)))) },
            { evidence.copy(receipts = listOf(receipt().copy(sources = listOf(receipt().sources.single().copy(qualifiedName = "demo.Other"))))) },
        )
        invalid.forEach { assertFailsWith<IllegalArgumentException> { it() } }
        val inner = NodeId("class:demo/Foo\$Action")
        evidence.copy(declarations = listOf(declaration.copy(owner = inner, symbols = listOf(inner), classPath = "demo/Foo\$Action.class")))
    }

    @Test
    fun `graph matching is bidirectional and does not alter calls or retention`() {
        val evidence = evidence(listOf(receipt()), listOf(declaration()))
        val graph = graph()
        val provenance = provenance()
        evidence.requireMatches(graph, provenance, SCOPE)
        assertEquals(2, graph.edges.size)
        assertEquals(EdgeKind.CALL, graph.edges.last().kind)
        val invalid: List<() -> Unit> = listOf(
            { evidence.requireMatches(graph, provenance, "app:release") },
            { evidence.requireMatches(graph, provenance.copy(memberScopes = listOf(SCOPE)), SCOPE) },
            { evidence.requireMatches(graph, provenance.copy(witnesses = emptyList()), SCOPE) },
            { evidence.requireMatches(graph, provenance.copy(witnesses = provenance.witnesses.map { it.copy(scope = "app:release") }), SCOPE) },
            { evidence.requireMatches(graph, provenance.copy(witnesses = provenance.witnesses + provenance.witnesses), SCOPE) },
            { evidence.requireMatches(graph, provenance.copy(inputs = emptyList()), SCOPE) },
            { evidence.requireMatches(graph, provenance.copy(inputs = provenance.inputs + provenance.inputs), SCOPE) },
            { evidence.requireMatches(CodeGraph(graph.nodes.values, emptyList()), provenance, SCOPE) },
            { evidence.requireMatches(CodeGraph(graph.nodes.values.filter { it.id != METHOD }, emptyList()), provenance, SCOPE) },
            { evidence.requireMatches(CodeGraph(graph.nodes.values.map { it.copy(synthesized = false) }, graph.edges), provenance, SCOPE) },
            { evidence.requireMatches(CodeGraph(graph.nodes.values.map { it.copy(attributes = emptySet()) }, graph.edges), provenance, SCOPE) },
            { evidence.requireMatches(CodeGraph(graph.nodes.values.map { if (it.id == OWNER) it.copy(kind = NodeKind.INTERFACE) else it }, graph.edges), provenance, SCOPE) },
            { evidence.requireMatches(CodeGraph(graph.nodes.values.map { it.copy(attributes = it.attributes + NodeAttribute.EXTERNAL_STUB) }, graph.edges), provenance, SCOPE) },
            { evidence.copy(declarations = emptyList()).requireMatches(graph, provenance, SCOPE) },
        )
        invalid.forEach { assertFailsWith<IllegalArgumentException> { it() } }
    }

    @Test
    fun `unchanged class in observed transformed root requires every exact provenance link`() {
        val original = input("classes", "build/original")
        val selected = input("classes", "build/transformed", "b".repeat(64))
        val observation = ClassTransformObservation(SCOPE, ":app:snapshot", "hilt-superclass-v1", listOf(original), listOf(selected),
            listOf(TransformedClass("demo/App", HASH, "b".repeat(64))))
        val provenance = provenance().copy(inputs = listOf(selected), transformations = listOf(observation))
        val declaration = declaration().copy(selectedRoot = selected.path)
        val evidence = evidence(listOf(receipt()), listOf(declaration), provenance)
        evidence.requireMatches(graph(), provenance, SCOPE)
        for (bad in listOf(
            provenance.copy(transformations = emptyList()),
            provenance.copy(transformations = listOf(observation, observation)),
            provenance.copy(transformations = listOf(observation.copy(scope = "other"))),
            provenance.copy(transformations = listOf(observation.copy(changedClasses = listOf(TransformedClass("demo/Foo", HASH, "b".repeat(64)))))),
            provenance.copy(transformations = listOf(observation.copy(originalOutputs = listOf(original, input("classes", "unknown"))))),
            provenance.copy(transformations = listOf(observation.copy(selectedOutputs = listOf(selected, input("classes", "unknown"))))),
        )) assertFailsWith<IllegalArgumentException> {
            // 새 digest가 일치해도 원래 artifact 연결 검증이 독립적으로 거부해야 한다.
            evidence.copy(observations = evidence.observations.map {
                it.copy(currentDeclarationsSha256 = GenerationDigests.declarations(evidence.declarations, bad))
            }).requireMatches(graph(), bad, SCOPE)
        }
    }

    @Test
    fun `total limits prevent multiplying per receipt inventories`() {
        val tooManyArtifacts = (1..255).map { input("generatorArtifact", "external/extra-$it") }
        assertFailsWith<IllegalArgumentException> { receipt().copy(inputs = receipt().inputs + tooManyArtifacts) }
        val receipts = (1..129).map { receipt().copy(task = ":app:gen$it") }
        assertFailsWith<IllegalArgumentException> { evidence(receipts, emptyList()) }
        assertFailsWith<IllegalArgumentException> { GenerationEvidence(List(257) { receipt() }, emptyList(), emptyList()) }
        val huge = receipt().copy(inputs = receipt().inputs + (1..50_000).map { input("navigation", "res/navigation/n$it.xml") })
        assertFailsWith<IllegalArgumentException> { GenerationEvidence(listOf(huge, huge.copy(task = ":app:second")), emptyList(), emptyList()) }
    }

    companion object {
        private const val SCOPE = "app:debug"
        private val HASH = "a".repeat(64)
        private val OWNER = NodeId("class:demo/Foo")
        private val METHOD = NodeId("method:demo/Foo#run()V")
        private fun input(role: String, path: String, hash: String = HASH) = InputFingerprint(role, path, hash)
        private fun receipt() = GenerationReceipt(SCOPE, ":app:generateSafeArgsDebug", GenerationReceipt.POLICY, HASH, "build/generated",
            listOf(input("navigation", "res/navigation/main.xml"), input("generatorArtifact", "external/generator-0"),
                input("generatorActionArtifact", "external/action-0"), input("generatorOptions", "config/options")),
            input("generationMapping", "build/mapping.json"), listOf(input("generatedSource", "build/generated/demo/Foo.java")),
            listOf(GenerationSource("res/navigation/main.xml", "build/generated/demo/Foo.java", "demo.Foo", HASH)))
        private fun declaration() = GeneratedDeclaration(SCOPE, ":app:generateSafeArgsDebug", HASH, ":app:compileDebugJavaWithJavac",
            "build/generated/demo/Foo.java", HASH, OWNER, listOf(OWNER, METHOD), "build/original", "build/original", "demo/Foo.class", HASH)
        private fun evidence(receipts: List<GenerationReceipt>, rows: List<GeneratedDeclaration>, provenance: SnapshotProvenance = provenance()) =
            GenerationEvidence(receipts, rows, receipts.map { receipt ->
                val context = GenerationOriginContext(GenerationDigests.receipt(receipt), receipt.scope, receipt.task, "debug", receipt.outputRoot, receipt.mapping.path,
                    listOf(GenerationProviderBinding("navigationFiles", receipt.inputs.filter { it.role == "navigation" }.map { it.path }),
                        GenerationProviderBinding("outputDir", listOf(receipt.outputRoot)), GenerationProviderBinding("incrementalFolder", listOf("build"))),
                    GenerationOriginContext.ACTION_POLICY, receipt.inputs.filter { it.role == "generatorActionArtifact" })
                GenerationConsumptionObservation(context, context, HASH, receipt.attempt, "current-cold-success", "SUCCEEDED",
                    GenerationDigests.inventory(receipt), GenerationDigests.declarations(rows.filter { it.generatorTask == receipt.task }, provenance))
            })
        private fun graph() = CodeGraph(listOf(GraphNode(OWNER, "Foo", NodeKind.CLASS, synthesized = true,
            attributes = setOf(NodeAttribute.GENERATED_SOURCE_EVIDENCE)), GraphNode(METHOD, "run", NodeKind.METHOD, synthesized = true,
            attributes = setOf(NodeAttribute.GENERATED_SOURCE_EVIDENCE))), listOf(GraphEdge(OWNER, METHOD, EdgeKind.MEMBER), GraphEdge(METHOD, METHOD, EdgeKind.CALL)))
        private fun provenance() = SnapshotProvenance(listOf(input("classes", "build/original")), listOf(BuildWitness(SCOPE, "javac", ":app:compileDebugJavaWithJavac",
            listOf(input("sources", "build/generated"), input("buildConfig", "build.gradle"), input("compiler", "external/compiler"), input("options", "config/javac")),
            listOf(input("classes", "build/original")))))
    }
}
