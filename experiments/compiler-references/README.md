# Compiler reference enrichment experiment

이 실험은 bytecode 주 원천을 바꾸지 않고 인라인 전 상수 사용처를 같은 입력의 그래프에 보강할 수 있는지 비교한다.
제품 CLI/plugin에는 이 collector를 설치하거나 자동으로 실행하지 않는다. Kotlin compiler API 의존성은 이 독립
실험 빌드에만 있으며 배포 runtime에 포함되지 않는다.

## 재현

JDK 17과 저장소의 Kotlin 2.4.10 빌드 의존성이 준비된 환경에서 루트 기준으로 실행한다.

```sh
python3 experiments/compiler-references/run.py
```

모든 Gradle 호출은 offline이다. 최초 환경에서는 일반 제품 빌드로 의존성을 준비해야 한다. Java collector는
JDK의 `JavacTask`/`Trees` API로 의미 해석 후, bytecode 생성 전에 참조를 수집한다. Kotlin collector는
`FirAdditionalCheckersExtension`의 해석된 const property 참조와 IR extension의 field 접근을 각각 관찰한다.
컴파일러 로그·상수 값·절대경로를 sidecar에 기록하지 않는다.

## 결과와 결정

2026-09-08, JDK 17 / Kotlin 2.4.10, 단일 JVM fixture 전체 컴파일에서 검증했다.

| 입력 / 관측 단계 | 실제 상수 사용처 4개 중 연결한 간선 |
| --- | ---: |
| ASM + Kotlin metadata 기본 그래프 | 0 |
| Kotlin IR extension (Kotlin 사용처 3개) | 0 / 3 |
| javac Trees + Kotlin FIR | 4 |
| 입력 일치 검사 후 실험 그래프 보강 | 4 |

Java static final, Kotlin object const, top-level const의 `@file:JvmName`, companion const를 포함한다.
상수 값이 같은 미사용 선언과 동일 이름의 지역 변수는 참조 대상으로 수집되지 않는다. variant 불일치,
source revision 불일치, class 내용 불일치, 그래프 불일치, 없는 대상 선언의 5가지 보강을 거부한다.
기록은 `build/reports/compiler-references/{result,sidecar,bytecode,enriched}.json`에 생성한다.
로컬 warm offline 전체 실험 12.459초는 빌드·프로세스 시작을 포함하며 인덱싱 성능 비교값이 아니다.

**채택 판단:** 인라인 전 semantic reference 수집은 가능하며 Kotlin에서는 FIR 시점이 필요하다. IR의 선언 getter에
상수 field 접근이 남더라도 원래 사용 함수의 간선이 남았다는 뜻은 아니다. 기존 bytecode 주 원천을 유지하고,
제품에 일반 입력으로 도입하기 전에는 JVM identity 매핑과 증분 빌드 수명주기를 더 검증해야 한다.

## 실험의 의도적인 범위

- Java는 해석된 element와 erased type으로 JVM identity를 만든다. Kotlin은
  [fixture-mapping.json](fixture-mapping.json)의 **명시적인 fixture 전용 매핑**을 쓴다. companion의 JVM field
  owner 이동과 `@JvmName`을 이름 추측으로 일반화하지 않는다. 매핑 없는 참조·소비되지 않는 key·중복 JVM identity는 실패한다.
- 보강은 sidecar의 source/class SHA256, variant, 그래프 SHA256이 현재 입력과 모두 일치하고 두 선언이 실제
  그래프에 존재할 때만 `compilerReference` 간선을 추가한다. 이 지문은 변조 인증이나 artifact 서명이 아니다.
- 보강 결과는 실험용 JSON이다. 제품 `query`/`dead` 입력을 확대하거나 상수 보존 정책을 해제하지 않는다.
- Kotlin compiler extension API는 2.4.10에 고정한다. Android variant 자동 연결, KMP, source/bytecode 이름의
  일반적 매핑, 증분·병렬·실패한 컴파일의 sidecar 수명주기는 검증하지 않았다.
- source revision은 이 fixture의 source 내용 집합 지문이다. 전체 Git commit이나 의존성 잠금 파일을 대신하지
  않으며, 제품화에는 빌드 variant·compiler/plugin·classpath 지문까지 갖춘 입력 계약이 필요하다.

variant 필드는 이 실험의 선택 label이며 실제 Android build variant 추출을 검증한 것이 아니다. class/source
내용 지문은 별도로 비교한다. Java compiler 버전은 실행한 javac에서, Kotlin 버전은 실행 중인 compiler
extension에서 읽는다. 저장된 `bytecode.json` 파일 바이트의 SHA256이 sidecar에 기록된다.
`result.json`의 개수는 통과한 관측·거부 결과로 계산하며 wall time은 stdout에만 기록한다.
