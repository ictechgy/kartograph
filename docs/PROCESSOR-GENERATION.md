# Processor source generation evidence

개발 소스의 `javac-processors` collector는 명시한 JSR-269 processor 하나를 실행하면서
`Filer.createSourceFile`과 출력의 정상 close를 관찰한다. 파일 이름·`_Factory` suffix나
`@Generated` 문자열로 processor를 추측하지 않는다. 현재 배포된 0.12.0에는 포함되지 않는다.

## 수집과 확인

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

[기존 compiler witness 절차](../compiler-collectors/README.md)에 따라
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

현재는 javac JSR-269의 source 파일만 수집한다. KAPT/KSP, processor의 직접 filesystem
쓰기, class/resource 출력, processor 간 위임까지 완전하게 복원하지 않는다. javac 내부
환경의 구체 타입을 요구하는 processor는 wrapper와 호환되지 않을 수 있다. 그런 실패를
빈 성공 근거로 바꾸지 않는다. 이 경계는 실제 generic JSR-269 fixture와 Dagger2.59에서 검증한다.
annotation processing을 끄거나 adapter가 한 번도 실행되지 않으면 원시 근거를 만들지 않고
설정 진단을 남긴다. Gradle의 완료 witness 검증도 누락된 근거를 성공으로 수락하지 않는다.
