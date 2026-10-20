#!/usr/bin/env python3
"""Git 변경 경로를 공통 impact CLI에 전달한다. snapshot 갱신과 빌드는 CI의 선행 단계다."""
import argparse
import json
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
# partial clone(promisor 원격)이 빠진 객체를 받는 lazy fetch는 저장소 config의 원격 전송 명령(remote.<이름>.uploadpack 등)을
# 실행한다(설계 결정 D1). 빈 GIT_ALLOW_PROTOCOL은 어떤 전송도 허용하지 않고 저장소의 protocol.*.allow보다 우선하며(git 2.6.1+),
# GIT_NO_LAZY_FETCH=1(git 2.44+)은 lazy fetch를 띄우지 않는다. 그런 객체가 필요한 git은 받지 않고 실패한다.
GIT_TRANSPORT_ISOLATION = {"GIT_ALLOW_PROTOCOL": "", "GIT_NO_LAZY_FETCH": "1"}
# partial clone에서 git이 실패했을 때 오류에 덧붙이는 원인과 해결 방향이다.
PARTIAL_CLONE_HINT = (" (this partial clone lacks Git objects that kartograph does not fetch, because fetching runs commands "
                      "from the repository configuration; fetch them in a trusted step first or clone without --filter)")


class UsageParser(argparse.ArgumentParser):
    def error(self, message):
        raise ValueError("invalid arguments")


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
            raise RuntimeError("the repository Git configuration defines a filter driver whose name cannot be safely "
                               "overridden; rename or remove that driver")
        names.add(name.decode("ascii"))
    return sorted(names)


def configuration_keys(project, environment):
    """분석용 git이 읽을 설정의 키 이름(`git config -z --name-only --list` 출력 bytes)을 같은 격리로 읽는다."""
    query = subprocess.run(["git", *GIT_ISOLATION, "-C", str(project), "config", "-z", "--name-only", "--includes", "--list"],
                           env=environment, capture_output=True, timeout=30)
    if query.returncode:
        raise RuntimeError("cannot read the repository Git configuration to disable its filter drivers; verify the repository config")
    return query.stdout


def is_partial_clone(keys):
    """설정 키에 promisor 원격(partial clone)을 만드는 키가 있는지다.

    git은 extensions.partialClone과 remote.<이름>.promisor·remote.<이름>.partialCloneFilter로 promisor 원격을 정한다.
    키의 section·변수 이름은 소문자로 나온다. 값은 보지 않는다. 결과는 오류 안내 문구만 고른다.
    """
    return any(key == b"extensions.partialclone" or key.startswith(b"remote.") and key.endswith((b".promisor", b".partialclonefilter"))
               for key in keys.split(b"\0"))


def partial_clone_hint(project, environment):
    """실패한 git이 받지 않은 객체 때문일 수 있으면 오류에 덧붙일 안내다. git이 실패한 뒤에만 부른다."""
    try:
        return PARTIAL_CLONE_HINT if is_partial_clone(configuration_keys(project, environment)) else ""
    except (OSError, RuntimeError, subprocess.TimeoutExpired):
        # 안내 문구만 고르는 조회다. 설정을 다시 읽지 못하면 안내 없이 원래 실패만 알린다.
        return ""


def git_command(project, arguments, environment):
    """분석 대상 설정의 filter·fsmonitor·서명 확인 명령을 끈 git argv다.

    호출마다 바로 앞에서 같은 설정·디렉터리로 드라이버 이름을 다시 모아, 이름마다 세 명령을 빈 값으로, required를 false로
    덮는다. git은 빈 명령을 실행하지 않는다. 원격 전송은 environment의 GIT_TRANSPORT_ISOLATION이 막는다.
    """
    overrides = [option for name in filter_driver_names(configuration_keys(project, environment))
                 for option in ("-c", "filter." + name + ".clean=", "-c", "filter." + name + ".smudge=",
                                "-c", "filter." + name + ".process=", "-c", "filter." + name + ".required=false")]
    return ["git", *GIT_ISOLATION, *overrides, "-C", str(project), *arguments]


def main():
    parser = UsageParser(description="Report commit impact using matching base/current snapshots.")
    parser.add_argument("--binary", required=True)
    parser.add_argument("--project", default=".")
    parser.add_argument("--base", required=True)
    parser.add_argument("--base-graph", required=True)
    parser.add_argument("--graph-file", required=True)
    parser.add_argument("--base-project", help="checkout containing the captured base inputs; otherwise base freshness is unverified")
    parser.add_argument("--input", action="append", default=[], help="current external/slot=path freshness binding")
    parser.add_argument("--base-input", action="append", default=[], help="base external/slot=path freshness binding")
    parser.add_argument("--input-bindings", help="generated local external-input binding file for the current checkout")
    parser.add_argument("--base-input-bindings", help="generated local external-input binding file for the base checkout")
    parser.add_argument("--depth", type=int, default=100)
    parser.add_argument("--limit", type=int, default=500)
    parser.add_argument("--snapshot-max-mib", type=int, action="append",
                        help="saved snapshot read limit, 1..128 MiB (omitted: compatible CLI default, 64 MiB)")
    parser.add_argument("--timeout", type=int, default=120)
    parser.add_argument("--strict", action="store_true", help="exit 1 when traversal is incomplete or freshness is stale/unverified")
    args = parser.parse_args()
    if not args.base_project and (args.base_input or args.base_input_bindings):
        raise ValueError("base input bindings require --base-project")
    snapshot_limits = args.snapshot_max_mib if args.snapshot_max_mib is not None else [64]
    if len(snapshot_limits) != 1 or not 1 <= snapshot_limits[0] <= 128:
        raise ValueError("snapshot maximum must be one integer from 1 to 128 MiB")
    snapshot_limit = snapshot_limits[0]
    snapshot_options = ["--snapshot-max-mib", str(snapshot_limit)] if args.snapshot_max_mib is not None else []
    if args.timeout < 1 or not 1 <= args.depth <= 1000 or not 1 <= args.limit <= 100000:
        raise ValueError("invalid traversal or timeout budget")
    project = Path(args.project).resolve(strict=True)
    binary = Path(args.binary).resolve(strict=True)
    current_graph = Path(args.graph_file).resolve(strict=True)
    base_graph = Path(args.base_graph).resolve(strict=True)
    env = {key: value for key, value in os.environ.items() if not key.startswith("GIT_")}
    env.update(GIT_CONFIG_NOSYSTEM="1", GIT_CONFIG_GLOBAL=os.devnull, GIT_NO_REPLACE_OBJECTS="1", GIT_OPTIONAL_LOCKS="0",
               **GIT_TRANSPORT_ISOLATION)

    def git(*arguments):
        result = subprocess.run(git_command(project, arguments, env), env=env, capture_output=True, timeout=30)
        if result.returncode:
            raise RuntimeError("unable to read committed Git inputs; verify the repository and base commit" + partial_clone_hint(project, env))
        return result.stdout.decode("utf-8")

    if Path(git("rev-parse", "--show-toplevel").strip()).resolve() != project:
        raise ValueError("project must be the Git repository root")
    base = git("rev-parse", "--verify", "--end-of-options", args.base + "^{commit}").strip()
    current = git("rev-parse", "--verify", "HEAD^{commit}").strip()
    # diff.autoRefreshIndex=false: stat만 바뀐 파일의 내용을 다시 읽어 걸러 내며 index를 고쳐 쓰는(그때 저장소 hook이 도는)
    # 단계를 끈다. 그런 파일과 filter로 정규화되던 파일(git-lfs 등)은 변경으로 남는다(fail-safe).
    # --ignore-submodules=dirty: 서브모듈 안에서 git을 띄우지 않는다. 서브모듈이 가리키는 커밋의 변화는 계속 본다.
    # --no-renames: 변경이 있는지만 보므로 rename 판정은 결과를 바꾸지 않는다. 빼면 blob을 읽지 않아 partial clone에서도 받지
    # 않은 객체 없이 판정한다.
    if git("-c", "diff.autoRefreshIndex=false", "diff", "--name-only", "--no-renames", "--no-ext-diff", "--no-textconv",
           "--ignore-submodules=dirty", "HEAD", "--"):
        raise RuntimeError("tracked working tree changes are not represented by HEAD; commit them before CI impact checks "
                           "(if only timestamps changed or a Git filter such as git-lfs normalizes the files, "
                           "run git update-index --refresh in the trusted checkout first)")
    # rename을 D/A로 표현하면 이전 경로와 이후 경로가 모두 남고 공백/탭은 NUL 구분으로 보존된다.
    files = git("diff", "--name-only", "-z", "--no-renames", "--no-ext-diff", "--no-textconv", base, current, "--").split("\0")
    files = sorted(set(files) - {""})
    with tempfile.TemporaryDirectory(prefix="kartograph-impact-") as directory:
        paths = Path(directory) / "files.json"
        paths.write_text(json.dumps(files), encoding="utf-8")
        result = subprocess.run([str(binary), "impact", "--files-from", str(paths), "--graph-file", str(current_graph),
            "--base-graph", str(base_graph), "--revision", current, "--base-revision", base,
            "--depth", str(args.depth), "--limit", str(args.limit)] + snapshot_options, cwd=project, env=env,
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

    def verify(graph, checkout, bindings, scope, binding_file=None):
        command = [str(binary), "verify-snapshot", "--graph-file", str(graph), "--project", str(checkout),
                   "--scope", scope] + snapshot_options
        for binding in bindings:
            command.extend(["--input", binding])
        if binding_file:
            command.extend(["--input-bindings", str(Path(binding_file).resolve(strict=True))])
        checked = subprocess.run(command, cwd=checkout, env=env, capture_output=True, text=True, timeout=args.timeout)
        if checked.returncode not in (0, 1):
            raise RuntimeError("snapshot freshness verification failed; check evidence and external input bindings")
        try:
            document = json.loads(checked.stdout)
        except ValueError:
            raise RuntimeError("freshness CLI returned invalid JSON") from None
        if not isinstance(document, dict) or document.get("format") != "kartograph-freshness" or document.get("version") != 1 or document.get("status") not in ("matched", "stale", "unverified") or (checked.returncode == 0) != (document["status"] == "matched"):
            raise RuntimeError("unexpected freshness report; use a compatible CLI")
        return document

    report["freshness"] = {
        "current": verify(current_graph, project, args.input, inputs["current"]["scope"], args.input_bindings),
        "base": verify(base_graph, Path(args.base_project).resolve(strict=True), args.base_input, inputs["base"]["scope"], args.base_input_bindings) if args.base_project else
            {"status": "unverified", "reasons": ["base-checkout-not-supplied"]},
    }
    sys.stdout.write(json.dumps(report, ensure_ascii=True, sort_keys=True) + "\n")
    if args.strict and (report["status"] in ("partial", "notFound") or any(item["status"] != "matched" for item in report["freshness"].values())):
        return 1
    # 유효한 보고서의 불완전성은 JSON에 남긴다. 기본 CI는 보고용이며 strict만 진단 실패로 만든다.
    return 0


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
