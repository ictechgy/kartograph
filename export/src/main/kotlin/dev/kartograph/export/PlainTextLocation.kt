package dev.kartograph.export

import dev.kartograph.core.SourceLocation

/** CLI와 Gradle plugin의 plain-text 계약이 같은 위치 형식을 사용하도록 렌더링한다. */
public fun SourceLocation?.toPlainTextLocation(): String = when {
    this == null -> "-"
    line == null -> path
    column == null -> "$path:$line"
    else -> "$path:$line:$column"
}
