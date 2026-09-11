# 변경 영향 점검 채점 — 2026-09-11

사용자 목표는 수정 전 영향 점검, 사람·AI 공통 질의, 정적으로 복원 가능한 런타임 의존성, CI 갱신 속도다.
[구현 계약](../../docs/IMPACT.md)과 [계획](../../docs/IMPACT-PLAN.md)에 맞춰 실제 변경 두 개를 재현했다.

## 원천과 실행 범위

Kotlin SWE-bench의 고정 [revision b9025d8](https://github.com/Kotlin/kotlin-swe-bench/tree/b9025d86f7ae634901396766bd61f6d4ae6d2165)에서
AnkiDroid 6개·OkHttp 2개의 메타데이터를 조사했다. JVM 라이브러리 1개와 Android 앱 1개의 초기 코호트로
OkHttp #6887과 AnkiDroid #19661을 선택했다. #8968/#18903은 fail-to-pass 기대값이 없어서 이 recall 점수 대상에서
분리했다. 나머지 4개는 실행하지 않았으며 재현 불가라고 판단한 것은 아니다. 106개 전체 벤치마크 점수가 아니다.

Docker daemon을 사용할 수 없어 원래 source revision과 test/fix patch를 native Gradle/JVM으로 실행했다.
외부 `prepare.sh`/`test.sh`를 실행하지 않고, 새 로컬 checkout에 같은 test patch를 적용한 뒤 source fix만 바꿨다.
특히 OkHttp의 실패 테스트는 추가된 테스트이므로 **원본 저장소에 이미 있던 테스트의 사전 예측**이라고 부르지 않는다.
각 저장소의 원래 라이선스를 유지하며 이 저장소에는 제3자 소스/patch나 바이너리를 복사하지 않는다.

| 과제 | 원본 source | 실행/입력 |
|---|---|---|
| [OkHttp #6887](https://github.com/square/okhttp/pull/6887) | `1ff16232f86d2b302840a3bb2f9d386e94374753` | JDK 11, Gradle 7.2, `:okhttp-sse:test`; 8 compiled roots. 재현 script 최종 점수는 외부 dependency JAR 26개를 사용한다. |
| [AnkiDroid #19661](https://github.com/ankidroid/Anki-Android/pull/19661) | `8bfadcbe33cb68af5caa471bdce5f80a922500bb` | JDK 21, Gradle 9.2.0, `:AnkiDroid:testPlayDebugUnitTest --tests com.ichi2.anki.DeckPickerNoExternalFilesDirTest`; 9 compiled roots와 SDK 36 header. 다른 dependency header는 제공하지 않은 범위다. |

## 실제 회귀와 영향 후보

| 항목 | OkHttp | AnkiDroid |
|---|---:|---:|
| 실행한 테스트 | 24 | 3 |
| 수정 전 실패 | 1 (`UninitializedPropertyAccessException`) | 1 (`SystemStorageException`) |
| 정답 source patch 후 | 24 통과 | 3 통과 |
| 영향 보고서가 포함한 실패 테스트 | 1 / 1 | 1 / 1 |
| 파일 기준 영향 후보 | 55 | 20,699 |
| 후보에 포함된 실행 대상 테스트 | 24 / 24 | 3 / 3 |
| 저장 snapshot preflight 중앙값 / 표본 p95 | 0.308 / 0.312초 | 3.158 / 3.204초 |
| base/current 비교 중앙값 / 표본 p95 | 0.431 / 0.491초 | 5.848 / 6.319초 |

각 질의는 5회 실행했고 JSON 결정성·잘림 없음·실패 대상 경로를 확인했다. 프로세스 시작, snapshot 읽기/복원,
영향 탐색과 JSON 출력을 포함하며 source 빌드는 제외한다. p95는 이 작은 표본의 nearest-rank 값이다.
AnkiDroid는 전역 application 초기화 변경이어서 후보가 넓다. 후보가 실제로 불필요한지의 정답 집합은 없으므로
이 수치로 precision/오탐률을 만들지 않는다. 두 사례 모두 테스트를 줄였다는 증거는 없고, 테스트 생략도 수행하지 않았다.
회귀 테스트는 관측된 영향의 하한이며 모든 실행 경로의 정답이 아니다.

## 대형 입력과 저장 형식

AnkiDroid 입력은 **42,453 nodes / 371,600 edges / 74,127 external calls**다. v1 표현은 210,092,733 bytes로
기존 64 MiB 제한을 넘었다. `snapshot --compact` v2는 **42,271,956 bytes**로 같은 사실을 저장한다(약 79.9% 감소).
간선만 압축한 초기 시도는 약 111 MB여서 부족했고, 반복 문자열 사전과 node/call/edge 행의 인덱스를 사용했다.
최종 실제 snapshot의 parse→render 바이트 일치를 확인했고, 단위 테스트는 모든 그래프 사실·보존·baseline의 v1/v2 동등성을
확인한다. v1은 기본 형식으로 유지하며, query 문서의 필드는 바꾸지 않았다. 입력의 64 MiB 상한도 유지한다.
전체 capture는 약 3.4초였다. 이는 증분 인덱싱이나 전체 CI가 3.4초라는 뜻이 아니다.

## 런타임 근거와 남은 누락

`verify-impact-runtime.py`는 실제 javac/실행과 영향 경로를 대조한다. 직접 호출, 등록 callback, 등록 해제 callback,
static helper의 reflection 이름, reflection method 호출, reflection field의 Class 값 6개를 사용했다.
직접 호출·callback·helper reflection·method reflection은 해당 main 경로를 찾았다. 해제 callback도 잠재적 후보로
남았고 미사용 대조군은 main과 연결되지 않았다. **reflection field 값→생성자 경로는 여전히 놓치며 측정된 한계를 남긴다.**
따라서 모든 runtime 의존성을 해결했다고 주장하지 않는다.

## AI 스킬/질의 채점

숨겨진 테스트·정답 patch를 제외한 SSE production graph와 공개 Kotlin source 5개로 별도의 preflight 질문 두 개를 만들었다.
변경 대상은 `RealEventSource.processResponse`와 `ServerSentEventReader.processNextEvent`이며, 필요한 공개
`EventSources` 진입점은 직접 `processResponse`와 비동기 factory 경로인 `createFactory`다.

같은 Claude Opus 5, low effort에 읽기 전용 JSON action 프로토콜을 제공했다. 양쪽 모두 source read/literal search를
사용할 수 있고, 실험군만 스킬과 실제 impact 질의를 사용할 수 있다. 호출은 최대 4회, 직접 filesystem/shell 도구는
비활성화했다. 매 대화/질의 결과는 `packet-ask`로 정제하고 별도 임시 디렉터리의 CLI에 전달했다.

| 질문 | source arm | skill + impact arm |
|---|---|---|
| response | 2/2 진입점, read 2회, 14.4초 | 2/2 진입점, impact 1회, 13.1초 |
| reader | 2/2 진입점, read 2회, 13.3초 | 2/2 진입점, impact 1회, 14.6초 |

AI 입력은 별도의 production-only snapshot이다. 결과의 `inputs`는 snapshot SHA256, 실제 production class root의 파일 수/지문,
소스 파일별 SHA256을 기록한다. graph의 class owner 집합을 두 production root의 class 파일 집합과 대조하고 숨겨진
`EventSourcesHttpTest` 선언의 부재도 확인했다. 따라서 native 채점용 snapshot과 freshness 수치가 다를 수 있다.

정확도는 동률이고 양쪽 모두 추가 검토 필요성을 유지했다. 이번 두 질문에서 도구 호출 수는 줄었지만 일반적인 AI 성능
향상을 증명하지 않는다. 공식 SWE-bench 문제 해결 점수나 사용자 생산성 실험도 아니다. 모델 요청에는 provider 내부의
Haiku 보조 사용량도 기록됐으며 비교 주 모델은 동일했다. 시간에는 packet/CLI/model 대기까지 포함한다.

## 재현 도구

- [replay-impact-task.py](../../Scripts/replay-impact-task.py): 두 과제를 새 checkout에서 준비·빌드·동일 oracle 적용·채점한다.
  기존 디렉터리는 덮어쓰지 않는다. OkHttp 과제로 script 자체도 별도의 새 checkout에서 재실행했다.
- [score-impact.py](../../Scripts/score-impact.py): 실제 before/after 테스트 JSON과 snapshot을 받아 공통 CLI로 채점한다.
- [verify-impact-runtime.py](../../Scripts/verify-impact-runtime.py): 실행/미사용 대조군과 모델 origin을 확인한다.
- [evaluate-impact-agent.py](../../Scripts/evaluate-impact-agent.py): 위 제한된 AI A/B 비교를 수행한다. 외부 Claude 전송을 포함한다.
- [verify-snapshot-roundtrip.py](../../Scripts/verify-snapshot-roundtrip.py): 실제 snapshot 전체의 바이트 왕복과 canonical v1/v2 크기를 검증한다.
- [summarize-impact-scores.py](../../Scripts/summarize-impact-scores.py): 원시 score·runtime·AI·roundtrip 문서를 추가 가공 없이 합친다.
- [기계 판독 결과](results-2026-09-11.json): 이 조합 script가 생성한 관측값, 입력 SHA256, 경로, 코호트 제외/미실행 범위.

```sh
python3 Scripts/replay-impact-task.py --task square_okhttp-6887 \
  --work build/impact-replay-new --binary cli/build/install/kartograph/bin/kartograph \
  --build-java-home /path/to/jdk11 --analysis-java-home /path/to/jdk17 \
  --classpath-file /path/to/external-dependency-jars.txt
```

AnkiDroid에는 build JDK 21, Android SDK 환경과 `--android-jar`를 제공한다. dependency 목록은 해당 Gradle
test runtime configuration에서 얻은 외부 JAR 경로를 한 줄씩 기록한 것이다. 본문 수치는 앞서 명시한 범위의 결과다.
