"""실제 release workflow shell을 실행해 외부 쓰기 전 실패 경계를 확인한다."""

import json
import os
from pathlib import Path
import subprocess
import sys
import tempfile
import unittest

WORKFLOW = Path(__file__).resolve().parents[2] / ".github/workflows/release.yml"
SOURCE = "1" * 40
OTHER = "2" * 40
TAG = "v1.2.3"
ADMIT = "Validate manual source input"
RESOLVE = "Resolve release source"
ENSURE = "Ensure the exact release tag after verification"
CREATE = "Create GitHub release"
PUBLISH = "Publish Gradle plugin"
BLOCKS = (ADMIT, RESOLVE, ENSURE, CREATE, PUBLISH)

# 실제 네트워크/게시 명령 대신 인자와 상태 전이를 기록하며, 모르는 명령은 실패한다.
MOCK = r'''
import json
import os
from pathlib import Path
import sys

state_path = Path(os.environ["MOCK_STATE"])
state = json.loads(state_path.read_text())
command = Path(sys.argv[0]).name
args = sys.argv[1:]
with Path(os.environ["MOCK_LOG"]).open("a") as log:
    log.write(json.dumps([command, *args]) + "\n")

def save():
    state_path.write_text(json.dumps(state))

def fail():
    sys.exit(1)

if command == "git":
    if args == ["rev-parse", "HEAD"]:
        print(state["head"])
    elif args == ["rev-parse", "FETCH_HEAD^{commit}"]:
        print(state["fetched"])
    elif args[:1] == ["ls-remote"]:
        ref = args[-1]
        if state.get("lookup_failure") == ref:
            fail()
        if ref == "refs/heads/main":
            if state["main"] is None:
                sys.exit(2)
            print(state["main"] + "\t" + ref)
        elif ref == "refs/tags/v1.2.3":
            if state["tag"] is not None:
                print(state.get("tag_object", state["tag"]) + "\t" + ref)
        else:
            fail()
    elif args == ["fetch", "--no-tags", "--depth=1", "origin", "refs/tags/v1.2.3"]:
        if state.get("fetch_failure") or state["tag"] is None:
            fail()
        state["fetched"] = state["tag"]
        save()
    else:
        fail()
elif command == "gh":
    if args[:4] == ["api", "--method", "POST", "repos/example/project/git/refs"]:
        if state.get("create_tag_failure") or state["tag"] is not None:
            fail()
        if args[4:] != ["-f", "ref=refs/tags/v1.2.3", "-f", "sha=" + state["head"]]:
            fail()
        state["tag"] = state["head"]
        save()
    elif args[:3] == ["release", "create", "v1.2.3"]:
        if state.get("release_exists") or state.get("create_release_failure") or "--verify-tag" not in args:
            fail()
        state["release_exists"] = True
        save()
    elif args[:3] == ["release", "upload", "v1.2.3"]:
        if state.get("upload_failure") or "--clobber" in args:
            fail()
    else:
        fail()
elif command == "gradlew" and args == ["--no-daemon", ":gradle-plugin:publishPlugins"]:
    pass
else:
    fail()
'''


def shell_block(name):
    lines = WORKFLOW.read_text(encoding="utf-8").splitlines()
    start = lines.index("      - name: " + name)
    run = next(index for index in range(start + 1, len(lines)) if lines[index] == "        run: |")
    end = run + 1
    while end < len(lines) and (not lines[end] or lines[end].startswith("          ")):
        end += 1
    return "\n".join(line[10:] for line in lines[run + 1:end]) + "\n"


class ReleaseWorkflowTest(unittest.TestCase):
    def setUp(self):
        temporary = tempfile.TemporaryDirectory(prefix="kartograph-release-test-")
        self.addCleanup(temporary.cleanup)
        self.root = Path(temporary.name)
        self.bin = self.root / "bin"
        self.bin.mkdir()
        for path in (self.bin / "git", self.bin / "gh", self.root / "gradlew"):
            path.write_text("#!" + sys.executable + "\n" + MOCK, encoding="utf-8")
            path.chmod(0o755)
        self.reset()

    def reset(self, event="workflow_dispatch", **changes):
        self.state_path = self.root / "state.json"
        self.log_path = self.root / "calls.jsonl"
        self.state_path.write_text(json.dumps({"head": SOURCE, "main": SOURCE, "tag": None}))
        self.log_path.write_text("")
        (self.root / "VERSION").write_text("1.2.3\n")
        (self.root / "github-env").write_text("")
        self.env = {
            "PATH": str(self.bin) + os.pathsep + os.defpath,
            "HOME": str(self.root),
            "LC_ALL": "C",
            "MOCK_STATE": str(self.state_path),
            "MOCK_LOG": str(self.log_path),
            "GITHUB_ENV": str(self.root / "github-env"),
            "GITHUB_EVENT_NAME": event,
            "GITHUB_REF": "refs/heads/main" if event == "workflow_dispatch" else "refs/tags/" + TAG,
            "GITHUB_REF_NAME": "main" if event == "workflow_dispatch" else TAG,
            "GITHUB_SHA": SOURCE,
            "GITHUB_REPOSITORY": "example/project",
            "REQUESTED_SHA": SOURCE if event == "workflow_dispatch" else "",
            "REQUESTED_TAG": TAG if event == "workflow_dispatch" else "",
        }
        self.env.update(changes)

    def configure(self, **changes):
        state = json.loads(self.state_path.read_text())
        state.update(changes)
        self.state_path.write_text(json.dumps(state))

    def calls(self):
        return [json.loads(line) for line in self.log_path.read_text().splitlines()]

    def run_steps(self, *names):
        result = None
        for name in names:
            result = subprocess.run(
                ["bash", "--noprofile", "--norc", "-e", "-o", "pipefail", "-c", shell_block(name)],
                cwd=self.root, env=self.env, text=True, capture_output=True, timeout=20,
            )
            if result.returncode:
                return result
            for line in (self.root / "github-env").read_text().splitlines():
                key, value = line.split("=", 1)
                self.env[key] = value
        return result

    def pipeline(self):
        names = BLOCKS if self.env["GITHUB_EVENT_NAME"] == "workflow_dispatch" else BLOCKS[1:]
        return self.run_steps(*names)

    def assert_no_publication(self):
        self.assertFalse([call for call in self.calls() if call[:2] == ["gh", "release"] or call[0] == "gradlew"])

    def assert_no_mutation(self):
        self.assertFalse([call for call in self.calls() if call[0] in ("gh", "gradlew")])

    def test_shell_syntax_and_readiness_precedes_tag_creation(self):
        for name in BLOCKS:
            with self.subTest(name=name):
                result = subprocess.run(["bash", "-n"], input=shell_block(name), text=True, capture_output=True, timeout=20)
                self.assertEqual(0, result.returncode, result.stderr)
        workflow = WORKFLOW.read_text(encoding="utf-8")
        self.assertLess(workflow.index("run: Scripts/verify-release-readiness.sh"), workflow.index("      - name: " + ENSURE))

    def test_manual_sha_and_ref_admission_fails_before_external_commands(self):
        for changes in (
            {"REQUESTED_SHA": ""}, {"REQUESTED_SHA": "1" * 39},
            {"REQUESTED_SHA": "A" * 40}, {"REQUESTED_SHA": OTHER},
            {"REQUESTED_SHA": SOURCE + "\nINJECTED=yes"},
            {"GITHUB_REF": "refs/heads/feature"}, {"GITHUB_REF": "refs/tags/" + TAG},
        ):
            with self.subTest(changes=changes):
                self.reset(**changes)
                self.assertNotEqual(0, self.pipeline().returncode)
                self.assertEqual([], self.calls())

    def test_wrong_tag_version_or_checkout_blocks_publication(self):
        for changes, state, version in (
            ({"REQUESTED_TAG": "1.2.3"}, {}, "1.2.3"),
            ({"REQUESTED_TAG": TAG + "\nINJECTED=yes"}, {}, "1.2.3"),
            ({}, {}, "1.2.4"), ({}, {"head": OTHER}, "1.2.3"),
            ({}, {"main": OTHER}, "1.2.3"),
        ):
            with self.subTest(changes=changes, state=state, version=version):
                self.reset(**changes)
                self.configure(**state)
                (self.root / "VERSION").write_text(version + "\n")
                self.assertNotEqual(0, self.pipeline().returncode)
                self.assert_no_mutation()

    def test_wrong_push_ref_or_event_fails_closed(self):
        for event, ref in (("push", "refs/heads/main"), ("schedule", "refs/tags/" + TAG)):
            with self.subTest(event=event, ref=ref):
                self.reset(event=event, GITHUB_REF=ref)
                self.assertNotEqual(0, self.pipeline().returncode)
                self.assert_no_mutation()

    def test_absent_manual_tag_is_created_once_before_publication(self):
        result = self.pipeline()
        self.assertEqual(0, result.returncode, result.stderr)
        writes = [call for call in self.calls() if call[0] in ("gh", "gradlew")]
        self.assertEqual(["gh", "api", "--method", "POST", "repos/example/project/git/refs",
                          "-f", "ref=refs/tags/" + TAG, "-f", "sha=" + SOURCE], writes[0])
        self.assertEqual(["gh", "release", "create", TAG, "--verify-tag", "--generate-notes",
                          "--title", "kartograph " + TAG], writes[1])
        self.assertEqual(["gh", "release", "upload", TAG,
                          "cli/build/distributions/kartograph-1.2.3.zip",
                          "cli/build/distributions/kartograph-1.2.3.tar",
                          "gradle-plugin/build/libs/kartograph-gradle-plugin-1.2.3.jar",
                          "compiler-collectors/build/distributions/kartograph-compiler-collectors-1.2.3.zip",
                          "build/release/kartograph-1.2.3.cdx.json",
                          "build/release/kartograph-gradle-plugin-1.2.3.cdx.json",
                          "build/release/SHA256SUMS"], writes[2])
        self.assertEqual(["gradlew", "--no-daemon", ":gradle-plugin:publishPlugins"], writes[3])
        self.assertEqual(4, len(writes))

    def test_existing_lightweight_or_annotated_tag_is_never_recreated(self):
        for tag_object in (SOURCE, OTHER):
            with self.subTest(tag_object=tag_object):
                self.reset()
                self.configure(tag=SOURCE, tag_object=tag_object)
                result = self.pipeline()
                self.assertEqual(0, result.returncode, result.stderr)
                self.assertFalse([call for call in self.calls() if call[:2] == ["gh", "api"]])

    def test_existing_different_tag_and_missing_pushed_tag_are_not_changed(self):
        for event, tag in (("workflow_dispatch", OTHER), ("push", None)):
            with self.subTest(event=event, tag=tag):
                self.reset(event=event)
                self.configure(tag=tag)
                self.assertNotEqual(0, self.pipeline().returncode)
                self.assert_no_mutation()

    def test_historical_tag_push_does_not_require_current_main(self):
        self.reset(event="push")
        self.configure(main=OTHER, tag=SOURCE)
        result = self.pipeline()
        self.assertEqual(0, result.returncode, result.stderr)
        self.assertFalse([call for call in self.calls() if call[:2] == ["gh", "api"] or call[-1] == "refs/heads/main"])

    def test_uncertain_ref_reads_never_authorize_writes(self):
        for state in (
            {"lookup_failure": "refs/heads/main"}, {"main": None},
            {"lookup_failure": "refs/tags/" + TAG},
            {"tag": SOURCE, "fetch_failure": True},
        ):
            with self.subTest(state=state):
                self.reset()
                self.configure(**state)
                self.assertNotEqual(0, self.pipeline().returncode)
                self.assert_no_mutation()

    def test_tag_creation_failure_blocks_all_publication(self):
        self.configure(create_tag_failure=True)
        self.assertNotEqual(0, self.pipeline().returncode)
        self.assertEqual(1, len([call for call in self.calls() if call[:2] == ["gh", "api"]]))
        self.assert_no_publication()

    def test_existing_or_failed_release_blocks_upload_and_portal(self):
        for state in ({"release_exists": True}, {"create_release_failure": True}):
            with self.subTest(state=state):
                self.reset()
                self.configure(tag=SOURCE, **state)
                self.assertNotEqual(0, self.pipeline().returncode)
                self.assertFalse([call for call in self.calls() if call[:3] == ["gh", "release", "upload"] or call[0] == "gradlew"])

    def test_failed_upload_blocks_portal(self):
        self.configure(tag=SOURCE, upload_failure=True)
        self.assertNotEqual(0, self.pipeline().returncode)
        self.assertFalse([call for call in self.calls() if call[0] == "gradlew"])

    def test_drift_before_each_write_boundary_fails_closed(self):
        for boundary in (ENSURE, CREATE, PUBLISH):
            for drift in ("head", "main", "tag", "version"):
                with self.subTest(boundary=boundary, drift=drift):
                    self.reset()
                    self.configure(tag=SOURCE)
                    index = BLOCKS.index(boundary)
                    result = self.run_steps(*BLOCKS[:index])
                    self.assertEqual(0, result.returncode, result.stderr)
                    if drift == "version":
                        (self.root / "VERSION").write_text("1.2.4\n")
                    else:
                        self.configure(**{drift: OTHER})
                    before = len(self.calls())
                    self.assertNotEqual(0, self.run_steps(*BLOCKS[index:]).returncode)
                    subsequent = self.calls()[before:]
                    self.assertFalse([call for call in subsequent if call[0] in ("gh", "gradlew")])


if __name__ == "__main__":
    unittest.main()
