#!/usr/bin/env python3
"""실물 AGP 8 layout/R field/test 근거와 아직 없는 XML file selection을 구분한다."""
import hashlib
import json
import os
from pathlib import Path
import shutil
import runpy
import tempfile
import xml.etree.ElementTree as ET

ROOT = Path(__file__).resolve().parents[1]
run_process = runpy.run_path(str(Path(__file__).with_name("resource_process_diagnostics.py")))["run"]
PACKAGE = "dev/kartograph/compat"
LAYOUTS = ("screen", "child", "parent", "unrelated")
LIMITATION = "XML identity and exact nonfinal R ownership are captured; XML file impact and include-to-code mapping remain unavailable."
TIMEOUT = 600


def require(condition, message):
    if not condition:
        raise RuntimeError(message)


def digest(path):
    return hashlib.sha256(path.read_bytes()).hexdigest()


def field(name):
    return "field:" + PACKAGE + "/R$layout#" + name + ":I"


def accessor(name):
    return "method:" + PACKAGE + "/ResourceAccess#" + name + "()I"


def test_method(name):
    return "method:" + PACKAGE + "/ResourceAccessTest#" + name + "()V"


def verify_field_path(report, name):
    """정확한 field access와 실행 가능한 test의 call 경로가 모두 있어야 한다."""
    target, caller, test = field(name), accessor(name), test_method(name)
    require(report.get("format") == "kartograph-impact" and report.get("version") == 1,
            "unexpected impact format")
    require(report.get("status") == "found" and not report.get("unresolved"), "field selection incomplete")
    require([row["usr"] for row in report["changed"]] == [target], "exact R field was not selected")
    by_id = {row["usr"]: row for row in report["affected"]}
    require(caller in by_id and test in by_id, "compiled accessor/test missing from field impact")
    unrelated = {usr for other in LAYOUTS if other != name
                 for usr in (accessor(other), test_method(other))}
    require(not unrelated.intersection(by_id), "unrelated layout accessor/test leaked into field impact")
    expected = [
        (caller, [caller, target], ["fieldAccess"]),
        (test, [test, caller, target], ["call", "fieldAccess"]),
    ]
    witnesses = []
    for node, nodes, kinds in expected:
        paths = [path for path in by_id[node]["paths"]
                 if path["nodes"] == nodes and [edge["kind"] for edge in path["edges"]] == kinds]
        require(bool(paths), "exact compiler field/call witness missing")
        path = paths[0]
        require(all(edge["source"] == nodes[index] and edge["target"] == nodes[index + 1]
                    and edge["origin"] == "bytecode" and edge["traversal"] == "dependency"
                    for index, edge in enumerate(path["edges"])), "unexpected witness origin or direction")
        witnesses.append({"usr": node, "nodes": nodes, "kinds": kinds})
    return witnesses


def verify_unmapped(report, path):
    """빈 보고서가 아니라 명시적인 미지원 파일 선택만 근거로 인정한다."""
    require(report.get("format") == "kartograph-impact" and report.get("version") == 1,
            "unexpected file-impact format")
    require(report.get("status") == "notFound", "resource selection behavior changed; review the experiment")
    require(report.get("changed") == [] and report.get("affected") == [], "unexpected XML mapping")
    unresolved = report.get("unresolved")
    require(isinstance(unresolved, list) and len(unresolved) == 1
            and unresolved[0].get("requested") == path
            and unresolved[0].get("reason") == "unmappedFile", "missing explicit unmapped resource evidence")


def verify_resource_bindings(snapshot, xml_hashes):
    """실제 AGP main root·생산자·R field 대응과 XML byte identity를 독립적으로 확인한다."""
    facts = snapshot.get("androidResources")
    bindings = snapshot.get("androidResourceBindings")
    require(isinstance(facts, dict) and isinstance(bindings, dict), "resource admission evidence missing")
    require(facts.get("admission") == "unverified" and facts.get("namespace") == PACKAGE.replace("/", "."),
            "unexpected raw resource facts namespace or admission")
    require(bindings.get("scope") == facts.get("scope") == snapshot.get("scope")
            and bindings.get("namespace") == facts.get("namespace"), "resource scope/namespace mismatch")
    definitions = {row["path"]: row for row in facts["definitions"]}
    outcomes = {row["key"]["name"]: row for row in bindings["outcomes"]}
    require(len(outcomes) == len(bindings["outcomes"])
            and set(outcomes) == {row["key"]["name"] for row in facts["definitions"]},
            "resource key outcomes are not exhaustive")
    for name in LAYOUTS:
        path = "src/main/res/layout/" + name + ".xml"
        require(path in definitions and definitions[path]["sourceSha256"] == xml_hashes[name],
                "captured XML bytes differ from fixture")
        require(outcomes.get(name, {}).get("field") == field(name)
                and outcomes[name].get("unavailable") is None, "exact nonfinal R field was not admitted")
    roots = sorted((row["input"] for row in facts["roots"]), key=lambda row: row["path"])
    require(roots == bindings["mainRoots"], "captured main inventory differs from admitted inventory")
    producer = [row for row in snapshot["provenance"]["witnesses"]
                if row["compiler"] == "agp-process-resources"
                and row["artifact"] == bindings["producerArtifact"] and row["scope"] == bindings["scope"]]
    require(len(producer) == 1, "resource producer missing or ambiguous")
    require(sorted((row for row in producer[0]["inputs"] if row["role"] == "android-resources"),
                   key=lambda row: row["path"]) == roots, "producer byte roots do not cover captured inventory")
    require(producer[0]["outputs"] == [bindings["output"]]
            and bindings["output"] in snapshot["provenance"]["inputs"], "selected output is not witnessed")
    parent_id = definitions["src/main/res/layout/parent.xml"]["id"]
    require(any(row["sourceDefinitionId"] == parent_id and row["kind"] == "literal"
                and row["target"] == {"namespace": PACKAGE.replace("/", "."), "type": "layout", "name": "child"}
                for row in facts["includes"]), "literal include fact missing")
    return {"status": "captured", "scope": bindings["scope"], "namespace": bindings["namespace"],
            "boundFields": [outcomes[name]["field"] for name in LAYOUTS],
            "mainRootCount": len(roots), "literalInclude": "parent-to-child"}


def verify_junit(directory):
    files = sorted(directory.glob("TEST-*.xml"))
    require(bool(files), "JUnit execution reports missing")
    observed = []
    for path in files:
        root = ET.parse(path).getroot()
        for case in root.iter("testcase"):
            require(not any(case.find(tag) is not None for tag in ("skipped", "failure", "error")),
                    "resource test skipped or failed")
            observed.append((case.get("classname"), case.get("name")))
    expected = [("dev.kartograph.compat.ResourceAccessTest", name) for name in LAYOUTS]
    require(sorted(observed) == sorted(expected), "unexpected executed resource-test inventory")
    return [{"class": owner, "method": method} for owner, method in sorted(observed)]


def log_summary(report):
    """검증된 픽스처 식별자와 지문만 출력하고 실행 경로·환경·원시 출력은 제외한다."""
    return {
        "format": report["format"], "version": report["version"], "status": report["status"],
        "sourceRevision": report["sourceRevision"], "toolchain": report["toolchain"],
        "coverage": {
            "exactFieldCallTestWitnesses": "verified",
            "resourceFieldOwnership": "captured",
            "otherLayoutAccessorTests": "excluded",
            "xmlFileSelection": "unmappedFile",
            "literalIncludeMapping": "unmapped",
            "layoutInflation": "notExecuted",
            "finalInlinedOtherAgpOverlaysBinding": "unmeasured",
            "oldSnapshotAfterXmlEdit": "stale",
        },
        "phases": [{
            "phase": phase["phase"], "snapshotSha256": phase["snapshotSha256"],
            "freshness": phase["freshness"], "xmlSha256": phase["xmlSha256"],
            "executedTests": phase["executedTests"], "fieldWitnesses": phase["fieldWitnesses"],
            "unmappedResources": phase["unmappedResources"], "resourceBindings": phase["resourceBindings"],
        } for phase in report["phases"]],
        "sourceHashesUnchanged": report["phases"][0]["sourceSha256"] == report["phases"][1]["sourceSha256"],
    }


def run(arguments, cwd, output, name, expected=0):
    result = run_process(arguments, cwd=cwd, timeout=TIMEOUT, phase=name, expected=expected)
    # 원시 로그는 이 실행의 진단 디렉터리에만 둔다. 공개 업로드는 result.json만 사용한다.
    (output / (name + ".stdout")).write_text(result.stdout, encoding="utf-8")
    (output / (name + ".stderr")).write_text(result.stderr, encoding="utf-8")
    require(result.returncode == expected, name + " failed; inspect its local diagnostic log")
    return result.stdout


def write(project, relative, text):
    path = project / relative
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text(text, encoding="utf-8")


def prepare(project):
    shutil.copytree(ROOT / "fixtures/agp-8-smoke", project, dirs_exist_ok=True,
                    ignore=shutil.ignore_patterns("build", ".gradle", "local.properties"))
    build = (project / "build.gradle").read_text(encoding="utf-8")
    require("com.android.tools.build:gradle:8.7.3" in build
            and "org.jetbrains.kotlin:kotlin-gradle-plugin:2.0.21" in build,
            "fixture toolchain changed; update the bounded experiment deliberately")
    with (project / "build.gradle").open("a", encoding="utf-8") as stream:
        stream.write("\ndependencies { testImplementation 'junit:junit:4.13.2' }\n")
    write(project, "gradle.properties", "android.nonFinalResIds=true\nandroid.nonTransitiveRClass=true\n")
    for name in LAYOUTS:
        body = ('<LinearLayout xmlns:android="http://schemas.android.com/apk/res/android" '
                'android:layout_width="match_parent" android:layout_height="match_parent">'
                '<include layout="@layout/child"/></LinearLayout>') if name == "parent" else (
                '<TextView xmlns:android="http://schemas.android.com/apk/res/android" '
                'android:layout_width="match_parent" android:layout_height="wrap_content" android:text="Before"/>')
        write(project, "src/main/res/layout/" + name + ".xml", body + "\n")
    methods = "\n".join("  public static int " + name + "() { return R.layout." + name + "; }" for name in LAYOUTS)
    write(project, "src/main/java/dev/kartograph/compat/ResourceAccess.java",
          "package dev.kartograph.compat;\npublic class ResourceAccess {\n" + methods + "\n}\n")
    tests = "\n".join("  @org.junit.Test public void " + name + "() { org.junit.Assert.assertTrue("
                      "ResourceAccess." + name + "() >= 0); }" for name in LAYOUTS)
    write(project, "src/test/java/dev/kartograph/compat/ResourceAccessTest.java",
          "package dev.kartograph.compat;\npublic class ResourceAccessTest {\n" + tests + "\n}\n")


def main():
    output = ROOT / "build/reports/agp-8-resource-impact"
    output.mkdir(parents=True, exist_ok=True)
    # 재실행 실패 시 이전 성공 보고서를 공개하지 않는다.
    result_path = output / "result.json"
    if result_path.exists():
        result_path.unlink()
    gradle_home = os.environ.get("GRADLE_8_HOME")
    require(bool(gradle_home), "GRADLE_8_HOME must point to the verified Gradle 8.10.2 installation")
    gradle = Path(gradle_home) / "bin/gradle"
    version = run([gradle, "--version"], ROOT, output, "gradle-version")
    require("Gradle 8.10.2" in version.splitlines(), "the experiment requires Gradle 8.10.2")
    sdk = os.environ.get("ANDROID_HOME")
    require(bool(sdk) and (Path(sdk) / "platforms/android-35").is_dir(), "Android platform 35 is required")
    binary = ROOT / "cli/build/install/kartograph/bin/kartograph"
    require(binary.is_file(), "build the existing CLI and plugin before this experiment")
    revision = run(["git", "rev-parse", "HEAD"], ROOT, output, "source-revision").strip()
    require(len(revision) == 40 and all(c in "0123456789abcdef" for c in revision), "invalid source revision")
    libraries = sorted((binary.parent.parent / "lib").glob("*.jar"))
    require(bool(libraries), "CLI distribution libraries missing")
    with tempfile.TemporaryDirectory(prefix="resource-impact-", dir=ROOT / "fixtures") as temporary:
        project = Path(temporary)
        prepare(project)
        snapshot = project / "build/reports/kartograph/debug-snapshot.json"
        bindings = project / "build/kartograph/debug-input-bindings.json"
        gradle_command = [gradle, "--no-daemon", "--console=plain", "-p", project]
        source_paths = sorted([*project.glob("src/**/*.java"), *project.glob("src/**/*.kt")])
        source_hashes = {str(path.relative_to(project)): digest(path) for path in source_paths}
        layout_paths = {name: project / ("src/main/res/layout/" + name + ".xml") for name in LAYOUTS}
        parent = ET.parse(layout_paths["parent"]).getroot()
        require(parent.find("include").get("layout") == "@layout/child", "literal layout include missing")
        phases = []
        for phase in ("before", "after"):
            if phase == "after":
                layout_paths["child"].write_text(
                    layout_paths["child"].read_text(encoding="utf-8").replace('android:text="Before"', 'android:text="After"'),
                    encoding="utf-8")
                stale = json.loads(run([binary, "verify-snapshot", "--graph-file", snapshot,
                                       "--project", project, "--input-bindings", bindings],
                                      ROOT, output, "resource-edit-stale", expected=1))
                require(stale.get("status") == "stale", "XML-only edit did not invalidate captured inputs")
            run(gradle_command + ["kartographSnapshotDebug", "testDebugUnitTest", "--rerun-tasks"],
                ROOT, output, phase + "-build-tests")
            freshness = json.loads(run([binary, "verify-snapshot", "--graph-file", snapshot,
                                        "--project", project, "--input-bindings", bindings],
                                       ROOT, output, phase + "-freshness"))
            require(freshness.get("status") == "matched", "resource snapshot is not verified")
            require(source_hashes == {str(path.relative_to(project)): digest(path) for path in source_paths},
                    "code/test sources changed during XML-only experiment")
            admitted = verify_resource_bindings(json.loads(snapshot.read_text(encoding="utf-8")),
                {name: digest(path) for name, path in layout_paths.items()})
            executed = verify_junit(project / "build/test-results/testDebugUnitTest")
            witnesses, unmapped = {}, []
            for name in LAYOUTS:
                report = json.loads(run([binary, "impact", field(name), "--graph-file", snapshot, "--all"],
                                        ROOT, output, phase + "-" + name + "-field"))
                witnesses[name] = verify_field_path(report, name)
                path = str(layout_paths[name].relative_to(project))
                file_report = json.loads(run([binary, "impact", "--file", path, "--graph-file", snapshot],
                                             ROOT, output, phase + "-" + name + "-xml", expected=64))
                verify_unmapped(file_report, path)
                unmapped.append(path)
            phases.append({"phase": phase, "snapshotSha256": digest(snapshot),
                           "xmlSha256": {name: digest(path) for name, path in layout_paths.items()},
                           "sourceSha256": source_hashes, "freshness": "matched",
                           "executedTests": executed, "fieldWitnesses": witnesses,
                           "unmappedResources": unmapped, "resourceBindings": admitted})
        require(phases[0]["xmlSha256"]["child"] != phases[1]["xmlSha256"]["child"], "XML bytes did not change")
        require(all(phases[0]["xmlSha256"][name] == phases[1]["xmlSha256"][name]
                    for name in LAYOUTS if name != "child"), "unrequested XML changes")
        report = {"format": "kartograph-agp-resource-impact-experiment", "version": 1,
                  "status": "evidenceEstablishedWithKnownMappingGap",
                  "toolchain": {"agp": "8.7.3", "gradle": "8.10.2", "kgp": "2.0.21",
                                "javaTarget": 17, "compileSdk": 35, "nonFinalResIds": True},
                  "sourceRevision": revision, "fixtureBuildSha256": digest(project / "build.gradle"),
                  "cliLibrarySha256": {path.name: digest(path) for path in libraries},
                  "phases": phases, "include": {"parent": "layout/parent", "child": "layout/child"},
                  "limitations": [LIMITATION, "The unit tests execute accessors, not Android layout inflation.",
                                  "Final/inlined IDs, other AGP versions, overlays and binding XML are unmeasured."]}
        result_path.write_text(json.dumps(report, indent=2, sort_keys=True) + "\n", encoding="utf-8")
    print(json.dumps(log_summary(report), sort_keys=True))
    print("AGP resource experiment established exact R field/test evidence and the explicit XML mapping gap.")


if __name__ == "__main__":
    main()
