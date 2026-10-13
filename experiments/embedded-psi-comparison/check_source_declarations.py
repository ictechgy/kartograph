#!/usr/bin/env python3
"""명시적 sourceHeader 근거만 고정한 8개 source 식별자 위치와 대조한다."""
import argparse
import hashlib
import json
from pathlib import Path

HERE = Path(__file__).resolve().parent


def check(graph, expected, source_root, baseline=None):
    for name, digest in expected['sourceSha256'].items():
        if hashlib.sha256((source_root / name).read_bytes()).hexdigest() != digest:
            raise ValueError('Fixture source bytes differ from frozen location expectations')
    nodes = {node['usr']: node for node in graph['nodes']}
    if len(nodes) != len(graph['nodes']):
        raise ValueError('Duplicate graph node identity')
    rows = []
    for declaration in expected['methods']:
        text = (source_root / declaration['path']).read_text()
        utf16 = text.encode('utf-16-le')
        offset = declaration['offsetUtf16']
        name_bytes = declaration['name'].encode('utf-16-le')
        if utf16[2 * offset:2 * offset + len(name_bytes)] != name_bytes:
            raise ValueError('Frozen source name offset does not identify the expected method')
        prefix = utf16[:2 * offset].decode('utf-16-le')
        if prefix.count('\n') + 1 != declaration['line'] or len(prefix.rsplit('\n', 1)[-1].encode('utf-16-le')) // 2 + 1 != declaration['column']:
            raise ValueError('Frozen line/column and offset disagree')
        actual = nodes.get(declaration['usr'], {}).get('sourceDeclaration')
        fields = {key: declaration[key] for key in ['path', 'line', 'column', 'offsetUtf16']}
        fields.update(sourceSha256=expected['sourceSha256'][declaration['path']],
                      coordinateBasis=expected['coordinateBasis'], origin='sourceHeader', pathKind='projectRelative')
        match = actual is not None and all(actual.get(key) == value for key, value in fields.items())
        rows.append({'usr': declaration['usr'], 'expected': fields, 'sourceDeclaration': actual, 'exactMatch': match})
    result = {'scope': 'sourceDeclaration(sourceHeader) only; bytecode location and call-edge gates are separate',
              'expectedAnchors': len(rows), 'exactAnchors': sum(row['exactMatch'] for row in rows),
              'rows': rows, 'qualified': all(row['exactMatch'] for row in rows)}
    if baseline is not None:
        before = {node['usr']: node for node in baseline['nodes']}
        result['bytecodeLocationsUnchanged'] = nodes.keys() == before.keys() and all(
            node.get('location') == before[usr].get('location') for usr, node in nodes.items())
        result['qualified'] = result['qualified'] and result['bytecodeLocationsUnchanged']
    return result


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--graph', type=Path, required=True)
    parser.add_argument('--baseline-graph', type=Path)
    parser.add_argument('--output', type=Path, required=True)
    args = parser.parse_args()
    expected = json.loads((HERE / 'source-location-expectations.json').read_text())
    baseline = json.loads(args.baseline_graph.read_text()) if args.baseline_graph else None
    result = check(json.loads(args.graph.read_text()), expected, HERE / 'fixture', baseline)
    with args.output.open('x') as stream:
        json.dump(result, stream, indent=2, sort_keys=True)
        stream.write('\n')
    print(json.dumps({key: result[key] for key in ['expectedAnchors', 'exactAnchors', 'qualified']}))
    if not result['qualified']:
        raise SystemExit('Source-header anchor qualification failed; see preserved report')


if __name__ == '__main__':
    main()
