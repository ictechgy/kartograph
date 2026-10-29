#!/usr/bin/env python3
"""고정 source anchor 기대값으로 PSI와 제품의 같은 authored method 호출만 대조한다."""
import argparse
import hashlib
import json
from pathlib import Path

HERE = Path(__file__).resolve().parent
# fixture source에서 먼저 고정한 8개 명시적 method identity와 source 위치이다.
DECLARATIONS = {
    ('probe/PsiKotlin.kt', 4, 9): 'method:probe/PsiKotlin#leaf(I)I',
    ('probe/PsiKotlin.kt', 5, 9): 'method:probe/PsiKotlin#leaf(Ljava/lang/String;)Ljava/lang/String;',
    ('probe/PsiKotlin.kt', 6, 9): 'method:probe/PsiKotlin#caller()I',
    ('probe/PsiKotlin.kt', 7, 9): 'method:probe/PsiKotlin#unused()I',
    ('probe/PsiJava.java', 4, 23): 'method:probe/PsiJava#leaf(I)I',
    ('probe/PsiJava.java', 5, 26): 'method:probe/PsiJava#leaf(Ljava/lang/String;)Ljava/lang/String;',
    ('probe/PsiJava.java', 6, 26): 'method:probe/PsiJava#caller()Ljava/lang/String;',
    ('probe/PsiJava.java', 7, 23): 'method:probe/PsiJava#unused()I',
}
EXPECTED = {
    ('method:probe/PsiKotlin#caller()I', 'method:probe/PsiKotlin#leaf(I)I'),
    ('method:probe/PsiKotlin#caller()I', 'method:probe/PsiJava#leaf(I)I'),
    ('method:probe/PsiJava#caller()Ljava/lang/String;', 'method:probe/PsiJava#leaf(Ljava/lang/String;)Ljava/lang/String;'),
    ('method:probe/PsiJava#caller()Ljava/lang/String;', 'method:probe/PsiKotlin#leaf(Ljava/lang/String;)Ljava/lang/String;'),
}


def sha(path):
    return hashlib.sha256(path.read_bytes()).hexdigest()


def anchors():
    result = {}
    for (file, line, column), usr in DECLARATIONS.items():
        source = (HERE / 'fixture' / file).read_text()
        prefix = ''.join(source.splitlines(keepends=True)[:line - 1]) + source.splitlines()[line - 1][:column - 1]
        offset = len(prefix.encode('utf-16-le')) // 2
        result[(file, offset)] = usr
    return result


def measure(actual):
    return {'expected': len(EXPECTED), 'foundExpected': len(actual & EXPECTED),
            'missing': sorted(EXPECTED - actual), 'unexpected': sorted(actual - EXPECTED),
            'exactExpectedSet': actual == EXPECTED, 'observed': sorted(actual)}


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument('--psi', type=Path, required=True)
    parser.add_argument('--graph', type=Path)
    parser.add_argument('--output', type=Path, required=True)
    args = parser.parse_args()
    rows = [json.loads(line) for line in args.psi.read_text().splitlines()]
    source_hashes = {file: sha(HERE / 'fixture' / file) for file in sorted({key[0] for key in DECLARATIONS})}
    frozen = json.loads((HERE / 'fixture-manifest.json').read_text())
    if any(sha(HERE / name) != expected for name, expected in frozen['expectationFilesSha256'].items()):
        raise ValueError('Expectation implementation changed after fixture freeze')
    if source_hashes != frozen['sourceSha256']:
        raise ValueError('Fixture sources changed after expectations were frozen')
    anchor_map = anchors()
    primary = [row for row in rows if row['kind'] == 'method']
    unqualified = []
    edges = set()
    for row in primary:
        def identity(field):
            value = row.get(field)
            return anchor_map.get((value['file'], value['nameStartUtf16'])) if value else None
        caller, target = identity('caller'), identity('target')
        if not row['resolved'] or caller is None or target is None:
            unqualified.append(row)
        else:
            edges.add((caller, target))
    language_counts = {engine: sum(row['engine'] == engine for row in primary) for engine in ['kotlin-aa', 'java-psi']}
    result = {'scope': 'Eight explicit methods in exactly two source files; direct method-call target edges between those methods',
              'sourceSha256': source_hashes, 'expectationsFrozenBeforeExecution': True,
              'psi': {**measure(edges), 'methodCallSites': len(primary), 'methodCallSitesByEngine': language_counts,
                      'unresolvedOrUnmappedMethodCalls': unqualified,
                      'constructorBoundary': [row for row in rows if row['kind'] == 'constructor']},
              'excluded': ['implicit constructors', 'library/synthetic calls', 'whole-program reachability',
                           'runtime dispatch completeness', 'Android generated sources', 'performance ranking']}
    qualified = not unqualified and edges == EXPECTED and len(primary) == 4 and language_counts == {'kotlin-aa': 2, 'java-psi': 2}
    result['psi']['qualified'] = qualified
    if args.graph:
        graph = json.loads(args.graph.read_text())
        nodes = {node['usr']: node for node in graph['nodes']}
        ids = set(DECLARATIONS.values())
        graph_edges = {(edge['source'], edge['target']) for edge in graph['edges']
                       if edge['kind'] == 'call' and edge['source'] in ids and edge['target'] in ids}
        result['kartograph'] = {**measure(graph_edges), 'declaredMethodsFound': len(ids & nodes.keys()),
                                'declaredMethodsMissing': sorted(ids - nodes.keys()),
                                'graphSha256': sha(args.graph), 'tool': graph.get('tool'),
                                'totalGraphNodes': len(graph['nodes']), 'totalGraphEdges': len(graph['edges'])}
        result['kartograph']['sourceLocations'] = [
            {'usr': usr, 'expectedFile': file, 'expectedLine': line, 'expectedNameColumnUtf16': column,
             'actual': nodes.get(usr, {}).get('location')} for (file, line, column), usr in DECLARATIONS.items()]
        result['crossEnginePrimaryEdgeEquality'] = qualified and graph_edges == edges
        result['comparisonQualified'] = qualified and ids <= nodes.keys() and graph_edges == EXPECTED
        locations = result['kartograph']['sourceLocations']
        result['kartograph']['sourceFileAndLineMatches'] = sum(
            row['actual'] is not None and row['actual'].get('pathKind') == 'projectRelative' and
            row['actual'].get('path') == row['expectedFile'] and row['actual'].get('line') == row['expectedLine']
            for row in locations)
        result['kartograph']['exactNameColumnMatches'] = sum(
            row['actual'] is not None and row['actual'].get('pathKind') == 'projectRelative' and
            row['actual'].get('path') == row['expectedFile'] and row['actual'].get('line') == row['expectedLine'] and
            row['actual'].get('column') == row['expectedNameColumnUtf16'] for row in locations)
        result['kartograph']['locationQualification'] = 'Observed independently; not a required primary edge-quality gate'
    args.output.write_text(json.dumps(result, indent=2, sort_keys=True) + '\n')
    print(json.dumps({'psiQualified': qualified, 'primaryExpectedEdges': len(EXPECTED),
                      'crossEnginePrimaryEdgeEquality': result.get('crossEnginePrimaryEdgeEquality')}))
    if not qualified:
        raise SystemExit('PSI semantic qualification failed; see preserved report')
    if args.graph and not result['comparisonQualified']:
        raise SystemExit('Product comparison qualification failed; see preserved report')


if __name__ == '__main__':
    main()
