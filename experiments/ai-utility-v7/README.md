# v7 — source 표기 대응과 호출·동작 통제 평가

상태: 모델 본 실행 전. [프로토콜](PROTOCOL.md), [입력](cohort.json), [동작 근거](behavior-evidence.json)를
고정하고 전체 실행·원문 검증을 준비했다. 모델 결과는 실행 완료 후 같은 문서에 추가한다.

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

[qualification.json](qualification.json)의 `preparationSeconds`는 baseline/changed **Gradle 명령 두 개의 시간 합계**다.
기존 설치와 warm dependency cache를 사용했고 parser 실행·모델·리뷰·최초 설치 시간은 포함하지 않는다.
준비와 모델 사용 비용을 혼합하거나 첫 사용 성능으로 해석하지 않는다.

작은 합성 사례의 알려진 호출자 coverage와 관찰된 assertion 변경 대응만 측정한다. 설명 문장의 reasoning 품질,
복잡한 공개 프로젝트에서의 탐색 효용, 일반 생산성·삭제 안전성은 이 실험으로 입증하지 않는다.
