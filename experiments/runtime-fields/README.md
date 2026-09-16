# 필드 값 흐름의 실행·영향 비교

2026-09-12, 구현 결과를 보기 전에 다음 표본을 고정했다. 목적은 PR #36의 알려진 누락을 실제 실행과
`impact` 경로로 대조하는 것이다. 정적 그래프 전체의 정확도나 실제 저장소 변경의 대체 점수가 아니다.

| 표본 | 실제 실행 | 비교할 정적 근거 |
|---|---|---|
| reflective_initial | 변경 가능한 static Class 필드를 reflection으로 읽어 생성자 실행 | 원래 누락과 같은 mutable 필드; final 상수로 바꾸지 않음 |
| direct_string | static helper가 초기화한 String 필드로 class 로딩·생성 | GETSTATIC과 helper 초기화 |
| reassigned | helper가 Class 필드를 재대입한 뒤 reflection으로 읽음 | 초기값만 수집해서 마지막 대입 대상을 놓치는 경우 |
| inherited | 자식 Class의 public lookup으로 부모의 필드를 읽음 | JVM 상속과 reflection 조회 규칙 |
| kotlin_object | `@JvmField var`의 Class 값을 읽어 생성 | 실제 Kotlin compiler와 object 초기화 |
| unknown_write | 외부 실행 인자의 class를 reflection으로 필드에 쓰고 읽음 | 인자는 정적 분석기에 제공하지 않음; 미해결 경계를 보존해야 함 |

모든 표본에는 실행되지 않는 `Unused`가 있으며 JVM은 `USED`를 출력해야 한다. 사용 생성자를 바꾸면
실행 결과에 영향을 주는 경로가 존재하므로, `Used`의 변경 영향 보고서에 `Entry.main`이 포함되는지 확인한다.
`unknown_write`의 관측된 실행은 입력 공간의 한 가지 경우이며 임의 인자를 정적으로 알아낼 수 있다는 기대값이 아니다.

기준 CLI 배포 디렉터리는 `e505a92`에서 별도로 보존하고 JAR SHA256을 기록한다. 비교 시 양쪽에 같은
compiler 출력·keep rule·classpath를 제공한다. 그래프 생성과 저장 질의의 비용을 분리한다. raw 명령이나
로컬 절대경로를 공개 결과에 포함하지 않는다.

## 결과

같은 실제 compiler 출력을 양쪽 CLI에 제공했다. JDK 17과 Kotlin 2.4.10, macOS arm64에서 질의를 각 3회 실행했다.

| 표본 | 변경 전 main 영향 경로 | 변경 후 main 영향 경로 | 미사용 대조군의 main 경로 |
|---|---|---|---|
| reflective_initial | 누락 | 복원 | 없음 |
| direct_string | 누락 | 복원 | 없음 |
| reassigned | 누락 | 복원 | 없음 |
| inherited | 누락 | 복원 | 없음 |
| kotlin_object | 누락 | 복원 | 없음 |
| unknown_write | 누락·미해결 표시 | 미해결 표시 유지 | 없음 |

이 다섯 지원 표본에서는 실제 사용 대상을 새로 연결했다. 모든 필드 후보는 초기화 순서·unknown/외부 write 가능성을
함께 가지므로, 후보가 있어도 관련 미해결 호출 개수가 남는다. 재대입의 마지막 값이나 실제 실행 순서를 증명하지 않는다.
별도 compiler 회귀는 `ConstantValue`의 String, 문자열 길이/후보 수 한도, public/declared·숨김·interface lookup,
초기화 순환·재진입, 첫 class root 선택과 외부 field handle 변경 가능성을 확인한다.

고정 nowinandroid `12f80da6518e161ed16a06a68e71fb8a873576d6`, demoDebug의 같은 22개 class root에서
기본 진단 4개·private 진단 2개·multipreview 8개와 생성 출처/미사용 대조군 계약을 유지했다.
성능은 준비·빌드를 제외하고 프로세스 시작·입력 읽기·출력을 포함한다. 후보가 많아지는 개선이 전체 프로그램의
정밀도 향상을 뜻하지 않으며, 작은 시간 차이를 속도 개선으로 해석하지 않는다.

[기계 판독 결과](results-2026-09-12.json)는 비교 원본 JSON, 두 공개 앱 보고서, 자체 분석과 기존 SDK R8 회귀 보고서를
그대로 묶고 baseline commit과 제품 source SHA256을 덧붙인 것이다. CLI JAR·fixture source·실제 class/dependency
해시를 포함한다. SDK R8 결과는 고정된 기존 회귀 계약이며 최신 도구 전체의 우위 평가가 아니다.

## 재현

기준/현재 CLI 배포 디렉터리와 저장소의 compiler 의존성을 준비한 뒤 실행한다. 비교 script는 도구를 다운로드하지 않는다.
Kotlin 표본은 별도 임시 프로젝트에서 offline Gradle로 빌드한다. `--case`로 실행 범위를 명시할 수 있다.
기능 기대값을 만족하지 않으면 보고서를 남기고 1, 도구/입력 실패는 2를 반환한다.

```sh
export JAVA_HOME=/path/to/jdk17
python3 Scripts/compare-runtime-fields.py \
  --baseline /path/to/e505a92/bin/kartograph \
  --binary cli/build/install/kartograph/bin/kartograph \
  --output build/reports/runtime-fields-comparison.json
```
