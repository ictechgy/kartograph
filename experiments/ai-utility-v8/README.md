# v8 — 실제 공개 모듈의 호출·동작 영향 조사

상태: **모델 본 실행 전 사전 검토**. [INTAKE](INTAKE.md)·[프로토콜](PROTOCOL.md)·[네 제안 변경](changes.json)을
먼저 고정하고 기존 프로젝트의 전체 모듈 테스트로 독립 정답을 준비했다. v5/v6/v7 입력·원문·점수는 그대로다.

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

실제 모델은 기존과 같은 claude-opus-5[1m]/low·12정보호출·900초·3USD 제한으로 source/MCP 각2회·전체16회를 실행한다.
파일 내 유일한 source 이름을 허용하고 정확한 overload만 서명으로 구분하게 한다. 원문을 사후 수리하지 않으며,
유효성·호출 recall·관찰된 assertion 변경 recall·음성 대조 오선택·미대응·시간·CLI 추정 비용을 각각 보고한다.
실제 개발 생산성이나 모든 runtime 경로의 완전성을 주장하지 않는다.

원본 출처: [detekt 고정 revision](https://github.com/detekt/detekt/tree/046263730eb5368cb344489ac36543294e8e87bd),
[ktlint 고정 revision](https://github.com/ktlint/ktlint/tree/b44a53f414d81cdf5816a26c182ba2738e3c9670).
관련 원본 라이선스는 [detekt](upstream-licenses/detekt.txt)·[ktlint](upstream-licenses/ktlint.txt)에 보존했다.
