# kartograph

Kotlin/Android 코드베이스를 위한 질의 가능한 의존성 그래프. [cartograph](../cartograph)(Swift)의 자매 프로젝트다.

이름은 **K**otlin + cartograph. cartograph 가 iOS 지도를 그리듯 kartograph 는 Android 지도를 그린다.

## 무엇을 하려는가

Kotlin 에는 Periphery 에 해당하는 도구가 없다. 있는 것은 R8 에 기대는 Gradle 플러그인 하나와 구문만 파싱하는 CLI 하나뿐이고, 둘 다 "왜 미사용인가 / 왜 살아남았는가"를 말하지 않는다. 이 빈자리를 cartograph 가 이미 증명한 방식으로 채운다.

- 컴파일러가 기록한 사실을 원천으로 쓴다 — 텍스트 검색이 아니다
- 미사용 코드 · 순환 의존 · 레이어 규칙 · 아키텍처 지표를 한 그래프 위에서 낸다
- 모든 판정에 근거를 붙인다. 삭제 판정은 내지 않는다
- 에이전트가 소비할 것을 전제로 `query` 와 `skill` 을 처음부터 갖춘다

Android 만의 이점이 하나 있다. "안 쓰는 것처럼 보이지만 지우면 안 되는 것"이 이미 십 년 넘게 **ProGuard/R8 keep 규칙**으로 코드화되어 있다. Swift 쪽에서 손으로 모아야 했던 보존 규칙 지식이 여기서는 생태계에 있다.

## 상태

**0.1.0 release candidate. 아직 tag를 만들거나 외부에 publish하지 않았다.** 원천 실험에서 JVM 바이트코드 + 공식 Kotlin metadata를 주 그래프로 결정했고,
컴파일된 class root의 DOT 출력과 manifest/XML/`@Keep` member·class annotation/wildcard·상속 keep 규칙 기반
`dead --explain`이 동작한다. 재귀 include와 consumer rules 입력도 지원한다. `final` 등 추가
positive/negative JVM access flag도 지원한다. 일반 member signature 조건과 DI·직렬화·runtime callback
보존 정책도 연결됐다. baseline·변경 범위·CI report와 query·bridge·agent skill까지 구현됐지만 architecture
분석과 release packaging은 로컬 검증을 마쳤지만 tag/publish 전이므로 공개 릴리스로 취급하면 안 된다. 결정 근거는
[`docs/DECISION-truth-source.md`](docs/DECISION-truth-source.md)에 있다.
`graph`와 `dead`는 `--classes`를 반복해 여러 module/variant output root를 합칠 수 있고, 같은 JVM class는
첫 root의 사실을 결정적으로 사용한다.

상속 keep 규칙은 반복 가능한 `--classpath`로 받은 dependency class directory/JAR의 class header를
따라간다. 필요한 중간 타입이 입력에 없으면 잘못된 미사용 보고 대신 해결 방법이 포함된 도구 실패를 반환한다.
include는 project 내부 규칙이면 project realpath 안에서만, 명시적으로 전달한 외부 규칙이면 해당 파일의
디렉터리 안에서만 허용한다. 근거와 오류에는 절대경로를 출력하지 않는다.
AGP가 생성한 `proguard-android-optimize.txt`와 annotation 기반 `-keepclasseswithmembers` 조건은
실제 Android fixture에서 검증한다.
plain `-keep class X { *; }`는 class와 모든 직접 member를 root로 만들며, member instruction이 참조하는
owner class까지 도달성에 포함한다.
`@Inject`/Dagger/Hilt annotation은 DI 근거로, kotlinx.serialization/Gson/Moshi/Parcelize/Room annotation은
직렬화·생성 코드 근거로 annotated declaration과 소유 class를 보존한다. Moshi `*JsonAdapter`와 Room
`*_Impl` sibling은 annotated source와 정확한 생성 이름이 함께 있을 때 보존하며, Room KSP 실제 생성물로
검증한다. JNI, `@JavascriptInterface`, WorkManager, Retrofit, ViewModel, WebViewClient, Camera2 callback과
compile-time constant의 bytecode 손실도 보수적으로 처리한다. CLI와 Gradle report는 문자열 reflection과
동적 component 등록 한계를 함께 출력한다. compile-time constant 사용처는 bytecode에 남지 않으므로
constant owner는 보수적으로 보존하며 이 과보존 가능성도 limitation으로 출력한다.

지원하는 member specification은 annotation wildcard, method/field/constructor의 JVM visibility·이름·정확한
descriptor, `native <methods>`, plain `-keep` member다. 해석하지 못하는 보존 문법은 조용히 버리지 않고
파일·줄과 함께 실패한다. Phase 2 실측은 [`docs/PHASE2-VALIDATION.md`](docs/PHASE2-VALIDATION.md)에 있다.

## 설치와 호환성

CLI release archive는 GitHub Releases에, Gradle plugin은 Plugin Portal의
`io.github.ictechgy.kartograph`에 게시하도록 구성돼 있다. 아직 0.1.0을 publish하지 않았으므로 아래 versioned
설치는 `v0.1.0` tag release 뒤부터 유효하다. 소스 빌드와 Gradle plugin 실행에는 JDK 17 또는 21, Gradle
9.6.1을 검증 대상으로 삼는다. Android 연결은 AGP 9.3.2 public Variant API 기준이다.

```kotlin
plugins {
    id("io.github.ictechgy.kartograph") version "0.1.0"
}
```

GitHub release의 `kartograph-0.1.0.zip` 또는 `.tar`를 내려받아 압축을 풀고 `bin/kartograph`를 실행한다.
checksum과 signature 배포는 0.1.0 범위에 포함되지 않는다.

## 개발과 검증

개발에는 JDK 17 이상이 필요하다.

```bash
./gradlew test
./gradlew :koverVerify
./gradlew :cli:installDist
Scripts/verify-cli-contract.sh
Scripts/verify-fixture-corpus.sh
Scripts/verify-gradle-plugin-fixture.sh
Scripts/verify-agent-surface.sh
Scripts/verify-release-readiness.sh # clean build 두 번, publish하지 않음

# 현재는 컴파일된 class root를 명시한다.
cli/build/install/kartograph/bin/kartograph graph \
  --classes path/to/build/tmp/kotlin-classes/debug \
  --format dot

# 초기 dead 수직 슬라이스. 출력은 삭제 가능 판정이 아니라 도달 불가 사실이다.
cli/build/install/kartograph/bin/kartograph dead \
  --classes path/to/compiled/classes \
  --project path/to/project \
  --manifest app/src/main/AndroidManifest.xml \
  --resources app/src/main/res \
  --namespace dev.example.app \
  --keep-rules app/proguard-rules.pro \
  --classpath path/to/dependency/classes.jar \
  --strict

# 현재 전체 finding을 고정한 뒤 새 finding만 strict 대상으로 삼는다.
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

개발 중인 Gradle plugin은 Android variant마다 `kartographDead<Variant>` task를 등록한다. AGP public
Variant API가 dependency consumer rules를 merged file로 노출하지 않으므로 현재는 해당 파일을 명시한다.

```kotlin
plugins {
    id("io.github.ictechgy.kartograph")
}

kartograph {
    keepRules.from("proguard-rules.pro", "path/to/dependency/consumer-rules.pro")
    strict.set(true)
    baseline.set(layout.projectDirectory.file(".kartograph-baseline.json"))
    reportFormat.set("github-actions") // gradle, github-actions, sarif, json, text
}
```

```bash
./gradlew kartographDeadDebug
```

report는 `build/reports/kartograph/<variant>.txt`에 생성된다. include 대상이 task 실행 중 발견되는 현재
구조에서는 stale report를 피하기 위해 task output을 up-to-date/cache 결과로 재사용하지 않는다.

에이전트는 전체 graph dump 대신 한 symbol의 사용·의존·도달성을 query한다. Flutter/React Native 경계는
isthmus `bridge-facts` v1으로 내보내고, 프로젝트용 skill은 기존 파일을 보호하며 설치한다.

```bash
kartograph query UserService --classes path/to/classes --project . --depth 2 --limit 100
kartograph bridges --project . --format json
kartograph skill --project .
```

architecture query도 같은 usage edge 의미를 공유한다. `cycles`는 module/package SCC와 weakest edge를,
`rules`는 fail-closed layer YAML 위반과 실제 edge 근거를, `metrics`는 Martin Ca/Ce/I/A/D를 출력한다.

```bash
kartograph cycles --classes path/to/classes --strict
kartograph rules --classes path/to/classes --config .kartograph.yml --strict
kartograph metrics --classes path/to/classes
```

## 안전한 해석과 한계

kartograph의 finding과 `unreachable`은 주어진 입력 graph의 사실이다. **어떤 코드도 안전하게 삭제할 수 있다고
말하지 않는다.** reflection, JNI, 동적 등록, 누락된 variant/classpath와 stale build output은 결과를 바꿀 수
있다. 변경 전에 `--explain`, runtime 경로와 해당 variant 테스트를 검토한다. 전체 경계는
[`docs/LIMITATIONS.md`](docs/LIMITATIONS.md), 로컬 처리와 report 공개 시 주의점은
[`SECURITY.md`](SECURITY.md)에 있다.

| 문서 | 내용 |
|---|---|
| [`docs/PRD.md`](docs/PRD.md) | 무엇을 · 누구를 위해 · 어디까지 · 무엇을 하지 않을지 |
| [`docs/PLAN.md`](docs/PLAN.md) | 단계별 계획. Phase 0 은 코드가 아니라 **원천 결정 실험**이다 |
| [`docs/PHASE3-ADOPTION.md`](docs/PHASE3-ADOPTION.md) | baseline, `--since`, machine report와 Gradle 도입 계약 |
| [`docs/PHASE4-AGENT.md`](docs/PHASE4-AGENT.md) | query, 계량 limitations, bridge-facts와 agent skill 계약 |
| [`docs/PHASE5-VALIDATION.md`](docs/PHASE5-VALIDATION.md) | cycles, layer rules, Martin metrics self-analysis와 성능 근거 |
| [`docs/LIMITATIONS.md`](docs/LIMITATIONS.md) | 0.1.0 분석 경계와 finding의 안전한 해석 |
| [`docs/RESEARCH.md`](docs/RESEARCH.md) | 확인된 사실 · 확인되지 않은 주장 · 출처 |

## 라이선스

MIT. 상업적 사용을 포함해 영구 무료다.
