#!/usr/bin/env python3
"""javap 증거만으로 depth-1 직접 참조와 depth-2 간접 참조를 수집한다.

3단계 규칙: depth-1 호출자 **전부**를 확장한다. 선택도 상한도 두지 않는다.
제품 graph는 보지 않는다.
"""
import argparse, json, re, subprocess, sys, time
from pathlib import Path

COLLECT = Path(__file__).with_name('collect_refs.py')
DESC = re.compile(r'^\((?:\[*(?:[BCDFIJSZ]|L[^;]+;))*\)(?:\[*(?:[BCDFIJSZV]|L[^;]+;))$')


def split_method(symbol):
    body = symbol[len('method:'):]
    owner, rest = body.split('#', 1)
    match = re.match(r'^(.*)(\([^()]*\).+)$', rest)
    assert match, rest
    name, descriptor = match.group(1), match.group(2)
    assert DESC.match(descriptor), (rest, descriptor)
    return owner, f'{name}:{descriptor}'


def split_field(symbol):
    body = symbol[len('field:'):]
    owner, rest = body.split('#', 1)
    name, descriptor = rest.rsplit(':', 1)
    return owner, f'{name}:{descriptor}'


def to_targets(symbols):
    targets = {}
    for symbol in symbols:
        owner, member = split_method(symbol) if symbol.startswith('method:') else split_field(symbol)
        targets.setdefault(owner, set()).add(member)
    return {owner: sorted(members) for owner, members in targets.items()}


def run(case, base, roots, targets, javap, out, tag):
    path = out / f'targets-{tag}.json'
    out.mkdir(parents=True, exist_ok=True)
    path.write_text(json.dumps(targets, indent=2, sort_keys=True) + '\n')
    started = time.time()
    proc = subprocess.run([sys.executable, str(COLLECT), '--case', case, '--base', str(base),
                           '--roots', *roots, '--targets', str(path), '--javap', javap,
                           '--out', str(out / tag)], capture_output=True, text=True)
    if proc.returncode != 0:
        print(proc.stdout); print(proc.stderr, file=sys.stderr)
        raise SystemExit(f'collect failed: {case} {tag}')
    print(f'[{tag}] {proc.stdout.strip()} ({time.time() - started:.1f}s)')
    return json.loads((out / tag / 'direct-invocations.json').read_text())


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument('--case', required=True)
    parser.add_argument('--base', type=Path, required=True)
    parser.add_argument('--roots-file', type=Path, required=True)
    parser.add_argument('--changed', type=Path, required=True)
    parser.add_argument('--javap', required=True)
    parser.add_argument('--out', type=Path, required=True)
    args = parser.parse_args()

    roots = [line.strip() for line in args.roots_file.read_text().splitlines() if line.strip()]
    changed = [line.strip() for line in args.changed.read_text().splitlines() if line.strip()]
    depth1 = run(args.case, args.base, roots, to_targets(changed), args.javap, args.out, 'depth1')
    changed_set = set(changed)
    callers1 = sorted({row['caller'] for row in depth1['references']} - changed_set)
    depth2 = run(args.case, args.base, roots, to_targets(callers1), args.javap, args.out, 'depth2') \
        if callers1 else {'references': [], 'classCount': depth1['classCount'], 'roots': depth1['roots']}
    callers2 = sorted({row['caller'] for row in depth2['references']})
    direct_set = set(callers1)
    indirect_new = [symbol for symbol in callers2 if symbol not in changed_set and symbol not in direct_set]
    summary = {'case': args.case, 'changedSymbols': changed, 'classCount': depth1['classCount'],
               'roots': depth1['roots'], 'depth1Callers': callers1, 'depth2Callers': callers2,
               'indirectNewTargets': indirect_new,
               'counts': {'depth1': len(callers1), 'depth2All': len(callers2), 'indirectNew': len(indirect_new)}}
    (args.out / 'depth-summary.json').write_text(json.dumps(summary, indent=2) + '\n')
    print('depth1', len(callers1), 'depth2All', len(callers2), 'indirectNew', len(indirect_new))


if __name__ == '__main__':
    main()
