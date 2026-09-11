#!/usr/bin/env python3
"""실행된 런타임 경로가 변경 영향의 역방향 경로에도 나타나는지 대조한다."""
import json
import os
from pathlib import Path
import subprocess
import sys
import tempfile
import time

ROOT = Path(__file__).resolve().parent.parent
BINARY = ROOT / "cli/build/install/kartograph/bin/kartograph"


def main():
    jdk = Path(os.environ["JAVA_HOME"])
    cases = {
        "direct_call": (True, True), "callback_registered": (True, True),
        "callback_unregistered": (False, True), "factory_name": (True, True),
        "reflective_method": (True, True), "reflective_field": (True, False),
    }
    rows = []
    def run(command, expected=0):
        result = subprocess.run(list(map(str, command)), capture_output=True, text=True, timeout=120)
        if result.returncode != expected:
            raise RuntimeError("impact runtime stage failed: " + Path(str(command[0])).name)
        return result.stdout
    with tempfile.TemporaryDirectory(prefix="kartograph-impact-runtime-") as directory:
        for case, (used, linked) in cases.items():
            project = Path(directory) / case
            source = project / "src/probe/Entry.java"
            source.parent.mkdir(parents=True)
            source.write_text((ROOT / "fixtures/runtime-contracts" / case / "Entry.java").read_text())
            classes = project / "classes"
            classes.mkdir()
            run([jdk / "bin/javac", "-g", "-d", classes, source])
            observed = run([jdk / "bin/java", "-cp", classes, "probe.Entry"]).strip() == "USED"
            if observed != used:
                raise RuntimeError("runtime observation differs from fixture contract")
            snapshot = project / "graph.json"
            start = time.perf_counter()
            snapshot.write_text(run([BINARY, "snapshot", "--classes", classes, "--project", project, "--include-paths"]))
            capture = time.perf_counter() - start
            documents = {}
            for name in ["Used", "Unused"]:
                documents[name] = json.loads(run([BINARY, "impact", "class:probe/Entry$" + name, "--graph-file", snapshot]))
            caller = "method:probe/Entry#main([Ljava/lang/String;)V"
            found = [item for item in documents["Used"]["affected"] if item["usr"] == caller]
            if bool(found) != linked or any(item["usr"] == caller for item in documents["Unused"]["affected"]):
                raise RuntimeError("impact lost its runtime caller or connected an unused control")
            if not linked and not any("reflective-construction:" in text for text in documents["Used"]["limitations"]):
                raise RuntimeError("unmodeled runtime path lost its measured limitation")
            origins = sorted({edge["origin"] for item in found for path in item["paths"] for edge in path["edges"]})
            if case in ("factory_name", "reflective_method") and "runtimeModel" not in origins:
                raise RuntimeError("runtime dependency lost its modeled origin")
            rows.append({"case": case, "runtimeUsed": observed, "mainInImpact": bool(found), "origins": origins,
                "unusedControlConnected": False, "captureSeconds": capture,
                "unmodeledPathDisclosed": linked or any("reflective-construction:" in x for x in documents["Used"]["limitations"])})
    output = ROOT / "build/reports/impact-runtime.json"
    output.parent.mkdir(parents=True, exist_ok=True)
    output.write_text(json.dumps({"cases": rows, "interpretation": "Observed use is a lower bound. Unregistered callbacks remain potential dependencies; unknown reflective field values remain disclosed limitations."}, indent=2) + "\n")
    print("Impact runtime contracts verified:", len(rows), "executed cases with unused controls")


if __name__ == "__main__":
    try:
        main()
    except (OSError, ValueError, KeyError, RuntimeError, subprocess.TimeoutExpired) as error:
        print("error: " + (str(error) if isinstance(error, RuntimeError) else "impact runtime inputs unavailable"), file=sys.stderr)
        raise SystemExit(2)
