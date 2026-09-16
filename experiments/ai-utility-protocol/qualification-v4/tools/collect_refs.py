#!/usr/bin/env python3
"""collect_direct_oracle.py를 확장해 method 호출과 field 참조를 함께 수집한다.

원본과 같은 javap 출력·같은 owner/method 파싱을 쓰고, 다음만 추가한다.
- getstatic/putstatic/getfield/putfield의 Field 참조도 대상으로 인식한다
  (Kotlin companion 상수처럼 바뀐 선언이 field인 사례 때문이다).
- 여러 class root를 --roots로 직접 받는다(멀티 모듈 Gradle 저장소 때문이다).
제품 graph는 보지 않는다.
"""
import argparse, json, re, subprocess, sys
from pathlib import Path

# 원본 파서를 그대로 쓰기 위해 저장소의 preflight-evaluation 디렉터리를 import 경로에 넣는다.
sys.path.insert(0, str(Path(__file__).resolve().parents[3] / 'preflight-evaluation'))
from collect_direct_oracle import method_name  # 원본 파서를 그대로 쓴다

INVOKE = re.compile(r'\binvoke(?:virtual|special|static|interface|dynamic)\b')
FIELD = re.compile(r'\b(?:get|put)(?:static|field)\b')


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument('--case', required=True)
    parser.add_argument('--base', type=Path, required=True)
    parser.add_argument('--roots', nargs='+', required=True, help='checkout-relative class roots')
    parser.add_argument('--targets', type=Path, required=True)
    parser.add_argument('--javap', required=True)
    parser.add_argument('--out', type=Path, required=True)
    args = parser.parse_args()

    base = args.base.resolve(strict=True)
    roots = [(base / name).resolve(strict=True) for name in args.roots]
    for path in roots:
        if not path.is_dir() or not path.is_relative_to(base):
            parser.error(f'class root outside the frozen checkout: {path}')
    targets = json.loads(args.targets.read_text())
    if not isinstance(targets, dict) or not targets:
        parser.error('targets must be a non-empty owner map')

    files = {}
    for root in roots:
        for path in sorted(root.rglob('*.class')):
            owner = path.relative_to(root).as_posix().removesuffix('.class')
            files.setdefault(owner, path)
    if not files:
        parser.error('class roots contain no compiled classes')

    tracked = subprocess.check_output(['git', '-C', str(base), 'ls-files', '-z']).decode().split('\0')
    sources = [name for name in tracked if name.endswith(('.java', '.kt'))]

    names_by_prefix = {}
    for owner in files:
        display = owner.replace('/', '.')
        names_by_prefix.setdefault(display.split(' ', 1)[0], []).append((display, owner))
    for values in names_by_prefix.values():
        values.sort(key=lambda item: len(item[0]), reverse=True)

    args.out.mkdir(parents=True, exist_ok=True)
    records = []
    items = list(files.values())
    for number, start in enumerate(range(0, len(items), 150)):
        output = subprocess.check_output([args.javap, '-c', '-p', '-s', *items[start:start + 150]],
                                         text=True, stderr=subprocess.DEVNULL, timeout=600)
        (args.out / f'javap-{number}.txt').write_text(output)
        owner = source = declaration = method = descriptor = None
        for line in output.splitlines():
            if line == '}':
                owner = source = declaration = method = descriptor = None
                continue
            if line.startswith('Compiled from '):
                source = line.split('"')[1]
            match = re.search(r'^(?:public |protected |private |final |abstract |static )*(?:class|interface|enum|record) (.+)', line)
            if match:
                display = match.group(1)
                prefix = re.split(r'[ <{]', display, maxsplit=1)[0]
                owner = next((internal for name, internal in names_by_prefix.get(prefix, [])
                              if display.startswith(name) and display[len(name):len(name) + 1] in (' ', '<', '{')), None)
                declaration = method = descriptor = None
            elif line.startswith('  ') and not line.startswith('    ') and ('(' in line or 'static {}' in line):
                declaration = line
                method = method_name(line, owner) if owner else None
            elif line.strip().startswith('descriptor:') and method:
                descriptor = line.strip().split('descriptor:', 1)[1].strip()
            elif ' // ' in line and owner and method and descriptor and (INVOKE.search(line) or FIELD.search(line)):
                comment = line.split(' // ', 1)[1]
                kind = ('field' if comment.startswith('Field ') else
                        'method' if comment.startswith(('Method ', 'InterfaceMethod ')) else None)
                if kind is None:
                    continue
                reference = re.sub(r'^(?:InterfaceMethod|Method|Field) ', '', comment)
                if ':' not in reference:
                    continue
                left, desc = reference.split(':', 1)
                target_owner, target_name = left.rsplit('.', 1) if '.' in left else (owner, left)
                target_name = target_name.strip('"')
                accepted = targets.get(target_owner, 'absent')
                if accepted == 'absent':
                    continue
                if accepted is not None and f'{target_name}:{desc}' not in accepted:
                    continue
                package = owner.rsplit('/', 1)[0] if '/' in owner else ''
                suffix = f'{package}/{source}' if source else ''
                matches = [name for name in sources if name.endswith('/' + suffix) or name == suffix]
                records.append({'caller': f'method:{owner}#{method}{descriptor}',
                                'target': ('method:' if kind == 'method' else 'field:') + f'{target_owner}#{target_name}' + (desc if kind == 'method' else ':' + desc),
                                'refKind': kind + ('-write' if kind == 'field' and re.search(r'\bput(?:static|field)\b', line) else ''),
                                'sourceCandidates': matches,
                                'javapFile': f'javap-{number}.txt', 'instruction': line.strip()})
    unique = {json.dumps(row, sort_keys=True): row for row in records}
    result = {'case': args.case,
              'scope': 'Direct invocation and field references from javap; not a complete transitive or dynamic-impact oracle.',
              'classCount': len(files), 'roots': [str(path.relative_to(base)) for path in roots],
              'references': list(unique.values())}
    (args.out / 'direct-invocations.json').write_text(json.dumps(result, indent=2) + '\n')
    print(args.case, 'classes', len(files), 'direct references', len(unique))


if __name__ == '__main__':
    main()
