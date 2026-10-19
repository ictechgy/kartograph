"""배포 파일·체크섬·실제 로드 JAR를 검증하며 source fallback을 제공하지 않는다."""
import argparse
import hashlib
import json
import os
from pathlib import Path, PurePosixPath
import re
import stat
import tarfile
import zipfile

MAX_FILES = 4096
MAX_LIBRARIES = 256
MAX_RECEIPT = 512 * 1024
HASH = re.compile(r"[0-9a-f]{64}")
SAFE_NAME = re.compile(r"[A-Za-z0-9_.+-]{1,200}")


def require(condition, message):
    if not condition:
        raise RuntimeError(message)


def digest(path):
    checksum = hashlib.sha256()
    with path.open("rb") as stream:
        for block in iter(lambda: stream.read(1024 * 1024), b""):
            checksum.update(block)
    return checksum.hexdigest()


def parse_arguments(argv):
    parser = argparse.ArgumentParser(description="Verify real AGP resource evidence, optionally from retained release artifacts.")
    parser.add_argument("--packaged", action="store_true")
    for option in ("plugin-jar", "zip-cli", "tar-cli", "zip-archive", "tar-archive",
                   "checksums", "retained-hashes", "version", "evidence"):
        parser.add_argument("--" + option)
    args = parser.parse_args(argv)
    supplied = {key for key, value in vars(args).items() if key != "packaged" and value is not None}
    required = {"plugin_jar", "zip_cli", "tar_cli", "zip_archive", "tar_archive",
                "checksums", "retained_hashes", "version", "evidence"}
    if args.packaged and supplied != required:
        parser.error("--packaged requires every explicit artifact, checksum, version and evidence argument")
    if not args.packaged and supplied:
        parser.error("artifact arguments require --packaged; source fallback is forbidden")
    if args.packaged and not re.fullmatch(r"[0-9]+\.[0-9]+\.[0-9]+", args.version):
        parser.error("--version must be stable SemVer")
    return args


def regular_file(path):
    require(path.is_file() and not path.is_symlink(), "required regular artifact is missing or symbolic")
    return path.resolve(strict=True)


def inventory(root):
    require(root.is_dir() and not root.is_symlink(), "extracted installation directory is missing or symbolic")
    result = {}
    for path in sorted(root.rglob("*")):
        require(not path.is_symlink(), "symbolic extracted installation entry is forbidden")
        if path.is_dir():
            continue
        require(path.is_file(), "nonregular extracted installation entry is forbidden")
        relative = path.relative_to(root).as_posix()
        safe_relative(relative)
        require(relative not in result, "duplicate extracted installation entry")
        result[relative] = digest(path)
        require(len(result) <= MAX_FILES, "extracted installation exceeds bounded file inventory")
    require(bool(result), "empty extracted installation")
    return result


def safe_relative(name):
    require(isinstance(name, str) and len(name) <= 512 and "\\" not in name,
            "invalid archive or inventory path")
    parts = name.split("/")
    require(all(SAFE_NAME.fullmatch(part) and part not in (".", "..") for part in parts),
            "unsafe or aliased archive or inventory path")
    require(str(PurePosixPath(name)) == name, "noncanonical inventory path")
    return name


def archive_inventory(path, label, version):
    prefix = "kartograph-" + version + "/"
    result, seen = {}, set()
    def add(name, stream, directory=False):
        require(name.startswith(prefix), "archive has an unexpected installation root")
        relative = name[len(prefix):].rstrip("/") if directory else name[len(prefix):]
        if not relative:
            require(directory and name == prefix, "invalid archive root entry")
            require(name not in seen, "duplicate archive entry")
            seen.add(name)
            return
        safe_relative(relative)
        # 파일/디렉터리와 표기 변형 충돌도 거부한다.
        require(relative not in seen, "duplicate or colliding archive entry")
        seen.add(relative)
        require(len(seen) <= MAX_FILES * 2, "archive exceeds bounded entry inventory")
        if not directory:
            require(not any(str(parent) in result for parent in PurePosixPath(relative).parents
                            if str(parent) != "."), "archive file/parent collision")
            checksum = hashlib.sha256()
            for block in iter(lambda: stream.read(1024 * 1024), b""):
                checksum.update(block)
            result[relative] = checksum.hexdigest()
            require(len(result) <= MAX_FILES, "archive exceeds bounded file inventory")
    if label == "zip":
        with zipfile.ZipFile(path) as archive:
            for item in archive.infolist():
                mode = item.external_attr >> 16
                require(not stat.S_ISLNK(mode), "symbolic ZIP entry is forbidden")
                require(not item.flag_bits & 1, "encrypted ZIP entry is forbidden")
                require(stat.S_IFMT(mode) in (0, stat.S_IFREG, stat.S_IFDIR), "nonregular ZIP entry is forbidden")
                if item.is_dir():
                    add(item.filename, None, True)
                else:
                    with archive.open(item) as stream:
                        add(item.filename, stream)
    else:
        with tarfile.open(path, "r:") as archive:
            for item in archive:
                require(item.isfile() or item.isdir(), "nonregular TAR entry is forbidden")
                if item.isdir():
                    add(item.name.rstrip("/") + "/", None, True)
                else:
                    with archive.extractfile(item) as stream:
                        add(item.name, stream)
    require(bool(result), "empty CLI archive")
    for name in seen - {prefix}:
        require(not any(str(parent) in result for parent in PurePosixPath(name).parents
                        if str(parent) != "."), "archive file/parent collision")
    return dict(sorted(result.items()))


def checksums(path, relative_paths):
    require(path.stat().st_size <= 64 * 1024, "checksum inventory exceeds bound")
    result, basenames = {}, set()
    for line in path.read_text(encoding="utf-8").splitlines():
        match = re.fullmatch(r"([0-9a-f]{64})  ([A-Za-z0-9_./+-]+)", line)
        require(match is not None, "malformed checksum inventory")
        sha, name = match.groups()
        safe_relative(name)
        require(relative_paths or "/" not in name, "release checksum must use a basename")
        require(name not in result and Path(name).name not in basenames,
                "duplicate or basename-colliding checksum entry")
        result[name] = sha
        basenames.add(Path(name).name)
    require(0 < len(result) <= 64, "checksum inventory must be bounded and nonempty")
    return result


class PackagedArtifacts:
    def __init__(self, args, root):
        self.version = args.version
        self.root = root.resolve(strict=True)
        self.plugin = regular_file(Path(args.plugin_jar))
        self.clis = {label: regular_file(Path(getattr(args, label + "_cli"))) for label in ("zip", "tar")}
        self.archives = {label: regular_file(Path(getattr(args, label + "_archive"))) for label in ("zip", "tar")}
        self.checksums = regular_file(Path(args.checksums))
        self.retained = regular_file(Path(args.retained_hashes))
        self.output = Path(args.evidence).resolve()
        expected_plugin = "kartograph-gradle-plugin-" + self.version + ".jar"
        require(self.plugin.name == expected_plugin, "plugin artifact version/name differs")
        paths = [self.plugin, self.checksums, self.retained, *self.clis.values(), *self.archives.values()]
        require(len(set(paths)) == len(paths), "packaged artifact paths collide")
        require(len({(path.stat().st_dev, path.stat().st_ino) for path in paths}) == len(paths),
                "packaged artifacts alias the same file")
        for label, cli in self.clis.items():
            require(cli.name == "kartograph" and cli.parent.name == "bin"
                    and cli.parent.parent.name == "kartograph-" + self.version,
                    "explicit CLI must belong to the extracted versioned installation")
            require(os.access(cli, os.X_OK), "extracted CLI launcher is not executable")
            require(self.archives[label].name == "kartograph-" + self.version + "." + label,
                    "CLI archive version/name differs")
            require(self.output != cli.parent.parent and cli.parent.parent not in self.output.parents,
                    "evidence must not mutate an extracted installation")
        self.initial = self.capture()
        self.verify_manifests()
        self.verify_archives()
        self.verify_plugin()

    def capture(self):
        installations = {}
        for label, cli in self.clis.items():
            files = inventory(cli.parent.parent)
            libraries = {name: sha for name, sha in files.items() if name.startswith("lib/")}
            require(0 < len(libraries) <= MAX_LIBRARIES, "CLI library inventory missing or exceeds bound")
            require(all(name.endswith(".jar") for name in libraries), "unexpected CLI library entry")
            require(len({Path(name).name for name in libraries}) == len(libraries),
                    "CLI library basenames collide")
            installations[label] = {"launcherSha256": files.get("bin/kartograph"),
                                    "libraries": libraries, "files": files}
        return {"pluginSha256": digest(self.plugin), "checksumsSha256": digest(self.checksums),
                "retainedHashesSha256": digest(self.retained),
                "archives": {label: digest(path) for label, path in self.archives.items()},
                "installations": installations}

    def verify_manifests(self):
        manifest = checksums(self.checksums, False)
        retained = checksums(self.retained, True)
        require(self.checksums.name == "SHA256SUMS", "release checksum manifest name differs")
        expected_release = {
            "cli/build/distributions/kartograph-" + self.version + ".zip",
            "cli/build/distributions/kartograph-" + self.version + ".tar",
            "gradle-plugin/build/libs/kartograph-gradle-plugin-" + self.version + ".jar",
            "compiler-collectors/build/distributions/kartograph-compiler-collectors-" + self.version + ".zip",
            "build/release/kartograph-" + self.version + ".cdx.json",
            "build/release/kartograph-gradle-plugin-" + self.version + ".cdx.json",
        }
        expected_retained = expected_release | {"build/release/SHA256SUMS",
            "gradle-plugin/build/publications/pluginMaven/pom-default.xml"}
        require(set(retained) == expected_retained, "retained second-build inventory is incomplete")
        require(set(manifest) == {Path(name).name for name in expected_release},
                "release SHA256SUMS inventory is incomplete")
        # second.sha256는 이번 두 번째 clean build가 남긴 모든 release 파일을 검증한다.
        for name, expected in retained.items():
            candidate = self.root / name
            require(candidate.resolve().is_relative_to(self.root), "retained artifact escapes repository")
            require(digest(regular_file(candidate)) == expected, "retained second-build artifact digest differs")
        required = [self.plugin, self.checksums, *self.archives.values()]
        for path in required:
            require(path.is_relative_to(self.root), "retained artifact is outside the repository")
            name = path.relative_to(self.root).as_posix()
            require(retained.get(name) == digest(path), "artifact is absent from retained second-build inventory")
        for path in (self.plugin, *self.archives.values()):
            require(manifest.get(path.name) == digest(path), "release SHA256SUMS artifact digest differs")
        for name, expected in manifest.items():
            matches = [sha for relative, sha in retained.items() if Path(relative).name == name]
            require(matches == [expected], "release checksum is absent or ambiguous in retained inventory")

    def verify_archives(self):
        for label, archive in self.archives.items():
            require(archive_inventory(archive, label, self.version)
                    == self.initial["installations"][label]["files"],
                    "extracted CLI differs from its retained archive")
        require(self.initial["installations"]["zip"] == self.initial["installations"]["tar"],
                "ZIP/TAR complete installation inventories differ")

    def verify_plugin(self):
        with zipfile.ZipFile(self.plugin) as archive:
            names = archive.namelist()
            require(len(names) == len(set(names)), "plugin JAR contains colliding entries")
            raw = archive.read("META-INF/MANIFEST.MF").decode("utf-8").replace("\r\n", "\n")
            versions = re.findall(r"(?m)^Implementation-Version: ([^\n]+)$", raw)
            require(versions == [self.version], "plugin manifest version differs")
            name = "dev/kartograph/gradle/KartographPlugin.class"
            self.plugin_class_sha256 = hashlib.sha256(archive.read(name)).hexdigest()

    def unchanged(self):
        require(self.capture() == self.initial, "packaged artifact or complete CLI inventory mutated")
        self.verify_manifests()

    def public_identity(self):
        return {"plugin": {"name": self.plugin.name, "sha256": self.initial["pluginSha256"],
                           "classSha256": self.plugin_class_sha256},
                "checksumsSha256": self.initial["checksumsSha256"],
                "retainedHashesSha256": self.initial["retainedHashesSha256"],
                "archives": [{"format": label, "name": self.archives[label].name,
                              "sha256": self.initial["archives"][label],
                              "launcherSha256": self.initial["installations"][label]["launcherSha256"],
                              "libraries": [{"path": name, "sha256": sha} for name, sha in
                                            self.initial["installations"][label]["libraries"].items()]}
                             for label in ("zip", "tar")]}


def verify_runtime(document, artifacts):
    require(isinstance(document, dict) and set(document) == {
        "version", "includedBuilds", "projectDependencies", "pluginFileDependencySha256",
        "plugin", "resolvedBuildscriptArtifacts", "hostKotlin"}, "unexpected packaged runtime receipt")
    require(document["version"] == 1 and document["includedBuilds"] == 0
            and document["projectDependencies"] == 0, "included/project build fallback is forbidden")
    require(document["pluginFileDependencySha256"] == artifacts.initial["pluginSha256"],
            "fixture file dependency is not the retained plugin")
    plugin = document["plugin"]
    require(isinstance(plugin, dict) and set(plugin) == {"version", "jarSha256", "classSha256"},
            "unexpected loaded plugin identity")
    require(plugin["version"] == artifacts.version, "loaded plugin version differs")
    require(plugin["jarSha256"] == artifacts.initial["pluginSha256"]
            and plugin["classSha256"] == artifacts.plugin_class_sha256,
            "loaded plugin origin is not byte-bound to the retained JAR; transformed origins require explicit investigation")
    rows = document["resolvedBuildscriptArtifacts"]
    require(isinstance(rows, list) and 0 < len(rows) <= 256, "resolved buildscript inventory is missing or too large")
    identities = []
    for row in rows:
        require(isinstance(row, dict) and set(row) == {"coordinate", "file", "sha256"},
                "unexpected buildscript artifact identity")
        require(re.fullmatch(r"[A-Za-z0-9_.+-]+:[A-Za-z0-9_.+-]+:[A-Za-z0-9_.+-]+(?::[A-Za-z0-9_.+-]+)?", row["coordinate"])
                and SAFE_NAME.fullmatch(row["file"]) and HASH.fullmatch(row["sha256"]),
                "invalid or private buildscript artifact identity")
        identities.append((row["coordinate"], row["file"]))
    require(identities == sorted(set(identities)), "resolved buildscript inventory is unsorted or colliding")
    require(any(row["coordinate"] == "com.android.tools.build:gradle:8.7.3" for row in rows)
            and any(row["coordinate"].split(":")[:3] == ["org.jetbrains.kotlin", "kotlin-gradle-plugin", "2.0.21"]
                    for row in rows), "resolved AGP/KGP differs from the fixture")
    host = document["hostKotlin"]
    require(isinstance(host, dict) and set(host) == {"version", "jarSha256", "classSha256"},
            "unexpected loaded host Kotlin identity")
    require(re.fullmatch(r"[0-9]+\.[0-9]+\.[0-9]+(?:[-+][A-Za-z0-9_.+-]+)?", host["version"])
            and HASH.fullmatch(host["jarSha256"]) and HASH.fullmatch(host["classSha256"]),
            "invalid loaded host Kotlin identity")
    return document


MAX_DIFFERENCES = 16
FRESHNESS_FIELDS = ("format", "version", "status", "hashNanos", "reasons")
MISSING = object()


def canonical_json(value):
    return json.dumps(value, sort_keys=True, separators=(",", ":"), ensure_ascii=True,
                      allow_nan=False).encode("utf-8")


def json_identity(value):
    """임의 값/키 대신 JSON 타입·크기·내용 지문만 공개한다."""
    if value is MISSING:
        return {"type": "missing"}
    kind = {dict: "object", list: "array", str: "string", int: "integer",
            float: "number", bool: "boolean", type(None): "null"}[type(value)]
    encoded = canonical_json(value)
    result = {"type": kind, "sha256": hashlib.sha256(encoded).hexdigest(), "bytes": len(encoded)}
    if isinstance(value, (dict, list)):
        result["count"] = len(value)
    return result


def semantic_diagnostic(documents):
    """고정 freshness 경로만 펼치며 그 밖의 구조는 하나의 불투명 지문으로 남긴다."""
    result = {"zip": json_identity(documents["zip"]), "tar": json_identity(documents["tar"]),
              "differences": [], "truncated": False}
    rows = result["differences"]

    def add(path, left, right):
        if json_identity(left) == json_identity(right):
            return
        if len(rows) == MAX_DIFFERENCES:
            result["truncated"] = True
            return
        row = {"path": path, "zip": json_identity(left), "tar": json_identity(right)}
        if path == "/hashNanos":
            for label, value in (("zip", left), ("tar", right)):
                if type(value) is int and 0 <= value <= 2**63 - 1:
                    row[label]["value"] = value
        rows.append(row)

    left, right = documents["zip"], documents["tar"]
    if type(left) is not dict or type(right) is not dict:
        add("$", left, right)
        return result
    for key in FRESHNESS_FIELDS:
        add("/" + key, left.get(key, MISSING), right.get(key, MISSING))
    # 동적 object key는 경로에 넣지 않는다. 배열 index만 한정해서 공개한다.
    reasons = left.get("reasons"), right.get("reasons")
    if all(type(value) is list for value in reasons):
        count = max(map(len, reasons))
        for index in range(min(count, MAX_DIFFERENCES)):
            add("/reasons/" + str(index),
                reasons[0][index] if index < len(reasons[0]) else MISSING,
                reasons[1][index] if index < len(reasons[1]) else MISSING)
        if count > MAX_DIFFERENCES and canonical_json(reasons[0]) != canonical_json(reasons[1]):
            result["truncated"] = True
    add("$otherRootFields", {key: value for key, value in left.items() if key not in FRESHNESS_FIELDS},
        {key: value for key, value in right.items() if key not in FRESHNESS_FIELDS})
    return result


def public_semantic_diagnostic(document):
    """stdout 재투영에서도 고정 어휘·지문·정수 외의 문자열은 거부한다."""
    keys = {"zip", "tar", "type", "sha256", "bytes", "count", "value", "differences",
            "truncated", "path", "reason", "invalidFields", "comparison", "semanticSha256",
            "command", "canonicalJsonSha256", "excludedRootFields", "hashNanos"}
    strings = {"missing", "object", "array", "string", "integer", "number", "boolean", "null",
               "invalidFreshnessSchema", "semanticMismatch", "verify-snapshot", "impact",
               "$", "$otherRootFields", *FRESHNESS_FIELDS, *("/" + key for key in FRESHNESS_FIELDS),
               *("/reasons/" + str(index) for index in range(MAX_DIFFERENCES))}
    remaining = 512

    def check(value, depth=0):
        nonlocal remaining
        remaining -= 1
        require(remaining >= 0 and depth <= 6, "semantic diagnostic exceeds structural bound")
        if type(value) is dict:
            require(set(value) <= keys, "unexpected semantic diagnostic key")
            for item in value.values():
                check(item, depth + 1)
        elif type(value) is list:
            require(len(value) <= MAX_DIFFERENCES, "semantic diagnostic exceeds row bound")
            for item in value:
                check(item, depth + 1)
        elif type(value) is str:
            require(value in strings or HASH.fullmatch(value), "unsafe semantic diagnostic string")
        else:
            require(type(value) is bool or type(value) is int and 0 <= value <= 2**63 - 1,
                    "invalid semantic diagnostic value")

    check(document)
    require(len(canonical_json(document)) <= 16 * 1024, "semantic diagnostic exceeds byte bound")
    return document


class SemanticComparisonError(RuntimeError):
    def __init__(self, message, diagnostic):
        super().__init__(message)
        self.diagnostic = public_semantic_diagnostic(diagnostic)


def semantic_pair(documents, command):
    require(set(documents) == {"zip", "tar"}, "both extracted CLIs must be queried")
    require(command in ("verify-snapshot", "impact"), "unexpected packaged comparison command")
    raw = {label: canonical_json(document) for label, document in documents.items()}
    comparison = {"command": command,
                  "canonicalJsonSha256": {label: hashlib.sha256(value).hexdigest() for label, value in raw.items()},
                  "excludedRootFields": []}
    semantic = dict(raw)
    if command == "verify-snapshot":
        invalid = {}
        for label, value in documents.items():
            if type(value) is not dict:
                invalid[label] = ["$"]
                continue
            checks = {
                "format": value.get("format") == "kartograph-freshness",
                "version": type(value.get("version")) is int and value["version"] == 1,
                "status": value.get("status") in ("matched", "stale", "unverified"),
                "hashNanos": type(value.get("hashNanos")) is int and 0 <= value["hashNanos"] <= 2**63 - 1,
                "reasons": type(value.get("reasons")) is list
                           and all(type(reason) is str for reason in value["reasons"]),
            }
            if not all(checks.values()):
                invalid[label] = [key for key in FRESHNESS_FIELDS if not checks[key]]
        if invalid:
            diagnostic = semantic_diagnostic(documents)
            diagnostic.update({"reason": "invalidFreshnessSchema", "invalidFields": invalid, "comparison": comparison})
            raise SemanticComparisonError("invalid verify-snapshot freshness schema or hashNanos", diagnostic)
        # FreshnessCommand의 호출별 경과 시간만 제외한다. 나머지/추가 필드는 전부 비교한다.
        semantic = {label: canonical_json({key: value for key, value in document.items() if key != "hashNanos"})
                    for label, document in documents.items()}
        comparison["excludedRootFields"] = ["hashNanos"]
        comparison["hashNanos"] = {label: document["hashNanos"] for label, document in documents.items()}
    if semantic["zip"] != semantic["tar"]:
        diagnostic = semantic_diagnostic(documents)
        diagnostic.update({"reason": "semanticMismatch", "comparison": comparison,
                           "semanticSha256": {label: hashlib.sha256(value).hexdigest() for label, value in semantic.items()}})
        raise SemanticComparisonError("ZIP/TAR complete semantic outputs differ", diagnostic)
    return documents["zip"], hashlib.sha256(semantic["zip"]).hexdigest(), comparison

def write_receipt(path, document):
    encoded = json.dumps(document, sort_keys=True, indent=2, ensure_ascii=True) + "\n"
    require(len(encoded.encode("utf-8")) <= MAX_RECEIPT, "packaged verification receipt exceeds bound")
    path.write_text(encoded, encoding="utf-8")


def runtime_diagnostic(document):
    """실패 시에도 origin 지문만 보존하고 임의 문자열은 공개하지 않는다."""
    if not isinstance(document, dict):
        return {}
    result = {}
    for label in ("plugin", "hostKotlin"):
        value = document.get(label)
        if not isinstance(value, dict):
            continue
        identity = {key: value[key] for key in ("jarSha256", "classSha256")
                    if isinstance(value.get(key), str) and HASH.fullmatch(value[key])}
        version = value.get("version")
        if isinstance(version, str) and re.fullmatch(r"[0-9]+\.[0-9]+\.[0-9]+(?:[-+][A-Za-z0-9_.+-]+)?", version):
            identity["version"] = version
        result[label] = identity
    return result
