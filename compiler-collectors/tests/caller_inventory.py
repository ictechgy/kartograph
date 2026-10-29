"""고정한 전체 source/backend oracle을 producer header reader와 독립적인 javap으로 검증한다."""
import argparse
import base64
import contextlib
import hashlib
import json
import os
import pathlib
import re
import shutil
import subprocess
import tempfile

HERE = pathlib.Path(__file__).resolve().parent

def sha(path):
    return hashlib.sha256(path.read_bytes()).hexdigest()

def dec(value):
    return base64.urlsafe_b64decode(value + '=' * (-len(value) % 4)).decode()

def rows(path):
    return [line.split('\t') for line in path.read_text().splitlines()]

def files(root):
    return {str(p.relative_to(root)): sha(p) for p in sorted(root.rglob('*')) if p.is_file()}

def javap_methods(javap, path):
    result = subprocess.run([javap, '-v', '-p', '-s', str(path)], check=True, stdout=subprocess.PIPE, text=True).stdout
    owner = re.search(r'^\s*this_class:\s+#[0-9]+\s+//\s+(\S+)\s*$', result, re.M).group(1)
    count = int(re.search(r'interfaces:\s*[0-9]+, fields:\s*[0-9]+, methods:\s*([0-9]+)', result).group(1))
    selected = {}
    declaration = None
    current = None
    access = None
    body = result.split('\n{\n', 1)[1].split('\n}', 1)[0]
    for line in body.splitlines():
        if line.startswith('  ') and not line.startswith('   ') and line.strip():
            declaration = line.strip()
            current = None
            access = None
        elif line.startswith('    descriptor: ('):
            descriptor = line.split(': ', 1)[1]
            if declaration == 'static {};':
                name = '<clinit>'
            else:
                name = re.search(r'([^\s]+)\(', declaration).group(1)
                if name == owner.replace('/', '.'):
                    name = '<init>'
            current = 'method:' + owner + '#' + name + descriptor
        elif current and line.startswith('    flags:'):
            access = int(re.search(r'\(0x([0-9a-fA-F]+)\)', line).group(1), 16)
            assert current not in selected
            selected[current] = [access, False]
        elif current and line == '    Code:':
            selected[current][1] = True
    assert len(selected) == count, (path, count, selected)
    return selected

def verify(oracle, language, root, output, evidence, javap, source_names):
    inventory = rows(evidence / 'compiler-references-callers.tsv')
    assert inventory[0] == ['format', 'kartograph-source-caller-inventory', '1']
    actual_sources = {dec(row[1]): row[2] for row in inventory if row[0] == 'source'}
    assert actual_sources == {name: sha(root / name) for name in source_names}
    body_counts = {dec(row[1]): int(row[2]) for row in inventory if row[0] == 'body-count'}
    assert body_counts[language + '/NoClass.' + ('java' if language == 'java' else 'kt')] == 0
    assert body_counts[language + '/NoMethods.' + ('java' if language == 'java' else 'kt')] == 0
    bodies = {row[1]: row for row in inventory if row[0] == 'body'}
    assert {name: sum(dec(row[2]) == name for row in bodies.values()) for name in actual_sources} == body_counts
    outputs = {dec(row[1]): row[2] for row in inventory if row[0] == 'output'}
    assert outputs == files(output)
    backend = {dec(row[1]): [int(row[2]), row[3] == 'true'] for row in inventory if row[0] == 'backend'}
    independent = {}
    for path in output.rglob('*.class'):
        observed = javap_methods(javap, path)
        assert not independent.keys() & observed.keys()
        independent.update(observed)
    assert backend == independent
    associations = {}
    for row in inventory:
        if row[0] == 'body-method':
            associations.setdefault(row[1], []).append(dec(row[2]))
    reverse = {}
    for key, methods in associations.items():
        for method in methods:
            reverse.setdefault(method, []).append(key)
    backend_rows = {dec(row[1]): row for row in inventory if row[0] == 'backend'}
    for identity, row in backend_rows.items():
        links = reverse.get(identity, [])
        assert row[4:6] == (['SOURCE_BOUND', links[0]] if len(links) == 1 else ['UNKNOWN', '-'])
    expected = oracle[language]
    for identity in expected['requiredUnknownBackend']:
        assert backend_rows['method:inventory/' + identity][4] == 'UNKNOWN'
    for identity in expected['positiveNoBody']:
        method = 'method:inventory/' + identity
        body = bodies[backend_rows[method][5]]
        assert body[5:7] == ['ABSTRACT_METHOD', 'false']
        assert backend[method][1] is False and backend[method][0] & 0x0400
    for identity in expected.get('unsupportedNoBody', []):
        method = 'method:inventory/' + identity
        body = bodies[backend_rows[method][5]]
        assert body[5:7] == ['NATIVE_METHOD', 'false']
        assert backend[method][1] is False and backend[method][0] & 0x0100
    for identity, row in backend_rows.items():
        if '#<init>(' in identity and row[4] == 'SOURCE_BOUND':
            assert bodies[row[5]][5] in ['CONSTRUCTOR', 'IMPLICIT_CONSTRUCTOR']
    authored = rows(evidence / 'compiler-references-authored.tsv')
    callers = {dec(row[1]): row for row in authored if row[0] == 'caller'}
    ordinary = {method for key, body in bodies.items() if body[5] == 'ORDINARY_METHOD' for method in associations.get(key, [])}
    generated = 'method:inventory/generated/' + ('GeneratedInput#empty()V' if language == 'java' else 'GeneratedKotlinInput#empty()V')
    expected_ordinary = {'method:inventory/' + identity for identity in expected['completeOrdinary'] + expected['incompleteOrdinary']} | {generated}
    assert ordinary == expected_ordinary, (ordinary, expected_ordinary)
    for identity in expected['completeOrdinary']:
        assert callers['method:inventory/' + identity][5] == 'COMPLETE'
    for identity in expected['incompleteOrdinary']:
        assert callers['method:inventory/' + identity][5] == 'INCOMPLETE'
    for identity in expected['completeZero']:
        assert callers['method:inventory/' + identity][5:8:2] == ['COMPLETE', '0']
    assert callers[generated][5:8:2] == ['COMPLETE', '0']
    count = lambda kind, origin=None: sum(body[5] == kind and (origin is None or dec(body[7]) == origin) for body in bodies.values())
    if language == 'java':
        assert count('CONSTRUCTOR') == 4
        assert count('PROPERTY_INITIALIZER') == 2
        assert count('STATIC_INITIALIZER') == count('INSTANCE_INITIALIZER') == count('LAMBDA') == 1
        assert count('LOCAL_METHOD') == count('ANONYMOUS_METHOD') == 1
    else:
        assert count('PARAMETER_DEFAULT') == count('INSTANCE_INITIALIZER') == count('LAMBDA') == count('LOCAL_FUNCTION') == count('ANONYMOUS_METHOD') == 1
        assert count('PROPERTY_INITIALIZER') == 4
        assert count('ACCESSOR', 'FIR:Source:KtRealSourceElementKind') == 2
    return {'sources': len(actual_sources), 'sourceBodies': len(bodies), 'outputs': len(outputs), 'methods': len(backend),
            'unknownMethods': sum(row[4] == 'UNKNOWN' for row in backend_rows.values())}

def main():
    parser = argparse.ArgumentParser()
    parser.add_argument('--collector', type=pathlib.Path, required=True)
    parser.add_argument('--compiler-classpath', required=True)
    parser.add_argument('--work-dir', type=pathlib.Path)
    args = parser.parse_args()
    collector = args.collector.resolve()
    java_home = pathlib.Path(os.environ['JAVA_HOME'])
    java, javac, javap = [str(java_home / 'bin' / name) for name in ['java', 'javac', 'javap']]
    stdlib = next(path for path in args.compiler_classpath.split(os.pathsep) if 'kotlin-stdlib' in path and path.endswith('.jar'))
    oracle = json.loads((HERE / 'inventory_oracle_v1.json').read_text())
    result = {}
    if args.work_dir:
        args.work_dir.mkdir(parents=True, exist_ok=False)
    with (contextlib.nullcontext(str(args.work_dir)) if args.work_dir else tempfile.TemporaryDirectory(prefix='kartograph-caller-inventory-')) as temporary:
        root = pathlib.Path(temporary)
        shutil.copytree(HERE / 'fixtures/inventory', root, dirs_exist_ok=True)
        for name, fingerprint in oracle['sourceSha256'].items():
            assert sha(root / name) == fingerprint
        token = root / 'token'; token.write_text('e' * 64)
        for language in ['java', 'kotlin']:
            extension = 'java' if language == 'java' else 'kt'
            source_names = sorted(str(path.relative_to(root)) for path in (root / language).glob('*.' + extension)) + ['generated/GeneratedInput.' + extension]
            def command(output, evidence, inventory=True):
                output.mkdir(exist_ok=True); evidence.mkdir(exist_ok=True)
                options = [f'root={root}', f'output={evidence}', f'token={token}', 'authoredCalls=true', 'callPositions=true']
                if inventory:
                    options += ['callerInventory=true', f'classOutput={output}']
                if language == 'java':
                    return [javac, '-proc:none', '-encoding', 'UTF-8', '-classpath', str(collector), '-Xplugin:KartographEvidence ' + ' '.join(options),
                            '-d', str(output), *[str(root / name) for name in source_names]]
                return [java, '-cp', args.compiler_classpath, 'org.jetbrains.kotlin.cli.jvm.K2JVMCompiler', '-no-stdlib', '-no-reflect', '-jvm-target', '17',
                        '-classpath', stdlib, '-Xplugin=' + str(collector), *[value for option in options for value in ['-P', 'plugin:kartograph.compiler-evidence:' + option]],
                        '-d', str(output), *[str(root / name) for name in source_names]]
            output, evidence = root / (language + '-classes'), root / (language + '-evidence')
            subprocess.run(command(output, evidence), check=True)
            result[language] = verify(oracle, language, root, output, evidence, javap, source_names)
            baseline, old = root / (language + '-baseline'), root / (language + '-old-evidence')
            subprocess.run(command(baseline, old, False), check=True)
            assert files(output) == files(baseline)
            for name in ['compiler-references.tsv', 'compiler-references-authored.tsv']:
                assert (evidence / name).read_bytes() == (old / name).read_bytes()
            rejected = root / (language + '-rejected')
            before = files(output)
            failed = subprocess.run(command(output, rejected), stdout=subprocess.PIPE, stderr=subprocess.STDOUT)
            (root / (language + '-nonfresh.log')).write_bytes(failed.stdout)
            assert failed.returncode != 0 and not list(rejected.glob('*.tsv'))
            assert before == files(output)
            # source 삭제 뒤 남은 출력도 같은 fresh-output 계약으로 거부한다.
            removed = root / source_names[-1]; original = removed.read_bytes(); removed.unlink()
            try:
                failed = subprocess.run(command(output, rejected), stdout=subprocess.PIPE, stderr=subprocess.STDOUT)
                assert failed.returncode != 0 and not list(rejected.glob('*.tsv'))
            finally:
                removed.write_bytes(original)
            full_names = source_names
            source_names = [language + '/NoClass.' + extension, language + '/NoMethods.' + extension]
            empty_output, empty_evidence = root / (language + '-empty-classes'), root / (language + '-empty-evidence')
            subprocess.run(command(empty_output, empty_evidence), check=True)
            empty = rows(empty_evidence / 'compiler-references-callers.tsv')
            assert {dec(row[1]) for row in empty if row[0] == 'source'} == set(source_names)
            assert not [row for row in empty if row[0] in ['body', 'body-method', 'backend']]
            assert {int(row[2]) for row in empty if row[0] == 'body-count'} == {0}
            assert len(list(empty_output.rglob('*.class'))) == 1
            source_names = [language + '/NoClass.' + extension]
            no_output, no_evidence = root / (language + '-no-output-classes'), root / (language + '-no-output-evidence')
            no_class = subprocess.run(command(no_output, no_evidence), stdout=subprocess.PIPE, stderr=subprocess.STDOUT)
            (root / (language + '-no-output.log')).write_bytes(no_class.stdout)
            assert not list(no_evidence.glob('*.tsv'))
            source_names = full_names
            invalid = root / source_names[-1]; original = invalid.read_bytes()
            invalid.write_text('this source is syntactically invalid')
            try:
                failed_output, failed_evidence = root / (language + '-failed-classes'), root / (language + '-failed-evidence')
                failed = subprocess.run(command(failed_output, failed_evidence), stdout=subprocess.PIPE, stderr=subprocess.STDOUT)
                (root / (language + '-compiler-failure.log')).write_bytes(failed.stdout)
                assert failed.returncode != 0 and not list(failed_evidence.glob('*.tsv'))
            finally:
                invalid.write_bytes(original)
        def java_control(name, sources, extra=()):
            output, evidence = root / (name + '-classes'), root / (name + '-evidence')
            output.mkdir(); evidence.mkdir()
            options = f'root={root} output={evidence} token={token} authoredCalls=true callerInventory=true classOutput={output}'
            cmd = [javac, '-proc:none', '-encoding', 'UTF-8', '-classpath', str(collector),
                   '-Xplugin:KartographEvidence ' + options, '-d', str(output), *extra, *[str(root / path) for path in sources]]
            run = subprocess.run(cmd, stdout=subprocess.PIPE, stderr=subprocess.STDOUT)
            (root / (name + '.log')).write_bytes(run.stdout)
            return run, output, evidence
        header_controls = root / 'header-controls'; header_controls.mkdir()
        subprocess.run([javac, '-proc:none', '-classpath', str(collector), '-d', str(header_controls),
                        str(HERE / 'CallerOutputHeadersControl.java')], check=True)
        subprocess.run([java, '-cp', str(header_controls) + os.pathsep + str(collector),
                        'dev.kartograph.collectors.CallerOutputHeadersControl',
                        str(root / 'java-classes/inventory/JavaBodies.class'), 'inventory/JavaBodies.class', str(header_controls / 'fresh')], check=True)
        control = oracle['implicitSourceControl']
        run, output, evidence = java_control('implicit', control['named'], ['-sourcepath', str(root)])
        inventory = evidence / 'compiler-references-callers.tsv'
        if inventory.exists():
            assert run.returncode == 0
            observed = {dec(row[1]) for row in rows(inventory) if row[0] == 'source'}
            assert observed == set(control['processed'])
            result['implicitSource'] = 'all actual inputs inventoried; no completed receipt issued'
        else:
            assert b'javac did not complete every observed source unit' in run.stdout
            assert not list(evidence.glob('*.tsv'))
            result['implicitSource'] = 'implicit analysis is unsupported and publication rejected'
        run, output, evidence = java_control('annotation-default', [oracle['annotationDefaultControl']['source']])
        assert run.returncode == 0
        bodies = [row for row in rows(evidence / 'compiler-references-callers.tsv') if row[0] == 'body']
        assert sum(row[5] == 'ABSTRACT_METHOD' for row in bodies) == 1
        assert sum(row[5] == 'UNSUPPORTED' and dec(row[7]) == 'JAVAC_ANNOTATION_DEFAULT' for row in bodies) == 1
        result['annotationDefault'] = 'unsupported expression retained alongside abstract declaration'
        (root / 'result.json').write_text(json.dumps(result, sort_keys=True, indent=2) + '\n')
        print(json.dumps(result, sort_keys=True))
        print('complete source/backend oracle, unchanged C1/v3/output bytes, and stale-output rejection passed')

if __name__ == '__main__':
    main()
