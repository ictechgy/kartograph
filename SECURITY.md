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

저장 snapshot(`--graph-file`·`--base-graph`·MCP 시작)은 byte 상한(기본 64 MiB, 최대 128 MiB) 안에서 읽는다. compact v2는
문자열 하나를 여러 행이 참조하므로, 문자열 사전 참조와 정점 위치 참조가 펼치는 문자열이 합계 2,147,483,648자·33,554,432개를
넘으면 검증 전에 거부한다(exit 2). 상한 안의 문서라도 읽기 비용은 모양에 따라 다르다. 상한 바로 아래로 만든 적대 문서는
기본 heap에서 읽기만 해도 JDK 21은 약 50초, JDK 17은 약 80초가 걸렸고 RSS는 5 GB 가까이 썼다. 질의 결과가 공유 문자열을 다시
실으면 출력이 2 GB를 넘을 수도 있다. 출처를 모르는 snapshot은 시간·메모리 제한을 둔 환경에서 읽는다.

`reach`·`impact --format language-traversal`의 revision 확인과 `dead --since`는 PATH의 절대경로 항목에서 찾은
git만 실행하고(빈 항목·`.`·상대경로와, Windows에서는 실행 위치·분석 대상 디렉터리를 건너뛴다) 분석 대상 저장소의
git 설정이 고른 명령을 실행하지 않는다. git을 부를 때마다 바로 앞에서 설정된 filter 드라이버 이름을 읽어
clean·smudge·process 명령을 빈 값으로 덮고, `core.fsmonitor`, 서명 표시(`log.showSignature`), textconv, 외부 diff,
작업 트리 diff의 index 자동 갱신(`diff.autoRefreshIndex`, index를 다시 쓸 때 실행되는 hook 포함)을 끈다. 서브모듈
안에서는 git을 실행하지 않는다. 그 git은 서브모듈 저장소의 설정을 따르기 때문이다. 그래서 revision 확인은 프로젝트
아래에 서브모듈(gitlink)이 있으면 깨끗함을 증명할 수 없어 revision을 생략하고, `dead --since`는 서브모듈이 가리키는
커밋의 변화만 보고 서브모듈 안의 커밋하지 않은 변경은 보지 않는다. `-c`로 그대로 덮을 수 없는 드라이버 이름
(`A-Z a-z 0-9 . _ + -` 밖의 문자가 든 이름)이 있거나 설정을 읽지 못하면 git을 실행하지 않는다. 이때 revision은
생략하고 `dead --since`는 exit 2로 끝난다. 서브모듈이나 덮을 수 없는 설정 때문에 revision을 생략한 순회 문서에는
`revision-unverified:`로 시작하는 limitation을 남겨, 작업 트리가 더러워 생략한 경우와 구분한다.

partial clone(`--filter`로 받은 clone)이 빠진 객체를 받는 lazy fetch는 저장소에 설정된 원격 전송 설정(예:
`remote.<name>.uploadpack`·`core.sshCommand`)이 고른 명령을 실행한다. 그래서 git을 어떤 전송 프로토콜도 허용하지 않고
(`GIT_ALLOW_PROTOCOL`을 빈 값으로 둔다. 저장소의 `protocol.*.allow`보다 우선한다) lazy fetch도 끈(`GIT_NO_LAZY_FETCH=1`)
환경으로 실행한다. `dead --since`의 diff는 rename을 판정하지 않으므로 blobless clone(`--filter=blob:none`)에서도 빠진
blob 없이 범위를 읽는다. 빠진 객체가 꼭 필요하면(예: 기준 커밋의 tree가 없는 `--filter=tree:0` clone) git이 실패한다.
그때 revision은 생략하고, `dead --since`는 partial clone임을 알리는 오류로 exit 2가 된다. 신뢰하는 checkout이면 객체를
먼저 받아 두거나 `--filter` 없이 clone한다.

filter를 실행하지 않으므로 git-lfs처럼 filter로 정규화되는 파일은 stat 정보가 index와 다르면 내용이 커밋과 달라
보인다. 그때 revision은 생략한다(깨끗하다고 주장하지 않는다). `dead --since`는 stat이 index와 다른 파일의 내용을
다시 읽지 않으므로 그런 파일과 내용 없이 시각만 바뀐 파일도 범위에 넣는다. 신뢰하는 checkout이면 먼저
`git update-index --refresh`로 stat 정보를 갱신한다. 위에 적지 않은 설정은 범위(사용자 전역·시스템·저장소)와
상관없이 따른다. 이 경계가 git의 모든 경로를 막는다고 보장하지는 않으므로, 출처를 모르는 `.git`이 든 checkout에서는
`--revision`을 명시하고 `--since`를 쓰지 않는 편이 안전하다.

배포본에 든 PR 도우미 `Scripts/check-pr.py`·`Scripts/check-impact.py`도 git을 부를 때마다 같은 방식으로 filter
드라이버를 덮고 `core.fsmonitor`·서명 표시를 끄며, `git show`와 diff에서는 textconv·외부 diff를 끈다.
`check-impact.py`의 작업 트리 검사는 index 자동 갱신도 끄고 서브모듈 안에서 git을 실행하지 않는다. 그래서 서브모듈이
가리키는 커밋의 변화는 계속 거부하지만 서브모듈 안의 커밋하지 않은 변경은 보지 않고(서브모듈이 있으면 보고서
`limitations`에 `current: working-tree-submodules-unchecked:`로 시작하는 문구를 남긴다), filter로 정규화되거나 시각만
바뀐 파일은 작업 트리 변경으로 보고 exit 2로 끝난다(신뢰하는 checkout이면 먼저 `git update-index --refresh`).
덮을 수 없는 드라이버 이름이 있거나 설정을 읽지 못하면 두 도우미 모두 git을 실행하지 않고 exit 2로 끝난다. 두 도우미도
위와 같은 환경(`GIT_ALLOW_PROTOCOL` 빈 값, `GIT_NO_LAZY_FETCH=1`)으로 git을 실행해 partial clone의 빠진 객체를 받지 않는다.
커밋 사이 diff와 작업 트리 검사는 rename을 판정하지 않아 blobless clone에서도 동작하지만, `check-pr.py`가 읽는 기준
커밋의 baseline처럼 clone에 없는 객체가 꼭 필요하면 partial clone임을 알리는 오류로 exit 2가 된다. 신뢰하는 단계에서
그 객체를 먼저 받아 두거나 `--filter` 없이 clone한다.
