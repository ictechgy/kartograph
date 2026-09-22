#!/usr/bin/env python3
"""원문·채점·oracle 해시를 확인하고 사례별 coverage와 구조화 응답 실패를 집계한다."""
import argparse
import collections
import hashlib
import json
import math
from pathlib import Path
import statistics


def digest(path):
    return hashlib.sha256(path.read_bytes()).hexdigest()


def costs(values):
    """누락 비용을 0으로 바꾸지 않고 알려진 CLI 추정값만 별도로 합산한다."""
    known=[value for value in values if value is not None]
    if any(isinstance(value,bool) or not isinstance(value,(int,float)) or not math.isfinite(value) or value<0 for value in known):
        raise ValueError('invalid provider cost')
    return sum(known) if len(known)==len(values) else None,sum(known),len(values)-len(known)


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
    if execution['manifestSha256'] != digest(folder / 'manifest.json'):
        raise ValueError('manifest bytes changed')
    if execution.get('resultsSha256') != digest(folder / 'results.json'):
        raise ValueError('completed result bytes changed')
    expected_files={'source-manifest.json','product-command.json','mcp-config.json','stream.jsonl','stderr','tools.jsonl','answer.json'}
    for record in results:
        directory=record['directory']
        if not isinstance(directory,str) or Path(directory).name!=directory or directory in {'.','..'} or '\\' in directory:
            raise ValueError('invalid trial directory')
        evidence=record.get('evidenceSha256',{})
        available=record.get('proxyTraceAvailable')
        if available is not True and available is not False:
            raise ValueError('missing proxy trace status')
        if not available and (record['grading']['valid'] or 'proxy-trace-unavailable' not in record['infrastructureErrors']):
            raise ValueError('missing proxy trace was not recorded as failure')
        required=expected_files if available else expected_files-{'tools.jsonl'}
        if set(evidence)!=required or any(digest(folder/directory/name)!=value for name,value in evidence.items()):
            raise ValueError('trial evidence changed or incomplete')
    cases = []; trials = []
    for case in manifest['cases']:
        if digest(Path(case['inventory'])) != case['inventorySha256']:
            raise ValueError('frozen oracle changed')
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
                observations = product_observations(folder / r['directory'] / 'tools.jsonl') if r['proxyTraceAvailable'] else dict(
                    status='unavailable',requested=None,processed=None,agentFreshness=[],responses=[])
                record = dict(case=r['case'], arm=arm, repeat=r['repeat'], seconds=r['seconds'],
                              costUsd=r['providerCostUsd'], valid=r['grading']['valid'], error=r['grading']['error'],
                              primaryCoverage=r['grading']['primaryRecall'],
                              suiteCoverage=len(coverage & suite) / len(suite) if suite else None,
                              callCoverage=len(coverage & calls) / len(calls) if calls else None,
                              totalToolRequests=len(r['toolUses']), product=observations,
                              informationToolRequests=len(r['informationToolUses']),
                              outputToolRequests=len(r['outputToolUses']),
                              providerSubtype=r['providerSubtype'],structuredOutputError=r['structuredOutputError'],
                              ambiguous=r['grading']['ambiguousPredictions'], duplicates=r['grading']['duplicatePredictions'],
                              outsideOracle=len(r['grading']['outsideOracle']), infrastructureErrors=r['infrastructureErrors'])
                rows.append(record); trials.append(record)
            complete_cost,known_cost,missing_cost=costs([r['costUsd'] for r in rows])
            by_arm[arm] = dict(primaryCoverage=[r['primaryCoverage'] for r in rows],
                               suiteCoverage=[r['suiteCoverage'] for r in rows], callCoverage=[r['callCoverage'] for r in rows],
                               meanSeconds=statistics.mean(r['seconds'] for r in rows),
                               totalCostUsd=complete_cost,knownCostUsd=known_cost,missingCostTrials=missing_cost,
                               invalidResponses=sum(not r['valid'] for r in rows), invalidReasons=dict(errors))
        cases.append(dict(case=case['id'], module=case['module'], primaryOracle=len(primary),
                          suiteAnchors=len(suite), callAnchors=len(calls), arms=by_arm))
    complete_cost,known_cost,missing_cost=costs([r['providerCostUsd'] for r in results])
    report = dict(status='completed', trials=16, interpretation='Per-case known-anchor coverage; no aggregate impact detection or general productivity claim. CLI costs are client estimates.',
                  cases=cases, trialDetails=trials,
                  modelStageSeconds=sum(r['seconds'] for r in results),
                  modelCostUsd=complete_cost,knownModelCostUsd=known_cost,missingCostTrials=missing_cost,
                  responseValidityByArm={arm:dict(total=8,valid=sum(r['grading']['valid'] for r in results if r['arm']==arm),
                      failures=dict(collections.Counter(r['grading']['error'] for r in results if r['arm']==arm and not r['grading']['valid']))) for arm in ['source','mcp']},
                  infrastructureErrors=[dict(case=r['case'],arm=r['arm'],repeat=r['repeat'],errors=r['infrastructureErrors'])
                                        for r in results if r['infrastructureErrors']],
                  manifestSha256=execution['manifestSha256'],resultsSha256=execution['resultsSha256'])
    with args.output.open('x') as output:
        output.write(json.dumps(report, ensure_ascii=False, indent=2) + '\n')
    print(json.dumps(dict(trials=16, modelCostUsd=report['modelCostUsd'], modelStageSeconds=report['modelStageSeconds'])))


if __name__ == '__main__':
    main()
