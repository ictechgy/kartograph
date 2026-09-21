#!/usr/bin/env python3
"""고정 규칙으로 javap 호출과 실제 실행된 기존 rule suite를 oracle로 조립한다."""
import argparse
import hashlib
import json
from pathlib import Path
import re
import xml.etree.ElementTree as ET

from grade import Inventory

MODULES = {'detekt_detekt-7212': 'detekt-rules-naming', 'detekt_detekt-6446': 'detekt-rules-errorprone',
           'pinterest_ktlint-2774': 'ktlint-ruleset-standard', 'pinterest_ktlint-2727': 'ktlint-rule-engine'}


def changed_lines(text, module):
    files = {}; current = None; old_line = None
    for line in text.splitlines():
        if line.startswith('--- a/'):
            path = line[len('--- a/'):]
            if not path.startswith(module + '/'):
                raise ValueError('production diff escaped selected module')
            current = path[len(module) + 1:]; files.setdefault(current, dict(removed=set(), insertedBefore=set()))
        elif line.startswith('@@ '):
            old_line = int(re.search(r'@@ -(\d+)', line).group(1))
        elif current is not None and old_line is not None:
            if line.startswith('-'):
                files[current]['removed'].add(old_line); old_line += 1
            elif line.startswith('+'):
                files[current]['insertedBefore'].add(old_line)
            elif line.startswith(' '):
                old_line += 1
    return files


def intersects_function(evidence, changed):
    """서명 줄 삭제는 포함하고 다음 선언 앞에 새 선언을 넣은 것은 구분한다."""
    start, end = evidence['startLine'], evidence['endLine']
    return (any(start <= line <= end for line in changed.get('removed', [])) or
            any(start < line <= end for line in changed.get('insertedBefore', [])))


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--cases', type=Path, required=True)
    parser.add_argument('--cohort', type=Path, required=True)
    parser.add_argument('--output', type=Path, required=True)
    args = parser.parse_args(); args.output.mkdir(parents=True, exist_ok=False)
    summary = []
    for case in json.loads(args.cohort.read_text())['primary']:
        folder = args.cases / case['id']; module = MODULES[case['id']]; project = folder / 'base' / module
        data_file = folder / 'oracle-compiled/declarations.json'; data = json.loads(data_file.read_text())
        declarations = {d['id']: d for d in data['declarations']}
        changes = changed_lines((folder / 'production.diff').read_text(), module)
        changed = {d['id'] for d in declarations.values() if d['sourceEvidence'] and
                   intersects_function(d['sourceEvidence'], changes.get(d['file'], {}))}
        if not changed:
            raise ValueError('no changed existing method identity found')
        direct = {r['caller'] for r in data['references'] if r['target'] in changed} - changed
        indirect = {r['caller'] for r in data['references'] if r['target'] in direct} - direct - changed
        executed, skipped, unresolved_tests = set(), [], []
        xml_paths = sorted((project / 'build/test-results/test').glob('TEST-*.xml'))
        if not xml_paths:
            raise ValueError('original test execution evidence required')
        for path in xml_paths:
            suite = ET.parse(path).getroot()
            if int(suite.attrib.get('failures', 0)) or int(suite.attrib.get('errors', 0)):
                raise ValueError('original selected test suite failed')
            for test in suite.findall('testcase'):
                name = test.attrib['name']; owner = test.attrib['classname'].replace('.', '/')
                if test.find('skipped') is not None:
                    skipped.append(dict(owner=owner, name=name)); continue
                matched = [d['id'] for d in declarations.values() if d['id'].startswith('method:' + owner + '#') and d['sourceEvidence']
                           and (name == d['sourceEvidence']['name'] or name.startswith(d['sourceEvidence']['name'] + '('))]
                if len(matched) == 1:
                    executed.add(matched[0])
                else:
                    unresolved_tests.append(dict(owner=owner, name=name, candidates=matched))
        if unresolved_tests:
            raise ValueError('executed test declaration mapping is unresolved; inspect original test names')
        stems = {Path(file).stem for file in changes}
        suite_targets = {identity for identity in executed if Path(declarations[identity]['file']).stem in
                         {stem + suffix for stem in stems for suffix in ['Test', 'Spec']}}
        targets = direct | indirect | suite_targets
        # 2단계 호출에 테스트가 있으면 실제 실행된 선언만 포함한다.
        targets = {identity for identity in targets if not declarations[identity]['file'] or
                   '/test/' not in declarations[identity]['file'] or identity in executed}
        document = dict(format='kartograph-ai-utility-v5-oracle', case=case['id'], base=case['base'], module=module,
                        declarations=data['declarations'], targets=sorted(targets), changed=sorted(changed),
                        direct=sorted(direct), indirect=sorted(indirect), executedRuleSuiteAnchors=sorted(suite_targets),
                        executedTestMethods=sorted(executed), skippedTests=skipped,
                        evidence=dict(declarationsSha256=hashlib.sha256(data_file.read_bytes()).hexdigest(),
                                      productionDiffSha256=hashlib.sha256((folder / 'production.diff').read_bytes()).hexdigest(),
                                      junitSha256={path.name: hashlib.sha256(path.read_bytes()).hexdigest() for path in xml_paths}),
                        limitations=['Known static callers and executed same-rule regression anchors, not complete behavioral impact.',
                                     'Non-primary source-unmapped declarations are reported separately.',
                                     'Non-oracle predictions are unadjudicated, not false positives.'])
        inventory = Inventory(document)
        path = args.output / (case['id'] + '.json'); path.write_text(json.dumps(document, ensure_ascii=False, indent=2) + '\n')
        row = dict(case=case['id'], module=module, oracle=len(targets), primary=len(inventory.source_oracle),
                   direct=len(direct), indirect=len(indirect), executedRuleSuiteAnchors=len(suite_targets),
                   changed=sorted(changed), sha256=hashlib.sha256(path.read_bytes()).hexdigest())
        summary.append(row); print(json.dumps(row), flush=True)
    (args.output / 'summary.json').write_text(json.dumps(summary, indent=2) + '\n')


if __name__ == '__main__':
    main()
