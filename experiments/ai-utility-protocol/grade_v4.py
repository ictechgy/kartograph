#!/usr/bin/env python3
"""v4 실행 증거를 사전 등록 채점 규약대로 집계한다.

recall/precision은 oracle(직접+간접) 대비로만 계산하고, 파일 수준 부분 일치(0.5)는 별도 카운트로
분리한다. 도구 사용 횟수와 freshness 상태는 기록만 하며 점수로 환산하지 않는다. anchor 점수는 없다.
"""
import argparse
import hashlib
import json
from pathlib import Path
import re
import statistics

SOURCE_TOOLS = {'source_list', 'source_read', 'source_search'}
PRODUCT_TOOLS = {'query_symbol', 'impact', 'freshness'}
BUDGET_MESSAGE = 'Shared tool budget exhausted'


def decode_answer(result):
    """v3 grade.py와 같은 응답 해석 규칙. 12개 초과 답변은 그대로 invalid로 남긴다."""
    if not result:
        return None, 'missing-result'
    if result.get('is_error'):
        return None, 'provider-error'
    text = result.get('result', '')
    if not isinstance(text, str):
        return None, 'invalid-json'
    text = text.strip()
    if text.startswith('```') and text.endswith('```'):
        text = '\n'.join(text.splitlines()[1:-1])
    try:
        value = json.loads(text)
    except (ValueError, TypeError):
        return None, 'invalid-json'
    if not isinstance(value, dict) or not isinstance(value.get('targets'), list) or not isinstance(value.get('unknowns'), list):
        return None, 'invalid-answer-shape'
    if len(value['targets']) > 12:
        return None, 'too-many-targets'
    return value, None


def target_credit(prediction, target):
    """grade.py의 file+symbol 일치 규칙을 kind 무차별로 적용한다.

    v1 코퍼스 전용 override overload 휴리스틱(Gson `value(double)`)은 v4에서 쓰지 않는다.
    반환값 1.0은 정확 일치, 0.5는 파일 수준 부분 일치, 0.0은 불일치다.
    """
    if not isinstance(prediction, dict):
        return 0.0
    file = re.sub(r':\d+(?:-\d+)?$', '', str(prediction.get('file', '')).removeprefix('./'))
    if file != target['file']:
        return 0.0
    raw_symbol = str(prediction.get('symbol', '')).strip()
    symbol = raw_symbol.replace('`', '')
    if symbol == target['id']:
        return 1.0
    if symbol.startswith('method:'):
        return 0.0
    name = target['name']
    # 메서드 자리와 끝을 확인해 긴 Kotlin 이름의 접두어나 설명 속 단어를 별도 메서드로 세지 않는다.
    quoted = [value for value in re.findall(r'(?:^|[.#/])\s*`([^`]+)`(?!\.)', raw_symbol)
              if any(character.isspace() for character in value)]
    if quoted:
        matched = any(re.search(r'(?:^|[.#])' + re.escape(name) + r'$', value) for value in quoted)
    else:
        matched = re.search(r'(?:^|[.#/])\s*' + re.escape(name) + r'(?:\s*\(.*\))?(?=$|\s*/)', symbol)
    if matched:
        return 1.0
    stem = Path(file).stem
    # 클래스 뒤의 범위 설명은 메서드 이름이 아니다. 다른 메서드 표기는 부분 점수로 바꾸지 않는다.
    class_symbol = re.split(r'\s+(?=[(\[])', symbol, maxsplit=1)[0]
    if not class_symbol or class_symbol == stem or class_symbol.endswith('.' + stem) or class_symbol.endswith('/' + stem):
        return 0.5
    return 0.0


def oracle_targets(case):
    """직접 + 간접(depth-2) 대상을 relation 표시와 함께 하나의 채점 집합으로 만든다."""
    return ([dict(target, relation='direct') for target in case['targets']] +
            [dict(target, relation='indirect') for target in case['indirectTargets']])


def valid_predictions(answer):
    """precision 분모가 되는 유효 target과, 형식이 깨진 항목 수를 나눈다."""
    if not answer:
        return [], 0
    valid, malformed = [], 0
    for item in answer['targets']:
        if (isinstance(item, dict) and isinstance(item.get('file'), str) and item['file'].strip()
                and isinstance(item.get('symbol'), str) and item['symbol'].strip()):
            valid.append(item)
        else:
            malformed += 1
    return valid, malformed


def event_summary(path):
    """stream-json에서 최종 result, 요청된 tool_use, 관측한 tool_result 바이트를 모은다."""
    events, malformed = [], 0
    for line in path.read_text().splitlines():
        try:
            events.append(json.loads(line))
        except ValueError:
            malformed += 1
    results = [value for value in events if value.get('type') == 'result']
    uses = [content for event in events if event.get('type') == 'assistant'
            for content in event.get('message', {}).get('content', []) if content.get('type') == 'tool_use']
    bytes_seen = 0
    for event in events:
        if event.get('type') == 'user':
            for content in event.get('message', {}).get('content', []):
                if content.get('type') == 'tool_result':
                    bytes_seen += len(json.dumps(content.get('content'), ensure_ascii=False).encode('utf-8'))
    return results[-1] if results else None, uses, bytes_seen, malformed


def short_name(name):
    return str(name).rsplit('__', 1)[-1]


def proxy_summary(path):
    """proxy trace에서 실제로 처리된 호출과 freshness 응답 상태를 읽는다."""
    summary = {'traceAvailable': False, 'processedSource': 0, 'processedProduct': 0, 'budgetRejected': 0,
               'processedByTool': {}, 'freshnessCalls': 0, 'freshnessStatuses': [], 'freshnessReasons': [],
               'productTransportErrors': 0}
    if not path.is_file():
        return summary
    summary['traceAvailable'] = True
    pending = {}
    for line in path.read_text(encoding='utf-8', errors='replace').splitlines():
        try:
            event = json.loads(line)
        except ValueError:
            continue
        channel, message = event.get('channel'), event.get('message')
        if not isinstance(message, dict):
            continue
        if channel == 'from_client' and message.get('method') == 'tools/call':
            pending[message.get('id')] = short_name(message.get('params', {}).get('name'))
        elif channel == 'proxy_error':
            summary['productTransportErrors'] += 1
        elif channel == 'to_client' and message.get('id') in pending:
            name = pending.pop(message.get('id'))
            result = message.get('result')
            text = ''
            if isinstance(result, dict):
                for block in result.get('content', []) or []:
                    if isinstance(block, dict) and isinstance(block.get('text'), str):
                        text += block['text']
            if isinstance(result, dict) and result.get('isError') and BUDGET_MESSAGE in text:
                summary['budgetRejected'] += 1
                continue
            summary['processedByTool'][name] = summary['processedByTool'].get(name, 0) + 1
            if name in PRODUCT_TOOLS:
                summary['processedProduct'] += 1
            elif name in SOURCE_TOOLS:
                summary['processedSource'] += 1
            if name == 'freshness':
                summary['freshnessCalls'] += 1
                document = {}
                if isinstance(result, dict) and isinstance(result.get('structuredContent'), dict):
                    document = result['structuredContent'].get('document') or {}
                if not document:
                    try:
                        document = json.loads(text).get('document', {})
                    except (ValueError, AttributeError):
                        document = {}
                summary['freshnessStatuses'].append(document.get('status') if isinstance(document, dict) else None)
                reasons = document.get('reasons') if isinstance(document, dict) else None
                if isinstance(reasons, list):
                    summary['freshnessReasons'].append(reasons)
    return summary


def grade_trial(trial, oracle_case, manifest_case):
    directory = Path(trial['directory'])
    result, uses, bytes_seen, malformed_stream = event_summary(directory / 'stream.jsonl')
    answer, invalid_reason = decode_answer(result)
    targets = oracle_targets(oracle_case)
    predictions, malformed_predictions = valid_predictions(answer)
    credits = [max((target_credit(item, target) for item in predictions), default=0.0) for target in targets]
    prediction_credits = [max((target_credit(item, target) for target in targets), default=0.0) for item in predictions]
    matched_targets = sum(credit == 1.0 for credit in credits)
    partial_targets = sum(credit == 0.5 for credit in credits)
    matched_predictions = sum(credit == 1.0 for credit in prediction_credits)
    partial_predictions = sum(credit == 0.5 for credit in prediction_credits)
    proxy = proxy_summary(directory / 'tools.jsonl')
    requested_source = sum(short_name(use.get('name')) in SOURCE_TOOLS for use in uses)
    requested_product = sum(short_name(use.get('name')) in PRODUCT_TOOLS for use in uses)
    requested_by_tool = {}
    for use in uses:
        name = short_name(use.get('name'))
        requested_by_tool[name] = requested_by_tool.get(name, 0) + 1
    size = len(targets)
    return {
        'label': trial.get('label'), 'case': trial['case'], 'arm': trial['arm'], 'repeat': trial['repeat'],
        'transitiveCase': bool(manifest_case.get('transitive')),
        'answerValid': answer is not None, 'answerInvalidReason': invalid_reason,
        'oracleSize': size, 'oracleDirect': len(oracle_case['targets']),
        'oracleIndirect': len(oracle_case['indirectTargets']),
        'recallCeiling': min(1.0, 12 / size),
        'recall': matched_targets / size,
        'precision': (matched_predictions / len(predictions)) if predictions else None,
        'matchedOracleTargets': matched_targets, 'matchedPredictions': matched_predictions,
        'partialOracleTargets': partial_targets, 'partialPredictions': partial_predictions,
        'listedTargets': len(answer['targets']) if answer else 0,
        'validPredictions': len(predictions), 'malformedPredictions': malformed_predictions,
        'unmatchedPredictions': len(predictions) - matched_predictions - partial_predictions,
        'perOracleTarget': [{'id': target['id'], 'relation': target['relation'], 'kind': target['kind'],
                             'credit': credit} for target, credit in zip(targets, credits)],
        'toolCalls': {'requestedTotal': len(uses), 'requestedSource': requested_source,
                      'requestedProduct': requested_product, 'requestedByTool': requested_by_tool,
                      'processedSource': proxy['processedSource'], 'processedProduct': proxy['processedProduct'],
                      'processedByTool': proxy['processedByTool'], 'budgetRejected': proxy['budgetRejected'],
                      'traceAvailable': proxy['traceAvailable'],
                      'productTransportErrors': proxy['productTransportErrors']},
        'freshness': {'observable': trial['arm'] == 'mcp', 'calls': proxy['freshnessCalls'],
                      'statuses': proxy['freshnessStatuses'], 'reasons': proxy['freshnessReasons']},
        'observedToolContentBytes': bytes_seen, 'malformedStreamLines': malformed_stream,
        'seconds': trial['seconds'], 'costUsd': result.get('total_cost_usd') if result else None,
        'usage': result.get('usage') if result else None,
        'exit': trial.get('exit'), 'timedOut': trial.get('timedOut'),
        'infrastructureErrors': trial.get('infrastructureErrors'),
        'toolBudgetExceeded': trial.get('toolBudgetExceeded'),
        'evidenceHashes': trial.get('evidenceHashes'),
    }


def mean(values):
    values = [value for value in values if value is not None]
    return statistics.mean(values) if values else None


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument('--run', type=Path, required=True, help='run_v4.py가 만든 증거 디렉터리')
    parser.add_argument('--oracle', type=Path, required=True)
    parser.add_argument('--output', type=Path, required=True)
    args = parser.parse_args()
    if args.output.exists():
        raise SystemExit('error: use a new grading output directory')
    args.output.mkdir(parents=True)
    (args.output / 'blind').mkdir()
    manifest = json.loads((args.run / 'manifest.json').read_text())
    oracle_digest = hashlib.sha256(args.oracle.read_bytes()).hexdigest()
    if oracle_digest != manifest['oracleSha256']:
        raise SystemExit('error: oracle bytes differ from the prepared manifest')
    oracles = {case['id']: case for case in json.loads(args.oracle.read_text())['cases']}
    manifest_cases = {case['case']: case for case in manifest['cohort']}
    progress = json.loads((args.run / 'progress.json').read_text())
    rows, blind_key = [], {}
    for trial in progress:
        row = grade_trial(trial, oracles[trial['case']], manifest_cases[trial['case']])
        rows.append(row)
        blind = hashlib.sha256((trial['case'] + str(trial['repeat']) + trial['arm']).encode()).hexdigest()[:12]
        blind_key[blind] = {'case': trial['case'], 'repeat': trial['repeat'], 'arm': trial['arm'],
                            'directory': trial['directory']}
        answer = decode_answer(event_summary(Path(trial['directory']) / 'stream.jsonl')[0])[0]
        (args.output / 'blind' / (blind + '.json')).write_text(
            json.dumps({'case': trial['case'], 'answer': answer}, indent=2) + '\n')
    summary = []
    for case in manifest_cases:
        for arm in ('source', 'mcp'):
            chosen = [row for row in rows if row['case'] == case and row['arm'] == arm]
            if not chosen:
                continue
            summary.append({
                'case': case, 'arm': arm, 'trials': len(chosen),
                'oracleSize': chosen[0]['oracleSize'], 'recallCeiling': chosen[0]['recallCeiling'],
                'transitiveCase': chosen[0]['transitiveCase'],
                'recallValues': [row['recall'] for row in chosen], 'recallMean': mean(row['recall'] for row in chosen),
                'precisionValues': [row['precision'] for row in chosen],
                'precisionMean': mean(row['precision'] for row in chosen),
                'partialOracleTargets': [row['partialOracleTargets'] for row in chosen],
                'validAnswers': sum(row['answerValid'] for row in chosen),
                'invalidReasons': [row['answerInvalidReason'] for row in chosen if row['answerInvalidReason']],
                'productCallsRequested': sum(row['toolCalls']['requestedProduct'] for row in chosen),
                'productCallsProcessed': sum(row['toolCalls']['processedProduct'] for row in chosen),
                'sourceCallsRequested': sum(row['toolCalls']['requestedSource'] for row in chosen),
                'sourceCallsProcessed': sum(row['toolCalls']['processedSource'] for row in chosen),
                'freshnessCalls': sum(row['freshness']['calls'] for row in chosen),
                'freshnessStatuses': [status for row in chosen for status in row['freshness']['statuses']],
                'secondsMean': mean(row['seconds'] for row in chosen),
                'costUsdTotal': sum(row['costUsd'] or 0 for row in chosen),
            })
    cohort_rows = []
    for arm in ('source', 'mcp'):
        chosen = [row for row in rows if row['arm'] == arm]
        if not chosen:
            continue
        excluded = [row for row in chosen if row['case'] != 'fasterxml__jackson-core-1016']
        cohort_rows.append({
            'arm': arm, 'trials': len(chosen),
            'recallMeanAllCases': mean(row['recall'] for row in chosen),
            'precisionMeanAllCases': mean(row['precision'] for row in chosen),
            'recallMeanExcludingJackson': mean(row['recall'] for row in excluded),
            'precisionMeanExcludingJackson': mean(row['precision'] for row in excluded),
            'note': 'fasterxml__jackson-core-1016 has a structural recall ceiling of 0.375 and is also reported '
                    'on its own row. The excluding-jackson mean is descriptive only, not a substitute cohort mean.',
        })
    document = {
        'format': 'kartograph-ai-utility-v4-results', 'version': 1, 'mode': manifest['mode'],
        'manifestSha256': hashlib.sha256((args.run / 'manifest.json').read_bytes()).hexdigest(),
        'oracleSha256': oracle_digest, 'scoringRulesSha256': manifest['scoringRulesSha256'],
        'scoringRules': manifest['scoringRules'],
        'preExecutionChanges': manifest.get('preExecutionChanges', []),
        'plannedTrials': len(manifest['schedule']),
        'executedTrials': len(rows), 'trials': rows, 'byCaseAndArm': summary, 'byArm': cohort_rows,
        'manualReviewPending': True,
        'warning': 'Recall/precision are measured against a frozen review anchor built from javap references, '
                   'existing executed tests and original override declarations, not complete runtime impact. '
                   'Tool-call counts are recorded, never scored, and never read as a causal effect.',
    }
    (args.output / 'results-v4.json').write_text(json.dumps(document, indent=2) + '\n')
    (args.output / 'blind-key.json').write_text(json.dumps(blind_key, indent=2) + '\n')
    print(json.dumps({'byCaseAndArm': summary, 'byArm': cohort_rows}, indent=2))


if __name__ == '__main__':
    main()
