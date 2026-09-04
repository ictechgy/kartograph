# Changelog

이 프로젝트의 주목할 만한 변경은 이 파일에 기록한다. 형식은
[Keep a Changelog](https://keepachangelog.com/en/1.1.0/)를 따르고 버전은
[Semantic Versioning](https://semver.org/spec/v2.0.0.html)을 따른다.

## [Unreleased]

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

[Unreleased]: https://github.com/ictechgy/kartograph/compare/v0.1.0...HEAD
[0.1.0]: https://github.com/ictechgy/kartograph/releases/tag/v0.1.0
