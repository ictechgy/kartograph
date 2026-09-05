# PR gate (0.2.0)

`Scripts/check-pr.py`는 macOS/Linux의 Python 3.9+, Git 2.24+와 빌드된 CLI를 사용하는 얇은 도우미다. 분석 알고리즘은
CLI와 동일하다. 기준 commit의 baseline을 임시 디렉터리에 읽어 전체 현재 그래프를 `dead --strict`로
검사한다. 네트워크 접근·빌드·checkout·baseline 갱신은 하지 않는다.

## 최초 도입

기준 브랜치에서 프로젝트를 빌드하고 실제 manifest/resource/keep/consumer/classpath와 **동일한 분석 모드**로
`kartograph baseline --write .kartograph-baseline.json ...`을 실행한다. 결과를 검토하고 기준 브랜치에
커밋한다. private member 검사를 켜려면 baseline 생성과 PR 검사 모두 `--include-private-members`를 쓴다.
baseline v1은 분석 설정을 기록하지 않으므로 모드·variant·입력 일치는 호출자가 보장해야 한다.

```bash
# PR checkout에서 먼저 프로젝트의 해당 variant를 빌드한다.
python3 /path/to/kartograph-0.2.0/Scripts/check-pr.py \
  --binary /path/to/kartograph-0.2.0/bin/kartograph \
  --project . --base origin/main -- \
  --classes app/build/intermediates/built_in_kotlinc/debug/compileDebugKotlin/classes \
  --manifest app/build/intermediates/merged_manifests/debug/processDebugManifest/AndroidManifest.xml \
  --resources app/src/main/res --namespace dev.example.app \
  --keep-rules app/proguard-rules.pro \
  --report-format github-actions --include-private-members
```

경로는 예시다. 모든 관련 main module의 Kotlin/Java/생성 class root를 반복 `--classes`로 전달하고,
필요한 dependency hierarchy와 consumer rules도 기존 CLI와 같이 명시한다. 빌드되지 않은 코드나
누락된 root를 이 도우미가 찾아주지 않는다. `--project`는 Git 저장소 루트여야 하고 상대 class 경로는
그 루트 기준이다. 다른 baseline 위치는 `--baseline-path relative/path.json`으로 지정한다.

## 왜 `--since`를 쓰지 않는가

호출자 파일만 바뀌어도 수정하지 않은 파일이 새로 unreachable이 될 수 있다. 이 gate는 전체 그래프와
기준 baseline의 차이를 검사한다. `--since`는 로컬 변경 파일 탐색에는 유용하지만 PR의 새로운 진단
전체를 보장하지 않는다. PR에서 baseline을 재생성해도 이 gate가 읽는 기준 commit의 파일은 바뀌지 않는다.

종료 코드는 `0` 새 진단 없음, `1` 새 진단 있음, `2` 도구/기준 history/baseline 오류, `64` 사용 오류다.
`64`는 도우미의 형식 검사뿐 아니라 CLI가 거부한 입력(예: 잘못된 report-format)도 포함한다.
baseline이 기준 commit에 없거나 손상되면 빈 baseline으로 대체하지 않는다. 보고서 stdout과 CLI의
limitations는 보존한다. baseline은 런타임 안전성 증명이 아니며 삭제 승인도 아니다.
CLI 실행은 기본 300초로 제한하고 `--timeout <양의 초>`로 조절한다. Git 조회는 각각 30초 제한이다.
전달 옵션은 class/manifest/resource/namespace/keep/classpath/report-format 및 private/strict만 허용한다.
기존 CLI와 동일하게 `-`로 시작하는 옵션 값은 거부한다. 해당 상대경로는 `./-name`처럼 전달한다.

## GitHub Actions 연결

기존 `pull_request` build job에 아래를 적용한다. JDK/SDK 설치와 variant build, 검토한 CLI archive의
설치 단계는 프로젝트마다 다르므로 기존 job에서 준비한다. `KARTOGRAPH_DIST`는 PR 소스 바깥에 설치한
신뢰하는 배포본의 절대 디렉터리다. PR에서 수정한 도우미를 실행하면 baseline 보호 경계도 신뢰할 수 없다.

```yaml
permissions:
  contents: read

# steps 안에서:
# - uses: actions/checkout@3d3c42e5aac5ba805825da76410c181273ba90b1 # v7.0.1
#   with:
#     fetch-depth: 0
#     persist-credentials: false
# ... JDK/SDK/CLI 설치 및 현재 PR variant 빌드 ...
# - name: Check new unreachable declarations
#   env:
#     BASE_SHA: ${{ github.event.pull_request.base.sha }}
#   run: |
#     python3 "$KARTOGRAPH_DIST/Scripts/check-pr.py" \
#       --binary "$KARTOGRAPH_DIST/bin/kartograph" \
#       --project "$GITHUB_WORKSPACE" --base "$BASE_SHA" -- \
#       --classes path/to/compiled/classes \
#       --manifest path/to/merged/AndroidManifest.xml \
#       --resources app/src/main/res --namespace dev.example.app \
#       --keep-rules app/proguard-rules.pro --report-format github-actions
```

전체 history를 받아 기준 SHA가 존재하게 한다. checkout 기본 PR merge 결과와 명시적인 base SHA를
비교한다. PR 제목·브랜치 이름을 shell 코드로 삽입하지 않는다. fork 코드는 비밀 없는 일회성 runner에서
빌드하고 `pull_request_target`으로 실행하지 않는다. 빌드 코드 자체가 임의 실행이므로 이 도우미는
샌드박스나 악의적인 PR에 대한 보안 경계가 아니다. workflow/keep rules/baseline 변경도 사람의 검토가 필요하다.
base SHA는 merge-base가 아니라 PR 이벤트의 기준 브랜치 tip이다. 오래된 baseline이나 서로 다른 빌드
설정은 보수적으로 추가 진단을 만들 수 있다. 이 저장소 CI는 도우미 자체를 테스트하며, 아래 소비자 gate를
이 저장소의 필수 검사로 자동 설치하지는 않는다.

근거: [GitHub PR event](https://docs.github.com/en/actions/reference/workflows-and-actions/events-that-trigger-workflows#pull_request),
[checkout의 history/credentials 옵션](https://github.com/actions/checkout).

## 회귀 검증

`python3 -m unittest discover -s Scripts/tests -v`는 실제 Git commit·javac·CLI로 기존 부채 억제,
호출자 삭제로 생긴 미수정 Helper 진단, PR baseline 확장 무시, 기준 history/baseline 오류와 gate
덮어쓰기 거부를 확인한다. release verifier는 압축 해제한 도우미와 CLI로 동일 테스트를 실행한다.
