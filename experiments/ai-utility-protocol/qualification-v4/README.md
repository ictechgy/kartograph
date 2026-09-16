# v4 자격 검증 기록 (모델 실행 전)

[README.md](../README.md)의 "oracle 구성 절차 (v4, 실행 전 고정)"를 그대로 실행한 기록이다.
모델은 실행하지 않았고 제품 결과로 oracle을 바꾸지 않았다. 결과를 주장하지 않는다.

- 고정 표본: [cohort-v4.json](../cohort-v4.json)
- 확정 oracle: [oracle-v4.json](../oracle-v4.json), sha256 `c05648e7916af7a0ad039cba4bfcf77caa9d5e8779c8fe7d8248679e77a276fb`
- 도구·해시·scratch 경로: [environment.json](environment.json)
- 사례별 기록: [alibaba__fastjson2-2097](alibaba__fastjson2-2097.json),
  [detekt_detekt-7625](detekt_detekt-7625.json),
  [fasterxml__jackson-core-1016](fasterxml__jackson-core-1016.json),
  [pinterest_ktlint-2785](pinterest_ktlint-2785.json)

- 증거 수집기: [tools/collect_refs.py](tools/collect_refs.py), [tools/driver2.py](tools/driver2.py)
- 수집기 입력(class root·바뀐 심볼): [inputs/](inputs)

snapshot·javap 덤프·빌드 로그는 다중 MB라 저장소에 넣지 않고 sha256과 scratch 상대경로만 남겼다.
로컬 절대경로는 공개 기록에서 제외하고 `<scratch>`·`<kartograph-cli-install>` 토큰으로 적는다.
저장한 수집기는 실행본과 import 경로 한 줄만 다르며, 저장본으로 네 사례를 다시 수집해
`direct-invocations.json`과 `depth-summary.json`이 바이트까지 같음을 확인했다.

## 요약

| 사례 | 빌드 | 선언 f2p의 base 상태 | 직접 | 간접 | oracle 합계 | recall 천장 | transitive 사례 |
|---|---|---|---:|---:|---:|---:|---|
| alibaba__fastjson2-2097 | 성공 | 없음(테스트 파일 자체가 test_patch 신규) | 4 | 3 | 7 | 1.00 | 예 |
| detekt_detekt-7625 | 성공 | 존재·통과 | 1 | 3 | 4 | 1.00 | 예 |
| fasterxml__jackson-core-1016 | 성공(대체 POM) | 클래스 존재·통과, 신규 메서드 2개 없음 | 21 | 11 | 32 | 0.375 | 아니오 |
| pinterest_ktlint-2785 | 성공(JDK 21) | 메서드 없음(PR 신규), 포함 클래스 통과 | 2 | 2 | 4 | 1.00 | 아니오 |

응답 상한은 사례당 12개다. jackson만 oracle이 32개라 recall 천장이 0.375이며, 이 사례의 낮은 recall을
도구 효용 차이로 읽으면 안 된다. 나머지 세 사례의 천장은 1.00이다.

## 선언 fail-to-pass는 네 사례 모두 변경 전 원본에서 실패하지 않는다

dataset이 선언한 fail-to-pass는 test patch를 적용한 상태에서 정의된다. 변경 전 원본에는 해당
테스트가 없거나(3건) 이미 통과한다(1건). 숨기지 않고 그대로 기록한다. 새 테스트를 주입하지 않았고
기존 테스트를 고치지 않았다.

- fastjson2: `com.alibaba.fastjson2.issues_2000.Issue2096` 파일이 base에 없다(test_patch 신규 파일).
- jackson: `JsonFactoryTest`는 base에 있고 7개가 통과한다. f2p를 만드는
  `testCanonicalizationEnabled`/`testCanonicalizationDisabled`는 test_patch가 추가한다.
- detekt: `BaselineResultMappingSpec.creates on top of an existing a baseline file without issues()`는
  base에 있고 통과한다. PR의 test diff는 기대 XML을 `<CurrentIssues></CurrentIssues>`에서
  `<CurrentIssues/>`로 바꾸는 수정이며, 그 수정 없이는 base에서 실패하지 않는다.
- ktlint: `Issue 2779 - ...` 메서드가 base에 없다(PR 신규). 포함 클래스 34개는 통과한다.

## oracle 구성 방법

1. **바뀐 심볼**은 net production diff가 바꾼 선언을 원본 source와 javap 선언으로 확인해 정했다.
   Kotlin 두 사례의 PR은 여러 commit이므로 GitHub의 최종 net diff를 받아 test 파일을 뺀 뒤
   `git apply --check`로 고정 base에 적용 가능함을 확인했다(네 사례 모두 exit 0). diff는 적용하지 않았다.
2. **직접 대상**은 javap의 method/field 참조다. 원본 `collect_direct_oracle.py`를 그대로 쓰되
   멀티 모듈 class root와 field 참조(getstatic/putstatic)를 다루기 위해 같은 파서를 import하는
   `collect_refs.py`로 확장했다. 두 Java 사례에서 확장본과 원본의 결과가 depth1·depth2·indirectNew
   모두 완전히 일치함을 확인했다.
3. **간접 대상**은 depth-1 직접 호출자 **전부**를 같은 방식으로 확장해 얻은 depth-2 참조 중
   바뀐 심볼·직접 대상에 없던 것이다. 어느 호출자를 확장할지 고르지 않았고 상한도 두지 않았다.
4. **테스트 대상**은 위 참조 집합에 들어온 test source root 선언 중 원본 base에서 실제 실행된
   것만 `test`로 분류하고 실행 결과를 함께 기록했다. 실행된 대상은 모두 PASS였다. 새 PR 테스트는 넣지 않았다.
   테스트 클래스의 헬퍼·`@Parameters` 공급자처럼 JUnit 테스트 메서드가 아닌 선언은 `caller`로 두고
   `baseRunResult`에 그 사실을 적었다.
5. **override 지점**은 원본 선언으로 확인했다. jackson의 `TokenStreamFactory` abstract 선언 2건만
   해당한다. 나머지 세 사례의 바뀐 심볼은 static·private·protected final·companion field라 override 지점이 없다.
   등록 지점은 네 사례 모두 바뀐 심볼에 대해서는 없었고, 확인했지만 넣지 않은 항목은 사례 기록의
   `notInOracle`에 적었다.
6. 후보 사례가 4건(모두 새 depth-2 대상 ≥1)이라 사전 규칙대로 **사례 id 오름차순 앞 2건**인
   `alibaba__fastjson2-2097`과 `detekt_detekt-7625`를 "직접·간접 영향 나열" 사례로 정했다.
   정렬 키는 cohort-v4.json의 `id` 문자열이며 Unicode 오름차순이다. 제품·모델 결과는 보지 않았다.

### 균일한 owner 단위 규칙을 쓰지 않은 이유

바뀐 심볼의 **소유 클래스 전체**를 대상으로 삼으면 test 참조가 fastjson2 1건, detekt 5건, ktlint 0건인데
jackson은 461건이다. `JsonFactory`가 사실상 모든 테스트에서 쓰이기 때문이며 바뀐 메서드의 직접 참조가
아니다. 사례마다 다른 규칙을 쓰지 않기 위해 owner 단위는 채택하지 않았고, 그 결과 fastjson2와 ktlint의
oracle에는 `test` 대상이 없다. owner 단위 스캔 결과는 사례 기록의 `ownerLevelScanNote`에 남겼다.

## 제품 `impact` 오프라인 대조

모델 실행 전에 끝냈다. 제품 결과는 정답이 아니며 **oracle 항목을 추가하거나 빼지 않았다.**
제품만 찾은 대상은 사례 기록의 `productOnlyDirect`/`productOnlyIndirect`에만 남겼다.

| 사례 | 직접 javap/제품 direct/일치 | 간접 javap/제품 transitive/일치 |
|---|---|---|
| alibaba__fastjson2-2097 | 4 / 4 / 4 | 3 / 3 / 3 |
| detekt_detekt-7625 | 1 / 1 / 1 | 3 / 2 / 2 |
| fasterxml__jackson-core-1016 | 19 / 19 / 19 | 11 / 11 / 11 |
| pinterest_ktlint-2785 | 2 / 3 / 2 | 2 / 27 / 2 |

기록한 불일치는 두 건이다.

- **detekt**: `BaselineResultMapping.createOrUpdate`는 javap상 `createOrUpdate → BaselineFormat.write →
  save`의 확정 2단계 호출 사슬을 가진다. 제품은 `--depth 2`에서 이 대상을 affected에 넣지만 relation을
  `transitive`가 아니라 `structural`로 주고 경로를 `createOrUpdate → ValuesWithReason.iterator() → save`라는
  보수적 dispatch 경로로 표시한다. 같은 그래프에서 `impact 'BaselineFormat#write' --depth 1`을 실행하면
  같은 대상이 `direct`로 나온다. javap 증거를 유지해 oracle에 남겼다.
- **ktlint**: 제품은 depth 1에서 `class:SuppressionLocator` 노드를, depth 2에서 그 클래스의 다른 멤버
  27건을 반환한다. 클래스 포함 관계를 따라간 결과이며 javap의 호출·참조 사슬이 아니다. oracle에 넣지 않았다.

제품의 depth-1 `structural` 항목은 fastjson2 8건, jackson 6건, detekt 5건, ktlint 0건이다. 이 중
jackson의 `TokenStreamFactory` abstract 선언 2건만 원본 선언으로 독립 확인해 `override`로 넣었고
나머지는 넣지 않았다. 수동 snapshot이라 compiler witness가 없어 제품 신선도는 `unverified`로 남는다.

## 빌드 우회

- **jackson**: 원본 parent가 미배포 `jackson-base:2.16.0-SNAPSHOT`이다. checkout 루트에 추적되지 않는
  `pom-release-parent.xml`을 만들어 parent만 정식 `2.16.0`으로 바꾸고 `-f`로 지정했다. 원본 pom.xml과
  source는 그대로다. v3의 같은 우회와 동일하다.
- **ktlint**: build-logic이 `java-compilation=21` toolchain으로 precompiled script plugin을 만들어
  class file 65를 낸다. JDK 17로 Gradle을 실행하면 실패하므로(로그 보존) ktlint만 JDK 21.0.12로 실행했다.
  저장소 파일은 바꾸지 않았다.
- **detekt**: Develocity(ge.detekt.dev) remote cache와 Predictive Test Selection이 이 환경에서 접속되지
  않아 Gradle이 자동으로 remote cache를 끄고 전체 테스트를 선택했다. 설정 파일은 바꾸지 않았다.
- **snapshot**: 기본 v1 형식은 fastjson2와 detekt에서 128 MiB 상한을 넘겨 실패했다. 네 사례 모두
  무손실 `--compact`에 `--snapshot-max-mib 128`을 붙여 다시 캡처했다. 첫 실패도 기록에 남겼다.

## 한계

- 네 checkout의 추적 파일은 변경 0건이다. jackson만 추적되지 않는 대체 POM 1개가 추가돼 있다.
- oracle은 완전한 동적 영향 정답이 아니다. javap 직접/2단계 참조와 실제 실행한 기존 테스트,
  원본 override 선언으로 이루어진 검토 anchor다.
- jackson의 oracle 32개는 응답 상한 12개를 넘는다. 이 사례의 recall은 구조적으로 0.375를 넘을 수 없다.
- fastjson2와 ktlint의 oracle에는 test 대상이 0개다. 균일한 증거 규칙을 유지한 결과이며
  "관련 테스트가 없다"는 주장이 아니다.
