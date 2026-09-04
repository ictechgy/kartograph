# 분석 한계

kartograph는 컴파일러 산출물에서 관찰한 dependency graph를 질의한다. `unreachable` 또는 finding은 **코드를
안전하게 삭제할 수 있다는 판정이 아니다.** 실제 변경 전에는 출력 근거, runtime 경로, build variant와
테스트를 사람이 확인해야 한다.

## 0.1.0의 경계

- 문자열 reflection과 동적 component 등록은 호출 후보를 계수할 수 있지만 대상 symbol을 항상 복원할 수 없다.
- JNI, native lookup, framework callback과 serialization/DI codegen은 bytecode만으로 완전하게 증명할 수 없다.
- manifest/resource/keep rule 또는 dependency classpath를 전달하지 않으면 그 입력이 만드는 도달성을 볼 수 없다.
- 분석은 전달된 build variant의 class root만 나타낸다. 다른 flavor, build type, test, dynamic feature의 사실을
  자동으로 합치지 않는다.
- Kotlin compiler/Compose/KSP/kapt가 만든 선언은 `synthesized`로 구분하지만 모든 code generator를 식별하지는 않는다.
- source file과 line은 JVM debug attribute에 의존하므로 누락되거나 같은 basename 때문에 모호할 수 있다.
- stale build output은 stale graph를 만든다. kartograph는 source를 컴파일하지 않는다.
- package/module architecture는 JVM 이름과 입력 root를 기준으로 하며 Gradle dependency resolution model 자체는 아니다.
- Java와 Kotlin bytecode를 함께 읽지만 reflection configuration, runtime class loading과 외부 서비스 설정은 별도 입력이다.

`query`는 class root와 source에서 실제로 측정된 항목만 `limitations`에 싣고, 알릴 측정값이 없으면 빈
배열을 반환한다. `dead`와 그 machine report는 삭제 판단에 쓰이는 경로이므로 reflection·동적 등록·인라인
상수처럼 입력만으로 부재를 증명할 수 없는 보수적 한계를 항상 함께 싣는다.
