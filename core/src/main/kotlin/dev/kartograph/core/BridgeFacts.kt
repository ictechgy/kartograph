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
    /** v2 보조 채널(BasicMessageChannel·EventChannel) 문서에서만 쓰는 transport 식별자다. */
    val transport: String? = null,
    /** 읽은 소스의 최신 filesystem mtime이다. compiler snapshot 신선도는 별도 근거가 필요하다. */
    val sourceModifiedAt: String? = null,
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
    /** 동적 보조 채널 표현식에서 AST로 확인한 비어 있지 않은 literal prefix다. */
    val channelPrefix: String? = null,
    /**
     * `react-native` 이름 경계 사실(`module-export`·`component-export`)의 해석 경로다.
     * `core`는 생략하고 Expo Modules 선언만 `expo`를 싣는다. 메서드 사실에는 쓰지 않는다.
     */
    val mechanism: String? = null,
)

/** 프로젝트 상대 파일과 1부터 시작하는 위치다. */
public data class BridgeLocation(val path: String, val line: Int, val column: Int)

/** 사실을 담는 선언을 복원할 수 있을 때의 안정 식별자다. */
public data class BridgeSymbol(val qualifiedName: String, val usr: String? = null)
