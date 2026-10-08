#!/usr/bin/env python3
"""공개 compiler·0.19 배포본으로 호출 위치의 수집→저장→조회 계약을 skip 없이 검증한다."""
from __future__ import annotations

import argparse
import hashlib
import json
import os
from pathlib import Path
import subprocess
import sys
import tempfile
import urllib.request
import xml.etree.ElementTree as ET
import zipfile

ROOT = Path(__file__).resolve().parent.parent
OLD_URL = 'https://github.com/ictechgy/kartograph/releases/download/v0.19.0/kartograph-0.19.0.zip'
OLD_SHA256 = '84c81ae2137751ce26b4d215ff321a1fef6c9a0c9ffdbad72d08d5a70cdf8cf1'
SUITES = {
    'index': ['CompilerCallPositionImportTest', 'CompilerCallPositionKotlinIntegrationTest'],
    'export': ['CompilerCallEvidenceOldReaderTest'],
    'cli': ['CompilerCallPositionCliTest'],
    'gradle-plugin': ['CompilerCallPositionOptionParserTest', 'CompilerWitnessValidationTest',
                      'CompilerCallPositionMarkerLifecycleTest', 'CompilerCallPositionSnapshotIntegrationTest'],
}
PACKAGES = {'gradle-plugin': 'gradle', 'index': 'index', 'export': 'export', 'cli': 'cli'}


def sha256(path: Path) -> str:
    value = hashlib.sha256()
    with path.open('rb') as stream:
        for block in iter(lambda: stream.read(65536), b''):
            value.update(block)
    return value.hexdigest()


def junit_totals(directory: Path, expected: set[str]) -> dict:
    totals = dict(tests=0, failures=0, errors=0, skipped=0)
    for name in sorted(expected):
        path = directory / ('TEST-' + name + '.xml')
        if not path.is_file():
            raise ValueError('selected test suite has no JUnit result')
        suite = ET.parse(path).getroot()
        values = {key: int(suite.attrib[key]) for key in totals}
        if values['tests'] <= 0 or any(values[key] != 0 for key in ('failures', 'errors', 'skipped')):
            raise ValueError('selected test suite failed, was empty, or skipped required checks')
        for key in totals:
            totals[key] += values[key]
    return totals


def run(name: str, command: list[str], cwd: Path, reports: Path, env: dict[str, str]) -> None:
    with (reports / (name + '.log')).open('wb') as log:
        result = subprocess.run(command, cwd=cwd, env=env, stdout=log, stderr=subprocess.STDOUT, timeout=900)
    if result.returncode != 0:
        raise ValueError(name + ' failed; inspect the local verification log')


def old_distribution(archive: Path, work: Path) -> Path:
    if not archive.exists():
        archive.parent.mkdir(parents=True, exist_ok=True)
        # 공개 고정 배포본만 다운로드하며 인증·앱 입력은 전송하지 않는다.
        temporary = None
        try:
            with tempfile.NamedTemporaryFile(prefix='.kartograph-019-', dir=archive.parent, delete=False) as output:
                temporary = Path(output.name)
                with urllib.request.urlopen(OLD_URL, timeout=60) as response:
                    size = 0
                    while block := response.read(65536):
                        size += len(block)
                        if size > 100 * 1024 * 1024:
                            raise ValueError('released compatibility archive exceeds the download limit')
                        output.write(block)
            if sha256(temporary) != OLD_SHA256:
                raise ValueError('released compatibility archive differs from its pinned SHA-256')
            # 같은 디렉터리의 검증된 파일을 기존 archive를 덮어쓰지 않고 원자적으로 연결한다.
            os.link(temporary, archive)
        finally:
            if temporary is not None:
                temporary.unlink(missing_ok=True)
    if archive.stat().st_size > 100 * 1024 * 1024 or sha256(archive) != OLD_SHA256:
        raise ValueError('released compatibility archive differs from its pinned SHA-256')
    with zipfile.ZipFile(archive) as source:
        members = source.infolist()
        if sum(item.file_size for item in members) > 256 * 1024 * 1024:
            raise ValueError('released compatibility archive exceeds the unpacked limit')
        for item in members:
            path = Path(item.filename)
            if not path.parts or path.is_absolute() or '..' in path.parts or path.parts[0] != 'kartograph-0.19.0':
                raise ValueError('released compatibility archive contains an unexpected path')
            if ((item.external_attr >> 16) & 0o170000) == 0o120000:
                raise ValueError('released compatibility archive contains a symlink')
        source.extractall(work)
    home = work / 'kartograph-0.19.0'
    if not (home / 'bin/kartograph').is_file():
        raise ValueError('released compatibility CLI is missing')
    # 테스트는 launcher를 /bin/bash로 실행한다. 설치 권한을 변경하지 않는다.
    return home


def verify(args: argparse.Namespace) -> dict:
    reports = args.report_dir.resolve()
    reports.mkdir(parents=True, exist_ok=True)
    result_file = reports / 'verification.json'
    result_file.unlink(missing_ok=True)
    env = dict(os.environ)
    jdk = Path(env.get('KARTOGRAPH_JDK17_HOME', env.get('JAVA_HOME', ''))).resolve()
    if not (jdk / 'bin/javac').is_file():
        raise ValueError('set JAVA_HOME or KARTOGRAPH_JDK17_HOME to the approved JDK 17')
    version = subprocess.run([str(jdk / 'bin/javac'), '-version'], capture_output=True, text=True, timeout=30)
    if version.returncode != 0 or not version.stdout.strip().startswith('javac 17.'):
        raise ValueError('the call-position producer gate requires JDK 17')
    env['KARTOGRAPH_JDK17_HOME'] = str(jdk)
    gradle = [str(ROOT / 'gradlew'), '--no-daemon'] + (['--offline'] if args.offline else [])
    with tempfile.TemporaryDirectory(prefix='kartograph-call-positions-') as temp:
        work = Path(temp)
        runtime_file = work / 'runtime.json'
        env['KARTOGRAPH_CALL_RUNTIME_FILE'] = str(runtime_file)
        init = work / 'collector-runtime.gradle'
        init.write_text('''
            import groovy.json.JsonOutput
            gradle.projectsEvaluated {
                def p = gradle.rootProject
                def tools = p.configurations.create('callPositionVerificationTools')
                ['kotlin-build-tools-compat', 'kotlin-build-tools-impl'].each {
                    p.dependencies.add(tools.name, "org.jetbrains.kotlin:$it:2.4.10")
                }
                p.tasks.register('callPositionVerificationRuntime') {
                    dependsOn p.tasks.named('jar')
                    doLast {
                        def compiler = p.configurations.getByName('kotlinCompilerRuntime').files
                        def plugin = p.buildscript.configurations.getByName('classpath').files + tools.files
                        new File(System.getenv('KARTOGRAPH_CALL_RUNTIME_FILE')).text = JsonOutput.toJson([
                            compiler: compiler.collect { it.canonicalPath }.sort(),
                            plugin: plugin.collect { it.canonicalPath }.unique().sort()])
                    }
                }
            }
        ''')
        run('resolve-verified-runtime', gradle + ['--no-configuration-cache', '-p', 'compiler-collectors', '-I', str(init),
            'callPositionVerificationRuntime'], ROOT, reports, env)
        runtime = json.loads(runtime_file.read_text())
        for key in ('compiler', 'plugin'):
            if not runtime[key] or any(not Path(item).is_file() for item in runtime[key]):
                raise ValueError('resolved verification runtime contains missing files')
        compiler_cp = os.pathsep.join(runtime['compiler'])
        env.update(KARTOGRAPH_COLLECTOR_JAR=str(ROOT / 'compiler-collectors/build/libs/kartograph-compiler-collectors.jar'),
            KARTOGRAPH_KOTLIN_CLASSPATH=compiler_cp, KARTOGRAPH_KOTLIN_COMPILER_CLASSPATH=compiler_cp,
            KARTOGRAPH_KOTLIN_GRADLE_CLASSPATH=os.pathsep.join(runtime['plugin']),
            KARTOGRAPH_019_HOME=str(old_distribution(args.old_archive.resolve(), work)))
        run('producer', [sys.executable, '-m', 'unittest', '-v', 'test_call_positions'],
            ROOT / 'compiler-collectors/tests', reports, env)
        tests_init = work / 'required-tests.gradle'
        # 환경으로 opt-in한 E2E는 이전 skip 결과를 up-to-date로 재사용하지 않는다.
        tests_init.write_text('''
            allprojects {
                tasks.withType(org.gradle.api.tasks.testing.Test).configureEach {
                    outputs.upToDateWhen { false }
                }
            }
        ''')
        commands = gradle + ['-I', str(tests_init)]
        for module, suites in SUITES.items():
            commands += [':' + module + ':test']
            for suite in suites:
                commands += ['--tests', 'dev.kartograph.' + PACKAGES[module] + '.' + suite]
        run('required-integration-tests', commands, ROOT, reports, env)
        tests = {}
        for module, suites in SUITES.items():
            tests[module] = junit_totals(ROOT / module / 'build/test-results/test',
                {'dev.kartograph.' + PACKAGES[module] + '.' + suite for suite in suites})
        result = {'status': 'passed', 'tests': tests, 'oldArchiveSha256': OLD_SHA256,
            'collectorSha256': sha256(Path(env['KARTOGRAPH_COLLECTOR_JAR'])),
            'runtimeSha256': {key: {Path(item).name: sha256(Path(item)) for item in runtime[key]}
                              for key in ('compiler', 'plugin')},
            'limitations': ['Explicit opt-in collectors only; this gate does not establish global PSI accuracy or performance superiority.']}
        result_file.write_text(json.dumps(result, indent=2) + '\n')
        return result


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--offline', action='store_true', help='use only cached Gradle dependencies; the pinned archive must already exist')
    parser.add_argument('--old-archive', type=Path, default=ROOT / 'build/compatibility/kartograph-0.19.0.zip')
    parser.add_argument('--report-dir', type=Path, default=ROOT / 'build/reports/compiler-call-positions')
    args = parser.parse_args()
    if args.offline and not args.old_archive.is_file():
        parser.error('--offline requires an existing --old-archive')
    try:
        print(json.dumps(verify(args), sort_keys=True))
        return 0
    except (OSError, ValueError, KeyError, ET.ParseError, zipfile.BadZipFile, subprocess.TimeoutExpired):
        print('Compiler call-position verification failed; inspect the selected local logs.', file=sys.stderr)
        return 1


if __name__ == '__main__': sys.exit(main())
