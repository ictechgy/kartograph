# 코드 변경 영향 점검

`impact`는 수정할 선언에 의존하는 심볼과 연결 경로를 조사한다. 반환값은 **잠재적 영향 후보**이며 동작 변화의 증명,
삭제 승인이나 테스트 생략 승인이 아니다. `dead`의 도달성/보존 판정과 다른 질의다.

AI 클라이언트에서는 [MCP 서버](MCP.md)의 `query_symbol`, `impact`, `freshness`로 같은 조사를 수행할 수 있다.

## 수정 전에 조사

해당 variant를 먼저 빌드하고 기존 query와 같은 class·classpath·manifest/XML·keep 입력으로 capture한다.
반복 질의에서는 같은 파일을 재사용한다. 테스트의 영향도 조사하려면 관련 컴파일된 test root도 `--classes`로 포함한다.

```sh
kartograph snapshot --classes app-classes --classes test-classes --project . \
  --keep-rules keep.pro --include-paths --scope sample:debug > graph.json
kartograph impact 'method:sample/Repository#load()V' --graph-file graph.json
```

`--symbol`과 `--file`은 반복할 수 있다. 동명·overload는 명시적 JVM USR로 선택한다.
파일 선택은 그 파일에 연결한 모든 컴파일 선언을 포함하므로 메서드 선택보다 넓을 수 있다.
basename만 남은 입력은 같은 이름의 후보 전체를 포함하고 `source-file-candidates` 한계를 기록한다.
manifest/XML/keep 파일은 snapshot의 보존 근거 위치와 일치하는 선언을 선택한다. 설정 내용 전체를 해석하는 변경 분석은
아니며, 대응 사실 없는 build script·resource 파일 등은 `unmappedFile`로 남는다.

큰 결과는 관찰 후보 전체를 먼저 만든 다음 navigation 필터와 페이지를 적용한다. `--file`은 변경 선언을 고르는
입력이고 `--affected-file`은 그 결과의 후보 source path를 좁히는 필터이므로 서로 대체하지 않는다.
파일로 찾은 JVM 선언은 해당 ID가 존재하는 모든 입력 시점에서 조사한다. 파일을 이동한 경우 이전 경로만
선택하더라도 현재의 새 호출자를 포함하며, 두 시점의 탐색과 경로는 별도로 유지한다.

```sh
kartograph impact --symbol 'class:sample/Repository' --graph-file graph.json \
  --module feature --test-status production --relation direct \
  --sort file --offset 0 --limit 100

# 페이지 한도 없이 관찰된 후보를 모두 내보낸다. traversal/path budget은 여전히 보고된다.
kartograph impact 'class:sample/Repository' --graph-file graph.json --all
```

같은 필터 축의 값은 OR, 서로 다른 축은 AND다. 지원하는 필터는 `--module`, `--affected-file`, `--kind`,
`--test-status {test|production|unknown}`, `--relation {direct|structural|transitive|unknown}`,
`--path-status {complete|partial|unavailable}`다. 정렬은 기본 `review`와 `--sort usr|module|file|test|relation|path|path-status`를 지원하며,
동률은 항상 JVM USR로 결정한다. test 상태는 명시적으로 인식한 `src/test`, `src/androidTest`,
`src/testFixtures` 등의 source root와 `src/main` 등의 root만 분류하고, 나머지는 `unknown`으로 남긴다.
이 분류는 source 경로 관례이며 해당 선언이 실행 가능한 테스트라는 증거는 아니다.
module·file·test 필터는 각 축이 base 또는 current 사실에 맞으면 포함하며, 축별 일치 시점이 다를 수 있다.
같은 페이지 탐색에서는 입력·필터·정렬·예산을 고정한다. 결과 한도가 기본 경로 예산에도 영향을 주므로 한도를
바꿔 비교하려면 `--path-limit`을 명시한다.
전역 요약이 큰 경우 `--summary-limit <1..100000>`으로 각 축의 앞부분만 표시할 수 있다.
`summaryNavigation`은 원래·반환·생략 항목 수와 생략 후보 수를 알리고, 요약 생략 시 상태는
`partial`이다. 후보 총수·영향 페이지·선택·경로·한계는 바뀌지 않는다. 기본 CLI 출력은 전체 요약이며,
요약 항목의 제한은 영향 페이지 offset과 별개다.
`omittedCandidates`는 생략된 bucket count의 합이다. 시점별 파일·모듈이 다르면 같은 후보가
여러 bucket에 들어갈 수 있으므로 고유 후보 수를 뜻하지 않는다.
`--kind`는 대표 선언(current에 있으면 current)의 종류를 선택한다. `--sort test`는 시점별 분류가 같으면
그 상태로, 다르면 `unknown`으로 정렬한다. 필터·bucket은 개별 시점의 source 사실을 사용한다.

대형 입력에는 `snapshot --compact`를 사용한다. v2는 문자열 사전과 node/call/edge 배열의 참조 인덱스로 반복 정보를 줄인다.
정점·간선·외부 호출·보존 근거를 버리지 않으며 기존 v1과 같은 검증을 거친다. 기본 출력은 v1이고 query/impact는 두 형식을 읽는다.
저장·읽기 한도는 기본 64 MiB다. 큰 그래프는 capture와 saved query·impact·verify-snapshot·MCP 시작 시
`--snapshot-max-mib 128`을 지정해 최대 128 MiB까지 허용할 수 있다(1..128). 이 옵션은 그래프 사실이나
신선도 증거를 바꾸지 않는다. 일반 compiled query에는 적용하지 않는다. 메모리에 파싱한 그래프와 분석 작업은
파일 크기보다 많은 heap을 사용할 수 있다.

Gradle 자동 캡처에는 `kartograph { snapshotMaxMiB.set(128) }`을 설정한다. 기본값은 64이며
JVM·Android snapshot task에 같은 범위 검증을 적용한다. CI에서 만든 큰 파일을 읽는 명령에도 한도를 명시한다.
`Scripts/check-impact.py`에도 `--snapshot-max-mib 128`을 전달하면 영향 조사와 base/current 신선도
검사에 같은 한도를 적용한다. 옵션을 생략한 기존 실행 방식은 유지한다.

## CI에서 갱신하고 비교

### JVM 빌드에서 자동 캡처

Java 또는 Kotlin/JVM 프로젝트는 Gradle plugin에서 main/test compiler와 실제 SourceSet 출력 경로를 연결할 수 있다.
Kotlin은 의도한 Gradle toolchain provider를 명시적으로 지정한다. 이 provider를 Kotlin compiler와 증거 기록에
함께 적용하므로 기존 프로젝트가 사용하는 toolchain을 선택한다. 현재 KGP 2.4.10 소비 프로젝트에서 검증했다.

```kotlin
kartograph {
    snapshotsEnabled.set(true)
    includeSourcePaths.set(true)
    // Kotlin/JVM 프로젝트에서 지정한다. Java 전용 프로젝트에는 필요하지 않다.
    snapshotKotlinToolchain.set(javaToolchains.launcherFor(java.toolchain))
}
```

```sh
./gradlew kartographSnapshot -Pkartograph.revision="$COMMIT_SHA"
kartograph verify-snapshot --graph-file build/reports/kartograph/jvm-snapshot.json \
  --project . --input-bindings build/kartograph/jvm-input-bindings.json
kartograph impact 'class:sample/Repository' \
  --graph-file build/reports/kartograph/jvm-snapshot.json
```

`kartographSnapshot`은 테스트를 실행하지 않으며 선택한 컴파일·runtime artifact 생산 작업을 실행한다.
테스트 실행은 기존 CI 절차를 유지한다. Kotlin compiler가 분석에 읽은 Java 소스는 javac의 성공 증거를
대신하지 않는다. 소스가 있는 compiler의 출력 또는 증거가 빠지면 캡처가 실패한다.
소스가 없는 언어의 정상적인 `NO-SOURCE` 출력은 허용한다. 임의 라이브러리 누락은 계속 오류다.

개발 버전은 의존 프로젝트가 공개 Gradle artifact metadata로 선언한 class 디렉터리도 부재까지 추적한다.
예를 들어 Kotlin 전용 의존 모듈의 Java 출력이 아직 없어도 compiler가 실제 받은 classpath 상태를 기록한다.
빈 외부 출력 여러 개는 내용 해시만으로 서로 바꿔 연결하지 않으며, 위치를 구분하는 불투명 식별자를 사용한다.
나중에 해당 디렉터리에 바이트가 생기면 기존 snapshot은 stale이다. 임의 파일 의존성을 이 경로로 허용하거나
의존 모듈의 런타임 완전성을 증명하지 않는다. 이 수정은 0.15.0부터 제공하며 0.14.0에는 포함되지 않는다.

자동 수집이 지원하지 않는 compiler 입력은 일반 컴파일을 막지 않고 증거 생성 거부로 기록한다.
`kartographSnapshot`은 해당 증거가 없으면 사유와 함께 실패한다. 지원하지 않는 입력을 검증된 것으로
표시하지 않으며, 명시적인 수동 compiler witness API의 실패 계약은 유지한다.

설정 입력에는 해당 프로젝트와 상위 프로젝트의 build script·properties, `gradle/`의 catalog·wrapper·script,
`buildSrc` 및 included build 하위 모듈의 표준 설정·`src`를 포함한다. 생성된 `build` 출력과 `.gradle` 등의
캐시 디렉터리는 제외하며, `src` 안의 같은 이름 패키지는 보존한다. 아직 없는 관례 파일·디렉터리도 생성 여부를 추적한다.
자동 수집의 included build root는 현재 Gradle 빌드가 직접 포함한 위치다. 그 root 안의 하위 모듈과
`buildSrc`는 추적하지만, root 밖에서 간접으로 포함한 별도 build나 비표준 위치의 소스는 자동 탐색하지 않는다.
이러한 외부 build-logic과 applied script는 다음처럼 추가한다.

```kotlin
kartograph {
    snapshotBuildInputs.from(rootProject.file("conventions"), rootProject.file("config/analysis.gradle.kts"))
}
```

`verify-snapshot`은 기록된 입력의 현재 내용을 비교한다. 임의 Gradle 코드나 환경·네트워크 입력을 다시
평가하는 기능은 아니므로, 빌드가 외부에서 읽는 설정은 해당 입력 범위에 명시적으로 포함한다.

결과는 compact query snapshot이다. `jvm-input-bindings.json`은 외부 compiler/JDK 입력의 절대경로를
담는 해당 checkout 전용 파일이므로 커밋하거나 공개 artifact로 올리지 않는다. 아래 CI helper에는
`--input-bindings`와 `--base-input-bindings`로 각각 전달한다.

컴파일 task의 configuration cache·up-to-date 판정은 재사용하지만 snapshot 자체는 매번 전체 캡처한다.
선택적 [증분 class 파싱 캐시](INDEX-CACHE.md)는 같은 class 바이트의 파싱 결과를 재사용하며,
전체 관계·보존·신선도 검사는 계속 실행한다. snapshot output의 build cache를 의미하지 않는다. 이 자동 경로의 현재 범위는 JVM main/test와
지원하는 Android main/unit-test이며, 별도 custom source set 및 compiler-evidence collector의 자동 연결은 아직 검증 중이다.

#### Kotlin Multiplatform 프로젝트의 jvm target

KMP 프로젝트의 `jvm()` target은 `commonMain`과 `jvmMain`을 함께 일반 JVM class로 컴파일한다. kartograph의 원천은
JVM bytecode와 Kotlin metadata이므로 이 산출물은 그대로 분석 대상이며, 새 원천이나 설정은 필요 없다.
`kartograph snapshot --classes build/classes/kotlin/jvm/main --project . --include-paths`로 캡처하면
`commonMain`과 `jvmMain`의 소스 위치가 모두 해석되고, `actual` 선언을 `impact`로 질의하면 `commonMain`의
호출자를 해당 `commonMain` 파일 위치로 보고했다. Kotlin 2.4.10 multiplatform plugin과 `jvm()` target만 있는
표본에서 CLI로 확인했다. Gradle plugin의 `kartographSnapshot` 자동 캡처를 KMP jvm target compilation에 연결하는
것은 검증하지 않았다.

이 경로가 답하는 범위는 **JVM target에 미치는 영향**이다. `commonMain` 변경이 iOS·JS·Wasm target에 미치는 영향은
JVM 그래프로 증명하지 못한다. `expect`/`actual` 관계도 노출하지 않는다. 위 표본의 JVM 컴파일 결과에는 짝이 되는
선언 중 `actual` 쪽만 남았고, 그 class의 소스 파일은 `actual`이 있는 `jvmMain` 파일로 기록됐다. top-level `expect`
선언 자체는 class를 만들지 않았고, 남은 class·function·property의 metadata `isExpect` 플래그는 모두 false였다.
`actual typealias`나 다른 컴파일러 옵션은 측정하지 않았다. 자세한 조사와 결정은 [KMP 도달성 노트](KMP-REACHABILITY-NOTES.md)다.

### Android variant 자동 캡처

Android 프로젝트에도 같은 `snapshotsEnabled` 설정을 사용한다. Kotlin compiler에 적용할 toolchain은
다음처럼 지정한다. 기존 프로젝트의 toolchain 버전에 맞춰 선택한다.

```kotlin
kartograph {
    snapshotsEnabled.set(true)
    includeSourcePaths.set(true)
    snapshotKotlinToolchain.set(javaToolchains.launcherFor {
        languageVersion.set(JavaLanguageVersion.of(17))
    })
}
```

`./gradlew kartographSnapshotDebug`는 debug variant의 main과 활성화된 unit-test component를 캡처한다.
결과는 `build/reports/kartograph/debug-snapshot.json`, 해당 checkout 전용 경로 연결은
`build/kartograph/debug-input-bindings.json`이다. 테스트 자체는 실행하지 않는다.
SDK classpath, merged manifest, XML과 Java resource 입력을 함께 사용하며, 원래 baseline의 억제 상태도 보존한다.

AGP가 선언한 keep 파일 중 build 출력 아래에서 아직 생성되지 않은 파일은 개수를 한계에 보고한다.
해당 부모 디렉터리의 내용도 추적해 새 파일이 생겼을 때 이전 스냅샷을 그대로 검증하지 않는다.
소스 트리의 누락된 keep 파일은 계속 오류다.

application variant는 `process<Variant>Resources`가 생성하는 `R.jar`도 PROJECT class root에 들어온다.
plugin은 이 task에 resource producer witness(`agp-process-resources`)를 붙여 R.jar를 `classes` 출력으로 덮는다.
입력은 res 디렉터리·merged manifest·namespace·boot classpath이고, task가 실패하면 witness를 기록하지 않는다.
witness 없이 R.jar가 있으면 `verify-snapshot`은 `unwitnessed-class-root`로 거부하며, R.jar 내용이 바뀌면 `stale`이다.
library와 JVM 프로젝트의 경로는 바뀌지 않는다. 재현·설계 기록은 [APP-MODULE-EVIDENCE](APP-MODULE-EVIDENCE.md)다.

자동 캡처의 실제 설치 검증 조합은 다음과 같다.

| 모듈 | Android | Kotlin | Gradle | JDK / SDK | 검증 |
|---|---|---|---|---|---|
| library | AGP 9.3.2 | 내장 Kotlin | 9.6.1 | 17·21 / 36 | main/unit-test 증거, configuration cache, 같은 시각의 내용 변경, Java 테스트 소스 삭제 |
| library | AGP 8.7.3 | KGP 2.4.10 | 8.10.2 | 17 / 35 | compiler 재사용, 동일 snapshot, CLI 신선도 `matched`, Java/Kotlin main/test 선언과 manifest/XML 근거 |
| application | AGP 8.7.3 | KGP 2.4.10 | 8.10.2 | 17 / 35 | 공개 Portal 0.10.0 plugin, `processDebugResources` witness와 R.jar class root 포함 snapshot, configuration cache 재사용, class 캐시 6/6 hit, CLI·MCP 신선도 `matched` |

application 행은 Portal plugin DSL만 사용한 별도 프로젝트에서 2회 빌드해 확인했다. Android 기기 실행은 검증하지 않았다.
application의 R.jar witness 계약은 CI가 두 조합에서 고정한다. 최소 조합은 `agp-minimum` job과 tag workflow의
`Scripts/verify-agp-8-app-snapshot.sh`(AGP 8.7.3/Gradle 8.10.2 fixture)가 R.jar class root를 포함한 snapshot이
`verify-snapshot`에서 `matched`인지 검사한다. 최대 조합은 `AndroidApplicationSnapshotIntegrationTest`(AGP 9.3.2,
저장소 wrapper, JDK 17·21)가 witness 3종, R.jar class root, configuration cache 재사용을 검사한다. 같은 크기·수정 시각의
R.jar 내용 변경은 Gradle이 알아채지 못해 producer가 다시 실행되지 않는데, 이때 `verify-snapshot`은 `stale`,
capture는 `changed-classes`로 실패하며 이전 snapshot을 덮어쓰지 않는다. R.jar를 다시 생성하면 `matched`로 돌아오고
snapshot 바이트도 처음과 같다. witness가 없는 R.jar를
`unwitnessed-class-root`로 거부하는 계약은 `AppModuleRJarCliTest`·`RealAgpRJarCliTest`가 CLI 수준에서 고정한다.

#### 여러 모듈과 저장소 밖 build 디렉터리

여러 모듈 Android 앱(AGP 9, Compose)에 저장소 밖 init script로 plugin을 적용했을 때 드러난 실패를 다음처럼 다룬다.
재현은 `AndroidMultiModuleSnapshotIntegrationTest`(합성 3모듈, build 디렉터리 이동, Java 전용 unit test, 같은 바이트
외부 JAR)와 `IdenticalExternalInputSnapshotTest`가 고정한다.

- **build 디렉터리를 project 밖으로 옮겨도 된다.** 별도 opt-in 없이 각 project가 선언한 `layout.buildDirectory`를
  그 project의 build 출력으로 본다. merged manifest와 생성 resource XML의 근거 위치는 관례 배치와 같은
  `build/<build 디렉터리 기준 경로>`로 기록해 절대 경로를 남기지 않고, 옮긴 capture와 기본 배치 capture의 근거가
  같게 한다. build 디렉터리 밖이면서 project 밖인 XML은 계속 거부한다. class·witness 입력은 기존처럼 `external/`
  슬롯과 `build/kartograph/<variant>-input-bindings.json`(옮긴 build 디렉터리 안)으로 다시 연결한다.
- **같은 바이트의 외부 입력.** AndroidX KMP 분할 artifact는 항목 없는 stub JAR를 여러 transform 위치에 만든다.
  내용이 같은 일반 파일은 신선도 판정이 같으므로 compiler가 선언한 입력을 먼저, snapshot 전용 classpath를 나중에
  두고 결정적으로 고른다. 같은 내용의 입력이 여럿이면 서로 다른 파일에 짝짓는다. 비어 있는 서로 다른 출력처럼
  내용이 같은 **디렉터리**는 바꿔 연결하지 않고 task·입력 슬롯·후보(project 상대 경로 또는 `<external>/` 뒤 두 이름)를
  밝혀 거부한다. 후보가 없으면 task와 입력 슬롯, 후보 수를 알린다.
- **Java만 있는 unit test.** Kotlin unit-test 출력은 NO-SOURCE로 생기지 않지만 javac classpath에는 들어간다.
  같은 variant compiler의 선언 출력은 부재까지 추적하는 watch로 기록하므로 capture가 `fingerprint input is missing`으로
  멈추지 않는다. 나중에 그 디렉터리에 class가 생기면 이전 snapshot은 stale이다.
- **unit-test component 제외.** 기본값은 기존 계약대로 포함(`snapshotIncludeUnitTests = true`)이다. test 정점이 있어야
  `impact`가 테스트 검토 후보(`testStatus`)를 보여 주기 때문이다. `routes`는 스캔 단계에서 test source를 기본 제외하므로
  snapshot에 test가 있어도 route 사실이 늘지 않는다. production 그래프만 필요하거나 test compile 입력이 지원되지 않으면
  `snapshotIncludeUnitTests.set(false)` 또는 `-Pkartograph.snapshotIncludeUnitTests=false`로 끈다. 끄면 test compiler
  witness를 등록하지 않고 snapshot에 `unit-test-components-excluded:` limitation을 남긴다. androidTest는 원래 캡처하지 않는다.

모듈별 snapshot은 `kartograph snapshot merge`로 하나로 합친다. 모듈 snapshot 그래프에는 다른 모듈로 가는 참조·상속
간선이 없으므로 문서끼리 합치지 않고, 각 구성원의 검증된 class root·classpath를 함께 다시 인덱싱한다. 결과 그래프는
같은 class root를 `snapshot --classes`에 모두 준 것과 같고, JVM USR은 모듈과 무관해 그대로다.

```sh
./gradlew kartographSnapshotDebug
kartograph snapshot merge --project . --include-paths \
  --module app --graph-file app/build/reports/kartograph/debug-snapshot.json \
    --input-bindings app/build/kartograph/debug-input-bindings.json \
  --module core/network --graph-file core/network/build/reports/kartograph/debug-snapshot.json \
    --input-bindings core/network/build/kartograph/debug-input-bindings.json \
  --input-bindings-output build/kartograph/aggregate-input-bindings.json > aggregate-snapshot.json
kartograph verify-snapshot --graph-file aggregate-snapshot.json --project . \
  --input-bindings build/kartograph/aggregate-input-bindings.json
kartograph routes --role client --project . --graph-file aggregate-snapshot.json \
  --input-bindings build/kartograph/aggregate-input-bindings.json
```

- 구성원은 capture 당시와 같은 바이트여야 한다. stale이거나 외부 입력을 찾을 수 없는(`missing-external-input`) 구성원은
  거부한다. witness 없는 수동 capture처럼 `unverified`인 구성원은 합치되 `aggregate-member-unverified:`로 남긴다.
- provenance는 해시를 바꾸지 않고 경로만 `--project` 기준으로 옮겨 합치며, 구성원 scope 목록(`memberScopes`)을 싣는다.
  신선도 검사는 witness scope가 이 목록 안에 있을 때만 받는다. `--scope`를 생략하면 `aggregate:<variant>`다.
- 구성원 외부 슬롯은 `external/member-<n>/...`로 구분하며 `--input-bindings-output` 파일에 연결을 쓴다. 외부 입력이
  있으면 이 옵션은 필수이고, 파일에는 절대 경로가 있으므로 공개하지 않는다.
- 보존 근거는 구성원별 결과를 합친다. 모듈 경계를 넘는 keep rule·manifest는 다시 평가하지 않으며
  `aggregate-retention:` limitation으로 알린다. processor 관측이나 compiler-evidence witness가 있는 구성원은 아직 합치지 않는다.

`routes`는 `--input-bindings`로 snapshot과 함께 만든 로컬 연결을 받는다. 없으면 project 밖 입력(의존성 JAR, 옮긴 build
디렉터리)이 있는 snapshot은 `graph-file-freshness-unverified: missing-external-input`이다. plugin capture와 그 병합본은
연결을 주면 `matched`까지 확인된다. `snapshot --classes`로 만든 수동 capture는 compiler witness가 없으므로 연결을 줘도
`missing-build-witness`로 `unverified`에 머문다. 이는 증거 부족을 밝히는 결과이며, 검증이 필요하면 plugin 경로를 쓴다.

library 두 번째 조합에서는 KGP가 Gradle 8.14.4 이상으로 업그레이드하도록 권고한다. 경고를 억제하지 않고 검증했다.
Toolchain 연결은 기존 Kotlin bytecode target을 보존한다. JDK 21로 JVM target 17 코드를 컴파일하는 조합도 검증했다.

### 두 checkout 비교

저장소의 `python3 Scripts/verify-gradle-impact-ci.py`는 실제 배포 plugin JAR와 CLI를 사용해 임시 Git의
commit별로 빌드·캡처하고 strict CI 비교까지 실행한다. 변경하지 않은 테스트 호출자, no-change 비교,
rename 뒤 source 위치와 호출자, 잘못된 scope 거부를 검사한다. 같은 수정 시각·크기의 class 변경도
stale로 거부해야 한다. 변경 없는 build·capture·순수 query를 각각 두 번 반복하고 단계별 시간은
`build/reports/gradle-impact-ci/result.json`에 기록하며 GitHub CI도 같은 명령을 실행한다.
이 작은 fixture의 시간은 대형 프로젝트 성능 점수와 구분한다.

로컬과 GitHub CI의 같은 두-commit JVM fixture 관측값은 다음과 같다. 각각 한 번의 current 실행이며,
Gradle 시작 비용을 포함한다. Android 표본이나 대형 저장소의 성능 보장은 아니다.

| 단계 | 로컬 | GitHub CI |
|---|---:|---:|
| source build | 2.92초 | 5.01초 |
| snapshot capture 명령 | 2.68초 | 5.02초 |
| 내용 검증 | 0.20초 | 0.31초 |
| impact CI 비교(양쪽 검증 포함) | 0.63초 | 0.83초 |

원격 결과는 [검증 실행](https://github.com/ictechgy/kartograph/actions/runs/34755414975)의
`gradle-impact-ci` artifact에 단계별 종료 코드와 함께 기록했다.

base와 current checkout을 **같은 CLI 빌드·입력 범위·variant**로 빌드해 snapshot을 만든다.
각 capture에 `--revision <git rev-parse HEAD의 전체 값> --scope <프로젝트:variant>`를 전달한다.
라벨은 호출자의 선언이며 class/source 내용 지문이나 빌드 신선도 증명이 아니다. 분석 한계를 함께 확인한다.
현재 빌드와의 대응을 검증하려면 [compiler producer](BUILD-PROVENANCE.md)의 증거를 `--build-witness`로 붙이고
`verify-snapshot`을 실행한다. producer 입력·출력과 현재 바이트가 맞아야 `matched`가 되며, 증거 없는 snapshot은
`unverified`다. 동일 크기·시각으로 바뀐 파일도 내용 지문으로 비교한다.

```sh
# 각 checkout에서 빌드가 끝난 후 snapshot 생성. 실제 root와 추가 입력은 해당 프로젝트의 것을 사용한다.
kartograph snapshot --classes app-classes --classes test-classes --project . \
  --include-paths --revision "$COMMIT_SHA" --scope sample:debug > current.json

# current checkout에서 실행. base.json은 기준 commit에서 만든 snapshot이다.
python3 Scripts/check-impact.py --binary /path/to/kartograph --project . \
  --base origin/main --base-graph base.json --graph-file current.json > impact.json
```

helper는 commit 간 변경 파일을 NUL 구분으로 읽는다. rename의 이전·이후 경로, 삭제 경로도 포함한다.
snapshot 라벨과 실제 비교 commit, 두 scope, analyzer version이 다르면 실패한다. 동일 버전 문자열만으로 서로 다른
로컬 빌드를 식별할 수는 없으므로 CI가 사용하는 배포 artifact도 고정한다. tracked 미커밋 변경은 거부한다.
snapshot 파일 자체는 Git 밖의 CI artifact/cache로 관리하며 기존 미사용 baseline과 별개다.
helper 기본 모드는 보고용이다. 문서·설정 등 매핑되지 않은 파일도 `unresolved`에 남기되 유효한 보고서를 만들면 0을 반환한다.
확장자만으로 파일을 자동 무시하지 않는다. `--strict`는 선택/탐색이 partial/notFound일 때 종료 코드 1을 준다.
신선도도 `freshness`에 보고하며 `--strict`는 stale/unverified 증거에 1을 반환한다. 기준 snapshot의 파일도 검사하려면
`--base-project`를 제공한다. 외부 입력은 `--input` / `--base-input`으로 각각 연결하며 기준 checkout이 없으면 기준은 미검증이다.
큰 변경은 `--limit`을 높여 출력 잘림을 줄일 수 있다. 모든 런타임 경로의 완전성을 보장하는 모드는 아니다.

직접 `impact` CLI의 종료 코드는 정상 보고 0, 읽기/입력 문맥/도구 실패 2, 모호한 심볼·매핑할 수 없는 선택/사용 오류 64다.
영향 후보가 있다는 이유만으로 실패하지 않는다. 기존 `check-pr.py`는 계속 전체 그래프의 새 미사용 진단을 검사한다.

## JSON 계약

`format = kartograph-impact`, `version = 1`이다.

- `changed`: 선택한 선언과 존재 시점. 삭제된 선언도 base에서 유지한다.
- `affected`: 전체 관찰 후보에서 필터·정렬·offset·limit을 적용해 반환한 페이지. generated/private/baseline-suppressed 선언도 숨기지 않는다.
  전체 수는 `observedAffected`와 `summary.observed.candidates`에 있으며, `--all`은 페이지 limit을 없애지만 탐색·경로 예산을 없애지 않는다.
- `paths.nodes`: 후보 → 변경 선언 방향의 경로. `edges`는 `revision` 그래프의 실제 간선을 그대로 보존하며 `kind`와 `origin`을 포함한다.
  직접 변경한 method 계약의 하위 override도 포함한다. 그래프의 override는 상위→구현 방향이므로 이때는
  `traversal = overrideContract`로 역방향으로 읽음을 표시한다. 일반 간선은 `traversal = dependency`다.
  이 계약 확장은 실제 상속 관계의 override 간선만 따른다. dispatch 모델이 호출자→구현 후보로 기록한
  `origin = dispatchModel` 간선은 변경 method가 호출하는 인터페이스의 구현체를 "변경 계약의 override"로 만들지 않으며,
  호출자 방향(구현이 바뀌면 그 후보로 dispatch될 수 있는 호출자)으로만 계속 쓴다.
  멤버가 자기 소유 class를 가리키는 `reference` 간선(도달성용)은 영향 탐색의 사용 관계로 읽지 않는다. 따라서 `<clinit>`처럼
  class 정점에 닿는 변경은 그 class를 참조하는 다른 class·중첩 class·외부 호출자로 이어지지만, 같은 class의 멤버 전부로
  퍼지지는 않는다. `class:X` 자체를 변경 대상으로 고를 때도 X의 멤버는 이 소유 참조 경로로는 나열되지 않으며, 멤버가 X의
  생성자·static field 등을 실제로 쓰는 `call`·`field_access` 사슬은 그대로 후보가 된다.
  base/current 간선을 합쳐 실제로 없었던 경로를 만들지 않는다. 후보마다 각 시점의 결정적인 최단 경로 하나를 제공한다.
- `retention`: 시점별 보존 이유와 파일·줄. 보존 근거는 호출자 간선이 아니다.
- `observedAffected`: 탐색 한도 안에서 관측한 후보 수. 출력 한도보다 클 수 있다.
- `unresolved`: 모호성·부재·매핑되지 않은 파일. 빈 결과를 영향 없음으로 오해하지 않는다.
- `truncated.results/depth/budget`: 결과 페이지·깊이·방문/경로 예산의 잘림. 기본 깊이 100, 결과 500, 방문은 시점당 100,000개다.
  설명 경로에 포함하는 간선은 기본 100,000개로 제한하며 큰 출력 한도에서는 최대 500,000개까지 확장한다. 경로 예산을 넘은 후보도
  `affected`에서 제거하지 않고 `pathStatus=unavailable|partial`, `pathOmissions`, `budgets.pathOmissions`로 누락 사실을 기록한다.
- `limitations`: 각 시점의 runtime/freshness 한계와 잠재적 영향이라는 의미를 보존한다.

## 탐색 navigation과 source 사실

`summary.observed`는 페이지와 필터를 적용하기 전 관찰 후보 전체의 count다. `summary.filtered`는 같은 전체 후보에
필터만 적용한 count이며, 두 summary의 `byModule`, `byFile`, `byTestStatus`, `byRelation`, `byPathStatus`는
사람이 다음 질의를 고를 수 있는 설명용 그룹이다. `navigation.filtered`와 `navigation.returned`는
각각 필터 후 전체와 현재 페이지의 수이고, `hasNext`/`hasPrevious`와 `offset`/`limit`으로 페이지를 이어 간다.
따라서 첫 페이지의 `affected`만 세어 전체 후보 수나 영향 없음으로 해석하지 않는다.
offset이 0보다 큰 페이지는 앞의 후보를 포함하지 않으므로 마지막 페이지라도 `truncated.results=true`와
`status=partial`이다. 페이지의 다음 결과 유무는 `hasNext`로 판단한다.
base/current 사실이 서로 다른 module·file·test 상태를 가지면 한 후보가 여러 bucket에 포함될 수 있으므로 bucket 합계를
후보 수와 비교해 partition으로 해석하지 않는다.

각 선언에는 기존 대표 `module`/`location`과 함께 `facts`가 있다. `facts`는 base/current별 module, source 위치,
test 상태를 합치지 않으며 확인할 수 없는 값은 null 또는 `unknown`이다. `relation`은 한 간선 direct,
상속·override·annotation을 포함하는 structural, 그 밖의 다단계 transitive, 경로가 없어 확인하지 못한 unknown이다.
이 값들은 우선순위·위험 점수가 아니며 필터에서 제외된 후보가 안전하다는 뜻도 아니다.

`pathStatus=partial|unavailable`는 경로가 없다는 분석 결론이 아니라 예산 때문에 path witness를 일부 또는 전부
출력하지 못했다는 뜻일 수 있다. `pathOmissions`의 revision, reason, requiredEdges와 top-level `budgets`의 실제
방문·간선 사용량을 함께 검토한다. `--file` 선택의 unresolved 항목, base/current의 독립 `paths`, traversal 잘림과
snapshot freshness/runtime limitation은 페이지나 필터를 사용해도 보존된다.
누락 경로의 `edgeKinds`는 중복 없는 관계 종류다. 전체 경로 대신 거리와 이 유한한 집합을 보존해 경로 예산을
소진한 뒤 긴 경로를 계속 복제하지 않는다.

## isthmus trace용 순회 문서 (`language-traversal` v1)

`kartograph-impact` v1은 정점마다 via 하나만 싣기 때문에 root가 여럿이면 isthmus `trace`가 root 귀속을 via 사슬의
대표 root 하나로만 복원한다(`roots-provenance-partial`). root마다 따로 돌리면 문서가 커져 trace 입력 상한을 넘는다.
그래서 isthmus [`LANGUAGE-TRAVERSAL.md`](https://github.com/ictechgy/isthmus/blob/main/docs/LANGUAGE-TRAVERSAL.md)의
다중 root 순회 형식을 따로 낸다. 기본 `impact` 출력은 바꾸지 않는다.

```bash
# route-call을 감싼 심볼 전부를 root로 한 번에 역방향 순회한다. routes 문서를 그대로 root 목록으로 쓴다.
kartograph impact --format language-traversal --roots-from routes.json \
  --graph-file graph.json --project . [--dispatch candidates] [--generated-at 2026-09-27T00:00:00Z]
# 핸들러에서 정방향으로 순회한다(direction: dependencies).
kartograph reach <usr>... --graph-file graph.json --project .
```

- **id**: root id와 `reached[].symbol.usr`는 snapshot의 JVM USR(`method:owner#name(desc)ret`)이며 `routes`·`schema`의
  `symbol.usr`와 같은 문자열이다. `--roots-from`은 JSON 문자열 배열이나 bridge-facts 문서를 받는다. bridge-facts면
  사실의 `symbol.usr`를 문서 순서대로 중복 없이 root로 쓴다. 해석하지 못한 root는 원문을 id로 두고 `symbol` 없이 싣고
  `root-not-found:`와 `truncationReasons: ["root-not-found"]`를 단다(종료 코드 64, 문서는 출력한다).
- **`project`**: `--project`의 realpath다. isthmus는 모든 문서의 project가 같아야 조인하므로 `routes`와 같은 root를 준다.
  `--generated-at`을 주면 같은 입력에서 바이트가 같은 문서를 낸다.
- **`revision`**: `--revision <rev>`를 주면 그 값이다. 없으면 snapshot의 commit 라벨(`snapshot --revision`)이고, 그것도
  없으면 `--project` 디렉터리 아래에 커밋하지 않은 변경·추적되지 않은 파일이 없을 때만 git `HEAD`다. 작업 트리가
  더럽거나 저장소가 아니면 싣지 않는다 — 고친 소스 위에서 `HEAD`를 실으면 isthmus가 낡은 분석을 최신으로 본다.
  snapshot에 라벨이 있는데 `--revision`이 다르면 도구 실패(2)다. git은 CLI만 부르고 분석·렌더링은 부르지 않는다.
  저장한 snapshot을 읽으므로 `HEAD` 자동 감지는 snapshot을 지금 작업 트리에서 캡처했다고 가정한다. 다른 커밋에서
  캡처했다면 `snapshot --revision`으로 라벨을 남긴다.
- **`graphRevision`**: 순회한 그래프 내용의 `sha256:` 해시다. 정점 id·종류, 간선의 양 끝·종류·출처, 순회 간선의 근거 등급,
  어휘적 소속(`graph.enclosures`)과 그 캡처 여부, 정점별 잇지 못한 호출 수를 정렬해 담고 위치·이름·weight는 넣지 않는다.
  방향과 무관하므로 같은 snapshot 위의 `impact --format language-traversal`과 `reach` 문서가 같은 값을 내고, `--compact`나
  `--include-paths` 같은 snapshot 표기 차이에도 같다. isthmus는 같은 플랫폼 문서끼리 이 값이 다르면 다른 그래프에서
  나온 분석으로 본다.
- **제어 문자**: root와 `--revision`에 C0(U+0000–U+001F)·DEL·C1(U+0080–U+009F)·U+2028·U+2029나 짝 없는 서러게이트가
  있으면 문서를 만들기 전에 사용 오류(64)다. isthmus는 그런 id·revision이 든 문서를 통째로 거부한다.
- **roots·depth·via**: `reached[].roots`는 그 정점에 닿는 모든(자기 제외) root의 오름차순 인덱스다. 64개를 넘으면
  작은 64개만 싣고 `rootsTruncated: true`를 단다. 다른 root에서 닿은 root도 자기 인덱스 없이 싣는다. depth는 가장
  가까운(자기 제외) root까지의 간선 수(1..128)이고 via는 그 최단 경로의 직전 정점(동률이면 usr가 작은 쪽)이다.
  `relationships`는 via와 그 정점 사이에서 따른 간선 종류다(`call`·`fieldAccess`·`reference`·`override`·`contains` 등).
- **한 번에 계산한다**: 정점마다 가장 가까운 서로 다른 root 두 개를 기록하는 다중 출발 BFS로 depth·via를 구하고,
  등급별 간선 부분 그래프를 강연결요소로 접어 root 비트 집합을 위상 순서로 전파해 roots·evidence를 구한다.
  테스트는 무작위 그래프에서 root별 전수 BFS 결과와 대조한다. roots·evidence는 depth 상한과 무관한 실제 도달
  관계이며, depth 상한은 목록에 싣는 정점만 자른다(잘리면 `truncated`, `truncationReasons: ["depth"]`).
  도달 정점이 `--max-reached`(기본·최대 100,000)를 넘으면 (depth, usr) 앞부분만 싣고 `reached-limit`로 알린다.

### 람다의 어휘적 소속과 간선 등급

Kotlin 람다 class·익명 객체·suspend 람다·SAM 변환 class는 `FunctionN.invoke` 같은 외부 호출에 대해 dispatch
모델(`origin = dispatchModel`)이 모든 프로젝트 `invoke` 구현을 후보로 잇는다. 이 후보 간선을 따라가면 람다 본문
하나의 역방향 영향이 콜백을 받는 공통 함수(예: Compose 컴포넌트)를 거쳐 앱 대부분으로 퍼진다. 그래서 두 가지를 더했다.

- **어휘적 소속**: `snapshot`이 classfile `EnclosingMethod`를 `graph.enclosures`(`localClass` → `enclosing`)로 싣는다.
  순회는 이것을 `contains` 간선(감싼 선언 → 지역 class와 그 메서드·생성자)으로 쓴다. 람다 본문의 변경은 감싼 함수와
  그 호출자에 닿고, 정방향이면 감싼 함수에서 본문의 호출 대상에 닿는다. 컴파일러가 기록한 관계라 `direct`다.
  감싼 메서드가 그래프에 없거나 초기화 문맥이면 소유 class로 넓힌다. invokedynamic 람다는 본문이 같은 class의
  합성 메서드이고 bootstrap 인자 handle이 이미 `call` 간선이므로 따로 다루지 않는다.
- **간선 등급**: 모든 순회 간선에 등급을 매긴다. 문서의 `evidence`는 root마다 가장 강한 등급을 구한 뒤 그중 가장
  약한 값이다(root별 하한).

| 등급 | 간선 | 근거 |
|---|---|---|
| `direct` | bytecode·Kotlin metadata·compiler reference의 호출·필드 접근·참조·상속·어노테이션, `contains` | 컴파일러가 대상을 확정했다 |
| `bound` | override(dispatch) 중 수신 정적 타입이 프로젝트 타입이고, 그 타입과 모든 프로젝트 하위 타입의 구체 class가 메서드를 같은 프로젝트 구현 하나로 해석하는 것. `runtimeModel` 간선 | 닫힌 세계 가정에서 대상이 하나다 |
| `candidate` | 그 밖의 override: 구현이 둘 이상인 인터페이스, `java/lang/Object#toString`처럼 외부 소유 가상 호출의 계층 후보 | 가능성만 있다 |
| lambda(따로 제외) | 대상이 지역·익명 class의 멤버이거나 호출 지점이 Kotlin 함수 타입·`java/util/function`·`Runnable`·`Callable` 호출뿐인 dispatch | candidate와 같은 등급이지만 `contains`가 같은 본문을 정확히 잇는다 |
| `bound`/`candidate`(`callback`) | 받은 람다를 실행하거나 그대로 넘기는 함수 → 람다 본문. 아래 [콜백 흐름](#콜백-흐름-callback) | 호출 문맥 안에서만 참이라 닿은 함수를 더 퍼뜨리지 않는다 |

`bound`의 닫힌 세계 가정은 분석한 class가 프로젝트 타입의 구현을 모두 담는다는 것이다. 수신 타입이 외부
타입이면 라이브러리 객체도 올 수 있으므로 `bound`가 될 수 없다. 구체 하위 타입 하나라도 프로젝트 class 사슬 안에서
메서드를 찾지 못하면(외부 상위 class 상속, 인터페이스 default 메서드) 보수적으로 `candidate`다. 런타임 proxy·mock·
snapshot 밖 class는 모델링하지 않으며 `bound-dispatch-closed-world:` 한계로 알린다. 이 규칙은 dispatch 모델 간선과
상위 선언 → 구현 override 간선에 똑같이 적용한다.

### 콜백 흐름 (`callback`)

어휘적 소속만으로는 람다를 **실행하는** 쪽이 빠진다. 화면 F가 route 호출을 담은 람다 L을 공통 UI 함수 G(`onClick`)에
넘기면 L 본문의 변경은 F에는 닿지만 G에는 닿지 않는다. lambda fan-out(`--dispatch all`)을 따르면 G에 닿는 대신 G를
부르는 모든 화면으로 퍼진다. 그래서 `snapshot`이 bytecode 값 흐름 사실 두 가지를 더 싣고, 역방향 순회가 이를 호출
문맥 안에서만 잇는다.

- `graph.callbackArguments`: F 안에서 만든 람다(invokedynamic 구현 메서드, 또는 `EnclosingMethod`가 있는 지역·익명
  class)가 호출 지점 G(...)의 인자 i로 그대로 넘어간 사실이다. 지역 변수 복사·`checkcast`·분기 합류를 따라가며, 분기에서
  다른 값과 합쳐져도 L일 수 있으면 기록한다. Compose 컴파일러의 `ComposableLambdaKt.*(… block …)` 래퍼는 `block`과
  같은 값으로 본다(래퍼의 `invoke`가 같은 인자로 `block`을 부른다).
- `graph.parameterUses`: 함수형 타입(또는 SAM 호출 대상) 파라미터의 쓰임이다. 수신 객체 호출, 다른 호출의 인자,
  invokedynamic 캡처, 필드·반환·배열 저장, 모델 없는 bootstrap을 남긴다. `declared` 행은 그 파라미터의 쓰임을
  빠짐없이 기록했다는 표식이며, 표식이 없는 파라미터로 넘어간 값은 "쓰이지 않음"이 아니라 "모름"으로 다룬다.
- `graph.lambdaEscapes`: F가 만든 람다 값이 호출 인자가 아닌 방식(필드·배열 저장, 반환, F가 직접 실행, 모델 없는
  bootstrap)으로 쓰인 사실이다. 넘기는 쪽에서 새는 별칭을 잡는다.

G의 파라미터 i에서 출발해 값을 수정 없이 넘겨받은 프로젝트 메서드(정적·특수 호출, 닫힌 세계에서 구현이 하나인 가상
호출, invokedynamic 캡처의 구현 메서드)를 최대 8단계까지 따라간다. 그 경로에서 L의 함수형 메서드를 부르는 곳과
라이브러리 코드에 값을 넘기는 곳이 실행 지점이다. 실행 지점으로 이어지는 경로 위의 메서드마다 `callback` 간선(메서드 →
L 본문)을 만든다. 라이브러리에 넘긴 경우 그 코드가 어느 함수형 메서드를 부를지 모르므로 지역 class의 모든 인스턴스
메서드로 잇는다.

| 등급 | 조건 |
|---|---|
| `bound` | 값이 한 번도 빠져나가지 않았고 실행 지점이 모두 프로젝트 안의 호출이다. 같은 F가 만든 같은 L의 **모든** 쓰임(다른 호출 인자 포함)이 이 조건을 만족하고 `lambdaEscapes`가 없어야 한다. 닫힌 세계에서 L을 실행하는 곳은 이 경로들뿐이다 |
| `candidate` | 실행 지점은 찾았지만 값이 라이브러리 코드로 넘어가거나(예: Compose `Button(onClick = …)`), 필드·반환·배열로 빠져나가거나, 가상 호출 구현을 하나로 정하지 못하거나, 쓰임 기록이 없는 파라미터로 넘어가거나, 깊이를 넘거나, `Object`가 아닌 다른 메서드를 부르거나, 새 실행 지점이 있는 람다가 캡처했다 |
| (간선 없음) | 실행 지점 없이 빠져나갔다. 흐름 수를 이유별로 `callback-flow-unresolved:`에 싣는다 |

여러 흐름이 같은 간선을 만들면 약한 등급이 이긴다. 받은 람다를 실행하지도 넘기지도 않는 함수로 간 흐름은 이을 것이
없으므로 간선도 미해결 수도 만들지 않는다. `--dispatch`가 빼는 등급의 콜백 간선 수는 `callback-excluded:`로 알린다.

- **호출 문맥**: `callback` 간선은 F가 G를 부른 그 호출에서만 참이다. 역방향 순회는 이 간선으로 닿은 G를 목록에 싣지만
  G의 다른 호출자(다른 람다를 넘기는 화면)로 퍼뜨리지 않는다. 계산은 콜백 간선의 호출자마다 그림자 정점을 두고, 그림자에서는
  콜백 간선만 나가게 한 뒤 출력에서 본 정점과 합친다. 그래서 G가 받은 람다를 다시 다른 람다에 넘기는 경우처럼 콜백 간선끼리는
  이어진다. F와 그 호출자는 L의 어휘적 소속으로 이미 닿는다. 한 정점은 한 줄이며 일반 경로와 콜백 경로 중 가까운 쪽의
  depth·via를 쓰고, root·evidence는 두 경로를 합친다. 콜백 경로의 `relationships`는 `callback`이다. 무작위 그래프에서
  (정점, 그림자 여부) 상태 공간의 root별 전수 BFS와 대조해 검증한다.
- **정방향**(`reach`)은 콜백 간선을 따르지 않는다. F에서 L 본문으로 가는 길은 어휘적 소속이 잇고, G에서 출발하면 어느
  람다를 받았는지 문맥이 없다.
- **inline 함수**는 람다 본문이 호출한 함수 안으로 복사되므로 전달 사실이 없고 본문의 호출은 F의 `direct` 간선이다. inline
  함수 자체는 호출되지 않아 목록에 없다. `crossinline` 람다가 만드는 class는 `EnclosingMethod`로 F에 속한다.
- **Compose**: `@Composable` 람다는 `rememberComposableLambda`로 감싼 값을 추적한다. 재구성 람다(`updateScope`)처럼
  파라미터를 캡처한 람다가 같은 함수로 값을 되돌려 넘기기만 하면 실행 지점이 늘지 않으므로 등급을 낮추지 않는다.
  `Composer.changed`·`changedInstance`는 다음 구성에서 비교할 값으로만 쓰므로 쓰임에서 뺀다. 객체를 돌려주지 않는
  검사 호출(`Intrinsics.checkNotNull*`·`areEqual`, `Objects.equals`·`hashCode`·`isNull`·`nonNull`)도 같다. 대부분의
  UI 함수는 콜백을 Material 컴포넌트에 넘기므로 `candidate`다. 캡처 없는 composable 람다를 `ComposableSingletons`의
  필드에 올려 둔 경우는 값이 필드를 거치므로 추적하지 않는다.
- **추적하지 않는 것**: 필드에 저장한 뒤 다른 곳에서 부르는 콜백의 실행 측(생성자 인자로 넘긴 람다, class 기반 클로저의
  캡처 포함), 중단 지점 너머로 continuation 필드에 넣는 suspend 함수의 콜백(필드로 빠져나간 것으로 본다), 객체를
  반환하는 라이브러리 호출을 거친 별칭, 구현이 둘 이상인 인터페이스 메서드로 넘긴 람다. 이런 흐름은 `candidate`이거나
  `callback-flow-unresolved:`에 남는다. `--dispatch all`은 여전히 모든 lambda fan-out을 따른다.
- 콜백 사실이 없는 옛 snapshot은 `callback-facts-unavailable:`로 알리고 콜백 간선 없이 순회한다(이 기능 전과 같다).

### `--dispatch`와 기본값

| 값 | 따르는 간선 |
|---|---|
| `direct` | direct |
| `bound` | direct + bound |
| `candidates` (기본) | direct + bound + candidate |
| `all` | direct + bound + candidate + lambda(`candidate`로 표시) |

기본값은 `candidates`다. trace가 쓸모 있으려면 인터페이스 뒤의 호출자(저장소·use case 계층)를 놓치지 않아야 하고,
정직하려면 그런 hop을 `candidate`로 표시해 isthmus가 `candidate-dispatch` gap을 남기게 해야 한다. lambda fan-out은
같은 본문을 `contains`가 정확히 잇고, 따라가면 무관한 화면까지 모든 root가 거의 같은 집합에 닿아 route별 구분이
사라지므로 기본에서 뺀다. 빠진 간선 수는 `lambda-dispatch-excluded:`로 알린다. 람다를 받아 실행하는 쪽은
[콜백 흐름](#콜백-흐름-callback)이 호출 문맥 안에서 잇는다(`bound` 콜백은 `--dispatch bound`부터, `candidate` 콜백은
기본값부터 따른다). 제외로 여전히 잃는 것은 필드에 저장했다가 다른 곳에서 호출하는 콜백처럼 값 흐름으로 증명하지 못한
실행 측이다. 필요하면 `--dispatch all`로 따른다.
어휘적 소속 사실이 없는 옛 snapshot에서는 lambda 간선을 빼면 본문이 끊기므로 `candidate`로 따르고
`lexical-enclosures-unavailable:`로 알린다. 새 snapshot을 캡처하면 정밀한 결과가 나온다.

### 잇지 못한 호출 (`unresolvedCalls`)

문서는 항상 `dispatch`를 실으므로 모든 정점의 `evidence`와 잇지 못한 호출을 완전히 신고한다는 선언이다.
정점 자신의 나가는 호출 지점 중 다음을 센다(0이면 생략).

- 외부 소유 가상·인터페이스 호출 중 dispatch 해석이 `unresolved`로 끝난 것(`external-dispatch` 한계와 같은 집합)
- reflection 같은 런타임 라이브러리 모델이 붙었지만 값이 해석되지 않은 호출
- LambdaMetafactory·StringConcatFactory·ObjectMethods·SwitchBootstraps가 아닌 bootstrap의 invokedynamic

라이브러리 메서드를 부르는 정적·특수 호출은 대상이 확정된 호출이라 세지 않는다. 이 값은 `--dispatch` 값과 무관하다.

### 호환성

- `impact`의 기본 출력(`--format json`, `kartograph-impact` v1)은 바이트 단위로 같다. `graph.enclosures`는 간선이 아니라
  별도 사실이므로 도달성·dead·query·기존 impact 결과를 바꾸지 않는다.
- snapshot은 선택 필드 `graph.enclosures`를 v1·compact v2 모두 같은 평문 모양으로 싣는다. 옛 reader는 모르는 graph
  키를 읽지 않는다. 새 reader는 이 키가 없는 옛 snapshot을 "소속 사실 미캡처"로 구분한다.
- class 인덱스 캐시 형식이 5로 올라 옛 캐시 항목은 한 번 다시 파싱된다. 콜백 사실을 더하며 6으로 다시 올렸다.
- snapshot은 선택 필드 `graph.callbackArguments`·`graph.parameterUses`도 v1·compact v2에 같은 평문 모양으로 싣는다.
  옛 reader는 읽지 않고, 새 reader는 두 키가 없는 snapshot을 "콜백 사실 미캡처"로 구분한다. 역시 간선이 아니므로
  도달성·dead·query·기본 impact 결과를 바꾸지 않는다.
- `graphRevision`은 콜백 간선(순회 간선에 포함)과 콜백 사실 캡처 여부도 담는다. 같은 snapshot이라도 이 버전과 이전
  버전의 값은 다르다.
- `language-traversal`의 `--revision`은 이제 임의의 revision 문자열을 받는다(전에는 snapshot 라벨과 같은 전체 commit
  hash만 받고 라벨이 없는 snapshot이면 실패했다). `graphRevision`은 snapshot 파일 바이트의 hex 해시에서 `sha256:` 그래프
  내용 해시로 바뀌었다. 기본 `impact`의 `--revision`·`--base-revision`은 그대로 전체 commit hash다.
- `language-traversal` 전용 옵션(`--dispatch`·`--project`·`--roots-from`·`--generated-at`·`--max-reached`)은 기본
  형식에서 받지 않고, 기본 형식 전용 옵션(`--base-graph`·`--file`·`--limit`·필터 등)은 새 형식에서 사용 오류(64)다.

## 현재 한계와 평가

클래스 사용·상속·field 접근 등은 전부 잠재적 의존이다. 어떤 수정이 실제 동작을 바꾸는지는 이 관계만으로 결정되지 않는다.
인라인 상수의 지워진 사용처, 입력 밖의 호출자, 임의 runtime 값과 누락된 variant는 별도로 검토한다.
현재 갱신은 전체 snapshot capture다. 저장 그래프 재사용은 증분 인덱싱이 아니다.
평가는 [계획](IMPACT-PLAN.md)의 실제 변경 과제와 compiler/runtime 코퍼스에서 수행한다.
20,699개 후보의 탐색·출력량·시간 비교와 재현 명령은 [영향 탐색 평가](https://github.com/ictechgy/kartograph/blob/b7bcc1570d1adc851abf77be9f728f186ada1b9b/experiments/impact-navigation/README.md)에 있다.
