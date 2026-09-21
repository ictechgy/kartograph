# Processor generation evidence

0.14.0의 v2 기능과 0.15.0의 [v3 확장](#v3-task-입력과-jvm-선언-귀속)을 구분한다.

0.13.0의 `javac-processors` collector는 명시한 JSR-269 processor 하나를 실행하면서
`Filer.createSourceFile`과 출력의 정상 close를 관찰한다. 파일 이름·`_Factory` suffix나
`@Generated` 문자열로 processor를 추측하지 않는다. 0.12.0에는 포함되지 않는다.

## 수집과 확인

0.15.0의 collector JAR·runner·cache adapter는 별도
[collector ZIP](https://github.com/ictechgy/kartograph/releases/tag/v0.15.0)으로 배포하며,
CLI ZIP/TAR나 Portal plugin runtime에 포함하지 않는다. 소스로 빌드하려면
[v0.15.0 소스](https://github.com/ictechgy/kartograph/tree/v0.15.0)를 checkout하고 JDK 17로 실행한다.

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

## Output collector: KAPT/KSP와 여러 출력 종류

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
출력 root·바이트를 확인한 뒤 별도 `kartograph-processor-output-witness` v2 receipt를 쓴다.
실패한 재실행은 기존 receipt를 먼저 없애며 미닫힌 출력·변조·실패 빌드를 수락하지 않는다.

```bash
python3 compiler-collectors/processor_output_witness.py record --config /path/to/local-config.json
python3 compiler-collectors/processor_output_witness.py verify --config /path/to/local-config.json
```

설정에는 `project`, `scope`, `kind`, `processor`, `collectorJar`, `processorJar`, 프로젝트 상대
`inputs`/`outputRoots`, 서로 다른 `token`/`observations`/`receipt`, 실행할 `command` 인자 배열을
지정한다. control 파일은 output roots·명시된 입력·processor artifact 밖에 둔다. 실제 설정 예와 생성기 코드는
[`tests/output_attribution.py`](../compiler-collectors/tests/output_attribution.py)에 있다.
관찰 범위는 설정에 명시한 입력이다. 이 runner는 모든 compiler의 숨은 입력·전이 의존성이나
생산자 인증을 보장하지 않으며 기존 Gradle compiler witness를 대체하지 않는다.
기존 compiler witness 역시 생산자 인증은 제공하지 않는다.

### Snapshot 연동과 receipt 버전

```bash
kartograph snapshot --project /project --classes /project/build/classes/kotlin/main \
  --scope app:main --processor-output-config /path/to/local-config.json > snapshot.json
```

Gradle snapshot task에는 `processorOutputConfigs.from('local-config.json')`를 지정한다.
기존 compiler witness 검증은 유지한다. 두 경로 모두 config의 command를 실행하지 않고,
project·scope·입력·artifact·token·raw·관찰된 모든 출력의 bytes를 재검증한다. snapshot을
생성하는 동안 입력이 바뀌면 실패한다. CLI는 잘못된 receipt에 코드 2를 반환하고 부분 snapshot을 쓰지 않는다.

v1/v2 snapshot의 선택적 `processorOutputs`는 processor kind/identity, collector와 processor
artifact 지문, raw 지문, scope, 출력의 상대 path·kind·observation·raw SHA-256을 보존한다.
`processorGenerations`와 별도이며 그래프 간선·`synthesized`·reachability·dependency unused
판정을 바꾸지 않는다. 필드가 없는 이전 snapshot은 빈 목록으로 읽는다. local command와
artifact 절대경로는 snapshot에 쓰지 않는다. provenance는 설정·receipt·raw·명시한 입력과
관찰된 출력 파일을 추적한다. 출력 root 전체나 미관찰 파일의 완전성을 주장하지 않는다.

runner의 새 v2 receipt는 제품의 길이 구분 content fingerprint를 사용한다. 기존 개발 runner의
v1 receipt는 독립 `verify`에서 계속 확인하지만 snapshot 연결에는 v2 재수집이 필요하다.
성공한 v2 실행은 후속 Gradle dependency가 같은 입력을 재사용하도록 token 파일을 유지한다.
token은 완료 증거가 아니며 실패한 재실행은 token과 이전 receipt를 없앤다.

### Gradle cache 복원

collector ZIP의 `processor_output_cache.gradle`을 적용한 뒤 선택한 native task를 등록한다.

```groovy
apply from: '/path/to/processor_output_cache.gradle'
registerProcessorOutputCache('kspKotlin', file('local-config.json'))
// KAPT는 kaptKotlin, javac는 compileJava를 선택한다.
```

설정의 `inputs`에 적용한 adapter와 build 설정을 포함하고, `cacheOutputs`에는 callback 직접
쓰기로 생성할 **파일**의 프로젝트 상대경로를 지정한다(예: `["direct/direct.txt"]`). adapter는
native task의 기존 출력 선언을 유지한 채 raw sidecar와 이 파일만 더한다. 다른 task의 출력이나
손작성 파일을 cacheOutputs에 넣지 않는다. `--build-cache --configuration-cache`로 실행하고,
KAPT의 `useBuildCache=true`와 각 compiler의 전체 processing 설정을 사용한다.

runner가 raw를 지운 뒤 native task가 FROM-CACHE로 복원하더라도, 현재 입력 token과 source·
class·resource·직접 쓰기 bytes가 일치해야 receipt를 완료한다. raw가 cache에 없거나 task가
실행되지 않은 상태를 빈 성공으로 취급하지 않는다. Gradle의 입력 선언과 별도로 명시한 runner
입력만 검증하므로 전체 compiler input closure나 다른 Gradle 버전의 cache 호환성을 보장하지 않는다.

`./gradlew --no-daemon -p compiler-collectors outputAttributionTest`는 실제 javac17,
KAPT/Kotlin2.4.10, KSP2.3.12에서 각 4종류 출력을 확인한다. source/class/resource 바이트와
손작성 파일 제외, 입력/출력/raw/scope stale, 미닫힌 출력·깨진 생성 소스의 빌드 실패를 대조한다.
이 검사는 `integrationTest`에도 포함되며 native task build cache·configuration cache 대조를 수행한다.
`KARTOGRAPH_SNAPSHOT_CLI`를 설치된 CLI 경로로 지정하면 snapshot metadata와 그래프·보존 불변,
변조된 출력 거부까지 검사한다. 0.14.0의 선택적 collector에 포함되며 0.13.0에는 포함되지 않는다.

<a id="개발-소스-v3-task-입력과-jvm-선언-귀속"></a>

## v3 task 입력과 JVM 선언 귀속

이 절의 기능은 0.15.0부터 제공한다. 기존 0.14.0 ZIP·Portal plugin은 v2까지
지원하며, v3를 사용하려면 0.15.0의 collector runner/cache adapter와 CLI/plugin을 함께 설치한다.
기존 설정에 다음 선택 필드만 추가하고, 위의 `registerProcessorOutputCache`로 실제 native task를 선택한다.

```json
"compilerInputs": ".evidence/compiler-inputs.tsv"
```

adapter는 선택한 task의 `TaskInputs.files` 전체와 `TaskInputs.properties`를 실행 전후에
관찰한다. nested implementation은 식별자와 구현 artifact bytes를 포함하고, 지원하지 않는
속성 형태·실행 중 바뀐 입력은 실패한다. 읽기 전용 verify는 당시 속성 지문을 대조하며
Gradle 속성을 다시 평가하지 않으므로 환경만 바뀐 새 빌드 의도는 재수집해야 한다. KSP 2.3.12는 library를 ABI `output.bin`으로
노출하므로 `KspGradleConfig`의 원본 libraries·processor classpath·source roots도 함께 읽는다.
상위 task 전체·프로세스의 숨은 파일/환경 접근·생산자 인증은 포함하지 않는다.

완료된 v3 receipt의 `observation.compilerInputs`는 `coverage: "gradle-declared-task-inputs"`,
`complete: false`, task identity, `project/<프로젝트 상대경로>`·`external/compiler-input-N` 지문, 속성 지문과 `inventorySha256`을
보존한다. slot은 해당 receipt 안에서 해석한다. 로컬 TSV에는 실제 binding이 있으므로 공개
artifact에 올리지 않는다. snapshot에는 절대 binding·원시 옵션 값을 쓰지 않는다.
`inventorySha256`은 scope·task·token·속성·파일 kind/identity/hash를 길이 구분해 지문화하며
로컬 binding은 제외한다. 원본 TSV와 원시 입력을 보존하고 같은 bytes를 다른 위치에 보관할 때
binding만 재연결할 수 있다. 변경한 binding도 현재 파일의 bytes·kind·프로젝트 경계로 다시 검증한다.

Gradle이 입력을 ABI로 정규화해 이전 task 산출물을 FROM-CACHE로 복원해도 runner는 현재
원본 bytes와 대조한다. 불일치하면 receipt를 완료하지 않는다. 필요한 경우 명시한 실행 명령에
`--rerun-tasks`를 추가해 다시 수집한다. 검증 명령이 자동으로 compiler를 재실행하지 않는다.
실패한 실행은 이전 receipt와 입력 관찰 완료 파일을 없애며, v1/v2 독립 verify와 v2 snapshot
입력은 계속 지원한다. v3 receipt를 v2 CLI로 가져오는 것은 지원하지 않는다.

현재 bytes가 확인된 v3 API `class` 출력만 `processorOutputs[].declarations`로 연결한다.
ASM의 정확한 JVM class ID, 원래 선택된 class root의 동일 bytes, 그래프의 MEMBER 관계를 사용한다.
동명 class가 앞선 root의 다른 bytes로 가려지면 귀속하지 않고 미매핑 수를 남긴다. 생성 source와
최종 class의 관계는 basename으로 추측하지 않으며 resource·callback 직접 쓰기도 JVM 선언으로
바꾸지 않는다. 그래프 간선·retention·synthesized·dependency 판정과 기존 compiler witness 계약은 유지한다.

실제 javac17/KAPT2.4.10/KSP2.3.12에서 각각 4종 출력과 15/16/21개 입력 관찰을 검증했다.
native FROM-CACHE·configuration cache, 수동 목록 밖의 classpath 변경과 정규화 cache 거부,
출력/raw/scope/속성 변경, 미닫힘·깨진 생성 source를 대조한다. 설치한 0.15.0 CLI를
`KARTOGRAPH_SNAPSHOT_CLI`로 지정한 `outputAttributionTest`는 JVM 귀속과 기존 graph/retention
불변도 검사한다. 이 수치는 고정 fixture 관찰이며 모든 Gradle/compiler 버전의 완전성이 아니다.
