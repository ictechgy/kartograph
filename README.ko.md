# kartograph

Kotlin/Android 코드베이스의 의존성 그래프를 만들고 질의하는 도구다. cartograph(Swift)의 자매 프로젝트로, 설계와 교환 계약을 공유한다.

> [English](README.md)

이름은 **K**otlin + cartograph다. cartograph가 iOS의 지도를 그리듯, kartograph는 Android의 지도를 그린다.

## 무엇을 하는가

kartograph는 컴파일된 Kotlin/Android 코드에서 의존성 그래프를 만들고, 어떤 선언이 왜 도달 가능한지, 왜 보존되는지, 왜 도달 불가인지 설명한다.

- 근거는 텍스트 검색이 아니라 컴파일러가 남긴 사실이다.
- 미사용 코드, 순환 의존, 레이어 규칙, 아키텍처 지표를 모두 하나의 그래프에서 계산한다.
- 모든 판정에는 근거가 붙는다. "삭제해도 된다"는 판정은 내리지 않는다.
- `query`와 `skill`은 처음부터 에이전트가 쓰는 것을 전제로 만들었다.

Android에는 한 가지 유리한 점이 있다. "안 쓰는 것처럼 보이지만 지우면 안 되는 코드"가 이미 십 년 넘게 **ProGuard/R8 keep 규칙**으로 명문화되어 있다는 점이다. Swift 쪽에서는 손으로 모아야 했던 보존 지식이 여기서는 생태계 안에 들어 있다.

## 상태

현재 소스 버전은 [VERSION](VERSION) 파일에 있다. 배포된 버전과 산출물은 [GitHub Releases](https://github.com/ictechgy/kartograph/releases)에서 확인한다. 그래프 원천을 정하는 실험에서 JVM 바이트코드와 공식 Kotlin metadata를 주 그래프로 택했고, 그 근거는 [`docs/DECISION-truth-source.md`](docs/DECISION-truth-source.md)에 있다.

지금 동작하는 것:

- `impact`는 저장된 그래프로 변경 예정 심볼이나 commit 사이에 바뀐 파일의 잠재적 영향을 조사한다. base/current 경로, 삭제, runtime 근거, 불확실성을 사람·에이전트·CI에 같은 의미로 전달한다. [사용법](docs/IMPACT.md)과 [실제 변경 채점](https://github.com/ictechgy/kartograph/blob/b7bcc1570d1adc851abf77be9f728f186ada1b9b/experiments/change-impact/README.md)을 참고한다.
- `graph`는 컴파일된 class root를 DOT 또는 `code-graph` JSON 교환 문서로 렌더링한다. `--include-paths --project`를 주면 project 기준 source 경로도 해석한다. JSON에는 간선의 출처와 외부 호출의 해석 상태가 함께 기록된다. `--classes`를 반복해 여러 module/variant의 output root를 합칠 수 있으며, 같은 JVM class가 겹치면 항상 첫 root의 사실을 쓴다.
- `dead`는 Android 보존 근거(manifest, XML, `@Keep`, keep 규칙, 상속 hierarchy, DI/직렬화 어노테이션, JNI·프레임워크 콜백)에서 도달 불가한 class 선언을 보고한다. `--explain`, baseline, 만료일이 있는 `--suppress`, `--since`, machine report(text/gradle/github-actions/sarif/json/markdown)를 지원한다. JSON·SARIF·markdown 보고에는 `confidence` 등급(static / needs-runtime-review / unmeasured)이 실리는데, 이는 그 선언의 소스 파일에서 측정한 미해결 runtime 채널 수에서 나온다. 재귀 include와 consumer rules 입력도 지원한다.
- `why <symbol>`은 한 선언이 보존·도달·도달 불가인 이유를 한 번에 답한다. 보존 근거(파일·줄), 보존 root부터의 대표 경로, 직접 caller, test 전용 도달 표시, 도달 불가 선언의 측정된 신뢰도 등급을 출력한다. 답은 도달성 사실이지 삭제 승인이 아니다.
- `query`/`bridges`/`skill`은 전체 그래프를 덤프하는 대신 한 symbol의 사용·의존·도달성과 Flutter/React Native 브리지 사실을 에이전트에게 준다. `query`는 미해결 runtime 경로와 보수적 dispatch 후보의 수도 함께 알린다.
- `cycles`/`rules`/`metrics`는 module/package 순환과 weakest edge, fail-closed layer YAML, Martin Ca/Ce/I/A/D 지표를 분석한다.
- Gradle plugin은 AGP public Variant API 위에서 Android variant마다 `kartographDead<Variant>`와 `kartographGraph<Variant>` task를 등록한다.
- Gradle plugin의 `kartographSnapshot`과 `kartographSnapshot<Variant>` task는 JVM main/test와 Android main/unit-test 입력을 compiler witness와 함께 자동 캡처한다. Android application variant에서는 생성된 `R.jar`도 `processResources` producer witness로 덮는다. 반복 영향 질의는 [자동 캡처와 toolchain 설정](docs/IMPACT.md#jvm-빌드에서-자동-캡처)과 [build provenance 계약](docs/BUILD-PROVENANCE.md)을 참고한다.
- 선택적 [증분 파싱](docs/INDEX-CACHE.md)은 바뀌지 않은 class 사실과 dependency JAR header를 재사용한다. 현재 입력 검사와 전체 분석은 매 캡처마다 다시 한다.
- [MCP stdio 서버](docs/MCP.md)는 고정된 로컬 snapshot 위에서 `query_symbol`·`impact`·`freshness`를 제공하며, CLI와 같은 보고서를 쓴다.
- keep 규칙 파싱은 지원하지 않는 문법을 조용히 버리지 않고 파일·줄을 밝히며 실패한다(fail-closed). 근거와 오류 메시지에는 절대경로를 출력하지 않는다.

class 로딩, reflection 생성자, 알려진 method/field 접근은 메서드 안에서만 값을 추적해 연결하고, 외부 dispatch는 보수적인 상속 후보로 연결한다. class root와 CLI `--service-resources`에 있는 `META-INF/services` 등록은 provider를 보존한다. Gradle plugin은 선택한 variant의 Java resource 소스 디렉터리를 넘긴다. 외부 호출 JSON에서 API 모델 ID와 해석 결과는 별개 필드다. 선택적 [compiler collector](docs/COMPILER-EVIDENCE.md)는 javac/Kotlin 2.4.10 상수 참조와 javac Dagger 2.59 선택 binding을 snapshot에 더한다. collector는 따로 빌드해서 명시적으로 연결해야 하며, 지원하는 패턴과 남은 한계는 문서에 있다. 주 그래프와 보존 정책은 그대로 적용된다. callgraph 정밀도 보강은 아직 실험이다.

그래프가 보지 못하는 것은 [`docs/LIMITATIONS.md`](docs/LIMITATIONS.md)에, 측정된 보존 동작은 [`docs/PHASE2-VALIDATION.md`](docs/PHASE2-VALIDATION.md)에 있다.

분석기는 프로젝트의 static helper를 거치는 불변 인자와 String/Class 반환값도 제한적으로 추적한다. [실행 표본 5개 비교](https://github.com/ictechgy/kartograph/blob/b7bcc1570d1adc851abf77be9f728f186ada1b9b/experiments/runtime-returns/README.md)에서는 전에 놓치던 reflection 경로 3개를 되찾으면서 미사용 대조군은 전부 구분했다. 같은 보고서에 SearchDeadCode와 현재 R8의 실제 결과, 최적화 대조군, 여전히 놓치는 runtime 입력을 기록했다. 도구 전체의 정확도나 속도가 우위라는 뜻은 아니다. static field 값과 reflection 읽기에도 제한된 추적을 더했고, unknown 대입과 분석 한도는 그대로 남긴다. 정확히 하나로 정해지는 private/final instance helper와 Kotlin object/companion 메서드에도 같은 String/Class 반환값 추적을 확장해 Java/Kotlin 실행 표본 4건의 runtime 대상을 더 되찾았다. override 가능한 메서드와 알 수 없는 receiver 상태는 미해결로 남는다. [확대 평가](https://github.com/ictechgy/kartograph/blob/b7bcc1570d1adc851abf77be9f728f186ada1b9b/experiments/impact-evaluation/README.md)는 Java/Kotlin 수정 전 검토에서 확인한 실익과 함께 AI 수정 결과도 기록한다. 두 조건 모두 6/12 통과였고 실제 graph query는 0회였으므로, AI 생산성이 전반적으로 오른다는 주장은 입증되지 않았다.

## 설치와 호환성

CLI archive는 GitHub Releases에서 받는다. Gradle plugin `io.github.ictechgy.kartograph`는 [Plugin Portal](https://plugins.gradle.org/plugin/io.github.ictechgy.kartograph)에 해당 version이 올라온 뒤에 설치할 수 있다. GitHub Release 공개와 Portal 승인은 별개의 사건이다.

- kartograph 소스 빌드는 JDK 17 또는 21, Gradle 9.6.1로 검증했다.
- Android graph/dead task: AGP 8.7+, Gradle 8.10+, JDK 17+(AGP 8.7.3 / Gradle 8.10.2와 AGP 9.x에서 검증).
- 자동 snapshot: JVM Java/Kotlin은 Gradle 9.6.1과 JDK 17/21에서, Android는 [검증된 조합](docs/IMPACT.md#android-variant-자동-캡처)에서 확인했다. Kotlin compiler adapter는 KGP 2.4.10에서 검증했고 다른 버전은 보장하지 않는다. 최소 Android 조합은 Gradle 8.10.2에서 검증했지만, KGP 자체는 8.14.4 이상을 권고한다.

```kotlin
plugins {
    id("io.github.ictechgy.kartograph") version "0.10.0"
}
```

GitHub release에서 `kartograph-<version>.zip` 또는 `.tar`를 받는다. 0.5.0 이상은 압축을 풀기 전에 파일의 SHA256을 `SHA256SUMS`의 해당 항목과 대조하고, 그다음 `bin/kartograph`를 실행한다. release에는 CycloneDX runtime SBOM도 들어 있다. 별도 서명은 배포하지 않는다.

## 사용법

```bash
# DOT 형식 의존성 그래프.
cli/build/install/kartograph/bin/kartograph graph \
  --classes path/to/build/tmp/kotlin-classes/debug \
  --format dot

# 다른 도구가 소비할 교환 JSON. --include-paths는 class debug 정보의 source file 이름을
# --project 안에서 유일하게 일치하는 파일의 상대경로로 해석하고, 각 위치의 출처를 pathKind로 알린다.
# kartographGraph<Variant> Gradle task도 같은 문서를 만든다.
cli/build/install/kartograph/bin/kartograph graph \
  --classes path/to/build/tmp/kotlin-classes/debug \
  --format json \
  --include-paths \
  --project path/to/project

# 도달 불가 선언. 출력은 삭제 가능 판정이 아니라 도달성 사실이다.
cli/build/install/kartograph/bin/kartograph dead \
  --classes path/to/compiled/classes \
  --project path/to/project \
  --manifest app/src/main/AndroidManifest.xml \
  --resources app/src/main/res \
  --namespace dev.example.app \
  --keep-rules app/proguard-rules.pro \
  --classpath path/to/dependency/classes.jar \
  --test-classes path/to/test/classes \
  --strict

# 현재 finding을 고정한 뒤 새 finding만 strict 대상으로 삼는다.
# 상대 --write 경로는 호출한 shell이 아니라 --project를 기준으로 해석한다.
cli/build/install/kartograph/bin/kartograph baseline --write .kartograph-baseline.json \
  --classes path/to/compiled/classes --project path/to/project \
  --manifest app/src/main/AndroidManifest.xml --resources app/src/main/res \
  --namespace dev.example.app
cli/build/install/kartograph/bin/kartograph dead \
  --classes path/to/compiled/classes --project path/to/project \
  --manifest app/src/main/AndroidManifest.xml --resources app/src/main/res \
  --namespace dev.example.app --baseline .kartograph-baseline.json \
  --since origin/main --report-format sarif --strict
```

```bash
# 전체 graph 덤프 대신 한 symbol의 사용·의존·도달성을 query한다.
kartograph query UserService --classes path/to/classes --project . --depth 2 --limit 100
kartograph bridges --project . --format json
# Kotlin/JVM Flutter BasicMessageChannel 사실(v2)을 선택적으로 생성한다.
kartograph bridges --project . --target flutter --messages --graph-file build/reports/kartograph/main-graph.json
kartograph skill --project .
```

```bash
kartograph cycles --classes path/to/classes --strict
kartograph rules --classes path/to/classes --config .kartograph.yml --strict
kartograph metrics --classes path/to/classes
```

Gradle plugin은 report를 `build/reports/kartograph/<variant>.txt`에, 그래프 교환 문서를 `build/reports/kartograph/<variant>-graph.json`에 쓴다.

```kotlin
plugins {
    id("io.github.ictechgy.kartograph")
}

kartograph {
    keepRules.from("proguard-rules.pro", "path/to/dependency/consumer-rules.pro")
    strict.set(true)
    baseline.set(layout.projectDirectory.file(".kartograph-baseline.json"))
    reportFormat.set("github-actions") // gradle, github-actions, sarif, json, markdown, text
    includeSourcePaths.set(true) // 그래프 문서에 project 기준 source 경로를 해석해 싣는다(기본 false)
}
```

```bash
./gradlew kartographDeadDebug
./gradlew kartographGraphDebug
```

AGP public Variant API는 dependency consumer rules를 병합된 파일로 노출하지 않으므로, 그 파일들은 직접 지정한다. dead task는 up-to-date/cache 결과를 재사용하지 않는다. keep-rule include가 task 실행 중에야 발견되는 구조라 재사용하면 stale report가 될 수 있기 때문이다. graph task는 경로 해석을 켰을 때만 선언되지 않은 project source를 읽으므로, 그때만 재사용하지 않는다.

### 저장 그래프 질의와 생성 입력

아래 기능은 0.10.0 배포본에 들어 있다.
반복해서 조사할 때는 `snapshot`으로 그래프, 보존 근거, baseline 상태, 계량된 한계를 한 번만 저장한다.
live query와 같은 manifest/resource/namespace/keep/consumer/classpath 입력과 private member 옵션을 그대로 넘긴다.

```bash
kartograph snapshot --classes path/to/classes --project . \
  --keep-rules proguard-rules.pro > graph.snapshot.json
kartograph query UserService --graph-file graph.snapshot.json --depth 2 --limit 100
```

저장 질의는 현재 소스나 규칙을 다시 읽지 않으며 `saved-graph` 한계를 표시한다. 코드가 바뀌면 다시 캡처한다.
일반 `graph --format json` 출력에는 보존 문맥이 없으므로 snapshot 대신 쓸 수 없다.

생성 코드만 담긴 컴파일 출력은 `--classes`에 포함한 채로 `--generated-classes`로 표시한다.
이 표시는 `dead`, `baseline`, `graph`, `query`, `snapshot`이 공통으로 쓴다.

```bash
kartograph graph --classes path/to/normal/classes --classes path/to/generated/classes \
  --generated-classes path/to/generated/classes --format json
```

정점과 간선은 그대로 두고 `synthesized`와 `generatedInput`으로 출처만 구분한다. 생성 코드와 손으로 쓴 코드가 섞인 root는 표시하지 않는다.
Gradle에서는 `kartograph.generatedClassRoots` 또는 해당 variant task의 `generatedClassRoots`에 생성 전용 root를 넣는다. 그 task의 project class 입력에 없는 root는 오류다. class 이름으로 생성 여부를 추측하지 않는다.

extension 설정은 모든 variant에 적용된다. variant마다 출력 경로가 다르면 해당 이름의 task에 `generatedClassRoots`를 지정한다. extension에 debug 전용 root를 넣으면 release task의 입력과 맞지 않는다.

### Private members

private member 진단은 `dead --include-private-members`로 켠다(0.2.0에서 추가, 0.1.x에는 없음). 기본 class 보고에 더해, reachable이면서 합성되지 않은 class의 private method와 field/property를 추가로 보고한다. baseline 생성과 `query`에도 같은 옵션을 준다. Gradle에서는 `kartograph { includePrivateMembers.set(true) }`로 켠다.

이 모드는 `-keepclassmembers` 대상 member와 그 owner를 보수적으로 보존하므로, class-only 모드보다 class finding이 적을 수 있다. constructor, native, 합성 member, compile-time constant, file facade, unreachable owner 아래의 member는 보고하지 않는다. field 쓰기도 사용으로 치므로 unread-field 검사는 아니다. 해석할 수 없는 `-keepclassmembers` signature는 일치하는 class의 모든 직접 member를 보존하는 것으로 넓히고, 일반 `-keep` 해석은 전처럼 fail-closed로 실패한다. 외부 진입점으로 가정한 곳에서 도달 가능한 member는 그 private helper도 함께 보존한다(`EXTERNAL_MEMBER_ENTRY`로 설명하며, 이 때문에 미사용을 덜 보고할 수 있다). private reflection/serialization 관례까지 완전히 증명하지는 않으므로 keep/consumer rules와 runtime 테스트를 함께 검토한다.

## 개발과 검증

PR에서 **새로 생긴 진단 전체**를 막으려면 [PR gate 안내](docs/PR-CHECK.md)를 따른다. 배포본의 `Scripts/check-pr.py`는 기준 commit의 baseline을 읽고, 수정하지 않은 파일까지 검사한다. `--since`는 변경 파일 필터일 뿐이라, 호출자 삭제의 파급 효과까지 잡아야 하는 PR gate와는 다르다. 공개 Hilt/Compose/KSP 표본에서 측정한 결과와 남은 한계는 [공개 검증 기록](docs/PUBLIC-VALIDATION.md)에 있다.

아래 개발 검증에는 소스 checkout과 JDK 17 이상이 필요하다.

```bash
./gradlew test
./gradlew :koverVerify
./gradlew :cli:installDist
Scripts/verify-cli-contract.sh
Scripts/verify-fixture-corpus.sh
Scripts/verify-gradle-plugin-fixture.sh
Scripts/verify-agent-surface.sh
python3 -m unittest discover -s Scripts/tests -v
python3 Scripts/verify-runtime-corpus.py # Java/Kotlin 13건, JDK 17
python3 Scripts/verify-runtime-contracts.py # 차등 검사 6건, SDK Build Tools 35.0.0
python3 experiments/compiler-references/run.py # source checkout 전용, JDK 17
python3 experiments/dagger-bindings/run.py # source checkout 전용, JDK 17
python3 experiments/callgraph-precision/run.py # source checkout 전용, JDK 17
Scripts/verify-release-readiness.sh # clean build 두 번, publish하지 않음
```

## 안전한 해석과 한계

kartograph의 finding과 `unreachable`은 주어진 입력 그래프에 대한 사실이다. **어떤 코드도 안전하게 삭제할 수 있다고 말하지 않는다.** reflection, JNI, 동적 등록, 누락된 variant/classpath, stale build output은 결과를 바꿀 수 있다. 코드를 바꾸기 전에 `--explain`, runtime 경로, 해당 variant의 테스트를 검토한다. 전체 경계는 [`docs/LIMITATIONS.md`](docs/LIMITATIONS.md)에, 로컬 입력 처리와 report 공개 전 주의점은 [`SECURITY.md`](SECURITY.md)에 있다.

| 문서 | 내용 |
|---|---|
| [`docs/PRD.md`](docs/PRD.md) | 무엇을, 누구를 위해, 어디까지, 무엇을 하지 않을지 |
| [`docs/PLAN.md`](docs/PLAN.md) | 단계별 계획. Phase 0은 코드가 아니라 그래프 원천을 정한 실험이다 |
| [`docs/PHASE3-ADOPTION.md`](docs/PHASE3-ADOPTION.md) | baseline, `--since`, machine report, Gradle 도입 계약 |
| [`docs/PHASE4-AGENT.md`](docs/PHASE4-AGENT.md) | query, 측정된 한계, bridge-facts, agent skill 계약 |
| [`docs/PHASE5-VALIDATION.md`](docs/PHASE5-VALIDATION.md) | cycles, layer rules, Martin metrics, self-analysis, 성능 근거 |
| [`docs/LIMITATIONS.md`](docs/LIMITATIONS.md) | 분석 경계와 finding의 안전한 해석 |
| [`docs/RESEARCH.md`](docs/RESEARCH.md) | 확인된 사실, 확인되지 않은 주장, 출처 |

## 라이선스

kartograph는 MIT 라이선스다. 배포본에 내장된 의존성의 저작권과 라이선스 전문은 [`THIRD_PARTY_NOTICES.md`](THIRD_PARTY_NOTICES.md)와 [`LICENSES/`](LICENSES/)에 함께 들어 있다.
