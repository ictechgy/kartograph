# Changelog

이 프로젝트의 주목할 만한 변경은 이 파일에 기록한다. 형식은
[Keep a Changelog](https://keepachangelog.com/en/1.1.0/)를 따르고 버전은
[Semantic Versioning](https://semver.org/spec/v2.0.0.html)을 따른다.

## [Unreleased]

### Fixed

- source 탐색과 skill 설치가 프로젝트 밖 심볼릭 링크를 따라 읽거나 쓰지 않도록 경계를 검사한다.
- 컴파일 선언이 없는 입력을 실패로 처리하고 명령에 맞지 않거나 다른 모드에서 무시되는 CLI 옵션을 거부한다.
- source 신선도를 대응 class별로 비교하고 대응 불가능한 source는 계량 한계로 알린다.
- 누락된 JDK 상위 타입을 오류에 안내하고 bridges 시각은 반복 가능한 source snapshot 시각으로 기록한다.

### Changed

- 세 source 스캐너가 실제 하위 디렉터리 가지치기를 공유한다. XML 위치 계산은 파일당 줄 목록을 한 번 읽는다.
- query는 class 인덱싱에서 수집한 runtime 관측값을 재사용한다.

### Added

- AGP 8.7.3 / Gradle 8.10.2 / KGP 2.0.21의 영구 소비 프로젝트와 CI 게이트.
- 빌드 의존성 SHA256 검증, Dependabot, 배포 runtime CycloneDX SBOM과 SHA256SUMS.

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

[Unreleased]: https://github.com/ictechgy/kartograph/compare/v0.4.1...HEAD
[0.4.1]: https://github.com/ictechgy/kartograph/compare/v0.4.0...v0.4.1
[0.4.0]: https://github.com/ictechgy/kartograph/compare/v0.3.1...v0.4.0
[0.3.1]: https://github.com/ictechgy/kartograph/compare/v0.3.0...v0.3.1
[0.3.0]: https://github.com/ictechgy/kartograph/compare/v0.2.0...v0.3.0
[0.2.0]: https://github.com/ictechgy/kartograph/compare/v0.1.1...v0.2.0
[0.1.1]: https://github.com/ictechgy/kartograph/compare/v0.1.0...v0.1.1
[0.1.0]: https://github.com/ictechgy/kartograph/releases/tag/v0.1.0
