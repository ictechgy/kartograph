package dev.kartograph.index

import dev.kartograph.core.BridgeFact
import dev.kartograph.core.BridgeSymbol
import dev.kartograph.core.CodeGraph
import dev.kartograph.core.GraphNode
import dev.kartograph.core.NodeKind
import dev.kartograph.core.qualifiedName
import org.objectweb.asm.Type

/**
 * Retrofit 동사 어노테이션이 붙은 서비스 메서드의 소스 선언이다.
 *
 * Retrofit route-call 사실은 호출 지점이 아니라 인터페이스 선언에서 나온다. 추상 메서드는 bytecode에 줄 번호가 없어
 * 위치 기반 부착([attachSnapshotSymbol])으로는 JVM 정점을 찾을 수 없으므로, 소스에서 복원한 소유 타입·이름·동사로
 * 정점을 찾는다.
 *
 * @property owner 선언을 담은 타입의 JVM internal name이다(`pkg/Outer$Api`). 중첩 타입은 `$`로 잇는다
 * @property name 소스의 메서드 이름이다
 * @property verb 어노테이션 단순 이름이다(`GET`·`HTTP` 등). 정점의 `retrofit2/http/<verb>` 어노테이션과 대조한다
 * @property parameterCount 소스 매개변수 수다. 같은 이름·동사의 overload가 여럿일 때만 가르는 데 쓴다
 */
internal data class RetrofitDeclaration(val owner: String, val name: String, val verb: String, val parameterCount: Int)

/**
 * snapshot 그래프에서 Retrofit 서비스 메서드 정점을 찾아 route-call 사실에 JVM 신원을 붙인다.
 *
 * 신원은 인터페이스 메서드 자신이다. 호출 지점은 이 메서드를 `invokeinterface`로 부르므로, 역방향 순회가 call 간선을
 * 따라 모든 호출자(저장소·ViewModel·화면)에 닿는다. 한 선언에 호출 지점이 여럿이어도 사실 하나로 모두 덮는다.
 * 같은 파일 경로의 정점만 보며, 하나로 정해지지 않으면 신원을 붙이지 않는다(추측하지 않는다).
 */
internal class RetrofitSymbolIndex(graph: CodeGraph) {
    /** 소유 타입 internal name → 그 타입의 메서드 정점이다. */
    private val methodsByOwner: Map<String, List<GraphNode>> = graph.nodes.values
        .filter { it.kind == NodeKind.METHOD && it.id.value.startsWith("method:") }
        .groupBy { it.id.value.removePrefix("method:").substringBefore('#') }

    /** [declaration]과 하나로 맞는 정점이 있으면 그 신원을 단 사실을, 없으면 [fact] 그대로를 돌려준다. */
    fun attach(fact: BridgeFact, declaration: RetrofitDeclaration): BridgeFact {
        val node = find(fact.location.path, declaration) ?: return fact
        return fact.copy(symbol = BridgeSymbol(node.qualifiedName, node.id.value))
    }

    private fun find(path: String, declaration: RetrofitDeclaration): GraphNode? {
        val annotation = "retrofit2/http/${declaration.verb}"
        val candidates = methodsByOwner[declaration.owner].orEmpty().filter { node ->
            isJvmNameOf(jvmName(node), declaration.name) && annotation in node.annotations &&
                node.location?.path?.replace('\\', '/') == path
        }
        candidates.singleOrNull()?.let { return it }
        return candidates.filter { sourceParameterCount(it) == declaration.parameterCount }.singleOrNull()
    }

    private fun jvmName(node: GraphNode): String = node.id.value.substringAfter('#').substringBefore('(')

    /**
     * JVM 이름이 소스 이름 [name]의 것인지다. 값 class 매개변수를 받는 Kotlin 함수는 `name-<hash>`로 이름이 바뀐다.
     * `-`는 백틱 없는 Kotlin 식별자에 쓸 수 없어 다른 선언과 겹치기 어렵다. 겹치면 다른 overload처럼 매개변수 수로 가른다.
     */
    private fun isJvmNameOf(jvmName: String, name: String): Boolean = jvmName == name || jvmName.startsWith("$name-")

    /** descriptor의 매개변수 수다. Kotlin `suspend`가 덧붙인 마지막 `Continuation`은 소스 매개변수가 아니라 세지 않는다. */
    private fun sourceParameterCount(node: GraphNode): Int {
        val types = Type.getArgumentTypes(node.id.value.substring(node.id.value.indexOf('(')))
        return types.size - if (types.lastOrNull()?.internalName == "kotlin/coroutines/Continuation") 1 else 0
    }
}

/**
 * 매개변수 목록 `(`…`)` 안의 최상위 매개변수 수다. 문자열 내용이 가려진 [masked] 뷰에서 세며 `()`·`[]`·`{}`·타입 인자 `<>`
 * 중첩 안의 쉼표(`Map<String, Any>`, 어노테이션 인자)는 세지 않는다. `<`는 식별자 바로 뒤(`Map<`)일 때만 타입 인자를 열고
 * `>`는 연 타입 인자가 있을 때만 닫는다 — 기본값 식의 비교 연산자(`MAX > 0`, `a < b`)와 함수 타입의 `->`는 꺾쇠가 아니다.
 * Kotlin의 끝 쉼표는 매개변수가 아니다.
 */
internal fun parameterCount(masked: String, open: Int, close: Int): Int {
    var brackets = 0
    var angles = 0
    var count = 0
    var pending = false
    for (index in open + 1 until close) {
        val character = masked[index]
        val previous = masked.getOrNull(index - 1)
        when {
            character == '(' || character == '[' || character == '{' -> brackets++
            character == ')' || character == ']' || character == '}' -> brackets = (brackets - 1).coerceAtLeast(0)
            character == '<' && previous != null && (previous.isLetterOrDigit() || previous == '_') -> angles++
            character == '>' && previous != '-' && angles > 0 -> angles--
            character == ',' && brackets == 0 && angles == 0 -> { if (pending) count++; pending = false; continue }
        }
        if (!character.isWhitespace()) pending = true
    }
    return count + if (pending) 1 else 0
}
