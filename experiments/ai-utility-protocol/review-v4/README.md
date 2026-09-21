# v4 후속 검토 — 측정·답변 구체성 감사

2026-09-21에 **16개 응답·유효 예측 147개에 대한 측정 감사**를 수행했다. 동결된 oracle·scorer·점수를 바꾸지 않았다.
당시 `manualReviewPending: true` 기록을 성공으로 덮어쓰지 않는다. 모든 target의 동작·추가 영향 관계를 검증한 것은 아니다.

## 수행과 한계

- `audit_v4.py`로 조건명이 없는 16개 응답 전체를 기존 matcher와 대조했다. 입력 해시와 prediction별 실제 가점 identity는
  [matching-audit.json](matching-audit.json)에 있다. 새 효용 점수는 만들지 않는다.
- audit 스크립트의 해시 기록만으로 원본 미변경을 주장하지 않는다. 별도로 19개 입력 모두를 고정 commit
  `ca2fc7d6be30bf1c63d93737eedadeb980423168`의 Git blob과 대조한 [검사](frozen-input-check.json)를 남겼다.
  frozen oracle을 출력 경로로 지정하면 exit 2로 거부하고 원본 해시가 유지되는 실패 경로도 확인했다.
- `packet-ask` GLM medium에는 조건명 대응표·cohort 점수를 보내지 않고 blind 답변과 frozen oracle만 보냈다.
  [원응답](glm-review.json)과 [receipt](glm-receipt.json)를 보존했다. 답변에 graph 도구 언급이 있고 주 에이전트가 공개 평균을
  이미 알고 있으므로 **완전한 맹검이나 독립 인간 검토가 아니다.** packet redaction의 phone 1건은 전달 변형 가능성으로 남긴다.
- 주 에이전트가 원응답·원래 예측·oracle을 대조해 [처분](disposition.json)을 작성했다. GLM의 숫자와 판정은 정답으로 쓰지 않았다.
- 모든 예측이 가리킨 서로 다른 source 경로 43개를 고정 base에서 조회해 42개를 확보했다. URL·SHA256은
  [source-receipts.json](source-receipts.json)에 있다. 파일 존재 확인과 쟁점 source 읽기이며 147개 예측의 동작 주장의 완전한 검증은 아니다.

## 확인한 사항

1. `3012808a9245`의 7번째 예측 `FieldReaderObject.getObjectReader (L67, L86)` 한 개가 서로 다른 overload 두 개에
   가점됐다. 147개 예측 중 이런 다중 가점은 1개다. 원래 규칙에 따른 결과를 유지하고 다음 평가의 identity 계약을 고친다.
2. `81f2d1dbe1fd`의 11번째 예측 `core/src/test/java/com/alibaba/fastjson2/issues/Issue1676.java`는 원본에 없다.
   완전한 Git tree에는 `issues_1600/Issue1676.java`와 `fastjson1-compatible/.../v2issues/Issue1676.java`가 있다.
   파일명만으로 하나를 고를 수 없다. [tree 조회 근거](source-path-check.json)
3. GLM이 `3edee05b109d`에 있다고 쓴 ByteBuffer override는 원래 답변에 없다. frozen full-credit identity는 3개가 아닌
   2개다. detekt 범위도 GLM의 0–4가 아니라 3–4다. 이 오류와 몇몇 index 불일치를 처분에 남겼다.
4. `5be0347a9a15`의 정확한 JSONReader USR은 가점을 유지한다. evidence가 두 edge를 언급한다는 이유로 정확한
   selector까지 모호하다고 판정하지 않는다. `84fe91ffabef`의 null은 strict JSON 파싱 실패 표현이지 원문 부재의 증거가 아니다.
5. oracle 밖 target은 자동 오탐이 아니다. 예를 들어 BaselineResultMappingSpec의 원본에는 XML 본문을 문자 그대로
   비교하는 테스트가 있어 변경 조사 대상으로 설명할 근거가 있다. 실제 실패 여부는 이번에 실행하지 않았다.
6. synthetic accessor가 있다는 이유만으로 source 수준의 “sole consumer”를 곧바로 거짓으로 판정하지 않는다.
   코드 수준과 compiler 선언 수준을 구분한다. 변경 심볼 자체를 나열한 것도 재귀 등 실제 관계를 확인하기 전 일괄 감점하지 않는다.

## 후속 적용

[MCP 안내](../../../docs/MCP.md)에 초기 capture·freshness 복구·반복 조사 흐름과 basename 경로 추측 금지를 추가했다.
[다음 프로토콜 준비 조건](../NEXT-PROTOCOL.md)은 matched compiler 입력, 응답 상한, 대칭적인 identity 채점과 비용 측정을 요구한다.
새 코호트·실행·개선된 AI 성공률은 아직 없으며, 이 문서는 그런 결과를 주장하지 않는다.

재현: 저장소 루트에서 아래 명령을 실행한다. 원본 점수에는 쓰지 않는 별도 출력이다.

```sh
python3 experiments/ai-utility-protocol/audit_v4.py --output /path/to/matching-audit.json
```
