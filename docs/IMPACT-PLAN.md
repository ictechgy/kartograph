# 변경 영향 사전 점검: 구현·채점 계획

사용자 확정 목표(2026-09-11): 특정 코드 수정 전 영향 점검, 사람·AI 공통 질의/스킬, 런타임 의존성의 정적 근거,
CI에서 갱신할 수 있는 속도. 주 원천과 query v1 계약을 유지한다. 미사용/삭제 판정과 영향 후보를 혼동하지 않는다.

## 완료 계약

1. `impact`가 하나 이상의 심볼 또는 변경 파일을 입력받아 직접·간접 영향 후보와 실제 간선 경로를 반환한다.
   base/current snapshot을 각각 탐색해 삭제·signature 변경을 다루며, 두 시점의 간선을 섞어 가상의 경로를 만들지 않는다.
2. 스킬이 같은 명령과 JSON을 사용한다. 응답은 간선 kind/origin, 보존 근거, 신선도·미해결 한계와 잘림을 전달한다.
   자동 삭제나 테스트 생략을 승인하지 않는다. baseline suppression은 영향 대상을 숨기지 않는다.
3. reflection·static return·ServiceLoader·manifest/keep 입력과 양방향 미사용 대조군을 실제 compiler/실행으로 검증한다.
   상수 인라인 등 원천에 없는 관계는 복원했다고 주장하지 않는다. 경로 불명·모호한 심볼·입력 불일치를 0 영향으로 바꾸지 않는다.
4. CI helper가 Git 변경 파일과 두 snapshot을 연결한다. snapshot은 빌드 후 갱신하고, 전체 갱신/질의 비용을 따로 측정한다.
   증분 인덱서는 측정에서 필요할 때 도입한다. 현 범위는 기존 전체 capture와 저장 그래프 재사용이다.
5. 독립적인 실제 변경 과제를 선별해 영향 대상/관련 테스트 누락·후보 크기·질의/갱신 시간과 실행 가능한 회귀를 채점한다.
   새 테스트, 비재현 과제와 제한된 실행 범위를 별도로 기록한다. 데이터셋 존재를 벤치마크 통과로 세지 않는다.

## 순서와 책임

| 단계 | 구현 위치 | 검증 |
|---|---|---|
| 1. 순수 영향 분석 | analysis: 선택·시점별 역방향 탐색·경로·한도 | 실패하는 단위 테스트 → 구현, 시점 간 가상 경로/삭제/모호성 반례 |
| 2. CLI·문서 | export renderer, cli impact/snapshot reader, Skills | 실제 javac/Kotlin·runtime 근거, saved query 호환·오류·보안 출력 |
| 3. CI 연결 | Scripts/check-impact.py | 실제 Git rename/delete/공백 경로·누락 snapshot·종료 코드 |
| 4. 채점 | experiments/change-impact, Scripts | Kotlin SWE-bench 고정 revision의 실제 source/test와 대조, 공개 앱 성능 |
| 5. 완료 | docs, 검증 기록 | JDK17/21, 90% coverage, CLI/agent/Python/Android/plugin 계약과 리뷰 |

## 채점 원천과 구분

- Kotlin SWE-bench `b9025d86f7ae634901396766bd61f6d4ae6d2165`: 106개 과제 중 AnkiDroid 6·OkHttp 2를 먼저 조사한다.
  원본 테스트가 존재하고 현재 환경에서 재현되는 과제를 선택하며, 선택/제외 이유를 결과에 남긴다.
- Defects4J는 Java 보완 후보다. 854개 전체를 실행했다고 주장하지 않는다. 각 과제의 toolchain 제약을 우선 확인한다.
- AI 비교는 같은 과제·모델·예산의 도구 없음/있음 쌍으로 평가한다. 정답 patch와 hidden test를 agent 입력에서 제외한다.
  외부 모델의 실행 가능 범위와 비용을 확인하고, 실제 완료한 비교만 점수로 기록한다.
- 성능은 준비/빌드, snapshot capture, impact query를 분리하고 동일 입력에서 반복 측정한다.

## 상태

- 순수 분석·snapshot 기반 CLI·CI helper·compact v2 구현과 회귀 검증 완료. 공개 OkHttp/AnkiDroid 2개 과제와 AI 질의 A/B 채점 완료.
- 현재 `main` 기준 `9c66cc6`; 기존 HANDOFF.md 변경은 별도 보존한다. [상세 채점](../experiments/change-impact/README.md)을 기록했다. 제품 검증 명령은 AGENT-WORKFLOW.md, 최종 통합 상태는 PR 검사를 따른다.
