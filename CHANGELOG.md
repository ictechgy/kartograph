# Changelog

이 프로젝트의 주목할 만한 변경은 이 파일에 기록한다. 형식은
[Keep a Changelog](https://keepachangelog.com/en/1.1.0/)를 따르고 버전은
[Semantic Versioning](https://semver.org/spec/v2.0.0.html)을 따른다.

## [Unreleased]

## [0.18.2] - 2026-10-01

0.18.1 태그의 릴리스는 배포 문서의 설치 버전 검사에서 중단되어 아카이브·Portal에 발행하지 않았다.
태그를 유지하고 새 버전으로 배포한다.

### Fixed

- 의존성 분석 설치 예제와 compiler collector 안내의 버전을 현재 릴리스와 맞춘다.

- SQL FROM/JOIN의 테이블 값 함수를 관계로 추측하지 않고 미해석 피연산자로 센다.

## [0.18.0] - 2026-09-30

### 요약과 업그레이드 주의

- 새 명령과 형식: `routes --role client`(선언 래퍼·`java.net.URL`·Retrofit·Spring RestTemplate/RestClient/WebClient/
  `@HttpExchange`), `routes --role server`(Spring MVC·WebFlux, `limitationScopes`), `impact --format language-traversal`과
  새 `reach`, `schema`의 JPA·Spring Data persistence 사실, Gradle plugin `snapshot merge`. 세부는 아래 절이다.
- isthmus 호환: `routes`·`language-traversal` 문서는 isthmus http 도메인과 `trace`가 있는 isthmus-cli 0.10.0 이상(발행 예정)이
  필요하다. isthmus-cli 0.9.0은 이 문서를 거부한다. 벤더링한 적합성 벡터 lock은 isthmus `76b6141`이다.
- 동작 변경: class 인덱스 캐시 형식이 4(0.17.0)에서 6으로 바뀌어 업그레이드 뒤 첫 실행에서 옛 캐시 항목을 한 번 다시
  파싱한다. `schema`는 JPA 프로퍼티 이름을 그대로 컬럼으로 내던 동작 대신 감지한 Hibernate 명명 전략을 적용하며
  `jpa-persistence-sources:` limitation이 없어졌다. `bridges`·`schema`는 신선도가 확인된 snapshot에 사유가 빈
  `graph-file-freshness-matched: ` 한계를 더 이상 싣지 않는다. 이스케이프된 따옴표(`"\""`) 뒤 문자열 리터럴을 이제 리터럴로 읽어
  `bridges`·`schema` 결과가 달라질 수 있다.
- Gradle plugin: resource witness의 외부 디렉터리 슬롯 이름이 바뀌어 `processResources`가 한 번 다시 실행될 수 있다.
  `snapshot merge` 병합본의 `memberScopes`를 모르는 0.17.0 이하는 병합본을 `build-scope-mismatch`로 거부한다(fail-closed).
- 바뀌지 않은 것: `impact` 기본 출력(`kartograph-impact` v1), 도달성·dead·query 결과, 단일 capture snapshot 바이트.

### Added

- `routes --role client --project <dir> [--wrappers <http-wrappers.json>] [--include-tests] [<source-root>...]`가
  isthmus http 도메인용 `"target": "http"`, `"roles": ["client"]` `bridge-facts` 문서로 클라이언트 HTTP
  호출(`route-call`)을 낸다. isthmus `http-wrappers` v1로 선언한 래퍼(생성자 위치 인자, 기본 동사,
  순서가 바뀐 명명 인자, `methodEnum`·`.name`), 경로·동사를 증명한 `java.net.URL` 요청, Retrofit 동사
  어노테이션을 읽는다.
- 경로 해석은 isthmus `url-compose`·`http-template` 공유 벡터를 따른다. 같은 파일 상수, 세그먼트 전체
  보간(`{}`), query 꼬리와 `?`로 시작함을 증명한 끝 지역 변수 제거, userinfo·query·fragment 제거와
  고엔트로피·웹훅 세그먼트 마스킹을 적용한다. 벡터는 `fixtures/isthmus-conformance/`에 lock과 함께
  벤더링하고 테스트가 sha256 대조 뒤 모든 생산자 케이스를 실행한다.
- 테스트 소스 세트는 기본 제외(`sourceSets.tests: "excluded"`)이며 `--include-tests`는 `testSource`를
  단다. 낡은 선언(`http-wrapper-unresolved:`), 선언되지 않은 싱크(`http-wrapper-undeclared:`), 모델링하지
  않은 클라이언트(`route-call-coverage:`), 미상 base 뒤 상대 경로(`ambiguous-base-join:`)는 추측한 사실
  대신 limitation으로 계수한다.
- `impact --format language-traversal`이 isthmus `trace`용 `language-traversal` v1 역방향 문서를, 새 `reach`
  명령이 정방향(`dependencies`) 문서를 낸다. 여러 root를 한 번에 순회해 정점마다 닿는 모든 root(`roots`, 최대 64개와
  `rootsTruncated`), 최단 via 목격, root별 하한 근거 등급(`evidence`), 잇지 못한 호출(`unresolvedCalls`)을 싣는다.
  `--roots-from`은 routes·schema의 bridge-facts 문서를 root 목록으로 받는다. 결과는 무작위 그래프에서 root별 전수
  BFS와 대조해 검증한다.
- `--dispatch direct|bound|candidates|all`(기본 `candidates`)로 따를 dispatch 간선을 고른다. 구현이 하나로 정해지는
  프로젝트 수신 타입의 dispatch는 `bound`, 그 밖의 계층 후보는 `candidate`이며, `FunctionN`/SAM `invoke`에서 모든
  람다·익명 class 본문으로 퍼지는 후보는 기본에서 빼고 `lambda-dispatch-excluded:`로 계수한다.
- `language-traversal` 문서가 `revision`과 `graphRevision`을 cartograph와 같은 규칙으로 싣는다. `revision`은
  `--revision <rev>`(임의의 revision 문자열), snapshot의 commit 라벨, 프로젝트 디렉터리에 커밋하지 않은 변경·추적되지
  않은 파일이 없을 때의 git `HEAD` 순이며 작업 트리가 더러우면 뺀다. 고친 소스 위에서 `HEAD`를 실으면 isthmus가 낡은
  분석을 최신으로 보기 때문이다. git은 CLI 계층만 부른다. `graphRevision`은 snapshot 파일 바이트 해시 대신 정점
  id·종류, 간선 종류·출처, 순회 근거 등급, 어휘적 소속과 그 캡처 여부, 잇지 못한 호출 수의 `sha256:` 내용 해시라서
  같은 snapshot의 `impact --format language-traversal`과 `reach`가, snapshot 표기(`--compact`·`--include-paths`)와
  무관하게 같은 값을 낸다. root와 `--revision`에 isthmus가 거부하는 제어 문자(C0·DEL·C1·U+2028·U+2029)나 짝 없는
  서러게이트가 있으면 문서를 만들기 전에 종료 코드 64로 거부한다.
- `snapshot`이 classfile `EnclosingMethod`를 `graph.enclosures`로 싣는다. 순회는 람다·익명 객체·suspend 람다 본문을
  감싼 선언의 일부(`contains`, `direct`)로 잇는다. 이 필드가 없는 옛 snapshot은 `lexical-enclosures-unavailable:`로 알린다.

### Changed

- `routes`가 JVM 신원을 붙이지 못한 사실을 `missing-route-usrs:`로 센다. `--graph-file` snapshot의 source 경로가
  공통 접두사만큼 어긋나면 snapshot과 routes의 `--project` 불일치를 접두사와 함께 밝히고, snapshot이 stale이거나
  없을 때도 이유를 적는다. 전에는 usr가 조용히 0건이 됐다.
- class 인덱스 캐시 형식을 0.17.0의 4에서 6으로 올렸다(5를 거쳐 콜백 사실로 6). 옛 캐시 항목은 한 번 다시 파싱된다.

### Retrofit baseUrl 결합 (Added·Changed)

- `routes --role client`가 Retrofit 서비스 인터페이스를 만드는 `create` 호출(`create(Api::class.java)`·`create(Api.class)`·
  `create<Api>()`, `Class<T>`·reified 생성 함수의 호출)을 찾아 수신 식의 `baseUrl`을 따라간다. 같은 식의 빌더 사슬
  (`apply { baseUrl(…) }` 포함), 지역 `val`, 속성(`= …`, `by lazy`, getter, `var`·Java 필드의 모든 대입), 함수 몸체, 한정자가
  맞는 Dagger/Hilt `@Provides` provider(`@Inject` 생성자·필드, `@Provides` 매개변수), Koin `single`·`factory`와 `get()`을 읽는다.
  하위 서비스로 만든 결합은 상위 서비스 인터페이스의 상속 메서드에도 적용한다. 테스트 소스의 `create`는 보지 않는다.
- base가 리터럴(문자열·템플릿·같은 파일/다른 파일 상수·읽기 전용 속성·`HttpUrl.get`/`toHttpUrl` 래퍼·가장 가까운 모듈의
  `buildConfigField` 리터럴)이면 Retrofit 사실이 `authority`와 OkHttp RFC 3986 규칙으로 base 경로를 결합한 `pathAnchor: root`
  템플릿을 싣는다(`items/{id}` + `https://h/shop/v2/` → `/shop/v2/items/{}`, `../v1/x` → `/shop/v1/x`). base가 여럿이면(여러
  `create`, 빌드 타입마다 다른 `BuildConfig`) base마다 사실 하나다. isthmus workspace link의 `match.hosts`로 귀속된다.
- base를 풀지 못하면 `pathAnchor: base`를 유지하고, Retrofit 인스턴스를 담은 선언을 찾았으면 그 소스 한정 id를 새 route-call
  필드 `baseRef`(`kt:<pkg>.<Type>.<member>`, Koin 한정자는 `#named:x`)로 싣는다 — `match.baseRefs`로 귀속할 수 있다. 그런
  서비스 수는 `unresolved-base-url:`로 센다. 실행 시점 값·모호한 DI 결합·`/`로 끝나지 않는 base 경로는 authority를 만들지 않는다.
- URL을 바꾸는 OkHttp 인터셉터(`intercept` 몸체·인터셉터 람다 안의 `newBuilder()` 뒤 `url`·`host`·경로 변경)가 있으면 모든 Retrofit base를 버리고
  `url-rewrite-interceptors:`로 센다(헤더만 바꾸는 인터셉터는 세지 않는다).
- 검증: Retrofit 2.12.0 + MockWebServer 오라클에 코퍼스 팩토리로 만든 baseUrl 결합 케이스 10개를 더해 54케이스 중 일치 48·
  이유 있는 dynamic 6·불일치 0(결합 전 불일치 10). pythograph Phase 6 e2e에서 Android 주문·결제 호출이 isthmus trace 체인의
  `OrderViewModel.refresh`·`CheckoutViewModel.pay`까지 닿고 `unattributed-calls-omitted`가 사라졌다.

### Retrofit 인터셉터 client 결합 (Changed·Fixed)

- URL을 바꾸는 OkHttp 인터셉터 하나가 프로젝트의 모든 Retrofit base를 버리던 규칙을 인스턴스별로 좁혔다. 재작성 인터셉터(class·
  `object`와 그 하위 타입·`Interceptor { … }` 람다·익명 객체·부착 호출 안 람다)가 만들어지는 모든 곳에서 `addInterceptor`·
  `addNetworkInterceptor`까지 값의 흐름을 따라가고, Retrofit `client(…)`·`callFactory(…)`의 OkHttpClient를 base와 같은 DI 규칙
  (`@Provides` 한정자·Koin `get(named(…))`·속성·함수 몸체)과 빌더 사슬·`newBuilder()` 복사본·빌더 지역 변수로 풀어, 재작성이 붙은
  client를 쓰는 인스턴스의 base만 버린다. `client`를 주지 않은 Retrofit은 인터셉터 없는 기본 client다.
- 인스턴스마다 `url-rewrite-interceptors: Retrofit instance <baseRef> …` 한 줄을 내고, 모든 재작성이 원래 요청 URL의 authority만
  바꿈을 증명하면 숨은 요청(root 템플릿·상대 경로 접미사·리터럴 동사)의 isthmus 호출 측 `limitationScopes`를 싣는다.
- 보수 규칙: `Authenticator`·`EventListener`는 `NONE`이나 몸체에 재작성이 없는 프로젝트 구현만 요청을 바꾸지 않는 것으로 보고,
  OkHttpClient임을 증명하지 못한 `callFactory`는 재작성으로 본다. 재작성·부착의 흐름이나 base를 푼 인스턴스의 client를 하나라도
  증명하지 못하면(목록·반복문·래퍼·다른 함수 인자, `interceptors()` 조작, 매개변수 빌더·확장 함수 안 부착, 어디에도 붙지 않은 재작성,
  상위 타입 `@Provides`·`@Binds`, 원천을 모르는 client) 이전처럼 모든 base를 버리고 이유를 적은 프로젝트 한 줄로 센다.
- 수정: 재작성 client는 전체 URL·network-path 어노테이션의 host도 바꿀 수 있어 그 사실의 `authority`도 믿지 않는다(이전에는 인터셉터가
  있어도 어노테이션 host를 실었다). 새 요청 생성(`Request.Builder()`·`Request(…)`), 원래 요청이나 그 사본이 아닌 요청을 넘기는
  `chain.proceed(x)`, `newBuilder().apply { url(…) }`, 부착 호출 괄호 안의 Java 람다, Java `Interceptor x = …` 초기식도 재작성으로 센다.
  `Authenticator`는 돌려주는 요청이 모두 `null`·`response.request()` 계열일 때만 요청을 바꾸지 않는 것으로 본다.
- 내부: #123의 DI·속성·함수 몸체·Koin 추적을 `SourceValueResolver`로 옮겨 Retrofit base 해석과 OkHttpClient 해석이 공유한다.
- 검증: Retrofit 오라클에 인터셉터 결합 코퍼스(`interceptor-client`, 규칙마다 케이스 18개)를 더해 로컬 프록시로 실제 요청을 기록했다
  (Dagger 2.59·Koin 4.2.2, Maven Central). 일치 7·재작성으로 base를 버림 11(기록이 모두 재작성을 확인, 스코프 8개가 기록한 요청을 덮음)·
  불일치 0이며, 변경 전 `main`(5564cb3)은 18개 사실 모두 authority가 없었다. 기존 54케이스 기록은 바뀌지 않았다.

### Spring HTTP 클라이언트 route-call (Added·Fixed)

- `routes --role client`가 Spring `RestTemplate`(`getForObject`·`postForEntity`·`exchange`·`execute` 등)·`RestClient`·`WebClient`
  (`get()`…`method(…)` 뒤 `.uri(…)`) 호출과 `@HttpExchange` 인터페이스 메서드를 `route-call`로 낸다. 수신 식이 Spring 클라이언트임을
  증명한 호출만 보고, URL은 문자열 템플릿·`UriComponentsBuilder` 사슬·`URI`·빌더 람다를 읽는다. `@HttpExchange` 사실의 신원은
  인터페이스 메서드이고, 명령형 호출은 감싸는 메서드다.
- base URL은 빌더 사슬(`baseUrl`·`RestTemplateBuilder.rootUri`·`DefaultUriBuilderFactory`)·`@Bean`(타입·`@Qualifier`·`@Primary`·이름)·
  `@Value("${key}")`(저장소 안 기본 프로필 설정)로 풀고, Spring Framework 6.2.19·Boot 3.5.16 소스로 확인한 규칙으로 잇는다 —
  `UriBuilderFactory`는 문자열 연결 뒤 `//` 축약(`/api` + `users` = `/apiusers`), `rootUri`는 `/`로 시작하는 템플릿에만 붙는다.
  `@HttpExchange`는 타입·메서드 url을 `HttpServiceMethod.initUrl`처럼 잇고 `createClient`에 쓴 어댑터 클라이언트의 base를 쓴다.
  풀지 못하면 `pathAnchor: base`와 `baseRef`, `unresolved-base-url:`이고, 다른 프로필이 바꾸는 설정 값도 같은 접두사로 알린다.
  `service`는 싣지 않는다(대상 서비스 이름은 호출자 저장소에 없다 — link의 `match.hosts`·`match.baseRefs`로 귀속한다).
- 모델링하지 못한 요청 모양(`RequestEntity`, 클라이언트로 증명하지 못한 수신 식)은 `route-call-coverage:`로 센다. Spring 클라이언트
  import만으로 파일 전체를 모델링하지 않은 클라이언트로 세던 계수는 빼고, Feign(`org.springframework.cloud.openfeign.`)을 더했다.
- 합성 Spring Boot 앱 32개 호출의 실행 오라클(로컬 프록시 기록)과 모두 일치한다(변경 전 사실 0건). 서비스 A 핸들러 → RestClient →
  서비스 B route 체인이 isthmus trace workspace link로 이어진다([experiments/phase7b-spring-clients](experiments/phase7b-spring-clients/README.md)).
- 수정: 한 줄 식 몸체 Kotlin 함수(`fun f() = client.get()…`) 안의 사실이 `symbol.usr`를 잃던 결함 — 주 생성자가 있는 클래스 머리가
  Java 선언 정규식에 맞아 더 좁은 범위로 이겼고, 식 몸체는 뒤 선언의 `{`까지 범위를 잡았다. Kotlin 파일에는 Java 선언 모양을 쓰지 않고
  식 몸체는 식이 끝나는 줄까지로 잡는다.
- 내부: Retrofit base 해석의 소스 범위 도구(지역 선언·매개변수·속성 대입·`return`)를 `SourceScopes`로 옮겨 Spring 해석과 공유한다.
- 적합성 벡터를 isthmus `76b6141`로 다시 벤더링했다. isthmus #131이 이 결합 규칙을 base 결합 표에 받아 url-compose 벡터
  `base-join/spring-*` 13개(`producer:kartograph`, join `spring-uri-builder`·`spring-root-uri`·`spring-http-exchange`)로 고정했고,
  `RouteConformanceTest`가 케이스마다 합성 Kotlin 클라이언트를 실제 `RouteCallScanner`로 스캔해 템플릿·앵커·authority·
  `ambiguous-base-join:`을 비교한다(13개 모두 통과, 생산자 코드 변경 없음). 새 suite `http-dispatch`도 lock에 더했다 — 생산자 케이스는
  `order` 검증뿐이고 kartograph는 `order`를 내지 않으므로(서버 문서는 항상 `specificity`) 다른 생산자 규칙이 생기면 실패하도록만 검사한다.

### Spring 서버 라우트 (Added)

- `routes --role server --project <dir> [--graph-file <snapshot> [--input-bindings <file>]] [--service <name>] [--include-tests] [<source-root>...]`가
  Spring MVC·WebFlux 어노테이션 controller를 isthmus http `route-decl` 사실로 낸다(`"roles": ["server"]`,
  `"dispatch": "specificity"`). `@RequestMapping`·`@GetMapping` 계열·`@HttpExchange` 계열, 명시 `@AliasFor`로 병합한
  사용자 합성·메타 어노테이션, 인터페이스·상위 class에서 물려받은 매핑, 클래스×메서드 경로 곱, 동사 합집합과 동사 없는
  매핑의 `ANY`를 spring-webmvc 7.0.8 규칙대로 다룬다. 근거와 규칙표는 [Spring 서버 라우트](docs/SPRING-ROUTES.md)다.
- 신선한 `--graph-file`이 있으면 snapshot provenance의 class root에서 바이트코드 어노테이션 값(컴파일러가 접은
  Java·Kotlin 상수)을 읽고, `symbol.usr`는 snapshot에 있는 핸들러 메서드 JVM id(`impact`·`reach`와 같은 id)다. 위치는
  소스의 어노테이션 토큰이며 소스만 있을 때는 프로젝트 안 상수 색인으로 값을 푼다. snapshot 형식은 바꾸지 않았다.
- 저장소 안 Boot 설정의 기본 프로필로 `server.servlet.context-path`·`spring.webflux.base-path`와 `${key:default}`
  플레이스홀더를 푼다(저장소 어디에도 없는 키의 기본값만 `configDefault`). 기본값이 아닌 `spring.mvc.servlet.path`,
  `addPathPrefix`, 다른 프로필 재정의, 웹 스택 미상은 `unresolved-route-prefix:`와 `base` 앵커로 알린다.
- `trailingSlash`(Boot 3 이상 `strict`, Boot 2 `optional`, 버전 미상은 생략과 `route-framework-version-unknown:`),
  `narrowed`, 정규식 경로 변수의 `paramConstraints`(포함 관계를 증명한 모양만 `int`·`uuid`·`slug`), 끝 `/**`·`{*path}`의
  접두사 decl 펼침(usr가 있을 때 `catchAllPrefix`)을 싣는다. 템플릿으로 옮길 수 없는 경로·풀지 못한 상수·플레이스홀더는
  dynamic 사실과 `route-coverage:`로, 프레임워크 제공 경로(`/error`, 정적 리소스, actuator, Security, springdoc 등)는
  `framework-provided-routes:`로, 함수형 라우터·view controller·서블릿 등록·JAX-RS·AntPathMatcher는 `route-coverage:`로 센다.
- 검증: 공개 Spring Boot 앱 3개(spring-petclinic, spring-petclinic-kotlin, spring-petclinic-rest)와 합성 MVC·WebFlux 앱의
  `/actuator/mappings` 대비 정밀도 100%(바이트코드·소스 모드 모두). 합성 앱 소스와 기록한 오라클을
  `fixtures/spring-routes-corpus/`에 두고 `SpringRouteCorpusTest`가 대조한다. `missing-route-usrs:` 문구가 사실 종류
  (`route-decl`·`route-call`)를 밝힌다.

### Spring 라우트 limitation 스코프·CRUD 미해결 분류 (Added·Changed)

- `routes --role server`가 `framework-provided-routes:`를 제공자마다 한 줄로 내고, 받을 수 있는 요청의 상한을 증명한 제공자에
  isthmus http `limitationScopes`를 붙인다: 오류 컨트롤러(`/error`, 모든 method), welcome page(루트), 정적 리소스·webjars
  (`static-path-pattern`·`webjars-path-pattern` 접두사, `GET`·`HEAD`), actuator(base-path), springdoc(`GET`·`HEAD`), H2 console.
  원소는 context-path·base-path를 붙인 요청 경로이고, 설정을 확정하지 못하거나 다른 프로필이 바꾸면 그 제공자의 스코프를
  생략한다. Spring Security·Data REST·GraphQL은 스코프 없이 문서 전체에 적용된다. isthmus `78d3dee` 측정에서 error 판정
  가능 비율이 spring-petclinic 0%→57.1%, spring-petclinic-kotlin 0%→56.7%가 됐고(spring-petclinic-rest는 Security 때문에
  0%), 거짓 error는 0건이다.
- Spring이 빈 값을 받는 자리(마지막 요소 `*`, 부분 세그먼트의 변수·`*`)를 빈 값으로 채운 decl을 함께 낸다. 원본 포함 16개를
  넘으면 dynamic과 `route-template-expansion-capped:`다. 부분 세그먼트 `*`(`/files/*.json`)는 이제 dynamic이 아니다.
  `setMatchOptionalTrailingSeparator(true)`만 있는 프로젝트는 `trailingSlash: "optional"`이다.
- isthmus 적합성 벡터를 `78d3dee`로 다시 벤더링했다(`http-template`의 Spring PathPattern 15건, 새 `http-limitation-scope`).
  `RouteConformanceTest`가 `producer:kartograph` 케이스를 실제 서버 생산자로 돌린다.
- `reach`·`impact --format language-traversal --persistence-facts <file>`: `schema --graph-file` 문서가 호출 줄에서 호출자에
  그 저장소의 relation을 귀속한 상속 Spring Data 저장소 호출(`save`·`findById` 등, 프로젝트 저장소 인터페이스 owner)을
  `unresolvedCalls`에서 빼고 `persistence-modeled-calls: N`으로 알린다. fragment 재정의나 프로젝트 저장소 구현 class가 있으면
  빼지 않는다. spring-petclinic에서 isthmus trace의 `reach-possibly-incomplete`가 사라졌다.
- JPA 명명 전략과 Spring 라우트 설정의 Spring Boot 버전 검출을 `SpringBootVersions` 하나로 합쳤다. 표지 목록·버전 해석·카탈로그
  참조 해석을 공유하고, 형식마다 인정하는 소비자를 적어 두 쪽이 합치기 전에 읽던 형식 집합을 그대로 지킨다(동작 불변). 형식을
  합집합으로 넓히면 plugin 버전과 다른 버전을 고정한 starter 좌표가 함께 있을 때 라우트 설정의 버전이 "모름"으로 바뀐다.

### Spring Security·Data REST·GraphQL limitation 스코프 (Added·Changed)

- `routes --role server`가 Spring Security `framework-provided-routes:`에 스코프를 붙인다. `@Bean` 메서드가 `HttpSecurity`
  하나로 `SecurityFilterChain`을 만들고 몸체가 DSL 호출(lambda·chained·Kotlin `http { }`)과 `build()`뿐이면, 필터가 직접 응답하는
  경로(`LogoutFilter`의 `logoutUrl` — CSRF가 꺼지면 GET·POST·PUT·DELETE, form login 처리 URL의 POST, 기본 로그인·로그아웃 페이지와
  `/default-ui.css`의 GET, `oauth2Login`의 `/oauth2/authorization`·`/login/oauth2/code`, 7.x 보호 자원 메타데이터)를 context-path 아래
  요청 경로로 싣는다. 체인 bean이 없거나 모두 조건부거나 앱이 여럿이면 Boot 기본 체인(`formLogin`·`httpBasic`) 경로를 더한다. 모르는
  호출·configurer·사용자 필터·`RequestMatcher`·`WebSecurityCustomizer`·`Customizer` bean·spring.factories configurer·리액티브
  Security·Boot 2·일부 source 루트 스캔이면 이전처럼 문서 전체에 적용한다. 근거는 Spring Security 6.0.8·6.5.5·7.1.0과 Spring Boot
  3.0.13·3.5.6·4.1.0 공식 소스다.
- Spring Data REST는 `spring.data.rest.base-path`가 루트가 아니고 Boot 자동 구성 모듈이 있으며 코드가 base path를 바꾸지 않을 때 그
  아래 전체(모든 method)로, GraphQL은 `spring.graphql.http.path`·옛 `spring.graphql.path`·기본 `/graphql`과 `/schema`, GraphiQL,
  WebSocket 경로의 GET·HEAD·POST로 좁힌다. 값을 풀지 못하면 스코프를 생략한다.
- Spring Security 표지를 넓혔다. starter 없이 `spring-security-web`·`-config`나 OAuth2 starter만 써도 Security 한계를 낸다.
- isthmus `c395c59` 재측정에서 error 판정 가능 비율이 spring-petclinic-rest 0/49→40/49(81.6%)가 됐고, spring-petclinic 16/28·
  spring-petclinic-kotlin 17/30은 같다. 필터 응답을 실제로 기록한 오라클로 거짓 error는 0건이다(`measure_judgeable.py --probe`).

### language-traversal 도움말 (Fixed)

- `impact --help`가 안내하는 `impact --format language-traversal --help`(와 `-h`)가 `unknown traversal option: --help`로
  종료 코드 64였다. `--format language-traversal` 한 쌍을 뗀 나머지가 `--help`·`-h` 하나뿐이면 순회 도움말을 내고 0으로 끝난다.
  `reach --format language-traversal --help`도 같다. 다른 인자가 섞이면 이전처럼 사용 오류다.

### Gradle plugin 다중 모듈 snapshot (Fixed·Added)

- Fixed: build 디렉터리를 project 밖으로 옮긴 Android 모듈의 snapshot이 `Android XML is outside the project root`로
  실패하던 문제를 고쳤다. 선언된 build 디렉터리 아래 merged manifest·생성 resource는 `build/...` 근거 위치로 기록하고,
  resource producer witness의 외부 디렉터리는 위치 기반 불투명 슬롯으로 다시 연결한다. `kartographDead<Variant>`도 같다.
- Fixed: 같은 바이트의 외부 JAR(AndroidX stub JAR 등)가 여러 위치에 있으면 `compiler input binding is missing or ambiguous`로
  실패하던 문제를 고쳤다. compiler 선언 입력을 우선해 결정적으로 고르고, 내용이 같은 디렉터리만 task·입력·후보를 밝혀 거부한다.
- Fixed: Java만 있는 Android unit test에서 생성되지 않은 Kotlin unit-test 출력 때문에 javac witness가
  `fingerprint input is missing`으로 거부되던 문제를 고쳤다. 같은 variant compiler 출력은 부재까지 추적한다.
- Added: `kartograph snapshot merge`가 모듈별 snapshot을 구성원 class root 재인덱싱으로 하나로 합친다. provenance는 경로만
  옮겨 `memberScopes`와 함께 싣고 `--input-bindings-output`에 로컬 연결을 써서 합친 snapshot도 다시 검증할 수 있다.
- Added: `routes --input-bindings`로 project 밖 입력이 있는 snapshot의 신선도를 확인한다.
- Added: Android `snapshotIncludeUnitTests`(기본 `true`, `-Pkartograph.snapshotIncludeUnitTests`)로 unit-test component를
  snapshot에서 뺄 수 있다. 빼면 `unit-test-components-excluded:` limitation을 남긴다.
- Compatibility: 단일 capture 문서는 바이트가 같다. `memberScopes`는 병합본에만 쓰며, 이 필드를 모르는 옛 버전은
  병합본을 `build-scope-mismatch`로 검증하지 않는다(fail-closed). resource witness의 외부 디렉터리 슬롯 이름이 바뀌어
  `processResources`가 한 번 다시 실행될 수 있다.

### Compatibility

- `impact`의 기본 출력(`kartograph-impact` v1)은 바뀌지 않는다. `graph.enclosures`는 간선이 아닌 선택 필드라 도달성·dead·
  query·기존 impact 결과에 영향이 없고, 옛 reader는 이 키를 읽지 않는다.

### Callback flow (language-traversal)

- `snapshot`이 bytecode 값 흐름으로 관측한 콜백 사실 `graph.callbackArguments`(한 메서드에서 만든 람다·지역 class가
  호출 인자로 그대로 넘어간 사실), `graph.parameterUses`(함수형 파라미터의 실행·전달·캡처·필드·반환 쓰임),
  `graph.lambdaEscapes`(만든 람다를 필드·반환·직접 실행 등 호출 인자 밖으로 쓴 사실)를 싣는다.
  Compose `ComposableLambdaKt` 래퍼는 감싼 람다와 같은 값으로 본다.
- `impact --format language-traversal`이 받은 람다를 실행하거나 수정 없이 넘기는 함수(최대 8단계)를 람다 본문에
  `callback` 관계로 잇는다. 값이 빠져나가지 않고 실행 지점이 모두 프로젝트 안이면 `bound`, 라이브러리 코드에 넘기거나
  필드·반환 등으로 빠져나가면 `candidate`다. 이 간선으로 닿은 함수는 그 호출 문맥에서만 목록에 싣고 다른 호출자로
  퍼뜨리지 않아, 공통 UI 함수를 통해 무관한 화면이 들어오지 않는다. 실행 지점 없이 빠져나간 흐름은
  `callback-flow-unresolved:`에 이유별로, 콜백 사실이 없는 옛 snapshot은 `callback-facts-unavailable:`로 알린다.
  `reach`(정방향)는 콜백 간선을 따르지 않는다.
- class 인덱스 캐시 형식 6부터 `graphRevision`이 콜백 간선과 콜백 사실 캡처 여부를 담는다(형식 5 개발 빌드와 값이 다르다).
- Gradle plugin `kartographSnapshot`(Android variant 포함)도 CLI `snapshot`과 같은 콜백 사실을 싣는다. 이전에는
  `includeSourcePaths`를 켜면 source 경로를 붙이는 재조립에서 세 목록이 빠져, 캡처 표식은 참인데 사실이 비어 콜백 간선이
  조용히 사라졌다. 이 snapshot을 `snapshot merge`로 합친 결과도 한 번에 캡처한 CLI snapshot과 같은 사실을 싣는다.
- 호환성: 새 필드는 간선이 아닌 선택 필드라 도달성·dead·query·기본 `impact` 출력은 그대로다.

### 테스트 소스와 신선도 (language-traversal·routes)

- Changed: `impact --format language-traversal`과 `reach`가 테스트 소스 선언을 기본으로 순회하지 않는다. 테스트 소스는
  production과 따로 컴파일되는 별도 프로그램이고 production 코드는 테스트 class를 참조하지 않으므로, production 호출 지점의
  `bound` 판정은 production 구현만 센다. 전에는 plugin snapshot이 unit test를 기본으로 담으면 `FakeClient : Client` 같은
  테스트 fake가 두 번째 구현이 되어 `bound`가 `candidate`로 떨어지고 테스트 정점이 도달 목록을 채웠다. 테스트 정점은
  소스 위치의 경로 규칙(`routes`의 기본 테스트 제외와 같은 `src/test`·`src/androidTest`·`src/test<Variant>`·`src/*Test`·
  `src/testFixtures`)으로 가리고, 뺀 수를 `test-sources-excluded:`로 알린다. `--include-tests`는 전체 그래프를 순회한다.
  root가 테스트 선언이거나 production 선언이 테스트 선언을 호출·참조·상속하면(가정 위반) 빼지 않고 `test-sources-included:`로
  이유를 밝힌다. `graphRevision`은 실제로 순회한 그래프의 해시다.
- Added: `impact --format language-traversal`과 `reach`가 `routes`처럼 snapshot 입력의 신선도를 확인하고
  `--input-bindings`로 project 밖 입력을 다시 연결한다. 전에는 항상 `saved-graph:` 한계에 머물렀다.
- Fixed: `routes`·`bridges`·`schema`가 신선도가 확인된 snapshot에도 사유가 빈 `graph-file-freshness-matched: `를 한계로
  냈다. matched는 한계가 아니므로 싣지 않고 unverified·stale만 원인과 함께 싣는다.

### Class hop 범위 (language-traversal)

- Added: `impact --format language-traversal`과 `reach`에 `--class-hops all|member-only`(기본 `all`)를 더했다. 프레임워크
  콜백 모델(`runtimeModel`, class → 모든 멤버)을 거꾸로 따르면 ViewModel 멤버 하나의 변경이 소유 class를 거쳐 그 타입을
  시그니처·필드·캡처로 적은 모든 선언으로 퍼진다. `member-only`는 이름만 적은 참조와 콜백 모델을 같은 class 정점(상속 사슬
  포함)에서 잇지 않는다. class는 목록에 남고, 인스턴스 생성·상속·어휘적 소속·콜백·런타임 모델 참조·멤버 호출은 계속
  따른다. 따르지 않은 간선 수는 `class-hops-narrowed:`로 알린다. 기본값의 문서와 `graphRevision`은 그대로다.

### JPA·Spring Data persistence (schema)

- `schema`가 JPA 엔티티 매핑을 테이블·컬럼 사실로 낸다. `@Table(name, schema)`·`@Column`·`@JoinColumn(s)`·`@JoinTable`·
  `@Embedded`/`@Embeddable`과 `@AttributeOverride(s)`·SINGLE_TABLE/JOINED/TABLE_PER_CLASS 상속과 `@DiscriminatorColumn`·
  `@PrimaryKeyJoinColumn`·`@ElementCollection`/`@CollectionTable`/`@OrderColumn`·`@Transient`·`@MappedSuperclass`·Java getter
  property access를 다룬다. 이전에는 JPA 프로퍼티 이름을 그대로 컬럼으로 냈으나 이제 Hibernate 명명 전략을 적용한다.
- 명명 전략을 빌드·설정 파일에서 감지한다: Spring Boot 3(Hibernate 6 `CamelCaseToUnderscoresNamingStrategy` +
  `SpringImplicitNamingStrategy`), Spring Boot 4(Hibernate 7 `PhysicalNamingStrategySnakeCaseImpl` — 숫자 경계와 인용 이름
  처리가 다르다), 순수 Hibernate 6·7 기본값. `--jpa-naming <profile>`로 고정할 수 있다. 버전을 모르면 후보 사이에서 갈리는
  이름만 dynamic이고, 사용자 정의 전략·전역 인용·검증하지 않은 Hibernate 5 세대(`javax.persistence`, Boot 2)는 근거와 함께
  limitation(`jpa-naming-assumed:`·`jpa-naming-unresolved:`)으로 밝힌다.
- 명명 벡터 `fixtures/jpa-naming/vectors.json`(합성 엔티티 → 실제 Hibernate 6.6.53·7.2.24·7.4.5 스키마 export)을 추가하고
  `JpaNamingVectorTest`가 6개 케이스 × 31개 테이블의 완전 일치를 검사한다. 오라클은 네트워크가 필요해
  `experiments/jpa-persistence/run.py`로 따로 돌린다(기본 CI 밖).
- Spring Data 저장소(제네릭 기반 인터페이스·`@RepositoryDefinition` 포함)의 파생 질의 이름(Spring Data `PartTree` 규칙의
  속성 경로), `@Query` JPQL(별칭·join·`IN(…)`·SpEL `#{#entityName}`), native `@Query`, `@NamedQuery`/`@NamedNativeQuery`,
  상속 CRUD를 관계·컬럼 사실로 낸다. `EntityManager`의 `createQuery`/`createNativeQuery`/`createNamedQuery`/`find`/
  `getReference`와 Spring JDBC 수신자(`JdbcTemplate` 계열·`JdbcClient`) 호출도 읽는다.
- `--graph-file`이 있으면 저장소 호출 지점마다 사실을 내고 호출 명령을 담은 메서드의 JVM id를 `symbol.usr`로 싣는다
  (스파이크 S3: javac·kotlinc는 상속 CRUD 호출도 사용자 저장소 owner의 `invokeinterface`로 기록하지만 프로젝트 정점이
  없어 외부 호출로만 남는다). 저장소에 선언된 질의 메서드의 선언 사실은 인터페이스 메서드 id를 싣는다.
- 모델링하지 않은 매핑·해석하지 못한 질의 경로·JPA가 아닌 저장소·타입을 모르는 EntityManager 작업은 dynamic 사실과
  `jpa-unmodelled-mappings:`·`jpa-unresolved-query-paths:`·`unresolved-repository-methods:`·`non-jpa-repositories:`·
  `untyped-entity-manager-operations:`·`repository-call-sites-need-snapshot:` limitation으로 남긴다. 기존
  `jpa-persistence-sources:` limitation은 없어졌다.

### Fixed (schema)

- 문자열 리터럴 판정이 `"\""`처럼 이스케이프된 따옴표 바로 뒤의 닫는 따옴표를 놓쳐 리터럴을 동적 식으로 읽던 문제를
  고쳤다(bridges·schema 공용 해석기).

### Retrofit 오라클과 순회 대칭성 (Phase 4 종료 조건)

- `routes --role client`의 Retrofit 템플릿을 Retrofit 2.12.0 + OkHttp MockWebServer 실행 기록과 대조하는 합성 코퍼스
  (`fixtures/retrofit-corpus/`)와 재생성 오라클(`experiments/phase4-retrofit/`)을 더했다. 44개 케이스 중 38개가 템플릿·동사·
  authority까지 일치하고 6개는 이유가 있는 dynamic(`@Url`, `@Path(encoded = true)`, 부분 세그먼트, base 위 `..`, 파일 밖
  상수)이며 불일치는 0이다(수정 전 13개). cli `RetrofitOracleTest`가 커밋된 기록으로 검사한다.
- Fixed: Java Retrofit 어노테이션의 명명 요소(`@GET(value = …)`, `@HTTP(method = …, path = …)`)를 읽지 못해 `/{}` 템플릿과
  `methodDynamic`을 내던 문제, 풀지 못한 경로 상수(다른 파일 object 등)를 경로 매개변수 `{}`로 바꿔 거짓 템플릿(`/{}`)을 내던
  문제(이제 dynamic, query 뒤면 무시), `@Path(encoded = true)` 값이 `/`로 여러 세그먼트가 될 수 있는데 `{}` 한 세그먼트로 내던
  문제(이제 그 자리부터 dynamic), OkHttp가 지우는 점 세그먼트(`./x`, `a/../b`, `.`)를 템플릿에 남기던 문제(base 위로 올라가는
  `..`는 dynamic), 완전한 이름 `@retrofit2.http.GET`을 놓치던 문제, Java 인터페이스 상수(암묵적 static final)를 상수로 보지
  못하던 문제, OkHttp 값 타입(`RequestBody`·`ResponseBody`·`MediaType`·`MultipartBody`·`Headers`)만 import한 Retrofit 서비스
  파일을 모델링하지 않은 클라이언트로 세어 거짓 `route-call-coverage:`를 내던 문제(`FormBody`와 중첩 타입·동반 객체 확장
  `RequestBody.Companion.create`·`MultipartBody.Part` 포함).
- `RouteUrlRules.removeDotSegments`와 `JoinMode.Declared(resolveDotSegments)`를 더했다. 점 세그먼트 제거는 OkHttp로 확인한
  Retrofit 경로만 켜고, 엔진마다 다를 수 있는 `JoinMode.Rfc3986`(Ktor 등)은 바꾸지 않았다. 벤더링한 url-compose 벡터(isthmus
  `78d3dee`와 바이트 동일)는 그대로 모두 통과한다.
- `reach`와 `impact --format language-traversal`의 대칭성을 검증했다. 따르는 콜백 간선이 없으면(`--dispatch direct` 등) 모든
  `--class-hops`에서 정확한 전치(depth·evidence 포함)이고, 콜백 간선이 있으면 `impact`만 콜백 문맥 쌍
  `{G | G →cb+ B, B = U 또는 U ∈ reach(B)}`만큼 넓다(설계). 테스트 소스는 root가 범위를 정하므로 `--include-tests`나
  production 쌍에서만 같은 그래프를 비교한다. analysis `TraversalSymmetryTest`(무작위 그래프)와 cli
  `TraversalSymmetrySelfTest`(kartograph 자신의 컴파일 그래프 표본 373,161쌍)로 고정했다. 의도하지 않은 비대칭은 없었다.
  규칙은 [IMPACT](docs/IMPACT.md#정방향역방향-대칭성-reach--impact)다.

### Retrofit route-call 신원 (Fixed)

- Fixed: `routes --role client --graph-file`이 Retrofit route-call 사실에 `symbol.usr`를 붙이지 못하던 문제. 서비스 메서드는
  추상이라 bytecode에 줄 번호가 없고 사실 위치는 어노테이션 줄이어서 위치 기반 부착이 어떤 정점도 찾지 못했고,
  `missing-route-usrs:`만 남아 isthmus trace가 Android 주문·결제 호출에서 클라이언트 심볼로 이어지지 못했다. 이제 소스에서
  복원한 소유 타입(중첩은 `$`)·이름·동사 어노테이션으로 snapshot 정점을 찾고, 같은 이름·동사의 overload는 매개변수 수
  (`suspend`의 `Continuation` 제외)로 가른다. 신원은 인터페이스 메서드 자신이다 — 호출 지점이 이를 `invokeinterface`로 부르므로
  역방향 순회가 저장소·ViewModel까지 닿고, 같은 엔드포인트의 호출 지점이 여럿이어도 사실 하나로 덮는다.
- Fixed: 하위 인터페이스를 수신 타입으로 부른 상속 추상 메서드(`invokeinterface Sub.m`, `m`은 상위 인터페이스 선언)의 호출
  간선이 그래프에서 사라지던 문제. `Sub#m` 정점이 없어 간선이 버려지고 dispatch 모델은 구현 후보만 이어, 상위 선언(상속
  Retrofit 메서드의 usr)에서 역방향 순회가 호출자에 닿지 못했다. snapshot이 JVM 인터페이스 메서드 해석(JVMS §5.4.3.4)의 최대
  특수 추상 선언으로 `call` 간선을 더한다. default 메서드·`java/lang/Object` 메서드·class 수신 타입은 바꾸지 않고 외부 호출
  해석(`external-dispatch`)도 그대로다.
- Kotlin(suspend·`@HTTP`·기본 인자·overload·중첩·상속)과 Java 서비스의 실제 바이트코드로 usr와 역방향 도달을 index
  `RetrofitSymbolTest`·`InheritedInterfaceCallTest`, cli `RoutesCliTest`에 고정했다.

## [0.17.0] - 2026-09-24

### Added

- `schema --project <dir> [--format json] [--graph-file <snapshot>]`가 isthmus용
  `"target": "persistence"` `bridge-facts` 문서로 DB 관계 참조(`relation-use`)를 낸다.
  Room 어노테이션, `java.sql`/`javax.sql` import가 있는 파일의 JDBC 호출 인자, Exposed `Table`·DSL,
  jOOQ plain-SQL, SQL 모양 문자열 리터럴, SQLDelight `.sq`/`.sqm`을 스캔한다.
- 동적·미해석 근거는 `dynamic` 사실과 계량된 limitation으로 보존한다. 게이트 없는 리터럴은
  대문자 SQL 키워드만 인정하고 건너뛴 리터럴 수를 `skipped-sql-literals`로 알린다.
  JPA 파생 쿼리·Spring Data·Ktorm·jdbi는 지원을 주장하지 않으며 빈 스캔은 `"target": null`이다.
  `--graph-file`은 snapshot이 fresh일 때만 JVM 심볼 식별자를 붙인다.

## [0.16.0] - 2026-09-23

### Added

- MCP `discover_symbols`로 심볼 또는 파일의 실제 USR 후보를 페이지 조회한다. 응답 크기에 따라
  페이지를 줄여도 다음 offset과 전체 후보 수를 유지하며, current/base 존재 시점을 표시한다.

### Fixed

- Kotlin `package.function` 표기가 실패하면 FILE_FACADE metadata에 근거한 후보를 제공한다.
  모듈 snapshot에 저장소 기준 파일 경로를 보낸 경우에는 정확한 경로 우선·경로 구성 요소 suffix로
  실제 선언 후보를 제안한다. 오버로드와 여러 파일 중 하나를 자동 선택하지 않는다.
- 파일 전체 impact가 16 KiB를 넘으면 discovery 후 선택한 USR만 재조회하도록 안내한다.
  원래 query/impact 문서의 `notFound`·`ambiguous`·한계와 분석 결과는 유지한다.

### Verified

- v8 공개 detekt/ktlint snapshot의 실패 선택자 3개를 후보 조회와 정확한 USR 재조회로 복구했다.
  이는 저장된 그래프의 선택자 검증이며 AI 효용 개선이나 현재 빌드 신선도 증명이 아니다.
  기존 실험 원문·점수는 변경하지 않았다.

## [0.15.0] - 2026-09-22

### Added

- 선택적 `compilerInputs` 설정으로 javac/KAPT/KSP task의 선언 파일·값 속성과 구현 artifact를
  실행 전후에 대조하는 v3 processor receipt를 추가했다. KSP의 ABI 요약과 별도로 원본 library도
  추적하고, 정규화된 cache가 복원한 옛 JAR bytes·실패 빌드의 완료 기록은 거부한다.
- snapshot의 processor 출력에 실제 class bytes·JVM ID·선택된 class root가 일치하는 선언 귀속을
  보존한다. source basename·resource로 선언을 추측하거나 호출 간선·보존·synthesized를 바꾸지 않는다.
  공개 metadata는 `coverage: gradle-declared-task-inputs`, `complete: false`로 관찰 범위를 명시한다.

### Fixed

- JVM snapshot이 Java 소스가 없는 의존 프로젝트의 정상적인 빈 class 출력을 누락된 필수 입력으로
  거부하던 문제를 수정했다. Gradle이 선언한 프로젝트 class 디렉터리만 부재까지 추적하며, 같은 내용의
  빈 외부 출력도 위치 식별자로 구분한다. 임의 라이브러리 누락은 계속 실패하고 출력 파일이 생기거나 바뀌면 stale이 된다.

### Verified

- 보존된 Now in Android 산출물을 복원해 `RealAgpRJarCliTest` 2개를 skip 없이 재실행했다.
  실제 R.jar root의 witness 누락 거부·추가 후 matched·bytes 변조 시 stale 계약을 유지한다.

## [0.14.0] - 2026-09-21

### Added

- `snapshot --processor-output-config`와 Gradle snapshot task의 `processorOutputConfigs`가
  javac/KAPT/KSP의 완료된 v2 output receipt를 입력·artifact·scope·raw·출력 bytes와 대조하고
  `processorOutputs`에 보존한다. API 생성과 callback 중 직접 쓰기를 구분하며, 그래프 간선·
  synthesized·보존·의존성 판정은 바꾸지 않는다. source-only `processorGenerations`와 별도다.
- 선택적 Gradle adapter가 native processor task의 출력에 raw sidecar와 명시한 직접 쓰기 파일을
  추가한다. 실제 javac17/KAPT2.4.10/KSP2.3.12의 build cache 복원과 configuration cache 재사용,
  4종 출력·stale·실패 빌드를 검증한다. 모든 compiler 입력의 완전성을 주장하지 않는다.
- 버전·라이선스·collector JAR·runner·cache adapter를 포함한 별도 collector ZIP을
  GitHub release 산출물과 SHA256SUMS에 추가한다. CLI/plugin runtime 의존성은 늘리지 않는다.
  두 번의 재현 빌드와 압축 해제한 독립 javac 소비 프로젝트로 설치 계약을 검사한다.

### Changed

- standalone runner의 새 receipt는 제품과 같은 content fingerprint를 쓰는 v2다. 성공 뒤에는
  후속 Gradle 작업이 cache key를 재사용할 수 있게 token을 보존한다. token은 성공 증거가 아니다.
  기존 v1 receipt의 독립 `verify`는 유지하며 snapshot에 연결하려면 v2로 다시 수집한다.

## [0.13.0] - 2026-09-20

### Added

- `dependencies --baseline`·`--suppress`·`--write-baseline`과 Gradle의 dependencyBaseline/
  dependencySuppress·baselineOutput을 제공한다. 좌표·버전·scope·제안·클래스 근거를 정확히
  지문화하고 UTC 만료일과 입력 변경을 검사한다. capture는 필터 전 관찰을 저장하며,
  Gradle의 capture 설정은 strict를 비활성화하지 않는다. 잘못된 입력은 덮어쓰지 않는다.
- 선택적 소스 빌드 collector `javac-processors`가 JSR-269 Filer의 source 생성/close와
  실제 processor artifact를 관찰한다. 완료된 compiler/generated-source receipt와 일치한
  근거만 snapshot의 `processorGenerations`에 보존한다. 캐시·변조·실패·processing 비활성화
  경계를 검증하며, 귀속만으로 참조 간선·reachability·dependency unused 판정을 바꾸지 않는다.
  KAPT/KSP·직접 filesystem·class/resource 출력은 범위 밖이다.

### Changed

- 모든 `bridges` transport의 `generatedAt`을 문서 추출 시각으로 통일하고, 읽은 source의
  최신 filesystem mtime은 선택적 `sourceModifiedAt`에 기록한다. 빈 입력은 mtime을 생략한다.
  0.12.0 이하의 기본 generatedAt은 source mtime이었다. 두 시각 모두 compiler freshness
  또는 앱 실행 완전성을 증명하지 않는다.
- 새 processor 사용 문서를 CLI ZIP/TAR에 포함하고 선택적 collector의 소스 빌드 범위를 명시한다.

### Fixed

- compiler collector의 프로젝트·source 경로 별칭을 정규화해 macOS의 반복 javac 빌드에서
  같은 `/var`·`/private/var` 입력을 서로 다른 프로젝트 경계로 오인하지 않는다.

## [0.12.0] - 2026-09-20

### Added

- `dependencies --library`는 JVM Signature·Kotlin metadata·inline 본문으로 `api`/`implementation`
  배치를 검토하고, `--resolved-dependencies`는 실제 compile classpath에서 미선언 전이 의존성의
  소유자와 참조 타입을 보고한다. main/test 사용을 분리하고 모든 6개 보고 형식에 같은 근거·한계를
  유지한다. 모호한 소유권·미해석 API의 부재를 삭제나 scope 축소 근거로 삼지 않는다.
- Gradle의 `kartographDependencies` 및 Android `kartographDependencies<Variant>`가 public
  provider/Artifact API로 컴파일 출력을 연결한다. 선택적 test 컴파일, configuration cache,
  class/scope 입력 변경에 따른 무효화와 report를 쓴 뒤 strict 실패를 지원한다. 빌드 파일 자동
  수정·processor별 생성 코드 귀속은 제공하지 않는다.

### Fixed

- `bridges --target react-native`의 일반 RN/Expo 수신 측 스캔을 복원한다. 0.11.0의 RN 이벤트
  옵션 검증이 일반 v1 필터까지 Flutter로 제한해 정상 명령을 코드 64로 거부하던 회귀를 고쳤다.
  Basic/EventChannel·RN 이벤트 문서의 target 구분과 잘못된 옵션 거부는 유지한다.
- 의존성 분석 사용 문서를 CLI ZIP/TAR에 포함하고, README가 가리키는 문서가 실제 아카이브에
  존재하는지 릴리스 게이트에서 검사한다.
- dependency 참조 스캐너가 descriptor에서 지워진 제네릭 타입을 classfile Signature에서 복원해
  실제 컴파일 의존성이 미사용으로 보고되는 경우를 막는다. AAR의 class/embedded JAR를 읽고,
  손상된 내부 ZIP과 과도하게 깊은 Signature는 부분 판정 없이 거부한다.

## [0.11.0] - 2026-09-20

### Added

- Accept isthmus external-retentions v0 in `dead --external-retentions`, retain exact JVM nodes and
  their owning types, and preserve original bridge caller evidence in `--explain` and snapshots.
  Unmatched IDs and malformed documents fail without partial application.
- Export core React Native global event emissions with `bridges --rn-events`, using a separate
  v2 transport. Dynamic names and unsupported emitter bindings remain explicit limitations.

- `dead`가 누락된 보존 입력을 `input-hint` 진단으로 보고한다. keep/consumer rule이나 dependency classpath
  입력이 전달되지 않았거나 manifest가 component 보존 근거를 만들지 못한 채 finding이 보고되면
  text/gradle/github-actions/sarif/json/markdown 형식에 측정 사실과 과소계측 가능성을 함께 싣는다.
  JSON은 `inputHints`(id·message)로 노출한다. hint는 finding이 아니므로 strict 판정·baseline·종료 코드에
  관여하지 않고, finding이 0건이면 보고하지 않는다. Gradle plugin의 report도 같은 신호를 공유한다.
- `dead`가 `--runtime-classes`(줄 단위 class 목록)와 `--coverage`(JaCoCo/Kover XML)로 사용자가 제공한 런타임
  근거를 선택 입력으로 받는다. 관측된 class의 finding은 JSON·SARIF·markdown `confidence`가
  `runtime-observed`로 표시된다. 커버리지를 수집·실행하지 않고 class 단위로만 판정하며, finding·strict·
  종료 코드·baseline은 바뀌지 않는다.
- `dependencies` 명령이 선언 의존성 목록(TSV: coordinate·scope·artifact)과 classfile 참조를 대조해
  참조가 없는 dependency를 `unused-dependency`로 text/JSON에 보고한다. 참조는 descriptor·annotation
  (type-use 포함)·invokedynamic(handle descriptor 포함)·LDC/ConstantDynamic·지역 변수·module
  uses/provides까지 독립 스캐너로 모은다. `--strict`는 finding이 있으면 exit 1이고, processor·
  runtime-only와 test root 없는 test scope는 판정하지 않고 개수만 알린다. artifact는 project-relative
  경로를 그대로, 절대경로는 파일 이름만 출력하며, 판정은 dependency 삭제 승인이 아니다.

## [0.10.2] - 2026-09-18

### Added

- `dead`가 보존 근거를 하나도 만들지 않은 keep rule을 `unmatched-keep-rule` 입력 진단으로 보고한다.
  CLI와 Gradle plugin의 text/gradle/github-actions/sarif/json/markdown 보고에 규칙의 파일·줄 근거를 싣고,
  JSON은 `unmatchedKeepRules` 필드로 노출한다. `-keepnames`·`allowshrinking`처럼 root를 만들지 않는
  지시자는 대상이 아니며, unmatched는 규칙 삭제 승인이 아니다.

### Fixed

- `bridges`의 Expo Modules DSL 스캔이 세 가지 형태를 놓치던 공백을 메운다.
  중첩 제네릭 인자(`AsyncFunction<List<String>>`), 완전 정규화된 `ModuleDefinition`
  래퍼(`expo.modules.kotlin.modules.ModuleDefinition { }`), 수신자 한정 호출
  (`this.AsyncFunction`)이 모두 스캔에서 투명해 사실과 limitation 없이 사라졌다.
  제네릭 절은 탐욕 일치가 비교식 안의 `>`를 호출 앵커로 잘못 고정할 수 있어
  지연 일치로 바꿨고, `View<T>` 제네릭 형태를 새로 인식한다.
  0.10.1에서 인식되던 Expo 사실의 출력은 그대로다 — `expo-haptics@14.1.4`
  재스캔에서 module-export·method-handle 4건이 동일하게 나옴을 확인했다.

## [0.10.1] - 2026-09-18

### Added

- `bridges --target flutter --events`가 Flutter EventChannel의 `setStreamHandler`에서 stream-handle 사실을
  별도 v2 문서(`transport: event-channel`)로 보낸다. `--messages`와는 별도 문서라 동시 사용은 usage 오류다.
  송신 개념이 없는 EventChannel에는 send 계열 limitation을 만들지 않는다.
- `bridges`가 Expo Modules DSL을 인식한다. `import expo.modules.kotlin.modules.Module`이 있는 Kotlin
  파일에서 `class X : Module()` 선언을 모듈로 보고 `ModuleDefinition { }` 본문의 `Name(…)`·`View(…)`·
  `Function`/`AsyncFunction`을 읽는다. `module-export`·`component-export`에는 `"mechanism": "expo"`를
  싣고 메서드 사실에는 싣지 않는다. `Name`이 없으면 런타임 규칙과 같은 클래스명 폴백을 쓰고, 정의
  본문이 스캔 범위 밖이면 클래스명을 `dynamic` 근거로 남긴다. 컴포넌트 이름 경계는 뷰 클래스가 아니라
  모듈 이름이다(`requireNativeViewManager(moduleName)`). Java 파일은 receiver DSL을 쓸 수 없어
  import만 보이면 `unscanned-expo-java:`로 알린다.

### Changed

- `snapshot`의 입력 fingerprint가 파일 digest를 최대 4개 worker에서 병렬로 계산한다. digest 값·입력 순서·before/after 검증
  계약·내용 해시 정책은 그대로이며, spool 예산은 입력 순서대로 크기 기준으로 선할당한 뒤 실제 크기로 정산한다.
  dependency JAR이 많은 입력에서 fingerprint 단계 시간이 줄고, class 하나를 바꾼 뒤의 캡처 비율 측정은 `docs/INDEX-CACHE.md`에 기록한다.

### Fixed

- `bridges`가 `setMethodCallHandler`/`setStreamHandler`/`send` 수신자의 `!!`·`?.`을 인식한다.
  nullable 필드의 `channel!!.setXxx` 패턴을 놓쳐 경계가 비던 사례를 고친다. JNI/native interop
  파일은 정적 채널 키로 귀속할 수 없어 fact 대신 파일 수준 `unscanned-ffi-interop` limitation으로 보고한다.
- `snapshot` 입력 fingerprint의 병렬 digest에서 (1) 계획 단계의 symlink 검사와 파일 열기 사이가 벌어져 그 사이 symlink로
  바뀐 파일을 따라갈 수 있던 문제를 열기·크기 읽기에 `NOFOLLOW_LINKS`를 적용해 막고, (2) 캡처가 interrupt되면 worker가 만든
  임시 spool이 남을 수 있던 문제를 scope가 worker 종료를 기다린 뒤 닫도록 고치며, (3) 크기를 읽지 못한 JAR이 예산을
  차지하지 않은 채 spool돼 총 상한(256 MiB)을 넘길 수 있던 문제를, 속성을 읽지 못한 JAR을 그 pass의 spool 대상에서 빼는 것으로 막는다. 기록되는 fingerprint 값은 바뀌지 않는다.
- `impact`의 계약 확장이 dispatch 모델의 호출자→구현 후보 간선(`origin = dispatchModel`)을 변경 method의 override로 잘못
  따르던 문제를 고친다. 변경 method가 호출하는 인터페이스의 구현체가 영향 후보에 오르고, 실제 호출 사슬 대신 후보 경로가
  witness로 선택돼 `transitive` 호출자가 `structural`로 분류되던 사례가 있었다. 호출자 방향의 dispatch 후보 사용은 그대로다.
- `impact`가 멤버→소유 class `reference` 간선(도달성용)을 사용 관계로 읽어, `<clinit>`이나 class 정점에 닿는 변경이 같은 class의
  모든 멤버로 퍼지던 문제를 고친다. 다른 class와 중첩 class에서 오는 참조는 계속 영향에 포함한다.

## [0.10.0] - 2026-09-16

### Added

- `why <symbol>` 명령은 `dead`와 같은 입력·분석으로 한 선언의 보존 상태, 파일:줄 근거, 보존 root부터의 대표 경로,
  직접 caller, test-only 표시, 측정된 신뢰도 등급을 한 번에 출력한다. 답은 도달성 사실이며 삭제 승인이 아니다.
- JSON·SARIF·markdown finding에 `confidence` 등급(`static`/`needs-runtime-review`/`unmeasured`)을 싣는다.
  같은 소스 파일에서 측정된 미해결 runtime 채널 관측에서 산출하며 text/gradle/github-actions 출력은 바꾸지 않는다.
- `dead --suppress <file>`은 `expires` 날짜가 있는 finding 억제를 fail-closed로 읽고, 만료된 억제는 풀어
  machine report의 `expiredSuppressions`에 남긴다. `markdown` 리포트 형식과 dead JSON을 PR 코멘트 본문으로 바꾸는
  `Scripts/render-dead-comment.py`를 추가한다.
- Gradle plugin의 Android **application** variant 자동 캡처가 `process<Variant>Resources`의 생성 `R.jar`를
  resource producer witness로 덮는다. 0.9.0에서 `unwitnessed-class-root`로 거부되던 application snapshot이
  `matched`가 되고, R.jar가 바뀌면 `stale`로 보고한다. task 실패 시 witness를 기록하지 않는다.
- `bridges --target flutter --messages`는 Kotlin/JVM Flutter `BasicMessageChannel`의 실제 `setMessageHandler`
  등록을 선택적으로 bridge-facts v2로 내보낸다. literal 채널 이름과 immutable alias만 해석하고,
  Kotlin `var`·Java non-final·재할당된 이름은 dynamic으로 남긴다. Kotlin 송신 호출은 fact로 만들지 않고
  `unscanned-message-sends` limitation으로 센다.
- `--graph-file`을 주면 관찰 위치를 compiler snapshot의 실제 Kotlin/JVM 함수·메서드 정점과 조인할 때만
  `symbol.usr`를 붙이고, graph가 없거나 위치가 모호하면 `missing-handler-usrs` limitation을 보존한다.
  MethodChannel 사실도 같은 compiler 신원을 사용하며, snapshot 신선도를 먼저 확인해 stale 그래프의 USR은 버리고
  `graph-file-freshness-<status>` limitation으로 기록한다.

### Fixed

- bridge-facts v1과 v2의 위치 열을 교환 계약대로 UTF-8 byte offset으로 계산한다. 다국어 주석이 앞에 있는
  줄에서 문자 인덱스를 열로 내던 문제를 바로잡는다.
- Kotlin 함수 범위 계산에서 주석과 `when` 제어문을 제외해 compiler symbol 귀속이 오염되지 않게 한다.

### Changed

- ASM 9.10.1과 kotlin-metadata-jvm 2.4.20으로 갱신하고 의존성 검증 metadata를 다시 생성했다.
- `bridges` 문서의 `project`는 `.` 대신 입력 root의 canonical POSIX 절대경로다. 모든 위치는 여전히 project-relative다.

## [0.9.0] - 2026-09-14

### Added

- `snapshot --index-cache`와 Gradle snapshot의 선택적 로컬 파싱 캐시를 추가한다.
  현재 class 내용·분석기 구현·dependency JAR 내용을 확인하고, 변경되지 않은 파싱 사실만 재사용한다.
  입력 신선도·전체 분석·보존·source 위치는 다시 계산하며 cold/warm/변경 입력의 snapshot 일치를 검증한다.
- `mcp`는 MCP 2025-11-25 stdio에서 `query_symbol`·`impact`·`freshness`를 제공한다.
  시작 시 고정한 snapshot과 CLI의 분석·보고 경로를 공유하고, 크기 제한·취소·EOF·실제 Claude 연결을 검증한다.
- 정확히 선택되는 Java private/final instance helper와 Kotlin object/companion의 String/Class 반환값을 추적한다.
  실제 실행 표본 4건의 누락 경로를 복원하고 override·receiver 상태·재귀·분석 한도는 unknown으로 유지한다.
- 저장·읽기 한도를 명시하는 `--snapshot-max-mib`와 Gradle `snapshotMaxMiB`를 추가한다.
  기본 64 MiB를 유지하며 최대 128 MiB까지 허용한다. 실제 fastjson2 core/test의 88.7 MB snapshot을 검증한다.

### Fixed

- 공개 Gradle 표본의 classpath 수집을 task 실행 시점으로 옮겨 configuration lifecycle 오류를 해결한다.
- 자기 분석 smoke의 정점 수 검증을 독립 JSON 출력과 대조해 제품 성장에도 정점과 간선을 구분한다.
- 저장할 수 없는 큰 JAR 때문에 cache population을 반복하지 않고, 캐시 사용 불가 통계를 class당 한 번 센다.
- 실제 class header의 FINAL을 사용하고, 런타임 입력에 기여하지 않는 helper 호출이 분석 예산을 소진하지 않도록 한다.
- MCP의 실패한 source-style selector에 실제 USR 후보를 제공하며 overload를 임의 선택하지 않는다.
  도구 내용은 16 KiB로 제한하고, 페이지·경로 예산 조정을 명시해 클라이언트 표시 한도에 대응한다.
- `impact --summary-limit`와 MCP `summaryLimit`으로 전역 요약 항목 수를 별도로 제한한다.
  원래·반환·생략 수를 기록하며 선택·영향 후보·경로·분석 한계를 바꾸지 않는다.
- MCP worker의 치명적 오류가 영구 busy 상태를 남기지 않도록 종료하며,
  graph 명령의 손상·누락 classpath 입력을 정제된 도구 오류 2로 반환한다.
- 중첩 JSON 출력의 임시 문자열 생성을 줄이며 기존 출력 바이트와 문자 식별자를 보존한다.

### Changed

- `QuerySnapshotCodec`의 기본 render도 reader와 같은 64 MiB 한도를 적용한다.
  큰 문서를 직접 만드는 API 호출은 명시적 한도 overload를 사용하며 최대 128 MiB까지 허용한다.
- AI 변경 전 조사 48회와 원본 입력 감사를 공개한다. 일반적인 AI 생산성 향상은 미입증으로 유지한다.

## [0.8.0] - 2026-09-13

### Added

- 선택적 javac/Kotlin 2.4.10 상수 참조와 javac Dagger 2.59 선택 binding collector를 compiler 증거와 함께 snapshot에 연결하고, 불완전한 참조를 계량한다.

- Gradle JVM main/test의 `kartographSnapshot` 자동 수집과 checkout 전용 external-input bindings를 추가한다.
  Kotlin/Java별 실제 소스·출력·compiler 증거를 확인하며, 누락된 compiler와 정상 `NO-SOURCE`를 구분한다.
- Android variant의 `kartographSnapshot<Variant>`는 main/unit-test compiler 증거와 SDK·manifest·XML 입력을 함께 캡처한다.
  실제 AGP 9.3.2 배포 JAR 소비, configuration cache, 같은 수정 시각의 내용 변경과 테스트 소스 삭제를 검증한다.
- compiler task의 source/class/config/classpath 지문을 snapshot에 연결하고 `verify-snapshot`과 CI helper에서
  내용 일치·stale·미검증 상태를 구분한다. Java/Kotlin producer의 실패·캐시·경로 이동과 Android 입력을 검증한다.
- `impact`: 수정 예정 심볼/파일의 직접·간접 영향 후보를 시점별 경로·간선 출처와 함께 보고한다.
  base/current snapshot, 삭제·rename 경로, interface override 계약, CI helper와 에이전트 스킬을 연결한다.
- `snapshot --include-paths --revision --scope`와 lossless `--compact` v2. 기존 v1/query 필드 호환성을 유지한다.
- 공개 OkHttp/AnkiDroid 실제 회귀, runtime 영향 경로, 읽기 전용 AI 질의 비교와 재현·채점 스크립트.
- 프로젝트 static helper의 String/Class 반환값과 불변 인자를 제한적으로 전파해 reflection 대상을 연결한다.
  Java/Kotlin 실행·overload·unknown·재귀·분석량 제한 회귀와 SearchDeadCode/R8 비교 실험을 추가한다.
- `snapshot`과 `query --graph-file`: 그래프·보존 근거·baseline 상태·계량 한계를 저장하고 원본 입력 없이 질의한다.
- `--generated-classes`와 Gradle `generatedClassRoots`: 생성 전용 컴파일 입력의 출처로 선언을 구분하며 정점·간선은 유지한다.

### Changed

- CLI `impact`의 기본 정렬을 `review`로 바꿔 직접·간접 경로를 구조적·미확인 경로보다 먼저 보여준다.
  전체 후보·경로·한계는 유지한다. 기존 순서는 `--sort usr`로 선택하며 분석 API의 기본 정렬은 바뀌지 않는다.

### Fixed

- 일시적인 입력 변경·관측 실패가 해결된 뒤 이전 rejection 기록에 묶이지 않고 compiler 증거를 다시 생성한다.
- 빌드 실패 후 비어 있는 witness 출력이 `UP-TO-DATE`로 고정되는 문제를 복구하고, 실패 기록 삭제를 빌드 종료 시점으로 옮긴다.
- included build 하위 convention 모듈의 설정·소스 변경도 snapshot 입력으로 추적한다.
- 자동 compiler 관측의 미지원 입력으로 일반 빌드를 중단하지 않고, 스냅샷 요청에서 증거 거부 사유를 보고한다.
- 하위 프로젝트의 상위 설정 파일 연결과 설정 변경 추적을 보완하고, 설정 파일·catalog·build logic의 추가도 감지한다.
- Kotlin compiler witness의 toolchain 연결이 기존 bytecode target을 덮어쓰지 않도록 보존한다.
  JDK 21 / target 17 Android 일반 빌드와 자동 수집을 비교해 검증한다.
- static field의 String/Class 초기값·재대입·reflection get/set에서 알려진 런타임 후보를 복원한다.
  필드의 불확실성을 유지하며, classfile String 상수·상속/숨김·분석 한도와 Java/Kotlin 실행 대조를 검증한다.
- 프로젝트와 dependency에 정의된 반복·중첩 Compose multipreview 어노테이션을 보존 근거로 연결한다.
- JAR의 2초 시각 정밀도 안에서 발생한 차이는 stale로 단정하지 않고 freshness unknown으로 표시한다.

## [0.7.0] - 2026-09-09

### Added

- Java/Kotlin 4개 표본에서 실제 실행과 SootUp CHA/RTA·WALA 0-1-CFA·kartograph를 대조하는 정밀도 실험과 CI 검증.
- 호출 signature로 선택하는 JDK runtime 모델 목록과 외부 호출의 모델 ID.
- 알려진 reflection method/field 접근을 연결하고 미해결 호출을 각각 계량한다.
- 실제 Dagger SPI의 qualifier별 선택 binding을 JVM 선언에 연결하고 stale 입력·누락 binding을 거부하는 독립 실험.
- DroidBench 패턴의 Java 실행·kartograph 후보·R8 보존/실행을 분리하는 6개 차등 회귀 계약과 CI 검증.

## [0.6.0] - 2026-09-08

### Added

- 인라인 전 javac/FIR 상수 참조를 같은 입력의 그래프에 연결하는 독립 비교 실험과 CI 검증을 추가한다(제품 자동 보강은 아님).
- 제한된 메서드 내 값 추적에 기반한 class 로딩·reflection 생성자, 외부 상위 타입 dispatch와 서비스 provider 입력을 그래프에 연결한다.
- 간선 출처와 외부 호출 해석 상태를 JSON에 기록하고 Java/Kotlin 실행 코퍼스 13건을 CI에서 검증한다.
- 외부 호출 사실을 앱 선언과 분리해 그래프에 보존하고 런타임 사각지대와 상수 참조 손실을 query에 계량한다.
- Java/Kotlin 런타임·상수·DI 반례를 고정한 compiler 코퍼스를 추가한다.

### Fixed

- 인코딩된 어노테이션 기본값의 class 참조를 도달성에 반영한다.
- 상수 field query가 보존된 owner와 달리 unreachable로 보이지 않도록 INLINE_CONSTANT 근거를 공유한다.

## [0.5.0] - 2026-09-08

### Added

- AGP 8.7.3 / Gradle 8.10.2 / KGP 2.0.21의 영구 소비 프로젝트와 CI 게이트.
- 빌드 의존성 SHA256 검증, Dependabot, 배포 runtime CycloneDX SBOM과 SHA256SUMS.

### Changed

- 세 source 스캐너가 실제 하위 디렉터리 가지치기를 공유한다. XML 위치 계산은 파일당 줄 목록을 한 번 읽는다.
- query는 class 인덱싱에서 수집한 runtime 관측값을 재사용한다.

### Fixed

- source 탐색과 skill 설치가 프로젝트 밖 심볼릭 링크를 따라 읽거나 쓰지 않도록 경계를 검사한다.
- 컴파일 선언이 없는 입력을 실패로 처리하고 명령에 맞지 않거나 다른 모드에서 무시되는 CLI 옵션을 거부한다.
- source 신선도를 대응 class별로 비교하고 대응 불가능한 source는 계량 한계로 알린다.
- 누락된 JDK 상위 타입을 오류에 안내하고 bridges 시각은 반복 가능한 source snapshot 시각으로 기록한다.

## [0.4.1] - 2026-09-07

### Changed

- README를 영어로 쓰고 한국어 문서를 `README.ko.md`로 분리했다. 배포본에도 두 문서를 함께 싣는다.
- Portal 승인 가이드에 따라 Gradle 기능 호환성을 게시 메타데이터에 선언한다. configuration cache
  재사용은 Android fixture 게이트로 검증한 그대로 `true`로 게시한다.

## [0.4.0] - 2026-09-07

### Added

- Gradle plugin이 AGP 8.7 이상을 지원한다. 사용하는 Variant API가 AGP 7대부터 stable이라
  컴파일 기준을 `gradle-api:8.7.3`으로 낮췄고, AGP 8.7 + Gradle 8.10 조합에서 dead·graph task와
  configuration cache 재사용을 검증했다. AGP 9.x는 기존 Android fixture 게이트로 계속 검증한다.

### Fixed

- `query`·`bridges`·`skill`·`cycles`·`rules`·`metrics`가 `--help`/`-h`를 사용 오류(`64`)로
  거부하지 않고 각 명령의 사용법을 출력한 뒤 성공(`0`)으로 끝낸다. `baseline --help`는 `dead`
  도움말 대신 baseline 전용 사용법을 출력한다.
- variant keep 입력에 포함된 빌드 중간 산출물이 아직 생성되지 않았으면 건너뛴다(AGP 8의
  `default_proguard_files`). 빌드 출력 디렉터리 아래의 누락만 건너뛰고, 소스 트리 경로의
  누락은 기존대로 실패로 둔다.

## [0.3.1] - 2026-09-07

### Fixed

- 표준 출력을 UTF-8로 고정하고 machine 문서의 비ASCII를 `\uXXXX`로 escape한다. 이전에는 `LC_ALL=C` 같은
  비UTF-8 로케일에서 비ASCII 식별자가 `?`로 뭉개져 서로 다른 선언이 같은 `usr`로 붕괴하고, 교환 문서의
  join key가 조용히 충돌했다. 이제 로케일과 무관하게 같은 바이트를 낸다.
- 출력 쓰기가 실패하면(디스크 부족, 소비자 조기 종료 등) 잘린 문서를 성공으로 보고하지 않고 도구 실패(`2`)로
  끝낸다. 예기치 못한 실패도 JVM 기본 종료 코드 `1`(strict finding과 같은 값) 대신 `2`로 수렴시킨다.
- `SourceFile` attribute에 담긴 경로를 그래프에 넣기 전에 파일 이름 성분으로 줄인다. 이전에는 후처리 도구가
  남긴 절대경로가 `dead` report·`query`·저장소에 커밋되는 baseline 지문으로 그대로 흘러갈 수 있었다.
  **호환성**: 표준 javac/kotlinc 산출물처럼 attribute가 파일 이름만 담은 경우 지문은 그대로이므로 기존
  baseline이 계속 동작한다. attribute에 경로가 담겼던 항목만 지문이 바뀌므로(그 지문에는 절대경로가 새겨져
  있었다) 해당 baseline은 다시 생성해야 한다.
- `--include-paths`(Gradle `includeSourcePaths`)가 이름만 같은 무관한 파일을 사실로 확정하지 않는다. 선언의
  package가 후보 파일 디렉터리의 suffix일 때만 확정하고, 아니면 `unresolved-source-paths`로 센다.
- 프로젝트 source 탐색의 가지치기 규칙을 세 스캐너가 공유하는 한 벌로 통일한다. 이전에는
  `SourcePathIndex`·`BridgeFactScanner`·`RuntimeLimitationScanner`가 각자 다른 제외 집합을 써서
  `.claude/`·`.omx/`의 예제 코드가 `bridges` 교환 문서로 수확되거나 staleness 집계에 섞일 수 있었다.
  정본(`docs/DECISION-truth-source.md`) 6종에 빌드 산출물·도구 캐시를 더한 집합만 제외하며, 기존에
  제외하던 디렉터리를 다시 순회하지는 않으므로 종전 출력은 그대로다.
- 그래프 교환 문서가 경로 해석을 요청하지 않아도 `missing-source-paths`를 보고한다. 개수만으로 계산되는
  한계를 opt-in 뒤에 숨기지 않는다.

### Changed

- 게시되는 Gradle plugin POM에 name·description·url·licenses·developers·scm을 채우고, release 준비 검증이
  이 메타데이터와 배포본 문서의 버전 일관성을 함께 확인한다.
- README·`docs/PR-CHECK.md`·`docs/LIMITATIONS.md`·`docs/PUBLIC-VALIDATION.md`의 버전 하드코딩과 옛 서술을
  현행화한다.

## [0.3.0] - 2026-09-06

### Added

- BINARY/RUNTIME 보존 어노테이션의 명시적 값·배열·enum·중첩 어노테이션과 parameter annotation의 class 참조를
  도달성 간선으로 복원한다. 어노테이션 인자로만 참조되는 선언(예: BINARY 보존 `@PreviewParameter(X::class)`)이
  미사용 오탐이 되지 않는다.
- 중첩 class를 바깥 container와 참조 간선으로 연결해, 사용되는 중첩 class를 둔 바깥 선언이 미사용으로 보고되지 않는다.
- 바로 앞 constant 문자열 인자를 사용하는 `Class.forName`의 대상을 class 참조로 해석한다.
- 단일 file facade와 multi-file part의 도달 불가 top-level 함수를 finding으로 보고한다. inline 함수·property
  접근자·native·컴파일 상수·launcher `main`과 class-only 모드의 private top-level은 보수적으로 제외한다.
- `dead --test-classes <root>`는 production root에서는 도달 불가하나 test 코드에서만 도달되는 finding을
  `(used only by tests)`로 표시한다. 표시는 억제·삭제 승인이 아니며 strict/baseline에서 여전히 finding으로 계산한다.
  현재 CLI만 지원하고 Gradle plugin의 test variant 연결은 후속 작업이다.
- `graph --format json`은 정점의 `usr`·`qualifiedName`·`kind`·`accessibility`·`location`과 간선을 담은 결정적
  교환 문서(`code-graph` v1)를 만든다. `--include-paths --project <dir>`는 class debug 정보의 source file 이름을
  project 안에서 유일하게 일치하는 파일의 상대경로로 해석하고, 각 위치의 출처를 `pathKind`로, 확정하지 못한 수를
  `unresolved-source-paths`·`missing-source-paths` 한계로 함께 싣는다. 절대경로는 내보내지 않으며 `dot` 출력은
  기존대로 위치를 담지 않는다.
- Gradle plugin이 Android variant마다 `kartographGraph<Variant>` task를 등록해 같은 `code-graph` v1 문서를
  `build/reports/kartograph/<variant>-graph.json`에 쓴다. `kartograph { includeSourcePaths.set(true) }`는
  CLI의 `--include-paths`와 같이 opt-in이며, 켰을 때만 선언되지 않은 project source를 읽으므로 그때만
  task output을 재사용하지 않는다.

### Changed

- source 경로 인덱스와 그래프 경로 해석을 `index` 모듈의 공개 `SourcePathIndex`로 옮겨 CLI와 Gradle plugin이
  같은 해석 규칙을 쓴다. 동작과 출력은 그대로다.
- `--since`는 debug 정보가 basename만 남긴 경우 프로젝트의 유일 source 경로로 대조해 모호한 매칭을 줄인다.
  인덱스 실패나 모호한 basename은 기존 보수적 매칭으로 폴백한다.
- `dead` limitations에서 해결된 enclosing declaration 한계를 제거하고, annotation 값 한계는 SOURCE 보존
  어노테이션·기본값으로 좁혀 유지하며, top-level property 미보고 한계를 추가한다.

## [0.2.0] - 2026-09-05

### Added

- CLI/Gradle opt-in private member findings, 공통 보고 정책, 같은 모드의 baseline/query와 member keep-rule 보존.
- Gradle hierarchy에 Android SDK boot classpath를 포함해 framework 상속 keep 규칙을 해석한다.
- 기준 Git commit의 baseline만 적용하는 `Scripts/check-pr.py`와 실제 Git/javac 기반 PR 회귀 검증.
  전체 그래프를 검사해 수정하지 않은 파일의 새 미사용도 보고하고 PR의 baseline 추가로 숨기지 않는다.
- 고정 공개 nowinandroid 빌드의 Hilt 생성 코드 회귀 verifier와 재현 절차.

### Fixed

- Dagger/Hilt 생성 annotation과 실제 enclosing 관계를 반영해 생성 Java와 중첩 class를 일반 미사용 코드로 보고하지 않는다.
- Hilt application에서 정확한 generated component sibling을 보존하고 버전 테스트는 단일 VERSION과 대조한다.

## [0.1.1] - 2026-09-05

### Fixed

- 일반적인 비보존 ProGuard/R8 directive와 한 줄 `-keepclasseswithmembers` 규칙을 처리한다.
- manifest `meta-data`의 class 참조를 보존하고 unresolved placeholder 입력은 명시적으로 실패한다.
- reachable member에서 owner class로 이어지는 보수적 참조와 `javax` 등 JDK hierarchy를 복원한다.
- Gradle baseline capture, qualified-name architecture 설명과 Android generated-class 회귀 검증을 정렬한다.
- 실제 Moshi KSP nested adapter를 코퍼스에 추가하고 CI에서 Gradle wrapper와 Node 24 action을 검증한다.

## [0.1.0] - 2026-09-04

### Added

- JVM bytecode와 Kotlin metadata에서 class, method, field 및 근거가 있는 dependency graph 생성
- manifest, Android resource, keep rule, annotation과 runtime callback을 반영한 explainable reachability
- baseline, Git 변경 범위, Gradle/GitHub Actions/SARIF/sorted JSON report
- symbol query, isthmus bridge facts, 설치형 agent skill
- package/module cycle, YAML layer rule, Martin metric 분석
- Android variant별 분석 task를 제공하는 Gradle plugin

### Safety

- 결과는 graph에서 관찰한 도달성 상태이며 코드 삭제 승인이나 런타임 안전성 보장이 아니다.
- 동적 reflection, JNI, 런타임 등록과 불완전한 classpath 등 측정 가능한 한계를 결과에 포함한다.
- 잘린 class 입력은 정제된 도구 실패로 처리하고, activity alias와 FragmentContainerView 참조를 보존한다.
- JVM descriptor로 정확히 표현할 수 없는 member type wildcard keep rule은 파일·줄 오류로 거부한다.
- `bridge-facts`의 프로젝트와 위치를 상대경로로 제한하고 사용되지 않는 빈 test-support module을 제거했다.
- 배포본에 내장된 ASM과 Kotlin/JetBrains runtime dependency의 제3자 라이선스를 함께 제공한다.

[Unreleased]: https://github.com/ictechgy/kartograph/compare/v0.18.0...HEAD
[0.18.0]: https://github.com/ictechgy/kartograph/compare/v0.17.0...v0.18.0
[0.17.0]: https://github.com/ictechgy/kartograph/compare/v0.16.0...v0.17.0
[0.16.0]: https://github.com/ictechgy/kartograph/compare/v0.15.0...v0.16.0
[0.15.0]: https://github.com/ictechgy/kartograph/compare/v0.14.0...v0.15.0
[0.14.0]: https://github.com/ictechgy/kartograph/compare/v0.13.0...v0.14.0
[0.13.0]: https://github.com/ictechgy/kartograph/compare/v0.12.0...v0.13.0
[0.12.0]: https://github.com/ictechgy/kartograph/compare/v0.11.0...v0.12.0
[0.11.0]: https://github.com/ictechgy/kartograph/compare/v0.10.2...v0.11.0
[0.10.2]: https://github.com/ictechgy/kartograph/compare/v0.10.1...v0.10.2
[0.10.1]: https://github.com/ictechgy/kartograph/compare/v0.10.0...v0.10.1
[0.10.0]: https://github.com/ictechgy/kartograph/compare/v0.9.0...v0.10.0
[0.9.0]: https://github.com/ictechgy/kartograph/compare/v0.8.0...v0.9.0
[0.8.0]: https://github.com/ictechgy/kartograph/compare/v0.7.0...v0.8.0
[0.7.0]: https://github.com/ictechgy/kartograph/compare/v0.6.0...v0.7.0
[0.6.0]: https://github.com/ictechgy/kartograph/compare/v0.5.0...v0.6.0
[0.5.0]: https://github.com/ictechgy/kartograph/compare/v0.4.1...v0.5.0
[0.4.1]: https://github.com/ictechgy/kartograph/compare/v0.4.0...v0.4.1
[0.4.0]: https://github.com/ictechgy/kartograph/compare/v0.3.1...v0.4.0
[0.3.1]: https://github.com/ictechgy/kartograph/compare/v0.3.0...v0.3.1
[0.3.0]: https://github.com/ictechgy/kartograph/compare/v0.2.0...v0.3.0
[0.2.0]: https://github.com/ictechgy/kartograph/compare/v0.1.1...v0.2.0
[0.1.1]: https://github.com/ictechgy/kartograph/compare/v0.1.0...v0.1.1
[0.1.0]: https://github.com/ictechgy/kartograph/releases/tag/v0.1.0
