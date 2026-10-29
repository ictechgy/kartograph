# v6 intake — 모델 실행 전 선택 규칙

2026-09-22에 선택 규칙을 먼저 고정한다. v1–v5 입력·응답·oracle·점수는 수정하지 않는다.

- Kotlin SWE-bench revision `b9025d86f7ae634901396766bd61f6d4ae6d2165`의 detekt/detekt와
  pinterest/ktlint Gradle JVM 변경을 모집단으로 유지한다. v5의 previousCases·primary·exclusions까지
  포함한 미사용 목록을 PR 번호 내림차순으로 조회하고, 각 저장소에서 적격 사례 두 개를 선택한다.
- diff·제품 분석·AI 응답을 보기 전에 metadata로 선택한다. prepare.sh에 전체 base SHA가 있고
  expected_tests.json의 fail-to-pass 목록이 비어 있지 않아야 한다. 외부 prepare.sh는 실행하지 않는다.
- 기존 production 함수와 단일 변경 모듈을 찾을 수 있어야 한다. 실제 기존 테스트 실행과 compiler task의
  snapshot capture가 필요하며 모델 노출 전 CLI verify-snapshot이 matched여야 한다. 준비 실패는 기록하고
  조사한다. 자격 불충족 시 해당 이유와 다음 metadata 순서를 기록하며 모델 결과로 표본을 교체하지 않는다.
- 0.15.0 배포 CLI/plugin을 사용한다. Gradle wrapper·JDK·build-logic의 준비 변경은 별도로 기록하고
  production/test source를 바꾸지 않는다. 외부 build에는 scan과 remote build cache를 켜지 않는다.
- v5의 javap/Kotlin PSI 수집기와 source identity 채점기를 그대로 재사용한다. 정답은 변경된 기존 함수의
  직접/2단계 호출자와 실행된 동명 Test/Spec suite의 기존 선언이다. 제품 graph로 정답을 만들지 않는다.
  선택 suite에 이름 대응이 없으면 metadata의 기존 suite를 실행하되 oracle은 javap 호출 관계로 한정한다.
- 재사용하는 v5 채점기의 전체 oracle 상한 64개를 유지하고, 유일한 source signature가 있는 primary oracle은 1–64개여야 한다. 상한을 넘으면 정답을 자르지 않고
  모델 노출 전 미적격으로 기록한다. skipped·새 PR 테스트는 oracle에서 제외한다.
- source와 MCP를 각각 두 번 실행하며 반복 0은 source→MCP, 반복 1은 MCP→source 순서다.
  본 실행은 4개 사례·16회 전부를 보고한다. 무효·실패·예산 소진을 분모에 포함하고 선택적 재실행하지 않는다.

구조화 출력 연결의 smoke는 별도 synthetic Java 표본만 사용한다. 실제 사례의 답변으로 protocol을 조정하지 않는다.

## 모델 노출 전 준비 결과

- 최초 metadata 선택은 detekt6443·6352, ktlint2715·2617이었다. ktlint2715는 실제 테스트 51개와
  matched capture까지 성공했지만 매개변수화 테스트 8개의 표시명이 두 원본 함수에서 중복됐다.
  기존 XML→선언 대응기로 유일한 실행 identity를 확인할 수 없어 해당 사례 전체를 oracle 자격에서 제외했다.
  임의로 그 테스트만 빼거나 source 이름을 추측하지 않았다. 다음 ktlint2555는 fail-to-pass 목록이 비어
  metadata 조건에 맞지 않았고, 그다음 ktlint2554를 선택했다. 실제 사례의 모델 응답은 이 선택 전에 보지 않았다.
- 최종 표본은 detekt6443·6352, ktlint2617·2554다. 원래 production/test source와 Git base를 유지한다.
  detekt6443은 원래 Gradle8.3/JDK17, detekt6352는 Gradle8.3/JDK17로 실행했다. 6352의 원래 Gradle8.2.1은
  두 차례 테스트 후 누락된 Java class 디렉터리로 capture를 거부했으며 원본 실패를 보존했다.
  Gradle8.3 제어 실행은 matched였다. 이를 Gradle8.2.1의 원인 수정이나 원래 wrapper의 성공으로 부르지 않는다.
- ktlint2617·2554는 원래 Gradle8.7을 JDK21로 실행했다. 앞선 ktlint2715·2617의 JDK17 시도는
  Java21로 컴파일된 build-logic을 로드하지 못한 원인을 보존했다. 선택한 snapshot compilation은 같은 adapter로 JDK17을 사용한다.
- 최종 기존 suite는 79개 통과·0 skip·0 실패다. 독립 oracle의 primary 크기는 27·9·14·31이고,
  81개 전부 source signature와 JVM ID의 coverage가 같았다. 테스트 모음 anchor 79개·직접 호출 anchor 2개다.
  이 모집단을 호출 추론의 대표 표본이나 모델 학습에서 미노출된 표본이라고 주장하지 않는다.
- 실제 schema 성공·invalid schema 거부·budget 종료를 점검했다. 첫 source/MCP synthetic smoke는
  controller가 trace를 미리 만들어 proxy의 exclusive-create와 충돌했다. source 첫 회에서 차단됐고 원문을 보존했다.
  실제 proxy 회귀 테스트로 수정 후 별도의 새 smoke에서 source/MCP 모두 caller coverage 1.0, 형식 유효, 인프라 오류 0이었다.
  같은 mtime의 source byte 변경도 모델 호출 전에 차단되며 CLI는 stale, 원복 후 matched였다.
