#!/usr/bin/env python3
"""공개 fixture에서 compiler 참조 수집과 revision/variant 일치 보강을 비교하는 실험이다."""
import copy
import hashlib
import json
import os
import re
from pathlib import Path
import shutil
import subprocess
import tempfile
import time

HERE = Path(__file__).resolve().parent
ROOT = HERE.parent.parent
VARIANT = "jvm17-experiment"


def canonical(document):
    return (json.dumps(document, sort_keys=True, indent=2) + "\n").encode()


def digest(path):
    return hashlib.sha256(path.read_bytes()).hexdigest()


def snapshot(sources, class_roots, variant):
    return {
        "variant": variant,
        "fixtureMappingSha256": digest(HERE / "fixture-mapping.json"),
        "sources": {p.name: digest(p) for p in sorted(sources)},
        "classes": {f"{ordinal}/{p.relative_to(root).as_posix()}": digest(p)
                    for ordinal, root in enumerate(class_roots) for p in sorted(root.rglob("*.class"))},
    }


def command(args, cwd=ROOT):
    result = subprocess.run([str(x) for x in args], cwd=cwd, capture_output=True, text=True, timeout=600)
    if result.returncode:
        raise RuntimeError(f"experiment command failed: {Path(str(args[0])).name} (exit {result.returncode})")
    return result.stdout


def enrich(graph, sidecar, current):
    if sidecar["inputs"] != current:
        raise ValueError("compiler reference inputs do not match selected revision, variant or class outputs")
    if sidecar["graphSha256"] != hashlib.sha256(canonical(graph)).hexdigest():
        raise ValueError("compiler reference graph does not match")
    ids = {node["usr"] for node in graph["nodes"]}
    result = copy.deepcopy(graph)
    for reference in sidecar["references"]:
        if reference["source"] not in ids or reference["target"] not in ids:
            raise ValueError("compiler reference does not match a compiled declaration")
        result["edges"].append({"source": reference["source"], "target": reference["target"],
                                "kind": "fieldAccess", "origin": "compilerReference", "weight": 1})
    result["edges"].sort(key=lambda edge: (edge["source"], edge["target"], edge["kind"], edge.get("origin", "bytecode")))
    return result


def expect_rejected(graph, sidecar, current):
    try:
        enrich(graph, sidecar, current)
    except ValueError:
        return 1
    raise AssertionError("mismatched compiler references were accepted")


def validate_mapping(mapping, consumed=None):
    if len(set(mapping.values())) != len(mapping):
        raise ValueError("ambiguous fixture mapping")
    if consumed is not None and consumed != set(mapping):
        raise ValueError("stale fixture mapping entries")


def expect_mapping_rejected(mapping, consumed):
    try:
        validate_mapping(mapping, consumed)
    except ValueError:
        return 1
    raise AssertionError("invalid fixture mapping was accepted")


def main():
    start = time.monotonic()
    if not os.environ.get("JAVA_HOME"):
        raise RuntimeError("set JAVA_HOME to JDK 17 before running this experiment")
    jdk = Path(os.environ["JAVA_HOME"])
    java_version = command([jdk / "bin/javac", "-version"]).strip()
    if not java_version.startswith("javac 17."):
        raise RuntimeError("this comparison requires JDK 17")
    command([ROOT / "gradlew", "--offline", "--no-daemon", ":cli:installDist"])
    command([ROOT / "gradlew", "--offline", "--no-daemon", "-p", HERE / "collector", "jar"])
    collector = HERE / "collector/build/libs/reference-collector.jar"
    kotlin_version = re.search(r'kotlin\("jvm"\) version "([^\"]+)"', (HERE / "collector/build.gradle.kts").read_text()).group(1)
    sources = sorted((HERE / "fixtures").iterdir())
    before = {p.name: digest(p) for p in sources}
    with tempfile.TemporaryDirectory(prefix="kartograph-reference-experiment-") as temporary:
        work = Path(temporary)
        java_classes, kotlin_classes = work / "java-classes", work / "kotlin/build/classes/kotlin/main"
        javac_collector = work / "collector"
        command([jdk / "bin/javac", "-d", javac_collector, HERE / "JavaReferenceCollector.java"])
        java_rows = work / "java-references.tsv"
        command([jdk / "bin/java", "-cp", javac_collector, "JavaReferenceCollector",
                 HERE / "fixtures/JavaConstants.java", java_classes, java_rows])
        kotlin = work / "kotlin"
        (kotlin / "src/main/kotlin").mkdir(parents=True)
        shutil.copyfile(HERE / "fixtures/Constants.kt", kotlin / "src/main/kotlin/Constants.kt")
        (kotlin / "settings.gradle.kts").write_text(
            'pluginManagement { repositories { gradlePluginPortal(); mavenCentral() } }\nrootProject.name = "reference-fixture"\n')
        (kotlin / "build.gradle.kts").write_text(
            f'plugins {{ kotlin("jvm") version "{kotlin_version}" }}\nrepositories {{ mavenCentral() }}\n'
            'kotlin { jvmToolchain(17); compilerOptions { freeCompilerArgs.add("-Xplugin=${providers.gradleProperty("collector").get()}") } }\n')
        command([ROOT / "gradlew", "--offline", "--no-daemon", "-p", kotlin, f"-Pcollector={collector}", "compileKotlin"])
        assert before == {p.name: digest(p) for p in sources}, "sources changed during compilation"
        roots = [java_classes, kotlin_classes]
        graph = json.loads(command([ROOT / "cli/build/install/kartograph/bin/kartograph", "graph",
                                   "--classes", java_classes, "--classes", kotlin_classes, "--format", "json"]))
        mapping = json.loads((HERE / "fixture-mapping.json").read_text())
        validate_mapping(mapping)
        consumed = set()
        references, ir_references, compiler_versions = [], [], []
        for line in java_rows.read_text().splitlines() + (kotlin_classes / "compiler-references.tsv").read_text().splitlines():
            engine, caller, target, offset = line.split("\t")
            if engine == "compiler":
                compiler_versions.append(caller)
                continue
            if engine == "ir":
                if caller in {"probe.readObject", "probe.readTop", "probe.readCompanion"}:
                    ir_references.append((caller, target))
                continue
            if engine == "fir":
                # fixture 전용 명시 매핑이다. JVM 이름 추측을 일반 제품 mapper로 주장하지 않는다.
                if caller not in mapping or target not in mapping:
                    raise ValueError("unmapped or ambiguous compiler symbol")
                consumed.update((caller, target))
                caller, target = mapping[caller], mapping[target]
            references.append({"source": caller, "target": target, "sourceOffset": int(offset), "engine": engine})
        validate_mapping(mapping, consumed)
        stale_mapping = {**mapping, "probe/obsolete": "field:probe/obsolete#VALUE:I"}
        rejected_mappings = expect_mapping_rejected(stale_mapping, consumed)
        ambiguous_mapping = {**mapping, "probe/alias": next(iter(mapping.values()))}
        rejected_mappings += expect_mapping_rejected(ambiguous_mapping, set(ambiguous_mapping))
        assert compiler_versions == [kotlin_version], "compiler version differs from selected plugin"
        references.sort(key=lambda row: (row["source"], row["target"], row["sourceOffset"]))
        assert len(references) == 4, references
        assert len({row["target"] for row in references}) == 4
        assert not ir_references, "IR phase retained a tested use; revisit the comparison conclusion"
        expected_pairs = {(row["source"], row["target"]) for row in references}
        bytecode_uses = expected_pairs.intersection((e["source"], e["target"]) for e in graph["edges"])
        assert not bytecode_uses, "bytecode already contains a tested use"
        assert not any("shadow" in row["source"].lower() or "UNUSED" in row["target"] for row in references)
        controls = {
            "field:probe/JavaConstants#UNUSED:Ljava/lang/String;",
            "field:probe/Constants#UNUSED:Ljava/lang/String;",
            "field:probe/Holder#UNUSED:Ljava/lang/String;",
            "field:probe/NamedConstants#TOP_UNUSED:Ljava/lang/String;",
            "method:probe/JavaConstants#shadow()Ljava/lang/String;",
            "method:probe/NamedConstants#readShadow()Ljava/lang/String;",
        }
        assert controls.issubset(node["usr"] for node in graph["nodes"]), "control declaration was not compiled"
        assert not controls.intersection(row[key] for row in references for key in ("source", "target"))
        inputs = snapshot(sources, roots, VARIANT)
        sidecar = {"format": "experimental-compiler-references", "version": 1, "compiler": {"java": java_version, "kotlin": compiler_versions[0]},
                   "inputs": inputs, "graphSha256": hashlib.sha256(canonical(graph)).hexdigest(),
                   "references": references}
        enriched = enrich(graph, sidecar, snapshot(sources, roots, VARIANT))
        assert expected_pairs.issubset((e["source"], e["target"]) for e in enriched["edges"])
        rejected = expect_rejected(graph, sidecar, snapshot(sources, roots, "other-variant"))
        changed = copy.deepcopy(inputs); changed["sources"]["Constants.kt"] = "different-revision"
        rejected += expect_rejected(graph, sidecar, changed)
        changed = copy.deepcopy(inputs); changed["classes"][next(iter(changed["classes"]))] = "stale-output"
        rejected += expect_rejected(graph, sidecar, changed)
        changed = copy.deepcopy(sidecar); changed["references"][0]["target"] = "field:missing#VALUE:I"
        rejected += expect_rejected(graph, changed, inputs)
        changed_graph = copy.deepcopy(graph); changed_graph["edges"] = []
        rejected += expect_rejected(changed_graph, sidecar, inputs)
        report = ROOT / "build/reports/compiler-references"
        report.mkdir(parents=True, exist_ok=True)
        for name, document in [("sidecar.json", sidecar), ("bytecode.json", graph), ("enriched.json", enriched)]:
            (report / name).write_bytes(canonical(document))
        result = {"references": len(references), "bytecodeUseEdges": len(bytecode_uses), "irUseEdges": len(ir_references),
                  "enrichedUseEdges": len(expected_pairs.intersection((e["source"], e["target"]) for e in enriched["edges"])), "rejectedMismatches": rejected,
                  "rejectedMappings": rejected_mappings, "compiledControlDeclarations": len(controls), "unusedAndShadowControls": "passed"}
        (report / "result.json").write_text(json.dumps(result, indent=2) + "\n")
        print(json.dumps({**result, "elapsedSeconds": round(time.monotonic() - start, 3)}))


if __name__ == "__main__":
    main()
