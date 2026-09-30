# Spring HTTP 클라이언트 요청 오라클과 두 서비스 e2e (API 영향 계획 Phase 7b)

`routes --role client`의 Spring `RestTemplate`·`RestClient`·`WebClient`·`@HttpExchange` 사실이 실제 Spring이 보내는 요청과 같은지
실측하고, 서버 사이 호출 체인이 isthmus `trace`에서 이어지는지 확인한다. 제품 빌드와 기본 CI에는 들어가지 않는다. 규칙과 출처는
[SPRING-CLIENTS](../../docs/SPRING-CLIENTS.md)다.

## 요청 오라클

- `fixtures/spring-clients-corpus/oracle/spring-client/`의 합성 Spring Boot 클라이언트 앱(Kotlin 10파일·Java 4파일·설정 3파일, 이
  저장소를 위해 새로 쓴 코드)을 `harness/`의 독립 Gradle 빌드가 Spring Boot 3.5.16(Spring Framework 6.2.19)으로 컴파일하고, 웹 서버 없이
  컨텍스트를 띄워 호출마다 요청을 보낸다.
- host는 모두 `.example.test`다. harness가 JVM HTTP 프록시(`http.proxyHost`)를 127.0.0.1의 JDK `HttpServer`로 두면 JDK HttpClient
  (RestClient·WebClient·Boot `RestTemplateBuilder`)와 `HttpURLConnection`(`new RestTemplate()`)이 요청을 absolute-form으로 보내므로, 앱
  소스의 base URL을 바꾸지 않고 Spring이 만든 경로와 원래 host를 그대로 기록한다. 서버는 오라클 프로세스 안에서만 떠 있다.
- `run.py`가 기록에 소스 목록과 버전을 더해 `fixtures/spring-clients-corpus/oracle/spring-client-requests.json`을 쓴다(2026-09-30, JDK 21).
  재생성은 `python3 experiments/phase7b-spring-clients/run.py`(Maven Central·Gradle 플러그인 포털 필요), 검사는 cli `SpringClientOracleTest`다.
- 테스트는 같은 소스·설정을 임시 프로젝트로 복사해 `routes --role client`를 실행하고, 템플릿의 `{}`를 케이스 값으로 채운 경로와
  authority·동사를 기록과 대조한다. base가 실행 시점 값인 케이스(`env`)만 `pathAnchor: base`를 받고 기록한 base 경로 뒤에 붙여 대조한다.

### 결과

32개 호출(명령형 24, `@HttpExchange` 8):

| | 일치 | dynamic | 불일치 |
|---|---:|---:|---:|
| 변경 전(`main` 4c09d91) | 0 | 0 | 32(사실 0건 — `route-call-coverage: 10 source file(s)`만 냈다) |
| 변경 후 | 32 | 0 | 0 |

| 범주 | 케이스 | 기록 경로 | 사실 |
|---|---|---|---|
| `@Value` base(기본 프로필 yml, 대문자 host) | `/users/{id}` + `http://Users.Example.Test:8081/api` | `/api/users/42` | root `users.example.test:8081` `/api/users/{}` |
| query | `/users?active={active}` | `/api/users` | `/api/users` |
| 슬래시 없는 연결 | `status` + `/api` | `/apistatus` | `/apistatus` |
| 빌더 람다 | `uri { it.path("/users/{id}")… }` | `/api/users/7` | `/api/users/{}` |
| `//` 축약·끝 슬래시 | `/users//bulk/`, `method(HttpMethod.PATCH)` | `/api/users/bulk/` | `PATCH /api/users/bulk/` |
| 절대 URL | `http://audit.example.test/log/{day}` | `/log/mon` | root `audit.example.test` `/log/{}` |
| 점 세그먼트 유지 | `/users/./me/../self` | `/api/users/./me/../self` | 같음 |
| 다른 프로필 재정의 | `search.url`(prod가 바꿈) | `/s/query/shoes` | 기본 프로필 값 + `unresolved-base-url:` |
| WebClient 끝 슬래시 base | `orders` / `/orders/{id}` + `…/v1/` | `/v1/orders`·`/v1/orders/5` | `/v1/orders`·`/v1/orders/{}` |
| WebClient 람다·Map 변수 | `builder.path("orders/{id}/items")`, `uri(t, mapOf(…))` | `/v1/orders/5/items`·`/v1/orders/5/cancel` | `{}` 템플릿 |
| Boot `rootUri` | `getForObject`·`exchange(PUT)`·`delete`·`headForHeaders`·`postForObject` + `/billing` | `/billing/invoices/…` | 동사별 root 템플릿 |
| `rootUri` + 절대 URL | `http://ledger.example.test/entries` | `/entries` | root `ledger.example.test` |
| base 없는 RestTemplate + `@Value` 연결 | `"$legacyUrl/items/{id}"` | `/v0/items/i-9` | root `legacy.example.test` `/v0/items/{}` |
| `UriComponentsBuilder` | `fromUriString(url).path("/search")…toUri()`, `fromHttpUrl(url).pathSegment("items", id)` | `/v0/search`·`/v0/items/i-9` | `/v0/search`·`/v0/items/{}` |
| 실행 시점 base | `RestClient.create(System.getenv(…) ?: …)` | `/e/ping` | base `/ping` + baseRef `kt:…ClientConfig.envRestClient` |
| `@HttpExchange`(RestClientAdapter) | `@HttpExchange("/api")` + `items/{id}`·`/items`·`@HttpExchange(method = "PUT")`·`@DeleteExchange(url = …)` + `…/shop` | `/shop/api/items/…` | root `catalog.example.test` 템플릿 |
| Java `@Qualifier`·`.properties` base | `/stock/{sku}` + `…/inv/` | `/inv/stock/sku-1` | root `/inv/stock/{}` |
| Java 람다 | `uriBuilder -> uriBuilder.path("reservations/{sku}")…` | `/inv/reservations/sku-1` | `/inv/reservations/{}` |
| Java 상수 연결 | `BASE + "/parcels/{id}"`, `exchange(…, HttpMethod.GET, …)` | `/ship/parcels/p-1` | root `shipping.example.test` |
| Java `@HttpExchange`(WebClientAdapter) | `daily/{day}`·`/monthly/{month}` + `…/r/` | `/r/daily/…`·`/r/monthly/…` | root `reports.example.test` |

변경 전 행은 `main` 4c09d91의 CLI를 같은 소스에 실행해 쟀다(Spring 인식기가 없고 Spring 클라이언트를 import한 파일을 모델링하지 않은 클라이언트로만 셌다).

## 두 서비스 e2e

- `e2e/`의 독립 Gradle 빌드가 서비스 B(`users-service`, `GET /api/users/{id}` → `UserDirectory.find`)와 서비스 A(`orders-service`,
  `GET /orders/{id}` → `OrderService.describe` → `UserGateway.fetchUser` → RestClient `get().uri("/users/{id}")`, base는
  `@Value("${users.base-url}")` = `http://users.internal:8081/api`)를 컴파일한다.
- `run_trace.py --isthmus <isthmus dist/cli/main.js> [--record]`가 서비스마다 `snapshot`·`routes --role server`를, A는
  `routes --role client`도 실행하고, B는 route-decl usr 전체로 `reach`, A는 route-call usr 전체로 `impact --format language-traversal`을
  낸 뒤 workspace trace context(link `orders->users`, `match.hosts: ["users.internal:8081"]`)로 `isthmus trace`를 실행한다.
- 결과(isthmus origin/main `f9dcd1d`, 2026-09-30): B의 `GET /api/users/{}` 선택 → 핸들러 `UsersController.user` → A의 호출
  `UserGateway.fetchUser`(root `users.internal:8081` `/api/users/{}`, 품질 `exact`) → A의 역방향 도달 `OrderService.describe`(depth 1) →
  `OrdersController.order`(depth 2, A `GET /orders/{}`의 핸들러 usr와 같음). B 핸들러의 정방향 도달에 `UserDirectory.find`가 있다.
  gap은 `http-member-unlinked`(A의 서버 문서 — A가 server인 link가 없어 A의 route를 trace가 잇지 않음)와 `persistence-unscanned`(DB
  문서 없음)뿐이다. 기록은 `e2e/recorded/`(문서의 `project`는 합성 경로 `/e2e/<service>`)이고, 그 디렉터리에서
  `isthmus trace recorded/trace.context.json`으로 다시 실행할 수 있다.
- 이 e2e에서 한 줄 식 몸체 함수(`fun fetchUser(…) = client.get()…`)의 route-call이 `missing-route-usrs:`로 빠지는 기존 결함을 찾아
  고쳤다 — 주 생성자가 있는 Kotlin 클래스 머리가 Java 선언 정규식에 맞아 더 좁은 범위로 이겼고, 식 몸체 함수는 뒤 선언의 `{`까지 범위를
  잡았다.
