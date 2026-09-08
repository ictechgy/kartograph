# Callgraph precision comparison

동일한 컴파일 입력과 `Entry.main(String[])` root에서 **kartograph / SootUp CHA / SootUp RTA / WALA 0-1-CFA**를
비교한다. SootUp **3.0.1**, WALA **1.8.0**, Kotlin **2.4.10**, JDK **17**을 사용한다. WALA reflection은 기본값과
같은 `FULL`로 명시한다. 사전 runtime metadata나 실제 argument 값을 정적 엔진에 전달하지 않는다.

```sh
python3 experiments/callgraph-precision/run.py
```

별도 Gradle 실험의 고정 의존성만 Maven Central에서 받는다. 88개 artifact SHA256을 공식 원본과 대조했으며,
metadata 생성과 검증을 서로 다른 빈 Gradle user home에서 확인했다. 각 엔진은 별도의 classpath/JVM으로 실행하고
제품 runtime 의존성은 바꾸지 않는다. `-Xmx2g`와 180초 한도를 적용하며 실패·timeout을 빈 대상 집합으로 바꾸지 않는다.

## 실제 결과

2026-09-08 macOS / JDK 17, Java 3개·Kotlin 1개 표본을 각 방식으로 **3회** 실행해 같은 대상 집합을 확인했다.
`L`은 `Live.run()`, `D`는 `Dormant.run()`이다.

| 표본 | 두 실행 입력에서 관측된 대상 | kartograph | SootUp CHA | SootUp RTA | WALA 0-1-CFA FULL |
| --- | --- | --- | --- | --- | --- |
| Java: Live만 생성 | L | L, D | L, D | L | L |
| Java: Dormant도 생성하나 Live만 호출 | L | L, D | L, D | L, D | L |
| Kotlin: 위와 같은 입력 | L | L, D | L, D | L, D | L |
| Java: argument의 class 이름으로 reflection 생성 | L, D | L, D | L, D | 없음 | 없음 |

두 실행 입력은 `Live`, `Dormant` class 이름이다. 앞의 세 표본은 입력을 사용하지 않고 항상 Live만 호출한다.
reflection 표본은 두 입력에서 각각 다른 구현이 실제 호출된다. 따라서 그 사례의 D를 미사용으로 분류하지 않는다.
관측하지 못한 후보는 `unobservedCandidates`, 관측했는데 엔진이 놓친 대상은 `missedObservedTargets`로 구분한다.
전체 입력 공간이나 Android runtime에 대한 정확도 수치는 아니다.

kartograph는 실제 `graph` 결과의 공통 usage 간선을 main root부터 질의한다. 각 target의 실제 CLI query 결과와도
일치하는지 별도로 검증한다. 이 작은 표본의 keep 규칙은 Entry helper만 추가 보존하며, 그 helper는 main에서도
도달하므로 비교 root를 바꾸지 않는다. Android/DI/테스트 root 정책 전체를 이 실험에서 재구현하지 않는다.

## 비용과 재현 경계

로컬 3회 JVM 프로세스 wall time 중앙값의 표본별 범위는 kartograph **0.10–0.20초**, SootUp CHA **1.84–2.89초**,
SootUp RTA **1.83–3.48초**, WALA **0.70–2.12초**였다. 프로세스 시작·출력을 포함하고 측정 중 부하도 통제하지
않았으므로 알고리즘 자체의 성능 순위나 대형 앱 처리량으로 일반화하지 않는다. 비용은 pass/fail 임계값으로 쓰지 않는다.
SootUp은 기본 Library scope에서 library body 확장을 멈추지만 WALA는 자체 library 모델과 분석을 사용하므로
엔진 내부 작업량·정점 수는 직접 비교하지 않는다. Java/Kotlin target 두 개의 JVM 정체성만 공통 기준으로 대조한다.

`build/reports/callgraph-precision/results.json`은 입력 class/source, JDK/표준 라이브러리, 엔진 JAR/의존성/스크립트의
지문과 관측·후보를 기록한다. `timings.json`은 별도 비결정적 비용 기록이다. 선언 존재와 반복 대상 집합을 검사하고
오류 시 부분 비교를 성공으로 보고하지 않는다. JVM heap 한도는 실제 사용 메모리 측정값이 아니다.

## 채택 판단

**기본 그래프를 RTA나 CFA 결과로 대체하지 않는다.** 명시적 객체 흐름에서는 후보를 좁힐 수 있지만 이 설정의
reflection 표본은 실제 실행을 놓쳤다. 향후 선택적 정밀 질의는 모델·입력의 완전성을 확인하고, 미해결 경로에서는
기존 보수적 후보를 유지하는 방식으로 검증해야 한다. 이 비교가 WALA의 모든 reflection 설정이나 SootUp의 모든
분석 능력을 평가한 것은 아니다. 실제 Android callback·KSP/Hilt·대형 다중 모듈 성능은 이번 표본 밖이다.

참고: [SootUp callgraphs](https://soot-oss.github.io/SootUp/latest/callgraphs/),
[WALA](https://github.com/wala/WALA). 두 엔진의 라이선스와 배포 조건은 각 upstream을 따른다.

## 검증 계약과 엔진별 입력 역할

`expectations.json`은 최초 실제 측정을 고정한 회귀 계약이다. 실행 관측과 별개로 검사하며, 새 버전이나 의도적인
모델 변경에서 차이가 나면 빨간 CI를 통해 재검토한다. 기대값 자체를 실행 정답으로 간주하거나 자동 갱신하지 않는다.

모든 엔진은 **같은 app.jar**를 받는다. JAR의 전체 application class 목록이 각 엔진의 Application 영역과 정확히
일치하는지 검사한다. Kotlin stdlib도 같은 JAR을 주되, SootUp의 Library와 WALA의 Primordial에 배치한다.
이는 해당 엔진의 라이브러리 역할 설정이며 app class를 이동시키지 않는다. kartograph에는 동일 stdlib를 header
classpath로 전달한다. JDK는 같은 프로세스 JDK지만, 내부 로딩·library body 확장·모델은 동일하지 않으므로 이 비교는
순수 알고리즘만의 효과를 분리한 실험이 아니다.

reflection 실행 인자는 정적 엔진에 전달하지 않았다. 이 **입력과 구현·설정**에서 관측 대상이 누락됐다는 결론이며,
인자·추가 metadata를 주거나 다른 모델을 사용해도 해결할 수 없다는 뜻은 아니다.

첫 실행은 엔진과 루트 CLI의 의존성을 온라인으로 준비한다. 뒤의 Kotlin fixture만 준비된 동일 버전 cache를 쓰며
offline으로 컴파일한다. 빈 cache 검증 기록은 실험 의존성의 무결성·빌드 검증이고, 모든 환경에서 준비 없이
전체 harness를 offline으로 실행할 수 있다는 주장이 아니다. OS/architecture와 실제 JDK 지문을 결과에 기록한다.
CI는 두 결과 JSON만 `callgraph-precision` artifact로 14일 보관해 비교 수치와 입력 지문을 확인할 수 있게 한다.
