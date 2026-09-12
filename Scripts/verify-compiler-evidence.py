#!/usr/bin/env python3
"""실제 Gradle compiler 수집 결과를 snapshot/impact와 실패 경로까지 검증한다."""

from __future__ import annotations

import argparse
import hashlib
import json
import os
import re
from pathlib import Path
import shutil
import subprocess
import sys
import tempfile
import time
import xml.etree.ElementTree as ET

ROOT = Path(__file__).resolve().parent.parent


class CheckFailure(Exception):
    pass


def quoted(value: str | Path) -> str:
    return "'" + str(value).replace("\\", "\\\\").replace("'", "\\'").replace("\n", "\\n").replace("\r", "\\r") + "'"


def digest(path: Path) -> str:
    value = hashlib.sha256()
    with path.open("rb") as stream:
        for block in iter(lambda: stream.read(65536), b""):
            value.update(block)
    return value.hexdigest()


def fingerprint(path: Path) -> str:
    value = hashlib.sha256()
    for item in (b"file", digest(path).encode()):
        value.update(len(item).to_bytes(4, "big"))
        value.update(item)
    return value.hexdigest()


def verified_jars(directory: Path, metadata: Path) -> list[Path]:
    xml = ET.parse(metadata).getroot()
    ns = {"v": xml.tag.partition("}")[0].removeprefix("{")}
    trusted: dict[str, set[str]] = {}
    for artifact in xml.findall(".//v:artifact", ns):
        trusted.setdefault(artifact.attrib["name"], set()).update(node.attrib["value"] for node in artifact.findall("v:sha256", ns))
    jars = sorted(directory.glob("*.jar"))
    if not jars or any(digest(path) not in trusted.get(path.name, set()) for path in jars):
        raise CheckFailure("Dagger fixture JARs are missing or differ from verified metadata")
    return jars


class Runner:
    def __init__(self, reports: Path):
        self.reports = reports
        self.steps: list[dict] = []

    def run(self, name: str, command: list, cwd: Path, expected: int = 0) -> str:
        start = time.perf_counter()
        try:
            result = subprocess.run([str(item) for item in command], cwd=cwd, capture_output=True, text=True, timeout=300)
        except (OSError, subprocess.TimeoutExpired):
            raise CheckFailure(f"{name}: tool unavailable or timed out") from None
        (self.reports / f"{name}.stdout").write_text(result.stdout, encoding="utf-8")
        (self.reports / f"{name}.stderr").write_text(result.stderr, encoding="utf-8")
        self.steps.append({"name": name, "exit": result.returncode, "seconds": round(time.perf_counter() - start, 3)})
        if result.returncode != expected:
            raise CheckFailure(f"{name}: expected exit {expected}, actual {result.returncode}; inspect local stage logs")
        return result.stdout


def fixture(project: Path, kind: str, plugin: Path, collector: Path, dagger: list[Path]) -> tuple[Path, str]:
    kotlin = kind == "kotlin"
    filename = {"java": "JavaConstants.java", "kotlin": "KotlinConstants.kt", "dagger": "DaggerBindings.java"}[kind]
    source_root = "src/main/kotlin" if kotlin else "src/main/java"
    source = project / source_root / filename
    source.parent.mkdir(parents=True)
    shutil.copyfile(ROOT / "compiler-collectors/tests/fixtures" / filename, source)
    other = source.parent / ("Other.kt" if kotlin else "Other.java")
    other.write_text("package fixture\nfun other() = 0\n" if kotlin else "package fixture; class Other { int other(){return 0;} }\n")
    (project / "settings.gradle").write_text("rootProject.name='compiler-evidence-fixture'\nbuildCache { local { directory=file('.fixture-cache') } }\n")
    verification = project / "gradle/verification-metadata.xml"
    verification.parent.mkdir()
    shutil.copyfile(ROOT / "compiler-collectors/gradle/verification-metadata.xml", verification)
    kotlin_dependency = "classpath 'org.jetbrains.kotlin:kotlin-gradle-plugin:2.4.10'" if kotlin else ""
    language_plugin = "org.jetbrains.kotlin.jvm" if kotlin else "java"
    compiler_name = "compileKotlin" if kotlin else "compileJava"
    processor_paths = [collector, *dagger] if kind == "dagger" else [collector]
    dependencies = "dependencies { implementation files(" + ",".join(map(quoted, dagger)) + ") }" if kind == "dagger" else ""
    configure = """
        pluginClasspath.from(files(COLLECTOR))
        compilerOptions.jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
        compilerOptions.freeCompilerArgs.addAll('-P','plugin:kartograph.compiler-evidence:root='+rootUri,
            '-P','plugin:kartograph.compiler-evidence:output='+outputUri,
            '-P','plugin:kartograph.compiler-evidence:token='+tokenUri)
    """.replace("COLLECTOR", quoted(collector)) if kotlin else """
        options.annotationProcessorPath=files(PROCESSORS)
        options.compilerArgs.add('-Xplugin:KartographEvidence collector=KIND root='+rootUri+' output='+outputUri+' token='+tokenUri)
    """.replace("PROCESSORS", ",".join(map(quoted, processor_paths))).replace("KIND", "dagger-bindings" if kind == "dagger" else "javac-constants")
    if kind == "dagger":
        configure += "options.compilerArgs.addAll('-Akartograph.evidence.root='+rootUri,'-Akartograph.evidence.output='+outputUri,'-Akartograph.evidence.token='+tokenUri)\n"
    register = """
        dev.kartograph.gradle.KotlinCompilerWitnesses.INSTANCE.kotlinCompile(project, compiler, 'sample:main',
            files(SOURCES), files('build.gradle','settings.gradle'), launcher,
            files(configurations.named('kotlinBuildToolsApiClasspath'), extraInputs), true)
    """ if kotlin else """
        dev.kartograph.gradle.CompilerWitnesses.INSTANCE.javaCompile(project, compiler, 'sample:main',
            files(SOURCES), files('build.gradle','settings.gradle'), extraInputs, true)
    """
    register = register.replace("SOURCES", quoted(source_root))
    kotlin_inputs = """
        candidates.addAll(task.libraries.files); candidates.addAll(task.pluginClasspath.files); candidates.addAll(task.friendPaths.files)
        candidates.add(new File(org.jetbrains.kotlin.gradle.tasks.KotlinCompile.protectionDomain.codeSource.location.toURI()).canonicalFile)
    """ if kotlin else ""
    script = """
        buildscript { repositories { mavenCentral() }; dependencies { classpath files(PLUGIN); KOTLIN_DEPENDENCY } }
        apply plugin: LANGUAGE_PLUGIN
        apply plugin: 'io.github.ictechgy.kartograph'
        repositories { mavenCentral() }
        DEPENDENCIES
        def compiler=tasks.named(COMPILER)
        def token=dev.kartograph.gradle.CompilerWitnesses.INSTANCE.inputTokenFile(project,compiler)
        def evidence=dev.kartograph.gradle.CompilerWitnesses.INSTANCE.evidenceDirectory(project,compiler)
        def launcher=javaToolchains.launcherFor { languageVersion=JavaLanguageVersion.of(17) }
        def output=evidence.map { it.file('references.tsv') }
        def rootUri=project.layout.projectDirectory.asFile.toPath().toUri().toASCIIString()
        def outputUri=output.get().asFile.toPath().toUri().toASCIIString()
        def tokenUri=token.get().asFile.toPath().toUri().toASCIIString()
        def replay=providers.gradleProperty('replayEvidence').orNull
        def replayFile=replay==null ? null : file(replay)
        def partial=providers.gradleProperty('partialEvidence').isPresent()
        def extraInputs=files(replayFile==null ? [] : [replayFile])
        compiler.configure {
            CONFIGURE
            inputs.property('fixturePartial',partial)
            if(replayFile!=null) inputs.file(replayFile)
            doLast {
                if(replayFile!=null) output.get().asFile.bytes=replayFile.bytes
                if(partial) output.get().asFile.text=output.get().asFile.readLines().findAll { !it.startsWith('source\\t') }.join('\\n')+'\\n'
            }
        }
        REGISTER
        tasks.register('describeEvidenceInputs') {
            dependsOn compiler
            doLast {
                def task=compiler.get()
                def candidates=new LinkedHashSet(task.inputs.files.files)
                KOTLIN_INPUTS
                candidates.add(new File(launcher.get().metadata.installationPath.asFile,'lib/modules').canonicalFile)
                file('input-candidates-local.json').text=groovy.json.JsonOutput.toJson(candidates.findAll { it.isFile() }.collect { it.canonicalPath })
            }
        }
    """
    replacements = {"PLUGIN": quoted(plugin), "KOTLIN_DEPENDENCY": kotlin_dependency, "LANGUAGE_PLUGIN": quoted(language_plugin),
                    "DEPENDENCIES": dependencies, "COMPILER": quoted(compiler_name), "CONFIGURE": configure, "REGISTER": register,
                    "KOTLIN_INPUTS": kotlin_inputs}
    script = re.sub(r"\b(?:" + "|".join(replacements) + r")\b", lambda match: replacements[match[0]], script)
    (project / "build.gradle").write_text(script, encoding="utf-8")
    return source, compiler_name


def snapshot(runner: Runner, name: str, binary: Path, project: Path, compiler: str) -> tuple[Path, dict, dict]:
    witness = project / f"build/kartograph/witnesses/{compiler}/witness.json"
    value = json.loads(witness.read_text())
    candidates = {fingerprint(Path(path)): path for path in json.loads((project / "input-candidates-local.json").read_text())}
    bindings = {item["path"]: candidates[item["sha256"]] for item in value["inputs"] if item["path"].startswith("external/")}
    classes = project / value["outputs"][0]["path"]
    document = project / "snapshot.json"
    command = [binary, "snapshot", "--project", project, "--classes", classes, "--scope", "sample:main", "--build-witness", witness]
    for item in value["compilerEvidence"]:
        if item["role"] == "compilerEvidence":
            command += ["--compiler-evidence", project / item["path"]]
    for key, path in bindings.items():
        command += ["--input", key + "=" + path]
    document.write_text(runner.run(name + "-snapshot", command, project), encoding="utf-8")
    return document, value, bindings


def verify(runner: Runner, name: str, binary: Path, project: Path, snapshot_file: Path, bindings: dict, expected: int = 0, scope: str = "sample:main") -> dict:
    command = [binary, "verify-snapshot", "--project", project, "--graph-file", snapshot_file, "--scope", scope]
    for key, path in bindings.items():
        command += ["--input", key + "=" + path]
    return json.loads(runner.run(name, command, project, expected))


def impact(runner: Runner, name: str, binary: Path, project: Path, snapshot_file: Path, symbol: str) -> dict:
    return json.loads(runner.run(name, [binary, "impact", symbol, "--graph-file", snapshot_file, "--depth", "8", "--limit", "1000"], project))


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--binary", type=Path, default=ROOT / "cli/build/install/kartograph/bin/kartograph")
    parser.add_argument("--plugin", type=Path)
    parser.add_argument("--collector", type=Path, default=ROOT / "compiler-collectors/build/libs/kartograph-compiler-collectors.jar")
    parser.add_argument("--dagger-jars", type=Path, default=ROOT / "experiments/dagger-bindings/build/fixture-dependencies")
    parser.add_argument("--report-dir", type=Path, default=ROOT / "build/reports/compiler-evidence-integration")
    args = parser.parse_args()
    version = (ROOT / "VERSION").read_text().strip()
    plugin = (args.plugin or ROOT / f"gradle-plugin/build/libs/kartograph-gradle-plugin-{version}.jar").resolve()
    binary, collector = args.binary.resolve(), args.collector.resolve()
    reports = args.report_dir.resolve(); reports.mkdir(parents=True, exist_ok=True)
    (reports / "results.json").unlink(missing_ok=True)
    if not all(path.is_file() for path in (binary, collector, plugin)) or not os.environ.get("JAVA_HOME"):
        raise CheckFailure("build CLI/plugin/collectors and set JAVA_HOME to JDK 17 first")
    dagger = verified_jars(args.dagger_jars.resolve(), ROOT / "compiler-collectors/gradle/verification-metadata.xml")
    runner = Runner(reports); results = {}
    gradle = ROOT / "gradlew"
    with tempfile.TemporaryDirectory(prefix="kartograph-evidence-") as temporary:
        work = Path(temporary)
        for kind in ("java", "kotlin", "dagger"):
            project = work / (kind + " project")
            source, compiler = fixture(project, kind, plugin, collector, dagger)
            common = [gradle, "--no-daemon", "--build-cache"]
            runner.run(kind + "-build", common + ["--configuration-cache", compiler], project)
            again = runner.run(kind + "-unchanged", common + ["--configuration-cache", compiler], project)
            if "UP-TO-DATE" not in again or "Reusing configuration cache" not in again:
                raise CheckFailure(kind + ": compiler/configuration cache was not reused")
            witness_path = project / f"build/kartograph/witnesses/{compiler}/witness.json"
            completed_receipt = witness_path.read_bytes()
            runner.run(kind + "-clean", common + ["clean"], project)
            restored = runner.run(kind + "-restore", common + ["--configuration-cache", compiler], project)
            if "FROM-CACHE" not in restored or witness_path.read_bytes() != completed_receipt:
                raise CheckFailure(kind + ": compiler receipts were not restored with matching cached outputs")
            runner.run(kind + "-inputs", common + ["--no-configuration-cache", "describeEvidenceInputs"], project)
            document, witness, bindings = snapshot(runner, kind, binary, project, compiler)
            checked = verify(runner, kind + "-verify", binary, project, document, bindings)
            if checked["status"] != "matched": raise CheckFailure(kind + ": current evidence did not match")
            other = source.parent / ("Other.kt" if kind == "kotlin" else "Other.java")
            previous_token = witness["evidenceToken"]
            other.write_text(other.read_text().replace("0", "1"))
            runner.run(kind + "-one-file-change", common + ["--configuration-cache", compiler], project)
            updated = json.loads(witness_path.read_text())
            if updated["evidenceToken"] == previous_token: raise CheckFailure(kind + ": changed source reused old evidence token")
            for receipt in updated["compilerEvidence"]:
                if receipt["role"] == "compilerEvidence":
                    inventory = [line for line in (project / receipt["path"]).read_text().splitlines() if line.startswith("source\t")]
                    if len(inventory) < 2: raise CheckFailure(kind + ": one-file rebuild lost the unchanged source inventory")
            document, witness, bindings = snapshot(runner, kind + "-updated", binary, project, compiler)
            if verify(runner, kind + "-updated-verify", binary, project, document, bindings)["status"] != "matched":
                raise CheckFailure(kind + ": one-file rebuild is not verified")
            wrong = verify(runner, kind + "-wrong-scope", binary, project, document, bindings, 1, "sample:other")
            if wrong["status"] != "stale": raise CheckFailure(kind + ": scope mismatch was not rejected")
            graph = json.loads(document.read_text())["graph"]
            references = [edge for edge in graph["edges"] if edge.get("origin") == "compilerReference"]
            expected = {"java": 1, "kotlin": 6, "dagger": 3}[kind]
            if len(references) != expected: raise CheckFailure(kind + ": unexpected compiler reference count")
            if any("UNUSED" in edge["target"] or "$Unused#" in edge["target"] for edge in references):
                raise CheckFailure(kind + ": unused control became a selected compiler reference")
            # 배포 CLI의 실제 영향 질의가 각 collector의 참조를 탐색해야 한다.
            symbol = references[0]["target"]
            affected = impact(runner, kind + "-impact", binary, project, document, symbol)
            if not any(row["usr"] == references[0]["source"] for row in affected["affected"]):
                raise CheckFailure(kind + ": compiler reference is missing from impact traversal")
            results[kind] = {"references": references, "matched": True, "affected": affected["observedAffected"],
                             "generatedSourceReceipts": sum(item["role"] == "compilerGeneratedSource" for item in witness["compilerEvidence"])}
            evidence = next(project / item["path"] for item in witness["compilerEvidence"] if item["role"] == "compilerEvidence")
            saved = evidence.read_bytes(); evidence.write_bytes(saved + b"\n")
            if verify(runner, kind + "-tampered", binary, project, document, bindings, 1)["status"] != "stale":
                raise CheckFailure(kind + ": changed raw evidence was accepted")
            evidence.write_bytes(saved)
            old_receipt = project / "old-evidence.tsv"; old_receipt.write_bytes(saved)
            if kind == "java":
                original = source.read_text(); stamp = source.stat()
                changed = original.replace('"same"', '"next"', 1)
                if changed == original or len(changed) != len(original): raise CheckFailure("Java mutation fixture is invalid")
                source.write_text(changed); os.utime(source, ns=(stamp.st_atime_ns, stamp.st_mtime_ns))
                verify(runner, "java-preserved-mtime", binary, project, document, bindings, 1)
                replay = runner.run("java-old-sidecar-replay", common + ["--configuration-cache", compiler, "-PreplayEvidence=" + str(old_receipt)], project, 1)
                if (project / f"build/kartograph/witnesses/{compiler}/witness.json").exists():
                    raise CheckFailure("replayed sidecar left a success witness")
                source.write_text(original)
                partial = runner.run("java-partial-sidecar", common + ["--configuration-cache", compiler, "-PpartialEvidence=true"], project, 1)
                if "compiler evidence is partial" not in partial + (reports / "java-partial-sidecar.stderr").read_text():
                    raise CheckFailure("partial source inventory did not produce the expected diagnostic")
                if witness_path.exists(): raise CheckFailure("partial source inventory left a success witness")
                runner.run("java-rebuild", common + ["--configuration-cache", compiler], project)
                source.unlink(); other.unlink()
                runner.run("java-source-deletion", common + ["--configuration-cache", compiler], project)
                if witness_path.exists(): raise CheckFailure("source deletion left a success witness")
            if kind == "dagger":
                runtime = runner.run("dagger-runtime", [Path(os.environ["JAVA_HOME"]) / "bin/java", "-cp", os.pathsep.join([str(project / witness["outputs"][0]["path"]), *map(str, dagger)]), "fixture.DaggerBindings"], project)
                if runtime.strip() != "selected": raise CheckFailure("Dagger runtime did not select the expected service")
                source.write_text(source.read_text().replace('@Named("selected") Service service()', '@Named("missing") Service service()'))
                runner.run("dagger-missing-binding", common + ["--configuration-cache", compiler], project, 1)
                if (project / f"build/kartograph/witnesses/{compiler}/witness.json").exists():
                    raise CheckFailure("missing binding left a success witness")
                verify(runner, "dagger-failed-build-snapshot", binary, project, document, bindings, 1)
    result = {"status": "passed", "version": version, "collectorSha256": digest(collector), "pluginSha256": digest(plugin), "cases": results, "steps": runner.steps}
    (reports / "results.json").write_text(json.dumps(result, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    print(json.dumps({"status": "passed", "references": {key: len(value["references"]) for key, value in results.items()}}))
    return 0


if __name__ == "__main__":
    try:
        raise SystemExit(main())
    except (CheckFailure, OSError, ValueError, KeyError) as failure:
        message = str(failure) if isinstance(failure, CheckFailure) else "invalid or unavailable compiler evidence validation input"
        print("error: " + message, file=sys.stderr)
        raise SystemExit(1)
