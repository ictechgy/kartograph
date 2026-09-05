# Astra 지침·스킬·workflow 감사

확인일: 2026-09-06. 범위는 이 저장소의 AGENTS 5개, CLAUDE, 배포용 SKILL, CI/Release workflow와 직접 연결된 설치 테스트다. 사용자 미커밋 `HANDOFF.md`, 전역 설정·인증파일·다른 프로젝트는 변경하지 않는다.

## 공식 근거와 적용 범위

- [Astra 모델 가이드](https://developers.openai.com/api/docs/guides/latest-model): 지침 충돌·의도와 후속 실행·간결한 답변·위임·검증 규모를 조정한다. 이 감사에서는 반복 절차를 줄이고 프로젝트의 필수 계약은 유지한다.
- [Astra 모델 명세](https://developers.openai.com/api/docs/models/gpt-6-astra): 공식 ID는 `gpt-6-astra`, reasoning effort는 `low/medium/high/xhigh/max`다. 무조건 max로 바꾸지 않고 실제 workload로 비교해야 한다.
- [AGENTS 탐색](https://learn.chatgpt.com/docs/agent-configuration/agents-md): 경로별 지침 체인과 크기 제한이 있으므로 루트에는 공통 계약, 조건부 절차는 참조 문서에 둔다. 링크가 모든 하위 지침을 로드하지는 않는다.
- [스킬 가이드](https://learn.chatgpt.com/docs/build-skills): 구체적인 name/description, 필요할 때 본문 로드, `.agents/skills` 탐색과 symlink 지원을 반영한다.
- [GitHub concurrency](https://docs.github.com/en/actions/how-tos/write-workflows/choose-when-workflows-run/control-workflow-concurrency), [workflow 문법](https://docs.github.com/en/actions/reference/workflows-and-actions/workflow-syntax): 중복 PR 작업 취소·job timeout·권한 범위를 명시한다.
- [checkout](https://github.com/actions/checkout): 인증정보를 작업 환경에 유지할 필요가 없는 checkout에 `persist-credentials: false`를 쓴다.
- [release upload](https://cli.github.com/manual/gh_release_upload): 기존 파일을 덮어쓰는 `--clobber`를 제거한다. 부분 배포 복구는 자동 덮어쓰기가 아니라 상태 확인 후 수행한다.

추적된 제품 코드/workflow에는 OpenAI API 호출이나 모델 선택 설정이 없다. 따라서 API endpoint·모델·추론 강도를 바꾼 것은 아니다. 실제 선택은 Codex/실행 환경의 상위 설정에 달려 있다. 전역 정책을 프로젝트 파일로 우회하지 않는다. 향후 API 통합이 요청될 때만 해당 모델의 endpoint/parameter 호환성을 별도로 검증한다.

## 발견 → 변경

| 발견 | 영향 | 조치 |
|---|---|---|
| 매 작업마다 PRD/PLAN/RESEARCH/결정 전체 읽기 | 작은 작업에도 과거 맥락을 반복 로드 | 작업 종류에 따른 읽기 경로와 재개 시 HANDOFF로 구분 |
| 공통 검증·리뷰·배포 절차가 여러 AGENTS에 반복 | 추가 규칙 간 충돌과 반복 실행 가능성 | 루트는 계약, 상세 절차는 `AGENT-WORKFLOW.md`, 경로별 지침은 고유 조건만 유지 |
| 승인된 수정/읽기와 새 외부 작업의 구분이 약함 | 불필요한 재확인 또는 분석 요청에서 편집으로 확대 | 승인된 범위 안에서는 진행, 새 권한·범위만 질문; 스킬로 인한 정지는 근거 명시 |
| 스킬이 `truncated`를 단일 boolean으로 설명 | 잘린 이웃을 완전한 결과로 해석할 위험 | 실제 `result.truncated.{usedBy,dependsOn,members}`와 일치 |
| baseline을 기존 팀 결정으로 해석 | suppression을 검토·삭제 승인으로 과해석 | fingerprint 억제 사실로 한정 |
| 분석 스킬의 수정/빌드 절차와 staleness 표현이 과도함 | 읽기 전용 요청에도 불필요한 변경/빌드, rebuild 후 안전성 오인 | 요청 scope를 먼저 구분하고 한계가 지속되면 입력을 조사 |
| canonical 스킬이 `Skills/`에만 있음 | Codex 표준 로컬 탐색 위치와 다름 | `.agents/skills/kartograph`를 canonical 폴더로 향하는 상대 symlink로 연결 |
| 설치 테스트가 `changed`라는 일반 단어의 부재를 검사 | 정상적인 스킬 문구 변경이 테스트 실패 | 사용자 파일의 실제 보존과 번들 내용의 byte 일치로 교체 |
| CI에 concurrency/timeout이 없음 | 이전 PR 커밋의 빌드가 계속 실행됨 | PR 번호별 취소, test 30분·compatibility 20분 제한 |
| release가 job 외부에서도 쓰기 권한을 선언하고 asset 덮어쓰기 허용 | 권한 범위와 부분 배포 복구가 불명확 | job 권한 명시, 45분 제한, tag별 비취소 실행, 덮어쓰기 제거 |

CLAUDE의 참조 전용 형태, index/fixture의 구체적인 compiler 안전 규칙은 유지했다. 사용자 요구인 PR당 GLM 리뷰도 유지하되, 새 결함 없는 동일 diff의 반복 리뷰와 요청하지 않은 max 강제는 하지 않는다.
`compatibility (17)`, `compatibility (21)`, `test`의 이름과 실행 게이트는 바꾸지 않았다. 검사명을 없애거나 workflow-level paths-ignore로 required check를 pending 상태에 두는 최적화는 피했다. JDK 17 중복을 완전히 제거하려면 required-check 소비자와 job 역할을 먼저 확인해야 한다.

## 스킬 전달/탐색

canonical 파일은 `Skills/kartograph/SKILL.md` 하나다. `.agents/skills/kartograph`는 `../../Skills/kartograph`를 가리키며 복사본을 유지하지 않는다. symlink를 지원하지 않는 checkout 환경에서는 canonical 파일을 직접 읽는다. 기존 `kartograph skill` 명령의 `.claude/skills` 설치 대상은 그대로이며, 여기서 CLI 옵션/API를 확장하지 않았다.
SKILL에는 저장소 밖의 필수 참조를 추가하지 않았다. 공개 배포본에서도 자체적으로 해석할 수 있고 isthmus가 없을 때도 로컬 설명은 가능하다. 자동 탐색 연결의 구조는 검사하지만 현재 대화의 모델/스킬 목록이 갱신됐다고 단정하지 않는다.

## 검증과 측정 방법

- 변경 전 루트: 77줄 / 8,747 bytes. 변경 후 루트: 50줄 / 6,776 bytes, **기본 프로젝트 지침 약 22.5% 감소**. 상세 workflow는 조건부 참조다. 전체 문서 총량 감소나 모델 token/latency 개선 수치로 해석하지 않는다.
- bundled `agents_audit.py`: 범위·상대 링크·marker·크기 검증. `quick_validate.py`: 스킬 frontmatter/name 검증. symlink의 실제 대상과 canonical 내용도 대조한다.
- `./gradlew --offline --no-daemon :cli:test :cli:installDist`: 처음에는 `changed` 단어 검사에서 1개 실패; 원인을 확인한 뒤 실제 설치/보존 계약으로 바꿔 통과했다.
- 두 workflow의 YAML, 16개 `run` shell의 `bash -n`, trigger/check 이름/JDK matrix/권한/concurrency/고정 SHA를 로컬 검증한다.
- 로컬에 actionlint/shellcheck는 없어 새 도구를 설치하지 않았다. YAML/내장 shell 검사는 GitHub expression engine·runner·외부 publish 성공까지 증명하지 않는다. 새 workflow의 원격 CI/배포는 실행하지 않는다.
- 추가 lint는 도구 준비 후 `actionlint .github/workflows/ci.yml .github/workflows/release.yml`로 실행한다. GitHub에서의 실제 실행 검증은 승인된 PR에서 수행한다.
- HANDOFF의 변경 전 SHA-256과 동일함을 확인한다. 사용자 변경은 이번 감사의 commit/PR 범위에서 제외한다.

## 사용 시나리오 점검과 남은 검증

다음은 스키마·권한 계약에 대한 검토 기준이며 독립 모델 A/B 평가 결과가 아니다.

| 요청/입력 | 기대되는 결정 |
|---|---|
| unreachable JSON을 설명해 달라는 요청 | 근거·한계만 설명; 파일 편집/빌드/다운로드는 자동 실행하지 않음 |
| `truncated.usedBy=true` | 해당 방향을 더 조회하기 전 참조 부재를 단정하지 않음 |
| `suppressedByBaseline=true` | 억제 사실을 보고; 검토자·삭제 권한을 추정하지 않음 |
| ambiguous / notFound | candidate usr로 구분하거나 누락 입력을 설명; 이름만으로 선택하지 않음 |
| CLI 오류 수정 요청 | 실패 재현, 최소 변경, 관련 검증 후 완료; 새 문제 없이 같은 검증을 반복하지 않음 |
| 로컬 분석이 끝났지만 외부 배포는 미승인 | 로컬 결과는 전달하고 외부 동작 직전에 승인 범위를 확인 |

Astra의 실제 성능 비교는 같은 commit/입력·같은 모델/effort로 위 요청을 반복 실행해 정답·권한 준수·불필요한 도구 호출·벽시계 시간·token을 비교해야 한다. 이번 작업은 실제 Astra A/B 실험, 전역 설정 변경, 외부 모델 리뷰 또는 원격 PR/배포를 수행하지 않는다.
