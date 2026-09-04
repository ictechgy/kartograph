# kartograph 리서치 노트

2026-09-04 기준. **확인됨** 은 1차 출처를 직접 읽은 것, **확인 필요** 는 GLM 또는 기억에서 나온 주장이다. 확인 필요 항목 위에 설계를 세우지 않는다.

## 확인됨

### Kotlin Analysis API

- 존재하며 공식이다. 문서: https://kotlin.github.io/analysis-api/index_md.html
- 안정성 원문: *"The library offers both source and binary backward compatibility for its stable parts."*
- **Standalone 모드 원문: *"the Analysis API provides the Standalone mode for command-line tool developers, it is currently in development and subject to incompatible changes."*** — CLI 도구가 필요한 바로 그 모드가 불안정하다
- 대상 컴파일러: *"The API mainly targets the K2 Kotlin compiler, but there is also limited support for the legacy 1.0 compiler."*
- Standalone Maven 모듈은 완전한 전이 의존성을 제공하지 않는다. detekt 2.0.0-alpha.6은
  `analysis-api-*-for-ide` 여섯 모듈을 두 shadow JAR로 다시 묶는다
- 실측: Kotlin/AA 2.3.21과 2.4.10 사이에서 같은 프로브가 컴파일됐지만, classpath 46~359개와
  generated source, module/variant 구성이 필요했다. 전역 종료 API는 배포 조합에서 `NoSuchMethodError`
- 함의: v0.1 제품 의존성에서 제외한다. 자세한 값은 `docs/DECISION-truth-source.md`

### Kotlin Metadata JVM

- Kotlin 2.0부터 Stable. 좌표는 `org.jetbrains.kotlin:kotlin-metadata-jvm`이고 Kotlin compiler·stdlib과
  같은 버전으로 배포된다. 표준 라이브러리와 같은 하위 호환성 보장을 받는다
- 읽기 전용 도구는 더 새 metadata를 허용하는 `KotlinClassMetadata.readLenient()`를 쓸 수 있다.
  수정·재기록에는 지원 범위를 엄격히 검사하는 `readStrict()`가 맞다
- Kotlin 2.0.21 표본을 2.4.10 리더로 읽어 `internal`, data class, extension receiver를 모두 복원했다
- 구형 `org.jetbrains.kotlinx:kotlinx-metadata-jvm:0.5.0`은 Kotlin 2.0 metadata를 읽지 못했다

### KSP

- 공식 문서상 KSP processor는 expression과 statement를 검사할 수 없다. class·function·property 선언,
  annotation, type은 보지만 function body의 call·field access는 못 본다
- 세 KSP API 버전의 class ABI에도 body/call expression API가 없었다
- nowinandroid 실제 build는 KSP source 143개를 만들었다. 임시 processor도 files 9, declarations 96,
  inheritance edges 13, annotation edges 24를 JSON으로 썼지만 call edges는 0이었다

### Compose와 AGP 산출물

- 바이트코드에서 `androidx.compose.runtime.Composer`가 추가된 method를 로컬 Compose 프로젝트와
  nowinandroid에서 확인했다. 공개 표본에는 이런 method가 503개 있었다
- AGP 8은 `build/tmp/kotlin-classes/<variant>`, AGP 9 built-in Kotlin은
  `build/intermediates/built_in_kotlinc/<variant>/compile<Variant>Kotlin/classes`를 사용했다
- Hilt Java compile, javac, 순수 JVM module은 또 다른 class root를 만든다. Gradle plugin이 task에서
  root를 전달하는 방식을 정본으로 삼아야 한다

### 기존 도구

- **bye-bye-dead-code** (https://github.com/dipien/bye-bye-dead-code): Gradle 플러그인. README 원문 *"This task depends on R8 which detects unused code when you build your project."* `minifyEnabled` 필수. README 에 `// TODO` 섹션(Features, Usage)이 남아 있다. 유지보수 상태 불명
- **SearchDeadCode** (https://github.com/KevinDoremy/SearchDeadCode): Rust, 구문 파싱만("No JDK. No Gradle build"). 확신도 4단계(Confirmed/High/Medium/Low), JaCoCo/Kover/R8 `usage.txt` 로 승격. 원문 *"static analysis cannot see `Class.forName()`"*. 매니페스트 · 레이아웃 · DI 어노테이션 기반 보존을 자동 처리한다고 주장. 별 13개, 커밋 278개
- 컴파일러 수준 해석을 쓰는 Kotlin 전역 미사용 코드 도구는 **찾지 못했다**

### JS/Dart 쪽 (isthmus 와의 관계)

- 자세한 것은 `../dartograph/docs/RESEARCH.md`, `../isthmus/docs/RESEARCH.md`

## 확인 필요

- **GLM 주장: "JetBrains 의 새 Kotlin LSP 가 Analysis API Standalone 위에 있다"** — GLM 확신 중간, 미확인. 사실이면 Standalone 이 실사용되고 있다는 신호
- **AGP 기본 keep 규칙의 위치와 형식** — `proguard-android-optimize.txt` 가 SDK 안에 있는지, AGP 가 생성하는지(`build/intermediates/default_proguard_files`)
- **`chrisbanes/tivi` 의 현재 빌드 가능 여부** — Views 기반 추가 표본이 필요할 때 확인
- **Detekt 의 타입 해석 모드** — 존재하나 "실험적"이라는 GLM 주장. 확인 필요

## 로컬 도그푸딩 대상 (2026-09-04 스캔)

| 대상 | `.kt` | `.java` | 확인된 것 |
|---|---|---|---|
| local sample A | 20 | 0 | 빌드 성공. 초기 529는 `.omx`·`.worktrees` 복제본을 포함한 오집계 |
| local sample B | 121 | 0 | 빌드 성공. Compose(`org.jetbrains.kotlin.plugin.compose`) |
| local sample C | 82 | 0 | Compose. iOS 자매 앱과 출력 형태를 비교함 |
| local sample D | 38 | 0 | Compose |
| local sample E | 22 | 0 | View 기반 |

파일 수는 `git ls-files` 기준이다. 로컬 표본에는 추적 Java 파일이 없다. 공개 nowinandroid commit
`12f80da`는 추적 Kotlin 310개이며 Hilt+Compose+KSP/AGP 9 `demoDebug` 빌드에 성공했다.

## cartograph 에서 배운 것 중 여기 그대로 적용되는 것

`../cartograph/CHANGELOG.md` 의 0.1.0 ~ 0.4.0 항목이 원문이다. 요약:

- 인덱스가 못 보는 채널(리플렉션 · 문자열 이름)이 오탐의 전부다. Android 는 그 채널이 Swift 보다 많다(매니페스트 · XML · DI · JNI)
- 단위 테스트는 손으로 만든 스냅샷을 보므로 컴파일러가 실제로 무엇을 기록하는지 검증하지 못한다. 실제로 빌드되는 코퍼스가 필요하다
- 소스 탐색이 성능 병목이었다(210k 항목 → 가지치기로 33s → 2.2s). `build/`, `.gradle/`, `node_modules/` 가지치기를 처음부터
- 스토어 루트의 mtime 은 믿을 수 없다(하위 디렉터리에 쌓인다). 신선도 마커를 실측으로 정한다
- 에이전트용 출력은 "지워도 된다"를 절대 말하지 않고, 한계를 매 응답에 싣고, 규칙을 통과한 뒤 무엇을 할지를 적는다

## 출처

- Kotlin Analysis API 문서 — https://kotlin.github.io/analysis-api/index_md.html
- Kotlin Metadata JVM 문서 — https://kotlinlang.org/docs/metadata-jvm.html
- KSP 개요 — https://kotlinlang.org/docs/ksp-overview.html
- detekt Analysis API 재패키징 — https://github.com/detekt/detekt/tree/v2.0.0-alpha.6/detekt-kotlin-analysis-api
- nowinandroid — https://github.com/android/nowinandroid
- bye-bye-dead-code — https://github.com/dipien/bye-bye-dead-code
- SearchDeadCode — https://github.com/KevinDoremy/SearchDeadCode
- Kotlin 정적 분석 도구 개관(allegro, 2026-03) — https://blog.allegro.tech/2026/03/static-code-analysis-kotlin.html (미독. 세션에서 읽을 것)
