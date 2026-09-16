#!/usr/bin/env python3
"""제품 그래프를 보지 않고 javap의 직접 호출과 기존 테스트 파일을 기록한다."""
import argparse
import json
from pathlib import Path
import re
import shutil
import subprocess

TARGETS = {
 'google__gson-1093': {'com/google/gson/stream/JsonWriter': {'value:(D)Lcom/google/gson/stream/JsonWriter;'}},
 'fasterxml__jackson-core-1172': {'com/fasterxml/jackson/core/JsonPointer': {
     'append:(Lcom/fasterxml/jackson/core/JsonPointer;)Lcom/fasterxml/jackson/core/JsonPointer;',
     'appendProperty:(Ljava/lang/String;)Lcom/fasterxml/jackson/core/JsonPointer;',
     'appendIndex:(I)Lcom/fasterxml/jackson/core/JsonPointer;'}},
 'detekt_detekt-7715': {'io/gitlab/arturbosch/detekt/rules/exceptions/ThrowingExceptionsWithoutMessageOrCause': None},
 'pinterest_ktlint-2891': {'com/pinterest/ktlint/ruleset/standard/rules/UnnecessaryParenthesesBeforeTrailingLambdaRule': None},
}
OLD = {'google__gson-1093':'google__gson-1391','fasterxml__jackson-core-1172':'fasterxml__jackson-core-1208',
       'detekt_detekt-7715':'detekt_detekt-7718','pinterest_ktlint-2891':'pinterest_ktlint-2895'}


def method_name(declaration, owner):
    value = declaration.strip().removesuffix(';').split(' throws ', 1)[0]
    if value == 'static {}': return '<clinit>'
    if '(' not in value: return None
    value = value[:value.rfind('(')]
    value = re.sub(r'^(?:(?:public|protected|private|final|static|synchronized|abstract|native|strictfp|default)\s+)+', '', value)
    if value == owner.replace('/', '.'): return '<init>'
    depth = 0
    for index, char in enumerate(value):
        if char == '<': depth += 1
        elif char == '>': depth -= 1
        elif char == ' ' and depth == 0:
            remainder = value[index + 1:]
            if value.startswith('<'): return method_name(remainder+'()', owner)
            return remainder
    return None


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument('--case', required=True)
    parser.add_argument('--directory', type=Path, required=True)
    parser.add_argument('--inputs', type=Path, help='Native input inventory with classRoots; paths may be checkout-relative')
    parser.add_argument('--targets', type=Path, help='Owner to method:descriptor arrays; null selects all direct owner references')
    parser.add_argument('--javap', default=shutil.which('javap'), help='JDK javap executable')
    args = parser.parse_args()
    base = args.directory.resolve() / 'base'
    if args.inputs:
        inventory = json.loads(args.inputs.read_text())
        roots = [(base / name).resolve(strict=True) for name in inventory['classRoots']]
        if any(not path.is_dir() or not path.is_relative_to(base) for path in roots):
            parser.error('class roots must be directories inside the frozen checkout')
    else:
        if args.case not in OLD:
            parser.error('new cases require --inputs')
        old = json.loads((Path('build/reports/expanded-evaluation-20260913/trial-specs-v2-nativefix') / (OLD[args.case]+'.json')).read_text())
        roots = [base / name for name in old['classRoots'] if '/classes/' in name or name.endswith('/classes') or name.endswith('/test-classes')]
        roots = [path for path in roots if path.is_dir()]
    targets = json.loads(args.targets.read_text()) if args.targets else TARGETS.get(args.case)
    if (not isinstance(targets, dict) or not targets or
            any(not isinstance(owner, str) or not owner or methods is not None and
                (not isinstance(methods, (list, set)) or not methods or any(not isinstance(method, str) or ':' not in method for method in methods))
                for owner, methods in targets.items())):
        parser.error('provide valid --targets before collecting a new case')
    if not args.javap:
        parser.error('a JDK javap executable is required')
    files = {}
    for root in roots:
        for path in sorted(root.rglob('*.class')):
            owner = path.relative_to(root).as_posix().removesuffix('.class')
            if owner not in files: files[owner] = path
    if not files:
        parser.error('class roots must contain compiled classes')
    tracked = subprocess.check_output(['git','-C',str(base),'ls-files','-z']).decode().split('\0')
    sources = [name for name in tracked if name.endswith(('.java','.kt'))]
    javap = Path(args.javap)
    names_by_prefix = {}
    for owner in files:
        display = owner.replace('/', '.')
        names_by_prefix.setdefault(display.split(' ', 1)[0], []).append((display, owner))
    for values in names_by_prefix.values(): values.sort(key=lambda item: len(item[0]), reverse=True)
    records = []
    items = list(files.values())
    for number, start in enumerate(range(0, len(items), 150)):
        output = subprocess.check_output([javap,'-c','-p','-s',*items[start:start+150]],text=True,stderr=subprocess.DEVNULL,timeout=90)
        (args.directory / 'oracle' / f'javap-{number}.txt').write_text(output)
        owner = None; source = None; declaration = None; method = None; descriptor = None
        for line in output.splitlines():
            if line == '}':
                owner = source = declaration = method = descriptor = None
                continue
            if line.startswith('Compiled from '): source = line.split('"')[1]
            match = re.search(r'^(?:public |protected |private |final |abstract |static )*(?:class|interface|enum) (.+)',line)
            if match:
                display = match.group(1)
                prefix = re.split(r'[ <{]', display, maxsplit=1)[0]
                owner = next((internal for name, internal in names_by_prefix.get(prefix, []) if display.startswith(name) and display[len(name):len(name)+1] in (' ', '<', '{')), None)
                declaration = method = descriptor = None
            elif line.startswith('  ') and not line.startswith('    ') and ('(' in line or 'static {}' in line):
                declaration = line;method = method_name(line,owner) if owner else None
            elif line.strip().startswith('descriptor:') and method:
                descriptor = line.strip().split('descriptor:',1)[1].strip()
            elif re.search(r'\binvoke(?:virtual|special|static|interface)\b',line) and ' // ' in line and owner and method and descriptor:
                reference = re.sub(r'^(?:InterfaceMethod|Method) ', '', line.split(' // ',1)[1])
                if ':' not in reference: continue
                left, desc = reference.split(':',1)
                target_owner, target_name = left.rsplit('.',1) if '.' in left else (owner,left)
                target_name = target_name.strip('"')
                accepted = targets.get(target_owner, 'absent')
                if accepted == 'absent' or accepted is not None and target_name+':'+desc not in accepted: continue
                package = owner.rsplit('/',1)[0] if '/' in owner else ''
                suffix = package+'/'+source if source else ''
                matches = [name for name in sources if name.endswith('/'+suffix) or name==suffix]
                records.append({'caller':'method:'+owner+'#'+method+descriptor,
                    'target':'method:'+target_owner+'#'+target_name+desc,'sourceCandidates':matches,
                    'javapFile':f'javap-{number}.txt','instruction':line.strip()})
    unique = {json.dumps(row,sort_keys=True):row for row in records}
    result = {'case':args.case,'scope':'Direct invocation references from javap; not a complete transitive or dynamic-impact oracle.',
              'classCount':len(files),'roots':[str(path.relative_to(base)) for path in roots], 'references':list(unique.values())}
    (args.directory/'oracle/direct-invocations.json').write_text(json.dumps(result,indent=2)+'\n')
    print(args.case,'classes',len(files),'direct references',len(unique),'source files',sorted({p for row in records for p in row['sourceCandidates']}))


if __name__ == '__main__': main()
