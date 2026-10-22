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

from compare import verify_frozen_inputs

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


def terminate(signum, frame):
    raise InterruptedError('Experiment received signal ' + str(signum))


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--output', type=Path, required=True, help='new evidence directory')
    parser.add_argument('--runtime-classpath', type=Path, required=True)
    parser.add_argument('--adapter-source', type=Path, required=True)
    parser.add_argument('--product-source', type=Path, required=True)
    args = parser.parse_args()
    frozen = verify_frozen_inputs()
    execution = json.loads((HERE / 'execution-manifest.json').read_text())
    for name, expected in execution['implementationSha256'].items():
        if digest(HERE / name) != expected:
            raise ValueError('Reviewed execution implementation changed: ' + name)
    if digest(HERE / 'fixture-manifest.json') != execution['fixtureManifestSha256']:
        raise ValueError('Source fixture freeze changed')
    if digest(args.adapter_source) != frozen['reusedAdapter']['sha256']:
        raise ValueError('Adapter source differs from the published unchanged adapter')
    java = required_tool('JAVA_HOME', 'java')
    javac = required_tool('JAVA_HOME', 'javac')
    javap = required_tool('JAVA_HOME', 'javap')
    cli = required_tool('KARTOGRAPH_BIN')
    product_source = args.product_source.resolve()
    revision = subprocess.check_output(['git', '-C', str(product_source), 'rev-parse', 'HEAD'], text=True).strip()
    status = subprocess.check_output(['git', '-C', str(product_source), 'status', '--porcelain'], text=True)
    if revision != frozen['reusedAdapter']['repositoryRevision'] or status:
        raise ValueError('Expected clean pure-main254c product source')
    if cli != (product_source / 'cli/build/install/kartograph/bin/kartograph').resolve():
        raise ValueError('CLI must be the verified product checkout installed distribution')
    out = args.output.resolve()
    out.mkdir(parents=True, exist_ok=False)
    environment = os.environ.copy()
    for name in ['JAVA_TOOL_OPTIONS', 'JDK_JAVA_OPTIONS', '_JAVA_OPTIONS', 'KARTOGRAPH_OPTS']:
        environment.pop(name, None)
    environment['JAVA_OPTS'] = '-Xmx512m -Dfile.encoding=UTF-8'
    records = []
    deadline = time.monotonic() + 600

    def command(stage, arguments, limit=90, stdout_name=None):
        remaining = min(limit, deadline - time.monotonic())
        if remaining <= 0:
            raise TimeoutError('Overall 600-second experiment limit exceeded')
        with (out / (stdout_name or stage + '.stdout.log')).open('xb') as stdout, (out / (stage + '.stderr.log')).open('xb') as stderr:
            process = subprocess.Popen([str(value) for value in arguments], cwd=HERE, stdout=stdout,
                                       stderr=stderr, start_new_session=True, env=environment)
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

    shutil.copyfile(args.runtime_classpath, out / 'semantic-classpath.txt')
    cp = (out / 'semantic-classpath.txt').read_text().strip()
    runtime = [Path(value) for value in cp.split(os.pathsep)]
    observed = [{'name': path.name, 'bytes': path.stat().st_size, 'sha256': digest(path)} for path in runtime]
    if observed != json.loads((HERE / 'runtime-jars.json').read_text()):
        (out / 'changed-runtime.json').write_text(json.dumps(observed, indent=2) + '\n')
        raise ValueError('Existing runtime differs from the reviewed pinned bytes')
    stdlib = next(path for path in runtime if path.name == 'kotlin-stdlib-2.4.10.jar')
    libraries = cli.parent.parent / 'lib'
    product_jars = {path.name: digest(path) for path in sorted(libraries.glob('*.jar'))}
    if not product_jars:
        raise ValueError('Installed product distribution has no libraries')
    initial_inputs = {
        'sourceSha256': frozen['sourceSha256'], 'fixtureManifestSha256': digest(HERE / 'fixture-manifest.json'),
        'executionManifestSha256': digest(HERE / 'execution-manifest.json'), 'runtimeJars': observed,
        'adapterSourceSha256': digest(args.adapter_source),
        'productSourceRevision': revision, 'productSourceStatus': status,
        'productLauncherSha256': digest(cli), 'productLibrariesSha256': product_jars,
        'effectiveJavaOpts': environment['JAVA_OPTS'],
        'removedAmbientOptions': ['JAVA_TOOL_OPTIONS', 'JDK_JAVA_OPTIONS', '_JAVA_OPTIONS', 'KARTOGRAPH_OPTS'],
        'inferenceLimit': 'Clean checkout and installed distribution hashes identify selected inputs; parent retains the successful installDist build receipt.'
    }
    (out / 'inputs.json').write_text(json.dumps(initial_inputs, indent=2) + '\n')
    command('java-version', [java, '-version'])
    command('compiler-version', [java, '-Xmx512m', '-cp', cp, 'org.jetbrains.kotlin.cli.jvm.K2JVMCompiler',
                                 '-no-stdlib', '-no-reflect', '-version'])
    compiler = [java, '-Xmx512m', '-cp', cp, 'org.jetbrains.kotlin.cli.jvm.K2JVMCompiler',
                '-no-stdlib', '-no-reflect', '-jvm-target', '17']
    command('adapter-build', [*compiler, '-classpath', cp, '-d', out / 'adapter-classes', args.adapter_source.resolve()])
    command('fixture-kotlin-build', [*compiler, '-classpath', stdlib, '-d', out / 'fixture-classes',
                                    HERE / 'fixture/lowered/LoweredKotlin.kt', HERE / 'fixture/lowered/LoweredJava.java'])
    command('fixture-java-build', [javac, '-J-Xmx512m', '-g', '-encoding', 'UTF-8', '-classpath', str(out / 'fixture-classes') + os.pathsep + str(stdlib),
                                  '-d', out / 'fixture-classes', HERE / 'fixture/lowered/LoweredJava.java'])
    compiled = {name: {str(path.relative_to(out / name)): digest(path)
                      for path in sorted((out / name).rglob('*')) if path.is_file()}
                for name in ['adapter-classes', 'fixture-classes']}
    (out / 'compiled-inputs.json').write_text(json.dumps(compiled, indent=2) + '\n')
    command('psi', [java, '-Xmx512m', '-cp', str(out / 'adapter-classes') + os.pathsep + cp,
                    'PsiResolvedCallsKt', HERE / 'fixture', stdlib], stdout_name='psi-calls.jsonl')
    command('psi-grade', [sys.executable, HERE / 'compare.py', '--psi', out / 'psi-calls.jsonl',
                          '--output', out / 'psi-qualification.json'])
    command('kartograph', [cli, 'graph', '--classes', out / 'fixture-classes', '--format', 'json',
                           '--include-paths', '--project', HERE / 'fixture'], stdout_name='kartograph-graph.json')
    class_names = [str(path.relative_to(out / 'fixture-classes')).removesuffix('.class').replace(os.sep, '.')
                   for path in sorted((out / 'fixture-classes/lowered').glob('*.class'))]
    command('javap', [javap, '-J-Xmx512m', '-p', '-c', '-l', '-v', '-classpath', out / 'fixture-classes', *class_names],
            stdout_name='javap.txt')
    command('compare', [sys.executable, HERE / 'compare.py', '--psi', out / 'psi-calls.jsonl',
                        '--graph', out / 'kartograph-graph.json', '--javap', out / 'javap.txt',
                        '--output', out / 'comparison.json'])
    final_inputs = dict(initial_inputs)
    final_inputs.update({
        'sourceSha256': verify_frozen_inputs()['sourceSha256'],
        'fixtureManifestSha256': digest(HERE / 'fixture-manifest.json'),
        'executionManifestSha256': digest(HERE / 'execution-manifest.json'),
        'runtimeJars': [{'name': path.name, 'bytes': path.stat().st_size, 'sha256': digest(path)} for path in runtime],
        'adapterSourceSha256': digest(args.adapter_source),
        'productSourceRevision': subprocess.check_output(['git', '-C', str(product_source), 'rev-parse', 'HEAD'], text=True).strip(),
        'productSourceStatus': subprocess.check_output(['git', '-C', str(product_source), 'status', '--porcelain'], text=True),
        'productLauncherSha256': digest(cli),
        'productLibrariesSha256': {path.name: digest(path) for path in sorted(libraries.glob('*.jar'))},
    })
    final_compiled = {name: {str(path.relative_to(out / name)): digest(path)
                            for path in sorted((out / name).rglob('*')) if path.is_file()}
                      for name in ['adapter-classes', 'fixture-classes']}
    for name, expected in execution['implementationSha256'].items():
        if digest(HERE / name) != expected:
            raise ValueError('Execution implementation changed during run: ' + name)
    (out / 'final-inputs.json').write_text(json.dumps(final_inputs, indent=2) + '\n')
    (out / 'final-compiled-inputs.json').write_text(json.dumps(final_compiled, indent=2) + '\n')
    if final_inputs != initial_inputs or final_compiled != compiled:
        raise ValueError('Selected source/runtime/product/compiled inputs changed during run')
    if time.monotonic() > deadline:
        raise TimeoutError('Overall 600-second experiment limit exceeded during final readback')
    print('Scoped lowered-call readback qualified: ' + str(out / 'comparison.json'))


if __name__ == '__main__':
    signal.signal(signal.SIGTERM, terminate)
    main()
