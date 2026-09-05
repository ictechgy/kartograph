# GitHub workflow 지침

[공통 계약](../AGENTS.md)을 상속한다. workflow는 GitHub에서 실행되는 권한 있는 코드이며, 로컬 검증 성공과 원격 실행 성공을 구분한다.

- CI의 `test`, `compatibility (17)`, `compatibility (21)` 검사 이름과 JDK/coverage/fixture 게이트를 유지한다. 이름 변경·skip은 required-check 영향을 먼저 확인한다.
- 오래된 PR 실행만 취소한다. main 실행과 release 실행을 새 PR 때문에 취소하지 않는다. release concurrency는 tag별이며 실행 중 자동 취소하지 않는다.
- 최소 권한, SHA 고정 action, checkout의 `persist-credentials: false`를 유지한다. 외부 PR을 `pull_request_target`의 비밀·쓰기 권한으로 실행하지 않는다.
- release의 `contents: write`는 release job에 한정한다. Portal 비밀은 publish step에만 전달하고 환경 `release`의 보호를 우회하지 않는다.
- tag와 `VERSION`이 일치해야 publish한다. 기존 release asset을 자동 삭제/덮어쓰지 않는다. 부분 배포 재실행은 기존 상태와 사용자 승인을 확인하고 복구한다.
- YAML 구조·표현식·내장 shell을 검증한다. lint가 실행 가능한 runner/권한/외부 서비스까지 증명하지는 않는다. 실제 GitHub 실행은 별도 승인 후 최종 commit으로 확인한다.
- action 버전/runner 이미지/캐시 구성을 변경할 때는 공식 문서와 실제 호환성을 확인한다. 로컬 검사에 없다는 이유로 새 도구나 dependency를 자동 설치하지 않는다.
