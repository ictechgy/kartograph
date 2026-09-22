#!/usr/bin/env python3
"""고정 fixture를 독립 checkout에서 빌드하고 제품 밖의 동작 oracle을 생성한다."""
import argparse
import hashlib
import importlib.util
import json
import os
from pathlib import Path
import shutil
import subprocess
import sys
import time

HERE = Path(__file__).resolve().parent
ROOT = HERE.parents[1]
spec = importlib.util.spec_from_file_location('v7_preparation_oracle', HERE / 'assemble.py')
oracle = importlib.util.module_from_spec(spec)
spec.loader.exec_module(oracle)


def save(path, value):
    with path.open('x') as output:
        output.write(json.dumps(value, ensure_ascii=False, indent=2) + '\n')


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    for name in ['output', 'gradle', 'binary', 'plugin', 'java-home', 'parser-config']:
        parser.add_argument('--' + name, type=Path, required=True)
    args = parser.parse_args()
    out = args.output.resolve()
    out.mkdir(parents=True, exist_ok=False)
    base = out / 'base'
    shutil.copytree(HERE / 'fixtures', base)
    (base / '.gitignore').write_text('**/build/\n.gradle/\n.kotlin/\n')
    environment = dict(os.environ, JAVA_HOME=str(args.java_home),
        GIT_AUTHOR_DATE='2026-09-22T00:00:00Z', GIT_COMMITTER_DATE='2026-09-22T00:00:00Z')
    subprocess.run(['git', 'init', '-q', str(base)], check=True)
    subprocess.run(['git', '-C', str(base), 'add', '.'], check=True)
    subprocess.run(['git', '-C', str(base), '-c', 'user.name=Evaluation Fixture',
                    '-c', 'user.email=fixtures@example.invalid', 'commit', '-qm',
                    'test(experiments): 호출·동작 대조 입력을 고정한다'], env=environment, check=True)
    revision = subprocess.check_output(['git', '-C', str(base), 'rev-parse', 'HEAD'], text=True).strip()
    changed = out / 'changed'
    subprocess.run(['git', 'clone', '-q', '--local', str(base), str(changed)], check=True)
    changes = json.loads((HERE / 'changes.json').read_text())
    patches = out / 'patches'; patches.mkdir()
    for case, change in changes.items():
        path = changed / case / change['file']
        source = path.read_text()
        if source.count(change['before']) != 1:
            raise ValueError('expected unique mutation input')
        path.write_text(source.replace(change['before'], change['after']))
        patch = subprocess.check_output(['git', '-C', str(changed), 'diff', '--', case], text=True)
        (patches / (case + '.diff')).write_text(patch)
    capture = HERE / 'capture.init.gradle'
    common = [str(args.gradle), '--no-daemon', '--console=plain', '--no-scan', '--no-build-cache',
              '--no-configuration-cache', '--max-workers=2',
              '-Dorg.gradle.java.installations.paths=' + str(args.java_home)]
    commands = [
        ('baseline', base, [*common, '-I', str(capture), '-Devaluation.plugin=' + str(args.plugin),
            '-Devaluation.capture=' + str(capture), *[':' + case + ':test' for case in changes],
            *[':' + case + ':kartographSnapshot' for case in changes]]),
        ('changed', changed, [*common, '--continue', *[':' + case + ':test' for case in changes]])]
    attempts = []
    for label, directory, command in commands:
        start = time.monotonic()
        with (out / (label + '.log')).open('wb') as log:
            try:
                result = subprocess.run(command, cwd=directory, env=environment, stdout=log,
                                        stderr=subprocess.STDOUT, timeout=900)
                code = result.returncode
            except subprocess.TimeoutExpired:
                code = 124
        record = dict(stage=label, exit=code, seconds=time.monotonic() - start, command=command)
        save(out / (label + '-execution.json'), record); attempts.append(record)
        print(json.dumps({k: record[k] for k in ['stage', 'exit', 'seconds']}), flush=True)
        if label == 'baseline' and code != 0 or label == 'changed' and code != 1:
            raise ValueError('unexpected build outcome; preserve logs before retry')
    entries, summary = [], []
    empty_hash = hashlib.sha256(b'').hexdigest()
    for case, change in changes.items():
        project = base / case
        snapshot = project / 'build/reports/kartograph/jvm-snapshot.json'
        bindings = project / 'build/kartograph/jvm-input-bindings.json'
        verify = subprocess.run([str(args.binary), 'verify-snapshot', '--graph-file', str(snapshot),
            '--project', str(project), '--input-bindings', str(bindings), '--snapshot-max-mib', '128'],
            env=environment, capture_output=True, text=True, timeout=180)
        (out / (case + '-freshness.json')).write_text(verify.stdout)
        (out / (case + '-freshness.stderr')).write_text(verify.stderr)
        if verify.returncode != 0 or json.loads(verify.stdout)['status'] != 'matched':
            raise ValueError('baseline compiler snapshot did not match')
        compiled = out / (case + '-compiled')
        subprocess.run([sys.executable, str(HERE.parent / 'ai-utility-v5/collect.py'), '--project', str(project),
            '--parser-config', str(args.parser_config), '--jdk', str(args.java_home), '--output', str(compiled)], check=True)
        document = oracle.assemble(case, compiled / 'declarations.json', project / 'build/test-results/test',
            changed / case / 'build/test-results/test', patches / (case + '.diff'), change['changed'])
        oracle_path = out / (case + '-oracle.json'); save(oracle_path, document)
        entries.append(dict(id=case, repository='authored Kotlin caller/behavior controls', base=revision, module=case,
            checkout=str(base), sourceRoot=str(project), snapshot=str(snapshot), bindings=str(bindings),
            inventory=str(oracle_path), patch=str(patches / (case + '.diff')), scope=':' + case + ':jvm',
            setupPatchSha256=empty_hash))
        row = dict(case=case, calls=len(document['direct']) + len(document['indirect']),
            behaviorPositive=len(document['behaviorPositive']), behaviorNegative=len(document['behaviorNegative']),
            tests=len(document['executedTestMethods']), matched=True)
        summary.append(row); print(json.dumps(row), flush=True)
    if subprocess.check_output(['git', '-C', str(base), 'diff', 'HEAD']):
        raise ValueError('baseline source changed')
    save(out / 'trial-config-local.json', dict(binary=str(args.binary), plugin=str(args.plugin),
        javaHome=str(args.java_home), cases=entries, repeats=2, mode='trials'))
    save(out / 'qualification.json', dict(status='qualified-before-models', cases=summary,
        preparationSeconds=sum(row['seconds'] for row in attempts), sourceUnchanged=True))


if __name__ == '__main__':
    main()
