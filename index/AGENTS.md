# index 지침

이 디렉터리는 [공통 지침](../AGENTS.md)을 상속한다. 관찰한 입력 사실만 복원하고 분석 정책은 `analysis/`에 둔다.

## 입력과 식별자

- JVM identity/descriptor와 Kotlin source visibility를 구분한다. Java도 같은 경로로 검증한다.
- metadata는 기존 JVM 정점을 보강한다. backing property와 field를 별도 중복 정점으로 만들지 않는다.
- 여러 class root의 순서와 동일 JVM class의 첫 입력 우선 정책을 유지한다. 앱 그래프와 dependency hierarchy를 섞지 않는다.
- source file/line은 optional이다. basename 충돌·누락·인라인 관계를 임의 복원하거나 줄 0을 유효 위치로 만들지 않는다.
- 생성 annotation·정확한 원본/sibling·classfile enclosing 관계로 `synthesized`를 표시한다. `_Factory`, `$`, `*Kt` 같은 이름만으로 사용자 코드를 숨기지 않는다. 기존 Android 특수 생성물 예외를 넓힐 때도 반례를 추가한다.
- 손상된 입력에서 조용히 부분 그래프나 성공을 반환하지 않는다.

## 파서 안전성

- XML의 DTD/external entity를 끄고 실제 경로 기준으로 project/include 경계를 지킨다. 오류에 절대경로나 원시 입력을 노출하지 않는다.
- keep 규칙의 미지원 보존 문법과 불완전한 상속 hierarchy를 조용히 무시하지 않는다. 기존 opt-in `-keepclassmembers`의 보수적 member 확장과 일반 keep의 실패 경계를 구분한다.
- include는 파일 기준 상대경로·cycle·허용 realpath 경계를 검증한다. CLI/plugin의 의미를 파서별로 갈라놓지 않는다.

## 검증

`./gradlew --no-daemon :index:test`와 관련 Android/생성 코드 코퍼스를 루트에서 실행한다.
ASM 손작성 테스트만으로 compiler 호환성을 주장하지 않는다. 실제 javac/Kotlin·metadata·생성기 산출물과 동명 사용자 선언·잘린 class/JAR 반례도 검사한다.
원천/API 변경은 [원천 결정 기록](../docs/DECISION-truth-source.md)에 대응하는 버전·입력·시간·관측 불가능 항목을 실측한다.
