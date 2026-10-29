#!/usr/bin/env python3
"""공개 compiler·0.19 배포본으로 호출 위치의 수집→저장→조회 계약을 skip 없이 검증한다."""
from __future__ import annotations

import argparse
import hashlib
import types
import json
import os
import re
from pathlib import Path
import subprocess
import sys
import tempfile
import urllib.request
import xml.etree.ElementTree as ET
import zipfile

ROOT = Path(__file__).resolve().parent.parent
MODULE_HELPER = ROOT / 'Scripts/call_position_module_runtime.py'
module_runtime = types.ModuleType('call_position_module_runtime')
module_runtime.__file__ = str(MODULE_HELPER)
MODULE_HELPER_BYTES = MODULE_HELPER.read_bytes()
MODULE_HELPER_SHA256 = hashlib.sha256(MODULE_HELPER_BYTES).hexdigest()
exec(compile(MODULE_HELPER_BYTES, str(MODULE_HELPER), 'exec'), module_runtime.__dict__)
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


def collector_kotlin_version(build_file: Path) -> str:
    # 실행 대상 collector 빌드의 단일 명시 pin만 사용한다. 추측이나 최신 버전 선택은 하지 않는다.
    pins = re.findall(r'^val kotlinCompilerVersion = "([0-9]+\.[0-9]+\.[0-9]+)"$',
                      build_file.read_text(encoding='utf-8'), re.MULTILINE)
    if len(pins) != 1:
        raise ValueError('collector build must declare exactly one literal Kotlin compiler version')
    return pins[0]


def exact_runtime_artifact(paths: list[str], artifact: str, version: str) -> Path:
    candidates = [Path(item) for item in paths if Path(item).name.startswith(artifact + '-')
                  and Path(item).suffix == '.jar']
    expected = artifact + '-' + version + '.jar'
    if len(candidates) != 1 or candidates[0].name != expected or not candidates[0].is_file():
        raise ValueError('expected exactly one ' + expected + ' runtime artifact')
    return candidates[0]


def runtime_receipt(runtime: dict, version: str) -> dict:
    if not isinstance(runtime, dict) or set(runtime) != {'compiler', 'plugin', 'buildTools'}:
        raise ValueError('unexpected verification runtime fields')
    sealed = {}
    for role, paths in runtime.items():
        if not isinstance(paths, list) or not paths or any(not isinstance(item, str) or not item for item in paths):
            raise ValueError('verification runtime must contain explicit ordered file paths')
        if len(paths) != len(set(paths)) or len({Path(item).name for item in paths}) != len(paths):
            raise ValueError('verification runtime contains duplicate file identities')
        if any(not Path(item).is_file() for item in paths):
            raise ValueError('resolved verification runtime contains missing files')
        sealed[role] = [{'ordinal': index, 'artifact': Path(item).name, 'sha256': sha256(Path(item))}
                        for index, item in enumerate(paths)]
    for name in ('kotlin-compiler-embeddable', 'kotlin-stdlib'):
        exact_runtime_artifact(runtime['compiler'], name, version)
    for name in ('kotlin-build-tools-compat', 'kotlin-build-tools-impl'):
        exact_runtime_artifact(runtime['buildTools'], name, version)
    auxiliary_prefixes = tuple('kotlin-gradle-plugin-' + name + '-'
                               for name in ('annotations', 'api', 'idea', 'idea-proto'))
    # primary JAR의 잘못된 버전 표기를 후보 필터에서 숨기지 않는다.
    plugin_names = [Path(item).name for item in runtime['plugin']
                    if Path(item).name.startswith('kotlin-gradle-plugin-') and Path(item).suffix == '.jar'
                    and not Path(item).name.startswith(auxiliary_prefixes)]
    if len(plugin_names) != 1 or not re.fullmatch(
            r'kotlin-gradle-plugin-' + re.escape(version) + r'(?:-gradle[0-9]+)?\.jar', plugin_names[0]):
        raise ValueError('resolved Kotlin Gradle plugin differs from the collector build pin')
    return {'schemaVersion': 1, 'compilerVersion': version, 'artifacts': sealed}


def normalized_closures(report: dict) -> dict:
    # 보고용에는 machine-local 경로를 제외하고 해석된 순서·좌표·의존성과 bytes만 남긴다.
    return {role: {'roots': value['roots'], 'graph': value['graph'],
                   'artifacts': [dict(ordinal=index, **{k: v for k, v in item.items() if k != 'path'})
                                 for index, item in enumerate(value['artifacts'])]}
            for role, value in report['roles'].items()}


def resolved_runtime(report: dict, version: str) -> dict:
    """Gradle이 선택한 세 closure의 완전한 파일 대응과 현재 bytes를 검증한다."""
    def require(condition, message):
        if not condition:
            raise ValueError(message)
    require(isinstance(report, dict) and set(report) in (
            {'schemaVersion', 'compilerVersion', 'requestedBuildTools', 'roles'},
            {'schemaVersion', 'compilerVersion', 'requestedBuildTools', 'roles', 'moduleMirror'},
            {'schemaVersion', 'compilerVersion', 'requestedBuildTools', 'roles', 'moduleMirror', 'metadataAcquisition'}),
            'unexpected resolved runtime fields')
    require(type(report['schemaVersion']) is int and report['schemaVersion'] == 1 and
            report['compilerVersion'] == version, 'resolved runtime version mismatch')
    roots = ['org.jetbrains.kotlin:kotlin-build-tools-' + name + ':' + version for name in ('compat', 'impl')]
    require(report['requestedBuildTools'] == roots and isinstance(report['roles'], dict) and
            set(report['roles']) == {'compiler', 'plugin', 'buildTools'}, 'resolved runtime roles mismatch')
    runtime = {}
    for role, config in report['roles'].items():
        require(isinstance(config, dict) and set(config) == {'roots', 'graph', 'artifacts'}, 'invalid closure fields')
        graph, selected_roots, artifacts = config['graph'], config['roots'], config['artifacts']
        require(isinstance(graph, dict) and 0 < len(graph) <= 512 and
                all(isinstance(k, str) and len(k.split(':')) == 3 and all(k.split(':')) for k in graph),
                'invalid selected dependency graph')
        require(isinstance(selected_roots, list) and selected_roots and
                all(isinstance(x, str) for x in selected_roots) and selected_roots == sorted(set(selected_roots)),
                'invalid selected dependency roots')
        require(all(isinstance(v, list) and all(isinstance(x, str) and x in graph for x in v) and
                    v == sorted(set(v)) for v in graph.values()), 'invalid selected dependency children')
        reached, pending = set(), list(selected_roots)
        while pending:
            node = pending.pop()
            require(node in graph, 'selected dependency root is absent')
            if node not in reached:
                reached.add(node)
                pending.extend(graph[node])
        require(reached == set(graph), 'selected dependency closure is incomplete')
        require(isinstance(artifacts, list) and 0 < len(artifacts) <= 256, 'invalid runtime artifact count')
        paths, coordinates = [], set()
        for item in artifacts:
            require(isinstance(item, dict) and set(item) ==
                    {'group', 'name', 'version', 'classifier', 'extension', 'path', 'bytes', 'sha256'},
                    'invalid resolved artifact fields')
            require(all(isinstance(item[k], str) and item[k] for k in
                        ('group', 'name', 'version', 'extension', 'path', 'sha256')) and
                    isinstance(item['classifier'], str) and item['extension'] == 'jar', 'invalid artifact identity')
            require(type(item['bytes']) is int and 0 < item['bytes'] <= 128 * 1024 * 1024 and
                    re.fullmatch('[0-9a-f]{64}', item['sha256']), 'invalid artifact bytes receipt')
            coordinate = ':'.join(item[k] for k in ('group', 'name', 'version'))
            require(coordinate in graph, 'resolved artifact is absent from selected graph')
            coordinates.add(coordinate)
            path = Path(item['path'])
            expected = item['name'] + '-' + item['version'] + (
                '-' + item['classifier'] if item['classifier'] else '') + '.jar'
            require(path.is_absolute() and path.is_file() and not path.is_symlink() and path.name == expected,
                    'resolved artifact path does not match its identity')
            require(path.stat().st_size == item['bytes'] and sha256(path) == item['sha256'],
                    'resolved artifact bytes changed')
            if item['group'] == 'org.jetbrains.kotlin' and item['name'] != 'kotlin-reflect':
                require(item['version'] == version, 'mixed Kotlin runtime versions')
            paths.append(str(path))
        require(paths == sorted(set(paths)), 'resolved artifact order or identity changed')
        # 실제 KGP marker와 BOM만 JAR 없는 metadata 모듈이다. 나머지는 누락을 허용하지 않는다.
        metadata_only = {'org.jetbrains.kotlin.jvm:org.jetbrains.kotlin.jvm.gradle.plugin:' + version,
                         'org.jetbrains.kotlin:kotlin-gradle-plugins-bom:' + version} if role == 'plugin' else set()
        require(set(graph) - coordinates <= metadata_only, 'selected module artifact is missing')
        if role == 'buildTools':
            require(selected_roots == roots and coordinates == set(graph),
                    'build-tools closure requires every selected module artifact')
        runtime[role] = paths
    runtime_receipt(runtime, version)
    tooling = exact_runtime_artifact(runtime['buildTools'], 'kotlin-tooling-core', version)
    with zipfile.ZipFile(tooling) as archive:
        entries = archive.infolist()
        require(len(entries) <= 100000 and sum(x.filename ==
                'org/jetbrains/kotlin/tooling/core/KotlinToolingVersion.class' for x in entries) == 1,
                'build-tools closure lacks the required tooling class')
        require(archive.getinfo('org/jetbrains/kotlin/tooling/core/KotlinToolingVersion.class').file_size > 0,
                'build-tools tooling class is empty')
    if 'moduleMirror' in report:
        module_runtime.validate_module_capture(report, version)
    if 'metadataAcquisition' in report:
        module_runtime.validate_metadata_acquisition(report)
    return runtime


def write_build_tools_manifest(paths: list[str], version: str, destination: Path) -> None:
    rows = ['kartograph-build-tools-runtime\t1\t' + version]
    for ordinal, item in enumerate(paths):
        path = Path(item)
        if any(c in path.name for c in '\t\r\n'):
            raise ValueError('invalid runtime artifact filename')
        rows.append('\t'.join((str(ordinal), path.name, str(path.stat().st_size), sha256(path))))
    destination.write_text('\n'.join(rows) + '\n', encoding='utf-8')


def observed_kotlin_version(output: str, expected: str) -> None:
    versions = re.findall(r'(?m)^info: kotlinc-jvm ([^ ]+) ', output)
    if versions != [expected]:
        raise ValueError('observed compiler version differs from the collector build pin')


def probe_runtime(jdk: Path, compiler_cp: str, kotlin_version: str, receipt: dict, reports: Path) -> None:
    receipt['observedCompilerVersion'] = None
    receipt['versionProbe'] = {'state': 'PLANNED', 'exitCode': None, 'timeoutSeconds': 30,
        'argv': ['$JDK17/bin/java', '-cp', '$ORDERED_COMPILER_RUNTIME',
                 'org.jetbrains.kotlin.cli.jvm.K2JVMCompiler', '-version']}
    receipt_file = reports / 'runtime-receipt.json'
    receipt_file.write_text(json.dumps(receipt, indent=2) + '\n')
    try:
        observed = subprocess.run([str(jdk / 'bin/java'), '-cp', compiler_cp,
            'org.jetbrains.kotlin.cli.jvm.K2JVMCompiler', '-version'], capture_output=True, text=True, timeout=30)
    except subprocess.TimeoutExpired as failure:
        def partial(value):
            return value.decode('utf-8', errors='replace') if isinstance(value, bytes) else (value or '')
        (reports / 'compiler-version.log').write_text(partial(failure.stdout) + partial(failure.stderr))
        receipt['versionProbe'].update(state='TIMED_OUT', failureType='TimeoutExpired')
        raise
    except OSError as failure:
        receipt['versionProbe'].update(state='LAUNCH_FAILED', failureType=type(failure).__name__)
        raise
    else:
        receipt['versionProbe'].update(state='COMPLETED', exitCode=observed.returncode)
        (reports / 'compiler-version.log').write_text(observed.stdout + observed.stderr)
    finally:
        receipt_file.write_text(json.dumps(receipt, indent=2) + '\n')
    if observed.returncode != 0:
        raise ValueError('compiler version probe failed')
    observed_kotlin_version(observed.stdout + observed.stderr, kotlin_version)
    receipt['observedCompilerVersion'] = kotlin_version
    receipt_file.write_text(json.dumps(receipt, indent=2) + '\n')


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
    original_metadata = getattr(args, 'original_module_metadata', None)
    env = module_runtime.metadata_environment(dict(os.environ), original_metadata, args.offline)
    jdk = Path(env.get('KARTOGRAPH_JDK17_HOME', env.get('JAVA_HOME', ''))).resolve()
    if not (jdk / 'bin/javac').is_file():
        raise ValueError('set JAVA_HOME or KARTOGRAPH_JDK17_HOME to the approved JDK 17')
    version = subprocess.run([str(jdk / 'bin/javac'), '-version'], capture_output=True, text=True, timeout=30)
    if version.returncode != 0 or not version.stdout.strip().startswith('javac 17.'):
        raise ValueError('the call-position producer gate requires JDK 17')
    env['KARTOGRAPH_JDK17_HOME'] = str(jdk)
    kotlin_version = collector_kotlin_version(ROOT / 'compiler-collectors/build.gradle.kts')
    env['KARTOGRAPH_CALL_KOTLIN_VERSION'] = kotlin_version
    if not args.gradle.is_file():
        raise ValueError('explicit Gradle launcher is missing')
    gradle = [str(args.gradle.resolve()), '--no-daemon'] + (['--offline'] if args.offline else [])
    with tempfile.TemporaryDirectory(prefix='kartograph-call-positions-') as temp:
        work = Path(temp)
        with module_runtime.original_metadata_input(original_metadata, work / 'admitted-original-metadata', args.offline) as metadata_capture:
            env = module_runtime.metadata_environment(env, original_metadata, args.offline, metadata_capture)
            expected_acquisition = module_runtime.metadata_acquisition(metadata_capture)
            (reports / 'metadata-acquisition.json').write_text(json.dumps(expected_acquisition, indent=2) + '\n')
            runtime_file = work / 'runtime.json'
            env['KARTOGRAPH_CALL_RUNTIME_FILE'] = str(runtime_file)
            init = work / 'collector-runtime.gradle'
            resolver = ROOT / 'Scripts/resolve-call-position-runtime.gradle'
            init.write_text(resolver.read_text(encoding='utf-8').replace('@@KOTLIN_VERSION@@', kotlin_version))
            run('resolve-verified-runtime', gradle + ['--no-configuration-cache', '-p', 'compiler-collectors', '-I', str(init),
                'callPositionVerificationRuntime'], ROOT, reports, env)
            resolution = module_runtime.strict_json(module_runtime.stable_bytes(runtime_file, 8 * 1024 * 1024))
            runtime = resolved_runtime(resolution, kotlin_version)
            acquisition = module_runtime.validate_metadata_acquisition(resolution, expected_acquisition)
            receipt = runtime_receipt(runtime, kotlin_version)
            receipt['selectedClosures'] = normalized_closures(resolution)
            receipt['metadataAcquisition'] = acquisition
            receipt['resolverSha256'] = sha256(resolver)
            tools_manifest = reports / 'build-tools-runtime.tsv'
            module_receipt = reports / 'build-tools-module-runtime.json'
            mirror = work / 'build-tools-module-mirror'
            mirror_rows, module_tools = module_runtime.prepare_mirror(resolution, kotlin_version, mirror, module_receipt)
            verifier = ROOT / 'Scripts/verify-call-position-modules.gradle'
            write_build_tools_manifest(module_tools, kotlin_version, tools_manifest)
            receipt['buildToolsExecutionRegime'] = 'original-metadata-module-mirror-v1'
            receipt['buildToolsModuleReceiptSha256'] = sha256(module_receipt)
            receipt['buildToolsModuleHelperSha256'] = MODULE_HELPER_SHA256
            receipt['buildToolsVerifierSha256'] = sha256(verifier)
            receipt['buildToolsManifestSha256'] = sha256(tools_manifest)
            compiler_cp = os.pathsep.join(runtime['compiler'])
            receipt['collectorSha256'] = sha256(ROOT / 'compiler-collectors/build/libs/kartograph-compiler-collectors.jar')
            receipt['collectorBuildSha256'] = sha256(ROOT / 'compiler-collectors/build.gradle.kts')
            probe_runtime(jdk, compiler_cp, kotlin_version, receipt, reports)
            env.update(KARTOGRAPH_COLLECTOR_JAR=str(ROOT / 'compiler-collectors/build/libs/kartograph-compiler-collectors.jar'),
                KARTOGRAPH_KOTLIN_CLASSPATH=compiler_cp, KARTOGRAPH_KOTLIN_COMPILER_CLASSPATH=compiler_cp,
                KARTOGRAPH_KOTLIN_GRADLE_CLASSPATH=os.pathsep.join(runtime['plugin']),
                KARTOGRAPH_KOTLIN_BUILD_TOOLS_CLASSPATH=os.pathsep.join(module_tools),
                KARTOGRAPH_KOTLIN_BUILD_TOOLS_REPOSITORY=str(mirror),
                KARTOGRAPH_KOTLIN_BUILD_TOOLS_MODULE_RECEIPT=str(module_receipt),
                KARTOGRAPH_KOTLIN_BUILD_TOOLS_VERIFY_SCRIPT=str(verifier),
                KARTOGRAPH_KOTLIN_BUILD_TOOLS_VERIFY_SHA256=receipt['buildToolsVerifierSha256'],
                KARTOGRAPH_KOTLIN_BUILD_TOOLS_MANIFEST=str(tools_manifest),
                KARTOGRAPH_019_HOME=str(old_distribution(args.old_archive.resolve(), work)))
            module_runtime.verify_mirror(mirror, mirror_rows)
            module_runtime.verify_original_metadata(metadata_capture)
            module_runtime.validate_metadata_acquisition(resolution, expected_acquisition)
            if sha256(module_receipt) != receipt['buildToolsModuleReceiptSha256'] or \
                    sha256(MODULE_HELPER) != receipt['buildToolsModuleHelperSha256'] or \
                    sha256(verifier) != receipt['buildToolsVerifierSha256'] or \
                    resolved_runtime(resolution, kotlin_version) != runtime or \
                    runtime_receipt(runtime, kotlin_version)['artifacts'] != receipt['artifacts'] or \
                    sha256(tools_manifest) != receipt['buildToolsManifestSha256']:
                raise ValueError('verification runtime changed before producer execution')
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
            module_runtime.verify_mirror(mirror, mirror_rows)
            module_runtime.verify_original_metadata(metadata_capture)
            module_runtime.validate_metadata_acquisition(resolution, expected_acquisition)
            if sha256(module_receipt) != receipt['buildToolsModuleReceiptSha256'] or \
                    sha256(MODULE_HELPER) != receipt['buildToolsModuleHelperSha256'] or \
                    sha256(verifier) != receipt['buildToolsVerifierSha256'] or \
                    resolved_runtime(resolution, kotlin_version) != runtime or \
                    sha256(tools_manifest) != receipt['buildToolsManifestSha256'] or \
                    sha256(resolver) != receipt['resolverSha256'] or \
                    runtime_receipt(runtime, kotlin_version)['artifacts'] != receipt['artifacts'] or \
                    sha256(Path(env['KARTOGRAPH_COLLECTOR_JAR'])) != receipt['collectorSha256'] or \
                    sha256(ROOT / 'compiler-collectors/build.gradle.kts') != receipt['collectorBuildSha256']:
                raise ValueError('verification runtime or collector changed during the gate')
            result = {'status': 'passed', 'tests': tests, 'oldArchiveSha256': OLD_SHA256,
                'compilerVersion': kotlin_version, 'runtimeReceiptSha256': sha256(reports / 'runtime-receipt.json'),
                'collectorSha256': sha256(Path(env['KARTOGRAPH_COLLECTOR_JAR'])),
                'runtimeSha256': {key: {Path(item).name: sha256(Path(item)) for item in runtime[key]}
                                  for key in ('compiler', 'plugin', 'buildTools')},
                'limitations': ['Explicit opt-in collectors only; this gate does not establish global PSI accuracy or performance superiority.']}
    # metadata 검증과 임시 입력 정리를 포함한 모든 context가 정상 종료한 뒤에만 성공을 공개한다.
    result_file.write_text(json.dumps(result, indent=2) + '\n')
    return result


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--gradle', type=Path, default=ROOT / 'gradlew',
                        help='explicit Gradle launcher; defaults to the repository wrapper')
    parser.add_argument('--offline', action='store_true', help='use only cached Gradle dependencies; the pinned archive must already exist')
    parser.add_argument('--original-module-metadata', type=Path,
                        help='explicit portable original metadata bundle; requires --offline and fixed content pins')
    parser.add_argument('--old-archive', type=Path, default=ROOT / 'build/compatibility/kartograph-0.19.0.zip')
    parser.add_argument('--report-dir', type=Path, default=ROOT / 'build/reports/compiler-call-positions')
    args = parser.parse_args()
    if args.original_module_metadata is not None and not args.offline:
        parser.error('--original-module-metadata requires --offline')
    if args.offline and not args.old_archive.is_file():
        parser.error('--offline requires an existing --old-archive')
    try:
        print(json.dumps(verify(args), sort_keys=True))
        return 0
    except (OSError, ValueError, KeyError, ET.ParseError, zipfile.BadZipFile, subprocess.TimeoutExpired):
        print('Compiler call-position verification failed; inspect the selected local logs.', file=sys.stderr)
        return 1


if __name__ == '__main__': sys.exit(main())
