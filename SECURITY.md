# Security and privacy

## Supported versions

0.1.x의 최신 patch release에 보안 수정을 제공한다. 아직 외부에 공개된 release가 없으므로 0.1.0 tag가
생성되기 전에는 저장소의 `main`이 유일한 지원 대상이다.

## Reporting a vulnerability

공개 issue에 exploit, 개인 경로, 소스 코드 또는 다른 민감 정보를 올리지 않는다. GitHub 저장소의
**Security → Report a vulnerability**에서 private vulnerability report를 연다. 접수 후 7일 안에 확인하고,
영향과 수정 계획을 같은 private thread에서 갱신하는 것을 목표로 한다.

## Privacy model

kartograph의 CLI와 Gradle plugin은 입력한 class file, source/resource tree, manifest, keep rule과 Git metadata를
로컬에서 읽는다. 자체 telemetry, analytics, crash upload 또는 network 전송은 하지 않는다. 빌드 도구가
dependency를 내려받거나 사용자가 CI artifact/report를 업로드하는 동작은 kartograph의 전송이 아니며 해당
환경의 정책을 따른다.

report에는 symbol 이름, 프로젝트 상대 source 경로, 줄 번호와 사용 관계가 들어갈 수 있다. SARIF, JSON,
baseline, DOT 및 CI log는 소스 자체를 포함하지 않더라도 저장소 구조를 드러낼 수 있으므로 공개 artifact로
올리기 전에 검토한다. 오류 메시지는 의도적으로 절대경로를 피하지만 모든 third-party Gradle/JVM 오류의
출력까지 정제한다고 보장하지 않는다.

분석 대상은 신뢰된 build output이어야 한다. 조작된 class/JAR/resource를 처리하는 것은 보안 격리가 아니며,
CLI를 sandbox로 취급해서는 안 된다.
