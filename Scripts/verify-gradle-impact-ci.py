#!/usr/bin/env python3
"""실제 배포 plugin·Git checkout·CI helper를 연결하고 단계별 비용을 기록한다."""

import argparse
import hashlib
import json
import os
from pathlib import Path
import subprocess
import sys
import tempfile
import time


ROOT = Path(__file__).resolve().parents[1]


def main():
    parser = argparse.ArgumentParser(description="Verify automatic Gradle snapshots in a real base/current CI flow.")
    parser.add_argument("--gradle", type=Path, default=ROOT / "gradlew")
    parser.add_argument("--binary", type=Path, default=ROOT / "cli/build/install/kartograph/bin/kartograph")
    parser.add_argument("--plugin-jar", type=Path)
    parser.add_argument("--output", type=Path, default=ROOT / "build/reports/gradle-impact-ci/result.json")
    args = parser.parse_args()
    version = (ROOT / "VERSION").read_text().strip()
    plugin = (args.plugin_jar or ROOT / f"gradle-plugin/build/libs/kartograph-gradle-plugin-{version}.jar").resolve(strict=True)
    gradle = args.gradle.resolve(strict=True)
    binary = args.binary.resolve(strict=True)
    environment = {key: value for key, value in os.environ.items() if not key.startswith("GIT_")}
    environment.update(GIT_CONFIG_NOSYSTEM="1", GIT_CONFIG_GLOBAL=os.devnull, GIT_NO_REPLACE_OBJECTS="1")
    timings = []

    def run(stage, command, cwd, expected=0):
        started = time.perf_counter()
        result = subprocess.run(list(map(str, command)), cwd=cwd, env=environment,
                                capture_output=True, text=True, timeout=300)
        timings.append({"stage": stage, "seconds": round(time.perf_counter() - started, 6), "exit": result.returncode})
        if result.returncode != expected:
            raise RuntimeError(f"{stage} failed with exit {result.returncode}, expected {expected}")
        return result.stdout

    with tempfile.TemporaryDirectory(prefix="kartograph-gradle-ci-") as directory:
        workspace = Path(directory)
        current = workspace / "current checkout"
        current.mkdir()
        def write(relative, text):
            file = current / relative
            file.parent.mkdir(parents=True, exist_ok=True)
            file.write_text(text, encoding="utf-8")

        plugin_path = str(plugin).replace("\\", "\\\\").replace("'", "\\'")
        write("settings.gradle", "rootProject.name = 'impact-ci-fixture'\n")
        write("build.gradle", f"""buildscript {{ dependencies {{ classpath files('{plugin_path}') }} }}
apply plugin: 'java'
apply plugin: 'io.github.ictechgy.kartograph'
kartograph {{ snapshotsEnabled = true; includeSourcePaths = true }}
tasks.named('test') {{ doFirst {{ throw new GradleException('snapshot must not execute tests') }} }}
""")
        write(".gitignore", "build/\n.gradle/\n")
        source = "src/main/java/p/Target.java"
        write(source, "package p; public class Target { public int value() { return 1; } }\n")
        write("src/test/java/p/TargetCheck.java", "package p; public class TargetCheck { public int check() { return new Target().value(); } }\n")
        def git(*arguments):
            return run("git-" + arguments[0], ["git", "-c", "user.name=Fixture", "-c", "user.email=fixture@example.invalid", *arguments], current).strip()
        git("init", "-q")
        git("add", "--", ".")
        git("commit", "-qm", "base")
        base_revision = git("rev-parse", "HEAD")
        write(source, "package p; public class Target { public int value() { return 2; } }\n")
        git("add", "--", source)
        git("commit", "-qm", "change target")
        current_revision = git("rev-parse", "HEAD")
        base = workspace / "base checkout"
        run("clone-base", ["git", "clone", "--quiet", "--no-hardlinks", current, base], workspace)
        run("checkout-base", ["git", "checkout", "--quiet", "--detach", base_revision], base)

        graph_path = "build/reports/kartograph/jvm-snapshot.json"
        binding_path = "build/kartograph/jvm-input-bindings.json"
        for label, checkout, revision in (("base", base, base_revision), ("current", current, current_revision)):
            common = [gradle, "--no-daemon", "--console=plain", "--configuration-cache", f"-Pkartograph.revision={revision}"]
            run(label + "-build", common + ["testClasses"], checkout)
            run(label + "-capture", common + ["kartographSnapshot"], checkout)
            document = json.loads(run(label + "-verify", [binary, "verify-snapshot", "--graph-file", checkout / graph_path,
                "--project", checkout, "--input-bindings", checkout / binding_path], checkout))
            if document.get("status") != "matched":
                raise RuntimeError(label + " snapshot did not match its actual compiled inputs")

        command = [sys.executable, ROOT / "Scripts/check-impact.py", "--binary", binary, "--project", current,
            "--base", base_revision, "--base-project", base, "--base-graph", base / graph_path,
            "--graph-file", current / graph_path, "--base-input-bindings", base / binding_path,
            "--input-bindings", current / binding_path, "--strict"]
        report = json.loads(run("impact-ci", command, current))
        target = "method:p/Target#value()I"
        caller = "method:p/TargetCheck#check()I"
        if target not in {node["usr"] for node in report["changed"]} or caller not in {node["usr"] for node in report["affected"]}:
            raise RuntimeError("CI report omitted the changed method or its unchanged test caller")
        if report["status"] != "found" or any(item["status"] != "matched" for item in report["freshness"].values()):
            raise RuntimeError("CI helper did not preserve complete matching base/current inputs")

        snapshot_digest = hashlib.sha256((current / graph_path).read_bytes()).hexdigest()
        for repeat in range(1, 3):
            common = [gradle, "--no-daemon", "--console=plain", "--configuration-cache", f"-Pkartograph.revision={current_revision}"]
            run(f"repeat-{repeat}-build", common + ["testClasses"], current)
            capture_output = run(f"repeat-{repeat}-capture", common + ["kartographSnapshot"], current)
            if "> Task :kartographSnapshot" not in {line.strip() for line in capture_output.splitlines()}:
                raise RuntimeError("repeat capture did not execute the snapshot task")
            if hashlib.sha256((current / graph_path).read_bytes()).hexdigest() != snapshot_digest:
                raise RuntimeError("unchanged automatic capture changed the snapshot contents")
            queried = json.loads(run(f"repeat-{repeat}-query", [binary, "impact", target,
                "--graph-file", current / graph_path], current))
            if caller not in {node["usr"] for node in queried["affected"]}:
                raise RuntimeError("repeated saved query omitted the known caller")

        no_change = command.copy()
        for option, value in (("--base", current_revision), ("--base-project", current),
                              ("--base-graph", current / graph_path), ("--base-input-bindings", current / binding_path)):
            no_change[no_change.index(option) + 1] = value
        if json.loads(run("impact-ci-no-change", no_change, current))["status"] != "noChanges":
            raise RuntimeError("unchanged commit comparison did not report noChanges")
        matching_scope = json.loads(run("scope-matched", [binary, "verify-snapshot", "--graph-file", current / graph_path,
            "--project", current, "--input-bindings", current / binding_path, "--scope", report["inputs"]["current"]["scope"]], current))
        if matching_scope.get("status") != "matched":
            raise RuntimeError("automatic snapshot rejected its recorded scope")
        mismatch = json.loads(run("scope-mismatch", [binary, "verify-snapshot", "--graph-file", current / graph_path,
            "--project", current, "--input-bindings", current / binding_path, "--scope", "other:variant"], current, expected=1))
        if mismatch.get("status") != "stale" or "snapshot-scope-mismatch" not in mismatch.get("reasons", []):
            raise RuntimeError("automatic snapshot accepted the wrong requested scope")

        # Git source 상태는 그대로 두고, 같은 크기·수정 시각의 class 바이트 변경을 검증한다.
        classes = current / "build/classes/java/main/p/Target.class"
        original = classes.read_bytes()
        if not original:
            raise RuntimeError("compiler produced an empty class file")
        stamp = classes.stat()
        classes.write_bytes(original[:-1] + bytes([original[-1] ^ 1]))
        os.utime(classes, ns=(stamp.st_atime_ns, stamp.st_mtime_ns))
        stale = json.loads(run("impact-ci-stale", command, current, expected=1))
        if stale["freshness"]["current"]["status"] != "stale" or stale["freshness"]["base"]["status"] != "matched":
            raise RuntimeError("CI helper failed to distinguish stale current outputs from the unchanged base")
        classes.write_bytes(original)
        os.utime(classes, ns=(stamp.st_atime_ns, stamp.st_mtime_ns))

        renamed = "src/main/java/renamed/p/Target.java"
        (current / renamed).parent.mkdir(parents=True)
        git("mv", source, renamed)
        git("commit", "-qm", "move target source")
        moved_revision = git("rev-parse", "HEAD")
        run("rename-capture", [gradle, "--no-daemon", "--console=plain", "--configuration-cache",
            f"-Pkartograph.revision={moved_revision}", "kartographSnapshot"], current)
        moved = json.loads(run("impact-ci-rename", command, current))
        changed_target = next((node for node in moved["changed"] if node["usr"] == target), None)
        if not changed_target or not any(fact["revision"] == "current" and (fact.get("location") or {}).get("path") == renamed
                                         for fact in changed_target.get("facts", [])):
            raise RuntimeError("automatic rename comparison lost the current source location")
        if not any(fact["revision"] == "base" and (fact.get("location") or {}).get("path") == source
                   for fact in changed_target.get("facts", [])):
            raise RuntimeError("automatic rename comparison lost the base source location")
        if moved["inputs"]["current"]["revision"] != moved_revision:
            raise RuntimeError("automatic rename comparison did not use the current commit")
        if caller not in {node["usr"] for node in moved["affected"]} or any(value["status"] != "matched" for value in moved["freshness"].values()):
            raise RuntimeError("automatic rename comparison lost the caller or freshness")
        summary = {"status": "PASS", "analyzerVersion": version, "changedMethod": target, "unchangedTestCaller": caller,
            "freshness": {side: value["status"] for side, value in report["freshness"].items()},
            "staleCurrentRejected": True, "scopeMismatchRejected": True, "renamePreserved": True,
            "noChangeVerified": True, "repeatCount": 2, "timings": timings,
            "scope": "Controlled JVM commits using the built standalone plugin and CLI; repeated build/capture/query timings are not a large-project performance benchmark."}
        args.output.parent.mkdir(parents=True, exist_ok=True)
        args.output.write_text(json.dumps(summary, indent=2) + "\n", encoding="utf-8")
        print("Gradle impact CI verified: matched inputs, caller/rename/no-change preserved, stale output and wrong scope rejected")
    return 0


if __name__ == "__main__":
    try:
        raise SystemExit(main())
    except (OSError, ValueError, KeyError, TypeError, AttributeError, RuntimeError, subprocess.TimeoutExpired) as error:
        print("error: " + (str(error) if isinstance(error, RuntimeError) else "CI fixture inputs unavailable or invalid"), file=sys.stderr)
        raise SystemExit(2)
