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
 * @property queryTailStripped 끝 보간·리터럴 꼬리가 query/fragment임을 증명하고 떼어 냈다는 증거다
 * @property maskedSegments 고엔트로피·웹훅 규칙으로 `{}`로 가린 세그먼트 수다. 0이면 null이다
 * @property testSource `--include-tests`로 테스트 소스 세트에서 낸 사실이다
 */
public data class RouteCallEvidence(
    val pathAnchor: String,
    val methodDynamic: Boolean = false,
    val authority: String? = null,
    val service: String? = null,
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
