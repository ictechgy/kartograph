"""관측기의 실패 경계 검사다. 실제 Gradle/AGP 실행 성공을 대신하지 않는다."""
import copy
import importlib.util
import json
from pathlib import Path
import tempfile
from types import SimpleNamespace
import unittest
from unittest.mock import patch
import zipfile

SPEC = importlib.util.spec_from_file_location("gradle813", Path(__file__).parents[1] / "observe-safeargs-gradle813.py")
if SPEC is None or SPEC.loader is None:
    raise RuntimeError("test module specification with a loader is required")
MODULE = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(MODULE)


class Gradle813ObservationTest(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        self.root = Path(self.temp.name)
        self.home = self.root / "home"; (self.home / "lib").mkdir(parents=True)
        self.action = self.home / "lib/gradle-core-8.13.jar"
        with zipfile.ZipFile(self.action, "w") as jar:
            jar.writestr(MODULE.ACTION.replace(".", "/") + ".class", b"unit-test-only-class-bytes")
        self.observation = {
            "gradle": "8.13", "java": "17", "javaRuntimeVersion": "17.0.synthetic", "javaVendor": "unit-test-only",
            "task": ":generateSafeArgsDebug", "taskType": MODULE.TASK,
            "artifacts": {
                "plugin": {"type": MODULE.TASK, "name": "plugin.jar", "bytes": 1, "sha256": MODULE.PLUGIN_SHA},
                "generator": {"type": "androidx.navigation.safe.args.generator.NavSafeArgsGenerator", "name": "generator.jar", "bytes": 1, "sha256": MODULE.GENERATOR_SHA},
            },
            "actions": [{"type": MODULE.ACTION, "name": self.action.name, "bytes": self.action.stat().st_size,
                         "sha256": MODULE.digest(self.action), "distributionPath": "lib/gradle-core-8.13.jar"}],
            "state": {"executed": True, "skipped": False, "upToDate": False, "didWork": True, "failure": None, "skipMessage": None},
            "providers": {"navigationFiles": ["src/debug/res/navigation/graph.xml"], "allInputFiles": ["src/debug/res/navigation/graph.xml"],
                          "outputDir": "build/generated", "incrementalFolder": "build/mapping", "applicationId": "p.safeargs", "rFilePackage": "p", "useAndroidX": True, "generateKotlin": False},
        }

    def reject(self, change):
        value = copy.deepcopy(self.observation); change(value)
        with self.assertRaises((ValueError, KeyError, TypeError)):
            MODULE.validate_observation(value, self.home, "cold")

    def test_cold_and_actual_warm_states(self):
        MODULE.validate_observation(self.observation, self.home, "cold")
        self.observation["state"].update(skipped=True, upToDate=True, didWork=False, skipMessage="UP-TO-DATE")
        MODULE.validate_observation(self.observation, self.home, "warm")
        with self.assertRaises(ValueError):
            MODULE.validate_observation(self.observation, self.home, "cold")

    def test_non_object_observation_has_a_bounded_domain_error(self):
        for value in (None, False, 1, "bounded", []):
            with self.subTest(value=value), self.assertRaisesRegex(ValueError, "observation must be a JSON object"):
                MODULE.validate_observation(value, self.home, "cold")

    def test_archive_parent_and_new_unpack_destination_are_separate(self):
        # workflow의 실제 디렉터리 순서를 작은 ZIP으로 실행한다. 다운로드/Gradle 실행은 하지 않는다.
        root = self.root / "distribution"; root.mkdir()
        archive = root / "gradle-8.13-bin.zip"
        with zipfile.ZipFile(archive, "w") as zipped:
            zipped.writestr("gradle-8.13/bin/gradle", b"synthetic launcher, not executable")
        expected = MODULE.digest(archive)
        with self.assertRaises(FileExistsError):
            MODULE.unpack(archive, root, expected)
        home = MODULE.unpack(archive, root / "installation", expected)
        self.assertEqual(root / "installation/gradle-8.13", home)
        self.assertEqual(b"synthetic launcher, not executable", (home / "bin/gradle").read_bytes())
        self.assertEqual(expected, MODULE.digest(archive))
        with self.assertRaisesRegex(ValueError, "checksum"):
            MODULE.unpack(archive, root / "wrong-checksum", "0" * 64)
        self.assertFalse((root / "wrong-checksum").exists())

    def test_toolchain_and_generator_artifacts(self):
        for key, value in [("gradle", "9.7.1"), ("java", "21"), ("task", ":generateSafeArgsRelease"), ("taskType", "FakeTask")]:
            with self.subTest(key=key): self.reject(lambda x: x.update({key: value}))
        self.reject(lambda x: x["artifacts"]["plugin"].update(sha256="0" * 64))
        self.reject(lambda x: x["artifacts"]["generator"].update(type="FakeGenerator"))

    def test_extra_wrong_missing_and_mutated_actions(self):
        self.reject(lambda x: x["actions"].append(x["actions"][0]))
        self.reject(lambda x: x.update(actions=[]))
        self.reject(lambda x: x["actions"][0].update(type="ExtraAction"))
        self.reject(lambda x: x["actions"][0].update(sha256="0" * 64))
        self.reject(lambda x: x["actions"][0].update(distributionPath="../outside.jar"))
        self.reject(lambda x: x["actions"][0].update(name="different.jar"))
        self.action.write_bytes(self.action.read_bytes() + b"changed")
        with self.assertRaises(ValueError): MODULE.validate_observation(self.observation, self.home, "cold")

    def test_action_class_must_exist_in_observed_container(self):
        with zipfile.ZipFile(self.action, "w") as jar: jar.writestr("Other.class", b"test")
        self.observation["actions"][0].update(bytes=self.action.stat().st_size, sha256=MODULE.digest(self.action))
        with self.assertRaises(ValueError): MODULE.validate_observation(self.observation, self.home, "cold")

    def test_failure_skip_cache_and_incomplete_input_states(self):
        for changed in [{"failure": "Failure"}, {"executed": False}, {"skipMessage": "FROM-CACHE", "skipped": True}, {"upToDate": True}]:
            with self.subTest(changed=changed): self.reject(lambda x: x["state"].update(changed))
        self.reject(lambda x: x["providers"].update(allInputFiles=[]))
        self.reject(lambda x: x["providers"].update(generateKotlin=True))

    def test_full_source_inventory_and_mapping(self):
        for name in MODULE.EXPECTED_SOURCES:
            path = self.root / "build/generated" / name; path.parent.mkdir(parents=True, exist_ok=True); path.write_text("synthetic", encoding="utf-8")
        nav = self.root / "src/debug/res/navigation/graph.xml"; nav.parent.mkdir(parents=True); nav.write_text(MODULE.NAVIGATION, encoding="utf-8")
        mapping = self.root / "build/mapping/file_mappings.json"; mapping.parent.mkdir(parents=True)
        data = [{"navFile": "src/debug/res/navigation/graph.xml", "javaFiles": sorted(n[:-5].replace("/", ".") for n in MODULE.EXPECTED_SOURCES)}]
        mapping.write_text(json.dumps(data), encoding="utf-8")
        self.assertEqual(3, len(MODULE.capture_outputs(self.root)["sources"]))
        extra = self.root / "build/generated/p/Manual.java"; extra.write_text("manual", encoding="utf-8")
        with self.assertRaises(ValueError): MODULE.capture_outputs(self.root)
        extra.unlink(); data[0]["javaFiles"].pop(); mapping.write_text(json.dumps(data), encoding="utf-8")
        with self.assertRaises(ValueError): MODULE.capture_outputs(self.root)

    def test_distribution_checksum_path_and_expansion_bound(self):
        archive = self.root / "official-test.zip"
        with zipfile.ZipFile(archive, "w") as z:
            z.writestr("gradle-8.13/", b""); z.writestr("gradle-8.13/bin/gradle", b"synthetic")
        with self.assertRaises(ValueError): MODULE.unpack(archive, self.root / "bad-sha")
        with patch.object(MODULE, "MAX_EXPANDED", 4), self.assertRaises(ValueError):
            MODULE.unpack(archive, self.root / "expanded", MODULE.digest(archive))
        home = MODULE.unpack(archive, self.root / "extracted", MODULE.digest(archive))
        self.assertEqual(b"synthetic", (home / "bin/gradle").read_bytes())
        with zipfile.ZipFile(archive, "w") as z: z.writestr("gradle-8.13/../escape", b"bad")
        with self.assertRaises(ValueError): MODULE.unpack(archive, self.root / "escape", MODULE.digest(archive))
        with zipfile.ZipFile(archive, "w") as z:
            item = zipfile.ZipInfo("gradle-8.13/link"); item.external_attr = 0o120777 << 16; z.writestr(item, "elsewhere")
        with self.assertRaises(ValueError): MODULE.unpack(archive, self.root / "link", MODULE.digest(archive))

    def test_corrupt_archive_and_unknown_phase_are_not_success(self):
        archive = self.root / "corrupt.zip"; archive.write_bytes(b"truncated not-a-zip")
        with self.assertRaises(zipfile.BadZipFile): MODULE.unpack(archive, self.root / "corrupt", MODULE.digest(archive))
        with self.assertRaises(ValueError): MODULE.validate_observation(self.observation, self.home, "skipped")
        self.reject(lambda x: x.update(javaRuntimeVersion=""))

    def test_bounded_json_and_portable_paths(self):
        path = self.root / "json"
        for text in ['{"x":1,"x":2}', ' ' * (MODULE.MAX_JSON + 1)]:
            path.write_text(text, encoding="utf-8")
            with self.assertRaises(ValueError): MODULE.read_json(path)
        for name in ["/absolute", "../up", "a/../up", "C:/local", "a\\b", "a//b", "./a"]:
            with self.subTest(name=name), self.assertRaises(ValueError): MODULE.portable(name)

    def test_failed_validation_preserves_bounded_observed_identity(self):
        output = self.root / "failed-observation"
        value = copy.deepcopy(self.observation); value["actions"][0]["type"] = "UnknownActualAction"
        def fake_execution(*args, **kwargs):
            observed = kwargs["cwd"] / "build/observation.json"; observed.parent.mkdir()
            observed.write_text(json.dumps(value), encoding="utf-8")
            return SimpleNamespace(returncode=0)
        with patch("sys.argv", ["observer", "--output", str(output)]), patch.object(MODULE, "download"), \
             patch.object(MODULE, "unpack", return_value=self.home), patch.object(MODULE.subprocess, "run", side_effect=fake_execution):
            self.assertEqual(1, MODULE.main())
        receipt = json.loads((output / "receipt.json").read_text(encoding="utf-8"))
        self.assertEqual("failed", receipt["status"])
        self.assertFalse(receipt["productAdmission"])
        self.assertEqual("unverified", receipt["runs"][0]["validation"])
        self.assertEqual(value, receipt["runs"][0]["observation"])


if __name__ == "__main__":
    unittest.main()
