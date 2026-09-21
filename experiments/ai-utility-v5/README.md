# v5 완료 — matched 입력으로 16회 비교

2026-09-21, 고정한 4개 공개 모듈에서 source-only/MCP 각 2회, **16회 전부 실행**했다.
인프라 오류는 0건이며 선택적 재시작은 없었다. **이번 조건에서도 AI 효용 우위는 확인하지 못했다.**
이는 일반적인 AI 생산성의 결론이 아니며, 아래 사례별 검토 anchor와 응답 형식 결과다.

## 결과

값은 두 반복의 primary anchor coverage(%)다. `*`는 JSON 형식 오류로 사전 규칙에 따라 0점인 응답이다.

| 사례 | 검토 대상 성격 | source-only | MCP |
|---|---|---:|---:|
| detekt7212 | 같은 rule의 기존 테스트 7개 발견 | 100 / 100 | 0* / 0* |
| detekt6446 | 같은 rule의 기존 테스트 54개 발견 | 24.1 / 33.3 | 16.7 / 0* |
| ktlint2774 | 테스트 21개 + 직접 호출자 1개 | 0* / 31.8 | 31.8 / 0* |
| ktlint2727 | 호출 관계의 source 대응 선언 4개 | 75 / 75 | 75 / 75 |

세 사례는 이름으로 찾을 수 있는 회귀 suite 중심이므로 이들을 하나의 영향 탐지 점수로 합치지 않는다.
호출 관계만 다루는 ktlint2727은 두 조건 모두 75%였고 모델 단계 평균 시간은 source 67.1초, MCP 68.7초였다.
ktlint2774의 직접 호출자 1개는 MCP의 유효 응답 한 번에서 찾았고 source 두 번에서는 찾지 못했다.
이 한 번의 관측을 일반 효과로 확대하지 않는다. 테스트 모음 발견·호출 관계별 세부 값은 [결과 JSON](results/report.json)에 있다.

모델 단계 합계는 **938.97초**, 비용은 **5.3727505 USD**였다. 설치·컴파일·정답 작성·외부 리뷰 비용은 별도다.
별도 synthetic smoke 두 차례(각 source/MCP)는 0.2743815 USD였고 16회 점수에 넣지 않았다.

## 형식 실패와 도구 사용

- source 8회 중 1회, MCP 8회 중 4회가 `invalid-json`이었다. 모두 수신 원문의 **마지막 object 닫는 중괄호 1개가 누락**됐다.
  raw assistant text와 최종 result text가 같음을 확인했다. 모델과 upstream client 중 발생 지점은 별도로 분리하지 못했다.
  중괄호 하나를 추가하면 파싱된다는 진단만 남겼고 원문·점수를 고치지 않았다. [형식 진단](results/format-failure-readback.json)
- 따라서 0점을 모두 코드 이해 실패로 해석하지 않는다. 다음 평가에는 CLI의 구조화 출력 기능을 별도 프로토콜에서
  검증할 필요가 있다. 이번 결과를 그 방식으로 사후 재채점하거나 성공 응답만 다시 실행하지 않았다.
- 실제 제품 도구 요청/처리는 freshness 7/7, impact 6/6, query_symbol 3/3이었다. 모델이 요청한 freshness 7회는 모두 matched였다.
  도구를 호출하지 않은 것을 직접 감점하지 않았으며 호출 횟수도 효용 점수가 아니다.
- 예측의 oracle 밖 대상은 미판정이다. `oracleAgreement`는 불완전한 정답과의 겹침이며 false-positive precision이 아니다.

## 준비·검증

- [선택과 준비 변경](INTAKE.md), [고정 프로토콜](PROTOCOL.md), [실행 전 해시·순서](frozen-run.json),
  [정답 요약](oracle-summary.json), [전체 oracle](oracles/)을 보존했다.
- 네 모듈의 최종 snapshot은 모두 matched였고 원래 테스트는 **95개 통과·3개 skipped**였다.
  detekt7212의 native toolchain 설정 충돌은 별도 build-logic patch로 JDK17 capture에 연결했다. production/test source는 바꾸지 않았다.
  원본 capture 실패와 Gradle 버전 불일치 실패도 보존했으며, 손대지 않은 빌드의 성공이라고 주장하지 않는다.
- 준비 중 0.14.0의 **빈 의존 프로젝트 classpath 출력 거부**를 재현·수정했다. 사용한 plugin은 미발행 수정본이고,
  버전 문자열만으로 배포본과 구분하지 않고 [정확한 JAR 해시](qualification.json)를 고정했다. CLI는 검증된 0.14.0 배포본이다.
- 독립 javap·Kotlin PSI 선언을 source/class bytes와 다시 대조했고 primary 87개 모두 source signature와 USR이 같은 점수를 받았다.
  원본 응답·도구 기록 등 112개 파일의 저장 해시가 일치했다. [검증 요약](results/verification.json)
- [압축된 원문 답변](results/answers.json.gz)은 무손실이며 모든 실패를 포함한다. 전체 stream·proxy trace·native build 원본은
  공통 Git 디렉터리 `ai-utility-v5-20260921/`에 있다. native 입력은 `archives/native-inputs.tar.gz`, 작업 build 근거는 `archives/worktree-build-evidence.tar.gz`에 있다.
  모든 member 해시를 검증하고 빈 디렉터리에 실제 복원해 네 snapshot의 matched를 다시 확인한 뒤 원본 캐시를 정리했다.
  실제 위치·bindings 재연결·복원 방법은 `native-cleanup.json`과 최종 원장을 따른다.

제품 수정의 검증은 JDK17 전체 808개 중 806개 통과·기존 선택적 NIA 2개 skipped, coverage 90% 게이트,
JDK21 신규 회귀 4개, CLI·agent 계약, Python 제품 테스트 142개, Android/Gradle fixture와 자기 분석 5.389초다.
NIA 2개는 이전 별도 실행에서 통과한 입력 검증을 재사용하며 이번 JVM classpath 변경으로 재실행하지 않았다.
실험 코드의 회귀·실패 경로 29개와 실제 toy provider/source/MCP 연결도 확인했다.

## 재현

`SourceDeclarations.java` + `collect.py`가 제품 그래프 없이 원본 source signature·javap 호출을 수집하고,
`assemble.py`가 실행된 기존 테스트와 고정 규칙으로 oracle을 조립한다. `grade.py`는 한 예측을 최대 한 identity로 대응한다.
`run.py --config <local-config.json> --output <new-directory>`가 입력을 고정하고 `--execute --output <prepared-directory>`로 실행한다.
local config와 bindings에는 절대경로가 있어 공개하지 않는다. `report.py --run <completed-run> --output <new-report.json>`은
16회 순서·manifest를 확인하고 사례별 값을 집계한다. frozen run을 재실행하지 말고 새 실험 디렉터리와 프로토콜로 진행한다.
