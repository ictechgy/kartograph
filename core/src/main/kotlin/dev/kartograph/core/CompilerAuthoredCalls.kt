package dev.kartograph.core

/** ordinary-body-v1에서 caller 전체가 완전한지 나타낸다. 행이 없으면 UNKNOWN이다. */
public enum class AuthoredCallCoverage { COMPLETE, INCOMPLETE }

/** source 호출 선언의 소유 범위다. 이름 prefix나 runtime dispatch 후보에서 추정하지 않는다. */
public enum class AuthoredCallOrigin { PROJECT, BINARY }

/** compiler body 분류 또는 검증이 완전성을 증명하지 못한 이유다. */
public enum class AuthoredCallReason {
    UNSUPPORTED_DECLARATION, NO_BODY, UNSUPPORTED_SYNTAX, UNSUPPORTED_CALL, PROPERTY_ACCESS,
    UNMAPPED_POSITION, UNMAPPED_TARGET, UNMAPPED_EXTERNAL_TARGET, AMBIGUOUS_BACKEND_ID,
    FILTERED_TARGET, UNVERIFIED_TARGET_ORIGIN,
}

/** BYTECODE CALL과 독립된 명시적 source 호출이다. 도달성·dispatch의 간선으로 사용하지 않는다. */
public data class CompilerAuthoredCall(
    val target: NodeId,
    val origin: AuthoredCallOrigin,
    val targetSourcePath: String?,
    val offsetUtf16: Int,
    val endOffsetUtf16: Int,
) {
    init {
        requireMethod(target)
        require((origin == AuthoredCallOrigin.PROJECT) == (targetSourcePath != null)) { "authored target origin/path mismatch" }
        require(targetSourcePath == null || SourceDeclarationEvidence.isPortablePath(targetSourcePath)) { "invalid authored target path" }
        require(offsetUtf16 >= 0 && endOffsetUtf16 > offsetUtf16) { "invalid authored call range" }
    }
}

/**
 * 완료 receipt를 검증한 시점의 source 호출 전체 분류다. snapshot 재생은 현재 build 신선도를 다시 확인해야 한다.
 * COMPLETE의 calls는 전체 집합이며 bytecode/bootstrap/lowering 호출과 합집합을 만들지 않는다.
 */
public data class CompilerAuthoredCaller(
    val caller: NodeId,
    val file: CompilerEvidenceSource,
    val offsetUtf16: Int,
    val endOffsetUtf16: Int,
    val coordinateBasis: CompilerSourceCoordinateBasis,
    val compilerVersion: String,
    val classifiedNodes: Int,
    val explicitCalls: Int,
    val coverage: AuthoredCallCoverage,
    val reasons: List<AuthoredCallReason>,
    val calls: List<CompilerAuthoredCall>,
    val inputToken: String,
    val collectorArtifactSha256: String,
    val evidenceSha256: String,
) {
    init {
        requireMethod(caller)
        require(SourceDeclarationEvidence.isPortablePath(file.path)) { "invalid authored caller path" }
        require(offsetUtf16 >= 0 && endOffsetUtf16 > offsetUtf16) { "invalid authored caller range" }
        require(compilerVersion.length in 1..128 && compilerVersion.matches(Regex("[A-Za-z0-9._+\\-]+"))) { "invalid authored compiler version" }
        require(if (coordinateBasis == CompilerSourceCoordinateBasis.KOTLIN_UTF16_NORMALIZED_SOURCE) compilerVersion == "2.4.20"
            else compilerVersion.substringBefore('.') in setOf("17", "21")) { "unsupported authored compiler profile" }
        require(classifiedNodes in 0..100_000 && explicitCalls in 0..classifiedNodes && calls.size <= explicitCalls) { "invalid authored classification counts" }
        require(reasons.size <= 32 && reasons.distinct().size == reasons.size) { "invalid authored coverage reasons" }
        require((coverage == AuthoredCallCoverage.COMPLETE) == reasons.isEmpty()) { "authored coverage contradicts reasons" }
        require(coverage != AuthoredCallCoverage.COMPLETE || classifiedNodes > 0 && calls.size == explicitCalls) { "complete authored calls omit observations" }
        require(calls.all { it.offsetUtf16 >= offsetUtf16 && it.endOffsetUtf16 <= endOffsetUtf16 }) { "authored selector is outside caller" }
        require(calls.map { it.offsetUtf16 to it.endOffsetUtf16 }.distinct().size == calls.size) { "duplicate authored calls" }
        require(listOf(inputToken, collectorArtifactSha256, evidenceSha256).all { it.matches(Regex("[0-9a-f]{64}")) }) { "invalid authored evidence fingerprint" }
    }

    /** 선언이 필터에서 빠졌으면 완전한 빈 호출 집합으로 승격하지 않는다. */
    public fun retainingTargets(nodes: Set<NodeId>): CompilerAuthoredCaller {
        val retained = calls.filter { it.origin != AuthoredCallOrigin.PROJECT || it.target in nodes }
        return if (retained.size == calls.size) this else copy(calls = retained,
            coverage = AuthoredCallCoverage.INCOMPLETE, reasons = (reasons + AuthoredCallReason.FILTERED_TARGET).distinct().sortedBy { it.name })
    }
}

private fun requireMethod(id: NodeId) {
    val value = id.value
    require(value.length <= 4096 && value.startsWith("method:") && value.none { it.code < 32 }) { "invalid authored method identity" }
    val ownerEnd = value.indexOf('#', 7)
    val parameters = value.indexOf('(', ownerEnd + 1)
    require(ownerEnd > 7 && parameters > ownerEnd + 1 && value.lastIndexOf('#') == ownerEnd) { "invalid authored method identity" }
    fun owner(text: String): Boolean = text.isNotEmpty() && text.split('/').all { it.isNotEmpty() } &&
        text.none { it in ".;[():#" || it.isWhitespace() }
    require(owner(value.substring(7, ownerEnd)) && value.substring(ownerEnd + 1, parameters).none { it in ".;[/<>():#" }) {
        "invalid authored method name"
    }
    var position = parameters + 1
    fun type(allowVoid: Boolean) {
        var dimensions = 0
        while (value.getOrNull(position) == '[') { dimensions++; position++ }
        require(dimensions <= 255) { "authored descriptor array exceeds limit" }
        when (value.getOrNull(position++)) {
            'V' -> require(allowVoid && dimensions == 0) { "invalid authored void descriptor" }
            'Z', 'B', 'C', 'S', 'I', 'F', 'J', 'D' -> Unit
            'L' -> {
                val end = value.indexOf(';', position)
                require(end > position && owner(value.substring(position, end))) { "invalid authored object descriptor" }
                position = end + 1
            }
            else -> throw IllegalArgumentException("invalid authored method descriptor")
        }
    }
    while (value.getOrNull(position) != ')') type(false)
    position++
    type(true)
    require(position == value.length) { "trailing authored method descriptor" }
}
