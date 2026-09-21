# 경쟁 도구 비교와 후속

2026-09-21 기준 출시 kartograph **0.14.0**을 대상으로 한다. 별도 진행 중인 브랜치의 기능을 출시 기능에 포함하지 않는다.
제품의 목적은 사람·AI·CI가 같은 컴파일 그래프에서 변경 영향을 조사하는 것이다. 삭제 승인, runtime 수집, IDE plugin은
현재 PRD 범위에 넣지 않는다. 기능 개수와 발견 개수로 우열을 정하지 않는다.

## 공식 기능과 현재 차이

| 비교 대상 | 확인한 공식 기능 | kartograph 0.14.0의 상태·후속 |
|---|---|---|
| [SearchDeadCode 0.21.0](https://github.com/KevinDoremy/SearchDeadCode/blob/v0.21.0/README.md) | 소스 직접 분석으로 JDK/Gradle build 없이 시작, IDE 연결, LCOV 입력 | 컴파일 결과의 사실을 보존하면서 capture 준비·variant 선택·실패 복구를 쉽게 한다. 기존 지속 MCP 사용을 먼저 안내한다. LCOV·method/test 대응은 추가 입력 계약이 필요한 별도 기능이다. |
| [AutonomousApps 3.19.2](https://github.com/autonomousapps/dependency-analysis-gradle-plugin/blob/v3.19.2/README.asciidoc) | 미사용 annotation processor, 중복 classpath 클래스, 의존성 배치·모듈 조언 | Signature/Kotlin ABI·baseline/suppress는 이미 지원한다. processor 출력 receipt는 snapshot 근거이며 dependency advice에 연결되지 않는다. 귀속·리소스·runtime 근거 확장은 부재를 안전한 제거로 해석하지 않는 별도 검증이 필요하다. |
| SootUp/WALA | 알고리즘·설정에 따라 다른 호출 후보 정밀도 | [고정 fixture 비교](../experiments/callgraph-precision/README.md)에서는 일부 후보 축소와 reflection 경로 누락이 함께 관측됐다. 정밀 분석을 추가할 경우 알려진 receiver·호출 지점부터 제한하고 미해결 경로를 보존한다. |

위 릴리스 상태는 GitHub latest API로 확인했다. 두 README는 해당 tag에서 읽었다. SootUp/WALA 행은 기존 고정 버전 실험이며
최신 버전 성능 순위가 아니다. 경쟁 도구의 삭제 안전성 문구를 kartograph의 제품 계약으로 받아들이지 않는다.

## 이번에 확인·보완한 것

- [현재 버전 반복 질의 측정](../experiments/tool-comparison/README.md): 같은 NIA 표본의 저장 CLI 질의는 대상별 중앙값 377.3–387.5 ms,
  같은 문서의 지속 MCP 질의는 대상별 중앙값 5.2–11.3 ms였고 서버 시작은 363 ms였다. 새 엔진을 만들기 전에 기존 지속 세션으로
  사용 흐름을 개선할 근거다. SDC CLI는 약 7 ms지만 다른 보고서이므로 동등 작업 순위로 비교하지 않는다.
- [v4 후속 감사](../experiments/ai-utility-protocol/review-v4/README.md): 16개 응답·147개 예측에서 overload 다중 가점 1건과
  잘못된 source 경로 1건을 확인했다. 원래 점수·실패를 보존하고 외부 리뷰의 잘못된 집계도 정정했다.
- [MCP 안내](MCP.md): module/variant 선택→capture→verify→연결, freshness 사유별 복구, 반복 조사, source 경로 확인을 연결했다.
  이 안내 변경이 AI 성공률을 높였다는 결과는 아직 없다.
- [LIMITATIONS](LIMITATIONS.md)의 Signature·baseline/suppress·중복 소유권 설명과 [PRD](PRD.md)의 MCP 상태를 현재 계약에 맞췄다.

## 다음 작업의 완료 기준

1. **새 효용 평가 준비·실행:** [준비 조건](../experiments/ai-utility-protocol/NEXT-PROTOCOL.md)을 충족한 미사용 코호트를 고정하고
   source-only/MCP의 누락·과잉·근거·시간·비용을 전체 반복으로 비교한다. 현재의 음성·혼합 결과를 성공으로 다시 이름 붙이지 않는다.
2. **첫 사용과 갱신:** 깨끗한 별도 소비 프로젝트에서 설치부터 첫 유효 질의까지, 한 파일 변경부터 새 matched snapshot까지의
   시간·명령 수·실패 복구를 측정한다. 기존 warm 질의 측정은 이 검사를 대신하지 않는다.
3. **추가 분석 근거:** processor/resource 귀속, 선택적 호출 정밀도, method/test runtime 대응은 각각 독립된 입력·실패 코퍼스와
   현재 근거를 보존하는 계약이 생긴 뒤 확장한다. 이미 있는 캐시·baseline·필터·페이지 기능을 새 기능으로 다시 제안하지 않는다.

릴리스·설치 검증 완료는 위 제품 개선까지 완료됐다는 뜻이 아니다. 이번 변경은 문서·비교·후속 감사와 사용 안내이며
새 detector, 새 채점 결과, 새 버전 배포를 포함하지 않는다.
