"""배포 검증의 파일 identity·fail-closed 경계·공통 두 캡처 질의 조립을 검증한다."""
import copy
import importlib.util
import io
import json
import os
from pathlib import Path
import runpy
import tarfile
import tempfile
import unittest
from unittest import mock
import zipfile

ROOT = Path(__file__).resolve().parents[2]
SPEC = importlib.util.spec_from_file_location("packaged_resource_verifier", ROOT / "Scripts/verify-agp-8-resource-impact.py")
MODULE = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(MODULE)
HELPER = MODULE.PACKAGED


class PackagedResourceTest(unittest.TestCase):
    def setUp(self):
        temporary = tempfile.TemporaryDirectory(prefix="packaged-resource-test-")
        self.addCleanup(temporary.cleanup)
        self.root = Path(temporary.name)
        self.version = "1.2.3"
        (self.root / "fixtures").mkdir()
        self.plugin = self.root / "gradle-plugin/build/libs/kartograph-gradle-plugin-1.2.3.jar"
        self.plugin.parent.mkdir(parents=True)
        with zipfile.ZipFile(self.plugin, "w") as archive:
            archive.writestr("META-INF/MANIFEST.MF", "Manifest-Version: 1.0\r\nImplementation-Version: 1.2.3\r\n\r\n")
            archive.writestr("dev/kartograph/gradle/KartographPlugin.class", b"retained-plugin-class")
        self.files = {"bin/kartograph": b"#!/bin/sh\nexit 0\n", "lib/kartograph-1.2.3.jar": b"retained-cli",
                      "lib/stdlib.jar": b"runtime", "docs/README.md": b"documentation"}
        self.installations, self.archives = {}, {}
        for label in ("zip", "tar"):
            installation = self.root / label / "kartograph-1.2.3"
            self.installations[label] = installation
            for name, content in self.files.items():
                path = installation / name
                path.parent.mkdir(parents=True, exist_ok=True)
                path.write_bytes(content)
            (installation / "bin/kartograph").chmod(0o755)
            archive = self.root / ("cli/build/distributions/kartograph-1.2.3." + label)
            archive.parent.mkdir(parents=True, exist_ok=True)
            self.archives[label] = archive
            self.write_archive(label)
        extra = ("compiler-collectors/build/distributions/kartograph-compiler-collectors-1.2.3.zip",
                 "build/release/kartograph-1.2.3.cdx.json",
                 "build/release/kartograph-gradle-plugin-1.2.3.cdx.json",
                 "gradle-plugin/build/publications/pluginMaven/pom-default.xml")
        self.retained_paths = [self.plugin, *self.archives.values()]
        for relative in extra:
            path = self.root / relative
            path.parent.mkdir(parents=True, exist_ok=True)
            path.write_text("retained", encoding="utf-8")
            self.retained_paths.append(path)
        self.manifest = self.root / "build/release/SHA256SUMS"
        self.retained = self.root / "build/reports/release-readiness/second.sha256"
        self.retained.parent.mkdir(parents=True)
        self.refresh_checksums()
        self.arguments = ["--packaged", "--plugin-jar", str(self.plugin),
                          "--zip-cli", str(self.installations["zip"] / "bin/kartograph"),
                          "--tar-cli", str(self.installations["tar"] / "bin/kartograph"),
                          "--zip-archive", str(self.archives["zip"]), "--tar-archive", str(self.archives["tar"]),
                          "--checksums", str(self.manifest), "--retained-hashes", str(self.retained),
                          "--version", self.version, "--evidence", str(self.root / "evidence")]
        self.args = HELPER["parse_arguments"](self.arguments)

    def write_archive(self, label, files=None):
        files = self.files if files is None else files
        if label == "zip":
            with zipfile.ZipFile(self.archives[label], "w") as archive:
                for name, content in sorted(files.items()):
                    archive.writestr("kartograph-1.2.3/" + name, content)
        else:
            with tarfile.open(self.archives[label], "w") as archive:
                for name, content in sorted(files.items()):
                    entry = tarfile.TarInfo("kartograph-1.2.3/" + name)
                    entry.size = len(content)
                    archive.addfile(entry, io.BytesIO(content))

    def refresh_checksums(self):
        sha = HELPER["digest"]
        release = [path for path in self.retained_paths if path.suffix != ".xml"]
        self.manifest.write_text("".join(sha(path) + "  " + path.name + "\n"
                                       for path in sorted(release, key=lambda path: path.name)), encoding="utf-8")
        self.retained.write_text("".join(sha(path) + "  " + path.relative_to(self.root).as_posix() + "\n"
                                       for path in [*self.retained_paths, self.manifest]), encoding="utf-8")

    def admitted(self):
        return HELPER["PackagedArtifacts"](self.args, self.root)

    def runtime(self, artifacts):
        return {"version": 1, "includedBuilds": 0, "projectDependencies": 0,
                "pluginFileDependencySha256": artifacts.initial["pluginSha256"],
                "plugin": {"version": self.version, "jarSha256": artifacts.initial["pluginSha256"],
                           "classSha256": artifacts.plugin_class_sha256},
                "resolvedBuildscriptArtifacts": [
                    {"coordinate": "com.android.tools.build:gradle:8.7.3", "file": "gradle-8.7.3.jar", "sha256": "a" * 64},
                    {"coordinate": "org.jetbrains.kotlin:kotlin-gradle-plugin:2.0.21:gradle85",
                     "file": "kotlin-gradle-plugin-2.0.21-gradle85.jar", "sha256": "b" * 64}],
                "hostKotlin": {"version": "1.9.24", "jarSha256": "c" * 64, "classSha256": "d" * 64}}

    def test_every_packaged_argument_is_required_and_no_partial_source_fallback(self):
        with mock.patch("sys.stderr", new=io.StringIO()):
            for index in range(1, len(self.arguments), 2):
                with self.subTest(option=self.arguments[index]):
                    with self.assertRaises(SystemExit):
                        HELPER["parse_arguments"](self.arguments[:index] + self.arguments[index + 2:])
            with self.assertRaises(SystemExit):
                HELPER["parse_arguments"](self.arguments[1:])
            with self.assertRaises(SystemExit):
                HELPER["parse_arguments"](["--packaged"])
        self.assertFalse(HELPER["parse_arguments"]([]).packaged)

    def test_source_mode_routes_only_to_existing_source_entrypoint(self):
        with mock.patch.object(MODULE, "source_main") as source, mock.patch.object(MODULE, "packaged_main") as packaged:
            MODULE.main([])
            source.assert_called_once_with()
            packaged.assert_not_called()

    def test_absent_artifact_does_not_consult_source_installation(self):
        self.plugin.unlink()
        with self.assertRaisesRegex(RuntimeError, "required regular artifact"):
            self.admitted()

    def test_complete_archive_and_cli_inventory_is_bound_to_second_build(self):
        artifacts = self.admitted()
        artifacts.unchanged()
        public = artifacts.public_identity()
        self.assertEqual(2, len(public["archives"]))
        self.assertEqual(2, len(public["archives"][0]["libraries"]))
        self.assertNotIn(str(self.root), json.dumps(public))
        (self.installations["zip"] / "lib/stdlib.jar").write_bytes(b"changed")
        with self.assertRaisesRegex(RuntimeError, "mutated"):
            artifacts.unchanged()

    def test_archive_and_plugin_mutation_after_admission_fail(self):
        for label in ("plugin", "zip", "tar", "manifest", "retained"):
            with self.subTest(label=label):
                artifacts = self.admitted()
                path = {"plugin": self.plugin, "zip": self.archives["zip"], "tar": self.archives["tar"],
                        "manifest": self.manifest, "retained": self.retained}[label]
                original = path.read_bytes()
                path.write_bytes(original + b"x")
                try:
                    with self.assertRaisesRegex(RuntimeError, "mutated"):
                        artifacts.unchanged()
                finally:
                    path.write_bytes(original)

    def test_missing_extra_or_duplicate_library_is_not_ignored(self):
        installation = self.installations["zip"]
        for name in ("lib/new.jar", "lib/nested/stdlib.jar", "lib/unknown.txt"):
            with self.subTest(name=name):
                path = installation / name
                path.parent.mkdir(parents=True, exist_ok=True)
                path.write_bytes(b"unexpected")
                try:
                    with self.assertRaises(RuntimeError):
                        self.admitted()
                finally:
                    path.unlink()
        (installation / "lib/stdlib.jar").unlink()
        with self.assertRaises(RuntimeError):
            self.admitted()

    def test_symbolic_and_alias_artifacts_are_rejected(self):
        link = self.root / "linked.jar"
        link.symlink_to(self.plugin)
        with self.assertRaisesRegex(RuntimeError, "symbolic"):
            HELPER["regular_file"](link)
        args = copy.copy(self.args)
        args.tar_cli = args.zip_cli
        with self.assertRaisesRegex(RuntimeError, "collide"):
            HELPER["PackagedArtifacts"](args, self.root)

    def test_checksum_duplicates_basename_collisions_and_incomplete_sets_fail(self):
        original = self.retained.read_text()
        for text in (original + original.splitlines()[0] + "\n",
                     original + "0" * 64 + "  other/" + self.plugin.name + "\n",
                     "\n".join(original.splitlines()[1:]) + "\n"):
            with self.subTest(text=text):
                self.retained.write_text(text)
                with self.assertRaises(RuntimeError):
                    self.admitted()
        self.retained.write_text(original)
        self.manifest.write_text("0" * 64 + "  " + self.plugin.name + "\n")
        with self.assertRaises(RuntimeError):
            self.admitted()

    def test_wrong_manifest_version_fails_even_when_rehashed(self):
        with zipfile.ZipFile(self.plugin, "w") as archive:
            archive.writestr("META-INF/MANIFEST.MF", "Implementation-Version: 9.9.9\n")
            archive.writestr("dev/kartograph/gradle/KartographPlugin.class", b"retained-plugin-class")
        self.refresh_checksums()
        with self.assertRaisesRegex(RuntimeError, "manifest version"):
            self.admitted()

    def test_archive_alias_or_colliding_entry_fails(self):
        for name in ("kartograph-1.2.3/lib/stdlib.jar", "kartograph-1.2.3/lib/../escape.jar",
                     "wrong-root/lib/new.jar", "kartograph-1.2.3/lib//new.jar"):
            with self.subTest(name=name):
                self.write_archive("zip")
                with zipfile.ZipFile(self.archives["zip"], "a") as archive:
                    import warnings
                    with warnings.catch_warnings():
                        warnings.simplefilter("ignore", UserWarning)
                        archive.writestr(name, b"unexpected")
                self.refresh_checksums()
                with self.assertRaises(RuntimeError):
                    self.admitted()

    def test_archive_directory_beneath_file_is_rejected_in_either_order(self):
        for label in ("zip", "tar"):
            for directory_first in (True, False):
                with self.subTest(label=label, directory_first=directory_first):
                    path = self.root / ("collision." + label)
                    entries = [("docs/README.md", False), ("docs/README.md/child/", True)]
                    if directory_first:
                        entries.reverse()
                    if label == "zip":
                        with zipfile.ZipFile(path, "w") as archive:
                            for name, directory in entries:
                                archive.writestr("kartograph-1.2.3/" + name, b"" if directory else b"file")
                    else:
                        with tarfile.open(path, "w") as archive:
                            for name, directory in entries:
                                entry = tarfile.TarInfo("kartograph-1.2.3/" + name)
                                entry.type = tarfile.DIRTYPE if directory else tarfile.REGTYPE
                                entry.size = 0 if directory else 4
                                archive.addfile(entry, None if directory else io.BytesIO(b"file"))
                    with self.assertRaisesRegex(RuntimeError, "file/parent collision"):
                        HELPER["archive_inventory"](path, label, self.version)

    def test_zip_tar_divergence_is_rejected_even_with_matching_manifest(self):
        changed = dict(self.files, **{"lib/stdlib.jar": b"different"})
        self.write_archive("tar", changed)
        (self.installations["tar"] / "lib/stdlib.jar").write_bytes(b"different")
        self.refresh_checksums()
        with self.assertRaisesRegex(RuntimeError, "ZIP/TAR complete installation"):
            self.admitted()

    def test_loaded_origin_version_file_dependency_and_project_fallback_fail_closed(self):
        artifacts = self.admitted()
        valid = self.runtime(artifacts)
        self.assertEqual(valid, HELPER["verify_runtime"](valid, artifacts))
        for key in ("version", "jarSha256", "classSha256"):
            value = copy.deepcopy(valid)
            value["plugin"][key] = "9.9.9" if key == "version" else "f" * 64
            with self.subTest(key=key), self.assertRaises(RuntimeError):
                HELPER["verify_runtime"](value, artifacts)
        for key in ("includedBuilds", "projectDependencies", "pluginFileDependencySha256"):
            value = copy.deepcopy(valid)
            value[key] = "f" * 64 if key.endswith("Sha256") else 1
            with self.subTest(key=key), self.assertRaises(RuntimeError):
                HELPER["verify_runtime"](value, artifacts)

    def test_runtime_inventory_is_complete_bounded_sorted_and_private_path_free(self):
        artifacts = self.admitted()
        for change in ("missing-agp", "duplicate", "order", "host-path", "extra-path", "bound", "kgp-prefix", "kgp-prerelease"):
            value = self.runtime(artifacts)
            rows = value["resolvedBuildscriptArtifacts"]
            if change == "missing-agp":
                rows.pop(0)
            elif change == "duplicate":
                rows.append(copy.deepcopy(rows[1]))
            elif change == "order":
                rows.reverse()
            elif change == "host-path":
                rows[0]["file"] = "/private/user/gradle.jar"
            elif change == "extra-path":
                value["hostKotlin"]["origin"] = "/private/user/kotlin.jar"
            elif change in ("kgp-prefix", "kgp-prerelease"):
                rows[1]["coordinate"] = "org.jetbrains.kotlin:kotlin-gradle-plugin:" + (
                    "2.0.210" if change == "kgp-prefix" else "2.0.21-RC1")
            else:
                rows *= 129
            with self.subTest(change=change), self.assertRaises(RuntimeError):
                HELPER["verify_runtime"](value, artifacts)
        diagnostic = HELPER["runtime_diagnostic"]({"plugin": {"version": "/private/user",
            "jarSha256": "a" * 64, "classSha256": "secret", "origin": "/private/user"}})
        self.assertEqual({"plugin": {"jarSha256": "a" * 64}}, diagnostic)

    def test_complete_semantic_outputs_are_compared_without_projection(self):
        value = {"status": "found", "affected": [], "extra": {"newSemanticField": [1, 2]}}
        matched, digest = HELPER["semantic_pair"]({"zip": value, "tar": copy.deepcopy(value)})
        self.assertEqual(value, matched)
        self.assertEqual(64, len(digest))
        changed = copy.deepcopy(value)
        changed["extra"]["newSemanticField"][1] = 3
        with self.assertRaisesRegex(RuntimeError, "complete semantic outputs"):
            HELPER["semantic_pair"]({"zip": value, "tar": changed})
        with self.assertRaisesRegex(RuntimeError, "both extracted CLIs"):
            HELPER["semantic_pair"]({"zip": value})
        for left, right in ((True, 1), (1, 1.0), (False, 0), ([True], [1])):
            with self.subTest(left=left, right=right), self.assertRaisesRegex(RuntimeError, "complete semantic outputs"):
                HELPER["semantic_pair"]({"zip": {"value": left}, "tar": {"value": right}})
        with self.assertRaises(ValueError):
            HELPER["semantic_pair"]({"zip": {"value": float("nan")}, "tar": {"value": float("nan")}})

    def test_receipt_size_bound_is_enforced(self):
        with self.assertRaisesRegex(RuntimeError, "exceeds bound"):
            HELPER["write_receipt"](self.root / "oversized.json", {"value": "x" * HELPER["MAX_RECEIPT"]})


class PackagedOrchestrationTest(PackagedResourceTest):
    # 조립 harness는 외부 process만 대체한다. 실제 XML/field/JUnit oracle은 별도 계약 테스트가 담당한다.
    def exercise(self, divergence=False, mutated_snapshot=False, wrong_origin=False):
        sdk = self.root / "sdk"
        (sdk / "platforms/android-35").mkdir(parents=True, exist_ok=True)
        (sdk / "build-tools/35.0.0").mkdir(parents=True, exist_ok=True)
        artifacts = self.admitted()
        runtime = self.runtime(artifacts)
        if wrong_origin:
            runtime["plugin"]["jarSha256"] = "f" * 64
        builds, queries = [], []
        def prepare(project):
            for name in MODULE.LAYOUTS:
                text = '<LinearLayout><include layout="@layout/child"/></LinearLayout>' if name == "parent" else '<TextView android:text="Before"/>'
                MODULE.write(project, "src/main/res/layout/" + name + ".xml", text)
            MODULE.write(project, "src/main/java/ResourceAccess.java", "class ResourceAccess {}")
        def run(arguments, cwd, output, name, expected=0):
            values = list(map(str, arguments))
            if values == ["git", "rev-parse", "HEAD"]:
                return "e" * 40
            if values == ["git", "rev-parse", "HEAD^{tree}"]:
                return "a" * 40
            if values[0].endswith("/bin/gradle") and values[1:] == ["--version"]:
                return "Gradle 8.10.2\n"
            if values[0].endswith("/bin/gradle"):
                self.assertEqual(["verifyPackagedKartographRuntime", "kartographSnapshotDebug",
                                  "testDebugUnitTest", "--rerun-tasks"], values[-4:])
                self.assertIn("-Pkartograph.pluginJar=" + str(self.plugin), values)
                self.assertIn("--no-configuration-cache", values)
                project = Path(values[values.index("-p") + 1])
                builds.append(project)
                MODULE.write(project, "build/reports/kartograph/debug-snapshot.json",
                             json.dumps({"androidResources": {}, "androidResourceBindings": {}, "phase": len(builds)}))
                MODULE.write(project, "build/kartograph/debug-input-bindings.json", "{}")
                MODULE.write(project, "build/reports/kartograph/packaged-runtime.json", json.dumps(runtime))
                return ""
            self.assertIn(values[0], [str(path / "bin/kartograph") for path in self.installations.values()])
            if values[1:] == ["--version"]:
                return "kartograph " + self.version
            self.assertIn(values[1], ("impact", "verify-snapshot"))
            graph = Path(values[values.index("--graph-file") + 1])
            queries.append((name, str(graph), MODULE.digest(graph), expected))
            if mutated_snapshot:
                graph.write_text('{"mutated":true}')
            value = {"status": "stale" if name.startswith("resource-edit-stale") else "matched",
                     "complete": {"extra": 1}}
            if divergence and name.endswith("-tar"):
                value["complete"]["extra"] = 2
            return json.dumps(value)
        with mock.patch.object(MODULE, "ROOT", self.root), \
             mock.patch.object(MODULE, "prepare", side_effect=prepare), \
             mock.patch.object(MODULE, "run", side_effect=run), \
             mock.patch.object(MODULE, "verify_resource_bindings", return_value={"status": "captured"}), \
             mock.patch.object(MODULE, "verify_junit", return_value=["executed"]), \
             mock.patch.object(MODULE, "verify_field_path", return_value=["exact-field-witness"]), \
             mock.patch.object(MODULE, "verify_xml_impact", return_value={"includeParent": True}), \
             mock.patch.object(MODULE, "verify_resource_unavailable") as unavailable, \
             mock.patch.dict(os.environ, {"GRADLE_8_HOME": str(self.root / "gradle"), "ANDROID_HOME": str(sdk)}), \
             mock.patch("sys.stdout", new=io.StringIO()):
            MODULE.packaged_main(self.args)
        return builds, queries, unavailable

    def test_only_two_builds_and_identical_snapshots_for_both_clis(self):
        builds, queries, unavailable = self.exercise()
        self.assertEqual(2, len(builds))
        self.assertEqual(builds[0], builds[1])
        self.assertEqual(40, len(queries))
        for index in range(0, len(queries), 2):
            self.assertEqual(queries[index][1:], queries[index + 1][1:])
            self.assertTrue(queries[index][0].endswith("-zip"))
            self.assertTrue(queries[index + 1][0].endswith("-tar"))
        unavailable.assert_called_once()
        receipt = json.loads((self.root / "evidence/verification.json").read_text())
        self.assertEqual("passed", receipt["status"])
        self.assertEqual(2, receipt["agpBuildCount"])
        self.assertEqual("e" * 40, receipt["sourceRevision"])
        self.assertEqual("a" * 40, receipt["sourceTree"])
        self.assertEqual(20, len(receipt["semanticOutputSha256"]))
        self.assertTrue(receipt["artifactsUnchanged"])
        self.assertNotIn(str(self.root), json.dumps(receipt))
        receipt["private"] = "/private/user"
        receipt["phases"][0]["sourceSha256"]["/private/user"] = "f" * 64
        summary = MODULE.packaged_log_summary(receipt)
        self.assertEqual("a" * 40, summary["sourceTree"])
        self.assertEqual(20, summary["semanticPairCount"])
        self.assertEqual(self.version, summary["loadedRuntime"]["plugin"]["version"])
        self.assertEqual(receipt["artifacts"]["plugin"]["sha256"], summary["retainedPluginSha256"])
        self.assertEqual(2, len(summary["cliArchives"]))
        self.assertEqual(2, len(summary["phases"]))
        self.assertEqual({"child", "parent", "screen", "unrelated"}, set(summary["phases"][0]["xmlSha256"]))
        self.assertNotIn("private", json.dumps(summary))
        self.assertLess(len(json.dumps(summary).encode("utf-8")), 64 * 1024)

    def test_divergent_semantics_and_mutated_shared_snapshot_fail_with_receipt(self):
        for change in ("divergence", "mutated_snapshot", "wrong_origin"):
            with self.subTest(change=change), self.assertRaises(RuntimeError):
                self.exercise(**{change: True})
            receipt = json.loads((self.root / "evidence/verification.json").read_text())
            self.assertEqual("failed", receipt["status"])
            self.assertNotIn(str(self.root), json.dumps(receipt))


if __name__ == "__main__":
    unittest.main()
