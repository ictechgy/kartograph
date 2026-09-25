# HANDOFF

마지막 갱신: 2026-09-25 — 0.17.0(schema) 배포 완료

## 현재 상태

- **0.17.0 배포 완료.** [PR #103](https://github.com/ictechgy/kartograph/pull/103) squash 머지 `05e843b`(검증 head `05f24ae`와 tree 동일), annotated 태그 `v0.17.0`, [GitHub Release](https://github.com/ictechgy/kartograph/releases/tag/v0.17.0) asset 7개, [Plugin Portal](https://plugins.gradle.org/plugin/io.github.ictechgy.kartograph/0.17.0) 게시.
- 검증: PR CI `36011298688`, main CI `36015029951`, Release `36015069175` 모두 성공. 로컬 `verify-release-readiness.sh`는 `9cca600`에서 통과. 이후 `05f24ae`는 README만 바꿨다. 배포 asset의 `SHA256SUMS` 전부 OK, Portal JAR = Release JAR, `05e843b` 로컬 재빌드 CLI ZIP/TAR = 배포본. 배포 CLI의 `--version`과 Room fixture `schema` 스모크도 확인했다.
- GLM 리뷰(low)는 릴리스 파일에서 결함 0건이었고, 제공 버전 문구의 위치만 반영했다. packet에 미커밋 HANDOFF diff가 함께 들어갔다(승인 범위 밖, 비밀정보 없음). 앞으로 `--diff`는 커밋 범위를 명시한다.
- 원장: `.git/release-0.17.0-20260925/FINAL.json`, `SHA256SUMS`와 GLM 응답·readiness·release 로그의 gzip 사본(원본 해시 대조 완료). 필수 잔여 작업 없음.

- `kartograph schema`가 [PR #102](https://github.com/ictechgy/kartograph/pull/102)로 머지됐다(`45fc535`, `feature/schema-facts` 브랜치 정리됨). `schema`는 0.17.0으로 발행됐다.
- Room·JDBC·Exposed·jOOQ·SQL 리터럴·SQLDelight `.sq`/`.sqm`을 읽어 isthmus persistence 계약(`platform: "kotlin"`, `target: "persistence"`, `relation-use`) 문서를 낸다.
- 신규: `index/.../SchemaFactScanner.kt`(선언 패스→사실 패스), `index/.../SqlRelations.kt`(rustograph `source/schema.rs`의 SQL 렉서 포트 — 문장 경계·GRANT/ON 게이트·플레이스홀더 미해석 계수 포함). 공유 헬퍼는 `ChannelBridgeScanner.kt`에서 internal로 승격. `ProjectTraversal.walkSources`에 `extensions` 파라미터 추가.
- `named-arg` 판정은 선두 `name =` 패턴만 본다 — SQL 문자열 안의 `=`로 오인해 인자를 버리던 초기 결함을 테스트로 고정했다. `==` 비교 식은 `(?!=)` lookahead로 걸러진다.
- GLM 리뷰 2라운드를 반영했다. 1라운드(`ab803db` 전후): `.sq` 라벨이 문장 머리를 삼키던 문제, 산문 리터럴 오탐(게이트 없는 리터럴은 strict 모드 — 대문자 키워드만 발화), `'"'` 문자 리터럴이 뷰를 깨던 문제, 중첩 클래스 멤버 오귀속, 무인자 호출의 허위 동적 사실, `@field:` use-site 타깃, 미해석 피연산자 개수 계수, Exposed 미선언 대상의 동적 근거 보존. 2라운드(`8952e75`): 공백 무인자 호출 가드 우회, ungated 리터럴의 엔티티 번역 과적용 제거(`@Query` 경로만 유지), strict 낙전 리터럴의 `skipped-sql-literals` limitation 계수.
- 검증: `:index:test`·`:cli:test` 통과, CLI 계약·agent surface·자기 분석 스모크 게이트 통과, `/tmp` fixture → `isthmus check` end-to-end로 kotlin 문서 조인 확인.
- isthmus 측 kotlin 수용 테스트와 `hasPersistenceDomain`의 `target` 기반 판정 수정은 PR #111(`7e69fdf`)로 머지됐다. 같은 작업의 swift 수용은 PR #112(`b6a0eec`).
- 아래는 직전 0.16.0 배포·정리 기록이다.

- Kotlin/JVM 컴파일 그래프를 질의하는 CLI·Gradle plugin. 작업 규칙은 [AGENTS.md](AGENTS.md), 제품 범위는 [PRD](docs/PRD.md).
- 0.16.0 발행 기준 커밋 = `b7165489379b7916b0fa30ddded22aeb576ca696`, 당시 최신 발행 버전. 현재 VERSION·최신 발행은 **0.17.0**(`05e843b`).
- [PR100](https://github.com/ictechgy/kartograph/pull/100) 머지와 `v0.16.0` 태그·[GitHub Release](https://github.com/ictechgy/kartograph/releases/tag/v0.16.0)·[Plugin Portal](https://plugins.gradle.org/plugin/io.github.ictechgy.kartograph/0.16.0) 게시를 완료했다.
- 요청한 구현·CI·배포·독립 설치·정리의 필수 작업은 남지 않았다. 과거의 “다음 작업”을 자동 재개하지 않는다.

## 완료와 검증 근거

- [PR99](https://github.com/ictechgy/kartograph/pull/99): FILE_FACADE 기반 최상위 함수 후보, 모듈 경로 후보, MCP `discover_symbols` 페이지 조회로 선택자를 복구한다. 원래 notFound·ambiguous·분석 한계는 유지한다. [사용법](docs/MCP.md).
- 릴리스 PR CI `35776752442`, main CI `35780185751`, [Release run](https://github.com/ictechgy/kartograph/actions/runs/35780283974) 모두 성공. 검증 head `cf89e04`와 merge tree가 같다.
- 두 clean 빌드8개 산출물 해시, ZIP/TAR 각각142개 테스트·CLI 계약, 공개7개 asset digest·SHA256SUMS, 독립 Portal 설치/JAR 동일성·cache·freshness 대조를 통과했다.
- 실제 0.16.0 배포 CLI로 v8 실패 선택자3개를 정확한 USR로 재조회했다. AI 모델을 재실행하거나 과거 점수를 바꾸지는 않았다.
- 정본: `.git/release-0.16.0-20260923/FINAL.json`, `.git/selector-recovery-20260923/FINAL.json`. 다른 checkout에 이 로컬 원장이 있다고 가정하지 않는다.

## 이번 정리

- 완료·clean·머지 tree 일치를 확인한 worktree **6개를 제거**했다. 각 HEAD와 branch, `refs/archive/cleanup-20260923/*` 및 실행 산출물을 보존했다.
- Git에는 main과 아래 사용자 변경 worktree만 남았다. Orca 관리 목록에는 main만 남았다.
- 대형 과거 자료560개를348개 gzip blob으로 묶고, 프로젝트 cache261개도 압축 보존했다. 모든 payload와 worktree 파일13,278개를 실제 복원·해시 대조했다.
- 보존 archive를 포함한 배정 용량은 약 **3.8 GiB 감소**했다. SDK·설치 도구·전역 cache·원본 연구 입력·사용자 메모를 보존했다.
- 원장/복원 목록: `.git/cleanup-handoff-20260923/README.md`, `FINAL.json`, `artifact-archive-manifest.json`, `worktree-archives.json`.
- 이전 상세 인수인계는 [HANDOFF-HISTORY.md](HANDOFF-HISTORY.md)에 옮겼다. 정리 전 원문도 정리 원장에 그대로 있다.

## 보존한 사용자 변경

- `build/reports/expanded-evaluation-20260913/interface-v2-worktree`: detached `d4e6a81`, `Scripts/`의 파일6개에 미커밋 변경이 있어 보존했다. 부모 `build/reports`를 통째로 지우지 않는다.
- [자매 스킬 메모](HANDOFF-cartograph-skills-20260921.md)와 [cartograph 메모](HANDOFF.cartograph-notes.md)는 기존 사용자 파일이며 `.gitignore`로 로컬에만 둔다.
- HANDOFF는 main에 직접 커밋하지 않고 docs PR로 반영한다.

## 복원과 실행

원래 경로의 byte·mtime을 되살리며 기존 다른 내용을 덮어쓰지 않는다. 필요할 때만 선택 복원한다.

```bash
python3 .git/cleanup-handoff-20260923/restore-worktree.py ai-utility-v8-20260922
python3 .git/cleanup-handoff-20260923/restore-artifacts.py --list
python3 .git/cleanup-handoff-20260923/restore-artifacts.py --path '<목록의 원래 상대경로>'
python3 .git/cleanup-handoff-20260923/restore-caches.py --list
```

- 과거 manifest의 절대경로·frozen runtime hash를 수정하지 않았다. 과거 검증기를 실행하려면 필요한 worktree·입력·보고서/cache를 먼저 복원한다. 완료 모델 실행을 임의로 재시작하지 않는다.
- 0.16.0 CLI는 `.git/release-0.16.0-20260923/downloads-0.16.0/cli-zip/kartograph-0.16.0/bin/kartograph`에 남아 있다. 0.15.0 배포본과 v6/v7/v8 native archive도 보존했다.
- Java는 `JAVA_HOME=/opt/homebrew/opt/openjdk@17/libexec/openjdk.jdk/Contents/Home`을 명시한다. 기본 macOS java 런처는 등록이 없으며 전역 설정은 바꾸지 않았다.

## 한계와 다음 범위

- [v8 평가](experiments/ai-utility-v8/README.md)는 일관된 AI 효용 우위를 확인하지 못했다. 당시 실제 impact3회 모두 notFound였으며, 이번 별도 복구 검증을 과거 점수 향상으로 해석하지 않는다.
- 후속 AI 사용성 평가는 새 요청·프로토콜의 범위다. 기존 frozen proxy는3-tool 계약이므로 새4-tool 평가와 구분한다.

## 재개 프롬프트

`/Users/jinhongan/Desktop/kartograph`에서 HANDOFF.md와 AGENTS.md를 읽고, 0.16.0 배포·정리가 완료된 상태에서 새 요청 범위만 진행해줘. 과거 원문 검증이 필요하면 정리 원장의 복원 안내부터 확인해줘.
