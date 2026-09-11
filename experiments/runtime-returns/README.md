# Static return flow comparison

2026-09-11 측정. JVM bytecode와 Kotlin metadata 원천을 유지하면서 프로젝트 static helper의 String/Class 반환값을
추적한 결과다. 제품에 비교 도구의 의존성을 추가하지 않는다. 변경 전 기준은 `73dcad1`이고 구현은 **미배포 소스**다.

## 입력과 비교 방법

새로 작성한 [Java 4개·Kotlin 1개](fixtures)의 `Entry.main("Used")`를 먼저 실행해 `USED` 출력을 확인한다.
각 표본에는 실행되지 않는 `Unused`가 있다. 전체 입력 공간의 runtime coverage나 Android lifecycle 검사는 아니다.

- javac 17.0.20, Kotlin 2.4.10, macOS arm64. Kotlin 의존성은 같은 stdlib와 JetBrains annotations를 제공한다.
- kartograph 변경 전/후에 동일한 class directory와 `-keep class probe.Entry { *; }`를 준다. 각 Used/Unused 질의를
  3회 실행해 전체 JSON의 결정성도 확인한다. 문자열 원문을 새로운 보고 필드로 내보내지 않는다.
- [SearchDeadCode 0.21.0](https://github.com/KevinDoremy/SearchDeadCode/releases/tag/v0.21.0)은 같은 소스,
  `entry_points: ["probe.Entry"]`, 빈 exclude, incremental cache 없이 `--explain`한다. 실제 parsed 결과를 확인한다.
- [R8 9.4.17](https://dl.google.com/dl/android/maven2/com/android/tools/r8/9.4.17/r8-9.4.17.pom)은 조회 당시 Google Maven의
  최신 안정판이다. `--release --classfile --no-desugaring`, JDK 17, 이름 변경 금지로 실행한다. 같은 root의
  shrink-only, 최적화 켜기, root에도 `allowoptimization` 허용을 각각 검사한다. `-dontshrink` 실행 대조군도 둔다.
  class가 남았는지와 **변환 후 실행이 보존됐는지**는 별개로 기록한다. DEX backend나 R8 Configuration Analyzer
  전체를 평가하지 않았다. 도구 목적과 모델이 다르므로 동일 알고리즘 정확도 순위로 읽지 않는다.

## 관측 결과

모든 표본은 원래 실행에서 Used를 사용했다. `unknown_argument`의 실행 인자 값은 정적 엔진에 제공하지 않았다.

| 표본 | 변경 전 kartograph Used | 변경 후 Used / Unused | SearchDeadCode Used / Unused | R8 처리 후 실행 |
|---|---|---|---|---|
| Java literal helper | unreachable | reachable / unreachable | ALIVE / ALIVE | ClassNotFoundException |
| Java 인자 전달·helper 연결 | unreachable | reachable / unreachable | ALIVE / ALIVE | ClassNotFoundException |
| Kotlin top-level helper 연결 | unreachable | reachable / unreachable | DEAD / DEAD | ClassNotFoundException |
| Java overload 구분 | reachable | reachable / unreachable | ALIVE / ALIVE | 보존 |
| 알 수 없는 runtime 인자 | unreachable | unreachable / unreachable + 계량 한계 | ALIVE / ALIVE | ClassNotFoundException |

위 R8 실행 결과는 세 shrinking 설정에서 같았다. `-dontshrink` 대조군은 **5/5 실행 보존**이며 Unused도 남았다.
overload 표본은 최적화 시 Used class 자체가 사라져도 실행을 보존했다. 이 때문에 class 잔존만으로 비교하지 않는다.
추가 keep metadata를 제공하면 R8 결과는 달라질 수 있다.

확인한 강점은 **이 표본에서 helper 뒤에 숨은 사용 경로 3개를 새로 복원하면서 미사용 대조군 5개를 계속 구분한 것**이다.
Kotlin 표본은 SearchDeadCode가 놓쳤고 Java 표본의 Unused는 과보존했다. runtime 입력이 unknown인 사례는 개선 후에도
놓치므로 전반적인 정확도 우위나 삭제 안전성으로 일반화하지 않는다. 상수 문자열을 임의로 보존 root에 추가하지 않는다.

## 회귀와 비용

CLI compiler 테스트는 별도 Java/Kotlin 표본으로 실제 생성자 실행, 분기 합류, String/Class 인자·반환값, long 인자의 JVM
local slot, overload, 호출 문맥 분리, virtual 호출 보류, mutable field·unknown 분기, 재귀·깊이·값 집합·문맥 수 제한,
중복 class root의 첫 입력 선택을 검사한다. 원래 `factory_name` 차등 코퍼스 기대값도 실행 증거에 따라 갱신했다.

고정 nowinandroid `12f80da6518e161ed16a06a68e71fb8a873576d6`, demoDebug, class root 22개를 양쪽 배포 디렉터리로
순차 재검증했다. 기본 진단 **4개**, private 모드 **2개**, multipreview **8개**, 생성 출처와 미사용 대조군 계약을 유지했다.
각 3회 측정한 심볼 질의 중앙값은 변경 전 **1.021–1.072초**, 변경 후 **1.051–1.080초**였다. 이 측정은 빌드를 제외하고
프로세스 시작·출력을 포함한다. 순서/환경 변동이 있으므로 속도 향상을 주장하지 않는다. 저장 질의와 live result의 일치도 유지했다.

기계 판독 요약은 [results/2026-09-11.json](results/2026-09-11.json)이다. 원시 명령·stdout/stderr·시간은 재현 시 생성하는
보고서에 남기고, 컴파일된 class와 제품/비교 도구의 SHA256을 기록한다.

## 재현

도구를 별도로 준비한 뒤 실행한다. 스크립트는 비교 도구를 다운로드하지 않는다. `--baseline`은 변경 전 CLI 전체 배포
디렉터리의 실행 파일, `--binary`는 현재 `:cli:installDist` 산출물이다. R8 JAR은 [공식 안내](https://r8.googlesource.com/r8/+/refs/heads/main/README.md)의 Google Maven에서 얻고 체크섬을 대조한다.

```sh
export JAVA_HOME=/path/to/jdk-17
python3 Scripts/compare-runtime-returns.py \
  --baseline /path/to/baseline/bin/kartograph \
  --binary cli/build/install/kartograph/bin/kartograph \
  --searchdeadcode /path/to/searchdeadcode \
  --r8 /path/to/r8-9.4.17.jar \
  --output build/reports/runtime-return-comparison.json
```

Kotlin 비교 표본은 임시 Gradle 프로젝트에서 저장소의 compiler 버전과 dependency verification metadata로 컴파일한다.
`--offline` 빌드가 가능한 캐시를 먼저 준비해야 한다. runtime 테스트는 새 임시 디렉터리 안에서만 실행한다.
