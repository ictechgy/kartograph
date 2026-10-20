"""실제 Git·javac·배포 CLI로 PR gate의 억제 경계를 검증한다."""

import json
import os
from pathlib import Path
import shutil
import subprocess
import sys
import tempfile
import unittest


ROOT = Path(__file__).resolve().parents[2]
BINARY = Path(os.environ.get("KARTOGRAPH_BINARY", ROOT / "cli/build/install/kartograph/bin/kartograph")).resolve()
SCRIPT = Path(os.environ.get("KARTOGRAPH_PR_SCRIPT", ROOT / "Scripts/check-pr.py")).resolve()
TEST_ENV = {key: value for key, value in os.environ.items() if not key.startswith("GIT_")}
# 설계 결정 D1 회귀 고정물의 git 환경이다. 사용자 전역·시스템 설정이 끼어들지 않게 한다.
FIXTURE_ENV = {**TEST_ENV, "GIT_CONFIG_GLOBAL": os.devnull, "GIT_CONFIG_NOSYSTEM": "1"}
# stat을 낡게 만드는 두 시각(2001·2002-01-01 UTC)이다. 같은 초의 racy 재검사를 피하려고 먼 과거를 쓴다.
STALE_TIME, RESTALE_TIME = 978307200, 1009843200
# 도우미가 모든 git 호출의 -C 앞에 두어야 하는 공통 설정이다.
ISOLATION = ["-c", "core.fsmonitor=false", "-c", "log.showSignature=false"]


def plant_filter_driver(scratch, repository, name, stale):
    """분석 대상 config에 표식을 남기는 filter 드라이버를 걸고 표식 경로를 돌려준다.

    git은 stat이 index와 다른 추적 파일의 내용을 다시 읽을 때 드라이버를 실행한다. 일반 git status로 실제 실행을 먼저
    확인해 헛통과를 막고, 그 뒤 stale 파일 시각을 다른 과거로 한 번 더 돌린다. 설정은 config 파일에 바이트로 쓴다.
    """
    directory = Path(tempfile.mkdtemp(prefix="filter-driver-", dir=scratch))
    marker = directory / "ran"
    driver = directory / "driver.sh"
    driver.write_text("#!/bin/sh\n: > '" + str(marker) + "'\ncat\n")
    driver.chmod(0o700)
    with (repository / ".git" / "config").open("ab") as config:
        config.write(('[filter "' + name + '"]\n\tclean = "' + str(driver) + '"\n').encode("utf-8"))
    (repository / ".git" / "info").mkdir(exist_ok=True)
    (repository / ".git" / "info" / "attributes").write_bytes(("* filter=" + name + "\n").encode("utf-8"))
    for path in stale:
        os.utime(path, (STALE_TIME, STALE_TIME))
    subprocess.run(["git", "-C", str(repository), "--no-optional-locks", "status", "--porcelain"], env=FIXTURE_ENV,
                   capture_output=True, timeout=30, check=True)
    if not marker.exists():
        raise AssertionError("plain git status did not run the filter driver; the fixture cannot observe the boundary")
    marker.unlink()
    for path in stale:
        os.utime(path, (RESTALE_TIME, RESTALE_TIME))
    return marker


def plant_lazy_fetch_remote(scratch, source, clone, filter_spec, observe):
    """source에서 filter_spec으로 file:// partial clone을 만들고 그 원격 명령을 표식을 남기는 명령으로 바꾼다.

    압축본으로 받은 checkout의 .git/config가 고른 원격 명령(remote.origin.uploadpack)을 흉내 낸다. 같은 방법으로 만든
    확인용 clone에서 일반 git이 observe로 lazy fetch를 띄워 표식을 남기는지 먼저 확인해 헛통과를 막는다. 표식 경로를 돌려준다.
    """
    def git(directory, *arguments):
        return subprocess.run(["git", "-c", "protocol.file.allow=always", "-C", str(directory), *arguments], env=FIXTURE_ENV,
                              capture_output=True, text=True, timeout=60, check=True).stdout

    git(source, "config", "uploadpack.allowFilter", "true")
    directory = Path(tempfile.mkdtemp(prefix="upload-pack-", dir=scratch))
    marker = directory / "ran"
    command = directory / "upload-pack.sh"
    command.write_text("#!/bin/sh\n: > '" + str(marker) + "'\nexec git-upload-pack \"$@\"\n")
    command.chmod(0o700)
    witness = directory / "witness"
    for target in (witness, clone):
        git(scratch, "clone", "-q", "--no-local", "--filter=" + filter_spec, source.as_uri(), str(target))
        if not any(line.startswith("?") for line in git(target, "rev-list", "--objects", "--missing=print", "--all").splitlines()):
            raise AssertionError("the partial clone has no missing objects; the fixture cannot observe a lazy fetch")
        git(target, "config", "remote.origin.uploadpack", str(command))
    subprocess.run(["git", "-c", "protocol.file.allow=always", "-C", str(witness), *observe], env=FIXTURE_ENV,
                   capture_output=True, timeout=60)
    if not marker.exists():
        raise AssertionError("plain git did not fetch through the configured upload-pack; the fixture cannot observe the boundary")
    marker.unlink()
    return marker


def git_shim(directory, log, fail_configuration=False):
    """argv를 log에 한 줄(JSON)씩 남기고 실제 git으로 넘기는 shim을 directory에 만든다. 도우미는 PATH의 git을 쓴다.

    fail_configuration이면 `config` 하위 명령만 129로 끝낸다(조회 옵션을 모르는 옛 git처럼).
    """
    real_git = shutil.which("git", path=TEST_ENV.get("PATH"))
    directory.mkdir()
    (directory / "git").write_text("#!" + sys.executable + "\nimport json, os, sys\n" +
        "with open(" + repr(str(log)) + ", 'a') as log: log.write(json.dumps(sys.argv[1:]) + '\\n')\n" +
        ("if 'config' in sys.argv[1:]: sys.exit(129)\n" if fail_configuration else "") +
        "os.execv(" + repr(real_git) + ", [" + repr(real_git) + "] + sys.argv[1:])\n")
    (directory / "git").chmod(0o700)
    return {**TEST_ENV, "PATH": str(directory) + os.pathsep + TEST_ENV.get("PATH", "")}


class CheckPrTest(unittest.TestCase):
    def setUp(self):
        self.temporary = tempfile.TemporaryDirectory(prefix="kartograph-pr-test-")
        self.addCleanup(self.temporary.cleanup)
        self.project = Path(self.temporary.name)
        self.run_command("git", "init", "-q")
        self.run_command("git", "config", "user.name", "Fixture")
        self.run_command("git", "config", "user.email", "fixture@example.invalid")
        (self.project / ".gitignore").write_text("classes/\n")
        (self.project / "Entry.java").write_text(
            "public class Entry { public void run() { new Helper(); } }\n"
        )
        (self.project / "Helper.java").write_text("public class Helper {}\n")
        (self.project / "Existing.java").write_text("public class Existing {}\n")
        (self.project / "keep.pro").write_text("-keep class Entry { *; }\n")
        (self.project / "AndroidManifest.xml").write_text("<manifest><application/></manifest>\n")
        (self.project / "res").mkdir()
        self.compile()
        self.capture_baseline()
        self.run_command("git", "add", ".")
        self.run_command("git", "commit", "-qm", "fixture base")
        self.base = self.run_command("git", "rev-parse", "HEAD").stdout.strip()

    def run_command(self, *args):
        return subprocess.run(args, cwd=self.project, text=True, capture_output=True, check=True, env=TEST_ENV)

    def compile(self):
        self.run_command("javac", "-g", "-d", "classes", "Entry.java", "Helper.java", "Existing.java")

    def capture_baseline(self):
        self.run_command(str(BINARY), "baseline", "--write", ".kartograph-baseline.json",
                         "--project", str(self.project), "--classes", "classes", "--keep-rules", "keep.pro",
                         "--manifest", "AndroidManifest.xml", "--resources", "res", "--namespace", "fixture")

    def gate(self, *extra, base=None, helper_options=(), environment=TEST_ENV):
        return subprocess.run(
            [sys.executable, str(SCRIPT), "--binary", str(BINARY), "--project", str(self.project),
             "--base", base or self.base, *helper_options, "--", "--classes", "classes", "--keep-rules", "keep.pro",
             "--manifest", "AndroidManifest.xml", "--resources", "res", "--namespace", "fixture",
             "--report-format", "json", *extra], text=True, capture_output=True, env=environment,
        )

    def test_existing_debt_is_suppressed(self):
        result = self.gate()
        self.assertEqual(result.returncode, 0, result.stderr)
        self.assertEqual(json.loads(result.stdout)["diagnostics"], [])
        self.assertEqual(json.loads(result.stdout)["suppressedCount"], 1)

    def test_caller_removal_reports_untouched_helper_despite_pr_baseline_update(self):
        (self.project / "Entry.java").write_text("public class Entry { public void run() {} }\n")
        self.compile()
        self.capture_baseline()
        self.run_command("git", "add", ".")
        self.run_command("git", "commit", "-qm", "remove caller and expand baseline")
        result = self.gate()
        self.assertEqual(result.returncode, 1, result.stderr)
        document = json.loads(result.stdout)
        self.assertEqual([item["nodeId"] for item in document["diagnostics"]], ["class:Helper"])
        self.assertEqual(document["suppressedCount"], 1)

    def test_missing_base_fails_without_raw_ref_or_project_path(self):
        result = self.gate(base="missing-private-ref")
        self.assertEqual(result.returncode, 2)
        self.assertNotIn("missing-private-ref", result.stderr)
        self.assertNotIn(str(self.project), result.stderr)
        self.assertIn("resolve base commit", result.stderr)

    def test_missing_baseline_is_not_silently_empty(self):
        self.run_command("git", "rm", ".kartograph-baseline.json")
        self.run_command("git", "commit", "-qm", "remove baseline")
        result = self.gate(base="HEAD")
        self.assertEqual(result.returncode, 2, result.stderr)
        self.assertIn("read base baseline", result.stderr)

    def test_invalid_baseline_is_tool_failure(self):
        (self.project / ".kartograph-baseline.json").write_text("{}")
        self.run_command("git", "add", ".")
        self.run_command("git", "commit", "-qm", "invalid baseline")
        result = self.gate(base="HEAD")
        self.assertEqual(result.returncode, 2, result.stderr)
        self.assertIn("baseline fingerprints", result.stderr)

    def test_gate_overrides_and_capture_are_rejected(self):
        for option in ("--baseline", "--since", "--project", "--write-baseline", "--explain", "--help",
                       "-h", "--baseline=ignored", "--version", "-b"):
            with self.subTest(option=option):
                result = self.gate(option, "ignored")
                self.assertEqual(result.returncode, 64, result.stderr)

    def test_custom_baseline_path_with_spaces(self):
        self.run_command("git", "mv", ".kartograph-baseline.json", "baseline with spaces.json")
        self.run_command("git", "commit", "-qm", "custom baseline")
        result = self.gate(base="HEAD", helper_options=("--baseline-path", "baseline with spaces.json"))
        self.assertEqual(result.returncode, 0, result.stderr)
        self.assertEqual(json.loads(result.stdout)["suppressedCount"], 1)

    def test_missing_required_cli_value_preserves_usage_failure(self):
        self.assertEqual(self.gate("--classes").returncode, 64)

    def test_invalid_cli_report_format_preserves_usage_failure(self):
        result = self.gate("--report-format", "invalid")
        self.assertEqual(result.returncode, 64)
        self.assertIn("invalid report format", result.stderr)

    def test_test_classes_option_is_forwarded_without_rejection(self):
        result = self.gate("--test-classes", "classes")
        self.assertEqual(result.returncode, 0, result.stderr)
        self.assertEqual(json.loads(result.stdout)["suppressedCount"], 1)

    def test_service_resources_option_is_forwarded(self):
        resources = self.project / "services"
        resources.mkdir()
        result = self.gate("--service-resources", "services")
        self.assertEqual(result.returncode, 0, result.stderr)
        self.assertEqual(json.loads(result.stdout)["suppressedCount"], 1)

    def test_inherited_git_override_is_not_forwarded_to_cli(self):
        binary = self.project / "environment-cli"
        binary.write_text("#!/usr/bin/env python3\nimport os, sys\nsys.exit(7 if 'GIT_DIR' in os.environ else 0)\n")
        binary.chmod(0o700)
        result = self.gate(helper_options=("--binary", str(binary)),
                           environment={**TEST_ENV, "GIT_DIR": "/missing-fixture-git-dir"})
        self.assertEqual(result.returncode, 0, result.stderr)

    def test_non_root_project_is_rejected(self):
        result = self.gate(helper_options=("--project", str(self.project / "res")))
        self.assertEqual(result.returncode, 64, result.stderr)

    def test_cli_timeout_is_bounded_tool_failure(self):
        binary = self.project / "slow-cli"
        binary.write_text("#!/usr/bin/env python3\nimport time\ntime.sleep(60)\n")
        binary.chmod(0o700)
        result = self.gate(helper_options=("--binary", str(binary), "--timeout", "1"))
        self.assertEqual(result.returncode, 2, result.stderr)
        self.assertIn("timed out", result.stderr)

    def test_git_calls_disable_repository_commands_and_keep_the_result(self):
        scratch = Path(tempfile.mkdtemp(prefix="kartograph-pr-scratch-"))
        self.addCleanup(shutil.rmtree, scratch)
        marker = plant_filter_driver(scratch, self.project, "evil.name", [self.project / "Entry.java"])
        log = scratch / "git-calls.jsonl"

        result = self.gate(environment=git_shim(scratch / "shim", log))

        self.assertEqual(result.returncode, 0, result.stderr)
        self.assertEqual(json.loads(result.stdout)["suppressedCount"], 1)
        self.assertFalse(marker.exists(), "check-pr must not run the analyzed repository's filter driver")
        calls = [json.loads(line) for line in log.read_text().splitlines()]
        project = str(self.project.resolve())
        query = ISOLATION + ["-C", project, "config", "-z", "--name-only", "--includes", "--list"]
        overrides = ["-c", "filter.evil.name.clean=", "-c", "filter.evil.name.smudge=", "-c", "filter.evil.name.process=",
                     "-c", "filter.evil.name.required=false"]
        # 분석용 git 호출마다 바로 앞에서 드라이버를 다시 조회하고, 조회한 드라이버를 덮은 채로 실행한다.
        self.assertEqual([query] * 3, calls[0::2])
        analysis = calls[1::2]
        self.assertEqual(3, len(analysis))
        prefix = ISOLATION + overrides + ["-C", project]
        for call in analysis:
            self.assertEqual(prefix, call[:len(prefix)])
        self.assertEqual(["rev-parse", "rev-parse", "show"], [call[len(prefix)] for call in analysis])
        # show는 log 계열이라 외부 diff·textconv를 끈다(blob 읽기에는 원래 쓰이지 않지만 설정이 바꿀 여지를 없앤다).
        self.assertEqual(["--no-ext-diff", "--no-textconv"], analysis[2][len(prefix) + 1:len(prefix) + 3])

    def test_unoverridable_filter_driver_name_fails_closed(self):
        scratch = Path(tempfile.mkdtemp(prefix="kartograph-pr-scratch-"))
        self.addCleanup(shutil.rmtree, scratch)
        # git은 -c 값을 첫 '='에서 나누므로 이 이름은 덮을 수 없다.
        marker = plant_filter_driver(scratch, self.project, "a=b", [self.project / "Entry.java"])

        result = self.gate()

        self.assertEqual(result.returncode, 2, result.stderr)
        self.assertIn("filter driver whose name cannot be safely overridden", result.stderr)
        self.assertNotIn("a=b", result.stderr)
        self.assertFalse(marker.exists(), "check-pr must not run git while it cannot override the driver")

    def test_unreadable_git_configuration_fails_closed(self):
        scratch = Path(tempfile.mkdtemp(prefix="kartograph-pr-scratch-"))
        self.addCleanup(shutil.rmtree, scratch)
        log = scratch / "git-calls.jsonl"

        result = self.gate(environment=git_shim(scratch / "shim", log, fail_configuration=True))

        # 드라이버 이름을 모르면 덮을 수 없으므로 분석용 git을 하나도 실행하지 않고 도구 실패로 끝낸다.
        self.assertEqual(result.returncode, 2, result.stderr)
        self.assertIn("cannot read the Git configuration", result.stderr)
        self.assertNotIn(str(self.project), result.stderr)
        self.assertEqual(["config"], [call[call.index("-C") + 2] for call in map(json.loads, log.read_text().splitlines())])

    def partial_clone(self, observe):
        """이 저장소의 blobless clone을 만들어 커밋하지 않은 빌드 입력을 옮긴다. (clone, 표식)을 돌려준다."""
        scratch = Path(tempfile.mkdtemp(prefix="kartograph-pr-scratch-"))
        self.addCleanup(shutil.rmtree, scratch)
        clone = scratch / "clone"
        marker = plant_lazy_fetch_remote(scratch, self.project, clone, "blob:none", observe)
        # class 출력은 ignore되고 빈 resource 디렉터리는 커밋되지 않으므로 clone에 그대로 준비한다.
        shutil.copytree(self.project / "classes", clone / "classes")
        (clone / "res").mkdir()
        return clone, marker

    def test_base_baseline_missing_from_a_partial_clone_is_not_fetched(self):
        baseline = self.project / ".kartograph-baseline.json"
        # PR이 baseline을 고치면 blobless clone에는 기준 커밋의 baseline blob이 없다.
        baseline.write_text(baseline.read_text() + "\n")
        self.run_command("git", "commit", "-qam", "touch baseline")
        clone, marker = self.partial_clone(["show", self.base + ":.kartograph-baseline.json"])

        result = self.gate(helper_options=("--project", str(clone)))

        self.assertFalse(marker.exists(), "check-pr must not fetch missing objects through the analyzed repository's remote")
        self.assertEqual(2, result.returncode, result.stderr)
        self.assertIn("read base baseline", result.stderr)
        self.assertIn("partial clone", result.stderr)
        self.assertNotIn(str(clone), result.stderr)

    def test_blobless_clone_with_an_unchanged_baseline_keeps_the_result(self):
        (self.project / "Existing.java").write_text("public class Existing { }\n")
        self.run_command("git", "commit", "-qam", "touch source")
        # 기준 커밋의 다른 파일 blob은 clone에 없다. 확인용 clone에서 그 파일을 읽어 원격 명령이 관측되는지 본다.
        clone, marker = self.partial_clone(["show", self.base + ":Existing.java"])

        result = self.gate(helper_options=("--project", str(clone)))

        self.assertFalse(marker.exists(), "check-pr must not fetch missing objects through the analyzed repository's remote")
        self.assertEqual(0, result.returncode, result.stderr)
        self.assertEqual(1, json.loads(result.stdout)["suppressedCount"])

    def test_unexpected_cli_exit_maps_to_tool_failure(self):
        binary = self.project / "failing-cli"
        binary.write_text("#!/bin/sh\nexit 7\n")
        binary.chmod(0o700)
        result = self.gate(helper_options=("--binary", str(binary)))
        self.assertEqual(result.returncode, 2, result.stderr)


if __name__ == "__main__":
    unittest.main()
