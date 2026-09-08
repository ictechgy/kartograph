# Dagger selected binding evidence

Dagger **2.59**의 공식 `dagger.spi.model.BindingGraphPlugin`으로 선택된 component graph를 직렬화하는
독립 실험이다. `@Inject`·`@Binds`·`@Provides`와 qualifier 선택을 실제 annotation processor로 컴파일한다.
Dagger가 생성한 코드도 실행해 서비스 출력과 생성자 실행 기록이 선택된 binding과 일치하는지 확인한다.

```sh
# JDK 17, 저장소 루트에서 실행
python3 experiments/dagger-bindings/run.py
```

최초 실행은 Maven Central에서 고정 의존성을 받는다. 실험의 `gradle/verification-metadata.xml`은 JAR/POM/module
SHA256을 검사한다. 59개 artifact를 공식 Maven Central의 원본과 대조했다. 제품 runtime에 이 의존성을 넣지 않는다.

`selected` component는 service entry → abstract @Binds method → Selected 생성자 → Dependency 생성자의
3개 의미적 참조를, qualifier를 바꾼 `unused` component는 @Provides method와 Unused 생성자의 2개 참조를 수집한다.
이름과 qualifier 원문을 추측하지 않고 Dagger가 선택한 executable element의 JVM identity를 쓴다. qualifier의
문자열 값 자체는 내보내지 않는다. 선택되지 않은 선언도 실제 bytecode에 존재함을 확인한다.

누락된 binding은 실제 `[Dagger/MissingBinding]` 진단의 컴파일 실패로 거부한다. source·variant label·classpath/class/collector 해시와 graph 지문이
일치하며 모든 선언이 존재할 때만 실험 graph에 `compilerReference` 출처의 `reference` 간선을 추가한다.
이는 binding 관계이며 abstract @Binds method를 JVM이 호출한다는 주장이 아니다. 입력 불일치도 검사한다.
sidecar는 저장 후 다시 읽어 보강하며, 같은 구조의 source/class/classpath 지문 불일치도 거부한다.
보고서는 `build/reports/dagger-bindings/`의 variant별 sidecar/graph JSON에 생성한다.

## 범위

- Javac backend와 전체 재컴파일만 검증한다. KSP/Hilt·Android variant wiring·증분 빌드는 미검증이다.
- full/module/partial binding graph는 선택된 runtime component graph와 섞지 않는다. 매핑되지 않는 간선이 있으면
  보강을 거부한다. 표본 밖 multibinding·members injection의 합성 binding은 별도 매핑 검증이 필요하다.
- 제품 CLI/plugin에 자동 수집·import를 추가하지 않으며 기본 DI 보존 정책을 약화시키지 않는다. 다른 component나
  외부 진입점까지 조사하지 않고 선택되지 않은 binding을 삭제 가능한 것으로 판정하지 않는다.
- `graphSha256`은 Python `json.dumps(graph, sort_keys=True)` UTF-8의 해시다. 이 실험 harness 내부 교환 계약이며
  외부 인증·서명을 뜻하지 않는다. variant는 fixture의 qualifier 선택 label이다.

참고: [Dagger SPI](https://dagger.dev/dev-guide/spi.html), [Javac/KSP SPI 구분](https://dagger.dev/dev-guide/ksp.html).
