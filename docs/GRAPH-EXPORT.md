# 그래프 내보내기

`graph`는 전달한 class root를 한 그래프로 만든다. 정확한 JVM 호출 대상이 다른 입력 root에
있으면 일반 `call` 간선으로 연결한다. 같은 class가 여러 root에 있으면 첫 입력을 사용한다.

```sh
kartograph graph --classes build/classes --format ndjson > graph.ndjson
kartograph graph --classes library/classes --module-name library \
  --classes application/classes --module-name application \
  --format neo4j-csv --output-directory graph-export
```

`--module-name`은 바로 앞 `--classes`에 적용된다. 경로에서 모듈을 추측하지 않는다.
`--include-paths --project .`는 JSON, NDJSON, CSV에서 선택적으로 소스 경로를 해석한다.

## 형식과 메모리

- `json`: 기존 `code-graph` v1 구조를 유지하며 CLI는 Writer에 순서대로 쓴다.
- `ndjson`: 첫 행은 `code-graph-ndjson` v1 header, 이후는 `record`가 구분하는
  `node`, `edge` 및 외부 호출·서비스·enclosure·callback·compiler 위치 등의 근거 행이다.
- `neo4j-csv`: 새 디렉터리에 고정 헤더의 `nodes.csv`, `edges.csv`, 추가 근거의
  `facts.ndjson`, 완료 정보의 `manifest.json`을 쓴다. Neo4j bulk-import header를 사용한다.

CLI의 JSON/NDJSON 출력은 문서 전체 문자열과 JSON 행 목록을 만들지 않는다.
컴파일된 그래프와 분석 색인 자체는 여전히 메모리에 존재한다. 라이브러리의 기존
`GraphJsonRenderer.render`는 반환 타입이 String이므로 전체 문자열을 만든다.
CSV는 닫힌 staging 디렉터리를 게시한다. 기존 목적지는 거부하며 동일 도구의 동시
게시자는 reservation으로 직렬화된다. 실패 시 이번 호출의 staging만 정리한다.
종료된 프로세스의 reservation 파일이 남아 있으면 자동으로 제거하지 않고 새 목적지를 요구한다.
다른 프로세스로 같은 목적지를 동시에 수정하지 않는다. 출력 stream이 중간에 실패하면
종료 코드는 2이며, 해당 출력은 완성 문서로 소비하지 않는다.

## 후보와 출처

`--exclude-origin bytecode|kotlinMetadata|dispatchModel|runtimeModel|compilerReference`는 반복할 수 있다.
`dispatchModel`을 제외하면 후보 간선 생성도 건너뛴다. 제외는 간선에 적용되며 외부 호출
같은 원시 관찰 레코드는 남는다. 제외한 출처는 limitations에 기록한다.

`--dispatch-candidate-limit <n>`은 호출 대상 선언 하나에 적용되는 후보 수 상한이다
(기본 256, 범위 1–1,000,000). 상한을 넘으면 일부 후보만 확정적으로 보이지 않도록
그 후보 집합을 전부 생략하며, 발생 호출 수를 `dispatch-candidates-over-limit`로 보고한다.
일반 index/snapshot API의 기존 분석 정책은 변경하지 않는다.

기존 JSON은 호환성을 위해 `kind: override`, `origin: dispatchModel` 표현을 유지한다.
새 NDJSON/CSV는 이 후보를 `dispatchCandidate`로 구분하며, 같은 `(source,target,kind)`
간선을 합쳐 `origins`와 `originWeights`를 보존한다. `weight`는 입력에서 집계한 정적 관찰
횟수이며 실행 횟수가 아니다. 여러 출처의 합은 같은 소스 발생의 중복 관찰을 포함할 수
있으므로 실제 호출 수로 해석하지 않는다. `rawEdgeCount`와 내보낸 `edgeCount`는 다를 수 있다.

## 외부 대상과 위치

`--include-external-stubs`는 원시 관계가 가리키는 입력 밖 JVM identity의 최소 노드를
추가한다. 노드는 `external: true` 및 `externalStub` attribute를 가지며, 구현 본문이나
소스 위치를 추측하지 않는다. 외부 호출 원시 레코드도 보존한다. 이 노드를 프로젝트
구현이나 완전한 SDK 타입 색인으로 취급하지 않는다.

경로 후보는 package 디렉터리와 읽을 수 있는 소스 package 선언으로 좁힌다.
`ambiguous-source-paths`와 `unavailable-source-paths`는 각각 여러 후보와 후보 부재를
구분한다. 경로가 확정된 소스 안에서 타입 소유 사슬과 유일한 선언이 일치하면 타입 줄을
보강한다. 직접 선언한 Java field·Kotlin property·유일한 arity의 method·명시 constructor도
소유자·직접 member 범위를 대조해 빠진 줄을 채운다. 기존 compiler 줄은 보존하며 지역·생성·
accessor·file facade·같은 arity의 모호한 overload는 추측하지 않는다.
추가 줄은 `sourceDeclarationLocation` attribute로 구분한다. 현재 소스의 어휘적 관찰이며
classfile과 소스의 빌드 신선도를 증명하지 않는다. Kotlin primary-constructor property·생성자,
top-level member, Java 다중 field 선언 및 모든 언어 구문을 복원한다고 보장하지 않는다.

확정할 수 있는 직접 선언에는 기존 compiler `location`과 별도로 `sourceDeclaration`을 싣는다.
이 필드는 project 상대 `path`, raw bytes의 `sourceSha256`, 0부터 시작하는 `offsetUtf16`,
1부터 시작하는 `line`·`column`, `coordinateBasis: rawDecodedUtf16`, `origin: sourceHeader`를 포함한다.
좌표는 UTF-8을 decode한 원문 기준으로 BOM·CRLF·탭 code unit을 보존한다. column은 탭을 펼친
화면 열이 아니다. compiler CALL 좌표의 별도 기준과 혼합하지 않는다. 기존 `location.line`은
첫 실행 줄일 수 있으며 이를 선언 줄로 덮어쓰지 않는다. 같은 arity의 overload 등 모호한
header에는 이 필드가 없다. JVM 쪽 대응도 유일해야 하며 synthetic·extension member와
enum·중첩 타입의 생성자는 암시적 JVM 인자를 source 인자와 혼동하지 않도록 제외한다.
Kotlin method는 metadata에 기록된 원래 함수 이름이 JVM 이름과 일치해야 한다.
이 대조는 `@JvmName`의 annotation list·import alias·typealias 형태도 source 문자열 추측 없이
구분한다. 원래 이름을 확인할 metadata가 없으면 method 좌표를 보강하지 않는다.
hash는 읽은 source를 식별하며 컴파일 산출물과의 신선도를 보증하지 않는다.
기존의 줄 보강은 header 시작 줄이며, `sourceDeclaration.line`은 식별자 줄이다. 여러 줄 header에서
둘이 다르면 식별자의 선언 좌표는 `sourceDeclaration`을 사용한다. compiler 위치가 이미 상대
디렉터리를 포함하면 복원한 선언 경로도 정확히 같아야 한다. basename만 있을 때는 그 위치 자체로
디렉터리를 검증할 수 없으며, 원래 입력과의 신선도·인증을 이 근거만으로 판단하지 않는다.

JSON node와 live query의 subject·neighbor에 이 필드를 제공한다. `snapshot --include-paths`는
plain/compact 모두 별도 `graph.sourceDeclarations` 레코드로 보존하며 saved query는 source를
다시 읽지 않는다. NDJSON과 CSV의 `facts.ndjson`에는 `record: sourceDeclaration` 레코드가 들어간다.
Kotlin metadata의 함수 이름은 선택적 `graph.kotlinSourceNames` 레코드로 snapshot에 함께 보존한다.
CSV node의 기존 compiler 위치 컬럼과 compact node row는 유지한다. 이전 snapshot은 이 근거 없이 읽힌다.

어노테이션은 classfile에 있는 BINARY/CLASS 및 RUNTIME 보존 항목을 수집한다.
SOURCE 보존 항목은 classfile에 없어 복원하지 않는다. 대상이 입력 밖이면 일반 그래프에서
간선이 없어도 노드의 `annotations`에는 관찰한 identity가 남는다. 외부 stub 옵션은
외부 어노테이션 타입으로 가는 원시 간선도 보존한다. processor가 변형한 결과가 소스
어노테이션과 일대일로 같거나 DI 선택 관계를 전부 나타낸다고 보장하지 않는다.
