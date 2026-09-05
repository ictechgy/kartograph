# Gradle plugin 지침

이 디렉터리는 [공통 지침](../AGENTS.md)을 상속한다. 분석 엔진이 아니라 Gradle/AGP 입력 adapter다.

## Variant와 task

- AGP public Variant/Artifact API를 사용한다. 사용자 class output 경로를 추측해서 고정하지 않는다.
- `ScopedArtifacts.Scope.PROJECT`는 분석 그래프, `ALL`은 dependency hierarchy로 구분한다. Android SDK boot classpath도 유지한다.
- manifest/resource/namespace/class roots/keep rules를 provider와 선언된 task input으로 lazy 연결한다. configuration 단계에서 빌드·파일 탐색·dependency resolve를 수행하지 않는다.
- report 기록 후 strict 결과로 실패한다. baseline capture는 기존 baseline filter로 진단을 먼저 숨기지 않는다.
- 재귀 include처럼 실행 중 발견되는 입력 때문에 stale report를 재사용하지 않도록 한다. cache 관련 우회나 annotation 변경은 실제 configuration cache 재사용과 입력 변경으로 검증한다.

## 배포와 검증

- plugin ID는 [build.gradle.kts](build.gradle.kts)의 선언이 원천이며 현재 `io.github.ictechgy.kartograph`다. 버전은 루트 `VERSION`에서 읽는다.
- standalone plugin JAR의 분석/runtime 의존성 내장, POM 중복 의존성 제거, plugin descriptor, 라이선스/제3자 고지를 보존한다.
- [검증 workflow](../docs/AGENT-WORKFLOW.md)의 plugin/JDK/publication 게이트를 따른다. 실제 variant·report·strict·baseline·private member·configuration cache를 확인한다.
