# v7 완료 — 동작 예측은 동률, 호출 점수의 표기 차이는 별도 기록

2026-09-22, [프로토콜](PROTOCOL.md)과 [입력](cohort.json)을 고정한 뒤 source/MCP 각 8회·총 16회를 완료했다.
**16/16 구조화 응답 유효·인프라 오류 0·선택적 재시작 0**이다. 원문 112개 파일 해시와 반환 객체/원래
StructuredOutput tool input·원문 재채점이 일치했다. 기존 v5/v6 61개 파일은 byte-identical이다.

## 고정 규칙의 결과

각 값은 두 반복의 coverage(%)다. 호출은 독립적으로 확인한 정적 production 호출자, 동작은 실제 변경 후
assertion이 실패한 기존 테스트다. 이 두 분모를 합쳐 하나의 효용 점수로 만들지 않는다.

| 사례 | 호출 source / MCP | 동작 source / MCP | 유지된 테스트 오선택 |
|---|---|---|---:|
| threshold | 100·100 / 100·100 | 100·100 / 100·100 | 양쪽 0 |
| nulls | 100·100 / 100·100 | 100·100 / 100·100 | 양쪽 0 |
| callback | 80·80† / 100·100 | 100·100 / 100·100 | 양쪽 0 |
| dispatch | 100·100 / 100·100 | 100·100 / 100·100 | 양쪽 0 |

† source도 두 번 모두 실제 `Relay.dispatch`와 정확한 forwarding 줄을 지목했다. 그러나 한 번은
`Function2<Int,String,String>`, 한 번은 type 인자가 생략된 `Function2`를 썼고, 사전 등록한 원본 source 함수 타입
표기와 대응하지 않았다. 이를 caller 발견 차이로 해석하지 않는다. 사후 동치 규칙을 추가하거나 점수를 바꾸지 않았다.
MCP의 dispatch 첫 응답에도 `Portal.enter`의 반환형을 String 대신 boolean(`Z`)으로 쓴 잘못된 JVM USR이 있었다.
이 대상은 정적 oracle 밖이어서 primary 호출 점수에 영향을 주지 않는다. [미대응 3개 원문 대조](results/source-identity-readback.json)에
모두 남겼다. source의 oracle 밖 선언 4개·MCP 3개는 미판정으로 유지한다.

**관찰한 동작 예측은 동률이며, 일반적인 효용·caller 발견 우위는 확인하지 못했다.** 각 모듈은 source 파일 3개인
작은 합성 사례이고 동작 지표가 모두 100%인 천장 효과가 있다. 설명 문장의 정확도를 자동 채점한 결과도 아니다.
원문 보존용 `grading.primaryRecall`은 v5 호환 식별자 대응 진단이며 v7의 효용 결과로 사용하지 않는다.

## 시간·비용·실제 도구

| 사례 | source 평균초 | MCP 평균초 |
|---|---:|---:|
| threshold | 21.9 | 23.6 |
| nulls | 18.5 | 24.3 |
| callback | 21.9 | 24.5 |
| dispatch | 16.0 | 21.8 |

모델 단계 합계 **345.18초**, CLI 비용 추정 **1.7533455 USD**다. source는 156.73초·0.6039525 USD,
MCP는 188.45초·1.149393 USD였다. 실제 청구액이나 전체 작업 비용이 아니며 이 작은 과제 밖의 비용 순위로 일반화하지 않는다.
정보 도구 요청은 source 43회·MCP 40회다. MCP가 실제 처리한 제품 요청은 freshness 4·impact 7·query_symbol 2회다.
freshness는 모두 matched, impact는 모두 partial, query는 found 1·notFound 1이었다. 전체 그래프를 빠짐없이 조회했다고
주장하지 않으며 원문과 한계를 보존했다. [고정 보고서](results/report.json), [요약](results/summary.json),
[답변 원문](results/answers.json.gz), [검증](results/verification.json)을 따른다.

## 표기 대응 개선

v6에서 발견한 함수 타입의 선택적 매개변수 이름을 [새 매칭기](grade.py)에서 정규화한다.
`(amount: Int, currency: String) -> String`과 `(Int, String) -> String`은 같은 source 표기로 대응하지만,
타입·순서·nullability·반환 타입·모호한 overload·backtick 선언 이름은 보존한다. Kotlin 전체 타입 동치나
import/typealias 해석기를 제공하는 것은 아니다. 제품 query 문법을 변경하지 않는다.
기존 v5/v6 원문·정답·점수는 수정하지 않고 v6 관측 사례는 회귀 검사에만 사용한다.

## 독립 정답

네 개의 새 Kotlin 통제 사례는 먼저 작성한 [원본](fixtures/)과 [production 변경](changes.json)을 사용한다.
baseline 테스트 27개가 전부 통과했고, 별도 checkout에 해당 변경만 적용하면 assertion 실패 12개·통과 15개다.
실행 오류·skip·테스트 유실은 없으며 test source는 동일하다. 실패 12개를 동작 양성으로, 통과 15개를
관찰된 음성 대조로 정했다. suite 이름만으로 테스트를 정답에 넣지 않는다. 음성 대조에는 다른 overload/구현을
호출하는 테스트도 있어, 관찰된 test precision은 변경 함수에 도달한 테스트만의 정밀도가 아니라 이 suite의 과잉 예측을 측정한다.

| 사례 | 정적 production 호출자 | assertion 변경 | 유지 |
|---|---:|---:|---:|
| threshold | 3 | 3 | 4 |
| nulls | 3 | 3 | 4 |
| callback | 5 | 3 | 3 |
| dispatch | 1 | 3 | 4 |

호출 정답은 제품 graph를 읽지 않는 javap와 Kotlin PSI의 정확한 1·2단계 호출·선언 대응이다.
동적 인터페이스 dispatch가 누락될 수 있으므로 oracle 밖 production 응답을 오탐으로 세지 않는다.
dispatch 사례의 정적 호출자 1개와 실제 assertion 변경 3개는 서로 다른 근거다.
발행된 0.15.0 CLI/plugin으로 compiler snapshot 네 개가 모두 `matched`였으며 main/test class를 포함한다.

## 실행과 검증

```sh
python3 -m unittest discover -s experiments/ai-utility-v7 -p 'test*.py' -v
python3 experiments/ai-utility-v7/prepare.py --help
python3 experiments/ai-utility-v7/run.py --help
python3 experiments/ai-utility-v7/report.py --help
```

`prepare.py`는 독립 출력 디렉터리와 Gradle, 0.15.0 배포 CLI/plugin, JDK17, v5 Kotlin PSI parser의
`classes`/`classpath` 설정을 받는다. 고정 fixture를 복사한 baseline/changed 저장소에서 실제 테스트·capture·
독립 oracle을 만든다. `run.py --config … --output …`로 manifest를 만들고 별도 커밋에 해시와 순서를 고정한 뒤
`--execute`한다. 기존 실행 디렉터리를 재사용하지 않는다. `report.py`는 전체 16회와 원문 해시·원래 답변 재채점을 확인한다.

회귀 56개가 통과하며 CI에서도 같은 검사를 실행한다. GLM 사전 검토의 tool hash 확인·음성 대조 alias 대칭성
보완은 red/green으로 검증했다. [검토 처분](review-disposition.json)을 따른다. v6의 변경 없는 provider/schema smoke를
재사용했고, v7 실제 사례를 smoke로 먼저 노출하지 않았다. 원문은 `.git/ai-utility-v7-20260922/` 로컬 원장에 보존한다.
다른 checkout에 이 로컬 경로가 존재한다고 가정하지 않는다.

[qualification.json](qualification.json)의 `preparationSeconds`는 baseline/changed **Gradle 명령 두 개의 시간 합계**다.
결과 summary의 `nativeBuildAndTestSeconds`도 같은 44.48초를 명확한 필드 이름으로 기록한다.
기존 설치와 warm dependency cache를 사용했고 parser 실행·모델·리뷰·최초 설치 시간은 포함하지 않는다.
준비와 모델 사용 비용을 혼합하거나 첫 사용 성능으로 해석하지 않는다.

결과 해석·후속 보완의 GLM 리뷰에도 차단사항은 없었다. [결과 검토 처분](results/results-review-disposition.json)에
외부 리뷰의 산식 오타와 원문 재계산 결과도 구분했다. 완료 후 native 원본을 약343KB archive로 보존하고 2,164개 member를
실제 복원·해시 대조했으며 snapshot4개가 matched였다. 중복 parser는 보존된 v6 archive의 동일6개 파일을 확인한 뒤 정리했다.
작업 원장 배정 용량은 약74.79MB→4.26MB로 줄었고 원문183개 해시·v5/v6 61개가 유지됐다.
정리 후 보고서도 byte-identical하게 재생성했다. [정리·복원](results/cleanup.json)을 따르며 현재 native 입력은 archive 상태다.

작은 합성 사례의 알려진 호출자 coverage와 관찰된 assertion 변경 대응만 측정한다. 설명 문장의 reasoning 품질,
복잡한 공개 프로젝트에서의 탐색 효용, 일반 생산성·삭제 안전성은 이 실험으로 입증하지 않는다.
