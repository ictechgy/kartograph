#!/usr/bin/env python3
"""기준 commit의 baseline으로 전체 PR 그래프를 검사한다. 네트워크와 빌드는 호출자가 소유한다."""

import argparse
import os
from pathlib import Path
import re
import subprocess
import sys
import tempfile

# 분석 대상 저장소 설정이 정한 명령(fsmonitor 훅, log 계열의 서명 확인)을 모든 git 호출에서 끈다(설계 결정 D1).
GIT_ISOLATION = ("-c", "core.fsmonitor=false", "-c", "log.showSignature=false")
# -c로 넘겨도 같은 이름이 되는 filter 드라이버 이름이다(빈 이름 포함). '='는 git이 -c 값을 나누는 자리라 덮을 수 없고,
# 비ASCII·공백·따옴표는 argv 인코딩과 인용에 따라 다른 이름이 될 수 있다.
OVERRIDABLE_FILTER_DRIVER = re.compile(rb"[A-Za-z0-9._+-]*")
FILTER_DRIVER_COMMANDS = (b"clean", b"smudge", b"process")


class UsageError(Exception):
    """입력값을 오류에 재출력하지 않고 사용 오류를 구분한다."""


class Parser(argparse.ArgumentParser):
    def error(self, message):
        raise UsageError("invalid arguments; use --help and put dead options after --")


def filter_driver_names(keys):
    """`git config -z --name-only --list` 출력(bytes)에서 명령을 가진 filter 드라이버 이름을 모은다.

    `filter.`과 마지막 `.` 사이 전체(점·빈 이름 포함)가 이름이다. 덮을 수 없는 이름이 있으면 git을 실행하지 않도록
    멈춘다(fail-closed). 신뢰하지 않는 설정 값이므로 이름 원문은 오류에 싣지 않는다.
    """
    names = set()
    for key in keys.split(b"\0"):
        if not key.startswith(b"filter."):
            continue
        name, dot, command = key[len(b"filter."):].rpartition(b".")
        if not dot or command not in FILTER_DRIVER_COMMANDS:
            continue
        if not OVERRIDABLE_FILTER_DRIVER.fullmatch(name):
            raise RuntimeError("the Git configuration defines a filter driver whose name cannot be safely overridden; "
                               "rename or remove that driver")
        names.add(name.decode("ascii"))
    return sorted(names)


def git_command(project, arguments, environment):
    """분석 대상 설정의 filter·fsmonitor·서명 확인 명령을 끈 git argv다.

    호출마다 바로 앞에서 같은 설정·디렉터리로 드라이버 이름을 다시 모아, 이름마다 세 명령을 빈 값으로, required를 false로
    덮는다. git은 빈 명령을 실행하지 않는다.
    """
    query = subprocess.run(["git", *GIT_ISOLATION, "-C", str(project), "config", "-z", "--name-only", "--includes", "--list"],
                           capture_output=True, timeout=30, env=environment)
    if query.returncode:
        raise RuntimeError("cannot read the Git configuration to disable its filter drivers; verify the repository config")
    overrides = [option for name in filter_driver_names(query.stdout)
                 for option in ("-c", "filter." + name + ".clean=", "-c", "filter." + name + ".smudge=",
                                "-c", "filter." + name + ".process=", "-c", "filter." + name + ".required=false")]
    return ["git", *GIT_ISOLATION, *overrides, "-C", str(project), *arguments]


def git_output(project, *arguments, failure_message):
    """Git의 raw stderr에 ref·경로를 노출하지 않는다. 분석 대상 설정의 명령은 실행하지 않는다(git_command)."""
    git_environment = {key: value for key, value in os.environ.items() if not key.startswith("GIT_")}
    result = subprocess.run(git_command(project, arguments, git_environment), capture_output=True, timeout=30,
                            env=git_environment)
    if result.returncode:
        raise RuntimeError(failure_message)
    return result.stdout


def main(arguments=None):
    """CLI의 0/1/2/64 계약을 보존하면서 PR 쪽 baseline 덮어쓰기를 막는다."""
    parser = Parser(description=__doc__, allow_abbrev=False)
    parser.add_argument("--binary", required=True, help="trusted kartograph executable path")
    parser.add_argument("--project", required=True, help="Git repository root containing the compiled project")
    parser.add_argument("--base", required=True, help="fetched base commit SHA or ref")
    parser.add_argument("--baseline-path", default=".kartograph-baseline.json", help="repository-relative base baseline")
    parser.add_argument("--timeout", type=int, default=300, help="positive CLI timeout in seconds (default: 300)")
    parser.add_argument("dead_options", nargs=argparse.REMAINDER)
    try:
        options = parser.parse_args(arguments)
        forwarded = options.dead_options
        if not forwarded or forwarded[0] != "--":
            raise UsageError("pass dead input options after --")
        forwarded = forwarded[1:]
        if options.timeout <= 0:
            raise UsageError("timeout must be a positive number of seconds")
        # 새 CLI 옵션이 생겨도 검증되지 않은 우회 경로를 자동으로 허용하지 않는다.
        value_options = {"--classes", "--manifest", "--resources", "--namespace", "--keep-rules",
                         "--classpath", "--service-resources", "--report-format", "--test-classes", "--generated-classes"}
        flag_options = {"--strict", "--include-private-members"}
        index = 0
        while index < len(forwarded):
            option = forwarded[index]
            if option in flag_options:
                index += 1
            elif option in value_options and index + 1 < len(forwarded) and not forwarded[index + 1].startswith("-"):
                index += 2
            else:
                raise UsageError("unsupported or incomplete dead input option; gate overrides are not allowed")
        baseline_path = options.baseline_path
        if (not baseline_path or baseline_path.startswith("/") or "\\" in baseline_path
                or any(part in {"", ".", ".."} for part in baseline_path.split("/"))):
            raise UsageError("baseline path must be a normalized repository-relative file")
        project = Path(options.project).resolve(strict=True)
        binary = Path(options.binary).resolve(strict=True)
        repository = Path(git_output(project, "rev-parse", "--show-toplevel",
                                     failure_message="cannot locate Git repository; pass its root as project").decode().strip()).resolve()
        if repository != project:
            raise UsageError("project must be the Git repository root")
        commit = git_output(project, "rev-parse", "--verify", "--end-of-options",
                            options.base + "^{commit}",
                            failure_message="cannot resolve base commit; fetch the required history first").decode().strip()
        baseline = git_output(project, "show", "--no-ext-diff", "--no-textconv", commit + ":" + baseline_path,
                              failure_message="cannot read base baseline; commit the baseline at the selected base path first")
        with tempfile.TemporaryDirectory(prefix="kartograph-pr-") as temporary:
            baseline_file = Path(temporary) / "baseline.json"
            baseline_file.write_bytes(baseline)
            print("kartograph: checking full graph against base baseline", file=sys.stderr)
            result = subprocess.run(
                [str(binary), "dead", *forwarded, "--project", str(project),
                 "--baseline", str(baseline_file), "--strict"], cwd=project, timeout=options.timeout,
                env={key: value for key, value in os.environ.items() if not key.startswith("GIT_")},
            )
            return result.returncode if result.returncode in {0, 1, 2, 64} else 2
    except UsageError as error:
        print("error: " + str(error), file=sys.stderr)
        return 64
    except RuntimeError as error:
        print("error: " + str(error), file=sys.stderr)
        return 2
    except subprocess.TimeoutExpired:
        print("error: PR check timed out; verify inputs or increase --timeout for a larger graph", file=sys.stderr)
        return 2
    except (OSError, ValueError):
        print("error: cannot run PR check; verify executable, Git history and filesystem permissions", file=sys.stderr)
        return 2


if __name__ == "__main__":
    sys.exit(main())
