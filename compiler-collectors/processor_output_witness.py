#!/usr/bin/env python3
"""선택적 processor runner: raw 관찰을 성공한 전체 명령 및 명시된 입력에 연결한다."""
from __future__ import annotations

import argparse
import base64
import hashlib
import json
from pathlib import Path
import re
import subprocess
import sys


class EvidenceError(ValueError):
    pass


def digest(path: Path) -> str:
    value = hashlib.sha256()
    with path.open('rb') as source:
        for block in iter(lambda: source.read(65536), b''):
            value.update(block)
    return value.hexdigest()


def encoded_hash(value) -> str:
    return hashlib.sha256(json.dumps(value, sort_keys=True, separators=(',', ':')).encode()).hexdigest()


def artifact(path: Path) -> str:
    value = hashlib.sha256()
    for part in [b'file', digest(path).encode()]:
        value.update(len(part).to_bytes(4, 'big')); value.update(part)
    return value.hexdigest()


def portable(value: str) -> str:
    if not isinstance(value, str) or not value or value.startswith('/') or '\\' in value or ':' in value or any(ord(c) < 32 for c in value) or any(p in ('', '.', '..') for p in value.split('/')):
        raise EvidenceError('invalid portable path')
    return value


def locate(root: Path, value: str) -> Path:
    path = root / portable(value)
    if not path.resolve().is_relative_to(root) or any(p.is_symlink() for p in [path, *path.parents] if p.is_relative_to(root)):
        raise EvidenceError('symbolic or escaping path')
    return path


def inventory(path: Path) -> str:
    if path.is_symlink():
        raise EvidenceError('symbolic input')
    if path.is_file():
        return encoded_hash(['file', digest(path)])
    if not path.is_dir():
        raise EvidenceError('missing input')
    files = []
    for child in sorted(path.rglob('*')):
        if child.is_symlink():
            raise EvidenceError('symbolic input')
        if child.is_file():
            files.append([child.relative_to(path).as_posix(), digest(child)])
        elif not child.is_dir():
            raise EvidenceError('unsupported input type')
        if len(files) > 100_000:
            raise EvidenceError('input inventory exceeds limit')
    return encoded_hash(['directory', files])


def configuration(path: Path) -> tuple[dict, Path]:
    if path.stat().st_size > 1024 * 1024:
        raise EvidenceError('configuration exceeds limit')
    config = json.loads(path.read_text())
    root = Path(config['project']).resolve(strict=True)
    if config['kind'] not in ('javac', 'kapt', 'ksp') or not re.fullmatch(r'[A-Za-z0-9_.:-]{1,200}', config['scope']):
        raise EvidenceError('invalid processor scope')
    if not re.fullmatch(r'[A-Za-z_$][A-Za-z0-9_$]*(\.[A-Za-z_$][A-Za-z0-9_$]*)*', config['processor']):
        raise EvidenceError('invalid processor identity')
    if not config['inputs'] or not config['outputRoots'] or not config['command'] or not all(isinstance(v, str) and v for v in config['command']):
        raise EvidenceError('explicit inputs, outputs and command are required')
    for key in ('token', 'observations', 'receipt'):
        locate(root, config[key])
    for name in config['outputRoots']:
        if any(locate(root, config[key]).is_relative_to(locate(root, name)) for key in ('token', 'observations', 'receipt')):
            raise EvidenceError('control files must be outside generated output roots')
    if len(set(config[k] for k in ('token', 'observations', 'receipt'))) != 3:
        raise EvidenceError('control paths must be distinct')
    return config, root


def inputs(config: dict, root: Path) -> list[dict]:
    result = [{'path': portable(name), 'sha256': inventory(locate(root, name))} for name in config['inputs']]
    for key in ('collectorJar', 'processorJar'):
        result.append({'path': 'external/' + key, 'sha256': inventory(Path(config[key]).resolve(strict=True))})
    return result


def contract(config: dict) -> str:
    # 명령은 실행 승인된 로컬 설정이다. 원시 옵션·절대경로는 receipt에 기록하지 않는다.
    return encoded_hash({key: config[key] for key in ('scope', 'kind', 'processor', 'inputs', 'outputRoots', 'command', 'token', 'observations', 'receipt')})


def decode(value: str) -> str:
    try:
        decoded = base64.b64decode(value + '=' * (-len(value) % 4), altchars=b'-_', validate=True).decode('utf-8')
    except (ValueError, UnicodeError) as error:
        raise EvidenceError('invalid encoded output identity') from error
    if base64.urlsafe_b64encode(decoded.encode()).decode().rstrip('=') != value:
        raise EvidenceError('noncanonical output identity')
    return decoded


def observations(config: dict, root: Path, token: str) -> dict:
    path = locate(root, config['observations'])
    if not path.is_file() or path.stat().st_size > 16 * 1024 * 1024:
        raise EvidenceError('missing or oversized processor observations')
    rows = path.read_text(encoding='utf-8').splitlines()
    if not rows or rows[0] != 'format\tkartograph-processor-outputs\t1' or len(rows) > 100_007:
        raise EvidenceError('invalid processor observation format')
    headers, outputs = {}, []
    for row in rows[1:]:
        fields = row.split('\t')
        if fields[0] == 'output' and len(fields) == 5:
            _, kind, observation, name, sha = fields
            if (kind, observation) not in {('source', 'api'), ('class', 'api'), ('resource', 'api'), ('file', 'callback-scope')} or not re.fullmatch('[0-9a-f]{64}', sha):
                raise EvidenceError('invalid processor output row')
            name = portable(decode(name)); output = locate(root, name)
            if not any(output.is_relative_to(locate(root, parent)) for parent in config['outputRoots']):
                raise EvidenceError('output outside declared generated roots')
            if not output.is_file() or digest(output) != sha:
                raise EvidenceError('processor output changed')
            outputs.append({'path': name, 'kind': kind, 'observation': observation, 'sha256': sha})
        elif len(fields) == 2 and fields[0] in ('kind', 'token', 'processor', 'processorArtifact', 'collectorArtifact') and fields[0] not in headers:
            headers[fields[0]] = fields[1]
        else:
            raise EvidenceError('unknown or duplicate processor observation row')
    expected = {'kind': config['kind'], 'token': token, 'processor': base64.urlsafe_b64encode(config['processor'].encode()).decode().rstrip('='),
                'processorArtifact': artifact(Path(config['processorJar'])), 'collectorArtifact': artifact(Path(config['collectorJar']))}
    if headers != expected or len({o['path'] for o in outputs}) != len(outputs):
        raise EvidenceError('processor invocation identity mismatch')
    return {'processor': config['processor'], 'kind': config['kind'], 'outputs': sorted(outputs, key=lambda o: o['path']),
            'rawSha256': digest(path), 'processorArtifact': expected['processorArtifact'], 'collectorArtifact': expected['collectorArtifact']}


def record(path: Path, logs: Path) -> dict:
    config, root = configuration(path)
    receipt = locate(root, config['receipt'])
    receipt.unlink(missing_ok=True)
    locate(root, config['observations']).unlink(missing_ok=True)
    before = inputs(config, root)
    token = encoded_hash([contract(config), before])
    pending = locate(root, config['token']); pending.parent.mkdir(parents=True, exist_ok=True); pending.write_text(token)
    logs.mkdir(parents=True, exist_ok=True)
    try:
        with (logs / 'build.stdout').open('wb') as stdout, (logs / 'build.stderr').open('wb') as stderr:
            result = subprocess.run(config['command'], cwd=root, stdout=stdout, stderr=stderr, timeout=600)
        if result.returncode != 0:
            raise EvidenceError('processor build failed; no completed receipt')
        if inputs(config, root) != before or pending.read_text() != token:
            raise EvidenceError('processor inputs changed during the build')
        observed = observations(config, root, token)
        value = {'format': 'kartograph-processor-output-witness', 'version': 1, 'scope': config['scope'], 'token': token,
                 'configurationSha256': contract(config), 'inputs': before, 'observation': observed, 'buildExit': 0}
        receipt.parent.mkdir(parents=True, exist_ok=True)
        receipt.write_text(json.dumps(value, sort_keys=True, indent=2) + '\n')
        return value
    finally:
        pending.unlink(missing_ok=True)


def verify(path: Path) -> dict:
    config, root = configuration(path)
    receipt = locate(root, config['receipt'])
    if not receipt.is_file() or receipt.stat().st_size > 32 * 1024 * 1024:
        raise EvidenceError('missing or oversized completed receipt')
    value = json.loads(receipt.read_text())
    current = inputs(config, root)
    token = encoded_hash([contract(config), current])
    if value.get('format') != 'kartograph-processor-output-witness' or value.get('version') != 1 or value.get('buildExit') != 0 or value.get('scope') != config['scope'] or value.get('configurationSha256') != contract(config) or value.get('inputs') != current or value.get('token') != token:
        raise EvidenceError('completed processor receipt is stale')
    if observations(config, root, token) != value.get('observation'):
        raise EvidenceError('completed processor observations changed')
    return {'status': 'matched', 'scope': config['scope'], 'kind': config['kind'], 'outputs': len(value['observation']['outputs'])}


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('action', choices=['record', 'verify'])
    parser.add_argument('--config', type=Path, required=True)
    parser.add_argument('--logs', type=Path)
    args = parser.parse_args()
    try:
        if args.action == 'record':
            record(args.config, args.logs or args.config.parent / 'processor-build-logs')
        print(json.dumps(verify(args.config), sort_keys=True))
        return 0
    except (EvidenceError, OSError, ValueError, KeyError, TypeError, subprocess.TimeoutExpired):
        print(json.dumps({'status': 'stale', 'reason': 'processor evidence unavailable, invalid, failed or changed'}))
        return 1


if __name__ == '__main__':
    sys.exit(main())
