#!/usr/bin/env python3
"""고정 CURRENT 소비 Gradle만 새 테스트 출력에 준비한다. 전역 설치는 바꾸지 않는다."""
from __future__ import annotations

import argparse
import hashlib
import importlib.util
import json
import os
from pathlib import Path
import shutil
import stat
import tempfile
import zipfile

VERSION = "9.7.1"
DISTRIBUTION_SHA = "acd53f1edaf02f1a8ff99879f8a34b302661a057d9b063ae9e35b552f804d20a"
SPEC = importlib.util.spec_from_file_location("safeargs_distribution", Path(__file__).with_name("observe-safeargs-gradle813.py"))
SHARED = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(SHARED)


def receipt() -> dict:
    return {"schemaVersion": 1, "version": VERSION,
            "distributionUrl": f"https://services.gradle.org/distributions/gradle-{VERSION}-bin.zip",
            "distributionSha256": DISTRIBUTION_SHA, "home": f"distribution/gradle-{VERSION}",
            "scope": "fixed SafeArgs test consumer; separate from outer Gradle and analysis timing"}


def verify_prepared(output: Path) -> Path:
    """기존 출력도 인증 archive의 전량 파일과 일치해야 재사용한다."""
    if output.is_symlink() or not output.is_dir():
        raise ValueError("invalid SafeArgs output directory; clean the owned test output")
    archive = output / f"gradle-{VERSION}-bin.zip"
    record = output / "receipt.json"
    if archive.is_symlink() or not archive.is_file() or archive.stat().st_size > SHARED.MAX_ARCHIVE or SHARED.digest(archive) != DISTRIBUTION_SHA:
        raise ValueError("pinned Gradle archive mismatch; clean the owned test output")
    if record.is_symlink() or not record.is_file() or record.stat().st_size > 4096:
        raise ValueError("bounded Gradle preparation receipt is unavailable")
    def unique(pairs):
        value = {}
        for key, item in pairs:
            if key in value:
                raise ValueError("duplicate preparation receipt key")
            value[key] = item
        return value
    value = json.loads(record.read_text(encoding="utf-8"), object_pairs_hook=unique)
    expected_receipt = receipt()
    if type(value) is not dict or value.keys() != expected_receipt.keys() or type(value["schemaVersion"]) is not int or any(
        type(value[key]) is not str for key in expected_receipt if key != "schemaVersion"
    ) or value != expected_receipt:
        raise ValueError("pinned Gradle preparation receipt mismatch")
    expected = {archive.name, record.name, "distribution"}
    with zipfile.ZipFile(archive) as zipped:
        entries = SHARED.distribution_entries(zipped, version=VERSION)
        for info in entries:
            relative = Path("distribution") / info.filename.rstrip("/")
            target = output / relative
            expected.add(relative.as_posix())
            expected.update(p.as_posix() for p in relative.parents if p != Path("."))
            for parent in relative.parents:
                if parent != Path(".") and ((output / parent).is_symlink() or not (output / parent).is_dir()):
                    raise ValueError("missing or linked prepared Gradle directory")
            if target.is_symlink():
                raise ValueError("linked prepared Gradle entry")
            if not target.resolve().is_relative_to(output.resolve()):
                raise ValueError("prepared Gradle path escapes its output")
            if not target.is_dir() if info.is_dir() else not target.is_file():
                raise ValueError("missing or linked prepared Gradle entry")
            if info.is_dir():
                continue
            if target.stat().st_size != info.file_size:
                raise ValueError("prepared Gradle file size mismatch")
            if os.name != "nt" and stat.S_IMODE(target.stat().st_mode) != (0o755 if (info.external_attr >> 16) & 0o111 else 0o644):
                raise ValueError("prepared Gradle file permissions mismatch")
            h = hashlib.sha256()
            with zipped.open(info) as stream:
                for part in iter(lambda: stream.read(1024 * 1024), b""):
                    h.update(part)
            if SHARED.digest(target) != h.hexdigest():
                raise ValueError("prepared Gradle file content mismatch")
    actual = set()
    for path in output.rglob("*"):
        if path.is_symlink() or len(actual) >= len(expected):
            raise ValueError("linked or extra prepared Gradle entry")
        actual.add(path.relative_to(output).as_posix())
    if actual != expected:
        raise ValueError("prepared Gradle inventory mismatch")
    home = output / "distribution" / f"gradle-{VERSION}"
    launcher = home / "bin" / ("gradle.bat" if os.name == "nt" else "gradle")
    if not launcher.is_file() or not (home / "lib" / f"gradle-core-{VERSION}.jar").is_file():
        raise ValueError("complete pinned Gradle launcher and core are required")
    return home


def prepare(output: Path) -> Path:
    """실패한 staging은 보존하고 완성된 고정 설치만 최종 출력으로 공개한다."""
    if output.is_symlink():
        raise ValueError("linked SafeArgs output is not permitted")
    if output.exists():
        return verify_prepared(output)
    output.parent.mkdir(parents=True, exist_ok=True)
    if shutil.disk_usage(output.parent).free < SHARED.MAX_ARCHIVE + SHARED.MAX_EXPANDED + 64 * 1024 * 1024:
        raise ValueError("insufficient space for bounded Gradle fixture preparation")
    stage = Path(tempfile.mkdtemp(prefix=".safeargs-current-gradle-", dir=output.parent))
    archive = stage / f"gradle-{VERSION}-bin.zip"
    SHARED.download(archive, version=VERSION)
    home = SHARED.unpack(archive, stage / "distribution", DISTRIBUTION_SHA, version=VERSION)
    launcher = home / "bin" / ("gradle.bat" if os.name == "nt" else "gradle")
    core = home / "lib" / f"gradle-core-{VERSION}.jar"
    if not launcher.is_file() or not core.is_file():
        raise ValueError("complete pinned Gradle launcher and core are required")
    (stage / "receipt.json").write_text(json.dumps(receipt(), indent=2) + "\n", encoding="utf-8")
    verify_prepared(stage)
    # 동일 부모 내 rename이므로 불완전한 설치 경로는 소비자에게 노출하지 않는다.
    if output.is_symlink() or output.exists():
        raise ValueError("SafeArgs output appeared during preparation")
    stage.rename(output)
    return output / "distribution" / f"gradle-{VERSION}"


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--output", type=Path, required=True)
    args = parser.parse_args()
    try:
        prepare(args.output)
    except (OSError, ValueError, EOFError, RecursionError, zipfile.BadZipFile) as error:
        print(f"SafeArgs fixed Gradle preparation failed ({type(error).__name__}); retained staging evidence")
        return 1
    print(f"SafeArgs fixed Gradle {VERSION} prepared with its pinned SHA256")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
