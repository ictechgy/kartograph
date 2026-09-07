#!/usr/bin/env python3
"""배포물과 runtime SBOM의 결정적 SHA256 목록을 만든다."""

import hashlib
from pathlib import Path
import re
import shutil


def prepare(root: Path) -> Path:
    version = (root / "VERSION").read_text().strip()
    if not re.fullmatch(r"[0-9]+\.[0-9]+\.[0-9]+", version):
        raise ValueError("VERSION must contain a stable semantic version")
    target = root / "build/release"
    target.mkdir(parents=True, exist_ok=True)
    artifacts = [
        root / f"cli/build/distributions/kartograph-{version}.zip",
        root / f"cli/build/distributions/kartograph-{version}.tar",
        root / f"gradle-plugin/build/libs/kartograph-gradle-plugin-{version}.jar",
    ]
    for module, name in [("cli", "kartograph"), ("gradle-plugin", "kartograph-gradle-plugin")]:
        source = root / f"{module}/build/reports/sbom/{name}-{version}.cdx.json"
        destination = target / source.name
        shutil.copyfile(source, destination)
        artifacts.append(destination)
    lines = []
    for artifact in sorted(artifacts, key=lambda item: item.name):
        with artifact.open("rb") as stream:
            checksum = hashlib.sha256()
            for block in iter(lambda: stream.read(1024 * 1024), b""):
                checksum.update(block)
            digest = checksum.hexdigest()
        lines.append(f"{digest}  {artifact.name}\n")
    checksums = target / "SHA256SUMS"
    checksums.write_text("".join(lines), encoding="utf-8")
    return checksums


if __name__ == "__main__":
    prepare(Path(__file__).resolve().parent.parent)
