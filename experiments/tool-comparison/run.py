#!/usr/bin/env python3
"""고정 NIA 입력에서 배포 CLI의 반복 조사 비용을 측정한다. 앱을 빌드하지 않는다."""
import argparse
import hashlib
import json
import os
from pathlib import Path
import queue
import statistics
import subprocess
import threading
import time

TARGETS = {
    'MainActivityViewModel': 'com.google.samples.apps.nowinandroid.MainActivityViewModel',
    'NewsResourceDao': 'com.google.samples.apps.nowinandroid.core.database.dao.NewsResourceDao',
    'ListToMapMigration': 'com.google.samples.apps.nowinandroid.core.datastore.ListToMapMigration',
}


def sha(path):
    return hashlib.sha256(path.read_bytes()).hexdigest()


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    for name in ['fixture-report', 'cli', 'sdc', 'jdk', 'sdk', 'output']:
        parser.add_argument('--' + name, type=Path, required=True)
    parser.add_argument('--classpath-file', type=Path)
    args = parser.parse_args()
    out = args.output.resolve()
    out.mkdir(parents=True, exist_ok=False)
    old = args.fixture_report.resolve()
    project = old / 'work/nowinandroid'
    env = dict(os.environ, JAVA_HOME=str(args.jdk), JAVA_OPTS='-Xmx2g', ANDROID_HOME=str(args.sdk))
    env['PATH'] = str(args.jdk / 'bin') + os.pathsep + env['PATH']
    replacements = [(project, '<project>'), (out, '<output>'), (old, '<fixture-report>'),
                    (args.cli.parent.parent, '<cli-install>'), (args.sdc, '<sdc>'),
                    (args.jdk, '<jdk>'), (args.sdk, '<sdk>'), (Path.home(), '<home>')]

    def scrub(text):
        for path, token in sorted(replacements, key=lambda item: len(str(item[0])), reverse=True):
            text = text.replace(str(path), token)
        return text

    def save(name, data):
        (out / (name + '.json')).write_text(scrub(json.dumps(data, indent=2)) + '\n')

    def run(name, command, allowed=(0,)):
        start = time.perf_counter()
        proc = subprocess.run([str(x) for x in command], cwd=project, env=env,
                              capture_output=True, text=True, timeout=300)
        row = dict(command=[str(x) for x in command], exit=proc.returncode,
                   seconds=time.perf_counter() - start, stdout=proc.stdout, stderr=proc.stderr)
        save(name, row)
        if proc.returncode not in allowed:
            raise RuntimeError(name + ': unexpected exit; inspect preserved evidence')
        return row

    inputs = json.loads((old / 'input-manifest.json').read_text())
    revision = run('revision', ['git', 'rev-parse', 'HEAD'])['stdout'].strip()
    if revision != inputs['revision'] or run('tracked-status', ['git', 'status', '--porcelain', '--untracked-files=no'])['stdout']:
        raise RuntimeError('fixture revision or tracked source changed')
    for group in ['sourceSha256', 'classRootFileSha256']:
        for name, expected in inputs[group].items():
            if sha(project / name) != expected:
                raise RuntimeError('frozen input bytes changed')
    classes = [value for root in inputs['classRoots'] for value in ['--classes', project / root]]
    classpath_file = args.classpath_file or project / 'app/build/kartograph-validation-classpath.txt'
    jars = [Path(x) for x in classpath_file.read_text().splitlines()]
    common = [*classes, '--project', project, '--manifest',
              'app/build/intermediates/merged_manifests/demoDebug/processDemoDebugManifest/AndroidManifest.xml',
              '--resources', 'app/src/main/res', '--namespace', 'com.google.samples.apps.nowinandroid',
              '--keep-rules', 'app/proguard-rules.pro', '--keep-rules', 'core/datastore/consumer-proguard-rules.pro']
    common += [value for jar in [args.sdk / 'platforms/android-36/android.jar', *jars] for value in ['--classpath', jar]]
    sdc = [args.sdc, project, '--config', old / 'searchdeadcode-scope.yml', '--incremental', 'false']
    save('inputs', dict(inputs, classpathSha256={str(p): sha(p) for p in jars},
                        androidJarSha256=sha(args.sdk / 'platforms/android-36/android.jar'),
                        configSha256=sha(old / 'searchdeadcode-scope.yml')))
    save('tools', dict(cliVersion=run('cli-version', [args.cli, '--version'])['stdout'].strip(),
                       sdcVersion=run('sdc-version', [args.sdc, '--version'])['stdout'].strip(),
                       sdcSha256=sha(args.sdc),
                       cliLibraries={p.name: sha(p) for p in sorted((args.cli.parent.parent / 'lib').glob('*.jar'))},
                       java=run('java-version', [args.jdk / 'bin/java', '-version'])['stderr']))
    plan = dict(repeats=3, order='alternate tool order each repeat', targets=TARGETS,
                scope='same frozen source revision and documented variant; source vs compiled input coverage differs',
                excluded=['application build time', 'OS cold-cache control', 'runtime correctness', 'general accuracy ranking'],
                timestamp=time.time())
    save('plan', plan)  # 분석·질의 실행 전에 고정한다.
    results = {}

    def measure(label, commands, validator):
        rows = {tool: [] for tool in commands}
        for repeat in range(3):
            order = list(commands) if repeat % 2 == 0 else list(reversed(commands))
            for tool in order:
                row = run(f'{label}-{tool}-{repeat}', commands[tool])
                validator(tool, row['stdout'])
                rows[tool].append(row)
        results[label] = {tool: dict(seconds=[r['seconds'] for r in entries],
                                     medianSeconds=statistics.median(r['seconds'] for r in entries),
                                     stdoutBytes=len(entries[0]['stdout'].encode())) for tool, entries in rows.items()}
        save('results', results)
        print(label, {k: round(v['medianSeconds'], 4) for k, v in results[label].items()}, flush=True)

    def scan_valid(tool, text):
        json.loads(text)

    measure('scan', {'kartograph': [args.cli, 'dead', *common, '--report-format', 'json'],
                     'searchdeadcode': [*sdc, '--format', 'json']}, scan_valid)
    kg_graph = out / 'kartograph-snapshot.json'
    sd_graph = out / 'searchdeadcode-graph.json'
    row = run('capture-kartograph', [args.cli, 'snapshot', *common, '--include-paths', '--compact'])
    kg_graph.write_text(row['stdout'])
    json.loads(row['stdout'])
    run('capture-searchdeadcode', [*sdc, '--export-graph', sd_graph])
    sd_nodes = json.loads(sd_graph.read_text())['nodes']
    for short in TARGETS:
        if len([node for node in sd_nodes if node['name'] == short]) != 1:
            raise RuntimeError('searchdeadcode selector is absent or ambiguous')

    def query_valid(tool, text):
        if tool == 'kartograph':
            if json.loads(text)['status'] != 'found':
                raise RuntimeError('kartograph selector did not resolve')
        elif 'not in the graph' in text or not any(marker in text for marker in ['References to ', 'no references to ']):
            raise RuntimeError('searchdeadcode selector did not resolve')

    for short, fqn in TARGETS.items():
        kg_symbol = 'class:' + fqn.replace('.', '/')
        measure('saved-' + short, {
            'kartograph': [args.cli, 'query', kg_symbol, '--graph-file', kg_graph, '--depth', '2', '--limit', '10'],
            'searchdeadcode': [args.sdc, '--graph-file', sd_graph, '--refs-of', short]}, query_valid)

    # 지속 MCP는 같은 JVM을 재사용한다. 시작 비용과 호출 비용을 별도로 보존한다.
    start = time.perf_counter()
    with (out / 'mcp-stderr.txt').open('w') as err:
        proc = subprocess.Popen([str(args.cli), 'mcp', '--graph-file', str(kg_graph), '--project', str(project)],
                                cwd=project, env=env, stdin=subprocess.PIPE, stdout=subprocess.PIPE,
                                stderr=err, text=True)
        incoming = queue.Queue()
        def reader():
            for line in proc.stdout:
                incoming.put(line)
            incoming.put(None)
        thread = threading.Thread(target=reader, daemon=True)
        thread.start()
        counter = 0
        def rpc(method, params):
            nonlocal counter
            counter += 1
            proc.stdin.write(json.dumps(dict(jsonrpc='2.0', id=counter, method=method, params=params)) + '\n')
            proc.stdin.flush()
            while True:
                line = incoming.get(timeout=60)
                if line is None:
                    raise RuntimeError('MCP exited before response')
                data = json.loads(line)
                if data.get('id') == counter:
                    if 'error' in data:
                        raise RuntimeError('MCP protocol error')
                    return data['result']
        try:
            init = rpc('initialize', dict(protocolVersion='2025-11-25', capabilities={},
                                          clientInfo=dict(name='comparison', version='1')))
            results['mcpStartupSeconds'] = time.perf_counter() - start
            proc.stdin.write(json.dumps(dict(jsonrpc='2.0', method='notifications/initialized')) + '\n'); proc.stdin.flush()
            save('mcp-initialize', init)
            for short, fqn in TARGETS.items():
                durations = []
                for repeat in range(3):
                    start = time.perf_counter()
                    result = rpc('tools/call', dict(name='query_symbol', arguments=dict(symbol='class:' + fqn.replace('.', '/'), depth=2, limit=10)))
                    durations.append(time.perf_counter() - start)
                    save(f'mcp-{short}-{repeat}', result)
                    if result.get('isError') or result['structuredContent']['document']['status'] != 'found':
                        raise RuntimeError('MCP query failed')
                    saved = json.loads((out / f'saved-{short}-kartograph-0.json').read_text())
                    if result['structuredContent']['document'] != json.loads(saved['stdout']):
                        raise RuntimeError('CLI/MCP query documents differ; do not compare unequal pages')
                results['mcp-' + short] = dict(seconds=durations, medianSeconds=statistics.median(durations))
            fresh = rpc('tools/call', dict(name='freshness', arguments={}))
            save('mcp-freshness', fresh)
            results['freshness'] = fresh['structuredContent']['document']['status']
        finally:
            proc.stdin.close()
            try:
                proc.wait(timeout=10)
            except subprocess.TimeoutExpired:
                proc.terminate(); proc.wait(timeout=10)
            thread.join(timeout=2)
        if proc.returncode != 0:
            raise RuntimeError('MCP shutdown failed')
    save('results', results)
    save('output-hashes', {p.name: sha(p) for p in sorted(out.iterdir()) if p.is_file()})
    print('complete; output and process evidence preserved', flush=True)


if __name__ == '__main__':
    main()
