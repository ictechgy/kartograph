#!/usr/bin/env python3
"""고정된 JVM 표본에서 관측된 실행과 명시적 main-root의 호출 후보를 비교한다."""
import collections
import hashlib
import json
import os
import platform
from pathlib import Path
import re
import shutil
import statistics
import subprocess
import sys
import tempfile
import time
import zipfile

HERE = Path(__file__).resolve().parent
ROOT = HERE.parent.parent
MAIN = "method:probe/Entry#main([Ljava/lang/String;)V"
TARGETS = {"method:probe/Entry$Live#run()V": "Live", "method:probe/Entry$Dormant#run()V": "Dormant"}


def run(args, cwd=ROOT, timeout=180, env=None):
    start = time.monotonic()
    try:
        result = subprocess.run([str(x) for x in args], cwd=cwd, capture_output=True, text=True, timeout=timeout, env=env)
    except (OSError, subprocess.TimeoutExpired):
        raise RuntimeError("precision experiment tool unavailable or timed out") from None
    if result.returncode:
        category = "dependency verification" if "Dependency verification failed" in result.stdout + result.stderr else "execution"
        raise RuntimeError(f"precision experiment {Path(str(args[0])).name} {category} failed (exit {result.returncode})")
    return result.stdout, time.monotonic() - start


def digest(path):
    return hashlib.sha256(path.read_bytes()).hexdigest()


def candidates(graph):
    ids = {node["usr"] for node in graph["nodes"]}
    assert {MAIN, *TARGETS}.issubset(ids), "comparison declarations missing"
    successors = collections.defaultdict(list)
    for edge in graph["edges"]:
        if edge["kind"] != "member":
            successors[edge["source"]].append(edge["target"])
    # main-only 통제 실험이다. 제품의 Android/keep/DI root 정책을 재구현하지 않는다.
    seen = set()
    pending = [MAIN]
    while pending:
        node = pending.pop()
        if node in seen:
            continue
        seen.add(node)
        pending.extend(successors[node])
    return sorted(TARGETS[node] for node in TARGETS if node in seen)


def main():
    if not os.environ.get("JAVA_HOME"):
        raise RuntimeError("set JAVA_HOME to JDK 17")
    jdk = Path(os.environ["JAVA_HOME"])
    version = subprocess.run([str(jdk / "bin/javac"), "-version"], capture_output=True, text=True, timeout=30)
    if version.returncode or not (version.stdout + version.stderr).startswith("javac 17."):
        raise RuntimeError("precision experiment requires JDK 17")
    run([ROOT / "gradlew", "--no-daemon", "-p", HERE, "jar", "sootDependencies", "walaDependencies"], timeout=600)
    run([ROOT / "gradlew", "--no-daemon", ":cli:installDist"], timeout=600)
    kotlin_version = re.search(r'kotlin\("jvm"\) version "([^\"]+)"', (ROOT / "build.gradle.kts").read_text()).group(1)
    stdlib = ROOT / f"cli/build/install/kartograph/lib/kotlin-stdlib-{kotlin_version}.jar"
    engine_jar = HERE / "build/libs/callgraph-precision-experiment.jar"
    paths = {name: sorted((HERE / "build/libraries" / name).glob("*.jar")) for name in ("soot", "wala")}
    expected = json.loads((HERE / "expectations.json").read_text())
    sources = {p.parent.name: p for suffix in ("java", "kt") for p in (HERE / "fixtures").glob(f"*/Entry.{suffix}")}
    assert set(sources) == set(expected)
    observations, timings = [], []
    with tempfile.TemporaryDirectory(prefix="kartograph-callgraph-precision-") as temporary:
        for case, source in sorted(sources.items()):
            directory = Path(temporary) / case
            directory.mkdir()
            libraries = [stdlib] if source.suffix == ".kt" else []
            if libraries:
                kotlin = directory / "kotlin"
                (kotlin / "src/main/kotlin").mkdir(parents=True)
                shutil.copyfile(source, kotlin / "src/main/kotlin/Entry.kt")
                (kotlin / "settings.gradle.kts").write_text('pluginManagement { repositories { gradlePluginPortal(); mavenCentral() } }\nrootProject.name="precision-fixture"\n')
                (kotlin / "build.gradle.kts").write_text(f'plugins {{ kotlin("jvm") version "{kotlin_version}" }}\nrepositories {{ mavenCentral() }}\nkotlin {{ jvmToolchain(17) }}\n')
                (kotlin / "gradle").mkdir()
                shutil.copyfile(ROOT / "gradle/verification-metadata.xml", kotlin / "gradle/verification-metadata.xml")
                run([ROOT / "gradlew", "--offline", "--no-daemon", "-p", kotlin, "compileKotlin"], timeout=600)
                classes = kotlin / "build/classes/kotlin/main"
            else:
                classes = directory / "classes"
                run([jdk / "bin/javac", "-g", "-d", classes, source])
            app = directory / "app.jar"
            with zipfile.ZipFile(app, "w") as archive:
                for file in sorted(classes.rglob("*.class")):
                    archive.write(file, file.relative_to(classes))
            with zipfile.ZipFile(app) as archive:
                application_classes = sorted(name.removesuffix(".class").replace("/", ".") for name in archive.namelist())
            classpath_args = [value for jar in libraries for value in ("--classpath", jar)]
            (directory / "keep.pro").write_text("-keep class probe.Entry { *; }\n")
            witness = directory / "witness"
            runtime_cp = os.pathsep.join(map(str, [classes, *libraries]))
            run([jdk / "bin/javac", "-cp", runtime_cp, "-d", witness, HERE / "fixtures/RuntimeWitness.java"])
            observed = set()
            for argument in ("Live", "Dormant"):
                output, _ = run([jdk / "bin/java", "-cp", os.pathsep.join([runtime_cp, str(witness)]), "RuntimeWitness", "probe.Entry$" + argument])
                flags = output.strip().split(",")
                assert len(flags) == 2 and all(flag in ("true", "false") for flag in flags)
                observed.update(name for name, flag in zip(("Live", "Dormant"), flags) if flag == "true")
            assert sorted(observed) == expected[case]["observed"], case
            result = {"case": case, "applicationClasses": application_classes, "sourceSha256": digest(source), "classes": {p.relative_to(classes).as_posix(): digest(p) for p in sorted(classes.rglob("*.class"))},
                      "observedTargets": sorted(observed), "engines": {}}
            for engine in ("kartograph", "soot-cha", "soot-rta", "wala-01cfa"):
                samples, observed_sets = [], []
                for _ in range(3):
                    if engine == "kartograph":
                        output, elapsed = run([ROOT / "cli/build/install/kartograph/bin/kartograph", "graph", "--classes", app, "--format", "json", *classpath_args], env={**os.environ, "JAVA_OPTS": "-Xmx2g"})
                        graph = json.loads(output)
                        assert sorted(node["usr"].removeprefix("class:").replace("/", ".") for node in graph["nodes"] if node["usr"].startswith("class:")) == application_classes
                        targets = candidates(graph)
                    else:
                        kind = "wala" if engine.startswith("wala") else "soot"
                        cp = os.pathsep.join(map(str, [engine_jar, *paths[kind]]))
                        args = [jdk / "bin/java", "-Xmx2g", "-cp", cp]
                        args += ["probe.WalaRunner", app] if kind == "wala" else ["probe.SootRunner", engine.removeprefix("soot-"), app]
                        try:
                            output, elapsed = run([*args, *libraries])
                        except RuntimeError as error:
                            raise RuntimeError(f"{case}/{engine}: {error}") from None
                        engine_output = json.loads(output)
                        assert engine_output["applicationClasses"] == application_classes, (case, engine, "application scope mismatch")
                        targets = engine_output["targets"]
                    assert set(targets).issubset({"Live", "Dormant"})
                    observed_sets.append(targets)
                    samples.append(elapsed)
                assert all(targets == observed_sets[0] for targets in observed_sets), "non-deterministic target set"
                assert observed_sets[0] == expected[case]["engines"][engine], (case, engine, observed_sets[0])
                if engine == "kartograph":
                    cli_targets = []
                    for symbol, name in TARGETS.items():
                        query, _ = run([ROOT / "cli/build/install/kartograph/bin/kartograph", "query", symbol,
                                        "--classes", app, "--project", directory, "--keep-rules", "keep.pro", *classpath_args])
                        document = json.loads(query)
                        assert document["status"] == "found"
                        if document["result"]["reachability"]["state"] != "unreachable":
                            cli_targets.append(name)
                    result["cliQueriesAgree"] = sorted(cli_targets) == observed_sets[0]
                    assert result["cliQueriesAgree"], "main-root experiment differs from fixture CLI queries"
                result["engines"][engine] = {"targets": observed_sets[0], "missedObservedTargets": sorted(observed - set(observed_sets[0])),
                                            "unobservedCandidates": sorted(set(observed_sets[0]) - observed)}
                timings.append({"case": case, "engine": engine, "processWallSeconds": samples, "medianSeconds": statistics.median(samples)})
            observations.append(result)
            print("Precision comparison verified: " + case, flush=True)
    report = ROOT / "build/reports/callgraph-precision"
    report.mkdir(parents=True, exist_ok=True)
    versions = dict(line.split("=", 1) for line in (HERE / "versions.properties").read_text().splitlines())
    payload = {"versions": {**versions, "kotlin": kotlin_version, "jdk": (version.stdout + version.stderr).strip()},
               "harnessSha256": digest(Path(__file__)), "witnessSha256": digest(HERE / "fixtures/RuntimeWitness.java"),
               "maxHeapMiB": 2048, "kotlinStdlibSha256": digest(stdlib), "jdkReleaseSha256": digest(jdk / "release"),
               "platform": {"system": platform.system(), "machine": platform.machine()},
               "entrypoint": MAIN, "walaReflection": "FULL", "engineJarSha256": digest(engine_jar),
               "librarySha256": {name: {p.name: digest(p) for p in jars} for name, jars in paths.items()}, "cases": observations}
    (report / "results.json").write_text(json.dumps(payload, sort_keys=True, indent=2) + "\n")
    (report / "timings.json").write_text(json.dumps(timings, sort_keys=True, indent=2) + "\n")
    print(f"Callgraph precision verified: {len(observations)} JVM fixtures, 4 engines, 3 runs each")


if __name__ == "__main__":
    try:
        main()
    except (RuntimeError, AssertionError, ValueError, KeyError) as error:
        print("precision comparison failed: " + str(error), file=sys.stderr)
        raise SystemExit(2)
