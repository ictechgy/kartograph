# v8 — 실제 공개 모듈의 AI 영향 조사

모델 본 실행 전에 고정하는 계약이다. 모집단·네 제안 변경·사전 자격은 [INTAKE](INTAKE.md)와
[changes.json](changes.json)을 따른다. 과거 v5/v6/v7 입력·답변·점수는 변경하지 않는다.

## 비교

- 실제 detekt style(180 Kotlin source 파일)와 ktlint standard(207개)의 각 두 제안 변경을 사용한다.
  전체 해당 모듈의 source와 main/test compiled graph를 제공한다. 다른 모듈의 구현은 제공하지 않는다.
- 발행0.15.0 CLI/plugin, 원래 Gradle wrapper, detekt JDK17·ktlint JDK21, private member opt-in이다.
  source·compiler 입력·출력에 matched 증거가 있어야 실행한다. 제품 graph는 정답 생성에 사용하지 않는다.
- source 도구3개 / 같은 source + MCP query_symbol·impact·freshness 조건이다. 모든 도구 사용은 선택 사항이다.
- claude-opus-5[1m], effort low, 12 정보 호출·900초·CLI 비용 상한3USD·최대64예측이다.
  proxy가 처음12개 정보 요청까지만 처리한다. 초과 요청은 같은 예산 거부 응답만 받으며 요청 횟수와 처리 예산을 혼동하지 않는다.
  사례마다 source→MCP, MCP→source 두 반복으로 총16회를 전부 실행한다. 본 실행 상한은48USD다.
- v6의 structured_output 성공 계약과 v7의 typed grader를 그대로 사용한다. 무효·시간초과·예산 실패도 분모에
  남긴다. 도구 surface·입력 무결성·provider identity 실패는 중단하고 원문을 보존한다. 선택적 재시작은 없다.
- 파일 안에서 유일한 source 별칭은 parameter types 없이 사용하도록 안내한다. Kotlin backtick 이름의 실제 공백을
  보존하며 overload는 정확히 구분한다. graph USR은 관찰값을 복사할 수 있고 임의 descriptor/Function2 축약은 만들지 않는다.
  사후 fuzzy matching·alias 추가·JSON 수리·정답 변경은 하지 않는다.

## 독립 정답과 지표

- javap·실제 Gradle source set·Kotlin PSI로 전체 독립 선언/참조를 만들고 1·2단계 production 호출자를 고정한다.
  source 함수에 대응하지 않는 JVM 생성 선언은 별도 보존하며 중간 참조 경로로만 사용할 수 있다.
- 기존 test task 전체의 JUnit MethodSource와 JVM descriptor를 [observer](ExecutionObserver.java)로 관찰한다.
  표시명이나 정규식으로 parameterized invocation을 메서드에 추측 대응하지 않는다. 원본 test source는 바꾸지 않는다.
- [JUnit listener 계약](https://junit.org/junit5/docs/5.11.1/api/org.junit.platform.launcher/org/junit/platform/launcher/TestExecutionListener.html)은
  listener 예외가 테스트 실패로 전파되지 않을 수 있다. 따라서 journal close 후 SHA256 seal, 등록/종료 수,
  중복 UID·누락, Gradle JUnit XML 총수까지 확인해야 증거를 수락한다.
  이 버전은 실제 method leaf의 skip만 비교한다. container-level skip은 모두 지원 범위 밖이며 거부한다.
- 같은 invocation의 baseline pass→changed assertion-failure를 method별로 합친다. 일부 인자만 실패해도 그 메서드는
  한 번만 양성이다. 전부 pass인 메서드는 음성 대조다. 같은 skip은 별도 집계하고 변경된 skip·execution error는 거부한다.
- 제공한 class 안에서 source 대응을 잃은 테스트는 자격 실패다. 모듈 밖 상속 테스트는 실제 결과를 유지하며 안정적인
  pass일 때만 source 범위 밖으로 분리한다. 그 테스트가 양성이면 source 조건이 조사할 수 없어 해당 사례를 거부한다.
- kind=caller/override의 알려진 호출자 recall, kind=test의 관찰된 assertion 변경 recall와 음성 대조 오선택을 분리한다.
  미대응/모호/중복/종류 오류·source 범위 밖·정적 oracle 밖도 함께 보고한다. oracle 밖 production 대상을 오탐으로 세지 않는다.
  v5 호환 `primaryRecall`은 식별자 진단일 뿐 v8의 종합 효용 점수로 사용하지 않는다.

## 무결성과 해석

매 trial 전후 source·snapshot·bindings·oracle·prompt·도구를 검증한다. observer JAR·독립 parser·원본 실행 증거도
별도 preparation hash로 고정한다. 재채점에는 frozen runtime 도구와 raw/oracle hash를 확인하고, native 준비의 재실행과 구분한다.
raw·실패·비용 누락을 보존한다. 준비·observer smoke·외부 리뷰·model 단계 시간을 구분하고 CLI 비용을 청구액이라고 부르지 않는다.

제안 변경은 연구자가 실제 프로젝트에서 사전 선택한 변경이며 자연 발생 PR·무작위 표본이 아니다. 네 사례·두 반복의 결과를
일반 생산성·모든 runtime 경로·답변 reasoning의 정확도·삭제 안전성으로 확대하지 않는다. 전부 완료한 뒤 모든 미대응 식별자와
점수 차이의 원문을 확인한다. 공개 모델 학습에서 원래 프로젝트가 미노출됐다고 주장하지 않는다.
