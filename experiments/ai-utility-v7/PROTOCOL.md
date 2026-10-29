# v7 — 호출 관계와 동작을 분리하는 통제 실험

v5/v6의 입력·응답·점수는 불변이다. v6에서 확인한 선택적 함수 타입 매개변수 이름은 새 매칭기에서만
정규화한다. 타입 순서·nullability·반환 타입·overload·backtick 선언 이름·JVM USR은 합치지 않는다.
v6 사례는 매칭 회귀에만 사용하며 새 모델 평가에 섞지 않는다.

## 모델 노출 전 계약

- 새로 작성한 Kotlin/JVM 통제 사례 네 개(경계값, null 기본값, 콜백, 인터페이스 dispatch)를 모두 사용한다.
  `fixtures/`와 `changes.json`을 먼저 고정하며 제품 출력이나 모델 답변으로 사례를 선택하지 않는다.
  작은 합성 표본이며 공개 프로젝트 대표성·학습 미노출·실제 개발 시간 개선을 주장하지 않는다.
- baseline의 기존 JUnit 테스트를 전부 실행한다. 별도 checkout에 명시한 production 변경만 적용하고
  동일 테스트를 실행한다. base-pass/changed-assertion-fail을 동작 양성, 양쪽 pass를 음성 대조로 고정한다.
  test source는 바꾸지 않는다. skip·오류·테스트 유실·컴파일 실패는 qualification 실패이며 oracle에서 빼지 않는다.
- 호출 oracle은 제품 graph를 보지 않는 JDK javap의 정확한 호출 대상에서 1·2단계 **production** 호출자를
  수집한다. Kotlin PSI와 고유하게 대응되는 선언만 primary다. 동적 dispatch 누락은 명시하며 static oracle
  밖 production 응답을 오탐으로 부르지 않는다. 테스트 모음 이름만으로 정답을 추가하지 않는다.
- 동작 oracle은 실제 관찰한 양성 테스트다. 같은 함수에 도달해도 값/분기 때문에 결과가 유지되는 테스트가
  음성 대조에 포함된다. 이 고정된 테스트·patch 범위의 양성/음성만 판정하며 일반 안전성을 추론하지 않는다.
- 발행 0.15.0 CLI/plugin, 실제 compiler snapshot과 source/test class roots를 사용한다. 매 trial 전후
  입력 hashes와 verify-snapshot matched를 확인한다. 모델에 oracle·patch 적용 실행 결과는 노출하지 않는다.
- source 3개 도구 / 동일 source + MCP query_symbol·impact·freshness 조건, 각각 두 번, 총 16회다.
  순서는 각 사례에서 source→MCP, MCP→source다. v6와 같은 claude-opus-5[1m], low, 12 정보 호출,
  900초, CLI 3 USD 제한, 응답 64개와 structured_output 계약을 사용한다. 임의 실행/쓰기 도구는 없다.
- prompt는 production 호출 검토 대상과 **현재 assertion이 바뀔 것으로 예상되는 기존 테스트**를 구분한다.
  호출 경로만으로 테스트 실패를 단정하지 않도록 한다. kind=test를 동작 예측으로, caller/override를 호출
  예측으로 채점한다. 잘못된 kind는 상호 점수로 승격하지 않는다.
- 호출 coverage, 동작 recall, 관찰된 음성 대조 선택 수, 미대응/모호/범위 밖 예측, 시간·비용·도구를 각각
  보고한다. 하나의 종합 효용 점수로 합치지 않는다. reasoning/evidence 문장 품질을 자동 정확도로 간주하지 않는다.
- raw·실패·전체 순서를 보존하고 무효 답변은 분모에 남긴다. 선택적 재실행·사후 alias 추가·oracle 수정은 없다.
  사전 qualification 실패는 원인을 기록하고 준비를 수정할 수 있으나 본 실행의 실패는 그대로 남긴다.

## 재현 경계

준비·행동 실행·smoke·리뷰 시간/비용은 모델 본 실행과 분리한다. archive와 raw를 보존하고 실제 복원 및
해시 검증 뒤 재생성 cache를 정리한다. 모델 사용 이득이 없어도 같은 규칙으로 전부 보고한다.
