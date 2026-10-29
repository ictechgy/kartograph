"""고정된 공식 build-tools metadata와 실제 모듈 해석을 fixture 입력에 묶는다."""
import hashlib
import json
import os
from pathlib import Path, PurePosixPath
import re
import stat
from contextlib import contextmanager

MIB = 1024 * 1024
EXPECTED_TOOLS = {
    'org.jetbrains.kotlin:' + name + ':2.4.20' for name in (
        'kotlin-build-tools-api', 'kotlin-build-tools-compat', 'kotlin-build-tools-cri-impl',
        'kotlin-build-tools-impl', 'kotlin-compiler-embeddable', 'kotlin-compiler-runner',
        'kotlin-daemon-client', 'kotlin-daemon-embeddable', 'kotlin-script-runtime',
        'kotlin-stdlib', 'kotlin-tooling-core')
} | {'org.jetbrains.kotlin:kotlin-reflect:1.6.10',
     'org.jetbrains.kotlinx:kotlinx-coroutines-core-jvm:1.8.0', 'org.jetbrains:annotations:13.0'}


def require(condition, reason):
    if not condition:
        raise ValueError(reason)


def stable_bytes(path: Path, limit: int) -> bytes:
    path = Path(path)
    parent = os.open('/', os.O_RDONLY | os.O_DIRECTORY | os.O_NOFOLLOW)
    try:
        require(path.is_absolute() and '..' not in path.parts, 'absolute canonical runtime input required')
        for part in path.parent.parts[1:]:
            next_fd = os.open(part, os.O_RDONLY | os.O_DIRECTORY | os.O_NOFOLLOW, dir_fd=parent)
            os.close(parent)
            parent = next_fd
        before = os.stat(path.name, dir_fd=parent, follow_symlinks=False)
        identity = lambda info: (info.st_dev, info.st_ino, info.st_mode, info.st_size, info.st_mtime_ns, info.st_ctime_ns)
        require(stat.S_ISREG(before.st_mode) and 0 <= before.st_size <= limit, 'bounded regular runtime input required')
        fd = os.open(path.name, os.O_RDONLY | os.O_NONBLOCK | os.O_NOFOLLOW, dir_fd=parent)
        with os.fdopen(fd, 'rb') as stream:
            require(identity(os.fstat(stream.fileno())) == identity(before), 'runtime input changed before open')
            data = stream.read(limit + 1)
            require(len(data) == before.st_size and identity(os.fstat(stream.fileno())) == identity(before),
                    'runtime input changed while reading')
        require(identity(os.stat(path.name, dir_fd=parent, follow_symlinks=False)) == identity(before),
                'runtime input changed after read')
        return data
    finally:
        os.close(parent)


def digest(data: bytes) -> str:
    return hashlib.sha256(data).hexdigest()


def strict_json(data: bytes):
    def pairs(values):
        result = {}
        for key, value in values:
            require(key not in result, 'duplicate JSON field')
            result[key] = value
        return result
    return json.loads(data.decode('utf-8', errors='strict'), object_pairs_hook=pairs,
                      parse_constant=lambda _: (_ for _ in ()).throw(ValueError('nonfinite JSON')))


def relative_path(group, name, version, extension):
    require(all(isinstance(x, str) and re.fullmatch('[A-Za-z0-9_.+-]+', x) for x in (group, name, version)),
            'invalid Maven coordinate')
    require(all(part not in ('', '.', '..') for part in group.split('.')) and name not in ('.', '..') and
            version not in ('.', '..') and all(len(x) <= 256 for x in (group, name, version)),
            'noncanonical Maven coordinate')
    require(extension in ('jar', 'pom', 'module'), 'unsupported Maven extension')
    return '/'.join((group.replace('.', '/'), name, version, name + '-' + version + '.' + extension))


def validate_module_capture(report: dict, version: str) -> dict:
    require(version == '2.4.20', 'module-mirror profile requires the reviewed compiler pin')
    capture = report.get('moduleMirror')
    require(isinstance(capture, dict) and set(capture) == {'schemaVersion', 'kotlinVersion', 'requestedRoots',
            'selectedModules', 'graph', 'artifactlessModules', 'naturalArtifactOrder', 'naturalFileOrder',
            'metadataReferences', 'metadata'}, 'module-mirror fields')
    require(type(capture['schemaVersion']) is int and capture['schemaVersion'] == 1 and capture['kotlinVersion'] == version,
            'module-mirror version')
    tools = report['roles']['buildTools']
    require(capture['requestedRoots'] == tools['roots'] and capture['graph'] == tools['graph'], 'module graph differs')
    require(capture['selectedModules'] == sorted(tools['graph']) and len(capture['selectedModules']) == 14,
            'complete selected module universe differs')
    require(set(capture['selectedModules']) == EXPECTED_TOOLS and capture['artifactlessModules'] == [],
            'unreviewed or artifactless selected module')
    require(capture['metadataReferences'] == [{'from':'org.jetbrains.kotlinx:kotlinx-coroutines-core-jvm:1.8.0',
            'to':'org.jetbrains.kotlinx:kotlinx-coroutines-bom:1.8.0','reason':'imported-bom'}],
            'metadata reference closure differs')
    artifacts = tools['artifacts']
    require(len(artifacts) == 14, 'module-mirror artifact count')
    keys = [':'.join(row[k] for k in ('group', 'name', 'version')) for row in artifacts]
    require(len(set(keys)) == 14 and set(keys) == set(capture['selectedModules']), 'artifactless or duplicate selected module')
    for key in ('naturalArtifactOrder', 'naturalFileOrder'):
        order = capture[key]
        require(isinstance(order, list) and len(order) == 14 and len(set(order)) == 14 and set(order) == set(keys),
                'invalid actual natural module order')
    expected = {(key, 'pom'): 'selected-module' for key in keys}
    expected[('org.jetbrains.kotlin:kotlin-stdlib:' + version, 'module')] = 'published-gradle-metadata'
    expected[('org.jetbrains.kotlinx:kotlinx-coroutines-core-jvm:1.8.0', 'module')] = 'published-gradle-metadata'
    expected[('org.jetbrains.kotlinx:kotlinx-coroutines-bom:1.8.0', 'pom')] = 'imported-bom'
    metadata = capture['metadata']
    require(isinstance(metadata, list) and len(metadata) == 17, 'exact metadata closure required')
    observed = set()
    for row in metadata:
        require(isinstance(row, dict) and set(row) == {'group', 'name', 'version', 'extension', 'path',
                'relativePath', 'bytes', 'sha256', 'reason'}, 'metadata fields')
        coordinate = ':'.join(row[k] for k in ('group', 'name', 'version'))
        key = coordinate, row['extension']
        require(key in expected and key not in observed and row['reason'] == expected[key], 'unexpected metadata identity or role')
        observed.add(key)
        require(row['relativePath'] == relative_path(row['group'], row['name'], row['version'], row['extension']),
                'metadata Maven path differs')
        require(type(row['bytes']) is int and 0 < row['bytes'] <= MIB and re.fullmatch('[0-9a-f]{64}', row['sha256']),
                'metadata size or digest')
        data = stable_bytes(Path(row['path']), MIB)
        require(len(data) == row['bytes'] and digest(data) == row['sha256'], 'original metadata changed')
    require(observed == set(expected), 'metadata closure missing')
    return capture


def mirror_rows(report, version):
    capture = validate_module_capture(report, version)
    result = []
    for row in report['roles']['buildTools']['artifacts']:
        require(row['classifier'] == '' and row['extension'] == 'jar', 'unsupported tools artifact layout')
        relative = relative_path(row['group'], row['name'], row['version'], 'jar')
        result.append({'path': relative, 'source': row['path'], 'bytes': row['bytes'], 'sha256': row['sha256'],
                       'coordinate': ':'.join(row[k] for k in ('group', 'name', 'version')), 'kind': 'artifact'})
    for row in capture['metadata']:
        result.append({'path': row['relativePath'], 'source': row['path'], 'bytes': row['bytes'],
                       'sha256': row['sha256'], 'coordinate': ':'.join(row[k] for k in ('group', 'name', 'version')),
                       'kind': 'metadata', 'reason': row['reason']})
    require(len(result) == len({r['path'] for r in result}) == 31 and sum(r['bytes'] for r in result) <= 128*MIB,
            'mirror inventory bound or duplicate')
    return result


def directory(path: Path, create=False):
    path = Path(path).absolute()
    require('..' not in path.parts, 'noncanonical directory')
    fd = os.open('/', os.O_RDONLY | os.O_DIRECTORY | os.O_NOFOLLOW)
    try:
        for part in path.parts[1:]:
            if create:
                try: os.mkdir(part, 0o700, dir_fd=fd)
                except FileExistsError: pass
            following = os.open(part, os.O_RDONLY | os.O_DIRECTORY | os.O_NOFOLLOW, dir_fd=fd)
            os.close(fd); fd = following
        return fd
    except BaseException:
        os.close(fd); raise


def verify_mirror(root: Path, rows):
    expected = {row['path']: row for row in rows}
    expected_dirs = {str(p) for name in expected for p in PurePosixPath(name).parents if str(p) != '.'}
    seen = set(); seen_dirs = set(); entries = 0; total = 0
    def walk(parent, prefix):
        nonlocal entries, total
        with os.scandir(parent) as listing:
            for entry in listing:
                entries += 1
                require(entries <= 128, 'mirror entry limit')
                relative = prefix + entry.name
                info = os.stat(entry.name, dir_fd=parent, follow_symlinks=False)
                if stat.S_ISDIR(info.st_mode):
                    require(relative in expected_dirs, 'extra mirror directory')
                    seen_dirs.add(relative)
                    child = os.open(entry.name, os.O_RDONLY | os.O_DIRECTORY | os.O_NOFOLLOW, dir_fd=parent)
                    try: walk(child, relative + '/')
                    finally: os.close(child)
                else:
                    require(stat.S_ISREG(info.st_mode) and relative in expected, 'extra or nonregular mirror file')
                    row = expected[relative]; total += info.st_size
                    require(total <= 128*MIB, 'mirror byte limit')
                    data = stable_bytes(root / relative, MIB if row['kind'] == 'metadata' else 128*MIB)
                    require(len(data) == row['bytes'] and digest(data) == row['sha256'], 'mirror content changed')
                    seen.add(relative)
    fd = directory(root)
    try: walk(fd, '')
    finally: os.close(fd)
    require(seen == set(expected) and seen_dirs == expected_dirs, 'mirror membership changed')


def prepare_mirror(report, version, root: Path, receipt_file: Path):
    rows = mirror_rows(report, version)
    root.mkdir(exist_ok=False)
    for row in rows:
        data = stable_bytes(Path(row['source']), MIB if row['kind'] == 'metadata' else 128*MIB)
        require(len(data) == row['bytes'] and digest(data) == row['sha256'], 'mirror source changed')
        target = root / row['path']; parent = directory(target.parent, create=True)
        try:
            descriptor = os.open(target.name, os.O_WRONLY | os.O_CREAT | os.O_EXCL | os.O_NOFOLLOW, 0o600, dir_fd=parent)
            with os.fdopen(descriptor, 'wb') as out:
                out.write(data)
        finally: os.close(parent)
        source_info, target_info = Path(row['source']).stat(), target.stat()
        require((source_info.st_dev, source_info.st_ino) != (target_info.st_dev, target_info.st_ino) and target_info.st_nlink == 1,
                'mirror aliases source artifact')
    verify_mirror(root, rows)
    capture = report['moduleMirror']
    receipt = {'schemaVersion': 1, 'profile': 'kartograph-build-tools-module-mirror', 'kotlinVersion': version,
               'roots': capture['requestedRoots'], 'graph': capture['graph'], 'selectedModules': capture['selectedModules'],
               'naturalArtifactOrder': capture['naturalArtifactOrder'], 'naturalFileOrder': capture['naturalFileOrder'],
               'artifactlessModules': capture['artifactlessModules'], 'metadataReferences': capture['metadataReferences'],
               'entries': [{k:v for k,v in row.items() if k != 'source'} for row in rows]}
    with receipt_file.open('x', encoding='utf-8') as out:
        json.dump(receipt, out, indent=2); out.write('\n')
    by_key = {row['coordinate']: root/row['path'] for row in rows if row['kind'] == 'artifact'}
    return rows, [str(by_key[key]) for key in capture['naturalFileOrder']]


ORIGINAL_METADATA_PROFILE = Path(__file__).with_name('call-position-original-metadata-2.4.20.json')
ORIGINAL_METADATA_PROFILE_SHA256 = 'b19c3f3520225b716c4a82d18f28e42bea1fcd9eaf3b864fefda595baf94c32e'
METADATA_MODE_ENV = ('KARTOGRAPH_CALL_METADATA_INPUT', 'KARTOGRAPH_CALL_METADATA_INPUT_SHA256',
                     'KARTOGRAPH_CALL_METADATA_PROFILE_SHA256')


def expected_metadata_profile():
    data = stable_bytes(ORIGINAL_METADATA_PROFILE.absolute(), 64 * 1024)
    require(digest(data) == ORIGINAL_METADATA_PROFILE_SHA256, 'checked-in metadata profile changed')
    return strict_json(data)


def root_identity(root):
    fd = directory(root)
    try:
        info = os.fstat(fd)
        return info.st_dev, info.st_ino, info.st_mode, info.st_mtime_ns, info.st_ctime_ns
    finally:
        os.close(fd)


def original_metadata_bundle(root: Path):
    """입력 manifest가 고정 content pin을 다시 정의하게 두지 않는다."""
    root = Path(root).absolute()
    before = root_identity(root)
    data = stable_bytes(root / 'manifest.json', 64 * 1024)
    manifest = strict_json(data)
    expected = expected_metadata_profile()
    require(isinstance(manifest, dict) and type(manifest.get('schemaVersion')) is int and manifest == expected,
            'original metadata manifest differs from the checked-in content profile')
    require(len(manifest['entries']) == 17, 'exact seventeen original metadata entries required')
    rows = [{'path': 'manifest.json', 'bytes': len(data), 'sha256': digest(data), 'kind': 'metadata'}]
    total = 0
    for entry in manifest['entries']:
        require(type(entry['bytes']) is int and 0 < entry['bytes'] <= 256 * 1024,
                'original metadata file size bound')
        group, name, version = entry['coordinate'].split(':')
        require(entry['path'] == relative_path(group, name, version, entry['kind']) and
                entry['kind'] in ('pom', 'module'), 'original metadata path/type differs')
        content = stable_bytes(root / entry['path'], 256 * 1024)
        total += len(content)
        require(total <= 4 * MIB and len(content) == entry['bytes'] and digest(content) == entry['sha256'],
                'original metadata bytes differ from fixed pin')
        rows.append({'path': entry['path'], 'bytes': entry['bytes'], 'sha256': entry['sha256'], 'kind': 'metadata'})
    require(len({row['path'] for row in rows}) == 18, 'duplicate original metadata path')
    verify_mirror(root, rows)
    require(root_identity(root) == before, 'original metadata root changed')
    return {'root': root, 'rootIdentity': before, 'manifestSha256': digest(data),
            'profileSha256': ORIGINAL_METADATA_PROFILE_SHA256, 'rows': rows}


def copy_original_metadata(source: Path, owned: Path):
    source, owned = Path(source).absolute(), Path(owned).absolute()
    require(source != owned and source not in owned.parents and owned not in source.parents,
            'owned metadata copy cannot overlap its supplied source')
    captured = original_metadata_bundle(source)
    parent = directory(owned.parent)
    try:
        os.mkdir(owned.name, 0o700, dir_fd=parent)
    finally:
        os.close(parent)
    for row in captured['rows']:
        data = stable_bytes(source / row['path'], 256 * 1024)
        require(len(data) == row['bytes'] and digest(data) == row['sha256'], 'supplied metadata changed before copy')
        target = owned / row['path']
        parent = directory(target.parent, create=True)
        try:
            fd = os.open(target.name, os.O_WRONLY | os.O_CREAT | os.O_EXCL | os.O_NOFOLLOW, 0o600, dir_fd=parent)
            with os.fdopen(fd, 'wb') as stream:
                stream.write(data)
        finally:
            os.close(parent)
        left, right = (source / row['path']).lstat(), target.lstat()
        require((left.st_dev, left.st_ino) != (right.st_dev, right.st_ino) and right.st_nlink == 1,
                'owned metadata copy aliases supplied input')
    copied = original_metadata_bundle(owned)
    require(copied['manifestSha256'] == captured['manifestSha256'], 'copied metadata manifest differs')
    result = {'source': captured, 'owned': copied}
    verify_original_metadata(result)
    return result


def verify_original_metadata(capture):
    if capture is None:
        return
    for key in ('source', 'owned'):
        require(original_metadata_bundle(capture[key]['root']) == capture[key],
                'original metadata input/copy changed during execution')


def metadata_acquisition(capture):
    if capture is None:
        return {'schemaVersion': 1, 'mode': 'artifact-resolution-query-v1'}
    return {'schemaVersion': 1, 'mode': 'supplied-original-metadata-v1',
            'profileSha256': capture['owned']['profileSha256'], 'manifestSha256': capture['owned']['manifestSha256']}


def metadata_environment(environment, source, offline, capture=None):
    result = {key: value for key, value in environment.items() if key not in METADATA_MODE_ENV}
    require(source is None or offline is True, 'supplied original metadata requires explicit offline mode')
    if source is None:
        require(capture is None, 'metadata capture requires explicit supplied input')
    elif capture is not None:
        require(Path(source).absolute() == capture['source']['root'], 'metadata capture source differs')
        result.update(KARTOGRAPH_CALL_METADATA_INPUT=str(capture['owned']['root'] / 'manifest.json'),
                      KARTOGRAPH_CALL_METADATA_INPUT_SHA256=capture['owned']['manifestSha256'],
                      KARTOGRAPH_CALL_METADATA_PROFILE_SHA256=capture['owned']['profileSha256'])
    return result


def validate_metadata_acquisition(report, expected=None):
    value = report.get('metadataAcquisition')
    require(isinstance(value, dict) and type(value.get('schemaVersion')) is int and value['schemaVersion'] == 1,
            'explicit metadata acquisition receipt required')
    if value.get('mode') == 'artifact-resolution-query-v1':
        require(set(value) == {'schemaVersion', 'mode'}, 'ordinary query cannot claim supplied metadata')
    else:
        require(value.get('mode') == 'supplied-original-metadata-v1' and set(value) ==
                {'schemaVersion', 'mode', 'profileSha256', 'manifestSha256'} and
                value['profileSha256'] == ORIGINAL_METADATA_PROFILE_SHA256 and
                isinstance(value['manifestSha256'], str) and re.fullmatch('[0-9a-f]{64}', value['manifestSha256']),
                'supplied metadata acquisition fields/pins differ')
    require(expected is None or value == expected, 'metadata acquisition contradicts explicit CLI mode')
    return value


@contextmanager
def original_metadata_input(source, owned, offline):
    require(source is None or offline is True, 'supplied original metadata requires explicit offline mode')
    capture = None if source is None else copy_original_metadata(source, owned)
    try:
        yield capture
    finally:
        verify_original_metadata(capture)
