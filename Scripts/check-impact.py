#!/usr/bin/env python3
"""Git 변경 경로를 공통 impact CLI에 전달한다. snapshot 갱신과 빌드는 CI의 선행 단계다."""
import argparse
import json
import os
from pathlib import Path
import subprocess
import sys
import tempfile


class UsageParser(argparse.ArgumentParser):
    def error(self, message):
        raise ValueError("invalid arguments")


def main():
    parser = UsageParser(description="Report commit impact using matching base/current snapshots.")
    parser.add_argument("--binary", required=True)
    parser.add_argument("--project", default=".")
    parser.add_argument("--base", required=True)
    parser.add_argument("--base-graph", required=True)
    parser.add_argument("--graph-file", required=True)
    parser.add_argument("--depth", type=int, default=100)
    parser.add_argument("--limit", type=int, default=500)
    parser.add_argument("--timeout", type=int, default=120)
    parser.add_argument("--strict", action="store_true", help="exit 1 when the traversal or selection is incomplete")
    args = parser.parse_args()
    if args.timeout < 1 or not 1 <= args.depth <= 1000 or not 1 <= args.limit <= 100000:
        raise ValueError("invalid traversal or timeout budget")
    project = Path(args.project).resolve(strict=True)
    binary = Path(args.binary).resolve(strict=True)
    current_graph = Path(args.graph_file).resolve(strict=True)
    base_graph = Path(args.base_graph).resolve(strict=True)
    env = {key: value for key, value in os.environ.items() if not key.startswith("GIT_")}
    env.update(GIT_CONFIG_NOSYSTEM="1", GIT_CONFIG_GLOBAL=os.devnull, GIT_NO_REPLACE_OBJECTS="1", GIT_OPTIONAL_LOCKS="0")

    def git(*arguments):
        result = subprocess.run(["git", "-C", str(project), *arguments], env=env, capture_output=True, timeout=30)
        if result.returncode:
            raise RuntimeError("unable to read committed Git inputs; verify the repository and base commit")
        return result.stdout.decode("utf-8")

    if Path(git("rev-parse", "--show-toplevel").strip()).resolve() != project:
        raise ValueError("project must be the Git repository root")
    base = git("rev-parse", "--verify", "--end-of-options", args.base + "^{commit}").strip()
    current = git("rev-parse", "--verify", "HEAD^{commit}").strip()
    if git("diff", "--name-only", "--no-ext-diff", "--no-textconv", "HEAD", "--"):
        raise RuntimeError("tracked working tree changes are not represented by HEAD; commit them before CI impact checks")
    # rename을 D/A로 표현하면 이전 경로와 이후 경로가 모두 남고 공백/탭은 NUL 구분으로 보존된다.
    files = git("diff", "--name-only", "-z", "--no-renames", "--no-ext-diff", "--no-textconv", base, current, "--").split("\0")
    files = sorted(set(files) - {""})
    with tempfile.TemporaryDirectory(prefix="kartograph-impact-") as directory:
        paths = Path(directory) / "files.json"
        paths.write_text(json.dumps(files), encoding="utf-8")
        result = subprocess.run([str(binary), "impact", "--files-from", str(paths), "--graph-file", str(current_graph),
            "--base-graph", str(base_graph), "--revision", current, "--base-revision", base,
            "--depth", str(args.depth), "--limit", str(args.limit)], cwd=project, env=env,
            capture_output=True, text=True, timeout=args.timeout)
    if result.returncode not in (0, 64) or not result.stdout:
        raise RuntimeError("impact analysis failed; check snapshot revisions, scope, analyzer version and input files")
    try:
        report = json.loads(result.stdout)
    except ValueError:
        raise RuntimeError("impact CLI returned invalid JSON") from None
    if not isinstance(report, dict) or report.get("format") != "kartograph-impact" or report.get("version") != 1 or report.get("status") not in ("found", "partial", "notFound", "noChanges"):
        raise RuntimeError("unexpected impact report; use a compatible CLI")
    inputs = report.get("inputs")
    if not isinstance(inputs, dict) or not all(isinstance(inputs.get(side), dict) and inputs[side].get("scope") for side in ("base", "current")):
        raise RuntimeError("CI impact requires snapshots labeled with --scope project:variant")
    sys.stdout.write(result.stdout)
    if args.strict and report["status"] in ("partial", "notFound"):
        return 1
    return result.returncode


if __name__ == "__main__":
    try:
        raise SystemExit(main())
    except ValueError:
        print("error: invalid impact options or report data", file=sys.stderr)
        raise SystemExit(64)
    except (OSError, RuntimeError, subprocess.TimeoutExpired) as error:
        message = str(error) if isinstance(error, RuntimeError) else "impact inputs unavailable or command timed out"
        print("error: " + message, file=sys.stderr)
        raise SystemExit(2)
