#!/usr/bin/env python3
"""고정 nowinandroid 빌드에서 Hilt 생성 코드 회귀와 분석 시간을 측정한다. 전체 정확도 인증은 아니다."""

import argparse
import json
from pathlib import Path
import subprocess
import sys
import time


REVISION = "12f80da6518e161ed16a06a68e71fb8a873576d6"


def run(command):
    """공개 표본이라도 도구 오류에 로컬 경로와 원시 stderr를 재출력하지 않는다."""
    result = subprocess.run(command, capture_output=True, text=True, timeout=120)
    if result.returncode:
        raise RuntimeError("public sample command failed; verify the pinned build and complete classpath")
    return result.stdout


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--project", required=True)
    parser.add_argument("--binary", required=True)
    parser.add_argument("--android-jar", required=True)
    options = parser.parse_args()
    project = Path(options.project).resolve(strict=True)
    binary = str(Path(options.binary).resolve(strict=True))
    if run(["git", "-C", str(project), "rev-parse", "HEAD"]).strip() != REVISION:
        raise RuntimeError("public sample revision differs; use the documented pinned commit")
    if run(["git", "-C", str(project), "status", "--porcelain", "--untracked-files=no"]).strip():
        raise RuntimeError("public sample has tracked modifications; use a clean checkout")
    modules = [project / "app", project / "sync/work"]
    modules += [path for path in (project / "core").iterdir() if path.is_dir() and "test" not in path.name]
    modules += list((project / "feature").glob("*/*"))
    patterns = ["build/intermediates/classes/demoDebug/transformDemoDebugClassesWithAsm/dirs",
                "build/classes/kotlin/main", "build/classes/java/main"]
    roots = sorted({module / suffix for module in modules for suffix in patterns if (module / suffix).is_dir()})
    roots += sorted(jar for module in modules for jar in
                    (module / "build/intermediates/classes/demoDebug/transformDemoDebugClassesWithAsm/jars").glob("*.jar"))
    if len(roots) != 22:
        raise RuntimeError("expected 22 class roots for the pinned commit; build its demoDebug variant first")
    class_options = [value for root in roots for value in ("--classes", str(root))]
    graph = run([binary, "graph", *class_options])
    generated_ids = [
        "class:com/google/samples/apps/nowinandroid/MainActivityViewModel_Factory",
        "class:com/google/samples/apps/nowinandroid/MainActivityViewModel_HiltModules",
        "class:com/google/samples/apps/nowinandroid/NiaApplication_HiltComponents",
        "class:com/google/samples/apps/nowinandroid/DaggerNiaApplication_HiltComponents_SingletonC$Builder",
    ]
    for node_id in generated_ids:
        node_lines = [line for line in graph.splitlines() if line.startswith('  "' + node_id + '" [')]
        if len(node_lines) != 1 or "style=dashed" not in node_lines[0]:
            raise RuntimeError("public Hilt regression: generated node is missing or not marked synthesized")
    classpath = (project / "app/build/kartograph-validation-classpath.txt").read_text().splitlines()
    if not classpath or not all(Path(path).exists() for path in classpath):
        raise RuntimeError("public sample dependency artifacts are missing; rerun the classpath task")
    args = [binary, "dead", *class_options, "--project", str(project), "--manifest",
            "app/build/intermediates/merged_manifests/demoDebug/processDemoDebugManifest/AndroidManifest.xml",
            "--resources", "app/src/main/res", "--namespace", "com.google.samples.apps.nowinandroid",
            "--keep-rules", "app/proguard-rules.pro", "--keep-rules", "core/datastore/consumer-proguard-rules.pro",
            "--classpath", str(Path(options.android_jar).resolve(strict=True)), "--report-format", "json"]
    for path in classpath:
        args += ["--classpath", path]
    measurements = {}
    for mode in ("class", "member"):
        start = time.monotonic()
        document = json.loads(run(args + (["--include-private-members"] if mode == "member" else [])))
        reported = {item["nodeId"] for item in document["diagnostics"]}
        if reported.intersection(generated_ids):
            raise RuntimeError("public Hilt regression: generated declarations were reported")
        if not document["limitations"]:
            raise RuntimeError("public sample report is missing analysis limitations")
        measurements[mode] = {"diagnostics": len(reported), "seconds": round(time.monotonic() - start, 3)}
    print(json.dumps({"revision": REVISION, "classRoots": len(roots), "measurements": measurements}, sort_keys=True))


if __name__ == "__main__":
    try:
        main()
    except (OSError, ValueError, RuntimeError, subprocess.TimeoutExpired) as error:
        message = str(error) if isinstance(error, RuntimeError) else "public sample verification failed; check inputs"
        print("error: " + message, file=sys.stderr)
        sys.exit(2)
