package dev.kartograph.index

import dev.kartograph.analysis.LanguageTraversal
import dev.kartograph.analysis.TraversalDirection
import dev.kartograph.analysis.TraversalDispatch
import dev.kartograph.analysis.TraversalEvidence
import dev.kartograph.analysis.TraversalReached
import dev.kartograph.core.CodeGraph
import dev.kartograph.core.InvocationKind
import dev.kartograph.core.NodeId
import dev.kartograph.core.ParameterUseKind
import dev.kartograph.index.fixture.CallbackFlowFixture
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import org.junit.jupiter.api.io.TempDir

/**
 * 실제 Kotlin 컴파일 결과에서 콜백 값 흐름을 관측하고, 역방향 순회가 람다를 실행하는 함수를 호출 문맥 안에서만 잇는지
 * 확인한다. 모양별 기대값은 [CallbackFlowFixture]의 주석과 같다.
 */
class CallbackFlowIndexTest {
    private val owner = "dev/kartograph/index/fixture/CallbackFlowFixture"
    private val classesRoot: Path
        get() = Path.of(requireNotNull(CallbackFlowFixture::class.java.protectionDomain.codeSource).location.toURI())
    private val graph: CodeGraph by lazy { ClassFileIndexer().index(listOf(classesRoot)) }
    private val route = "method:$owner#route(Ljava/lang/String;)I"

    private fun method(name: String): NodeId =
        graph.nodes.keys.single { it.value.startsWith("method:$owner#$name(") }

    private fun reached(dispatch: TraversalDispatch = TraversalDispatch.CANDIDATES): Map<String, TraversalReached> =
        LanguageTraversal.traverse(graph, listOf(route), TraversalDirection.DEPENDENTS, dispatch).reached
            .filter { it.node.id.value.startsWith("method:$owner#") }.associateBy { it.node.name }

    @Test
    fun `lambda arguments and callback parameter uses are observed`() {
        val direct = method("direct")
        val argument = graph.callbackArguments.single { it.caller == method("screenDirect") && it.callee == direct }
        assertEquals(InvocationKind.VIRTUAL, argument.invocation)
        assertEquals(0, argument.argument)
        assertEquals("invoke()Ljava/lang/Object;", argument.samMethod)
        val uses = graph.parameterUses.filter { it.method == direct && it.parameter == 0 }.map { it.kind }.toSet()
        assertTrue(ParameterUseKind.DECLARED in uses && ParameterUseKind.RECEIVER in uses, "direct parameter uses: $uses")
        // 지역 class 람다는 samMethod 없이 class 정점을 가리킨다.
        val classLambda = graph.callbackArguments.single { it.caller == method("screenClassLambda") }
        assertNull(classLambda.samMethod)
        assertTrue(classLambda.lambda.value.startsWith("class:"))
        assertTrue(graph.callbackArguments.any { it.caller == method("screenAnonymous") && it.lambda.value.startsWith("class:") })
        // 분기에서 합쳐진 인자는 두 출처를 모두 기록한다.
        assertEquals(2, graph.callbackArguments.count { it.caller == method("screenMerged") && it.callee == direct })
    }

    @Test
    fun `inline lambda bodies are copied into the caller instead of passed`() {
        assertTrue(graph.callbackArguments.none { it.caller == method("screenInline") })
        val rows = reached()
        assertEquals(TraversalEvidence.DIRECT, rows.getValue("screenInline").evidence)
        assertFalse("inlined" in rows, "an inline function body is not a separate callee")
    }

    @Test
    fun `functions that invoke a lambda argument are linked with bound evidence`() {
        val rows = reached()
        listOf("direct", "forwardOuter", "forwardInner", "sam", "runnable", "restartable").forEach { name ->
            val row = rows[name] ?: error("$name must be reached through the callback flow")
            assertEquals(TraversalEvidence.BOUND, row.evidence, name)
        }
        assertEquals(listOf("callback"), rows.getValue("forwardInner").relationships)
        // 캡처 람다(재구성 람다 모양)는 같은 함수로 되돌려 넘기기만 해서 bound를 낮추지 않는다.
        assertTrue(rows.keys.any { it.startsWith("restartable\$lambda") })
    }

    @Test
    fun `escaping or library hand-off keeps candidate evidence and returns are not linked`() {
        val rows = reached()
        assertEquals(TraversalEvidence.CANDIDATE, rows.getValue("escapeField").evidence)
        assertEquals(TraversalEvidence.CANDIDATE, rows.getValue("handToLibrary").evidence)
        assertEquals(TraversalEvidence.CANDIDATE, rows.getValue("closureInvoke").evidence)
        // 리뷰 지적(H2): Compose runtime이 재구성 때 감싼 람다를 다시 실행할 수 있어 bound가 아니다.
        assertEquals(TraversalEvidence.CANDIDATE, rows.getValue("slot").evidence)
        assertFalse("escapeReturn" in rows, "a returned callback has no invocation to link")
        // 리뷰 지적 재현: 넘기는 쪽에서 같은 람다를 필드에 저장하면 실행자가 이 경로뿐이라고 할 수 없다.
        assertEquals(TraversalEvidence.CANDIDATE, rows.getValue("invokeStored").evidence)
        assertTrue(graph.lambdaEscapes.any { it.caller == method("screenStored") && it.kind == ParameterUseKind.FIELD })
        assertFalse("keep" in rows, "keep only inspects the closure")
        val bound = reached(TraversalDispatch.BOUND)
        assertFalse("escapeField" in bound)
        assertTrue("direct" in bound)
    }

    @Test
    fun `callback callers are not expanded to their other callers`() {
        val rows = reached()
        assertTrue("screenDirect" in rows, "the lambda's enclosing screen is reached through containment")
        assertFalse("screenOther" in rows, "another screen passes an unrelated lambda to the same function")
        val limitations = LanguageTraversal.traverse(graph, listOf(route), TraversalDirection.DEPENDENTS).limitations
        assertTrue(limitations.any { it.startsWith("callback-flow: ") })
        assertTrue(limitations.any { it.startsWith("callback-flow-unresolved: ") && "return" in it })
    }

    @Test
    fun `forward traversal does not follow callback edges`() {
        val forward = LanguageTraversal.traverse(graph, listOf(method("direct").value), TraversalDirection.DEPENDENCIES)
        assertTrue(forward.reached.none { it.node.id.value == route }, "direct alone does not reach a caller's lambda body")
    }

    @Test
    fun `callback facts are deterministic and survive the class cache`(@TempDir directory: Path) {
        val cache = ClassIndexCache(directory.resolve("cache"), "callbacks")
        val cold = ClassFileIndexer(cache).index(listOf(classesRoot))
        val warm = ClassFileIndexer(cache).index(listOf(classesRoot))
        assertTrue(cold.callbackArguments.isNotEmpty() && cold.parameterUses.isNotEmpty())
        assertEquals(cold.callbackArguments, warm.callbackArguments)
        assertEquals(cold.parameterUses, warm.parameterUses)
        assertEquals(graph.callbackArguments, cold.callbackArguments)
        // 바로 분석하는 명령은 사실을 모으지 않는다. 캐시를 쓰면 항목 재사용을 위해 항상 모은다.
        val skipped = ClassFileIndexer(callbackFacts = false).index(listOf(classesRoot))
        assertTrue(skipped.callbackArguments.isEmpty() && skipped.parameterUses.isEmpty() && skipped.lambdaEscapes.isEmpty())
        assertEquals(skipped.edges, graph.edges)
        val cachedSkip = ClassFileIndexer(ClassIndexCache(directory.resolve("cache"), "callbacks"), callbackFacts = false).index(listOf(classesRoot))
        assertEquals(cold.callbackArguments, cachedSkip.callbackArguments)
    }
}
