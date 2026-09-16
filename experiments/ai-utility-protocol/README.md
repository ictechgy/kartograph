# AI 효용 실증 프로토콜 (사전 등록 스캐폴드 — 미실행)

상태: **실행되지 않은 프로토콜 틀**. 이 문서는 결과를 주장하지 않는다. 0.9.0의 48회 코호트 비교는
"검토 anchor 점수"이며 일반 AI 생산성 향상의 근거로 쓰지 않기로 기록돼 있다(HANDOFF). 이 프로토콜은
그 주장을 검증 가능하게 만들기 위한 사전 등록 양식이다. 실행 전에 모든 칸을 채우고, 실행 후에는
칸을 바꾸지 않는다. 바꿔야 하면 원본을 보존하고 새 버전을 만든다.

## 고정 항목 (실행 전에만 채운다)

아래 값은 v4용으로 고정했다. 채운 것은 계획일 뿐이며 결과가 아니다.

| 항목 | v4 고정값 |
|---|---|
| 가설 | MCP(build-grounded graph 질의)를 연결한 에이전트는 미연결 대비 영향 조사 과제에서 더 정확하다 |
| 귀무가설 | 차이 없음. 점수 차이는 그래프 사용 횟수의 인과 효과로 해석하지 않는다 |
| 표본 | 공개 저장소 4건(비공개 소스 제외), [cohort-v4.json](cohort-v4.json)에 고정: `alibaba__fastjson2-2097` (base `3f6275bc…`), `fasterxml__jackson-core-1016` (base `315ac5ad…`), `detekt_detekt-7625` (base `82ab4c5a…`), `pinterest_ktlint-2785` (base `4ec50723…`). 사례당 변경 전 영향 질문 1세트(조사 대상 최대 12개) |
| oracle | 사례별로 기존 호출자·테스트·override·등록 지점의 고정 집합을 실행 전에 만든다. 산출 방법은 v3과 같다: javap의 method reference(직접 호출), 원본에서 실제 실행한 기존 테스트, 원본 선언(override/등록). 여기에 v4의 추가 요건으로 4건 중 **최소 2건**은 transitive(간접) 영향 없이는 답할 수 없는 "직접·간접 영향 나열" 질문을 포함한다. 구성 절차는 아래 별도 절에 고정한다. 채점 key는 실행 후 공개한다 |
| 코호트 | (a) 소스만 — list/read/literal-search만 제공, (b) MCP — 같은 source 표면에 실제 제품의 `query_symbol`/`impact`/`freshness` 추가. 시스템 프롬프트·모델·온도·예산 동일 고정. 제품 도구 호출을 강제하지 않는다 |
| 모델·예산 | v1~v3과 동일: Claude Code, `claude-opus-5[1m]`, effort low, 전체 도구 호출 12회, 벽시계 900초, 실행당 최대 추정 비용 3 USD. 두 조건이 같은 예산을 공유한다 |
| 채점 | oracle 대비 recall(누락 없음)·precision(과잉 나열 없음). freshness 질문은 별도 점수로 분리. `query_symbol`/`impact`/`freshness` 사용 횟수는 기록만 하고 점수로 환산하지 않는다. anchor 점수와 섞지 않는다 |
| 반복 | 조건당 사례별 2회, 총 16회(4 사례 × 2 조건 × 2 반복). 상한 초과·중단도 16회 안에 그대로 남긴다 |
| 원본 보존 | 모델 응답 원문, 채점 스크립트, oracle을 그대로 보존하고 각각 sha256을 남긴다. 입력 dataset 파일의 download receipt(url·bytes·sha256)는 [intake-v4/receipts.json](intake-v4/receipts.json)에 고정했다 |

### oracle 구성 절차 (v4, 실행 전 고정)

1. 각 사례의 production 변경에서 바뀐 심볼 집합을 정한다. Kotlin의 여러 commit PR은 v3과 같이
   최종 net production diff로 정규화한 뒤 고정 base에 적용 가능한지 확인한다.
2. 바뀐 심볼의 **직접** 참조를 javap의 method reference로 수집한다(`collect_direct_oracle.py`와 같은
   방식). 기존 테스트는 원본에서 실제 실행해 확인하고, override·등록 지점은 원본 선언으로 확인한다.
   새 PR 테스트는 넣지 않으며 기존 건너뜀은 대상으로 세지 않는다.
3. **간접** 영향 대상은 2단계로 만든다. 1단계 직접 호출자 **전부**에 대해 같은 javap 증거로 그 호출자(2단계)를
   수집한다. 어느 호출자를 확장할지 고르지 않으며 상한도 두지 않는다. 2단계에서 1단계에 없던 대상이 하나 이상
   추가되는 사례만 "직접·간접 영향 나열" 질문의 후보가 된다. 2단계 대상이 12개를 넘으면 자르지 않고 그대로
   oracle에 두며, 응답 상한 12개 때문에 생기는 recall 천장은 사례별 oracle 크기와 함께 보고한다.
4. 후보 사례가 3건 이상이면 사례 id 오름차순으로 앞의 2건을 쓴다. 이 정렬 규칙은 metadata만 쓰며
   제품·모델 결과를 보지 않는다. 후보가 2건 미만이면 그 사실을 기록하고, 요건을 사후에 완화하는 대신
   미충족으로 남긴다.
5. 3에서 만든 2단계 대상은 **자격 검증(qualification) 단계에서 오프라인으로** 제품의 `impact`와
   대조한다. 이 대조는 모델 실행 전에 끝내며, 모델 실행 중에는 하지 않는다. **oracle 집합은 javap·원본
   테스트·원본 선언 증거만으로 확정되며, 제품 `impact` 대조는 일치·불일치 보고서를 남길 뿐 oracle 항목을
   추가하거나 빼지 않는다.** 제품만 찾은 대상은 보고서에 별도로 기록하고 oracle에 넣지 않는다.
6. 완성한 oracle의 sha256을 모델 노출 전에 고정한다.

## 측정 구분 (결과 문단에서 강제)

- 그래프 질의 사용 횟수(`query_symbol`/`impact`/`freshness`)를 코호트별로 기록하고, 점수 차이를
  도구의 인과 효과로 주장하려면 사용과 결과의 연결을 별도로 보여야 한다.
- anchor 점수(사전 고정 검토 기준)와 실무 과제 점수(oracle recall/precision)를 섞지 않는다.
- 미실행·중단·한도 초과는 결과에 그대로 남긴다.

## 0.9.0과의 차이

- v1~v3 코호트에서 `impact` 사용이 0회였던 문제를 대상 과제 설계로 다룬다: oracle에 "직접·간접
  영향 나열"을 포함해 impact 없이 정답이 나오지 않는 과제를 최소 절반으로 만든다.
- freshness 질문(스냅샷 신선도 판단)은 별도 점수로 유지한다 — 0.9.0에서 v3가 freshness 8회로
  유의미한 사용을 보인 유일 축이었기 때문이다.

## v4 intake 상태 (선택 시점 기록)

이 절은 표본을 고정한 intake 시점의 기록이며 이후 단계로 바꾸지 않는다. 자격 검증 결과는 다음 절에 있다.

metadata만 고정했고 **아무것도 실행하지 않았다**. 표본 4건과 제외 2건은 고정 revision의 base SHA와
선언된 fail-to-pass 존재 여부만 보고 정했으며, 제품 질의·모델 실행·빌드·oracle 작성은 하지 않았다.
선택이 끝난 뒤 Java 두 사례의 변경 파일 경로만 dataset에서 확인했고 이 값은 선택에 쓰이지 않았다.
따라서 이 절은 어떤 효용 결과도 주장하지 않는다.

- 고정 표본: [cohort-v4.json](cohort-v4.json)
- 내림차순 선택 스캔 기록: [intake-v4/selection-scan.json](intake-v4/selection-scan.json)
- download receipt(url·bytes·sha256): [intake-v4/receipts.json](intake-v4/receipts.json)
- intake 시점의 남은 작업: 원본 빌드·focused 테스트 자격 검증, oracle 작성과 sha256 고정, 16회 실행, 채점.

## v4 자격 검증 상태 (완료, 모델 미실행)

원본 빌드·기존 테스트 실행·oracle 작성·제품 `impact` 오프라인 대조를 끝냈다. 모델은 실행하지 않았고
채점 key도 공개하지 않았다. 이 절은 효용 결과를 주장하지 않는다.

- 확정 oracle: [oracle-v4.json](oracle-v4.json), sha256
  `c05648e7916af7a0ad039cba4bfcf77caa9d5e8779c8fe7d8248679e77a276fb`
- 사례별 기록·도구 해시: [qualification-v4/](qualification-v4/README.md)
- 네 사례 모두 빌드에 성공했다. jackson은 미배포 parent 대신 정식 `2.16.0`을 쓰는 대체 POM으로,
  ktlint는 build-logic의 class file 65 때문에 JDK 21로 빌드했다. 우회는 모두 기록에 남겼다.
- 네 사례의 선언 fail-to-pass는 변경 전 원본에서 **실패하지 않는다**. 3건은 해당 테스트가 원본에 없고
  1건은 이미 통과한다. 새 테스트를 주입하지 않았고 이 사실을 결과에서 숨기지 않는다.
- 후보 4건 모두 새 depth-2 대상이 있어 4단계 규칙대로 사례 id 오름차순 앞 2건
  (`alibaba__fastjson2-2097`, `detekt_detekt-7625`)을 "직접·간접 영향 나열" 사례로 고정했다.
  **최소 2건 요건을 충족한다.**
- 사례별 oracle 크기는 fastjson2-2097 7개, detekt-7625 4개, jackson-core-1016 32개,
  ktlint-2785 4개다. 응답 상한 12개 때문에 `fasterxml__jackson-core-1016`의 recall 천장은
  0.375이며 나머지 세 사례는 1.00이다.
- 제품 `impact` 대조는 보고서로만 남겼고 oracle 항목을 더하거나 빼지 않았다. 불일치 2건
  (detekt의 relation 분류, ktlint의 클래스 포함 관계 확장)은 javap 증거를 유지한 채 기록했다.
- 남은 작업: 16회 실행과 채점.
