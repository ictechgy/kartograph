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
    /**
     * `target: "http"` 문서가 스캔한 역할이다(`client`·`server`). 사실이 0건이어도 "스캔했으나 없음"을
     * 표현하려고 싣는다 — roles가 있는 http 문서는 사실이 없어도 target을 유지한다.
     */
    val roles: List<String>? = null,
    /** http 문서의 테스트 소스 세트 스캔 여부다(`excluded` | `included`). 출력에서는 `sourceSets.tests`다. */
    val testSources: String? = null,
    /** http 문서의 기본 서비스 신원이다. 사실의 service와 다르면 소비자가 문서를 거부한다. */
    val service: String? = null,
    /**
     * `route-decl`을 담은 http 문서의 디스패치 모델이다(`specificity`). Spring PathPattern은 구체성 순서로
     * 고르므로 서버 문서는 항상 `specificity`를 싣는다. 클라이언트 문서에는 싣지 않는다.
     */
    val dispatch: String? = null,
    /**
     * http 문서의 limitation 스코프다. 항목은 [limitations]의 문구를 가리키고, 렌더러가 정렬된 `limitations`의 인덱스로
     * 바꾼다 — 문서를 조립하는 쪽이 한계를 더하거나 정렬해도 스코프가 다른 한계를 가리키지 않게 하기 위해서다.
     * 스코프가 없는 한계는 문서 전체에 적용된다.
     */
    val limitationScopes: List<RouteLimitationScope> = emptyList(),
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
    /** `route-call` 사실 전용 증거 필드다. 다른 kind의 사실에는 싣지 않는다. */
    val route: RouteCallEvidence? = null,
    /** `route-decl` 사실 전용 증거 필드다. [route]와 함께 싣지 않는다. */
    val routeDecl: RouteDeclEvidence? = null,
)

/** 프로젝트 상대 파일과 1부터 시작하는 위치다. */
public data class BridgeLocation(val path: String, val line: Int, val column: Int)

/** 사실을 담는 선언을 복원할 수 있을 때의 안정 식별자다. */
public data class BridgeSymbol(val qualifiedName: String, val usr: String? = null)
