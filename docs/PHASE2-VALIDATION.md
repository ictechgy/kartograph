# Phase 2 validation

2026-09-04에 로컬 Android 프로젝트 다섯 개를 현재 `dead` pipeline으로 다시 분석했다. 개인 소스의
경로와 symbol 이름은 공개 문서에 남기지 않고 집계와 재현 조건만 기록한다.

## 입력

- Android variant class root와 순수 JVM module의 `build/classes/{kotlin,java}/main`을 함께 전달했다.
- 프로젝트가 명시한 `proguard-rules.pro`가 있으면 함께 전달했다.
- manifest, resource root, namespace는 분석 variant와 일치시켰다.
- 각 프로젝트의 `unreachable` 상위 10건 또는 전체 finding이 10건 미만이면 전부를 main source에서
  수동 대조했다.

## 결과

| 대상 | 전체 finding | 수동 대조 | 확인된 false positive |
|---|---:|---:|---:|
| A | 1 | 1 | 0 |
| B | 14 | 10 | 0 |
| C | 1 | 1 | 0 |
| D | 1 | 1 | 0 |
| E | 0 | 0 | 0 |

검증 중 발견한 새 오탐 계열은 실제 Android 코퍼스에 먼저 재현한 뒤 수정했다.

- interface call에서 project implementation으로 이어지는 override dispatch
- Kotlin compiler synthetic callback의 runtime-invoked body
- reached class의 JVM class initializer
- reached `ViewModel`, `WebViewClient`, Camera2 callback의 framework-invoked member
- Kotlin/Java compile-time constant owner의 사라진 bytecode reference
- compiler synthetic method가 기록한 line number `0`

최종 코퍼스는 39개 retained case와 2개 report case를 exact 비교한다. Room과 Moshi 표본은 KSP가 실제로
`*_Impl.class`와 nested `*JsonAdapter.class`를 생성하고 Kotlin compiler가 compile한 산출물을 사용한다.
