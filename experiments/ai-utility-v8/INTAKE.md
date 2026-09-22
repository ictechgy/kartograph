# v8 intake — 실제 공개 모듈의 호출·동작 영향

2026-09-22, 제품 graph·새 모델 응답을 보기 전에 다음 범위를 고정한다. v5/v6/v7 자료는 수정하지 않는다.

- detekt/detekt v1.23.8, commit `046263730eb5368cb344489ac36543294e8e87bd`, `detekt-rules-style`.
  production92개/9,625줄, test88개/26,644줄이다.
- ktlint/ktlint 1.5.0, commit `b44a53f414d81cdf5816a26c182ba2738e3c9670`, `ktlint-ruleset-standard`.
  production104개/16,814줄, test103개/37,439줄이다. 기존 pinterest/ktlint 태그 주소는 이 저장소로 이동한다.
- 수치는 src/main/kotlin·src/test/kotlin의 빈 줄을 제외한 줄 수다. 최소100개 source 파일·10,000줄의
  실제 모듈을 요구한다. 테스트를 포함한 모듈 전체를 도구에 노출하며, 전체 다중 모듈 저장소를 캡처했다고 주장하지 않는다.
- 원본 소스와 기존 테스트를 읽어 공유 헬퍼의 제안 변경 네 개를 `changes.json`에 고정한다. 자연 발생 PR 표본이나
  무작위 대표 표본은 아니다. 새 toy 구현·새 테스트를 실제 프로젝트 대신 넣지 않는다.
- 순서는 detekt line content, detekt trimming methods, ktlint disabled max line length, ktlint import subpackages다.
  각 과제는 별도 changed checkout에서 명시한 production 변경만 적용한다. base/test source는 그대로다.
- 모듈의 기존 test task 전체를 실행한다. JUnit Platform의 실제 MethodSource/JVM descriptor를 observer로 기록해
  parameterized 표시명이 겹쳐도 실행 identity를 추측하지 않는다. 별도 작은 observer 계약 smoke는 평가 표본이 아니다.
- baseline은 실행된 테스트 전부 pass여야 한다. 변경 후 assertion 실패만 양성으로 인정하며, 실행 오류·test identity
  유실·새 invocation·observer 불완전·skip 상태 변경은 자격 실패다. 양쪽에서 동일하게 skip인 테스트는 별도 수로 남긴다.
- 양성 기존 test method 최소2개, 유지된 test method 최소10개, source-addressable production caller 최소2개를 요구한다.
  정적 caller는 1·2단계 javap 참조이며 최소 두 production 파일에 걸쳐야 한다. 생성 선언·동적 dispatch 한계는 별도 기록한다.
  caller+양성 test method의 전체 primary oracle이 64개를 넘으면 잘라내지 않고 사전 자격 실패로 기록한다.
- 자격 실패는 원문·이유를 보존한다. 준비 문제를 고칠 수 있으나 모델 점수로 사례/테스트를 교체하지 않는다.
  이 네 사례로 계약을 만족하지 못하면 실패를 기록한 뒤 모델 노출 전에 새 intake를 별도로 명시한다.
- 발행0.15.0 CLI/plugin과 원래 Gradle wrapper를 사용한다. JDK는 detekt17·ktlint21이며 필요 adapter는 기록한다.
  build scan·remote build cache는 켜지 않는다. private member는 snapshot에서 명시적으로 opt-in한다.
- source/MCP 각2회·총16회, 정보 도구12회·900초·CLI 비용 제한3USD·응답64개를 유지한다.
  source 조건도 쓸 수 있는 **파일 내 유일한 source 이름**을 허용하고, 모호한 overload만 정확한 서명으로 구분한다.
  출력·무결성·typed scoring은 검증된 v7 계약을 재사용하며 원문을 사후 수리하지 않는다.

모델 실행 전에 전체 source·컴파일 snapshot·bindings·observer·oracle·prompt·도구·모델을 해시로 고정한다.
실제 입력은 컴파일 완료 증거로 `matched`여야 한다. 첫 설치/갱신 비용 측정은 이번 실험의 별도 목표가 아니다.
모듈 크기만으로 실제 과제가 어려웠다거나 일반 개발 생산성이 개선됐다고 결론 내리지 않는다.

## source 범위 명료화 — 모델 노출 전

ktlint의 기존 test task에는 의존 모듈 `ktlint-test`에 선언된 상속 테스트 한 개도 있다. 관찰한 JVM identity와
전체 실행 결과는 보존하되, 제공한 class/source 범위에 없는 안정적인 pass 메서드는 primary 음성 대조와 분리한다.
그 메서드가 변경 후 실패하면 source 조건으로 검토할 수 없으므로 해당 사례는 미적격이다. 제공한 class 안의
미대응 메서드를 이 규칙으로 제외하지 않는다. detekt에서 발견한 test 이름의 실제 끝 공백 두 개는 javap reader와
backtick alias를 회귀로 수정해 모두 원래 선언에 대응했다. 기존 v5/v6/v7 parser·점수는 변경하지 않았다.
