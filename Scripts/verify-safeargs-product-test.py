#!/usr/bin/env python3
"""Require actual cold source/class attribution and installed CLI consumption in the native fixture."""
import hashlib
import importlib.util
import json
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]


def verify(evidence):
    spec = importlib.util.spec_from_file_location('native_observation', ROOT / 'Scripts/verify-safeargs-native-test.py')
    module = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(module)
    captured = module.inventory(evidence)
    required = {'product/receipt.json', 'product/warm.json', 'product/warm-extra.txt',
                'product/Debug-warm-snapshot.json', 'product/query-warm-command.json',
                'product/live-warm-graph.json', 'product/live-warm-dead.json',
                'product/live-extra-graph.json', 'product/live-disabled.txt'}
    required.update(f'product/live-{variant}-{kind}.json' for variant in ['Debug', 'Release'] for kind in ['graph', 'dead'])
    required.update(f'product/{variant}-cold-snapshot.json' for variant in ['Debug', 'Release'])
    required.update(f'product/public-{variant}.json' for variant in ['Debug', 'Release'])
    required.update(f'product/{prefix}-{variant}-command.json'
                    for variant in ['Debug', 'Release'] for prefix in ['public', 'query'])
    if not required.issubset(captured):
        raise ValueError('product evidence is outside the capture inventory')
    receipt = json.loads((evidence / 'product/receipt.json').read_text(encoding='utf-8'))
    rows = receipt['variants']
    if len(rows) != 2 or {row['variant'] for row in rows} != {'Debug', 'Release'}:
        raise ValueError('both actual cold variants are required')
    for row in rows:
        variant = row['variant']
        if row['sources'] != 3 or row['publicVersion'] != '0.25.0' or row['publicGenerated'] is not False or row['queryExit'] != 0:
            raise ValueError('actual public/current CLI comparison is missing')
        if any(row[key] is not True for key in ['coldGenerated', 'manualUnchanged', 'callsUnchanged', 'retentionUnchanged']):
            raise ValueError('classification or negative controls failed')
        if row.get('liveGraph') is not True or row.get('liveDead') is not True:
            raise ValueError('live graph/dead classification is missing')
        verify_live(evidence, f'live-{variant}', 'current-cold-success/SUCCEEDED')
        raw = (evidence / f'product/{variant}-cold-snapshot.json').read_bytes()
        if hashlib.sha256(raw).hexdigest() != row['snapshotSha256']:
            raise ValueError('cold snapshot receipt mismatch')
        snapshot = json.loads(raw)
        if snapshot['version'] != 4 or 'generationEvidence' not in snapshot:
            raise ValueError('mandatory generation snapshot contract is missing')
        public = json.loads((evidence / f'product/public-{variant}.json').read_text(encoding='utf-8'))
        if public['tool']['version'] != '0.25.0':
            raise ValueError('wrong public baseline tool version')
        for prefix in ['public', 'query']:
            command = json.loads((evidence / f'product/{prefix}-{variant}-command.json').read_text(encoding='utf-8'))
            if command['exit'] != 0 or command['timeout'] or not command['jars']:
                raise ValueError('CLI process failed or lacks installed byte identities')
    warm = json.loads((evidence / 'product/warm.json').read_text(encoding='utf-8'))
    if (warm['originBasis'] != 'historical-byte-equivalence' or warm['currentGenerationStatus'] != 'UP_TO_DATE'
            or warm['currentAttempt'] is not None or warm['sameOrigin'] is not True or warm['newInvocation'] is not True or warm['queryExit'] != 0):
        raise ValueError('warm historical origin/current status is not separated')
    raw = (evidence / 'product/Debug-warm-snapshot.json').read_bytes()
    if hashlib.sha256(raw).hexdigest() != warm['snapshotSha256']:
        raise ValueError('warm snapshot receipt mismatch')
    command = json.loads((evidence / 'product/query-warm-command.json').read_text(encoding='utf-8'))
    if command['exit'] != 0 or command['timeout'] or not command['jars']:
        raise ValueError('warm CLI process failed')
    if warm.get('liveGraph') is not True or warm.get('liveDead') is not True:
        raise ValueError('warm live graph/dead classification is missing')
    verify_live(evidence, 'live-warm', 'historical-byte-equivalence/UP_TO_DATE')
    if (evidence / 'product/warm-extra.txt').read_text(encoding='utf-8') != 'whole inventory changed; generation-unverified\n':
        raise ValueError('changed output inventory was not rejected')
    extra = json.loads((evidence / 'product/live-extra-graph.json').read_text(encoding='utf-8'))
    nodes = {node['usr']: node for node in extra['nodes']}
    if nodes['class:p/StartFragmentArgs']['synthesized'] is not False or 'generation-live-unverified:' not in json.dumps(extra):
        raise ValueError('changed output inventory promoted live generated identity')
    if (evidence / 'product/live-disabled.txt').read_text(encoding='utf-8') != 'snapshots disabled: no snapshot, generation verification or unit-test task dependency\n':
        raise ValueError('snapshot disabled task dependency control failed')
    return {'complete': True, 'scope': 'cold and unchanged UP-TO-DATE Java SafeArgs source/class attribution; no runtime or private-app claim',
            'variants': rows, 'warm': warm}


def verify_live(evidence, prefix, state):
    graph = json.loads((evidence / f'product/{prefix}-graph.json').read_text(encoding='utf-8'))
    dead = json.loads((evidence / f'product/{prefix}-dead.json').read_text(encoding='utf-8'))
    nodes = {node['usr']: node for node in graph['nodes']}
    findings = {row.get('nodeId') for row in dead['diagnostics']}
    for name in ['p/StartFragmentArgs', 'p/StartFragmentDirections', 'p/StartFragmentDirections$Idle']:
        if nodes[f'class:{name}']['synthesized'] is not True or f'class:{name}' in findings:
            raise ValueError('live generated classification was not consumed')
    for name in ['p/ManualArgs', 'p/ManualDirections', 'other/StartFragmentArgs']:
        if nodes[f'class:{name}']['synthesized'] is not False or f'class:{name}' not in findings:
            raise ValueError('live handwritten negative control changed')
    for value in [graph, dead]:
        text = json.dumps(value)
        if f'generation-live-observation: {state}' not in text or 'liveRevalidated=true' not in text:
            raise ValueError('live generation current status is missing')


if __name__ == '__main__':
    report = verify(ROOT / 'build/reports/safeargs-native')
    rendered = json.dumps(report, ensure_ascii=False) + '\n'
    if len(rendered.encode('utf-8')) > 16 * 1024:
        raise ValueError('incomplete product evidence: output budget exceeded')
    (ROOT / 'build/reports/safeargs-product-verification.json').write_text(rendered, encoding='utf-8')
    print(rendered, end='')
