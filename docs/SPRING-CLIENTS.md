# Spring HTTP 클라이언트 route-call (`routes --role client`)

서버 사이 호출(서비스 A의 핸들러 → 서비스 B의 API)을 isthmus `trace`의 workspace link로 잇기 위해, Spring의 명령형 클라이언트
(`RestTemplate`·`RestClient`·`WebClient`)와 선언형 클라이언트(`@HttpExchange` 인터페이스) 호출을 isthmus http 도메인의
`route-call` 사실로 낸다. 계약은 isthmus `docs/GRAPH-EXCHANGE.md`의 route 사실 필드·base 접두사 절이다. 구현은
`index/.../SpringClientCalls.kt`(호출 지점), `SpringClientResolver.kt`(클라이언트 식 → 종류·base), `SpringHttpExchanges.kt`
(`@HttpExchange`), `SpringUriRules.kt`(결합 규칙)이고 `RouteCallScanner`가 조립한다.

## 인식하는 호출

수신 식이 Spring 클라이언트임을 증명한 호출만 사실로 낸다. 증명은 import로 확인한 타입(`RestTemplate`·`RestOperations`·
`RestClient`·`WebClient`)의 선언이나 생성 식이다. 같은 이름의 다른 객체 메서드(`map.put(…)`)를 호출로 읽지 않기 위해서다.

| 클라이언트 | 모양 | 동사 |
|---|---|---|
| `RestTemplate` | `getForObject`·`getForEntity`·`postForObject`·`postForEntity`·`postForLocation`·`put`·`patchForObject`·`delete`·`headForHeaders`·`optionsForAllow`(첫 인자 URL, Kotlin `getForObject<T>(…)` 확장 포함) | 메서드 이름 |
| `RestTemplate` | `exchange(url, HttpMethod.X, …)`·`execute(url, HttpMethod.X, …)` | 두 번째 인자(`HttpMethod.X`·`HttpMethod.valueOf("X")`·정적 import `X`), 아니면 `methodDynamic` |
| `RestClient`·`WebClient` | `get()`·`post()`·`put()`·`patch()`·`delete()`·`head()`·`options()`·`method(HttpMethod.X)` 뒤 사슬의 첫 `.uri(…)` | 동사 메서드 |
| `@HttpExchange` 인터페이스 | `@GetExchange`…`@DeleteExchange`, `@HttpExchange(method = "X", url = …)`, 타입 수준 `@HttpExchange(url)` | 메서드 수준, 없으면 타입 수준, 없으면 `methodDynamic` |

URL 인자는 문자열 템플릿(리터럴·Kotlin 템플릿·`+` 연결·상수·지역 `val`·`@Value` 속성), `UriComponentsBuilder` 사슬
(`fromUriString`·`fromHttpUrl`·`fromPath`·`newInstance` 뒤 `path`·`pathSegment`·`scheme`·`host`·query 메서드, `toUriString()`·
`toUri()`), `URI.create(x)`·`URI(x)`, `.uri { it.path(…).build(…) }`·`uri(b -> b.path(…).build(…))` 빌더 람다,
`.uri(template, Function)`을 읽는다.

`@HttpExchange` 사실은 메서드마다 하나이고 신원은 인터페이스 메서드다(Retrofit과 같다). base는 인터페이스를 만든
`createClient(Api::class.java)`·`createClient(Api.class)`·`createClient<Api>()` 호출의 `HttpServiceProxyFactory`
(`builderFor(adapter)`·`builder(adapter)`·`exchangeAdapter(adapter)`)에 넘긴 `RestClientAdapter`·`WebClientAdapter`·
`RestTemplateAdapter`의 클라이언트에서 온다. 프로젝트의 `@Controller`·`@RestController`가 구현하는 인터페이스는 `createClient`가
없으면 서버 계약으로 보고 client 사실을 내지 않는다(서버 decl은 `routes --role server`가 낸다). `URI` 매개변수가 있으면 URL 전체가
실행 시점 값이라(`UrlArgumentResolver`) dynamic이다.

## base 결합

Spring Framework 6.2.19·Spring Boot 3.5.16 소스(Maven Central `*-sources.jar`)로 확인했고, 오라클 실행이 재확인한다.
기존 네 갈래(RFC 3986·슬래시 결합·dio 단순 연결) 어느 것과도 같지 않아 isthmus #131(`76b6141`)이 `docs/HTTP-WRAPPERS.md` base 결합
표에 Spring 행으로 받았고, url-compose 벡터 `base-join/spring-*`(`producer:kartograph`) 13개가 고정한다. `RouteConformanceTest`는 케이스마다
합성 Kotlin 클라이언트를 실제 `RouteCallScanner`로 스캔해 비교한다.

**`DefaultUriBuilderFactory`(RestClient·WebClient `baseUrl`, `RestClient.create(url)`, `DefaultUriBuilderFactory(url)`을 준
`uriTemplateHandler`·`uriBuilderFactory`) — 문자열 연결 + `//` 축약.**

- 템플릿에 host가 없으면 base 빌더를 복제해 템플릿의 구성요소를 옮긴다(`spring-web` `DefaultUriBuilderFactory.java:292-295`,
  `UriComponentsBuilder.java:461-465` → `HierarchicalUriComponents.java:531-551`). 경로는 `builder.path(getPath())`
  (`HierarchicalUriComponents.java:933`)로 옮겨지고, `path`는 `FullPathComponentBuilder.append`로 **슬래시 없이 이어 붙인다**
  (`UriComponentsBuilder.java:835-848`, `896-898`). 그래서 base 경로 `/api` + `users` = `/apiusers`, `/api/` + `/users` = `/api//users`다.
- 빌드할 때 `getSanitizedPath`가 모든 `//`를 `/`로 줄인다(`UriComponentsBuilder.java:906`, `910-921`). 위 예는 `/api/users`다. base 없이
  쓴 템플릿의 `//`도 줄어든다.
- 경로가 `/`로 시작하지 않으면 URI 문자열이 host 뒤에 `/`를 넣는다(`HierarchicalUriComponents.java:491-497`) — base `http://h` + `users`
  = `/users`.
- host가 있는 템플릿(`http://…`, `//host/…`)은 base를 쓰지 않는다(`DefaultUriBuilderFactory.java:295`의 `uri.getHost() == null` 분기).
  빈 템플릿은 base 자신이다(`:289-290`).
- 점 세그먼트는 지우지 않는다(`normalize()`를 부르지 않는다) — 오라클의 `/api/users/./me/../self`가 그대로 전송됐다.
- URI 템플릿 변수는 `\{([^/]+?)\}`이고 `{name:regex}`의 이름은 `:` 앞이다(`UriComponents.java:51`, `298-301`). 세그먼트 전체를 채우면
  `{}`, 부분 세그먼트면 url-compose 규칙대로 dynamic이다. RestClient·WebClient의 기본 인코딩(`TEMPLATE_AND_VALUES`,
  `DefaultUriBuilderFactory.java:51`)은 값의 `/`를 `%2F`로 인코딩하지만 RestTemplate의 기본(`URI_COMPONENT`, `RestTemplate.java:271-275`)은
  인코딩하지 않는다 — 그래도 `{}`로 낸다(문자열 보간의 `{}`와 같은 가정, 오라클 값은 한 세그먼트).

**Spring Boot `RestTemplateBuilder.rootUri` — `/`로 시작하는 템플릿에만 문자열로 붙인다.** `build()`가 마지막에
`RootUriBuilderFactory.applyTo`로 감싸고(`spring-boot` `RestTemplateBuilder.java:734-736`), `apply`는 `/`로 시작하는 템플릿에만
root를 앞에 붙인다(`RootUriTemplateHandler.java:62-67`, `RootUriBuilderFactory.java:39-41`). 결과는 base 없는 `DefaultUriBuilderFactory`가
파싱하므로 `//` 축약도 적용된다. `/`로 시작하지 않는 상대 템플릿은 host 없이 남아 요청할 수 없으므로 경로를 확정하지 않는다.

**URI 인자.** `RestTemplate`의 `URI` 오버로드는 `uriTemplateHandler`를 거치지 않는다(`RestTemplate.java:429`, `838-841`).
`RestClient.uri(URI)`는 상대 URI를 base에 `URI.resolve`(RFC 3986)로 해석하고(`DefaultRestClient.java:340-348`) —
`/x`는 base 경로를 버리고, `x`는 base 경로의 마지막 `/` 뒤에 붙는다 — `WebClient.uri(URI)`는 그대로 보낸다(`DefaultWebClient.java:256-259`).
빌더 람다 `.uri { … }`는 base를 복제한 빌더를 받아(`DefaultRestClient.java:335-337`, `DefaultWebClient.java:251-253`) 템플릿처럼 문자열로 잇는다.

**`@HttpExchange`.** 타입 수준 url과 메서드 수준 url이 둘 다 있으면 `type + ("/" if 둘 다 경계 슬래시가 없을 때) + method`, 하나만 있으면
그것이다(`HttpServiceMethod.java:234-258`). 결과 템플릿은 어댑터가 클라이언트의 `uri(template, vars)`로 넘기므로
(`RestClientAdapter.java:100-108`) 위 `DefaultUriBuilderFactory` 결합이 이어진다. 동사는 메서드 수준, 없으면 타입 수준이다(`:218-230`).
어노테이션의 `${…}`는 `HttpServiceProxyFactory`에 `embeddedValueResolver`를 줄 때만 풀리므로(`:241-244`) 생산자는 dynamic으로 낸다.

사실로 옮기면 다음과 같다.

| base | 템플릿 `/x` | 템플릿 `x` | 템플릿이 절대 URL |
|---|---|---|---|
| 리터럴(`DefaultUriBuilderFactory`) | `base경로 + /x`(`//` 축약), root + authority | `base경로 + x`(슬래시 없음), root + authority | host 뒤 경로, root + 그 authority |
| 미상(`DefaultUriBuilderFactory`) | base | dynamic + `ambiguous-base-join:` | 같음 |
| 리터럴 `rootUri` | `root경로 + /x`, root + authority | dynamic(요청 불가) | 같음 |
| 미상 `rootUri`·base 없음 | base | dynamic | 같음 |

base 앞에 값을 모르는 조각이 오는 템플릿(`"$usersUrl/users/{id}"`, `userServiceUrl + "/x"`)은 단순 연결로 본다 — 뒤 리터럴이 `/`로
시작하면 base, 아니면 dynamic + `ambiguous-base-join:`이다. 값이 무엇이든(절대 URL이든 base 뒤 상대 경로든) `/x`는 세그먼트 경계의
꼬리이므로 base 주장이 맞다.

## base 값 해석

- **빌더 사슬:** `RestClient.builder()`·`WebClient.builder()`(주입받은 Boot 빌더 포함) … `baseUrl(x)` … `build()`,
  `RestClient.create(x)`·`WebClient.create(x)`, `RestClient.create(restTemplate)`·`builder(restTemplate)`(RestTemplate의 base),
  `RestTemplateBuilder`(주입·생성) … `rootUri(x)` … `build()`, `uriTemplateHandler`·`uriBuilderFactory(DefaultUriBuilderFactory(x))`,
  `mutate()`. base를 바꾸지 않는 설정 메서드(헤더·인터셉터·요청 factory·codec 등)는 건너뛰고, 모르는 빌더 메서드는 base를 모르는
  것으로 둔다(종류는 유지).
- **선언 따라가기:** 같은 함수의 `val`, 속성·필드(초기식·`by lazy`·getter·가변이면 모든 대입, Java 생성자 대입), 함수 몸체
  (식 몸체·모든 `return`), Kotlin 주 생성자·Java 생성자·`@Bean` 메서드 매개변수와 `@Autowired`·`@Inject`·`@Resource` 필드는 Spring
  주입으로 푼다 — `@Qualifier("x")`·`@Named("x")`가 있으면 그 이름의 `@Bean`(메서드 이름·`@Bean("x")`·`@Bean(name = …)`·메서드의
  `@Qualifier`), 없으면 그 타입 빈이 하나일 때 그것, 여럿이면 `@Primary` 하나, 그다음 매개변수·속성 이름과 같은 빈 이름이다. 프로젝트가
  선언한 한정자 어노테이션이나 고를 수 없는 경우는 base를 모른다. 일반 메서드 매개변수로 받은 클라이언트는 호출자마다 다를 수 있어
  base를 모른다.
- **Boot가 주입하는 빌더**(`RestClient.Builder`·`WebClient.Builder`·`RestTemplateBuilder`)는 base가 없다. 프로젝트에
  `RestClientCustomizer`·`WebClientCustomizer`·`RestTemplateCustomizer`가 있으면 모른다.
- **값:** 리터럴·템플릿·연결, 같은 파일/다른 파일 상수, 읽기 전용 속성 초기식, 지역 `val`, `@Value("${key}")`·`@Value("${key:default}")`가
  붙은 매개변수·속성·필드·주 생성자 매개변수. 설정은 서버 라우트와 같은 기본 프로필 규칙이다([SPRING-ROUTES](SPRING-ROUTES.md)의
  설정 절, `SpringProjectConfig`): 앱 모듈(`@SpringBootApplication`·Boot 실행 호출)의 `application*.yml`·`.properties`,
  `spring.profiles.active`로 켠 프로필까지. 다른 프로필이 같은 키를 다르게 정하면 기본 프로필 값을 쓰고 `unresolved-base-url:`로
  알린다. 환경 변수·명령행·config server·SpEL(`#{…}`) 재정의, `@ConfigurationProperties` 객체는 모델링하지 않는다.
- authority는 userinfo를 뗀 소문자 `host[:port]`이고 scheme 기본 포트(http 80·https 443)는 지운다(Retrofit base와 같은 정규화).
  URI 템플릿 변수(`{host}`)가 든 base는 풀지 않는다.

## 신원·baseRef·service

- `symbol.usr`는 명령형 호출이면 호출을 감싸는 메서드의 JVM id(impact·reach와 같은 id 공간), `@HttpExchange`면 인터페이스 메서드
  id다. `--graph-file`이 신선할 때만 붙고, 못 붙이면 `missing-route-usrs:`로 센다. 한 줄 식 몸체 함수(`fun f() = client.get()…`)가
  주 생성자 있는 클래스 머리에 가려 신원을 잃던 소스 범위 판정도 이 작업에서 고쳤다(`ChannelBridgeScanner.enclosingDeclaration`).
- `baseRef`는 base를 공급하는 선언의 소스 한정 id(`kt:` 접두사)다 — 클라이언트를 담은 `@Bean` 메서드·속성·필드, 인라인 생성이면
  감싸는 함수, 값을 모르는 앞 조각(`"$usersUrl/…"`)이면 그 값을 담은 속성(`kt:pkg.Type.usersUrl`). workspace link의 `match.baseRefs`로
  쓸 수 있고, base를 풀었으면 authority가 `match.hosts`로 귀속을 맡는다.
- **`service`는 싣지 않는다.** route-call의 `service`는 호출이 향하는 서버의 신원인데, 대상 서버의 `spring.application.name`은 호출자
  저장소에 없다. host가 서비스 이름인 구성(Spring Cloud LoadBalancer의 `http://user-service/…`)도 authority로 그대로 실리므로 link의
  `match.hosts`로 귀속하면 된다. 문서 `--service`는 기존처럼 사용자가 줄 때만 싣는다.

## 한계

- 수신 식을 클라이언트로 증명하지 못한 호출 중 RestTemplate 전용 메서드 이름(`getForObject` 등), `.get()…uri(` 사슬, URL 템플릿
  모양(절대 URL·`{x}`)의 첫 인자를 받은 `put`·`delete`·`exchange`·`execute`, `exchange(RequestEntity, …)`, 여러 문장으로 나눈 요청
  spec은 `route-call-coverage:`로 센다.
- base를 풀지 못해 상대 경로를 base 앵커로 낸 호출과 `@HttpExchange` 인터페이스는 `unresolved-base-url:`로 센다(다른 프로필 의존도
  같은 접두사로 따로 센다). 경로 구조 전체가 감싸는 함수의 매개변수에서 오는 호출은 다른 싱크처럼 `http-wrapper-undeclared:`로 센다.
- Feign(`feign.`·`org.springframework.cloud.openfeign.`), OkHttp·Ktor·`java.net.http` 직접 호출은 모델링하지 않는다
  (`route-call-coverage:` 파일 수). Spring Boot 4의 `@ImportHttpServices`와 `HttpServiceGroup` 설정 base도 모델링하지 않아
  `createClient`가 없는 인터페이스는 base 앵커다.

## 오라클 검증

합성 Spring Boot 클라이언트 앱 32개 호출을 실제로 실행해 로컬 프록시가 받은 요청과 대조했다 — 32개 모두 일치, dynamic 0, 불일치 0.
방법·표는 [experiments/phase7b-spring-clients](../experiments/phase7b-spring-clients/README.md), 기록은
`fixtures/spring-clients-corpus/oracle/spring-client-requests.json`, 검사는 cli `SpringClientOracleTest`다.

## 두 서비스 e2e

서비스 A(`orders-service`)의 핸들러 `GET /orders/{id}` → `OrderService` → `UserGateway.fetchUser`(RestClient,
`@Value` base `http://users.internal:8081/api`)가 서비스 B(`users-service`)의 `GET /api/users/{id}`를 부른다. isthmus
`trace`(origin/main `f9dcd1d`)의 workspace context에 link `orders->users`(`match.hosts: ["users.internal:8081"]`)를 두고 B의 route를
선택하면, B의 핸들러 → A의 호출 지점(`exact`) → A의 역방향 도달(`OrderService.describe`, A route 핸들러 `OrdersController.order`)로
이어진다. 재현은 `experiments/phase7b-spring-clients/e2e/run_trace.py`, 기록은 `e2e/recorded/`다. A 자신의 route는 A가 server인
link가 없어 trace가 잇지 않고(`http-member-unlinked`), 핸들러 usr가 A route-decl의 usr와 같은 것으로 확인한다.

## isthmus 문서 반영

아래 제안은 isthmus #131(`76b6141`)이 `docs/HTTP-WRAPPERS.md` base 결합 표와 url-compose 벡터로 받았다(이 저장소는 isthmus를 고치지 않는다).
제안 당시 내용:

| 결합 방식 | 예 | 경로 `/x` | 경로 `x` |
|---|---|---|---|
| 단순 연결 + 중복 슬래시 축약, base 리터럴 | Spring `DefaultUriBuilderFactory`(RestClient·WebClient `baseUrl`, `@HttpExchange` 어댑터) | `base경로 + /x`(`//`→`/`), root | `base경로 + x`, root(base `http://h`는 `/x`) |
| 단순 연결 + 중복 슬래시 축약, base 미상 | 같음 | base | dynamic + `ambiguous-base-join:` |
| `/` 접두 템플릿에만 root 연결 | Spring Boot `RestTemplateBuilder.rootUri` | 리터럴이면 `root경로 + /x` root, 미상이면 base | root 없음(요청 불가) — dynamic |

같은 절에 dio와의 차이(Spring은 점 세그먼트를 지우지 않고, `//` 축약을 scheme 뒤만이 아니라 경로 전체에 적용하며, host 없는 base
경로 `+ x`에 `/`를 넣는다)와, `@HttpExchange`의 타입·메서드 url 슬래시 결합(`HttpServiceMethod.initUrl`)을 적어 두면 다른 JVM
생산자와 해석이 갈리지 않는다. GRAPH-EXCHANGE의 "base 접두사와 클라이언트 결합" 절의 "라이브러리별 네 갈래"도 다섯 갈래가 된다.
