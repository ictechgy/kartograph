# 코드 변경 영향 점검

`impact`는 수정할 선언에 의존하는 심볼과 연결 경로를 조사한다. 반환값은 **잠재적 영향 후보**이며 동작 변화의 증명,
삭제 승인이나 테스트 생략 승인이 아니다. `dead`의 도달성/보존 판정과 다른 질의다.

## 수정 전에 조사

해당 variant를 먼저 빌드하고 기존 query와 같은 class·classpath·manifest/XML·keep 입력으로 capture한다.
반복 질의에서는 같은 파일을 재사용한다. 테스트의 영향도 조사하려면 관련 컴파일된 test root도 `--classes`로 포함한다.

```sh
kartograph snapshot --classes app-classes --classes test-classes --project . \
  --keep-rules keep.pro --include-paths --scope sample:debug > graph.json
kartograph impact 'method:sample/Repository#load()V' --graph-file graph.json
```

`--symbol`과 `--file`은 반복할 수 있다. 동명·overload는 명시적 JVM USR로 선택한다.
파일 선택은 그 파일에 연결한 모든 컴파일 선언을 포함하므로 메서드 선택보다 넓을 수 있다.
basename만 남은 입력은 같은 이름의 후보 전체를 포함하고 `source-file-candidates` 한계를 기록한다.
manifest/XML/keep 파일은 snapshot의 보존 근거 위치와 일치하는 선언을 선택한다. 설정 내용 전체를 해석하는 변경 분석은
아니며, 대응 사실 없는 build script·resource 파일 등은 `unmappedFile`로 남는다.

큰 결과는 관찰 후보 전체를 먼저 만든 다음 navigation 필터와 페이지를 적용한다. `--file`은 변경 선언을 고르는
입력이고 `--affected-file`은 그 결과의 후보 source path를 좁히는 필터이므로 서로 대체하지 않는다.
파일로 찾은 JVM 선언은 해당 ID가 존재하는 모든 입력 시점에서 조사한다. 파일을 이동한 경우 이전 경로만
선택하더라도 현재의 새 호출자를 포함하며, 두 시점의 탐색과 경로는 별도로 유지한다.

```sh
kartograph impact --symbol 'class:sample/Repository' --graph-file graph.json \
  --module feature --test-status production --relation direct \
  --sort file --offset 0 --limit 100

# 페이지 한도 없이 관찰된 후보를 모두 내보낸다. traversal/path budget은 여전히 보고된다.
kartograph impact 'class:sample/Repository' --graph-file graph.json --all
```

같은 필터 축의 값은 OR, 서로 다른 축은 AND다. 지원하는 필터는 `--module`, `--affected-file`, `--kind`,
`--test-status {test|production|unknown}`, `--relation {direct|structural|transitive|unknown}`,
`--path-status {complete|partial|unavailable}`다. 정렬은 `--sort usr|module|file|test|relation|path|path-status`이며,
동률은 항상 JVM USR로 결정한다. test 상태는 명시적으로 인식한 `src/test`, `src/androidTest`,
`src/testFixtures` 등의 source root와 `src/main` 등의 root만 분류하고, 나머지는 `unknown`으로 남긴다.
이 분류는 source 경로 관례이며 해당 선언이 실행 가능한 테스트라는 증거는 아니다.
module·file·test 필터는 각 축이 base 또는 current 사실에 맞으면 포함하며, 축별 일치 시점이 다를 수 있다.
같은 페이지 탐색에서는 입력·필터·정렬·예산을 고정한다. 결과 한도가 기본 경로 예산에도 영향을 주므로 한도를
바꿔 비교하려면 `--path-limit`을 명시한다.
`--kind`는 대표 선언(current에 있으면 current)의 종류를 선택한다. `--sort test`는 시점별 분류가 같으면
그 상태로, 다르면 `unknown`으로 정렬한다. 필터·bucket은 개별 시점의 source 사실을 사용한다.

대형 입력에는 `snapshot --compact`를 사용한다. v2는 문자열 사전과 node/call/edge 배열의 참조 인덱스로 반복 정보를 줄인다.
정점·간선·외부 호출·보존 근거를 버리지 않으며 기존 v1과 같은 검증을 거친다. 기본 출력은 v1이고 query/impact는 두 형식을 읽는다.
저장 파일의 64 MiB 입력 제한은 유지한다.

## CI에서 갱신하고 비교

base와 current checkout을 **같은 CLI 빌드·입력 범위·variant**로 빌드해 snapshot을 만든다.
각 capture에 `--revision <git rev-parse HEAD의 전체 값> --scope <프로젝트:variant>`를 전달한다.
라벨은 호출자의 선언이며 class/source 내용 지문이나 빌드 신선도 증명이 아니다. 분석 한계를 함께 확인한다.
현재 빌드와의 대응을 검증하려면 [compiler producer](BUILD-PROVENANCE.md)의 증거를 `--build-witness`로 붙이고
`verify-snapshot`을 실행한다. producer 입력·출력과 현재 바이트가 맞아야 `matched`가 되며, 증거 없는 snapshot은
`unverified`다. 동일 크기·시각으로 바뀐 파일도 내용 지문으로 비교한다.

```sh
# 각 checkout에서 빌드가 끝난 후 snapshot 생성. 실제 root와 추가 입력은 해당 프로젝트의 것을 사용한다.
kartograph snapshot --classes app-classes --classes test-classes --project . \
  --include-paths --revision "$COMMIT_SHA" --scope sample:debug > current.json

# current checkout에서 실행. base.json은 기준 commit에서 만든 snapshot이다.
python3 Scripts/check-impact.py --binary /path/to/kartograph --project . \
  --base origin/main --base-graph base.json --graph-file current.json > impact.json
```

helper는 commit 간 변경 파일을 NUL 구분으로 읽는다. rename의 이전·이후 경로, 삭제 경로도 포함한다.
snapshot 라벨과 실제 비교 commit, 두 scope, analyzer version이 다르면 실패한다. 동일 버전 문자열만으로 서로 다른
로컬 빌드를 식별할 수는 없으므로 CI가 사용하는 배포 artifact도 고정한다. tracked 미커밋 변경은 거부한다.
snapshot 파일 자체는 Git 밖의 CI artifact/cache로 관리하며 기존 미사용 baseline과 별개다.
helper 기본 모드는 보고용이다. 문서·설정 등 매핑되지 않은 파일도 `unresolved`에 남기되 유효한 보고서를 만들면 0을 반환한다.
확장자만으로 파일을 자동 무시하지 않는다. `--strict`는 선택/탐색이 partial/notFound일 때 종료 코드 1을 준다.
신선도도 `freshness`에 보고하며 `--strict`는 stale/unverified 증거에 1을 반환한다. 기준 snapshot의 파일도 검사하려면
`--base-project`를 제공한다. 외부 입력은 `--input` / `--base-input`으로 각각 연결하며 기준 checkout이 없으면 기준은 미검증이다.
큰 변경은 `--limit`을 높여 출력 잘림을 줄일 수 있다. 모든 런타임 경로의 완전성을 보장하는 모드는 아니다.

직접 `impact` CLI의 종료 코드는 정상 보고 0, 읽기/입력 문맥/도구 실패 2, 모호한 심볼·매핑할 수 없는 선택/사용 오류 64다.
영향 후보가 있다는 이유만으로 실패하지 않는다. 기존 `check-pr.py`는 계속 전체 그래프의 새 미사용 진단을 검사한다.

## JSON 계약

`format = kartograph-impact`, `version = 1`이다.

- `changed`: 선택한 선언과 존재 시점. 삭제된 선언도 base에서 유지한다.
- `affected`: 전체 관찰 후보에서 필터·정렬·offset·limit을 적용해 반환한 페이지. generated/private/baseline-suppressed 선언도 숨기지 않는다.
  전체 수는 `observedAffected`와 `summary.observed.candidates`에 있으며, `--all`은 페이지 limit을 없애지만 탐색·경로 예산을 없애지 않는다.
- `paths.nodes`: 후보 → 변경 선언 방향의 경로. `edges`는 `revision` 그래프의 실제 간선을 그대로 보존하며 `kind`와 `origin`을 포함한다.
  직접 변경한 method 계약의 하위 override도 포함한다. 그래프의 override는 상위→구현 방향이므로 이때는
  `traversal = overrideContract`로 역방향으로 읽음을 표시한다. 일반 간선은 `traversal = dependency`다.
  base/current 간선을 합쳐 실제로 없었던 경로를 만들지 않는다. 후보마다 각 시점의 결정적인 최단 경로 하나를 제공한다.
- `retention`: 시점별 보존 이유와 파일·줄. 보존 근거는 호출자 간선이 아니다.
- `observedAffected`: 탐색 한도 안에서 관측한 후보 수. 출력 한도보다 클 수 있다.
- `unresolved`: 모호성·부재·매핑되지 않은 파일. 빈 결과를 영향 없음으로 오해하지 않는다.
- `truncated.results/depth/budget`: 결과 페이지·깊이·방문/경로 예산의 잘림. 기본 깊이 100, 결과 500, 방문은 시점당 100,000개다.
  설명 경로에 포함하는 간선은 기본 100,000개로 제한하며 큰 출력 한도에서는 최대 500,000개까지 확장한다. 경로 예산을 넘은 후보도
  `affected`에서 제거하지 않고 `pathStatus=unavailable|partial`, `pathOmissions`, `budgets.pathOmissions`로 누락 사실을 기록한다.
- `limitations`: 각 시점의 runtime/freshness 한계와 잠재적 영향이라는 의미를 보존한다.

## 탐색 navigation과 source 사실

`summary.observed`는 페이지와 필터를 적용하기 전 관찰 후보 전체의 count다. `summary.filtered`는 같은 전체 후보에
필터만 적용한 count이며, 두 summary의 `byModule`, `byFile`, `byTestStatus`, `byRelation`, `byPathStatus`는
사람이 다음 질의를 고를 수 있는 설명용 그룹이다. `navigation.filtered`와 `navigation.returned`는
각각 필터 후 전체와 현재 페이지의 수이고, `hasNext`/`hasPrevious`와 `offset`/`limit`으로 페이지를 이어 간다.
따라서 첫 페이지의 `affected`만 세어 전체 후보 수나 영향 없음으로 해석하지 않는다.
offset이 0보다 큰 페이지는 앞의 후보를 포함하지 않으므로 마지막 페이지라도 `truncated.results=true`와
`status=partial`이다. 페이지의 다음 결과 유무는 `hasNext`로 판단한다.
base/current 사실이 서로 다른 module·file·test 상태를 가지면 한 후보가 여러 bucket에 포함될 수 있으므로 bucket 합계를
후보 수와 비교해 partition으로 해석하지 않는다.

각 선언에는 기존 대표 `module`/`location`과 함께 `facts`가 있다. `facts`는 base/current별 module, source 위치,
test 상태를 합치지 않으며 확인할 수 없는 값은 null 또는 `unknown`이다. `relation`은 한 간선 direct,
상속·override·annotation을 포함하는 structural, 그 밖의 다단계 transitive, 경로가 없어 확인하지 못한 unknown이다.
이 값들은 우선순위·위험 점수가 아니며 필터에서 제외된 후보가 안전하다는 뜻도 아니다.

`pathStatus=partial|unavailable`는 경로가 없다는 분석 결론이 아니라 예산 때문에 path witness를 일부 또는 전부
출력하지 못했다는 뜻일 수 있다. `pathOmissions`의 revision, reason, requiredEdges와 top-level `budgets`의 실제
방문·간선 사용량을 함께 검토한다. `--file` 선택의 unresolved 항목, base/current의 독립 `paths`, traversal 잘림과
snapshot freshness/runtime limitation은 페이지나 필터를 사용해도 보존된다.
누락 경로의 `edgeKinds`는 중복 없는 관계 종류다. 전체 경로 대신 거리와 이 유한한 집합을 보존해 경로 예산을
소진한 뒤 긴 경로를 계속 복제하지 않는다.

## 현재 한계와 평가

클래스 사용·상속·field 접근 등은 전부 잠재적 의존이다. 어떤 수정이 실제 동작을 바꾸는지는 이 관계만으로 결정되지 않는다.
인라인 상수의 지워진 사용처, 입력 밖의 호출자, 임의 runtime 값과 누락된 variant는 별도로 검토한다.
현재 갱신은 전체 snapshot capture다. 저장 그래프 재사용은 증분 인덱싱이 아니다.
평가는 [계획](IMPACT-PLAN.md)의 실제 변경 과제와 compiler/runtime 코퍼스에서 수행한다.
20,699개 후보의 탐색·출력량·시간 비교와 재현 명령은 [영향 탐색 평가](../experiments/impact-navigation/README.md)에 있다.
