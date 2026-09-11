#!/usr/bin/env python3
"""실제 compact snapshot 전체를 공개 codec API로 왕복하고 크기와 사실 수를 기록한다."""
import argparse
import hashlib
import json
import os
from pathlib import Path
import subprocess
import sys
import tempfile


def main():
    parser = argparse.ArgumentParser(description="Verify compact snapshot round-trip and compare canonical encoded sizes.")
    parser.add_argument("--binary", required=True)
    parser.add_argument("--snapshot", required=True)
    parser.add_argument("--output", required=True)
    args = parser.parse_args()
    binary = Path(args.binary).resolve(strict=True)
    snapshot = Path(args.snapshot).resolve(strict=True)
    jdk = Path(os.environ["JAVA_HOME"])
    libraries = sorted((binary.parent.parent / "lib").glob("*.jar"))
    classpath = os.pathsep.join(map(str, libraries))
    if not libraries:
        raise RuntimeError("provide the installed CLI distribution")
    with tempfile.TemporaryDirectory(prefix="kartograph-snapshot-check-") as directory:
        root = Path(directory)
        source = root / "SnapshotCheck.java"
        source.write_text('''import java.nio.file.*;
import dev.kartograph.export.*;
public class SnapshotCheck {
 public static void main(String[] args) throws Exception {
  String encoded=Files.readString(Path.of(args[0]));
  var snapshot=QuerySnapshotCodec.INSTANCE.parse(encoded);
  String plain=QuerySnapshotCodec.INSTANCE.render(snapshot);
  String compact=QuerySnapshotCodec.INSTANCE.render(snapshot,true);
  if(!encoded.equals(compact)) throw new IllegalStateException("round trip differs");
  System.out.println("{\\"nodes\\":"+snapshot.getGraph().getNodeCount()+",\\"edges\\":"+snapshot.getGraph().getEdgeCount()+",\\"externalCalls\\":"+snapshot.getGraph().getExternalCalls().size()+",\\"plainBytes\\":"+plain.length()+",\\"compactBytes\\":"+compact.length()+",\\"roundTripEqual\\":true}");
 }
}
''')
        compiled = subprocess.run([str(jdk / "bin/javac"), "-cp", classpath, str(source)], capture_output=True, text=True, timeout=60)
        if compiled.returncode:
            raise RuntimeError("round-trip check compilation failed")
        checked = subprocess.run([str(jdk / "bin/java"), "-Xmx3g", "-cp", str(root) + os.pathsep + classpath,
            "SnapshotCheck", str(snapshot)], capture_output=True, text=True, timeout=180)
        if checked.returncode:
            raise RuntimeError("compact snapshot round-trip failed")
    record = json.loads(checked.stdout)
    record["snapshotSha256"] = hashlib.sha256(snapshot.read_bytes()).hexdigest()
    record["codecJars"] = {p.name: hashlib.sha256(p.read_bytes()).hexdigest() for p in libraries}
    Path(args.output).write_text(json.dumps(record, indent=2) + "\n")
    print("Snapshot round-trip verified:", record["nodes"], "nodes,", record["compactBytes"], "bytes")


if __name__ == "__main__":
    try:
        main()
    except (OSError, KeyError, ValueError, RuntimeError, subprocess.TimeoutExpired) as error:
        print("error: " + (str(error) if isinstance(error, RuntimeError) else "snapshot verification input failed"), file=sys.stderr)
        raise SystemExit(2)
