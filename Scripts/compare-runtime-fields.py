#!/usr/bin/env python3
"""같은 Java/Kotlin 실행 입력의 필드 경로를 변경 전후 impact 질의로 대조한다."""
import argparse
import hashlib
import json
import os
from pathlib import Path
import re
import shutil
import statistics
import subprocess
import sys
import tempfile
import time

ROOT = Path(__file__).resolve().parent.parent
FIXTURES = ROOT / "experiments/runtime-fields/fixtures"
CASES = ("reflective_initial", "direct_string", "reassigned", "inherited", "kotlin_object", "unknown_write")


def digest(path):
    return hashlib.sha256(path.read_bytes()).hexdigest()


def main():
    parser = argparse.ArgumentParser(description="Compare field-flow impact against actual JVM execution and unused controls.")
    parser.add_argument("--baseline", required=True)
    parser.add_argument("--binary", required=True)
    parser.add_argument("--output", required=True)
    parser.add_argument("--case", action="append", choices=CASES, dest="cases")
    parser.add_argument("--repeats", type=int, choices=range(1, 11), default=3)
    args = parser.parse_args()
    binaries = {name: Path(getattr(args, name)).resolve(strict=True) for name in ("baseline", "binary")}
    if not os.environ.get("JAVA_HOME"):
        raise RuntimeError("set JAVA_HOME to a complete JDK 17")
    jdk = Path(os.environ["JAVA_HOME"])
    versions = {}

    def run(command, cwd, timeout=120):
        started = time.perf_counter()
        result = subprocess.run(list(map(str, command)), cwd=cwd, capture_output=True, text=True, timeout=timeout)
        elapsed = time.perf_counter() - started
        if result.returncode:
            raise RuntimeError("comparison stage failed: " + Path(str(command[0])).name)
        return result.stdout, elapsed

    versions["javac"] = run([jdk / "bin/javac", "-version"], ROOT)[0].strip()
    artifacts = {name: {p.name: digest(p) for p in sorted((binary.parent.parent / "lib").glob("*.jar"))}
                 for name, binary in binaries.items()}
    if any(not jars for jars in artifacts.values()):
        raise RuntimeError("both tools must be complete installed CLI distributions")
    rows, failures = [], []
    selected = args.cases or CASES
    if len(set(selected)) != len(selected):
        raise RuntimeError("duplicate comparison case")
    with tempfile.TemporaryDirectory(prefix="kartograph-field-comparison-") as directory:
        temporary = Path(directory)
        for case in selected:
            sources = sorted((FIXTURES / case).glob("Entry.*"))
            if len(sources) != 1:
                raise RuntimeError("each case must have exactly one Entry source")
            original = sources[0]
            project = temporary / case
            source = project / "src/probe"
            source.mkdir(parents=True)
            shutil.copyfile(original, source / original.name)
            classes = project / "classes"
            classes.mkdir()
            kotlin = original.suffix == ".kt"
            libraries = []
            if kotlin:
                compiler = re.search(r'kotlin\("jvm"\) version "([^"]+)"', (ROOT / "build.gradle.kts").read_text())
                if compiler is None:
                    raise RuntimeError("cannot determine the repository Kotlin compiler version")
                versions["kotlin"] = compiler.group(1)
                (project / "settings.gradle.kts").write_text('pluginManagement { repositories { gradlePluginPortal(); mavenCentral() } }\n')
                (project / "build.gradle.kts").write_text('plugins { kotlin("jvm") version "' + compiler.group(1) + '" }\n'
                    'repositories { mavenCentral() }\nkotlin { jvmToolchain(17) }\nsourceSets.main { kotlin.srcDir("src") }\n')
                (project / "gradle").mkdir()
                shutil.copyfile(ROOT / "gradle/verification-metadata.xml", project / "gradle/verification-metadata.xml")
                run([ROOT / "gradlew", "--offline", "--no-daemon", "-p", project, "classes"], ROOT, timeout=600)
                classes = project / "build/classes/kotlin/main"
                for pattern in ("kotlin-stdlib-*.jar", "annotations-*.jar"):
                    matches = sorted((binaries["binary"].parent.parent / "lib").glob(pattern))
                    if len(matches) != 1:
                        raise RuntimeError("candidate distribution lacks a unique Kotlin runtime dependency")
                    libraries.extend(matches)
            else:
                run([jdk / "bin/javac", "-g", "-d", classes, source / original.name], project)
            class_files = sorted(classes.rglob("*.class"))
            if not class_files:
                raise RuntimeError("compiler produced no class files")
            observed, _ = run([jdk / "bin/java", "-cp", os.pathsep.join(map(str, [classes, *libraries])),
                               "probe.Entry", "probe.Entry$Target"], project)
            if observed.strip() != "USED":
                raise RuntimeError("JVM did not execute the expected target constructor")
            (project / "keep.pro").write_text("-keep class probe.Entry { *; }\n")
            row = {"case": case, "sourceSha256": digest(original), "runtimeUsed": True,
                   "classSha256": {p.relative_to(classes).as_posix(): digest(p) for p in class_files},
                   "dependencySha256": {p.name: digest(p) for p in libraries}, "tools": {}}
            caller = "method:probe/Entry#main([Ljava/lang/String;)V"
            for tool, binary in binaries.items():
                command = [binary, "snapshot", "--classes", classes, "--project", project,
                           "--keep-rules", "keep.pro", "--include-paths", "--compact", "--scope", "runtime-fields:" + case]
                for library in libraries:
                    command.extend(["--classpath", library])
                encoded, capture = run(command, project)
                snapshot = project / (tool + ".json")
                snapshot.write_text(encoded, encoding="utf-8")
                results = {"captureSeconds": capture, "snapshotBytes": snapshot.stat().st_size}
                for name in ("Used", "Unused"):
                    symbol = "class:probe/" + ("" if kotlin else "Entry$") + name
                    observations, times = [], []
                    for _ in range(args.repeats):
                        output, seconds = run([binary, "impact", symbol, "--graph-file", snapshot], project)
                        observations.append(json.loads(output))
                        times.append(seconds)
                    document = observations[0]
                    if any(other != document for other in observations):
                        raise RuntimeError("repeated impact result differs on identical inputs")
                    if document.get("status") != "found" or any(document["truncated"].values()):
                        raise RuntimeError("fixture impact is missing or truncated")
                    main = [item for item in document["affected"] if item["usr"] == caller]
                    results[name] = {"mainInImpact": bool(main), "affectedCount": len(document["affected"]),
                                     "limitations": document["limitations"], "witness": main,
                                     "queryMedianSeconds": statistics.median(times), "querySamplesSeconds": times}
                row["tools"][tool] = results
            candidate = row["tools"]["binary"]
            if candidate["Unused"]["mainInImpact"]:
                failures.append(case + ": unused control linked to main")
            if case != "unknown_write" and not candidate["Used"]["mainInImpact"]:
                failures.append(case + ": executed target lacks main impact path")
            if case == "unknown_write" and not any("reflection-strings:" in value or "reflective-construction:" in value
                                                   for value in candidate["Used"]["limitations"]):
                failures.append(case + ": runtime argument lost its measured uncertainty")
            rows.append(row)
            print("Compared:", case, flush=True)
    report = {"scope": "Executed synthetic compiler cases; not real-project repair accuracy or complete runtime coverage.",
              "versions": versions, "kartographJars": artifacts, "repeats": args.repeats,
              "cases": rows, "verificationPassed": not failures, "failures": failures}
    output = Path(args.output)
    output.parent.mkdir(parents=True, exist_ok=True)
    output.write_text(json.dumps(report, indent=2) + "\n")
    print("Field-flow contract:", "passed" if not failures else "failed", "for", len(rows), "cases")
    return 1 if failures else 0


if __name__ == "__main__":
    try:
        raise SystemExit(main())
    except (OSError, ValueError, RuntimeError, subprocess.TimeoutExpired) as error:
        print("field-flow comparison failed: " + (str(error) if isinstance(error, RuntimeError) else "invalid comparison input"), file=sys.stderr)
        raise SystemExit(2)
