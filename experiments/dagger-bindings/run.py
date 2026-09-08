#!/usr/bin/env python3
"""실제 Dagger SPI 선택 결과를 같은 compiler 산출물의 JVM 선언에 연결한다."""
import copy
import hashlib
import json
import os
from pathlib import Path
import subprocess
import sys
import tempfile

HERE = Path(__file__).resolve().parent
ROOT = HERE.parent.parent


def run(args, cwd=ROOT, expected=0, details=False):
    try:
        result = subprocess.run([str(x) for x in args], cwd=cwd, capture_output=True, text=True, timeout=600)
    except (OSError, subprocess.TimeoutExpired):
        raise RuntimeError("Dagger experiment tool unavailable or timed out") from None
    if result.returncode != expected:
        raise RuntimeError(f"Dagger experiment stage {Path(str(args[0])).name} failed: expected {expected}, actual {result.returncode}")
    return result if details else result.stdout


def digest(path):
    return hashlib.sha256(path.read_bytes()).hexdigest()


def snapshot(source, classes, jars, variant):
    return {"sourceSha256": digest(source), "variant": variant,
            "classes": {p.relative_to(classes).as_posix(): digest(p) for p in sorted(classes.rglob("*.class"))},
            "classpath": {p.name: digest(p) for p in jars}}


def join(graph, sidecar, inputs):
    if inputs != sidecar["inputs"]:
        raise ValueError("Dagger evidence differs from selected compiler inputs")
    if hashlib.sha256(json.dumps(graph, sort_keys=True).encode()).hexdigest() != sidecar["graphSha256"]:
        raise ValueError("Dagger evidence graph differs from selected graph")
    ids = {node["usr"] for node in graph["nodes"]}
    if sidecar["unmapped"] or sidecar["component"] not in ids:
        raise ValueError("Dagger evidence is incomplete")
    result = copy.deepcopy(graph)
    for row in sidecar["edges"]:
        if row["source"] not in ids or row["target"] not in ids or row["weight"] <= 0:
            raise ValueError("Dagger evidence has no compiled declaration")
        result["edges"].append({"source": row["source"], "target": row["target"], "kind": "reference",
                                "origin": "compilerReference", "weight": row["weight"]})
    result["edges"].sort(key=lambda row: (row["source"], row["target"], row["kind"], row.get("origin", "bytecode")))
    return result


def main():
    if not os.environ.get("JAVA_HOME"):
        raise RuntimeError("set JAVA_HOME to JDK 17 for this experiment")
    jdk = Path(os.environ["JAVA_HOME"])
    compiler = run([jdk / "bin/javac", "-version"], details=True)
    if not (compiler.stdout + compiler.stderr).startswith("javac 17."):
        raise RuntimeError("Dagger experiment requires JDK 17")
    run([ROOT / "gradlew", "--no-daemon", "-p", HERE, "jar", "dependenciesForFixture"])
    run([ROOT / "gradlew", "--offline", "--no-daemon", ":cli:installDist"])
    jars = sorted((HERE / "build/fixture-dependencies").glob("*.jar"))
    collector = HERE / "build/libs/dagger-bindings-experiment.jar"
    cp = os.pathsep.join(map(str, jars))
    processor_path = os.pathsep.join([str(collector), cp])
    source_text = (HERE / "fixtures/Entry.java").read_text()
    documents = {}
    with tempfile.TemporaryDirectory(prefix="kartograph-dagger-bindings-") as temporary:
        for variant in ("selected", "unused", "missing"):
            directory = Path(temporary) / variant
            directory.mkdir()
            source = directory / "Entry.java"
            source.write_text(source_text.replace('@Named("selected") Service service()', f'@Named("{variant}") Service service()'))
            classes = directory / "classes"
            compilation = run([jdk / "bin/javac", "-g", "-cp", cp, "-processorpath", processor_path,
                 "-processor", "dagger.internal.codegen.ComponentProcessor", "-d", classes,
                 "-s", directory / "generated", source], expected=1 if variant == "missing" else 0, details=True)
            if variant == "missing":
                assert "[Dagger/MissingBinding]" in compilation.stderr, "failure was not a Dagger missing-binding diagnostic"
                assert not list(classes.glob("META-INF/kartograph/dagger/*.tsv")), "failed component emitted selected binding evidence"
                continue
            assert run([jdk / "bin/java", "-cp", os.pathsep.join([str(classes), cp]), "probe.Entry"]).strip() == ("selected\ndependency,selected" if variant == "selected" else "unused\nunused")
            resources = list(classes.glob("META-INF/kartograph/dagger/*.tsv"))
            assert len(resources) == 1, "selected component evidence missing or duplicated"
            rows = [line.split("\t") for line in resources[0].read_text().splitlines()]
            assert rows[0] == ["component", "class:probe/Entry$App"]
            assert rows[1] == ["unmapped", "0"]
            edges = [{"source": row[1], "target": row[2], "role": row[3], "weight": int(row[4])}
                     for row in rows[2:] if len(row) == 5 and row[0] == "edge"]
            assert len(edges) == len(rows) - 2
            expected_target = "method:probe/Entry$Bindings#" + variant + "(Lprobe/Entry$" + ("Selected" if variant == "selected" else "Unused") + ";)Lprobe/Entry$Service;"
            entry = [row for row in edges if row["role"] == "entryPoint"]
            assert len(entry) == 1 and entry[0]["target"] == expected_target
            assert len(edges) == (3 if variant == "selected" else 2)
            rejected_owner = "Unused" if variant == "selected" else "Selected"
            assert not any("Entry$" + rejected_owner + "#" in row["target"] for row in edges)
            graph = json.loads(run([ROOT / "cli/build/install/kartograph/bin/kartograph", "graph",
                                   "--classes", classes, "--format", "json"]))
            ids = {node["usr"] for node in graph["nodes"]}
            assert "class:probe/Entry$" + rejected_owner in ids, "unselected control was not compiled"
            inputs = snapshot(source, classes, jars + [collector], variant)
            sidecar = {"format": "dagger-binding-experiment", "version": 1,
                       "daggerVersion": (HERE / "dagger-version.txt").read_text().strip(),
                       "component": rows[0][1], "unmapped": int(rows[1][1]), "inputs": inputs, "edges": edges,
                       "graphSha256": hashlib.sha256(json.dumps(graph, sort_keys=True).encode()).hexdigest()}
            saved = directory / "sidecar.json"
            saved.write_text(json.dumps(sidecar))
            sidecar = json.loads(saved.read_text())
            enriched = join(graph, sidecar, snapshot(source, classes, jars + [collector], variant))
            documents[variant] = {"sidecar": sidecar, "graph": enriched}
            for change in ("variant", "sourceSha256", "classpath", "classes"):
                stale = copy.deepcopy(inputs)
                if isinstance(stale[change], dict):
                    stale[change][next(iter(stale[change]))] = "0" * 64
                else:
                    stale[change] = "mismatch"
                try:
                    join(graph, sidecar, stale)
                except ValueError:
                    continue
                raise AssertionError("stale Dagger evidence accepted")
    output = ROOT / "build/reports/dagger-bindings"
    output.mkdir(parents=True, exist_ok=True)
    for variant, documents_for_variant in documents.items():
        for kind, document in documents_for_variant.items():
            (output / f"{variant}-{kind}.json").write_text(json.dumps(document, sort_keys=True, indent=2) + "\n")
    print("Dagger SPI verified: 3 selected edges, 2 alternate edges, compiled unselected controls, missing binding rejected, stale inputs rejected")


if __name__ == "__main__":
    try:
        main()
    except (RuntimeError, AssertionError, KeyError, ValueError) as error:
        print("Dagger experiment failed: " + str(error), file=sys.stderr)
        raise SystemExit(2)
