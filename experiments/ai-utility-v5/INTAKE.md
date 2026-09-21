# v5 intake — 실행 전 선택 규칙

상태: metadata 선택·빌드 자격 검증 전. 모델 실행/효용 결과 없음. 2026-09-21에 다음 규칙을 고정한다.

- 모집단은 기존 Kotlin SWE-bench 고정 revision `b9025d86f7ae634901396766bd61f6d4ae6d2165`의
  `detekt/detekt`, `pinterest/ktlint` Gradle JVM 변경이다. 기존 v1–v4의 Java Maven 표본은 자동 capture가
  없어 이번 모집단에서 제외한다. Java 전체나 원래 네 저장소 모집단으로 일반화하지 않는다.
- 각 저장소에서 PR 번호 내림차순으로 **미사용 적격 사례 두 개씩** 선택한다. 이전 cohort의 previousCases,
  primary, exclusions와 v4 primary/exclusions를 모두 제외한다. 새 사례의 diff·제품 결과·AI 응답을 보기 전에 선택한다.
- metadata 적격 조건: prepare.sh에 전체 40자리 base SHA가 있고 expected_tests.json의 fail-to-pass 목록이 비어 있지 않다.
  외부 prepare.sh는 metadata로 읽으며 실행하지 않는다. 각 URL·수신 bytes·SHA256과 제외 사유를 남긴다.
- 자격 조건: 원본 build/test가 동작하고 지원되는 실제 compiler task의 성공 증거로 capture와 verify가 `matched`여야 한다.
  수동 라벨이나 가짜 witness를 쓰지 않는다. 빌드/API 실패를 기록하고 원인을 조사한다. 결과가 나쁜 사례를 교체하지 않는다.
- javap·원본 source에서 독립 선언 목록·source signature aliases·직접/간접 oracle을 작성한다. 제품 impact로 oracle을 만들지 않는다.
  각 선언의 sourceAddressable 분류와 근거를 실행 전에 고정하며, source로 지목할 선언에는 유일한 signature alias가 필요하다.
  primary oracle와 제외 identity 목록을 모두 보고한다. compiler-only 선언은 별도 지표이며 primary oracle은 비어 있을 수 없다.
- 응답과 oracle의 상한은 공통 64개다. oracle이 넘는 사례는 답변을 보기 전에 미적격으로 기록하고, oracle을 자르지 않는다.
- 네 사례 자격이 확인된 뒤 모델·예산·prompt·scorer·모든 입력 해시와 실행 순서를 별도 manifest로 고정한다.
  소스만/MCP 각 2회(총 16회)를 예정하되, 자격 미충족 상태에서 실행 완료·AI 효용을 주장하지 않는다.

## 모델 노출 전 준비 변경·oracle 규칙

- ktlint 두 사례는 Gradle 8.14.3/JDK 21 launch, 선택 compilation JDK 17로 준비했다. detekt는 원래 wrapper
  (7212: 8.7/JDK 21 launch, 6446: 8.3/JDK 17 launch)를 사용했다. 버전 변경·실패 비용을 별도 기록한다.
- detekt7212 원본은 자체 `toolchain.use(java8Launcher)`로 toolchain을 확정해 자동 capture의 JDK17 연결을 거부했다.
  별도 준비 patch는 그 setter block만 제거하고 capture adapter가 실제 JDK17을 연결하게 한다. JVM1.8 target은 유지한다.
  production·test source는 그대로이며 build-logic patch와 전후 해시를 보존한다. 원본 빌드 그대로의 성공으로 주장하지 않는다.
- 이번 평가의 source 도구와 그래프는 모두 **변경된 production 모듈**로 한정한다. 의존 모듈의 전체 source/graph가
  주어졌다고 가정하지 않는다. 질문의 diff 경로만 같은 모듈 기준으로 바꾸며 코드 변경 내용은 바꾸지 않는다.
- 정답은 javap의 변경된 기존 메서드 직접 호출자와 그 호출자 전체의 2단계 호출자다. 변경 메서드 자신은 별도 표시한다.
  여기에 변경 rule과 같은 이름의 원래 `Test`/`Spec` 파일이 있으면 실제 실행된 해당 suite 메서드를 회귀 검토 anchor로
  추가한다. 이 규칙은 callback을 직접 호출하지 않는 테스트도 포함하며, 실제 동작 영향의 완전한 정답이 아니다.
  대응 suite가 없는 CodeFormatter는 기존 KtLintTest를 실행하되 javap 직접/2단계 호출과 대응하는 선언만 oracle에 넣는다.
  skipped·새 PR 테스트는 정답에 넣지 않는다. oracle 밖 예측은 미판정으로 남긴다.
- Kotlin compiler PSI의 원본 함수 범위와 javap 선언·descriptor·줄 정보를 대조한다. 생성 lambda와 단축 overload를
  source 함수의 signature로 오인하지 않는다. source 대응이 없는 선언은 primary 분모에서 명시적으로 분리하고 목록을 낸다.
- 위 준비는 모델 응답을 보기 전에 끝낸다. 원래 v1–v4 점수와 직접적인 전후 인과 비교를 하지 않는다.
