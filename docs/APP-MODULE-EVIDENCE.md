# Android application 모듈의 R.jar 증거 연결 — 재현·설계 노트

상태: **설계 전 재현 대기**. 이 문서는 구현을 요구하지 않으며, `unwitnessed-class-root` 판정을
약화하지 않는 선에서 application 모듈 자동 캡처를 일반화하기 위한 비교 실험 계획이다.
0.9.0의 자동 캡처 검증표는 **Android library**다. 앱 전체 지원으로 일반화하지 않는다.

## 현재 실패의 사실 관계

- AGP 8 application 모듈의 독립 사전검증에서 `kartographSnapshotDebug`가 snapshot 뒤 신선도 검사에서
  `unwitnessed-class-root`로 거부됐다. 재현 자료: `build/reports/product-limits-20260914/local-android-1.log`,
  `local-android-consumer/`(로컬 증거, Git 제외).
- 원인 가설(검증 전): AGP 8 앱의 `process<Variant>Resources`가 만드는 생성 `R.jar`가 PROJECT class
  root에 포함되는데, compiler witness(javac/kotlinc task output)의 `outputs`는 javac classes만 커버한다.
  `ProvenanceVerifier`는 모든 `classes` role 입력이 어떤 witness output(경로+sha256)으로 덮이는지
  요구하므로(`index/src/main/kotlin/dev/kartograph/index/ProvenanceVerifier.kt`의 `unwitnessed-class-root`)
  R.jar가 덮이지 않으면 거부가 맞는 동작이다.
- 지금 하지 말아야 할 것: R.jar를 class root에서 몰래 제외하거나, witness 조건을 완화하거나,
  producer 없는 class root를 `unverified` 대신 `matched`로 승격하는 것. 이는 producer 증거 계약을
  약화한다([BUILD-PROVENANCE](BUILD-PROVENANCE.md)).

## 재현·비교 실험 계획 (구현 전 필수)

1. **최소 재현 유지.** AGP 8.7.3/Gradle 8.10.2 application 1개(활동 1개, resource 1개)로
   `kartographSnapshotDebug` → `verify-snapshot` 재현을 스크립트로 고정한다. 기존 `local-android-1.log`와
   같은 실패 이유 집합이 나오는지 먼저 확인한다.
2. **producer 사실 관계 측정.** 같은 빌드에서 `processDebugResources`의 declared inputs(merged resources,
   aapt2, namespace)와 outputs(`R.jar`, merged resources, proguard rules)를 Gradle Variant/Artifact API로
   덤프해 표로 남긴다. R.jar의 class root 통합 경로(어느 task가 R.jar를 `--classes`에 합치는지)를
   `AndroidSnapshotTasks`/`KartographAndroidSnapshotTask` 기준으로 확인한다.
3. **library와의 차이 표.** 같은 실험을 library 모듈에서 반복해 왜 library는 지금 통과하는지(R.jar가
   class root에 없는지, 아니면 다른 witness가 덮는지)를 대조한다. 차이가 곧 설계 조건이다.
4. **결정 기록.** 아래 설계 후보 중 하나를 고르고 이유를 이 문서에 추가한다. 비교 실험 없이 고르지 않는다.

## 설계 후보

- **A. AGP resource producer witness를 별도 kind로 추가.** `process<Variant>Resources`를 witness producer로
  등록해 R.jar를 `outputs`(role 구분 포함)로 기록하고, 입력으로 merged resources를 남긴다. compiler
  witness와 같은 fail-closed 원칙을 유지하며 ProvenanceVerifier 변경은 최소(새 witness kind 수용)다.
- **B. R.jar를 별도 role의 입력으로 분리.** class root에서 R.jar를 떼어 `role=rjar` 같은 별도 입력으로
  fingerprinting하고, verifier의 classes 덮음 요구에서 제외한다. 그래프 사실은 유지되지만 "모든 class
  root는 producer 증거를 가진다"는 원칙이 예외를 얻는다.
- **C. 증거 없이 제외(기각).** R.jar는 선언 그래프에 기여하지 않으니 빼는 방향이지만, R.class 참조가
  정말 그래프에 필요 없는지 먼저 증명해야 하며(리소스 상수 인라인), 실패 이유를 공개 문서로 유지해야 한다.

## 검증 범위 (구현 시)

- 단위: 합성 witness로 R.jar 커버 유지·부재 시나리오 양쪽(양방향 코퍼스 관례 준수).
  부재 시에도 기존 `unwitnessed-class-root` 거부가 유지됨을 회귀로 잠근다.
- 통합: 최소 재현 앱에서 capture→verify `matched`, 무결성(수정 시 `stale`) 2회.
- 공개: AGP 최소/최대 조합 CI 작업과 독립 설치 검증의 검증표를 application으로 확장한 표를
  릴리스 노트에 추가한다. library 검증표는 그대로 남긴다.
