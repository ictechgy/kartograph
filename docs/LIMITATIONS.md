# 분석 한계

kartograph는 컴파일러 산출물에서 관찰한 dependency graph를 질의한다. `unreachable` 또는 finding은 **코드를
안전하게 삭제할 수 있다는 판정이 아니다.** 실제 변경 전에는 출력 근거, runtime 경로, build variant와
테스트를 사람이 확인해야 한다.

## 현재 릴리스의 경계

- Hilt/Dagger의 확인된 생성 marker와 Hilt application sibling은 구분하지만 모든 generator를 인식하지는 않는다.
  protobuf generated wrapper/Kotlin DSL처럼 확인된 marker가 없는 생성물은 출처를 명시적으로 전달하는 설계가
  아직 없어 일부가 계속 보고될 수 있다. 공개 표본의 범위와 남은 진단은
  [공개 검증 기록](PUBLIC-VALIDATION.md)에 명시한다. 보고는 삭제 승인이 아니다.
  BINARY/RUNTIME 보존 어노테이션의 명시적 값·parameter annotation의 class 참조와 사용되는 중첩 class의 바깥
  container와 인코딩된 어노테이션 기본값의 class 참조는 도달성에 포함한다. bytecode에 남지 않는 SOURCE 보존
  어노테이션은 복원하지 못한다. `dead`의 모든 보고 형식에는 generation marker 한계를 포함한다.

- `--include-private-members`는 JVM/source 모두 private인 method·field/property만 선택적으로 추가한다.
  class member는 reachable 비생성 owner가 하나로 확정되는 경우에 한하며 constructor/native/constant는 제외한다.
  file facade의 private top-level 함수는 아래 top-level 보고 규칙을 따르며 이 reachable-owner 요건을 추가로
  요구하지 않는다(facade owner는 synthesized다).
  field 쓰기는 사용으로 취급한다. `-keepclassmembers`는 member와 owner를 무조건 보존하는 보수적 근사이며
  두 모드 간 class finding도 달라질 수 있다. private reflection에는 명시 keep rule이 여전히 필요하다.
  Kotlin inline 함수와 Java serialization callback은 제외하며, 해석할 수 없는 `-keepclassmembers` member
  signature는 matching class의 모든 직접 member 보존으로 확장한다.
  `allowshrinking` 규칙은 이 모드에서도 root가 아니다. Java serialization callback 제외는 이름 기반이므로
  같은 이름의 일반 private member도 보고하지 않을 수 있다. owner가 없거나 여러 개이면 보고를 보류한다.
  Gradle은 SDK boot classpath를 자동 제공하지만 CLI는 framework 상속 규칙을 위해 `android.jar`를
  `--classpath`로 전달해야 할 수 있다.
  reachable owner의 비private member·inline 함수·직렬화 callback도 잠재적 진입점으로 취급하므로
  외부에서 실제 사용되지 않는 public API의 private helper까지 보존할 수 있다.

- `Class.forName`의 overload와 `ClassLoader.loadClass`는 같은 메서드 안의 지역 변수·분기·일부 문자열 결합을
  추적해 프로젝트 class로 연결한다. 알려진 class의 reflection 생성자는 인자 개수에 맞는 후보를 연결한다.
  값 집합·명령·frame 크기를 제한하며, 알 수 없는 값과 한도 초과는 query 한계로 남긴다. 메서드 사이의 값 전달,
  임의 계산, `Method.invoke`·reflection field 접근과 동적 component 등록은 완전하게 해석하지 않는다.
- JNI, native lookup, framework callback과 serialization/DI codegen은 bytecode만으로 완전하게 증명할 수 없다.
- manifest/resource/keep rule 또는 dependency classpath를 전달하지 않으면 그 입력이 만드는 도달성을 볼 수 없다.
- manifest `meta-data`의 class-like `android:name`/`android:value`는 보수적으로 보존한다. class 위치에
  unresolved placeholder가 남은 source manifest는 추측하지 않고 실패하므로 가능하면 merged manifest를 쓴다.
  점으로 구분된 일반 metadata 문자열도 존재하지 않는 class root가 될 수 있고, 실제 class 이름과 우연히
  같으면 과보존할 수 있다. 값 전체가 pure placeholder이면 class인지 API key인지 구분할 수 없어 root로
  추측하지 않는다.
- 분석은 전달된 build variant의 class root만 나타낸다. 다른 flavor, build type, test, dynamic feature의 사실을
  자동으로 합치지 않는다.
- `dead --test-classes`는 production root에서 도달 불가이나 test root에서만 도달되는 finding을 `(used only by
  tests)`로 **표시**만 한다. 표시는 억제·삭제 승인이 아니며 strict/baseline에서 여전히 finding으로 계산한다.
  test root를 전달하지 않으면 이 구분을 볼 수 없고, Gradle plugin은 아직 test variant를 연결하지 않는다(CLI 전용).
  test-only 여부는 전달된 test class root와 production root가 같은 variant/컴파일 기준일 때만 신뢰할 수 있다.
  test 도달성은 production 도달성과 같은 정적 간선 모델(classpath 상위 타입 dispatch 미해결 포함)을 쓰므로, test가
  라이브러리 상위 타입 virtual dispatch로만 도달하는 finding은 표시되지 않을 수 있다(표시 누락은 보수 방향이며
  finding 자체는 계속 보고된다). 같은 FQN이 production·test 양쪽에 있으면 첫 root 사실이 우선해 표시가 누락될 수
  있고, 같은 root를 `--classes`와 `--test-classes` 양쪽에 넘기면 test 전용 seed가 비어 표시 없이 성공한다.
- Kotlin compiler/Compose/KSP/kapt가 만든 선언은 `synthesized`로 구분하지만 모든 code generator를 식별하지는 않는다.
- Kotlin file facade 자체는 synthesized infrastructure로 제외하지만, 단일 file facade와 multi-file part의 도달
  불가한 top-level 함수는 finding으로 보고한다. `@JvmMultifileClass` facade의 위임 method는 synthesized로
  취급해 보고하지 않으므로(보수적 방향) multi-file top-level은 part 기준으로만 보고된다. inline 함수·property
  접근자·backing field·native 함수·launcher `main`과 private 모드 아닌 private top-level은 보수적으로 보고하지
  않는다. top-level property는 여전히 미사용이어도 보고되지 않는다.
- source file과 line은 JVM debug attribute에 의존하므로 누락되거나 같은 basename 때문에 모호할 수 있다.
- `graph --format json --include-paths`와 Gradle의 `kartographGraph<Variant>`(`includeSourcePaths`)는
  그 source file 이름을 `--project` 안에서 유일하게 일치하는 파일에만
  상대경로로 확정한다. 같은 이름이 여러 모듈에 있거나 project 밖에서 컴파일된 class는 확정하지 않고 파일 이름을
  그대로 두며, 각 위치의 출처를 `pathKind`(`projectRelative`/`sourceFileName`)로, 확정하지 못한 수를
  `unresolved-source-paths`·`missing-source-paths` 한계로 함께 싣는다. 이름이 유일해도 선언의 package가 후보
  파일이 놓인 디렉터리의 suffix가 아니면 확정하지 않는다(다른 모듈이나 project 밖 class가 이름만 같은 파일로
  단언되지 않게 한다). package와 디렉터리가 다른 합법적인 Kotlin 배치도 이 때문에 미확정으로 남을 수 있다.
  `SourceFile` attribute는 임의 문자열이라 절대경로가 담길 수 있으므로 그래프에 들어가기 전에 파일 이름 성분만
  남기며, 남는 이름이 없으면 위치를 만들지 않는다. 따라서 graph·dead·query·baseline 어느 표면에서도 빌드 기계의
  절대경로를 내보내지 않는다. `missing-source-paths`는 개수만으로 계산되므로 경로 해석을 요청하지 않아도 보고한다.
  Gradle task의 project root는 Gradle project 디렉터리이므로 경로도 그 기준이다.
  경로 인덱스는 `build` 등 산출물 디렉터리를 순회에서 제외하므로, KSP/kapt가 그 안에 만든 생성 source와 이름이
  같은 project source가 있으면 생성 선언이 사람이 쓴 파일로 확정될 수 있다. 확정된 경로도 그래프가 아니라 파일
  이름 대조의 결과이며 삭제 판단의 근거가 아니다.
- stale build output은 stale graph를 만든다. kartograph는 source를 컴파일하지 않는다.
  `query`는 유일한 source 파일 이름에 대응하는 class들의 시각과 source 시각을 비교한다. 서로 다른 source의
  최신 class가 오래된 산출물을 가리지 않는다. SourceFile 누락·동명 source·미컴파일 source는
  `index-freshness-unknown`으로 계수한다. 이름 대조와 파일 시각은 내용 지문이나 완전한 freshness 증명이 아니며,
  시각을 보존한 파일 복사·source 삭제·프로젝트 밖의 동명 산출물을 항상 구분하지 못한다.
- package/module architecture는 JVM 이름과 입력 root를 기준으로 하며 Gradle dependency resolution model 자체는 아니다.
- Java와 Kotlin bytecode를 함께 읽지만 reflection configuration, runtime class loading과 외부 서비스 설정은 별도 입력이다.

`query`는 class root와 source에서 실제로 측정된 항목만 `limitations`에 싣고, 알릴 측정값이 없으면 빈
배열을 반환한다. `dead`와 그 machine report는 삭제 판단에 쓰이는 경로이므로 reflection·동적 등록·인라인
상수처럼 입력만으로 부재를 증명할 수 없는 보수적 한계를 항상 함께 싣는다.

## 런타임 관측 범위

그래프 JSON의 `externalCalls`는 앱 정점 밖 메서드 호출을 별도로 보존한다. 외부 선언을 앱의 dead 후보로
추가하지 않으며 호출자·대상 JVM identity·호출 종류·같은 메서드 내 invoke 명령 ordinal·위치를 기록한다. invokedynamic의 bootstrap과 method handle 대상도 구분해 기록한다.
`resolvedTargets`는 제공된 사실으로 연결한 프로젝트 대상이며 빈 배열은 대상 부재의 증명이 아니다.

`query`는 ClassLoader 로딩, reflection 생성자, ServiceLoader, 프로젝트 상위 타입에 대한 미해결 외부
virtual 호출, 해석된 프로젝트 밖 runtime 대상, 값 분석 한도, 인라인 상수 사용처 손실을 실제 입력 개수로 알린다.
notFound 응답에도 동일하게 포함한다. 이 관측은 아직 연결하지 못한 관계를 드러내는 것이며 실제 실행 횟수가 아니다.
상수 field도 `INLINE_CONSTANT`로 보존하지만 원래 호출자 간선을 복원했다는 뜻은 아니다.

외부 virtual/interface 호출은 전달된 classpath header와 프로젝트 상속 관계로 가능한 구현을 연결한다.
이는 실제 receiver를 증명하는 points-to 분석이 아니므로 여러 구현과 상속 메서드를 보수적으로 연결할 수 있다.
`externalCalls.resolution`과 간선 `origin`은 미해결 호출, 후보 dispatch, runtime 모델을 구분한다.

`META-INF/services`는 class root의 디렉터리/JAR 및 CLI `--service-resources`에서 읽는다. 등록된 프로젝트
provider는 `SERVICE_PROVIDER` 근거로 보존하고 알려진 `ServiceLoader` 요청에 연결한다. 파일·줄은 입력별
상대 위치로 기록하며 provider 코드를 실행하지 않는다. Gradle plugin은 해당 variant의 Java resource 원천
디렉터리를 전달한다. 병합된 최종 resource가 아니므로 overlay·패키징 제외에 따라 과보존할 수 있다.
JPMS `module-info`의 `provides`와 동적 provider 등록은 지원하지 않는다.

DI 어노테이션 보존은 지원되는 어노테이션을 진입점으로 삼는 보수적 모델이다. Dagger/Hilt binding 선택이나
실제 객체 수명·주입 경로를 증명하지 않는다.

외부 dispatch 후보 간선은 도달성에 사용하지만 호출자의 선언 의존성을 뜻하지 않으므로 패키지 순환·레이어 규칙·
결합도에서는 제외한다. bytecode·metadata·확정된 runtime 참조는 해당 구조 질의에 유지한다.

서비스 registry의 유효한 이름 파일은 잘못된 provider·인코딩·크기 초과 시 부분 결과 대신 실패한다.
서비스 이름이 될 수 없는 백업·편집기 파일은 건너뛴다. 입력 ordinal은 같은 입력 순서에서 결정적이며 순서를
바꾸면 위치가 달라질 수 있다. baseline 지문은 이 위치에 의존하지 않는다. dependency classpath는 header 전용이며
그 안의 registry를 읽으려면 CLI `--service-resources`에도 해당 JAR을 전달해야 한다. 외부 provider의 구현 본문은
프로젝트 class root에 포함되지 않는 한 분석하지 않는다.

간선 identity는 `(source, target, kind, origin)`이다. 같은 쌍에 bytecode와 모델 근거가 함께 있으면 별도 간선으로
보존하고 weight는 각 출처 안에서만 합친다. 출처 없는 기존 JSON 간선은 `bytecode`를 뜻한다. `resolvedTargets`와
`projectCandidates`는 실행 대상의 확정이 아니며 query의 `dispatch-candidates`가 이런 호출 개수도 함께 알린다.
