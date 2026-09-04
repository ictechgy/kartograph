package dev.kartograph.core

/** GRAPH-EXCHANGE v1의 Kotlin 생산 문서다. */
public data class BridgeFactsDocument(
    val format: String = "bridge-facts",
    val version: Int = 1,
    val tool: BridgeTool = BridgeTool("kartograph", KartographVersion.current),
    val generatedAt: String,
    val platform: String = "kotlin",
    val target: String?,
    val project: String,
    val facts: List<BridgeFact>,
    val limitations: List<String>,
)

/** 교환 문서의 생산 도구 식별자다. */
public data class BridgeTool(val name: String, val version: String)

/** Kotlin 소스에서 관측한 브리지 등록 또는 핸들러 사실이다. */
public data class BridgeFact(
    val kind: String,
    val channel: String?,
    val method: String? = null,
    val dynamic: Boolean,
    val location: BridgeLocation,
    val symbol: BridgeSymbol? = null,
    val target: String,
)

/** 프로젝트 상대 파일과 1부터 시작하는 위치다. */
public data class BridgeLocation(val path: String, val line: Int, val column: Int)

/** 사실을 담는 선언을 복원할 수 있을 때의 안정 식별자다. */
public data class BridgeSymbol(val qualifiedName: String, val usr: String? = null)
