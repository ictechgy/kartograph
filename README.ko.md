# kartograph

Kotlin/Android 코드베이스를 위한 질의 가능한 의존성 그래프. cartograph(Swift)의 자매 프로젝트로 설계와 교환 계약을 공유한다.

> [English](README.md)

이름은 **K**otlin + cartograph. cartograph가 iOS 지도를 그리듯 kartograph는 Android 지도를 그린다.

## 무엇을 하는가

Kotlin에는 Periphery에 해당하는 도구가 없다. 있는 것은 R8에 기대는 Gradle 플러그인 하나와 구문만 파싱하는 CLI 하나뿐이고, 둘 다 "왜 미사용인가 / 왜 살아남았는가"를 말하지 않는다. 이 빈자리를 cartograph가 이미 증명한 방식으로 채운다.

- 컴파일러가 기록한 사실을 원천으로 쓴다 — 텍스트 검색이 아니다.
- 미사용 코드·순환 의존·레이어 규칙·아키텍처 지표를 한 그래프 위에서 낸다.
- 모든 판정에 근거를 붙인다. 삭제 판정은 내지 않는다.
- 에이전트가 소비할 것을 전제로 `query`와 `skill`을 처음부터 갖춘다.

Android만의 이점이 하나 있다. "안 쓰는 것처럼 보이지만 지우면 안 되는 것"이 이미 십 년 넘게 **ProGuard/R8 keep 규칙**으로 코드화되어 있다. Swift 쪽에서 손으로 모아야 했던 보존 규칙 지식이 여기서는 생태계에 녹아 있다.

## 상태

현재 소스 버전은 [VERSION](VERSION) 파일에 적혀 있다. 배포된 버전과 산출물은 [GitHub Releases](https://github.com/ictechgy/kartograph/releases)에서 확인한다. 그래프 원천 결정 실험에서 JVM 바이트코드 + 공식 Kotlin metadata를 주 그래프로 정했고, 근거는 [`docs/DECISION-truth-source.md`](docs/DECISION-truth-source.md)에 있다.

현재 동작하는 것:

- `graph`는 컴파일된 class root를 DOT 또는 `code-graph` JSON 교환 문서로 렌더링하며, 요청하면 project 기준 source 경로를 해석한다(`--include-paths --project`). `--classes`를 반복해 여러 module/variant output root를 합칠 수 있고, 같은 JVM class는 첫 root의 사실을 결정적으로 사용한다.
- `dead`는 Android 보존 근거(manifest, XML, `@Keep`, keep 규칙, 상속 hierarchy, DI/직렬화 어노테이션, JNI·프레임워크 콜백)에서 도달 불가한 class 선언을 보고하며, `--explain`·baseline·`--since`·machine report를 지원한다. 재귀 include와 consumer rules 입력도 지원한다.
- `query`/`bridges`/`skill`은 전체 graph 덤프 대신 한 symbol의 사용·의존·도달성과 Flutter/React Native 브리지 사실을 에이전트에게 제공한다.
- `cycles`/`rules`/`metrics`는 module/package 순환과 weakest edge, fail-closed layer YAML, Martin Ca/Ce/I/A/D 지표를 분석한다.
- Gradle plugin은 AGP public Variant API 위에서 Android variant마다 `kartographDead<Variant>`와 `kartographGraph<Variant>` task를 등록한다.
- keep 규칙 파싱은 지원하지 않는 문법을 조용히 버리지 않고 파일·줄과 함께 실패한다(fail-closed). 근거와 오류에는 절대경로를 출력하지 않는다.

그래프가 보지 못하는 것은 [`docs/LIMITATIONS.md`](docs/LIMITATIONS.md)에, 측정된 보존 동작은 [`docs/PHASE2-VALIDATION.md`](docs/PHASE2-VALIDATION.md)에 있다.

## 설치와 호환성

CLI archive는 GitHub Releases에서 받는다. Gradle plugin `io.github.ictechgy.kartograph`는 [Plugin Portal](https://plugins.gradle.org/plugin/io.github.ictechgy.kartograph)에 version이 표시된 뒤 설치할 수 있다. GitHub Release 공개와 Portal 승인·설치 가능 여부는 별개다.

- kartograph 소스 빌드는 JDK 17 또는 21, Gradle 9.6.1로 검증한다.
- Gradle plugin 적용: AGP 8.7 이상, Gradle 8.10 이상, JDK 17 이상(AGP 8.7 + Gradle 8.10 조합 검증, AGP 9.x는 Android fixture 게이트로 검증).

```kotlin
plugins {
    id("io.github.ictechgy.kartograph") version "0.5.0"
}
```

GitHub release의 `kartograph-<version>.zip` 또는 `.tar`를 내려받는다. 0.5.0 이상은 압축을 풀기 전에 파일의 SHA256을 `SHA256SUMS`의 해당 항목과 대조한 뒤 `bin/kartograph`를 실행한다. release에는 CycloneDX runtime SBOM도 포함한다. 별도 서명은 배포하지 않는다.

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
kartograph skill --project .
```

```bash
kartograph cycles --classes path/to/classes --strict
kartograph rules --classes path/to/classes --config .kartograph.yml --strict
kartograph metrics --classes path/to/classes
```

Gradle plugin은 report를 `build/reports/kartograph/<variant>.txt`에, 그래프 교환 문서를 `build/reports/kartograph/<variant>-graph.json`에 만든다.

```kotlin
plugins {
    id("io.github.ictechgy.kartograph")
}

kartograph {
    keepRules.from("proguard-rules.pro", "path/to/dependency/consumer-rules.pro")
    strict.set(true)
    baseline.set(layout.projectDirectory.file(".kartograph-baseline.json"))
    reportFormat.set("github-actions") // gradle, github-actions, sarif, json, text
    includeSourcePaths.set(true) // 그래프 문서에 project 기준 source 경로를 해석해 싣는다(기본 false)
}
```

```bash
./gradlew kartographDeadDebug
./gradlew kartographGraphDebug
```

AGP public Variant API가 dependency consumer rules를 merged file로 노출하지 않으므로 해당 파일은 직접 지정한다. keep-rule include가 task 실행 중 발견되는 현재 구조에서는 stale report를 피하기 위해 dead task output을 up-to-date/cache 결과로 재사용하지 않는다. 그래프 task는 경로 해석을 켰을 때만 선언되지 않은 project source를 읽으므로 그때만 재사용하지 않는다.

### Private members

private member 진단은 `dead --include-private-members`로 선택한다(0.2.0에서 추가, 0.1.x에는 없음). 기본 class 보고에 더해 reachable이면서 합성되지 않은 class의 private method와 field/property만 추가하며, baseline 생성과 `query`에도 같은 옵션을 사용한다. Gradle에서는 `kartograph { includePrivateMembers.set(true) }`로 켠다.

이 모드는 `-keepclassmembers` 대상 member와 owner를 보수적으로 보존하므로 class-only 결과보다 적은 class finding을 낼 수 있다. constructor, native, 합성 member, compile-time constant, file facade와 unreachable owner 하위 member는 보고하지 않고, field 쓰기도 사용으로 취급하므로 unread-field 검사가 아니다. 설명할 수 없는 `-keepclassmembers` signature는 matching class의 모든 직접 member 보존으로 넓히고, 일반 `-keep` 해석은 기존처럼 fail-closed로 실패한다. 외부 진입점으로 가정한 곳에서 도달 가능한 member의 private helper도 함께 보존하며(`EXTERNAL_MEMBER_ENTRY`로 설명, 미사용을 덜 보고할 수 있다). private reflection/serialization 관례까지 완전하게 증명하지 않으므로 keep/consumer rules와 runtime 테스트를 함께 검토한다.

## 개발과 검증

PR에서 **새로 생긴 진단 전체**를 막으려면 [PR gate 안내](docs/PR-CHECK.md)를 따른다. 배포본의 `Scripts/check-pr.py`는 기준 commit의 baseline을 읽고 수정하지 않은 파일까지 검사한다. `--since`는 변경 파일 필터이므로 호출자 삭제의 파급 효과를 모두 검사하는 PR gate와는 다르다. 실제 Hilt/Compose/KSP 공개 표본의 측정과 남은 한계는 [공개 검증 기록](docs/PUBLIC-VALIDATION.md)에 있다.

kartograph 자체 개발에는 JDK 17 이상이 필요하다.

```bash
./gradlew test
./gradlew :koverVerify
./gradlew :cli:installDist
Scripts/verify-cli-contract.sh
Scripts/verify-fixture-corpus.sh
Scripts/verify-gradle-plugin-fixture.sh
Scripts/verify-agent-surface.sh
python3 -m unittest discover -s Scripts/tests -v
Scripts/verify-release-readiness.sh # clean build 두 번, publish하지 않음
```

## 안전한 해석과 한계

kartograph의 finding과 `unreachable`은 주어진 입력 graph의 사실이다. **어떤 코드도 안전하게 삭제할 수 있다고 말하지 않는다.** reflection, JNI, 동적 등록, 누락된 variant/classpath와 stale build output은 결과를 바꿀 수 있다. 변경 전에 `--explain`, runtime 경로와 해당 variant 테스트를 검토한다. 전체 경계는 [`docs/LIMITATIONS.md`](docs/LIMITATIONS.md), 로컬 처리와 report 공개 시 주의점은 [`SECURITY.md`](SECURITY.md)에 있다.

| 문서 | 내용 |
|---|---|
| [`docs/PRD.md`](docs/PRD.md) | 무엇을 · 누구를 위해 · 어디까지 · 무엇을 하지 않을지 |
| [`docs/PLAN.md`](docs/PLAN.md) | 단계별 계획. Phase 0은 코드가 아니라 그래프 원천을 정한 실험이다 |
| [`docs/PHASE3-ADOPTION.md`](docs/PHASE3-ADOPTION.md) | baseline, `--since`, machine report와 Gradle 도입 계약 |
| [`docs/PHASE4-AGENT.md`](docs/PHASE4-AGENT.md) | query, 측정된 한계, bridge-facts와 agent skill 계약 |
| [`docs/PHASE5-VALIDATION.md`](docs/PHASE5-VALIDATION.md) | cycles, layer rules, Martin metrics, self-analysis와 성능 근거 |
| [`docs/LIMITATIONS.md`](docs/LIMITATIONS.md) | 분석 경계와 finding의 안전한 해석 |
| [`docs/RESEARCH.md`](docs/RESEARCH.md) | 확인된 사실 · 확인되지 않은 주장 · 출처 |

## 라이선스

kartograph는 MIT 라이선스다. 배포본에 내장된 의존성의 저작권과 라이선스는 [`THIRD_PARTY_NOTICES.md`](THIRD_PARTY_NOTICES.md)와 [`LICENSES/`](LICENSES/)에 함께 제공한다.
