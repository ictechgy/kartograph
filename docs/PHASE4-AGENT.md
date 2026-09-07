# Phase 4 agent contract

## Query

`kartograph query <symbol>`은 자매 도구 cartograph의 `SymbolQueryDocument`와 같은 필드 이름과 optional
필드 생략 규칙을 사용한다. `members`·`declaredIn`은 containment이고 `usedBy`·`dependsOn`의 usage 관계와
섞지 않는다. `--depth`와 `--limit`으로 응답을 제한하며 잘렸으면 `truncated`가 true다.

query에는 `dead`와 같은 `--manifest`, `--resources`, `--namespace`, `--keep-rules`, `--classpath`,
`--baseline` 입력을 줄 수 있다. `notFound`와 `ambiguous`도 JSON과 계량된 limitations를 stdout에 쓰고 exit
64로 끝나므로 자동화는 문서의 `status`와 종료 코드를 함께 읽어야 한다.

계량 limitations는 현재 class root와 project source에서 문자열 reflection, JNI, 동적 Android 등록,
build 이후 수정된 source를 센다. 관측된 항목이 없으면 배열은 비어 있다.

## Bridge facts

`kartograph bridges --project <root>`은 isthmus `bridge-facts` version 1을 출력한다. Kotlin 수신 측에서
Flutter `MethodChannel` 등록·method handler와 React Native `@ReactModule`·`@ReactMethod`를 수집한다.
문서의 `project`는 현재 입력 root를 뜻하는 `.`이고 모든 위치는 project-relative다. build, test source set,
`node_modules`, worktree 복제본은 제외한다.

동적 channel, 귀속하지 못한 handler, inline lambda가 아닌 handler, source scan으로 JVM USR을 만들 수 없는
handler는 fact를 버리거나 성공으로 가장하지 않고 각각 limitation으로 센다. 현재 source scanner는
`missing-handler-usrs`를 항상 명시하며, isthmus는 `channel: null` 또는 dynamic fact를 조인하지 않는다.

## Agent skill

`kartograph skill [--project <root>]`은 검토 가능한 [`Skills/kartograph/SKILL.md`](../Skills/kartograph/SKILL.md)를
`.claude/skills/kartograph/SKILL.md`에 설치한다. 기존 파일은 `--force` 없이는 덮어쓰지 않는다. 이 skill은
unreachable을 삭제 승인으로 해석하지 않고 limitations, truncation, baseline, 다른 언어의 bridge를 먼저
확인하도록 가르친다.

## 재현성과 파일 경계

`bridges.generatedAt`은 검사한 source들의 최신 수정 시각을 UTC로 기록한 snapshot 시각이다.
source가 없으면 Unix epoch이며 wall clock을 사용하지 않아 같은 파일·시각 입력은 같은 JSON을 낸다.
프로젝트 내부의 source 파일 링크는 실제 대상도 허용 경계 안일 때만 읽고, 디렉터리 링크는 순회하지 않는다.
프로젝트 밖 또는 실제 대상을 해석할 수 없는 source 링크는 부분 보고 없이 실패한다.
경로 인덱스는 이런 실패에서 전체를 미확정으로 돌려 유일 후보를 잘못 단언하지 않는다. `skill`은 프로젝트 루트 alias를 허용하지만
`.claude/skills/kartograph/SKILL.md` 설치 경로 안의 링크는 `--force`여도 거부한다.

탐색·설치는 부모 디렉터리가 실행 중 교체되지 않는 로컬 트리를 전제로 한다. 파일을 열 때 최종 링크를
따라가지 않고 읽기 직전 실제 경계를 재검사하지만, 다른 프로세스의 동시 부모 디렉터리 교체를 격리하는
파일시스템 sandbox는 아니다. `skill --force`는 기존 파일 내용을 직접 덮지 않고 새 파일로 교체해
프로젝트 밖 hard link의 내용도 보존한다.
