# MCP로 변경 전 영향 조사

kartograph는 로컬 snapshot을 읽는 MCP stdio 서버를 제공한다. 분석에 사용하는
그래프·보존·도달성·영향 탐색과 보고서는 CLI와 공유한다. 서버가 코드를 빌드하거나
수정하지 않으므로, 먼저 의도한 variant와 테스트 출력을 캡처한다.

```sh
kartograph mcp --graph-file build/reports/kartograph/jvm-snapshot.json \
  --project . --input-bindings build/kartograph/jvm-input-bindings.json
```

클라이언트의 MCP 서버 설정에는 다음과 같이 등록할 수 있다. `command`와 경로는
실제 설치·프로젝트 위치로 지정한다. 필요한 JDK는 해당 클라이언트 환경에서 제공한다.

```json
{
  "mcpServers": {
    "kartograph": {
      "command": "/path/to/kartograph",
      "args": [
        "mcp",
        "--graph-file", "/project/build/reports/kartograph/jvm-snapshot.json",
        "--project", "/project",
        "--input-bindings", "/project/build/kartograph/jvm-input-bindings.json"
      ]
    }
  }
}
```

`--base-graph`로 이전 snapshot을 함께 고정할 수 있다. 프로젝트·scope·외부 입력
binding은 시작할 때만 설정하며 질의 인자로 바꿀 수 없다. `--project`를 생략하면
신선도 결과는 `unverified`다. 로컬 binding 문서는 절대 경로를 담으므로 공개하지 않는다.

Snapshot 파일의 기본 읽기 한도는 각각 64 MiB다. 큰 그래프에는 시작 옵션
`--snapshot-max-mib 128`로 최대 128 MiB까지 명시적으로 허용할 수 있다(허용값 1..128).
현재·base 파일에 같은 한도를 적용하며, 도구 호출 중에는 한도를 바꿀 수 없다.
파일의 허용 크기는 JVM heap 사용량과 다르다. 파싱한 그래프와 질의 작업에 필요한
추가 메모리를 확보해야 한다. 도구 내용의 16 KiB 한도는 그대로 적용한다.

| 도구 | 용도 | 확인할 내용 |
|---|---|---|
| `query_symbol` | 이름 또는 정확한 JVM USR의 호출자·의존 대상·멤버 조사 | `document.status`, `limitations`, `result.truncated` |
| `impact` | 수정할 심볼·파일의 잠재적 영향 후보와 경로 조사 | `changed`, `affected`, `unresolved`, 경로의 origin·revision·누락, pagination |
| `freshness` | 고정한 현재 snapshot과 실제 설정된 입력 비교 | `matched`, `stale`, `unverified`와 이유 |

반환 wrapper의 `document`는 기존 CLI 보고서이고, `snapshot`은 서버가 읽은 내용의
해시·분석기 버전·scope·revision이다. JSON text와 structured content를 함께 제공한다.
`query_symbol`과 `impact`는 live 입력을 검사하지 않는다. 현재 빌드에 대한 결론을
내리기 전에 `freshness`를 호출한다. 이 검사는 현재 snapshot만 대상으로 하며,
`matched`도 런타임 완전성·삭제·테스트 생략을 승인하지 않는다.

서버는 시작 시 snapshot을 한 번 읽어 메모리에 고정한다. CI가 파일을 갱신해도
이미 실행 중인 서버의 그래프는 바뀌지 않는다. 새 snapshot을 사용하려면 서버를
재시작한다. 질의 중 파일이 교체되어 서로 다른 그래프의 신선도와 결과가 섞이는
일을 피하기 위한 경계다.

기본 페이지는 query 10개, impact 5개이며 최대 요청은 100개다. Impact의 기본 depth는
2, 경로 간선 예산은 100이다. 필요한 범위에 맞춰 스키마의 한도 안에서 늘릴 수 있다.
전역 요약은 `summaryLimit`(기본 5, 최대 100)으로 축별 항목 수를 따로 제한한다.
`summaryNavigation`은 원래·반환·생략한 항목 수와 생략된 후보 수를 기록한다.
`omittedCandidates`는 생략된 bucket count의 합이다. 같은 후보가 시점별 여러 파일·모듈 bucket에
속할 수 있으므로 고유 후보 수와 같다고 해석하지 않는다.
후보 총수·선택·영향·경로·한계는 유지하며, 요약이 생략되면 보고서는 `partial`이다.
요약 항목은 정렬된 앞부분이며 영향 후보의 offset으로 이동하지 않는다. 전체 요약은 CLI
`impact`에서 `--summary-limit` 없이 읽거나 더 많은 요약 항목을 요청해 확인한다.
파일 인자는 그래프 안의 상대 경로 선택자이며 임의 파일 읽기 기능이 아니다.
`files`와 `symbols`를 함께 지정하면 두 선택의 합집합이므로 넓은 파일을 추가하면
메서드 하나로 범위를 좁힌 효과가 사라질 수 있다.

질의는 정확한 USR(`method:p/C#m(I)V`)이나 qualified name(`p.C.m`)을 사용한다.
`C.m` 또는 `p.C.m(int)` 같은 표기가 `notFound`가 되면 wrapper의 `suggestions`에서
실제 후보 USR을 확인해 다시 질의한다. 괄호 안 타입을 해석해 overload를 선택하는 기능은
아니다. 원래 `notFound`·`ambiguous`는 유지하며 후보 수·반환 수·잘림을 알린다.
후보의 `presentIn`은 current/base 중 존재하는 시점을 표시한다. 두 시점 모두에 있으면 위치는
current 기준이며, base에만 있는 후보는 current 전용 `query_symbol`로 찾을 수 없다.
이 경우 base/current를 함께 조사하는 `impact`에서 해당 USR을 사용한다.
복구 후보가 크면 서버의 `suggestionLimit`을 10 → 3 → 0으로 줄인다. 마지막 단계도 요청별
전체 후보 수·반환 수 0·잘림을 남기며, `response.effective`에 실제 한도를 알린다.
같은 요청의 후보 탐색은 한 번만 수행한다. 원래 질의 문서 자체가 너무 크면 명시적으로 실패한다.

도구 text 내용은 **16 KiB**로 제한한다. 큰 결과에는 최대 세 번의 결정적인 시도로 페이지·
경로·요약 출력 예산을 줄인다. `response.requested`와 `response.effective`, `adapted`, `attempts`에
이를 명시하며, `document`는 실제 적용한 한도의 원래 CLI 보고서다. 선택·depth·방문 한도는
유지하고 원래 navigation·truncation·경로 생략 정보를 보존한다. 적용된 페이지를 기준으로
다음 offset을 조사한다. 최소 페이지에도 필수 내용을 담을 수 없으면 좁은 심볼을 요청하도록
오류를 반환한다. JSON이나 근거를 조용히 잘라서 보내지 않는다.

프로토콜은 **MCP 2025-11-25 stdio**다. `initialize`로 연결하며 도구 capability만
제공한다. 현재의 2026-07-28 프로토콜이나 HTTP 서버를 구현했다고 주장하지 않는다.
현대 클라이언트의 legacy fallback은 [공식 stdio 문서](https://modelcontextprotocol.io/specification/2026-07-28/basic/transports/stdio)에 설명돼 있으며,
도구 결과 형식은 [2025-11-25 tools 문서](https://modelcontextprotocol.io/specification/2025-11-25/server/tools)를 따른다.

입력 frame은 UTF-8 256 KiB, 전체 JSON-RPC 응답은 1 MiB로 제한한다.
앞의 16 KiB 내용 한도는 클라이언트가 큰 응답을 별도 파일로 바꾸는 문제를 줄이기 위한
더 작은 제한이며, text와 structured content를 함께 담는 전송 한도와 구분한다.
JSON 숫자 토큰은 128자, 지수의 절댓값은 10,000까지 허용한다. 이 한계는
큰 정수 변환에 과도한 CPU·메모리를 쓰기 전에 적용한다. 한 번에 도구 작업 하나를
처리하고 겹치는 요청은 거부한다. 취소된 요청의 응답은 보내지 않지만 이미 시작한
분석·해시 계산이 즉시 중단된다고 보장하지 않는다. stdin EOF로 종료한다.

설치된 Claude Code 2.1.270에서 실제 발견과 `query_symbol`·`freshness` 호출을
확인했다. 도구 연결 성공은 AI 생산성 향상의 근거와 별도로 취급한다. 자동 평가를
위해 Claude의 safe mode를 사용하면 명시한 MCP도 비활성화되므로 실제 연결 상태와
호출 이벤트를 확인해야 한다.

실제 fastjson2 core와 기존 테스트 6,202개 class의 compact snapshot은 88,695,953 bytes였다.
명시적 128 MiB 설정으로 저장·조회했고, 기본 64 MiB 읽기는 거부했다. 로컬 JDK 21에서 capture의
최대 RSS는 약 2.57 GB, 기본 heap의 saved query는 1.86 GB였다. `-Xmx1g`에서도 동일한 query
결과를 냈으며 RSS는 약 1.28 GB였다. 이는 한 공개 입력의 관측값이고 다른 그래프의 메모리
요구량을 보장하지 않는다. RSS에는 JVM heap 밖의 메모리도 포함한다.
