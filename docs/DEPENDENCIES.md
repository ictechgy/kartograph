# 선언 의존성 분석

`kartograph dependencies`는 선언된 dependency와 색인된 bytecode 참조를 대조해 참조가 없는 dependency를
`unused-dependency`로 보고한다. 컴파일된 class root와 선언 목록만 읽으며 빌드·커버리지·processor를
실행하지 않는다. 판정은 참조 부재의 측정이며 dependency 삭제 승인이 아니다.

## 사용

```bash
kartograph dependencies --classes app/build/tmp/kotlin-classes/debug \
  --project . --dependencies dependencies.tsv --report-format json
```

- `--classes`는 반복할 수 있고 `--test-classes`로 test root를 더하면 test bytecode 참조도 함께 센다.
- `--strict`는 finding이 있으면 exit 1, 없으면 exit 0이다. 사용 오류는 64, 도구 실패는 2다.
- `--report-format`은 지금 `text`와 `json`만 지원한다(다른 형식은 후속).

## 입력 파일

`--dependencies <file>`은 `coordinate<TAB>scope<TAB>artifact` TSV다. artifact 경로는 `--project`
기준으로 해석한다.

```
# coordinate	scope	artifact
com.squareup.okhttp3:okhttp:4.12.0	implementation	build/libs/okhttp-4.12.0.jar
androidx.annotation:annotation:1.9.1	compileOnly	libs/annotation-1.9.1.jar
```

- 빈 줄과 `#` 주석을 허용하고, 같은 항목이 중복되면 한 번만 판정한다.
- scope: `api`, `implementation`, `compileOnly`, `runtimeOnly`, `annotationProcessor`, `kapt`, `ksp`,
  `testImplementation`, `testCompileOnly`, `testRuntimeOnly`.
- `api`·`implementation`·`compileOnly`는 판정한다. `testImplementation`·`testCompileOnly`는
  `--test-classes`를 준 경우에만 판정한다. `runtimeOnly`와 processor scope는 세기만 하고 판정하지 않는다.
- 알 수 없는 scope·빈 필드·잘못된 열 수·빈 목록은 부분 적용 없이 exit 2로 실패한다.
- artifact가 없거나 class directory/JAR이 아니면 exit 2다. 오류에는 절대경로를 싣지 않는다.

## 판정

artifact의 class 중 앱 bytecode가 하나도 참조하지 않으면 unused다. 참조는 classfile 간선 전체
(호출·field 접근·type 참조·annotation·상속·method/field descriptor)에서 모은다. classfile이 없는
artifact는 판정하지 않고 개수만 보고한다. 출력의 artifact는 project-relative 경로를 그대로 쓰고
절대경로는 파일 이름만 남기며, 한계 목록은 모든 형식에 함께 실린다.

## 한계

- 전달한 class root의 참조만 측정한다. reflection 문자열, runtime class loading, SOURCE-retention
  annotation, annotation processor가 만든 코드, resource 기반 사용은 해석하지 않는다.
- signature에만 있는 generic type 인자는 bytecode descriptor에서 지워져 보이지 않는다.
- test root를 주지 않으면 test scope는 판정하지 않는다. 주더라도 main scope dependency가 test에서만
  쓰이면 unused로 보일 수 있다.
- 같은 class 이름이 앱과 dependency에 겹치면 그 dependency를 사용됨으로 볼 수 있다(과소 보고).
- baseline·suppress·confidence 같은 `dead` 보조 장치는 아직 없다.

## 아직 없는 것 (후속)

- api/impl 오배치와 undeclared(전이) 사용 판정
- Gradle plugin 자동 배선(`kartographDependencies<Variant>`)
- sarif/gradle/github-actions/markdown 형식과 baseline/suppress
- processor scope의 생성 코드 귀속
