#!/usr/bin/env python3
"""실제 공개 모듈의 독립 정적 호출과 JUnit invocation 변화로 oracle을 고정한다."""
import argparse
import hashlib
import importlib.util
import json
from pathlib import Path
import xml.etree.ElementTree as ET

HERE = Path(__file__).resolve().parent


def load(name, path):
    spec = importlib.util.spec_from_file_location(name, path)
    module = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(module)
    return module


execution = load('v8_native_execution', HERE / 'execution.py')
previous = load('v7_typed_oracle', HERE.parent / 'ai-utility-v7/assemble.py')
Inventory = previous.grade.Inventory


def digest(path):
    return hashlib.sha256(path.read_bytes()).hexdigest()


def verify_xml(folder, observed):
    """listener 완결성에 더해 Gradle의 JUnit XML 총수를 독립 대조한다."""
    paths = sorted(folder.glob('TEST-*.xml'))
    if not paths:
        raise ValueError('native JUnit XML missing')
    totals = dict(tests=0, failures=0, errors=0, skipped=0)
    for path in paths:
        suite = ET.parse(path).getroot()
        if int(suite.attrib['tests']) != len(suite.findall('testcase')):
            raise ValueError('JUnit XML testcase count mismatch')
        for key in totals:
            totals[key] += int(suite.attrib.get(key, 0))
    rows = list(observed['invocations'].values())
    if (totals['tests'] != len(rows) or totals['failures'] + totals['errors'] !=
            sum(row['status'] in ['assertion-failure', 'error'] for row in rows) or
            totals['skipped'] != sum(row['status'] in ['skipped', 'aborted'] for row in rows)):
        raise ValueError('observer and native JUnit execution totals differ')
    return totals, {path.name: digest(path) for path in paths}


def source_scope(data, behavior):
    """계속 실행한 의존 모듈의 상속 테스트를 숨기지 않고 source 범위와 구분한다."""
    if data.get('shadowedOwners'):
        raise ValueError('shadowed compiled owners invalidate the independent inventory')
    declarations = {row['id']: row for row in data['declarations']}
    out_of_scope = []
    for identity in behavior['executed']:
        if identity in declarations and declarations[identity]['sourceAddressable']:
            continue
        if identity in declarations or any(identity.startswith('method:' + owner + '#') for owner in data['classSha256']):
            raise ValueError('executed test in a supplied class lacks a source identity')
        out_of_scope.append(identity)
    if set(out_of_scope) & set(behavior['positive']):
        raise ValueError('behavior-positive inherited test is outside supplied source scope')
    return dict(positive=behavior['positive'], negative=sorted(set(behavior['negative']) - set(out_of_scope)),
                executed=sorted(set(behavior['executed']) - set(out_of_scope)), outOfScope=sorted(out_of_scope))


def validate_native_records(baseline, changed, patch_hash):
    """성공한 원본 컴파일과 production 변경 외의 tracked 부작용 부재를 요구한다."""
    if baseline.get('exit') != 0 or baseline.get('matched') is not True or baseline.get('dirtyPatchSha256') != hashlib.sha256(b'').hexdigest():
        raise ValueError('baseline must remain clean after successful tests and capture')
    if changed.get('exit') != 1 or changed.get('dirtyPatchSha256') != patch_hash:
        raise ValueError('changed execution differs from the proposed production patch')


def assemble(case, inventory_path, baseline, changed):
    """원래 모듈 전체를 유지하며 정답의 source/USR 대칭성과 사전 상한을 검사한다."""
    data = json.loads(inventory_path.read_text()); declarations = {row['id']: row for row in data['declarations']}
    patch = changed / 'production.diff'
    native_record = json.loads((changed / 'execution.json').read_text())
    validate_native_records(json.loads((baseline / 'execution.json').read_text()), native_record, digest(patch))
    differences = previous.diff.changed_lines(patch.read_text(), case['module'])
    changed_ids = {row['id'] for row in declarations.values() if row['sourceEvidence'] and
        previous.diff.intersects_function(row['sourceEvidence'], differences.get(row['file'], {}))}
    if len(changed_ids) != 1 or any(declarations[i]['sourceEvidence']['name'] != case['function'] for i in changed_ids):
        raise ValueError('production patch changed an unexpected declaration')
    before = execution.read_execution(baseline / 'observer'); after = execution.read_execution(changed / 'observer')
    before_counts, before_hashes = verify_xml(baseline / 'junit', before)
    after_counts, after_hashes = verify_xml(changed / 'junit', after)
    behavior = execution.partition(before, after)
    scoped = source_scope(data, behavior)
    # 생성 JVM 선언을 중간 경로로 보존하되 primary는 실제 원본 함수만이다.
    direct_all = {row['caller'] for row in data['references'] if row['target'] in changed_ids} - changed_ids
    indirect_all = {row['caller'] for row in data['references'] if row['target'] in direct_all} - direct_all - changed_ids
    production = {row['id'] for row in declarations.values() if row['sourceAddressable'] and row['file'].startswith('src/main/')}
    direct, indirect = direct_all & production, indirect_all & production
    callers = direct | indirect
    if (len(callers) < 2 or len({declarations[identity]['file'] for identity in callers}) < 2 or
            len(scoped['positive']) < 2 or len(scoped['negative']) < 10):
        raise ValueError('case does not meet preregistered caller/behavior qualification')
    targets = sorted(callers | set(behavior['positive']))
    document = dict(format='kartograph-ai-utility-v8-oracle', case=case['id'], module=case['module'], base=case['base'],
        declarations=data['declarations'], targets=targets, changed=sorted(changed_ids), direct=sorted(direct), indirect=sorted(indirect),
        behaviorPositive=scoped['positive'], behaviorNegative=scoped['negative'], executedTestMethods=scoped['executed'],
        outOfScopeExecutedMethods=scoped['outOfScope'],
        nonPrimaryStaticCallers=sorted((direct_all | indirect_all) - callers),
        behavior=behavior, nativeTotals=dict(baseline=before_counts, changed=after_counts),
        evidence=dict(declarationsSha256=digest(inventory_path), productionDiffSha256=digest(patch),
            baselineObserver=before['evidenceSha256'], changedObserver=after['evidenceSha256'],
            baselineJUnit=before_hashes, changedJUnit=after_hashes),
        limitations=['Fixed proposed production mutations in real public modules; not natural PRs or a representative random sample.',
            'Known exact javap call targets at depth 1/2; dynamic dispatch and generated-source gaps remain.',
            'A method is behavior-positive when at least one identical native invocation changes from pass to assertion failure.',
            'Negative labels apply only to the executed fixed tests and mutation; other production predictions are unadjudicated.',
            'Scope is one complete supplied module; dependency implementations and other modules may be unavailable.'])
    Inventory(document)
    return document


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--config', type=Path, required=True)
    parser.add_argument('--output', type=Path, required=True)
    args = parser.parse_args(); config = json.loads(args.config.read_text())
    args.output.mkdir(parents=True, exist_ok=False)
    results = []
    for case in json.loads((HERE / 'changes.json').read_text()):
        source = config[case['source']]
        document = assemble(case, Path(source['inventory']), Path(source['baseline']), Path(config['changed'][case['id']]))
        path = args.output / (case['id'] + '.json'); path.write_text(json.dumps(document, ensure_ascii=False, indent=2) + '\n')
        row = dict(case=case['id'], callers=len(document['direct']) + len(document['indirect']),
            positiveMethods=len(document['behaviorPositive']), negativeMethods=len(document['behaviorNegative']),
            failingInvocations=document['behavior']['assertionFailures'], totalInvocations=document['behavior']['totalInvocations'],
            primary=len(document['targets']), oracleSha256=digest(path))
        results.append(row); print(json.dumps(row), flush=True)
    (args.output / 'summary.json').write_text(json.dumps(results, indent=2) + '\n')


if __name__ == '__main__': main()
