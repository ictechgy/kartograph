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
git만 실행하고(빈 항목·`.`·상대경로와, Windows에서는 실행 위치·분석 대상 디렉터리를 건너뛴다) 분석 대상 저장소의
git 설정이 고른 명령을 실행하지 않는다. git을 부를 때마다 바로 앞에서 설정된 filter 드라이버 이름을 읽어
clean·smudge·process 명령을 빈 값으로 덮고, `core.fsmonitor`, 서명 표시(`log.showSignature`), textconv, 외부 diff,
작업 트리 diff의 index 자동 갱신(`diff.autoRefreshIndex`, index를 다시 쓸 때 실행되는 hook 포함)을 끈다. 서브모듈
안에서는 git을 실행하지 않는다. 그 git은 서브모듈 저장소의 설정을 따르기 때문이다. 그래서 revision 확인은 프로젝트
아래에 서브모듈(gitlink)이 있으면 깨끗함을 증명할 수 없어 revision을 생략하고, `dead --since`는 서브모듈이 가리키는
커밋의 변화만 보고 서브모듈 안의 커밋하지 않은 변경은 보지 않는다. `-c`로 그대로 덮을 수 없는 드라이버 이름
(`A-Z a-z 0-9 . _ + -` 밖의 문자가 든 이름)이 있거나 설정을 읽지 못하면 git을 실행하지 않는다. 이때 revision은
생략하고 `dead --since`는 exit 2로 끝난다.

filter를 실행하지 않으므로 git-lfs처럼 filter로 정규화되는 파일은 stat 정보가 index와 다르면 내용이 커밋과 달라
보인다. 그때 revision은 생략한다(깨끗하다고 주장하지 않는다). `dead --since`는 stat이 index와 다른 파일의 내용을
다시 읽지 않으므로 그런 파일과 내용 없이 시각만 바뀐 파일도 범위에 넣는다. 신뢰하는 checkout이면 먼저
`git update-index --refresh`로 stat 정보를 갱신한다. 위에 적지 않은 설정은 범위(사용자 전역·시스템·저장소)와
상관없이 따른다. 예를 들어 partial clone에서 빠진 객체를 받아 와야 하면 git은 저장소에 설정된 원격 전송 설정
(예: `remote.<name>.uploadpack`)이 고른 명령을 실행할 수 있다. 그러니 출처를 모르는 `.git`이 든 checkout에서는
여전히 `--revision`을 명시하고 `--since`를 쓰지 않는다.
