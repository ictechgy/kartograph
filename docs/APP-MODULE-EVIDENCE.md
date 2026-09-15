# Android application 모듈의 R.jar 증거 연결 — 재현·설계 노트

상태: **CLI 레벨 비교 실험 완료, 실제 AGP 재현 대기**. 이 문서는 `unwitnessed-class-root` 판정을
약화하지 않는 선에서 application 모듈 자동 캡처를 일반화하기 위한 실험 계획과 그중 CLI 절반의
실행 결과를 기록한다. 0.9.0의 자동 캡처 검증표는 **Android library**다. 앱 전체 지원으로 일반화하지 않는다.

## CLI 레벨 비교 실험 결과 (2026-09-14, AppModuleRJarCliTest / 2026-09-15 실물 산출물, RealAgpRJarCliTest)

실제 AGP 빌드 없이 verifier 계약을 양방향으로 실행했다(`cli/src/test/kotlin/.../AppModuleRJarCliTest.kt`):

- **재현.** javac 산출 `classes/`와 processResources 형태의 `generated/R.jar`를 같은 snapshot의
  class root로 캡처하고, `classes`만 커버하는 compiler witness를 붙였다. `verify-snapshot`은
  `unverified` + `unwitnessed-class-root`(exit 1)로 거부한다. AGP 8 application 거부의
  원인 가설이 verifier 계약과 일치함을 확인했다.
- **후보 A 계약 검증.** 같은 시나리오에 `processDebugResources`를 producer로 하는 두 번째 witness
  (inputs: sources=res, buildConfig, compiler, options / outputs: classes=generated/R.jar)를 추가하면
  `matched`(exit 0)가 된다. R.jar 내용을 한 바이트라도 바꾸면 `stale` + `changed-classes`로 실패한다.
  즉 witness 추가로 거부를 풀어도 producer 증거 강제력은 그대로 유지된다.
- capture 단계는 witness가 없어도 성공하고 거부는 verify 단계에서 일어난다는 경계도 그대로다.

**실물 AGP 산출물 실험 (2026-09-15, RealAgpRJarCliTest — 보존된 Now in Android demoDebug 빌드 산출물 기준).**
합성 픽스처를 실제 AGP 산출물로 대체해 동일 계약을 재검증했다:

- 입력: 실제 앱·라이브러리 모듈의 `transformDemoDebugClassesWithAsm/dirs` 22개(726개 class,
  Hilt/KSP 생성 코드 포함) + 실제 `compile_r_class_jar` 산출 `R.jar`.
- witness가 class 디렉터리만 커버하면 실물 R.jar root에서 `unverified` + `unwitnessed-class-root`,
  resource producer witness를 추가하면 `matched`, 실물 R.jar 변조 시 `stale` + `changed-classes`.
  합성 실험과 동일한 계약이 실물 산출물에서도 성립함을 확인했다.
- 남은 실 AGP 절반은 Gradle plugin wiring 자체다: 실제 `kartographSnapshotDebug` 실행(Gradle 데몬·
  AGP artifact 필요)으로 1단계를 마무리하고 위 스케치대로 구현한다.

## 현재 실패의 사실 관계

- AGP 8 application 모듈의 독립 사전검증에서 `kartographSnapshotDebug`가 snapshot 뒤 신선도 검사에서
  `unwitnessed-class-root`로 거부됐다. 재현 자료: `build/reports/product-limits-20260914/local-android-1.log`,
  `local-android-consumer/`(로컬 증거, Git 제외).
- 원인(아래 CLI 실험으로 확인): AGP 8 앱의 `process<Variant>Resources`가 만드는 생성 `R.jar`가 PROJECT class
  root에 포함되는데, compiler witness(javac/kotlinc task output)의 `outputs`는 javac classes만 커버한다.
  `ProvenanceVerifier`는 모든 `classes` role 입력이 어떤 witness output(경로+sha256)으로 덮이는지
  요구하므로(`index/src/main/kotlin/dev/kartograph/index/ProvenanceVerifier.kt`의 `unwitnessed-class-root`)
  R.jar가 덮이지 않으면 거부가 맞는 동작이다.
- 지금 하지 말아야 할 것: R.jar를 class root에서 몰래 제외하거나, witness 조건을 완화하거나,
  producer 없는 class root를 `unverified` 대신 `matched`로 승격하는 것. 이는 producer 증거 계약을
  약화한다([BUILD-PROVENANCE](BUILD-PROVENANCE.md)).

## 재현·비교 실험 계획 (구현 전 필수)

0. **CLI 절반 완료(위 섹션).** 남은 것은 실제 AGP 절반이다.
1. **최소 재현 유지(AGP 필요).** `Scripts/verify-agp-8-app-snapshot.sh`가 이 단계를 자동 실행한다
   (`GRADLE_8_HOME`·`ANDROID_HOME` 필요, `fixtures/agp-8-smoke` application fixture 사용).
   현재 계약: `kartographSnapshotDebug`가 `unwitnessed-class-root`로 실패하거나, 캡처가 성공하면
   verify 단계의 같은 사유를 확인한다. 캡처가 성공하는데 scope에 R.jar가 없으면 fixture에
   `src/main/res/values/strings.xml`을 추가해 R.jar 생성을 보장하는 것이 기록된 다음 진단이다.
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

### 후보 A 구현 스케치 (1단계 확인으로 확정 후 착수)

`AppModuleRJarCliTest`가 계약을 양방향으로 고정했으므로, wiring은 다음 순서로 기계적이다. 다만
1~3단계의 실측(어느 provider가 R.jar를 노출하는지)이 선행된다 — 이 스케치는 그 확인 전까지 착수하지 않는다.

1. **producer 식별.** 1단계 재현에서 `process<Variant>Resources` task의 declared outputs 중 R.jar의
   정확한 provider를 확인한다. 후보: task output file(`outputs.files`에서 이름이 `R.jar`인 것), AGP
   variant artifact provider, 또는 ScopedArtifact PROJECT scope가 R.jar를 병합하는 중간 디렉터리.
   `AndroidSnapshotTasks.kt` 70-77행의 `forScope(ScopedArtifacts.Scope.PROJECT).toGet(CLASSES)`가
   스냅샷 class root를 만드는 지점이고, 거부의 원인은 이 scope 안의 R.jar 기원 파일이 witness output
   목록에 없다는 것이다.
2. **witness 생성.** `CompilerWitnesses`에 `automaticResourceProcess(project, processResourcesTask,
   scope, additionalInputs)` 형태의 자동 producer를 추가한다. `BuildWitness`는 compiler 식별자를
   `"agp-process-resources"`로, inputs는 res 소스·buildConfig·compiler(aapt2)·options, outputs는
   R.jar fingerprint(`role="classes"`)로 채운다. 기록 실패 시 기존 compiler witness와 같이
   bounded rejection record로 실패시킨다.
3. **wiring.** `AndroidSnapshotTasks.register`에서 resource witness 파일을 `snapshot.buildWitnessFiles.from(...)`
   에 추가하고, `fingerprintFiles`의 classes 입력이 R.jar root를 계속 포함하는지 유지한다.
   `ProvenanceVerifier` 변경은 불필요하다 — AppModuleRJarCliTest가 보여줬듯 기존 판정으로 matched가 된다.
4. **회귀 앵커.** `AppModuleRJarCliTest`는 그대로 유지하고, `Scripts/verify-agp-8-app-snapshot.sh`의
   기대를 `matched`로 뒤집는다. 거부 경로의 회귀는 script의 실패 모드와 코퍼스 단위 테스트가 잠근다.
5. **검증표 확장.** library 검증표에 application 행을 추가하고(AGP 최소/최대 조합·독립 설치), 릴리스
   노트의 "Android library 전용" 문구를 갱신한다.

## 검증 범위 (구현 시)

- 단위: 합성 witness로 R.jar 커버 유지·부재 시나리오 양쪽(양방향 코퍼스 관례 준수).
  부재 시에도 기존 `unwitnessed-class-root` 거부가 유지됨을 회귀로 잠근다.
- 통합: 최소 재현 앱에서 capture→verify `matched`, 무결성(수정 시 `stale`) 2회.
- 공개: AGP 최소/최대 조합 CI 작업과 독립 설치 검증의 검증표를 application으로 확장한 표를
  릴리스 노트에 추가한다. library 검증표는 그대로 남긴다.
