# Runtime differential contracts

[DroidBench](https://github.com/secure-software-engineering/DroidBench/tree/a57fa6f42f278591695672f1aa8b37c275139370)의
Reflection·Callbacks/Unregister 패턴을 참고해 새로 작성한 Java 표본이다. upstream 소스나 taint 정답을 복사하지
않았다. 실제 Android lifecycle/ICC를 실행하는 검사는 아니며 callback 등록·해제의 Java 동작을 고정한다.

`python3 Scripts/verify-runtime-contracts.py`는 JDK 17, Android SDK Build Tools **35.0.0**의 R8과 현재 CLI를 쓴다.
원래 class의 실행, kartograph query, R8 결과 class와 보존 이유, R8 처리 후 실행을 따로 검사한다.
같은 `-keep class probe.Entry { *; }` 규칙을 주고 R8에는 `-dontoptimize -dontobfuscate`를 적용해 최적화에 의한
차이를 줄인다. R8과 일치한다는 사실을 source 삭제 승인으로 취급하지 않는다.

최초 0.6.0 측정은 `expectations.json`에 고정했다. 알려진 누락도 기대값으로 명시해 우연한 결과 변경을 검출한다.
기능 보완 시 실행 증거와 함께 기대값을 갱신한다. R8도 factory 문자열·reflection field 사례에서는 추가 metadata
없이 원래 실행을 보존하지 못했다. 반대로 unregister 사례는 실행되지 않는데 두 정적 분석 모두 후보를 유지한다.
이 차이를 숨기지 않고 `build/reports/runtime-contracts.json`에 분리해 기록한다.

각 사례에는 bytecode에 실제 존재하지만 사용되지 않는 `Unused` 대조 선언이 있다. 외부 앱·개인 source는 입력하지
않으며 프로세스에 timeout을 적용하고 원시 compiler 오류 대신 단계·종료 코드로 실패를 보고한다.

keep 규칙은 `Entry`만 진입점으로 보존한다. 중첩 `Used`는 호출·reflection 등 각 엔진의 추론으로 살아남는지
검사하며 이름이 다른 `Unused`는 모든 사례에서 query `unreachable`이고 R8 결과에서 제거되어야 한다.

후속 API 모델 보강으로 `reflective_method`의 kartograph 기대값은 `reachable`로 갱신했다.
field가 반환하는 값과 factory의 반환 문자열을 따라가는 사례는 여전히 별도 미해결 경계로 유지한다.
