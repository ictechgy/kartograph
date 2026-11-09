# HANDOFF HISTORY

> 정리 전의 과거 기록이다. 현재 상태는 [HANDOFF.md](HANDOFF.md)를 따른다. 과거의 “최신”, “다음 작업”, 경로는 현재 상태나 실행 승인이 아니다.

> 2026-09-20까지의 과거 기록이다. 현재 재개 정보는 [HANDOFF.md](HANDOFF.md)를 따른다.

## 2026-09-30 HANDOFF 원문 (2026-11-04 이관)

> 0.17.0~0.18.0 무렵의 재개 기록이다. 여기의 "진행 중"·"다음 범위"·재개 프롬프트는 현재 지시가 아니다. language-traversal·http route-call·Spring route-decl·Retrofit 대칭성은 0.18.0 전후로 머지됐다(CHANGELOG 참고). 개인 절대경로는 `<kartograph checkout>`으로 바꿨다.

마지막 갱신: 2026-09-30 — #122–#127(Retrofit usr·baseUrl·인터셉터, Spring 클라이언트, 벡터 재벤더링, Security 스코프) 머지 반영.
이전 갱신: 2026-09-25 — 변경 내용 기반 impact 설계 완료(spec 검토 대기), 0.17.0 배포 완료

### 진행 중: isthmus trace용 `language-traversal` 생산자

- **브랜치:** `feature/language-traversal` (로컬만, push·PR 안 함). 계약은 isthmus origin/main의 `docs/LANGUAGE-TRAVERSAL.md`다.
- **구현:** `impact --format language-traversal`(역방향)·`reach`(정방향)는 `cli/.../TraversalCommand.kt`, 순회는
  `analysis/.../LanguageTraversal.kt`(다중 출발 top-2 BFS + 등급별 SCC 비트 전파), 간선 등급은 `analysis/.../TraversalEdges.kt`,
  렌더링은 `export/.../LanguageTraversalCodec.kt`다. snapshot은 `graph.enclosures`(classfile `EnclosingMethod`)를 싣고
  class 인덱스 캐시는 v5다. `routes`의 `missing-route-usrs:`는 `cli/.../RouteSymbolDiagnostics.kt`다.
- **결정:** 기본 `--dispatch candidates`는 `FunctionN`/SAM 콜백 fan-out을 빼고 람다 본문을 `contains`로 잇는다. 단일 구체
  프로젝트 구현으로 해석되는 dispatch만 `bound`다. 근거는 [IMPACT](docs/IMPACT.md#isthmus-trace용-순회-문서-language-traversal-v1).
- **검증:** 무작위 그래프 600개에서 root별 전수 BFS와 대조한다(`LanguageTraversalTest`). 비공개 도그푸딩 앱은 읽기 전용으로
  대조했고 결과는 공개 기록에 싣지 않는다. 기본 `impact` 출력이 이전 배포본과 바이트 단위로 같은지 실제 snapshot으로 확인했다.
- **남은 범위:** 람다를 인자로 받아 호출하는 쪽(콜백 호출자)의 정밀 귀속(인자 흐름 필요), 정방향 `reach`의 실제 서버 검증.
- **테스트 소스·신선도 (2026-09-28, `fix/test-sources-freshness`):** 순회는 테스트 소스 정점(`TestSourceSets` 경로 규칙,
  routes와 공유)을 기본으로 빼고 production 부분 그래프에서 bound를 매긴다(`analysis/.../TestSourceScope.kt`). root가 테스트거나
  production → 테스트 비-dispatch 간선이 있으면 전체 그래프로 되돌린다. 순회도 routes처럼 신선도를 확인하고(`--input-bindings`),
  matched는 routes·bridges·schema·순회 모두 한계로 싣지 않는다. 비공개 E2E에서 unit test 포함 snapshot의 역방향 결과가 unit test
  제외 snapshot 결과와 행 단위로 같아졌다(수치는 비공개 원장).

### 진행 중: http route-call 생산자 (`kartograph routes`)

- **브랜치:** `feature/http-route-calls`(#107 머지). isthmus 소비자 계약은 isthmus main의 `docs/GRAPH-EXCHANGE.md` http 절·`docs/HTTP-WRAPPERS.md`다(isthmus #118로 머지).
- **구현:** `routes --role client`가 `"target": "http"`, `"roles": ["client"]` 문서를 낸다. `index/.../RouteUrlRules.kt`(url-compose·http-template 순수 규칙), `RouteSourceModel.kt`(패키지·import·선언 범위·상수·경로 식 해석), `RouteCallScanner.kt`(래퍼·`java.net.URL`·Retrofit), `export/.../HttpWrappersCodec.kt`, `cli/.../RoutesCommand.kt`.
- **적합성 벡터:** `fixtures/isthmus-conformance/`에 `http-template.json`·`url-compose.json`과 `conformance.lock`(isthmus 커밋·sha256)을 벤더링했다. `RouteConformanceTest`가 sha256 대조 뒤 모든 생산자 케이스를 돌린다. isthmus가 벡터를 바꾸면 다시 복사하고 lock을 갱신한다. 현재 lock은 isthmus `76b6141`(#125, `http-dispatch.json` 추가, Spring base 결합 러너 `spring-uri-builder`·`spring-root-uri`·`spring-http-exchange`)이다.
- **검증:** 합성 문서를 isthmus `check`로 조인해 수용을 확인했다. 비공개 도그푸딩 앱은 읽기 전용으로만 대조했고 결과는 공개 기록에 싣지 않는다(오라클 정의 지점 전부 일치, 줄 차이는 계약의 호출 시작 줄 규칙 때문).
- **남은 범위:** 파일 밖 상수(Retrofit base 값은 #123이 다른 파일 상수까지 푼다), 수신자 타입 추론 없는 멤버 호출, OkHttp·Ktor 직접 호출, Java 명명 인자 없음 전제, `@file:JvmName` facade.
- **Retrofit usr (2026-09-29, `fix/retrofit-usr`):** Retrofit 사실의 신원은 인터페이스 메서드다(`index/.../RetrofitSymbols.kt`,
  소유 타입·이름·동사 어노테이션·매개변수 수). 하위 인터페이스로 부른 상속 추상 메서드는 `ClassFileIndexer`의
  `inheritedInterfaceCallEdges`가 상위 선언으로 `call` 간선을 더한다. pythograph Phase 6 e2e에서 Android 주문·결제 사실에 usr가
  붙고 역방향 순회가 저장소·ViewModel에 닿는다(#122 머지). 당시 남았던 trace 체인의 `unattributed-calls-omitted`(authority 없는
  `pathAnchor: base`)는 아래 baseUrl 결합(#123)으로 해소됐다.
- **Retrofit baseUrl 결합 (2026-09-29, `feature/retrofit-baseurl`, #123 머지):** `create` 호출의 수신 식을 따라가 base를 푼다
  (`index/.../RetrofitBaseUrls.kt` 색인·URL, `RetrofitInstanceResolver.kt` 수신 식·DI·Koin, `SourceDeclarations.kt` 파일 밖 선언,
  `BuildConfigFields.kt`). 리터럴이면 authority + root 결합 템플릿, 아니면 base + `baseRef`(`kt:` 소스 한정 id)와
  `unresolved-base-url:`. 오라클 54케이스(팩토리 10 추가) 불일치 0, pythograph e2e에서 Android 주문·결제 체인이 ViewModel까지 닿고
  `unattributed-calls-omitted`가 0이 됐다(pythograph 기록 재생성은 후속). 재작성 인터셉터가 있으면 모든 base를 버리던 규칙은
  #127이 인스턴스별로 좁혔다. 남은 범위: 오버로드 팩토리, 확장 함수 수신 객체, Dagger `@Binds`·다중 컴포넌트.
- **인터셉터 client 결합 (2026-09-30, `feature/interceptor-scope`, #127 머지):** URL 재작성 인터셉터 → `addInterceptor` → OkHttpClient → Retrofit
  결합을 따라가 재작성 client를 쓰는 인스턴스의 base만 버린다(`InterceptorRewriteIndex.kt` 재작성 개체의 값 흐름,
  `OkHttpClientResolver.kt` client 해석·`Authenticator`/`EventListener` 판정, `SourceValueResolver.kt` #123 DI 공유). 결합을 증명하지
  못하면 이전처럼 전체 base를 버린다. 인스턴스별 한계에 authority만 바꾸는 재작성이면 호출 측 스코프. 오라클 인터셉터 케이스 18개
  (일치 7·재작성으로 버림 11·불일치 0). 남은 범위: 헬퍼 함수로 옮긴 재작성·라이브러리 인터셉터(어휘로 못 봄), Java 생성자 주입,
  `@Binds`·다중 바인딩(항상 전체 대체), 재작성 뒤 host를 모르는데 남는 `baseRef` 귀속.

### 완료: Spring HTTP 클라이언트 route-call (API 영향 Phase 7b)

- **브랜치:** `feature/spring-clients`(#124 머지). 규칙·출처·오라클·e2e는 [SPRING-CLIENTS](docs/SPRING-CLIENTS.md)와
  [experiments/phase7b-spring-clients](experiments/phase7b-spring-clients/README.md)다.
- **구현:** `index/.../SpringClientCalls.kt`(호출 지점·`SpringClientSetup`), `SpringClientResolver.kt`(클라이언트 식 → 종류·base, `@Bean`·
  `@Value`), `SpringHttpExchanges.kt`(`@HttpExchange`), `SpringUriRules.kt`(결합 규칙). Retrofit과 공유하는 소스 범위 도구는 `SourceScopes.kt`다.
  한 줄 식 몸체 함수의 `symbol.usr` 누락은 `ChannelBridgeScanner.enclosingDeclaration`에서 고쳤다.
- **검증:** 합성 Spring Boot 앱 32개 호출 실행 오라클 전부 일치(`SpringClientOracleTest`), isthmus origin/main `f9dcd1d` workspace trace에서
  B route → A 호출(`exact`) → A 핸들러 체인 확인(`e2e/run_trace.py`, 기록 `e2e/recorded/`).
- **isthmus 반영:** isthmus #131(`76b6141`)이 `HTTP-WRAPPERS.md` base 결합 표에 Spring 행과 url-compose `base-join/spring-*` 13개를
  받았고, #125가 재벤더링해 러너로 13개 모두 생산자 코드 변경 없이 통과했다.
- **남은 범위:** Feign, Boot 4 `@ImportHttpServices`,
  `@ConfigurationProperties` base, trace가 client member의 역방향 도달을 그 member의 route-decl로 잇는 기능(isthmus 쪽).

### 진행 중: Spring route-decl 생산자 (`routes --role server`, API 영향 Phase 4a)

- **브랜치:** `feature/spring-routes`. 규칙·근거·오라클 결과는 [SPRING-ROUTES](docs/SPRING-ROUTES.md)다.
- **구현:** `index/.../RouteDeclScanner.kt`(조립·사실), `SpringMappingResolver.kt`(핸들러·병합), `SpringPathPatterns.kt`(템플릿),
  `SpringProjectConfig.kt`·`SpringConfigDocuments.kt`(Boot 설정·빌드 표지), `SpringBytecodeReader.kt`·`SpringSourceReader.kt`·
  `SpringModelMerger.kt`(값·위치 원천), `SpringProjectSignals.kt`(모델링하지 않은 경로). CLI는 `RoutesCommand`가 snapshot
  provenance의 class root를 넘긴다.
- **검증:** 공개 앱 3개와 합성 앱 2개의 actuator 오라클 정밀도 100%. 합성 앱과 기록한 오라클은 `fixtures/spring-routes-corpus/`,
  비교 스크립트는 `experiments/phase4-spring-routes/compare_mappings.py`.
- **스코프 (2026-09-29, `feature/spring-scopes`):** `framework-provided-routes:`를 제공자마다 내고 증명한 제공자에 http
  `limitationScopes`를 붙인다(`index/.../SpringFrameworkRoutes.kt`, 검증·겹침은 `RouteLimitationScopes.kt`). 빈 값 변형 decl,
  isthmus `78d3dee` 벡터 재벤더링, `reach --persistence-facts`(`analysis/.../PersistenceModeledCalls.kt`), Boot 버전 검출 통합
  (`SpringBootVersions.kt`). 판정 가능 비율 측정 절차·수치는 [SPRING-ROUTES](docs/SPRING-ROUTES.md#오라클-검증-2026-09-28).
- **Security·Data REST·GraphQL 스코프 (2026-09-30, `feature/security-scopes`, #126 머지):** 정적으로 풀리는 서블릿
  `SecurityFilterChain`(logout·form login·기본 로그인 페이지·`oauth2Login`·7.x resource server 메타데이터)의 필터 응답 경로와
  Data REST base path·GraphQL 경로를 `limitationScopes`로 싣는다. 규칙·근거는 [SPRING-ROUTES](docs/SPRING-ROUTES.md#spring-security).
  spring-petclinic-rest 판정 가능 비율 0/49 → 40/49(정밀도 40/40).
- **남은 범위:** 함수형 라우터 추출, Security 중 모델링하지 않은 것(SAML2·WebAuthn·one-time token·`oauth2Client`·
  `passwordManagement`, 사용자 필터·`RequestMatcher`·`WebSecurityCustomizer`, 리액티브 Security, Boot 2 — 모두 스코프 생략),
  정적 리소스 위치 열거(의존성 JAR 포함),
  빈 값 변형 decl 표식(isthmus 미결), Phase 4b 나머지.

### 진행 중: Phase 4 종료 조건 — Retrofit 오라클과 순회 대칭성

- **브랜치:** `feature/retrofit-symmetry`. Retrofit 결과·규칙 근거는 [experiments/phase4-retrofit](experiments/phase4-retrofit/README.md),
  대칭성 결론은 [IMPACT](docs/IMPACT.md#정방향역방향-대칭성-reach--impact)다.
- **Retrofit:** 합성 코퍼스 44케이스가 Retrofit 2.12.0 + MockWebServer 기록과 일치(38)하거나 이유 있는 dynamic(6)이다. 오라클
  재생성은 `python3 experiments/phase4-retrofit/run.py`(Maven Central 필요, 기본 CI 밖), 검사는 cli `RetrofitOracleTest`.
- **대칭성:** 콜백 간선을 따르지 않으면 정확한 전치, 따르면 `impact`만 콜백 문맥 쌍만큼 넓다(설계). 테스트 소스는 root가 범위를
  정한다. analysis `TraversalSymmetryTest`, cli `TraversalSymmetrySelfTest`.
- **남은 범위:** 클라이언트 쪽 파일 밖 상수(바이트코드 어노테이션 값 읽기), Retrofit `encoded = false` 부분 세그먼트의
  재현율(공유 규칙상 dynamic), isthmus `match.interfaces`에 상속 상위 인터페이스를 적어야 하는 점.

### 진행 중: 변경 내용 기반 impact 진입점

- **목표:** v8에서 agent가 정확한 선택자를 만들지 못해 MCP impact 3회가 모두 `notFound`로 끝난 문제를 푼다. agent가 가진 "수정한 파일+줄"(`changes` 또는 unified `diff`)을 받아 변경 선언을 찾고 기존 impact로 잇는다. AI 효용 개선은 주장하지 않고, 이후 새 평가로 판단한다.
- **브랜치:** `feature/change-based-impact` (로컬만, push 안 함). spec 커밋은 `cd984c7`이다: [docs/superpowers/specs/2026-09-25-change-based-impact-design.md](docs/superpowers/specs/2026-09-25-change-based-impact-design.md).
- **사용자가 확정한 결정:**
  1. 입력은 AI가 파일+줄 범위나 diff 텍스트를 넘긴다. MCP는 계속 git과 소스를 읽지 않는다.
  2. 줄 범위는 **혼합(C)** 방식이다. bytecode LineNumberTable을 기준으로 삼고(SMAP으로 inline 줄 제외), 소스 어휘 분석은 시그니처와 어노테이션까지 **넓히는 데만** 쓴다. 출처(`bytecode`/`source-extended`/`source-only`)를 기록한다.
  3. 새 MCP 도구는 만들지 않고 기존 `impact`에 `changes`/`diff`를 추가한다. CLI에는 `--diff`/`--changes-from`을 추가한다.
  4. 줄 범위는 `GraphNode`/query 스키마에 넣지 않고 snapshot 선택 필드 `sourceRanges`에 둔다.
  5. 해석하지 못한 줄은 파일 전체로 부풀리지 않고 `unmappedLines`/`rangesUnavailable`로 명시한다.
- **탐색에서 확인한 사실:**
  - 그래프에는 메서드 **시작 줄만** 있다(`ClassFileIndexer`의 `firstLine`).
  - 선언 범위 어휘 분석은 `ChannelBridgeScanner.enclosingDeclaration`에 이미 있다.
  - MCP `impact`는 `files`를 받지만 경로 suffix 매칭은 `discover_symbols`(`McpTools.kt`)에만 있다.
  - 변경 파일 Git 수집은 `ChangedFiles.kt`에 있다.
- **다음 단계:**
  1. 사용자가 spec을 승인하면 `superpowers:writing-plans`로 구현 계획을 쓰고, 실행 방식을 사용자가 고르게 한다.
  2. **PR 1 (실험, 제품 코드 없음):** `experiments/source-ranges/`에 A(bytecode만)와 C(혼합)의 prototype을 만들고 줄 → 선언 정답률을 비교한다. 정답 세트는 v8 detekt/ktlint 실제 변경 4건과 사례 fixture다. A가 동등하면 A로 단순화한다. 결과를 사용자에게 보고한 뒤 다음으로 넘어간다.
  3. **PR 2:** 범위 캡처와 `sourceRanges`. 예전 CLI가 새 키를 거부하는지 먼저 확인한다.
  4. **PR 3:** 해석, MCP/CLI, 문서, 스킬. 끝나면 v8 notFound 입력 3건을 diff 형태로 다시 조회해 확인한다.
- **남은 위험:** 프로퍼티 초기화 줄에서 생성자와 프로퍼티 중 어느 쪽이 선택될지, 소스 휴리스틱의 오판(문자열 안 중괄호, DSL, 여러 줄 식 본문)

#### 경쟁 도구 비교 요약 (2026-09-25, 대화 결과만 있고 별도 문서는 없음)

- **차별점:** bytecode 원천, keep 규칙의 파일·줄 근거, base/current impact, 측정된 한계, bridge·schema 사실을 한 도구에서 모두 하는 경우는 찾지 못했다.
- **약점:** 도구를 쓴 결과가 행동으로 이어지는 계층이 약하다. 가장 큰 약점은 AI 효용이 아직 증명되지 않았다는 점이다.
- **PRD 범위 안의 보강 우선순위:**
  1. 변경 기반 진입점 (현재 작업)
  2. impact를 실행 가능한 테스트 목록(Gradle `--tests`)으로 출력하고 androidTest를 연결
  3. 첫 사용과 갱신 비용 측정
  4. R8 Configuration Analyzer(AGP 9.3.0+, 원문 확인)와 겹치는 영역을 "규칙 → 심볼·영향"으로 차별화하고, `usage.txt`를 선택 입력으로 받는 것 검토
  5. impact PR 코멘트
  6. 그래프 내보내기(Neo4j/SQLite)
- **범위 결정이 필요한 것** (PRD의 "하지 않는 것"과 충돌): IDE 표시, 자동 수정·삭제(유지 권장), 운영 환경 runtime 수집
- **원문 확인:** R8 Configuration Analyzer와 SearchDeadCode(★14)는 원문으로 확인했다. 나머지 도구의 star 수와 버전은 에이전트 조사 값이라 다시 확인해야 한다.

#### 효과가 있었던 것과 없었던 것

- **효과 있음:** 릴리스 전에 PR head와 merge tree가 같은지 대조하고 배포본을 재빌드해 비교했다. README가 바뀌면 CLI ZIP/TAR 해시도 바뀐다는 것을 이렇게 확인했다. 브랜치는 "PR head = 브랜치 끝" 기준으로 정리했다.
- **효과 없음 / 주의:**
  - `packet-ask --diff HEAD~1`은 **작업 트리를 포함**한다. 미커밋 파일까지 전송되므로 커밋 범위를 명시하거나 작업 트리를 비운 뒤 실행한다.
  - `gh pr checks --watch`를 PR 생성 직후에 실행하면 "검사 없음"으로 끝난다. run ID를 조회한 뒤 `gh run watch`를 쓴다.
  - zsh에서는 `set -- $var` 방식의 단어 분리가 되지 않으므로 bash로 실행한다.
  - `./gradlew clean`을 해도 `build/reports`의 보존 worktree는 남는 것을 확인했다. 그래도 root `build/`를 직접 지우지 않는다.

### 현재 상태

- **0.17.0 배포 완료.** [PR #103](https://github.com/ictechgy/kartograph/pull/103) squash 머지 `05e843b`(검증 head `05f24ae`와 tree 동일), annotated 태그 `v0.17.0`, [GitHub Release](https://github.com/ictechgy/kartograph/releases/tag/v0.17.0) asset 7개, [Plugin Portal](https://plugins.gradle.org/plugin/io.github.ictechgy.kartograph/0.17.0) 게시.
- 검증: PR CI `36011298688`, main CI `36015029951`, Release `36015069175` 모두 성공. 로컬 `verify-release-readiness.sh`는 `9cca600`에서 통과. 이후 `05f24ae`는 README만 바꿨다. 배포 asset의 `SHA256SUMS` 전부 OK, Portal JAR = Release JAR, `05e843b` 로컬 재빌드 CLI ZIP/TAR = 배포본. 배포 CLI의 `--version`과 Room fixture `schema` 스모크도 확인했다.
- GLM 리뷰(low)는 릴리스 파일에서 결함 0건이었고, 제공 버전 문구의 위치만 반영했다. packet에 미커밋 HANDOFF diff가 함께 들어갔다(승인 범위 밖, 비밀정보 없음). 앞으로 `--diff`는 커밋 범위를 명시한다.
- 원장: `.git/release-0.17.0-20260925/FINAL.json`, `SHA256SUMS`와 GLM 응답·readiness·release 로그의 gzip 사본(원본 해시 대조 완료). 필수 잔여 작업 없음.

- `kartograph schema`가 [PR #102](https://github.com/ictechgy/kartograph/pull/102)로 머지됐다(`45fc535`, `feature/schema-facts` 브랜치 정리됨). `schema`는 0.17.0으로 발행됐다.
- Room·JDBC·Exposed·jOOQ·SQL 리터럴·SQLDelight `.sq`/`.sqm`을 읽어 isthmus persistence 계약(`platform: "kotlin"`, `target: "persistence"`, `relation-use`) 문서를 낸다.
- 신규: `index/.../SchemaFactScanner.kt`(선언 패스→사실 패스), `index/.../SqlRelations.kt`(rustograph `source/schema.rs`의 SQL 렉서 포트 — 문장 경계·GRANT/ON 게이트·플레이스홀더 미해석 계수 포함). 공유 헬퍼는 `ChannelBridgeScanner.kt`에서 internal로 승격. `ProjectTraversal.walkSources`에 `extensions` 파라미터 추가.
- `named-arg` 판정은 선두 `name =` 패턴만 본다 — SQL 문자열 안의 `=`로 오인해 인자를 버리던 초기 결함을 테스트로 고정했다. `==` 비교 식은 `(?!=)` lookahead로 걸러진다.
- GLM 리뷰 2라운드를 반영했다. 1라운드(`ab803db` 전후): `.sq` 라벨이 문장 머리를 삼키던 문제, 산문 리터럴 오탐(게이트 없는 리터럴은 strict 모드 — 대문자 키워드만 발화), `'"'` 문자 리터럴이 뷰를 깨던 문제, 중첩 클래스 멤버 오귀속, 무인자 호출의 허위 동적 사실, `@field:` use-site 타깃, 미해석 피연산자 개수 계수, Exposed 미선언 대상의 동적 근거 보존. 2라운드(`8952e75`): 공백 무인자 호출 가드 우회, ungated 리터럴의 엔티티 번역 과적용 제거(`@Query` 경로만 유지), strict 낙전 리터럴의 `skipped-sql-literals` limitation 계수.
- 검증: `:index:test`·`:cli:test` 통과, CLI 계약·agent surface·자기 분석 스모크 게이트 통과, `/tmp` fixture → `isthmus check` end-to-end로 kotlin 문서 조인 확인.
- isthmus 측 kotlin 수용 테스트와 `hasPersistenceDomain`의 `target` 기반 판정 수정은 PR #111(`7e69fdf`)로 머지됐다. 같은 작업의 swift 수용은 PR #112(`b6a0eec`).
- 아래는 직전 0.16.0 배포·정리 기록이다.

- Kotlin/JVM 컴파일 그래프를 질의하는 CLI·Gradle plugin. 작업 규칙은 [AGENTS.md](AGENTS.md), 제품 범위는 [PRD](docs/PRD.md).
- 0.16.0 발행 기준 커밋 = `b7165489379b7916b0fa30ddded22aeb576ca696`, 당시 최신 발행 버전. 현재 VERSION·최신 발행은 **0.17.0**(`05e843b`).
- [PR100](https://github.com/ictechgy/kartograph/pull/100) 머지와 `v0.16.0` 태그·[GitHub Release](https://github.com/ictechgy/kartograph/releases/tag/v0.16.0)·[Plugin Portal](https://plugins.gradle.org/plugin/io.github.ictechgy.kartograph/0.16.0) 게시를 완료했다.
- 요청한 구현·CI·배포·독립 설치·정리의 필수 작업은 남지 않았다. 과거의 “다음 작업”을 자동 재개하지 않는다.

### 완료와 검증 근거

- [PR99](https://github.com/ictechgy/kartograph/pull/99): FILE_FACADE 기반 최상위 함수 후보, 모듈 경로 후보, MCP `discover_symbols` 페이지 조회로 선택자를 복구한다. 원래 notFound·ambiguous·분석 한계는 유지한다. [사용법](docs/MCP.md).
- 릴리스 PR CI `35776752442`, main CI `35780185751`, [Release run](https://github.com/ictechgy/kartograph/actions/runs/35780283974) 모두 성공. 검증 head `cf89e04`와 merge tree가 같다.
- 두 clean 빌드8개 산출물 해시, ZIP/TAR 각각142개 테스트·CLI 계약, 공개7개 asset digest·SHA256SUMS, 독립 Portal 설치/JAR 동일성·cache·freshness 대조를 통과했다.
- 실제 0.16.0 배포 CLI로 v8 실패 선택자3개를 정확한 USR로 재조회했다. AI 모델을 재실행하거나 과거 점수를 바꾸지는 않았다.
- 정본: `.git/release-0.16.0-20260923/FINAL.json`, `.git/selector-recovery-20260923/FINAL.json`. 다른 checkout에 이 로컬 원장이 있다고 가정하지 않는다.

### 이번 정리

- 완료·clean·머지 tree 일치를 확인한 worktree **6개를 제거**했다. 각 HEAD와 branch, `refs/archive/cleanup-20260923/*` 및 실행 산출물을 보존했다.
- 2026-09-23 기준 Git worktree는 main과 아래 사용자 변경 worktree만 남았다. Orca 관리 목록에는 main만 남았다.
- 2026-09-25 추가: 2026-09-17 stash(`preserve-local-docs-before-integration`)는 내용이 main에 이미 있어 `refs/archive/cleanup-20260925/stash-preserve-local-docs-20260917`로 보존한 뒤 비웠다. 중복 배포본(`cli/build/distributions`, `compiler-collectors/build/distributions`, `build/release`)은 지웠고, readiness 근거는 `.git/release-0.17.0-20260925/release-readiness-local.tar.gz`로 옮겼다. 원장은 `.git/cleanup-branches-20260925/FINAL.json`이다.
- 2026-09-25: 머지 완료 브랜치(PR head = 브랜치 끝, 또는 main에 포함)를 로컬 21개·원격 24개 삭제했다(고유 26개 = 양쪽 19 + 원격 전용 5 + 로컬 전용 2). 끝 커밋은 `refs/archive/cleanup-20260925/*`에 보존했다. 남은 것은 main과 열린 dependabot PR #92 브랜치다. 복원은 `git branch <name> refs/archive/cleanup-20260925/<name>`으로 하고, 원장은 `.git/cleanup-branches-20260925/FINAL.json`이다. worktree 복원 스크립트는 `cleanup-20260923` archive ref를 쓰므로 영향이 없다.
- 대형 과거 자료560개를348개 gzip blob으로 묶고, 프로젝트 cache261개도 압축 보존했다. 모든 payload와 worktree 파일13,278개를 실제 복원·해시 대조했다.
- 보존 archive를 포함한 배정 용량은 약 **3.8 GiB 감소**했다. SDK·설치 도구·전역 cache·원본 연구 입력·사용자 메모를 보존했다.
- 원장/복원 목록: `.git/cleanup-handoff-20260923/README.md`, `FINAL.json`, `artifact-archive-manifest.json`, `worktree-archives.json`.
- 이전 상세 인수인계는 [HANDOFF-HISTORY.md](HANDOFF-HISTORY.md)에 옮겼다. 정리 전 원문도 정리 원장에 그대로 있다.

### 보존한 사용자 변경

- `build/reports/expanded-evaluation-20260913/interface-v2-worktree`: detached `d4e6a81`, `Scripts/`의 파일6개에 미커밋 변경이 있어 보존했다. 부모 `build/reports`를 통째로 지우지 않는다.
- [자매 스킬 메모](HANDOFF-cartograph-skills-20260921.md)와 [cartograph 메모](HANDOFF.cartograph-notes.md)는 기존 사용자 파일이며 `.gitignore`로 로컬에만 둔다.
- HANDOFF는 main에 직접 커밋하지 않고 docs PR로 반영한다.

### 복원과 실행

원래 경로의 byte·mtime을 되살리며 기존 다른 내용을 덮어쓰지 않는다. 필요할 때만 선택 복원한다.

```bash
python3 .git/cleanup-handoff-20260923/restore-worktree.py ai-utility-v8-20260922
python3 .git/cleanup-handoff-20260923/restore-artifacts.py --list
python3 .git/cleanup-handoff-20260923/restore-artifacts.py --path '<목록의 원래 상대경로>'
python3 .git/cleanup-handoff-20260923/restore-caches.py --list
```

- 과거 manifest의 절대경로·frozen runtime hash를 수정하지 않았다. 과거 검증기를 실행하려면 필요한 worktree·입력·보고서/cache를 먼저 복원한다. 완료 모델 실행을 임의로 재시작하지 않는다.
- 0.16.0 CLI는 `.git/release-0.16.0-20260923/downloads-0.16.0/cli-zip/kartograph-0.16.0/bin/kartograph`에 남아 있다. 0.15.0 배포본과 v6/v7/v8 native archive도 보존했다.
- Java는 `JAVA_HOME=/opt/homebrew/opt/openjdk@17/libexec/openjdk.jdk/Contents/Home`을 명시한다. 기본 macOS java 런처는 등록이 없으며 전역 설정은 바꾸지 않았다.

### 한계와 다음 범위

- [v8 평가](experiments/ai-utility-v8/README.md)는 일관된 AI 효용 우위를 확인하지 못했다. 당시 실제 impact3회 모두 notFound였으며, 이번 별도 복구 검증을 과거 점수 향상으로 해석하지 않는다.
- 후속 AI 사용성 평가는 새 요청·프로토콜의 범위다. 기존 frozen proxy는3-tool 계약이므로 새4-tool 평가와 구분한다.

### 재개 프롬프트

`<kartograph checkout>`에서 HANDOFF.md와 AGENTS.md를 읽고 `feature/change-based-impact` 브랜치로 전환해줘. spec(`docs/superpowers/specs/2026-09-25-change-based-impact-design.md`)이 사용자 검토를 기다리고 있으니, 승인되면 writing-plans로 구현 계획을 쓰고 PR 1(A/C 비교 실험)부터 진행해줘. 0.17.0 배포와 정리는 완료된 상태다.


새 세션이 이어받기 위한 문서다. 작업 규칙은 [AGENTS.md](AGENTS.md), Claude Code 전용 사항은 [CLAUDE.md](CLAUDE.md). 이 파일은 **지금 어디까지 왔고 다음이 무엇인지**만 담는다.

마지막 갱신: 2026-09-20 (네 저장소 호환 릴리스 검증 완료; P1.1-2 PR #85 머지, 0.11.0 미포함)

## 목표

Kotlin/Android 코드베이스의 의존성 그래프를 컴파일러 산출물에서 만들고, 그 위에서 미사용 코드·순환·레이어 규칙·지표·변경 영향을 근거와 함께 답하는 CLI와 Gradle plugin. [cartograph](../cartograph)(Swift)의 자매. 제품 범위는 `docs/PRD.md`.

## 현재 상태

### 0.11.0 발행과 P1.1-2 후속

- 브리지 확장을 포함한 0.11.0은 [PR #84](https://github.com/ictechgy/kartograph/pull/84),
  tag `v0.11.0`(`14f0673`)으로 발행됐다. [릴리스 run 35459154848](https://github.com/ictechgy/kartograph/actions/runs/35459154848)이 성공했고,
  GitHub CLI 아카이브·Plugin Portal marker/JAR를 확인했다. 독립 다운로드 CLI는 0.11.0이며
  Portal JAR SHA256 `d51510d6bee5f5075ba8cfece3829d84546a8e3c8c8405ba4cf01eaf24752398`가 GitHub asset과 일치했다.
- P1.1-2 개발 소스는 `dependencies --library`의 api/implementation 배치 조언,
  `--resolved-dependencies` 전이 소유권, main/test 구분, 여섯 보고 형식과
  JVM/Android dependency task를 추가한다. [사용법·관찰 범위](docs/DEPENDENCIES.md)를 따른다.
  0.11.0 발행본에는 이 후속 기능이 없으며 다음 릴리스 대상이다.
- [PR #85](https://github.com/ictechgy/kartograph/pull/85)는 `e1e38a3`으로 머지됐다.
  [최종 CI 35465066320](https://github.com/ictechgy/kartograph/actions/runs/35465066320)의
  test·JDK 17/21·최소 AGP 4잡이 모두 성공했다. 로컬 783 tests(실패·오류·skip 0), Kover,
  CLI/agent, Android corpus 44 retained/4 reportable을 통과했다. 자기 분석은 7,624 nodes,
  dead/private/cycles/rules 0, 총 3.82초였다. 이 수치는 성능 개선 측정이 아니다.
- 사용자가 isthmus npm 0.8.0을 발행했다. registry 아카이브와 검증 후보의 바이트 동일성,
  별도 npm 설치의 CLI 계약·cold-cache 검사를 확인해 네 저장소 호환 세트 검증을 마쳤다.
  같은 아카이브로 검증한 실제 Kotlin 컴파일→snapshot→bridges→retention→dead/explain
  근거를 재사용한다. 지원되는 when(call.method)와 합성 Flutter API stub의 하네스이며
  앱 전체 런타임 검증은 아니다. [전체 인계](https://github.com/ictechgy/isthmus/blob/main/HANDOFF.md)를 참조한다.
- 실제 javac/Kotlin metadata 회귀와 Gradle configuration cache·strict·입력 변경 검증을 추가했다.
  최종 완료 근거는 위 PR/CI와 실제 Git 상태로 확인한다. processor별 생성 코드 귀속,
  baseline/suppress는 이번 핵심 범위와 구분된 후속이다. 불완전한 API·alias·클래스 소유권은
  한계로 남기며 부재를 삭제나 scope 축소의 근거로 삼지 않는다.
- Gradle JVM 경로와 Android API를 별도 클래스로 분리한다. 그렇지 않으면 configuration-cache
  lambda 역직렬화가 JVM 프로젝트에서 AGP `Variant`를 로드해 실패한다(실제 회귀로 확인).
- 이전 HANDOFF 동기화는 [PR #83](https://github.com/ictechgy/kartograph/pull/83)으로 머지됐다.
  `.claude/`·`HANDOFF.cartograph-notes.md`는 기존 사용자 파일이며 유지한다.

아래는 이전 브리지 머지·검증의 기록이다. 당시의 미발행 상태는 위 0.11.0 발행으로 해소됐다.

### 자매 브리지 확장 머지 완료 — 2026-09-20

- [PR #82](https://github.com/ictechgy/kartograph/pull/82)을 squash merge했다(`37f0053`).
  로컬·원격 main이 같고 머지 트리의 내용 해시는 검토·CI를 통과한 PR head의 tree 해시와 일치한다.
  이 HANDOFF는 해당 머지의 인계 기록이다. 기존 `.claude/`와
  `HANDOFF.cartograph-notes.md`는 사용자 미추적 파일로 보존했다.
- `dead --external-retentions <path>`가 isthmus external-retentions v0를 실제 JVM ID에
  적용한다. EXTERNAL_BRIDGE 근거·원본 caller 위치를 explain과 query snapshot v1/v2에
  보존하고, MEMBER 관계의 소유 타입까지 같은 근거를 전달한다. 잘못된 입력·빈 ID·그래프에
  없는 ID는 부분 적용 없이 실패한다. 관련 없는 형제 메서드를 함께 보존하지 않는다.
- `bridges --rn-events`는 명시적인 RCTDeviceEventEmitter 요청 뒤 emit을 v2
  `react-native-event`로 낸다. 완전 수식 이름은 import 없이 인식하며, 잘못 닫힌 인자는
  동적으로 남긴다. 실제 심볼 연결은 `snapshot --include-paths`와 유일한 소스 경로 해석이
  필요하다. package/디렉터리 불일치와 build witness·신선도 공백을 추측으로 해소하지 않는다.
- [CI run 35453722844](https://github.com/ictechgy/kartograph/actions/runs/35453722844)의
  test·JDK 17/21·최소 AGP 조합 4잡 모두 통과했다. 전체 tests/Kover·CLI/agent·compiler/
  precision·Android corpus·Gradle plugin·metadata 게이트가 포함된다. 앞선 JDK 21 로컬
  검증은 760 tests, 실패·오류·skip 0, corpus 44 retained/4 reportable, 6모듈 자기 분석
  7,173 nodes·총 5.46초였다. 실제 Kotlin snapshot→bridges→isthmus retention→dead/explain과
  JS/Swift/Kotlin RN 이벤트 조인도 검증했다.
- GLM packet-ask 초기 `42a71c2772aa`, 수정분 `3d7e8434c741` 리뷰를 완료했고
  [반영·기각 근거](https://github.com/ictechgy/kartograph/pull/82#issuecomment-5743320316)를
  PR에 남겼다. 아래 과거 packet-review 실패는 현재 차단 상태가 아니다.
- 동반 머지: isthmus [#96](https://github.com/ictechgy/isthmus/pull/96), cartograph
  [#123](https://github.com/ictechgy/cartograph/pull/123), dartograph
  [#127](https://github.com/ictechgy/dartograph/pull/127).
  [교환 계약](https://github.com/ictechgy/isthmus/blob/main/docs/GRAPH-EXCHANGE.md)과 [전체 인계](https://github.com/ictechgy/isthmus/blob/main/HANDOFF.md)를 참조한다.
  이전 임시 로그가 현재도 존재한다고 가정하지 말고 PR/CI와 남아 있는 저장소 기록을 확인한다.
- 이번 구현·검증·머지는 완료했다. 새 태그/발행은 하지 않았으며 기존 0.10.2 발행본과
  이 main의 추가 기능을 구분한다. RN 엔진/앱 전체 실행 검증은 아니다.

### 이전 기준 — 2026-09-19 (PR #80까지의 이력)

- **0.10.1·0.10.2를 공개했다(2026-09-18)**: 0.10.1은 tag `v0.10.1` → release workflow 전체 게이트 성공(run 35290692293) →
  GitHub Release 6 asset과 Plugin Portal 발행을 확인했다. 0.10.2는 별도 세션이 PR #74(squash `ecaa2be`)로 준비·머지하고 tag `v0.10.2`를 발행했다
  (GitHub Release draft=false 확인). `CHANGELOG.md` Unreleased에는 이후 머지된 #75 `input-hint`·#77 런타임 근거·#79 `dependencies` 항목이 있다.
- 2026-09-16~18에 머지된 PR: #59 impact 결함 → #60 EventChannel → #61 fingerprint 병렬화 → #62 단계 B 기각 기록 → #63 과제 종료 결론 →
  #64 3관점 리뷰 반영 → #65 JDK 17/21 재측정 → #66 README 퇴고 → #67 Expo Modules(다른 세션) → #68 HANDOFF 완료 절 → #69 HANDOFF 갱신 → #70 로컬 문서 통합(squash `9e377fd`) →
  #47 Dependabot `jvm` 2.4.20(squash `5d8e491`) → #71 release 0.10.1(squash `0ea604a`) → #73 unmatched keep rule 진단(squash `18c502d`) → #74 release 0.10.2(squash `ecaa2be`) → #75 누락 입력 input-hint 진단(squash `b7ea7f6`) → #76 HANDOFF 갱신(squash `bedb714`) → #77 런타임 근거 confidence(squash `4c12ce6`) → #78 HANDOFF 갱신(squash `a3f33ce`) → #79 미사용 선언 의존성(squash `3a5d142`) → #80 HANDOFF 갱신(squash `aa51968`).
- 2026-09-19 **PRD 개정**: "런타임 커버리지 통합 금지(정적 사실만)" 조항을 "JaCoCo/Kover를 실행·결합해 수집하지 않는다.
  사용자가 제공한 class-list·JaCoCo/Kover XML은 `dead`의 `runtime-observed` confidence 표시용 선택 입력으로만 받는다"로 바꿨다(PR #77).
  finding·strict·종료 코드는 그대로이고, 손상된 근거 입력은 도구 실패로 거부한다.
- 2026-09-19 외부 리뷰 인프라: `packet-review`가 provider(glm·qwen)·effort·파일 수와 무관하게 `packet-ask exited 125`로 실패했다.
  단일 파일 최소 packet도 동일했고, 한 번은 `hourly packet-review limit reached (6)`이었다. #75·#77·#79는 내부 독립 리뷰 + CI로 검증했다.
  같은 시각 `chore/release-0.10.2`·`fix/expo-dsl-scan-gaps` 원격 브랜치를 삭제했고(합의 완료), #75·#77·#79의 작업 브랜치도 머지 후 삭제했다.
- 열린 PR: 없음(2026-09-19, #80 머지 후 기준). PR #72(Expo DSL 중첩 제네릭·FQN·this. 스캔, `fix/expo-dsl-scan-gaps`의 작업)가 squash `81cb6eb`로 머지됐고, 이전 세션 작업 `feat/unmatched-keep-rule-diagnostics`는 worktree·원격 모두 정리했다.
  메인 체크아웃은 `main`으로 전환했고 오래된 브랜치를 대대적으로 정리했다(사용자 승인): 로컬 35개·원격 47개를 삭제했다
  (ancestor 또는 머지된 PR의 head 일치로 검증). `phase0/1/2`·`feat/adoption-competitiveness`는 재작성 전 이력·squash 전 변형으로
  내용이 main에 더 나은 형태로 포함됨을 diff로 확인 후 삭제(머지하면 Expo 스캔·KGP 2.4.20 회귀). 잔여 `pr/21`·`pr/46` ref도 제거.
  보존: worktree 체크아웃 2개(`feat/expanded-impact-evaluation`, `docs/handoff-session-20260917`와 그 원격), 스냅샷 마커 `public-main`.
  원격 `fix/expo-dsl-scan-gaps`(#72)와 `chore/release-0.10.2`(#74)는 2026-09-19 사용자 합의 후 삭제했고, 임시 worktree `/private/tmp/kartograph-rel-0.10.2`는 그전에 이미 제거됐다.
- 문서 통합은 PR #70으로 완료됐다: `AGENTS.md` 산출물 정리·근거 보존 규칙, `docs/PHASE4-AGENT.md` EventSink 미스캔 설명, HANDOFF 통합. 검증: `git diff --check`, GLM medium 리뷰 blocker/major 0, CI 4 job pass(`test` 26m43s 포함, run 35227567984).
- 기존 344줄 HANDOFF와 로컬 문서 2개의 원문은 stash `96a029edea8810d6c5a8d01efad2334f18686d44`(`preserve-local-docs-before-integration-20260917`)에 보존했다. 필요한 원문은 `git show 96a029e:HANDOFF.md`로 확인한다. 최신 문서 위에 stash 전체를 적용해 과거 상태로 되돌리지 않는다.
- 근거·측정 산출물은 `.claude/worktrees/perf-fingerprint-parallel/build/reports/`에 있다. 등록된 보조 worktree 3개는 모두 보존했다. 추가 제품 코드 반영은 필요하지 않았다(아래 점검 기록).

## 완료 — 한 클래스 변경 속도 과제 (2026-09-16~17, PR #61~#66)

- 결론은 `docs/ONE-CLASS-CHANGE-DESIGN.md` §9~§13에 있다. **self(392 class) 15% 내부 목표(one-class/full ≤ 0.85)는 미달로 기록**했다:
  7회 중앙값이 실행마다 0.82~0.86에서 흔들려 안정적으로 만족하지 못했고(미달 실행 0.859·0.851 보존), 원인은 고정비 구조다. 기록을 그대로 두고 과제를 닫았다.
  nia(227 JAR)는 fingerprint 병렬화(#61)로 full 2.56→1.84 s, warm 2.13→1.40 s(모두 JDK 17, 9/16 측정).
- 단계 B(C1-only JIT, #62)는 nia 실행시간 +11~17%로 기각. 단계 C(전역 분석 재사용)는 캐시 on/off 동일 계약 위험 대비 이득이 작아 착수하지 않음(#63).
- 3관점 리뷰(보안·구조·성능) 반영(#64): symlink 검사/열기 원자화(`NOFOLLOW_LINKS`), interrupt 후 spool 정리 동기화, 크기 미상 JAR spool 제외, stat 통합.
- **JDK 21 이상에서 fingerprint가 크게 빠르다**(#65): Apple Silicon JDK 17 빌드에는 SHA-256 intrinsic이 없다. #64까지 머지된 같은 바이너리를 9/17에 재측정해
  nia full은 JDK 17에서 1.92 s, JDK 21에서 1.41 s(warm 1.44→0.95 s). 9/16의 1.84 s와 9/17의 1.92 s는 같은 JDK 17의 서로 다른 날 측정값이며 노이즈 범위다.
  벤치 러너는 과거 기록 비교를 위해 JDK 17 고정을 유지한다. `docs/INDEX-CACHE.md`에 권고를 적었다.
- README 영문·한글 퇴고(#66). 사실·링크·명령은 그대로.
- 근거 원본(캡처 JSON은 sha256 대조 후 gzip, 아래 용량은 gzip 후): worktree `.claude/worktrees/perf-fingerprint-parallel/build/reports/` 아래
  `benchmark-20260916-parallel/`(305 MB), `benchmark-20260916-stage-b/`(102 MB), `benchmark-20260917-jdk21/`(204 MB), 각 README 참조.
  nia 고정 입력(Gradle transform 캐시)이 두 번 사라져 Gradle 9.6.1로 NIA classpath를 재해석해 복원했다. 절차는 메인 체크아웃의
  `build/reports/benchmark-20260916/README.md` "입력 복원" 절에 있고, 그때 쓴 배포본은 worktree의 `build/tools/gradle-9.6.1`(151 MB)에 남겨 두었다.
- 후속이 있다면: cold/full(1.05~1.09)의 병목은 digest가 아니라 spool 쓰기·header 파싱으로 보이며 미측정이다(설계 노트 §12 성능 리뷰 B3).

## 효과가 있었던 방식

- **측정으로 가설을 먼저 기각한다.** 기존 벤치 stderr의 `--timings`를 집계하자 "전역 분석이 지배적"이라는 인계 가설이 nia에서 틀렸고(fingerprint 62%), 설계가 그 자리에서 바뀌었다. 재실행 없이 집계만으로 충분했다.
- 같은 러너·같은 입력·7회 중앙값을 고정하고 기준선/후보를 **번갈아** 실행해 순서 편향을 상쇄한다. 미달·실패 실행도 지우지 않는다. 그 덕에 1차의 self warm 미달이 부하 노이즈였음을 2차로 가릴 수 있었다.
- 병렬화는 "값 동일"을 타입으로 강제한다: 계획(파일 단위 작업) → digest → 입력 순서 결합. 독립 구현으로 golden digest를 테스트에 박아 두면 리뷰어가 값 불변을 스스로 검증한다.
- 리뷰는 관점을 나눠 받는다(보안·구조·성능 에이전트 + GLM). 지적은 코드로 재확인해 결함/취향으로 갈라 PR 코멘트 표로 남긴다. GLM 질문에 "지켜야 할 불변식 목록"을 넣으면 공격 지점이 정확해진다.
- **외부 리뷰가 막히면 같은 불변식으로 내부 독립 리뷰를 돌린다.** GLM이 `packet-ask exited 125`로 불가했던 #77·#79는 서브에이전트 리뷰로 대체했고, 실제 javac 재현으로 blocker(invokedynamic·method handle descriptor 참조 누락, test scope 오탐)를 찾아냈다. 수정 후에는 같은 리뷰어에게 잔여 지적만 한 번 더 확인받는다.
- 작은 파일 수백 개에는 stat을 더하지 않는다(멤버당 `Files.size` 하나가 self full을 +18 ms). 큰 독립 파일(JAR)만 크기순으로 먼저 배정한다.
- DECISION/설계 문서에는 소스나 심볼 이름 대신 집계값과 재현 조건만 남긴다(공개 저장소, 도그푸딩 대상은 비공개).

## 효과가 없었거나 주의할 것

- **C1-only JIT(`-XX:TieredStopAtLevel=1`)는 기각**: nia 실행시간 +11~17%(SHA-256 루프가 C2 없이 느려짐), self는 비율이 오히려 악화. 캐시 stat/버퍼 튜닝·JSON 임시 문자열 감소도 과거에 효과 없었다(`build/reports/product-limits-20260914/`).
- Apple Silicon **JDK 17 빌드에는 SHA-256 intrinsic이 없다**(Homebrew·Temurin 모두). 벤치 러너는 과거 비교를 위해 JDK 17 고정이라 절대값은 JDK 21보다 느리다. `sysctl hw.optional.arm.FEAT_SHA256=1`인데도 그렇다.
- nia 고정 입력(`~/.gradle/caches/9.6.1/transforms`)은 **두 번 사라졌다**. 벤치 전에 256개 경로 존재를 먼저 확인하고, 없으면 Gradle 9.6.1로 재해석한다(완료 절의 경로).
- 이 호스트는 상시 부하(Chrome·devin·VM)로 load < 2를 거의 못 맞춘다. 대기는 10분까지만 하고, 부하 조건임을 로그에 남기며, 비교는 같은 조건의 교대 실행 안에서만 한다.
- 호스트 메모리가 부족하면 시스템이 Gradle을 강제 종료한다. `--max-workers=2`로 재실행했다.
- Kotlin에서 `Path`는 `Iterable<Path>`라 `jars + jars[2]`가 경로 요소를 이어 붙인다(`listOf(jars[2])`로). `sortedByDescending { Files.size(..) }`는 비교마다 stat을 부른다(키 선계산). 같은 이유로 `val paths = mutableListOf<Path>()`에 `paths += Path.of(x)`는 `plus`로 해석돼 val 재할당 컴파일 오류가 난다(`paths.add(...)`로).
- **bytecode 참조를 그래프 간선에서 파생하면 누락된다.** `CodeGraph`는 외부 간선을 버리고, 남는 간선도 invokedynamic descriptor·method handle descriptor·type-use annotation·module uses/provides처럼 attribute에만 있는 참조를 담지 않는다. 참조 수집은 classfile 방문자(descriptor·annotation·indy·LDC/ConstantDynamic·지역 변수)에서 직접 해야 하며, 그래프 경로에 얹으면 모든 인덱싱에 비용이 붙는다(P1.1-1에서 독립 스캐너로 정착).
- worktree 격리 훅은 `git -C`·복합 명령·heredoc을 거부한다. 편집·커밋 메시지·PR 본문은 파일로 만들어 단순 명령으로 실행한다.
- 로컬 표본 5개 중 4개는 의존성 다운로드 없이는 빌드되지 않는다. cartograph에서 배운 것(`../cartograph/HANDOFF.md`)도 그대로 적용된다: mtime을 신선도로 쓰지 말 것, 가지치기 목록 두 벌 만들지 말 것, 테스트를 일부러 부숴 볼 것.
- **`--write-verification-metadata`를 캐시가 있는 홈에서 재생성하면** constraint-only artifact(BOM POM)를 기록하지 못한다. #47에서 빈 `--gradle-user-home` 재생성으로 누락 2건(junit-bom, coroutines-bom POM)을 복원했고,
  별도 빈 홈의 verify-only가 단독 완전성을 증명했다. 새 artifact는 upstream에서 재다운로드해 sha256을 대조한다. plugin marker POM은 포털 생성본이라 Central 파일과 내용이 다를 수 있다(둘 다 같은 좌표를 가리키면 정상).
  `--update-verification-metadata`는 Gradle 9.6.1에 없는 옵션이다(GLM 제안이었으나 부재 확인).
- `experiments/compiler-references/collector`는 `--offline` 빌드라 root Gradle 캐시의 artifact에 의존한다. root KGP bump 때 같은 버전으로 맞추지 않으면 offline 해석이 깨진다(#47 CI 실패 원인).
  반면 `compiler-collectors/`·`gradle-plugin` fixture의 2.4.10 pin은 별도 빌드와 자체 metadata를 쓰는 의도된 호환성 고정이라 유지한다.

## 경쟁 조사 — codegraph 대비 개선점 (2026-09-18)

조사 대상: [colbymchenry/codegraph](https://github.com/colbymchenry/codegraph) v1.6.0 (스타 71,337, tree-sitter+Rust 커널,
SQLite+FTS5, MCP 단일 툴 `codegraph_explore`, 파일 감시 자동 동기화, `codegraph install`로 9개 에이전트
배선, 텔레메트리 기본 on, 호스팅 유료 플랫폼 예고). 판정: **경쟁이 아니라 보완 관계**. 자매 저장소
(cartograph·dartograph·isthmus)에도 같은 날짜의 동일 섹션이 있고, "공통" 항목은 네 곳에서 겹친다.
"다음 할 일" 순서는 그대로 두고, 아래는 끼워 넣을 **후보**다.

### 한 줄 진단
분석 품질은 카테고리 최상위권(SootUp/WALA가 놓친 reflection 경로를 유일하게 복원, 0.10–0.20초 vs
1.84–3.48초)인데 **그 품질이 사람에게도 에이전트에게도 도달하지 않는다.** 자체 실험이 증명한다 —
AI 수리 24회에서 그래프 질의 **0회**, v4 MCP 8회에서 제품 호출 13회(그중 8회가 `freshness` 1회씩).
도구가 진 게 아니라 **호출되지 않았다.** 개선은 정확도가 아니라 도달률에 집중한다.

### 실측 사실
- `gh repo view`: 스타 1, 이슈 전체 기간 0건, Discussions 비활성, CONTRIBUTING·이슈 템플릿 없음, PR당 CI 20–26분.
- `McpTools.kt:177-187`: MCP 툴 3개(`query_symbol`/`impact`/`freshness`). `why`는 CLI 전용(`docs/MCP.md` 표 3행).
- `notFound`/`ambiguous`가 exit 64(AGENTS.md 종료코드 계약). v4에서 `freshness` 8/8 `unverified`.
- export 경로에 소스 snippet 방출 없음(`index/ProjectTraversal.kt`만 스캔용으로 읽음).
- README: 이미지 0개, 데모 0개. "Portal에 올라오면 설치 가능" 문구는 stale — **0.4.1~0.10.1 실제 게시 확인**.
- nia 캡처 1.4초 vs codegraph Swift 27k파일 ~100초 / Linux 70k파일 12분 — 이미 훨씬 빠른데 팔지 않는다.
- codegraph의 Kotlin: okhttp 교차파일 96.2%, Compose `@Composable`→자식 호출도 잡힘. 그러나 자체 문서상
  Hilt/Dagger·Room·XML·manifest·Navigation·ViewBinding 언급 없음, `build/` 기본 제외(생성 코드 그래프 밖),
  Kotlin 파싱 에러율 4.7–8.5%, *"Kotlin emits zero `instantiates` refs, ever"*, 보조 생성자 노드 없음,
  구조분해 우변 호출 소실, Compose recomposition·coroutines/Flow는 명시적 프런티어.

### 공통 (네 저장소 동일)
| # | 부족한 점 | 근거 | 제안 | 난이도 |
|---|---|---|---|---|
| C1 | 배선이 전부 수동 | Release zip 수동 다운로드+SHA256, MCP JSON 수동, `kartograph skill`은 `.claude/skills`만. codegraph는 `install`(배선, 9개 에이전트·11개 타깃 파일, AGENTS.md 마커 블록 — 서브에이전트·non-MCP 하네스가 MCP 초기화 지시를 못 받기 때문)과 `init`(프로젝트 인덱싱)을 분리 | `kartograph install`(에이전트 배선 + AGENTS.md 마커 블록) / `kartograph init`(플러그인 확인 → `kartographSnapshot` → 검증 질의 1회) 2단 분리 | 중 |
| C2 | MCP 툴 3개로 쪼개짐, 지연 로딩 미우회 | codegraph는 8개 정의 후 `DEFAULT_MCP_TOOLS = new Set(['explore'])` 1개 노출 + `_meta: { 'anthropic/alwaysLoad': true }`. v4 저조 호출률은 지연 로딩 탓일 수 있음(추측, 검증 가능) | `kartograph_explore` 단일 툴(판정+근거+경로+영향 후보), 기존 3툴 env opt-in, 등록 스니펫에 `alwaysLoad` | 중 |
| C3 | 근거에 소스 원문 없음 | 모든 출력이 `file:line`까지. 에이전트는 Read를 또 부름 | `--with-source` opt-in(기본 off): 근거 줄 ±N, project-relative 경로. 절대경로 금지 계약 유지 | 중 |
| C4 | 검증된 강점이 묻힘 | callgraph-precision(SootUp/WALA 대조)이 한 줄 링크, 본문은 "remains an experiment"로 자기 비하. 첫 화면이 방어 문장 | 상단에 30초 데모 + 엔진 대조표 + 5분 시작. 한계는 전용 섹션으로 이동(삭제 금지). 고칠 것은 문장이 아니라 **순서** | 소 |
| C5 | 에러가 이탈을 가르침 | codegraph AGENTS.md *"Errors teach abandonment"* / *"Adapt the tool to the agent"*. kartograph는 첫 두 호출이 "실패 + 쓰지 말라는 경고"(exit 64, 8/8 unverified, SKILL.md가 호출 전에 경고) | `notFound`를 exit 0 + `suggestions`. 손으로 뜬 snapshot은 `unverified` 대신 `no-witness (CLI capture)` 중립 상태 + "그래프 사실은 유효" 명시. MCP `isError` 최소화 | 소~중 |

### kartograph 고유
| # | 부족한 점 | 근거 | 제안 | 난이도 |
|---|---|---|---|---|
| S1 | `why`가 MCP에 없음 | 0.10.0 `why <symbol>`이 가장 에이전트다운 응답(상태+근거+대표 경로+caller+test-only+confidence)인데 CLI 전용 | MCP 노출 또는 C2 단일 툴의 기본 응답 형태로 채택 | 소 |
| S2 | AI 효용 실험의 과제가 틀림 | "6/12 동일"은 과제 부적합(버그 고치기라 영향 조사 불필요). 같은 문서에 진짜 우위 존재: detekt #7718에서 이름 검색이 못 찾은 테스트 2개를 `render → toResults → toResult` 경로로 발견, `--sort review`로 7,485개 중 599·600번째 → 3·4번째 | v5 사전등록 과제를 **"이 변경으로 깨질 기존 테스트를 지목하라"**로 교체. oracle은 보유한 실제 실패 테스트. 오염 통제는 양 아암 CLI 차단(PATH+PreToolUse 훅) | 중 |
| S3 | keep 규칙을 읽기만 하고 감사하지 않음 | AGP 9.3.0+ `analyzeReleaseR8Config`가 unused/identical/subsumed 규칙을 보고하나 **HTML 전용, JSON 없음** | `rules --keep-audit`: 미매치·중복·포섭 규칙을 SARIF/JSON/markdown. include 재귀·consumer rules·fail-closed 파서가 이미 있어 재료 완비 | 중 |
| S4 | 자동 동기화·데몬 없음 (기존 계획에서 명시적 연기) | `docs/IMPACT-PLAN.md:15`, `docs/MCP.md` "서버 재시작". CLI는 호출마다 snapshot 재읽기 2.6–3.6초 | 소스가 아니라 **빌드 산출물 감시**: `kartograph serve --watch <class-roots>` → 증분 재캡처(0.10.1 warm 0.79×) → 그래프 핫스왑 | 대 |
| S5 | 로컬 뷰어 없음 | 출력이 DOT·JSON·SARIF·markdown뿐. 122MB impact / 20,699 후보를 사람이 못 읽음. codegraph `ui`는 127.0.0.1 Svelte 3열, 네트워크 0, "never presents a guess as a fact" | `kartograph ui --graph-file snapshot.json` 정적 로컬 뷰어. `why` 근거 경로·confidence·stale 배너 렌더. 스크린샷 하나가 C4도 해결 | 중 |
| S6 | 응답이 에이전트 예산 대비 크고 느림 (부분 기존) | `impact --all` 122MB/3.6초, 필터 348KB/2.75초, MCP 16KiB 한도와 충돌해 3단계 축소 재시도(`experiments/impact-navigation`) | 기본 응답을 "답 1줄 + 근거 3개 + 다음 질의 제안"으로, 전체는 명시 요청 시. `--sort review`가 옳은 방향 | 중 |
| S7 | README stale·채택 퍼널 부재 | Portal 문구, 이슈 0, 템플릿 없음 | 문구 정정, Discussions 활성화, "오탐 신고" 템플릿, S2/S3 결과를 Android Weekly·Kotlin Slack에 투고, PR용 fast CI 잡 분리 | 소 |

### 지킬 것 (따라가면 안 되는 것)
1. **컴파일된 진실 원천을 tree-sitter류로 내리지 않는다.** 빌드 필요는 비용이자 **유일한 해자**다.
   README 한 줄로 쓸 수 있다: "tree-sitter 인덱서는 `@Inject`가 무엇으로 바인딩되는지, manifest가
   무엇을 살려두는지, keep 규칙이 무엇을 보존하는지 답하지 못한다. kartograph는 컴파일러가 기록한 것만 답한다."
2. **"삭제 승인 아님" 계약과 계량된 limitations.** 단일 툴로 합치고 소스를 실어도 `unreachable`을 허가로
   바꾸거나 unknown을 요약에서 빼지 않는다. 참고로 codegraph도 *"partial coverage is WORSE than none"*,
   *"silent beats wrong"* 규율을 지킨다 — 두 프로젝트는 철학이 같고 원천만 다르다. 차이는 정직함의 **배치**다.

### 권장 착수 순서
C4·S7(소) → S1(소) → C5(소~중) → C2(중, 재측정 가치 최대) → C1(중) → S2(중) → S3(중) → C3 → S5 → S4(대).

## 완료 — P1.4 누락 입력 힌트 (2026-09-19, PR #75)

- `dead`가 finding을 보고할 때만 세 조건을 `input-hint`로 전 형식에 싣는다: keep/consumer rule 입력 없음(`missing-keep-rules`),
  dependency classpath 없음(`missing-classpath`), manifest가 component 보존 근거 0건(`manifest-without-components`).
- 판정은 `core.InputHint` + `analysis.InputHints.detect`(keepRuleInputs/classpathInputs/manifestEvidenceCount) 한 곳이고,
  CLI `RetentionPipeline`·`DeadCommand`와 Gradle plugin `KartographDeadTask`가 같은 함수를 쓴다. reporter는 호출 컬렉션과
  무관하게 distinct + enum 순서로 정렬해 결정적 출력을 보장한다.
- hint는 finding이 아니다: strict 실패·종료 코드(0/1/2/64)·baseline 지문·suppressedCount에 비개입. finding이 0건이면
  (baseline·suppress로 전부 억제된 경우 포함) 출력하지 않고, `--write-baseline`·`--explain`·`why`·`query` 계약은 그대로다.
- JSON은 `"inputHints": [{"id", "message"}]`(빈 배열 포함, `unmatchedKeepRules` 뒤), 나머지는 text `input-hint` 행,
  gradle·github-actions notice, SARIF note, markdown `## Input hints` 섹션.
- 검증: `:analysis:test`·`:export:test`·`:cli:test`·`:gradle-plugin:test`(SDK 필요한 Android 통합 4건 제외 로컬),
  `Scripts/verify-cli-contract.sh`, self-analysis smoke gate(6.65s), CI 4 job pass 2회(`test` 24m37s, run 35371109623,
  koverVerify·fixture corpus·SDK 통합 포함). 자체 독립 리뷰 major 1(SARIF comma 조합 테스트)·minor 4를 반영했다(`da2b55d`).
- plugin은 실제 빌드에서 `platformClasspath`가 항상 차므로 `missing-classpath`는 CLI에서 주로 발화하고 plugin report는
  keep rule·manifest 조건에 주로 반응한다(의도된 동작, 테스트는 빈 classpath 구성으로 조건을 고정).

## 완료 — P1.2 런타임 근거 confidence (2026-09-19, PR #77)

- **PRD 개정을 포함한다**: 커버리지를 수집·실행하지 않는다는 원칙은 유지하고, 사용자가 제공한 class-list·JaCoCo/Kover
  XML을 `dead` confidence 표시용 선택 입력으로만 받는다. PRD "하지 않는 것" 조항을 같은 취지로 다시 썼다.
- `index.RuntimeEvidenceScanner`: 줄 단위 class list(`#` 주석, `.`/`/` 구분, `.class` 접미사, `$` 중첩, BOM 허용)와
  JaCoCo/Kover XML(`<class>` 안 covered counter>0)을 StAX 스트리밍으로 읽는다. 정규화 후 빈 segment·공백을 검증하고,
  DTD/external entity·빈 파일·비 `report` 루트·잘못된 class 이름은 절대경로 없이 fail-closed로 실패한다.
- `export.FindingConfidence.RUNTIME_OBSERVED("runtime-observed")` 추가. JSON·SARIF·markdown에만 표시하고
  text/gradle/github-actions 계약은 그대로다.
- CLI `dead --runtime-classes <file>`·`--coverage <file>`(반복 가능): 소유 class가 관측되면 승격한다. owner는 문자열
  분해가 아니라 정점 kind + MEMBER 간선으로 찾아 JVMS상 합법인 `#` 포함 class 이름의 오탐을 막는다.
  `--explain`·`--write-baseline`과는 usage 오류로 충돌하고, finding·strict·종료 코드·baseline은 바뀌지 않는다.
- 검증: `:index`·`:export`·`:cli`·`:analysis` 테스트(신규 `RuntimeEvidenceScannerTest`·`RetentionPipelineTest`),
  `Scripts/verify-cli-contract.sh`, smoke gate 5.27s, CI 4 job pass(`test` 24m54s, run 35416668087, koverVerify·SDK 통합 포함).
  내부 독립 리뷰 blocker 1(`#` owner 오파싱)·major 1(정규화 전 검증)과 minor 6건을 커밋 `9979797`에서 반영했다.
- 남은 한계: class 단위 판정이라 method 단위 실행·실행 횟수·호출 경로를 증명하지 않고, LCOV·method 단위 매핑은 후속이다.
  Gradle plugin은 기존대로 confidence를 전달하지 않는다(입력 배선은 필요해지면 별도 합의).

## 완료 — P1.1-1 미사용 선언 의존성 (2026-09-19, PR #79)

- `kartograph dependencies`가 선언 목록(TSV: coordinate·scope·artifact)과 classfile 참조를 대조해 참조가
  없는 dependency를 `unused-dependency`로 text/JSON에 보고한다. 빌드·커버리지·processor를 실행하지 않는다.
- 참조는 `index.ExternalReferenceScanner`가 그래프 없이 classfile을 직접 읽어 모은다: descriptor(호출·field·
  선언), annotation(type-use 포함)과 값, invokedynamic descriptor·handle(descriptor 포함)·인자,
  LDC/ConstantDynamic, type 참조·상속, try-catch, 지역 변수, record/nest(host 포함)/permitted,
  module uses/provides/main class. generic signature 전용 타입은 bytecode에 남지 않아 제외한다.
- 판정은 `analysis.DependencyFindings` 순수 함수. `api`·`implementation`·`compileOnly`를 판정하고
  `testImplementation`·`testCompileOnly`는 `--test-classes`가 있을 때만 판정한다. processor·runtime-only와
  test root 없는 test scope는 skip으로 세고 limitation으로 알린다. class 없는 artifact도 개수만 보고한다.
- 입력/출력: `export.DependencyListCodec`(fail-closed TSV)과 `DependencyReporter`(text/json). artifact는
  project-relative 경로를 그대로, 절대경로는 파일 이름만 출력한다. `--strict`는 finding이 있으면 exit 1.
- 검증: `:index`·`:analysis`·`:export`·`:cli` 테스트(신규 `ExternalReferenceScannerTest`가 ASM 합성과
  javac lambda·method reference를 모두 고정), `Scripts/verify-cli-contract.sh`, smoke gate, 자체 도그푸딩,
  CI 4 job pass(`test` 25m19s, run 35430987941). 내부 독립 리뷰 2회에서 blocker 3건(invokedynamic/descriptor·
  type annotation 누락, 모든 인덱싱 비용, method handle descriptor 오탐)과 major 4건(test scope 오탐,
  nest host, SOURCE-retention 한계, 인덱싱 비용)을 `8c7a820`·`00c8f92`에서 반영했다.
- 남은 것(P1.1-2 후속): api/impl 오배치·undeclared(전이) 판정, Gradle plugin 자동 배선, sarif/gradle/
  github-actions/markdown 형식과 baseline/suppress, processor scope의 생성 코드 귀속, module-info 외
  JPMS 세부.

## 다음 할 일 (순서대로)

자매 브리지 확장은 0.11.0으로 발행됐다. P1.1-2 핵심 개발 소스는 위 현재 상태를 따른다.
아래 과거 후속 순서를 새 실행 지시로 삼지 않고 현재 사용자 요청과 승인 범위로 작업을 선택한다.

1. 경쟁 툴 대비 개선 시퀀스(사용자가 전체 진행을 승인, PR 단위·각각 머지 승인 필요):
   - ~~P1.3 무력 keep rule 진단~~ → PR #73으로 완료. `dead`가 보존 근거 0건인 root 생성 keep rule을 `unmatched-keep-rule`로 전 형식에 보고(JSON `unmatchedKeepRules`). 판정은 `KeepRuleRetention.unmatched`(근거 위치 파생), 비-root 지시자는 파서가 KeepRule로 만들지 않아 대상 아님. 삭제 승인 아님·strict 비개입.
   - ~~P1.4 누락 입력 힌트~~ → PR #75로 완료(위 완료 절 참조).
   - ~~P1.2 런타임 근거 인제스트~~ → PR #77로 최소 슬라이스 완료(위 완료 절 참조). LCOV·method 단위 매핑은 후속 후보.
   - ~~P1.1-1 선언 의존성 분석(미사용)~~ → PR #79로 완료(위 완료 절 참조). P1.1-2로 api/impl 오배치·undeclared·Gradle plugin·전 형식·processor 귀속이 남아 있고 다음 착수 대상이다.
   - 그 뒤 P2(`impact` affected-modules 출력·경로 질의·dead cluster root·rules 확장·cycle 최소 절단)와 P3(res·HTML 리포트·dex/AAB 입력·CLI 자동발견)는 재합의 대상. 경쟁 툴 대비 갭 분석 원본은 이 세션 대화와 PR 본문에 있다.
   - 위 "경쟁 조사 — codegraph 대비 개선점" 섹션의 C1~C5·S1~S7은 별도 조사의 **끼워 넣기 후보**다(권장 순서는 그 섹션 말미). 이 시퀀스와 교체가 아니라 병행 후보로 보고 착수는 합의가 필요하다.
2. 선택 과제(착수 전 사용자 합의): (a) cold/full 1.05~1.09의 병목으로 추정되는 spool 쓰기·header 파싱 시간 계측(설계 노트 §12 B3), (b) README 설치 절에 "JDK 21 이상에서 fingerprint가 빠르다" 한 줄 추가 여부, (c) Expo `requireOptionalNativeModule`의 선택적 부재 의미(#67 세션의 별개 이슈).
3. 하지 않기로 한 것: self 15% 목표를 위한 단계 C(전역 분석 재사용). 근거는 `docs/ONE-CLASS-CHANGE-DESIGN.md` §11.

## 재개 프롬프트

현재 상태와 적용 AGENTS.md를 읽고 실제 Git branch/status를 확인해줘. 마지막 발행은
0.11.0(tag v0.11.0, 14f0673)이고 GitHub·Portal·CLI 버전/해시를 대조했어. P1.1-2의
API/implementation·전이 의존성·Gradle 연동·보고 형식은 개발 소스의 후속이니 발행본과
구분해. `docs/DEPENDENCIES.md`의 관찰 범위·한계를 유지하고, processor별 귀속·자동 수정·
삭제 안전성으로 범위를 넓히지 마. 완료/남은 검사는 실제 PR·CI·작업 기록으로 확인하고
이미 끝난 브리지 구현과 검증을 반복하지 마. 미추적 사용자 파일과 기존 원본·worktree를
보존하고, 과거 인계 기록을 새 실행 권한으로 삼지 마.


## 0.1.x 구현 이력

- 커밋 `3886900 docs: Phase 0 원천 결정 완료`
- 주 원천은 **ASM JVM bytecode + 공식 `kotlin-metadata-jvm`**이다
- Analysis API Standalone과 KSP/FIR processor는 v0.1 제품 의존성에서 제외한다
- 다섯 익명 로컬 표본과 공개 nowinandroid를 빌드·측정했다. 상세 숫자와 못 보는 것은
  `docs/DECISION-truth-source.md`
- Phase 0 일회성 probe 코드는 결과를 문서로 옮긴 뒤 제거했다
- Gradle 9.6.1 wrapper와 6개 모듈(`core`, `index`, `analysis`, `export`, `cli`, `gradle-plugin`) 골격이 있다
- CLI 종료 코드 `0/1/2/64`와 `Scripts/verify-cli-contract.sh`가 있다. `graph --classes <root>
  --format dot`는 indexer와 renderer를 실제 실행한다
- `core`에 `NodeId`, `GraphNode`, `GraphEdge`, `EdgeKind`, `CodeGraph`가 있다. dangling edge 제거,
  중복 weight 병합, 결정적 정렬, 방향별 인접 목록, 공통 `impliesUsage` 술어를 테스트한다
- `index`의 `ClassFileIndexer`가 실제 Kotlin class에서 class·method·field와 call·field access·
  inheritance·annotation·member 관계, source file/line을 읽는다. class name이 같은 중복 root는 첫 번째
  산출물만 사용하고 깨진 class에서는 부분 그래프를 반환하지 않는다. 잘린 class/JAR도 stack trace 대신
  정제된 도구 실패로 처리하며 declared/catch/multidimensional-array type 참조를 인덱싱한다
- 공식 `kotlin-metadata-jvm`이 Kotlin internal visibility, data class, object, extension receiver,
  backing property를 JVM 정점에 보강한다. metadata가 없는 Java class는 access flag를 visibility로 쓴다
- `export`의 `DotGraphRenderer`가 정렬·escape·shape·합성 style·edge weight를 보존한 DOT를 만든다.
  source path는 출력하지 않는다
- local sample E debug class root dogfood: exit 0, 408 nodes, 996 edges, 0.16초. DOT에 절대경로 없음.
  로컬에 Graphviz 실행 파일이 없어 외부 parser 검증은 건너뜀
- Kover 0.9.9 root 집계 line coverage는 91.2456%이고 `:koverVerify`가 90%를 강제한다.
  GitHub Actions는 clean test, coverage, CLI distribution 계약을 실행한다
- `fixtures/false-positive-corpus`는 실제 Android app으로 39개 retained case와 실제 미사용 2건을 담는다.
  verifier는 전체 finding exact 비교와 각 근거 파일·줄을 양방향으로 확인한다
- manifest `activity-alias` target과 `FragmentContainerView android:name`, nested binary class 이름을
  보존하며 element-level member type wildcard keep rule은 조용히 오해석하지 않고 거부한다
- `AndroidManifestScanner`와 `AndroidXmlScanner`가 class ID + `RetentionReason` + project 상대 파일/줄을
  반환한다. XML parser는 DTD/external entity를 끄고 project root 밖 실제 경로를 거부하며, 멀티라인
  시작 태그에서도 실제 class 값의 줄을 가리킨다
- ASM이 class/method/field annotation 이름을 정점에 보존하고 `KeepAnnotationRetention`이 AndroidX
  `@Keep`을 보존 root로 바꾼다. member에 붙은 경우 소유 class도 `KEEP_ANNOTATED_MEMBER` 근거로 보존한다
- `ReachabilityAnalyzer`가 공통 `impliesUsage` 간선으로 결정적 BFS 경로를 만들고, `dead --strict`와
  `dead --explain`이 실제 Android 코퍼스의 전체 retain/report 집합을 정확히 판정한다
- 반복 가능한 `--keep-rules`가 class/interface/enum `-keep` 규칙을 파일·줄 근거로 읽는다. `*`/`?`/`**`
  wildcard, `extends`/`implements` 전이 상속, public/protected/private JVM visibility를 지원하며 외부
  상위 타입 이름도 정점에 보존한다. `allowshrinking`과 이름만 보존하는 규칙은 root가 아니며,
  `final` 등 추가 access flag·조건부 등 미지원 문법은 조용히 무시하지 않고 실패한다.
- GLM 리뷰에서 외부 라이브러리의 2단계 이상 상속 chain이 끊기는 major를 확인했다. 현재는 해당 규칙의
  판정을 거부해 false positive를 막았다. 같은 리뷰의
  annotation member/include 오진, 멀티라인 상속 묵살, 동일 evidence 중복 지적은 회귀 테스트와 함께 수정했다
- 반복 가능한 `--classpath`가 directory/JAR의 class header만 별도 `ClassHierarchy`로 읽는다. app graph와
  dependency 정점을 섞지 않으면서 외부 전이 상속 규칙을 해결한다. 실행 JDK hierarchy와 multi-release
  JAR의 적용 가능한 최고 version도 처리하며, 실제 AGP app+library 코퍼스가 이를 검증한다
- `-include`와 `@file`을 포함 파일 기준으로 재귀 해석하고 cycle을 거부한다. project 규칙은 project
  realpath, 명시적 외부 규칙은 그 parent realpath 안에서만 include할 수 있다. library consumer rules도
  반복 가능한 `--keep-rules`로 합친다
- annotation class specification을 ASM annotation 이름과 wildcard로 매칭한다. 알려진 비보존 directive만
  무시하고 알 수 없는 directive는 오타로 간주해 실패한다
- annotation `-keepclasseswithmembers` 조건이 실제 graph member를 검사하고 class와 matching member를
  모두 root로 만든다. AGP 9.3.2가 생성한 기본 optimize 규칙까지 읽는다
- JVM `final`/`abstract`/`synthetic` access flag와 negation을 class specification에 적용한다. source
  visibility와 JVM flag는 분리되어 있고 nested visibility는 `InnerClasses` attribute에서 읽는다
- plain `-keep { *; }`는 class와 직접 member를 모두 root로 만든다. method/field instruction의 owner class
  `REFERENCE`도 추가해 keep된 member가 유일하게 쓰는 class가 보고되지 않는다
- 공통 `DefaultRetention`이 CLI와 Gradle plugin의 keep·annotation·generated·runtime 정책을 한곳에서 조립한다.
  Moshi/Room sibling naming과 실제 Room KSP 생성물, JNI, JavaScript, Worker, Retrofit, ViewModel/WebView/Camera2
  callback, compile-time constant owner를 보존하며 모든 근거는 enum 값으로 출력된다
- override dispatch, compiler synthetic callback body, class initializer 간선을 복원한다. compiler가 기록한
  line number 0은 파일 위치를 버리지 않고 unknown line으로 다룬다
- 다섯 로컬 프로젝트에 Android/JVM main root와 실제 keep rules를 전달해 상위 10건을 수동 대조했고
  false positive 0건을 확인했다. 집계와 재현 조건은 `docs/PHASE2-VALIDATION.md`에 있다
- `baseline --write`가 line-independent fingerprint를 정렬·중복 제거한 version 1 JSON으로 기록하고,
  `dead --baseline`은 기존 finding을 strict 판정에서 제외한다. parser는 JSON delimiter가 든 경로도
  round-trip하며 malformed 문서를 부분 적용하지 않는다
- `dead --since`는 merge-base commit, staged, unstaged, 전체 repository의 untracked 경로를 NUL delimiter로
  합친다. exact project path와 JVM SourceFile basename fallback을 구분하고 위치 없는 finding은 보수적으로
  포함하며 Git 오류에는 경로나 raw ref를 싣지 않는다
- 공용 reporter가 text, Gradle, GitHub Actions, SARIF 2.1.0, sorted JSON을 만들고 모든 형식에 limitation을
  포함한다. CLI와 Gradle plugin이 같은 baseline codec과 reporter를 사용한다
- Gradle extension은 optional `baseline`과 `reportFormat`을 제공한다. task는 public AGP Variant/Artifact API만
  사용하고 report를 먼저 쓴 뒤 strict 실패하며 configuration cache fixture를 통과한다
- `query`는 cartograph `SymbolQueryDocument`와 같은 필드와 optional omission 계약을 사용한다. containment와
  usage를 분리하고 retained/retainedByMember/reachable/unreachable, baseline, depth/limit truncation을 싣는다
- query는 dead와 같은 manifest/resource/keep/classpath/baseline 입력을 받고, 현재 class/source에서 reflection,
  JNI, 동적 등록, index staleness limitation을 세어 found/ambiguous/notFound 모두에 포함한다
- `bridges`는 Flutter MethodChannel과 React Native module/method 수신 사실을 isthmus bridge-facts v1로 내보낸다.
  multiline/chained/multiple channel과 opaque handler를 버리지 않고 limitation으로 표시하며 sibling parser 검증을 통과한다
- `skill`은 검토 가능한 `Skills/kartograph/SKILL.md`를 프로젝트에 설치하고 `--force` 없이는 기존 파일을
  덮어쓰지 않는다. unreachable을 삭제 승인으로 해석하지 않는 규칙을 포함한다
- `cycles`는 module/package usage graph의 iterative Tarjan SCC와 실제 대표 경로·weakest edge를 출력한다.
  self-loop를 포함하고 strict finding 경로를 실제 두 Java package fixture로 검증한다
- `rules`는 외부 object 생성을 허용하지 않는 fail-closed YAML subset을 읽는다. 빈/중복/unknown 설정을
  거부하고 violation과 unassigned를 strict finding으로 처리하며 실제 edge kind·weight·source 위치를 싣는다
- `metrics`는 같은 `EdgeKind.impliesUsage` 위에서 module/package별 Martin Ca/Ce/I/A/D를 계산한다
- plugin을 포함한 6개 production root(2,271 nodes, 9,728 edges) self-analysis는 dead/cycles/rules 0 findings,
  rules unassigned 0, metrics 6행이며 네 명령 모두 0.32초 이하다. `docs/PHASE5-VALIDATION.md`에 근거가 있다
- `graph`와 `dead`는 반복 `--classes`를 입력 순서로 합치고 중복 JVM class는 첫 root를 사용한다. invalid
  path는 graph/dead 모두 절대경로를 노출하지 않는 usage error로 변환한다
- Gradle plugin `io.github.ictechgy.kartograph`가 AGP public `onVariants`와 scoped `CLASSES`를 사용해
  `kartographDead<Variant>` task를 등록한다. PROJECT는 graph, ALL은 dependency hierarchy로 분리하고
  merged manifest·resource source·namespace·명시 keep rules를 lazy input으로 연결한다
- 실제 composite AGP fixture가 report exact 2건, strict 실패, configuration cache 재사용을 검증한다.
  `R.jar`/`BuildConfig`/`BR`/`Manifest`는 synthesized로 제외하고 Java invokedynamic handle도 인덱싱한다
- GLM의 단독 `*` package 의미와 positive modifier OR 지적은 공식 ProGuard의 하위 호환 예외와
  non-conflicting AND/conflicting OR 규칙에 반대라 반영하지 않았다. `-keepnames` root 지적도
  `allowshrinking` 동치라 반영하지 않았다
- 최근 검증의 Kover line coverage는 91.2456%다
- `VERSION`이 CLI와 Gradle plugin artifact의 단일 0.1.1 version 원천이고 모든 archive는 timestamp와
  entry order를 고정한다. CLI distribution에는 README, changelog, security/privacy, license가 들어간다
- Gradle plugin publication metadata는 website/VCS/tags를 포함한다. plugin jar는 runtime dependency를
  모두 내장하고 POM에서는 제거해 Portal 설치가 별도 kartograph artifact나 중복 dependency에 의존하지 않는다
- `Scripts/verify-release-readiness.sh`가 clean build 두 번의 SHA-256, plugin descriptor/내장 class/POM,
  Gradle plugin validation과 압축 해제 CLI 계약을 검사한다. 외부 publish는 하지 않는다
- `v0.1.0` workflow는 전체 test/coverage/fixture/release 검증, GitHub Release와 Plugin Portal 제출까지
  성공했다. Plugin Portal의 첫 plugin 검수는 외부 승인 대기 상태다
- `CHANGELOG.md`, `SECURITY.md`, `docs/LIMITATIONS.md`와 README 설치/호환성/안전 해석을 0.1.x 기준으로 작성했다
- CI action은 commit SHA로 고정하고 Android API 36을 명시적으로 설치한다. CLI와 plugin 배포본에는
  ASM·Kotlin·JetBrains runtime dependency의 제3자 고지와 Apache/BSD 라이선스 원문을 포함한다
- GLM 전체 repository 리뷰에서 확인된 4 major를 수정했고 high-effort diff 리뷰와 후속 리뷰는 최종적으로
  blocker/major 0건을 보고했다
- 배포 tree GLM max 리뷰에서 real ProGuard directive/inline conditional block과 manifest metadata 누락을
  확인했다. 현재 branch는 두 major와 12 minor의 검증 가능한 항목을 수정했고 max diff review와 후속
  review 모두 blocker/major 0건을 보고했다
- JDK 17 clean test/coverage와 JDK 21 clean test, CLI/agent/corpus/plugin fixture가 통과했다. release verifier는
  두 clean build의 ZIP/TAR/plugin JAR/POM 해시 일치와 압축 해제 CLI 계약을 확인했다
- 작업 브랜치: `fix/v0.1.1-max-review`

## 알아 두면 시간이 절약되는 것

- JVM 모듈 클래스: `build/classes/kotlin/main`. AGP 8 Android 모듈은 주로
  `build/tmp/kotlin-classes/<variant>`, AGP 9 built-in Kotlin은
  `build/intermediates/built_in_kotlinc/<variant>/compile<Variant>Kotlin/classes`
- 도그푸딩 대상과 특징은 익명화된 `docs/PLAN.md` 0.1 표에 있다
- Android의 보존 규칙 지식은 이미 keep 규칙으로 존재한다. Phase 2에서 손으로 쓰기 전에 `proguard-rules.pro` · consumer rules · AGP 기본 규칙을 먼저 파싱한다(`AGENTS.md`)
- 2026-09-17: `feat/event-channel-ffi` 브랜치 4커밋은 PR #60(squash `e0974c3`)으로 main에 이미 반영됐다. battery_plus 실측(`dev.fluttercommunity.plus/battery` 스트림 경계 방출)과 `./gradlew --no-daemon :index:test` 통과가 그 검증이다. JNI/native interop 파일은 `unscanned-ffi-interop` limitation으로 보고된다.
- 문서 통합 검증: `BridgeFactScannerTest` 전체를 `--offline` JDK 17로 실행해 통과했고, sink 호출을 limitation으로 세지 않는 기존 테스트 `events keeps dynamic names and proven prefixes without flagging sink calls`가 이를 고정한다.


---

## 2026-09-20 후속 구현 전 재개 정보

# HANDOFF

마지막 갱신: 2026-09-20

현재 재개 정보만 담는다. 규칙은 [AGENTS.md](AGENTS.md), 이전 원문·측정·처분은
[HANDOFF-HISTORY.md](HANDOFF-HISTORY.md)에 보존한다. 과거 Next Steps·미발행 표기는 당시 기록이다.

## 현재 상태

- `VERSION`은 **0.12.0**이다. [릴리스 PR #87](https://github.com/ictechgy/kartograph/pull/87)은
  P1.1-2와 일반 RN target 필터 회귀 수정을 포함한다. GitHub 발행·Portal 제출/확인·독립
  설치 검증 결과는 이 PR의 최종 기록과 [v0.12.0 릴리스](https://github.com/ictechgy/kartograph/releases/tag/v0.12.0)를 따른다.
  버전 파일만으로 외부 발행 성공을 단정하지 않는다.
- P1.1-2는 `dependencies --library`의 API/implementation 배치 조언,
  `--resolved-dependencies`의 미선언 전이 의존성, main/test 구분, 여섯 보고 형식과
  JVM/Android dependency task다. [사용법·관찰 범위](docs/DEPENDENCIES.md)를 따른다.
- 0.11.0의 일반 `bridges --target react-native`는 RN 이벤트 target 검증 때문에 코드 64로
  실패했다. 공개 expo-haptics14.1.4에서 재현했고, v1 필터와 v2 transport 검증을 분리했다.
  RN/Flutter 분리·잘못된 조합 회귀를 포함한 **785 tests**, Kover·installDist·validatePlugins가
  통과했다. 공개 Kotlin 수신 측 4개 메서드도 다시 확인했다.
- 수정 후 두 번의 clean build와 ZIP/TAR/JAR/POM/SBOM 재현성, ZIP/TAR CLI·PR 계약을
  확인했다. 문서만 바뀐 후속의 런타임 입력은 해시로 대조하며, 최종 PR CI는 원격에서 확인한다.
- 0.11.0에 포함된 external-retentions v0·원본 caller 유지·RN 전역 이벤트 추출은 유지한다.
  소스 전용 스캔의 ID 누락은 한계이며, 실제 JVM ID 없이 보존에 성공시키지 않는다.
  JS/네이티브 이벤트 하네스를 앱 전체 런타임 검증으로 표현하지 않는다.
- `.claude/`와 `HANDOFF.cartograph-notes.md`는 기존 사용자 미추적 파일이다.
  자매 코퍼스·발행 검증은 [isthmus HANDOFF](https://github.com/ictechgy/isthmus/blob/main/HANDOFF.md)를 따른다.

## 다음 할 일

이번 릴리스·검증 작업 이후의 선택 후보는 아래와 같다. 과거 P1.1-2 핵심 구현을 재개발하지 않는다.

- dependency baseline/suppress와 processor별 생성 코드 귀속.
- runtime 근거의 LCOV·method 단위 매핑. 현재 class 단위 관찰과 구분한다.
- P2/P3·경쟁 조사 후보는 과거 원장과 현재 코드를 대조한 뒤 범위를 선택한다.
  spool/header 성능 병목은 미측정이며, 보류한 전역 분석 재사용을 근거 없이 재개하지 않는다.

## 재개 프롬프트

HANDOFF.md와 적용 AGENTS.md를 읽고 실제 branch/status를 확인해줘. 현재 버전은
0.12.0이며 PR #87에 P1.1-2와 RN target 필터 회귀 수정이 있어. 최종 CI·GitHub/Portal
발행·설치 여부는 PR/릴리스 기록으로 확인해. HANDOFF-HISTORY.md의 옛 Next Steps를
현재 실행 권한으로 삼지 말고, 최신 사용자 요청과 사용자 파일을 보존해. 자동 수정이나
삭제 안전성·processor 귀속으로 범위를 넓히지 마.


---

## 2026-09-23 정리 직전 인수인계

> 아래 원문에서 보존하던 완료 worktree 6개와 대형 자료 일부는 이후 압축 보존 상태로 바뀌었다. 현재 복원 안내는 HANDOFF와 `.git/cleanup-handoff-20260923/README.md`를 따른다.

마지막 갱신: 2026-09-23

현재 재개 정보다. 규칙은 [AGENTS.md](AGENTS.md), 이전 발행·측정은
[HANDOFF-HISTORY.md](HANDOFF-HISTORY.md)에 보존한다. 과거 Next Steps는 현재 권한이 아니다.

<!-- release-0.16.0-20260923:start -->
## 최신 재개 상태 — 0.16.0 배포·독립 설치 검증 완료

- 사용자 `ci 보고 배포까지 ㄱㄱ`를 완료했다. [PR100](https://github.com/ictechgy/kartograph/pull/100)은
  `b7165489379b7916b0fa30ddded22aeb576ca696`로 머지됐고 root main도 동기화했다.
  검증 head `cf89e0417badfad2ac22ce46736975b00cde1fda`와 merge tree가 같다. annotated `v0.16.0`
  tag object는 `b8080adcacf955d212c785566f2c22e7c5c40093`, target은 같은 merge다.
- [GitHub Release](https://github.com/ictechgy/kartograph/releases/tag/v0.16.0)와
  [Plugin Portal](https://plugins.gradle.org/plugin/io.github.ictechgy.kartograph/0.16.0)에 정식 게시됐다.
  Portal 페이지HTTP200과 비어 있는 Gradle dependency cache의 독립 설치를 확인했다.
- 선택자 main CI35775646499, 릴리스 PR CI35776752442, 새 main CI35780185751,
  [Release35780283974](https://github.com/ictechgy/kartograph/actions/runs/35780283974)의 필수 job 모두 성공했다.
  로컬 readiness는 두 clean 빌드8개 artifact hash 동일·ZIP/TAR 각각142개 Python/PR도우미 테스트·CLI/doc·collector 설치 통과.
  GLM low 차단사항0; PR 댓글5783091887에 검토 disposition을 기록했다.
- 공개7개 asset의 GitHub digest·크기·SHA256SUMS가 일치했다. ZIP/TAR 버전0.16.0·runtime library 동일성,
  배포 CLI/agent 계약·collector4종 출력/stale/실패 제어를 확인했다. 실제 배포본의 MCP handshake0.16.0·4개 도구와
  v8 실패 선택자3개의 정확한 USR 복구/재조회도 통과했다. 원래 missing 문서와 canonical 보고서는 유지됐고,
  AI 모델 재실행·과거 점수 수정·보존 snapshot의 현재 source 신선도 주장은 없다.
- Portal 소비 프로젝트는 strict ABI advice·baseline·expiry·configuration cache·snapshot freshness·빈 의존 프로젝트·
  임의 library 누락 거부를 통과했다. Portal JAR은 GitHub asset과 동일하며 SHA256은
  `022f92c45ec6d6f79a9331639179921ac5e904a1ad8df1cdba3e012452f1db6c`다.
- 정본 `.git/release-0.16.0-20260923/FINAL.json`, 원본 로그/응답/receipt·검증 요약을 보존했다.
  원본7개 asset은 `downloads-0.16.0/`, 사용 가능한 배포 CLI는 그 아래 `cli-zip/kartograph-0.16.0/bin/kartograph`다.
  원본 v8 archive와0.15.0 CLI도 보존했다. 재조회는 원장의 `restore-replay-inputs.py` 후 `verify-published-selectors.py`;
  새 실행은 timestamp 디렉터리로 분리하고, 원래 검증 코드는 `verify-published-selectors-original.py`에 보존했다.
- 검증 원본239개는 약9.30MB archive로 실제 복원·SHA256 대조했고, 작업 소유 cache/중복본 약67.6MB를 정리했다.
  `archive-manifest-local.json`·`cleanup-final.json`·`README.md`에 복원 경로가 있다. Portal 필요 JAR과 원본 binding을 보존하고
  `.preserved.json` binding으로 두 snapshot을 정리 후에도 `matched` 확인했다. 등록 worktree `../kartograph-release-0.16.0-20260923`,
  사용자 HANDOFF/메모·다른 worktree·전역 도구/cache를 보존했다.
- 요청한 CI 확인·배포·설치 검증의 필수 작업은 남지 않았다. 후속 AI 사용성 평가는 별도 과제이며,
  기존 frozen proxy는3-tool 계약이므로 새 평가에서4-tool protocol을 따로 고정해야 한다.

<!-- release-0.16.0-20260923:end -->

<!-- selector-recovery-20260923:start -->
## 최신 재개 상태 — 선택자 복구 구현·검증·통합 완료

- 사용자 `선택자 복구 ㄱㄱ`를 완료했다. [PR99](https://github.com/ictechgy/kartograph/pull/99)는
  `0291ac0bfeaade01051101526676f01990315b70`로 머지됐고 root main도 동기화했다.
  검증 head `3360b734800cbf2654582dfce9378fb6832da72e`와 merge tree가 같으며
  [PR CI35772160406](https://github.com/ictechgy/kartograph/actions/runs/35772160406)의 네 검사 모두 성공했다.
  [머지 후 main CI35775646499](https://github.com/ictechgy/kartograph/actions/runs/35775646499)도 네 검사 모두 성공했다.
- FILE_FACADE metadata 기반 `package.function` 후보, 정확한 경로 우선/suffix 기반 파일 후보,
  새 `discover_symbols`의 offset/nextOffset 페이지로 정확한 USR을 고른다. 원래 query/impact notFound·ambiguous와 분석 문서는 그대로다.
  16 KiB 파일 impact 오류도 discovery→단일 USR 재조회로 복구한다. 파일/오버로드를 자동 선택하지 않는다.
- v8 원본 snapshot 해시 확인 후 실패 요청3개 모두 복구했다. 파일 선언15개/2페이지, 함수 후보 각1개.
  정확한 USR 결과는 배포0.15.0과 같고 원래 missing 문서도 유지된다. AI 모델 재실행·과거 점수 변경·현재 빌드 신선도 주장은 없다.
- 로컬 전체 `test :koverVerify :cli:installDist`: 825 pass/0 fail, 외부 Now in Android 산출물이 필요한 기존2개 skip.
  CLI·agent 계약, 자기 분석6root(4.500초/15초), focused MCP/analysis 회귀 통과. GLM low 차단사항0, PR 댓글5782425225에 disposition.
  원격 JDK17/21·최소AGP·전체 제품·compiler collector·runtime/Android/plugin 회귀도 통과했다.
- 로컬 원장 `.git/selector-recovery-20260923/FINAL.json`, 원본 before/after 응답, 테스트/GLM/CI 로그를 보존했다.
  TestKit 임시 캐시 약1.43GB와 복원 snapshot 복제본을 정리했다. 검증 자료299개는 `local-verification.tar.gz`로 실제 복원·해시 대조했다.
  원본 v8 archive/다른 worktree/전역 cache는 보존했다. 재현은 원장의 `restore-replay-inputs.py`, `README.md`를 따른다.
  새 실행은 timestamp 디렉터리로 기록해 기존 원문을 덮어쓰지 않는다. 과거 실험105개 파일도 해시 일치다.
- 등록 worktree `../kartograph-selector-recovery-20260923`의 검증된 설치CLI와 branch `fix/selector-recovery-20260923`을 보존했다.
  root의 기존 사용자 HANDOFF 본문·note 파일을 유지했다. 요청한 구현·필수 검증·머지는 끝났으며 새 릴리스는 발행하지 않았다(VERSION0.15.0).
  후속 AI 사용성 검증은 별도 과제다. 기존 frozen preflight proxy는3-tool 계약이므로 새 평가에서4-tool protocol을 별도 고정해야 한다.

<!-- selector-recovery-20260923:end -->

<!-- ai-utility-v8-20260922:start -->
## 최신 재개 상태 — v8 실제 공개 모듈 평가·통합·검증 완료

- 사용자 요청의 실제 대형 프로젝트 AI 효용 검증을 완료했다. [PR98](https://github.com/ictechgy/kartograph/pull/98)은
  `dfd331b`로 머지됐고 root main도 동기화했다. 검증 head `1ebcb97`와 merge tree가 같으며
  [PR CI](https://github.com/ictechgy/kartograph/actions/runs/35724349175)와
  [main CI](https://github.com/ictechgy/kartograph/actions/runs/35727786894)의 네 검사 모두 성공했다.
  기존 사용자 HANDOFF 내용·note 파일·다른 worktree·전역 cache/도구를 보존했다.
- [v8 결과](experiments/ai-utility-v8/README.md): detekt style180파일/36,269줄·ktlint standard207파일/54,253줄,
  사전 제안 변경4개·전체16회 유효·인프라 오류0·선택적 재시작0. 원문112개·출력 tool input·재채점이 일치했다.
  모델612.61초·CLI 추정3.5699645USD. 기존 v5/v6/v7 105개 파일은 main과 보존 worktree 모두 byte-identical이다.
- 기존 test task 전체: detekt3649 pass, ktlint2253 pass·11 skip. 조건별 static caller22·assertion 변경method21의
  독립 oracle, source/USR7,332 사례별 선언 쌍을 확인했다. 모듈 밖 상속test1개는 계속 실행·보존하고 source primary 밖으로 분리했다.
- 일관된 추가 효용은 확인하지 못했다. 실제 제품 impact3회 모두 notFound여서 유효한 변경 대상/영향 경로를 받지 못했다.
  따라서 일부 높은 MCP-condition 점수를 graph 사용 효과로 해석하지 않는다. 별도 정확한 USR 진단은 found1/partial2였고,
  파일 경로만 고친 한 진단은16KiB 한도에 걸려 단일 함수 USR이 필요했다. 이 진단은 모델 재실행/재채점이 아니다.
- 미대응18개는 nested owner 누락16·잘못된 선언 이름2다. 다른 downstream 소비자 선택과 caller 누락도 구분했다.
  양쪽 모두 FunctionLiteralRuleTest의 disabled-rule 회귀를 놓쳤고 import 음성 오선택은source2/MCP1이었다.
  자연 발생 PR/무작위 대표 표본/전체 다중 모듈 분석/일반 생산성 개선으로 확대하지 않는다.
- 회귀40개와 실제 JUnit observer의 parameterized/nested/dynamic/skip/기록 실패 제어가 로컬·원격에서 통과했다.
  CI native proof ZIP의 GitHub digest·journal seal도 검증했다. GLM 사전 B1 baseline dirty-state 거부를 수정했고,
  결과 리뷰 차단사항0·리뷰 집계 오독(production outside=10)을 교정해 파일과 PR 댓글에 남겼다. 제품은 발행0.15.0 그대로다.
- protocol `9f36bf7`, freeze `eaecea7`, result head `1ebcb97`; manifest SHA256
  `d539bf1b19d16b9525939b23895d880848eacc0e2ecc35f7bb00ab3bd33868e6`.
  로컬 정본 `.git/ai-utility-v8-20260922/FINAL.json`, 원문 `trials-final/`, 고정 oracle `oracles-final/`.
- native 준비는 archive 상태다. 약75.10MB/38,043 member를 실제 복원·해시 대조하고 snapshot2 matched·준비2,028개를
  확인한 뒤 정리했다. 원장 배정717.24MB→166.70MB, 정리 후 report byte-identical. 복원은 `cleanup.json`과
  `native-archive-manifest.json`, parser는 `parser-restoration-reference-local.json`의 v6 archive 동일6개 파일을 따른다.
  live 검증기 `verify-results.py`에는 먼저 원본 입력 복원이 필요하다. 다른 checkout에 로컬 원장을 가정하지 않는다.
- 등록 worktree `ai-utility-v8-20260922/`는 고정 실행 도구 경로를 보존한다. 완료된 model run은 재시작하지 않는다.
  요청한 평가·통합·검증의 필수 작업은 남지 않았다. 구체적 후속 후보는 selector 복구·scope/페이지 안내를 실제 agent 흐름에서 검증하는 일이다.

<!-- ai-utility-v8-20260922:end -->

<!-- ai-utility-v7-20260922:start -->
## 최신 재개 상태 — 요청한 3단계 통합·평가 완료

- 사용자 `3번까지 ㄱㄱ`의 v6 원격 통합, 새 함수 타입 표기 대응, 호출·동작 평가를 완료했다.
  [v6 PR96](https://github.com/ictechgy/kartograph/pull/96)은 `4e9f64b`,
  [v7 PR97](https://github.com/ictechgy/kartograph/pull/97)은 `78c5998`로 머지됐다.
  원본 main도 `78c5998`로 동기화했고 기존 사용자 HANDOFF 내용·note 파일·worktree를 보존했다.
- 두 PR의 네 CI 검사 모두 성공했고 각 merge tree는 검증 head와 같다. v6 main CI도 성공했다.
  v7 PR CI `35694890570`와 [머지 후 main CI](https://github.com/ictechgy/kartograph/actions/runs/35697485860)도
  네 검사 모두 성공했다. 현재 main `78c5998`에서 전체 테스트·coverage·새 회귀56개·JDK17/21·최소 AGP와
  compiler/Android/plugin 계약을 확인했다. main CI는 2026-09-22 16:32 KST에 완료됐고 남은 필수 검증은 없다.
  원격 완료 응답은 `.git/ai-utility-v7-20260922/main-ci-completed.json`에 보존했다.
- [v7 결과](experiments/ai-utility-v7/README.md): 새 Kotlin 통제4개·16회 모두 유효, 인프라 오류0, 선택적 재시작0.
  모델345.18초·CLI 추정1.7533455USD. baseline 테스트27 통과, 변경 후 assertion 실패12·통과15,
  독립 static caller12. 양쪽 동작 예측100%·음성 대조 오선택0이다.
- callback source80%/MCP100%는 같은 caller를 Function2로 쓴 source 표기 미대응이다. source2·MCP1 미대응을
  모두 원문 대조했고 점수·aliases는 수정하지 않았다. 작은 합성 표본의 천장 효과이며 일반 효용·caller 발견 우위를 주장하지 않는다.
- 평가용 매칭기에서 함수 타입의 선택적 인자 이름을 대응한다. 임의 typealias/Function2 축약·잘못된 descriptor를
  자동 수리하지 않으며 제품 query 문법은 바꾸지 않았다. 제품 발행 버전은 **0.15.0**이다.
- 프로토콜 `0a0749c`, 실행 전 동결 `4f62a5f`, 결과 head `8797dd1`을 보존했다. manifest SHA256은
  `4b600a793467c451a86755a385400ad6ddbddb6aa6fcc4ad1022ab1a776cbf51`이다. 완료 run을 재시작하지 않는다.
- 원문112개·출력 tool input·원문 재채점이 일치한다. GLM 사전/결과 검토 차단사항0이며 보완과 리뷰 산식 오타의
  처분을 파일·PR 코멘트에 남겼다. v5/v6 61개 파일은 현재 main과 보존 worktree 모두 byte-identical이다.
- 로컬 정본 `.git/ai-utility-v7-20260922/FINAL.json`, 원문 `trials-final/`·`native-1/`.
  native base/changed는 archive 상태다. 약343KB·2,164개 member 실제 복원·snapshot4 matched 뒤 정리했고
  원문183개 hashes를 보존했다. 정리 후 report도 byte-identical하게 재생성했다.
  복원은 `cleanup.json`, parser는 `parser-restoration-reference-local.json`의 v6 archive 동일6개 member를 따른다.
- 등록 worktree `ai-utility-v7-20260922/`와 `ai-utility-v6-20260922/`는 고정 실행 도구 경로를 보존한다.
  다른 checkout에 로컬 원장이 있다고 가정하지 않는다. 전역 cache·기존 도구·다른 worktree는 유지했다.
  요청된 작업의 추가 구현은 남지 않았다. 이후 범위는 새 요청으로 정한다.

<!-- ai-utility-v7-20260922:end -->

<!-- ai-utility-v6-20260922:start -->
## 최신 재개 상태 — v6 구조화 출력 실험·검증·정리 완료

- 사용자 요청대로 새 공개 표본4개에서 source/MCP 각2회·총16회를 전부 실행했다. source8/8·MCP8/8 구조화 응답 유효,
  인프라 오류0·선택적 재시작0. [결과 문서](ai-utility-v6-20260922/experiments/ai-utility-v6/README.md)를 따른다.
- 작업은 **로컬 검토 branch** `experiment/ai-utility-v6-20260922`에 있다. 등록 worktree는
  `ai-utility-v6-20260922/`이며 HEAD `f94a1e8`이다. protocol `9c124fb`·실행 전 고정 `0e9f423`·결과 `38bdd1d`를 보존했다.
  이후 PR96으로 원격 통합했다. main의 제품 코드·VERSION 0.15.0은 이 실험으로 바꾸지 않았다.
- 실제 schema 성공·invalid schema 거부·budget 종료·source/MCP toy·같은 mtime byte 변경 거부를 검증했다.
  회귀32개·기존 native 테스트79개 통과, 원문112개 해시·원문 재채점·반환 객체/원문 tool input이 일치했다.
  v5의29개 파일도 그대로다. GLM 코드 검토의 두 차단사항은 회귀로 수정했고 결과 해석 검토에는 차단사항이 없었다.
- 최종 표본은 detekt6443·6352, ktlint2617·2554이며 oracle27·9·14·31개다. suite anchor79개·직접 caller2개다.
  일부 MCP 조건의 고정 식별자 매칭 coverage가 더 높았지만, ktlint2554의 source도 두 번 모두 같은 caller를 지목했다.
  함수 타입의 선택적 매개변수 이름 생략을 매칭기가 놓친 차이를 발견 우위로 읽지 않는다.
  원문·aliases·oracle·점수는 수정하지 않고 source-signature-readback을 분리했다. 일반 AI 효용 우위는 확정하지 않는다.
- 모델 단계966.58초·CLI 비용 추정4.736082USD다. 준비/별도 smoke/리뷰는 제외했다.
  실제 제품 요청은 freshness2·query_symbol3·impact4였고 모두 처리됐다. 요청한 freshness는 둘 다 matched였다.
- 준비 실패4건을 보존했다. ktlint의 Java21 build logic은 JDK21 launch로, detekt6352의 capture는 원래 Gradle8.2.1
  실패 두 번 뒤 Gradle8.3 제어 실행으로 matched였다. production/test source는 바꾸지 않았다.
  ktlint2715는 parameterized 표시명8개가 모호해 oracle 자격에서 제외했고,2555는 f2p0으로 metadata 제외했다.
- 작업용 cache 정리로 배정 용량은 약9.33GB→0.62GB다. 원본 native 입력은 약556MB 아카이브로 보존했다.
  26,222개 member와 실제 복원 snapshot6개(최종4·제외1·toy1) matched를 확인한 뒤 원본 native 디렉터리를 정리했다.
  원문 근거180개 해시는 유지된다. 기존 사용자 파일·다른 worktree·전역 cache·기존 설치 도구는 보존했다.
- 정본은 `.git/ai-utility-v6-20260922/FINAL.json`이다. 복원은 `native-cleanup.json`·`native-archive-manifest.json`을 따른다.
  기존 raw manifest의 절대 경로는 바꾸지 않았다. 완료 후 검토 worktree를 옮긴 기록은 `worktree-relocation-local.json`이며
  고정된 코드13개 파일의 bytes가 같다. 원본 입력은 archive 상태이므로 current 검증 전에 복원해야 한다.
  이 로컬 원장을 다른 checkout에 가정하지 않고 완료된 frozen 실행을 다시 시작하지 않는다.

### 다음 후보

필수 실험 작업은 끝났다. 다음은 함수 타입 등 동치 source 표기와 호출·행동 oracle을 사전 검증하는 별도 프로토콜이다.
원격 반영을 요청받으면 위 로컬 branch와 이미 확보한 검증·GLM 근거를 사용한다.

<!-- ai-utility-v6-20260922:end -->

<!-- release-0.15.0-20260922:start -->
## 최신 재개 상태 — 0.15.0 발행·독립 설치·정리 완료

- 사용자 승인으로 [PR #95](https://github.com/ictechgy/kartograph/pull/95)를
  `8ffcec174dfecf597944440a940898ecc9e1685a`로 머지하고 `v0.15.0`을 발행했다.
  검증 head `54b9f64`와 merge tree는 동일하다. 원본 main도 fast-forward했고 기존 사용자 변경을 보존했다.
- **현재 소스·최신 발행 버전은 0.15.0**이다. PR93의 선택적 v3 compiler 입력·JVM 선언 귀속과
  PR94의 빈 의존 프로젝트 출력 수정이 포함된다. [GitHub Release](https://github.com/ictechgy/kartograph/releases/tag/v0.15.0),
  [Plugin Portal](https://plugins.gradle.org/plugin/io.github.ictechgy.kartograph/0.15.0) 공개와 독립 설치를 확인했다.
- [PR CI](https://github.com/ictechgy/kartograph/actions/runs/35634758597)·
  [main CI](https://github.com/ictechgy/kartograph/actions/runs/35638096364)의 네 검사가 모두 성공했고,
  [Release workflow](https://github.com/ictechgy/kartograph/actions/runs/35638152200)도 성공했다.
  8종 재현 해시·배포물 계약·GLM 리뷰 처분을 완료했다. Linux의 macOS 전용 Python 2개 skip은
  로컬 ZIP/TAR 검사에서 각각 142개 전체 통과한 근거와 구분해 보존한다.
- 내려받은 GitHub 7개 asset의 API digest·SHA256SUMS가 일치했고 ZIP/TAR CLI는 모두 0.15.0이다.
  배포 collector의 독립 javac 검사와 실제 javac/KAPT/KSP 4종 출력·15/16/21개 선언 입력,
  native FROM-CACHE·configuration cache·실패 거부·snapshot 귀속을 확인했다.
  전체 compiler 환경의 완전성이나 source/resource의 JVM 귀속을 주장하지 않는다.
- 빈 dependency cache의 Portal 설치는 strict·baseline·만료·cache·freshness 및 PR94 빈 의존 프로젝트
  대조를 포함한 17단계를 통과했다. 해석된 plugin JAR는 GitHub asset과 같고, 임의 라이브러리 누락은 계속 거부한다.
- 첫 로컬 readiness의 `.git` 아래 TMPDIR 거부와 재실행의 기존 증거 디렉터리 거부는 실행 환경 문제였다.
  원본 로그를 보존하고 임시 경로를 바로잡아 설치 검증을 완료했다. 태그 workflow에서는 원래 readiness 전체가 통과했다.
- 이번 worktree·재생성 cache/중복 배포본 약 93MB(allocated)를 정리했다. 원본 로컬 빌드·fixture는
  약 38MB 아카이브로 보존하고 실제 복원·해시를 대조했다. 보존 427개 파일과 collector receipt 4개,
  Portal snapshot 2개는 정리 후에도 각각 해시 일치·`matched`다. 사용자 파일·기존 worktree·SDK·전역 cache는 유지했다.
- 정본은 `.git/release-0.15.0-20260922/FINAL.json`이다. 복원은 `archive-manifest-local.json`과
  `cleanup-final-local.json`을 따른다. 소스는 `refs/archive/release-0.15.0-20260922`로 복구할 수 있다.
  Portal cache 입력의 동일 bytes는 `portal-preserved-inputs/`, 새 로컬 binding은 각 소비 프로젝트의
  `build/kartograph/jvm-input-bindings-preserved-local.json`에 있다. 원래 binding도 기록으로 보존했다.
  기존 사용자 HANDOFF는 같은 원장의 `HANDOFF.before.md`에 있다. 이 로컬 경로를 다른 checkout에 가정하지 않는다.

### 다음 작업

필수 릴리스 작업은 남아 있지 않다. 다음 후보는 기존 v5 원문·채점을 보존한 별도의 구조화 출력 검증 프로토콜이다.
아래 0.14.0·PR94 기록의 미발행/릴리스 다음 단계는 당시 상태이며 이번 0.15.0 발행으로 완료됐다.

<!-- release-0.15.0-20260922:end -->

<!-- competitive-integration-20260921:start -->
## 최신 재개 상태 — PR94 통합·검증·정리 완료

- [PR #94](https://github.com/ictechgy/kartograph/pull/94)는 `4b3188d95e8de328c546810736a7ecd63a173fd4`로
  머지됐고 원본 main도 동기화했다. 검증 head `39a537d`와 merge의 tree는 동일하다.
  [PR CI](https://github.com/ictechgy/kartograph/actions/runs/35576685058)와
  [머지 후 main CI](https://github.com/ictechgy/kartograph/actions/runs/35579474938)의
  test·JDK17·JDK21·최소 AGP 네 검사가 **모두 성공**했다.
- Java 소스가 없는 의존 프로젝트의 정상적인 빈 class 출력을 JVM snapshot이 거부하던 문제를 수정했다.
  공개 Gradle artifact metadata로 선언된 프로젝트 출력만 부재까지 추적하고, 빈 외부 출력은 위치 식별자로
  구분한다. 임의 라이브러리 누락은 계속 실패하며 출력 변경은 stale로 판정한다. PR93의 processor 귀속도 유지했다.
- 로컬 **815개 통과·선택적 NIA 2개 skipped**, coverage 90%·plugin validation·CLI/agent 계약,
  실험 회귀 29개·자기 분석 4.334s가 통과했다. GLM 제품·CI 리뷰의 의견 처분도 끝났다.
  이전 main의 실제 30분 초과 취소를 확인해 `test` job 한도만 45분으로 늘렸고 검증 명령·판정 기준은 유지했다.
- [경쟁 비교·사용 안내](docs/COMPETITIVE-EVIDENCE.md)와 [v5 평가](experiments/ai-utility-v5/README.md)를
  통합했다. 새 공개 모듈 4개에서 matched 입력으로 16회를 완료했으나 AI 효용 우위는 확인하지 못했다.
  JSON 형식 실패 5건과 원문·점수를 보존했고, 고정 v5 파일 29개는 통합 전과 동일하다.
- 소스 VERSION과 최신 발행 버전은 **0.14.0**이다. **PR93·PR94는 미발행 개발 변경**이며,
  기존 0.14.0 배포물에 포함되지 않는다. 이번 통합에서 새 태그·릴리스를 만들지 않았다.
- 완료 worktree `competitive-evidence-20260921`과 재생성 산출물 약 1.44GB를 정리했다.
  검증 근거 1,396개는 약 20MB 아카이브로 보존하고 실제 복원·해시 일치를 확인했다.
  CI 산출물 ZIP 3개도 GitHub digest와 대조했다. 사용자 변경·다른 담당자 worktree·전역 cache는 유지했다.
- 로컬 정본은 `.git/competitive-integration-20260921/FINAL.json`이며, 이후 확정된 main CI 성공은
  같은 원장의 `main-ci-completed.json`에 있다. 정리·복원은 `cleanup.json`, `build-archive-manifest.json`,
  `build-restoration.json`을 따른다. 원래 branch는 `refs/archive/competitive-integration-20260921`로
  복구할 수 있고 당시 로컬 HANDOFF는 `HANDOFF.final.md`·`HANDOFF.final.patch`에 보존했다.
  v5 원본 실험 근거는 `.git/ai-utility-v5-20260921/`에 있다. 이 로컬 경로를 다른 checkout에 가정하지 않는다.

### 다음 작업

1. PR93·PR94를 포함할 **새 버전을 정하고 릴리스 readiness·발행·독립 설치를 검증**한다.
   현재 0.14.0의 발행·설치 완료와 구분하며 새 배포를 완료한 것으로 표시하지 않는다.
2. JSON 형식 실패를 줄이는 **구조화 출력 검증을 새 프로토콜로 진행**한다.
   기존 v5 원문·채점은 수정하지 않고 일부 성공 사례만 재실행하지 않는다.

아래 PR93·PR91 기록과 2026-09-20 목록은 당시 근거다. 완료된 snapshot/cache·실기기·collector 발행 작업을
과거 목록 때문에 다시 시작하지 않는다. 이번 인계 갱신은 위 후속 작업의 구현·발행을 시작한 것이 아니다.
<!-- competitive-integration-20260921:end -->

<!-- compiler-input-attribution-20260921:start -->
## 2026-09-21 compiler 입력·선언 귀속 후속 완료

- [PR #93](https://github.com/ictechgy/kartograph/pull/93)은 `183a6c7`로 머지됐다. 검증한 `61ac7f2`와
  tree가 같고 [원격 CI 4개](https://github.com/ictechgy/kartograph/actions/runs/35557251475)가 모두 통과했다.
- 선택적 v3 receipt는 Gradle 선언 입력·속성·nested implementation artifact와 KSP 원본 libraries를
  대조한다. `project/`와 외부 slot을 구분하며, 잘못된 control 경로가 원래 입력을 지우지 않도록 검증한다.
  API class bytes·JVM ID·선택 root·MEMBER 관계가 일치할 때만 선언 귀속을 보존한다. 그래프/보존 판정은 유지한다.
- 실제 javac/KAPT/KSP 4종 출력·15/16/21 입력·native FROM-CACHE/configuration cache와 ABI 정규화 뒤
  JAR bytes 변경 거부를 통과했다. source/resource/직접 callback 쓰기의 JVM 귀속, 숨은 IO·환경 완전성은 주장하지 않는다.
- 제품 813개/0 skip, coverage, Python142, Android44 retained/4 reportable, CLI/agent, JDK17/21 plugin fixture,
  자기 분석4.423s/15s가 통과했다. NIA 보존 17roots+R.jar의700파일을 복원해 기존2테스트도 skip 없이 통과했다.
- VERSION **0.14.0**은 유지하며 이번 변경은 **개발 소스**다. 기존0.14.0 배포물에는 v3가 없고 새 태그/배포는 하지 않았다.
  검증·리뷰·복원 원장은 `.git/compiler-input-attribution-20260921/`에 있다.
- 완료된 TestKit cache 11,560개 항목을 원본 bytes/link와 대조한 623,003,074-byte 아카이브로
  보존한 뒤 생성물 경로를 정리했다. 복원은 같은 원장의 `testkit-cache-cleanup.json`을 따른다.
  정리 후 javac/KAPT/KSP 보존 receipt는 모두 `matched`였다. SDK·전역 cache·worktree는 유지했다.

<!-- compiler-input-attribution-20260921:end -->

<!-- processor-integration-20260921:start -->
## 2026-09-21 추가 확장 완료

- [PR #91](https://github.com/ictechgy/kartograph/pull/91)은 `ca2fc7d6be30bf1c63d93737eedadeb980423168`로
  머지됐다. 검사한 `a084a09`와 merge의 tree가 같고 최종 CI test/JDK17/JDK21/최소 AGP가 모두 통과했다.
- **0.14.0**의 [GitHub release](https://github.com/ictechgy/kartograph/releases/tag/v0.14.0)와
  [Plugin Portal](https://plugins.gradle.org/plugin/io.github.ictechgy.kartograph/0.14.0) 발행·독립 설치를
  확인했다. [릴리스 workflow](https://github.com/ictechgy/kartograph/actions/runs/35526574145)는 성공했다.
  CLI ZIP/TAR·plugin JAR·collector ZIP·SBOM 2종·SHA256SUMS의 7개 다운로드 digest와 manifest가 일치했다.
  Portal cold 설치의 JAR도 GitHub JAR와 같고 strict/baseline/expiry/config-cache·freshness 대조를 통과했다.
- CLI `snapshot --processor-output-config`와 Gradle snapshot task의 `processorOutputConfigs`는
  완료된 v2 output receipt를 현재 입력·artifact·scope·raw·출력 bytes와 대조해 `processorOutputs`에
  보존한다. 기존 source-only `processorGenerations`·compiler witness를 유지하고 그래프 간선·보존·
  synthesized·dependency 판정은 바꾸지 않는다. API 출력과 callback 중 직접 쓰기를 구분한다.
- 실제 javac17/KAPT2.4.10/KSP2.3.12 각각 source/class/resource/file 4종과 native FROM-CACHE,
  configuration cache, snapshot, stale/실패 대조를 통과했다. 다운로드한 collector·runner·adapter·CLI로도
  세 경로를 다시 확인했다. standalone v1 receipt의 독립 verify는 유지하며 snapshot에는 v2 재수집이 필요하다.
  성공 뒤 남는 token은 cache 입력이며 성공 증거가 아니다. 전체 compiler 입력·생산자 인증은 주장하지 않는다.
- 로컬 제품 802개·coverage gate·CLI/plugin 계약, Python142개, Android fixture44 retained/4 reportable,
  자기 분석4.544s를 통과했다. 선택적 NIA2개는 보존 빌드 산출물 부재로 skip했다. 해당 공개 산출물을
  `build/reports/android-tool-comparison-20260911/work/nowinandroid`에 복원하고 JDK17에서
  `./gradlew --no-daemon :cli:test --tests dev.kartograph.cli.RealAgpRJarCliTest`로 실행할 수 있다.
- 자매 [isthmus PR #105](https://github.com/ictechgy/isthmus/pull/105)도 `697185d`로 머지·CI 완료됐다.
  iPhone 실기기 iOS27 release와 RN0.81.4/Hermes 새 아키텍처 release 26checks(전용 Android emulator),
  실제 무음 미디어·구독 해제·checkpoint 이후 lifecycle과 앱/AVD 정리를 확인했다.
  [공개 근거](https://github.com/ictechgy/isthmus/blob/main/experiments/real-corpus/results/runtime-native-development-results.json)를 따른다.
  iOS RN·모든 lifecycle·가청 출력·정적 producer 완전성은 검증 범위 밖이다. isthmus npm0.9.0은 새로 발행하지 않았다.
- 개발 검증·GLM 처분은 `.git/processor-integration-20260921/`, 발행·설치 검증은
  `.git/processor-snapshot-release-20260921/`에 보존한다. 첫 Portal 검증의 fixture task 조회 시점 오류와
  한 로컬 TestKit configuration-cache 재시도 실패/복구도 기록했으며 정상 결과로 덮어쓰지 않았다.
- 통합 작업 캐시22개(6,586,929 logical bytes)를 복원 가능한 휴지통으로 옮겼고 보존22764개 파일의
  bytes와 세 processor receipt의 matched 상태가 유지됐다. SDK·전역 cache·worktree·원본은 유지한다.
  복원은 `.git/processor-integration-20260921/cleanup-final.json`의 to를 비어 있는 from으로 옮긴다.
  발행 담당자의 정리 원장과 구분하며 다른 checkout에 이 로컬 경로가 있다고 가정하지 않는다.

- 발행 정본은 kartograph `.git/processor-snapshot-release-20260921/FINAL.json`이다. 발행 담당자의
  별도 정리는 소유 디렉터리 23개(427,441,025 logical / 437,784,576 allocated bytes)를 제거했다.
  원본 native fixture 395개는 무손실 아카이브로 보존했고 최종 보존 근거 532개 해시를 재확인했다.
  설치 receipt 4개와 Portal freshness도 정리 후 `matched`다. 원본 TAR는 유지하고 중복 추출본만
  정리했다. 복원/보존 경로는 같은 원장의 `FINAL-CLEANUP-local.json`·`final-preservation-plan-local.json`을
  따른다. 위 통합 작업의 휴지통 이동 22개와 별도이며 남은 필수 발행 작업은 없다.

아래 2026-09-20 내용은 이전 기록이며 당시의 세 추가 후보는 이번 작업에서 처리했다.
<!-- processor-integration-20260921:end -->

## 2026-09-20 완료 상태

- 선택적 output collector 확장은 [PR #90](https://github.com/ictechgy/kartograph/pull/90)에서
  `5add226`으로 머지됐다. 최종 CI의 test·JDK17/21 compatibility·최소 AGP 검사와 GLM 지적 반영이
  완료됐고 merge tree는 검사한 head `f6c93f8`과 같다. 자매 isthmus
  [PR #104](https://github.com/ictechgy/isthmus/pull/104) (`a356f49`)도 머지·CI 완료됐다.
- 현재 소스 버전은 **0.13.0**이다. [새 릴리스](https://github.com/ictechgy/kartograph/releases/tag/v0.13.0)와
  [Plugin Portal](https://plugins.gradle.org/plugin/io.github.ictechgy.kartograph/0.13.0)의 발행·독립 설치 확인은 앞선 발행 원장에 있다.
  #90의 새 output collector는 개발 소스이며 새 릴리스로 발행하지 않았다.
  이전 **0.12.0**의 [PR87](https://github.com/ictechgy/kartograph/pull/87)과
  [릴리스](https://github.com/ictechgy/kartograph/releases/tag/v0.12.0)에 P1.1-2·RN target 필터 수정이
  포함됐다. 이전 버전의 GitHub·Plugin Portal·독립 설치 검증은 과거 원장에 있다.
- 0.13.0의 `dependencies`는 `--baseline`, `--suppress`, `--write-baseline`을 지원한다.
  좌표·버전·scope·제안·클래스 근거를 정확히 지문화하고, Gradle은 dependencyBaseline/
  dependencySuppress와 task baselineOutput을 제공한다. capture는 필터 전 관찰을 저장하며,
  UTC 만료일·파일 변경을 task 입력으로 확인한다. CLI·Gradle strict는 필터 뒤 진단만 센다.
  [사용법](docs/DEPENDENCIES.md)을 따른다. 억제는 제거 승인이 아니다.
- `bridges`의 generatedAt은 추출 시각으로 수정했고 최신 source mtime은 optional
  sourceModifiedAt에 보존한다. v1·Basic·Event·RN 모두 UTC 밀리초 형식을 사용한다.
  0.12.0 이하의 기본 generatedAt이 source mtime이었던 사실과 compiler freshness 한계를 구분한다.
- [processor source 귀속](docs/PROCESSOR-GENERATION.md)은 실제 JSR-269 Filer 생성/close와
  processor artifact를 관찰한다. javac-processors v2 raw evidence는 완료된 compiler/generated-source
  receipt와 일치할 때만 snapshot.processorGenerations로 들어간다. 생성기 이름 추측·자동 보존·
  dependency unused 판정 변경은 없다. 실제 generic processor·Dagger2.59와 실패 경로를 검사한다.
  0.13.0의 snapshot 연동 범위는 source-only로 유지한다. 개발 collector의 별도 output sidecar/
  runner는 javac/KAPT/KSP source·class·resource 및 지정 디렉터리 callback 중 직접 byte 변경을
  구분한다. 실제 3가지 처리 경로에서 각 4종 출력과 stale/미닫힘/깨진 source 실패 대조를 통과했다.
  직접 쓰기 관찰은 API 호출 귀속이나 다른 writer 신원의 증명이 아니다. 캐시·전체 compiler 입력·
  그래프 연동을 주장하지 않으며 [정확한 실행 범위](docs/PROCESSOR-GENERATION.md)를 따른다.
- source mtime·basename·빈 결과로 완전성을 추론하지 않는다. 공개 RN Sound.kt의 컴파일
  JVM ID·retention·원본 JS explain과 실제 Flutter macOS/Android 실행 근거는
  [isthmus 인계](https://github.com/ictechgy/isthmus/blob/main/HANDOFF.md)에 있다.
- 실제 javac17/KAPT2.4.10/KSP2.3.12에서 각 4종 출력, 손작성 파일 제외, source/output/raw/scope stale,
  미닫힘·깨진 생성 source의 빌드 실패를 확인했다. 입력/control 중첩, 초기화 전 조회, canonical URI와
  중복 open 상태도 회귀 검증했다. 기존 collector와 제품 evidence lifecycle 검사도 통과했다.
- 최종 원장은 자매 isthmus의 `.git/evidence-runtime-expansion-20260920/FINAL.json`이다.
  `processor-final-evidence/`에는 성공한 raw/source/class/resource/direct 출력과 실제 JAR·입력을
  보존했으며 세 `*-success-config-local.json` 모두 보존본에서 `matched`였다.
  캐시 디렉터리 32개를 휴지통으로 옮긴 뒤 근거 파일 1,580개의 해시가 유지됐다. 복원은 같은 원장의
  `cleanup-final.json`에서 `from`이 비어 있을 때만 `to`를 되돌린다. SDK·설치 도구는 보존했다.
  다른 checkout에 이 로컬 경로가 있다고 가정하지 말고 공개 PR·검증 스크립트를 함께 사용한다.
- `.claude/`와 `HANDOFF.cartograph-notes.md`는 기존 사용자 미추적 파일로 보존한다.

## 2026-09-20 당시 다음 범위 선택 (이후 완료)

당시 추가 후보였던 다음 세 가지는 위 PR91·0.14.0 발행·isthmus 검증에서 처리했다.

1. 새 KAPT/KSP output receipt의 snapshot 연동과 Gradle cache 복원.
2. 자매 isthmus의 iPhone 실기기·iOS release, RN Fabric/TurboModules·lifecycle·실제 미디어 재생 검증.
3. 새 선택적 collector의 릴리스와 설치본 검증.

현재 인계 갱신은 후속 구현·발행을 시작한 것이 아니다. 0.13.0 발행 원시는 자매 isthmus의
`.git/release-followups-20260920/`에 있다. 기존 baseline/suppress·JSR-269 source 귀속·새 출력 수집을
과거 목록 때문에 다시 구현하지 않는다. runtime LCOV·method 매핑과 P2/P3·성능 후보도 별도 선택 범위다.

- cartograph의 테스트 영향·기계적 수정·public 검토 안내 공유는 [HANDOFF-cartograph-skills-20260921.md](HANDOFF-cartograph-skills-20260921.md)에 있다. 제품/스키마 변경 지시가 아닌 자매 알림이다.
