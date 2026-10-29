#!/usr/bin/env python3
"""고정된 열두 PSI 행을 먼저 확인한 뒤 이 표본의 lowering만 대조한다."""
import argparse
from collections import Counter
import hashlib
import json
from pathlib import Path
import re

HERE = Path(__file__).resolve().parent


def digest(path):
    return hashlib.sha256(path.read_bytes()).hexdigest()


def verify_frozen_inputs():
    frozen = json.loads((HERE / 'fixture-manifest.json').read_text())
    for name, expected in frozen['sourceSha256'].items():
        if digest(HERE / 'fixture' / name) != expected:
            raise ValueError('Frozen source changed: ' + name)
    for name, expected in frozen['expectationAndProposalFilesSha256'].items():
        if digest(HERE / name) != expected:
            raise ValueError('Frozen expectation/proposal changed: ' + name)
    return frozen


def canonical(row):
    return json.dumps(row, sort_keys=True, separators=(',', ':'))


def qualify_source(rows, expected):
    sites = expected['internalCallSites'] + expected['externalBoundarySites']
    wanted = Counter(canonical(site['expectedAdapterRow']) for site in sites)
    actual = Counter(canonical(row) for row in rows)
    return {'qualified': actual == wanted, 'expectedRows': sum(wanted.values()),
            'actualRows': len(rows),
            'missingRows': [json.loads(row) for row in (wanted - actual).elements()],
            'unexpectedRows': [json.loads(row) for row in (actual - wanted).elements()],
            'internalSites': len(expected['internalCallSites']),
            'externalBoundarySites': len(expected['externalBoundarySites']),
            'externalIdentityLimit': 'Out-of-root target identities are not exported or qualified.'}


def method_ref(reference, owner):
    name, descriptor = reference.split(':', 1)
    if '.' in name:
        owner, name = name.rsplit('.', 1)
    return 'method:' + owner + '#' + name.strip('"') + descriptor


def read_javap(text):
    """JDK 17 verbose 출력에서 이 표본의 method/직접 호출/bootstrap handle만 읽는다."""
    methods = {}
    for chunk in re.split(r'(?=^Classfile )', text, flags=re.M):
        owner_match = re.search(r'^\s*this_class:\s+#\d+\s+//\s+(\S+)', chunk, re.M)
        if not owner_match:
            continue
        owner = owner_match.group(1)
        headers = list(re.finditer(r'^  ([^\n]+\([^\n]*\);)\n    descriptor: (\S+)\n', chunk, re.M))
        bootstraps = {}
        if '\nBootstrapMethods:\n' in chunk:
            section = chunk.split('\nBootstrapMethods:\n', 1)[1]
            blocks = list(re.finditer(r'^  (\d+): ([^\n]+)', section, re.M))
            for index, block in enumerate(blocks):
                body = section[block.start():blocks[index + 1].start() if index + 1 < len(blocks) else len(section)]
                if 'java/lang/invoke/LambdaMetafactory.metafactory:' not in block.group(2):
                    continue
                bootstraps[int(block.group(1))] = [method_ref(value, owner) for value in
                    re.findall(r'^\s+#\d+ REF_invoke\w+ (\S+)', body, re.M)]
        for index, header in enumerate(headers):
            body = chunk[header.end():headers[index + 1].start() if index + 1 < len(headers) else chunk.find('\n}', header.end())]
            name = header.group(1).split('(', 1)[0].split()[-1]
            if name == owner.replace('/', '.'):
                name = '<init>'
            usr = 'method:' + owner + '#' + name + header.group(2)
            if usr in methods:
                raise ValueError('Duplicate javap method: ' + usr)
            references = re.findall(r'^\s+\d+: invoke(?:static|virtual|special|interface)\s+[^\n]*// (?:InterfaceMethod|Method) (\S+)', body, re.M)
            indices = [int(value) for value in re.findall(r'^\s+\d+: invokedynamic\s+[^\n]*// InvokeDynamic #(\d+):', body, re.M)]
            methods[usr] = {'calls': sorted({method_ref(value, owner) for value in references}),
                'synthetic': bool(re.search(r'^\s+flags:.*\bACC_SYNTHETIC\b', body, re.M)),
                'lambdaImplementationHandles': sorted({value for idx in indices for value in bootstraps.get(idx, [])})}
    return methods


def readback(graph, methods, expected, lowering):
    declarations = {row['id']: row['usr'] for row in expected['declarations']}
    nodes = {row['usr']: row for row in graph['nodes']}
    raw_edges = [row for row in graph['edges'] if row['kind'] == 'call' and row.get('origin', 'bytecode') == 'bytecode']
    raw = {(row['source'], row['target']) for row in raw_edges}
    bytecode = {(caller, target) for caller, facts in methods.items() for target in facts['calls']}
    hypotheses = {row['site']: row for row in lowering['transformedSites']}
    observations = []
    for site in expected['internalCallSites']:
        source = declarations[site['lexicalCaller']]
        target = declarations[site['selectedSourceTarget']]
        pair = (source, target)
        evidence_pairs = [pair]
        details = {}
        checks = {}
        kind = site['loweringHypothesis']
        if kind == 'preserved-direct':
            checks = {'bytecodeDirectCall': pair in bytecode, 'productDirectCall': pair in raw}
        elif kind == 'erased-inline-target':
            expanded = (source, hypotheses[site['id']]['expandedBodyTarget'])
            evidence_pairs.append(expanded)
            checks = {'bytecodeSourceTargetErased': pair not in bytecode,
                      'bytecodeExpandedBodyCall': expanded in bytecode,
                      'productSourceTargetNotInvented': pair not in raw,
                      'productExpandedBodyCall': expanded in raw}
        elif kind == 'redirected-default-bridge-target':
            hypothesis = hypotheses[site['id']]
            bridge = hypothesis['expectedBridge']
            required = [(source, bridge), (bridge, target)]
            evidence_pairs += required
            association = nodes.get(bridge, {}).get('kotlinDefaultBridge')
            details['actualDefaultBridgeAssociation'] = association
            checks = {'bytecodeRedirectAndBridgeTarget': all(edge in bytecode for edge in required),
                      'productRedirectAndBridgeTarget': all(edge in raw for edge in required),
                      'bytecodeOriginalDirectCallAbsent': pair not in bytecode,
                      'productOriginalDirectCallNotInvented': pair not in raw,
                      'exactExistingAssociation': isinstance(association, dict) and association.get('basis') == 'kotlinMetadataAndBytecode' and all(
                          association.get(key) == value for key, value in hypothesis['existingAssociationExpected'].items())}
        elif kind == 'relocated-default-expression-owner':
            moved = (hypotheses[site['id']]['expectedRawOwner'], target)
            evidence_pairs.append(moved)
            checks = {'bytecodeMovedExpressionCall': moved in bytecode,
                      'productMovedExpressionCall': moved in raw,
                      'bytecodeLexicalOwnerNotDirectCaller': pair not in bytecode,
                      'productLexicalOwnerNotInvented': pair not in raw}
        elif kind == 'relocated-lambda-body-owner':
            candidates = [usr for usr in methods.get(source, {}).get('lambdaImplementationHandles', [])
                          if usr in methods and usr not in declarations.values() and (usr, target) in bytecode]
            details['actualImplementationCandidates'] = candidates
            details['implementationSyntheticFlags'] = {usr: methods[usr]['synthetic'] for usr in candidates}
            checks = {'uniqueCompilerBodyFromActualBootstrapHandle': len(candidates) == 1,
                      'bytecodeLexicalOwnerNotDirectCaller': pair not in bytecode,
                      'productLexicalOwnerNotInvented': pair not in raw}
            if len(candidates) == 1:
                body = candidates[0]
                evidence_pairs += [(source, body), (body, target)]
                checks.update({'productBootstrapImplementationHandle': (source, body) in raw,
                               'productImplementationBodyCall': (body, target) in raw})
        else:
            checks['supportedFixtureHypothesis'] = False
        observations.append({'site': site['id'], 'sourceCallerSemantics': site['sourceContext'],
            'sourcePair': list(pair), 'classification': kind if checks and all(checks.values()) else 'unclassified',
            'checks': checks, **details,
            'observedBytecodeDirectPairs': [list(edge) for edge in evidence_pairs if edge in bytecode],
            'observedRawProductEdges': [edge for edge in raw_edges if (edge['source'], edge['target']) in evidence_pairs]})
    declared = set(declarations.values())
    unused = declarations['K.unused']
    raw_authored = {edge for edge in raw if set(edge) <= declared}
    bytecode_authored = {edge for edge in bytecode if set(edge) <= declared}
    gates = {'allExplicitProductMethodsPresent': declared <= nodes.keys(),
             'allExplicitJavapMethodsPresent': declared <= methods.keys(),
             'uniqueProductNodeIdentities': len(nodes) == len(graph['nodes']),
             'exactRawAuthoredPairsMatchDisassembly': raw_authored == bytecode_authored,
             'unusedHasNoRawIncomingCall': not any(target == unused for _, target in raw),
             'allSitesClassifiedWithEvidence': all(row['classification'] != 'unclassified' for row in observations)}
    return {'qualified': all(gates.values()), 'gates': gates,
        'missingProductMethods': sorted(declared - nodes.keys()),
        'missingJavapMethods': sorted(declared - methods.keys()), 'siteObservations': observations,
        'rawCallsBetweenAuthoredMethods': sorted([list(edge) for edge in raw_authored]),
        'rawAuthoredPairsMissingFromProduct': sorted([list(edge) for edge in bytecode_authored - raw_authored]),
        'rawAuthoredPairsUnsupportedByDisassembly': sorted([list(edge) for edge in raw_authored - bytecode_authored]),
        'tool': graph.get('tool'),
        'scopeLimit': 'Compiler-output/source preservation observations only; not runtime callback execution, general completeness, accuracy or performance ranking.'}


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--psi', type=Path, required=True)
    parser.add_argument('--graph', type=Path)
    parser.add_argument('--javap', type=Path)
    parser.add_argument('--output', type=Path, required=True)
    args = parser.parse_args()
    frozen = verify_frozen_inputs()
    expected = json.loads((HERE / 'source-call-expectations.json').read_text())
    rows = [json.loads(line) for line in args.psi.read_text().splitlines() if line.strip()]
    source = qualify_source(rows, expected)
    result = {'fixtureManifestSha256': digest(HERE / 'fixture-manifest.json'),
              'sourceSha256': frozen['sourceSha256'], 'psiSha256': digest(args.psi), 'psi': source}
    code = 0 if source['qualified'] else 1
    result['status'] = 'source-qualified' if source['qualified'] else 'source-qualification-failed'
    # 소스 gate 실패 때는 제품 자료를 읽거나 lowering 성공을 계산하지 않는다.
    if source['qualified'] and args.graph:
        if not args.javap:
            raise ValueError('--graph requires actual --javap output')
        product = readback(json.loads(args.graph.read_text()), read_javap(args.javap.read_text()), expected,
                           json.loads((HERE / 'lowering-expectations.json').read_text()))
        result.update({'product': product, 'graphSha256': digest(args.graph), 'javapSha256': digest(args.javap),
                       'status': 'qualified-scoped-readback' if product['qualified'] else 'unclassified-or-failed-readback'})
        code = 0 if product['qualified'] else 2
    with args.output.open('x') as stream:
        json.dump(result, stream, indent=2, sort_keys=True)
        stream.write('\n')
    print(json.dumps({'status': result['status'], 'sourceQualified': source['qualified'],
                      'productQualified': result.get('product', {}).get('qualified')}))
    raise SystemExit(code)


if __name__ == '__main__':
    main()
