#!/usr/bin/env python3
"""실제 JUnit 엔진으로 producer의 source identity·완결성 계약을 확인한다."""
import argparse
import hashlib
import json
import os
from pathlib import Path
import subprocess
import urllib.request
import zipfile

HERE = Path(__file__).resolve().parent
JUNIT_URL = 'https://repo.maven.apache.org/maven2/org/junit/platform/junit-platform-console-standalone/1.10.2/junit-platform-console-standalone-1.10.2.jar'
JUNIT_SHA256 = 'a1de557821293ce903c213c694165fff532cf92081bac4238b9e05b35f04f43f'


def build(jdk, junit, output):
    """관찰자와 별도 smoke 테스트를 컴파일하며 application 소스에 넣지 않는다."""
    output.mkdir(parents=True, exist_ok=False)
    classes = output / 'classes'; classes.mkdir()
    subprocess.run([str(jdk / 'bin/javac'), '--release', '17', '-cp', str(junit), '-d', str(classes),
                    str(HERE / 'ExecutionObserver.java'), str(HERE / 'ObserverContract.java')], check=True)
    jar = output / 'observer.jar'
    with zipfile.ZipFile(jar, 'w', compression=zipfile.ZIP_DEFLATED) as archive:
        for path in sorted((classes / 'evaluation').glob('ExecutionObserver*.class')):
            archive.write(path, path.relative_to(classes).as_posix())
        archive.writestr('META-INF/services/org.junit.platform.launcher.TestExecutionListener', 'evaluation.ExecutionObserver\n')
    return jar, classes


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--jdk', type=Path, required=True)
    parser.add_argument('--junit', type=Path)
    parser.add_argument('--download-junit', action='store_true')
    parser.add_argument('--output', type=Path, required=True)
    args = parser.parse_args(); out = args.output.resolve()
    if bool(args.junit) == args.download_junit:
        parser.error('choose exactly one of --junit or --download-junit')
    if args.download_junit:
        dependency = out.with_name(out.name + '-dependencies'); dependency.mkdir(parents=True, exist_ok=False)
        raw = urllib.request.urlopen(JUNIT_URL, timeout=60).read()
        if hashlib.sha256(raw).hexdigest() != JUNIT_SHA256:
            raise ValueError('downloaded JUnit artifact hash mismatch')
        args.junit = dependency / 'junit-platform-console-standalone-1.10.2.jar'; args.junit.write_bytes(raw)
    if hashlib.sha256(args.junit.read_bytes()).hexdigest() != JUNIT_SHA256:
        raise ValueError('unqualified JUnit artifact')
    jar, classes = build(args.jdk, args.junit, out)
    cases = []
    for changed in [False, True]:
        folder = out / ('changed' if changed else 'base'); folder.mkdir()
        command = [str(args.jdk / 'bin/java'), '-Devaluation.observer.output=' + str(folder),
            '-Devaluation.changed=' + str(changed).lower(), '-cp', os.pathsep.join(map(str, [jar, classes, args.junit])),
            'org.junit.platform.console.ConsoleLauncher', 'execute', '--disable-banner', '--details=summary',
            '--select-class', 'evaluation.ObserverContract']
        result = subprocess.run(command, capture_output=True, text=True, timeout=60)
        (folder / 'stdout.txt').write_text(result.stdout); (folder / 'stderr.txt').write_text(result.stderr)
        assert result.returncode == (1 if changed else 0), result.stderr
        journals = list(folder.glob('plan-*.jsonl'))
        assert len(journals) == 1, 'one complete observer journal required'
        seal = json.loads(journals[0].with_name(journals[0].name + '.complete.json').read_text())
        assert seal['complete'] is True and seal['sha256'] == hashlib.sha256(journals[0].read_bytes()).hexdigest()
        events = [json.loads(line) for line in journals[0].read_text().splitlines()]
        assert events[0]['type'] == 'start' and events[-1]['type'] == 'complete' and events[-1]['complete'] is True
        rows = [event for event in events if event['type'] == 'test']
        assert len(rows) == 8 and len({row['uid'] for row in rows}) == 8
        assert len([row for row in rows if row['method'] == 'method:evaluation/ObserverContract#alpha(I)V']) == 2
        assert len([row for row in rows if row['method'] == 'method:evaluation/ObserverContract#beta(I)V']) == 2
        assert len([row for row in rows if row['method'] == 'method:evaluation/ObserverContract#dynamicCases()Ljava/util/stream/Stream;']) == 2
        assert len([row for row in rows if row['method'] == 'method:evaluation/ObserverContract$Group#nestedMethod()V']) == 1
        assert len([row for row in rows if row['status'] == 'skipped']) == 1
        assert len([row for row in rows if row['status'] == 'assertion-failure']) == (2 if changed else 0)
        assert not [row for row in rows if row['status'] not in ['pass', 'assertion-failure', 'skipped']]
        cases.append(rows)
    assert {(r['uid'], r['method']) for r in cases[0]} == {(r['uid'], r['method']) for r in cases[1]}
    invalid = out / 'not-a-directory'; invalid.write_text('output failure control')
    command = [str(args.jdk / 'bin/java'), '-Devaluation.observer.output=' + str(invalid),
        '-cp', os.pathsep.join(map(str, [jar, classes, args.junit])), 'org.junit.platform.console.ConsoleLauncher',
        'execute', '--disable-banner', '--details=summary', '--select-class', 'evaluation.ObserverContract']
    failed_sink = subprocess.run(command, capture_output=True, text=True, timeout=60)
    (out / 'failed-sink.stdout').write_text(failed_sink.stdout); (out / 'failed-sink.stderr').write_text(failed_sink.stderr)
    assert failed_sink.returncode == 0 and 'Evaluation observer could not start evidence' in failed_sink.stderr
    # JUnit 성공만으로 증거 수집 성공을 추론하면 안 된다.
    import importlib.util
    spec = importlib.util.spec_from_file_location('observer_evidence_check', HERE / 'execution.py')
    consumer = importlib.util.module_from_spec(spec); spec.loader.exec_module(consumer)
    try:
        consumer.read_execution(invalid)
    except ValueError:
        pass
    else:
        raise AssertionError('failed sink was accepted as execution evidence')
    result = dict(status='passed', invocationsPerRun=8, parameterizedMethods=2, dynamicInvocations=2,
        nestedInvocations=1, skippedPerRun=1, observedChangedAssertions=2, successfulTestsWithFailedEvidenceRejected=True,
        jarSha256=hashlib.sha256(jar.read_bytes()).hexdigest(), junitSha256=hashlib.sha256(args.junit.read_bytes()).hexdigest())
    (out / 'verification.json').write_text(json.dumps(result, indent=2) + '\n')
    print(json.dumps(result))


if __name__ == '__main__': main()
