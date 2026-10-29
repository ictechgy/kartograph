#!/usr/bin/env python3
"""독립 bytecode 호출과 변경 전후 assertion 결과로 정답을 고정한다."""
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


grade = load('v7_oracle_grade', HERE / 'grade.py')
diff = load('v6_diff_only', HERE.parent / 'ai-utility-v6/assemble.py')


def behavioral_partition(before, after):
    """동일 테스트 집합의 base-pass/changed-assertion-fail만 동작 양성으로 인정한다."""
    if not before or before.keys() != after.keys() or set(before.values()) != {'pass'}:
        raise ValueError('all baseline tests must pass and test identities must stay identical')
    if not set(after.values()) <= {'pass', 'assertion-failure'}:
        raise ValueError('skips and execution errors invalidate qualification')
    positive = sorted(identity for identity, status in after.items() if status == 'assertion-failure')
    negative = sorted(identity for identity, status in after.items() if status == 'pass')
    if not positive or not negative:
        raise ValueError('both behavioral positive and negative controls are required')
    return positive, negative


def test_results(folder, declarations):
    """JUnit XML을 원래 Kotlin 선언에 유일하게 대응하고 생략된 테스트를 거부한다."""
    outcomes, hashes = {}, {}
    paths = sorted(folder.glob('TEST-*.xml'))
    if not paths:
        raise ValueError('JUnit execution evidence missing')
    for path in paths:
        hashes[path.name] = hashlib.sha256(path.read_bytes()).hexdigest()
        suite = ET.parse(path).getroot()
        tests = suite.findall('testcase')
        if int(suite.attrib['tests']) != len(tests):
            raise ValueError('JUnit test count mismatch')
        for test in tests:
            prefix = 'method:' + test.attrib['classname'].replace('.', '/') + '#'
            name = test.attrib['name']
            ids = [row['id'] for row in declarations if row['id'].startswith(prefix) and row.get('sourceEvidence')
                   and name in (row['sourceEvidence']['name'], row['sourceEvidence']['name'] + '()')]
            if len(ids) != 1 or ids[0] in outcomes:
                raise ValueError('test identity unresolved or duplicate')
            status = 'pass'
            if test.find('skipped') is not None:
                status = 'skip'
            elif test.find('error') is not None:
                status = 'error'
            elif test.find('failure') is not None:
                failures = test.findall('failure')
                status = 'assertion-failure' if all(f.attrib.get('type') in
                    {'java.lang.AssertionError', 'org.junit.ComparisonFailure'} for f in failures) else 'error'
            outcomes[ids[0]] = status
    expected = {row['id'] for row in declarations if row['sourceAddressable'] and
                row['file'].startswith('src/test/')}
    if outcomes.keys() != expected:
        raise ValueError('all authored test declarations must execute exactly once')
    return outcomes, hashes


def assemble(case, data_path, before, after, patch, changed_id):
    """제품 graph 없이 호출 정답과 테스트 동작 정답을 분리해 구성한다."""
    data = json.loads(data_path.read_text())
    declarations = {row['id']: row for row in data['declarations']}
    changes = diff.changed_lines(patch.read_text(), case)
    changed = {row['id'] for row in declarations.values() if row['sourceEvidence'] and
               diff.intersects_function(row['sourceEvidence'], changes.get(row['file'], {}))}
    if changed != {changed_id}:
        raise ValueError('patch must change exactly the predeclared existing method')
    production = {row['id'] for row in declarations.values() if row['sourceAddressable'] and
                  row['file'].startswith('src/main/')}
    direct = {r['caller'] for r in data['references'] if r['target'] in changed} & production - changed
    indirect = {r['caller'] for r in data['references'] if r['target'] in direct} & production - changed - direct
    base_results, base_hashes = test_results(before, data['declarations'])
    changed_results, changed_hashes = test_results(after, data['declarations'])
    positive, negative = behavioral_partition(base_results, changed_results)
    targets = sorted(direct | indirect | set(positive))
    document = dict(format='kartograph-ai-utility-v7-oracle', case=case, declarations=data['declarations'],
        targets=targets, changed=sorted(changed), direct=sorted(direct), indirect=sorted(indirect),
        behaviorPositive=positive, behaviorNegative=negative, executedTestMethods=sorted(base_results),
        behaviorResults=dict(baseline=base_results, changed=changed_results),
        evidence=dict(declarationsSha256=hashlib.sha256(data_path.read_bytes()).hexdigest(),
                      patchSha256=hashlib.sha256(patch.read_bytes()).hexdigest(), baselineJUnit=base_hashes, changedJUnit=changed_hashes),
        limitations=['Static caller oracle is exact javap targets at depth 1 and 2; dynamic dispatch may be absent.',
                     'Behavior labels apply only to this fixed patch and executed assertions.',
                     'Other production declarations are unadjudicated, not false positives.',
                     'Synthetic controls do not establish real-project productivity.'])
    grade.Inventory(document)
    return document
