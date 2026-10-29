package dev.kartograph.export

import dev.kartograph.core.KotlinDefaultBridge

/** 기본 인자 bridge의 확정 근거를 raw CALL과 독립된 선택 필드로 내보낸다. */
internal fun KotlinDefaultBridge.toJsonValue(): Map<String, Any> = sortedMapOf(
    "target" to target.value,
    "basis" to basis,
    "valueParameterCount" to valueParameterCount,
    "maskParameterCount" to maskParameterCount,
)

/** snapshot 경계에서도 동일 owner/name/receiver/mask/marker descriptor 계약을 확인한다. */
internal fun KotlinDefaultBridge.matches(bridge: dev.kartograph.core.GraphNode, target: dev.kartograph.core.GraphNode): Boolean {
    if (bridge.id == target.id || !target.id.value.startsWith("method:")) return false
    val owner = target.id.value.substringAfter("method:").substringBefore('#')
    val method = target.id.value.substringAfter('#', "")
    val name = method.substringBefore('(')
    val descriptor = method.substringAfter(name, "")
    if (!descriptor.startsWith('(') || ')' !in descriptor || owner.isEmpty() || name.isEmpty()) return false
    val receiver = if (dev.kartograph.core.JvmModifier.STATIC in target.jvmModifiers) "" else "L$owner;"
    val arguments = descriptor.substringAfter('(').substringBefore(')')
    val result = descriptor.substringAfter(')')
    if (result.isEmpty()) return false
    val expected = "method:$owner#$name\$default($receiver$arguments${"I".repeat(maskParameterCount)}Ljava/lang/Object;)$result"
    return bridge.id.value == expected
}
