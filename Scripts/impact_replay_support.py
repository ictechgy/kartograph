"""공개 변경 재현의 실제 테스트·입력 수집·snapshot 검증을 공유한다."""
import hashlib
import json
import os
from pathlib import Path
import subprocess
import time
import xml.etree.ElementTree as ET
import zipfile


def file_sha256(path):
    """파일 내용 지문을 계산한다."""
    with Path(path).open("rb") as stream:
        return hashlib.file_digest(stream, "sha256").hexdigest()


def graph_class_owners(document):
    """클래스 선언 ID로 소유자를 읽어 이름 안의 # 문자를 보존한다."""
    graph = document["graph"]
    ids = [graph["stringTable"][row[0]] for row in graph["nodes"]] if document["version"] == 2 else [row["usr"] for row in graph["nodes"]]
    return {value.removeprefix("class:") for value in ids if value.startswith("class:")}


def junit_results(repo, reports):
    """실제 보고서의 테스트 상태를 수집하며 기존 실패도 보존한다."""
    rows = []
    for report in sorted(reports):
        for test in ET.parse(report).iter("testcase"):
            failed = test.find("failure") is not None or test.find("error") is not None
            skipped = test.find("skipped") is not None
            rows.append({"report": report.relative_to(repo).as_posix(), "class": test.get("classname"),
                         "name": test.get("name"), "status": "failed" if failed else "skipped" if skipped else "passed",
                         "flaky": test.find("flakyFailure") is not None or test.find("rerunFailure") is not None})
    return rows


def maven_inputs(repo):
    """Surefire가 기록한 실제 module/class path에서 입력을 얻는다."""
    reports = sorted(repo.rglob("target/surefire-reports/TEST-*.xml"))
    if not reports:
        raise ValueError("no actual Maven test report")
    paths = []
    for report in reports:
        properties = ET.parse(report).find("properties")
        if properties is None:
            continue
        for key in ("jdk.module.path", "surefire.test.class.path"):
            item = properties.find("property[@name='" + key + "']")
            if item is not None:
                for value in item.get("value", "").split(os.pathsep):
                    if value:
                        path = Path(value).resolve()
                        if path not in paths:
                            paths.append(path)
    roots = [p for p in paths if p.is_dir() and p.is_relative_to(repo) and any(p.rglob("*.class"))]
    jars = [p for p in paths if p.is_file() and p.suffix == ".jar"]
    if not roots:
        raise ValueError("no compiled project roots in test runtime inputs")
    return roots, jars


def capture_pristine(repo, output, binary, java_home, revision, scope, roots, jars):
    """oracle 주입 전 source·class와 snapshot을 묶어 검증하고 보관한다."""
    repo = Path(repo).resolve()
    output = Path(output).resolve()
    tracked = subprocess.run(["git", "-C", str(repo), "ls-files", "-z"], capture_output=True,
                             text=True, check=True, timeout=30).stdout.split("\0")
    sources = sorted(p for p in tracked if p.endswith((".java", ".kt")) and
                     (repo / p).is_file() and not (repo / p).is_symlink())
    hashes = {name: file_sha256(repo / name) for name in sources}
    (output / "pristine-source-sha256.json").write_text(json.dumps(hashes, indent=2) + "\n")
    command = [str(binary), "snapshot", "--project", str(repo), "--include-paths", "--compact",
               "--revision", revision, "--scope", scope]
    for path in roots:
        command += ["--classes", str(path)]
    for path in jars:
        command += ["--classpath", str(path)]
    env = os.environ.copy()
    env["JAVA_HOME"] = str(java_home)
    start = time.perf_counter()
    snapshot = output / "pristine-graph.json"
    with snapshot.open("w") as stdout, (output / "pristine-snapshot.stderr").open("w") as stderr:
        process = subprocess.run(command, env=env, stdout=stdout, stderr=stderr, timeout=240)
    seconds = time.perf_counter() - start
    if process.returncode:
        raise RuntimeError("pristine capture failed; inspect the local stage log")
    if hashes != {name: file_sha256(repo / name) for name in sources}:
        raise RuntimeError("sources changed during pristine capture")
    document = json.loads(snapshot.read_text())
    graph = document["graph"]
    ids = [graph["stringTable"][row[0]] for row in graph["nodes"]] if document["version"] == 2 else [row["usr"] for row in graph["nodes"]]
    owners = graph_class_owners(document)
    class_owners = set()
    files = []
    for root in roots:
        for file in sorted(root.rglob("*.class")):
            relative = file.relative_to(root).as_posix()
            files.append(file)
            if not relative.endswith(("module-info.class", "package-info.class")):
                class_owners.add(relative[:-6])
    if owners != class_owners:
        raise RuntimeError("snapshot owners differ from the compiled class inventory")
    archive = output / "pristine-classes.zip"
    with zipfile.ZipFile(archive, "w", compression=zipfile.ZIP_DEFLATED) as target:
        for file in files:
            target.write(file, file.relative_to(repo).as_posix())
    result = {"graphSha256": file_sha256(snapshot), "classArchiveSha256": file_sha256(archive),
              "classFiles": len(files), "graphOwners": len(owners), "nodes": len(ids),
              "edges": len(graph["edges"]), "sourceInventorySha256": file_sha256(output / "pristine-source-sha256.json"),
              "sourceCommit": revision, "sourceFiles": len(sources), "seconds": seconds,
              "bytes": snapshot.stat().st_size, "rootPaths": [p.relative_to(repo).as_posix() for p in roots],
              "dependencyJars": len(jars), "scope": "original source and tests before hidden test patch"}
    (output / "pristine-evidence.json").write_text(json.dumps(result, indent=2) + "\n")
    (output / "classpath-local.json").write_text(json.dumps({"classes": [str(p) for p in roots],
                                                             "classpath": [str(p) for p in jars]}, indent=2) + "\n")
    return result
