#!/usr/bin/env python3
"""동결된 trial 결과를 사례·anchor 종류별로 집계한다. 통합 영향 탐지 점수는 만들지 않는다."""
import argparse
import collections
import hashlib
import json
from pathlib import Path
import statistics


def product_observations(path):
    pending = {}; requested = collections.Counter(); processed = collections.Counter()
    freshness = []; statuses = []
    for line in path.read_text().splitlines():
        event = json.loads(line); message = event['message']; channel = event['channel']
        if message.get('method') == 'tools/call':
            name = message.get('params', {}).get('name')
            if channel == 'from_client' and name in ['query_symbol', 'impact', 'freshness']:
                requested[name] += 1
            if channel == 'to_product':
                processed[name] += 1; pending[message['id']] = name
        if channel == 'from_product' and message.get('id') in pending:
            name = pending[message['id']]
            result = message.get('result', {})
            wrapped = result.get('structuredContent')
            if wrapped is None:
                try:
                    wrapped = json.loads(result['content'][0]['text'])
                except (ValueError, KeyError, IndexError, TypeError):
                    wrapped = {}
            document = wrapped.get('document', {})
            status = document.get('status')
            statuses.append(dict(tool=name, status=status, isError=result.get('isError')))
            if name == 'freshness':
                freshness.append(status)
    return dict(requested=dict(requested), processed=dict(processed), agentFreshness=freshness, responses=statuses)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--run', type=Path, required=True)
    parser.add_argument('--output', type=Path, required=True)
    args = parser.parse_args(); folder = args.run
    manifest = json.loads((folder / 'manifest.json').read_text())
    execution = json.loads((folder / 'execution.json').read_text())
    results = json.loads((folder / 'results.json').read_text())
    identities = lambda rows: [(r['case'], r['arm'], r['repeat']) for r in rows]
    if (execution['status'] != 'completed' or len(results) != 16 or
            identities(results) != identities(manifest['schedule'])):
        raise ValueError('complete frozen16 schedule required; partial runs must be reported separately')
    if execution['manifestSha256'] != hashlib.sha256((folder / 'manifest.json').read_bytes()).hexdigest():
        raise ValueError('manifest bytes changed')
    cases = []; trials = []
    for case in manifest['cases']:
        oracle = json.loads(Path(case['inventory']).read_text())
        primary = {d['id'] for d in oracle['declarations'] if d['sourceAddressable']} & set(oracle['targets'])
        suite = set(oracle['executedRuleSuiteAnchors']) & primary
        calls = (set(oracle['direct']) | set(oracle['indirect'])) & primary
        by_arm = {}
        for arm in ['source', 'mcp']:
            selected = [r for r in results if r['case'] == case['id'] and r['arm'] == arm]
            errors = collections.Counter(r['grading']['error'] for r in selected if not r['grading']['valid'])
            rows = []
            for r in selected:
                coverage = set(r['grading']['covered'])
                observations = product_observations(folder / r['directory'] / 'tools.jsonl')
                record = dict(case=r['case'], arm=arm, repeat=r['repeat'], seconds=r['seconds'],
                              costUsd=r['providerCostUsd'], valid=r['grading']['valid'], error=r['grading']['error'],
                              primaryCoverage=r['grading']['primaryRecall'],
                              suiteCoverage=len(coverage & suite) / len(suite) if suite else None,
                              callCoverage=len(coverage & calls) / len(calls) if calls else None,
                              totalToolRequests=len(r['toolUses']), product=observations,
                              ambiguous=r['grading']['ambiguousPredictions'], duplicates=r['grading']['duplicatePredictions'],
                              outsideOracle=len(r['grading']['outsideOracle']), infrastructureErrors=r['infrastructureErrors'])
                rows.append(record); trials.append(record)
            by_arm[arm] = dict(primaryCoverage=[r['primaryCoverage'] for r in rows],
                               suiteCoverage=[r['suiteCoverage'] for r in rows], callCoverage=[r['callCoverage'] for r in rows],
                               meanSeconds=statistics.mean(r['seconds'] for r in rows),
                               totalCostUsd=sum(r['costUsd'] or 0 for r in rows),
                               invalidResponses=sum(not r['valid'] for r in rows), invalidReasons=dict(errors))
        cases.append(dict(case=case['id'], module=case['module'], primaryOracle=len(primary),
                          suiteAnchors=len(suite), callAnchors=len(calls), arms=by_arm))
    report = dict(status='completed', trials=16, interpretation='Per-case known-anchor coverage; no aggregate impact detection or general productivity claim.',
                  cases=cases, trialDetails=trials,
                  modelStageSeconds=sum(r['seconds'] for r in results),
                  modelCostUsd=sum(r['providerCostUsd'] or 0 for r in results),
                  infrastructureErrors=[dict(case=r['case'],arm=r['arm'],repeat=r['repeat'],errors=r['infrastructureErrors'])
                                        for r in results if r['infrastructureErrors']],
                  manifestSha256=execution['manifestSha256'])
    with args.output.open('x') as output:
        output.write(json.dumps(report, ensure_ascii=False, indent=2) + '\n')
    print(json.dumps(dict(trials=16, modelCostUsd=report['modelCostUsd'], modelStageSeconds=report['modelStageSeconds'])))


if __name__ == '__main__':
    main()
