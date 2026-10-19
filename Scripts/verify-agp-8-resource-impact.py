#!/usr/bin/env python3
"""실물 AGP 8 XML 선택·literal include·정확한 R field/test 근거를 각각 검증한다."""
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
LIMITATION = "XML impact uses captured exact R ownership and recorded literal includes; runtime inflation and configuration winners remain unmeasured."
TIMEOUT = 600
ABSENT_ROOT = "src/declaredAbsent/res"


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



def verify_xml_impact(report, name, xml_sha256):
    """XML→키→field 기록과 실제 bytecode 소비자 경로를 각각 검증한다."""
    names = ("child", "parent") if name == "child" else (name,)
    expected_fields = {field(value) for value in names}
    require(report.get("format") == "kartograph-impact" and report.get("version") == 1,
            "unexpected XML impact format")
    require(report.get("status") == "found" and report.get("unresolved") == [],
            "admitted XML selection is incomplete")
    require({row["usr"] for row in report["changed"]} == expected_fields,
            "XML selected different R fields")
    require(report.get("selectedByRevision") == {"current": sorted(expected_fields)},
            "XML fields lost revision-local selection")
    selection = report.get("resourceSelections", {}).get("current", {})
    require(selection.get("status") == "admitted" and selection.get("coverage") == "partial"
            and selection.get("scope") == "::debug" and selection.get("namespace") == PACKAGE.replace("/", "."),
            "XML resource selection provenance missing")
    path = "src/main/res/layout/" + name + ".xml"
    receipts = selection.get("files", [])
    require(len(receipts) == 1 and receipts[0].get("requested") == path and receipts[0].get("status") == "matched",
            "XML exact file receipt missing")
    definitions = receipts[0].get("definitions", [])
    key = {"namespace": PACKAGE.replace("/", "."), "type": "layout", "name": name}
    require(len(definitions) == 1 and definitions[0].get("path") == path
            and definitions[0].get("sourceSha256") == xml_sha256 and definitions[0].get("key") == key,
            "XML source identity differs from changed fixture")
    resources = selection.get("resources", [])
    require(len(resources) == len(names)
            and {row.get("field") for row in resources} == expected_fields
            and all(row.get("unavailable") is None and row["key"] == {
                "namespace": PACKAGE.replace("/", "."), "type": "layout", "name": value}
                for value in names for row in resources if row.get("field") == field(value)),
            "resource key-to-field evidence differs")
    require(selection.get("selectedFields") == sorted(expected_fields),
            "resource seed inventory differs")
    binding = selection.get("binding", {})
    require(binding.get("producerArtifact") == ":processDebugResources"
            and all(isinstance(binding.get(label), str) and len(binding[label]) == 64
                    for label in ("resourceFactsSha256", "producerSha256"))
            and binding.get("output", {}).get("role") == "classes",
            "resource producer/output identity missing")
    if name == "child":
        parent = next(row for row in resources if row["key"]["name"] == "parent")
        parent_ids = {definition["id"] for definition in parent["definitions"]}
        require(any(row.get("sourceDefinitionId") in parent_ids and row.get("kind") == "literal"
                    and row.get("target") == key for row in selection.get("includes", [])),
                "child XML did not retain literal parent include evidence")
    by_id = {row["usr"]: row for row in report["affected"]}
    witnesses = []
    for value in names:
        target, caller, test = field(value), accessor(value), test_method(value)
        for usr, nodes, kinds in ((caller, [caller, target], ["fieldAccess"]),
                                 (test, [test, caller, target], ["call", "fieldAccess"])):
            require(usr in by_id, "XML-selected compiled accessor/test is absent")
            paths = [item for item in by_id[usr]["paths"] if item["revision"] == "current"
                     and item["nodes"] == nodes and [edge["kind"] for edge in item["edges"]] == kinds]
            require(bool(paths), "XML-selected bytecode witness missing")
            require(all(edge["source"] == nodes[index] and edge["target"] == nodes[index + 1]
                        and edge["origin"] == "bytecode" and edge["traversal"] == "dependency"
                        for index, edge in enumerate(paths[0]["edges"])), "XML witness origin/direction differs")
            witnesses.append({"usr": usr, "nodes": nodes, "kinds": kinds})
    require(not {usr for other in LAYOUTS if other not in names
                 for usr in (accessor(other), test_method(other))}.intersection(by_id),
            "unrelated layout accessor/test leaked into XML impact")
    return {"file": path, "sourceSha256": xml_sha256, "selectedFields": sorted(expected_fields),
            "witnesses": witnesses, "includeParent": name == "child",
            "resourceFactsSha256": binding["resourceFactsSha256"], "coverage": "partial"}


def verify_resource_unavailable(report, path):
    """이전 reader가 채널을 잃어도 현재 graph의 동명 R field를 대신 선택하지 않는다."""
    require(report.get("format") == "kartograph-impact" and report.get("status") == "notFound",
            "legacy resource selection must be unavailable")
    require(report.get("changed") == [] and report.get("affected") == [],
            "legacy graph fabricated XML mapping")
    require(report.get("unresolved") == [{"requested": path, "reason": "resourceEvidenceUnavailable",
                                        "candidates": [], "revision": "current"}],
            "legacy resource coverage refusal missing")

def verify_resource_bindings(snapshot, xml_hashes):
    """실제 AGP main root·생산자·R field 대응과 XML byte identity를 독립적으로 확인한다."""
    facts = snapshot.get("androidResources")
    bindings = snapshot.get("androidResourceBindings")
    require(isinstance(facts, dict) and isinstance(bindings, dict), "resource admission evidence missing")
    require(facts.get("admission") == "unverified" and facts.get("namespace") == PACKAGE.replace("/", "."),
            "unexpected raw resource facts namespace or admission")
    require(bindings.get("scope") == facts.get("scope") == snapshot.get("scope")
            and bindings.get("namespace") == facts.get("namespace")
            and bindings.get("scope") == "::debug", "resource scope/namespace mismatch")
    definitions = {row["path"]: row for row in facts["definitions"]}
    outcomes = {row["key"]["name"]: row for row in bindings["outcomes"]}
    require(len(outcomes) == len(bindings["outcomes"])
            and set(outcomes) == {row["key"]["name"] for row in facts["definitions"]},
            "resource key outcomes are not exhaustive")
    require(all(row["key"] == {"namespace": PACKAGE.replace("/", "."), "type": "layout", "name": row["key"]["name"]}
                for row in [*facts["definitions"], *bindings["outcomes"]]), "unexpected resource key namespace/type")
    for name in LAYOUTS:
        path = "src/main/res/layout/" + name + ".xml"
        require(path in definitions and definitions[path]["sourceSha256"] == xml_hashes[name],
                "captured XML bytes differ from fixture")
        require(outcomes.get(name, {}).get("field") == field(name)
                and outcomes[name].get("unavailable") is None, "exact nonfinal R field was not admitted")
    roots = sorted((row["input"] for row in facts["roots"]), key=lambda row: row["path"])
    require(roots == bindings["mainRoots"], "captured main inventory differs from admitted inventory")
    require(any(row["path"] == ABSENT_ROOT for row in roots), "declared absent main root was omitted")
    require(bindings["producerArtifact"] == ":processDebugResources", "unexpected fixture producer artifact")
    namespace_bytes = ("namespace=" + PACKAGE.replace("/", ".")).encode("utf-8")
    expected_options = {"role": "options", "path": "processDebugResources-options",
                        "sha256": hashlib.sha256(len(namespace_bytes).to_bytes(4, "big") + namespace_bytes).hexdigest()}
    require(bindings.get("namespaceOptions") == expected_options, "fixture namespace option identity differs")
    producer = [row for row in snapshot["provenance"]["witnesses"]
                if row["compiler"] == "agp-process-resources"
                and row["artifact"] == bindings["producerArtifact"] and row["scope"] == bindings["scope"]]
    require(len(producer) == 1, "resource producer missing or ambiguous")
    require(producer[0]["inputs"].count(expected_options) == 1, "fixture namespace option is not witnessed")
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
            "mainRootCount": len(roots), "declaredAbsentRoot": "captured", "literalInclude": "parent-to-child"}


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
            "xmlFileSelection": "exactCapturedResourceFields",
            "literalIncludeMapping": "recordedReverseClosure",
            "layoutInflation": "notExecuted",
            "finalInlinedOtherAgpOverlaysBinding": "unmeasured",
            "oldSnapshotAfterXmlEdit": "stale",
        },
        "phases": [{
            "phase": phase["phase"], "snapshotSha256": phase["snapshotSha256"],
            "freshness": phase["freshness"], "xmlSha256": phase["xmlSha256"],
            "executedTests": phase["executedTests"], "fieldWitnesses": phase["fieldWitnesses"],
            "xmlImpact": phase["xmlImpact"], "resourceBindings": phase["resourceBindings"],
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
        stream.write("\nandroid.sourceSets.main.res.srcDir('src/declaredAbsent/res')\n"
                     "dependencies { testImplementation 'junit:junit:4.13.2' }\n")
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


def source_main():
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
            require(not (project / ABSENT_ROOT).exists(), "declared absent fixture root unexpectedly exists")
            admitted = verify_resource_bindings(json.loads(snapshot.read_text(encoding="utf-8")),
                {name: digest(path) for name, path in layout_paths.items()})
            executed = verify_junit(project / "build/test-results/testDebugUnitTest")
            witnesses, xml_impacts = {}, []
            for name in LAYOUTS:
                report = json.loads(run([binary, "impact", field(name), "--graph-file", snapshot, "--all"],
                                        ROOT, output, phase + "-" + name + "-field"))
                witnesses[name] = verify_field_path(report, name)
                path = str(layout_paths[name].relative_to(project))
                file_report = json.loads(run([binary, "impact", "--file", path, "--graph-file", snapshot],
                                             ROOT, output, phase + "-" + name + "-xml"))
                xml_impacts.append(verify_xml_impact(file_report, name, digest(layout_paths[name])))
            phases.append({"phase": phase, "snapshotSha256": digest(snapshot),
                           "xmlSha256": {name: digest(path) for name, path in layout_paths.items()},
                           "sourceSha256": source_hashes, "freshness": "matched",
                           "executedTests": executed, "fieldWitnesses": witnesses,
                           "xmlImpact": xml_impacts, "resourceBindings": admitted})
        legacy = json.loads(snapshot.read_text(encoding="utf-8"))
        legacy.pop("androidResources", None)
        legacy.pop("androidResourceBindings", None)
        legacy_path = project / "legacy-snapshot.json"
        legacy_path.write_text(json.dumps(legacy), encoding="utf-8")
        legacy_report = json.loads(run([binary, "impact", "--file", "src/main/res/layout/child.xml",
                                       "--graph-file", legacy_path], ROOT, output, "legacy-resource-unavailable", expected=64))
        verify_resource_unavailable(legacy_report, "src/main/res/layout/child.xml")
        require(phases[0]["xmlSha256"]["child"] != phases[1]["xmlSha256"]["child"], "XML bytes did not change")
        require(all(phases[0]["xmlSha256"][name] == phases[1]["xmlSha256"][name]
                    for name in LAYOUTS if name != "child"), "unrequested XML changes")
        report = {"format": "kartograph-agp-resource-impact-experiment", "version": 1,
                  "status": "capturedLayoutImpactEstablished",
                  "toolchain": {"agp": "8.7.3", "gradle": "8.10.2", "kgp": "2.0.21",
                                "javaTarget": 17, "compileSdk": 35, "nonFinalResIds": True},
                  "sourceRevision": revision, "fixtureBuildSha256": digest(project / "build.gradle"),
                  "cliLibrarySha256": {path.name: digest(path) for path in libraries},
                  "phases": phases, "include": {"parent": "layout/parent", "child": "layout/child"},
                  "limitations": [LIMITATION, "The unit tests execute accessors, not Android layout inflation.",
                                  "Final/inlined IDs, other AGP versions, overlays and binding XML are unmeasured."]}
        result_path.write_text(json.dumps(report, indent=2, sort_keys=True) + "\n", encoding="utf-8")
    print(json.dumps(log_summary(report), sort_keys=True))
    print("AGP resource experiment established XML layout/include impact with exact R field/test witnesses and explicit coverage limits.")



# 배포 모드는 명시적 파일들만 소비한다. source-mode 준비·installDist 호출은 없다.
PACKAGED = runpy.run_path(str(Path(__file__).with_name("packaged_resource_artifacts.py")))


def packaged_log_summary(report):
    """검증한 공개 projection과 고정 형식 지문만 stdout에 남긴다."""
    summary = (log_summary(report) if report["status"] == "passed" else
               {"format": report["format"], "version": 1, "status": "failed", "stage": report["stage"]})
    summary["releaseVersion"] = report["releaseVersion"]
    for name in ("sourceRevision", "sourceTree"):
        value = report.get(name)
        if isinstance(value, str) and len(value) == 40 and all(c in "0123456789abcdef" for c in value):
            summary[name] = value
    artifacts = report.get("artifacts", {})
    retained = artifacts.get("plugin", {}).get("sha256")
    if isinstance(retained, str) and PACKAGED["HASH"].fullmatch(retained):
        summary["retainedPluginSha256"] = retained
    summary["loadedRuntime"] = PACKAGED["runtime_diagnostic"](report.get("loadedRuntimeDiagnostic", {}))
    summary["cliArchives"] = [{"format": row["format"], "sha256": row["sha256"]}
                              for row in artifacts.get("archives", [])
                              if row.get("format") in ("zip", "tar")
                              and isinstance(row.get("sha256"), str) and PACKAGED["HASH"].fullmatch(row["sha256"])]
    summary["agpBuildCount"] = 2 if report.get("agpBuildCount") == 2 else 0
    summary["semanticPairCount"] = len(report.get("semanticOutputSha256", {}))
    summary["artifactsUnchanged"] = report.get("artifactsUnchanged") is True
    queries = {phase + "-" + suffix for phase in ("before", "after")
               for suffix in ("freshness", *(name + "-" + kind for name in LAYOUTS for kind in ("field", "xml")))}
    queries.update(("resource-edit-stale", "legacy-resource-unavailable"))
    if report.get("lastQuery") in queries:
        summary["lastQuery"] = report["lastQuery"]
        current = report.get("semanticComparisons", {}).get(report["lastQuery"], {})
        summary["lastQueryDecodedStdoutSha256"] = {
            label: value for label, value in current.get("decodedStdoutSha256", {}).items()
            if label in ("zip", "tar") and isinstance(value, str) and PACKAGED["HASH"].fullmatch(value)}
    summary["freshnessComparisons"] = {}
    for name in ("before-freshness", "resource-edit-stale", "after-freshness"):
        comparison = report.get("semanticComparisons", {}).get(name, {})
        if comparison.get("command") == "verify-snapshot" and comparison.get("status") == "matched":
            # 알려진 세 질의의 검증된 시간과 원본 JSON 지문만 공개한다.
            summary["freshnessComparisons"][name] = PACKAGED["public_semantic_diagnostic"]({
                key: comparison[key] for key in ("command", "canonicalJsonSha256", "excludedRootFields", "hashNanos")})
    # 이 객체는 semantic_pair의 고정 경로/타입/지문 생성기로만 작성한다.
    if "semanticMismatch" in report:
        summary["semanticMismatch"] = PACKAGED["public_semantic_diagnostic"](report["semanticMismatch"])
    return summary


def packaged_main(args):
    output = Path(args.evidence).resolve()
    output.mkdir(parents=True, exist_ok=True)
    result_path = output / "verification.json"
    if result_path.exists():
        result_path.unlink()
    report = {"format": "kartograph-packaged-agp-resource-impact", "version": 1,
              "status": "failed", "releaseVersion": args.version, "stage": "artifact-admission"}
    try:
        artifacts = PACKAGED["PackagedArtifacts"](args, ROOT)
        report["artifacts"] = artifacts.public_identity()
        report["stage"] = "toolchain-admission"
        gradle_home = os.environ.get("GRADLE_8_HOME")
        require(bool(gradle_home), "GRADLE_8_HOME must point to the verified Gradle 8.10.2 installation")
        gradle = Path(gradle_home) / "bin/gradle"
        version = run([gradle, "--version"], ROOT, output, "gradle-version")
        require("Gradle 8.10.2" in version.splitlines(), "the experiment requires Gradle 8.10.2")
        sdk = os.environ.get("ANDROID_HOME")
        require(bool(sdk) and (Path(sdk) / "platforms/android-35").is_dir()
                and (Path(sdk) / "build-tools/35.0.0").is_dir(),
                "Android platform 35 and build-tools 35.0.0 are required")
        revision = run(["git", "rev-parse", "HEAD"], ROOT, output, "source-revision").strip()
        require(len(revision) == 40 and all(c in "0123456789abcdef" for c in revision), "invalid source revision")
        report["sourceRevision"] = revision
        tree = run(["git", "rev-parse", "HEAD^{tree}"], ROOT, output, "source-tree").strip()
        require(len(tree) == 40 and all(c in "0123456789abcdef" for c in tree), "invalid source tree")
        report["sourceTree"] = tree
        report["toolchain"] = {"agp": "8.7.3", "gradle": "8.10.2", "kgp": "2.0.21",
                               "javaTarget": 17, "compileSdk": 35, "nonFinalResIds": True}
        for label, binary in artifacts.clis.items():
            observed = run([binary, "--version"], ROOT, output, label + "-version").strip()
            require(observed == "kartograph " + args.version, "extracted CLI version differs")
        semantic_hashes, comparisons = {}, {}
        report["semanticOutputSha256"] = semantic_hashes
        report["semanticComparisons"] = comparisons
        with tempfile.TemporaryDirectory(prefix="packaged-resource-impact-", dir=ROOT / "fixtures") as temporary:
            project = Path(temporary)
            prepare(project)
            snapshot = project / "build/reports/kartograph/debug-snapshot.json"
            bindings = project / "build/kartograph/debug-input-bindings.json"
            runtime_path = project / "build/reports/kartograph/packaged-runtime.json"
            gradle_command = [gradle, "--no-daemon", "--console=plain", "--no-configuration-cache",
                              "-p", project, "-Pkartograph.pluginJar=" + str(artifacts.plugin)]
            source_paths = sorted([*project.glob("src/**/*.java"), *project.glob("src/**/*.kt")])
            source_hashes = {str(path.relative_to(project)): digest(path) for path in source_paths}
            layout_paths = {name: project / ("src/main/res/layout/" + name + ".xml") for name in LAYOUTS}
            parent = ET.parse(layout_paths["parent"]).getroot()
            require(parent.find("include").get("layout") == "@layout/child", "literal layout include missing")
            phases, runtimes = [], []

            def query(arguments, name, expected=0, graph=None):
                # 두 배포 CLI가 동일한 snapshot/bindings 바이트를 읽었는지 질의 전후 확인한다.
                guarded = [graph or snapshot, bindings]
                before = [digest(path) for path in guarded]
                documents = {}
                report["lastQuery"] = name
                comparison = {"command": arguments[0], "status": "failed", "decodedStdoutSha256": {}}
                comparisons[name] = comparison
                for label, binary in artifacts.clis.items():
                    require([digest(path) for path in guarded] == before, "shared query inputs mutated")
                    stdout = run([binary, *arguments], ROOT, output, name + "-" + label, expected=expected)
                    comparison["decodedStdoutSha256"][label] = hashlib.sha256(stdout.encode("utf-8")).hexdigest()
                    documents[label] = json.loads(stdout)
                    require([digest(path) for path in guarded] == before, "shared query inputs mutated")
                try:
                    value, sha, identity = PACKAGED["semantic_pair"](documents, arguments[0])
                except PACKAGED["SemanticComparisonError"] as error:
                    report["semanticMismatch"] = error.diagnostic
                    raise
                comparison.update(identity)
                comparison["status"] = "matched"
                semantic_hashes[name] = sha
                return value

            for phase in ("before", "after"):
                report["stage"] = phase
                if phase == "after":
                    layout_paths["child"].write_text(
                        layout_paths["child"].read_text(encoding="utf-8").replace('android:text="Before"', 'android:text="After"'),
                        encoding="utf-8")
                    stale = query(["verify-snapshot", "--graph-file", snapshot, "--project", project,
                                   "--input-bindings", bindings], "resource-edit-stale", expected=1)
                    require(stale.get("status") == "stale", "XML-only edit did not invalidate captured inputs")
                # 정확히 두 AGP 빌드만 실행한다. 각 빌드가 두 CLI에 공통인 snapshot을 만든다.
                run(gradle_command + ["verifyPackagedKartographRuntime", "kartographSnapshotDebug",
                                     "testDebugUnitTest", "--rerun-tasks"],
                    ROOT, output, phase + "-build-tests")
                require(runtime_path.is_file() and runtime_path.stat().st_size <= 256 * 1024,
                        "bounded loaded-runtime receipt is missing")
                runtime = json.loads(runtime_path.read_text(encoding="utf-8"))
                report["loadedRuntimeDiagnostic"] = PACKAGED["runtime_diagnostic"](runtime)
                runtimes.append(PACKAGED["verify_runtime"](runtime, artifacts))
                require(all(item == runtimes[0] for item in runtimes), "loaded runtime changed between captures")
                artifacts.unchanged()
                freshness = query(["verify-snapshot", "--graph-file", snapshot, "--project", project,
                                   "--input-bindings", bindings], phase + "-freshness")
                require(freshness.get("status") == "matched", "resource snapshot is not verified")
                require(source_hashes == {str(path.relative_to(project)): digest(path) for path in source_paths},
                        "code/test sources changed during XML-only experiment")
                require(not (project / ABSENT_ROOT).exists(), "declared absent fixture root unexpectedly exists")
                admitted = verify_resource_bindings(json.loads(snapshot.read_text(encoding="utf-8")),
                    {name: digest(path) for name, path in layout_paths.items()})
                executed = verify_junit(project / "build/test-results/testDebugUnitTest")
                witnesses, xml_impacts = {}, []
                for name in LAYOUTS:
                    field_value = query(["impact", field(name), "--graph-file", snapshot, "--all"],
                                        phase + "-" + name + "-field")
                    witnesses[name] = verify_field_path(field_value, name)
                    path = str(layout_paths[name].relative_to(project))
                    file_value = query(["impact", "--file", path, "--graph-file", snapshot, "--all"],
                                       phase + "-" + name + "-xml")
                    xml_impacts.append(verify_xml_impact(file_value, name, digest(layout_paths[name])))
                phases.append({"phase": phase, "snapshotSha256": digest(snapshot),
                               "xmlSha256": {name: digest(path) for name, path in layout_paths.items()},
                               "sourceSha256": source_hashes, "freshness": "matched",
                               "executedTests": executed, "fieldWitnesses": witnesses,
                               "xmlImpact": xml_impacts, "resourceBindings": admitted})
            legacy = json.loads(snapshot.read_text(encoding="utf-8"))
            legacy.pop("androidResources", None)
            legacy.pop("androidResourceBindings", None)
            legacy_path = project / "legacy-snapshot.json"
            legacy_path.write_text(json.dumps(legacy), encoding="utf-8")
            legacy_report = query(["impact", "--file", "src/main/res/layout/child.xml",
                                   "--graph-file", legacy_path, "--all"],
                                  "legacy-resource-unavailable", expected=64, graph=legacy_path)
            verify_resource_unavailable(legacy_report, "src/main/res/layout/child.xml")
            require(phases[0]["xmlSha256"]["child"] != phases[1]["xmlSha256"]["child"], "XML bytes did not change")
            require(all(phases[0]["xmlSha256"][name] == phases[1]["xmlSha256"][name]
                        for name in LAYOUTS if name != "child"), "unrequested XML changes")
            report.update({"status": "passed", "stage": "complete", "agpBuildCount": len(phases),
                           "loadedRuntime": runtimes[0], "phases": phases,
                           "semanticOutputSha256": semantic_hashes,
                           "cliComparison": "canonical-json-equality-except-validated-verify-snapshot-root-hashNanos",
                           "limitations": [LIMITATION, "The unit tests execute accessors, not Android layout inflation.",
                                           "Final/inlined IDs, other AGP versions, overlays and binding XML are unmeasured."]})
        artifacts.unchanged()
        report["artifactsUnchanged"] = True
    except BaseException:
        report["status"] = "failed"
        raise
    finally:
        PACKAGED["write_receipt"](result_path, report)
        summary = json.dumps(packaged_log_summary(report), sort_keys=True)
        require(len(summary.encode("utf-8")) <= 64 * 1024, "packaged stdout summary exceeds bound")
        print(summary, flush=True)


def main(argv=None):
    args = PACKAGED["parse_arguments"](argv)
    if args.packaged:
        return packaged_main(args)
    return source_main()


if __name__ == "__main__":
    main()
