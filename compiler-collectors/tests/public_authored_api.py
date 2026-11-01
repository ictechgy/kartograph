#!/usr/bin/env python3
"""설치한 공개 analysis/core JAR만 쓰는 외부 소비자를 독립 compiler module로 검증한다."""

from __future__ import annotations

import argparse
import hashlib
import json
import os
from pathlib import Path
import re
import subprocess
import tempfile
import zipfile

HERE = Path(__file__).resolve().parent
EXPECTED = "PUBLIC_AUTHORED_API complete=COMPLETE incomplete=INCOMPLETE missing=UNKNOWN invalid=REJECTED immutable=YES\n"


def sha256(path: Path) -> str:
    digest = hashlib.sha256()
    with path.open("rb") as stream:
        for chunk in iter(lambda: stream.read(1024 * 1024), b""):
            digest.update(chunk)
    return digest.hexdigest()


def exact_jar(paths: list[Path], family: str, version: str) -> Path:
    candidates = [path for path in paths if path.name.startswith(family + "-")]
    if len(candidates) != 1 or candidates[0].name != f"{family}-{version}.jar" or not candidates[0].is_file():
        raise AssertionError(f"exactly one {family}-{version}.jar is required")
    return candidates[0]


def distribution_jars(distribution: str, version: str, stdlib_version: str) -> list[Path]:
    if not distribution or not Path(distribution).is_dir():
        raise AssertionError("explicit built distribution is required; use -Pkartograph.consumerDistribution=<installDist>")
    root = Path(distribution).resolve()
    if not (root / "VERSION").is_file() or (root / "VERSION").read_text().strip() != version:
        raise AssertionError("installed distribution VERSION does not match the requested product version")
    paths = list((root / "lib").glob("*.jar"))
    jars = [exact_jar(paths, "analysis", version), exact_jar(paths, "core", version),
            exact_jar(paths, "kotlin-stdlib", stdlib_version)]
    for path, required in zip(jars, ("dev/kartograph/analysis/AuthoredCallUniverse.class",
                                      "dev/kartograph/core/CodeGraph.class", "kotlin/Unit.class")):
        if path.is_symlink() or path.resolve().parent != root / "lib":
            raise AssertionError("consumer JAR must be a regular file inside the explicit distribution")
        with zipfile.ZipFile(path) as archive:
            if archive.namelist().count(required) != 1:
                raise AssertionError(f"installed {path.name} is missing its required public API class")
    return jars


def normalize_command(command: list[str], path_tokens: dict[str, str], work: Path) -> list[str]:
    def path(value: str) -> str:
        if value in path_tokens:
            return path_tokens[value]
        candidate = Path(value)
        if candidate.is_absolute() and candidate.resolve().is_relative_to(work.resolve()):
            return "@work/" + candidate.resolve().relative_to(work.resolve()).as_posix()
        raise ValueError("command contains an unknown path")

    normalized = []
    for index, argument in enumerate(command):
        if index > 0 and command[index - 1] in ("-cp", "-classpath"):
            normalized.append(os.pathsep.join(path(part) for part in argument.split(os.pathsep)))
        elif index == 0 or argument in path_tokens or "/" in argument or "\\" in argument or os.pathsep in argument:
            normalized.append(path(argument))
        else:
            normalized.append(argument)
    return normalized


def publish(report: Path, status: str, inputs: dict, commands: list[dict], **extra) -> None:
    # 공개 CI artifact에는 machine-local 절대경로나 classpath를 쓰지 않는다.
    outcomes = [{key: value for key, value in command.items() if key != "command"} for command in commands]
    raw_attempt = report / "attempt.json"
    (report / "public-report.json").write_text(json.dumps(
        {"schemaVersion": 1, "status": status, "inputs": inputs, "commands": outcomes,
         "rawAttemptSha256": sha256(raw_attempt) if commands and raw_attempt.is_file() else None, **extra}, indent=2) + "\n")


def execute(name: str, command: list[str], cwd: Path, env: dict, report: Path,
            inputs: dict, public_inputs: dict, commands: list[dict], path_tokens: dict[str, str], expected: int = 0,
            timeout: int = 120) -> subprocess.CompletedProcess:
    out, err = report / f"{name}.stdout", report / f"{name}.stderr"
    normalized = normalize_command(command, path_tokens, cwd)
    attempt = {"name": name, "command": command, "normalizedArgv": normalized,
               "rawCommandSha256": hashlib.sha256(json.dumps(command, separators=(",", ":")).encode()).hexdigest(),
               "state": "PLANNED", "exit": None}
    commands.append(attempt)

    def record() -> None:
        (report / "attempt.json").write_text(json.dumps({"inputs": inputs, "commands": commands}, indent=2) + "\n")
        publish(report, "RUNNING" if attempt["state"] == "PLANNED" else "ATTEMPTED", public_inputs, commands)

    record()
    try:
        with out.open("w") as stdout, err.open("w") as stderr:
            result = subprocess.run(command, cwd=cwd, env=env, text=True, stdout=stdout, stderr=stderr, timeout=timeout)
        attempt.update(state="COMPLETED" if result.returncode == expected else "UNEXPECTED_EXIT", exit=result.returncode)
    except subprocess.TimeoutExpired:
        attempt["state"] = "TIMEOUT"
        raise
    except OSError:
        attempt["state"] = "LAUNCH_FAILURE"
        raise
    finally:
        attempt.update(stdoutSha256=sha256(out), stderrSha256=sha256(err))
        record()
    result.stdout, result.stderr = out.read_text(), err.read_text()
    if result.returncode != expected:
        raise AssertionError(f"{name} exit {result.returncode}, expected {expected}: {result.stderr[-3000:]}")
    return result


def verify(args: argparse.Namespace) -> dict:
    report = Path(args.report).resolve()
    report.mkdir(parents=True, exist_ok=True)
    receipt = report / "verification.json"
    # 실패한 재실행이 이전 성공 receipt를 남기지 않는다. 다른 build/사용자 파일은 건드리지 않는다.
    receipt.unlink(missing_ok=True)
    publish(report, "VALIDATING_INPUTS", {}, [])
    try:
        return verify_inputs(args, report)
    except Exception as failure:
        receipt.unlink(missing_ok=True)
        public = json.loads((report / "public-report.json").read_text())
        public.update(status="FAILED", failureType=type(failure).__name__)
        (report / "public-report.json").write_text(json.dumps(public, indent=2) + "\n")
        raise


def verify_inputs(args: argparse.Namespace, report: Path) -> dict:
    jars = distribution_jars(args.distribution, args.version, args.distribution_stdlib_version)
    entries = args.compiler_classpath.split(os.pathsep)
    compiler = [Path(entry).resolve() for entry in entries if entry]
    if len(compiler) != len(entries) or not compiler or len(compiler) > 64 or len(set(compiler)) != len(compiler) or any(
            not path.is_file() or path.suffix != ".jar" for path in compiler):
        raise AssertionError("compiler runtime must be an explicit unique JAR-only classpath")
    exact_jar(compiler, "kotlin-compiler-embeddable", args.kotlin_version)
    exact_jar(compiler, "kotlin-stdlib", args.kotlin_version)
    home = Path(os.environ.get("JAVA_HOME", "")).resolve()
    java = home / "bin/java"
    sdk = [home / "release", home / "lib/modules", java]
    if not os.environ.get("JAVA_HOME") or any(not path.is_file() for path in sdk):
        raise AssertionError("JAVA_HOME must name the explicit native JDK")
    sources = [HERE / "fixtures/public-authored-api/PublicAuthoredApi.kt", HERE / "fixtures/public-authored-api/ForbiddenInternal.kt"]
    tracked = [*jars, *compiler, *sources, *sdk, Path(args.distribution).resolve() / "VERSION"]
    before = {str(path): sha256(path) for path in tracked}
    public_inputs = {f"distribution/lib/{path.name}": before[str(path)] for path in jars}
    public_inputs.update({f"compiler-runtime/{number}/{path.name}": before[str(path)] for number, path in enumerate(compiler)})
    public_inputs.update({f"consumer-source/{path.name}": before[str(path)] for path in sources})
    public_inputs.update({f"native-jdk/{path.relative_to(home)}": before[str(path)] for path in sdk})
    public_inputs["distribution/VERSION"] = before[str(Path(args.distribution).resolve() / "VERSION")]
    path_tokens = {str(path): f"@distribution/lib/{path.name}" for path in jars}
    path_tokens.update({str(path): f"@compiler-runtime/{number}/{path.name}" for number, path in enumerate(compiler)})
    path_tokens.update({str(path): f"@consumer-source/{path.name}" for path in sources})
    path_tokens.update({str(path): f"@native-jdk/{path.relative_to(home)}" for path in sdk})
    env = {key: value for key, value in os.environ.items() if key not in
           {"CLASSPATH", "KOTLIN_OPTS", "JAVA_TOOL_OPTIONS", "JDK_JAVA_OPTIONS", "_JAVA_OPTIONS"}}
    commands = []

    def run(name: str, command: list[str], cwd: Path, expected: int = 0) -> subprocess.CompletedProcess:
        return execute(name, command, cwd, env, report, before, public_inputs, commands, path_tokens, expected)

    with tempfile.TemporaryDirectory(prefix="kartograph-public-api-") as temporary:
        work = Path(temporary)
        launcher = [str(java), "-cp", os.pathsep.join(map(str, compiler)), "org.jetbrains.kotlin.cli.jvm.K2JVMCompiler"]
        actual = run("compiler-version", [*launcher, "-version"], work)
        if not re.search(r"kotlinc-jvm " + re.escape(args.kotlin_version) + r"(?:\s|$)", actual.stdout + actual.stderr):
            raise AssertionError("actual compiler version does not match the pinned version")
        output = work / "classes"
        options = ["-no-stdlib", "-no-reflect", "-jvm-target", "17", "-module-name", "kartograph_public_api_consumer",
                   "-classpath", os.pathsep.join(map(str, jars))]
        run("consumer-compile", [*launcher, *options, "-d", str(output), str(sources[0])], work)
        result = run("consumer-run", [str(java), "-cp", os.pathsep.join(map(str, [output, *jars])),
                                      "external.consumer.PublicAuthoredApiKt"], work)
        if result.stdout != EXPECTED or result.stderr:
            raise AssertionError("packaged public API consumer did not satisfy the frozen complete/incomplete/invalid-scope oracle")
        forbidden = run("internal-access-denied", [*launcher, *options, "-d", str(work / "forbidden"), str(sources[1])], work, 1)
        if "CompilerSourceFacts" not in forbidden.stderr or not re.search(r"internal|invisible", forbidden.stderr, re.IGNORECASE):
            raise AssertionError("internal-access negative failed for an unrelated reason")
        classes = {str(path.relative_to(output)): sha256(path) for path in sorted(output.rglob("*")) if path.is_file()}
    if before != {str(path): sha256(path) for path in tracked}:
        raise AssertionError("packaged consumer inputs changed during verification")
    value = {"schemaVersion": 1, "status": "PASS", "distribution": str(Path(args.distribution).resolve()),
             "productVersion": args.version, "kotlinVersion": args.kotlin_version,
             "distributionStdlibVersion": args.distribution_stdlib_version,
             "targetClasspath": [str(path) for path in jars], "inputs": before, "commands": commands,
             "consumerOutputs": classes, "expectedStdout": EXPECTED}
    (report / "verification.json").write_text(json.dumps(value, indent=2) + "\n")
    publish(report, "PASS", public_inputs, commands, productVersion=args.version, kotlinVersion=args.kotlin_version,
            distributionStdlibVersion=args.distribution_stdlib_version,
            targetClasspath=[f"distribution/lib/{path.name}" for path in jars], consumerOutputs=classes, expectedStdout=EXPECTED)
    return value


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("--distribution", required=True)
    parser.add_argument("--version", required=True)
    parser.add_argument("--kotlin-version", required=True)
    parser.add_argument("--distribution-stdlib-version", required=True)
    parser.add_argument("--compiler-classpath", required=True)
    parser.add_argument("--report", required=True)
    args = parser.parse_args()
    verify(args)
    print("packaged authored API consumer passed")


if __name__ == "__main__":
    main()
