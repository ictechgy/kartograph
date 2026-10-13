"""고정한 Kotlin runtime 선택과 증거 버전을 JVM 없이 검증한다."""

import contextlib
import hashlib
import io
import json
import os
from pathlib import Path
import sys
import tempfile
import unittest
from unittest import mock

import run as collectors


class KotlinRuntimeTests(unittest.TestCase):
    def setUp(self):
        self.temporary = tempfile.TemporaryDirectory()
        self.addCleanup(self.temporary.cleanup)
        self.root = Path(self.temporary.name)
        self.version = "2.4.20"
        self.compiler = self.artifact("kotlin-compiler-embeddable-2.4.20.jar")
        self.stdlib = self.artifact("kotlin-stdlib-2.4.20.jar")
        self.collector = self.artifact("collector.jar")
        self.classpath = os.pathsep.join(map(str, (self.compiler, self.stdlib)))

    def artifact(self, name):
        path = self.root / name
        path.write_bytes(name.encode())
        return path

    def test_reviewed_runtime_reaches_compiler_and_records_exact_hashes(self):
        output = io.StringIO()
        with contextlib.redirect_stdout(output), mock.patch.object(
            collectors, "run", side_effect=RuntimeError("compiler reached")
        ) as compile:
            with self.assertRaisesRegex(RuntimeError, "compiler reached"):
                collectors.kotlin_test(Path("/unused/bin/java"), self.collector,
                                       self.classpath, self.root, kotlin_version=self.version)
        command = compile.call_args.args[0]
        self.assertEqual(command[command.index("-classpath") + 1], self.stdlib)
        provenance = json.loads(output.getvalue())
        self.assertEqual(provenance["compilerVersion"], self.version)
        for key, path in (("compiler", self.compiler), ("stdlib", self.stdlib), ("collector", self.collector)):
            self.assertEqual(provenance[key], {"artifact": path.name,
                                             "sha256": hashlib.sha256(path.read_bytes()).hexdigest()})

    def test_missing_wrong_duplicate_and_prefix_only_artifacts_are_rejected(self):
        for artifact, correct, other in (("kotlin-stdlib", self.stdlib, self.compiler),
                                         ("kotlin-compiler-embeddable", self.compiler, self.stdlib)):
            wrong = self.artifact(f"{artifact}-2.4.10.jar")
            prefix = self.artifact(f"{artifact}-2.4.200.jar")
            sources = self.artifact(f"{artifact}-2.4.20-sources.jar")
            absent = self.root / "missing" / correct.name
            for invalid in ([], [wrong], [prefix], [sources], [correct, correct], [correct, wrong], [absent]):
                with self.subTest(artifact=artifact, invalid=[path.name for path in invalid]):
                    classpath = os.pathsep.join(map(str, [other, *invalid]))
                    with mock.patch.object(collectors, "run") as compile:
                        with self.assertRaisesRegex(AssertionError, f"exactly one {artifact}-2.4.20.jar"):
                            collectors.kotlin_test(Path("/unused/bin/java"), self.collector,
                                                   classpath, self.root, kotlin_version=self.version)
                        compile.assert_not_called()

    def test_kotlin_evidence_requires_exact_version_and_collector_hash(self):
        source = self.artifact("Source.kt")
        evidence = self.root / "evidence.tsv"
        for version, fingerprint, valid in ((self.version, collectors.content_fingerprint(self.collector), True),
                                             ("2.4.10", collectors.content_fingerprint(self.collector), False),
                                             ("2.4.200", collectors.content_fingerprint(self.collector), False),
                                             (self.version, "0" * 64, False)):
            with self.subTest(version=version, fingerprint=fingerprint):
                evidence.write_text("format\tkartograph-compiler-evidence\t1\ncollector\tkotlin-constants\n"
                                    f"compiler\t{version}\nartifact\t{fingerprint}\nunmapped\t0\n"
                                    f"source\t{collectors.encoded(source.name)}\t{collectors.sha256(source)}\n")
                if valid:
                    collectors.assert_evidence(evidence, "kotlin-constants", source, set(),
                                               self.collector, self.version, exact_compiler=True)
                else:
                    with self.assertRaises(AssertionError):
                        collectors.assert_evidence(evidence, "kotlin-constants", source, set(),
                                                   self.collector, self.version, exact_compiler=True)

    def test_cli_passes_explicit_build_version_to_kotlin_phase(self):
        arguments = ["run.py", "--collector", str(self.collector), "--kotlin-classpath", self.classpath,
                     "--kotlin-version", self.version, "--dagger-classpath", "unused.jar"]
        with mock.patch.object(sys, "argv", arguments), mock.patch.object(collectors, "java_home", return_value=self.root), \
             mock.patch.object(collectors, "run"), mock.patch.object(collectors, "java_test"), \
             mock.patch.object(collectors, "kotlin_test") as kotlin, mock.patch.object(collectors, "dagger_test"), \
             mock.patch.object(collectors, "processor_outputs_test"), contextlib.redirect_stdout(io.StringIO()):
            collectors.main()
        self.assertEqual(kotlin.call_args.kwargs, {"kotlin_version": self.version})


if __name__ == "__main__":
    unittest.main()
