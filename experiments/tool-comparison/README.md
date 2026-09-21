# 0.14.0 반복 조사 비용 재측정

2026-09-21 배포 kartograph 0.14.0과 당일 GitHub latest인 SearchDeadCode 0.21.0을 같은 NIA revision
`12f80da6518e161ed16a06a68e71fb8a873576d6`에서 실행했다. 기존 0.7.0 비교를 현재 성능으로 읽지 않기 위한 후속이다.

## 입력과 방법

- 공개 원본 소스 163개와 기존 컴파일 입력 파일 727개의 해시가 이전 inventory와 일치했다. 22개 class root와
  문서화된 demoDebug source 범위를 사용한다. SearchDeadCode에는 생성 소스를 넣지 않으며 kartograph에는 생성 class가 포함된다.
- 두 배포본의 버전·해시는 [tools.json](tools.json)에 있다. SearchDeadCode 바이너리는 최신 GitHub asset digest와 일치한다.
  kartograph는 앞서 다운로드·검증한 0.14.0 배포본을 사용했다.
- 오래된 classpath 경로 226개 중 179개가 없어 같은 프로젝트에서 Gradle 9.6.1로 재해석했다. 현재 226개 JAR의 해시는
  raw 입력 기록에 보존했다. **이전 classpath와 바이트가 같다고 검증한 것은 아니다.** 앱 빌드·테스트는 요청하지 않았다.
- 도구 실행 순서를 번갈아 세 번 반복한다. 세 선택자가 SDC graph 안에서 유일한지 확인했고 kartograph 질의는 `found`를 확인했다.
  클래스뿐 아니라 interface/object도 허용하며, 존재하는 선언의 `no references`는 정상 응답이다.
- kartograph CLI/MCP는 depth 2·limit 10으로 고정하고 **9개 MCP 응답 문서 모두 해당 대상의 첫 번째 CLI 문서와 완전히 같은지** 검사했다.
  MCP 시작 비용과 지속 호출 비용을 분리했다. SDC `--refs-of`는 다른·더 작은 보고서이므로 동등 작업 성능 순위로 쓰지 않는다.

## 완주한 실행의 중앙값

| 작업 | kartograph CLI | SearchDeadCode CLI | kartograph 지속 MCP |
|---|---:|---:|---:|
| dead scan | 1.054 s | 0.310 s | 측정 안 함 |
| 저장 MainActivityViewModel 질의 | 387.5 ms | 6.9 ms | 11.3 ms |
| 저장 NewsResourceDao 질의 | 377.3 ms | 7.0 ms | 6.4 ms |
| 저장 ListToMapMigration 질의 | 382.5 ms | 6.8 ms | 5.2 ms |

MCP 시작+initialize는 362.6 ms였다. 숫자는 이 입력과 환경의 3회 관측이며 일반 정확도나 AI 생산성의 순위가 아니다.
SearchDeadCode MCP는 이번에 시간 측정하지 않았다. 전체 반복 값은 [results.json](results.json)에 있다.

반복 조사에서는 기존 MCP를 사용하는 안내를 먼저 개선할 근거가 됐다. 이 측정만으로 JVM 시작·snapshot 파싱 각각의
비용을 분리할 수 없으므로 profiler 없이 특정 함수가 병목이라고 단정하지 않는다. 새 daemon이나 증분 엔진을 도입하지 않았다.

## 실패와 한계

1. 최초 preflight는 삭제된 과거 classpath 경로 때문에 실패했다.
2. offline Gradle 재해석은 isolated-projects/configuration-cache 조합, 다음 시도는 캐시에 없는 plugin 때문에 실패했다.
   기존 비교와 같은 Gradle 9.6.1로 해석한 시도는 성공했다. 원본 source는 수정하지 않았다.
3. 비교 harness가 SDC의 정상 `no references`를 실패로 오인한 실행과 interface/object를 class로 제한한 실행을 보존했다.
   이후 실제 node의 유일성과 응답을 검사하도록 고쳤다. 제품 실패로 분류하지 않는다.
4. 성공한 준비·중간 실행 때문에 **입력은 이미 warm 상태**였다. cold 시작, 설치, 앱 빌드, 수정 후 capture 비용은 측정하지 않았다.
   실패 시도의 성공한 부분 측정도 남겼으며 빠른 실행만 선택해서 합산하지 않는다.
5. 성능 snapshot은 수동 캡처이고 freshness는 `unverified`(`missing-external-input`)다. compiler witness도 없으므로
   binding을 연결하는 것만으로 current-build 근거가 되지 않는다. 이 데이터를 matched AI 효용 실험으로 쓰지 않는다.
6. 소스·컴파일 입력·보존 정책·출력 범위가 다른 도구의 finding 수를 정확도로 비교하지 않는다.

[provenance.json](provenance.json)에 모든 시도·실패와 원본 해시를 기록했다. 전체 원본은 로컬 공통 Git 디렉터리의
`competitive-evidence-20260921/comparison-raw.tar.gz`에 무손실 보존한다. `cleanup.json`은 모든 member의
원본 해시·복원 방법을 기록한다. 공개 기록에는 개인 절대경로를 넣지 않는다.

## 재현

`run.py`는 기존 NIA fixture report의 `input-manifest.json`, `searchdeadcode-scope.yml`과
`work/nowinandroid`를 받는다. source/class 해시와 revision이 다르면 실패한다. 출력 디렉터리는 새 경로여야 한다.
`source-scope.json`은 과거 범위의 수동 확인용 기록이며 harness가 읽지 않는다. SDC 설정은 해시를 기록하지만
variant 의미까지 자동 판정하지 않으므로 실행 전에 `targets`·`exclude` 목록을 검토한다.

```sh
python3 experiments/tool-comparison/run.py \
  --fixture-report /path/to/android-fixture-report \
  --cli /path/to/kartograph-0.14.0/bin/kartograph \
  --sdc /path/to/searchdeadcode-macos-aarch64 \
  --jdk /path/to/jdk17 --sdk /path/to/android-sdk \
  --classpath-file /path/to/current-classpath.txt \
  --output /path/to/new-evidence-directory
```

원본 fixture를 빌드하는 절차는 [공개 표본 검증](../../docs/PUBLIC-VALIDATION.md)을 따른다. 이전 실행에서 보존한
컴파일 바이트와 새 빌드가 일치하지 않으면 기존 manifest를 조용히 바꾸지 않고 새로운 입력 코호트로 기록한다.

## 새 소비 프로젝트의 안내 검증

별도 최소 Java `:app` 프로젝트에서 배포 plugin JAR로 task를 등록하고 문서의 모듈별 경로를 실행했다.
`tasks --all`에서 `kartographSnapshot`을 찾았고, 최초 capture·verify, 소스 변경 후 stale(exit 1),
재capture 후 matched, saved query found와 MCP freshness matched/query found를 확인했다.
재capture는 configuration cache를 재사용했다. [실행 기록](onboarding.json)

최초 capture 4.24초, 수정 후 capture 3.80초는 이 작은 fixture의 관측이다. 검증된 다운로드 JAR와 기존 전역 캐시를
사용했으므로 cold Portal 설치·실제 앱의 첫 사용 시간으로 일반화하지 않는다. 소스·snapshot·bindings는 로컬 근거로 보존한다.
