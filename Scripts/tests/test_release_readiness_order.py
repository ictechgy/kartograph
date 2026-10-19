"""실제 readiness shell의 독립 검증 순서와 지연된 test 실패의 종료 전파를 검증한다.

readiness가 봉인한 배포물 해시 목록이 release publish job이 다시 계산할 공용 목록과 같은지도 확인한다.
"""
import json
import os
from pathlib import Path
import subprocess
import sys
import tempfile
import unittest

ROOT = Path(__file__).resolve().parents[2]
SCRIPT = ROOT / "Scripts/verify-release-readiness.sh"
# readiness와 release publish job이 함께 쓰는 배포물 빌드·경로·해시 스크립트다.
ARTIFACTS = ROOT / "Scripts/release-artifacts.sh"

# 외부 빌드/CLI만 대체한다. 실제 shell 조건·set -e·hash 비교·최종 종료는 실행한다.
MOCK = r'''
import hashlib
import json
import os
from pathlib import Path
import sys

root = Path.cwd()
state_path = Path(os.environ["MOCK_STATE"])
state = json.loads(state_path.read_text())
command, args = Path(sys.argv[0]).name, sys.argv[1:]
version = "1.2.3"
reports = root / "build/reports/release-readiness"
plugin = root / "gradle-plugin/build/libs/kartograph-gradle-plugin-1.2.3.jar"
release_files = [
    root / "cli/build/distributions/kartograph-1.2.3.zip",
    root / "cli/build/distributions/kartograph-1.2.3.tar",
    plugin,
    root / "compiler-collectors/build/distributions/kartograph-compiler-collectors-1.2.3.zip",
    root / "build/release/kartograph-1.2.3.cdx.json",
    root / "build/release/kartograph-gradle-plugin-1.2.3.cdx.json",
]

def write(path, content):
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text(content)

def sha(path):
    return hashlib.sha256(path.read_bytes()).hexdigest()

def event(name):
    state["events"].append({"name": name, "args": args})
    state_path.write_text(json.dumps(state))
    if os.environ.get("MOCK_FAIL_STAGE") == name:
        sys.exit(17)

def installation(destination, label):
    installed = destination / ("kartograph-" + version)
    launcher = installed / "bin/kartograph"
    write(launcher, "#!" + sys.executable + "\n" + Path(sys.argv[0]).read_text().split("\n", 1)[1])
    launcher.chmod(0o755)
    for name in ["README.md", "README.ko.md", "docs/LIMITATIONS.md", "Skills/kartograph/SKILL.md",
                 "THIRD_PARTY_NOTICES.md", "LICENSES/Apache-2.0.txt", "LICENSES/BSD-3-Clause.txt",
                 "Scripts/check-pr.py"]:
        write(installed / name, "fixture\n")
    for name in ("BINDINGS", "CAPTURE", "EVIDENCE", "IMPACT-EXPERIMENT", "IMPACT", "PRODUCER-EVIDENCE"):
        write(installed / ("docs/ANDROID-RESOURCE-" + name + ".md"), "fixture\n")
    state[label + "_cli"] = str(launcher)
    state_path.write_text(json.dumps(state))

if command == "gradlew":
    if any(row["name"] == "package-proof" for row in state["events"]):
        raise SystemExit("repository build attempted after packaged proof")
    if args == ["--no-daemon", "clean", ":cli:distZip", ":cli:distTar", ":gradle-plugin:jar",
                ":gradle-plugin:generatePomFileForPluginMavenPublication", ":gradle-plugin:validatePlugins",
                ":cli:runtimeSbom", ":gradle-plugin:runtimeSbom"]:
        event("build")
        for path in release_files[:3]:
            write(path, "retained:" + path.name)
        if os.environ.get("MOCK_NONREPRODUCIBLE") == "1" and sum(row["name"] == "build" for row in state["events"]) == 2:
            plugin.write_text("second build differs")
        write(root / "gradle-plugin/build/publications/pluginMaven/pom-default.xml",
              "\n".join("<" + name + ">fixture</" + name + ">" for name in
                        ("name", "description", "url", "licenses", "scm")))
    elif args == ["--no-daemon", "-p", "compiler-collectors", "clean", "distZip"]:
        event("collector-build")
        write(release_files[3], "retained:collector")
    elif args[:5] == ["--no-daemon", "--no-configuration-cache", "-p", "compiler-collectors", "-I"] and args[-1] == "releaseEnumRuntime":
        event("enum-runtime")
        write(Path(os.environ["KARTOGRAPH_ENUM_RUNTIME_FILE"]), "[]")
    else:
        raise SystemExit("unexpected repository build")
elif command == "python3":
    if args == ["Scripts/prepare-release-metadata.py"]:
        event("metadata")
        for path in release_files[-2:]:
            write(path, "retained:" + path.name)
        write(root / "build/release/SHA256SUMS",
              "".join(sha(path) + "  " + path.name + "\n" for path in sorted(release_files)))
    elif args[:1] == ["Scripts/verify-collector-installation.py"]:
        event("collector-installation")
        destination = Path(args[args.index("--evidence") + 1])
        write(destination / ("installed/kartograph-compiler-collectors-" + version + "/lib/kartograph-compiler-collectors.jar"),
              "installed collector")
    elif args == ["-m", "unittest", "discover", "-s", "Scripts/tests", "-v"]:
        label = "zip" if os.environ["KARTOGRAPH_BINARY"] == state["zip_cli"] else "tar"
        assert os.environ["KARTOGRAPH_BINARY"] == state[label + "_cli"]
        assert os.environ["KARTOGRAPH_PR_SCRIPT"] == str(Path(state[label + "_cli"]).parents[1] / "Scripts/check-pr.py")
        event(label + "-discovery")
        if label == "zip" and os.environ.get("MOCK_MUTATE_ARTIFACT") == "1":
            plugin.write_text("changed after sealing")
        print(label + " original unittest output", flush=True)
        print(label + " original unittest diagnostics", file=sys.stderr, flush=True)
        sys.exit(int(os.environ.get("MOCK_" + label.upper() + "_STATUS", "0")))
    elif args[:2] == ["Scripts/verify-agp-8-resource-impact.py", "--packaged"]:
        event("package-proof")
        options = dict(zip(args[2::2], args[3::2]))
        assert options == {
            "--plugin-jar": "gradle-plugin/build/libs/kartograph-gradle-plugin-1.2.3.jar",
            "--zip-cli": state["zip_cli"], "--tar-cli": state["tar_cli"],
            "--zip-archive": "cli/build/distributions/kartograph-1.2.3.zip",
            "--tar-archive": "cli/build/distributions/kartograph-1.2.3.tar",
            "--checksums": "build/release/SHA256SUMS",
            "--retained-hashes": "build/reports/release-readiness/second.sha256",
            "--version": "1.2.3", "--evidence": "build/reports/release-readiness/packaged-resource-impact",
        }
        names = [row["name"] for row in state["events"]]
        assert names.count("build") == names.count("collector-build") == names.count("metadata") == 2
        assert all(name in names for name in ("collector-installation", "extract-zip", "extract-tar",
                                              "cli-zip", "cli-tar", "zip-discovery", "tar-discovery",
                                              "enum-runtime", "enum-proof"))
        retained = (reports / "second.sha256").read_text()
        assert retained == (reports / "first.sha256").read_text()
        assert len(retained.splitlines()) == 8
        for line in retained.splitlines():
            expected, name = line.split("  ", 1)
            if sha(root / name) != expected:
                raise SystemExit(33)
        write(reports / "packaged-resource-impact/verification.json", '{"status":"passed"}\n')
    elif args[:1] == ["Scripts/verify-release-documentation.py"]:
        event("documentation")
    elif args[:2] == ["-", version] and len(args) == 7:
        event("enum-proof")
        assert args[3:5] == [state["zip_cli"], state["tar_cli"]]
        assert Path(args[5]).is_file()
    elif args[:1] == ["-"] and len(args) == 2:
        event("documentation-links")
    else:
        raise SystemExit("unexpected Python command")
elif command == "shasum" and args[:2] == ["-a", "256"] and len(args) == 3:
    event("hash")
    print(sha(root / args[2]) + "  " + args[2])
elif command == "jar" and args == ["tf", str(plugin.relative_to(root))]:
    event("plugin-admission")
    print("\n".join([
        "META-INF/gradle-plugins/io.github.ictechgy.kartograph.properties",
        "dev/kartograph/analysis/ReachabilityAnalyzer.class", "dev/kartograph/index/ClassFileIndexer.class",
        "org/objectweb/asm/ClassReader.class", "kotlin/metadata/jvm/KotlinClassMetadata.class",
        "kotlin/jvm/internal/Intrinsics.class", "org/jetbrains/annotations/NotNull.class",
        "META-INF/kartograph/LICENSE", "META-INF/kartograph/THIRD_PARTY_NOTICES.md",
        "META-INF/licenses/Apache-2.0.txt", "META-INF/licenses/BSD-3-Clause.txt",
    ]))
elif command == "unzip" and args[:2] == ["-q", "cli/build/distributions/kartograph-1.2.3.zip"] and args[2] == "-d":
    event("extract-zip")
    installation(Path(args[3]), "zip")
elif command == "tar" and args[:3] == ["-xf", "cli/build/distributions/kartograph-1.2.3.tar", "-C"]:
    event("extract-tar")
    installation(Path(args[3]), "tar")
elif command == "kartograph" and args == ["--version"]:
    print("kartograph " + version)
elif command == "verify-cli-contract.sh" and len(args) == 1:
    label = "zip" if args[0] == state["zip_cli"] else "tar"
    assert args[0] == state[label + "_cli"]
    event("cli-" + label)
else:
    raise SystemExit("unexpected external command")
'''


class ReleaseReadinessOrderTest(unittest.TestCase):
    def run_readiness(self, zip_status=0, tar_status=0, fail_stage="", mutate=False, nonreproducible=False):
        temporary = tempfile.TemporaryDirectory(prefix="readiness-order-")
        self.addCleanup(temporary.cleanup)
        root = Path(temporary.name)
        (root / "Scripts").mkdir()
        (root / "bin").mkdir()
        (root / "tmp").mkdir()
        (root / "VERSION").write_text("1.2.3\n")
        script = root / "Scripts/verify-release-readiness.sh"
        script.write_text(SCRIPT.read_text(encoding="utf-8"), encoding="utf-8")
        artifacts = root / "Scripts/release-artifacts.sh"
        artifacts.write_text(ARTIFACTS.read_text(encoding="utf-8"), encoding="utf-8")
        artifacts.chmod(0o755)
        state = root / "state.json"
        state.write_text(json.dumps({"events": []}))
        for path in [root / "gradlew", root / "Scripts/verify-cli-contract.sh",
                     *(root / "bin" / name for name in ("python3", "shasum", "jar", "unzip", "tar"))]:
            path.write_text("#!" + sys.executable + "\n" + MOCK, encoding="utf-8")
            path.chmod(0o755)
        env = {"PATH": str(root / "bin") + os.pathsep + os.defpath, "HOME": str(root),
               "TMPDIR": str(root / "tmp"), "LC_ALL": "C", "MOCK_STATE": str(state),
               "MOCK_ZIP_STATUS": str(zip_status), "MOCK_TAR_STATUS": str(tar_status),
               "MOCK_FAIL_STAGE": fail_stage, "MOCK_MUTATE_ARTIFACT": "1" if mutate else "0",
               "MOCK_NONREPRODUCIBLE": "1" if nonreproducible else "0"}
        result = subprocess.run(["bash", "--noprofile", "--norc", str(script)], cwd=root,
                                env=env, text=True, capture_output=True, timeout=30)
        events = [row["name"] for row in json.loads(state.read_text())["events"]]
        return result, events, root

    def run_artifacts(self, root, *arguments):
        """readiness를 마친 임시 root에서 공용 배포물 스크립트를 같은 mock 빌드·해시 도구로 실행한다."""
        env = {"PATH": str(root / "bin") + os.pathsep + os.defpath, "HOME": str(root),
               "TMPDIR": str(root / "tmp"), "LC_ALL": "C", "MOCK_STATE": str(root / "state.json")}
        return subprocess.run(["bash", "--noprofile", "--norc", str(root / "Scripts/release-artifacts.sh"), *arguments],
                              cwd=root, env=env, text=True, capture_output=True, timeout=30)

    def event_count(self, root):
        return len(json.loads((root / "state.json").read_text())["events"])

    def test_success_keeps_all_prerequisites_and_both_discoveries_before_package_proof(self):
        result, events, root = self.run_readiness()
        self.assertEqual(0, result.returncode, result.stderr)
        self.assertIn("release readiness 통과", result.stdout)
        package = events.index("package-proof")
        self.assertLess(events.index("zip-discovery"), events.index("extract-tar"))
        self.assertLess(events.index("tar-discovery"), events.index("enum-runtime"))
        self.assertLess(events.index("enum-runtime"), events.index("enum-proof"))
        self.assertLess(events.index("enum-proof"), package)
        self.assertLess(max(index for index, name in enumerate(events) if name in
                            ("build", "collector-build", "metadata", "hash", "plugin-admission")), package)
        self.assertLess(package, events.index("documentation-links"))
        self.assertEqual(2, events.count("documentation"))
        for label in ("zip", "tar"):
            self.assertEqual("0\n", (root / ("build/reports/release-readiness/" + label + "-discovery-exit.log")).read_text())

    def test_normal_discovery_failures_preserve_output_and_package_evidence_but_fail_readiness(self):
        for zip_status, tar_status in ((1, 0), (0, 1), (1, 1)):
            with self.subTest(zip_status=zip_status, tar_status=tar_status):
                result, events, root = self.run_readiness(zip_status, tar_status)
                self.assertEqual(1, result.returncode, result.stderr)
                self.assertEqual(1, events.count("package-proof"))
                self.assertEqual(2, events.count("documentation"))
                self.assertNotIn("release readiness 통과", result.stdout)
                self.assertIn("script-test discovery exits zip=" + str(zip_status) + " tar=" + str(tar_status)
                              + "; packaged resource verification passed.", result.stderr)
                for label, status in (("zip", zip_status), ("tar", tar_status)):
                    self.assertIn(label + " original unittest output", result.stdout)
                    self.assertIn(label + " original unittest diagnostics", result.stderr)
                    self.assertEqual(str(status) + "\n",
                                     (root / ("build/reports/release-readiness/" + label + "-discovery-exit.log")).read_text())
                self.assertEqual({"status": "passed"}, json.loads(
                    (root / "build/reports/release-readiness/packaged-resource-impact/verification.json").read_text()))

    def test_non_test_failure_statuses_abort_immediately_without_package_proof(self):
        for status in (2, 64, 130, 143):
            for label in ("zip", "tar"):
                with self.subTest(status=status, label=label):
                    result, events, root = self.run_readiness(
                        status if label == "zip" else 0, status if label == "tar" else 0)
                    self.assertEqual(status, result.returncode, result.stderr)
                    self.assertNotIn("package-proof", events)
                    if label == "zip":
                        self.assertNotIn("extract-tar", events)
                    self.assertNotIn("release readiness 통과", result.stdout)
                    self.assertEqual(str(status) + "\n",
                                     (root / ("build/reports/release-readiness/" + label + "-discovery-exit.log")).read_text())

    def test_other_failures_remain_immediate_even_after_deferred_test_failure(self):
        for stage in ("build", "metadata", "hash", "collector-installation", "plugin-admission",
                      "extract-tar", "cli-tar", "enum-runtime", "enum-proof", "package-proof", "documentation"):
            with self.subTest(stage=stage):
                result, events, root = self.run_readiness(zip_status=1, fail_stage=stage)
                self.assertEqual(1 if stage == "plugin-admission" else 17, result.returncode, result.stderr)
                self.assertEqual(stage, events[-1])
                self.assertNotIn("release readiness 통과", result.stdout)
                if stage not in ("package-proof", "documentation"):
                    self.assertNotIn("package-proof", events)

    def test_failed_reproducibility_seal_stops_before_any_consumer(self):
        result, events, root = self.run_readiness(nonreproducible=True)
        self.assertEqual(1, result.returncode, result.stderr)
        self.assertEqual(2, events.count("build"))
        self.assertNotIn("collector-installation", events)
        self.assertNotIn("zip-discovery", events)
        self.assertNotIn("package-proof", events)
        self.assertNotIn("release readiness 통과", result.stdout)

    def test_artifact_mutation_after_failed_discovery_cannot_create_package_success(self):
        result, events, root = self.run_readiness(zip_status=1, mutate=True)
        self.assertEqual(33, result.returncode, result.stderr)
        self.assertEqual("package-proof", events[-1])
        self.assertFalse((root / "build/reports/release-readiness/packaged-resource-impact/verification.json").exists())
        self.assertNotIn("release readiness 통과", result.stdout)

    def test_sealed_hashes_are_the_shared_artifact_list_publish_recomputes(self):
        # publish job은 공용 스크립트로 다시 계산한 목록을 verify job이 넘긴 목록과 문자 그대로 비교한다.
        result, events, root = self.run_readiness()
        self.assertEqual(0, result.returncode, result.stderr)
        sealed = (root / "build/reports/release-readiness/second.sha256").read_text()
        hashes = self.run_artifacts(root, "hashes")
        self.assertEqual((0, sealed), (hashes.returncode, hashes.stdout), hashes.stderr)
        paths = self.run_artifacts(root, "paths")
        self.assertEqual(0, paths.returncode, paths.stderr)
        self.assertEqual([line.split("  ", 1)[1] for line in sealed.split("\n")[:-1]], paths.stdout.split("\n")[:-1])

    def test_shared_artifact_script_rejects_unknown_requests_and_missing_artifacts(self):
        result, events, root = self.run_readiness()
        self.assertEqual(0, result.returncode, result.stderr)
        for arguments in ((), ("publish",), ("hashes", "extra")):
            with self.subTest(arguments=arguments):
                before = self.event_count(root)
                rejected = self.run_artifacts(root, *arguments)
                self.assertEqual((64, ""), (rejected.returncode, rejected.stdout), rejected.stderr)
                self.assertEqual(before, self.event_count(root))
        missing = "gradle-plugin/build/libs/kartograph-gradle-plugin-1.2.3.jar"
        (root / missing).unlink()
        result = self.run_artifacts(root, "hashes")
        self.assertEqual(2, result.returncode, result.stderr)
        self.assertIn(missing, result.stderr)


if __name__ == "__main__":
    unittest.main()
