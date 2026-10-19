"""실제 Git의 삭제/이름 변경을 두 snapshot의 영향 질의에 연결한다."""
import json
import os
from pathlib import Path
import subprocess
import shutil
import sys
import tempfile
import unittest

ROOT = Path(__file__).resolve().parents[2]
BINARY = Path(os.environ.get("KARTOGRAPH_BINARY", ROOT / "cli/build/install/kartograph/bin/kartograph")).resolve()
# 설계 결정 D1 회귀 고정물의 git이다. 사용자 전역·시스템 설정과 훅·서명이 고정물에 끼어들지 않게 한다.
FIXTURE_ENV = {**{key: value for key, value in os.environ.items() if not key.startswith("GIT_")},
               "GIT_CONFIG_GLOBAL": os.devnull, "GIT_CONFIG_NOSYSTEM": "1"}
FIXTURE_GIT = ["git", "-c", "user.name=Fixture", "-c", "user.email=fixture@example.invalid", "-c", "commit.gpgsign=false",
               "-c", "core.hooksPath=" + os.devnull, "-c", "protocol.file.allow=always"]
# 커밋 전 파일 시각(2000), stat을 낡게 만드는 두 시각(2001·2002), racy index 시각(1999)이다. 모두 1월 1일 UTC다.
# 같은 초에 쓰고 커밋한 파일은 git이 racy로 보고 내용을 다시 읽어 결과가 시각에 따라 흔들리므로 먼 과거를 쓴다.
COMMITTED_TIME, STALE_TIME, RESTALE_TIME, RACY_INDEX_TIME = 946684800, 978307200, 1009843200, 915148800


def fixture_git(directory, *arguments):
    """고정물 git을 실행하고 표준 출력을 돌려준다. 실패하면 CalledProcessError로 시험을 멈춘다."""
    return subprocess.run([*FIXTURE_GIT, "-C", str(directory), *map(str, arguments)], env=FIXTURE_ENV,
                          capture_output=True, text=True, timeout=30, check=True).stdout.strip()


def commit_fixture(repository, files):
    """새 저장소에 files를 쓰고 첫 커밋을 만든다. 파일 시각은 커밋 전에 과거로 둔다."""
    repository.mkdir(parents=True, exist_ok=True)
    fixture_git(repository, "init", "-q")
    for name, text in files.items():
        path = repository / name
        path.parent.mkdir(parents=True, exist_ok=True)
        path.write_text(text, encoding="utf-8")
        os.utime(path, (COMMITTED_TIME, COMMITTED_TIME))
    fixture_git(repository, "add", "-A")
    fixture_git(repository, "commit", "-qm", "fixture")


def racy_index(repository):
    """index 파일 시각을 항목보다 이르게 돌려 모든 항목을 racy로 만든다. git은 그 항목의 내용을 다시 읽어 대조한다."""
    index = Path(fixture_git(repository, "rev-parse", "--absolute-git-dir")) / "index"
    os.utime(index, (RACY_INDEX_TIME, RACY_INDEX_TIME))


def plant_filter_driver(scratch, repository, name, stale, key="clean", info_attributes=False, required=False):
    """분석 대상 config에 표식을 남기는 filter 드라이버를 걸고 표식 경로를 돌려준다.

    git은 stat이 index와 다르거나 racy인 추적 파일의 내용을 다시 읽을 때 드라이버를 실행한다. 일반 git status로 실제
    실행을 먼저 확인해 헛통과를 막고, 그 뒤 stale 파일 시각을 다른 과거로 한 번 더 돌린다. 설정은 config 파일에
    바이트로 쓴다(비ASCII 이름을 그대로 두기 위해서다). 커밋을 마친 뒤에 건다. required면 git-lfs의 기본 설정처럼
    `required = true`를 함께 둔다(명령만 비우면 git이 그 파일에서 실패한다).
    """
    directory = Path(tempfile.mkdtemp(prefix="filter-driver-", dir=scratch))
    marker = directory / "ran"
    driver = directory / "driver.sh"
    driver.write_text("#!/bin/sh\n: > '" + str(marker) + "'\n" + ("" if key == "process" else "cat\n"))
    driver.chmod(0o700)
    git_directory = Path(fixture_git(repository, "rev-parse", "--absolute-git-dir"))
    with (git_directory / "config").open("ab") as config:
        config.write(('[filter "' + name + '"]\n\t' + key + ' = "' + str(driver) + '"\n' +
                      ("\trequired = true\n" if required else "")).encode("utf-8"))
    if info_attributes:
        (git_directory / "info").mkdir(exist_ok=True)
        (git_directory / "info" / "attributes").write_bytes(("* filter=" + name + "\n").encode("utf-8"))
    for path in stale:
        os.utime(path, (STALE_TIME, STALE_TIME))
    # process 드라이버는 handshake에 답하지 않아 이 status가 실패한다. 보는 것은 종료 코드가 아니라 표식이다.
    subprocess.run([*FIXTURE_GIT, "-C", str(repository), "--no-optional-locks", "status", "--porcelain"], env=FIXTURE_ENV,
                   capture_output=True, timeout=30)
    if not marker.exists():
        raise AssertionError("plain git status did not run the filter driver; the fixture cannot observe the boundary")
    marker.unlink()
    for path in stale:
        os.utime(path, (RESTALE_TIME, RESTALE_TIME))
    return marker


class ImpactGateTest(unittest.TestCase):
    def run_with_fixture_cli(self, scratch, project, base, environment=None):
        """호출만 기록하는 대역 CLI로 check-impact를 실행한다. git 경계만 시험하므로 javac·배포 CLI가 필요 없다.

        environment가 없으면 현재 환경을 물려준다.
        """
        graph = scratch / "graph.json"
        graph.write_text("{}")
        calls = scratch / "calls.jsonl"
        binary = scratch / "fixture-cli"
        binary.write_text("#!" + sys.executable + "\n" +
            "import json,sys\nfrom pathlib import Path\n" +
            "with Path(" + repr(str(calls)) + ").open('a') as log: log.write(json.dumps(sys.argv[1:])+'\\n')\n" +
            "if sys.argv[1] == 'impact':\n" +
            " print(json.dumps({'format':'kartograph-impact','version':1,'status':'noChanges'," +
            "'inputs':{'base':{'scope':'fixture:main'},'current':{'scope':'fixture:main'}}}))\n" +
            "else: print(json.dumps({'format':'kartograph-freshness','version':1,'status':'matched'}))\n")
        binary.chmod(0o700)
        result = subprocess.run([sys.executable, str(ROOT / "Scripts/check-impact.py"), "--binary", str(binary),
                                 "--project", str(project), "--base", base, "--base-graph", str(graph), "--graph-file", str(graph)],
                                capture_output=True, text=True, timeout=60, env=environment)
        return result, calls

    def test_unreadable_git_configuration_fails_closed(self):
        with tempfile.TemporaryDirectory() as directory:
            scratch = Path(directory)
            project = scratch / "project"
            commit_fixture(project, {"src/A.kt": "class A\n"})
            base = fixture_git(project, "rev-parse", "HEAD")
            # argv를 남기고 실제 git으로 넘기되 config 하위 명령만 129로 끝내는 shim이다(조회 옵션을 모르는 옛 git처럼).
            log = scratch / "git-calls.jsonl"
            real_git = shutil.which("git")
            shim = scratch / "shim"
            shim.mkdir()
            (shim / "git").write_text("#!" + sys.executable + "\nimport json, os, sys\n" +
                "with open(" + repr(str(log)) + ", 'a') as log: log.write(json.dumps(sys.argv[1:]) + '\\n')\n" +
                "if 'config' in sys.argv[1:]: sys.exit(129)\n" +
                "os.execv(" + repr(real_git) + ", [" + repr(real_git) + "] + sys.argv[1:])\n")
            (shim / "git").chmod(0o700)

            result, calls = self.run_with_fixture_cli(scratch, project, base,
                                                      {**os.environ, "PATH": str(shim) + os.pathsep + os.environ.get("PATH", "")})

            # 드라이버 이름을 모르면 덮을 수 없으므로 분석용 git을 하나도 실행하지 않고 도구 실패로 끝낸다.
            self.assertEqual(2, result.returncode, result.stderr)
            self.assertIn("cannot read the repository Git configuration", result.stderr)
            self.assertNotIn(str(project), result.stderr)
            self.assertEqual(["config"], [call[call.index("-C") + 2] for call in map(json.loads, log.read_text().splitlines())])
            self.assertFalse(calls.exists(), "the impact CLI must not run")

    def test_working_tree_check_runs_no_repository_filter_driver(self):
        # 커밋된 .gitattributes와 .git/info/attributes, 점이 든 이름, process 키가 모두 같은 경계를 지난다.
        for name, key, info_attributes in (("evil", "clean", False), ("evil.name", "clean", False), ("evil", "process", True)):
            with self.subTest(driver=name + "." + key), tempfile.TemporaryDirectory() as directory:
                scratch = Path(directory)
                project = scratch / "project"
                attributes = {} if info_attributes else {".gitattributes": "* filter=" + name + "\n"}
                commit_fixture(project, {"src/A.kt": "class A\n", **attributes})
                base = fixture_git(project, "rev-parse", "HEAD")
                marker = plant_filter_driver(scratch, project, name, [project / "src" / "A.kt"], key, info_attributes)

                result, _ = self.run_with_fixture_cli(scratch, project, base)

                self.assertFalse(marker.exists(), "check-impact must not run the analyzed repository's filter driver")
                # 드라이버를 돌려 내용을 다시 대조하지 않으므로 stat이 낡은 추적 파일은 작업 트리 변경으로 본다(fail-safe).
                self.assertEqual(2, result.returncode, result.stderr)
                self.assertIn("tracked working tree changes", result.stderr)

    def test_racily_clean_entries_are_compared_without_the_filter_driver(self):
        # stat이 그대로여도 racy 항목은 내용을 다시 읽는다. diff.autoRefreshIndex=false만으로는 드라이버가 돈다.
        # 점이 든 이름과 process 키, git-lfs처럼 required인 드라이버도 같은 경계를 지난다.
        for name, key, required in (("evil", "clean", False), ("evil.name", "clean", False), ("evil", "process", False),
                                    ("evil", "clean", True)):
            with self.subTest(driver=name + "." + key, required=required), tempfile.TemporaryDirectory() as directory:
                scratch = Path(directory)
                project = scratch / "project"
                commit_fixture(project, {"src/A.kt": "class A\n"})
                base = fixture_git(project, "rev-parse", "HEAD")
                racy_index(project)
                marker = plant_filter_driver(scratch, project, name, [], key, info_attributes=True, required=required)

                result, calls = self.run_with_fixture_cli(scratch, project, base)

                self.assertFalse(marker.exists(), "check-impact must not run the filter driver for racy entries")
                # 드라이버 없이 대조해도 내용이 그대로라 작업 트리 변경이 없다. 보고서는 지금처럼 나온다.
                self.assertEqual(0, result.returncode, result.stderr)
                self.assertEqual("noChanges", json.loads(result.stdout)["status"])
                self.assertEqual(["impact", "verify-snapshot"], [json.loads(line)[0] for line in calls.read_text().splitlines()])

    def test_unoverridable_filter_driver_name_fails_closed(self):
        # git은 -c 값을 첫 '='에서 나누므로 "a=b"는 덮을 수 없다. 비ASCII 이름은 argv 인코딩에 따라 다른 이름이 될 수 있다.
        for name in ("a=b", "\u00e9vil"):
            with self.subTest(name=name), tempfile.TemporaryDirectory() as directory:
                scratch = Path(directory)
                project = scratch / "project"
                commit_fixture(project, {"src/A.kt": "class A\n"})
                base = fixture_git(project, "rev-parse", "HEAD")
                marker = plant_filter_driver(scratch, project, name, [project / "src" / "A.kt"], info_attributes=True)

                result, calls = self.run_with_fixture_cli(scratch, project, base)

                self.assertEqual(2, result.returncode, result.stderr)
                self.assertIn("filter driver whose name cannot be safely overridden", result.stderr)
                self.assertNotIn(name, result.stderr)
                self.assertFalse(marker.exists(), "check-impact must not run git while it cannot override the driver")
                self.assertFalse(calls.exists(), "the impact CLI must not run")

    def test_dirty_submodule_is_not_inspected_but_a_moved_commit_still_fails(self):
        with tempfile.TemporaryDirectory() as directory:
            scratch = Path(directory)
            library = scratch / "library"
            commit_fixture(library, {"inner.txt": "inner\n", "edited.txt": "base\n"})
            project = scratch / "project"
            commit_fixture(project, {"top.kt": "top\n"})
            fixture_git(project, "submodule", "--quiet", "add", library, "sub")
            fixture_git(project, "commit", "-qm", "submodule")
            base = fixture_git(project, "rev-parse", "HEAD")
            submodule = project / "sub"
            # 서브모듈 저장소 자신의 config에 건 드라이버다. 상위 저장소의 diff가 서브모듈 안에서 git을 띄우면 실행된다.
            marker = plant_filter_driver(scratch, submodule, "evil", [submodule / "inner.txt"], info_attributes=True)
            (submodule / "edited.txt").write_text("uncommitted\n")

            result, _ = self.run_with_fixture_cli(scratch, project, base)

            # 서브모듈 안의 커밋하지 않은 변경은 보지 않는다. 그것을 보려면 서브모듈 저장소의 설정을 따르는 git을 띄워야 한다.
            self.assertEqual(0, result.returncode, result.stderr)
            self.assertFalse(marker.exists(), "check-impact must not run git inside the submodule")
            fixture_git(submodule, "-c", "filter.evil.clean=", "commit", "-qam", "moved")
            moved, _ = self.run_with_fixture_cli(scratch, project, base)
            # 기록과 다른 커밋을 가리키는 서브모듈은 지금처럼 HEAD에 없는 작업 트리 변경이다.
            self.assertEqual(2, moved.returncode, moved.stderr)
            self.assertIn("tracked working tree changes", moved.stderr)
            self.assertFalse(marker.exists(), "check-impact must not run git inside the submodule")

    def test_snapshot_size_budget_reaches_impact_and_both_freshness_checks(self):
        with tempfile.TemporaryDirectory() as directory:
            project = Path(directory)
            subprocess.run(['git', 'init', '-q', str(project)], check=True)
            subprocess.run(['git', '-C', str(project), '-c', 'user.name=Fixture',
                            '-c', 'user.email=fixture@example.invalid', 'commit', '--allow-empty', '-qm', 'base'], check=True)
            base = subprocess.check_output(['git', '-C', str(project), 'rev-parse', 'HEAD'], text=True).strip()
            graph = project / 'graph.json'
            graph.write_text('{}')
            calls = project / 'calls.jsonl'
            binary = project / 'fixture-cli'
            binary.write_text('#!' + sys.executable + '\n' +
                'import json,sys\nfrom pathlib import Path\n' +
                'with Path(' + repr(str(calls)) + ').open("a") as log: log.write(json.dumps(sys.argv[1:])+"\\n")\n' +
                'if sys.argv[1] == "impact":\n' +
                ' print(json.dumps({"format":"kartograph-impact","version":1,"status":"noChanges",' +
                '"inputs":{"base":{"scope":"fixture:main"},"current":{"scope":"fixture:main"}}}))\n' +
                'else: print(json.dumps({"format":"kartograph-freshness","version":1,"status":"matched"}))\n')
            binary.chmod(0o700)
            command = [sys.executable, str(ROOT / 'Scripts/check-impact.py'), '--binary', str(binary),
                       '--project', str(project), '--base-project', str(project), '--base', base,
                       '--base-graph', str(graph), '--graph-file', str(graph), '--snapshot-max-mib', '128']
            result = subprocess.run(command, capture_output=True, text=True, timeout=30)
            self.assertEqual(0, result.returncode, result.stderr)
            recorded = [json.loads(line) for line in calls.read_text().splitlines()]
            self.assertEqual(['impact', 'verify-snapshot', 'verify-snapshot'], [row[0] for row in recorded])
            for row in recorded:
                self.assertEqual('128', row[row.index('--snapshot-max-mib') + 1])
            default = subprocess.run(command[:-2], capture_output=True, text=True, timeout=30)
            self.assertEqual(0, default.returncode, default.stderr)
            for row in [json.loads(line) for line in calls.read_text().splitlines()][-3:]:
                self.assertNotIn('--snapshot-max-mib', row)
            without_base = command.copy()
            at = without_base.index('--base-project')
            del without_base[at:at + 2]
            self.assertEqual(64, subprocess.run(without_base + ['--base-input', 'external/example=unused'],
                                                capture_output=True, timeout=30).returncode)
            for value in ['0', '129', 'bad']:
                invalid = command[:-1] + [value]
                self.assertEqual(64, subprocess.run(invalid, capture_output=True, timeout=30).returncode)
            self.assertEqual(64, subprocess.run(command + ['--snapshot-max-mib', '64'],
                                                capture_output=True, timeout=30).returncode)

    def test_deleted_source_keeps_old_callers_and_noop_is_explicit(self):
        javac = str(Path(os.environ["JAVA_HOME"]) / "bin/javac") if "JAVA_HOME" in os.environ else shutil.which("javac")
        if not javac:
            self.skipTest("javac is required for the compiler/Git fixture")
        with tempfile.TemporaryDirectory() as directory:
            project = Path(directory)
            def run(*args, expected=0):
                result = subprocess.run(list(map(str, args)), cwd=project, capture_output=True, text=True, timeout=30)
                self.assertEqual(expected, result.returncode, result.stderr)
                return result.stdout
            run("git", "init", "-q")
            run("git", "config", "user.email", "fixture@example.invalid")
            run("git", "config", "user.name", "Fixture")
            source = project / "source space"
            source.mkdir()
            (source / "Caller.java").write_text('public class Caller {void run() throws Exception {Class.forName("Changed");}}')
            (source / "Changed.java").write_text("public class Changed {static void run(){}}")
            run("git", "add", "--", "source space")
            run("git", "commit", "-qm", "base")
            base = run("git", "rev-parse", "HEAD").strip()
            classes = project / "classes"
            classes.mkdir()
            run(javac, "-g", "-d", classes, *source.glob("*.java"))
            before = project / "base.json"
            before.write_text(run(BINARY, "snapshot", "--classes", classes, "--project", project,
                "--include-paths", "--revision", base, "--scope", "fixture:main"))
            (source / "Changed.java").unlink()
            run("git", "add", "-u")
            run("git", "commit", "-qm", "remove")
            current = run("git", "rev-parse", "HEAD").strip()
            fresh = project / "fresh"
            fresh.mkdir()
            run(javac, "-g", "-d", fresh, source / "Caller.java")
            after = project / "current.json"
            after.write_text(run(BINARY, "snapshot", "--classes", fresh, "--project", project,
                "--include-paths", "--revision", current, "--scope", "fixture:main"))
            command = [sys.executable, ROOT / "Scripts/check-impact.py", "--binary", BINARY, "--project", project,
                "--base", base, "--base-graph", before, "--graph-file", after]
            report = json.loads(run(*command))
            self.assertIn("class:Changed", [item["usr"] for item in report["changed"]])
            self.assertIn("method:Caller#run()V", [item["usr"] for item in report["affected"]])
            self.assertEqual("found", report["status"])
            self.assertEqual("unverified", report["freshness"]["current"]["status"])
            empty_bindings = {"format": "kartograph-local-input-bindings", "version": 1, "bindings": {}}
            current_bindings = project / "current-bindings.json"
            base_bindings = project / "base-bindings.json"
            current_bindings.write_text(json.dumps(empty_bindings))
            base_bindings.write_text(json.dumps(empty_bindings))
            bound_command = command + ["--base-project", project, "--input-bindings", current_bindings,
                                       "--base-input-bindings", base_bindings, "--snapshot-max-mib", "128"]
            bound = json.loads(run(*bound_command))
            self.assertEqual("unverified", bound["freshness"]["base"]["status"])
            base_bindings.write_text("{}")
            run(*bound_command, expected=2)
            base_bindings.write_text(json.dumps(empty_bindings))
            current_bindings.write_text("{}")
            run(*bound_command, expected=2)
            current_bindings.write_text(json.dumps(empty_bindings))
            strict = json.loads(run(*command, "--strict", expected=1))
            self.assertEqual("found", strict["status"])
            self.assertEqual("unverified", strict["freshness"]["current"]["status"])
            original_class = (fresh / "Caller.class").read_bytes()
            (fresh / "Caller.class").write_bytes(original_class + b"changed")
            stale = json.loads(run(*command))
            self.assertEqual("stale", stale["freshness"]["current"]["status"])
            run(*command, "--strict", expected=1)
            (fresh / "Caller.class").write_bytes(original_class)
            self.assertNotIn(str(project), json.dumps(report))
            noop = command.copy()
            noop[noop.index("--base") + 1] = current
            noop[noop.index("--base-graph") + 1] = after
            self.assertEqual("noChanges", json.loads(run(*noop))["status"])
            mismatch = command.copy()
            mismatch[mismatch.index("--base-graph") + 1] = after
            run(*mismatch, expected=2)
            moved = project / "renamed folder"
            moved.mkdir()
            run("git", "mv", "source space/Caller.java", "renamed folder/Caller.java")
            run("git", "commit", "-qm", "rename")
            renamed = run("git", "rev-parse", "HEAD").strip()
            run(javac, "-g", "-d", fresh, moved / "Caller.java")
            renamed_graph = project / "renamed.json"
            renamed_graph.write_text(run(BINARY, "snapshot", "--classes", fresh, "--project", project,
                "--include-paths", "--revision", renamed, "--scope", "fixture:main"))
            compare = [sys.executable, ROOT / "Scripts/check-impact.py", "--binary", BINARY, "--project", project,
                "--base", current, "--base-graph", after, "--graph-file", renamed_graph]
            renamed_report = json.loads(run(*compare))
            self.assertEqual([], renamed_report["unresolved"])
            self.assertIn("class:Caller", [item["usr"] for item in renamed_report["changed"]])
            (project / "runtime-config.toml").write_text("enabled = false\n")
            run("git", "add", "--", "runtime-config.toml")
            run("git", "commit", "-qm", "config")
            configured = run("git", "rev-parse", "HEAD").strip()
            config_graph = project / "configured.json"
            config_graph.write_text(run(BINARY, "snapshot", "--classes", fresh, "--project", project,
                "--include-paths", "--revision", configured, "--scope", "fixture:main"))
            unresolved = json.loads(run(sys.executable, ROOT / "Scripts/check-impact.py", "--binary", BINARY,
                "--project", project, "--base", renamed, "--base-graph", renamed_graph, "--graph-file", config_graph,
                "--strict", expected=1))
            self.assertEqual("unmappedFile", unresolved["unresolved"][0]["reason"])
            report_only = json.loads(run(sys.executable, ROOT / "Scripts/check-impact.py", "--binary", BINARY,
                "--project", project, "--base", renamed, "--base-graph", renamed_graph, "--graph-file", config_graph))
            self.assertEqual("notFound", report_only["status"])
            self.assertEqual("unmappedFile", report_only["unresolved"][0]["reason"])

    def test_usage_errors_keep_the_usage_exit_code(self):
        result = subprocess.run([sys.executable, str(ROOT / "Scripts/check-impact.py"), "--unknown"],
            capture_output=True, text=True, timeout=20)
        self.assertEqual(64, result.returncode)
        self.assertNotIn("Traceback", result.stderr)
