# v6 — 구조화 출력을 검증하는 새 영향 조사

상태: **모델 본 실행 전 고정**. schema/provider smoke·네 표본의 입력 자격·외부 검토 처분을 완료했다.

- 제품: 발행된 0.15.0 CLI/plugin의 실제 bytes. 모집단·선택·oracle은 [INTAKE](INTAKE.md)를 따른다.
- 모델: `claude-opus-5[1m]`, effort low. 설치된 Claude CLI 버전·binary hash와 관찰한 model identity를 고정한다.
- 조건: source 도구 3개 / 같은 source와 실제 MCP query_symbol·impact·freshness. 개인 지침·memory·기본 실행/파일 도구는 끈다.
- 예산: 각 trial 정보 도구 12회, 900초, CLI 비용 제한 3 USD, 응답 64개. 16회 상한은 48 USD다.
  schema 완성을 위한 CLI 내부 처리는 정보 도구 호출과 구분하고 시간·추정 비용에는 포함한다.
- 출력: `--json-schema`를 사용하고 성공 result의 `structured_output`만 수락한다. raw result text는 그대로
  보존한다. structured output 누락·schema 위반·provider 오류·budget/timeout은 실패로 남기며 raw text로
  fallback하거나 JSON을 수리하지 않는다. CLI 내부 재시도와 실험 trial 재시작은 구분한다.
- 채점: v5의 고정 source/USR identity 채점기를 재사용한다. 유효 응답만 채점하고 무효 응답은 coverage 0으로 남긴다.
  test-suite 발견과 call-graph anchor를 사례별로 분리한다. 불완전한 oracle 밖 응답을 오탐으로 세지 않는다.
- 무결성: 매 trial 전후 source·snapshot·bindings·oracle·prompt·schema·도구 hashes와 matched를 확인한다.
  tool surface·model identity·입력 무결성 실패는 중단하며 원본을 보존한다. 형식 실패는 기록하고 예정된 전체 순서를 계속한다.
  post-trial 검증 자체가 실패해도 중단하고 예외 종류를 기록하며, timeout을 실제 byte 변경이라고 단정하지 않는다.
- 보고: 16회의 유효성·실패 이유·사례별 coverage·실제 도구 호출·모델 단계 시간·CLI 추정 비용을 보고한다.
  누락된 비용을 0으로 대체하지 않는다. 준비·capture·smoke·리뷰 비용은 본 실험과 분리한다.
- v5와 표본·제품·CLI·출력 프로토콜이 달라 결과 차이를 구조화 출력의 단독 인과 효과나 일반 생산성 향상으로 읽지 않는다.
  최종 oracle은 테스트 모음 anchor 79개와 호출 anchor 2개다. 미사용은 이전 실험 기준이며 모델 학습 미노출의 보장이 아니다.

[CLI 공식 문서](https://code.claude.com/docs/en/headless#get-structured-output)와
[Agent SDK 오류 계약](https://code.claude.com/docs/en/agent-sdk/structured-outputs#error-handling)을 확인했다.
성공 subtype에도 structured output이 없을 수 있으므로 별도 검증한다. 도구의 형식 보장이 모든 실행의 성공을 뜻하지 않는다.

다중 파일 diff 경계와 모듈 import 고정에 관한 GLM 지적은 회귀로 재현·수정했다.
현재 네 사례의 oracle은 재조립 후에도 byte-identical이다. handshake 계수·실제 MCP wrapper·schema·budget·timeout·
원문/결과/oracle 해시 변경·실제 source proxy를 포함한 32개 검사가 통과했다. [검토 처분](review-disposition.json)을 따른다.
