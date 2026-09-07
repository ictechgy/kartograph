# 에이전트 작업·검증 workflow

공통 계약은 [AGENTS.md](../AGENTS.md)다. 이 문서는 검증·PR·릴리스가 작업 범위에 들어갈 때 읽는다.

## 작업을 끝내는 기준

분석은 증거와 한계를 답하면 완료다. 구현은 요청한 동작·관련 회귀 검증·남은 제약을 확인하면 완료다.
단순한 가정 때문에 멈추지 말고 승인된 작업을 진행하되, 권한·비밀정보·제품 범위를 확대해야 하면 구체적인 대상과 이유를 묻는다.
긴 작업은 반환된 session/process ID로 결과를 수집한다. 같은 결과를 기다리기 위해 여러 watch/poll 루프를 동시에 만들지 않는다.

## 변경별 검증 선택

아래 명령은 저장소 루트 기준이다. 필요한 JDK/SDK/의존성이 없으면 먼저 가능한 검사를 수행하고 미실행 이유와 명령을 남긴다. 같은 commit·입력의 통과 근거가 있으면 새 문제가 없는 한 반복하지 않는다.

| 변경 | 먼저 실행 | 추가/최종 게이트 |
|---|---|---|
| 지침·일반 문서 | diff, 링크·scope·경로·중복/충돌 확인 | CI 게이트는 유지; 문구를 복제한 테스트를 만들지 않음 |
| `Skills/kartograph/SKILL.md` | 스킬 구조 + 실제 사용 시나리오 검토 | `./gradlew --no-daemon :cli:test :cli:installDist`, 설치·agent surface 확인 |
| Kotlin 모듈 | 해당 `:<module>:test`, 버그를 재현한 focused test | `./gradlew --no-daemon test :koverVerify :cli:installDist` |
| index/retention | 단위 테스트 + 실제 compiler 코퍼스의 수정 전 실패 | `Scripts/verify-fixture-corpus.sh`, 자기 분석 |
| Gradle plugin | `:gradle-plugin:test :gradle-plugin:validatePlugins` | `Scripts/verify-gradle-plugin-fixture.sh`, JDK 17/21 |
| Python/CLI gate | `python3 -m unittest discover -s Scripts/tests -v` | CLI/실제 Git/javac 계약; Kotlin CLI는 먼저 installDist |
| shell/workflow | YAML·표현식·`bash -n`/가능한 lint, 영향 경로 smoke | 관련 fixture; 원격 실행 없이 GitHub 성공을 주장하지 않음 |
| version/packaging/release | 위 제품 게이트 | `Scripts/verify-release-readiness.sh` 및 배포본 재검증 |

제품 PR에서는 CI의 전체 테스트·line coverage 90%·JDK 17/21·CLI/agent/Android/plugin 계약을 유지한다.
CLI 계약은 `Scripts/verify-cli-contract.sh`, agent 계약은 `Scripts/verify-agent-surface.sh`다. 먼저 `:cli:installDist`로 실행 파일을 준비한다.
생성 코드/새 오탐에는 양방향 exact fixture를 추가한다. 외부 도그푸딩은 [PUBLIC-VALIDATION](PUBLIC-VALIDATION.md)의 실제 입력으로 재현한다.
자기 분석은 6개 production root, `.kartograph-self.pro`, `.kartograph.yml`로 class-only/private dead·strict cycles/rules 0을 확인한다. 분석 root 누락으로 0을 만들지 않는다.

## PR과 외부 리뷰

- PR마다 GLM 리뷰를 `packet-ask`로 받는 프로젝트 요구는 유지한다. 현재 에이전트(MAIN)는 대상 저장소의 최소 파일/diff와 preview를 확인한다. 외부 리뷰 CLI(SUB)는 실제 저장소에서 직접 실행하지 않고 정제한 packet만 받는다.
- 질문은 stdin으로 전달하고 승인된 자료/대상/요청 effort를 따른다. 자동 redaction은 비밀정보 부재의 증명이 아니며 비공개 표본은 처음부터 제외한다.
- correctness·보안·회귀·scope 결함을 실제 코드/테스트로 확인해 수정한다. 선택적 표현 의견은 결함과 구분하고 기각 이유를 PR 코멘트에 남긴다.
- 새 변경이나 미해결 결함이 있을 때만 해당 범위를 재리뷰한다. 같은 diff를 “지적 0”이 될 때까지 무제한 반복하거나 단순 문서 편집에 자동 `max`를 강제하지 않는다. 사용자의 명시적 review/effort 요구는 따른다.
- 커밋은 승인된 파일만 stage한다. 머지 시 승인·최종 head·검사 결과·충돌 여부를 확인하고 머지 후 상태를 검증한다. PR 요청과 태그/배포 권한을 혼동하지 않는다.

## 릴리스와 실패 복구

`VERSION`을 단일 원천으로 사용한다. `verify-release-readiness.sh`는 두 번의 clean build와 ZIP/TAR/JAR/POM 해시·내장 고지·압축 해제 CLI/PR 도우미를 검사하며 publish하지 않는다. 다른 로컬 빌드와 동시에 실행하지 않는다.
release workflow는 tag별 실행을 직렬화하고 실행 중 자동 취소하지 않는다. 별도 tag 사이의 실행 순서는 보장하지 않으므로 여러 버전을 동시에 게시하지 않는다.
GitHub asset이 이미 있으면 upload가 실패하도록 해 원본을 보존한다. GitHub만 성공하고 Portal이 실패한 부분 배포는 상태를 확인한 후 승인된 복구를 한다. 재실행을 위해 기존 asset/tag를 자동 제거하지 않는다.
GitHub 공개, Portal 제출, Portal 승인, 독립 프로젝트 설치는 별도 결과다. 확인하지 않은 단계는 완료라고 쓰지 않는다.

## 의존성·배포 검증 유지

제품 루트 빌드는 `gradle/verification-metadata.xml`의 SHA256으로 의존성을 검증한다.
새 의존성이나 버전 변경 시 [Gradle 검증 절차](https://docs.gradle.org/current/userguide/dependency_verification.html)에 따라
`./gradlew --write-verification-metadata sha256 test :cli:runtimeSbom :gradle-plugin:runtimeSbom :gradle-plugin:validatePlugins`
으로 후보를 생성하고 좌표·출처·체크섬 diff를 검토한다. 자동 생성한 체크섬은 원본의 안전성을 증명하지 않으며
검증 실패를 없애려고 기존 체크섬을 무조건 다시 생성하지 않는다. 최초 목록은 비어 있는 별도
`--gradle-user-home <temporary-directory>`에서도 검사해 로컬 해석 캐시에 가려진 BOM/POM 누락을 잡는다. Dependabot의 버전 변경 PR도
새 체크섬을 검토해 추가하기 전에는 이 검증에서 실패할 수 있다. 별도 Android 소비 fixture의 build 의존성은
이 제품 루트 검증 범위와 구분한다.

`GRADLE_8_HOME=/path/to/gradle-8.10.2 bash Scripts/verify-agp-8-fixture.sh`는 최소 지원 조합의
정확한 finding·graph·strict 실패·configuration cache 재사용을 검사한다. 제품 JAR은 저장소 wrapper로
만들고 소비 프로젝트를 별도 Gradle 8.10.2로 실행해 실제 배포물의 하위 호환성을 검사한다.
PR CI와 tag workflow 모두 같은 fixture를 실행한다. JDK 17과 Android SDK 35가 필요하다.

`runtimeSbom`은 각 배포 runtime에 실제 해석된 외부 라이브러리 좌표와 JAR SHA256을
[CycloneDX 1.6](https://cyclonedx.org/docs/1.6/json/) inventory로 기록한다. 컴파일 전용 AGP·테스트 의존성이나
시스템 JDK는 배포 runtime inventory에 포함하지 않는다. `verify-release-readiness.sh`는 이 SBOM 두 개와
배포물 SHA256SUMS까지 두 번 빌드해 재현성을 확인한다. 사용자는 release 파일들을 같은 디렉터리에 받은 후
`shasum -a 256 -c SHA256SUMS`로 무결성을 확인할 수 있다.
