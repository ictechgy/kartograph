# Changelog

이 프로젝트의 주목할 만한 변경은 이 파일에 기록한다. 형식은
[Keep a Changelog](https://keepachangelog.com/en/1.1.0/)를 따르고 버전은
[Semantic Versioning](https://semver.org/spec/v2.0.0.html)을 따른다.

## [Unreleased]

## [0.16.0] - 2026-09-23

### Added

- MCP `discover_symbols`로 심볼 또는 파일의 실제 USR 후보를 페이지 조회한다. 응답 크기에 따라
  페이지를 줄여도 다음 offset과 전체 후보 수를 유지하며, current/base 존재 시점을 표시한다.

### Fixed

- Kotlin `package.function` 표기가 실패하면 FILE_FACADE metadata에 근거한 후보를 제공한다.
  모듈 snapshot에 저장소 기준 파일 경로를 보낸 경우에는 정확한 경로 우선·경로 구성 요소 suffix로
  실제 선언 후보를 제안한다. 오버로드와 여러 파일 중 하나를 자동 선택하지 않는다.
- 파일 전체 impact가 16 KiB를 넘으면 discovery 후 선택한 USR만 재조회하도록 안내한다.
  원래 query/impact 문서의 `notFound`·`ambiguous`·한계와 분석 결과는 유지한다.

### Verified

- v8 공개 detekt/ktlint snapshot의 실패 선택자 3개를 후보 조회와 정확한 USR 재조회로 복구했다.
  이는 저장된 그래프의 선택자 검증이며 AI 효용 개선이나 현재 빌드 신선도 증명이 아니다.
  기존 실험 원문·점수는 변경하지 않았다.

## [0.15.0] - 2026-09-22

### Added

- 선택적 `compilerInputs` 설정으로 javac/KAPT/KSP task의 선언 파일·값 속성과 구현 artifact를
  실행 전후에 대조하는 v3 processor receipt를 추가했다. KSP의 ABI 요약과 별도로 원본 library도
  추적하고, 정규화된 cache가 복원한 옛 JAR bytes·실패 빌드의 완료 기록은 거부한다.
- snapshot의 processor 출력에 실제 class bytes·JVM ID·선택된 class root가 일치하는 선언 귀속을
  보존한다. source basename·resource로 선언을 추측하거나 호출 간선·보존·synthesized를 바꾸지 않는다.
  공개 metadata는 `coverage: gradle-declared-task-inputs`, `complete: false`로 관찰 범위를 명시한다.

### Fixed

- JVM snapshot이 Java 소스가 없는 의존 프로젝트의 정상적인 빈 class 출력을 누락된 필수 입력으로
  거부하던 문제를 수정했다. Gradle이 선언한 프로젝트 class 디렉터리만 부재까지 추적하며, 같은 내용의
  빈 외부 출력도 위치 식별자로 구분한다. 임의 라이브러리 누락은 계속 실패하고 출력 파일이 생기거나 바뀌면 stale이 된다.

### Verified

- 보존된 Now in Android 산출물을 복원해 `RealAgpRJarCliTest` 2개를 skip 없이 재실행했다.
  실제 R.jar root의 witness 누락 거부·추가 후 matched·bytes 변조 시 stale 계약을 유지한다.

## [0.14.0] - 2026-09-21

### Added

- `snapshot --processor-output-config`와 Gradle snapshot task의 `processorOutputConfigs`가
  javac/KAPT/KSP의 완료된 v2 output receipt를 입력·artifact·scope·raw·출력 bytes와 대조하고
  `processorOutputs`에 보존한다. API 생성과 callback 중 직접 쓰기를 구분하며, 그래프 간선·
  synthesized·보존·의존성 판정은 바꾸지 않는다. source-only `processorGenerations`와 별도다.
- 선택적 Gradle adapter가 native processor task의 출력에 raw sidecar와 명시한 직접 쓰기 파일을
  추가한다. 실제 javac17/KAPT2.4.10/KSP2.3.12의 build cache 복원과 configuration cache 재사용,
  4종 출력·stale·실패 빌드를 검증한다. 모든 compiler 입력의 완전성을 주장하지 않는다.
- 버전·라이선스·collector JAR·runner·cache adapter를 포함한 별도 collector ZIP을
  GitHub release 산출물과 SHA256SUMS에 추가한다. CLI/plugin runtime 의존성은 늘리지 않는다.
  두 번의 재현 빌드와 압축 해제한 독립 javac 소비 프로젝트로 설치 계약을 검사한다.

### Changed

- standalone runner의 새 receipt는 제품과 같은 content fingerprint를 쓰는 v2다. 성공 뒤에는
  후속 Gradle 작업이 cache key를 재사용할 수 있게 token을 보존한다. token은 성공 증거가 아니다.
  기존 v1 receipt의 독립 `verify`는 유지하며 snapshot에 연결하려면 v2로 다시 수집한다.

## [0.13.0] - 2026-09-20

### Added

- `dependencies --baseline`·`--suppress`·`--write-baseline`과 Gradle의 dependencyBaseline/
  dependencySuppress·baselineOutput을 제공한다. 좌표·버전·scope·제안·클래스 근거를 정확히
  지문화하고 UTC 만료일과 입력 변경을 검사한다. capture는 필터 전 관찰을 저장하며,
  Gradle의 capture 설정은 strict를 비활성화하지 않는다. 잘못된 입력은 덮어쓰지 않는다.
- 선택적 소스 빌드 collector `javac-processors`가 JSR-269 Filer의 source 생성/close와
  실제 processor artifact를 관찰한다. 완료된 compiler/generated-source receipt와 일치한
  근거만 snapshot의 `processorGenerations`에 보존한다. 캐시·변조·실패·processing 비활성화
  경계를 검증하며, 귀속만으로 참조 간선·reachability·dependency unused 판정을 바꾸지 않는다.
  KAPT/KSP·직접 filesystem·class/resource 출력은 범위 밖이다.

### Changed

- 모든 `bridges` transport의 `generatedAt`을 문서 추출 시각으로 통일하고, 읽은 source의
  최신 filesystem mtime은 선택적 `sourceModifiedAt`에 기록한다. 빈 입력은 mtime을 생략한다.
  0.12.0 이하의 기본 generatedAt은 source mtime이었다. 두 시각 모두 compiler freshness
  또는 앱 실행 완전성을 증명하지 않는다.
- 새 processor 사용 문서를 CLI ZIP/TAR에 포함하고 선택적 collector의 소스 빌드 범위를 명시한다.

### Fixed

- compiler collector의 프로젝트·source 경로 별칭을 정규화해 macOS의 반복 javac 빌드에서
  같은 `/var`·`/private/var` 입력을 서로 다른 프로젝트 경계로 오인하지 않는다.

## [0.12.0] - 2026-09-20

### Added

- `dependencies --library`는 JVM Signature·Kotlin metadata·inline 본문으로 `api`/`implementation`
  배치를 검토하고, `--resolved-dependencies`는 실제 compile classpath에서 미선언 전이 의존성의
  소유자와 참조 타입을 보고한다. main/test 사용을 분리하고 모든 6개 보고 형식에 같은 근거·한계를
  유지한다. 모호한 소유권·미해석 API의 부재를 삭제나 scope 축소 근거로 삼지 않는다.
- Gradle의 `kartographDependencies` 및 Android `kartographDependencies<Variant>`가 public
  provider/Artifact API로 컴파일 출력을 연결한다. 선택적 test 컴파일, configuration cache,
  class/scope 입력 변경에 따른 무효화와 report를 쓴 뒤 strict 실패를 지원한다. 빌드 파일 자동
  수정·processor별 생성 코드 귀속은 제공하지 않는다.

### Fixed

- `bridges --target react-native`의 일반 RN/Expo 수신 측 스캔을 복원한다. 0.11.0의 RN 이벤트
  옵션 검증이 일반 v1 필터까지 Flutter로 제한해 정상 명령을 코드 64로 거부하던 회귀를 고쳤다.
  Basic/EventChannel·RN 이벤트 문서의 target 구분과 잘못된 옵션 거부는 유지한다.
- 의존성 분석 사용 문서를 CLI ZIP/TAR에 포함하고, README가 가리키는 문서가 실제 아카이브에
  존재하는지 릴리스 게이트에서 검사한다.
- dependency 참조 스캐너가 descriptor에서 지워진 제네릭 타입을 classfile Signature에서 복원해
  실제 컴파일 의존성이 미사용으로 보고되는 경우를 막는다. AAR의 class/embedded JAR를 읽고,
  손상된 내부 ZIP과 과도하게 깊은 Signature는 부분 판정 없이 거부한다.

## [0.11.0] - 2026-09-20

### Added

- Accept isthmus external-retentions v0 in `dead --external-retentions`, retain exact JVM nodes and
  their owning types, and preserve original bridge caller evidence in `--explain` and snapshots.
  Unmatched IDs and malformed documents fail without partial application.
- Export core React Native global event emissions with `bridges --rn-events`, using a separate
  v2 transport. Dynamic names and unsupported emitter bindings remain explicit limitations.

- `dead`가 누락된 보존 입력을 `input-hint` 진단으로 보고한다. keep/consumer rule이나 dependency classpath
  입력이 전달되지 않았거나 manifest가 component 보존 근거를 만들지 못한 채 finding이 보고되면
  text/gradle/github-actions/sarif/json/markdown 형식에 측정 사실과 과소계측 가능성을 함께 싣는다.
  JSON은 `inputHints`(id·message)로 노출한다. hint는 finding이 아니므로 strict 판정·baseline·종료 코드에
  관여하지 않고, finding이 0건이면 보고하지 않는다. Gradle plugin의 report도 같은 신호를 공유한다.
- `dead`가 `--runtime-classes`(줄 단위 class 목록)와 `--coverage`(JaCoCo/Kover XML)로 사용자가 제공한 런타임
  근거를 선택 입력으로 받는다. 관측된 class의 finding은 JSON·SARIF·markdown `confidence`가
  `runtime-observed`로 표시된다. 커버리지를 수집·실행하지 않고 class 단위로만 판정하며, finding·strict·
  종료 코드·baseline은 바뀌지 않는다.
- `dependencies` 명령이 선언 의존성 목록(TSV: coordinate·scope·artifact)과 classfile 참조를 대조해
  참조가 없는 dependency를 `unused-dependency`로 text/JSON에 보고한다. 참조는 descriptor·annotation
  (type-use 포함)·invokedynamic(handle descriptor 포함)·LDC/ConstantDynamic·지역 변수·module
  uses/provides까지 독립 스캐너로 모은다. `--strict`는 finding이 있으면 exit 1이고, processor·
  runtime-only와 test root 없는 test scope는 판정하지 않고 개수만 알린다. artifact는 project-relative
  경로를 그대로, 절대경로는 파일 이름만 출력하며, 판정은 dependency 삭제 승인이 아니다.

## [0.10.2] - 2026-09-18

### Added

- `dead`가 보존 근거를 하나도 만들지 않은 keep rule을 `unmatched-keep-rule` 입력 진단으로 보고한다.
  CLI와 Gradle plugin의 text/gradle/github-actions/sarif/json/markdown 보고에 규칙의 파일·줄 근거를 싣고,
  JSON은 `unmatchedKeepRules` 필드로 노출한다. `-keepnames`·`allowshrinking`처럼 root를 만들지 않는
  지시자는 대상이 아니며, unmatched는 규칙 삭제 승인이 아니다.

### Fixed

- `bridges`의 Expo Modules DSL 스캔이 세 가지 형태를 놓치던 공백을 메운다.
  중첩 제네릭 인자(`AsyncFunction<List<String>>`), 완전 정규화된 `ModuleDefinition`
  래퍼(`expo.modules.kotlin.modules.ModuleDefinition { }`), 수신자 한정 호출
  (`this.AsyncFunction`)이 모두 스캔에서 투명해 사실과 limitation 없이 사라졌다.
  제네릭 절은 탐욕 일치가 비교식 안의 `>`를 호출 앵커로 잘못 고정할 수 있어
  지연 일치로 바꿨고, `View<T>` 제네릭 형태를 새로 인식한다.
  0.10.1에서 인식되던 Expo 사실의 출력은 그대로다 — `expo-haptics@14.1.4`
  재스캔에서 module-export·method-handle 4건이 동일하게 나옴을 확인했다.

## [0.10.1] - 2026-09-18

### Added

- `bridges --target flutter --events`가 Flutter EventChannel의 `setStreamHandler`에서 stream-handle 사실을
  별도 v2 문서(`transport: event-channel`)로 보낸다. `--messages`와는 별도 문서라 동시 사용은 usage 오류다.
  송신 개념이 없는 EventChannel에는 send 계열 limitation을 만들지 않는다.
- `bridges`가 Expo Modules DSL을 인식한다. `import expo.modules.kotlin.modules.Module`이 있는 Kotlin
  파일에서 `class X : Module()` 선언을 모듈로 보고 `ModuleDefinition { }` 본문의 `Name(…)`·`View(…)`·
  `Function`/`AsyncFunction`을 읽는다. `module-export`·`component-export`에는 `"mechanism": "expo"`를
  싣고 메서드 사실에는 싣지 않는다. `Name`이 없으면 런타임 규칙과 같은 클래스명 폴백을 쓰고, 정의
  본문이 스캔 범위 밖이면 클래스명을 `dynamic` 근거로 남긴다. 컴포넌트 이름 경계는 뷰 클래스가 아니라
  모듈 이름이다(`requireNativeViewManager(moduleName)`). Java 파일은 receiver DSL을 쓸 수 없어
  import만 보이면 `unscanned-expo-java:`로 알린다.

### Changed

- `snapshot`의 입력 fingerprint가 파일 digest를 최대 4개 worker에서 병렬로 계산한다. digest 값·입력 순서·before/after 검증
  계약·내용 해시 정책은 그대로이며, spool 예산은 입력 순서대로 크기 기준으로 선할당한 뒤 실제 크기로 정산한다.
  dependency JAR이 많은 입력에서 fingerprint 단계 시간이 줄고, class 하나를 바꾼 뒤의 캡처 비율 측정은 `docs/INDEX-CACHE.md`에 기록한다.

### Fixed

- `bridges`가 `setMethodCallHandler`/`setStreamHandler`/`send` 수신자의 `!!`·`?.`을 인식한다.
  nullable 필드의 `channel!!.setXxx` 패턴을 놓쳐 경계가 비던 사례를 고친다. JNI/native interop
  파일은 정적 채널 키로 귀속할 수 없어 fact 대신 파일 수준 `unscanned-ffi-interop` limitation으로 보고한다.
- `snapshot` 입력 fingerprint의 병렬 digest에서 (1) 계획 단계의 symlink 검사와 파일 열기 사이가 벌어져 그 사이 symlink로
  바뀐 파일을 따라갈 수 있던 문제를 열기·크기 읽기에 `NOFOLLOW_LINKS`를 적용해 막고, (2) 캡처가 interrupt되면 worker가 만든
  임시 spool이 남을 수 있던 문제를 scope가 worker 종료를 기다린 뒤 닫도록 고치며, (3) 크기를 읽지 못한 JAR이 예산을
  차지하지 않은 채 spool돼 총 상한(256 MiB)을 넘길 수 있던 문제를, 속성을 읽지 못한 JAR을 그 pass의 spool 대상에서 빼는 것으로 막는다. 기록되는 fingerprint 값은 바뀌지 않는다.
- `impact`의 계약 확장이 dispatch 모델의 호출자→구현 후보 간선(`origin = dispatchModel`)을 변경 method의 override로 잘못
  따르던 문제를 고친다. 변경 method가 호출하는 인터페이스의 구현체가 영향 후보에 오르고, 실제 호출 사슬 대신 후보 경로가
  witness로 선택돼 `transitive` 호출자가 `structural`로 분류되던 사례가 있었다. 호출자 방향의 dispatch 후보 사용은 그대로다.
- `impact`가 멤버→소유 class `reference` 간선(도달성용)을 사용 관계로 읽어, `<clinit>`이나 class 정점에 닿는 변경이 같은 class의
  모든 멤버로 퍼지던 문제를 고친다. 다른 class와 중첩 class에서 오는 참조는 계속 영향에 포함한다.

## [0.10.0] - 2026-09-16

### Added

- `why <symbol>` 명령은 `dead`와 같은 입력·분석으로 한 선언의 보존 상태, 파일:줄 근거, 보존 root부터의 대표 경로,
  직접 caller, test-only 표시, 측정된 신뢰도 등급을 한 번에 출력한다. 답은 도달성 사실이며 삭제 승인이 아니다.
- JSON·SARIF·markdown finding에 `confidence` 등급(`static`/`needs-runtime-review`/`unmeasured`)을 싣는다.
  같은 소스 파일에서 측정된 미해결 runtime 채널 관측에서 산출하며 text/gradle/github-actions 출력은 바꾸지 않는다.
- `dead --suppress <file>`은 `expires` 날짜가 있는 finding 억제를 fail-closed로 읽고, 만료된 억제는 풀어
  machine report의 `expiredSuppressions`에 남긴다. `markdown` 리포트 형식과 dead JSON을 PR 코멘트 본문으로 바꾸는
  `Scripts/render-dead-comment.py`를 추가한다.
- Gradle plugin의 Android **application** variant 자동 캡처가 `process<Variant>Resources`의 생성 `R.jar`를
  resource producer witness로 덮는다. 0.9.0에서 `unwitnessed-class-root`로 거부되던 application snapshot이
  `matched`가 되고, R.jar가 바뀌면 `stale`로 보고한다. task 실패 시 witness를 기록하지 않는다.
- `bridges --target flutter --messages`는 Kotlin/JVM Flutter `BasicMessageChannel`의 실제 `setMessageHandler`
  등록을 선택적으로 bridge-facts v2로 내보낸다. literal 채널 이름과 immutable alias만 해석하고,
  Kotlin `var`·Java non-final·재할당된 이름은 dynamic으로 남긴다. Kotlin 송신 호출은 fact로 만들지 않고
  `unscanned-message-sends` limitation으로 센다.
- `--graph-file`을 주면 관찰 위치를 compiler snapshot의 실제 Kotlin/JVM 함수·메서드 정점과 조인할 때만
  `symbol.usr`를 붙이고, graph가 없거나 위치가 모호하면 `missing-handler-usrs` limitation을 보존한다.
  MethodChannel 사실도 같은 compiler 신원을 사용하며, snapshot 신선도를 먼저 확인해 stale 그래프의 USR은 버리고
  `graph-file-freshness-<status>` limitation으로 기록한다.

### Fixed

- bridge-facts v1과 v2의 위치 열을 교환 계약대로 UTF-8 byte offset으로 계산한다. 다국어 주석이 앞에 있는
  줄에서 문자 인덱스를 열로 내던 문제를 바로잡는다.
- Kotlin 함수 범위 계산에서 주석과 `when` 제어문을 제외해 compiler symbol 귀속이 오염되지 않게 한다.

### Changed

- ASM 9.10.1과 kotlin-metadata-jvm 2.4.20으로 갱신하고 의존성 검증 metadata를 다시 생성했다.
- `bridges` 문서의 `project`는 `.` 대신 입력 root의 canonical POSIX 절대경로다. 모든 위치는 여전히 project-relative다.

## [0.9.0] - 2026-09-14

### Added

- `snapshot --index-cache`와 Gradle snapshot의 선택적 로컬 파싱 캐시를 추가한다.
  현재 class 내용·분석기 구현·dependency JAR 내용을 확인하고, 변경되지 않은 파싱 사실만 재사용한다.
  입력 신선도·전체 분석·보존·source 위치는 다시 계산하며 cold/warm/변경 입력의 snapshot 일치를 검증한다.
- `mcp`는 MCP 2025-11-25 stdio에서 `query_symbol`·`impact`·`freshness`를 제공한다.
  시작 시 고정한 snapshot과 CLI의 분석·보고 경로를 공유하고, 크기 제한·취소·EOF·실제 Claude 연결을 검증한다.
- 정확히 선택되는 Java private/final instance helper와 Kotlin object/companion의 String/Class 반환값을 추적한다.
  실제 실행 표본 4건의 누락 경로를 복원하고 override·receiver 상태·재귀·분석 한도는 unknown으로 유지한다.
- 저장·읽기 한도를 명시하는 `--snapshot-max-mib`와 Gradle `snapshotMaxMiB`를 추가한다.
  기본 64 MiB를 유지하며 최대 128 MiB까지 허용한다. 실제 fastjson2 core/test의 88.7 MB snapshot을 검증한다.

### Fixed

- 공개 Gradle 표본의 classpath 수집을 task 실행 시점으로 옮겨 configuration lifecycle 오류를 해결한다.
- 자기 분석 smoke의 정점 수 검증을 독립 JSON 출력과 대조해 제품 성장에도 정점과 간선을 구분한다.
- 저장할 수 없는 큰 JAR 때문에 cache population을 반복하지 않고, 캐시 사용 불가 통계를 class당 한 번 센다.
- 실제 class header의 FINAL을 사용하고, 런타임 입력에 기여하지 않는 helper 호출이 분석 예산을 소진하지 않도록 한다.
- MCP의 실패한 source-style selector에 실제 USR 후보를 제공하며 overload를 임의 선택하지 않는다.
  도구 내용은 16 KiB로 제한하고, 페이지·경로 예산 조정을 명시해 클라이언트 표시 한도에 대응한다.
- `impact --summary-limit`와 MCP `summaryLimit`으로 전역 요약 항목 수를 별도로 제한한다.
  원래·반환·생략 수를 기록하며 선택·영향 후보·경로·분석 한계를 바꾸지 않는다.
- MCP worker의 치명적 오류가 영구 busy 상태를 남기지 않도록 종료하며,
  graph 명령의 손상·누락 classpath 입력을 정제된 도구 오류 2로 반환한다.
- 중첩 JSON 출력의 임시 문자열 생성을 줄이며 기존 출력 바이트와 문자 식별자를 보존한다.

### Changed

- `QuerySnapshotCodec`의 기본 render도 reader와 같은 64 MiB 한도를 적용한다.
  큰 문서를 직접 만드는 API 호출은 명시적 한도 overload를 사용하며 최대 128 MiB까지 허용한다.
- AI 변경 전 조사 48회와 원본 입력 감사를 공개한다. 일반적인 AI 생산성 향상은 미입증으로 유지한다.

## [0.8.0] - 2026-09-13

### Added

- 선택적 javac/Kotlin 2.4.10 상수 참조와 javac Dagger 2.59 선택 binding collector를 compiler 증거와 함께 snapshot에 연결하고, 불완전한 참조를 계량한다.

- Gradle JVM main/test의 `kartographSnapshot` 자동 수집과 checkout 전용 external-input bindings를 추가한다.
  Kotlin/Java별 실제 소스·출력·compiler 증거를 확인하며, 누락된 compiler와 정상 `NO-SOURCE`를 구분한다.
- Android variant의 `kartographSnapshot<Variant>`는 main/unit-test compiler 증거와 SDK·manifest·XML 입력을 함께 캡처한다.
  실제 AGP 9.3.2 배포 JAR 소비, configuration cache, 같은 수정 시각의 내용 변경과 테스트 소스 삭제를 검증한다.
- compiler task의 source/class/config/classpath 지문을 snapshot에 연결하고 `verify-snapshot`과 CI helper에서
  내용 일치·stale·미검증 상태를 구분한다. Java/Kotlin producer의 실패·캐시·경로 이동과 Android 입력을 검증한다.
- `impact`: 수정 예정 심볼/파일의 직접·간접 영향 후보를 시점별 경로·간선 출처와 함께 보고한다.
  base/current snapshot, 삭제·rename 경로, interface override 계약, CI helper와 에이전트 스킬을 연결한다.
- `snapshot --include-paths --revision --scope`와 lossless `--compact` v2. 기존 v1/query 필드 호환성을 유지한다.
- 공개 OkHttp/AnkiDroid 실제 회귀, runtime 영향 경로, 읽기 전용 AI 질의 비교와 재현·채점 스크립트.
- 프로젝트 static helper의 String/Class 반환값과 불변 인자를 제한적으로 전파해 reflection 대상을 연결한다.
  Java/Kotlin 실행·overload·unknown·재귀·분석량 제한 회귀와 SearchDeadCode/R8 비교 실험을 추가한다.
- `snapshot`과 `query --graph-file`: 그래프·보존 근거·baseline 상태·계량 한계를 저장하고 원본 입력 없이 질의한다.
- `--generated-classes`와 Gradle `generatedClassRoots`: 생성 전용 컴파일 입력의 출처로 선언을 구분하며 정점·간선은 유지한다.

### Changed

- CLI `impact`의 기본 정렬을 `review`로 바꿔 직접·간접 경로를 구조적·미확인 경로보다 먼저 보여준다.
  전체 후보·경로·한계는 유지한다. 기존 순서는 `--sort usr`로 선택하며 분석 API의 기본 정렬은 바뀌지 않는다.

### Fixed

- 일시적인 입력 변경·관측 실패가 해결된 뒤 이전 rejection 기록에 묶이지 않고 compiler 증거를 다시 생성한다.
- 빌드 실패 후 비어 있는 witness 출력이 `UP-TO-DATE`로 고정되는 문제를 복구하고, 실패 기록 삭제를 빌드 종료 시점으로 옮긴다.
- included build 하위 convention 모듈의 설정·소스 변경도 snapshot 입력으로 추적한다.
- 자동 compiler 관측의 미지원 입력으로 일반 빌드를 중단하지 않고, 스냅샷 요청에서 증거 거부 사유를 보고한다.
- 하위 프로젝트의 상위 설정 파일 연결과 설정 변경 추적을 보완하고, 설정 파일·catalog·build logic의 추가도 감지한다.
- Kotlin compiler witness의 toolchain 연결이 기존 bytecode target을 덮어쓰지 않도록 보존한다.
  JDK 21 / target 17 Android 일반 빌드와 자동 수집을 비교해 검증한다.
- static field의 String/Class 초기값·재대입·reflection get/set에서 알려진 런타임 후보를 복원한다.
  필드의 불확실성을 유지하며, classfile String 상수·상속/숨김·분석 한도와 Java/Kotlin 실행 대조를 검증한다.
- 프로젝트와 dependency에 정의된 반복·중첩 Compose multipreview 어노테이션을 보존 근거로 연결한다.
- JAR의 2초 시각 정밀도 안에서 발생한 차이는 stale로 단정하지 않고 freshness unknown으로 표시한다.

## [0.7.0] - 2026-09-09

### Added

- Java/Kotlin 4개 표본에서 실제 실행과 SootUp CHA/RTA·WALA 0-1-CFA·kartograph를 대조하는 정밀도 실험과 CI 검증.
- 호출 signature로 선택하는 JDK runtime 모델 목록과 외부 호출의 모델 ID.
- 알려진 reflection method/field 접근을 연결하고 미해결 호출을 각각 계량한다.
- 실제 Dagger SPI의 qualifier별 선택 binding을 JVM 선언에 연결하고 stale 입력·누락 binding을 거부하는 독립 실험.
- DroidBench 패턴의 Java 실행·kartograph 후보·R8 보존/실행을 분리하는 6개 차등 회귀 계약과 CI 검증.

## [0.6.0] - 2026-09-08

### Added

- 인라인 전 javac/FIR 상수 참조를 같은 입력의 그래프에 연결하는 독립 비교 실험과 CI 검증을 추가한다(제품 자동 보강은 아님).
- 제한된 메서드 내 값 추적에 기반한 class 로딩·reflection 생성자, 외부 상위 타입 dispatch와 서비스 provider 입력을 그래프에 연결한다.
- 간선 출처와 외부 호출 해석 상태를 JSON에 기록하고 Java/Kotlin 실행 코퍼스 13건을 CI에서 검증한다.
- 외부 호출 사실을 앱 선언과 분리해 그래프에 보존하고 런타임 사각지대와 상수 참조 손실을 query에 계량한다.
- Java/Kotlin 런타임·상수·DI 반례를 고정한 compiler 코퍼스를 추가한다.

### Fixed

- 인코딩된 어노테이션 기본값의 class 참조를 도달성에 반영한다.
- 상수 field query가 보존된 owner와 달리 unreachable로 보이지 않도록 INLINE_CONSTANT 근거를 공유한다.

## [0.5.0] - 2026-09-08

### Added

- AGP 8.7.3 / Gradle 8.10.2 / KGP 2.0.21의 영구 소비 프로젝트와 CI 게이트.
- 빌드 의존성 SHA256 검증, Dependabot, 배포 runtime CycloneDX SBOM과 SHA256SUMS.

### Changed

- 세 source 스캐너가 실제 하위 디렉터리 가지치기를 공유한다. XML 위치 계산은 파일당 줄 목록을 한 번 읽는다.
- query는 class 인덱싱에서 수집한 runtime 관측값을 재사용한다.

### Fixed

- source 탐색과 skill 설치가 프로젝트 밖 심볼릭 링크를 따라 읽거나 쓰지 않도록 경계를 검사한다.
- 컴파일 선언이 없는 입력을 실패로 처리하고 명령에 맞지 않거나 다른 모드에서 무시되는 CLI 옵션을 거부한다.
- source 신선도를 대응 class별로 비교하고 대응 불가능한 source는 계량 한계로 알린다.
- 누락된 JDK 상위 타입을 오류에 안내하고 bridges 시각은 반복 가능한 source snapshot 시각으로 기록한다.

## [0.4.1] - 2026-09-07

### Changed

- README를 영어로 쓰고 한국어 문서를 `README.ko.md`로 분리했다. 배포본에도 두 문서를 함께 싣는다.
- Portal 승인 가이드에 따라 Gradle 기능 호환성을 게시 메타데이터에 선언한다. configuration cache
  재사용은 Android fixture 게이트로 검증한 그대로 `true`로 게시한다.

## [0.4.0] - 2026-09-07

### Added

- Gradle plugin이 AGP 8.7 이상을 지원한다. 사용하는 Variant API가 AGP 7대부터 stable이라
  컴파일 기준을 `gradle-api:8.7.3`으로 낮췄고, AGP 8.7 + Gradle 8.10 조합에서 dead·graph task와
  configuration cache 재사용을 검증했다. AGP 9.x는 기존 Android fixture 게이트로 계속 검증한다.

### Fixed

- `query`·`bridges`·`skill`·`cycles`·`rules`·`metrics`가 `--help`/`-h`를 사용 오류(`64`)로
  거부하지 않고 각 명령의 사용법을 출력한 뒤 성공(`0`)으로 끝낸다. `baseline --help`는 `dead`
  도움말 대신 baseline 전용 사용법을 출력한다.
- variant keep 입력에 포함된 빌드 중간 산출물이 아직 생성되지 않았으면 건너뛴다(AGP 8의
  `default_proguard_files`). 빌드 출력 디렉터리 아래의 누락만 건너뛰고, 소스 트리 경로의
  누락은 기존대로 실패로 둔다.

## [0.3.1] - 2026-09-07

### Fixed

- 표준 출력을 UTF-8로 고정하고 machine 문서의 비ASCII를 `\uXXXX`로 escape한다. 이전에는 `LC_ALL=C` 같은
  비UTF-8 로케일에서 비ASCII 식별자가 `?`로 뭉개져 서로 다른 선언이 같은 `usr`로 붕괴하고, 교환 문서의
  join key가 조용히 충돌했다. 이제 로케일과 무관하게 같은 바이트를 낸다.
- 출력 쓰기가 실패하면(디스크 부족, 소비자 조기 종료 등) 잘린 문서를 성공으로 보고하지 않고 도구 실패(`2`)로
  끝낸다. 예기치 못한 실패도 JVM 기본 종료 코드 `1`(strict finding과 같은 값) 대신 `2`로 수렴시킨다.
- `SourceFile` attribute에 담긴 경로를 그래프에 넣기 전에 파일 이름 성분으로 줄인다. 이전에는 후처리 도구가
  남긴 절대경로가 `dead` report·`query`·저장소에 커밋되는 baseline 지문으로 그대로 흘러갈 수 있었다.
  **호환성**: 표준 javac/kotlinc 산출물처럼 attribute가 파일 이름만 담은 경우 지문은 그대로이므로 기존
  baseline이 계속 동작한다. attribute에 경로가 담겼던 항목만 지문이 바뀌므로(그 지문에는 절대경로가 새겨져
  있었다) 해당 baseline은 다시 생성해야 한다.
- `--include-paths`(Gradle `includeSourcePaths`)가 이름만 같은 무관한 파일을 사실로 확정하지 않는다. 선언의
  package가 후보 파일 디렉터리의 suffix일 때만 확정하고, 아니면 `unresolved-source-paths`로 센다.
- 프로젝트 source 탐색의 가지치기 규칙을 세 스캐너가 공유하는 한 벌로 통일한다. 이전에는
  `SourcePathIndex`·`BridgeFactScanner`·`RuntimeLimitationScanner`가 각자 다른 제외 집합을 써서
  `.claude/`·`.omx/`의 예제 코드가 `bridges` 교환 문서로 수확되거나 staleness 집계에 섞일 수 있었다.
  정본(`docs/DECISION-truth-source.md`) 6종에 빌드 산출물·도구 캐시를 더한 집합만 제외하며, 기존에
  제외하던 디렉터리를 다시 순회하지는 않으므로 종전 출력은 그대로다.
- 그래프 교환 문서가 경로 해석을 요청하지 않아도 `missing-source-paths`를 보고한다. 개수만으로 계산되는
  한계를 opt-in 뒤에 숨기지 않는다.

### Changed

- 게시되는 Gradle plugin POM에 name·description·url·licenses·developers·scm을 채우고, release 준비 검증이
  이 메타데이터와 배포본 문서의 버전 일관성을 함께 확인한다.
- README·`docs/PR-CHECK.md`·`docs/LIMITATIONS.md`·`docs/PUBLIC-VALIDATION.md`의 버전 하드코딩과 옛 서술을
  현행화한다.

## [0.3.0] - 2026-09-06

### Added

- BINARY/RUNTIME 보존 어노테이션의 명시적 값·배열·enum·중첩 어노테이션과 parameter annotation의 class 참조를
  도달성 간선으로 복원한다. 어노테이션 인자로만 참조되는 선언(예: BINARY 보존 `@PreviewParameter(X::class)`)이
  미사용 오탐이 되지 않는다.
- 중첩 class를 바깥 container와 참조 간선으로 연결해, 사용되는 중첩 class를 둔 바깥 선언이 미사용으로 보고되지 않는다.
- 바로 앞 constant 문자열 인자를 사용하는 `Class.forName`의 대상을 class 참조로 해석한다.
- 단일 file facade와 multi-file part의 도달 불가 top-level 함수를 finding으로 보고한다. inline 함수·property
  접근자·native·컴파일 상수·launcher `main`과 class-only 모드의 private top-level은 보수적으로 제외한다.
- `dead --test-classes <root>`는 production root에서는 도달 불가하나 test 코드에서만 도달되는 finding을
  `(used only by tests)`로 표시한다. 표시는 억제·삭제 승인이 아니며 strict/baseline에서 여전히 finding으로 계산한다.
  현재 CLI만 지원하고 Gradle plugin의 test variant 연결은 후속 작업이다.
- `graph --format json`은 정점의 `usr`·`qualifiedName`·`kind`·`accessibility`·`location`과 간선을 담은 결정적
  교환 문서(`code-graph` v1)를 만든다. `--include-paths --project <dir>`는 class debug 정보의 source file 이름을
  project 안에서 유일하게 일치하는 파일의 상대경로로 해석하고, 각 위치의 출처를 `pathKind`로, 확정하지 못한 수를
  `unresolved-source-paths`·`missing-source-paths` 한계로 함께 싣는다. 절대경로는 내보내지 않으며 `dot` 출력은
  기존대로 위치를 담지 않는다.
- Gradle plugin이 Android variant마다 `kartographGraph<Variant>` task를 등록해 같은 `code-graph` v1 문서를
  `build/reports/kartograph/<variant>-graph.json`에 쓴다. `kartograph { includeSourcePaths.set(true) }`는
  CLI의 `--include-paths`와 같이 opt-in이며, 켰을 때만 선언되지 않은 project source를 읽으므로 그때만
  task output을 재사용하지 않는다.

### Changed

- source 경로 인덱스와 그래프 경로 해석을 `index` 모듈의 공개 `SourcePathIndex`로 옮겨 CLI와 Gradle plugin이
  같은 해석 규칙을 쓴다. 동작과 출력은 그대로다.
- `--since`는 debug 정보가 basename만 남긴 경우 프로젝트의 유일 source 경로로 대조해 모호한 매칭을 줄인다.
  인덱스 실패나 모호한 basename은 기존 보수적 매칭으로 폴백한다.
- `dead` limitations에서 해결된 enclosing declaration 한계를 제거하고, annotation 값 한계는 SOURCE 보존
  어노테이션·기본값으로 좁혀 유지하며, top-level property 미보고 한계를 추가한다.

## [0.2.0] - 2026-09-05

### Added

- CLI/Gradle opt-in private member findings, 공통 보고 정책, 같은 모드의 baseline/query와 member keep-rule 보존.
- Gradle hierarchy에 Android SDK boot classpath를 포함해 framework 상속 keep 규칙을 해석한다.
- 기준 Git commit의 baseline만 적용하는 `Scripts/check-pr.py`와 실제 Git/javac 기반 PR 회귀 검증.
  전체 그래프를 검사해 수정하지 않은 파일의 새 미사용도 보고하고 PR의 baseline 추가로 숨기지 않는다.
- 고정 공개 nowinandroid 빌드의 Hilt 생성 코드 회귀 verifier와 재현 절차.

### Fixed

- Dagger/Hilt 생성 annotation과 실제 enclosing 관계를 반영해 생성 Java와 중첩 class를 일반 미사용 코드로 보고하지 않는다.
- Hilt application에서 정확한 generated component sibling을 보존하고 버전 테스트는 단일 VERSION과 대조한다.

## [0.1.1] - 2026-09-05

### Fixed

- 일반적인 비보존 ProGuard/R8 directive와 한 줄 `-keepclasseswithmembers` 규칙을 처리한다.
- manifest `meta-data`의 class 참조를 보존하고 unresolved placeholder 입력은 명시적으로 실패한다.
- reachable member에서 owner class로 이어지는 보수적 참조와 `javax` 등 JDK hierarchy를 복원한다.
- Gradle baseline capture, qualified-name architecture 설명과 Android generated-class 회귀 검증을 정렬한다.
- 실제 Moshi KSP nested adapter를 코퍼스에 추가하고 CI에서 Gradle wrapper와 Node 24 action을 검증한다.

## [0.1.0] - 2026-09-04

### Added

- JVM bytecode와 Kotlin metadata에서 class, method, field 및 근거가 있는 dependency graph 생성
- manifest, Android resource, keep rule, annotation과 runtime callback을 반영한 explainable reachability
- baseline, Git 변경 범위, Gradle/GitHub Actions/SARIF/sorted JSON report
- symbol query, isthmus bridge facts, 설치형 agent skill
- package/module cycle, YAML layer rule, Martin metric 분석
- Android variant별 분석 task를 제공하는 Gradle plugin

### Safety

- 결과는 graph에서 관찰한 도달성 상태이며 코드 삭제 승인이나 런타임 안전성 보장이 아니다.
- 동적 reflection, JNI, 런타임 등록과 불완전한 classpath 등 측정 가능한 한계를 결과에 포함한다.
- 잘린 class 입력은 정제된 도구 실패로 처리하고, activity alias와 FragmentContainerView 참조를 보존한다.
- JVM descriptor로 정확히 표현할 수 없는 member type wildcard keep rule은 파일·줄 오류로 거부한다.
- `bridge-facts`의 프로젝트와 위치를 상대경로로 제한하고 사용되지 않는 빈 test-support module을 제거했다.
- 배포본에 내장된 ASM과 Kotlin/JetBrains runtime dependency의 제3자 라이선스를 함께 제공한다.

[Unreleased]: https://github.com/ictechgy/kartograph/compare/v0.16.0...HEAD
[0.16.0]: https://github.com/ictechgy/kartograph/compare/v0.15.0...v0.16.0
[0.15.0]: https://github.com/ictechgy/kartograph/compare/v0.14.0...v0.15.0
[0.14.0]: https://github.com/ictechgy/kartograph/compare/v0.13.0...v0.14.0
[0.13.0]: https://github.com/ictechgy/kartograph/compare/v0.12.0...v0.13.0
[0.12.0]: https://github.com/ictechgy/kartograph/compare/v0.11.0...v0.12.0
[0.11.0]: https://github.com/ictechgy/kartograph/compare/v0.10.2...v0.11.0
[0.10.2]: https://github.com/ictechgy/kartograph/compare/v0.10.1...v0.10.2
[0.10.1]: https://github.com/ictechgy/kartograph/compare/v0.10.0...v0.10.1
[0.10.0]: https://github.com/ictechgy/kartograph/compare/v0.9.0...v0.10.0
[0.9.0]: https://github.com/ictechgy/kartograph/compare/v0.8.0...v0.9.0
[0.8.0]: https://github.com/ictechgy/kartograph/compare/v0.7.0...v0.8.0
[0.7.0]: https://github.com/ictechgy/kartograph/compare/v0.6.0...v0.7.0
[0.6.0]: https://github.com/ictechgy/kartograph/compare/v0.5.0...v0.6.0
[0.5.0]: https://github.com/ictechgy/kartograph/compare/v0.4.1...v0.5.0
[0.4.1]: https://github.com/ictechgy/kartograph/compare/v0.4.0...v0.4.1
[0.4.0]: https://github.com/ictechgy/kartograph/compare/v0.3.1...v0.4.0
[0.3.1]: https://github.com/ictechgy/kartograph/compare/v0.3.0...v0.3.1
[0.3.0]: https://github.com/ictechgy/kartograph/compare/v0.2.0...v0.3.0
[0.2.0]: https://github.com/ictechgy/kartograph/compare/v0.1.1...v0.2.0
[0.1.1]: https://github.com/ictechgy/kartograph/compare/v0.1.0...v0.1.1
[0.1.0]: https://github.com/ictechgy/kartograph/releases/tag/v0.1.0
