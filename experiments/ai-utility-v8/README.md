# v8 완료 — 실제 모듈에서 추가 효용 미확인, selector 사용 실패를 관찰

2026-09-22, [INTAKE](INTAKE.md)·[프로토콜](PROTOCOL.md)·[네 제안 변경](changes.json)을 고정한 뒤
source/MCP 각8회·총16회를 모두 실행했다. **16/16 구조화 응답 유효·인프라 오류0·선택적 재시작0**이다.
원문112개 해시·반환 객체/StructuredOutput tool input·원문 재채점이 일치했다. v5/v6/v7 105개 파일은 그대로다.

## 고정 규칙의 결과

숫자는 각 반복의 정확한 식별자 대응 coverage(%)다. 호출자와 실제 assertion 변경의 분모를 합치지 않는다.

| 변경 | 호출 source / MCP | 동작 source / MCP | 유지 테스트 오선택 source / MCP |
|---|---|---|---|
| detekt line content | 0·100 / 100·0 | 0·100† / 100·0† | 0·0 / 0·0 |
| detekt trimming methods | 100·100 / 100·100 | 100·100 / 100·100 | 0·0 / 0·0 |
| ktlint disabled max length | 92.9·57.1 / 100·100 | 50·50 / 50·50 | 0·0 / 0·0 |
| ktlint import subpackages | 100·100 / 25·100 | 76.9·23.1† / 69.2·53.8† | 2·0 / 1·0 |

원래 점수는 [report.json](results/report.json)에 보존했다. 결과가 사례·반복마다 달라 **일관된 추가 효용 우위는 확인하지 못했다**.
특히 MCP 조건에서 실제 제품 요청은 impact3회였고 **셋 다 notFound**였다. 유효한 changed target/영향 경로를 얻지
못했으므로 더 완전했던 답변을 graph 경로 사용의 효과로 해석하지 않는다. source 도구는 두 조건에 동일하게 제공했다.

## 원문 대조와 selector 진단

† 미대응18개를 전부 대조했다. 16개는 정확한 test 이름·파일·근거 줄을 제시했지만 `@Nested` 소유자를 생략한
부분 한정 이름이었다. literal test 이름만 쓰거나 완전한 nested owner를 쓰는 사전 별칭과 달라 점수를 받지 못했다.
이를 테스트를 전혀 찾지 못했다고 해석하지 않는다. 나머지2개는 실제 선언과 다른 caller/test 이름이었다.
근거 줄이 특정 선언을 가리켜도 사후 자동 수리·가점하지 않았다. [식별자 대조](results/identity-readback.json)를 따른다.

- line content의 caller0점 답변들은 실제 `visitKtFile` 호출자 대신 별도의 downstream `visit(KtFileContent)` 메서드를
  선택했다. 유효한 검토 대상일 수 있지만 이번 정적 caller oracle과는 다른 선언이라 oracle 밖으로 유지한다.
- disabled max length는 양쪽 네 답변이 모두 `FunctionLiteralRuleTest`의 disabled-rule 회귀 한 개를 빠뜨렸다.
  따라서 동작50%는 양성2개 중1개만 예측한 결과이며 이 항목의 누락은 식별자 표기 문제와 구분한다.
- import subpackages에서 실제로 통과한 test를 실패할 것으로 예측한 항목은 source2개·MCP1개였다.
  미대응 예측은 이 음성 대조 수에 포함하지 않고 별도 보고한다.

oracle 밖 대응13건은 production10건(source5/MCP5)과 관찰된 음성 test3건(source2/MCP1)이다.
실행 범위 밖 test 선택은0건이다. `observedTestPrecision=null`은 판정 가능한 test 예측의 분모가0일 때이며,
양성 예측이0이어도 음성 대조를 선택했다면 precision은0.0이다.

모델이 보낸 잘못된 경로/선택자를 같은 MCP에서 사후 재현했고 모두 notFound였다. 하나는 모듈 이름을 다시 붙인
repository-relative 파일 경로였고, 둘은 JVM file-facade owner가 없는 source 형태 이름이었다.
동일 입력의 정확한 JVM USR로 좁힌 별도 진단은 모두 대상을 찾았다(found1·partial2). 이는 모델 재실행이나 새 점수가 아니다.
line-content의 파일 경로만 바로잡은 진단은 추가 선언까지 선택해16KiB 응답 한도에 걸렸고, 단일 함수 USR로 좁혀야 했다.
페이지가 partial인 결과를 전체 영향이라고 부르지 않는다. [selector 대조](results/selector-readback.json)를 따른다.

이번 결과는 기본 도구 제공만으로 agent가 유효한 graph 선택자에 도달하지 못한 사용 흐름도 드러낸다. selector 복구와
scope/페이지 안내를 검증하는 것이 구체적인 후속 후보이며, 이를 고친 이후의 효용은 새 실험으로 판단해야 한다.

## 시간·비용·도구

| 변경 | source 평균초 | MCP 평균초 |
|---|---:|---:|
| detekt line content | 23.9 | 27.9 |
| detekt trimming methods | 30.2 | 28.7 |
| ktlint disabled max length | 25.4 | 28.6 |
| ktlint import subpackages | 68.9 | 72.6 |

모델 단계 **612.61초**, CLI 비용 추정 합계 **3.5699645USD**다. source296.77초·1.7021325USD,
MCP315.83초·1.867832USD다. 정보 도구 요청은 source51회·MCP55회이며, 제품 impact3회 외에는 source 도구를 썼다.
query_symbol/freshness를 모델이 호출하지 않았다. 실행기에서 매 trial 전후 수행한 matched 검증과 agent의 도구 사용은 구분한다.
준비·진단·리뷰·최초 설치 시간과 청구액을 뜻하지 않는다. [요약](results/summary.json),
[원래 답변](results/answers.json.gz), [검증](results/verification.json)에 세부를 보존했다.

## 범위와 실제 실행

| 모듈 | production/test 파일 | 빈 줄 제외 source 줄 | baseline test invocation | snapshot |
|---|---:|---:|---|---|
| detekt-rules-style 1.23.8 | 92 / 88 | 36,269 | 3,649 pass | 6,105 nodes / 25,076 edges, matched |
| ktlint-ruleset-standard 1.5.0 | 104 / 103 | 54,253 | 2,253 pass / 11 skip | 5,593 nodes / 29,693 edges, matched |

정확한 commit은 [cohort.json](cohort.json)에 있다. 전체 다중 모듈 저장소를 캡처한 것은 아니며 의존 모듈 구현은
제공 범위 밖일 수 있다. 연구자가 원본 공유 헬퍼를 읽어 사전 등록한 제안 변경이며 자연 발생 PR·무작위 대표 표본은 아니다.
새 toy 코드나 새 assertion을 본 평가의 정답으로 사용하지 않는다. 모듈 크기만으로 과제가 어렵다고 단정하지 않는다.

## 독립 정답

| 제안 변경 | 정적 production 호출자 | assertion 변경 메서드 | 통과 유지 메서드 |
|---|---:|---:|---:|
| detekt line content | 2 | 4 | 1,847 |
| detekt trimming methods | 2 | 2 | 1,849 |
| ktlint disabled max length | 14 | 2 | 1,802 |
| ktlint import subpackages | 4 | 13 | 1,791 |

각 사례의 baseline은 같은 고정 원본이다. **호출자와 동작 지표는 분리**하며 같은 테스트를 여러 변경에서 조사한
횟수를 고유 테스트 수로 합산하지 않는다. 호출 정답은 제품 graph를 읽지 않는 javap와 Kotlin PSI/source set으로 만들었다.
동작 정답은 실제 기존 test task의 baseline pass→changed assertion-failure다. 네 제안 변경은 별도 checkout에서 적용했다.
변경 후 tracked diff가 제안 production diff와 같고 test source는 바뀌지 않았음을 확인했다.

JUnit의 실제 MethodSource/JVM descriptor와 invocation UID를 관찰하고, journal을 닫은 뒤 SHA256 seal을 남긴다.
parameterized 표시명 중복·nested·dynamic·leaf skip을 실제 엔진에서 검증했다. test가 성공해도 observer 파일 기록이
실패하면 증거를 거부한다. 등록/종료 수·UID·source identity와 Gradle JUnit XML 총수도 대조한다.
이 버전이 identity를 기록하지 않는 container skip은 거부한다.

ktlint의 상속 테스트 한 메서드는 의존 모듈 ktlint-test에 선언돼 있다. 두 상태의 실행과 통과 결과는 유지하고 제공 source
범위 밖으로 분리했다. 만약 그 메서드가 실패했다면 해당 사례는 미적격이다. 제공한 class 안의 미대응 메서드를 임의로
빼지 않는다. detekt 테스트 이름 두 개에 있던 실제 끝 공백은 새 javap reader와 backtick alias에서 보존해 모두 대응했다.
사례별 primary43개·음성 대조7,289개, 총7,332개 선언 쌍의 source/USR 대응을 확인했다. 이는 사례 간 중복을 포함한다.

## 준비 실패와 검증

첫 detekt 실행은 테스트가 통과했지만 compiler capture가 거부됐다. 평가 adapter가 test-only observer.jar를
compiler buildInputs에 넣어 발생한 문제였다. 미공급 compiler artifact가 observer.jar 하나임을 확인해 입력 분류를 고쳤다.
다시 실행한 테스트 3,649개의 identity/결과와 compiler 출력701개는 그대로였고 발행0.15.0 snapshot은 matched였다.
원본 실패와 진단용 setup 실패도 보존했다. [처분](capture-adapter-disposition.json)을 따른다.

```sh
python3 -m unittest discover -s experiments/ai-utility-v8 -p 'test*.py' -v
python3 experiments/ai-utility-v8/verify_observer.py --jdk "$JAVA_HOME" --download-junit --output build/reports/ai-utility-v8-observer
```

JUnit control은 Maven Central의 고정1.10.2 standalone artifact를 SHA256으로 검사한다. 원래 대상 프로젝트는 각각의
JUnit 버전을 그대로 쓴다. 관찰자 JAR는 test runtime에만 추가하며 production compiler runtime으로 넣지 않는다.
모델 전에 observer smoke와 회귀40개가 통과했고 같은 검사를 CI에 추가했다. 검증 결과는 [qualification](qualification.json),
[source/USR 대조](oracle-parity.json)에 있다. 실제 원장·실패·JVM/JUnit 원문은 로컬 `.git/ai-utility-v8-20260922/`에 있으며
다른 checkout에 존재한다고 가정하지 않는다. 출력 디렉터리는 재사용하지 않는다.

사전 GLM 검토의 baseline 빌드 후 tracked 변경 거부를 회귀로 보완했고, 두 실제 baseline은 원래부터 clean이었다.
[사전 처분](review-disposition.json)·[결과 검토 처분](results/results-review-disposition.json)에 반영/기각 근거와
외부 리뷰의 집계 오독을 구분했다. 현재 native 원본은 archive 상태다. 약75.10MB archive의38,043개 member를
실제 복원·해시 대조하고 두 baseline snapshot matched와 준비 artifact2,028개를 확인한 뒤 cache를 정리했다.
원장 배정 용량은 약717.24MB→166.70MB이며, 정리 후 보고서는 byte-identical하게 재생성됐다.
[정리·복원 계약](results/cleanup.json)을 따르고 원래 실행 manifest/bindings는 수정하지 않는다.

실제 모델은 기존과 같은 claude-opus-5[1m]/low·12정보호출·900초·3USD 제한으로 source/MCP 각2회·전체16회를 실행했다.
파일 내 유일한 source 이름을 허용하고 정확한 overload만 서명으로 구분하게 한다. 원문을 사후 수리하지 않으며,
유효성·호출 recall·관찰된 assertion 변경 recall·음성 대조 오선택·미대응·시간·CLI 추정 비용을 각각 보고한다.
실제 개발 생산성이나 모든 runtime 경로의 완전성을 주장하지 않는다.

원본 출처: [detekt 고정 revision](https://github.com/detekt/detekt/tree/046263730eb5368cb344489ac36543294e8e87bd),
[ktlint 고정 revision](https://github.com/ktlint/ktlint/tree/b44a53f414d81cdf5816a26c182ba2738e3c9670).
관련 원본 라이선스는 [detekt](upstream-licenses/detekt.txt)·[ktlint](upstream-licenses/ktlint.txt)에 보존했다.
