#!/usr/bin/env python3
"""실제 javac/Kotlin 2.4.10/Dagger 2.59 collector 계약을 검증한다."""

from __future__ import annotations

import argparse
import base64
import hashlib
import os
from pathlib import Path
import shutil
import subprocess
import tempfile


HERE = Path(__file__).resolve().parent
FIXTURES = HERE / "fixtures"
DEFAULT_JDK = Path("/opt/homebrew/opt/openjdk@17/libexec/openjdk.jdk/Contents/Home")


def java_home() -> Path:
    configured = os.environ.get("JAVA_HOME")
    home = Path(configured) if configured else DEFAULT_JDK
    javac = home / "bin/javac"
    java = home / "bin/java"
    if not javac.is_file() or not java.is_file():
        raise AssertionError("JDK 17 javac/java are required")
    version = subprocess.run([str(javac), "-version"], capture_output=True, text=True, check=False)
    actual = version.stdout + version.stderr
    if not actual.startswith("javac 17."):
        raise AssertionError("collector tests require JDK 17")
    return home


def run(command: list[Path | str], cwd: Path, expected: int = 0) -> subprocess.CompletedProcess[str]:
    result = subprocess.run([str(item) for item in command], cwd=cwd, capture_output=True, text=True, timeout=180)
    if result.returncode != expected:
        raise AssertionError(
            f"command failed: {Path(str(command[0])).name}, expected {expected}, actual {result.returncode}\n"
            f"stderr={result.stderr[-2000:]}"
        )
    return result


def sha256(path: Path) -> str:
    return hashlib.sha256(path.read_bytes()).hexdigest()


def content_fingerprint(path: Path) -> str:
    raw = sha256(path).encode()
    digest = hashlib.sha256()
    for value in (b"file", raw):
        digest.update(len(value).to_bytes(4, "big"))
        digest.update(value)
    return digest.hexdigest()


def encoded(value: str) -> str:
    return base64.urlsafe_b64encode(value.encode()).decode().rstrip("=")


def expand_classpath(value: str) -> str:
    entries: list[str] = []
    for item in value.split(os.pathsep):
        if item.endswith("/*"):
            directory = Path(item[:-2])
            entries.extend(str(path) for path in sorted(directory.glob("*.jar")))
        else:
            entries.append(item)
    return os.pathsep.join(entries)


def evidence(path: Path) -> list[list[str]]:
    rows = [line.rstrip("\r").split("\t") for line in path.read_text(encoding="utf-8").splitlines() if line]
    if not rows or rows[0] != ["format", "kartograph-compiler-evidence", "1"]:
        raise AssertionError("raw evidence header is not canonical")
    return rows


def assert_evidence(path: Path, collector: str, source: Path, expected_edges: set[tuple[str, str, str]], artifact: Path, compiler_prefix: str) -> None:
    rows = evidence(path)
    headers = {row[0]: row[1] for row in rows[1:] if row[0] in {"collector", "compiler", "token", "artifact", "unmapped"}}
    if headers.get("collector") != collector:
        raise AssertionError(f"collector header mismatch: {headers}")
    if not headers.get("compiler", "").startswith(compiler_prefix):
        raise AssertionError(f"compiler version mismatch: {headers.get('compiler')}")
    if headers.get("artifact") != content_fingerprint(artifact):
        raise AssertionError("collector artifact fingerprint mismatch")
    if headers.get("unmapped") != "0":
        raise AssertionError(f"collector reported unmapped endpoints: {headers.get('unmapped')}")
    source_rows = [row for row in rows if row[0] == "source"]
    relative = source.name
    expected_source = ["source", encoded(relative), sha256(source)]
    if expected_source not in source_rows:
        raise AssertionError(f"compiler source inventory does not contain {relative}: {source_rows}")
    actual_edges = {(row[1], row[2], row[3]) for row in rows if row[0] == "edge"}
    expected_wire_edges = {(encoded(source_id), encoded(target_id), kind) for source_id, target_id, kind in expected_edges}
    if actual_edges != expected_wire_edges:
        raise AssertionError(f"compiler edges differ:\nexpected={sorted(expected_edges)}\nactual={sorted(actual_edges)}")
    leaked_values = {"same", "selected", "unused"}
    if any(field in leaked_values for row in rows for field in row):
        raise AssertionError("raw evidence leaked fixture values")


def java_test(javac: Path, collector: Path, work: Path) -> None:
    root = work / "java with spaces"
    root.mkdir()
    source = root / "JavaConstants.java"
    shutil.copyfile(FIXTURES / source.name, source)
    classes = root / "classes"
    sidecar = root / "javac.tsv"
    token = root / "token"
    token.write_text("1" * 64, encoding="utf-8")
    run([
        javac, "-g", "-proc:none", "-processorpath", collector,
        f"-Xplugin:KartographEvidence collector=javac-constants root={root.as_uri()} output={sidecar.as_uri()} token={token.as_uri()}",
        "-d", classes, source,
    ], root)
    expected = {
        ("method:fixture/JavaConstants#read()Ljava/lang/String;", "field:fixture/JavaConstants#USED:Ljava/lang/String;", "constant"),
    }
    assert_evidence(sidecar, "javac-constants", source, expected, collector, "17.")

    broken = root / "Broken.java"
    broken.write_text("package fixture; final class Broken {", encoding="utf-8")
    run([
        javac, "-g", "-proc:none", "-processorpath", collector,
        f"-Xplugin:KartographEvidence collector=javac-constants root={root.as_uri()} output={sidecar.as_uri()} token={token.as_uri()}",
        "-d", root / "failed-classes", source, broken,
    ], root, expected=1)
    if sidecar.exists():
        raise AssertionError("failed javac compilation published compiler evidence")

    shapes = root / "JavaShapes.java"
    shutil.copyfile(FIXTURES / shapes.name, shapes)
    package_info = root / "package-info.java"
    package_info.write_text('/** Inventory-only source unit. */\npackage fixture;\n', encoding="utf-8")
    run([javac, "-g", "-proc:none", "-processorpath", collector,
         f"-Xplugin:KartographEvidence collector=javac-constants root={root.as_uri()} output={sidecar.as_uri()} token={token.as_uri()}",
         "-d", root / "shape-classes", shapes, package_info], root)
    shape_rows = evidence(sidecar)
    shape_edges = {(row[1], row[2]) for row in shape_rows if row[0] == "edge"}
    expected_shapes = {(encoded(source), encoded("field:fixture/JavaShapes#USED:Ljava/lang/String;")) for source in (
        "method:fixture/JavaShapes#first()Ljava/lang/String;", "method:fixture/Helper#second()Ljava/lang/String;",
        "method:fixture/JavaShapes$Nested#<init>()V")}
    if shape_edges != expected_shapes or ["unmapped", "2"] not in shape_rows:
        raise AssertionError("javac attributed types or constructor mapping do not match actual JVM shapes")
    if not any(row[:2] == ["source", encoded("package-info.java")] for row in shape_rows):
        raise AssertionError("class-less source unit was omitted from compiler inventory")


def kotlin_test(java: Path, collector: Path, kotlin_classpath: str, work: Path) -> None:
    root = work / "kotlin"
    root.mkdir()
    source = root / "KotlinConstants.kt"
    shutil.copyfile(FIXTURES / source.name, source)
    classes = root / "classes"
    sidecar = root / "kotlin.tsv"
    token = root / "token"
    token.write_text("2" * 64, encoding="utf-8")
    classpath = kotlin_classpath.split(os.pathsep)
    stdlib = next((Path(path) for path in classpath if Path(path).name.startswith("kotlin-stdlib-2.4.10")), None)
    if stdlib is None:
        raise AssertionError("Kotlin 2.4.10 stdlib is missing from the test classpath")
    run([
        java, "-cp", kotlin_classpath, "org.jetbrains.kotlin.cli.jvm.K2JVMCompiler",
        "-no-reflect", "-jvm-target", "17", "-jdk-home", java.parent.parent,
        "-classpath", stdlib, f"-Xplugin={collector}",
        "-P", f"plugin:kartograph.compiler-evidence:root={root}",
        "-P", f"plugin:kartograph.compiler-evidence:output={sidecar}",
        "-P", f"plugin:kartograph.compiler-evidence:token={token}",
        "-d", classes, source,
    ], root)
    expected = {
        ("method:fixture/RenamedConstants#renamedRead()Ljava/lang/String;", "field:fixture/RenamedConstants#TOP_USED:Ljava/lang/String;", "constant"),
        ("method:fixture/RenamedConstants#readObject()Ljava/lang/String;", "field:fixture/Constants#USED:Ljava/lang/String;", "constant"),
        ("method:fixture/RenamedConstants#readTop()Ljava/lang/String;", "field:fixture/RenamedConstants#TOP_USED:Ljava/lang/String;", "constant"),
        ("method:fixture/RenamedConstants#readCompanion()Ljava/lang/String;", "field:fixture/Holder#USED:Ljava/lang/String;", "constant"),
        ("method:fixture/RenamedConstants#overloaded(I)Ljava/lang/String;", "field:fixture/Constants#USED:Ljava/lang/String;", "constant"),
        ("method:fixture/RenamedConstants#overloaded(Ljava/lang/String;)Ljava/lang/String;", "field:fixture/RenamedConstants#TOP_USED:Ljava/lang/String;", "constant"),
    }
    assert_evidence(sidecar, "kotlin-constants", source, expected, collector, "2.4.10")

    members = root / "KotlinMembers.kt"
    shutil.copyfile(FIXTURES / members.name, members)
    member_output = root / "members.tsv"
    run([java, "-cp", kotlin_classpath, "org.jetbrains.kotlin.cli.jvm.K2JVMCompiler", "-no-reflect", "-jvm-target", "17",
         "-jdk-home", java.parent.parent, "-classpath", stdlib, f"-Xplugin={collector}",
         "-P", f"plugin:kartograph.compiler-evidence:root={root}", "-P", f"plugin:kartograph.compiler-evidence:output={member_output}",
         "-P", f"plugin:kartograph.compiler-evidence:token={token}", "-d", root / "member-classes", members], root)
    member_edges = {
        ("method:fixture/Reader#read()Ljava/lang/String;", "field:fixture/MemberConstants#USED:Ljava/lang/String;", "constant"),
        ("method:fixture/Reader2#read()Ljava/lang/String;", "field:fixture/MemberConstants#USED:Ljava/lang/String;", "constant"),
        ("method:fixture/KotlinMembersKt#readNamed()Ljava/lang/String;", "field:fixture/NamedHolder#USED:Ljava/lang/String;", "constant"),
        ("method:fixture/KotlinMembersKt#readPrivate()Ljava/lang/String;", "field:fixture/MemberConstants#USED:Ljava/lang/String;", "constant"),
    }
    assert_evidence(member_output, "kotlin-constants", members, member_edges, collector, "2.4.10")

    library_root = work / "constant-library"
    library_root.mkdir()
    library_source = library_root / "Library.kt"
    library_source.write_text('package external.fixture\nconst val VALUE = "same"\n', encoding="utf-8")
    library_classes = library_root / "classes"
    run([java, "-cp", kotlin_classpath, "org.jetbrains.kotlin.cli.jvm.K2JVMCompiler", "-no-reflect", "-jvm-target", "17",
         "-jdk-home", java.parent.parent, "-classpath", stdlib, "-d", library_classes, library_source], library_root)
    misleading = root / "Misleading.kt"
    misleading.write_text('package external.fixture\nobject Nested { const val VALUE = "same" }\nfun readExternal() = VALUE\n', encoding="utf-8")
    misleading_output = root / "misleading.tsv"
    run([java, "-cp", kotlin_classpath, "org.jetbrains.kotlin.cli.jvm.K2JVMCompiler", "-no-reflect", "-jvm-target", "17",
         "-jdk-home", java.parent.parent, "-classpath", os.pathsep.join([str(stdlib), str(library_classes)]), f"-Xplugin={collector}",
         "-P", f"plugin:kartograph.compiler-evidence:root={root}", "-P", f"plugin:kartograph.compiler-evidence:output={misleading_output}",
         "-P", f"plugin:kartograph.compiler-evidence:token={token}", "-d", root / "misleading-classes", misleading], root)
    misleading_rows = evidence(misleading_output)
    if any(row[0] == "edge" for row in misleading_rows) or ["unmapped", "1"] not in misleading_rows:
        raise AssertionError("external constant was guessed to be a same-name nested project field")

    broken = root / "Broken.kt"
    broken.write_text("package fixture\nfun broken() =", encoding="utf-8")
    run([
        java, "-cp", kotlin_classpath, "org.jetbrains.kotlin.cli.jvm.K2JVMCompiler",
        "-no-reflect", "-jvm-target", "17", "-jdk-home", java.parent.parent,
        "-classpath", stdlib, f"-Xplugin={collector}",
        "-P", f"plugin:kartograph.compiler-evidence:root={root}",
        "-P", f"plugin:kartograph.compiler-evidence:output={sidecar}",
        "-P", f"plugin:kartograph.compiler-evidence:token={token}",
        "-d", root / "failed-classes", source, broken,
    ], root, expected=1)
    if sidecar.exists():
        raise AssertionError("failed Kotlin compilation published compiler evidence")


def dagger_test(javac: Path, java: Path, collector: Path, dagger_classpath: str, work: Path) -> None:
    root = work / "dagger"
    root.mkdir()
    source = root / "DaggerBindings.java"
    shutil.copyfile(FIXTURES / source.name, source)
    classes = root / "classes"
    sidecar = root / "dagger.tsv"
    token = root / "token"
    token.write_text("3" * 64, encoding="utf-8")
    processorpath = os.pathsep.join([str(collector), dagger_classpath])
    run([
        javac, "-g", "-cp", dagger_classpath, "-processorpath", processorpath,
        "-processor", "dagger.internal.codegen.ComponentProcessor",
        f"-Xplugin:KartographEvidence collector=dagger-bindings root={root} output={sidecar} token={token}",
        f"-Akartograph.evidence.root={root}", f"-Akartograph.evidence.output={sidecar}", f"-Akartograph.evidence.token={token}",
        "-d", classes, "-s", root / "generated", source,
    ], root)
    expected = {
        ("method:fixture/DaggerBindings$App#service()Lfixture/DaggerBindings$Service;", "method:fixture/DaggerBindings$Bindings#selected(Lfixture/DaggerBindings$Selected;)Lfixture/DaggerBindings$Service;", "binding"),
        ("method:fixture/DaggerBindings$Bindings#selected(Lfixture/DaggerBindings$Selected;)Lfixture/DaggerBindings$Service;", "method:fixture/DaggerBindings$Selected#<init>(Lfixture/DaggerBindings$Dependency;)V", "binding"),
        ("method:fixture/DaggerBindings$Selected#<init>(Lfixture/DaggerBindings$Dependency;)V", "method:fixture/DaggerBindings$Dependency#<init>()V", "binding"),
    }
    assert_evidence(sidecar, "dagger-bindings", source, expected, collector, "2.59")
    run([java, "-cp", os.pathsep.join([str(classes), dagger_classpath]), "fixture.DaggerBindings"], root)

    unpaired_sidecar = root / "unpaired.tsv"
    run([
        javac, "-g", "-cp", dagger_classpath, "-processorpath", processorpath,
        "-processor", "dagger.internal.codegen.ComponentProcessor",
        f"-Akartograph.evidence.root={root}", f"-Akartograph.evidence.output={unpaired_sidecar}", f"-Akartograph.evidence.token={token}",
        "-d", root / "unpaired-classes", "-s", root / "unpaired-generated", source,
    ], root, expected=1)
    if unpaired_sidecar.exists():
        raise AssertionError("unpaired processor published final compiler evidence")

    missing_source = root / "Missing.java"
    missing_source.write_text(source.read_text(encoding="utf-8").replace('@Named("selected") Service service()', '@Named("missing") Service service()'), encoding="utf-8")
    missing_sidecar = root / "missing.tsv"
    run([
        javac, "-g", "-cp", dagger_classpath, "-processorpath", processorpath,
        "-processor", "dagger.internal.codegen.ComponentProcessor",
        f"-Xplugin:KartographEvidence collector=dagger-bindings root={root} output={missing_sidecar} token={token}",
        f"-Akartograph.evidence.root={root}", f"-Akartograph.evidence.output={missing_sidecar}", f"-Akartograph.evidence.token={token}",
        "-d", root / "missing-classes", "-s", root / "missing-generated", missing_source,
    ], root, expected=1)
    if missing_sidecar.exists():
        raise AssertionError("missing-binding compilation published Dagger evidence")

    library = root / "LibraryModule.java"
    shutil.copyfile(FIXTURES / library.name, library)
    library_output = root / "library.tsv"
    run([javac, "-g", "-cp", dagger_classpath, "-processorpath", processorpath, "-processor", "dagger.internal.codegen.ComponentProcessor",
         f"-Xplugin:KartographEvidence collector=dagger-bindings root={root} output={library_output} token={token}",
         f"-Akartograph.evidence.root={root}", f"-Akartograph.evidence.output={library_output}", f"-Akartograph.evidence.token={token}",
         "-d", root / "library-classes", "-s", root / "library-generated", library], root)
    assert_evidence(library_output, "dagger-bindings", library, set(), collector, "2.59")


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("--collector", type=Path, required=True)
    parser.add_argument("--kotlin-classpath", required=True)
    parser.add_argument("--dagger-classpath", required=True)
    args = parser.parse_args()
    if not args.collector.is_file():
        raise AssertionError("collector jar is missing")
    home = java_home()
    javac = home / "bin/javac"
    java = home / "bin/java"
    dagger_classpath = expand_classpath(args.dagger_classpath)
    with tempfile.TemporaryDirectory(prefix="kartograph-compiler-collectors-") as temporary:
        work = Path(temporary)
        staging_classes = work / "stage-test-classes"
        run([javac, "-cp", args.collector, "-d", staging_classes,
             HERE / "java/dev/kartograph/collectors/DaggerEvidenceFilesTest.java"], work)
        run([java, "-cp", os.pathsep.join([str(staging_classes), str(args.collector)]),
             "dev.kartograph.collectors.DaggerEvidenceFilesTest", work / "stages"], work)
        java_test(javac, args.collector, work)
        kotlin_test(java, args.collector, args.kotlin_classpath, work)
        dagger_test(javac, java, args.collector, dagger_classpath, work)
    print("compiler collectors verified: javac constants, Kotlin JVM identities, Dagger selected bindings, failures rejected")


if __name__ == "__main__":
    main()
