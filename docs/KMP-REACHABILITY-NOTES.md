# KMP 도달성 — 범위 조사 노트

상태: **조사 노트**. 구현 계획이 아니며, 어떤 조각이 제품 가치가 있는지와 원천 계약과의 충돌을
기록한다. PRD의 "나중에(v0.2+)" 항목이고, 경쟁 도구 조사에서 JVM/Android 어느 도구도 다루지 않는
녹색지대다.

## 현재 위치

- kartograph의 원천은 **JVM bytecode + 공식 kotlin-metadata-jvm**이다
  ([DECISION-truth-source](DECISION-truth-source.md)). Kotlin Multiplatform 프로젝트의 `jvm()` target은
  common·platform source set을 일반 JVM class로 컴파일하므로, 지금도 KMP 프로젝트의 **JVM target
  산출물**은 그대로 분석된다. 여기에 제품 격차는 없다.
- 격차는 (1) common source set이 `klib`/metadata로만 존재하는 native·js target, (2) `expect`/`actual`
  링크 관계, (3) Kotlin/Wasm·JS 상의 참조 복원에 있다.

## 기록해 둘 사실

- JVM 컴파일에서 `expect` 선언은 대응 `actual`과 같은 모듈의 일반 선언으로 나타난다. 메타데이터의
  Kotlin flags와 `MULTIFILE_CLASS` 등으로 expect/actual를 구분할 수 있는지는
  kotlin-metadata-jvm 2.4.x 기준 확인이 필요하다(이 노트는 확인 전 상태다).
- `commonMain`만 바꿨을 때 iOS target에 미치는 영향은 JVM 그래프로 증명할 수 없다. 이 사실을
  숨기지 않는 한계 표기가 제품 계약(`dead`의 계량된 limitations)과 일치한다.
- `impact`는 저장된 그래프 위의 질의이므로, KMP 지원의 최소 즉시 가치는 **JVM target snapshot으로
  common 모듈 코드의 영향 조사**다. 새 원천(klib 파서)은 필요 없다.

## 후속 결정이 필요한 것

1. **JVM-target 우선 문서화만 할지.** Gradle plugin이 KMP 프로젝트의 jvm target variant를 자동 캡처
   대상으로 안내하는 문서를 추가하는 최소 작업. 원천 변경이 없어 회귀 위험이 낮다.
2. **expect/actual 링크를 근거로 노출할지.** metadata 기반 `EXPECT_ACTUAL` 보존 근거 또는 간선 출처로
   추가하는 작업. `EdgeKind`·`RetentionReason` enum 확장이 필요하고 코퍼스 양방향 케이스가 선행된다.
3. **klib 원천 도입 여부.** 원천 결정 실험(Phase 0)에 준하는 새 비교 실험이 필요한 규모다. 현재
   계약("원천 변경에는 비교 실험이 먼저다")상 단독으로 진행하지 않는다.

결론: 당장의 제품 가치는 (1)이다. (2)·(3)은 실험 계약 없이 착수하지 않는다.

## 2026-09-16 실험 결과와 결정

Kotlin 2.4.10 multiplatform plugin, `jvm()` target만 있는 최소 표본(`commonMain`: `expect class Platform`,
`expect fun currentTimeMillis`, `expect val lineSeparator`, `expect object Registry`, 이들을 호출하는 `Greeter`,
`actual` 없는 `CommonOnly`; `jvmMain`: 대응 `actual` 4개와 `JvmOnly`)을 JDK 17로 컴파일하고 JVM 산출물을 조사했다.

- **산출 class:** `CommonOnly`, `Greeter`, `JvmKt`, `JvmOnly`, `Platform`, `Registry` 6개. `expect` top-level 선언을 담는
  `CommonKt` facade는 생기지 않고, top-level 함수·프로퍼티는 `actual` 쪽만 `JvmKt`에 남는다. class/object의 `actual`은
  각자의 class(`Platform`, `Registry`)로 나온다.
- **expect 흔적:** kotlin-metadata-jvm 2.4.20의 `Attributes.isExpect`는 class·function·property 전부 false다.
  `Platform.class`의 `SourceFile`은 `Jvm.kt`(actual의 파일)이고 상수 풀에 `Common.kt`나 `expect` 문자열이 없다.
  즉 **이 표본(Kotlin 2.4.10, `jvm()` target, JDK 17, 기본 컴파일러 옵션)의 JVM 산출물에서는 어느 선언이 `expect`였는지,
  어느 `actual`과 짝인지 복원할 근거를 찾지 못했다.** 설계 관점에서도 JVM ABI에는 expect/actual 개념이 없고 common·platform
  source set이 class 생성 전에 하나로 컴파일되므로 일반적으로 남지 않을 것으로 보이지만, 이는 실측이 아닌 추론이다.
  `actual typealias`, 다른 컴파일러 옵션·버전은 측정하지 않았다. klib의 common metadata에는 `expect` 정보가 남는 것이
  일반적이므로 이 결론은 JVM 산출물에 한정한다.
- **kartograph 동작:** `snapshot --classes build/classes/kotlin/jvm/main --include-paths`는 `commonMain`·`jvmMain`
  소스 위치를 모두 해석했고, `impact 'method:probe/Platform#name()Ljava/lang/String;'`는
  `Greeter#greet`(`src/commonMain/kotlin/probe/Common.kt`)를 직접 영향으로 보고했다. 즉 (1)의 가치는 실제로 있다.

결정:

1. **(1) JVM target 문서화 — 완료.** [IMPACT](IMPACT.md#kotlin-multiplatform-프로젝트의-jvm-target)에 CLI 경로와 범위·한계를 적었다.
   Gradle plugin 자동 캡처의 KMP jvm compilation 연결은 검증하지 않았고 문서에도 그렇게 썼다.
2. **(2) expect/actual 근거 노출 — 현재 원천(JVM 산출물)으로는 착수하지 않는다.** 위 실험에서 JVM 산출물의 bytecode·metadata에
   링크 근거를 찾지 못했으므로 `EXPECT_ACTUAL` 근거를 JVM 산출물의 metadata에서 만들 수 없다. 코퍼스 케이스로 고정할 대상
   사실 자체가 없어 설계를 진행하지 않는다. 이 항목은 (3)의 klib/common metadata 원천이 있을 때 다시 연다.
3. **(3) klib 원천 — 변경 없음.** 원천 결정 실험 없이는 진행하지 않는다.

원본 표본·컴파일 산출물·metadata 프로브·CLI 출력은 로컬 증거 `build/reports/kmp-experiment-20260916/`에 보존했다.
이 경로는 Git 밖이며 위 항목들이 재현에 필요한 사실(표본 구성·버전·관찰값)을 모두 담도록 썼다.

