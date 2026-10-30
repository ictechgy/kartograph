"""설치 입력 누락/혼합이 공개 소비 검증을 통과하거나 과거 성공을 남기지 않음을 검증한다."""

import argparse
import json
import hashlib
import os
from pathlib import Path
import subprocess
import tempfile
import unittest
from unittest import mock
import zipfile

import public_authored_api as consumer


class PublicAuthoredApiTests(unittest.TestCase):
    def setUp(self):
        self.temporary = tempfile.TemporaryDirectory()
        self.addCleanup(self.temporary.cleanup)
        self.root = Path(self.temporary.name)
        self.dist = self.root / "installed"
        self.lib = self.dist / "lib"
        self.lib.mkdir(parents=True)
        (self.dist / "VERSION").write_text("0.20.1\n")
        self.names = ("analysis-0.20.1.jar", "core-0.20.1.jar", "kotlin-stdlib-2.4.20.jar")
        for name, entry in zip(self.names, ("dev/kartograph/analysis/AuthoredCallUniverse.class",
                                           "dev/kartograph/core/CodeGraph.class", "kotlin/Unit.class")):
            with zipfile.ZipFile(self.lib / name, "w") as archive:
                archive.writestr(entry, b"fixture")

    def selected(self):
        return consumer.distribution_jars(str(self.dist), "0.20.1", "2.4.20")

    def test_only_three_exact_installed_jars_are_selected(self):
        (self.lib / "cli-0.20.1.jar").write_bytes(b"not a target input")
        self.assertEqual([path.name for path in self.selected()], list(self.names))

    def split_runtime_inputs(self):
        (self.lib / self.names[2]).rename(self.lib / "kotlin-stdlib-2.4.21.jar")
        compiler = self.root / "compiler"
        compiler.mkdir()
        paths = [compiler / "kotlin-compiler-embeddable-2.4.20.jar", compiler / "kotlin-stdlib-2.4.20.jar"]
        for path in paths:
            path.write_bytes(b"compiler fixture; never executed")
        home = self.root / "jdk"
        for name in ("release", "lib/modules", "bin/java"):
            path = home / name
            path.parent.mkdir(parents=True, exist_ok=True)
            path.write_bytes(b"native JDK fixture; never executed")
        args = argparse.Namespace(distribution=str(self.dist), version="0.20.1", kotlin_version="2.4.20",
                                  distribution_stdlib_version="2.4.21", compiler_classpath=os.pathsep.join(map(str, paths)),
                                  report=str(self.root / "split-report"))
        return args, paths, home

    def test_compiler_and_packaged_stdlib_versions_are_independently_pinned(self):
        args, paths, home = self.split_runtime_inputs()
        observed = {}

        def execute(name, command, cwd, env, report, inputs, public_inputs, commands, path_tokens, expected=0):
            observed[name] = (command, expected)
            stdout = consumer.EXPECTED if name == "consumer-run" else ""
            stderr = "info: kotlinc-jvm 2.4.20\n" if name == "compiler-version" else ""
            if name == "internal-access-denied":
                stderr = "Cannot access 'CompilerSourceFacts': it is internal"
            return subprocess.CompletedProcess(command, expected, stdout, stderr)

        with mock.patch.dict(os.environ, {"JAVA_HOME": str(home)}), \
             mock.patch.object(consumer, "execute", side_effect=execute):
            value = consumer.verify(args)
        self.assertEqual(set(observed), {"compiler-version", "consumer-compile", "consumer-run", "internal-access-denied"})
        compiler = observed["compiler-version"][0]
        self.assertEqual(compiler[compiler.index("-cp") + 1], os.pathsep.join(str(path.resolve()) for path in paths))
        compile_command = observed["consumer-compile"][0]
        target_classpath = compile_command[compile_command.index("-classpath") + 1].split(os.pathsep)
        self.assertEqual(target_classpath, [str((self.lib / name).resolve()) for name in
                                          (self.names[0], self.names[1], "kotlin-stdlib-2.4.21.jar")])
        self.assertEqual(observed["internal-access-denied"][1], 1)
        self.assertEqual(value["kotlinVersion"], "2.4.20")
        self.assertEqual(value["distributionStdlibVersion"], "2.4.21")
        receipt = json.loads((Path(args.report) / "public-report.json").read_text())
        self.assertEqual(receipt["kotlinVersion"], "2.4.20")
        self.assertEqual(receipt["distributionStdlibVersion"], "2.4.21")
        self.assertIn("distribution/lib/kotlin-stdlib-2.4.21.jar", receipt["inputs"])
        self.assertTrue(any(name.endswith("/kotlin-stdlib-2.4.20.jar") for name in receipt["inputs"]))

    def test_wrong_distribution_stdlib_expectation_fails_before_compiler(self):
        args, _, home = self.split_runtime_inputs()
        args.distribution_stdlib_version = "2.4.22"
        with mock.patch.dict(os.environ, {"JAVA_HOME": str(home)}), mock.patch.object(consumer, "execute") as run:
            with self.assertRaisesRegex(AssertionError, "kotlin-stdlib-2.4.22.jar"):
                consumer.verify(args)
            run.assert_not_called()

    def test_packaged_stdlib_cannot_replace_the_pinned_compiler_runtime(self):
        args, paths, home = self.split_runtime_inputs()
        different = paths[1].with_name("kotlin-stdlib-2.4.21.jar")
        different.write_bytes(paths[1].read_bytes())
        for selection in ([paths[0], different], [paths[0]], [*paths, different]):
            with self.subTest(selection=[path.name for path in selection]):
                args.compiler_classpath = os.pathsep.join(map(str, selection))
                with mock.patch.dict(os.environ, {"JAVA_HOME": str(home)}), mock.patch.object(consumer, "execute") as run:
                    with self.assertRaisesRegex(AssertionError, "kotlin-stdlib-2.4.20.jar"):
                        consumer.verify(args)
                    run.assert_not_called()

    def test_missing_distribution_fails_and_invalidates_old_success_before_compiler(self):
        report = self.root / "report"
        report.mkdir()
        receipt = report / "verification.json"
        receipt.write_text(json.dumps({"status": "PASS"}))
        args = argparse.Namespace(distribution="", version="0.20.1", kotlin_version="2.4.20",
                                  distribution_stdlib_version="2.4.20",
                                  compiler_classpath="unused.jar", report=str(report))
        with mock.patch.object(consumer.subprocess, "run") as run:
            with self.assertRaisesRegex(AssertionError, "explicit built distribution"):
                consumer.verify(args)
            run.assert_not_called()
        self.assertFalse(receipt.exists())

    def test_missing_wrong_duplicate_and_external_jars_are_rejected(self):
        original = self.lib / self.names[0]
        alternate = self.lib / "analysis-0.20.0.jar"
        original.rename(alternate)
        with self.assertRaisesRegex(AssertionError, "exactly one analysis"):
            self.selected()
        alternate.rename(original)
        alternate.write_bytes(original.read_bytes())
        with self.assertRaisesRegex(AssertionError, "exactly one analysis"):
            self.selected()
        alternate.unlink()
        outside = self.root / original.name
        original.rename(outside)
        with self.assertRaisesRegex(AssertionError, "exactly one analysis"):
            self.selected()
        original.symlink_to(outside)
        with self.assertRaisesRegex(AssertionError, "regular file inside"):
            self.selected()

    def test_wrong_distribution_version_and_missing_api_are_rejected(self):
        (self.dist / "VERSION").write_text("other-version")
        with self.assertRaisesRegex(AssertionError, "VERSION"):
            self.selected()
        (self.dist / "VERSION").write_text("0.20.1")
        with zipfile.ZipFile(self.lib / self.names[0], "w") as archive:
            archive.writestr("old/Unrelated.class", b"fixture")
        with self.assertRaisesRegex(AssertionError, "required public API"):
            self.selected()

    def test_timeout_launch_failure_and_nonzero_keep_planned_and_partial_evidence(self):
        for outcome, error in (("TIMEOUT", subprocess.TimeoutExpired), ("LAUNCH_FAILURE", OSError),
                               ("UNEXPECTED_EXIT", AssertionError)):
            with self.subTest(outcome=outcome):
                report = self.root / outcome
                report.mkdir()
                (report / "verification.json").write_text('{"status":"PASS"}')
                command = ["/private/machine/jdk/bin/java", "-cp", "/private/machine/analysis.jar", "Consumer"]
                inputs = {"/private/machine/analysis.jar": "a" * 64}
                public_inputs = {"distribution/lib/analysis.jar": "a" * 64}
                args = argparse.Namespace(report=str(report))

                def launch(actual, **kwargs):
                    planned = json.loads((report / "attempt.json").read_text())
                    self.assertEqual(planned["commands"][0]["state"], "PLANNED")
                    self.assertEqual(planned["commands"][0]["command"], command)
                    kwargs["stdout"].write("partial output\n")
                    kwargs["stderr"].write("partial diagnostic\n")
                    if outcome == "TIMEOUT":
                        raise subprocess.TimeoutExpired(actual, kwargs["timeout"])
                    if outcome == "LAUNCH_FAILURE":
                        raise FileNotFoundError("test-only missing executable")
                    return subprocess.CompletedProcess(actual, 7)

                def verification(_, destination):
                    return consumer.execute("fixture", command, self.root, {}, destination, inputs,
                                            public_inputs, [], {command[0]: "@native-jdk/bin/java",
                                                                command[2]: "@distribution/lib/analysis.jar"}, timeout=1)

                with mock.patch.object(consumer.subprocess, "run", side_effect=launch), \
                     mock.patch.object(consumer, "verify_inputs", side_effect=verification):
                    with self.assertRaises(error):
                        consumer.verify(args)
                self.assertFalse((report / "verification.json").exists())
                local = json.loads((report / "attempt.json").read_text())
                attempt = local["commands"][0]
                self.assertEqual(attempt["state"], outcome)
                self.assertEqual(attempt["exit"], 7 if outcome == "UNEXPECTED_EXIT" else None)
                self.assertEqual(attempt["stdoutSha256"], hashlib.sha256(b"partial output\n").hexdigest())
                self.assertEqual((report / "fixture.stderr").read_text(), "partial diagnostic\n")
                public_text = (report / "public-report.json").read_text()
                self.assertNotIn("/private/", public_text)
                self.assertNotIn('"command":', public_text)
                public = json.loads(public_text)
                self.assertEqual(public["status"], "FAILED")
                self.assertEqual(public["commands"][0]["state"], outcome)
                self.assertEqual(public["inputs"], public_inputs)
                self.assertEqual(public["commands"][0]["normalizedArgv"],
                                 ["@native-jdk/bin/java", "-cp", "@distribution/lib/analysis.jar", "Consumer"])
                self.assertEqual(public["rawAttemptSha256"], consumer.sha256(report / "attempt.json"))
                self.assertEqual(public["commands"][0]["rawCommandSha256"],
                                 hashlib.sha256(json.dumps(command, separators=(",", ":")).encode()).hexdigest())

    def test_normalized_argv_retains_exact_flags_and_order_and_rejects_unknown_paths(self):
        paths = {"/jdk/bin/java": "@native-jdk/bin/java", "/a.jar": "@compiler-runtime/0/a.jar",
                 "/b.jar": "@compiler-runtime/1/b.jar", "/source/Consumer.kt": "@consumer-source/Consumer.kt"}
        command = ["/jdk/bin/java", "-cp", "/b.jar:/a.jar", "Compiler", "-no-stdlib", "-jvm-target", "17",
                   "-d", str(self.root / "classes"), "/source/Consumer.kt"]
        expected = ["@native-jdk/bin/java", "-cp", "@compiler-runtime/1/b.jar:@compiler-runtime/0/a.jar", "Compiler",
                    "-no-stdlib", "-jvm-target", "17", "-d", "@work/classes", "@consumer-source/Consumer.kt"]
        self.assertEqual(consumer.normalize_command(command, paths, self.root), expected)
        for position, value in ((0, "/unknown/java"), (2, "/a.jar:/unknown.jar"), (9, "/unknown/Source.kt")):
            invalid = command.copy()
            invalid[position] = value
            with self.assertRaisesRegex(ValueError, "unknown path"):
                consumer.normalize_command(invalid, paths, self.root)

    def test_failure_after_writing_local_receipt_cannot_leave_success(self):
        report = self.root / "late-failure"
        args = argparse.Namespace(report=str(report))

        def late_failure(_, destination):
            (destination / "verification.json").write_text('{"status":"PASS"}')
            raise OSError("test-only final report failure")

        with mock.patch.object(consumer, "verify_inputs", side_effect=late_failure):
            with self.assertRaises(OSError):
                consumer.verify(args)
        self.assertFalse((report / "verification.json").exists())
        self.assertEqual(json.loads((report / "public-report.json").read_text())["status"], "FAILED")


if __name__ == "__main__":
    unittest.main()
