#!/usr/bin/env python3
"""고정 source 기대값으로 PSI 선언 출력의 양방향 집합과 위치를 검증한다."""
import argparse
import json
from pathlib import Path

EXPECTED = {
    ('probe/PsiJava.java', 'probe.PsiJava', 'leaf', ('int',)): (4, 23),
    ('probe/PsiJava.java', 'probe.PsiJava', 'leaf', ('String',)): (5, 26),
    ('probe/PsiJava.java', 'probe.PsiJava', 'caller', ()): (6, 26),
    ('probe/PsiJava.java', 'probe.PsiJava', 'unused', ()): (7, 23),
    ('probe/PsiKotlin.kt', 'probe.PsiKotlin', 'leaf', ('Int',)): (4, 9),
    ('probe/PsiKotlin.kt', 'probe.PsiKotlin', 'leaf', ('String',)): (5, 9),
    ('probe/PsiKotlin.kt', 'probe.PsiKotlin', 'caller', ()): (6, 9),
    ('probe/PsiKotlin.kt', 'probe.PsiKotlin', 'unused', ()): (7, 9),
}


def check(source_root, rows):
    observed = {}
    for row in rows:
        key = (row['file'], row['owner'], row['name'], tuple(row['parameterTypeText']))
        if key in observed:
            raise ValueError('Duplicate declaration: ' + repr(key))
        observed[key] = (row['nameLine'], row['nameColumnUtf16'])
        if row['semanticResolution'] is not False:
            raise ValueError('Syntax milestone must not claim semantic resolution')
        text = (source_root / row['file']).read_text()
        utf16 = text.encode('utf-16-le')
        start, end, name = row['declarationStartUtf16'], row['declarationEndUtf16Exclusive'], row['nameStartUtf16']
        if not 0 <= start <= name < end <= len(utf16) // 2:
            raise ValueError('Declaration offsets out of bounds')
        length = len(row['name'].encode('utf-16-le'))
        if utf16[name * 2:name * 2 + length].decode('utf-16-le') != row['name']:
            raise ValueError('Declaration name does not match source offset')
    if observed != EXPECTED:
        raise ValueError(f'Declaration/location set differs: expected {EXPECTED!r}, actual {observed!r}')
    return {'status': 'passed', 'explicitSourceMethods': len(rows), 'exactNameLocations': len(rows),
            'scope': 'two authored source files; no call resolution or graph-quality claim'}


if __name__ == '__main__':
    parser = argparse.ArgumentParser()
    parser.add_argument('source_root', type=Path)
    parser.add_argument('jsonl', type=Path)
    args = parser.parse_args()
    print(json.dumps(check(args.source_root, [json.loads(line) for line in args.jsonl.read_text().splitlines()])))
