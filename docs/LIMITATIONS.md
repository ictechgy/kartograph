# 분석 한계

kartograph는 컴파일러 산출물에서 관찰한 dependency graph를 질의한다. `unreachable` 또는 finding은 **코드를
안전하게 삭제할 수 있다는 판정이 아니다.** 실제 변경 전에는 출력 근거, runtime 경로, build variant와
테스트를 사람이 확인해야 한다.

## 현재 릴리스의 경계

- Hilt/Dagger의 확인된 생성 marker와 Hilt application sibling은 구분하지만 모든 generator를 인식하지는 않는다.
  protobuf generated wrapper/Kotlin DSL처럼 marker가 없는 생성물은 `--generated-classes`로 생성 전용 컴파일 root를
  명시할 수 있다. 해당 root는 `--classes`에도 포함돼야 한다. Gradle의 같은 입력은 `generatedClassRoots`다.
  선언은 `synthesized`와 `generatedInput`으로 표시하며 정점·간선은 유지한다. 표시는 보존 root를 추가하지 않는다.
  생성/수동 코드가 섞인 root는 통째로 지정하지 않는다. 잘못된 출처 지정은 수동 코드의 finding도 숨길 수 있다.
  출처를 주지 않으면 기존 marker 정책을 따르므로 일부 생성물이 보고될 수 있다. 공개 표본의 범위와 남은 진단은
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
  프로젝트의 정확한 JVM static 호출에서는 불변 인자와 String/Class 반환값을 helper 사이에서도 전파한다.
  반환 경로가 unknown을 포함하면 일부 상수만으로 해석 완료를 주장하지 않는다. virtual/interface 호출의 반환값,
  dependency 본문, 임의 계산과 동적 component 등록은 완전하게 해석하지 않는다.
  값 집합은 16개·문자열은 4096자, 메서드는 명령 20,000개·frame slot 250,000개로 제한한다. 반환값 분석은
  runtime 메서드별 호출 깊이 8·문맥 128개·누적 frame slot 1,000,000개로 추가 제한하며 재귀·한도 초과는
  `runtime-analysis-limits`와 미해결 호출 개수로 남긴다. 알려진 method 이름·인자 개수와 field 이름의
  reflection 접근은 연결한다. static field의 String/Class 후보는 아래의 may-write 범위로 복원하며, 일반 객체 상태나 reflection 호출 반환값은 추적하지 않는다.
- JNI, native lookup, framework callback과 serialization/DI codegen은 bytecode만으로 완전하게 증명할 수 없다.
- Compose multipreview는 프로젝트 또는 전달된 dependency classpath의 어노테이션 선언에서 `@Preview`와 반복
  컨테이너로 이어지는 경로를 따라간다. 어노테이션 이름만으로 보존하지 않는다. 새 multipreview 경로는 해당
  method를 보존하며, 같은 owner의 무관한 method를 새 root로 만들지 않는다. dependency header가 없거나
  SOURCE-retention으로 정보가 사라졌으면 경로를 복원하지 못한다. 직접 `@Preview`와 그 반복 컨테이너는 같은
  보수적 owner 정책을 사용한다.
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
  JAR entry 시각은 일반 ZIP의 정밀도를 고려해 2초 구간으로 보수적으로 취급한다. source 시각이 그 구간 안에
  있으면 stale이 아니라 `index-freshness-unknown`이며, 구간 이후의 변경은 계속 stale로 잡는다.
  디렉터리 class 파일은 이 허용 구간을 적용하지 않는다.
- `snapshot`은 생성 시점의 그래프·보존 근거·baseline 상태·측정 한계를 고정한다. `query --graph-file`은
  원본 class/source/규칙 파일을 읽지 않으며 `saved-graph` 한계를 추가한다. 현재 source와 일치하는지는 확인하지
  않으므로 변경 후에는 새 snapshot을 만든다. live 입력이나 baseline을 섞어 저장된 의미를 바꿀 수 없다.
  일반 `graph --format json` 문서에는 보존 문맥이 없으므로 질의 snapshot으로 읽지 않는다. 입력은 UTF-8 JSON,
  최대 64 MiB이며 지원하지 않는 버전·손상·중복 정점·dangling edge를 오류로 거부한다.
- `snapshot --compact` v2는 반복 문자열과 graph 행을 인덱스로 저장한다. 사실을 생략하지 않으며 v1도 계속 읽는다.
  `--revision`/`--scope`는 호출자의 입력 라벨이고 내용 지문이나 빌드 신선도 증명이 아니다.
- `impact`는 잠재적 사용/계약 의존을 역방향으로 조사하며 실제 동작 변화나 테스트 생략을 승인하지 않는다.
  삭제/rename을 조사할 때는 같은 analyzer·입력 범위로 만든 base/current snapshot이 필요하다.
  보존만 됐다는 사실은 호출자 근거가 아니다. 범위가 큰 클래스/파일 변경은 많은 후보를 만들 수 있으며, 출력·깊이·방문·경로
  예산에 따른 잘림과 불명확한 파일 매핑을 보고한다. [영향 점검 계약](IMPACT.md)을 따른다.
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
결합도에서는 제외한다. bytecode·metadata·값 기반 runtime 참조는 해당 구조 질의에 유지한다.

서비스 registry의 유효한 이름 파일은 잘못된 provider·인코딩·크기 초과 시 부분 결과 대신 실패한다.
서비스 이름이 될 수 없는 백업·편집기 파일은 건너뛴다. 입력 ordinal은 같은 입력 순서에서 결정적이며 순서를
바꾸면 위치가 달라질 수 있다. baseline 지문은 이 위치에 의존하지 않는다. dependency classpath는 header 전용이며
그 안의 registry를 읽으려면 CLI `--service-resources`에도 해당 JAR을 전달해야 한다. 외부 provider의 구현 본문은
프로젝트 class root에 포함되지 않는 한 분석하지 않는다.

간선 identity는 `(source, target, kind, origin)`이다. 같은 쌍에 bytecode와 모델 근거가 함께 있으면 별도 간선으로
보존하고 weight는 각 출처 안에서만 합친다. 출처 없는 기존 JSON 간선은 `bytecode`를 뜻한다. `resolvedTargets`와
`projectCandidates`는 실행 대상의 확정이 아니며 query의 `dispatch-candidates`가 이런 호출 개수도 함께 알린다.

## 라이브러리 runtime 모델

JDK API 모델은 owner·이름·descriptor·static 여부를 확인하고 해당 호출이 있을 때만 적용한다. 호출이 없는
모델은 보존 root를 만들지 않는다. 지원 모델은 `RuntimeLibraryModels`에 모으며 외부 호출 JSON의 `model`은
대응하는 모델 ID, `resolution`과 `resolvedTargets`는 실제 대상 해석 결과다. 모델 ID만으로 해석 완료를 뜻하지 않는다.

`Class.getMethod/getDeclaredMethod`와 `Method.invoke`는 알려진 이름·인자 개수의 프로젝트 method 후보를 연결한다.
같은 개수의 overload는 보수적으로 포함하며 선언 밖의 override·실제 receiver까지 완전하게 구분하지 않는다.
`Class.getField/getDeclaredField`의 이름을 `Field.get/set` 및 primitive 변형까지 전달한다. public lookup은 상속된
선언을 찾되 일치하는 선언에서 멈춰 숨겨진 부모 field를 섞지 않는다. 입력 class의 public 선언·interface·superclass 순서로 찾고 declared lookup은 해당 owner만 검색한다. dependency header의 상속 경로도 따르지만 그 header의 field 선언·숨김은 수입하지 않으므로 경계 밖에서는 조상 후보를 보수적으로 포함할 수 있다. 알려지지 않은 이름은 method/field별 호출 개수로 알린다.
프로젝트 static helper의 String/Class 반환값은 제한적으로 추적하지만 외부 선언의 구현·reflection 메서드 반환값·
field 값의 일반적 흐름은 여전히 미해결일 수 있다.

static field는 실제 `PUTSTATIC`과 알려진 `Field.set`의 stack 값에서 String/Class 후보를 수집한다. 직접 `GETSTATIC`과
`Field.get`은 같은 field lookup을 사용한다. mutable 필드도 대상으로 하며 선언된 타입만으로 값을 만들지 않는다.
대입 명령이 없는 static String 상수는 classfile `ConstantValue` 속성을 읽으며 같은 4096자 한도를 적용한다.
별도 helper의 write·여러 write·분기 후보를 합치되, 실행 순서나 마지막 write를 단정하지 않는 may-write 분석이다.
알려진 후보의 `runtimeModel` 간선을 생성해도 초기화 전 기본값, initializer 순환/재진입, unknown 대입, 공개 field나
외부로 전달한 reflection handle의 변경 가능성이 남는다. 따라서 field에서 유래한 class 로딩·생성 호출은 후보가 있어도
`reflection-strings`·`reflective-construction` 등의 미해결 개수를 유지한다. 이는 final field에도 적용하는 보수적 한계이며 완전한 값 해석을 뜻하지 않는다.
알려진 필드에 대한 알려진 reflective write는 후보에 포함하지만, unknown lookup·외부/JNI/MethodHandle write의 값,
인자를 따라가는 void helper write와 instance field/heap 상태는 복원하지 않는다. 문자열 원문은 보고서에 추가하지 않는다.
순환 read는 unknown으로 끊고 그 결과에 의존한 field/helper 요약은 다른 read를 위해 캐시하지 않는다.
field의 String/Class 후보를 각각 16개·깊이 8·writer 분석 128회·누적 frame slot 1,000,000개로 runtime 메서드별로 제한한다.
직접 writer를 한 번 인덱싱해 무관한 field의 쓰기는 예산을 소비하지 않는다. 알려진 reflective setter는 실제 조회 대상이
정해질 때까지 후보 writer로 분석한다. 이름이 literal에 한정되면 다른 field의 setter를 제외하며, field/문자열 계산에서
이름이 올 수 있으면 후보를 유지한다. 호출·반환·선택한 write의 입력을 역방향으로 조사해 소비되지 않는 field 값은
해석하지 않는다. `Object`에 담긴 String/Class도 소비 경로가 있으면 분석한다. 이 의존 수집은 명령당 producer 64개와
method당 의존 간선 100,000개로 제한하며 불완전하면 기존 전체 값 분석으로 되돌아가 후보를 임의로 버리지 않는다.
개별 메서드 예산은 위와 같으며 field writer의 helper들은 별도의 반환값 예산
(깊이 8·문맥 128·frame slot 1,000,000)을 공유한다. 호출자에서 직접 분석하는 helper 예산과는 독립적이다.
write 값에 영향을 준 한도는 부분 요약을 버리고 `runtime-analysis-limits`를 남긴다. 후보 집합이 한도를 넘은 상태는
합류 때 다시 알려진 후보로 되돌아가지 않게 해 반복문 분석이 수렴하도록 한다.
실제 javac/Kotlin 테스트는 상속/숨김·여러/unknown write·reflective get/set·외부 handle escape·초기화 재진입·한도 초과와
unused control을 확인한다. `reflective_field` 실행 표본의 mutable 초기화는 바꾸지 않았으며 JVM 실행과 역방향 impact의
`main → Target 생성자 → Used` 경로를 함께 확인한다. 이는 임의 런타임 경로의 완전성 증거는 아니다.

이는 NullAway의 라이브러리 모델 분리와 GraalVM의 조건부 metadata 설계를 참고한 호출별 모델이다. GraalVM
`typeReached` JSON을 Android에 그대로 import하거나 JVM agent의 관측 부재를 미사용 증거로 취급하지 않는다.
지원되지 않은 호출의 반환값은 unknown으로 유지한다. helper 인자에는 변경 가능한 객체 상태를 전달하지 않으며
문자열·Class·정수와 불변인 배열 길이만 사용한다. callee가 객체나 field를 변경하지 않는다고 가정하지 않는다.

이름은 알려졌지만 public/declared 검색 조건 등에 맞는 프로젝트 member가 없으면 `runtime-member-lookup`으로
별도 계량한다. 외부 class 대상과 조회 조건 불일치를 같은 범주로 단정하지 않는다.
