"""실제 release workflow shell을 job별로 실행해 외부 쓰기 전 실패 경계를, job·step 구조로 권한·비밀·캐시 경계를 확인한다.

verify job은 검증 메타데이터 밖의 fixture 빌드와 전체 테스트를 비밀·쓰기 토큰·environment 없이 돌리고 배포물 해시 목록만
넘긴다. publish job은 그 목록을 믿지 않는다. source를 스스로 다시 확인하고, 다시 빌드한 배포물의 목록이 넘겨받은 목록과
문자 그대로 같을 때만 tag·release·Portal에 쓴다. 두 job은 checkout과 GITHUB_ENV를 공유하지 않으므로 테스트도 job마다 새로 둔다.
"""

import json
import os
from pathlib import Path
import re
import shutil
import subprocess
import sys
import tempfile
import unittest

REPOSITORY = Path(__file__).resolve().parents[2]
WORKFLOW = REPOSITORY / ".github/workflows/release.yml"
WORKFLOWS = WORKFLOW.parent
# publish job이 배포물을 다시 빌드하고 해시를 계산할 때 실행하는 저장소 스크립트다. 테스트도 실제 파일을 실행한다.
RELEASE_SCRIPTS = ("Scripts/release-artifacts.sh", "Scripts/prepare-release-metadata.py")
SOURCE = "1" * 40
OTHER = "2" * 40
TAG = "v1.2.3"
ADMIT = "Validate manual source input"
RESOLVE = "Resolve release source"
READINESS = "Verify release artifacts without publishing"
RECORD = "Record verified release artifact hashes"
REBUILD = "Rebuild release artifacts"
MATCH = "Match rebuilt artifacts to the verified hashes"
ENSURE = "Ensure the exact release tag after verification"
CREATE = "Create GitHub release"
PUBLISH = "Publish Gradle plugin"
# 테스트가 실행하는 verify job step이다. 나머지 verify step은 실제 Gradle·Android SDK가 필요해 구조로만 확인한다.
VERIFY_BLOCKS = (ADMIT, RESOLVE, READINESS, RECORD)
# publish job의 run step 전체다. 테스트는 publish job의 run step을 빠짐없이 실행한다.
PUBLISH_BLOCKS = (ADMIT, RESOLVE, REBUILD, MATCH, ENSURE, CREATE, PUBLISH)
WRITE_BOUNDARIES = (ENSURE, CREATE, PUBLISH)
DISPATCH_ONLY = "github.event_name == 'workflow_dispatch'"
# Scripts/release-artifacts.sh가 해시 목록을 내는 순서다. POM은 해시로만 대조하고 release asset으로 올리지 않는다.
ARTIFACTS = (
    "cli/build/distributions/kartograph-1.2.3.zip",
    "cli/build/distributions/kartograph-1.2.3.tar",
    "gradle-plugin/build/libs/kartograph-gradle-plugin-1.2.3.jar",
    "compiler-collectors/build/distributions/kartograph-compiler-collectors-1.2.3.zip",
    "gradle-plugin/build/publications/pluginMaven/pom-default.xml",
    "build/release/kartograph-1.2.3.cdx.json",
    "build/release/kartograph-gradle-plugin-1.2.3.cdx.json",
    "build/release/SHA256SUMS",
)
UPLOADS = tuple(path for path in ARTIFACTS if not path.endswith(".xml"))
PLUGIN_JAR = ARTIFACTS[2]
# harness가 흉내 내는 job·run step 키다. 다른 키(예: continue-on-error, working-directory)는 실행 의미가 달라 멈춘다.
JOB_KEYS = {"runs-on", "timeout-minutes", "permissions", "environment", "needs", "outputs", "steps"}
RUN_STEP_KEYS = {"name", "id", "if", "shell", "env", "with", "run"}

# 실제 네트워크·빌드·게시 명령 대신 job별 인자와 상태 전이를 기록하며, 모르는 명령은 실패한다.
# 호출마다 새 Python 프로세스가 뜨므로 빌드·게시 경로에서만 쓰는 모듈은 그때 import한다.
MOCK = r'''
import json
import os
from pathlib import Path
import sys

state_path = Path(os.environ["MOCK_STATE"])
state = json.loads(state_path.read_text())
command = Path(sys.argv[0]).name
args = sys.argv[1:]
job = os.environ["MOCK_JOB"]
workspace = Path.cwd()
with Path(os.environ["MOCK_LOG"]).open("a") as log:
    log.write(json.dumps({"job": job, "argv": [command, *args]}) + "\n")

def save():
    state_path.write_text(json.dumps(state))

def fail():
    sys.exit(1)

def digest(name):
    import hashlib
    return hashlib.sha256((workspace / name).read_bytes()).hexdigest()

def produce(*names):
    # 같은 source의 빌드는 job이 달라도 같은 바이트를 낸다. rebuild_drift는 지정한 job의 배포물 하나만 바꾼다.
    for name in names:
        path = workspace / name
        path.parent.mkdir(parents=True, exist_ok=True)
        drift = "drifted\n" if state.get("rebuild_drift") == [job, name] else ""
        path.write_text("artifact " + name + "\n" + drift)

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
        if state.get("upload_failure") or "--clobber" in args or not all((workspace / name).is_file() for name in args[3:]):
            fail()
        state["uploaded"] = {name: digest(name) for name in args[3:]}
        save()
    else:
        fail()
elif command == "gradlew":
    version = (workspace / "VERSION").read_text().strip()
    if args == ["--no-daemon", "clean", ":cli:distZip", ":cli:distTar", ":gradle-plugin:jar",
                ":gradle-plugin:generatePomFileForPluginMavenPublication", ":gradle-plugin:validatePlugins",
                ":cli:runtimeSbom", ":gradle-plugin:runtimeSbom"]:
        produce(f"cli/build/distributions/kartograph-{version}.zip", f"cli/build/distributions/kartograph-{version}.tar",
                f"gradle-plugin/build/libs/kartograph-gradle-plugin-{version}.jar",
                "gradle-plugin/build/publications/pluginMaven/pom-default.xml",
                f"cli/build/reports/sbom/kartograph-{version}.cdx.json",
                f"gradle-plugin/build/reports/sbom/kartograph-gradle-plugin-{version}.cdx.json")
    elif args == ["--no-daemon", "-p", "compiler-collectors", "clean", "distZip"]:
        produce(f"compiler-collectors/build/distributions/kartograph-compiler-collectors-{version}.zip")
    elif args == ["--no-daemon", ":gradle-plugin:publishPlugins"]:
        state["published_plugin"] = digest(f"gradle-plugin/build/libs/kartograph-gradle-plugin-{version}.jar")
        save()
    else:
        fail()
elif command == "python3" and args == ["Scripts/prepare-release-metadata.py"]:
    # 실제 release metadata 스크립트가 SBOM을 build/release로 옮기고 SHA256SUMS를 쓴다.
    import runpy
    runpy.run_path(str(workspace / args[0]), run_name="__main__")
else:
    fail()
'''

# verify job의 readiness step이 부르는 gate 대역이다. 실제 readiness의 두 번 빌드와 검증 순서는
# test_release_readiness_order.py가 검사한다. 여기서는 공용 스크립트로 clean build와 봉인(second.sha256)만 하고,
# 앞선 step의 export에 기대지 않고 GRADLE_8_HOME을 받는지 확인한다.
READINESS_GATE = """#!/bin/sh
set -eu
test "$GRADLE_8_HOME" = "$MOCK_GRADLE_HOME"
Scripts/release-artifacts.sh build
mkdir -p build/reports/release-readiness
Scripts/release-artifacts.sh hashes > build/reports/release-readiness/second.sha256
"""


def require(condition, message):
    """harness가 흉내 내지 않는 workflow 배치를 만나면 조용히 통과시키지 않고 멈춘다."""
    if not condition:
        raise AssertionError(message)


def install(path, text):
    """테스트 작업 공간에 실행 파일 하나를 쓴다."""
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text(text, encoding="utf-8")
    path.chmod(0o755)


def scalar(value):
    """step 값 하나를 읽는다. 따옴표를 벗기고, 따옴표 밖의 줄 끝 주석은 버린다."""
    text = value.strip()
    if text[:1] in ("'", '"'):
        end = text.find(text[0], 1)
        return text[1:end] if end > 0 else text
    return text.split(" #", 1)[0].rstrip()


def run_body(lines):
    """`run: |` 본문 줄을 shell 입력 하나로 잇는다. 다음 step 앞의 빈 줄은 본문에 넣지 않는다."""
    while lines and not lines[-1].strip():
        lines.pop()
    return "\n".join(lines) + "\n"


def workflow_jobs(path):
    """PyYAML 없이 job별 최상위 값, permissions·outputs 같은 한 단계 map, step 목록을 읽는다.

    이 저장소 workflow의 2칸 들여쓰기(`jobs:` 아래 `  <job>:`, `    steps:` 아래 `      - `)만 다룬다. step에서는 최상위
    키, with·env 입력, `run: |` 본문을 읽는다. 다른 배치를 만나면 빈 값으로 조용히 통과시키지 않도록 ValueError로 멈춘다.
    여러 줄 with 값과 더 깊은 job 설정(예: strategy.matrix)은 판정에 쓰지 않는다.
    """
    jobs, job, step, section, step_section, body, in_jobs = {}, None, None, None, None, None, False
    for raw in path.read_text(encoding="utf-8").split("\n"):
        content = raw.lstrip(" ")
        indent = len(raw) - len(content)
        if body is not None:
            if not content or indent >= 10:
                body.append(raw[10:])
                continue
            step["run"], body = run_body(body), None
        if not content or content.startswith("#"):
            continue
        if indent == 0:
            in_jobs, job = content == "jobs:", None
        elif not in_jobs:
            continue
        elif indent == 2:
            if not content.endswith(":"):
                raise ValueError(f"{path.name}: unexpected job layout: {content}")
            job, section, step = {"steps": []}, None, None
            jobs[content[:-1]] = job
        elif indent == 4:
            key, _, value = content.partition(":")
            section = None if value.strip() else key
            if section is None:
                job[key] = scalar(value)
            elif section != "steps":
                job[section] = {}
        elif section == "steps":
            if indent == 6:
                if not content.startswith("- "):
                    raise ValueError(f"{path.name}: unexpected steps layout: {content}")
                step, step_section = {"with": {}, "env": {}}, None
                job["steps"].append(step)
                content, indent = content[2:], 8
            if step is None:
                raise ValueError(f"{path.name}: unexpected steps layout: {content}")
            if indent == 8:
                key, _, value = content.partition(":")
                step_section = None if value.strip() else key
                if key == "run" and value.strip() == "|":
                    body = []
                elif step_section is None:
                    step[key] = scalar(value)
            elif indent == 10 and step_section in ("with", "env"):
                key, _, value = content.partition(":")
                step[step_section][key] = scalar(value)
        elif section is not None and indent == 6:
            key, _, value = content.partition(":")
            job[section][key] = scalar(value)
    if body is not None:
        step["run"] = run_body(body)
    return jobs


def workflow_steps(path):
    """모든 job의 step을 순서대로 잇는다. job을 가리지 않는 캐시 경계 검사에 쓴다."""
    return [step for job in workflow_jobs(path).values() for step in job["steps"]]


def step_named(job, name):
    """job에서 이름이 name인 step 하나를 찾는다. 없거나 둘 이상이면 실패한다."""
    matches = [step for step in job["steps"] if step.get("name") == name]
    require(len(matches) == 1, f"expected exactly one step named {name!r}, found {len(matches)}")
    return matches[0]


def action(step):
    """step이 쓰는 action 이름을 고정 SHA 없이 돌려준다. run step이면 빈 문자열이다."""
    return step.get("uses", "").split("@", 1)[0]


def file_commands(path):
    """runner가 GITHUB_ENV·GITHUB_OUTPUT 파일을 읽는 규칙대로 NAME=VALUE와 NAME<<DELIMITER 값을 읽는다.

    actions/runner처럼 빈 줄은 건너뛰고, 여러 줄 값에는 구분자 바로 앞 줄바꿈을 넣지 않으며, 구분자가 없거나 형식이 틀리면
    실패한다. Linux runner 기준이라 줄바꿈은 LF만 센다.
    """
    lines = path.read_text(encoding="utf-8").split("\n")
    values, index = {}, 0
    while index < len(lines):
        line, index = lines[index], index + 1
        if not line:
            continue
        equals, heredoc = line.find("="), line.find("<<")
        if equals > 0 and (heredoc < 0 or equals < heredoc):
            key, value = line.split("=", 1)
        else:
            key, _, delimiter = line.partition("<<")
            require(heredoc > 0 and delimiter, "invalid file command: " + line)
            require(delimiter in lines[index:], "matching delimiter not found: " + delimiter)
            end = lines.index(delimiter, index)
            value, index = "\n".join(lines[index:end]), end + 1
        values[key] = value
    return values


class WorkflowCacheBoundaryTest(unittest.TestCase):
    """release job과 입력 SHA의 코드를 실행하는 dispatch 검증의 캐시 경계를 확인한다.

    dispatch 검증은 기본 브랜치 캐시 범위에서 검토 전 코드를 실행할 수 있다. 그 job이 저장한 Gradle·JDK 캐시를
    release job이 복원하면, 다시 검증되지 않는 wrapper 배포본의 init 스크립트가 게이트와 게시 단계에 적용된다.
    verify job이 넘기는 해시 목록도 publish job의 비교 기준이므로 두 release job 모두 캐시를 쓰지 않는다.
    """

    def steps_using(self, workflow, action_name):
        steps = [step for step in workflow_steps(WORKFLOWS / workflow) if action(step) == action_name]
        self.assertTrue(steps, f"{workflow} has no {action_name} step")
        return steps

    def cache_actions(self, workflow):
        actions = [action(step) for step in workflow_steps(WORKFLOWS / workflow)]
        return [name for name in actions if name in ("actions/cache", "actions/cache/restore", "actions/cache/save")]

    def test_release_jobs_neither_restore_nor_save_build_caches(self):
        jobs = workflow_jobs(WORKFLOW)
        self.assertEqual(["verify", "publish"], list(jobs))
        for name, job in jobs.items():
            with self.subTest(job=name):
                setups = [step["with"] for step in job["steps"] if action(step) == "actions/setup-java"]
                self.assertEqual(1, len(setups))
                self.assertEqual(("temurin", "17"), (setups[0].get("distribution"), setups[0].get("java-version")))
                self.assertNotIn("cache", setups[0])
                self.assertEqual("false", setups[0].get("cache-jdk"))
        # Gradle 8.10.2는 verify job의 AGP 8 fixture에만 필요하다. publish job은 저장소 wrapper만 쓴다.
        gradles = [(name, step) for name, job in jobs.items() for step in job["steps"]
                   if action(step) == "gradle/actions/setup-gradle"]
        self.assertEqual(["verify"], [name for name, _ in gradles])
        for _, step in gradles:
            self.assertEqual("8.10.2", step["with"].get("gradle-version"))
            self.assertEqual("true", step["with"].get("cache-disabled"))
        self.assertEqual([], self.cache_actions("release.yml"))

    def test_dispatch_validation_restores_caches_without_saving_them(self):
        for workflow in ("release-readiness.yml", "cli-repeatability.yml"):
            with self.subTest(workflow=workflow):
                for step in self.steps_using(workflow, "actions/setup-java"):
                    self.assertEqual(("temurin", "17"), (step["with"].get("distribution"), step["with"].get("java-version")))
                    self.assertEqual("gradle", step["with"].get("cache"))
                    self.assertEqual("true", step["with"].get("cache-read-only"))
                for step in workflow_steps(WORKFLOWS / workflow):
                    if action(step) == "gradle/actions/setup-gradle":
                        self.assertIn("true", (step["with"].get("cache-read-only"), step["with"].get("cache-disabled")))
                self.assertFalse([name for name in self.cache_actions(workflow) if name != "actions/cache/restore"])


class ReleaseJobBoundaryTest(unittest.TestCase):
    """권한·비밀·environment가 닿는 job과, 실행 테스트가 흉내 내지 않는 action·job 설정을 구조로 확인한다.

    publish job의 run step이 fixture·테스트·SDK 명령을 실행하지 않는다는 점은 ReleaseWorkflowTest가 그 job의 run step을
    모르는 명령을 실패시키는 mock으로 빠짐없이 실행해 확인한다.
    """

    def setUp(self):
        self.jobs = workflow_jobs(WORKFLOW)
        self.assertEqual(["verify", "publish"], list(self.jobs))
        self.verify, self.publish = self.jobs["verify"], self.jobs["publish"]

    def test_verify_job_runs_the_whole_gate_without_write_token_or_environment(self):
        self.assertEqual({"contents": "read"}, self.verify.get("permissions"))
        self.assertNotIn("environment", self.verify)
        self.assertEqual({"artifact-hashes": "${{ steps.verified.outputs.artifact-hashes }}"}, self.verify.get("outputs"))
        gate = ["Accept Android SDK licenses", "Install fixture Android platform", "Test and verify coverage",
                "Verify fixtures", "Verify optional compiler collectors",
                "Verify exact compiler call positions without skipped checks",
                "Verify minimum supported AGP and Gradle", READINESS, RECORD, "Preserve release readiness evidence"]
        self.assertEqual(gate, [step.get("name") for step in self.verify["steps"] if step.get("name") in gate])
        self.assertEqual("verified", step_named(self.verify, RECORD).get("id"))
        self.assertEqual("always()", step_named(self.verify, "Preserve release readiness evidence").get("if"))

    def test_publish_job_holds_write_token_and_environment_and_runs_only_release_steps(self):
        self.assertEqual("verify", self.publish.get("needs"))
        self.assertEqual("release", self.publish.get("environment"))
        self.assertEqual({"contents": "write"}, self.publish.get("permissions"))
        self.assertEqual(["actions/checkout", "gradle/actions/wrapper-validation", "actions/setup-java"],
                         [action(step) for step in self.publish["steps"] if "uses" in step])
        self.assertEqual(list(PUBLISH_BLOCKS), [step.get("name") for step in self.publish["steps"] if "run" in step])
        self.assertEqual({"VERIFIED_HASHES": "${{ needs.verify.outputs.artifact-hashes }}"},
                         step_named(self.publish, MATCH)["env"])

    def test_both_jobs_check_out_admit_and_resolve_the_same_source(self):
        for job in (self.verify, self.publish):
            checkouts = [step["with"] for step in job["steps"] if action(step) == "actions/checkout"]
            self.assertEqual([{"ref": "${{ inputs.source_sha || github.sha }}", "persist-credentials": "false"}], checkouts)
        for name in (ADMIT, RESOLVE):
            with self.subTest(name=name):
                self.assertEqual(step_named(self.verify, name), step_named(self.publish, name))

    def test_secrets_and_workflow_token_reach_only_publish_write_steps(self):
        expressions = re.findall(r"\$\{\{(.*?)\}\}", WORKFLOW.read_text(encoding="utf-8"))
        sensitive = sorted(expression.strip() for expression in expressions
                           if "secrets" in expression or "token" in expression)
        self.assertEqual(["github.token", "github.token", "secrets.GRADLE_PUBLISH_KEY", "secrets.GRADLE_PUBLISH_SECRET"],
                         sensitive)
        self.assertEqual({"GH_TOKEN": "${{ github.token }}"}, step_named(self.publish, ENSURE)["env"])
        self.assertEqual({"GH_TOKEN": "${{ github.token }}"}, step_named(self.publish, CREATE)["env"])
        self.assertEqual({"GRADLE_PUBLISH_KEY": "${{ secrets.GRADLE_PUBLISH_KEY }}",
                          "GRADLE_PUBLISH_SECRET": "${{ secrets.GRADLE_PUBLISH_SECRET }}"},
                         step_named(self.publish, PUBLISH)["env"])

    def test_shell_syntax_and_readiness_precedes_tag_creation(self):
        # tag 생성은 verify job의 readiness가 성공해야 시작되는 publish job에만 있다.
        self.assertTrue(step_named(self.verify, READINESS))
        self.assertTrue(step_named(self.publish, ENSURE))
        self.assertEqual([], [step for step in self.verify["steps"] if step.get("name") in WRITE_BOUNDARIES])
        # 실패 뒤에도 도는 조건은 verify job의 증거 upload뿐이다.
        self.assertEqual({ADMIT: DISPATCH_ONLY, "Preserve release readiness evidence": "always()"},
                         {step.get("name"): step["if"] for step in self.verify["steps"] if "if" in step})
        self.assertEqual({ADMIT: DISPATCH_ONLY}, {step.get("name"): step["if"] for step in self.publish["steps"] if "if" in step})
        for name, job in self.jobs.items():
            # job 조건이나 실패 무시가 있으면 verify 실패나 해시 불일치를 넘어 쓰기 단계가 실행될 수 있다.
            self.assertEqual(set(), (set(job) - JOB_KEYS), name)
            for step in job["steps"]:
                self.assertNotIn("continue-on-error", step, step.get("name"))
                if "run" in step:
                    with self.subTest(job=name, step=step.get("name")):
                        result = subprocess.run(["bash", "-n"], input=step["run"], text=True, capture_output=True, timeout=20)
                        self.assertEqual(0, result.returncode, result.stderr)


class ReleaseRun:
    """release.yml의 두 job을 runner처럼 실행하는 임시 작업 공간이다.

    job마다 자기 checkout(작업 디렉터리)과 새 GITHUB_ENV·GITHUB_OUTPUT을 갖고, 원격 상태(state.json)와 호출 기록만 공유한다.
    외부 명령은 모르는 인자를 실패시키는 mock이고, 이 harness가 흉내 내지 않는 workflow 식·조건·키를 만나면 멈춘다.
    """

    def __init__(self, root):
        self.root = root
        self.jobs = workflow_jobs(WORKFLOW)
        self.state_path = root / "state.json"
        self.log_path = root / "calls.jsonl"
        for name in ("git", "gh", "python3"):
            install(root / "bin" / name, "#!" + sys.executable + "\n" + MOCK)
        # readiness step은 `command -v gradle`의 위치로 GRADLE_8_HOME을 정한다.
        install(root / "bin/gradle", "#!/bin/sh\nexit 0\n")
        self.reset()

    def reset(self, event="workflow_dispatch", inputs=None, **changes):
        """원격 상태, 호출 기록, 두 job의 새 checkout, workflow 입력과 runner 환경을 처음 상태로 되돌린다."""
        self.state_path.write_text(json.dumps({"head": SOURCE, "main": SOURCE, "tag": None}))
        self.log_path.write_text("")
        for job in ("verify", "publish"):
            workspace = self.root / job
            if workspace.exists():
                shutil.rmtree(workspace)
            install(workspace / "gradlew", "#!" + sys.executable + "\n" + MOCK)
            for script in RELEASE_SCRIPTS:
                install(workspace / script, (REPOSITORY / script).read_text(encoding="utf-8"))
        install(self.root / "verify/Scripts/verify-release-readiness.sh", READINESS_GATE)
        self.set_version("1.2.3")
        dispatch = event == "workflow_dispatch"
        self.inputs = {"source_sha": SOURCE, "tag": TAG} if dispatch else {}
        self.inputs.update(inputs or {})
        self.outputs = {}
        self.env = {
            "PATH": str(self.root / "bin") + os.pathsep + os.defpath,
            "HOME": str(self.root),
            "LC_ALL": "C",
            "MOCK_STATE": str(self.state_path),
            "MOCK_LOG": str(self.log_path),
            "MOCK_GRADLE_HOME": str(self.root),
            "GITHUB_EVENT_NAME": event,
            "GITHUB_REF": "refs/heads/main" if dispatch else "refs/tags/" + TAG,
            "GITHUB_REF_NAME": "main" if dispatch else TAG,
            "GITHUB_SHA": SOURCE,
            "GITHUB_REPOSITORY": "example/project",
        }
        self.env.update(changes)

    def set_version(self, version, jobs=("verify", "publish")):
        for job in jobs:
            (self.root / job / "VERSION").write_text(version + "\n")

    def configure(self, **changes):
        state = json.loads(self.state_path.read_text())
        state.update(changes)
        self.state_path.write_text(json.dumps(state))

    def evaluate(self, job, value, step_outputs):
        """`${{ 이름 }}` 하나로 된 값을 workflow 입력, 비밀, 이 job의 step output, needs로 이은 job output으로 푼다."""
        match = re.fullmatch(r"\$\{\{ ([A-Za-z0-9_.-]+) \}\}", value)
        if match is None:
            require("${{" not in value, "unsupported workflow expression: " + value)
            return value
        scope, _, name = match.group(1).partition(".")
        source, separator, output = name.partition(".outputs.")
        if scope == "inputs":
            return self.inputs.get(name, "")
        if scope == "secrets":
            return "mock-secret"
        if match.group(1) == "github.token":
            return "mock-token"
        if scope == "steps" and separator:
            return step_outputs.get(source, {}).get(output, "")
        if scope == "needs" and separator:
            require(source == self.jobs[job].get("needs"), f"{job} reads outputs of {source} without needing it")
            return self.outputs.get(source, {}).get(output, "")
        raise AssertionError("unsupported workflow expression: " + value)

    def applies(self, condition):
        if condition is None:
            return True
        require(condition == DISPATCH_ONLY, "unsupported step condition: " + condition)
        return self.env["GITHUB_EVENT_NAME"] == "workflow_dispatch"

    def run_job(self, job, names=None, before=None):
        """job 하나를 자기 checkout과 step마다 새 GITHUB_ENV·GITHUB_OUTPUT 파일로 실행한다.

        names가 없으면 그 job의 run step을 빠짐없이 순서대로 실행한다. before=(step 이름, 함수)는 그 step 직전에 상태를
        바꾼다. 첫 실패에서 멈추고(GitHub의 기본 success() 조건), 끝까지 성공하면 job outputs를 self.outputs[job]에 둔다.
        """
        definition = self.jobs[job]
        require(set(definition) <= JOB_KEYS, f"{job} has job settings this harness does not model")
        env = dict(self.env, MOCK_JOB=job)
        step_outputs, result = {}, None
        for index, step in enumerate(definition["steps"]):
            if "run" not in step or (names is not None and step.get("name") not in names) or not self.applies(step.get("if")):
                continue
            require(set(step) <= RUN_STEP_KEYS and step.get("shell") == "bash",
                    f"{step.get('name')} uses step settings this harness does not model")
            if before is not None and step.get("name") == before[0]:
                before[1]()
            files = {name: self.root / f"{job}-{index}.{name}" for name in ("env", "output")}
            for path in files.values():
                path.write_text("")
            step_env = dict(env, GITHUB_ENV=str(files["env"]), GITHUB_OUTPUT=str(files["output"]))
            step_env.update({key: self.evaluate(job, value, step_outputs) for key, value in step["env"].items()})
            result = subprocess.run(
                ["bash", "--noprofile", "--norc", "-e", "-o", "pipefail", "-c", step["run"]],
                cwd=self.root / job, env=step_env, text=True, capture_output=True, timeout=30,
            )
            if result.returncode:
                # GitHub은 실패 뒤의 step도 if로 다시 판정한다. 남은 run step이 기본 success() 조건일 때만 여기서 멈춘 것과 같다.
                later = [step.get("if") for step in definition["steps"][index + 1:] if "run" in step]
                require(set(later) <= {None, DISPATCH_ONLY}, f"{job} has a run step that may run after a failed step")
                return result
            env.update(file_commands(files["env"]))
            if "id" in step:
                step_outputs[step["id"]] = file_commands(files["output"])
        require(result is not None, f"{job} ran no run step named {names}")
        self.outputs[job] = {key: self.evaluate(job, value, step_outputs)
                             for key, value in definition.get("outputs", {}).items()}
        return result

    def run_alone(self, job):
        """job 하나만 실행한다. publish job도 verify job의 output 없이 source를 스스로 먼저 확인해야 한다."""
        return self.run_job(job, VERIFY_BLOCKS if job == "verify" else None)

    def pipeline(self, handover=None, before=None, verified=None):
        """verify job이 성공했을 때만 publish job을 새 환경으로 이어 실행한다(needs: verify).

        verified를 주면 같은 기본 dispatch 입력으로 이미 성공한 verify job의 output을 다시 쓴다. handover는 job 사이에서
        verify output을 바꿔 오염된 verify job이 넘길 수 있는 목록을 흉내 내고, before는 publish job 안에서 적용한다.
        """
        if verified is None:
            result = self.run_job("verify", VERIFY_BLOCKS)
            if result.returncode:
                return result
        else:
            self.outputs["verify"] = dict(verified)
        if handover is not None:
            self.outputs["verify"]["artifact-hashes"] = handover(self.outputs["verify"]["artifact-hashes"])
        return self.run_job("publish", before=before)

    def calls(self, job=None):
        rows = [json.loads(line) for line in self.log_path.read_text().split("\n") if line]
        return [row["argv"] for row in rows if job is None or row["job"] == job]

    def writes(self):
        # 이 workflow의 gh 호출은 모두 tag·release 쓰기이고, Portal 게시는 publishPlugins다.
        return [call for call in self.calls() if call[0] == "gh" or call[-1] == ":gradle-plugin:publishPlugins"]

    def builds(self, job):
        return [call for call in self.calls(job)
                if call[0] in ("gradlew", "python3") and call[-1] != ":gradle-plugin:publishPlugins"]


class ReleaseWorkflowTest(unittest.TestCase):
    """두 release job을 실제 shell로 실행해 입력 검증·source 재확인·해시 대조·쓰기 경계가 외부 쓰기를 막는지 확인한다."""

    @classmethod
    def setUpClass(cls):
        # 기본 dispatch 입력의 verify job은 tag를 읽지 않고 원격 상태를 바꾸지 않으며, output은 실행마다 같다
        # (test_published_files_...가 새 실행과 비교한다). publish job만 바꾸는 시나리오는 이 output을 다시 쓴다.
        temporary = tempfile.TemporaryDirectory(prefix="kartograph-release-verified-")
        cls.addClassCleanup(temporary.cleanup)
        release = ReleaseRun(Path(temporary.name))
        result = release.run_job("verify", VERIFY_BLOCKS)
        require(result.returncode == 0, "the default verify job failed: " + result.stderr)
        cls.verified = dict(release.outputs["verify"])

    def setUp(self):
        temporary = tempfile.TemporaryDirectory(prefix="kartograph-release-test-")
        self.addCleanup(temporary.cleanup)
        self.release = ReleaseRun(Path(temporary.name))

    def assert_no_publication(self):
        self.assertFalse([call for call in self.release.writes() if call[:2] == ["gh", "release"] or call[0] == "gradlew"])

    def assert_no_mutation(self):
        self.assertEqual([], self.release.writes())

    def test_release_readiness_reexports_gradle_home_in_its_own_step(self):
        # 앞선 Actions step의 export는 다음 step으로 전달되지 않는다. 기본 환경에는 GRADLE_8_HOME이 없다.
        self.assertNotIn("GRADLE_8_HOME", self.release.env)
        result = self.release.run_job("verify", (READINESS,))
        self.assertEqual(0, result.returncode, result.stderr)

    def test_manual_sha_and_ref_admission_fails_before_external_commands(self):
        release = self.release
        for inputs, changes in (
            ({"source_sha": ""}, {}), ({"source_sha": "1" * 39}, {}),
            ({"source_sha": "A" * 40}, {}), ({"source_sha": OTHER}, {}),
            ({"source_sha": SOURCE + "\nINJECTED=yes"}, {}),
            ({}, {"GITHUB_REF": "refs/heads/feature"}), ({}, {"GITHUB_REF": "refs/tags/" + TAG}),
        ):
            for job in ("verify", "publish"):
                with self.subTest(inputs=inputs, changes=changes, job=job):
                    release.reset(inputs=inputs, **changes)
                    self.assertNotEqual(0, release.run_alone(job).returncode)
                    self.assertEqual([], release.calls())

    def test_wrong_tag_version_or_checkout_blocks_publication(self):
        release = self.release
        for inputs, state, version in (
            ({"tag": "1.2.3"}, {}, "1.2.3"),
            ({"tag": TAG + "\nINJECTED=yes"}, {}, "1.2.3"),
            ({}, {}, "1.2.4"), ({}, {"head": OTHER}, "1.2.3"),
            ({}, {"main": OTHER}, "1.2.3"),
        ):
            for job in ("verify", "publish"):
                with self.subTest(inputs=inputs, state=state, version=version, job=job):
                    release.reset(inputs=inputs)
                    release.configure(**state)
                    release.set_version(version)
                    self.assertNotEqual(0, release.run_alone(job).returncode)
                    self.assert_no_mutation()
                    self.assertEqual([], release.builds(job))

    def test_wrong_push_ref_or_event_fails_closed(self):
        release = self.release
        for event, ref in (("push", "refs/heads/main"), ("schedule", "refs/tags/" + TAG)):
            for job in ("verify", "publish"):
                with self.subTest(event=event, ref=ref, job=job):
                    release.reset(event=event, GITHUB_REF=ref)
                    self.assertNotEqual(0, release.run_alone(job).returncode)
                    self.assert_no_mutation()
                    self.assertEqual([], release.builds(job))

    def test_publish_job_resolves_its_own_source_after_verification(self):
        # GITHUB_ENV는 job을 건너가지 않는다. verify 뒤에 바뀐 source를 publish job이 배포물을 만들기 전에 거부한다.
        release = self.release
        for change in ("head", "main", "version"):
            with self.subTest(change=change):
                release.reset()
                if change == "version":
                    drift = lambda: release.set_version("1.2.4", jobs=("publish",))
                else:
                    drift = lambda change=change: release.configure(**{change: OTHER})
                self.assertNotEqual(0, release.pipeline(before=(RESOLVE, drift), verified=self.verified).returncode)
                self.assert_no_mutation()
                self.assertEqual([], release.builds("publish"))

    def test_absent_manual_tag_is_created_once_before_publication(self):
        result = self.release.pipeline()
        self.assertEqual(0, result.returncode, result.stderr)
        writes = self.release.writes()
        self.assertEqual(["gh", "api", "--method", "POST", "repos/example/project/git/refs",
                          "-f", "ref=refs/tags/" + TAG, "-f", "sha=" + SOURCE], writes[0])
        self.assertEqual(["gh", "release", "create", TAG, "--verify-tag", "--generate-notes",
                          "--title", "kartograph " + TAG], writes[1])
        self.assertEqual(["gh", "release", "upload", TAG, *UPLOADS], writes[2])
        self.assertEqual(["gradlew", "--no-daemon", ":gradle-plugin:publishPlugins"], writes[3])
        self.assertEqual(4, len(writes))

    def test_published_files_are_rebuilt_bytes_equal_to_the_verified_artifacts(self):
        release = self.release
        result = release.pipeline()
        self.assertEqual(0, result.returncode, result.stderr)
        self.assertEqual(self.verified, release.outputs["verify"])
        pairs = [line.split("  ", 1) for line in release.outputs["verify"]["artifact-hashes"].split("\n")]
        self.assertEqual(list(ARTIFACTS), [path for _, path in pairs])
        verified = {path: digest for digest, path in pairs}
        state = json.loads(release.state_path.read_text())
        self.assertEqual({path: verified[path] for path in UPLOADS}, state["uploaded"])
        self.assertEqual(verified[PLUGIN_JAR], state["published_plugin"])
        # publish job은 verify job의 파일을 받지 않고 자기 checkout에서 루트·compiler-collectors·metadata만 다시 만든다.
        self.assertEqual(3, len(release.builds("publish")))

    def test_handed_over_hash_list_must_match_the_rebuilt_artifacts_exactly(self):
        def edit_line(index, change):
            def handover(value):
                lines = value.split("\n")
                lines[index] = change(lines[index])
                return "\n".join(lines)
            return handover

        def lines(value):
            return value.split("\n")

        malformed, unexpected, different = ("malformed artifact hash list", "does not name exactly the release artifacts",
                                            "differ from the verified artifacts")
        cases = {
            "changed digest": (edit_line(2, lambda line: ("0" if line[0] != "0" else "1") + line[1:]), None, different),
            "drifted rebuild": (None, ["publish", PLUGIN_JAR], different),
            "missing entry": (lambda value: "\n".join(lines(value)[:2] + lines(value)[3:]), None, unexpected),
            "extra entry": (lambda value: value + "\n" + "f" * 64 + "  build/release/extra.jar", None, unexpected),
            "reordered entries": (lambda value: "\n".join([lines(value)[1], lines(value)[0], *lines(value)[2:]]),
                                  None, unexpected),
            "foreign path": (edit_line(7, lambda line: line.replace("  build/", "  /build/")), None, unexpected),
            "empty list": (lambda value: "", None, malformed),
            "uppercase digest": (edit_line(0, lambda line: line[:64].upper() + line[64:]), None, malformed),
            "binary marker": (edit_line(0, lambda line: line.replace("  ", " *", 1)), None, malformed),
            "carriage return": (edit_line(0, lambda line: line + "\r"), None, malformed),
            "trailing newline": (lambda value: value + "\n", None, malformed),
            "workflow command": (lambda value: "::error::handed-over-marker\n" + value, None, malformed),
        }
        release = self.release
        for case, (handover, drift, message) in cases.items():
            with self.subTest(case=case):
                release.reset()
                if drift is not None:
                    release.configure(rebuild_drift=drift)
                result = release.pipeline(handover=handover, verified=self.verified)
                self.assertNotEqual(0, result.returncode)
                self.assertIn(message, result.stderr)
                self.assert_no_mutation()
                # 다시 빌드하기 전이 아니라, 다시 빌드한 뒤의 비교에서 멈췄다.
                self.assertEqual(3, len(release.builds("publish")))
                # 넘겨받은 값은 workflow 명령으로 해석될 수 있어 로그에 나오지 않는다.
                self.assertNotIn("handed-over-marker", result.stdout + result.stderr)

    def test_verify_job_hands_over_only_artifacts_readiness_sealed(self):
        release = self.release

        def change_after_readiness():
            (release.root / "verify" / PLUGIN_JAR).write_text("changed after readiness\n")
        result = release.run_job("verify", VERIFY_BLOCKS, before=(RECORD, change_after_readiness))
        self.assertNotEqual(0, result.returncode)
        self.assertIn("changed after release readiness sealed them", result.stderr)
        self.assertNotIn("verify", release.outputs)

    def test_existing_lightweight_or_annotated_tag_is_never_recreated(self):
        release = self.release
        for tag_object in (SOURCE, OTHER):
            with self.subTest(tag_object=tag_object):
                release.reset()
                release.configure(tag=SOURCE, tag_object=tag_object)
                result = release.pipeline(verified=self.verified)
                self.assertEqual(0, result.returncode, result.stderr)
                self.assertFalse([call for call in release.calls() if call[:2] == ["gh", "api"]])

    def test_existing_different_tag_and_missing_pushed_tag_are_not_changed(self):
        release = self.release
        for event, tag in (("workflow_dispatch", OTHER), ("push", None)):
            with self.subTest(event=event, tag=tag):
                release.reset(event=event)
                release.configure(tag=tag)
                self.assertNotEqual(0, release.pipeline().returncode)
                self.assert_no_mutation()

    def test_historical_tag_push_does_not_require_current_main(self):
        release = self.release
        release.reset(event="push")
        release.configure(main=OTHER, tag=SOURCE)
        result = release.pipeline()
        self.assertEqual(0, result.returncode, result.stderr)
        self.assertFalse([call for call in release.calls() if call[:2] == ["gh", "api"] or call[-1] == "refs/heads/main"])

    def test_uncertain_ref_reads_never_authorize_writes(self):
        release = self.release
        main_states = ({"lookup_failure": "refs/heads/main"}, {"main": None})
        for state in (*main_states, {"lookup_failure": "refs/tags/" + TAG}, {"tag": SOURCE, "fetch_failure": True}):
            # verify job은 tag를 읽지 않는다. main 읽기는 verify job과 publish job이 각자 다시 한다.
            for stage in ("verify", "publish") if state in main_states else ("publish",):
                with self.subTest(state=state, stage=stage):
                    release.reset()
                    if stage == "verify":
                        release.configure(**state)
                        result = release.pipeline()
                    else:
                        result = release.pipeline(before=(RESOLVE, lambda state=state: release.configure(**state)),
                                                  verified=self.verified)
                    self.assertNotEqual(0, result.returncode)
                    self.assert_no_mutation()

    def test_tag_creation_failure_blocks_all_publication(self):
        release = self.release
        release.configure(create_tag_failure=True)
        self.assertNotEqual(0, release.pipeline(verified=self.verified).returncode)
        self.assertEqual(1, len([call for call in release.calls() if call[:2] == ["gh", "api"]]))
        self.assert_no_publication()

    def test_existing_or_failed_release_blocks_upload_and_portal(self):
        release = self.release
        for state in ({"release_exists": True}, {"create_release_failure": True}):
            with self.subTest(state=state):
                release.reset()
                release.configure(tag=SOURCE, **state)
                self.assertNotEqual(0, release.pipeline(verified=self.verified).returncode)
                self.assertFalse([call for call in release.writes()
                                  if call[:3] == ["gh", "release", "upload"] or call[0] == "gradlew"])

    def test_failed_upload_blocks_portal(self):
        release = self.release
        release.configure(tag=SOURCE, upload_failure=True)
        self.assertNotEqual(0, release.pipeline(verified=self.verified).returncode)
        self.assertFalse([call for call in release.writes() if call[0] == "gradlew"])

    def test_drift_before_each_write_boundary_fails_closed(self):
        release = self.release
        for boundary in WRITE_BOUNDARIES:
            for drift in ("head", "main", "tag", "version"):
                with self.subTest(boundary=boundary, drift=drift):
                    release.reset()
                    release.configure(tag=SOURCE)
                    marks = []

                    def change(drift=drift, marks=marks):
                        marks.append(len(release.calls()))
                        if drift == "version":
                            release.set_version("1.2.4", jobs=("publish",))
                        else:
                            release.configure(**{drift: OTHER})
                    self.assertNotEqual(0, release.pipeline(before=(boundary, change), verified=self.verified).returncode)
                    self.assertEqual(1, len(marks))
                    subsequent = release.calls()[marks[0]:]
                    self.assertFalse([call for call in subsequent
                                      if call[0] == "gh" or call[-1] == ":gradle-plugin:publishPlugins"])


if __name__ == "__main__":
    unittest.main()
