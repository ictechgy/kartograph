# Spring 서버 라우트 (`routes --role server`)

_기록: 2026-09-28, 스코프·빈 값 변형 2026-09-29 · 상태: 미출시(API 변경 영향 계획 Phase 4a) · isthmus http 계약은 아직 '개발 중'이다_

Spring MVC·WebFlux 어노테이션 controller를 isthmus http 도메인의 `route-decl` 사실로 낸다. 계약은 isthmus
`docs/GRAPH-EXCHANGE.md`의 "개발 중: HTTP 경계" 절이고, 이 문서는 kartograph 생산자의 규칙·근거·검증 결과다.

```bash
# 소스만: 위치와 값을 모두 소스에서 읽는다. usr는 없다(missing-route-usrs:).
kartograph routes --role server --project . --service api
# snapshot과 함께: class root의 바이트코드 어노테이션이 값, snapshot이 usr, 소스가 위치를 준다.
kartograph snapshot --classes build/classes/java/main --project . --include-paths > graph.json
kartograph routes --role server --project . --service api --graph-file graph.json > server.http.json
# 핸들러에서 정방향으로 순회한다(isthmus trace의 forward 분석).
kartograph reach --roots-from server.http.json --graph-file graph.json --project . > server-forward.json
```

문서는 `"target": "http"`, `"roles": ["server"]`, `"dispatch": "specificity"`, `sourceSets`, 선택 `service`를 싣는다.
사실은 (HTTP 동사 또는 `ANY`, 정규 경로 템플릿)마다 하나이고 `pathAnchor`, `trailingSlash`, `narrowed`,
`paramConstraints`, `configDefault`, `catchAllPrefix`, `testSource`를 계약 이름으로 싣는다.

## 값 원천: 바이트코드 우선, 소스는 위치

| 원천 | 쓰는 때 | 주는 것 |
|---|---|---|
| snapshot class root(바이트코드) | `--graph-file`이 stale이 아니고 provenance의 `classes` 입력이 지금 있을 때(`external/` 슬롯은 `--input-bindings`로 연결한 것) | 어노테이션 값(상수가 접힌 값), 타입 계층, 메서드 descriptor → usr |
| 소스(Kotlin·Java) | 항상 | 어노테이션 토큰 위치, 바이트코드에 없는 타입(테스트 소스 등)의 값 |

어노테이션 값은 컴파일 시간 상수라 컴파일러가 접어 넣는다. 스파이크 S5에서 javap로 확인했다(아래). 그래서 파일 밖
상수, 다른 모듈 상수, Kotlin 문자열 템플릿 상수도 바이트코드에서는 이미 리터럴이다. 소스만 있을 때는 프로젝트 안
상수 색인(`import static`, Java 인터페이스 상수, Kotlin 최상위·`object`·`companion` `const val`, `Type.NAME` 한정
참조, 문자열 연결·템플릿)을 따라가고, 풀지 못하면 dynamic 사실과 `route-coverage:`로 남긴다.

snapshot 형식은 바꾸지 않는다(어노테이션 값 보존은 계획상 change-based-impact의 snapshot 변경 뒤로 미뤘다). snapshot은
이미 class root 경로와 지문을 provenance에 싣고 있어, 신선도를 확인한 class root를 다시 읽는 것으로 충분하다.

위치는 계약대로 소스의 어노테이션 토큰이다. 메서드 매핑을 찾은 요소(상속이면 인터페이스·상위 메서드)의 토큰이 1순위,
없으면 핸들러 메서드 이름 토큰이다. 둘 다 없으면(생성 소스를 스캔하지 않은 경우 등) 사실을 내지 않고
`route-coverage: N handler method(s) have no source location …`로 센다. 바이트코드 메서드와 소스 선언은 이름·매개변수 수로
맞추고, 오버로드는 LineNumberTable 첫 줄 앞의 가장 가까운 선언으로 가른다.

## 규칙과 근거

근거는 spring-webmvc·spring-webflux·spring-web·spring-core 7.0.8(Boot 4.1.0), 6.2.10 소스, spring-boot 4.1.0·3.5.6
소스다(Maven Central sources JAR).

| 규칙 | 결과 | 근거 |
|---|---|---|
| 핸들러 bean | 타입 계층(상위 class·인터페이스) 어디든 `@Controller`(메타 포함, `@RestController`·프로젝트 선언 stereotype)가 있는 구체 class. Boot 2(Spring 5)는 타입 수준 `@RequestMapping`만 있어도 핸들러 | `RequestMappingHandlerMapping.isHandler` |
| 매핑 탐색 | 요소 → 인터페이스 → 상위 class 순서에서 매핑이 있는 첫 요소의 것만 쓴다. 한 요소에 매핑이 둘 이상이면 첫 것 + `route-coverage:` | `MergedAnnotations` TYPE_HIERARCHY + `firstRunOf` |
| 후보 메서드 | 핸들러 class와 상위 class의 비합성 메서드, 재정의되지 않은 인터페이스 default 메서드. usr는 선언 class 메서드다(상속 메서드는 상위 class id) | `MethodIntrospector.selectMethods` |
| 합성·메타 어노테이션 | `@GetMapping` 계열, `@GetExchange` 계열, 프로젝트가 선언한(Java `@interface`·Kotlin `annotation class`) 메타 어노테이션. 명시 `@AliasFor`만 따르고(거울 속성 포함), 합성 속성 값은 기본값이어도 메타 값을 덮는다. 관례 기반 덮어쓰기(Spring 6에서 폐기 예정, 7에서 제거)는 모델링하지 않는다 | `TypeMappedAnnotation.getValue`, `AnnotationTypeMapping` |
| `@HttpExchange` 서버 매핑 | `value`/`url`이 경로, `method` 문자열이 동사(없으면 `ANY`), `contentType`·`accept`·`headers`는 narrowed | `createRequestMappingInfo(HttpExchange)` |
| 클래스×메서드 | 경로의 곱집합. 각 경로는 앞 `/` 보충 뒤 `combine`(겹치는 `/` 하나로). 클래스 패턴에 `*`·`?`가 있으면 결과가 경로 매칭에 달려 dynamic | `PathPatternParser.initFullPathPattern`, `PathPattern.combine`·`concat` |
| 빈 매핑 | 클래스·메서드 모두 경로가 없으면 `""`과 `/` 두 패턴(접두사가 있으면 `/ctx`와 `/ctx/`) | `getMappingForMethod`의 `paths("", "/")` |
| 동사 | 클래스·메서드 동사의 합집합, 비면 `ANY`. HEAD·OPTIONS 자동 처리는 decl로 내지 않는다(isthmus가 `head-as-get`·`options-any`로 잇는다). 알 수 없는 동사는 사실 대신 `route-coverage:` | `RequestMethodsRequestCondition.combine` |
| narrowed | 클래스나 메서드에 `params`·`headers`·`consumes`·`produces`·`version`이 하나라도 있으면 | `RequestMappingInfo` 조건 |
| 경로 변수 | 세그먼트 전체 `{x}`·`*` → `{}`, 부분 세그먼트 `p{x}s`·`p*s` → `p{}s`, 세그먼트에 변수·와일드카드 둘 이상·`?` → dynamic | `CaptureVariablePathElement`, `WildcardPathElement`, `RegexPathElement` |
| 빈 값 변형 | Spring이 빈 값을 받는 자리(마지막 요소 `*`, 부분 세그먼트의 변수·`*`)를 빈 값으로 채운 decl을 같은 method·symbol·위치로 함께 낸다(`/files/*` → `/files/{}`·`/files/`, `/files/{name}.json` → `/files/{}.json`·`/files/.json`). 정규식이 빈 값을 받지 않는 변수(`{n:[a-z]+}`)와 세그먼트 전체 변수·가운데 `*`는 펼치지 않는다. 원본 포함 16개를 넘으면 dynamic + `route-template-expansion-capped:` | `WildcardPathElement`(마지막 요소는 0글자), `RegexPathElement`(기본 `(.*)`), isthmus `spring/*` 벡터 |
| 정규식 제약 | `{x:re}` → `paramConstraints`. 언어가 계약의 가장 넓은 정의에 포함될 때만 닫힌 종류(`\d+`·`[0-9]+`·`-?\d+` 등 → `int`, 8-4-4-4-12 hex 클래스 → `uuid`, slug 문자만 쓴 `[…]+`·`\w+` → `slug`), 나머지는 `regex`와 원문 `pattern` | 계약의 `paramConstraints` 절 |
| catch-all | 끝 `/**`·`/{*x}` → `…/{**}`와 접두사 decl(0세그먼트 매칭). usr가 있으면 접두사 decl에 `catchAllPrefix`, 없으면(계약상 표식에 usr 필수) 같은 키의 명시 decl이 없을 때만 일반 decl. 끝이 아닌 `**`·`{*x}`는 dynamic | `WildcardTheRestPathElement`, `CaptureTheRestPathElement` |
| 끝 슬래시 | Boot 3 이상(Spring 6+) `strict`, Boot 2(Spring 5) `optional`, 버전 미상은 생략 + `route-framework-version-unknown:`. 소스에 `setUseTrailingSlashMatch(true)`·`UrlHandlerFilter`가 있으면 생략, 그것 없이 `setMatchOptionalTrailingSeparator(true)`만 있으면 `optional` | `PathPatternParser` 기본값(6.0에서 `matchOptionalTrailingSeparator=false`) |
| 대소문자 | `caseInsensitive`는 내지 않는다(증명하지 않음) | `PathPatternParser.caseSensitive=true` |

### 접두사와 설정

- 서블릿 스택: `server.servlet.context-path`를 붙인다. Boot `cleanContextPath`처럼 끝 `/`를 떼고 `/`는 빈 접두사다.
  `/`로 시작하지 않으면 Boot가 거부하는 값이라 `base` 앵커 + `unresolved-route-prefix:`다. 기본값(`/`)이 아닌
  `spring.mvc.servlet.path`는 계약 예시대로 확정하지 못한 접두사(`base`)다.
- WebFlux: `spring.webflux.base-path`를 붙인다. `cleanBasePath`처럼 앞 `/`를 보충하고 끝 `/`를 뗀다. context-path는
  쓰지 않는다.
- 스택은 `spring.main.web-application-type`이 있으면 그것이다(`none`이면 웹 서버가 없으므로 사실을 내지 않고
  `route-coverage:`로 센다). 없으면 빌드 파일의 main 의존성 표지로 정한다
  (`spring-boot-starter-web`·`-webmvc`·`spring-webmvc` / `-webflux`). 주석과 테스트 구성(`testImplementation`, Maven
  `<scope>test</scope>`)은 뺀다. 앱 모듈 빌드 파일이 한쪽만 쓰면 그것, 둘 다 쓰면 Boot 기본인 서블릿이다. 모듈 빌드 파일로
  정하지 못하면 프로젝트 전체에서 한쪽 표지만 보일 때만 정한다 — 버전 카탈로그는 선언만 모으므로 양쪽이 다 보이면 모른다.
  스택을 모르는데 접두사 설정이 있으면 `base`다.
- `WebMvcConfigurer`·`WebFluxConfigurer`의 `addPathPrefix`·`setPathPrefixes`가 소스에 있으면 모든 사실을 `base`로 둔다.
- 설정 파일: 모듈의 `src/main/resources/`, `src/main/resources/config/`, 모듈 루트, 모듈 `config/`의
  `application*.properties|yml|yaml`(뒤가 이기고 같은 위치에서는 properties가 이긴다). 키는 relaxed binding처럼 비교한다.
- 프로필: 기본 프로필만 템플릿에 쓴다. 기본 프로필은 `spring.profiles.active`(없으면 `spring.profiles.default`, 없으면
  `default`)와 `spring.profiles.include`다. 다른 프로필(`application-<p>.*`, `spring.config.activate.on-profile`, 옛
  `spring.profiles`)이 접두사를 바꾸면 기본 프로필 값을 쓰고 `unresolved-route-prefix: … differs in other profiles`로
  알린다. 환경 변수·명령행·config server 재정의는 모델링하지 않는다.
- `spring.config.import`가 있는 모듈은 가져온 설정을 읽지 않고, Boot에서는 가져온 문서가 가져온 쪽 값을 덮으므로 그 모듈의
  모든 키를 미상으로 본다. 접두사는 `base`, 플레이스홀더 경로는 dynamic이고 `web-application-type`도 쓰지 않는다.
- 플레이스홀더(`${key}`·`${key:default}`, 중첩 포함): 기본 프로필 값이 있으면 그 값(다른 프로필이 재정의하면 매핑이면
  `route-coverage:`, 접두사면 `unresolved-route-prefix:`), 저장소 어디에도 없으면 기본값 + `configDefault`, 다른 프로필에만
  있거나 기본값이 없으면 dynamic + `route-coverage:`. SpEL `#{…}`은 풀지 않는다.
- 다중 모듈: 앱 모듈은 `@SpringBootApplication`(또는 `@SpringBootConfiguration` + `@EnableAutoConfiguration`) 타입이 있거나,
  `org.springframework.boot`에서 가져온(또는 패키지까지 적은) `SpringApplication.run`·`SpringApplicationBuilder`·`runApplication`을
  부르는 모듈이다. 같은 이름의 프로젝트 도우미와 테스트 지원용 `@SpringBootConfiguration`만 있는 모듈은 세지 않는다. 소스 모듈이 앱 모듈이면 그 모듈 설정만 쓴다(설정이 없는 앱 모듈은 접두사가 없다). 라이브러리 모듈은
  자기 `application.yml`을 쓰지 않고(앱의 같은 이름 파일에 가려진다) 모든 앱 모듈을 후보로 보며, 모든 후보가 같은 접두사·같은
  플레이스홀더 값을 줄 때만 쓴다. 다르면 `base`와 `unresolved-route-prefix:`다. 앱 모듈을 찾지 못하면 자기 설정, 없으면 설정이
  있는 모든 모듈이 후보다.

### 프레임워크 제공 경로와 스코프

합성 decl은 내지 않고 제공자마다 `framework-provided-routes:` 한 줄을 낸다. 받을 수 있는 요청(method, 경로)의 상한을 증명한
제공자에는 isthmus http `limitationScopes`(계약 GRAPH-EXCHANGE "http limitation 스코프")를 붙인다. 스코프 밖의 호출은 그 한계가
가리지 않으므로 isthmus가 `route-call-without-decl`·`route-method-mismatch` error를 판정할 수 있다. 스코프 원소는 라우트와 같은
접두사 규칙(context-path·base-path)을 적용한 요청 경로다. 앱 모듈이 여럿이면 앱마다 구해 합치고, 한 앱이라도 증명하지 못하면 그
제공자는 스코프 없이 문서 전체에 적용된다.

| 제공자 | 스코프 | 근거 |
|---|---|---|
| 오류 컨트롤러(서블릿) | `templates: [<접두사>/error]`, 모든 method. `server.error.path`·`error.path`를 같은 플레이스홀더 규칙으로 풀고 빈 값 변형도 넣는다. 접두사를 모르면 `templateSuffixes`. 다른 프로필이 바꾸거나 풀지 못하면 생략 | `BasicErrorController`의 `@RequestMapping("${server.error.path:${error.path:/error}}")`, method 제한 없음 |
| welcome page | 서블릿 `templates: [<접두사>]`(모든 method), WebFlux는 `GET`·`HEAD`. 접두사를 모르면 생략 | `WelcomePageNotAcceptableHandlerMapping`은 루트를 method 제한 없는 Controller로 받는다(406). WebFlux `WelcomePageRouterFunctionFactory`는 `GET /` |
| 정적 리소스·webjars | `templatePrefixes`, `methods: [GET, HEAD]`. `static-path-pattern`·`webjars-path-pattern`(기본 `/**`·`/webjars/**`)의 리터럴 접두사로 좁히고 기본 `/webjars`는 항상 넣는다. 값을 확정하지 못하거나 프로젝트가 `addResourceHandler`로 직접 등록하면 앱 접두사 전체 | `ResourceHttpRequestHandler`·`ResourceWebHandler`는 GET·HEAD만 받는다. `WebMvcAutoConfiguration.addResourceHandlers` |
| actuator | `templatePrefixes: [<접두사>+base-path, management.server.base-path+base-path, <접두사>/cloudfoundryapplication]`. base-path가 비거나(루트 매핑) health group `additional-path` 키가 있으면 생략 | `WebEndpointProperties.basePath = "/actuator"`와 `cleanBasePath` |
| springdoc | `templatePrefixes: [<접두사>]`, `methods: [GET, HEAD]` | springdoc-openapi 3.1.0의 엔드포인트가 모두 `@GetMapping`이거나 리소스 핸들러다 |
| H2 console | `templatePrefixes: [<접두사>+spring.h2.console.path]`(기본 `/h2-console`), 모든 method | `H2ConsoleAutoConfiguration`의 서블릿 매핑 `path/*`와 `setPath` 검증 |
| Spring Security, Spring Data REST, GraphQL | 스코프 없음(문서 전체) | Security 엔드포인트는 코드 DSL(`loginPage`·`oauth2Login` 등), `Customizer<HttpSecurity>` bean, spring.factories 기본 구성으로 바뀐다. Data REST의 기본 base-path는 루트이고 GraphQL은 소스로 확인하지 않았다 |

정적 리소스 위치(의존성 JAR의 `META-INF/resources`, 빌드 생성물, 리소스 체인 버전 경로)는 저장소 소스로 열거할 수 없으므로 파일
경로로 좁히지 않는다. 그래서 GET·HEAD 호출은 정적 리소스 스코프가 가리고, 그 밖의 method 호출만 error를 판정할 수 있다.
근거는 Spring Boot 4.1.0·4.1.1, Spring Framework 6.2.10·7.0.8, springdoc-openapi 3.1.0 공식 소스(Maven Central sources JAR)와,
공개 앱·합성 코퍼스를 실제로 띄워 받은 `/actuator/mappings`의 프레임워크 매핑이다. `SpringRouteCorpusTest`가 기록한 프레임워크
매핑이 모두 스코프 안에 드는지 대조한다.

다음은 사실로 내지 않고 스코프 없는 `route-coverage:`로 센다: 함수형 라우터(`RouterFunction`·`router {}`·`coRouter {}`),
view·redirect·status controller 등록, `ServletRegistrationBean`·`@WebServlet`, JAX-RS, AntPathMatcher(Boot 2.6 이전 MVC 기본이거나
설정한 경우). 함수형 라우터는 등록 경로를 추출하지 않으므로 아는 접두사 아래라는 것도 증명하지 못한다. 다른 프로필만 정한
플레이스홀더 경로도 dynamic 사실이라 isthmus가 `unjoined-dynamic-routes`로 따로 세고, 계약에 dynamic decl의 스코프 표현이 아직
없어(미결 항목) 스코프를 붙이지 않는다.

## 알려진 차이

실제 매칭과 템플릿이 조금 다른 경우다. 모두 드문 모양이며 isthmus 벡터로 정할 후보다.

- 빈 값 변형 decl은 원본과 묶는 표식이 계약에 아직 없다(미결 항목). 그래서 변형 decl도 미호출 진단(`route-decl-without-call`)의
  대상이 된다.
- 라이브러리가 선언한 stereotype·합성 매핑 어노테이션과 라이브러리 상위 타입의 매핑은 보이지 않는다. controller의 상위
  타입이 모델 밖이면 `route-coverage: … inherit from types outside …`로, `@Controller`가 보이지 않는데 매핑을 선언한 구체
  class(라이브러리 stereotype, 모델 밖 상위 타입, 다른 등록 방식 가능성)는 `route-coverage: … declare mappings without a
  visible @Controller …`로 센다. `java.`·`javax.`·`jakarta.`·`kotlin.`·`org.springframework.` 상위 타입은 세지 않는다.
- `@Controller` bean이 component scan 범위 안이라고 가정한다.

## 스파이크 결과

- **S1(오라클 의존성 로컬 확보): 가능.** 이 환경에서 Maven Central·Gradle Plugin Portal에서 Boot 4.1.0/4.1.1 의존성을
  받아 spring-petclinic(Gradle), spring-petclinic-kotlin(Gradle), spring-petclinic-rest(Maven, openapi-generator 포함)를
  빌드했다. GitHub Actions 경로는 필요 없었다.
- **S5(Kotlin `const val` 어노테이션 값): 바이트코드에 리터럴로 접힌다.** 최상위 `const val`, `object`·`companion object`
  상수, `"$A/users"` 템플릿 상수, `A + "/x"` 연결, Kotlin에서 참조한 Java `static final String`이 모두
  `RuntimeVisibleAnnotations`에 완성된 문자열로 들어 있음을 Kotlin 2.4.20·javap(JDK 21)로 확인했다. Kotlin
  `annotation class` 속성의 `@get:AliasFor(annotation = X::class)`도 속성 메서드의 `RuntimeVisibleAnnotations`에 남는다.
  이 결과로 값 원천을 바이트코드 우선으로 정했다.
- S3(`repo.save` 호출 대상 id)는 Phase 4b 범위다.

## 오라클 검증 (2026-09-28)

앱을 `--server.address=127.0.0.1`로 띄워 `/actuator/mappings`를 받고 끈 뒤, 프로젝트 핸들러 메서드(프레임워크 패키지 제외)의
(동사, 정규화한 패턴, 핸들러)를 사실과 비교했다. 비교 스크립트는 `experiments/phase4-spring-routes/compare_mappings.py`다.
패턴 정규화는 추출기와 독립인 파이썬 구현이다. 핸들러 신원은 바이트코드 모드에서 `usr`(오라클 `handlerMethod`의
className·name·descriptor로 만든 JVM id), 소스 모드에서 qualifiedName이다.

| 앱(공개 저장소 revision) | 스택 | 바이트코드 모드 정밀도 / 재현율 | 소스 모드 정밀도 / 재현율 |
|---|---|---|---|
| spring-projects/spring-petclinic `818c413` (Apache-2.0) | Java MVC, Boot 4.1.0 | 17/17 / 17/17 | 17/17 / 17/17 |
| spring-petclinic/spring-petclinic-kotlin `da08609` (Apache-2.0) | Kotlin MVC, Boot 4.1.1 | 18/18 / 18/18 | 18/18 / 18/18 |
| spring-petclinic/spring-petclinic-rest `4cd8e1b` (Apache-2.0) | Java MVC, context-path `/petclinic/`, OpenAPI 생성 인터페이스 매핑 | 37/37 / 37/37 | 37/37 / 37/37¹ |
| 합성 edge-mvc (`fixtures/spring-routes-corpus/edge-mvc`) | Java+Kotlin MVC, Boot 4.1.0 | 60/60 / 60/64² | 60/60 / 60/64² |
| 합성 edge-webflux (`fixtures/spring-routes-corpus/edge-webflux`) | Kotlin WebFlux, base-path | 16/16 / 16/16 | 16/16 / 16/16 |

¹ Maven 생성 소스(`target/generated-sources`)는 가지치기 디렉터리가 아니어서 소스 모드도 인터페이스 매핑을 읽었다. Gradle
`build/` 아래 생성 소스는 읽지 않으므로 그때는 바이트코드 모드가 필요하다(소스 모드는 `route-coverage:`로 알린다).
² 빈자리 4건은 계약상 dynamic으로 남긴 것이다: 다른 프로필에만 정의한 플레이스홀더(기본값을 쓰지 않음) 2건, 한
세그먼트의 변수 두 개(`/v{major}.{minor}`) 2건. 둘 다 dynamic 사실과 `route-coverage:`로 드러난다.

정밀도는 모든 표본에서 100%다. 2026-09-29부터 edge-mvc는 빈 값 변형 decl 2건(`/api/j/doc/.json`·`/api/java/doc/.json`)을 더 낸다.
기록은 패턴 단위라 이 템플릿이 따로 없지만 같은 핸들러의 `/doc/{name}.json`이 받는 경로다(`SpringRouteCorpusTest`가 이 둘만 허용한다). 오라클 패턴 중 `/owners/*/pets/…`처럼 끝이 아닌 세그먼트 전체 `*`는 `{}`로 맞췄다
(`WildcardPathElement`는 가운데 `*`에 한 글자 이상을 요구한다). WebFlux 공개 표본(spring-petclinic-reactive)은 Cassandra가
필요해 띄우지 못했다. 소스 모드 출력만 수동으로 확인했다(Boot 2.x, `optional` 끝 슬래시, 32건).

**error 판정 가능 비율 (2026-09-29, isthmus `78d3dee`)**: 합성 클라이언트 호출로 측정했다. 앱마다 서버 문서(소스 모드)의 정적
root 선언 키 하나당 맞는 호출 하나와, 선언이 없는 경로(`…/zz-missing`)·선언 경로의 다른 method 호출을 만들고, 실제로 서비스되는
프레임워크 경로(`/actuator/health`, `POST /error`, `/webjars/…`, rest는 `/v3/api-docs`·`/swagger-ui/…`·`/h2-console/…`) 호출도
넣었다. 음성 표본은 오라클(`/actuator/mappings`)의 프로젝트 핸들러가 받지 않는 호출이고, 비율은 그중 isthmus `check`가 error로
판정한 비율이다. 정밀도는 error가 난 호출을 오라클의 프로젝트·프레임워크 핸들러·서블릿 매핑 전체와 대조해 계산했다.

| 앱 | 전(한계 문서 전체) | 후(스코프) | 그중 GET·HEAD 외 | 정밀도 |
|---|---|---|---|---|
| spring-petclinic | 0/28 (0%) | 16/28 (57.1%) | 16/17 | 16/16 |
| spring-petclinic-kotlin | 0/30 (0%) | 17/30 (56.7%) | 17/18 | 17/17 |
| spring-petclinic-rest | 0/49 (0%) | 0/49 (0%) | 0/40 | error 없음 |

남은 빈자리의 원인은 두 가지다. GET·HEAD 호출은 정적 리소스 스코프(`/` 아래 GET·HEAD)가 가린다(위치를 열거할 수 없음). 루트
경로의 다른 method 호출은 welcome page 스코프가 가린다(petclinic·kotlin의 1건). spring-petclinic-rest는 Spring Security가 스코프
없는 한계로 남아 문서 전체가 가려진다 — 참고로 Security 한 줄만 빼고 같은 측정을 하면 40/49(81.6%), 정밀도 40/40이다. 거짓
error는 모든 표본에서 0건이다.

## isthmus 호환성

적합성 벡터는 isthmus `78d3dee`의 `http-template`(Spring PathPattern `spring/*` 15건 포함)·`url-compose`·`http-limitation-scope`를
`fixtures/isthmus-conformance/`에 벤더링했고, `RouteConformanceTest`가 `producer`·`producer:kartograph` 케이스를 모두 돌린다. Spring
케이스는 합성 Boot 3.5 프로젝트를 실제 서버 생산자로 스캔해 템플릿·catch-all 접두사·끝 슬래시를 비교하고, 스코프 케이스는
`RouteLimitationScopes`(검증·겹침 판정)로 실행한다. 측정에는 isthmus `78d3dee`를 저장소 밖에서 빌드한 `dist/cli/main.js`를 썼다.

처음 호환성 확인은 isthmus `main` `9de927a`를 저장소 밖에 풀어 `node src/cli/main.ts`로 했다. `check`가 서버 문서(바이트코드·소스 모드,
catch-all 접두사·`paramConstraints`·dynamic·`configDefault` 포함)와 클라이언트 문서를 받아 조인한다(정수 제약 위반 호출은
후보에서 빠지고, 접두사 decl은 `catch-all` 품질로 맞는다). `trace`가 route 선택 → 핸들러 usr → `kartograph reach` 순회를
같은 id로 잇는다.

## 상속 Spring Data CRUD 호출과 `reach`

`repo.save(x)`·`repo.findById(id)`처럼 `CrudRepository`·`JpaRepository`에서 상속한 메서드는 프로젝트 정점이 없어 `reach`·`impact`가
`unresolvedCalls`로 세고, isthmus trace가 `reach-possibly-incomplete` gap을 낸다. 그러나 `schema --graph-file`은 이 호출 지점을
호출자 메서드의 relation-use 사실로 이미 귀속한다. `reach`·`impact --format language-traversal`에 그 persistence 문서를
`--persistence-facts`로 주면(문서의 `project`가 `--project` realpath와 같아야 하고, 아니면 종료 코드 2), 다음이 모두 참인 호출만
미해결에서 빼고 `persistence-modeled-calls: N`으로 알린다. 같은 snapshot으로 만든 문서를 주는 것은 사용자 몫이다(문서에 snapshot
신원이 없다).

1. 외부 인터페이스·가상 호출이고 dispatch 해석이 `UNRESOLVED`다(라이브러리 모델·reflection·invokedynamic은 빼지 않는다).
2. 호출 owner가 스냅샷의 프로젝트 인터페이스이고 프로젝트 상위 인터페이스를 따라 Spring Data 기반 인터페이스(`Repository`·
   `CrudRepository`·`JpaRepository` 등)에 닿는다.
3. 호출이 프로젝트 코드로 갈 수 없다: owner의 프로젝트 상위 인터페이스가 같은 이름의 메서드를 선언하지 않고(fragment 재정의),
   스냅샷에 Spring Data 기반 타입·`SimpleJpaRepository`를 구현하거나 상속한 프로젝트 타입(class·Kotlin object·enum)이 없다(사용자
   base class·직접 구현).
4. persistence 문서가 owner 저장소 선언 파일에 둔 relation(상속 CRUD 표면의 도메인 테이블)과 같은 relation의 사실을
   `symbol.usr`가 호출자이고 `location.line`이 그 호출 줄(줄이 없으면 호출자 선언 줄 — 스캐너와 같은 규칙)인 곳에 둔다. 명명
   전략이 갈려 dynamic으로 낸 사실은 같은 원문으로 맞춘다.

같은 줄에서 같은 테이블을 쓰는 다른 문장의 사실과는 구분하지 않지만, 그때도 호출자 → 테이블 relation-use는 사실로 있다. 저장소
AOP advice처럼 프레임워크가 끼워 넣는 프로젝트 코드는 선언된 저장소 메서드 호출과 마찬가지로 모델링하지 않는다. spring-petclinic에서 핸들러 17개의 정방향 문서가 미해결 5건(모두 `owners.save`)에서 0건이 됐고, isthmus trace의
`reach-possibly-incomplete` gap 2건이 사라졌으며 relation-use 수(4)는 같았다.
