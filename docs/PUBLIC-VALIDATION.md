# v0.2 public validation

2026-09-05에 공개 `android/nowinandroid`의 commit
`12f80da6518e161ed16a06a68e71fb8a873576d6`를 다시 빌드했다. JDK 17, AGP 9.3.2,
Kotlin 2.3.0, protobuf 4.29.2, `:app:assembleDemoDebug` 기준이며 빌드는 1분 48초에 성공했다.
upstream의 deprecated API와 선택적인 window extension instrumentation 경고는 남아 있다.

## 발견과 수정

Hilt 변환 전 Kotlin/javac root만 입력하면 앱의 실제 superclass와 Hilt component가 빠진다.
측정에는 Android module의 **변환 후 dirs와 jars**, JVM module의 main class root 22개를 사용한다.
runtime dependency는 Gradle artifact view의 `android-classes-jar`로 읽고 Android API 36을 추가한다.
test·benchmark·catalog·build-logic은 제외한다. resource 입력은 app의 main res, manifest는 merged manifest다.
이것은 해당 variant와 입력 집합의 측정이며 모든 variant 또는 모든 resource overlay의 검증이 아니다.

완전한 hierarchy를 넣기 전 private 모드의 keepclassmembers는 명시적인 입력 누락 오류를 반환했다.
이 실패를 정상 결과로 바꾸거나 빈 hierarchy로 대체하지 않았다.

확인된 Hilt/Dagger Java 생성물의 annotation이 classfile에 남아도 `synthesized`로 표시되지 않았다.
`DaggerGenerated`, `OriginatingElement`, aggregation metadata marker를 인덱싱하고 실제 enclosing
관계로 중첩 생성물까지 표시하도록 수정했다. Hilt application annotation에서 정확한 component sibling을
찾아 기존 generated-code 보존 정책에도 연결했다. `_Factory` 이름만 가진 사용자 코드는 제외하지 않는다.

`fixtures/generated-code-corpus`는 marker의 CLASS retention을 javac로 재현한 최소 표본이다.
수정 전 생성 Factory·Nested·Deep가 잘못 보고됐고 수정 후 사용자 `User_Factory`만 보고된다.
실제 processor 산출물과의 대조는 공개 nowinandroid verifier의 4개 Hilt 정점 존재/생성 표시/비보고 검사다.

| 같은 입력의 결과 | 수정 전 | 수정 후 | 수정 후 분석 시간 |
|---|---:|---:|---:|
| 기본 class 진단 | 169 | 25 | 1.068초 |
| private opt-in 전체 진단 | 104 | 16 | 0.887초 |

이는 **진단 수**이지 정확도나 안전한 삭제 건수가 아니다. opt-in 감소는 keepclassmembers와 외부 member
진입점의 보수적 보존 때문이다. private member 진단은 이 표본에서 0건이었다. 해당 수치를 유지하는 것을
정확도 gate로 삼지 않고 확인된 Hilt 정점만 회귀 검사한다. 두 모드 모두 CLI 진입부터 report까지 측정했다.

## 남은 한계와 다음 정확도 작업

- protobuf의 generated Java wrapper와 Kotlin DSL은 위 Hilt marker가 없으며 일부가 계속 보고된다.
  generated-source/module의 출처를 명시적으로 전달하는 설계가 필요하다. 이름만으로 모든 `*Kt`를 숨기지 않는다.
- `DatabaseMigrations`의 바깥 container와 `SearchUiStatePreviewParameterProvider`의 PreviewParameter annotation
  인자 참조는 후속 작업으로 해결했다. 중첩 class의 바깥 container 참조와 어노테이션 값·parameter annotation의
  class 참조를 도달성에 포함한다(아래 CHANGELOG와 `LIMITATIONS.md`). 남은 계열은 protobuf generated-source 출처다.
- `ListToMapMigration`은 조사한 main source에서 등록을 찾지 못했고 test에서만 호출됐다. 이는 main 그래프의
  진단과 일치하지만 삭제 승인이 아니며 test 포함 여부를 사용자가 정해야 한다. test-only 사용 분류는
  `dead --test-classes <root>`로 제공한다(표시일 뿐 억제나 삭제 승인이 아니며 CLI 전용이다).
- 따라서 이 공개 프로젝트에서 **오탐 0을 달성했다고 주장하지 않는다**. 나머지 계열도 최소 코퍼스부터
  추가하는 다음 단계다. 현재 출시에 대한 안전 해석은 `LIMITATIONS.md`와 같다.

## 재현 (저장소 checkout에서)

공개 표본을 위 commit으로 checkout하고 별도 디렉터리에서 빌드한다. secret이나 개인 프로젝트는 필요 없다.
아래 경로는 자리표시자이며 JDK 17·Android SDK를 먼저 설정한다.

```bash
./gradlew :cli:installDist
./gradlew -p /path/to/nowinandroid :app:assembleDemoDebug
./gradlew -p /path/to/nowinandroid \
  -I /path/to/kartograph/Scripts/public-sample-classpath.gradle \
  --no-configuration-cache -Dorg.gradle.unsafe.isolated-projects=false \
  :app:kartographValidationClasspath
python3 Scripts/verify-public-sample.py \
  --project /path/to/nowinandroid \
  --binary cli/build/install/kartograph/bin/kartograph \
  --android-jar /path/to/android-sdk/platforms/android-36/android.jar
```

verifier는 네트워크·build·checkout을 실행하지 않는다. commit/추적 파일 변경 여부/22개 root/의존성 파일/
Hilt 정점의 존재와 synthesized 표시/진단 제외/limitations를 검사하고 집계 JSON만 출력한다.
classpath 파일에는 로컬 절대경로가 있으므로 build 디렉터리에만 두고 커밋하거나 리뷰에 전송하지 않는다.
