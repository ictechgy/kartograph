# 원천 결정 기록

상태: **결정 완료** (2026-09-04)

## 결정

kartograph의 주 그래프 원천은 **JVM 바이트코드 + `kotlin-metadata-jvm`**으로 한다.

- ASM으로 class·method·field 정점과 call·field access·inheritance·annotation 간선을 읽는다.
- 공식 `org.jetbrains.kotlin:kotlin-metadata-jvm`으로 Kotlin visibility, data class, 확장 함수 수신자 등
  JVM descriptor만으로 잃는 사실을 복원한다.
- `SourceFile`·`LineNumberTable`·Kotlin debug 정보를 best-effort 위치 근거로 쓴다. 정확히 복원하지
  못한 위치는 추측하지 않고 응답의 `limitations`에 싣는다.
- KSP/kapt/Hilt/Compose가 만든 class도 같은 그래프에 넣되 `synthesized`로 표시한다.
- R8 `usage.txt`는 선택적 대조 오라클로만 쓴다. keep 규칙은 Phase 2의 보존 근거다.
- Analysis API Standalone과 KSP 프로세서는 v0.1 제품 의존성에 넣지 않는다.

이 선택은 후보 A가 가장 많은 실제 산출물을 가장 짧은 시간에 읽고, Java와 컴파일러 생성 코드를
추가 사용자 설정 없이 포함했기 때문이다. 후보 B는 소스 위치가 가장 정확하지만 classpath·variant·생성
소스 구성이 빌드 시스템 하나를 다시 만드는 수준이고 Standalone 수명주기가 아직 불안정하다. 후보 C의
KSP 공개 API는 expression과 statement를 제공하지 않아 호출 그래프를 만들 수 없다.

## 결정 기준과 측정 규칙

다섯 로컬 프로젝트와 공개 `android/nowinandroid`를 다음 기준으로 비교했다.

1. 그래프 정점과 간선 수
2. Hilt/KSP 생성 클래스와 Compose 변환 결과의 가시성
3. 선언을 파일과 줄로 되돌릴 수 있는 비율
4. 사용자가 준비해야 하는 빌드·classpath·plugin 설정
5. 인덱스 생성 또는 분석 실행 시간

후보 A의 간선은 중복을 제거한 `(출발 정점, 도착 정점, 종류)` 튜플이다. 전체 간선은 SDK와 외부
라이브러리로 향하는 관계도 포함하고, 괄호 안에는 양 끝 정점이 모두 프로젝트에 있는 내부 간선을 썼다.
후보 B는 PSI의 call site를 한 번씩 센다. 두 후보의 선언 단위가 달라 정점 수끼리 직접 비교하지 않는다.

프로젝트 소스나 심볼 이름은 기록하지 않고 집계값과 재현 조건만 남겼다.

## 환경과 빌드 기준선

- JDK: Homebrew OpenJDK 17.0.20
- Android SDK: API 35·36, Build-Tools 34.0.0
- 바이트코드 리더: ASM 9.9
- metadata 검증: `kotlin-metadata-jvm` 2.4.10
- 로컬 variant: `debug`, sample D는 `productionDebug`
- 공개 표본: `android/nowinandroid` commit `12f80da`, `demoDebug`, Kotlin 2.3.0, KSP 2.3.4,
  Hilt 2.59, AGP 9.3.2

| 프로젝트 | 추적 Kotlin 파일 | 빌드 | wall time | 비고 |
|---|---:|---|---:|---|
| local sample A | 20 | 성공 | 61초 | third-party map SDK D8 stack-map 경고가 있었으나 패키징 성공 |
| local sample B | 121 | 성공 | 39초 | 14개 모듈 |
| local sample C | 82 | 성공 | 5초 | warm offline build |
| local sample D | 38 | 성공 | 55초 | `assembleProductionDebug` |
| local sample E | 22 | 성공 | 6분 31초 | Gradle·SDK 최초 설치 포함 |
| nowinandroid | 310 | 성공 | 4분 39초 | 최초 의존성 해석, 551 tasks |

초기 sample A 529개 집계는 `.omx`와 `.worktrees`의 복제본을 포함한 잘못된 값이었다. 추적 파일을
기준으로 고쳤고, 실험 중 실제 위치 매핑도 worktree를 가지치기하자 1/99에서 99/99로 회복됐다.
Phase 1의 파일 탐색기는 `.git`, `.gradle`, `.omx`, `.worktrees`, `.claude`, `node_modules`를 한 벌의
가지치기 규칙으로 관리해야 한다.

AGP 8 계열은 주로 `build/tmp/kotlin-classes/<variant>`를 썼지만, AGP 9 built-in Kotlin은
`build/intermediates/built_in_kotlinc/<variant>/compile<Variant>Kotlin/classes`를 썼다. javac,
Hilt Java compile, 순수 JVM 모듈도 별도 class root를 만들었다. Gradle plugin은 이 경로들을 task에서
전달하고, 독립 CLI의 자동 탐색은 명시적인 `limitations`가 있는 best-effort 기능이어야 한다.

## 후보 A — JVM 바이트코드와 Kotlin metadata

ASM으로 클래스·메서드·필드를 정점으로 만들고, 메서드 호출·필드 접근·상속·어노테이션을 간선으로
추출했다. 시간은 소스 위치 대조를 위한 파일 탐색까지 포함한다.

| 프로젝트 | 정점 | 간선 (내부) | `SourceFile` 단일 매핑 | 줄 정보 중 유효 위치 | Kotlin metadata class | Compose 변환 method | 시간 |
|---|---:|---:|---:|---:|---:|---:|---:|
| local sample A | 1,316 | 4,873 (2,422) | 99/99 (100%) | 561/561 (100%) | 98 | 0 | 89ms |
| local sample B | 10,707 | 38,825 (17,129) | 928/997 (93.1%) | 3,737/3,837 (97.4%) | 1,001 | 378 | 880ms |
| local sample C | 3,242 | 11,727 (4,441) | 331/356 (93.0%) | 1,033/1,079 (95.7%) | 356 | 171 | 248ms |
| local sample D | 2,400 | 8,612 (3,892) | 194/206 (94.2%) | 850/870 (97.7%) | 206 | 84 | 335ms |
| local sample E | 445 | 1,584 (747) | 28/29 (96.6%) | 210/216 (97.2%) | 28 | 0 | 164ms |
| nowinandroid | 7,285 | 25,874 (8,641) | 559/838 (66.7%) | 2,316/3,036 (76.3%) | 634 | 503 | 360ms |

모든 표본의 class 파일을 오류 없이 읽었다. `nowinandroid`에서는 다음 생성 사실도 보였다.

- Hilt/Dagger 이름 패턴의 생성 class 137개
- `build/generated` 소스로 역매핑된 class 85개
- descriptor에 `androidx.compose.runtime.Composer`가 들어간 변환 method 503개

공개 [Kotlin Metadata JVM 문서](https://kotlinlang.org/docs/metadata-jvm.html)는 현재 좌표를
`org.jetbrains.kotlin:kotlin-metadata-jvm`으로 안내하고 Kotlin compiler·stdlib과 같은 버전을 쓰도록
한다. Kotlin 2.0부터 Stable이며 표준 라이브러리와 같은 호환성 보장을 받는다. 읽기 전용 도구에는
새 metadata도 허용하는 `readLenient()`가 맞다.

Kotlin 2.0.21 기본 metadata로 `internal` class, data class, `internal` function, extension receiver가
각각 2개인 표본을 만들었다.

| 리더 | 읽은 파일 | 실패 | `internal` class | data class | `internal` function | extension receiver |
|---|---:|---:|---:|---:|---:|---:|
| 구형 `kotlinx-metadata-jvm` 0.5.0 | 4 | 4 | — | — | — | — |
| 공식 `kotlin-metadata-jvm` 2.4.10 | 4 | 0 | 2 | 2 | 2 | 2 |

구형 `org.jetbrains.kotlinx` 좌표는 Kotlin 2.x에 쓸 수 없고, 공식 현재 API는 후보 A에 필요한 Kotlin
사실을 모두 복원했다.

### release와 R8 대조

R8 입력 전 release class는 debug와 마찬가지로 `SourceFile`과 줄 번호를 유지했다. minify가 켜진 기존
release의 R8 `usage.txt`를 프로젝트 패키지로 한정해 집계한 결과는 다음과 같다.

| 프로젝트 | 완전히 제거된 class | 제거된 member |
|---|---:|---:|
| local sample C | 43 | 863 |
| local sample D (`productionRelease`) | 125 | 923 |
| local sample E | 15 | 222 |

`usage.txt`는 해당 빌드의 keep 규칙과 전체 의존성 아래에서 R8이 제거한 결과라 도달성 결과를 대조하는
오라클로 쓸 수 있다. 호출 관계, 소스 위치, 보존 경로는 주지 않으므로 그래프 원천은 아니다.

### 후보 A가 못 보는 것

- debug 속성이 제거된 class의 파일·줄 위치
- `SourceFile` 이름이 여러 모듈·생성 디렉터리에서 겹칠 때의 유일한 파일 경로. `nowinandroid`가 이
  한계를 실제로 보였다
- 인라인된 호출을 원래 call site로 되돌린 관계
- Compose 변환 전 함수와 합성 lambda의 완전한 1:1 대응
- bytecode에 남지 않은 source-only 사실과 문자열 기반 런타임 참조

위 항목은 숨기지 않고 프로젝트별 `limitations`로 계량한다. 위치를 복원하지 못해도 그래프 정점과
간선은 유지하고, 존재하지 않는 위치를 만들어 내지 않는다.

## 후보 B — Analysis API Standalone

Kotlin은 Analysis API의 stable 부분에 source·binary 호환성을 제공하지만, 공식
[Analysis API 문서](https://kotlin.github.io/analysis-api/index_md.html)는 command-line용 Standalone만은
개발 중이며 비호환 변경 대상이라고 명시한다. JetBrains의 Maven 산출물은 완전한 전이 의존성을 제공하지
않아 detekt 2.0.0-alpha.6이 `*-for-ide` 모듈 여섯 개를 다시 묶은 JAR로 실험했다.

tracked `src/main` source, JDK, 실제 Kotlin compile task의 classpath를 넣어 얻은 결과다.

| 프로젝트 | Kotlin/Java 파일 | classpath 항목 | source 선언 | resolved/unresolved call | 위치 | 시간 |
|---|---:|---:|---:|---:|---:|---:|
| local sample A | 11/0 | 46 | 1,239 | 1,817/0 | 100% | 3.68초 |
| local sample B | 76/0 | 71 | 7,260 | 7,145/114 | 100% | 7.27초 |
| local sample C | 46/0 | 47 | 1,679 | 1,545/0 | 100% | 3.63초 |
| local sample D | 18/0 | 69 | 1,537 | 1,407/9 | 100% | 5.03초 |
| local sample E | 18/0 | 56 | 598 | 536/34 | 100% | 3.03초 |
| nowinandroid | 264/140 | 359 | 4,858 | 3,880/559 | 100% | 7.38초 |

`nowinandroid`에는 생성 파일 163개와 생성 Kotlin 선언 668개가 포함됐다. source root를 주지 않으면
이들은 보이지 않는다. local sample E에 classpath를 빼면 resolved/unresolved가 188/382로 악화돼 call의
67.0%를 잃었다. classpath 56개를 넣으면 536/34가 됐다.

동일 프로브와 통합 테스트는 Kotlin/AA 2.3.21 계열과 2.4.10 계열에서 모두 컴파일되고 통과했다.
그러나 다음 운영 비용은 버전 테스트와 별개로 실제 발생했다.

- variant마다 Kotlin compile task의 `libraries`를 수집해야 했다. 루트 task가 다른 project의
  configuration을 읽는 방식은 Gradle 9.7 exclusive-lock 검사에서 실패해 project별 task가 필요했다.
- local sample A는 configuration cache를 꺼야 했고, nowinandroid는 Isolated Projects와 configuration
  cache를 함께 꺼야 classpath를 추출할 수 있었다.
- source module을 평평하게 합친 실험이라 실제 module/friend dependency 모델을 제품에서 다시 만들어야 한다.
- 공식 웹 문서의 `analyze` package와 2.4.10 배포 JAR의 package가 이미 달랐다.
- session disposable만으로 CLI process가 끝나지 않았다. 전역 cleanup API는 배포 조합에서
  `NoSuchMethodError`가 나 프로세스 격리와 강제 종료가 필요했다.

### 후보 B가 못 보는 것

- 명시하지 않은 generated source와 classpath에 없는 symbol
- Compose IR 변환 이후의 실제 method shape
- source가 없는 dependency 내부 구현과 R8 이후 결과
- 정확한 Gradle module/variant/friend 관계를 재구성하지 않은 평면 session의 의미

소스 위치 품질은 가장 좋지만, A보다 8~41배 느리고 입력 준비가 분석보다 복잡했다. v0.1에서 이 비용과
불안정 API를 떠안을 근거가 없다.

## 후보 C — KSP 또는 FIR 빌드 시점 덤프

공식 [KSP 문서](https://kotlinlang.org/docs/ksp-overview.html)는 KSP processor가 expression과
statement를 검사할 수 없다고 명시한다. 캐시된 KSP API 1.9.0-1.0.13, 1.9.24-1.0.20,
2.0.21-1.0.28의 ABI도 `Resolver`와 declaration/type API만 제공하고 function body나 call expression은
제공하지 않았다.

`nowinandroid`의 실제 KSP build에서 KSP 생성 source 143개, Hilt 경로 8개, 기타 생성 source 12개를
확인했다. 별도 processor를 app module에 주입해 다음 JSON 집계를 실제로 만들었다.

| files | declarations | inheritance edges | annotation edges | call edges |
|---:|---:|---:|---:|---:|
| 9 | 96 | 13 | 24 | 0 |

`callEdges`는 구현을 빠뜨린 값이 아니라 KSP 공개 API에 body 표현이 없어 만들 수 없는 값이다.
`:app:kspDemoDebugKotlin --rerun-tasks`는 warm dependency 상태에서도 선행 작업 274개를 실행해 baseline
23초, probe 주입 시 25초가 걸렸다.

FIR compiler plugin은 body를 볼 수 있지만 compiler 내부 API와 사용자 compile에 결합된다. 후보 B보다
더 강한 버전 고정과 설치 비용을 지면서 후보 A가 이미 제공하는 간선을 다시 만드는 선택이라 v0.1에서
사용하지 않는다.

### 후보 C가 못 보는 것

- KSP: function body의 call·field access와 Compose IR 변환 결과
- KSP가 실행되지 않는 module과 build variant
- processor를 설치하지 않은 기존 build 산출물
- FIR plugin: 고정한 compiler 버전 밖의 호환성

## 최종 비교

| 기준 | A. bytecode + metadata | B. Analysis API Standalone | C. KSP/FIR dump |
|---|---|---|---|
| call graph | 실제 JVM call·field instruction | source call, classpath 누락 시 손실 | KSP 0; FIR만 가능 |
| generated code | Hilt/KSP/Compose compile 결과 전부 | generated source를 직접 넣은 것만 | processor가 보는 round만 |
| Java | 같은 class model로 포함 | Java PSI/module 구성이 별도 | KSP Java model은 가능, body 불가 |
| source 위치 | debug 정보 기반 best-effort | PSI 기준 100% | 선언 위치만 정확 |
| 준비 비용 | build된 class root | source + JDK + 46~359 classpath + generated roots + module model | 사용자 build에 plugin 설치 |
| 분석 비용 | 89~880ms | 3.03~7.38초 | warm KSP 경로 23초 |
| API 안정성 | JVM class format + Stable metadata API | Standalone은 비호환 변경 가능 | KSP 안정 표면은 좁고 FIR은 내부 API |
| v0.1 역할 | **주 원천** | 제품에서 제외, 교차 검증에만 사용 | 제품에서 제외 |

## Phase 1에 고정하는 제약

1. `index`는 class root 발견과 ASM/metadata 해석을 adapter 뒤에 둔다.
2. class root는 Gradle plugin이 task에서 전달하는 경로를 정본으로 하고 CLI 자동 탐색은 별도 adapter다.
3. `GraphNode`는 JVM identity와 Kotlin source identity를 구분하고 합성 여부를 값으로 가진다.
4. 위치는 optional이다. ambiguous/missing 위치를 임의 선택하지 않는다.
5. ASM과 metadata가 만든 동일 member를 JVM signature로 결합한다.
6. R8 결과와 keep 규칙은 그래프 사실을 덮어쓰지 않고 보존·검증 근거로 추가한다.
7. Analysis API나 compiler plugin을 core·analysis 모듈에 의존성으로 넣지 않는다.
