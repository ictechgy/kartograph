"""합성 배포물로 checksum 생성의 재현성과 실패 경계를 확인한다."""

import hashlib
import importlib.util
from pathlib import Path
import tempfile
import unittest

spec = importlib.util.spec_from_file_location("release_metadata", Path(__file__).resolve().parents[1] / "prepare-release-metadata.py")
module = importlib.util.module_from_spec(spec)
spec.loader.exec_module(module)


class ReleaseMetadataTest(unittest.TestCase):
    def test_checksums_cover_artifacts_and_sboms_deterministically(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            (root / "VERSION").write_text("1.2.3\n")
            paths = [
                "cli/build/distributions/kartograph-1.2.3.zip",
                "cli/build/distributions/kartograph-1.2.3.tar",
                "gradle-plugin/build/libs/kartograph-gradle-plugin-1.2.3.jar",
                "cli/build/reports/sbom/kartograph-1.2.3.cdx.json",
                "gradle-plugin/build/reports/sbom/kartograph-gradle-plugin-1.2.3.cdx.json",
            ]
            for name in paths:
                path = root / name
                path.parent.mkdir(parents=True, exist_ok=True)
                path.write_bytes(name.encode())
            first = module.prepare(root).read_bytes()
            self.assertEqual(first, module.prepare(root).read_bytes())
            expected = {Path(name).name: hashlib.sha256(name.encode()).hexdigest() for name in paths}
            actual = {name: digest for digest, name in (line.split("  ") for line in first.decode().splitlines())}
            self.assertEqual(expected, actual)
            (root / paths[0]).write_bytes(b"changed")
            self.assertNotEqual(first, module.prepare(root).read_bytes())
            (root / paths[0]).unlink()
            with self.assertRaises(FileNotFoundError):
                module.prepare(root)

    def test_invalid_version_fails_before_writing(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            (root / "VERSION").write_text("../invalid")
            with self.assertRaises(ValueError):
                module.prepare(root)
            self.assertFalse((root / "build").exists())
