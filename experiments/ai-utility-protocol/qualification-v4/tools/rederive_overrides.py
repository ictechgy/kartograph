#!/usr/bin/env python3
"""원본 선언만으로 override 지점을 다시 유도한다. 제품 출력을 읽지 않는다.

규칙: 바뀐 메서드 심볼 중 private/static/final이 아닌 것에 대해, checkout의 class root 안에서
소유 타입의 상위·하위 타입을 전이적으로 모으고 같은 name+descriptor를 선언하는 타입을 모두 고른다.
"""
import argparse, json, re, subprocess, sys
from pathlib import Path

HEADER = re.compile(r'^(?P<mods>(?:public |protected |private |final |abstract |static |strictfp )*)'
                    r'(?P<kind>class|interface|enum|record) (?P<name>[\w.$]+)'
                    r'(?:<[^>]*>)?(?: extends (?P<ext>[\w.$<>, ]+?))?(?: implements (?P<impl>[\w.$<>, ]+?))? *\{?$')


def javap(tool, paths):
    return subprocess.check_output([tool, '-p', '-s', *paths], text=True, stderr=subprocess.DEVNULL, timeout=600)


def strip_generics(value):
    depth, out = 0, []
    for char in value:
        if char == '<': depth += 1
        elif char == '>': depth -= 1
        elif depth == 0: out.append(char)
    return ''.join(out)


def parse(text):
    """returns {binaryName: {'supers': [...], 'methods': {(name, descriptor): flags}}}"""
    types, current, pending = {}, None, None
    for line in text.splitlines():
        match = HEADER.match(line.strip())
        if match and not line.startswith('    '):
            name = match.group('name')
            supers = []
            for group in ('ext', 'impl'):
                if match.group(group):
                    supers += [item.strip() for item in strip_generics(match.group(group)).split(',') if item.strip()]
            current = types.setdefault(name, {'supers': supers, 'methods': {}})
            current['supers'] = supers
            pending = None
            continue
        if current is None:
            continue
        stripped = line.strip()
        if stripped.startswith('descriptor:') and pending:
            current['methods'][(pending[0], stripped.split('descriptor:', 1)[1].strip())] = pending[1]
            pending = None
        elif line.startswith('  ') and not line.startswith('    ') and stripped.startswith('static {}'):
            pending = ('<clinit>', {'static'})
        elif line.startswith('  ') and not line.startswith('    ') and '(' in stripped:
            body = stripped.removesuffix(';').split(' throws ', 1)[0]
            flags = set(re.findall(r'\b(public|protected|private|static|final|abstract|synchronized|native|default)\b',
                                   body[:body.find('(')]))
            head = re.sub(r'^(?:(?:public|protected|private|final|static|synchronized|abstract|native|strictfp|default)\s+)+',
                          '', body[:body.find('(')])
            head = strip_generics(head).strip()
            name = head.rsplit(' ', 1)[-1] if ' ' in head else head
            if name == current_name_of(types, current): name = '<init>'
            pending = (name, flags)
    return types


def current_name_of(types, current):
    for name, value in types.items():
        if value is current: return name
    return None


def relatives(types, root):
    up, stack = set(), [root]
    while stack:
        name = stack.pop()
        for parent in types.get(name, {}).get('supers', []):
            if parent not in up:
                up.add(parent); stack.append(parent)
    down = {name for name in types if root in closure(types, name)}
    return (up | down) - {root}


def closure(types, name):
    seen, stack = set(), [name]
    while stack:
        current = stack.pop()
        for parent in types.get(current, {}).get('supers', []):
            if parent not in seen:
                seen.add(parent); stack.append(parent)
    return seen


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument('--case', required=True)
    parser.add_argument('--base', type=Path, required=True)
    parser.add_argument('--roots-file', type=Path, required=True)
    parser.add_argument('--changed', type=Path, required=True)
    parser.add_argument('--javap', required=True)
    args = parser.parse_args()

    base = args.base.resolve(strict=True)
    roots = [(base / line.strip()).resolve(strict=True) for line in args.roots_file.read_text().splitlines() if line.strip()]
    files = {}
    for root in roots:
        for path in sorted(root.rglob('*.class')):
            files.setdefault(path.relative_to(root).as_posix().removesuffix('.class'), path)
    items = list(files.values())
    types = {}
    for start in range(0, len(items), 150):
        types.update(parse(javap(args.javap, items[start:start + 150])))

    findings = []
    for symbol in (line.strip() for line in args.changed.read_text().splitlines() if line.strip()):
        if not symbol.startswith('method:'):
            findings.append({'changed': symbol, 'overridable': False, 'reason': 'field 선언은 override 대상이 아니다', 'sites': []})
            continue
        owner, rest = symbol[len('method:'):].split('#', 1)
        match = re.match(r'^(.*)(\([^()]*\).+)$', rest)
        name, descriptor = match.group(1), match.group(2)
        display = owner.replace('/', '.')
        if name in ('<init>', '<clinit>'):
            findings.append({'changed': symbol, 'overridable': False,
                             'reason': f'{name}은 JVM이 암묵 호출하는 초기화자라 override 지점이 없다', 'sites': []})
            continue
        flags = types.get(display, {}).get('methods', {}).get((name, descriptor))
        if flags is None:
            findings.append({'changed': symbol, 'overridable': False, 'reason': f'{display}에서 선언을 찾지 못했다', 'sites': []})
            continue
        blockers = sorted(flags & {'private', 'static', 'final'})
        if blockers or name in ('<init>', '<clinit>'):
            findings.append({'changed': symbol, 'overridable': False,
                             'reason': f'선언 flag {sorted(flags)} — {blockers or [name]} 때문에 override 지점이 없다', 'sites': []})
            continue
        sites = sorted(f'method:{other.replace(".", "/")}#{name}{descriptor}'
                       for other in relatives(types, display)
                       if (name, descriptor) in types.get(other, {}).get('methods', {}))
        findings.append({'changed': symbol, 'overridable': True, 'reason': f'선언 flag {sorted(flags)}',
                         'relatedTypesScanned': len(relatives(types, display)), 'sites': sites})
    print(json.dumps({'case': args.case, 'classCount': len(files), 'typeCount': len(types),
                      'findings': findings}, indent=2, ensure_ascii=False))


if __name__ == '__main__':
    main()
