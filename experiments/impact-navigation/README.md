# 큰 영향 보고서 탐색 — 2026-09-13

같은 AnkiDroid 그래프에서 **20,699개 영향 후보를 보존**하면서 파일·모듈·경로 요약, 필터, 정렬,
페이지 탐색과 전체 내보내기를 비교했다. 이전 CLI는 `1a60b7e`에서 고정했고 두 CLI가 정확히 같은
base/current snapshot을 읽었다. 새 CLI의 메타데이터가 늘어 전체 출력은 커졌지만 전체 질의 시간은 줄었다.

| 작업 | 반환 후보 | 출력 bytes | CLI 프로세스 시간 |
|---|---:|---:|---:|
| 이전 CLI 전체 출력 | 20,699 | 111,352,103 | 6.406초 |
| 새 CLI `--all` | 20,699 | 122,165,135 | 3.809초 |
| 새 CLI 요약 + 첫 후보 | 1 | 246,400 | 2.677초 |
| 알려진 실패 파일로 필터 | 34 | 347,948 | 2.697초 |

전체 출력과 파일 필터는 각 3회 중앙값이다. 이전/새 전체 출력의 실행 순서를 번갈았고 동일 작업의 JSON 바이트
결정성도 확인했다. 요약과 10개씩의 페이지 탐색은 각 1회이며 페이지 시간은 2.625–2.807초였다.
macOS ARM64·JDK 17의 관측값이고 프로세스 시작, snapshot 읽기·복원, 탐색·출력을 포함한다.
검증기의 JSON decode는 별도 기록하며 source 빌드와 snapshot capture는 포함하지 않는다.

전체 질의 중앙값은 약 40.5% 줄었다. 이 입력에서 후보마다 보존 근거 전체를 반복 검색하던 부분을
시점·선언별 인덱스로 바꾼 효과를 profiler와 동일 출력 비교로 확인했다. 경로 예산을 소진하면
후보·거리·중복 없는 관계 종류를 유지하고 긴 경로 전체의 복제는 중단한다.

## 실패 대상을 찾는 작업

[기존 실제 변경 평가](../change-impact/README.md)의 AnkiDroid #19661에서 실행한 실패 oracle을 재사용했다.
변경 파일은 `AnkiDroid/src/main/java/com/ichi2/anki/AnkiDroidApp.kt`다. 이전 전체 USR 정렬의
9,304번째였던 실패 선언은 해당 테스트 파일의 34개 후보를 경로 길이로 정렬하면 9번째다.
관련 파일 출력량은 이전 전체 출력의 약 1/320이다.

```sh
# 그래프·변경 입력·예산을 고정한 상태에서 source 파일별 후보를 확인한다.
kartograph impact --file AnkiDroid/src/main/java/com/ichi2/anki/AnkiDroidApp.kt \
  --base-graph before.json --graph-file after.json --limit 1 --path-limit 500000 \
  | jq '.summary.observed.byFile'

# 테스트 로그나 앞의 요약에서 고른 파일의 실제 경로를 검토한다.
kartograph impact --file AnkiDroid/src/main/java/com/ichi2/anki/AnkiDroidApp.kt \
  --base-graph before.json --graph-file after.json --path-limit 500000 \
  --affected-file AnkiDroid/src/test/java/com/ichi2/anki/DeckPickerNoExternalFilesDirTest.kt \
  --sort path --limit 10 --offset 0
```

이 작업은 **파일을 알고 시작하는 탐색**이다. 사람이 실제로 9,304개를 읽었다는 측정, 숨긴 실패 대상을 AI가
찾았다는 실험, 수정 성공률 향상이나 테스트 생략의 증거로 해석하지 않는다. 20,699개 후보의 precision 정답 집합도 없다.
이전 29.8 MB 보고서는 single-snapshot·10,000개 출력이므로 위 dual-snapshot 전체 출력과 직접 비교하지 않았다.

## 검증·재현

[검증기](../../Scripts/verify-impact-navigation.py)는 전체 ID 집합, 모든 출력 경로의 양 끝과 간선 방향,
요약의 모듈·파일 수, 실패 파일의 후보 집합, 페이지의 순서·누락·중복을 검사한다. 이 입력의 모듈 정보는
모두 unknown으로 유지됐다. 모듈·파일·test 분류가 시점별로 바뀌는 경우와 긴 경로의 작은 예산은 별도 단위 테스트로 검증한다.
집계 결과에는 입력·CLI JAR·구현 SHA256과 개별 측정값을 [JSON](results-2026-09-13.json)으로 남겼다.

원본 source는 AnkiDroid `8bfadcbe33cb68af5caa471bdce5f80a922500bb`, 테스트/수정 patch는 Kotlin SWE-bench
`b9025d86f7ae634901396766bd61f6d4ae6d2165`의 `ankidroid_Anki-Android-19661`이다.
동일한 주입 테스트 patch를 양쪽 revision에 적용한 native 재현이며 전체 벤치마크 점수가 아니다.
[replay 도구](../../Scripts/replay-impact-task.py)의 JDK 21/Android SDK 실행으로 `before.json`·`after.json`을
다시 만들 수 있다. JDK·의존성·재현 commit 라벨이 바뀐 입력의 바이트 지문과 시간은 이 측정과 구분한다.
제3자 source·patch·snapshot·바이너리는 이 폴더에 복사하지 않는다.

```sh
python3 Scripts/verify-impact-navigation.py \
  --baseline-binary /path/to/previous-distribution/bin/kartograph \
  --binary cli/build/install/kartograph/bin/kartograph \
  --base-graph /path/to/replay/before.json --graph-file /path/to/replay/after.json \
  --changed-file AnkiDroid/src/main/java/com/ichi2/anki/AnkiDroidApp.kt \
  --failure-file AnkiDroid/src/test/java/com/ichi2/anki/DeckPickerNoExternalFilesDirTest.kt \
  --failure-target 'method:com/ichi2/anki/DeckPickerNoExternalFilesDirTest#Fatal error is shown when getExternalFilesDir is null and collection is set but unwritable()V' \
  --output build/reports/navigation-new --repetitions 3
```

실행 전 `JAVA_HOME`을 분석용 JDK 17로 지정한다. 출력 디렉터리는 새 경로여야 한다.
검증기는 저장 입력만 읽고 네트워크나 source 빌드를 수행하지 않는다.
