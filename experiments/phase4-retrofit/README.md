# Retrofit 요청 오라클 (API 영향 계획 Phase 4 종료 조건)

종료 조건 "Retrofit 템플릿이 MockWebServer 기록 경로와 일치한다"를 실측한다. 제품 빌드와 기본 CI에는 들어가지 않는다.

## 방법

- `fixtures/retrofit-corpus/oracle/retrofit-client/`의 합성 서비스(Kotlin 5파일·Java 2파일, 이 저장소를 위해 새로 쓴 코드)를
  `harness/`의 독립 Gradle 빌드가 Retrofit 2.12.0(OkHttp 3.14.9)으로 컴파일한다. 오라클은 메서드마다 요청을 보내고 OkHttp
  MockWebServer 3.14.9가 받은 동사·인코딩된 경로·query를 기록한다. 다른 host로 가는 전체 URL·network-path 어노테이션은
  인터셉터가 host·port만 MockWebServer로 바꾸고, 바꾸기 전 host를 `authority`로 기록한다. 경로는 Retrofit이 만든 그대로다.
- `run.py`가 기록에 소스 목록과 버전을 더해 `fixtures/retrofit-corpus/oracle/retrofit-requests.json`을 쓴다(2026-09-29, JDK 21).
- cli `RetrofitOracleTest`가 같은 소스를 임시 프로젝트로 복사해 `kartograph routes --role client`를 실행하고, isthmus 소비자의
  결합대로 비교한다 — `pathAnchor: base`면 base URL 경로 뒤에, `root`면 host 루트에 템플릿을 붙이고 `{}`에 Retrofit 규칙으로
  인코딩한 `@Path` 값을 넣는다. `{}`는 한 세그먼트라는 주장이므로 넣은 값이 `/`를 담으면 불일치다. dynamic 사실은 이유를
  적은 케이스만 받고, 증명한 `channelPrefix`가 기록 경로의 접두사인지 본다. 동사와 `authority`도 대조한다.
- `baseRef`는 isthmus 계약이 이 버전 출력에 싣지 않는 필드라(GRAPH-EXCHANGE "route 사실 필드") 비교하지 않는다. base URL은
  케이스마다 `/api/v1/`(경로 세그먼트 있음) 또는 `/`다.

## 결과

44개 케이스(서비스 메서드 39개, 같은 메서드를 다른 base·값·상속 프록시로 부른 경우 포함):

| | 일치 | dynamic(이유 있음) | 불일치 |
|---|---:|---:|---:|
| 수정 전 (`main` 3507399) | 29 | 2 | 13 |
| 수정 후 | 38 | 6 | 0 |

수정 전에는 서비스 6파일 모두에 거짓 `route-call-coverage:`도 붙었다(OkHttp `RequestBody`·`ResponseBody` import를 모델링하지
않은 클라이언트로 셌다).

| 범주 | 케이스 | 수정 전 | 수정 후 |
|---|---|---|---|
| 동사 | `@GET`·`@POST`·`@PUT`·`@PATCH(value=)`·`@DELETE`·`@HEAD`·`@OPTIONS`·`@HTTP(method, path, hasBody)` | 일치 | 일치 |
| 계약 밖 동사 | `@HTTP(method = "PURGE")` | `methodDynamic`, 경로 일치 | 같음 |
| query | `@Query`, `@QueryMap`, 어노테이션 안 `?active=true` | 경로 불변, 일치 | 같음 |
| base 결합 | `users/{id}` + `/api/v1/`·`/`, `/health`(host 루트로 되돌아감) | 일치 | 같음 |
| `@Path` 값 | `a/b`(→ `a%2Fb` 한 세그먼트), `q 1`(→ `q%201`) | 일치 | 같음 |
| `@Path(encoded = true)` | `docs/{path}` + `guide/intro` → 두 세그먼트 | `/docs/{}` 거짓 한 세그먼트 | dynamic, `channelPrefix` `/docs/` |
| 부분 세그먼트 | `files/{name}.json` | dynamic(`compose.interpolation`) | 같음 |
| 점 세그먼트 | `./status`, `.`, `reports/../summary`, `/legacy/./reports/../export` | 점이 템플릿에 남음 | `/status`, `/`, `/summary`, `/legacy/export` |
| base 위 `..` | `../v2/status` → `/api/v2/status` | `/../v2/status` | dynamic(미상 base) |
| `@Url` | `@GET @Url` | dynamic | 같음 |
| 전체 URL·network-path | `https://uploads.example.com/v2/blobs/{id}`, `//cdn.example.com/assets/logo.png` | root + authority 일치 | 같음 |
| Kotlin `suspend` | `suspend fun profile` | 일치 | 같음 |
| 완전한 이름 | `@retrofit2.http.GET` | 사실 없음 | 일치 |
| 상수 | 파일 최상위 `const val`·템플릿 안 `$USERS`, 동반 객체(한정·비한정), 같은 파일 object, `ORGS + "/…"` | 일치 | 같음 |
| 파일 밖 상수 | `SharedPaths.MEMBERS`(다른 파일 object) | `/{}`·`/{}/{}` 거짓 템플릿 | dynamic |
| Java | `@GET(value = …)`, `@HTTP(method = …, path = …)`, 인터페이스 상수 `ITEMS`, 같은 파일 class 상수 | `/{}`·`methodDynamic` | 일치 |
| 상속 | `AdminApi : BaseApi`, `LegacyAdminApi extends LegacyApi`로 만든 프록시의 상속 메서드 | 일치(symbol은 선언한 상위 인터페이스) | 같음 |

## 공식 소스로 확인한 규칙

Maven Central의 `retrofit-2.12.0-sources.jar`·`okhttp-3.14.9-sources.jar`에서 확인했고, 오라클 실행이 재확인한다.

- Retrofit `RequestBuilder`는 상대 URL을 `HttpUrl.resolve`·`newBuilder(relative)`로 base에 결합한다. OkHttp `HttpUrl.Builder.resolvePath`는
  `/`(또는 `\`)로 시작하면 경로를 루트로 되돌리고, 아니면 base의 마지막 `/` 뒤를 버린 뒤 붙이며 `.`·`%2e`는 건너뛰고
  `..`는 한 세그먼트를 지운다(RFC 3986 §5.2.4와 같은 결과). `Retrofit.Builder`는 `/`로 끝나지 않는 base URL을 거부한다.
- `@Path` 값은 `canonicalizeForPath`가 제어·비ASCII 문자와 `` "<>^`{}|\?#``(와 공백)을 늘, `/`·`%`는 `encoded = false`일 때만
  퍼센트 인코딩한다. 값 전체가 `.`·`..` 세그먼트를 만들면 `@Path parameters shouldn't perform path traversal`로 거부한다.
- `RequestFactory`는 어노테이션 query 문자열 안의 `{name}`, `@Url`과 경로 값의 동시 사용, `@Url` 없는 빈 경로를 거부한다.
- `Retrofit.validateServiceInterface`(2.12.0)는 서비스 인터페이스의 상위 인터페이스를 거부하지 않고 타입 매개변수만 거부한다.
  상속 메서드는 선언한 인터페이스의 `Method`로 요청을 만든다.

## 재현

```sh
python3 experiments/phase4-retrofit/run.py          # retrofit-requests.json을 다시 쓴다
python3 experiments/phase4-retrofit/run.py --check  # 현재 파일과 오라클이 같은지 확인한다
./gradlew :cli:test --tests dev.kartograph.cli.RetrofitOracleTest   # 표는 테스트 stdout에 나온다
```

JDK 21과 Maven Central·Gradle Plugin Portal 접근이 필요하다. MockWebServer는 오라클 프로세스 안에서만 떠 있다가 종료된다.
기본 CI는 네트워크 의존 오라클을 돌리지 않고, 커밋된 기록에 대한 `RetrofitOracleTest`만 실행한다.

## 한계

- 소스 스캐너라 파일 밖 상수(다른 파일 object·Java class 상수)는 증명하지 못하고 dynamic이다. 바이트코드의 접힌 어노테이션
  값을 읽는 경로(`routes --role server`의 `--graph-file`)는 클라이언트 쪽에 아직 없다.
- 호출 쪽 부분 세그먼트(`{name}.json`)는 `encoded = false`면 Retrofit이 `/`를 인코딩해 실제로는 한 세그먼트지만, 공유 규칙
  `compose.interpolation`대로 dynamic으로 둔다(재현율 손실, 거짓 사실 없음).
- 상속 메서드의 `symbol`은 어노테이션을 선언한 상위 인터페이스 메서드다. isthmus 매니페스트 `match.interfaces`에 하위
  인터페이스만 적으면 상속 메서드 호출은 그 선언으로 귀속되지 않는다 — 상위 인터페이스도 적는다.
- OkHttp가 `\`를 `/`처럼 경로 구분자로 읽는 동작, 경로 안 중복 슬래시, `@Path` 이름이 템플릿에 없는 오류 선언은 케이스에 없다.
