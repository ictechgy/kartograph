#!/usr/bin/env python3
"""선별한 공개 Kotlin 과제를 새 checkout에서 재현한다. 외부 준비 스크립트나 파괴적 Git 명령은 실행하지 않는다."""
import argparse
import hashlib
import json
import os
from pathlib import Path
import subprocess
import sys
import time
import urllib.request
import xml.etree.ElementTree as ET

ROOT = Path(__file__).resolve().parent.parent
DATASET = "b9025d86f7ae634901396766bd61f6d4ae6d2165"
CASES = {
    "square_okhttp-6887": ("square/okhttp", "1ff16232f86d2b302840a3bb2f9d386e94374753", ":okhttp-sse:test", [], "okhttp-sse/build/test-results/test"),
    "ankidroid_Anki-Android-19661": ("ankidroid/Anki-Android", "8bfadcbe33cb68af5caa471bdce5f80a922500bb",
        ":AnkiDroid:testPlayDebugUnitTest", ["--tests", "com.ichi2.anki.DeckPickerNoExternalFilesDirTest"], "AnkiDroid/build/test-results/testPlayDebugUnitTest"),
}


def main():
    parser = argparse.ArgumentParser(description="Replay a pinned public impact task with an injected common regression oracle.")
    parser.add_argument("--task", choices=CASES, required=True)
    parser.add_argument("--work", required=True, help="new directory; existing contents are never overwritten")
    parser.add_argument("--binary", required=True)
    parser.add_argument("--build-java-home", required=True)
    parser.add_argument("--analysis-java-home", default=os.environ.get("JAVA_HOME"), required="JAVA_HOME" not in os.environ)
    parser.add_argument("--android-jar", help="required for Anki; SDK header-only dependency scope")
    parser.add_argument("--classpath-file", help="optional dependency JAR list for both snapshots")
    args = parser.parse_args()
    work = Path(args.work).resolve()
    work.mkdir(parents=True, exist_ok=False)
    repo = work / "repo"
    binary = Path(args.binary).resolve(strict=True)
    owner, base, task, extra, results = CASES[args.task]
    env = {k: v for k, v in os.environ.items() if not k.startswith("GIT_")}
    env.update(GIT_CONFIG_NOSYSTEM="1", GIT_CONFIG_GLOBAL=os.devnull, GIT_NO_REPLACE_OBJECTS="1", CI="true")
    build_env = dict(env, JAVA_HOME=str(Path(args.build_java_home).resolve()))
    build_env["PATH"] = build_env["JAVA_HOME"] + "/bin:" + env["PATH"]
    analysis_env = dict(env, JAVA_HOME=str(Path(args.analysis_java_home).resolve()))
    analysis_env["PATH"] = analysis_env["JAVA_HOME"] + "/bin:" + env["PATH"]

    def run(command, label, cwd=work, environment=env, accepted=(0,), timeout=1200):
        start = time.perf_counter()
        with (work / (label + ".log")).open("w") as log:
            result = subprocess.run(list(map(str, command)), cwd=cwd, env=environment, stdout=log, stderr=subprocess.STDOUT, timeout=timeout)
        if result.returncode not in accepted:
            raise RuntimeError("replay stage failed: " + label + "; inspect the local stage log")
        return result.returncode, time.perf_counter() - start

    def git(*command):
        result = subprocess.run(["git", "-C", str(repo), "-c", "core.hooksPath=/dev/null", "-c", "commit.gpgsign=false", *command],
            env=env, capture_output=True, text=True, timeout=120)
        if result.returncode:
            raise RuntimeError("replay Git stage failed")
        return result.stdout if "-z" in command else result.stdout.strip()

    run(["git", "init", repo], "init")
    git("remote", "add", "origin", "https://github.com/" + owner + ".git")
    run(["git", "-C", repo, "fetch", "--depth", "1", "origin", base], "fetch")
    git("switch", "-c", "impact-replay", "FETCH_HEAD")
    git("config", "user.name", "Fixture")
    git("config", "user.email", "fixture@example.invalid")
    hashes = {}
    for name, path in [("test.patch", "tests/test.patch"), ("fix.patch", "solution/fix.patch"), ("expected.json", "tests/expected_tests.json")]:
        url = "https://raw.githubusercontent.com/Kotlin/kotlin-swe-bench/" + DATASET + "/tasks/" + args.task + "/" + path
        content = urllib.request.urlopen(url, timeout=30).read()
        (work / name).write_bytes(content)
        hashes[name] = hashlib.sha256(content).hexdigest()
    def patch_paths(name):
        return sorted({line[6:] for line in (work / name).read_text().splitlines() if line.startswith(("--- a/", "+++ b/"))})
    expected_failures = {name.removesuffix("()") for name in json.loads((work / "expected.json").read_text())["f2p_tests"]}
    git("apply", "--check", str(work / "test.patch"))
    git("apply", str(work / "test.patch"))
    git("add", "--", *patch_paths("test.patch"))
    git("commit", "-m", "test: 공통 회귀 oracle을 고정한다")
    before_sha = git("rev-parse", "HEAD")
    records = {}
    classpath = Path(args.classpath_file).read_text().splitlines() if args.classpath_file else []
    if owner.startswith("ankidroid/"):
        if not args.android_jar:
            raise RuntimeError("Anki replay requires --android-jar")
        classpath += [str(Path(args.android_jar).resolve(strict=True))]
    for phase in ["before", "after"]:
        if phase == "after":
            git("apply", "--check", str(work / "fix.patch"))
            git("apply", str(work / "fix.patch"))
            git("add", "--", *patch_paths("fix.patch"))
            git("commit", "-m", "fix: 공개 정답 patch를 재현한다")
        # 이 새 checkout에서 만든 보고서만 지워 이전 XML이 점수로 재사용되지 않게 한다.
        for report in (repo / results).glob("TEST*.xml"):
            report.unlink()
        code, seconds = run(["./gradlew", "--no-daemon", "--no-build-cache", task, *extra, "--max-workers=2"],
            phase + "-test", repo, build_env, accepted=(0, 1))
        tests = []
        for report in sorted((repo / results).glob("TEST*.xml")):
            for test in ET.parse(report).iter("testcase"):
                tests.append({"class": test.get("classname"), "method": test.get("name"),
                    "status": "failed" if test.find("failure") is not None or test.find("error") is not None else
                    ("skipped" if test.find("skipped") is not None else "passed")})
        if not tests or (phase == "before" and not any(t["status"] == "failed" for t in tests)) or (phase == "after" and code != 0):
            raise RuntimeError("no valid before-failure/after-success oracle: " + phase)
        if phase == "before" and { (t["class"] + "." + t["method"]).removesuffix("()") for t in tests if t["status"] == "failed" } != expected_failures:
            raise RuntimeError("observed failures differ from the pinned benchmark target")
        (work / (phase + "-tests.json")).write_text(json.dumps({"exit": code, "seconds": seconds, "tests": tests}, indent=2))
        if owner == "square/okhttp":
            roots = list(repo.glob("*/build/classes/*/main")) + list((repo / "okhttp-sse").glob("build/classes/*/test"))
        else:
            roots = [p for p in repo.glob("*/build/tmp/kotlin-classes/*") if p.name in ("playDebug", "debug", "playDebugUnitTest")]
            roots += [p for p in repo.glob("*/build/intermediates/javac/*/*/classes") if "release" not in str(p).lower()]
        roots = [p for p in roots if p.is_dir() and any(p.rglob("*.class"))]
        sha = git("rev-parse", "HEAD")
        command = [str(binary), "snapshot", "--project", str(repo), "--include-paths", "--compact", "--revision", sha, "--scope", args.task + ":native"]
        command += [value for p in sorted(roots) for value in ("--classes", str(p))]
        command += [value for p in classpath for value in ("--classpath", p)]
        start = time.perf_counter()
        with (work / (phase + ".json")).open("w") as out, (work / (phase + "-snapshot.err")).open("w") as err:
            captured = subprocess.run(command, env=analysis_env, stdout=out, stderr=err, timeout=240)
        if captured.returncode:
            raise RuntimeError("snapshot stage failed: " + phase)
        records[phase] = {"revision": sha, "testExit": code, "tests": len(tests), "classRoots": len(roots),
            "captureSeconds": time.perf_counter() - start, "snapshotBytes": (work / (phase + ".json")).stat().st_size}
        print("Replayed:", args.task, phase, flush=True)
    files = git("diff", "--name-only", "-z", "--no-renames", before_sha, "HEAD").split("\0")
    files = sorted(set(files) - {""})
    (work / "files.json").write_text(json.dumps(files))
    run([sys.executable, ROOT / "Scripts/score-impact.py", "--binary", binary, "--base-graph", work / "before.json",
        "--graph-file", work / "after.json", "--before-tests", work / "before-tests.json", "--after-tests", work / "after-tests.json",
        "--files", work / "files.json", "--output", work / "score.json"], "score", environment=analysis_env)
    (work / "replay.json").write_text(json.dumps({"task": args.task, "datasetRevision": DATASET, "upstreamBase": base,
        "patchSha256": hashes, "phases": records, "dependencyHeaders": len(classpath),
        "scope": "Native replay of scoped tests with the same injected test patch in both revisions; not the full Harbor verifier."}, indent=2) + "\n")


if __name__ == "__main__":
    try:
        main()
    except (OSError, ValueError, RuntimeError, subprocess.TimeoutExpired) as error:
        print("error: " + (str(error) if isinstance(error, RuntimeError) else "replay setup or inputs failed"), file=sys.stderr)
        raise SystemExit(2)
