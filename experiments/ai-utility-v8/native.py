#!/usr/bin/env python3
"""고정 공개 checkout의 전체 test/capture 실행과 매 시도의 원본 결과를 보존한다."""
import argparse
import hashlib
import json
import os
from pathlib import Path
import shutil
import subprocess
import time

HERE = Path(__file__).resolve().parent


def save(path, value):
    with path.open('x') as output:
        output.write(json.dumps(value, ensure_ascii=False, indent=2) + '\n')


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--config', type=Path, required=True)
    parser.add_argument('--source', choices=['detekt', 'ktlint'], required=True)
    parser.add_argument('--case')
    parser.add_argument('--output', type=Path, required=True)
    args = parser.parse_args(); config = json.loads(args.config.read_text())
    out = args.output.resolve(); out.mkdir(parents=True, exist_ok=False)
    candidates = json.loads((HERE / 'changes.json').read_text())
    rows = [row for row in candidates if row['source'] == args.source]
    selected = next(row for row in rows if row['id'] == args.case) if args.case else rows[0]
    source = Path(config['sources']) / args.source
    if args.case:
        checkout = out / 'changed'
        subprocess.run(['git', 'clone', '-q', '--local', str(source), str(checkout)], check=True)
        target = checkout / selected['module'] / selected['file']
        content = target.read_text(); assert content.count(selected['before']) == 1
        target.write_text(content.replace(selected['before'], selected['after']))
        patch = subprocess.check_output(['git', '-C', str(checkout), 'diff', '--binary', 'HEAD'], text=True)
        (out / 'production.diff').write_text(patch)
    else:
        checkout = source
        assert not subprocess.check_output(['git', '-C', str(source), 'diff', 'HEAD'])
    assert subprocess.check_output(['git', '-C', str(checkout), 'rev-parse', 'HEAD'], text=True).strip() == selected['base']
    jdk = config['jdks'][selected['java']]
    environment = {k: v for k, v in os.environ.items() if k in ['PATH', 'HOME', 'USER', 'LOGNAME', 'SHELL', 'TMPDIR', 'LANG', 'LC_CTYPE']}
    environment['JAVA_HOME'] = jdk
    command = [str(checkout / 'gradlew'), '--no-daemon', '--console=plain', '--no-scan', '--no-build-cache',
        '--no-configuration-cache', '--max-workers=2', '-Dorg.gradle.java.installations.paths=' + ','.join(config['jdks'].values()),
        '-I', str(HERE / 'capture.init.gradle'), '-Devaluation.module=:' + selected['module'],
        '-Devaluation.plugin=' + config['plugin'], '-Devaluation.capture=' + str(HERE / 'capture.init.gradle'),
        '-Devaluation.java=' + selected['java'], '-Devaluation.observer.jar=' + config['observer'],
        '-Devaluation.observer.output=' + str(out / 'observer'), '-Devaluation.sources=' + str(out / 'sources.json'),
        '-Devaluation.capture.enabled=' + ('false' if args.case else 'true'), ':' + selected['module'] + ':test']
    if not args.case:
        command += [':' + selected['module'] + ':evaluationSources', ':' + selected['module'] + ':kartographSnapshot']
    started = time.monotonic()
    with (out / 'build.log').open('wb') as log:
        try:
            result = subprocess.run(command, cwd=checkout, env=environment, stdout=log, stderr=subprocess.STDOUT, timeout=1800)
            code = result.returncode
        except subprocess.TimeoutExpired:
            code = 124
    record = dict(source=args.source, case=args.case, checkout=str(checkout), module=selected['module'],
        base=selected['base'], command=command, exit=code, seconds=time.monotonic() - started,
        observerSha256=hashlib.sha256(Path(config['observer']).read_bytes()).hexdigest(),
        adapterSha256=hashlib.sha256((HERE / 'capture.init.gradle').read_bytes()).hexdigest(),
        dirtyPatchSha256=hashlib.sha256(subprocess.check_output(['git', '-C', str(checkout), 'diff', '--binary', 'HEAD'])).hexdigest())
    results = checkout / selected['module'] / 'build/test-results/test'
    if results.is_dir():
        shutil.copytree(results, out / 'junit')
    if not args.case and code == 0:
        graph = checkout / selected['module'] / 'build/reports/kartograph/jvm-snapshot.json'
        bindings = checkout / selected['module'] / 'build/kartograph/jvm-input-bindings.json'
        verify = subprocess.run([config['binary'], 'verify-snapshot', '--graph-file', str(graph),
            '--project', str(checkout / selected['module']), '--input-bindings', str(bindings), '--snapshot-max-mib', '128'],
            env=environment, capture_output=True, text=True, timeout=180)
        (out / 'freshness.json').write_text(verify.stdout); (out / 'freshness.stderr').write_text(verify.stderr)
        record['matched'] = verify.returncode == 0 and json.loads(verify.stdout).get('status') == 'matched'
        shutil.copy2(graph, out / 'snapshot-original.json'); shutil.copy2(bindings, out / 'bindings-original.json')
    save(out / 'execution.json', record)
    if not args.case and record['dirtyPatchSha256'] != hashlib.sha256(b'').hexdigest():
        raise SystemExit('Baseline tracked files changed during native execution; evidence rejected and preserved')
    print(json.dumps({key: record.get(key) for key in ['source', 'case', 'exit', 'seconds', 'matched']}))
    if code != (1 if args.case else 0) or not args.case and not record.get('matched'):
        raise SystemExit('Native preparation needs review; original attempt preserved')


if __name__ == '__main__': main()
