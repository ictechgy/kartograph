# kartograph 계획

Phase 는 순서대로다. **Phase 0 의 결정이 나기 전에 Phase 1 코드를 쓰지 않는다.**

## Phase 0 — 원천 결정 실험 (코드 없음, 1~2 세션)

목표: `docs/PRD.md` 의 세 후보(A 바이트코드, B Analysis API Standalone, C 빌드 시점 덤프)를 실제 프로젝트에서 돌려 보고 표로 비교해 하나를 정한다.

### 0.1 도그푸딩 대상 확보

2026-09-04에 빌드와 파일 수를 다시 확인한 대상:

| 프로젝트 | 추적 `.kt` | 특징 | 용도 |
|---|---|---|---|
| local sample A | 20 | worktree 복제본이 많음 | 탐색 가지치기, 작은 Android 앱 |
| local sample B | 121 | Compose, 로컬 중 가장 큼 | 성능 기준값, Compose 변환 가시성 |
| local sample C | 82 | Compose, iOS 자매 앱 존재 | 두 도구의 출력 형태 비교 |
| local sample D | 38 | Compose | 작은 대조군 |
| local sample E | 22 | View 기반 | XML 레이아웃 참조 케이스 |
| `android/nowinandroid` | 310 | 공개, Hilt+Compose+KSP, AGP 9 | 생성 코드와 AGP 9 경로 |

로컬 표본에 Flutter/RN 프로젝트는 없다. 부족하면 공개 프로젝트를 쓴다 — Hilt+Compose+KSP가 전부
들어간 `android/nowinandroid`, Views 기반이 필요하면 `chrisbanes/tivi`를 검토한다.

각 프로젝트를 **한 번씩 빌드해 둔다.** `./gradlew assembleDebug`. R8 대조가 필요하면 `minifyEnabled true` 로 release 빌드도.

### 0.2 후보 A: 바이트코드

- `build/tmp/kotlin-classes/debug` (또는 `build/intermediates`) 의 `.class` 를 ASM `ClassReader` 로 읽어 정점(클래스 · 메서드 · 필드)과 간선(호출 · 필드 접근 · 상속 · 어노테이션)을 뽑는 30분짜리 스크립트
- `kotlin-metadata-jvm` 으로 Kotlin 가시성(`internal`), `data class`, 확장 함수 수신자를 복원할 수 있는지 확인
- 소스 위치: `SourceFile` · `LineNumberTable` 디버그 속성으로 파일 · 줄을 얼마나 복원하는지
- 측정: 정점 수 · 간선 수 · 실행 시간 · Hilt 생성 클래스(`Hilt_*`, `*_Factory`)가 보이는지 · Compose 변환 후 함수 시그니처(`$composer`, `$changed` 파라미터)가 어떻게 보이는지

### 0.3 후보 B: Analysis API Standalone

- `analysis-api-standalone` 아티팩트로 세션을 열어 `KtFile` 을 해석하고 참조를 뽑는 스크립트. 공식 문서(`kotlin.github.io/analysis-api`)의 Standalone 절부터
- **클래스패스와 생성 소스 디렉터리(`build/generated/ksp`)를 직접 넣어야 한다.** 이게 얼마나 고통스러운지가 핵심 측정값
- 측정: 같은 다섯 항목 + Kotlin 버전을 하나 올렸을 때 스크립트가 그대로 컴파일되는지(호환성 실측)

### 0.4 후보 C: 빌드 시점 덤프

- KSP 프로세서 하나를 붙여 빌드 중 심볼 · 참조를 JSON 으로 쓴다. KSP 는 참조 해석을 제한적으로만 제공하므로(선언 위주) **간선을 얼마나 뽑을 수 있는지**가 관건
- 대안: FIR 컴파일러 플러그인. 내부 API 라 호환성 보장이 없다는 점을 측정값에 포함

### 0.5 결정

다섯 항목 표 + 각 후보가 **못 보는 것**의 목록. `docs/DECISION-truth-source.md` 로 남긴다. 하이브리드(A 주 그래프 + B/C 로 소스 사실 보강)가 답이면 그렇게 적는다.

Phase 0 의 산출물은 코드가 아니라 **이 문서 하나**다.

## Phase 1 — 골격 (2~3 세션)

- Gradle 멀티 모듈: `core`(모델 · 그래프 · 설정), `index`(원천 어댑터), `analysis`(도달성 · SCC), `export`(리포트 · DOT와 machine JSON), `cli`, `gradle-plugin`. cartograph 의 `Sources/AGENTS.md` 가 이 분리를 왜 했는지 설명한다 — 그 이유가 여기서도 성립하는지 확인하고 `AGENTS.md` 를 쓴다
- `CodeGraph`, `GraphNode`, `GraphEdge`, `EdgeKind.impliesUsage` — cartograph 의 모델을 Kotlin 으로. `impliesUsage` 를 도달성 분석과 `query` 가 **같은 술어**로 쓰는 구조를 처음부터 잡는다
- 종료 코드 계약과 `verify-cli-contract.sh` 를 첫 커밋에
- 커버리지 게이트(Kover 또는 JaCoCo, 라인 90%)를 CI 에 첫 주에
- `graph` 명령이 실제 프로젝트에서 DOT 를 낸다 — 이것이 Phase 1 의 끝

## Phase 2 — 보존 규칙과 `dead` (3~4 세션)

- **오탐 코퍼스 먼저.** `fixtures/false-positive-corpus/` 에 실제로 빌드되는 작은 Android 앱. 케이스마다 "이것은 보고되면 안 된다 / 이것은 보고되어야 한다" 를 스크립트가 양방향으로 검증. 첫 케이스 목록은 PRD 의 보존 규칙 목록 그대로
- keep 규칙 파서: `proguard-rules.pro`, `consumer-rules.pro`, AGP 기본 규칙(`proguard-android-optimize.txt`), `@Keep`. 규칙 → 보존 근거(파일 · 줄) 매핑
- 매니페스트 · XML 레이아웃 · 내비게이션 그래프 스캐너 (cartograph 의 `InterfaceBuilderScanner` 자리)
- DI · 직렬화 어노테이션 규칙
- `ReachabilityAnalyzer` + `dead --explain`. `RetentionReason` 은 enum 값이고 영문 설명을 갖는다
- 다섯 도그푸딩 대상에서 상위 10건 손 검증. **새 오탐 계열을 찾으면 코퍼스에 먼저 넣고 고친다**

## Phase 3 — 도입 경로 (2 세션)

- 베이스라인 파일, `baseline --write`
- `--since <ref>` (git 변경 파일 → 보고 범위). cartograph 의 `ChangedFiles` 가 `-z`, `--full-name`, 세 갈래(ref 대비 · 워크트리 · untracked)를 왜 썼는지 CHANGELOG 에 있다. 같은 함정이 있다
- 리포트 형식: `gradle`(IDE 가 클릭 가능한 형식), `github-actions`, `sarif`, `json`
- Gradle 플러그인: `./gradlew kartographDead --strict`

## Phase 4 — 에이전트 표면 (1~2 세션)

- `query <symbol>`: cartograph 의 `SymbolQueryDocument` 스키마와 **필드 이름까지 같게.** 자매 도구 사이에서 소비자가 같은 파서를 써야 한다. `limitations` 에 들어갈 Android 고유 항목: 리플렉션 문자열(`Class.forName` 호출 수), JNI, 매니페스트에 없는 동적 등록, 인덱스 신선도
- `skill`: cartograph 의 `Skills/cartograph/SKILL.md` 를 출발점으로. "규칙을 통과한 뒤 무엇을 할지", "public API 는 보존 뿌리가 아니다" 절은 그대로. Android 고유 절: "R8 이 지운다고 미사용은 아니다 — 리플렉션으로 살아 있을 수 있다"
- `isthmus` 를 위한 **브리지 사실 내보내기**: `MethodChannel` · `NativeModule` 등록 이름과 핸들러 위치를 JSON 으로. 형식은 `../isthmus/docs/GRAPH-EXCHANGE.md`

## Phase 5 — 순환 · 규칙 · 지표 · 릴리스

- `cycles`(Tarjan + 최약 간선), `rules`(레이어 YAML), `metrics`(Martin)
- 릴리스 워크플로: 태그 → jar + Gradle 플러그인 포털 (또는 GitHub Release + `brew`/`sdkman`). 릴리스 전에 **압축 푼 산출물로 CLI 계약을 재검증**한다 — cartograph 첫 릴리스가 여기서 막혔다
- 0.1.0

## 세션 운영

- 한 세션은 한 Phase 의 일부만. 세션 시작 시 `git status --short --branch` 와 이 문서의 진행 표를 본다
- PR 마다 GLM 리뷰. 리뷰의 주장을 코드로 확인하고, 거절은 이유와 함께 남긴다. cartograph PR #7 · #8 코멘트가 형식의 예다
- 도그푸딩에서 나온 모든 오탐은 코퍼스 케이스가 된다. 예외 없음

## 진행 표

| Phase | 상태 | 비고 |
|---|---|---|
| 0 원천 결정 | 완료 | A(바이트코드 + metadata)를 주 원천으로 결정 |
| 1 골격 | 완료 | 실제 프로젝트 DOT, line 90% gate, CI 완료 |
| 2 보존 규칙 · dead | 완료 | 37 retained/2 report 코퍼스, Room KSP sibling, runtime callback, 다섯 로컬 프로젝트 상위 finding 오탐 0 |
| 3 도입 경로 | 완료 | deterministic baseline, NUL-safe `--since`, Gradle/GitHub/SARIF/JSON, Gradle strict 도입 계약 완료 |
| 4 에이전트 표면 | 완료 | cartograph 호환 query, 계량 limitations, bridge-facts v1, 설치형 agent skill |
| 5 순환 · 규칙 · 지표 · 릴리스 | 완료 | architecture 분석, 0.1.0 재현 가능 packaging, JDK 17/21과 압축 해제 산출물 검증 완료; tag/publish 미실행 |
