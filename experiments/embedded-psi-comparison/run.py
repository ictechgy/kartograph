#!/usr/bin/env python3
"""고정 PSI runtime과 실제 Kartograph CLI를 같은 fixture에서 비교한다."""
import argparse
import hashlib
import json
import os
from pathlib import Path
import shutil
import signal
import subprocess
import sys
import time

HERE = Path(__file__).resolve().parent


def digest(path):
    return hashlib.sha256(path.read_bytes()).hexdigest()


def required_tool(variable, filename=None):
    value = os.environ.get(variable)
    if not value:
        raise ValueError('Set ' + variable)
    candidate = str(Path(value) / 'bin' / filename) if filename else value
    resolved = shutil.which(candidate)
    if not resolved:
        raise ValueError('Executable unavailable for ' + variable)
    return Path(resolved).resolve()


def stop_process(process):
    try:
        os.killpg(process.pid, signal.SIGTERM)
    except ProcessLookupError:
        process.wait()
        return
    try:
        process.wait(timeout=5)
    except subprocess.TimeoutExpired:
        try:
            os.killpg(process.pid, signal.SIGKILL)
        except ProcessLookupError:
            pass
        process.wait()


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--output', type=Path, required=True, help='new evidence directory')
    args = parser.parse_args()
    java = required_tool('JAVA_HOME', 'java')
    javac = required_tool('JAVA_HOME', 'javac')
    gradle = required_tool('GRADLE')
    cli = required_tool('KARTOGRAPH_BIN')
    out = args.output.resolve()
    out.mkdir(parents=True, exist_ok=False)
    records = []
    deadline = time.monotonic() + 600

    def command(stage, arguments, limit=90, stdout_name=None):
        remaining = min(limit, deadline - time.monotonic())
        if remaining <= 0:
            raise TimeoutError('Overall 600-second experiment limit exceeded')
        with (out / (stdout_name or stage + '.stdout.log')).open('xb') as stdout, (out / (stage + '.stderr.log')).open('xb') as stderr:
            process = subprocess.Popen([str(value) for value in arguments], cwd=HERE, stdout=stdout,
                                       stderr=stderr, start_new_session=True)
            try:
                status = process.wait(timeout=remaining)
            except subprocess.TimeoutExpired:
                stop_process(process)
                status = 124
            except BaseException as original:
                record = {'stage': stage, 'exit': None, 'status': 'interrupted', 'exception': type(original).__name__}
                try:
                    stop_process(process)
                except BaseException as cleanup_error:
                    record['cleanupException'] = type(cleanup_error).__name__
                records.append(record)
                try:
                    (out / 'stages.json').write_text(json.dumps(records, indent=2) + '\n')
                except BaseException as record_error:
                    print('Could not record interrupted stage: ' + type(record_error).__name__, file=sys.stderr)
                raise
        records.append({'stage': stage, 'exit': status})
        (out / 'stages.json').write_text(json.dumps(records, indent=2) + '\n')
        if status:
            raise RuntimeError(f'{stage} failed with exit {status}; preserve this output directory before retrying')

    command('dependencies', [gradle, '--no-daemon', '--console=plain', '--no-scan', '--max-workers=1',
                            '-p', HERE / 'semantic', 'writeSemanticClasspath'], limit=300)
    shutil.copyfile(HERE / 'semantic/build/semantic-classpath.txt', out / 'semantic-classpath.txt')
    cp = (out / 'semantic-classpath.txt').read_text().strip()
    runtime = [Path(value) for value in cp.split(os.pathsep)]
    observed = [{'name': path.name, 'bytes': path.stat().st_size, 'sha256': digest(path)} for path in runtime]
    if observed != json.loads((HERE / 'runtime-jars.json').read_text()):
        (out / 'changed-runtime.json').write_text(json.dumps(observed, indent=2) + '\n')
        raise ValueError('Resolved runtime differs from the reviewed pinned bytes; do not regenerate the pin to hide the difference')
    stdlib = next(path for path in runtime if path.name == 'kotlin-stdlib-2.4.10.jar')
    command('record-dependencies', [sys.executable, HERE / 'record_inputs.py', out, 'dependency-resolution'])
    command('java-version', [java, '-version'])
    command('compiler-version', [java, '-cp', cp, 'org.jetbrains.kotlin.cli.jvm.K2JVMCompiler',
                                 '-no-stdlib', '-no-reflect', '-version'])
    compiler = [java, '-Xmx512m', '-cp', cp, 'org.jetbrains.kotlin.cli.jvm.K2JVMCompiler',
                '-no-stdlib', '-no-reflect', '-jvm-target', '17']
    command('adapter-build', [*compiler, '-classpath', cp, '-d', out / 'adapter-classes', HERE / 'semantic/PsiResolvedCalls.kt'])
    command('fixture-kotlin-build', [*compiler, '-classpath', stdlib, '-d', out / 'fixture-classes',
                                    HERE / 'fixture/probe/PsiKotlin.kt', HERE / 'fixture/probe/PsiJava.java'])
    command('fixture-java-build', [javac, '-g', '-encoding', 'UTF-8', '-classpath', str(out / 'fixture-classes') + os.pathsep + str(stdlib),
                                  '-d', out / 'fixture-classes', HERE / 'fixture/probe/PsiJava.java'])
    command('record-fixture', [sys.executable, HERE / 'record_inputs.py', out, 'compiled-fixture'])
    command('psi', [java, '-Xmx512m', '-cp', str(out / 'adapter-classes') + os.pathsep + cp,
                    'PsiResolvedCallsKt', HERE / 'fixture', stdlib], stdout_name='psi-calls.jsonl')
    command('psi-grade', [sys.executable, HERE / 'compare.py', '--psi', out / 'psi-calls.jsonl',
                          '--output', out / 'psi-qualification.json'])
    libraries = cli.parent.parent / 'lib'
    (out / 'product-distribution.json').write_text(json.dumps({
        'launcherSha256': digest(cli), 'libraries': {p.name: digest(p) for p in sorted(libraries.glob('*.jar'))},
        'libraryLocationConvention': 'installed CLI sibling lib directory; an empty inventory does not identify a wrapper runtime'
    }, indent=2) + '\n')
    command('kartograph', [cli, 'graph', '--classes', out / 'fixture-classes', '--format', 'json',
                           '--include-paths', '--project', HERE / 'fixture'], stdout_name='kartograph-graph.json')
    command('compare', [sys.executable, HERE / 'compare.py', '--psi', out / 'psi-calls.jsonl',
                        '--graph', out / 'kartograph-graph.json', '--output', out / 'comparison.json'])
    print('Scoped four-edge comparison passed: ' + str(out / 'comparison.json'))


if __name__ == '__main__':
    main()
