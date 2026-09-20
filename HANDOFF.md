# HANDOFF

마지막 갱신: 2026-09-20

현재 재개 정보만 담는다. 규칙은 [AGENTS.md](AGENTS.md), 이전 원문·측정·처분은
[HANDOFF-HISTORY.md](HANDOFF-HISTORY.md)에 보존한다. 과거 Next Steps·미발행 표기는 당시 기록이다.

## 현재 상태

- `VERSION`은 **0.12.0**이다. [릴리스 PR #87](https://github.com/ictechgy/kartograph/pull/87)은
  P1.1-2와 일반 RN target 필터 회귀 수정을 포함한다. GitHub 발행·Portal 제출/확인·독립
  설치 검증 결과는 이 PR의 최종 기록과 [v0.12.0 릴리스](https://github.com/ictechgy/kartograph/releases/tag/v0.12.0)를 따른다.
  버전 파일만으로 외부 발행 성공을 단정하지 않는다.
- P1.1-2는 `dependencies --library`의 API/implementation 배치 조언,
  `--resolved-dependencies`의 미선언 전이 의존성, main/test 구분, 여섯 보고 형식과
  JVM/Android dependency task다. [사용법·관찰 범위](docs/DEPENDENCIES.md)를 따른다.
- 0.11.0의 일반 `bridges --target react-native`는 RN 이벤트 target 검증 때문에 코드 64로
  실패했다. 공개 expo-haptics14.1.4에서 재현했고, v1 필터와 v2 transport 검증을 분리했다.
  RN/Flutter 분리·잘못된 조합 회귀를 포함한 **785 tests**, Kover·installDist·validatePlugins가
  통과했다. 공개 Kotlin 수신 측 4개 메서드도 다시 확인했다.
- 수정 후 두 번의 clean build와 ZIP/TAR/JAR/POM/SBOM 재현성, ZIP/TAR CLI·PR 계약을
  확인했다. 문서만 바뀐 후속의 런타임 입력은 해시로 대조하며, 최종 PR CI는 원격에서 확인한다.
- 0.11.0에 포함된 external-retentions v0·원본 caller 유지·RN 전역 이벤트 추출은 유지한다.
  소스 전용 스캔의 ID 누락은 한계이며, 실제 JVM ID 없이 보존에 성공시키지 않는다.
  JS/네이티브 이벤트 하네스를 앱 전체 런타임 검증으로 표현하지 않는다.
- `.claude/`와 `HANDOFF.cartograph-notes.md`는 기존 사용자 미추적 파일이다.
  자매 코퍼스·발행 검증은 [isthmus HANDOFF](https://github.com/ictechgy/isthmus/blob/main/HANDOFF.md)를 따른다.

## 다음 할 일

이번 릴리스·검증 작업 이후의 선택 후보는 아래와 같다. 과거 P1.1-2 핵심 구현을 재개발하지 않는다.

- dependency baseline/suppress와 processor별 생성 코드 귀속.
- runtime 근거의 LCOV·method 단위 매핑. 현재 class 단위 관찰과 구분한다.
- P2/P3·경쟁 조사 후보는 과거 원장과 현재 코드를 대조한 뒤 범위를 선택한다.
  spool/header 성능 병목은 미측정이며, 보류한 전역 분석 재사용을 근거 없이 재개하지 않는다.

## 재개 프롬프트

HANDOFF.md와 적용 AGENTS.md를 읽고 실제 branch/status를 확인해줘. 현재 버전은
0.12.0이며 PR #87에 P1.1-2와 RN target 필터 회귀 수정이 있어. 최종 CI·GitHub/Portal
발행·설치 여부는 PR/릴리스 기록으로 확인해. HANDOFF-HISTORY.md의 옛 Next Steps를
현재 실행 권한으로 삼지 말고, 최신 사용자 요청과 사용자 파일을 보존해. 자동 수정이나
삭제 안전성·processor 귀속으로 범위를 넓히지 마.
