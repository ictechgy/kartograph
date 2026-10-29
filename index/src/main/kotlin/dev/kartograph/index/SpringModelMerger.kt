package dev.kartograph.index

/**
 * 바이트코드 모델에 소스 위치를 붙이고 소스에만 있는 타입을 더한다.
 *
 * 값은 바이트코드가 정본이다(상수가 이미 접혀 있다). 소스는 어노테이션 토큰 위치와 경로만 준다. 같은 이름의 소스
 * 타입이 없으면 바이트코드 타입은 위치 없이 남고, 그 핸들러는 계약대로 사실 대신 `route-coverage:`로 센다.
 */
internal object SpringModelMerger {
    /**
     * @param bytecode class root에서 읽은 타입들이다. 비어 있으면 소스 모델을 그대로 쓴다
     * @param source 소스에서 읽은 타입들이다
     * @param lineOf (소스 경로, 오프셋) → 1부터 시작하는 줄이다. 오버로드를 첫 줄로 가를 때 쓴다
     */
    fun merge(bytecode: List<SpringType>, source: List<SpringType>, lineOf: (String, Int) -> Int?): List<SpringType> {
        if (bytecode.isEmpty()) return source
        val sourceByName = source.associateBy { it.name }
        val merged = bytecode.map { type -> sourceByName[type.name]?.let { withSource(type, it, lineOf) } ?: type }
        val compiled = bytecode.mapTo(mutableSetOf()) { it.name }
        return merged + source.filter { it.name !in compiled }
    }

    private fun withSource(compiled: SpringType, source: SpringType, lineOf: (String, Int) -> Int?): SpringType = compiled.copy(
        sourcePath = source.sourcePath,
        isTest = source.isTest,
        annotations = locate(compiled.annotations, source.annotations),
        methods = compiled.methods.map { method -> sourceMethod(method, source, lineOf)?.let { withSource(method, it) } ?: method },
    )

    private fun withSource(compiled: SpringMethod, source: SpringMethod): SpringMethod =
        compiled.copy(nameOffset = source.nameOffset, annotations = locate(compiled.annotations, source.annotations))

    /**
     * 바이트코드 메서드에 맞는 소스 메서드다 — 같은 이름·매개변수 수가 하나면 그것, 여럿이면 첫 줄 앞에서 가장 가까운
     * 선언이다. 줄로도 가리지 못하면 위치를 붙이지 않는다.
     */
    private fun sourceMethod(compiled: SpringMethod, source: SpringType, lineOf: (String, Int) -> Int?): SpringMethod? {
        if (compiled.isSynthetic) return null
        val candidates = source.methods.filter { it.name == compiled.name && it.parameterCount == compiled.parameterCount }
            .ifEmpty { source.methods.filter { it.name == compiled.name } }
        if (candidates.size <= 1) return candidates.singleOrNull()
        val firstLine = compiled.firstLine ?: return null
        val path = source.sourcePath ?: return null
        return candidates.mapNotNull { candidate -> candidate.nameOffset?.let { lineOf(path, it) }?.let { candidate to it } }
            .filter { it.second <= firstLine }.maxByOrNull { it.second }?.first
    }

    /** 바이트코드 어노테이션마다 같은 타입(없으면 같은 단순 이름)의 소스 토큰 위치를 붙인다. */
    private fun locate(compiled: List<SpringAnnotation>, source: List<SpringAnnotation>): List<SpringAnnotation> = compiled.map { annotation ->
        val match = source.firstOrNull { it.type == annotation.type }
            ?: source.firstOrNull { it.type.substringAfterLast('.') == annotation.type.substringAfterLast('.') }
        annotation.copy(offset = match?.offset)
    }
}
