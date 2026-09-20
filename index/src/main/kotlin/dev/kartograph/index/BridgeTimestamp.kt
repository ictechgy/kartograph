package dev.kartograph.index

import java.time.Instant
import java.time.format.DateTimeFormatterBuilder

private val bridgeTimeFormat = DateTimeFormatterBuilder().appendInstant(3).toFormatter()

/** 생산 문서의 시각을 교환 계약에 맞는 UTC 밀리초 형식으로 통일한다. */
internal fun bridgeTimestamp(instant: Instant): String = bridgeTimeFormat.format(instant)
