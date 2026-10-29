# AI 효용 실증 프로토콜 (사전 등록 — v4 실행 완료)

상태: **v4 16회 실행·채점 완료**(결과는 아래 [v4 결과](#v4-결과-16회-실행채점-완료) 절). 이 문서는
아래 절이 명시한 범위를 넘는 결과를 주장하지 않는다. 0.9.0의 48회 코호트 비교는
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
- 남은 작업: 없음. 16회 실행과 채점은 [v4 결과](#v4-결과-16회-실행채점-완료) 절에 있다.

## v4 결과 (16회 실행·채점 완료)

사전 등록한 16회를 고정 순서로 중단 없이 실행하고 채점했다. 선택적 재시작은 없었고 16회를 모두
그대로 남겼다. 이 절은 도구의 인과 효과를 주장하지 않는다.

### 실행 기록

- 하네스: [run_v4.py](run_v4.py), [grade_v4.py](grade_v4.py). v3의
  [run.py](../preflight-evaluation/run.py)와 [session_mcp.py](../preflight-evaluation/session_mcp.py)를
  복사하지 않고 import해서 쓴다. 모델·effort·12콜·900초·3 USD 상한·격리 flag는 v1~v3과 같다.
- 실행 manifest: [results-v4/manifest.json](results-v4/manifest.json), sha256
  `eeacfe16e9871e4ed6ddde8d2e8a2916c32c5bc3ef643c45fa8329ecf7b63a44`.
- 채점 규약(`scoringRules`)은 모델 노출 전에 manifest에 고정했다. sha256
  `1b356474687379bc67367c6957cad61d1328407503c4cbcd813ab0baaf183a5b`.
- oracle sha256은 실행 전후 모두 `c05648e7916af7a0ad039cba4bfcf77caa9d5e8779c8fe7d8248679e77a276fb`로 같다.
- 순서는 사전 등록대로 사례(cohort-v4 `primary` 순) → 조건(source 먼저, mcp 나중) → 반복(0, 1)이다.
- 16회 밖의 smoke 1회(`smoke-0`, fastjson2/mcp)를 인프라 확인용으로 먼저 실행했고
  [results-v4/smoke/](results-v4/smoke)에 따로 남겼다. **결과에 포함하지 않는다.** 재시도는 없었다.
- smoke 이후·16회 시작 전에 `scoringRules`에 overload 해설 문장 한 건을 추가했고 manifest의
  `preExecutionChanges`에 이전 해시와 함께 기록했다. 채점 규칙 자체, oracle, prompt, 코호트, 예산,
  순서는 바꾸지 않았다.

### 사례 × 조건

`recall` 분모는 사례 oracle 전체(직접+간접)이고, 천장은 min(1, 12/oracle 크기)다. `precision` 분모는
모델이 나열한 유효 target 수다. 파일 수준 부분 일치(0.5)는 recall·precision에 넣지 않고 따로 센다.

| 사례 | oracle | 천장 | 조건 | recall (2회) | recall 평균 | precision (2회) | precision 평균 | 초(평균) | USD(2회 합) |
|---|---:|---:|---|---|---:|---|---:|---:|---:|
| alibaba__fastjson2-2097 | 7 | 1.000 | source | 0.143, 0.429 | 0.286 | 0.083, 0.167 | 0.125 | 79.9 | 0.8304 |
| alibaba__fastjson2-2097 | 7 | 1.000 | mcp | 0.429, 0.286 | 0.357 | 0.250, 0.182 | 0.216 | 80.6 | 0.8349 |
| fasterxml__jackson-core-1016 | 32 | 0.375 | source | 0.000, 0.062 | 0.031 | (무효), 0.182 | 0.182 | 55.0 | 0.5381 |
| fasterxml__jackson-core-1016 | 32 | 0.375 | mcp | 0.000, 0.000 | 0.000 | 0.000, 0.000 | 0.000 | 57.2 | 0.5830 |
| detekt_detekt-7625 | 4 | 1.000 | source | 0.750, 0.750 | 0.750 | 0.300, 0.300 | 0.300 | 47.0 | 0.5336 |
| detekt_detekt-7625 | 4 | 1.000 | mcp | 0.750, 1.000 | 0.875 | 0.300, 0.364 | 0.332 | 65.7 | 0.6852 |
| pinterest_ktlint-2785 | 4 | 1.000 | source | 0.250, 0.250 | 0.250 | 0.167, 0.167 | 0.167 | 43.7 | 0.4758 |
| pinterest_ktlint-2785 | 4 | 1.000 | mcp | 0.000, 0.250 | 0.125 | 0.000, 0.143 | 0.071 | 49.0 | 0.5239 |

코호트 평균(4사례 전부, 조건당 8회): source recall 0.329 / precision 0.195, mcp recall 0.339 /
precision 0.155. 무효 응답(jackson source 반복0) 1건은 recall에 0으로 포함되고 precision은 분모가 없어 평균에서
빠지므로 source precision 0.195는 유효 7회의 평균이다. 이 취합 방식은 `grade_v4.py`의 동작을 실행 후 서술한 것이며
사전 등록 문면에는 없었다. `fasterxml__jackson-core-1016`은 천장이 0.375라 위 표에 별도 행으로도 남겼다.
jackson을 뺀 서술용 평균은 source recall 0.429 / precision 0.197, mcp recall 0.452 / precision 0.206이며
대체 코호트 평균이 아니다.

파일 수준 부분 일치(oracle 기준/예측 기준)는 fastjson2 source 반복1이 2/1, jackson source 반복1이 6/2,
jackson mcp 반복0이 8/3, jackson mcp 반복1이 1/1, detekt mcp 반복0이 1/1이고 나머지는 0/0이다.
recall·precision에는 넣지 않았다.

### 제품 도구 사용과 freshness (mcp 조건, 기록만 하고 점수로 쓰지 않음)

| mcp 실행 | 제품 호출 요청/처리 | 내역(요청) | freshness |
|---|---|---|---|
| fastjson2-2097 반복0 | 2 / 2 | freshness 1, query_symbol 1 | 1회, `unverified` |
| fastjson2-2097 반복1 | 2 / 2 | freshness 1, impact 1 | 1회, `unverified` |
| jackson-core-1016 반복0 | 1 / 1 | freshness 1 | 1회, `unverified` |
| jackson-core-1016 반복1 | 1 / 1 | freshness 1 | 1회, `unverified` |
| detekt-7625 반복0 | 1 / 1 | freshness 1 | 1회, `unverified` |
| detekt-7625 반복1 | 4 / 3 | freshness 1, query_symbol 1, impact 2 | 1회, `unverified` |
| ktlint-2785 반복0 | 1 / 1 | freshness 1 | 1회, `unverified` |
| ktlint-2785 반복1 | 1 / 1 | freshness 1 | 1회, `unverified` |

freshness는 mcp 조건에서만 관측 가능하므로 **별도 점수로 분리**하고 조건 간 비교에 쓰지 않는다.
8회 모두 `freshness`를 정확히 1회 호출했고 8회 모두 `unverified`였다. 사유는 네 사례 모두
`build-scope-mismatch`, `missing-build-witness`, `unwitnessed-class-root`다. 자격 검증 때와 같이
snapshot을 수동으로 캡처해 compiler witness가 없기 때문이며, 제품 결함 판정이 아니다.
source 조건에는 freshness가 없으므로 0회이고, 이 0을 낮은 점수로 읽지 않는다.

제품 호출은 16회 중 mcp 8회에서 요청 13회·처리 12회다. `impact`는 2회 실행에서 3회 요청(2회 처리),
`query_symbol`은 2회 실행에서 각 1회 요청·처리됐다. v1~v3에서 `impact` 사용이 0회였던 것과 달리
사용은 나타났지만, **이 횟수를 점수로 환산하지 않으며 점수 차이의 원인으로도 해석하지 않는다.**

### 예산·시간·무효 응답

- 16회 총 비용 5.0049 USD(실행당 0.2301~0.4539 USD). 실행당 상한 3 USD와 총 48 USD 모두 넘지 않았다.
  smoke 1회 0.4250 USD를 더하면 5.4299 USD다.
- 벽시계는 실행당 42.3~88.2초, 16회 합계 956.2초(실행별 초를 합한 뒤 반올림)(전체 경과 959.2초)다. 900초 상한에 걸린 실행은 없다.
- 16회 모두 exit 0이고 인프라 오류·timeout·provider 오류·stream 손상은 0건이다. 도구 표면은 16회 모두
  기대한 집합과 정확히 같았고(`source_*` 3개, mcp 조건은 여기에 `query_symbol`/`impact`/`freshness` 추가),
  MCP 서버 상태는 모두 `connected`였다.
- 무효 응답 1건: `fasterxml__jackson-core-1016` source 반복0. 사유 `invalid-json`이다. 모델이 JSON 앞에
  "Budget exhausted; concluding with the evidence gathered." 산문을 붙여 v3과 같은 파서 규칙에서 무효가 됐다.
  이 실행을 다시 돌리지 않았고 recall 0.000·precision 미산출로 그대로 남겼다.
- 12회 공유 도구 예산은 16회 모두 소진됐다. 요청은 13~15회였고 초과분(1~3회)은 proxy가 거부했다.
  거부는 설계된 동작이며 인프라 실패가 아니다.

### 이 결과가 보여주는 것과 보여주지 않는 것

- **보여주는 것**: 사전 등록한 16회를 계약대로 실행했고, 두 조건의 oracle 대비 recall·precision을
  같은 규칙으로 산출했으며, mcp 조건에서 제품 그래프 도구가 실제로 호출됐고 freshness가 8회 모두
  `unverified`를 반환했다는 사실.
- **보여주지 않는 것**: 도구가 점수를 올렸다는 인과. 코호트 평균 차이(recall +0.010, precision -0.040)는
  사례 4건·조건당 8회 규모에서 사례 간 분산보다 작고, 방향도 사례마다 갈린다(fastjson2·detekt는 mcp가
  높고 ktlint·jackson은 낮다). **도구 호출 횟수와 점수의 연결을 따로 보이지 않았으므로 인과로 읽지 않는다.**
- `fasterxml__jackson-core-1016`의 recall 천장은 응답 상한 12개와 oracle 32개 때문에 **0.375**다.
  이 사례의 낮은 recall을 도구 효용 차이로 읽으면 안 된다.
- 네 사례의 선언 fail-to-pass는 **변경 전 원본에서 실패하지 않는다**(3건 부재, 1건 통과). 새 테스트를
  주입하지 않았고 이 사실을 결과에서 숨기지 않는다.
- transitive 두 사례(`alibaba__fastjson2-2097`, `detekt_detekt-7625`)는 사전 등록한
  문자열 순서 tie-break(cohort-v4 `id`의 Unicode code point 오름차순)로 정했으며 제품·모델 결과를 보지 않았다.
- symbol 매칭은 v3 `grade.py` 규칙을 그대로 쓰므로 **동명 overload를 구분하지 않는다.** 예측 하나가
  같은 파일의 동명 oracle 항목 여러 개에 점수를 줄 수 있고, 정확한 USR을 쓰면 하나만 맞는다.
  동명 overload가 많은 사례에서 recall이 구조적으로 높게 나올 수 있다.
- oracle은 완전한 동적 영향 정답이 아니다. javap 직접/2단계 참조와 원본에서 실제 실행한 기존 테스트,
  원본 override 선언으로 이루어진 검토 anchor다. 추가 대상을 자동으로 오탐으로 단정하지 않는다.
- anchor 점수(사전 고정 검토 기준)와 섞지 않았다. 0.9.0의 48회 코호트 비교는 별도 결과로 유지한다.
- 동결 시점에는 수동 검토가 남아 있었다(`manualReviewPending: true`). 조건명을 가린 자료는
  [results-v4/blind/](results-v4/blind)에 있고 대응표는 [results-v4/blind-key.json](results-v4/blind-key.json)이다.
  2026-09-21 [후속 측정·답변 구체성 감사](review-v4/README.md)는 16개 응답과 예측 147개를 대조하고
  overload 다중 가점 1건·존재하지 않는 예측 파일 경로 1건을 확인했다. 원래 점수는 바꾸지 않았으며,
  추가 target 전체의 동작 판정·독립 인간 검토·새 matched-input 코호트 실행은 완료하지 않았다.

### 증거

- 채점 결과: [results-v4/results-v4.json](results-v4/results-v4.json)
- 실행 기록: [results-v4/progress.json](results-v4/progress.json),
  [results-v4/execution.json](results-v4/execution.json)
- 최종 응답 원문: [results-v4/answers/](results-v4/answers)
- MCP proxy trace: [results-v4/traces/](results-v4/traces)
- 저장소에 넣지 않은 stream 로그·snapshot·diff의 sha256과 scratch 경로:
  [results-v4/evidence-hashes.json](results-v4/evidence-hashes.json). 로컬 절대경로는 공개 기록에서
  제외하고 `<scratch>`·`<qual-scratch>`·`<kartograph-cli-install>` 토큰으로 적는다.
