"""CI가 실제 호출 위치 검사를 skip이나 오래된 보고서로 통과하지 않는지 확인한다."""
import importlib.util
import copy
import hashlib
import json
import subprocess
import io
import zipfile
from unittest.mock import patch
import tempfile
from pathlib import Path
import unittest

SCRIPT = Path(__file__).resolve().parents[1] / 'verify-compiler-call-positions.py'

class CompilerCallPositionGateTest(unittest.TestCase):
    def setUp(self):
        spec = importlib.util.spec_from_file_location('call_position_gate', SCRIPT)
        self.gate = importlib.util.module_from_spec(spec)
        spec.loader.exec_module(self.gate)

    def test_explicit_metadata_option_requires_offline_before_any_runtime(self):
        with patch.object(self.gate.sys, 'argv', ['verify', '--original-module-metadata', '/explicit']), \
             patch.object(self.gate, 'verify') as execute, patch.object(self.gate.sys, 'stderr', io.StringIO()):
            with self.assertRaises(SystemExit) as raised: self.gate.main()
            self.assertEqual(2, raised.exception.code)
            execute.assert_not_called()

    def test_requires_every_selected_suite_and_rejects_skips_and_failures(self):
        with tempfile.TemporaryDirectory() as temp:
            report = Path(temp)
            expected = {'sample.First', 'sample.Second'}
            def write(name, **attrs):
                attributes = dict(tests=1, failures=0, errors=0, skipped=0)
                attributes.update(attrs)
                report.joinpath('TEST-' + name + '.xml').write_text('<testsuite ' +
                    ' '.join(f'{key}="{value}"' for key, value in attributes.items()) + '/>')
            write('sample.First')
            with self.assertRaises(ValueError): self.gate.junit_totals(report, expected)
            for key in ('skipped', 'failures', 'errors'):
                write('sample.Second', **{key: 1})
                with self.assertRaises(ValueError): self.gate.junit_totals(report, expected)
            write('sample.Second')
            self.assertEqual(2, self.gate.junit_totals(report, expected)['tests'])

    def test_zero_test_suite_is_not_success(self):
        with tempfile.TemporaryDirectory() as temp:
            Path(temp, 'TEST-sample.Empty.xml').write_text(
                '<testsuite tests="0" failures="0" errors="0" skipped="0"/>')
            with self.assertRaises(ValueError): self.gate.junit_totals(Path(temp), {'sample.Empty'})

    def test_single_explicit_build_pin_is_required(self):
        with tempfile.TemporaryDirectory() as temp:
            build = Path(temp, 'build.gradle.kts')
            build.write_text('val kotlinCompilerVersion = "2.4.20"\n')
            self.assertEqual('2.4.20', self.gate.collector_kotlin_version(build))
            for content in ('', 'val kotlinCompilerVersion = versionProvider\n',
                            'val kotlinCompilerVersion = "2.4.20"\nval kotlinCompilerVersion = "2.4.10"\n'):
                build.write_text(content)
                with self.assertRaises(ValueError): self.gate.collector_kotlin_version(build)

    def runtime(self, root, version='2.4.20'):
        result = {'compiler': [], 'plugin': [], 'buildTools': []}
        for role, names in {'compiler': ['kotlin-compiler-embeddable', 'kotlin-stdlib'],
                            'plugin': ['kotlin-gradle-plugin'],
                            'buildTools': ['kotlin-build-tools-compat', 'kotlin-build-tools-impl']}.items():
            for name in names:
                suffix = '-gradle96' if name == 'kotlin-gradle-plugin' else ''
                path = root / (name + '-' + version + suffix + '.jar')
                path.write_bytes(('public synthetic ' + name).encode())
                result[role].append(str(path))
        return result

    def test_runtime_artifacts_bind_unique_names_hashes_order_and_legacy_pin(self):
        with tempfile.TemporaryDirectory() as temp:
            root = Path(temp)
            for version in ('2.4.20', '2.4.10'):
                runtime = self.runtime(root, version)
                receipt = self.gate.runtime_receipt(runtime, version)
                self.assertEqual(version, receipt['compilerVersion'])
                for role, paths in runtime.items():
                    self.assertEqual([Path(p).name for p in paths], [x['artifact'] for x in receipt['artifacts'][role]])
                    self.assertEqual(list(range(len(paths))), [x['ordinal'] for x in receipt['artifacts'][role]])
                    self.assertEqual([hashlib.sha256(Path(p).read_bytes()).hexdigest() for p in paths],
                                     [x['sha256'] for x in receipt['artifacts'][role]])
                Path(runtime['compiler'][0]).write_bytes(b'changed compiler bytes')
                self.assertNotEqual(receipt, self.gate.runtime_receipt(runtime, version))

    def test_runtime_wrong_missing_duplicate_and_mixed_versions_are_rejected(self):
        with tempfile.TemporaryDirectory() as temp:
            root = Path(temp)
            runtime = self.runtime(root)
            for role in runtime:
                for index in range(len(runtime[role])):
                    for mutation in ('missing', 'duplicate', 'wrong', 'mixed'):
                        changed = {k: list(v) for k, v in runtime.items()}
                        original = Path(changed[role][index])
                        wrong = root / original.name.replace('2.4.20', '2.4.10')
                        wrong.write_bytes(b'public wrong-version fixture')
                        if mutation == 'missing': changed[role].pop(index)
                        elif mutation == 'duplicate': changed[role].append(str(original))
                        elif mutation == 'wrong': changed[role][index] = str(wrong)
                        else: changed[role].append(str(wrong))
                        with self.subTest(role=role, index=index, mutation=mutation), self.assertRaises(ValueError):
                            self.gate.runtime_receipt(changed, '2.4.20')
            absent = {k: list(v) for k, v in runtime.items()}
            Path(absent['compiler'][0]).unlink()
            with self.assertRaises(ValueError): self.gate.runtime_receipt(absent, '2.4.20')

    def test_primary_plugin_prerelease_and_unknown_versions_are_not_hidden(self):
        with tempfile.TemporaryDirectory() as temp:
            root = Path(temp)
            runtime = self.runtime(root)
            for artifact in ('annotations', 'api', 'idea', 'idea-proto'):
                auxiliary = root / ('kotlin-gradle-plugin-' + artifact + '-2.4.20.jar')
                auxiliary.write_bytes(b'public auxiliary fixture')
                runtime['plugin'].append(str(auxiliary))
            self.gate.runtime_receipt(runtime, '2.4.20')
            for version in ('2.4.10-RC', '2.4.20-RC', '2.4.20-Beta2', '2.4.20-dev-123', 'unknown'):
                candidate = root / ('kotlin-gradle-plugin-' + version + '.jar')
                candidate.write_bytes(b'public wrong primary fixture')
                for mixed in (False, True):
                    changed = {k: list(v) for k, v in runtime.items()}
                    if not mixed:
                        changed['plugin'] = [x for x in changed['plugin']
                                             if Path(x).name != 'kotlin-gradle-plugin-2.4.20-gradle96.jar']
                    changed['plugin'].append(str(candidate))
                    with self.subTest(version=version, mixed=mixed), self.assertRaises(ValueError):
                        self.gate.runtime_receipt(changed, '2.4.20')

    def resolution(self, root):
        runtime = self.runtime(root)
        auxiliary = root / 'kotlin-gradle-plugin-api-2.4.20.jar'
        auxiliary.write_bytes(b'public synthetic auxiliary')
        runtime['plugin'].append(str(auxiliary))
        tooling = root / 'kotlin-tooling-core-2.4.20.jar'
        with zipfile.ZipFile(tooling, 'w') as archive:
            archive.writestr('org/jetbrains/kotlin/tooling/core/KotlinToolingVersion.class', b'public synthetic class')
        runtime['buildTools'].append(str(tooling))
        roles = {}
        for role, paths in runtime.items():
            artifacts = []
            for path in sorted(paths):
                file = Path(path)
                stem = file.name.removesuffix('.jar').removesuffix('-gradle96').removesuffix('-2.4.20')
                artifacts.append(dict(group='org.jetbrains.kotlin', name=stem, version='2.4.20',
                    classifier='gradle96' if stem == 'kotlin-gradle-plugin' else '', extension='jar',
                    path=str(file), bytes=file.stat().st_size, sha256=hashlib.sha256(file.read_bytes()).hexdigest()))
            ids = [':'.join(x[k] for k in ('group', 'name', 'version')) for x in artifacts]
            graph = {key: [] for key in ids}
            roots = ids
            if role == 'buildTools':
                roots = ['org.jetbrains.kotlin:kotlin-build-tools-compat:2.4.20',
                         'org.jetbrains.kotlin:kotlin-build-tools-impl:2.4.20']
                graph[roots[0]] = ['org.jetbrains.kotlin:kotlin-tooling-core:2.4.20']
            roles[role] = dict(roots=sorted(roots), graph=graph, artifacts=artifacts)
        return dict(schemaVersion=1, compilerVersion='2.4.20', requestedBuildTools=roles['buildTools']['roots'], roles=roles)

    def test_selected_closures_and_fixture_receipt_preserve_exact_roles_order_and_bytes(self):
        with tempfile.TemporaryDirectory() as temp:
            root = Path(temp)
            resolution = self.resolution(root)
            runtime = self.gate.resolved_runtime(resolution, '2.4.20')
            self.assertEqual({'compiler', 'plugin', 'buildTools'}, set(runtime))
            for role in runtime:
                self.assertEqual([x['path'] for x in resolution['roles'][role]['artifacts']], runtime[role])
            normalized = self.gate.normalized_closures(resolution)
            self.assertTrue(all('path' not in x for role in normalized.values() for x in role['artifacts']))
            manifest = root / 'fixture.tsv'
            self.gate.write_build_tools_manifest(runtime['buildTools'], '2.4.20', manifest)
            expected = ['kartograph-build-tools-runtime\t1\t2.4.20']
            for i, item in enumerate(resolution['roles']['buildTools']['artifacts']):
                expected.append('\t'.join((str(i), Path(item['path']).name, str(item['bytes']), item['sha256'])))
            self.assertEqual('\n'.join(expected) + '\n', manifest.read_text())

    def test_selected_build_tools_reject_missing_reordered_duplicate_and_disconnected_artifacts(self):
        with tempfile.TemporaryDirectory() as temp:
            original = self.resolution(Path(temp))
            mutations = []
            for role in original['roles']:
                for change in ('omit', 'omit_last', 'reverse', 'duplicate', 'wrong_hash', 'wrong_bytes', 'wrong_version'):
                    altered = copy.deepcopy(original)
                    artifacts = altered['roles'][role]['artifacts']
                    if change == 'omit': artifacts.pop(0)
                    elif change == 'omit_last': artifacts.pop()
                    elif change == 'reverse': artifacts.reverse()
                    elif change == 'duplicate': artifacts.append(copy.deepcopy(artifacts[0]))
                    elif change == 'wrong_hash': artifacts[0]['sha256'] = '0' * 64
                    elif change == 'wrong_bytes': artifacts[0]['bytes'] += 1
                    else: artifacts[0]['version'] = '2.4.10'
                    mutations.append((role + ':' + change, altered))
            for change in ('missing_graph', 'unknown_child', 'unreachable', 'wrong_root', 'bool_schema'):
                altered = copy.deepcopy(original)
                config = altered['roles']['buildTools']
                if change == 'missing_graph': config['graph'].pop('org.jetbrains.kotlin:kotlin-tooling-core:2.4.20')
                elif change == 'unknown_child': config['graph'][config['roots'][0]].append('missing:module:1')
                elif change == 'unreachable': config['graph']['unreachable:module:1'] = []
                elif change == 'wrong_root': config['roots'] = config['roots'][:1]
                else: altered['schemaVersion'] = True
                mutations.append((change, altered))
            for label, altered in mutations:
                with self.subTest(change=label), self.assertRaises(ValueError):
                    self.gate.resolved_runtime(altered, '2.4.20')

    def test_selected_closure_rejects_changed_live_bytes_and_mixed_pinned_kotlin(self):
        with tempfile.TemporaryDirectory() as temp:
            root = Path(temp)
            original = self.resolution(root)
            tooling = next(x for x in original['roles']['buildTools']['artifacts'] if x['name'] == 'kotlin-tooling-core')
            wrong = root / 'kotlin-tooling-core-2.4.10.jar'
            wrong.write_bytes(Path(tooling['path']).read_bytes())
            altered = copy.deepcopy(original)
            changed = next(x for x in altered['roles']['buildTools']['artifacts'] if x['name'] == 'kotlin-tooling-core')
            changed.update(path=str(wrong), version='2.4.10')
            config = altered['roles']['buildTools']
            old, new = 'org.jetbrains.kotlin:kotlin-tooling-core:2.4.20', 'org.jetbrains.kotlin:kotlin-tooling-core:2.4.10'
            config['graph'][new] = config['graph'].pop(old)
            config['graph'][config['roots'][0]] = [new]
            with self.assertRaises(ValueError): self.gate.resolved_runtime(altered, '2.4.20')
            Path(tooling['path']).write_bytes(b'changed bytes')
            with self.assertRaises(ValueError): self.gate.resolved_runtime(original, '2.4.20')

    def test_actual_compiler_version_must_match_once_and_exactly(self):
        self.gate.observed_kotlin_version('info: kotlinc-jvm 2.4.20 (JRE 17)\n', '2.4.20')
        for text in ('', 'info: kotlinc-jvm 2.4.10 (JRE 17)\n',
                     'info: kotlinc-jvm 2.4.20-RC (JRE 17)\n',
                     'info: kotlinc-jvm 2.4.20 (JRE 17)\n' * 2):
            with self.assertRaises(ValueError): self.gate.observed_kotlin_version(text, '2.4.20')

    def test_version_probe_retains_timeout_launch_failure_and_wrong_version(self):
        cases = [
            (subprocess.TimeoutExpired(['java'], 30, output=b'partial stdout', stderr=b'partial stderr'), 'TIMED_OUT', None),
            (OSError('synthetic launch failure'), 'LAUNCH_FAILED', None),
            (subprocess.CompletedProcess(['java'], 1, 'compiler stopped', ''), 'COMPLETED', 1),
            (subprocess.CompletedProcess(['java'], 0, 'info: kotlinc-jvm 2.4.10 (JRE 17)\n', ''), 'COMPLETED', 0),
        ]
        for result, status, code in cases:
            with self.subTest(status=status, code=code), tempfile.TemporaryDirectory() as temp:
                reports = Path(temp)
                receipt = {'compilerVersion': '2.4.20'}
                args = {'side_effect': result} if isinstance(result, BaseException) else {'return_value': result}
                with patch.object(self.gate.subprocess, 'run', **args):
                    with self.assertRaises((OSError, ValueError, subprocess.TimeoutExpired)):
                        self.gate.probe_runtime(Path('/unused/jdk'), 'sealed-runtime', '2.4.20', receipt, reports)
                retained = json.loads((reports / 'runtime-receipt.json').read_text())
                self.assertEqual(status, retained['versionProbe']['state'])
                self.assertEqual(code, retained['versionProbe']['exitCode'])
                self.assertIsNone(retained['observedCompilerVersion'])
                if status == 'TIMED_OUT':
                    self.assertEqual('partial stdoutpartial stderr', (reports / 'compiler-version.log').read_text())
                self.assertFalse((reports / 'verification.json').exists())

    def archive(self, name='kartograph-0.19.0/bin/kartograph', symlink=False):
        result = io.BytesIO()
        with zipfile.ZipFile(result, 'w') as archive:
            entry = zipfile.ZipInfo(name)
            if symlink: entry.external_attr = 0o120777 << 16
            archive.writestr(entry, 'public synthetic fixture')
        return result.getvalue()

    def test_verified_download_is_atomic_and_extraction_succeeds(self):
        payload = self.archive()
        with tempfile.TemporaryDirectory() as temp:
            root = Path(temp); archive = root / 'download.zip'
            with patch.object(self.gate, 'OLD_SHA256', hashlib.sha256(payload).hexdigest()), \
                 patch.object(self.gate.urllib.request, 'urlopen', return_value=io.BytesIO(payload)):
                home = self.gate.old_distribution(archive, root / 'installed')
            self.assertTrue((home / 'bin/kartograph').is_file())
            self.assertEqual(payload, archive.read_bytes())
            self.assertEqual([], list(root.glob('.kartograph-019-*')))

    def test_failed_download_leaves_no_partial_archive_and_existing_file_is_preserved(self):
        with tempfile.TemporaryDirectory() as temp:
            root = Path(temp); archive = root / 'download.zip'
            with patch.object(self.gate.urllib.request, 'urlopen', return_value=io.BytesIO(b'incomplete')):
                with self.assertRaises(ValueError): self.gate.old_distribution(archive, root / 'installed')
            self.assertFalse(archive.exists())
            self.assertEqual([], list(root.glob('.kartograph-019-*')))
            archive.write_bytes(b'preserve existing file')
            with patch.object(self.gate.urllib.request, 'urlopen', side_effect=AssertionError('must not download')):
                with self.assertRaises(ValueError): self.gate.old_distribution(archive, root / 'installed')
            self.assertEqual(b'preserve existing file', archive.read_bytes())

    def test_even_pinned_archive_cannot_escape_or_install_symlink(self):
        for name, symlink in [('kartograph-0.19.0/../../escape', False),
                              ('/absolute', False), ('kartograph-0.19.0/bin/kartograph', True)]:
            with self.subTest(name=name), tempfile.TemporaryDirectory() as temp:
                root = Path(temp); archive = root / 'download.zip'; payload = self.archive(name, symlink)
                archive.write_bytes(payload)
                with patch.object(self.gate, 'OLD_SHA256', hashlib.sha256(payload).hexdigest()):
                    with self.assertRaises(ValueError): self.gate.old_distribution(archive, root / 'installed')
                self.assertFalse((root / 'installed').exists())

if __name__ == '__main__': unittest.main()
