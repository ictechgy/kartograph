#!/usr/bin/env python3
"""실제 source set의 javap·Kotlin PSI를 대응한다. test resource 예제와 제품 graph는 oracle에 넣지 않는다."""
import argparse
import hashlib
import json
from pathlib import Path
import re
import subprocess

MODIFIERS = re.compile(r'^(?:(?:public|protected|private|final|static|abstract|synchronized|native|strictfp|default)\s+)+')


def name_after_type(text):
    text = MODIFIERS.sub('', text.lstrip())
    if text.startswith('<'):
        depth = 0
        for index, ch in enumerate(text):
            depth += (ch == '<') - (ch == '>')
            if depth == 0:
                return name_after_type(text[index + 1:])
    depth = 0
    for index, ch in enumerate(text):
        depth += (ch == '<') - (ch == '>')
        if ch == ' ' and depth == 0:
            return text[index + 1:]
    return text


def read_javap(text, owners):
    classes, current, member = [], None, None
    in_body = False
    for line in text.splitlines():
        if line.startswith('Classfile '):
            path = Path(line[len('Classfile '):]).resolve()
            if path not in owners:
                raise ValueError('javap class file is outside declared inputs')
            current = dict(owner=owners[path], source=None, members=[])
            classes.append(current); member = None; in_body = False
        elif current is not None:
            stripped = line.strip()
            if line == '{':
                in_body = True
            elif line == '}':
                in_body = False; member = None
            elif stripped.startswith('SourceFile: '):
                current['source'] = stripped.split('"')[1]
            elif in_body and line.startswith('  ') and not line.startswith('   ') and stripped.endswith(';'):
                if stripped == 'static {};':
                    name, kind = '<clinit>', 'method'
                elif '(' in stripped:
                    head = stripped[:stripped.rfind('(')].lstrip()
                    simple = MODIFIERS.sub('', head)
                    name = '<init>' if simple == current['owner'].replace('/', '.') else name_after_type(head)
                    kind = 'method'
                else:
                    name, kind = name_after_type(stripped[:-1]), 'field'
                member = dict(name=name, kind=kind, descriptor=None, lines=[], flags=[], calls=[])
                current['members'].append(member)
            elif member is not None:
                if stripped.startswith('descriptor: '):
                    member['descriptor'] = stripped[len('descriptor: '):]
                elif stripped.startswith('flags: '):
                    member['flags'] = re.findall(r'ACC_[A-Z_]+', stripped)
                elif (match := re.match(r'line (\d+): \d+$', stripped)):
                    member['lines'].append(int(match.group(1)))
                elif re.match(r'\d+:\s+invoke', stripped) and '// ' in stripped:
                    comment = stripped.split('// ', 1)[1]
                    if comment.startswith(('Method ', 'InterfaceMethod ')):
                        qualified, descriptor = comment.split(' ', 1)[1].rsplit(':', 1)
                        owner, separator, name = qualified.rpartition('.')
                        if not separator:
                            owner, name = current['owner'], qualified
                        name = name.strip('"')
                        member['calls'].append('method:' + owner + '#' + name + descriptor)
    for cls in classes:
        for m in cls['members']:
            if m['descriptor'] is None:
                raise ValueError('javap member lacks a descriptor')
            m['id'] = (f'method:{cls["owner"]}#{m["name"]}{m["descriptor"]}' if m['kind'] == 'method'
                       else f'field:{cls["owner"]}#{m["name"]}:{m["descriptor"]}')
    return classes


def quote_name(name):
    return name if re.fullmatch(r'[A-Za-z_][A-Za-z_0-9]*', name) else '`' + name + '`'


def source_aliases(function):
    owners = function['owners']
    name = quote_name(function['name'])
    source_owner = '.'.join(quote_name(x) for x in owners)
    package_owner = '.'.join(x for x in [function['package'], source_owner] if x)
    prefixes = {name, '.'.join(x for x in [source_owner, name] if x),
                '.'.join(x for x in [package_owner, name] if x)}
    if function['receiver']:
        prefixes |= {'.'.join(x for x in [source_owner, function['receiver'], name] if x),
                     '.'.join(x for x in [package_owner, function['receiver'], name] if x)}
    aliases = set(prefixes)
    for prefix in prefixes:
        for types in [function['types'], function['namedParameters']]:
            for separator in [',', ', ']:
                aliases.add(prefix + '(' + separator.join(types) + ')')
    return sorted(aliases)


def matches_source(member, fun, owner_names, module_suffixes, file):
    """생성 lambda·단축 overload를 원래 함수로 잘못 대응하지 않는다."""
    names_match = fun['name'] == member['name'] or any(
        member['name'] == fun['name'] + '$' + suffix for suffix in module_suffixes)
    owner_match = fun['owners'] == owner_names or not fun['owners'] and owner_names[-1].endswith('Kt')
    parameters = member['descriptor'].split(')', 1)[0][1:]
    arity = len(re.findall(r'\[*(?:L[^;]+;|[BCDFIJSZ])', parameters))
    expected = len(fun['types']) + bool(fun['receiver']) + bool(fun.get('suspend'))
    return (fun['file'] == file and not fun['local'] and names_match and owner_match and arity == expected and
            any(fun['startLine'] <= line <= fun['endLine'] for line in member['lines']))


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--project', type=Path, required=True, help='선택 모듈의 source root')
    parser.add_argument('--source-list', type=Path, required=True, help='Gradle Kotlin source set이 선언한 실제 파일 목록')
    parser.add_argument('--parser-config', type=Path, required=True)
    parser.add_argument('--jdk', type=Path, required=True)
    parser.add_argument('--output', type=Path, required=True)
    args = parser.parse_args()
    project = args.project.resolve(); out = args.output.resolve(); out.mkdir(parents=True, exist_ok=False)
    tracked = set(subprocess.check_output(['git', '-C', str(project), 'ls-files'], text=True).splitlines())
    declared = json.loads(args.source_list.read_text())
    if not isinstance(declared, list) or any(not isinstance(name, str) or name.startswith('/') or '..' in Path(name).parts for name in declared):
        raise ValueError('invalid declared source list')
    sources = sorted(name for name in declared if name in tracked and name.endswith('.kt') and (project / name).is_file())
    if not sources:
        raise ValueError('tracked compiler sources are required')
    (out / 'source-files.txt').write_text('\n'.join(sources) + '\n')
    config = json.loads(args.parser_config.read_text())
    cp = config['classes'] + ':' + config['classpath']
    with (out / 'source-declarations.jsonl').open('w') as output, (out / 'source-parser.err').open('w') as error:
        subprocess.run([str(args.jdk / 'bin/java'), '-cp', cp, 'SourceDeclarations', str(project),
                        str(out / 'source-files.txt')], stdout=output, stderr=error, text=True, check=True, timeout=120)
    functions = [json.loads(line) for line in (out / 'source-declarations.jsonl').read_text().splitlines()]
    roots = [project / name for name in ['build/classes/kotlin/test', 'build/classes/java/test',
                                        'build/classes/kotlin/main', 'build/classes/java/main']]
    module_suffixes = {re.sub(r'[^\w$]', '_', p.stem) for root in roots for p in root.glob('META-INF/*.kotlin_module')}
    by_owner = {}; duplicate_owners = []
    for root in roots:
        for path in sorted(root.rglob('*.class')):
            owner = path.relative_to(root).as_posix().removesuffix('.class')
            if owner in by_owner:
                duplicate_owners.append(owner)
            else:
                by_owner[owner] = path.resolve()
    if not by_owner:
        raise ValueError('no compiled class inputs')
    owners = {path: owner for owner, path in by_owner.items()}
    classes = []
    files = list(owners)
    for index in range(0, len(files), 80):
        result = subprocess.run([str(args.jdk / 'bin/javap'), '-v', '-p', '-s', '-c', '-l',
                                 *map(str, files[index:index + 80])], text=True, capture_output=True, timeout=120)
        (out / f'javap-{index // 80}.txt').write_text(result.stdout)
        (out / f'javap-{index // 80}.err').write_text(result.stderr)
        if result.returncode:
            raise ValueError('javap failed; inspect preserved logs')
        classes.extend(read_javap(result.stdout, owners))
    packages = {}
    for file in sources:
        content = (project / file).read_text()
        package = re.search(r'^package\s+([\w.]+)', content, re.M)
        packages[file] = package.group(1) if package else ''
    declarations, references, unmapped = [], [], []
    for cls in classes:
        package = cls['owner'].rsplit('/', 1)[0].replace('/', '.') if '/' in cls['owner'] else ''
        matches = [s for s in sources if Path(s).name == cls['source'] and packages[s] == package]
        if len(matches) != 1:
            unmapped.append(dict(owner=cls['owner'], sourceFile=cls['source'], candidates=matches))
        file = matches[0] if len(matches) == 1 else None
        owner_names = cls['owner'].split('/')[-1].split('$')
        for member in cls['members']:
            matched = []
            if member['kind'] == 'method' and 'ACC_SYNTHETIC' not in member['flags']:
                for fun in functions:
                    if matches_source(member, fun, owner_names, module_suffixes, file):
                        matched.append(fun)
            aliases = source_aliases(matched[0]) if len(matched) == 1 else []
            declarations.append(dict(id=member['id'], file=file, aliases=aliases, sourceAddressable=bool(aliases),
                                     sourceEvidence=matched[0] if len(matched) == 1 else None,
                                     flags=member['flags'], lines=sorted(set(member['lines']))))
            references.extend(dict(caller=member['id'], target=target) for target in sorted(set(member['calls'])))
    result = dict(format='kartograph-v5-independent-declarations', tool='JDK javap + Kotlin compiler PSI + declared source sets (no product graph)',
                  declarations=declarations, references=references, unmappedClassSources=unmapped,
                  shadowedOwners=sorted(set(duplicate_owners)),
                  declaredSourceListSha256=hashlib.sha256(args.source_list.read_bytes()).hexdigest(),
                  untrackedDeclaredSources=sorted(set(declared)-tracked),
                  sourceSha256={s: hashlib.sha256((project / s).read_bytes()).hexdigest() for s in sources},
                  classSha256={owner: hashlib.sha256(path.read_bytes()).hexdigest() for owner, path in by_owner.items()})
    (out / 'declarations.json').write_text(json.dumps(result, indent=2, ensure_ascii=False) + '\n')
    print(json.dumps(dict(classes=len(classes), declarations=len(declarations), references=len(references),
                         sourceAddressable=sum(d['sourceAddressable'] for d in declarations), unmappedClasses=len(unmapped))))


if __name__ == '__main__':
    main()
