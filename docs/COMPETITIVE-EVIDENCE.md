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
- [v5 후속 평가](../experiments/ai-utility-v5/README.md): 새 공개 모듈 4개의 matched 입력으로 16회를 모두 실행했다.
  호출 관계 사례는 양쪽 모두 75%였고 효용 우위는 확인하지 못했다. JSON 형식 실패 5건을 포함해 원문·점수를 보존했다.
  준비 중 발견한 빈 의존 프로젝트 class 출력의 snapshot 거부는 개발 버전에서 수정했으며, 배포 0.14.0에는 포함되지 않는다.
- [MCP 안내](MCP.md): module/variant 선택→capture→verify→연결, freshness 사유별 복구, 반복 조사, source 경로 확인을 연결했다.
  이 안내 변경이 AI 성공률을 높였다는 결과는 아직 없다.
- [LIMITATIONS](LIMITATIONS.md)의 Signature·baseline/suppress·중복 소유권 설명과 [PRD](PRD.md)의 MCP 상태를 현재 계약에 맞췄다.

## 다음 작업의 완료 기준

2026-09-22 [v6 후속 평가](../experiments/ai-utility-v6/README.md)는 발행 0.15.0과 새 표본 4개로 16회를 완료했다.
구조화 응답 16/16 유효·인프라 오류 0이었고 원문 재채점과 저장 점수가 같았다. 일부 MCP 조건의 높은 식별자 매칭
coverage가 관측됐지만, caller 차이 하나는 source의 동치 함수 타입 표기를 매칭하지 못한 결과였다.
두 조건 모두 해당 caller를 지목했으므로 발견 우위로 확대하지 않는다. 기존 v5와 v6의 원문·고정 점수는 유지했다.

1. **source 표기와 정답 계약:** [v7 통제 평가](../experiments/ai-utility-v7/README.md)에서 선택적 함수 타입 인자 이름의
   대응을 보완하고 호출·실제 assertion 변경을 분리했다. 새 Kotlin 통제4개·16회를 완료했으며 양쪽 모두 동작 예측100%·
   음성 대조 오선택0이었다. callback의 source 호출 coverage80%는 같은 caller의 `Function2` 표기 미대응이므로 발견 우위로
   해석하지 않는다. 기존 원문·점수는 유지했다. 작은 합성 과제의 천장 효과가 있어 공개 프로젝트의 실제 효용은 별도 문제다.
   다음 대규모 비교도 [준비 조건](../experiments/ai-utility-protocol/NEXT-PROTOCOL.md)을 지키며 기존 답변을 사후 수리하지 않는다.
   [v8 실제 모듈 평가](../experiments/ai-utility-v8/README.md)도 detekt180파일·ktlint207파일의 전체 해당 모듈에서
   16회를 완료했다. 사례별 결과가 달랐고 MCP의 실제 impact3회는 모두 notFound여서 graph 경로 활용 효과는 확인하지 못했다.
   사후 정확한 USR 대조는 동작했으며 기존 점수는 보존했다. 다음은 큰 표본 추가에 앞서 selector 복구·scope/페이지 안내를
   실제 agent 흐름에서 검증하는 과제다. 다른 모듈 구현까지 포함한 전체 저장소 분석이나 자연 발생 PR 표본이라고 주장하지 않는다.
2. **첫 사용과 갱신:** 깨끗한 별도 소비 프로젝트에서 설치부터 첫 유효 질의까지, 한 파일 변경부터 새 matched snapshot까지의
   시간·명령 수·실패 복구를 측정한다. 기존 warm 질의 측정은 이 검사를 대신하지 않는다.
3. **추가 분석 근거:** processor/resource 귀속, 선택적 호출 정밀도, method/test runtime 대응은 각각 독립된 입력·실패 코퍼스와
   현재 근거를 보존하는 계약이 생긴 뒤 확장한다. 이미 있는 캐시·baseline·필터·페이지 기능을 새 기능으로 다시 제안하지 않는다.

릴리스·설치 검증 완료는 위 제품 개선까지 완료됐다는 뜻이 아니다. 이번 통합은 문서·비교·후속 감사·v5 결과와
JVM snapshot 입력 수정을 포함하며, 새 detector나 새 버전 배포를 포함하지 않는다.
