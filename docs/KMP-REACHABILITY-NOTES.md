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
