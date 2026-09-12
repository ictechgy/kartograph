"""제한된 수정 실험 실행기의 핵심 동작을 검증하는 로컬 테스트다.

임시 Git 저장소와 가짜 packet/model/native 프로세스를 사용한다. provider,
network, Gradle 또는 실제 checkout의 제품 binary를 호출하지 않는다.
"""

import importlib.util
import json
import os
import signal
from pathlib import Path
import subprocess
import sys
import tempfile
import textwrap
import unittest
from unittest.mock import patch


MODULE_SPEC = importlib.util.spec_from_file_location(
    "evaluate_impact_repair_target",
    Path(__file__).resolve().parents[1] / "evaluate-impact-repair.py",
)
MODULE = importlib.util.module_from_spec(MODULE_SPEC)
sys.modules[MODULE_SPEC.name] = MODULE
assert MODULE_SPEC.loader is not None
MODULE_SPEC.loader.exec_module(MODULE)


class FakePacket:
    def __init__(self):
        self.packets = []

    def request(self, packet_path, *, question, timeout, max_bytes):
        del question, timeout, max_bytes
        packet = packet_path.read_text(encoding="utf-8")
        self.packets.append(packet)
        return MODULE.PacketReply(ok=True, text=packet, metadata={"inspect": {"ok": True}, "preview": {"ok": True}})


class FakeModel:
    def __init__(self, actions):
        self.actions = list(actions)
        self.calls = []

    def request(self, packet, *, model, effort, timeout):
        self.calls.append((packet, model, effort, timeout))
        if not self.actions:
            return MODULE.ModelReply(ok=False, error="fake_exhausted")
        return MODULE.ModelReply(ok=True, text=json.dumps(self.actions.pop(0)), metadata={"model": "fake-model"})


class RepairLauncherTest(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.root = Path(self.temp.name)
        self.repo = self.root / "repo"
        self.repo.mkdir()
        self._git("init", "-q")
        self._git("config", "user.email", "fixture@example.invalid")
        self._git("config", "user.name", "fixture")
        (self.repo / "src/main").mkdir(parents=True)
        (self.repo / "src/test").mkdir(parents=True)
        (self.repo / "classes/p").mkdir(parents=True)
        (self.repo / "src/main/Thing.java").write_text("class Thing { int value = 1; }\n", encoding="utf-8")
        (self.repo / "src/main/config.xml").write_text("<value>one</value>\n", encoding="utf-8")
        (self.repo / "src/test/ThingTest.java").write_text("class ThingTest {}\n", encoding="utf-8")
        (self.repo / "issue.md").write_text("Make Thing's value two.\n", encoding="utf-8")
        (self.repo / "build.gradle.kts").write_text("plugins {}\n", encoding="utf-8")
        (self.repo / "libs").mkdir()
        (self.repo / "libs/project.jar").write_bytes(b"project dependency")
        (self.repo / "classes/p/Thing.class").write_bytes(b"fixture class")
        self._git("add", ".")
        self._git("commit", "-qm", "base")
        self.revision = self._git("rev-parse", "HEAD").strip()
        # 더러운 이웃 파일은 원본 template에만 의도적으로 둔다.
        (self.repo / "gold-fix.patch").write_text("secret gold neighbor\n", encoding="utf-8")
        (self.repo / "hidden").mkdir()
        (self.repo / "hidden/Thing.java").write_text("gold source\n", encoding="utf-8")
        self.binary = self.root / "fake-kartograph"
        self.binary.write_text(
            "#!/usr/bin/env python3\n" + textwrap.dedent(
                """
                import json
                import sys
                if sys.argv[1] == "snapshot":
                    print(json.dumps({
                        "format": "kartograph-query-snapshot", "version": 1,
                        "revision": "REVISION",
                        "graph": {"nodes": [{"usr": "class:p/Thing"}]},
                    }))
                elif sys.argv[1] == "impact":
                    print(json.dumps({
                        "format": "kartograph-impact", "version": 1,
                        "observedAffected": 1, "affected": [{"usr": "class:p/Thing"}],
                        "unresolved": [], "limitations": [],
                        "truncated": {"results": False},
                    }))
                else:
                    raise SystemExit(3)
                """
            ).replace("REVISION", self.revision),
            encoding="utf-8",
        )
        self.binary.chmod(0o700)

    def tearDown(self):
        self.temp.cleanup()

    def _git(self, *arguments):
        return subprocess.run(
            ["git", *arguments], cwd=self.repo, check=True, capture_output=True, text=True,
        ).stdout

    def spec(self, **changes):
        value = {
            "id": "fixture",
            "repository": str(self.repo),
            "revision": self.revision,
            "problemFile": "issue.md",
            "compileCommand": [sys.executable, "-c", "pass"],
            "testCommand": [sys.executable, "-c", "pass"],
            "binary": str(self.binary),
            "classRoots": ["classes"],
            "classpath": [],
        }
        value.update(changes)
        return MODULE.TrialSpec.from_json(value)

    def test_source_arm_isolated_from_dirty_neighbors_and_extracts_allowed_patch(self):
        packet = FakePacket()
        model = FakeModel([
            {"action": "read", "path": "src/main/Thing.java"},
            {"action": "replace", "path": "src/main/Thing.java", "old": "value = 1", "new": "value = 2"},
            {"action": "test"},
            {"action": "finish", "summary": "updated the value"},
        ])
        output = self.root / "source-trial"
        result = MODULE.run_trial(
            self.spec(readPaths=["src/main", "src/test"], editPaths=["src/main/Thing.java"]),
            arm="source", output=output, wall_seconds=30,
            packet_transport=packet, model_transport=model,
        )
        self.assertEqual("finished", result["modelOutcome"])
        self.assertEqual(["src/main/Thing.java"], result["final"]["modifiedPaths"])
        patch = (output / "patch.diff").read_text(encoding="utf-8")
        self.assertIn("value = 2", patch)
        self.assertNotIn("gold-fix", patch)
        workspace = output / "workspace"
        self.assertFalse((workspace / "gold-fix.patch").exists())
        self.assertFalse((workspace / "hidden/Thing.java").exists())
        self.assertFalse(any(str(self.repo) in packet_text for packet_text in packet.packets))
        self.assertFalse(any(str(self.repo) in call[0] for call in model.calls))

    def test_impact_arm_exposes_impact_only_after_fresh_owned_snapshot(self):
        packet = FakePacket()
        model = FakeModel([
            {"action": "impact", "symbol": "class:p/Thing"},
            {"action": "finish", "summary": "reviewed impact"},
        ])
        result = MODULE.run_trial(
            self.spec(), arm="impact", output=self.root / "impact-trial", wall_seconds=30,
            packet_transport=packet, model_transport=model,
        )
        self.assertEqual("finished", result["modelOutcome"])
        self.assertTrue(result["capture"]["ok"])
        self.assertEqual(1, result["capture"]["graphOwners"])
        self.assertEqual(1, result["capture"]["classOwners"])
        tool = result["trace"][0]["tool"]
        self.assertTrue(tool["ok"], tool)
        self.assertEqual("impact", tool["action"])
        self.assertIn("Impact analysis skill contract", packet.packets[0])

    def test_source_arm_rejects_impact_action_through_controller(self):
        packet = FakePacket()
        model = FakeModel([
            {"action": "impact", "symbol": "class:p/Thing"},
            {"action": "finish", "summary": "done"},
        ])
        result = MODULE.run_trial(
            self.spec(), arm="source", output=self.root / "source-impact", wall_seconds=30,
            packet_transport=packet, model_transport=model,
        )
        self.assertEqual("finished", result["modelOutcome"])
        self.assertEqual("impact_unavailable_in_source_arm", result["trace"][0]["tool"]["error"])
        self.assertNotIn("Impact analysis skill contract", packet.packets[0])

    def test_known_visible_baseline_failures_are_context_without_hidden_artifacts(self):
        packet = FakePacket()
        model = FakeModel([{"action": "finish", "summary": "ready"}])
        result = MODULE.run_trial(
            self.spec(knownBaselineFailures=[{"class": "p.ThingTest", "name": "date", "status": "failed"}]),
            arm="source", output=self.root / "baseline-context", wall_seconds=30,
            packet_transport=packet, model_transport=model,
        )
        self.assertEqual("finished", result["modelOutcome"])
        self.assertIn("p.ThingTest", packet.packets[0])
        self.assertNotIn("gold", packet.packets[0].lower())

    def test_budget_and_provider_failures_are_recorded(self):
        packet = FakePacket()
        model = FakeModel([
            {"action": "read", "path": "src/main/Thing.java"},
            {"action": "read", "path": "src/main/Thing.java"},
        ])
        result = MODULE.run_trial(
            self.spec(), arm="source", output=self.root / "budget", wall_seconds=30,
            max_tools=1, packet_transport=packet, model_transport=model,
        )
        self.assertEqual("call_budget_exhausted", result["modelOutcome"])
        self.assertEqual(2, result["model"]["requests"])
        self.assertEqual(1, result["model"]["toolAttempts"])

        class FailingPacket:
            def request(self, packet_path, *, question, timeout, max_bytes):
                del packet_path, question, timeout, max_bytes
                return MODULE.PacketReply(ok=False, error="packet_inspect_failed")

        failed = MODULE.run_trial(
            self.spec(), arm="source", output=self.root / "provider-failure", wall_seconds=30,
            packet_transport=FailingPacket(), model_transport=FakeModel([]),
        )
        self.assertEqual("packet_inspect_failed", failed["modelOutcome"])
        self.assertEqual(1, failed["model"]["requests"])

    def test_setup_failure_keeps_refresh_evidence_and_does_not_call_model(self):
        class NeverModel:
            def request(self, packet, *, model, effort, timeout):
                raise AssertionError("model must not start when setup fails")

        result = MODULE.run_trial(
            self.spec(compileCommand=[sys.executable, "-c", "raise SystemExit(7)" ]),
            arm="source", output=self.root / "setup-failure", wall_seconds=30,
            packet_transport=FakePacket(), model_transport=NeverModel(),
        )
        self.assertEqual("preparation_failed", result["modelOutcome"])
        self.assertEqual("compile_failed", result["capture"]["refresh"]["error"])
        self.assertEqual(0, result.get("model", {}).get("requests", 0))

    def test_large_inventory_keeps_prompt_bounded_and_paths_discoverable(self):
        prepared = MODULE.prepare_trial(self.spec(), "source", self.root / "large-inventory")
        prepared.read_paths = [f"module/src/main/p/Thing{i:04d}.java" for i in range(4000)]
        prepared.edit_paths = list(prepared.read_paths)
        prepared.baseline_source_hashes = dict.fromkeys(prepared.read_paths, "digest")
        prompt = MODULE._base_prompt(prepared)
        self.assertLess(len(prompt), 12000)
        self.assertIn('"fileCount": 4000', prompt)
        self.assertIn('"editableCount": 4000', prompt)
        self.assertIn('"directory": "module"', prompt)
        self.assertIn("list", prompt)

    def test_missing_request_cost_cannot_be_reported_as_a_complete_total(self):
        self.assertIsNone(MODULE._aggregate_cost([{"cost": 1.25}, {"cost": None}]))
        self.assertEqual(1.5, MODULE._aggregate_cost([{"cost": 1.25}, {"cost": .25}]))

    def test_setup_compile_uses_setup_budget_and_model_tools_keep_their_limit(self):
        prepared = MODULE.prepare_trial(self.spec(), "source", self.root / "setup-budget")
        with patch.object(MODULE, "RepairSession") as session_type:
            MODULE._make_session(prepared, max_tools=1, wall_seconds=900, setup=True)
            self.assertEqual(900, session_type.call_args.kwargs["command_timeout"])
            MODULE._make_session(prepared, max_tools=12, wall_seconds=900, setup=False)
            self.assertEqual(180, session_type.call_args.kwargs["command_timeout"])

    @unittest.skipUnless(os.name == "posix", "requires process groups")
    def test_provider_timeout_terminates_child_after_leader_exit(self):
        marker = self.root / "child.json"
        child = ("import json,os,signal,time; from pathlib import Path; "
                 "signal.signal(signal.SIGTERM,signal.SIG_IGN); "
                 "Path('child.json').write_text(json.dumps({'pid':os.getpid(),'group':os.getpgrp()})); time.sleep(30)")
        parent = ("import subprocess,sys,time; from pathlib import Path; "
                  "subprocess.Popen([sys.executable,'-c'," + repr(child) + "]); "
                  "\nwhile not Path('child.json').exists(): time.sleep(.01)\n")
        try:
            result = MODULE._run_process_bounded(
                [sys.executable, "-c", parent], input_text="", cwd=self.root, env=None, timeout=.5,
            )
            self.assertTrue(result[4])
            process = json.loads(marker.read_text())
            state = subprocess.run(["ps", "-o", "stat=", "-p", str(process["pid"])],
                                   capture_output=True, text=True, timeout=2).stdout.strip()
            self.assertTrue(not state or state.startswith("Z"), "provider child survived timeout")
        finally:
            if marker.exists():
                try:
                    os.killpg(json.loads(marker.read_text())["group"], signal.SIGKILL)
                except ProcessLookupError:
                    pass

    def test_compact_v2_owner_validation_preserves_hash_in_class_usr(self):
        classes = self.root / "compact-classes/p"
        classes.mkdir(parents=True)
        (classes / "Spec$Thing#1439.class").write_bytes(b"class")
        snapshot = {
            "format": "kartograph-query-snapshot", "version": 2,
            "graph": {"stringTable": ["class:Spec$Thing#1439"], "nodes": [[0]]},
        }
        evidence = MODULE.validate_snapshot_ownership(snapshot, [classes])
        self.assertTrue(evidence["ok"])
        self.assertEqual(1, evidence["graphOwners"])

    def test_project_classpath_is_relocated_to_trial_workspace(self):
        spec = self.spec(classpath=["libs/project.jar"])
        workspace = self.root / "workspace"
        workspace.mkdir()
        relocated = MODULE._snapshot_arguments(spec, workspace)
        self.assertIn(str((workspace / "libs/project.jar").resolve()), relocated)
        self.assertNotIn(str(self.repo / "libs/project.jar"), relocated)

    def test_invalid_edit_paths_are_rejected_before_model(self):
        with self.assertRaises(MODULE.SpecError):
            self.spec(editPaths=["src/test/ThingTest.java"])

    def test_existing_output_is_never_overwritten(self):
        output = self.root / "already-used"
        output.mkdir()
        marker = output / "marker.txt"
        marker.write_text("keep", encoding="utf-8")
        with self.assertRaises(MODULE.SpecError):
            MODULE.run_trial(
                self.spec(), arm="source", output=output, wall_seconds=30,
                packet_transport=FakePacket(), model_transport=FakeModel([]),
            )
        self.assertEqual("keep", marker.read_text(encoding="utf-8"))

    def test_explicit_production_resource_edit_path_remains_allowed(self):
        packet = FakePacket()
        model = FakeModel([
            {"action": "replace", "path": "src/main/config.xml", "old": "one", "new": "two"},
            {"action": "finish", "summary": "updated resource"},
        ])
        result = MODULE.run_trial(
            self.spec(readPaths=["src/main/config.xml"], editPaths=["src/main/config.xml"]),
            arm="source", output=self.root / "resource-trial", wall_seconds=30,
            packet_transport=packet, model_transport=model,
        )
        self.assertEqual("finished", result["modelOutcome"])
        self.assertEqual(["src/main/config.xml"], result["final"]["modifiedPaths"])
        self.assertIn("<value>two</value>", (self.root / "resource-trial/patch.diff").read_text())

    def test_native_build_patch_is_applied_for_setup_but_excluded_from_result_patch(self):
        native = self.root / "native-build.patch"
        native.write_text(
            "diff --git a/build.gradle.kts b/build.gradle.kts\n"
            "--- a/build.gradle.kts\n+++ b/build.gradle.kts\n"
            "@@ -1 +1 @@\n-plugins {}\n+plugins { id(\"fixture\") }\n",
            encoding="utf-8",
        )
        result = MODULE.run_trial(
            self.spec(nativeBuildPatch=str(native)), arm="source",
            output=self.root / "native-trial", wall_seconds=30,
            packet_transport=FakePacket(), model_transport=FakeModel([
                {"action": "finish", "summary": "no source edit"},
            ]),
        )
        self.assertEqual("finished", result["modelOutcome"])
        self.assertEqual(["build.gradle.kts"], result["preparation"]["nativeBuildPaths"])
        self.assertEqual("", (self.root / "native-trial/patch.diff").read_text(encoding="utf-8"))
        self.assertIn("id(\"fixture\")", (self.root / "native-trial/workspace/build.gradle.kts").read_text())

    def test_inline_native_build_patch_is_supported_without_staging_it_in_workspace(self):
        patch = (
            "diff --git a/build.gradle.kts b/build.gradle.kts\n"
            "--- a/build.gradle.kts\n+++ b/build.gradle.kts\n"
            "@@ -1 +1 @@\n-plugins {}\n+plugins { id(\"inline\") }\n"
        )
        result = MODULE.run_trial(
            self.spec(nativeBuildPatch={"patch": patch}), arm="source",
            output=self.root / "inline-native-trial", wall_seconds=30,
            packet_transport=FakePacket(), model_transport=FakeModel([
                {"action": "finish", "summary": "ready"},
            ]),
        )
        self.assertEqual("finished", result["modelOutcome"])
        self.assertEqual(["build.gradle.kts"], result["preparation"]["nativeBuildPaths"])
        self.assertFalse((self.root / "inline-native-trial/workspace/.native-build.patch").exists())


if __name__ == "__main__":
    unittest.main()
