# Changelog

이 프로젝트의 주목할 만한 변경은 이 파일에 기록한다. 형식은
[Keep a Changelog](https://keepachangelog.com/en/1.1.0/)를 따르고 버전은
[Semantic Versioning](https://semver.org/spec/v2.0.0.html)을 따른다.

## [Unreleased]

### Added

- 어노테이션 값·parameter annotation의 class 참조를 도달성 간선으로 복원한다. 어노테이션 인자로만 참조되는
  선언(예: `@PreviewParameter(X::class)`)이 미사용 오탐이 되지 않는다.
- 중첩 class를 바깥 container와 참조 간선으로 연결해, 사용되는 중첩 class를 둔 바깥 선언이 미사용으로 보고되지 않는다.
- 바로 앞 constant 문자열 인자를 사용하는 `Class.forName`의 대상을 class 참조로 해석한다.
- file facade의 도달 불가 top-level 함수를 finding으로 보고한다. inline 함수·property 접근자·native·
  launcher `main`과 class-only 모드의 private top-level은 보수적으로 제외한다.

### Changed

- `--since`는 debug 정보가 basename만 남긴 경우 프로젝트의 유일 source 경로로 대조해 모호한 매칭을 줄인다.
- 해결된 한계(annotation 값·enclosing declaration)를 `dead` limitations에서 제거하고, top-level property
  미보고 한계를 추가한다.

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

[Unreleased]: https://github.com/ictechgy/kartograph/compare/v0.2.0...HEAD
[0.2.0]: https://github.com/ictechgy/kartograph/compare/v0.1.1...v0.2.0
[0.1.1]: https://github.com/ictechgy/kartograph/compare/v0.1.0...v0.1.1
[0.1.0]: https://github.com/ictechgy/kartograph/releases/tag/v0.1.0
