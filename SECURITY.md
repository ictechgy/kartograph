# Security and privacy

## Supported versions

0.10.x의 최신 공개 patch release와 다음 patch를 준비하는 `main`에 보안 수정을 제공한다.

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

`bridges`·`routes`·`schema`가 내는 `bridge-facts` 문서와 `reach`·`impact --format language-traversal` 문서의
`project` 값은 isthmus가 문서를 조인할 때 쓰는 `--project`의 canonical POSIX 절대 realpath라서 사용자 홈 경로나
계정 이름을 드러낼 수 있다. `bridge-facts`의 fact 위치는 프로젝트 상대경로만 사용한다. 이 문서들을 공개
artifact로 올리기 전에는 `project` 값을 치환하거나 지운다(예: `jq '.project="."'`). 소비 도구가 report를
저장하거나 결합하면서 절대경로를 덧붙일 수 있으므로 최종 공개 artifact는 별도로 확인한다.

분석 대상은 신뢰된 build output이어야 한다. 조작된 class/JAR/resource를 처리하는 것은 보안 격리가 아니며,
CLI를 sandbox로 취급해서는 안 된다.

`reach`·`impact --format language-traversal`의 revision 확인과 `dead --since`는 PATH의 절대경로 항목에서 찾은
git만 실행하고(빈 항목·`.`·상대경로와, Windows에서는 실행 위치·분석 대상 디렉터리를 건너뛴다) 저장소 config의
`core.fsmonitor` 명령은 끈다. 그 밖의 대상 저장소 git 설정(filter·textconv·diff 드라이버 등)은 따르므로, 출처를
모르는 `.git`이 든 checkout에서는 `--revision`을 명시하고 `--since`를 쓰지 않는다.
