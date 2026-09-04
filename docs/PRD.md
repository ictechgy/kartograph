# kartograph PRD

## 한 줄

Kotlin/Android 코드베이스의 의존성 그래프를 컴파일러 산출물에서 만들고, 그 위에서 미사용 코드 · 순환 · 레이어 규칙 · 지표를 근거와 함께 답하는 CLI.

## 문제

Android 개발자가 "이 클래스 지워도 되나"를 물을 방법이 없다.

- **Android Lint** 는 리소스 미사용은 보지만 전역 미사용 코드 탐지기가 아니다
- **Detekt** 는 private 멤버 · import 수준의 구문 검사다
- **IntelliJ 의 Unused declaration 검사** 는 IDE 안에서만 돌고 CI 에 못 넣는다
- **R8** 은 사실상 최종 판정기지만 "무엇이 왜 미사용인가"를 개발자에게 보고하지 않는다. 결과는 `usage.txt` 한 장이다
- 오픈소스 두 개가 있다: `bye-bye-dead-code`(R8 의존, README 에 `// TODO` 가 남아 있음), `SearchDeadCode`(Rust 구문 파싱, 별 13개, "`Class.forName()` 은 못 본다"고 스스로 명시). 둘 다 컴파일러 수준의 해석을 쓰지 않는다

Swift 에서 Periphery 가 했던 일을 Kotlin 에서 하는 도구가 **없다**. `docs/RESEARCH.md` 에 확인 경위가 있다.

## 사용자

1. **CI 를 가진 Android 팀** — PR 마다 `--since origin/main --strict` 로 새로 생긴 미사용 코드와 레이어 위반을 막는다
2. **레거시를 정리하는 개발자** — 베이스라인을 찍고 부채를 조금씩 줄인다
3. **코딩 에이전트** — 삭제 전에 `query` 로 묻고, `limitations` 와 `reason` 을 읽고, 판정을 사람에게 넘긴다

세 번째 사용자가 설계를 좌우한다. 에이전트는 판정을 곧바로 편집으로 옮기므로 출력이 산문보다 확신하면 안 된다.

## 범위

### 반드시 (v0.1)

- 심볼 그래프: 클래스 · 함수 · 프로퍼티 · 생성자, 참조 종류(call · reference · inheritance · override · member 등)
- `dead` — 보존 루트에서 도달 불가한 선언, `--explain` 으로 근거
- `cycles` — Tarjan SCC + 끊을 후보 간선
- `graph` — 결정적인 DOT
- `query <symbol>` — usedBy · dependsOn · members · reachability · limitations, JSON. cartograph 의 `SymbolQueryDocument` 와 **같은 스키마**. 자매 도구 사이에서 소비자가 같은 파서를 쓸 수 있어야 한다
- 보존 규칙 — keep 규칙 파싱 + 매니페스트 + XML 레이아웃 + DI/직렬화 어노테이션. 근거에 출처를 남긴다
- 베이스라인, `--since`, 종료 코드 계약, `xcode`-대응 리포트 형식은 `gradle`/`github-actions`/`sarif`/`json`
- `skill` — 에이전트용 스킬 설치
- 오탐 코퍼스 + 양방향 검증 스크립트

### 나중에 (v0.2+)

- `rules`(레이어 규칙 YAML), `metrics`(Martin 지표) — cartograph 에서 검증된 대로 옮기되 v0.1 에 넣지 않는다. 원천 결정과 보존 규칙이 먼저다
- 멀티 모듈 Gradle 프로젝트의 모듈 레벨 그래프
- Compose 전용 규칙(`@Preview`, `@Composable` 람다 도달성)
- KMP(Kotlin Multiplatform) 공통 코드

### 하지 않는 것

- **삭제 판정.** `deletable: true` 같은 필드는 영원히 없다
- **자동 삭제.** 리포트만 낸다
- **런타임 커버리지 통합.** JaCoCo/Kover 와의 결합은 다른 도구의 일이다. 정적 사실만 낸다
- **IDE 플러그인.** CLI 와 Gradle 플러그인까지만
- **Java 전용 프로젝트를 위한 특별 대우.** 원천이 바이트코드면 Java 는 자연히 포함되고, 아니면 v0.1 범위 밖이다

## 핵심 설계 결정

### 원천은 무엇인가

이것이 프로젝트의 전부다. `docs/PLAN.md` Phase 0에서 세 후보를 실측했다.

| 후보 | 장점 | 위험 |
|---|---|---|
| **A. 바이트코드 + kotlin-metadata** (ASM 으로 `.class` 읽기, Kotlin 고유 정보는 metadata 에서) | 형식이 안정적. 생성 코드(Hilt/KSP/Compose 변환 후)를 전부 본다. Java 공짜. R8 `usage.txt` 와 대조 가능 | 소스 위치는 디버그 정보에 의존. 인라인 함수 · 람다는 원 소스와 모양이 다르다. "왜"를 소스 언어로 말하기 어려울 수 있다 |
| **B. Analysis API Standalone** | 해석된 심볼 · 타입 · 참조를 소스 기준으로. cartograph 의 IndexStoreDB 에 가장 가까운 의미 | 공식 문서: *"currently in development and subject to incompatible changes"*. IDE 밖에서 소스 루트 · 클래스패스 · 생성 소스 디렉터리를 직접 구성해야 한다 |
| **C. 빌드 시점 덤프 인덱서** (KSP 또는 컴파일러 플러그인이 빌드 중 사실을 파일로 씀) | IndexStoreDB 모델 그대로 — "컴파일러가 기록한 파일"을 읽는다. API 가 흔들려도 덤프 형식만 지키면 된다 | 사용자의 빌드에 플러그인을 끼워야 한다. Compose IR 변환 이후는 KSP 가 못 본다 |

결정은 **A를 주 그래프로, Kotlin 사실은 공식 `kotlin-metadata-jvm`, 위치는 디버그 정보로 보강**하는
구조다. Analysis API Standalone과 KSP/FIR plugin은 v0.1 제품 의존성에서 제외한다. 실측표, 각 후보가
못 보는 것, Phase 1 제약은 [`DECISION-truth-source.md`](DECISION-truth-source.md)에 있다.

### 보존 규칙의 원천

keep 규칙이 1차다. 이유: 생태계가 십 년 넘게 검증해 온, 기계가 읽는 형태의 "지우면 안 되는 것" 목록이고, 라이브러리가 consumer rules 로 같이 배포한다. `-keep class * extends android.app.Activity` 를 파싱해 "Activity 를 상속하므로 보존" 이라는 근거를 **규칙 파일 경로와 줄 번호와 함께** 낼 수 있다.

keep 규칙이 못 덮는 것을 손으로 쓴다:

- 매니페스트가 이름으로 참조하는 컴포넌트(Activity · Service · Receiver · Provider)
- XML 레이아웃 · 메뉴 · 내비게이션 그래프가 이름으로 참조하는 클래스
- DI 그래프: `@Inject` 생성자, `@Provides`, `@Binds`, `@AndroidEntryPoint`, `@HiltViewModel`
- 직렬화: kotlinx.serialization `@Serializable`, Gson/Moshi 필드명, Parcelable `CREATOR`, Room `@Entity`/`@Dao`, Retrofit 인터페이스
- JNI `external` 함수, `@JavascriptInterface`, WorkManager `Worker` 서브클래스
- Compose `@Preview`
- 테스트 (`@Test`, JUnit 러너, Robolectric)

각 규칙에는 cartograph 의 `RetentionReason` 처럼 **값으로 된 이름**이 있고, 픽스처 코퍼스에 재현 케이스가 있어야 한다.

## 성공 기준

- 로컬 Android 표본 5개(`docs/PLAN.md`)에서 돌아가고, 각 프로젝트의 미사용 보고 상위 10건을 손으로 검증했을 때 **오탐 0건**
- 오탐 코퍼스가 최소 10개 계열을 덮고 CI 에서 양방향으로 검증된다
- `query` 출력이 cartograph 와 같은 스키마라 같은 스킬 문장으로 가르칠 수 있다
- 자기 분석 findings 0, 커버리지 90% 이상
- 공개 도그푸딩 대상 nowinandroid(추적 Kotlin 310파일)에서 `dead`가 10초 안에 끝난다 — Phase 0의
  원천 읽기 기준값은 1초 미만이다

## 비교표 (README 용, 사실 확인 후 게시)

| | Detekt | Android Lint | R8 | kartograph |
|---|---|---|---|---|
| 전역 미사용 코드 | private 수준 | 리소스만 | ✅ 판정만 | ✅ 근거와 함께 |
| 왜 살아남았나 | — | — | — | `dead --explain` |
| 순환 의존 | — | — | — | ✅ 끊을 후보 |
| 에이전트용 질의 | — | — | — | `query`, `skill` |
| 분석 한계 동봉 | — | — | — | `limitations` |

## 이름과 라이선스

kartograph. MIT. cartograph 와 같은 이유로 — Periphery 의 상업화가 남긴 자리를 오픈소스로 채우는 것이 이 프로젝트 군의 출발점이다.
