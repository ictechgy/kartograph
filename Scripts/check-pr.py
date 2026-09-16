#!/usr/bin/env python3
"""기준 commit의 baseline으로 전체 PR 그래프를 검사한다. 네트워크와 빌드는 호출자가 소유한다."""

import argparse
import os
from pathlib import Path
import subprocess
import sys
import tempfile


class UsageError(Exception):
    """입력값을 오류에 재출력하지 않고 사용 오류를 구분한다."""


class Parser(argparse.ArgumentParser):
    def error(self, message):
        raise UsageError("invalid arguments; use --help and put dead options after --")


def git_output(project, *arguments, failure_message):
    """Git의 raw stderr에 ref·경로를 노출하지 않는다."""
    git_environment = {key: value for key, value in os.environ.items() if not key.startswith("GIT_")}
    result = subprocess.run(["git", "-C", str(project), *arguments], capture_output=True, timeout=30, env=git_environment)
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
        baseline = git_output(project, "show", commit + ":" + baseline_path,
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
