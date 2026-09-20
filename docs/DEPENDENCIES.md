# 선언 의존성 분석

이 문서는 0.12.0의 P1.1-2 기능을 설명한다. 0.11.0의 `dependencies`는 미사용 의존성의
text/JSON 보고까지 지원한다. `--library`, 전이 의존성 검사, 추가 보고 형식과
Gradle dependency task는 0.12.0부터 사용할 수 있다.

`kartograph dependencies`는 선언 목록·해석된 compile classpath·컴파일 참조를 대조한다.
분석 대상 코드를 실행하거나 빌드 파일을 수정하지 않으며, 발견은 의존성 삭제 승인이 아니다.

## CLI

```bash
kartograph dependencies --classes app/build/classes/java/main --project . \
  --dependencies declared.tsv --resolved-dependencies resolved.tsv \
  --library --report-format json --strict
```

- `--classes`·`--test-classes`는 반복할 수 있다. main/test 참조는 분리하고,
  test에서 확인한 사용을 main 의존성 삭제 후보로 바꾸지 않는다.
- `--library`는 라이브러리의 `api`/`implementation` 배치 조언을 켠다. 앱 모드는
  소비자 API를 추측하지 않으며 미사용·미선언 의존성만 검사한다.
- `--resolved-dependencies`가 없으면 전이 의존성 소유권을 검사하지 않았다는 한계를 남긴다.
- 보고 형식은 `text`, `json`, `sarif`, `gradle`, `github-actions`, `markdown`이다.
- 기본은 보고 후 exit 0, `--strict`는 발견이 있으면 1이다. 사용 오류는 64,
  손상된 목록/클래스/JAR, 누락된 입력, classfile 없는 root는 2다. 부분 결과를 쓰지 않는다.

## 입력 목록

두 목록은 `coordinate<TAB>scope<TAB>artifact` TSV를 쓴다. `declared.tsv`는 직접 선언,
`resolved.tsv`는 실제로 선택된 compile classpath의 직접·전이 artifact 전체다.
빈 줄과 `#` 주석을 허용하고 중복 항목은 한 번만 판정한다. 경로는 `--project` 기준이며
절대경로도 입력할 수 있지만 보고서는 절대경로의 파일 이름만 표시한다.

```text
example:public-api:1.0	implementation	libs/public-api.jar
example:transitive:2.0	implementation	libs/transitive.jar
```

scope는 `api`, `implementation`, `compileOnly`, `compileOnlyApi`, `runtimeOnly`,
`annotationProcessor`, `kapt`, `ksp`, `testImplementation`, `testCompileOnly`, `testRuntimeOnly`다.
processor/runtime scope는 compile 참조만으로 사용을 판정하지 않는다. test scope도
`--test-classes`가 없으면 판정을 보류한다. `compileOnly`/`compileOnlyApi`의 runtime 의미를
바꾸는 scope 조언은 하지 않는다.

artifact는 class directory, JAR, AAR를 지원한다. AAR의 `classes.jar`와 `libs/*.jar`를 읽고,
리소스 전용 artifact는 class가 없다는 계수로 남긴다. 내부 JAR는 중앙 디렉터리를 검증하며
256 MiB를 넘으면 실패한다. multi-release JAR는 기존 규칙대로 base class만 사용한다.
같은 component/scope에 여러 artifact가 있으면 합쳐서 판단한다.

## 발견과 근거

| ruleId | 의미 | 주요 근거 |
|---|---|---|
| `unused-dependency` | 공급한 main/test 컴파일 참조에서 해당 선언 사용을 관찰하지 못함 | 선언 scope와 artifact |
| `dependency-scope-mismatch` | 라이브러리의 관찰된 ABI 노출이 api/implementation 선언과 다름 | `suggestedScope`, `evidenceClasses` |
| `undeclared-dependency` | 실제 참조 타입의 artifact가 해당 compile scope에 직접 선언되지 않음 | 해석된 component, `evidenceClasses`, 확정할 수 있을 때만 `suggestedScope` |

JVM descriptor·Signature·annotation·명령 참조와 Kotlin metadata의 가시성·타입·inline 본문을
사용한다. 제네릭 타입은 descriptor에서 지워져도 Signature에 남으면 복원한다. Kotlin internal,
private setter/backing-field annotation, private 소유 타입은 공개 API로 승격하지 않는다.
Java의 non-private/compiler-visible 선언을 ABI 후보로 본다. 의미 기준은
[Gradle Java Library의 API/implementation 구분](https://docs.gradle.org/current/userguide/java_library_plugin.html#sec:java_library_recognizing_dependencies)이다.

동명 class가 여러 component 또는 프로젝트 출력에 겹치면 소유자를 추측하지 않는다.
선택 버전이 달라도 같은 Maven module의 선언 여부를 대조한다. Kotlin metadata·typealias 소유자·
감싸는 클래스·internal 공개 경로를 복원할 수 없거나 Java module API를 분류하지 못하면,
부재에 근거한 unused/배치 축소를 보류한다. 미선언 사용 근거는 남기되 API scope를 모르면
`suggestedScope`를 생략한다. 모든 형식에 동일한 limitations가 포함된다.

## Gradle

```kotlin
plugins {
    id("io.github.ictechgy.kartograph") // 이 기능을 포함하는 개발 배포본
}
kartograph {
    reportFormat.set("json")
    dependencyIncludeTests.set(true) // 선택: 테스트를 실행하지 않고 컴파일 결과만 포함
    strict.set(true)
}
```

- JVM: `kartographDependencies`, 보고서 `build/reports/kartograph/jvm-dependencies.txt`.
- Android: `kartographDependencies<Variant>`(예: `kartographDependenciesDebug`),
  보고서 `build/reports/kartograph/<variant>-dependencies.txt`.
- `java-library`/Android library에서는 API 조언을 켜고 앱에서는 끈다.
- source set·public Variant/Artifact API로 class 출력을 연결한다. 의존성은 execution용
  provider에서 해석하며 configuration 단계에서 resolve하지 않는다.
- 선언 scope·선택된 artifact·class 바이트는 task 입력이다. configuration cache와
  up-to-date 검사를 사용하며 입력이 바뀌면 다시 분석한다. report를 쓴 뒤 strict 실패를 낸다.
- 알 수 없는 선언 scope나 선택 classpath에 artifact가 없는 플랫폼/processor 선언은
  판정하지 않고 계수·한계를 남긴다. processor별 생성 코드 귀속은 별도 후속이다.

## 한계와 후속

reflection 문자열·리소스 기반 사용·SOURCE-retention annotation·processor별 귀속은 완전하게
복원하지 않는다. 외부 typealias metadata, 일부 internal/inline 생성 타입과 Java module의
exports/reexports도 추가 근거가 필요하다. signature 중첩은 512단계까지 지원한다.
이 관찰 범위의 부재를 dependency 제거 안전성으로 읽지 않는다. baseline/suppress는 후속이다.
