# v6 — 구조화 출력 검증과 새 source/MCP 비교

본 실행 전에 [프로토콜](PROTOCOL.md), [선택·준비 기록](INTAKE.md), [코호트](cohort.json),
[자격 결과](qualification.json), [source/USR 대칭 채점](oracle-parity.json), [외부 검토 처분](review-disposition.json)을 고정했다.
기존 v5 원문·채점은 보존한다. 이 디렉터리는 별도의 새 실험이다.

최종 표본은 detekt6443·6352와 ktlint2617·2554다. 기존 테스트 79개가 통과했고 네 snapshot이 matched다.
독립 oracle은 27·9·14·31개이며, 압축된 원본은 [oracles](oracles/)에 있다. 테스트 모음 anchor79개와
직접 호출 anchor2개를 구분하며 통합 영향 탐지·일반 생산성 점수로 해석하지 않는다.

실행기는 CLI의 `--json-schema`와 성공 `structured_output`을 검증한다. 누락·schema 위반·예산 소진·인프라
실패를 원문과 함께 남기며 JSON 수리·raw text fallback·선택적 재시작을 하지 않는다. 준비·smoke 비용은 본16회와 별도다.

검증: `python3 -m unittest discover -s experiments/ai-utility-v6 -p 'test*.py' -v`.
실제 실행은 로컬 경로를 담은 config로 `run.py --config <config> --output <new-directory>`를 준비한 뒤,
`run.py --execute --output <prepared-directory>`를 사용한다. `report.py`는 완료된16회와 모든 근거 해시를 확인한다.
동결된 실행을 다시 시작하지 않는다. 새로운 반복은 새 프로토콜·디렉터리로 기록한다.
