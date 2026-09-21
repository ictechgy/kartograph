# v5 — matched 입력을 사용하는 모듈 영향 조사

상태: **모델 본 실행 전 고정**. 선택·준비의 원본과 변경은 [INTAKE](INTAKE.md)에 있다.
이 실험은 일반 AI 생산성이나 저장소 전체의 정확도를 증명하지 않는다.

| 항목 | 고정 계약 |
|---|---|
| 표본 | detekt7212·6446, ktlint2774·2727. 이전 코호트에서 쓰지 않은 공개 Gradle 변경 4건 |
| 범위 | 변경된 production 모듈의 기존 source와 컴파일된 main/test. 외부 모듈 전체 그래프는 없음 |
| 조건 | 같은 source 도구 3개 / 같은 source + 실제 kartograph MCP query_symbol·impact·freshness |
| 모델 | Claude Code `claude-opus-5[1m]`, effort low. 개인 instruction·memory·기본 파일/실행 도구 비활성화 |
| 예산 | 조건당 정보 도구 12회, 900초, 최대 3 USD. 응답·oracle 상한 64개 |
| 반복 | 사례별 source/MCP 각 2회 = 16회. 반복0 source→MCP, 반복1 MCP→source |
| 준비 게이트 | 매 trial 전후 실제 CLI verify-snapshot `matched`, source·snapshot·bindings·oracle·도구 해시 일치 |
| 출력 | JSON 한 개. 언어 표기 `json`(소문자) 또는 언어 없는 단독 code fence는 본문·종료선을 개행으로 구분한 경우만 허용하며 앞뒤 설명문은 무효. 실패·예산 소진·무효 응답 보존 |
| primary | source로 유일하게 지목 가능한 고정 oracle 선언의 coverage. 같은 선언 중복·모호한 overload에 추가 가점 없음 |
| 추가 관찰 | 전체 선언 coverage, 모호성·중복·oracle 밖 예측·시간·비용·실제 도구 사용. Oracle 밖은 미판정 |
| 중단 | provider/tool surface 또는 입력 무결성 실패 시 원본과 실행 상태를 보존하고 선택적 재시작하지 않음 |

## 정답과 한계

[javap + 원본 Kotlin PSI](collect.py)는 제품 그래프를 읽지 않는다. PSI는 source signature를 대응하는 데만 사용하고
직접/간접 참조는 javap에서 얻는다. [조립기](assemble.py)는 변경된 기존 함수의 직접/2단계 호출자와 실제 실행한
같은 rule의 기존 Test/Spec suite를 검토 anchor로 고정한다. 새 PR 테스트와 skipped 테스트는 넣지 않는다.
CodeFormatter는 대응하는 이름의 suite가 없어 javap 참조만 oracle로 사용한다.

[고정 요약](oracle-summary.json)의 primary 크기는 **7, 54, 22, 4**다. CodeFormatter의 source 대응 없는 선언 1개는
추가 지표에만 포함한다. 이 known-anchor coverage는 완전한 동작 영향 recall이나 수리 성공률이 아니다.
특히 같은 rule의 회귀 suite 포함은 해당 테스트 모두가 이 patch로 실패한다는 뜻이 아니다.
비교되는 두 조건에는 같은 oracle과 같은 source 범위를 적용한다.

**사례별 결과를 기본으로 보고한다.** detekt 두 사례는 test-suite 발견, ktlint2774는 test-suite 발견+직접 호출,
ktlint2727은 호출 관계 조사로 구분한다. 세 사례가 이름으로 찾을 수 있는 회귀 suite 중심이므로 전체 평균을 하나의
영향 탐지·추론 성능 수치로 보고하지 않는다. test-suite anchor와 call-graph anchor의 범위를 분리하고,
응답 무효 사유·format 오류율도 조건별로 함께 제시한다.

source aliases는 전체 제공 선언 목록에서 유일해야 한다. JVM USR·정확한 source signature는 동일 identity에 연결되며,
source 구두점 주변의 공백만 정규화한다. backtick 내부 문자열은 보존한다. compiler lambda·단축 overload를 원본 함수로
오인하지 않으며, source 대응 여부와 제외 identity를 명시한다. 로컬 함수·로컬 클래스 안의 메서드는 현재 source alias 대응 범위 밖이며,
대응되지 않았다는 이유만으로 compiler-generated라고 단정하지 않는다. 이번 primary oracle에는 해당 형태가 없다. 파일 이름만으로 source 경로를 추측하지 않는다.

관측되지 않은 호출·불완전한 source 대응·오라클 밖 예측을 자동 오탐으로 분류하지 않는다. `oracleAgreement`는
불완전한 oracle과의 겹침 비율이며 false-positive precision이 아니다. 무효 응답은 primary coverage 0으로 남긴다.

## 준비 환경과 smoke

컴파일 준비에는 빈 프로젝트 classpath 디렉터리를 처리하는 **미발행 수정본 plugin**을 사용했다. 내장 버전 문자열은
0.14.0이며 정확한 JAR 해시로 구분한다. CLI는 검증된 0.14.0 배포본이다. 원래 0.14.0의 최초 capture 실패도 보존했다.
detekt7212의 toolchain 설정 patch와 wrapper/JDK 선택은 INTAKE에 명시했다. 모든 모듈 production/test source는 고정 base와 같다.
기존 suite는 95개 통과·3개 skipped이며, 네 최종 capture 모두 matched였다.

실제 표본을 사용하지 않는 별도 Java toy로 두 조건의 연결을 점검했다. 첫 smoke는 JSON 앞 설명/코드 fence와 hybrid
selector 때문에 무효였으며 보존했다. **본 실행 전에** 최종 JSON 형식의 system 안내·selector 예제를 추가하고,
단독 JSON fence만 허용하도록 채점기를 명시했다. 수정 후 toy의 두 조건 모두 올바른 caller를 찾고 tool surface 검사를 통과했다.
이 smoke는 16회 결과·효용 점수에 넣지 않는다. 같은 mtime의 source byte 변경이 모델 실행 전에 차단되는 실패 경로도 검사했다.

v1–v4와 모집단·출력 상한·준비·채점이 달라 전후 차이를 이 수정의 인과 효과로 읽지 않는다. 준비 비용과 모델 사용 비용을
분리하며, 충분한 반복·실제 동작 판정 없이 일반 생산성 향상을 주장하지 않는다.
