"""설치 압축 해제의 버전·필수 산출물·경로 실패 경계를 검사한다."""
import importlib.util
import io
from pathlib import Path
import tempfile
import unittest
import zipfile

spec = importlib.util.spec_from_file_location('collector_installation', Path(__file__).resolve().parents[1] / 'verify-collector-installation.py')
module = importlib.util.module_from_spec(spec)
spec.loader.exec_module(module)


class CollectorInstallationTest(unittest.TestCase):
    def archive(self, path, *, jar_version='1.2.3', extra=None, omitted=None):
        jar_bytes = io.BytesIO()
        with zipfile.ZipFile(jar_bytes, 'w') as jar:
            jar.writestr('META-INF/MANIFEST.MF', 'Manifest-Version: 1.0\nImplementation-Version: ' + jar_version + '\n')
            for name in ['dev/kartograph/collectors/OutputRecordingProcessor.class',
                         'dev/kartograph/collectors/RecordingSymbolProcessorProvider.class',
                         'META-INF/services/com.google.devtools.ksp.processing.SymbolProcessorProvider',
                         'META-INF/kartograph/LICENSE', 'META-INF/kartograph/THIRD_PARTY_NOTICES.md']:
                jar.writestr(name, b'unit archive content')
        files = {'VERSION': b'1.2.3\n', 'README.md': b'Installed collector 1.2.3', 'LICENSE': b'unit license',
                 'THIRD_PARTY_NOTICES.md': b'unit notices', 'processor_output_witness.py': b'# unit runner', 'processor_output_cache.gradle': b'// cache adapter',
                 'lib/kartograph-compiler-collectors.jar': jar_bytes.getvalue()}
        with zipfile.ZipFile(path, 'w') as archive:
            for name, data in files.items():
                if name != omitted:
                    archive.writestr('kartograph-compiler-collectors-1.2.3/' + name, data)
            if extra:
                archive.writestr(extra, b'outside')

    def test_matching_distribution_extracts(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            self.archive(root / 'collector.zip')
            installed = module.unpack(root / 'collector.zip', '1.2.3', root / 'installed')
            self.assertEqual('1.2.3', (installed / 'VERSION').read_text().strip())

    def test_rejects_wrong_jar_version_and_missing_runner(self):
        for options in [{'jar_version': '1.2.2'}, {'omitted': 'processor_output_witness.py'}]:
            with self.subTest(options=options), tempfile.TemporaryDirectory() as directory:
                root = Path(directory)
                self.archive(root / 'collector.zip', **options)
                with self.assertRaises(ValueError):
                    module.unpack(root / 'collector.zip', '1.2.3', root / 'installed')

    def test_escaping_and_symbolic_entries_rejected_before_extraction(self):
        symbolic = zipfile.ZipInfo('kartograph-compiler-collectors-1.2.3/symlink')
        symbolic.create_system = 3
        symbolic.external_attr = 0o120777 << 16
        for extra in ['../outside', '/absolute', 'other-prefix/file', 'kartograph-compiler-collectors-1.2.3/../outside',
                      'kartograph-compiler-collectors-1.2.3/a\\b', symbolic]:
            with self.subTest(extra=str(extra)), tempfile.TemporaryDirectory() as directory:
                root = Path(directory)
                self.archive(root / 'collector.zip', extra=extra)
                with self.assertRaises(ValueError):
                    module.unpack(root / 'collector.zip', '1.2.3', root / 'installed')
                self.assertFalse((root / 'installed').exists())

    def test_invalid_version_rejected(self):
        with self.assertRaises(ValueError):
            module.unpack(Path('unused.zip'), '../invalid', Path('unused'))
