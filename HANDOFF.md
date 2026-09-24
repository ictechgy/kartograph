# HANDOFF

마지막 갱신: 2026-09-24 — schema(persistence) 생산자 작업 중

## 현재 상태

- `feature/schema-facts` 브랜치에서 `kartograph schema` 명령을 구현했다 — Room·JDBC·Exposed·jOOQ·SQL 리터럴·SQLDelight `.sq`/`.sqm`을 읽어 isthmus persistence 계약(`platform: "kotlin"`, `relation-use`) 문서를 낸다.
- 신규: `index/.../SchemaFactScanner.kt`(선언 패스→사실 패스), `index/.../SqlRelations.kt`(rustograph `source/schema.rs`의 SQL 렉서 포트 — 문장 경계·GRANT/ON 게이트·플레이스홀더 미해석 계수 포함). 공유 헬퍼는 `ChannelBridgeScanner.kt`에서 internal로 승격. `ProjectTraversal.walkSources`에 `extensions` 파라미터 추가.
- `named-arg` 판정은 선두 `name =` 패턴만 본다 — SQL 문자열 안의 `=`로 오인해 인자를 버리던 초기 결함을 테스트로 고정했다.
- 검증: `:index:test`·`:cli:test` 통과, `/tmp` fixture → `isthmus check` end-to-end로 kotlin 문서 조인 확인. 잔여: 커밋·PR·GLM 리뷰, isthmus 문서 표의 kartograph 행.
- 아래는 0.16.0 배포·정리 완료 상태다.

- Kotlin/JVM 컴파일 그래프를 질의하는 CLI·Gradle plugin. 작업 규칙은 [AGENTS.md](AGENTS.md), 제품 범위는 [PRD](docs/PRD.md).
- `main` = `b7165489379b7916b0fa30ddded22aeb576ca696`, VERSION·최신 발행 버전은 **0.16.0**.
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
- [자매 스킬 메모](HANDOFF-cartograph-skills-20260921.md)와 [cartograph 메모](HANDOFF.cartograph-notes.md)는 기존 사용자 파일이다.
- HANDOFF·HISTORY 갱신은 로컬 변경이며 main에 직접 커밋하지 않았다. 제품 소스는 바꾸지 않았다.

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
