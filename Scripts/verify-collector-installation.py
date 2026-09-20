#!/usr/bin/env python3
"""압축 해제한 collector·runner만 소비하는 별도 javac 프로젝트로 설치 계약을 검사한다."""
from __future__ import annotations

import argparse
import hashlib
import json
import os
from pathlib import Path, PurePosixPath
import re
import shutil
import stat
import subprocess
import sys
import zipfile


def unpack(archive: Path, version: str, destination: Path) -> Path:
    if not re.fullmatch(r'[0-9]+\.[0-9]+\.[0-9]+', version):
        raise ValueError('expected a stable collector version')
    prefix = 'kartograph-compiler-collectors-' + version
    with zipfile.ZipFile(archive) as source:
        members = source.infolist()
        if len(members) > 1000 or sum(entry.file_size for entry in members) > 64 * 1024 * 1024:
            raise ValueError('collector archive exceeds installation limits')
        names = set()
        for entry in members:
            path = PurePosixPath(entry.filename)
            mode = entry.external_attr >> 16
            if (entry.filename in names or not path.parts or path.parts[0] != prefix or path.is_absolute()
                    or '..' in path.parts or '\\' in entry.filename or ':' in entry.filename
                    or any(ord(c) < 32 for c in entry.filename)
                    or stat.S_ISLNK(mode) or stat.S_IFMT(mode) not in (0, stat.S_IFREG, stat.S_IFDIR)):
                raise ValueError('invalid collector archive member')
            names.add(entry.filename)
        source.extractall(destination)
    installed = destination / prefix
    required = ['VERSION', 'README.md', 'LICENSE', 'THIRD_PARTY_NOTICES.md',
                'processor_output_witness.py', 'processor_output_cache.gradle', 'lib/kartograph-compiler-collectors.jar']
    if any(not (installed / name).is_file() for name in required):
        raise ValueError('collector distribution is incomplete')
    if (installed / 'VERSION').read_text().strip() != version or '@VERSION@' in (installed / 'README.md').read_text():
        raise ValueError('collector distribution version mismatch')
    with zipfile.ZipFile(installed / 'lib/kartograph-compiler-collectors.jar') as jar:
        manifest = jar.read('META-INF/MANIFEST.MF').decode('utf-8')
        if ('Implementation-Version: ' + version) not in manifest.splitlines():
            raise ValueError('collector JAR version mismatch')
        for name in ['dev/kartograph/collectors/OutputRecordingProcessor.class',
                     'dev/kartograph/collectors/RecordingSymbolProcessorProvider.class',
                     'META-INF/services/com.google.devtools.ksp.processing.SymbolProcessorProvider',
                     'META-INF/kartograph/LICENSE', 'META-INF/kartograph/THIRD_PARTY_NOTICES.md']:
            if name not in jar.namelist():
                raise ValueError('collector JAR is incomplete')
    return installed


def run(args: list[str], cwd: Path, log: Path, expected: int = 0) -> None:
    with log.open('wb') as output:
        result = subprocess.run(args, cwd=cwd, stdout=output, stderr=subprocess.STDOUT, timeout=120)
    if result.returncode != expected:
        raise ValueError('installed collector check failed; inspect the selected evidence directory')


def verify(archive: Path, version: str, evidence: Path) -> dict:
    evidence.mkdir(parents=True, exist_ok=False)
    installed = unpack(archive, version, evidence / 'installed')
    fixture = evidence / 'consumer'
    fixture.mkdir()
    sources = fixture / 'processor-src'
    sources.mkdir()
    repository = Path(__file__).resolve().parent.parent
    for name in ('OutputProcessor.java', 'OutputBinary.java'):
        shutil.copyfile(repository / 'compiler-collectors/tests/fixtures' / name, sources / name)
    java_home = os.environ.get('JAVA_HOME')
    javac = str(Path(java_home) / 'bin/javac') if java_home else 'javac'
    jar = str(Path(java_home) / 'bin/jar') if java_home else 'jar'
    classes = fixture / 'processor-classes'
    run([javac, '--release', '17', '-d', str(classes), *map(str, sorted(sources.glob('*.java')))], fixture, evidence / 'processor-compile.log')
    processor = fixture / 'processor.jar'
    run([jar, '--create', '--file', str(processor), '-C', str(classes), '.'], fixture, evidence / 'processor-package.log')
    project = fixture / 'app'
    for directory in ['src', 'classes', 'generated', 'direct']:
        (project / directory).mkdir(parents=True)
    source = project / 'src/Input.java'
    source.write_text('package fixture; class Input { int value(){return Generated.value();} }\n')
    (project / 'direct/handwritten.txt').write_text('handwritten control')
    collector = installed / 'lib/kartograph-compiler-collectors.jar'
    options = {'kartograph.processor': 'fixture.OutputProcessor', 'kartograph.outputs.kind': 'javac',
               'kartograph.outputs.root': project.as_uri(), 'kartograph.outputs.output': (project / '.evidence/raw.tsv').as_uri(),
               'kartograph.outputs.token': (project / '.evidence/token').as_uri(), 'kartograph.outputs.directRoot': str(project / 'direct')}
    command = [javac, '--release', '17', '-processorpath', os.pathsep.join(map(str, [collector, processor])),
               '-processor', 'dev.kartograph.collectors.OutputRecordingProcessor',
               *['-A' + key + '=' + value for key, value in options.items()], '-d', str(project / 'classes'),
               '-s', str(project / 'generated'), str(source)]
    config = {'project': str(project), 'scope': 'installed:main', 'kind': 'javac', 'processor': 'fixture.OutputProcessor',
              'collectorJar': str(collector), 'processorJar': str(processor), 'inputs': ['src'],
              'outputRoots': ['classes', 'generated', 'direct'], 'token': '.evidence/token', 'observations': '.evidence/raw.tsv',
              'receipt': '.evidence/witness.json', 'command': command}
    config_file = evidence / 'consumer-config-local.json'
    config_file.write_text(json.dumps(config, indent=2) + '\n')
    runner = [sys.executable, str(installed / 'processor_output_witness.py')]
    run([*runner, 'record', '--config', str(config_file), '--logs', str(evidence / 'success-logs')], project, evidence / 'record.log')
    run([*runner, 'verify', '--config', str(config_file)], project, evidence / 'verify.log')
    receipt = json.loads((project / config['receipt']).read_text())
    outputs = receipt['observation']['outputs']
    expected = {('source', 'api'), ('class', 'api'), ('resource', 'api'), ('file', 'callback-scope')}
    if len(outputs) != 4 or {(row['kind'], row['observation']) for row in outputs} != expected:
        raise ValueError('installed collector output kinds differ')
    if not (project / 'classes/fixture/Input.class').is_file() or any('handwritten' in row['path'] for row in outputs):
        raise ValueError('installed collector compile or handwritten control failed')
    # 성공 출력·원시 관찰은 실패 대조 전에 별도 보존한다.
    successful = evidence / 'successful-consumer'
    shutil.copytree(project, successful)
    success_config_file = evidence / 'success-config-local.json'
    success_config_file.write_text(json.dumps({**config, 'project': str(successful)}, indent=2) + '\n')
    for label, path in [('source', source), ('output', project / outputs[0]['path']), ('raw', project / config['observations'])]:
        original = path.read_bytes()
        path.write_bytes(original + b'changed')
        run([*runner, 'verify', '--config', str(config_file)], project, evidence / (label + '-stale.log'), 1)
        path.write_bytes(original)
    config['command'].append('-Afixture.mode=broken')
    config_file.write_text(json.dumps(config, indent=2) + '\n')
    run([*runner, 'record', '--config', str(config_file), '--logs', str(evidence / 'failure-logs')], project, evidence / 'failed-build.log', 1)
    if (project / config['receipt']).exists():
        raise ValueError('failed installed build retained a successful receipt')
    run([*runner, 'verify', '--config', str(success_config_file)], successful, evidence / 'preserved-success.log')
    result = {'status': 'passed', 'version': version, 'archiveSha256': hashlib.sha256(archive.read_bytes()).hexdigest(),
              'consumer': 'independent-javac17', 'outputKinds': sorted(kind for kind, _ in expected),
              'staleControls': ['source', 'output', 'raw'], 'failedBuildReceiptRemoved': True,
              'limitations': ['Installed javac adapter and standalone runner only; KAPT/KSP and Gradle cache require the integration suite.']}
    (evidence / 'verification.json').write_text(json.dumps(result, indent=2) + '\n')
    return result


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--archive', type=Path, required=True)
    parser.add_argument('--version', required=True)
    parser.add_argument('--evidence', type=Path, required=True)
    args = parser.parse_args()
    try:
        print(json.dumps(verify(args.archive.resolve(strict=True), args.version, args.evidence.resolve()), sort_keys=True))
        return 0
    except (OSError, ValueError, KeyError, zipfile.BadZipFile, subprocess.TimeoutExpired):
        print('Collector installation verification failed; inspect the selected evidence directory.', file=sys.stderr)
        return 1


if __name__ == '__main__':
    sys.exit(main())
