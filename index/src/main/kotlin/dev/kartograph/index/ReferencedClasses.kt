package dev.kartograph.index

import dev.kartograph.core.NodeId

/**
 * classfile 원시 간선의 target에서 참조 class 후보를 모은다.
 * JVMS class 이름에는 '#'이 올 수 있어 member target은 가능한 owner 접두사를 모두 보수적으로 포함한다.
 * 과대 포함은 "사용됨"으로 기울 뿐 unused 오탐을 만들지 않는다.
 */
internal fun referencedClassCandidates(target: NodeId): List<String> {
    val value = target.value
    return when {
        value.startsWith("class:") -> listOf(value.removePrefix("class:"))
        value.startsWith("method:") || value.startsWith("field:") -> {
            val rest = value.substringAfter(':')
            val candidates = mutableListOf<String>()
            var index = rest.indexOf('#')
            while (index > 0) {
                candidates += rest.substring(0, index)
                index = rest.indexOf('#', index + 1)
            }
            candidates.ifEmpty { listOf(rest).filter(String::isNotEmpty) }
        }
        else -> emptyList()
    }
}
