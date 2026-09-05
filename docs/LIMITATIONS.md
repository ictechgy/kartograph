# 분석 한계

kartograph는 컴파일러 산출물에서 관찰한 dependency graph를 질의한다. `unreachable` 또는 finding은 **코드를
안전하게 삭제할 수 있다는 판정이 아니다.** 실제 변경 전에는 출력 근거, runtime 경로, build variant와
테스트를 사람이 확인해야 한다.

## 0.2.0의 경계

- Hilt/Dagger의 확인된 생성 marker와 Hilt application sibling은 구분하지만 모든 generator를 인식하지는 않는다.
  protobuf wrapper/Kotlin DSL, annotation 값·parameter만으로 참조되는 선언, 사용되는 중첩 class의 바깥
  container에는 알려진 보고 한계가 있다. 공개 표본의 범위와 남은 진단은
  [공개 검증 기록](PUBLIC-VALIDATION.md)에 명시한다. 보고는 삭제 승인이 아니다.
  `dead`의 모든 보고 형식에도 generation marker·annotation value·enclosing declaration 한계를 포함한다.

- `--include-private-members`는 JVM/source 모두 private인 method·field/property만 선택적으로 추가한다.
  reachable 비생성 owner가 하나로 확정되는 경우에 한하며 constructor/native/constant/file-facade는 제외한다.
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

- 문자열 reflection과 동적 component 등록은 호출 후보를 계수할 수 있지만 대상 symbol을 항상 복원할 수 없다.
- JNI, native lookup, framework callback과 serialization/DI codegen은 bytecode만으로 완전하게 증명할 수 없다.
- manifest/resource/keep rule 또는 dependency classpath를 전달하지 않으면 그 입력이 만드는 도달성을 볼 수 없다.
- manifest `meta-data`의 class-like `android:name`/`android:value`는 보수적으로 보존한다. class 위치에
  unresolved placeholder가 남은 source manifest는 추측하지 않고 실패하므로 가능하면 merged manifest를 쓴다.
  점으로 구분된 일반 metadata 문자열도 존재하지 않는 class root가 될 수 있고, 실제 class 이름과 우연히
  같으면 과보존할 수 있다. 값 전체가 pure placeholder이면 class인지 API key인지 구분할 수 없어 root로
  추측하지 않는다.
- 분석은 전달된 build variant의 class root만 나타낸다. 다른 flavor, build type, test, dynamic feature의 사실을
  자동으로 합치지 않는다.
- Kotlin compiler/Compose/KSP/kapt가 만든 선언은 `synthesized`로 구분하지만 모든 code generator를 식별하지는 않는다.
- Kotlin file facade는 synthesized infrastructure로 제외하므로 v0.1.x의 `dead`는 사용하지 않는 top-level
  함수·프로퍼티 자체를 finding으로 보고하지 않는다.
- source file과 line은 JVM debug attribute에 의존하므로 누락되거나 같은 basename 때문에 모호할 수 있다.
- stale build output은 stale graph를 만든다. kartograph는 source를 컴파일하지 않는다.
- package/module architecture는 JVM 이름과 입력 root를 기준으로 하며 Gradle dependency resolution model 자체는 아니다.
- Java와 Kotlin bytecode를 함께 읽지만 reflection configuration, runtime class loading과 외부 서비스 설정은 별도 입력이다.

`query`는 class root와 source에서 실제로 측정된 항목만 `limitations`에 싣고, 알릴 측정값이 없으면 빈
배열을 반환한다. `dead`와 그 machine report는 삭제 판단에 쓰이는 경로이므로 reflection·동적 등록·인라인
상수처럼 입력만으로 부재를 증명할 수 없는 보수적 한계를 항상 함께 싣는다.
