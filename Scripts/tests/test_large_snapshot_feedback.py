"""128 MiB 초과 실제 파일 round-trip. 합성 크기 경계 검증이며 앱 정확도 실증은 아니다."""
import hashlib
import json
import os
from pathlib import Path
import subprocess
import tempfile
import queue
import threading
import unittest

ROOT = Path(__file__).resolve().parents[2]


class LargeSnapshotFeedbackTest(unittest.TestCase):
    def test_actual_large_plain_and_compact_roundtrip(self):
        distribution = ROOT / "cli/build/install/kartograph"
        libraries = distribution / "lib"
        if not libraries.is_dir():
            self.skipTest("requires :cli:installDist")
        java_home = os.environ.get("JAVA_HOME")
        java = str(Path(java_home) / "bin/java") if java_home else "java"
        javac = str(Path(java_home) / "bin/javac") if java_home else "javac"
        suffix = ".exe" if os.name == "nt" else ""
        if java_home:
            java += suffix
            javac += suffix
        report = ROOT / "build/reports/feedback-large-snapshot.json"
        report.parent.mkdir(parents=True, exist_ok=True)
        with tempfile.TemporaryDirectory(prefix="kartograph-large-") as temp:
            directory = Path(temp)
            subprocess.run([javac, "-encoding", "UTF-8", "-cp", str(libraries / "*"), "-d", temp,
                            str(ROOT / "Scripts/fixtures/LargeSnapshotProbe.java")], check=True, timeout=60)
            source = directory / "legacy-v1.json"
            count = 6200
            with source.open("w", encoding="utf-8", newline="\n") as stream:
                stream.write('{"format":"kartograph-query-snapshot","version":1,"toolVersion":"0.25.0","includePrivateMembers":false,"suppressed":[],"retention":[],"limitations":["synthetic boundary fixture; source freshness not established"],"graph":{"nodes":[')
                for index in range(count):
                    if index:
                        stream.write(",")
                    node = dict(usr=f"class:Fixture{index}", name=f"Fixture{index}_" + ("x" * 22000 if index >= 3 else ""),
                                kind="class", accessibility="public", jvmVisibility="public", jvmModifiers=[],
                                attributes=[], annotations=[], supertypes=[], synthesized=False)
                    json.dump(node, stream, separators=(",", ":"))
                stream.write('],"edges":[')
                for index in range(count - 1):
                    if index:
                        stream.write(",")
                    json.dump(dict(source=f"class:Fixture{index}", target=f"class:Fixture{index+1}",
                                   kind="reference", origin="bytecode", weight=1), stream, separators=(",", ":"))
                stream.write('],"externalCalls":[],"serviceProviders":[]}}')
            self.assertGreater(source.stat().st_size, 128 * 1024 * 1024)
            results = []
            for compact in [False, True]:
                destination = directory / ("compact.json" if compact else "plain.json")
                result = subprocess.run([java, "-Xmx2g", "-cp", os.pathsep.join([temp, str(libraries / "*")]),
                                         "LargeSnapshotProbe", str(source), str(destination), str(compact).lower()],
                                        text=True, capture_output=True, timeout=180)
                self.assertEqual(0, result.returncode, result.stderr)
                row = json.loads(result.stdout)
                with destination.open("rb") as stream:
                    row.update(compact=compact, sha256=hashlib.file_digest(stream, "sha256").hexdigest())
                commands = [
                    ["query", "class:Fixture0", "--depth", "1", "--limit", "2"],
                    ["impact", "class:Fixture1", "--depth", "1", "--limit", "2"],
                    ["reach", "class:Fixture0", "--project", temp, "--depth", "1", "--max-reached", "2"],
                ]
                flows = []
                cli = [java, "-Xmx2g", "-cp", str(libraries / "*"), "dev.kartograph.cli.MainKt"]
                for command in commands:
                    flow = subprocess.run(cli + command + ["--graph-file", str(destination), "--snapshot-max-mib", "256"],
                        text=True, capture_output=True, timeout=90)
                    self.assertEqual(0, flow.returncode, flow.stderr)
                    self.assertIn("synthetic boundary fixture", flow.stdout)
                    flows.append(dict(command=command[0], status=flow.returncode))
                messages = [dict(jsonrpc="2.0", id=1, method="initialize", params=dict(protocolVersion="2025-11-25", capabilities={}, clientInfo=dict(name="large-boundary-test", version="1"))),
                    dict(jsonrpc="2.0", method="notifications/initialized"),
                    dict(jsonrpc="2.0", id=2, method="tools/call", params=dict(name="query_symbol", arguments=dict(symbol="class:Fixture0", depth=1, limit=2)))]
                with subprocess.Popen(cli + ["mcp", "--graph-file", str(destination), "--snapshot-max-mib", "256"],
                    stdin=subprocess.PIPE, stdout=subprocess.PIPE, stderr=subprocess.PIPE, text=True) as mcp:
                    replies = queue.Queue()
                    reader = threading.Thread(target=lambda: [replies.put(line) for line in mcp.stdout], daemon=True)
                    reader.start()
                    try:
                        mcp.stdin.write(json.dumps(messages[0]) + "\n"); mcp.stdin.flush()
                        self.assertNotIn("error", json.loads(replies.get(timeout=30)))
                        mcp.stdin.write("".join(json.dumps(message) + "\n" for message in messages[1:])); mcp.stdin.flush()
                        response = json.loads(replies.get(timeout=30))
                        mcp.stdin.close()
                        self.assertEqual(0, mcp.wait(timeout=30), mcp.stderr.read())
                    finally:
                        if mcp.poll() is None:
                            mcp.kill()
                self.assertNotIn("error", response)
                self.assertFalse(response["result"].get("isError", False), response)
                self.assertIn("synthetic boundary fixture", json.dumps(response))
                flows.append(dict(command="mcp query_symbol", status=mcp.returncode))
                row.update(flows=flows, truncatedRejected=True, malformedRejected=True)
                results.append(row)
            baselines = []
            for label, base in [("public025", ROOT.parents[1] / "evidence/feedback/kartograph-public-025/kartograph-0.25.0/lib"),
                                ("candidate399d2", ROOT.parents[1] / "evidence/kartograph/release-026-4cd-zip/kartograph-0.26.0/lib")]:
                if base.is_dir():
                    baseline = subprocess.run([java, "-Xmx2g", "-cp", os.pathsep.join([temp, str(base / "*")]),
                        "LargeSnapshotProbe", str(source), "unused", "false", "reject128"], text=True, capture_output=True, timeout=90)
                    self.assertEqual(0, baseline.returncode, baseline.stderr)
                    baselines.append(dict(version=label, **json.loads(baseline.stdout)))
            report.write_text(json.dumps(dict(inputBytes=source.stat().st_size, heapLimitBytes=2*1024**3,
                memoryMetric="observed live JVM heap after parse/render, not peak RSS", results=results, baselines=baselines,
                limits="Synthetic 6200-node chain with long distinct labels; validates byte and expansion bounds, not private app accuracy or typical whole-app memory."), indent=2), encoding="utf-8")


if __name__ == "__main__":
    unittest.main()
