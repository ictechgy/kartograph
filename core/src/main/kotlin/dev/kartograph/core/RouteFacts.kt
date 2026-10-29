package dev.kartograph.core

/**
 * isthmus http 도메인의 `route-call` 사실에만 붙는 증거 필드다.
 *
 * 조인 키는 [BridgeFact.method]와 [BridgeFact.channel](정규 경로 템플릿)이고, 이 값들은 앵커·귀속·
 * error 근거 여부를 소비자에게 알리는 증거다. 존재 자체가 증거인 표식은 계약상 `true`만 허용되므로
 * 거짓이면 출력에서 생략한다.
 *
 * @property pathAnchor 템플릿이 서버 경로 루트부터 확정됐으면 `root`, 정적으로 모르는 base 뒤면 `base`
 * @property methodDynamic 동사가 리터럴로 확정되지 않았다는 표시다. 이때 [BridgeFact.method]는 null이다
 * @property authority userinfo를 뗀 소문자 리터럴 host다. 귀속 입력이며 조인 키가 아니다
 * @property service 선언된 래퍼가 지정한 서비스 신원이다
 * @property baseRef base URL을 공급하는 선언(Retrofit 인스턴스·provider)의 생산자 id다. workspace link `match.baseRefs`로
 *   귀속하는 입력이며 조인 키가 아니다. base를 정적으로 풀지 못해도 인스턴스를 찾았으면 싣는다
 * @property queryTailStripped 끝 보간·리터럴 꼬리가 query/fragment임을 증명하고 떼어 냈다는 증거다
 * @property maskedSegments 고엔트로피·웹훅 규칙으로 `{}`로 가린 세그먼트 수다. 0이면 null이다
 * @property testSource `--include-tests`로 테스트 소스 세트에서 낸 사실이다
 */
public data class RouteCallEvidence(
    val pathAnchor: String,
    val methodDynamic: Boolean = false,
    val authority: String? = null,
    val service: String? = null,
    val baseRef: String? = null,
    val queryTailStripped: Boolean = false,
    val maskedSegments: Int? = null,
    val testSource: Boolean = false,
)

/**
 * `http-wrappers` v1 선언 한 건이다. 사용자가 "어느 인자가 경로이고 어느 인자가 동사인지"를
 * 선언한 앱 자체 HTTP 래퍼이며, 소스만으로 추측하지 않기 위한 입력이다.
 *
 * @property language 호출 측 생산자 언어다. kartograph는 `kotlin` 선언만 해석한다
 * @property kind `constructor` 또는 `function`
 * @property owner 소유 타입 FQN(생성자·멤버 함수) 또는 최상위 함수의 패키지 경로
 * @property name 생성자면 `<init>`, 함수면 함수 이름
 * @property methodArg 동사 인자 위치·이름이다. 없으면 [defaultMethod]가 필수다
 * @property pathArg 경로 인자 위치·이름이다
 * @property defaultMethod 동사 인자를 생략했을 때 선언의 기본 동사다
 * @property methodEnum enum case(또는 상수 이름) → 계약 동사 매핑이다
 * @property pathAnchor 래퍼 경로가 root인지 base 뒤인지에 대한 사용자 선언이다
 * @property service 이 래퍼 호출의 서비스 신원이다
 */
public data class HttpWrapperDeclaration(
    val language: String,
    val kind: String,
    val owner: String,
    val name: String,
    val methodArg: HttpWrapperArgument?,
    val pathArg: HttpWrapperArgument,
    val defaultMethod: String?,
    val methodEnum: Map<String, String>,
    val pathAnchor: String,
    val service: String?,
)

/**
 * 래퍼 인자를 찾는 방법이다. [label]이 있으면 이름 붙은 인자를 먼저 찾고, 없으면 [index] 위치의
 * 이름 없는 인자를 쓴다. 둘 중 하나 이상은 반드시 있다.
 */
public data class HttpWrapperArgument(val index: Int?, val label: String?)

/**
 * isthmus http 도메인의 `route-decl` 사실에만 붙는 증거 필드다.
 *
 * 조인 키는 [BridgeFact.method](`ANY` 포함)와 [BridgeFact.channel]이다. 존재 자체가 증거인 표식은 계약상
 * `true`만 허용되므로 거짓이면 출력에서 생략한다.
 *
 * @property pathAnchor 템플릿이 서버 경로 루트부터 확정됐으면 `root`, 확정하지 못한 접두사 뒤면 `base`
 * @property trailingSlash 끝 슬래시 매칭이다 — `strict`(정확히 같아야 함), `optional`(있든 없든 같은 핸들러),
 *   null(버전·설정을 증명하지 못해 모름)
 * @property narrowed params·headers·consumes·produces·version 조건으로 같은 키를 나눈 핸들러다
 * @property paramConstraints 경로 변수의 정규식 제약이다. 세그먼트 순서로 정렬한다
 * @property configDefault 저장소 안 어디서도 재정의하지 않은 `${key:default}` 기본값을 썼다는 증거다
 * @property catchAllPrefix 0세그먼트 catch-all(끝 `**`·`{*path}`)을 펼친 접두사 decl이다
 * @property testSource `--include-tests`로 테스트 소스 세트에서 낸 사실이다
 */
public data class RouteDeclEvidence(
    val pathAnchor: String,
    val trailingSlash: String? = null,
    val narrowed: Boolean = false,
    val paramConstraints: List<RouteParamConstraint> = emptyList(),
    val configDefault: Boolean = false,
    val catchAllPrefix: Boolean = false,
    val testSource: Boolean = false,
)

/**
 * 경로 변수 하나의 제약이다(계약의 `paramConstraints` 항목).
 *
 * @property segment 템플릿 세그먼트의 0부터 시작하는 인덱스다(선행 `/` 뒤 첫 세그먼트가 0)
 * @property kind `int`·`uuid`·`slug`·`regex` 중 하나다. 닫힌 종류는 원래 정규식의 언어가 그 종류의 가장 넓은
 *   정의에 포함될 때만 쓴다 — 소비자가 그 정의를 어긴 호출을 후보에서 빼도 프레임워크보다 넓게 빼지 않게 하기 위해서다
 * @property pattern `regex` 종류의 원래 정규식이다(정보용). 다른 종류에서는 null이다
 */
public data class RouteParamConstraint(val segment: Int, val kind: String, val pattern: String? = null)

/**
 * http limitation 스코프 하나다(isthmus GRAPH-EXCHANGE "http limitation 스코프"). 한계 하나가 가릴 수 있는 요청(method, 경로)의
 * 보수적 상한이며, 경로 필드 셋 중 하나 이상이 있어야 한다.
 *
 * @property limitation 스코프를 붙일 한계 문구다. 같은 문서의 `limitations`에 정확히 있어야 한다
 * @property templates 정확한 정규 템플릿 집합이다(`{**}` 허용)
 * @property templatePrefixes 세그먼트 경계의 root 접두사다. `/`는 모든 경로다
 * @property templateSuffixes 알 수 없는 앞부분 뒤의 세그먼트 경계 접미사다
 * @property methods 스코프가 가리는 HTTP 동사다. 비어 있으면 모든 method다
 */
public data class RouteLimitationScope(
    val limitation: String,
    val templates: List<String> = emptyList(),
    val templatePrefixes: List<String> = emptyList(),
    val templateSuffixes: List<String> = emptyList(),
    val methods: List<String> = emptyList(),
)
