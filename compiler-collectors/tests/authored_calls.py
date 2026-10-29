#!/usr/bin/env python3
"""실제 compiler의 caller 전체를 검사한다. oracle은 collector 출력보다 먼저 고정했다."""
import argparse, base64, hashlib, json, os, pathlib, shutil, subprocess, tempfile

HERE = pathlib.Path(__file__).resolve().parent

def decode(value):
    return base64.urlsafe_b64decode(value + '=' * (-len(value) % 4)).decode('utf-8')

def rows(path):
    callers, calls = {}, {}
    for line in path.read_text().splitlines():
        fields = line.split('\t')
        if fields[0] == 'caller':
            assert decode(fields[1]) not in callers
            callers[decode(fields[1])] = fields
        if fields[0] == 'authored':
            calls.setdefault(decode(fields[1]), []).append(decode(fields[2]))
    return callers, calls

def verify(oracle, language, path):
    callers, calls = rows(path)
    accounted = set()
    for caller, expected in oracle[language].items():
        if 'targets' in expected:
            accounted.add(caller)
            assert callers[caller][5] == expected['status'], (caller, callers[caller])
            assert sorted(calls.get(caller, [])) == sorted(expected['targets']), caller
        else:
            matching = {key for key in callers if '#' + caller + '(' in key}
            accounted.update(matching)
            selected = [callers[key] for key in matching]
            assert selected and all(row[5] == 'INCOMPLETE' for row in selected), caller
    assert accounted == set(callers), ('unexpected ordinary callers', set(callers) - accounted)

def main():
    parser = argparse.ArgumentParser()
    parser.add_argument('--collector', type=pathlib.Path, required=True)
    parser.add_argument('--kotlin-classpath', required=True)
    args = parser.parse_args()
    compiler = args.kotlin_classpath
    stdlib = next(p for p in compiler.split(os.pathsep) if pathlib.Path(p).name == 'kotlin-stdlib-2.4.20.jar')
    java = str(pathlib.Path(os.environ['JAVA_HOME']) / 'bin/java') if os.environ.get('JAVA_HOME') else 'java'
    javac = str(pathlib.Path(os.environ['JAVA_HOME']) / 'bin/javac') if os.environ.get('JAVA_HOME') else 'javac'
    oracle = json.loads((HERE / 'authored_oracle_v1.json').read_text())
    empty_oracle = json.loads((HERE / 'authored_empty_oracle_v1.json').read_text())
    empty_hashes = json.loads((HERE / 'authored_empty_source_sha256_v1.json').read_text())
    with tempfile.TemporaryDirectory(prefix='kartograph-authored-') as temporary:
        root = pathlib.Path(temporary)
        token = root / 'token'; token.write_text('a' * 64)
        cases = [('java', 'JavaCalls.java', 'authored', oracle, oracle['sourceSha256']),
                 ('kotlin', 'Calls.kt', 'authored', oracle, oracle['sourceSha256']),
                 ('java', 'EmptyJava.java', 'authored-empty', empty_oracle, empty_hashes),
                 ('kotlin', 'EmptyKotlin.kt', 'authored-empty', empty_oracle, empty_hashes)]
        for language, filename, fixture, expected, hashes in cases:
            source = root / filename; shutil.copyfile(HERE / 'fixtures' / fixture / filename, source)
            assert hashlib.sha256(source.read_bytes()).hexdigest() == hashes[filename]
            def command(out, evidence, authored=True):
                out.mkdir(exist_ok=True); evidence.mkdir(exist_ok=True)
                opts = [f'root={root}', f'output={evidence}', f'token={token}', 'callPositions=true']
                if authored: opts += ['authoredCalls=true']
                if language == 'java':
                    return [javac, '-proc:none', '-encoding', 'UTF-8', '-classpath', str(args.collector),
                        '-Xplugin:KartographEvidence ' + ' '.join(opts), '-d', str(out), str(source)]
                return [java, '-cp', compiler, 'org.jetbrains.kotlin.cli.jvm.K2JVMCompiler', '-no-stdlib', '-no-reflect',
                    '-jvm-target', '17', '-Xstring-concat=indy-with-constants', '-classpath', stdlib, '-Xplugin=' + str(args.collector),
                    *[value for opt in opts for value in ['-P', 'plugin:kartograph.compiler-evidence:' + opt]], '-d', str(out), str(source)]
            out, evidence = root / (filename + '-classes'), root / (filename + '-evidence')
            first = command(out, evidence)
            subprocess.run(first, check=True)
            verify(expected, language, evidence / 'compiler-references-authored.tsv')
            other_out, other_evidence = root / (filename + '-legacy-classes'), root / (filename + '-legacy-evidence')
            subprocess.run(command(other_out, other_evidence, authored=False), check=True)
            assert (evidence / 'compiler-references.tsv').read_bytes() == (other_evidence / 'compiler-references.tsv').read_bytes()
            expected_files = {p.relative_to(out): p.read_bytes() for p in out.rglob('*') if p.is_file()}
            actual_files = {p.relative_to(other_out): p.read_bytes() for p in other_out.rglob('*') if p.is_file()}
            assert expected_files == actual_files, 'instrumentation changed compiler output'
            source.write_text('syntax error invalid source')
            assert subprocess.run(first, stdout=subprocess.PIPE, stderr=subprocess.PIPE).returncode != 0
            assert not (evidence / 'compiler-references-authored.tsv').exists(), 'failed compilation retained source coverage'
    print('authored complete-caller oracle, unchanged v3/classfiles and failure invalidation passed')

if __name__ == '__main__':
    main()
