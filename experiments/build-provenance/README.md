# 빌드 증거와 snapshot 신선도 검증

2026-09-12, PR #37의 `dc33ba0`을 기준으로 검증했다. [사용 계약](../../docs/BUILD-PROVENANCE.md)은
시간·commit 라벨과 파일 내용/성공한 compiler task 기록을 구분한다. 문서는 실제 실행 결과이며 모든 빌드 생산자나
런타임 경로의 인증이 아니다.

## 실제 Java 빌드에서 snapshot까지

작은 Java 프로젝트의 `A.work()` 호출을 `B.work()`로 바꾸고 파일 크기·mtime을 유지했다. 실제 Git commit은
갱신했지만 class 파일은 그대로 뒀다. 기준 버전은 최신 라벨을 받아들이면서 옛 A 호출을 유지했고 staleness를
표시하지 않았다. 새 버전에서는 등록된 `JavaCompile` producer의 입력·출력 기록을 붙여 다음을 확인했다.

| 단계 | `verify-snapshot` 결과 | 종료 코드 |
|---|---|---:|
| 정상 컴파일 → 증거 → snapshot | matched | 0 |
| 소스 내용만 변경, class 유지 | stale | 1 |
| 같은 오래된 class에 최신 commit 라벨로 다시 capture | stale | 1 |
| 변경 소스를 재컴파일하고 새 증거로 capture | matched | 0 |

외부 디렉터리를 `JavaCompile.destinationDirectory`로 지정한 별도 프로젝트도 검증했다. snapshot과 compiler
증거가 서로 다른 external 슬롯 이름을 사용해도 같은 경로와 바이트로 연결되면 matched였다. CLI의 명시적
`--classes` 순서 비교도 함께 실행했다.

## compiler lifecycle과 호환성

실제 Gradle TestKit의 Java/Kotlin 소비 프로젝트에서 source 변경, UP-TO-DATE, clean 후 FROM-CACHE,
configuration cache 재사용, 경로 이동, 구문 오류, 컴파일 중 소스 수정, 뒤에 추가된 action 실패와 dependency 실패를
검사했다. 실패한 build가 성공 증거를 남기지 않는지 확인했다. 테스트별 cache를 분리해 다른 테스트의 cache hit를
첫 실행 성공으로 오인하지 않았다. JDK 17·21의 정식 전체 테스트와 90% coverage 게이트가 통과했다.

Claude 리뷰 후에는 ABI가 같은 의존성 구현을 바꾸고 `clean`했을 때 옛 증거가 복원되는 반례를 추가했다.
독립적인 바이트 입력을 Gradle에 선언해 이를 고쳤으며, 생성 소스 provider·configuration cache 재사용과
`--continue`의 실패 무효화를 함께 검사했다. 인증서 리소스의 확장자나 `credentials`라는 상위 폴더 이름 때문에
snapshot이 실패하던 문제와 상대 `--generated-classes`의 경로 기준도 회귀 테스트로 고정했다.

Kotlin producer는 KGP의 공개 API를 실제 compiler task의 classloader에서 사용한다. Gradle의 내부
`ImplementationValue` 객체 대신 실제 구성된 compiler 인자를 지문에 넣으며, KGP 구현 artifact와 선언된 파일 입력도
기록한다. 컴파일러 runtime configuration은 호출자가 명시하며, 공개 source/library/plugin/friend 입력과
함께 바이트 키에 연결한다. KGP 내부 증분 캐시 파일을 공개 입력 API로 취급하지 않는다.
증거는 전용 output 디렉터리에 둬 Kotlin compiler의 output 준비·cache 복원과 함께 동작하게 했다.

Android는 기존 [fixture-library](../../fixtures/false-positive-corpus/fixture-library)의 AGP 9.3.2 / SDK 36 / debug
Kotlin compiler를 명시적으로 등록했다. JDK 17과 원래 Java/Kotlin bytecode target 11을 맞춰 실행했고, public
destination provider의 실제 output으로 snapshot을 만든 뒤 외부 입력 슬롯을 연결해 matched를 확인했다.
AGP 변환 후의 다른 JAR에 이 compiler 증거를 자동으로 귀속시키지는 않는다.

기존 Android 코퍼스 44 retained / 4 reportable, plugin 소비 및 최소 AGP 8.7.3 / Gradle 8.10.2 검증도 유지했다.
최소 조합 검사는 기존 plugin의 호환성이고, Kotlin producer의 모든 KGP 버전 지원을 뜻하지 않는다.

## 대형 입력과 비용

고정 nowinandroid `12f80da6518e161ed16a06a68e71fb8a873576d6`, demoDebug의 같은 22개 class root에서 양쪽 도구를
검증했다. 사라진 캐시 경로는 [기존 classpath 재현 절차](../../docs/PUBLIC-VALIDATION.md)를 통해 다시 생성했다.
기본 진단·private 진단·multipreview·생성 출처·미사용 대조군과 저장 질의/실시간 질의의 일치를 검사했다.
빌드를 제외한 capture와 질의 시간은 아래 JSON에 원본 관측으로 기록했다. 내용 해싱은 추가 비용이며 증분 빌드
또는 전체 CI의 속도 개선으로 표현하지 않는다.

[기계 판독 결과](results-2026-09-12.json)는 Java workflow, 외부 출력, Android workflow, 공개 표본, 자체 분석 및
검증 기록을 묶은 것이다. 로컬 절대경로·외부 슬롯의 실제 binding 파일·Gradle 원시 로그는 공개하지 않는다.

## 재현 진입점

```sh
./gradlew --no-daemon :gradle-plugin:test --tests '*CompilerWitnessIntegrationTest*'
./gradlew --no-daemon :index:test :export:test :cli:test
python3 -m unittest discover -s Scripts/tests -v
```

compiler 등록, capture 및 `verify-snapshot` 명령은 [BUILD-PROVENANCE](../../docs/BUILD-PROVENANCE.md)를 따른다.
source/class/config/classpath가 달라지는 사례와 build 증거가 없는 이전 snapshot을 구분한다. pre/post 관측 사이의
변경 후 원복, 선언되지 않은 processor 입력, 악의적인 증거 생산자는 이 방식만으로 배제하지 못한다.
