#!/usr/bin/env python3
"""같은 실행 표본의 반환값 경로를 CLI·SearchDeadCode·R8에서 대조한다. 도구를 다운로드하지 않는다."""
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
import zipfile

ROOT = Path(__file__).resolve().parent.parent
FIXTURES = ROOT / "experiments/runtime-returns/fixtures"


def digest(path):
    return hashlib.sha256(path.read_bytes()).hexdigest()


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--baseline", required=True)
    parser.add_argument("--binary", required=True)
    parser.add_argument("--searchdeadcode", required=True)
    parser.add_argument("--r8", required=True)
    parser.add_argument("--output", required=True)
    args = parser.parse_args()
    binaries = {key: Path(getattr(args, key)).resolve() for key in ["baseline", "binary", "searchdeadcode", "r8"]}
    if not all(path.is_file() for path in binaries.values()):
        raise RuntimeError("provide all four prebuilt comparison tools")
    jdk = Path(os.environ["JAVA_HOME"])
    records = []
    replacements = {str(path): "$" + key.upper() for key, path in binaries.items()}
    replacements.update({str(jdk): "$JDK", str(ROOT): "$REPO", str(Path.home()): "$HOME"})

    def scrub(value):
        for path, label in sorted(replacements.items(), key=lambda item: -len(item[0])):
            value = value.replace(path, label)
        return value

    def run(command, project, accepted=(0,), timeout=180):
        start = time.perf_counter()
        result = subprocess.run(list(map(str, command)), cwd=project, capture_output=True, text=True, timeout=timeout)
        records.append({"command": [scrub(str(x)) for x in command], "exit": result.returncode,
                        "seconds": time.perf_counter() - start, "stdout": scrub(result.stdout), "stderr": scrub(result.stderr)})
        if result.returncode not in accepted:
            failure = Path(args.output).with_suffix(".failure.json")
            failure.parent.mkdir(parents=True, exist_ok=True)
            failure.write_text(json.dumps(records[-1], indent=2) + "\n")
            raise RuntimeError("comparison command failed: " + Path(str(command[0])).name)
        return result

    versions = {"javac": run([jdk / "bin/javac", "-version"], ROOT).stdout.strip(),
                "r8": run([jdk / "bin/java", "-cp", binaries["r8"], "com.android.tools.r8.R8", "--version"], ROOT).stdout.strip(),
                "searchdeadcode": run([binaries["searchdeadcode"], "--version"], ROOT).stdout.strip()}
    rows = []
    with tempfile.TemporaryDirectory(prefix="kartograph-return-comparison-") as directory:
        temporary = Path(directory)
        replacements[str(temporary)] = "$WORK"
        for fixture in sorted(FIXTURES.iterdir()):
            case = fixture.name
            project = temporary / case
            source = project / "src"
            source.mkdir(parents=True)
            sources = sorted(fixture.glob("Entry.*"))
            if len(sources) != 1:
                raise RuntimeError("each comparison fixture needs exactly one Entry source")
            original = sources[0]
            shutil.copyfile(original, source / original.name)
            classes = project / "classes"
            classes.mkdir()
            kotlin = original.suffix == ".kt"
            libraries = []
            if kotlin:
                version = re.search(r'kotlin\("jvm"\) version "([^"]+)"', (ROOT / "build.gradle.kts").read_text()).group(1)
                (project / "settings.gradle.kts").write_text('pluginManagement { repositories { gradlePluginPortal(); mavenCentral() } }\n')
                (project / "build.gradle.kts").write_text('plugins { kotlin("jvm") version "' + version + '" }\n'
                    'repositories { mavenCentral() }\nkotlin { jvmToolchain(17) }\nsourceSets.main { kotlin.srcDir("src") }\n')
                (project / "gradle").mkdir()
                shutil.copyfile(ROOT / "gradle/verification-metadata.xml", project / "gradle/verification-metadata.xml")
                run([ROOT / "gradlew", "--offline", "--no-daemon", "-p", project, "classes"], ROOT, timeout=600)
                classes = project / "build/classes/kotlin/main"
                library_root = binaries["binary"].parent.parent / "lib"
                stdlib = sorted(library_root.glob("kotlin-stdlib-*.jar"))
                annotations = sorted(library_root.glob("annotations-*.jar"))
                if len(stdlib) != 1 or len(annotations) != 1:
                    raise RuntimeError("the CLI distribution must contain Kotlin stdlib and JetBrains annotations")
                libraries = stdlib + annotations
                versions["kotlin"] = version
            else:
                run([jdk / "bin/javac", "-g", "-d", classes, source / original.name], project)
            classpath = os.pathsep.join(map(str, [classes, *libraries]))
            execution = run([jdk / "bin/java", "-cp", classpath, "probe.Entry", "Used"], project)
            if execution.stdout.strip() != "USED":
                raise RuntimeError("fixture did not execute its Used target")
            (project / "keep.pro").write_text("-keep class probe.Entry { *; }\n")
            (project / "scope.yml").write_text('targets: ["src"]\nexclude: []\nentry_points: ["probe.Entry"]\n')
            row = {"case": case, "sourceSha256": digest(original),
                   "classSha256": {str(file.relative_to(classes)): digest(file) for file in sorted(classes.rglob("*.class"))},
                   "runtimeUsed": True, "tools": {}}
            for name in ["Used", "Unused"]:
                symbol = "class:probe/" + ("" if kotlin else "Entry$") + name
                for tool in ["baseline", "binary"]:
                    observations, times = [], []
                    for _ in range(3):
                        command = [binaries[tool], "query", symbol, "--classes", classes,
                            "--project", project, "--keep-rules", "keep.pro"]
                        for library in libraries:
                            command.extend(["--classpath", library])
                        query = json.loads(run(command, project).stdout)
                        if query["status"] != "found":
                            raise RuntimeError("a compared declaration is absent from the graph")
                        observations.append(query)
                        times.append(records[-1]["seconds"])
                    if any(query != observations[0] for query in observations):
                        raise RuntimeError("repeated query results are not deterministic")
                    query = observations[0]
                    row["tools"].setdefault(tool, {})[name] = {"state": query["result"]["reachability"]["state"],
                        "limitations": query["limitations"], "medianSeconds": statistics.median(times)}
                explanation = run([binaries["searchdeadcode"], project, "--config", project / "scope.yml",
                    "--incremental", "false", "--explain", name], project)
                match = re.search(r"Verdict: (ALIVE|DEAD)", explanation.stdout)
                if match is None or "parsed" not in explanation.stderr:
                    raise RuntimeError("SearchDeadCode did not explain a parsed declaration")
                row["tools"].setdefault("searchdeadcode", {})[name] = {"verdict": match.group(1)}
            with zipfile.ZipFile(project / "input.jar", "w") as archive:
                for file in sorted(classes.rglob("*.class")):
                    archive.write(file, file.relative_to(classes))
            for mode in ["shrinkOnly", "optimized", "optimizedRoots", "unshrunkControl"]:
                optimize = mode != "shrinkOnly"
                configuration = project / (mode + ".pro")
                keep = (project / "keep.pro").read_text()
                if mode in ("optimizedRoots", "unshrunkControl"):
                    keep = keep.replace("-keep class", "-keep,allowoptimization class")
                if mode == "unshrunkControl":
                    keep += "-dontshrink\n"
                configuration.write_text(keep + "-dontobfuscate\n" +
                    ("" if optimize else "-dontoptimize\n") + "-whyareyoukeeping class probe." +
                    ("" if kotlin else "Entry$") + "Used\n")
                output = project / (mode + ".jar")
                command = [jdk / "bin/java", "-cp", binaries["r8"], "com.android.tools.r8.R8", "--release", "--classfile",
                    "--no-desugaring", "--lib", jdk, "--pg-conf", configuration, "--output", output, project / "input.jar"]
                for library in libraries:
                    command.extend(["--lib", library])
                run(command, project)
                transformed = run([jdk / "bin/java", "-cp", os.pathsep.join(map(str, [output, *libraries])),
                    "probe.Entry", "Used"], project, accepted=(0, 1))
                with zipfile.ZipFile(output) as archive:
                    names = archive.namelist()
                prefix = "probe/" + ("" if kotlin else "Entry$")
                row["tools"]["r8-" + mode] = {"keepsUsed": prefix + "Used.class" in names,
                    "keepsUnused": prefix + "Unused.class" in names,
                    "preservesExecution": transformed.returncode == 0 and transformed.stdout.strip() == "USED"}
            expected = "unreachable" if case == "unknown_argument" else "reachable"
            if not row["tools"]["r8-unshrunkControl"]["preservesExecution"]:
                raise RuntimeError("R8 unshrunk control did not preserve execution")
            if row["tools"]["binary"]["Used"]["state"] != expected or row["tools"]["binary"]["Unused"]["state"] != "unreachable":
                raise RuntimeError("candidate violated the runtime target or unused control contract")
            if case == "unknown_argument" and not any(item.startswith("reflection-strings:") for item in row["tools"]["binary"]["Used"]["limitations"]):
                raise RuntimeError("unknown argument lost its measured limitation")
            rows.append(row)
            print("Compared:", case, flush=True)
    output = Path(args.output)
    output.parent.mkdir(parents=True, exist_ok=True)
    artifacts = {tool: {file.name: digest(file) for file in sorted((binaries[tool].parent.parent / "lib").glob("*.jar"))}
                 for tool in ["baseline", "binary"]}
    output.write_text(json.dumps({"versions": versions, "kartographJars": artifacts, "r8Sha256": digest(binaries["r8"]),
        "searchdeadcodeSha256": digest(binaries["searchdeadcode"]), "cases": rows, "commands": records}, indent=2) + "\n")
    print("Runtime return comparison verified:", len(rows), "executed cases")


if __name__ == "__main__":
    try:
        main()
    except (RuntimeError, OSError, ValueError, subprocess.TimeoutExpired) as error:
        detail = str(error) if isinstance(error, RuntimeError) else type(error).__name__
        print("runtime return comparison failed:", detail, file=sys.stderr)
        raise SystemExit(2)
