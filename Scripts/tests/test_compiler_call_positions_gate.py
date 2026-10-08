"""CI가 실제 호출 위치 검사를 skip이나 오래된 보고서로 통과하지 않는지 확인한다."""
import importlib.util
import hashlib
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
