"""고정 소비 Gradle 준비의 안전 경계다. 실제 Gradle 성공을 대신하지 않는다."""
import hashlib
import importlib.util
import io
import json
import os
from pathlib import Path
import shutil
import stat
import tempfile
import unittest
from unittest.mock import patch
import zipfile

SPEC = importlib.util.spec_from_file_location("current_gradle", Path(__file__).parents[1] / "prepare-safeargs-current-gradle.py")
if SPEC is None or SPEC.loader is None:
    raise RuntimeError("test module specification with a loader is required")
MODULE = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(MODULE)


class CurrentGradlePreparationTest(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        self.root = Path(self.temp.name)
        self.archive = self.root / "synthetic.zip"
        self.output = self.root / "prepared"
        self.make_archive()

    def make_archive(self, version="9.7.1", extra=None):
        with zipfile.ZipFile(self.archive, "w") as zipped:
            zipped.writestr(f"gradle-{version}/bin/gradle", b"selection-only, never executed")
            zipped.writestr(f"gradle-{version}/bin/gradle.bat", b"selection-only, never executed")
            zipped.writestr(f"gradle-{version}/lib/gradle-core-{version}.jar", b"selection-only, never executed")
            if extra:
                zipped.writestr(*extra)

    def prepare(self, checksum=None):
        expected = checksum or hashlib.sha256(self.archive.read_bytes()).hexdigest()
        def synthetic_download(path, *, version):
            self.assertEqual("9.7.1", version)
            shutil.copyfile(self.archive, path)
        with patch.object(MODULE, "DISTRIBUTION_SHA", expected), patch.object(MODULE.SHARED, "download", side_effect=synthetic_download):
            return MODULE.prepare(self.output)

    def test_complete_pinned_output_and_receipt_are_published_together(self):
        home = self.prepare()
        self.assertEqual(self.output / "distribution/gradle-9.7.1", home)
        value = json.loads((self.output / "receipt.json").read_text())
        self.assertEqual("9.7.1", value["version"])
        self.assertEqual(hashlib.sha256(self.archive.read_bytes()).hexdigest(), value["distributionSha256"])
        self.assertEqual("distribution/gradle-9.7.1", value["home"])
        self.assertFalse(list(self.root.glob(".safeargs-current-gradle-*")))

    def test_gradle_created_container_and_forced_repeat_revalidate_the_same_child(self):
        container = self.root / "gradle-declared-output"
        container.mkdir()
        self.output = container / "prepared"
        first = self.prepare()
        before = {str(p.relative_to(self.output)): p.read_bytes() for p in self.output.rglob("*") if p.is_file()}
        expected = hashlib.sha256(self.archive.read_bytes()).hexdigest()
        # --rerun-tasks 또는 script input 변경 뒤의 Exec 재실행과 같은 기존 출력 상태다.
        with patch.object(MODULE, "DISTRIBUTION_SHA", expected), patch.object(MODULE.SHARED, "download", side_effect=AssertionError("verified output must not be downloaded again")):
            self.assertEqual(first, MODULE.prepare(self.output))
            self.assertEqual(first, MODULE.prepare(self.output))
        after = {str(p.relative_to(self.output)): p.read_bytes() for p in self.output.rglob("*") if p.is_file()}
        self.assertEqual(before, after)

    def test_partial_or_changed_prepared_output_is_not_silently_reused(self):
        home = self.prepare()
        core = home / "lib/gradle-core-9.7.1.jar"
        original = core.read_bytes()
        core.write_bytes(b"x" * len(original))
        with self.assertRaisesRegex(ValueError, "content mismatch"):
            self.prepare()
        core.unlink()
        with self.assertRaisesRegex(ValueError, "missing"):
            self.prepare()
        core.write_bytes(original)
        extra = home / "unexpected"
        extra.write_text("preserve unexpected input")
        with self.assertRaisesRegex(ValueError, "extra|inventory"):
            self.prepare()
        self.assertEqual("preserve unexpected input", extra.read_text())

    def test_reuse_rejects_changed_archive_receipt_and_linked_file(self):
        home = self.prepare()
        cached = self.output / "gradle-9.7.1-bin.zip"
        original_archive = cached.read_bytes()
        cached.write_bytes(original_archive + b"changed")
        with self.assertRaisesRegex(ValueError, "archive mismatch"):
            self.prepare()
        cached.write_bytes(original_archive)
        record = self.output / "receipt.json"
        original = record.read_bytes()
        record.write_text('{"version":"9.7.1","version":"9.7.1"}')
        with self.assertRaisesRegex(ValueError, "duplicate"):
            self.prepare()
        record.write_bytes(original)
        core = home / "lib/gradle-core-9.7.1.jar"
        external = self.root / "preserve-external"
        external.write_bytes(core.read_bytes())
        core.unlink()
        core.symlink_to(external)
        with self.assertRaisesRegex(ValueError, "linked"):
            self.prepare()
        self.assertTrue(external.exists())

    def test_receipt_schema_and_field_types_are_exact(self):
        self.prepare()
        record = self.output / "receipt.json"
        value = json.loads(record.read_text())
        for key, changed in [("schemaVersion", True), ("schemaVersion", 1.0), ("schemaVersion", "1"),
                             ("version", 971), ("home", None), ("scope", []), ("unknown", "extra")]:
            with self.subTest(key=key, changed=changed):
                altered = {**value, key: changed}
                record.write_text(json.dumps(altered))
                with self.assertRaisesRegex(ValueError, "receipt mismatch"):
                    self.prepare()

    @unittest.skipIf(os.name == "nt", "POSIX mode contract")
    def test_reuse_rejects_changed_executable_permissions(self):
        home = self.prepare()
        core = home / "lib/gradle-core-9.7.1.jar"
        core.chmod(0o755)
        with self.assertRaisesRegex(ValueError, "permissions mismatch"):
            self.prepare()

    def test_directory_link_is_rejected_before_reading_its_external_files(self):
        home = self.prepare()
        external = self.root / "external-lib"
        (home / "lib").rename(external)
        (home / "lib").symlink_to(external, target_is_directory=True)
        with patch.object(MODULE.SHARED, "digest", wraps=MODULE.SHARED.digest) as digest:
            with self.assertRaisesRegex(ValueError, "linked"):
                self.prepare()
        self.assertFalse(any(call.args[0].name == "gradle-core-9.7.1.jar" for call in digest.call_args_list))

    def test_checksum_mismatch_retains_staging_but_never_publishes_home(self):
        with self.assertRaisesRegex(ValueError, "checksum"):
            self.prepare("0" * 64)
        self.assertFalse(self.output.exists())
        self.assertEqual(1, len(list(self.root.glob(".safeargs-current-gradle-*"))))

    def test_another_version_is_not_admitted_even_with_matching_test_digest(self):
        self.make_archive(version="9.8.1")
        with self.assertRaisesRegex(ValueError, "distribution entry"):
            self.prepare()
        self.assertFalse(self.output.exists())

    def test_existing_and_linked_output_are_not_overwritten(self):
        self.output.mkdir()
        marker = self.output / "owner-input"
        marker.write_text("preserve")
        with self.assertRaises(ValueError):
            self.prepare()
        self.assertEqual("preserve", marker.read_text())
        link = self.root / "linked"
        link.symlink_to(self.output, target_is_directory=True)
        with self.assertRaises(ValueError):
            MODULE.prepare(link)
        self.assertEqual("preserve", marker.read_text())

    def test_path_escape_and_symlink_members_are_rejected(self):
        for extra in [("../outside", b"escape"), ("gradle-9.7.1/../outside", b"escape")]:
            with self.subTest(extra=extra):
                self.make_archive(extra=extra)
                with self.assertRaises(ValueError):
                    self.prepare()
                self.assertFalse(self.output.exists())
        info = zipfile.ZipInfo("gradle-9.7.1/linked")
        info.external_attr = (stat.S_IFLNK | 0o777) << 16
        self.make_archive(extra=(info, b"../outside"))
        with self.assertRaisesRegex(ValueError, "linked"):
            self.prepare()
        self.assertFalse((self.root / "outside").exists())

    def test_expansion_and_duplicate_members_are_rejected_before_publication(self):
        with patch.object(MODULE.SHARED, "MAX_EXPANDED", 4), self.assertRaisesRegex(ValueError, "expanded"):
            self.prepare()
        self.make_archive(extra=("gradle-9.7.1/bin/gradle", b"duplicate"))
        with self.assertRaisesRegex(ValueError, "duplicate"):
            self.prepare()
        self.assertFalse(self.output.exists())

    def test_missing_core_never_publishes_an_incomplete_installation(self):
        with zipfile.ZipFile(self.archive, "w") as zipped:
            zipped.writestr("gradle-9.7.1/bin/gradle", b"selection-only")
        with self.assertRaisesRegex(ValueError, "complete pinned"):
            self.prepare()
        self.assertFalse(self.output.exists())

    def test_download_is_restricted_to_registered_official_versions(self):
        with patch.object(MODULE.SHARED.urllib.request, "urlopen") as request:
            with self.assertRaisesRegex(ValueError, "unregistered"):
                MODULE.SHARED.download(self.root / "unexpected.zip", version="latest")
            request.assert_not_called()
            request.return_value = io.BytesIO(b"synthetic download, never executed")
            MODULE.SHARED.download(self.root / "download.zip", version="9.7.1")
            request.assert_called_once_with("https://services.gradle.org/distributions/gradle-9.7.1-bin.zip", timeout=60)


if __name__ == "__main__":
    unittest.main()
