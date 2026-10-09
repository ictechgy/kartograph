package dev.kartograph.export

import dev.kartograph.core.SourceDeclarationEvidence

/** 좌표와 raw source hash를 함께 싣고 compiler·현재 빌드 신선도로 오인하지 않게 출처를 표시한다. */
internal fun SourceDeclarationEvidence.toJsonValue(): Map<String, Any?> = sortedMapOf(
    "path" to path,
    "pathKind" to "projectRelative",
    "sourceSha256" to sourceSha256,
    "offsetUtf16" to offsetUtf16,
    "line" to line,
    "column" to column,
    "coordinateBasis" to "rawDecodedUtf16",
    "origin" to "sourceHeader",
)
