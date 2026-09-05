# Scripts 지침

이 디렉터리는 [공통 지침](../AGENTS.md)을 상속한다. 실행·검증을 조립하며 Kotlin 분석 알고리즘을 재구현하지 않는다.

## 실행 안전성

- shebang과 실제 shell을 확인한다. 명령은 인자 배열로 전달하고 경로를 quote한다. zsh/bash 워드 분할을 가정한 문자열 명령이나 `eval`을 쓰지 않는다.
- macOS/Linux 차이를 검증한다. macOS에 GNU `timeout`이 있다고 가정하지 않는다. Python subprocess에는 필요한 timeout·종료 코드 처리를 둔다.
- 긴 작업은 도구의 session/process ID와 종료 결과로 추적한다. `pgrep -f`가 자기 자신을 잡는 대기 루프나 `.done` 파일 존재만으로 성공 판정을 만들지 않는다.
- 임시 디렉터리는 고유하게 만들고 소유한 정확한 경로만 정리한다. 공용 시스템 변수나 넓은 디렉터리를 삭제 대상으로 쓰지 않는다.
- 실패를 `|| true` 등으로 성공으로 바꾸지 않는다. 실패를 기대하는 테스트는 실제 종료 코드를 비교한다. 원시 stderr·Git ref·개인 경로가 오류에 섞이지 않게 한다.

## PR gate와 검증

- `check-pr.py`는 전체 현재 그래프에 기준 commit baseline만 적용한다. PR baseline 확장이나 `--since`로 신규 진단을 숨기지 않는다.
- 옵션 allowlist, Git/CLI 환경 격리, timeout, `0/1/2/64` 계약을 유지한다. 새 CLI 옵션은 검토 후 허용하며 help/쓰기/우회 경로를 자동 전달하지 않는다.
- `Scripts/tests`는 실제 Git commit·javac·배포 CLI로 검사한다. 기존 부채 억제, 미수정 파일의 새 진단, 오류 구분을 결과로 검증한다. 소스 문자열 포함 여부만 검사하는 테스트로 대체하지 않는다.
- 공개 verifier는 고정 revision·실제 입력·선택 정점의 존재까지 확인한다. 전체 진단 수를 정확도 정답으로 고정하거나 한계를 숨기지 않는다.
- 루트에서 `python3 -m unittest discover -s Scripts/tests -v`를 실행한다. bash 수정은 `bash -n <변경 파일>`과 실제 동작을 확인한다.
- `verify-release-readiness.sh`는 루트 clean build를 두 번 실행하므로 같은 저장소의 다른 빌드와 동시에 돌리지 않는다. ZIP/TAR/plugin JAR/POM 해시, 내장 고지, 압축 해제 CLI와 PR 도우미를 검사한다.
- 릴리스/의존성 설치/외부 다운로드는 사용자 승인 범위에서만 한다. credential 값이나 개인 classpath 목록을 결과·리뷰 packet에 넣지 않는다.
