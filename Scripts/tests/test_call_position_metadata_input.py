"""명시적인 offline metadata bundle은 고정 pin과 현재 bytes만 사용해야 한다."""
import copy
import importlib.util
import json
import os
from pathlib import Path
import tempfile
import unittest
from unittest.mock import patch

HELPER = Path(__file__).resolve().parents[1] / 'call_position_module_runtime.py'
spec = importlib.util.spec_from_file_location('metadata_input_tested', HELPER)
m = importlib.util.module_from_spec(spec); spec.loader.exec_module(m)


class MetadataInputTest(unittest.TestCase):
    def fixture(self, parent):
        root = parent / 'supplied'; root.mkdir()
        profile = m.expected_metadata_profile()
        # 실제 pin 파일은 별도 검사한다. 경계 알고리즘은 독립된 작은 공개 fixture bytes로 검사한다.
        for entry in profile['entries']:
            data = ('fixture:' + entry['coordinate'] + ':' + entry['kind']).encode()
            entry['bytes'], entry['sha256'] = len(data), m.digest(data)
            path = root / entry['path']; path.parent.mkdir(parents=True, exist_ok=True); path.write_bytes(data)
        (root / 'manifest.json').write_text(json.dumps(profile))
        return root, profile

    def test_checked_in_profile_has_exact_hash_and_seventeen_entries(self):
        profile = m.expected_metadata_profile()
        self.assertEqual(17, len(profile['entries']))
        self.assertEqual(47590, sum(row['bytes'] for row in profile['entries']))
        self.assertTrue(all(not Path(row['path']).is_absolute() for row in profile['entries']))
        with tempfile.TemporaryDirectory() as temp:
            wrong = Path(temp) / 'profile.json'; wrong.write_text(json.dumps(profile) + ' ')
            with patch.object(m, 'ORIGINAL_METADATA_PROFILE', wrong), self.assertRaises(ValueError):
                m.expected_metadata_profile()

    def test_exact_portable_bundle_copy_and_acquisition(self):
        with tempfile.TemporaryDirectory() as temp:
            parent = Path(temp); source, profile = self.fixture(parent)
            with patch.object(m, 'expected_metadata_profile', return_value=profile):
                capture = m.copy_original_metadata(source, parent / 'owned')
                m.verify_original_metadata(capture)
                for row in capture['source']['rows']:
                    self.assertNotEqual((source / row['path']).stat().st_ino,
                                        (parent / 'owned' / row['path']).stat().st_ino)
                expected = m.metadata_acquisition(capture)
                self.assertEqual(expected, m.validate_metadata_acquisition({'metadataAcquisition': expected}, expected))
                env = m.metadata_environment({'PATH': 'test'}, source, True, capture)
                self.assertEqual(str(parent / 'owned/manifest.json'), env['KARTOGRAPH_CALL_METADATA_INPUT'])
                self.assertEqual('test', env['PATH'])

    def test_missing_modified_rehashed_and_extra_metadata_are_rejected(self):
        for action in ('missing', 'modified', 'rehashed', 'extra_file', 'extra_directory'):
            with self.subTest(action=action), tempfile.TemporaryDirectory() as temp:
                root, profile = self.fixture(Path(temp)); fixed = copy.deepcopy(profile)
                file = root / profile['entries'][0]['path']
                if action == 'missing': file.unlink()
                elif action in ('modified', 'rehashed'):
                    file.write_bytes(b'changed')
                    if action == 'rehashed':
                        profile['entries'][0].update(bytes=7, sha256=m.digest(b'changed'))
                        (root / 'manifest.json').write_text(json.dumps(profile))
                elif action == 'extra_file': (root / 'extra.pom').write_bytes(b'extra')
                else: (root / 'extra').mkdir()
                with patch.object(m, 'expected_metadata_profile', return_value=fixed), self.assertRaises((ValueError, OSError)):
                    m.original_metadata_bundle(root)

    def test_manifest_unknown_duplicate_bool_path_version_and_bom_are_rejected(self):
        for action in ('unknown', 'duplicate_key', 'boolean', 'traversal', 'absolute', 'wrong_version', 'wrong_bom', 'duplicate_entry'):
            with self.subTest(action=action), tempfile.TemporaryDirectory() as temp:
                root, profile = self.fixture(Path(temp)); fixed = copy.deepcopy(profile)
                if action == 'unknown': profile['extra'] = 1
                elif action == 'boolean': profile['schemaVersion'] = True
                elif action == 'traversal': profile['entries'][0]['path'] = '../escape.pom'
                elif action == 'absolute': profile['entries'][0]['path'] = '/escape.pom'
                elif action == 'wrong_version': profile['compilerVersion'] = '2.4.10'
                elif action == 'wrong_bom': profile['entries'][-1]['coordinate'] = 'wrong:bom:1'
                elif action == 'duplicate_entry': profile['entries'][-1] = profile['entries'][0]
                data = json.dumps(profile)
                if action == 'duplicate_key': data = data.replace('"schemaVersion": 1', '"schemaVersion": 1, "schemaVersion": 1')
                (root / 'manifest.json').write_text(data)
                with patch.object(m, 'expected_metadata_profile', return_value=fixed), self.assertRaises(ValueError):
                    m.original_metadata_bundle(root)

    def test_nonregular_file_and_root_ancestor_alias_are_rejected(self):
        for action in ('fifo', 'file_link', 'root_link', 'ancestor_link'):
            with self.subTest(action=action), tempfile.TemporaryDirectory() as temp:
                parent = Path(temp); root, profile = self.fixture(parent); selected = root
                file = root / profile['entries'][0]['path']
                if action == 'fifo': file.unlink(); os.mkfifo(file)
                elif action == 'file_link':
                    data = file.read_bytes(); file.unlink(); other = parent / 'real'; other.write_bytes(data); file.symlink_to(other)
                elif action == 'root_link':
                    selected = parent / 'linked'; selected.symlink_to(root, target_is_directory=True)
                else:
                    alias = parent / 'alias'; alias.symlink_to(parent, target_is_directory=True); selected = alias / 'supplied'
                with patch.object(m, 'expected_metadata_profile', return_value=profile), self.assertRaises((ValueError, OSError)):
                    m.original_metadata_bundle(selected)
        with self.assertRaises(ValueError): m.stable_bytes(Path('/dev/null'), 10)

    def test_owned_copy_cannot_overlap_or_follow_parent_link(self):
        with tempfile.TemporaryDirectory() as temp:
            parent = Path(temp); root, profile = self.fixture(parent)
            alias = parent / 'alias'; alias.symlink_to(parent, target_is_directory=True)
            with patch.object(m, 'expected_metadata_profile', return_value=profile):
                for owned in (root, root / 'child', parent, alias / 'unsafe'):
                    with self.subTest(owned=owned), self.assertRaises((ValueError, OSError)):
                        m.copy_original_metadata(root, owned)
                self.assertFalse((parent / 'unsafe').exists())

    def test_source_and_owned_mutations_reject_even_when_body_raises(self):
        for changed in ('source', 'owned'):
            with self.subTest(changed=changed), tempfile.TemporaryDirectory() as temp:
                parent = Path(temp); root, profile = self.fixture(parent)
                with patch.object(m, 'expected_metadata_profile', return_value=profile), self.assertRaises(ValueError):
                    with m.original_metadata_input(root, parent / 'owned', True) as capture:
                        target = capture[changed]['root'] / profile['entries'][0]['path']
                        target.write_bytes(b'changed')
                        raise RuntimeError('retained producer failure')

    def test_read_time_growth_and_replacement_are_rejected(self):
        for action in ('growth', 'replacement'):
            with self.subTest(action=action), tempfile.TemporaryDirectory() as temp:
                path = Path(temp) / 'file'; path.write_bytes(b'first')
                original = os.fdopen
                def opened(fd, mode):
                    stream = original(fd, mode)
                    if action == 'growth': path.write_bytes(b'first plus growth')
                    else:
                        other = path.with_name('other'); other.write_bytes(b'first'); other.replace(path)
                    return stream
                with patch.object(m.os, 'fdopen', side_effect=opened), self.assertRaises(ValueError):
                    m.stable_bytes(path, 32)

    def test_explicit_offline_only_and_inherited_mode_is_removed(self):
        inherited = {key: 'untrusted' for key in m.METADATA_MODE_ENV}; inherited['PATH'] = 'normal'
        self.assertEqual({'PATH': 'normal'}, m.metadata_environment(inherited, None, False))
        self.assertEqual({'PATH': 'normal'}, m.metadata_environment(inherited, Path('/explicit'), True))
        with self.assertRaises(ValueError): m.metadata_environment(inherited, Path('/explicit'), False)
        with patch.object(m, 'copy_original_metadata') as copy_bundle:
            with m.original_metadata_input(None, Path('/not-created'), False) as capture: self.assertIsNone(capture)
            copy_bundle.assert_not_called()
        with patch.object(m, 'copy_original_metadata') as copy_bundle, self.assertRaises(ValueError):
            with m.original_metadata_input(Path('/explicit'), Path('/not-created'), False): pass
            copy_bundle.assert_not_called()

    def test_acquisition_absence_contradictions_and_rewritten_pins_are_rejected(self):
        normal = m.metadata_acquisition(None)
        self.assertEqual(normal, m.validate_metadata_acquisition({'metadataAcquisition': normal}, normal))
        supplied = {'schemaVersion':1, 'mode':'supplied-original-metadata-v1',
                    'profileSha256':m.ORIGINAL_METADATA_PROFILE_SHA256, 'manifestSha256':'a'*64}
        self.assertEqual(supplied, m.validate_metadata_acquisition({'metadataAcquisition': supplied}, supplied))
        for bad in (None, dict(normal, schemaVersion=True), dict(normal, manifestSha256='a'*64),
                    dict(supplied, profileSha256='b'*64), dict(supplied, mode='fallback'),
                    dict(supplied, manifestSha256='invalid')):
            with self.subTest(bad=bad), self.assertRaises(ValueError):
                m.validate_metadata_acquisition({'metadataAcquisition':bad})
        with self.assertRaises(ValueError): m.validate_metadata_acquisition({'metadataAcquisition':supplied}, normal)
        with self.assertRaises(ValueError): m.validate_metadata_acquisition({})

    def test_verify_publishes_success_only_after_terminal_metadata_context(self):
        from contextlib import ExitStack, contextmanager
        from types import SimpleNamespace
        script = HELPER.with_name('verify-compiler-call-positions.py')
        spec = importlib.util.spec_from_file_location('terminal_metadata_gate', script)
        gate = importlib.util.module_from_spec(spec); spec.loader.exec_module(gate)
        for mutate in (False, True):
            with self.subTest(mutate=mutate), tempfile.TemporaryDirectory() as temp:
                parent = Path(temp); source, profile = self.fixture(parent)
                (parent / 'jdk/bin').mkdir(parents=True); (parent / 'jdk/bin/javac').write_bytes(b'not executable')
                gradle = parent / 'gradle'; gradle.write_bytes(b'not executable')
                reports = parent / 'reports'
                args = SimpleNamespace(report_dir=reports, original_module_metadata=source, offline=True,
                                       gradle=gradle, old_archive=parent / 'unused-old.zip')
                original_context = m.original_metadata_input
                @contextmanager
                def terminal_context(source_root, owned, offline):
                    with original_context(source_root, owned, offline) as captured:
                        yield captured
                        if mutate:
                            (captured['owned']['root'] / profile['entries'][0]['path']).write_bytes(b'changed at final exit')
                def fake_run(label, command, cwd, report_root, environment):
                    if label == 'resolve-verified-runtime':
                        record = {'metadataAcquisition': {'schemaVersion':1, 'mode':'supplied-original-metadata-v1',
                                  'profileSha256': environment['KARTOGRAPH_CALL_METADATA_PROFILE_SHA256'],
                                  'manifestSha256': environment['KARTOGRAPH_CALL_METADATA_INPUT_SHA256']}}
                        Path(environment['KARTOGRAPH_CALL_RUNTIME_FILE']).write_text(json.dumps(record))
                with ExitStack() as stack:
                    stack.enter_context(patch.dict(os.environ, {'JAVA_HOME':str(parent / 'jdk'), 'KARTOGRAPH_JDK17_HOME':str(parent / 'jdk')}))
                    stack.enter_context(patch.object(gate, 'module_runtime', m))
                    stack.enter_context(patch.object(m, 'expected_metadata_profile', return_value=profile))
                    stack.enter_context(patch.object(m, 'original_metadata_input', side_effect=terminal_context))
                    stack.enter_context(patch.object(gate.subprocess, 'run', return_value=SimpleNamespace(returncode=0, stdout='javac 17.0.20')))
                    stack.enter_context(patch.object(gate, 'collector_kotlin_version', return_value='2.4.20'))
                    stack.enter_context(patch.object(gate, 'run', side_effect=fake_run))
                    stack.enter_context(patch.object(gate, 'resolved_runtime', return_value={'compiler':[], 'plugin':[], 'buildTools':[]}))
                    stack.enter_context(patch.object(gate, 'runtime_receipt', side_effect=lambda *_: {'artifacts':{}}))
                    stack.enter_context(patch.object(gate, 'normalized_closures', return_value={}))
                    stack.enter_context(patch.object(m, 'prepare_mirror', return_value=([], [])))
                    stack.enter_context(patch.object(m, 'verify_mirror'))
                    stack.enter_context(patch.object(gate, 'write_build_tools_manifest'))
                    stack.enter_context(patch.object(gate, 'probe_runtime'))
                    stack.enter_context(patch.object(gate, 'old_distribution', return_value=parent / 'unused-old'))
                    stack.enter_context(patch.object(gate, 'sha256', return_value='a'*64))
                    stack.enter_context(patch.object(gate, 'MODULE_HELPER_SHA256', 'a'*64))
                    stack.enter_context(patch.object(gate, 'junit_totals', return_value={'tests':1,'failures':0,'errors':0,'skipped':0}))
                    if mutate:
                        with self.assertRaises(ValueError): gate.verify(args)
                        self.assertFalse((reports / 'verification.json').exists(), 'terminal rejection must not leave a passed receipt')
                    else:
                        self.assertEqual('passed', gate.verify(args)['status'])
                        self.assertEqual('passed', json.loads((reports / 'verification.json').read_text())['status'])


if __name__ == '__main__': unittest.main()
