package dev.kartograph.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class ProcessorOutputInventoryTest {
    private val sha = "a".repeat(64)
    private val compilerInputs = ProcessorCompilerInputs(":compileJava",
        listOf(InputFingerprint("processorCompilerInput", "project/classpath.jar", sha)), sha, sha)

    private fun observation(outputs: List<ProcessorOutput>, declarations: List<ProcessorDeclaration>,
        inputs: ProcessorCompilerInputs? = compilerInputs) =
        ProcessorOutputs("app:main", "javac", "fixture.Provider", sha, sha, outputs, sha, inputs, declarations)

    private fun declaration(output: String, index: Int = 0): ProcessorDeclaration {
        val owner = NodeId("class:fixture/G$index")
        return ProcessorDeclaration(output, owner, listOf(owner))
    }

    private fun processorEvidence(sources: List<CompilerEvidenceSource>, generated: List<CompilerEvidenceSource>) =
        CompilerEvidence("javac-processors", "21", sha, sha, sources, 0, emptyList(), ProcessorGeneration("fixture.Provider", sha, generated))

    @Test fun `processor declarations still require an observed class api output`() {
        val outputs = listOf(
            ProcessorOutput("generated/A.class", "class", "api", sha),
            ProcessorOutput("generated/A.java", "source", "api", sha),
            ProcessorOutput("generated/a.properties", "resource", "api", sha),
            ProcessorOutput("direct/A.class", "file", "callback-scope", sha),
        )
        assertEquals(listOf(declaration("generated/A.class")), observation(outputs, listOf(declaration("generated/A.class"))).declarations)
        // source·resource 출력, callback 범위에서 본 파일, 관측하지 않은 경로는 실제 class 출력의 근거가 아니다.
        for (path in listOf("generated/A.java", "generated/a.properties", "direct/A.class", "generated/Missing.class")) {
            val error = assertFailsWith<IllegalArgumentException>(path) { observation(outputs, listOf(declaration(path))) }
            assertEquals("processor declarations require observed class outputs", error.message, path)
        }
        // 출력 근거 검사가 compiler 입력 근거 검사보다 먼저다.
        val unobserved = assertFailsWith<IllegalArgumentException> { observation(outputs, listOf(declaration("generated/A.java")), inputs = null) }
        assertEquals("processor declarations require observed class outputs", unobserved.message)
        val withoutInputs = assertFailsWith<IllegalArgumentException> { observation(outputs, listOf(declaration("generated/A.class")), inputs = null) }
        assertEquals("processor declarations require compiler input evidence", withoutInputs.message)
    }

    @Test fun `processor declaration checks read each output a bounded number of times`() {
        // 리뷰 지적 재현(security#4): 선언마다 출력 목록 전체를 다시 훑어, 출력·선언 각 10만 개인 snapshot 블록 하나의 parse가 20초를 넘었다.
        // 시간 대신 출력 원소를 읽은 횟수로, 검사 비용이 (선언 수 × 출력 수)로 늘지 않는지 결정적으로 본다.
        val outputs = CountingList(List(INVENTORY) { ProcessorOutput("generated/G$it.class", "class", "api", sha) })
        val observed = observation(outputs, List(INVENTORY) { declaration("generated/G$it.class", it) })
        assertEquals(INVENTORY, observed.declarations.size)
        assertTrue(outputs.reads <= READS_PER_ITEM * INVENTORY, "declaration checks read ${outputs.reads} outputs for $INVENTORY declarations")
    }

    @Test fun `processor source attribution still requires the exact observed compiler source`() {
        val observed = CompilerEvidenceSource("generated/A.java", sha)
        assertEquals(listOf(observed), processorEvidence(listOf(observed), listOf(observed)).processorGeneration?.sources)
        // 경로가 다르거나, 같은 경로라도 관찰한 바이트 지문이 다르면 compiler가 본 source가 아니다.
        for (generated in listOf(CompilerEvidenceSource("generated/B.java", sha), CompilerEvidenceSource("generated/A.java", "b".repeat(64)))) {
            val error = assertFailsWith<IllegalArgumentException>("$generated") { processorEvidence(listOf(observed), listOf(generated)) }
            assertEquals("processor outputs must be observed compiler sources", error.message, "$generated")
        }
    }

    @Test fun `processor source attribution reads each observed source a bounded number of times`() {
        // 같은 결함(security#4): 생성 source마다 관찰 source 목록을 List 포함 검사로 다시 훑어 비용이 (생성 수 × 관찰 수)로 늘었다.
        val generated = List(INVENTORY) { CompilerEvidenceSource("generated/G$it.java", sha) }
        val sources = CountingList(generated)
        assertEquals(generated, processorEvidence(sources, generated).processorGeneration?.sources)
        assertTrue(sources.reads <= READS_PER_ITEM * INVENTORY, "source attribution read ${sources.reads} sources for $INVENTORY generated sources")
    }

    /** 원소를 읽은 횟수를 세는 목록이다. 검사가 목록을 몇 번 훑는지 시간 대신 결정적으로 확인한다. */
    private class CountingList<T>(private val items: List<T>) : AbstractList<T>() {
        var reads = 0
            private set

        override val size: Int get() = items.size

        override fun get(index: Int): T {
            reads++
            return items[index]
        }
    }

    private companion object {
        /** 읽기 횟수 비교에 쓰는 출력·선언 수와, 검사가 원소 하나를 읽어도 되는 상한이다. */
        const val INVENTORY = 1_000
        const val READS_PER_ITEM = 4
    }
}
