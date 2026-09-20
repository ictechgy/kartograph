"""현재 버전이 경로나 같은 줄에 있어도 이전 설치 예시를 검출한다."""
import importlib.util
from pathlib import Path
import tempfile
import unittest

spec = importlib.util.spec_from_file_location('release_documentation', Path(__file__).resolve().parents[1] / 'verify-release-documentation.py')
module = importlib.util.module_from_spec(spec)
spec.loader.exec_module(module)


class ReleaseDocumentationTest(unittest.TestCase):
    def test_current_version_in_extraction_path_does_not_hide_old_installation(self):
        with tempfile.TemporaryDirectory(prefix='kartograph-1.2.3-') as directory:
            root = Path(directory)
            (root / 'README.md').write_text('id("io.github.ictechgy.kartograph") version "0.9.0"\n')
            self.assertEqual(['README.md:1'], module.mismatches(root, '1.2.3'))

    def test_current_and_old_versions_on_same_line_remain_mismatched(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            (root / 'README.md').write_text('kartograph-1.2.3.zip replaces kartograph-0.9.0.tar\n')
            self.assertEqual(['README.md:1'], module.mismatches(root, '1.2.3'))

    def test_current_installation_and_historical_prose_are_accepted(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            (root / 'README.md').write_text('Version 0.9.0 was previously released.\nversion "1.2.3" and kartograph-1.2.3.zip\n')
            self.assertEqual([], module.mismatches(root, '1.2.3'))

    def test_missing_directory_and_invalid_version_are_errors(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            with self.assertRaises(ValueError):
                module.mismatches(root / 'missing', '1.2.3')
            with self.assertRaises(ValueError):
                module.mismatches(root, '../invalid')
