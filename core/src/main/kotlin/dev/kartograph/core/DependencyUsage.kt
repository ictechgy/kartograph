package dev.kartograph.core

/** 같은 컴파일 입력에서 관찰한 전체 참조와 소비자에게 노출되는 타입 참조를 분리한다. */
public data class DependencyUsage(
    val referencedClasses: Set<String>,
    val apiClasses: Set<String> = emptySet(),
    val declaredClasses: Set<String> = emptySet(),
    val apiComplete: Boolean = true,
    val limitations: List<String> = emptyList(),
)
