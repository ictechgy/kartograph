# kartograph PRD

## 한 줄

Kotlin/Android 코드 수정 전에 잠재적 영향을 근거와 함께 점검하고, 사람·AI·CI가 같은 컴파일 그래프를 질의하는 도구.

## 사용자 확정 목표 (2026-09-11)

1. 특정 코드 수정 전 직접·간접 영향을 받는 선언과 연결 경로를 조사한다.
2. AI는 질의 스킬 또는 MCP로 같은 근거를 소비한다. 현재는 CLI·설치형 스킬을 제공한다.
3. reflection·framework·생성 코드 등 런타임에 드러나는 관계를 가능한 범위에서 정적으로 복원하고 미해결 범위를 알린다.
4. CI가 그래프를 반복 갱신할 수 있어야 한다. 전체 갱신과 저장 질의를 먼저 측정하고, 증분은 필요성이 확인되면 도입한다.

## 문제와 사용자

수정 대상의 호출자·소비자·진입 경로는 여러 파일과 생성 코드에 걸쳐 있다. 소스 검색, IDE 질의, Gradle 모듈 그래프,
R8의 보존 설명은 서로 다른 사실을 제공한다. kartograph는 컴파일된 JVM identity와 근거가 포함된 그래프 질의로
변경 전 조사와 CI의 변경 후 재검사를 연결한다. 기존 도구가 없다는 전제에 기대지 않는다.

- 개발자·리뷰어: 변경 예정 심볼의 영향 후보, 실제 간선 경로와 한계를 함께 검토한다.
- 코딩 에이전트: 같은 스킬/JSON을 사용하고 unknown·truncation·stale 입력을 영향 없음으로 오해하지 않는다.
- CI 담당자: 같은 analyzer·variant로 만든 base/current snapshot과 Git 변경 파일로 영향 보고서를 갱신한다.

`impact`는 잠재적 의존을 조사한다. `dead`는 설정된 보존 root의 도달성을 검사한다. 두 결과 모두 삭제나 테스트 생략을
승인하지 않는다. 구현·채점 계약은 [IMPACT-PLAN](IMPACT-PLAN.md), 사용법은 [IMPACT](IMPACT.md)에 있다.

## 범위

### 반드시 (v0.1)

- 심볼 그래프: 클래스 · 함수 · 프로퍼티 · 생성자, 참조 종류(call · reference · inheritance · override · member 등)
- `dead` — 보존 루트에서 도달 불가한 선언, `--explain` 으로 근거
- `cycles` — Tarjan SCC + 끊을 후보 간선
- `rules` — layer YAML 위반과 미할당 선언
- `metrics` — module/package Martin 지표
- `graph` — 결정적인 DOT
- `query <symbol>` — usedBy · dependsOn · members · reachability · limitations, JSON. cartograph 의 `SymbolQueryDocument` 와 **같은 스키마**. 자매 도구 사이에서 소비자가 같은 파서를 쓸 수 있어야 한다
- 보존 규칙 — keep 규칙 파싱 + 매니페스트 + XML 레이아웃 + DI/직렬화 어노테이션. 근거에 출처를 남긴다
- 베이스라인, `--since`, 종료 코드 계약, `xcode`-대응 리포트 형식은 `gradle`/`github-actions`/`sarif`/`json`
- `skill` — 에이전트용 스킬 설치
- 오탐 코퍼스 + 양방향 검증 스크립트

### 나중에 (v0.2+)

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

## 비교·평가 원칙

IntelliJ는 의존성 분석과 명령행 검사를, Gradle은 빌드 의존성 분석을, R8은 `-whyareyoukeeping`과 configuration 분석을
제공한다. 기능 부재를 가정하거나 서로 다른 그래프/규칙의 진단 개수를 정확도 순위로 쓰지 않는다.
실제 변경 과제에서 영향 누락·후보 크기·AI 조사 결과·갱신 비용을 따로 측정한다. 고정 회귀와 공개 평가 데이터는
현재 지원 범위의 증거이며 모든 runtime 동작에 대한 완전성 증명이 아니다.

## 이름과 라이선스

kartograph. MIT. cartograph 와 같은 이유로 — Periphery 의 상업화가 남긴 자리를 오픈소스로 채우는 것이 이 프로젝트 군의 출발점이다.
