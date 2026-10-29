# v6 완료 — 구조화 응답 16/16 유효, 효용 해석은 제한

2026-09-22, 새 공개 표본 4개에서 source/MCP 각 2회, **16회를 전부 실행**했다.
구조화 응답은 source 8/8·MCP 8/8 유효했고 인프라 오류·선택적 재시작은 없었다.
원문 tool input과 반환된 `structured_output`이 같고, 원문 재채점과 저장 점수도 모두 일치했다.
기존 v5의 29개 파일은 byte-identical이다. 표본·제품·출력 방식이 달라 v5와의 차이를 단독 인과 효과로 읽지 않는다.

## 고정 규칙의 결과

값은 각 반복의 **정확한 식별자 매칭에 따른 known-anchor coverage(%)**다. 형식 유효성이 답변의 사실 정확성을 뜻하지 않는다.

| 사례 | primary / 성격 | source | MCP |
|---|---|---:|---:|
| detekt6443 | 27 / 기존 suite 26 + caller 1 | 100 / 40.7 | 100 / 100 |
| detekt6352 | 9 / 기존 suite 9 | 100 / 100 | 100 / 100 |
| ktlint2617 | 14 / 기존 suite 14 | 71.4 / 85.7 | 78.6 / 85.7 |
| ktlint2554 | 31 / 기존 suite 30 + caller 1 | 22.6 / 16.1† | 25.8 / 22.6 |

원래 점수는 [report.json](results/report.json)에 그대로 보존했다. 정답 81개 중 79개는 같은 rule의 기존 테스트 모음
검토 anchor이며 호출 관계는 2개다. 테스트 모두가 이 patch로 실패한다는 뜻이 아니므로 전체 평균을 영향 탐지 정확도로 합치지 않는다.

## 원문 검토에서 확인한 제한

† ktlint2554의 source 응답도 두 번 모두 `SpacingAroundCurlyRule.beforeVisitChildNodes`와 정확한 호출 위치를
명시했다. 다만 `(Int, String, Boolean) -> Unit`으로 쓴 함수 타입을 고정 매칭기는 원문의
`(offset: Int, errorMessage: String, canBeAutoCorrected: Boolean) -> Unit`과 대응하지 못했다.
[Kotlin의 함수 타입 표기](https://kotlinlang.org/docs/lambdas.html#function-types)에서 이 내부 매개변수 이름은 선택 사항이다.
같은 caller를 지목한 답변의 표기 차이가 source 0점/MCP 1점으로 나타난 것이므로 **호출자 발견 우위로 해석하지 않는다**.
원문·aliases·oracle·점수는 바꾸지 않았으며 [별도 대조 기록](results/source-signature-readback.json)에 근거를 남겼다.

- 원문을 대조한 두 caller는 양쪽 조건이 각 반복에서 지목했다. MCP는 정확한 JVM USR을 제공했다.
- detekt6443의 source 두 번째 답변은 12개 대상으로 범위를 좁혔다. MCP 두 번째 답변은 제품 graph 도구를
  한 번도 호출하지 않고도 100%였다. 이 차이를 graph 사용의 효과로 단정할 수 없다.
- source의 미대응 식별자는 10개, MCP는 0개였다. 위 2개 외의 미대응 예측을 자동으로 정정하거나 가점하지 않았다.
  oracle 밖 대응 선언은 source 7개·MCP 12개이며 모두 미판정이다. 이를 false-positive precision으로 바꾸지 않는다.

이번 조건에서 형식 실패는 관측되지 않았다. 일부 MCP 조건의 더 높은 매칭 coverage는 관측됐으나,
표기 대응 한계·테스트 모음 위주 과제·사례별 2회 반복 때문에 **일반적인 AI 효용 우위는 확정하지 않는다**.
다음 비교에서는 함수 타입의 동치 source 표기를 먼저 검증하고, 호출 관계와 실제 행동 검증을 더 포함한 정답 계약을 새로 고정해야 한다.

## 시간·비용·도구

| 사례 | source 평균초 | MCP 평균초 |
|---|---:|---:|
| detekt6443 | 80.7 | 68.4 |
| detekt6352 | 42.2 | 62.5 |
| ktlint2617 | 64.7 | 62.3 |
| ktlint2554 | 48.9 | 53.6 |

모델 단계 합계는 **966.58초**, CLI가 보고한 비용 추정 합계는 **4.736082 USD**였다
(source 2.1550565 / MCP 2.5810255). 실제 청구 금액·준비·외부 리뷰 비용과 구분한다.
별도 synthetic smoke의 수신 추정액은 0.1831455 USD, schema/budget 대조의 수신 추정액은 0.0242175 USD다.
준비 빌드 9회의 누적 실행 시간은 1004.26초이며 병렬 실행·실패·제외 표본을 포함한다. 전체 벽시계나 첫 사용 시간 지표가 아니다.

실제 제품 요청/처리는 freshness 2/2, query_symbol 3/3, impact 4/4였다. 모델이 요청한 freshness는 둘 다 matched였다.
정보 도구와 StructuredOutput 완료 도구를 분리해 기록했으며, graph 도구를 호출하지 않은 것을 감점하지 않았다.
호출 횟수 자체도 효용 점수가 아니다. [요약](results/summary.json)을 따른다.

## 준비·검증·재현

- [선택·준비](INTAKE.md), [고정 프로토콜](PROTOCOL.md), [코호트](cohort.json), [실행 전 해시·순서](frozen-run.json)를 보존했다.
  원래 production/test source를 유지했고 최종 기존 테스트 79개·네 matched snapshot을 확인했다.
  JDK/Gradle 준비 실패 4건, ktlint2715의 oracle identity 제외와 2555의 metadata 제외도 기록했다.
- 독립 javap/Kotlin PSI oracle 27·9·14·31개는 source/JVM 대응을 대조했다. 이 사전 검사는 알려진 aliases에 대한
  대칭성 검사였으며 모델의 모든 동치 표기를 포괄하지 못했다. [자격](qualification.json)·[대칭 검사](oracle-parity.json)·[oracles](oracles/).
- 새 회귀 32개, 실제 schema·budget 실패 대조, source/MCP toy, 같은 mtime source 변경 거부를 확인했다.
  GLM 지적 두 건을 회귀로 수정하고 네 oracle이 재조립 후 같은 bytes임을 확인했다. [검토 처분](review-disposition.json).
- 원문 112개 파일 해시·반환 객체/원문 tool input·원문 재채점·실행 후 freshness·v5 보존을 대조했다.
  [검증](results/verification.json), [무손실 원문 답변](results/answers.json.gz), [준비 시도](results/preparation-attempts.json)를 보존한다.
  source 표기 대조는 Codex 주 에이전트가 수행한 사후 검토이며 맹검 사람 평가가 아니다.

검증 명령은 `python3 -m unittest discover -s experiments/ai-utility-v6 -p 'test*.py' -v`다.
`run.py --config <local-config> --output <new-directory>`로 준비하고 `--execute --output <prepared-directory>`로 실행한다.
`report.py`는 완료된 16회와 근거 해시를 확인한다. 완료한 실행을 선택적으로 다시 시작하지 않는다.
전체 stream·proxy trace·native 원본·정리/복원 원장은 공통 Git 디렉터리 `ai-utility-v6-20260922/`에 있다.
실제 로컬 경로는 다른 checkout에 있다고 가정하지 않는다.

작업용 cache 정리 후 원본 입력은 약 556MB 아카이브로 보존했다. 26,222개 member를 해시 대조하고 빈 디렉터리에
실제 복원해 최종 표본4개·제외 표본1개·toy1개의 snapshot을 모두 matched로 확인했다. 원문 근거180개 해시도 유지됐다.
그 뒤 원본 native 디렉터리와 재생성 cache를 정리해 작업 디렉터리 배정 용량은 약9.33GB에서0.62GB로 줄었다.
현재 native 입력은 아카이브 상태이므로 다시 조회하려면 복원해야 한다. [정리·복원 요약](results/cleanup.json)을 따른다.
