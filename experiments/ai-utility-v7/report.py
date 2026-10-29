#!/usr/bin/env python3
"""원문을 재채점하고 호출·동작·음성 대조를 별도 지표로 보고한다."""
import argparse
import collections
import importlib.util
import json
from pathlib import Path
import statistics

HERE = Path(__file__).resolve().parent


def load(name, path):
    spec = importlib.util.spec_from_file_location(name, path)
    module = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(module)
    return module


study = load('v7_report_study', HERE / 'run.py')
helpers = load('v6_report_helpers', HERE.parent / 'ai-utility-v6/report.py')


def render(folder):
    """완료된 전체 schedule과 raw 해시를 확인하며 원래 답변을 수리하지 않는다."""
    manifest = json.loads((folder / 'manifest.json').read_text())
    execution = json.loads((folder / 'execution.json').read_text())
    records = json.loads((folder / 'results.json').read_text())
    expected = [(c['id'], arm, repeat) for c in manifest['cases'] for repeat in range(2)
                for arm in (['source', 'mcp'] if repeat == 0 else ['mcp', 'source'])]
    identities = lambda rows: [(r['case'], r['arm'], r['repeat']) for r in rows]
    if (manifest['format'] != 'kartograph-ai-utility-v7-manifest' or len(manifest['cases']) != 4 or
            execution['status'] != 'completed' or len(records) != 16 or
            identities(records) != expected or identities(manifest['schedule']) != expected):
        raise ValueError('complete frozen 16 schedule required')
    if execution['manifestSha256'] != helpers.digest(folder / 'manifest.json'):
        raise ValueError('manifest changed')
    if execution['resultsSha256'] != helpers.digest(folder / 'results.json'):
        raise ValueError('results changed')
    if not manifest.get('toolHashes') or any(not Path(path).is_file() or helpers.digest(Path(path)) != value
            for path, value in manifest['toolHashes'].items()):
        raise ValueError('frozen tooling changed before regrading')
    inventories = {}
    for case in manifest['cases']:
        if helpers.digest(Path(case['inventory'])) != case['inventorySha256']:
            raise ValueError('oracle changed')
        inventories[case['id']] = study.Inventory(json.loads(Path(case['inventory']).read_text()))
    rows = []
    for record in records:
        name = record['directory']
        if not isinstance(name, str) or Path(name).name != name or name in {'.', '..'} or '\\' in name:
            raise ValueError('invalid trial directory')
        directory = folder / name
        trace = record.get('proxyTraceAvailable')
        files = {'source-manifest.json', 'product-command.json', 'mcp-config.json', 'stream.jsonl', 'stderr', 'answer.json'}
        if trace is True:
            files.add('tools.jsonl')
        elif trace is not False or record['grading']['valid'] or 'proxy-trace-unavailable' not in record['infrastructureErrors']:
            raise ValueError('missing trace was not recorded as failure')
        if set(record['evidenceSha256']) != files or any(
                helpers.digest(directory / path) != value for path, value in record['evidenceSha256'].items()):
            raise ValueError('raw evidence changed')
        _, provider, uses, _, _ = study.transport.parse_events(directory / 'stream.jsonl')
        document, error = study.validate_result(provider)
        answer = json.loads((directory / 'answer.json').read_text())
        original = provider.get('structured_output') if provider else None
        if (answer['structuredOutput'] != original or
                answer['text'] != (provider.get('result', '') if provider else '')):
            raise ValueError('answer differs from original provider result')
        if error or record['infrastructureErrors']:
            scored = study.grade('', inventories[record['case']])
            scored['error'] = error or 'infrastructure-error'
        else:
            scored = study.grade(json.dumps(document, ensure_ascii=False), inventories[record['case']])
        if scored != record['grading'] or scored != answer['grading']:
            raise ValueError('original answer regrading differs')
        if record['providerCostUsd'] != (provider.get('total_cost_usd') if provider else None):
            raise ValueError('reported cost differs from provider record')
        if record['toolUses'] != [dict(name=u.get('name'), input=u.get('input')) for u in uses]:
            raise ValueError('tool usage differs from original stream')
        counts = collections.Counter(row['status'] for row in scored['predictions'])
        observations = helpers.product_observations(directory / 'tools.jsonl') if trace else dict(status='unavailable')
        rows.append(dict(case=record['case'], arm=record['arm'], repeat=record['repeat'],
            valid=scored['valid'], error=scored['error'], callCoverage=scored['callRecall'],
            behaviorCoverage=scored['behaviorRecall'], negativeControlsSelected=len(scored['negativeControlPredictions']),
            observedTestPrecision=scored['observedTestPrecision'], kindMismatches=scored['kindMismatches'],
            productionOutsideOracle=len(scored['productionPredictionsOutsideOracle']),
            testOutsideControls=len(scored['testPredictionsOutsideControls']), predictionStatusCounts=dict(counts),
            predictionCount=scored['predictionCount'], seconds=record['seconds'], costUsd=record['providerCostUsd'],
            informationToolRequests=len(record['informationToolUses']), outputToolRequests=len(record['outputToolUses']),
            product=observations, infrastructureErrors=record['infrastructureErrors']))
    cases = []
    for case in manifest['cases']:
        inv = inventories[case['id']]
        arms = {}
        for arm in ['source', 'mcp']:
            selected = [row for row in rows if row['case'] == case['id'] and row['arm'] == arm]
            cost, known, missing = helpers.costs([row['costUsd'] for row in selected])
            arms[arm] = dict(callCoverage=[row['callCoverage'] for row in selected],
                behaviorCoverage=[row['behaviorCoverage'] for row in selected],
                negativeControlsSelected=[row['negativeControlsSelected'] for row in selected],
                observedTestPrecision=[row['observedTestPrecision'] for row in selected],
                meanSeconds=statistics.mean(row['seconds'] for row in selected), totalCostUsd=cost,
                knownCostUsd=known, missingCostTrials=missing, invalidResponses=sum(not row['valid'] for row in selected))
        cases.append(dict(case=case['id'], staticCallers=len(inv.call_oracle), behaviorPositive=len(inv.behavior_positive),
                          behaviorNegative=len(inv.behavior_negative), arms=arms))
    cost, known, missing = helpers.costs([row['costUsd'] for row in rows])
    return dict(status='completed', trials=16, cases=cases, trialDetails=rows,
        interpretation='Synthetic fixed-patch controls; caller recall and assertion changes are separate. No general productivity or reasoning-quality claim.',
        modelStageSeconds=sum(row['seconds'] for row in rows), modelCostUsd=cost, knownModelCostUsd=known,
        missingCostTrials=missing, responseValidityByArm={arm: dict(total=8,
            valid=sum(row['valid'] for row in rows if row['arm'] == arm)) for arm in ['source', 'mcp']},
        infrastructureErrors=[dict(case=row['case'], arm=row['arm'], repeat=row['repeat'], errors=row['infrastructureErrors'])
                              for row in rows if row['infrastructureErrors']],
        manifestSha256=execution['manifestSha256'], resultsSha256=execution['resultsSha256'])


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--run', type=Path, required=True)
    parser.add_argument('--output', type=Path, required=True)
    args = parser.parse_args()
    report = render(args.run)
    with args.output.open('x') as output:
        output.write(json.dumps(report, ensure_ascii=False, indent=2) + '\n')
    print(json.dumps({key: report[key] for key in ['trials', 'modelStageSeconds', 'modelCostUsd']}))


if __name__ == '__main__': main()
