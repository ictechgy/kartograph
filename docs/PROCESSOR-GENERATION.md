# Processor source generation evidence

0.13.0의 `javac-processors` collector는 명시한 JSR-269 processor 하나를 실행하면서
`Filer.createSourceFile`과 출력의 정상 close를 관찰한다. 파일 이름·`_Factory` suffix나
`@Generated` 문자열로 processor를 추측하지 않는다. 0.12.0에는 포함되지 않는다.

## 수집과 확인

collector JAR는 선택적 소스 빌드 산출물이며 CLI ZIP/TAR나 Portal plugin의 runtime에
포함되지 않는다. [v0.13.0 소스](https://github.com/ictechgy/kartograph/tree/v0.13.0)를
checkout한 저장소 루트에서 JDK 17로 빌드한다.

```bash
./gradlew --no-daemon -p compiler-collectors integrationTest
```

선택한 processor와 collector JAR를 javac processor path에 넣고 다음 인자를 전달한다.
경로는 해당 프로젝트의 실제 경로 또는 file URI다.

```text
-processor dev.kartograph.collectors.RecordingProcessor
-Akartograph.processor=dagger.internal.codegen.ComponentProcessor
-Xplugin:KartographEvidence collector=javac-processors root=<project> output=<evidence> token=<token-file>
-Akartograph.evidence.root=<project>
-Akartograph.evidence.output=<evidence>
-Akartograph.evidence.token=<token-file>
```

[기존 compiler witness 절차](https://github.com/ictechgy/kartograph/blob/v0.13.0/compiler-collectors/README.md)에 따라
`CompilerWitnesses.javaCompile(..., compilerEvidence = true)`를 등록한다. 수집기 출력만으로
빌드 성공을 주장하지 않는다. 완료 receipt는 generating processor artifact가 실제 compiler
입력에 포함되는지, source가 선언된 generated source root 안인지, 원본 bytes와 token이
같은지를 검증한다. 실패·partial·이전 sidecar·변조된 source는 완료 근거로 수락하지 않는다.

기존 `snapshot --scope ... --build-witness ... --compiler-evidence ... --input ...`가 이를
다시 확인한 뒤 JSON의 `processorGenerations`에 아래 정보를 보존한다. v1/v2 snapshot 모두
같은 필드를 사용하며, 기존 snapshot에서 생략된 필드는 빈 목록으로 읽는다.

- `processor`: 실행한 processor의 Java 클래스 이름.
- `artifactSha256`: witness와 같은 content fingerprint. JAR raw SHA-256과는 구분한다.
- `sources`: 실제 생성된 source의 프로젝트 상대 `path`와 raw byte `sha256`.

`Scripts/verify-compiler-evidence.py`는 실제 Gradle·Dagger2.59 출력에서 snapshot까지
연결하고 configuration/build cache, 한 파일 변경, source receipt, 실패 빌드를 대조한다.
단위 소비 경계는 수동 receipt로 검사하고, 실제 수집기 실행과 구분한다.

## 해석 범위

생성 귀속은 관찰 메타데이터다. 그래프 간선·synthesized·reachability·dependency unused
판정을 바꾸지 않는다. 생성 source가 0개인 processor도 검증·리소스 생성 역할을 수행할 수
있으므로 제거 후보로 바꾸지 않는다. dependency 좌표는 이 artifact 근거와 별도로 대조해야 한다.

0.13.0의 snapshot 연동은 javac JSR-269의 source 파일만 수집한다. KAPT/KSP, processor의 직접 filesystem
쓰기, class/resource 출력, processor 간 위임까지 완전하게 복원하지 않는다. javac 내부
환경의 구체 타입을 요구하는 processor는 wrapper와 호환되지 않을 수 있다. 그런 실패를
빈 성공 근거로 바꾸지 않는다. 이 경계는 실제 generic JSR-269 fixture와 Dagger2.59에서 검증한다.
annotation processing을 끄거나 adapter가 한 번도 실행되지 않으면 원시 근거를 만들지 않고
설정 진단을 남긴다. Gradle의 완료 witness 검증도 누락된 근거를 성공으로 수락하지 않는다.

## 개발 collector: KAPT/KSP와 여러 출력 종류

선택적 collector 소스에는 `OutputRecordingProcessor`(javac/KAPT)와
`RecordingSymbolProcessorProvider`(KSP2.3.12)가 추가됐다. 기존 v2 source-only 형식을
넓혀 해석하지 않고 `kartograph-processor-outputs` v1 raw 문서를 만든다. source·class·resource의
API 생성과 정상 close, 실행한 processor/provider 클래스와 JAR 지문을 기록한다.
KSP에서는 실제 `CodeGenerator.generatedFile`의 새 파일을 관찰하며 경로를 확장자만으로 추측하지 않는다.
[공식 CodeGenerator 계약](https://github.com/google/ksp/blob/2.3.12/api/src/main/kotlin/com/google/devtools/ksp/processing/CodeGenerator.kt)에 따른다.

두 adapter에 `kartograph.processor=<delegate class>`와 아래 옵션을 넘긴다.
javac는 `-A` 옵션, [KAPT](https://kotlinlang.org/docs/kapt.html)는 `kapt.arguments`, KSP는 `ksp.arg`를 사용한다.
javac/KAPT에서는 adapter만 annotation processor로 선택한다. KAPT fixture는 adapter 이름 하나만
있는 service selector JAR를 사용한다. KSP는 collector의 service provider를 사용하고 delegate가
별도로 자동 발견되지 않도록 선택한다. 여러 processor의 출력을 하나로 섞어 귀속하지 않는다.

```text
kartograph.outputs.kind=javac|kapt|ksp
kartograph.outputs.root=<project path or file URI>
kartograph.outputs.output=<raw TSV path or file URI>
kartograph.outputs.token=<runner pending-token path or file URI>
kartograph.outputs.directRoot=<optional existing project subdirectory path>
```

`directRoot`는 API 출력 디렉터리와 분리한다. delegate의 init/process/finish callback 전후
지정 디렉터리의 파일 바이트가 바뀐 경우 `kind=file, observation=callback-scope`로 기록한다.
Filer/CodeGenerator 출력의 `observation=api`와 구분한다. 기존의 그대로인 파일은 제외한다.
동시에 실행된 다른 writer의 신원, callback 밖 비동기 쓰기, 동일 bytes 재쓰기, 전체 파일 시스템의
완전한 쓰기 이력을 증명하지 않는다. 그러므로 직접 쓰기 관찰을 정확한 API 호출 귀속으로 바꾸지 않는다.

raw 파일만으로 빌드 성공을 주장하지 않는다. 함께 제공되는
[`processor_output_witness.py`](../compiler-collectors/processor_output_witness.py)는 승인된 로컬
명령을 실행하고 코드 0, 명시된 입력의 전후 지문, collector/processor artifact, token,
출력 root·바이트를 확인한 뒤 별도 `kartograph-processor-output-witness` v1 receipt를 쓴다.
실패한 재실행은 기존 receipt를 먼저 없애며 미닫힌 출력·변조·실패 빌드를 수락하지 않는다.

```bash
python3 compiler-collectors/processor_output_witness.py record --config /path/to/local-config.json
python3 compiler-collectors/processor_output_witness.py verify --config /path/to/local-config.json
```

설정에는 `project`, `scope`, `kind`, `processor`, `collectorJar`, `processorJar`, 프로젝트 상대
`inputs`/`outputRoots`, 서로 다른 `token`/`observations`/`receipt`, 실행할 `command` 인자 배열을
지정한다. control 파일은 output roots·명시된 입력·processor artifact 밖에 둔다. 실제 설정 예와 생성기 코드는
[`tests/output_attribution.py`](../compiler-collectors/tests/output_attribution.py)에 있다.
fixture는 `--rerun-tasks --no-build-cache --no-configuration-cache`로 전체 재실행하며, 이 새 runner에
Gradle cache 복원 지원을 주장하지 않는다. 관찰 범위는 설정에 명시한 입력이다. 이 runner는 모든 compiler의
숨은 입력·전이 의존성이나 생산자 인증을 보장하지 않으며 기존 Gradle compiler witness를 대체하지 않는다.
기존 compiler witness 역시 생산자 인증은 제공하지 않는다. 새 receipt는 snapshot 그래프·간선·
`synthesized`·dependency unused 판정에 연결하지 않은 별도 생성 관찰이다.

`./gradlew --no-daemon -p compiler-collectors outputAttributionTest`는 실제 javac17,
KAPT/Kotlin2.4.10, KSP2.3.12에서 각 4종류 출력을 확인한다. source/class/resource 바이트와
손작성 파일 제외, 입력/출력/raw/scope stale, 미닫힌 출력·깨진 생성 소스의 빌드 실패를 대조한다.
이 검사는 `integrationTest`에도 포함된다. 개발 소스 변경이며 0.13.0 배포 JAR에는 포함되지 않는다.
