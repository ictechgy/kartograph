#!/usr/bin/env python3
"""제안된 수정 패치 하나를 결정적으로 채점한다.

이 과정은 AI 실행기와 의도적으로 독립되어 있다. 요청한 커밋을 복제하고
신뢰한 native 빌드 패치를 적용한 뒤 제안된 제품 패치를 검증·적용하고,
신뢰한 hidden test 패치를 적용해 고정된 test argv를 실행한다. 실제 JUnit
관찰에 기대한 gold 통과 테스트가 모두 있고 승인하지 않은 실패가 없을
때에만 패치를 받아들인다. 종료 코드와 모델 주장은 정답 판정에 쓰지 않는다.

최소 JSON 명세::

    {
      "id": "case-id",
      "repository": "/local/checkout",
      "revision": "<full commit SHA>",
      "nativeBuildPatch": "/trusted/build-config.patch",
      "proposedPatch": "/trial/patch.diff",
      "editPaths": ["module/src/main/p/Thing.kt"],
      "testPatch": "/trusted/hidden-tests.patch",
      "testCommand": ["./gradlew", "test"],
      "testEnv": {"TEST_REPORT_DIR": "build/test-results"},
      "reportsGlobs": ["**/test-results/**/TEST-*.xml"],
      "expectedGoldTests": "/trusted/gold-tests.json",
      "knownBaselineFailures": []
    }

``compileCommand``와 ``timeoutSeconds``는 설정된 test command가 먼저 컴파일하지
않는 프로젝트를 위한 선택 편의 항목이다. native와 hidden 패치는 이 독립
채점기가 신뢰하는 입력이며 실행기가 모델 workspace로 복사하지 않는다.
``testIdentityPolicy``는 기본 ``exact``이며 detekt JUnit object identity 비교가
필요한 경우에만 ``detekt-junit-object-identities-v1``을 선택한다.
"""

import argparse
from collections import Counter
from dataclasses import dataclass
import hashlib
import json
import math
import os
from pathlib import Path
import re
import signal
import stat
import subprocess
import sys
import tempfile
import time
import xml.etree.ElementTree as ET
from typing import Any, Iterable, Mapping


DEFAULT_TIMEOUT_SECONDS = 900.0
MAX_TIMEOUT_SECONDS = 900.0
MAX_LOG_BYTES = 16 * 1024 * 1024
_SHA = re.compile(r"^(?:[0-9a-fA-F]{40}|[0-9a-fA-F]{64})$")
_DRIVE = re.compile(r"^[A-Za-z]:")
_ENV_NAME = re.compile(r"^[A-Za-z_][A-Za-z0-9_]*$")
_TEST_PARTS = {
    "test", "tests", "androidtest", "testfixtures", "test-fixtures",
    "integrationtest", "functionaltest", "commontest", "sharedtest",
}
_SECRET_PARTS = {
    ".git", ".ssh", ".aws", ".gnupg", ".netrc", "auth", "auth.json",
    "credentials", "credentials.json", "secrets", "secret", "keystore",
    "keychain", "id_rsa", "id_ed25519", "password", "passwords", "token",
    "tokens",
}
_SECRET_SUFFIXES = (".pem", ".key", ".p12", ".pfx", ".jks", ".keystore")
_BUILD_FILES = {
    "build.gradle", "build.gradle.kts", "settings.gradle", "settings.gradle.kts",
    "gradle.properties", "gradle.lockfile", "gradlew", "gradlew.bat", "pom.xml",
    "mvnw", "mvnw.cmd", "ant.xml", "build.xml", "libs.versions.toml",
}
_DIFF_HEADER = re.compile(r"^diff --git a/(.*?) b/(.*)$")
_IDENTITY_POLICIES = {"exact", "detekt-junit-object-identities-v1"}
_DETEKT_IDENTITY_CONTEXTS = (
    (
        "detekt-core/build/test-results/test/TEST-io.gitlab.arturbosch.detekt.core.suppressors.AnnotationSuppressorSpec$Full#20Qualified#20names$general#20cases.xml",
        "io.gitlab.arturbosch.detekt.core.suppressors.AnnotationSuppressorSpec$Full Qualified names$general cases",
        (
            re.compile(r"^\[1\] org\.jetbrains\.kotlin\.resolve\.BindingTraceContext\$1@[0-9a-f]+$"),
            re.compile(r"^\[2\] org\.jetbrains\.kotlin\.resolve\.BindingContext\$1@[0-9a-f]+$"),
        ),
    ),
    (
        "detekt-formatting/build/test-results/test/TEST-io.gitlab.arturbosch.detekt.formatting.WrapperSmokeTestSpec.xml",
        "io.gitlab.arturbosch.detekt.formatting.WrapperSmokeTestSpec",
        (re.compile(r"^for rule: (io\.gitlab\.arturbosch\.detekt\.formatting\.wrappers\.[A-Za-z_$][A-Za-z0-9_$]*)@[0-9a-f]+$"),),
    ),
)


class GradeError(RuntimeError):
    """제한된 채점 입력·실행·근거 처리 중 발생한 실패다."""


class GradeSpecError(GradeError):
    """채점 명세가 올바르지 않다."""


class PatchError(GradeError):
    """제안했거나 신뢰한 패치를 안전하게 적용할 수 없다."""


class TrustedInputError(GradeError):
    """신뢰한 채점 입력을 준비하거나 적용할 수 없다."""


class OracleApplicationError(GradeError):
    """신뢰한 hidden test 입력을 workspace에 적용할 수 없다."""


def _identity_policy(value: Any) -> str:
    if value is None:
        return "exact"
    if not isinstance(value, str) or value not in _IDENTITY_POLICIES:
        raise GradeSpecError("testIdentityPolicy must be exact or detekt-junit-object-identities-v1")
    return value


def _sha256_bytes(value: bytes) -> str:
    return hashlib.sha256(value).hexdigest()


def _sha256_text(value: str) -> str:
    return _sha256_bytes(value.encode("utf-8"))


def _canonical(value: Any) -> str:
    return json.dumps(value, ensure_ascii=False, sort_keys=True, separators=(",", ":"))


def _safe_relative(value: Any) -> str | None:
    if not isinstance(value, str) or not value or "\x00" in value or "\\" in value:
        return None
    if value.startswith("/") or _DRIVE.match(value) or any(ord(c) < 32 for c in value):
        return None
    parts = value.split("/")
    if any(part in {"", ".", ".."} for part in parts):
        return None
    return "/".join(parts)


def _denied(value: str) -> bool:
    parts = value.lower().split("/")
    return any(part == ".env" or part.startswith(".env.") or part in _SECRET_PARTS or part.endswith(_SECRET_SUFFIXES) for part in parts)


def _is_test_path(value: str) -> bool:
    parts = value.lower().split("/")
    if any(part in _TEST_PARTS for part in parts):
        return True
    stem = Path(parts[-1]).stem
    return stem.startswith("test") or stem.endswith("test") or stem.endswith("tests")


def _is_build_path(value: str) -> bool:
    normalised = value.replace("\\", "/")
    if not normalised:
        return False
    parts = normalised.lower().split("/")
    name = parts[-1]
    if name in _BUILD_FILES:
        return True
    if parts[0] in {"gradle", ".gradle"}:
        return True
    if name.startswith("build.gradle") or name.startswith("settings.gradle"):
        return True
    if name.endswith((".gradle", ".gradle.kts")):
        return True
    return "wrapper" in parts and parts[0] == "gradle"


def _scrub(value: Any, paths: Iterable[Any]) -> Any:
    paths = tuple(paths)
    trusted_paths = _trusted_path_strings(paths)
    if isinstance(value, str):
        text = value
        for path in trusted_paths:
            text = _known_path_pattern(path).sub("<local>", text)
        return text
    if isinstance(value, list):
        return [_scrub(item, paths) for item in value]
    if isinstance(value, tuple):
        return [_scrub(item, paths) for item in value]
    if isinstance(value, dict):
        return {str(key): _scrub(item, paths) for key, item in value.items()}
    return value


def _trusted_path_strings(paths: Iterable[Any]) -> list[str]:
    candidates: set[str] = set()
    for item in paths:
        if not item:
            continue
        raw = str(item)
        try:
            absolute = Path(raw).is_absolute()
        except (OSError, ValueError):
            continue
        if raw and absolute and raw != os.sep:
            candidates.add(raw)
    return sorted(candidates, key=len, reverse=True)


def _known_path_pattern(path: str) -> re.Pattern[str]:
    return re.compile(rf"(?<![A-Za-z0-9_.-]){re.escape(path)}(?![A-Za-z0-9_.-])")


def _argv(value: Any, name: str) -> tuple[str, ...]:
    if not isinstance(value, list) or not value or any(not isinstance(item, str) or not item for item in value):
        raise GradeSpecError(f"{name} must be a non-empty argv list")
    if any("\x00" in item or any(ord(c) < 32 for c in item) for item in value):
        raise GradeSpecError(f"{name} contains an invalid argument")
    return tuple(value)


def _relative_paths(value: Any, name: str, *, required: bool = True) -> tuple[str, ...]:
    if value is None and not required:
        return ()
    if not isinstance(value, list) or (required and not value):
        raise GradeSpecError(f"{name} must be a non-empty relative path list")
    result: list[str] = []
    for item in value:
        normalised = _safe_relative(item)
        if normalised is None or _denied(normalised):
            raise GradeSpecError(f"{name} contains an invalid relative path")
        if normalised not in result:
            result.append(normalised)
    return tuple(result)


def _env(value: Any) -> dict[str, str]:
    if value is None:
        return {}
    if not isinstance(value, dict) or any(not isinstance(key, str) or not _ENV_NAME.fullmatch(key) or not isinstance(item, str) for key, item in value.items()):
        raise GradeSpecError("testEnv must map environment names to strings")
    return dict(value)


@dataclass(frozen=True)
class GradeSpec:
    identifier: str
    repository: Path
    revision: str
    native_build_patch: Any
    proposed_patch: Any
    edit_paths: tuple[str, ...]
    test_patch: Any
    test_command: tuple[str, ...]
    test_env: Mapping[str, str]
    report_globs: tuple[str, ...]
    expected_gold_tests: Any
    known_baseline_failures: Any
    test_identity_policy: str = "exact"
    compile_command: tuple[str, ...] | None = None
    timeout_seconds: float = DEFAULT_TIMEOUT_SECONDS
    spec_directory: Path = Path.cwd()

    @classmethod
    def from_json(cls, value: Any, *, spec_directory: Path | None = None) -> "GradeSpec":
        if not isinstance(value, dict):
            raise GradeSpecError("spec must be a JSON object")
        identifier = value.get("id")
        if not isinstance(identifier, str) or not identifier or len(identifier) > 200 or any(ord(c) < 32 for c in identifier):
            raise GradeSpecError("id must be non-empty text")
        revision = value.get("revision")
        if not isinstance(revision, str) or not _SHA.fullmatch(revision):
            raise GradeSpecError("revision must be a full commit SHA")
        base = (spec_directory or Path.cwd()).resolve()
        repository_value = value.get("repository")
        if not isinstance(repository_value, str) or not repository_value:
            raise GradeSpecError("repository must be a local checkout path")
        repository = Path(repository_value).expanduser()
        if not repository.is_absolute():
            repository = base / repository
        try:
            repository = repository.resolve(strict=True)
        except OSError as error:
            raise GradeSpecError("repository is not accessible") from error
        if not repository.is_dir():
            raise GradeSpecError("repository must be a directory")
        report_value = value.get("reportsGlobs", value.get("reportGlobs", value.get("reportsGlob", value.get("reports"))))
        if isinstance(report_value, str):
            report_value = [report_value]
        if not isinstance(report_value, list) or not report_value:
            raise GradeSpecError("reportsGlobs must be a non-empty list")
        report_globs: list[str] = []
        for pattern in report_value:
            if not isinstance(pattern, str) or not pattern or pattern.startswith("/") or _DRIVE.match(pattern) or "\\" in pattern:
                raise GradeSpecError("reportsGlobs contains an invalid pattern")
            parts = pattern.split("/")
            if any(part in {"", ".", ".."} or _denied(part) for part in parts):
                raise GradeSpecError("reportsGlobs contains an invalid pattern")
            if pattern not in report_globs:
                report_globs.append(pattern)
        compile_value = value.get("compileCommand")
        timeout_value = value.get("timeoutSeconds", DEFAULT_TIMEOUT_SECONDS)
        if not isinstance(timeout_value, (int, float)) or isinstance(timeout_value, bool) or not math.isfinite(float(timeout_value)) or not 0 < float(timeout_value) <= MAX_TIMEOUT_SECONDS:
            raise GradeSpecError("timeoutSeconds must be between 0 and 900")
        edit_paths = _relative_paths(value.get("editPaths"), "editPaths")
        if any(_is_test_path(path) or _is_build_path(path) for path in edit_paths):
            raise GradeSpecError("editPaths must contain production paths only")
        test_identity_policy = _identity_policy(value.get("testIdentityPolicy"))
        return cls(
            identifier=identifier,
            repository=repository,
            revision=revision.lower(),
            native_build_patch=value.get("nativeBuildPatch"),
            proposed_patch=value.get("proposedPatch"),
            edit_paths=edit_paths,
            test_patch=value.get("testPatch"),
            test_command=_argv(value.get("testCommand"), "testCommand"),
            test_env=_env(value.get("testEnv")),
            report_globs=tuple(report_globs),
            expected_gold_tests=value.get("expectedGoldTests"),
            known_baseline_failures=value.get("knownBaselineFailures") or [],
            test_identity_policy=test_identity_policy,
            compile_command=_argv(compile_value, "compileCommand") if compile_value is not None else None,
            timeout_seconds=float(timeout_value),
            spec_directory=base,
        )


def load_spec(path: Path | str) -> GradeSpec:
    try:
        spec_path = Path(path).resolve(strict=True)
        value = json.loads(spec_path.read_text(encoding="utf-8"))
    except (OSError, UnicodeError, json.JSONDecodeError) as error:
        raise GradeSpecError("unable to read spec JSON") from error
    return GradeSpec.from_json(value, spec_directory=spec_path.parent)


def _git(cwd: Path, arguments: list[str], *, timeout: float = 60.0) -> str:
    try:
        result = subprocess.run(["git", *arguments], cwd=cwd, capture_output=True, text=True, timeout=timeout, check=False)
    except (OSError, subprocess.TimeoutExpired) as error:
        raise GradeError("git operation failed") from error
    if result.returncode:
        raise GradeError("git operation failed")
    return result.stdout


def _git_paths(cwd: Path, arguments: list[str]) -> list[str]:
    value = _git(cwd, arguments)
    return [item for item in (value.split("\0") if "\0" in value else value.splitlines()) if item]


def _safe_workspace_path(workspace: Path, relative: str, *, require_exists: bool = False) -> Path:
    normalised = _safe_relative(relative)
    if normalised is None or _denied(normalised):
        raise GradeError("workspace path is not permitted")
    candidate = workspace / normalised
    current = workspace
    try:
        for part in normalised.split("/"):
            current = current / part
            info = current.lstat()
            if stat.S_ISLNK(info.st_mode):
                raise GradeError("workspace symlink path is not permitted")
        resolved = candidate.resolve(strict=require_exists)
    except FileNotFoundError:
        if require_exists:
            raise GradeError("workspace path does not exist")
        resolved = candidate.resolve(strict=False)
    except OSError as error:
        raise GradeError("workspace path is not accessible") from error
    try:
        resolved.relative_to(workspace.resolve(strict=True))
    except (OSError, ValueError) as error:
        raise GradeError("workspace path escapes clone") from error
    return resolved


def _path_hashes(workspace: Path, paths: Iterable[str]) -> dict[str, str]:
    result: dict[str, str] = {}
    for relative in sorted(set(paths)):
        try:
            path = _safe_workspace_path(workspace, relative, require_exists=True)
            if path.is_file():
                result[relative] = _sha256_bytes(path.read_bytes())
        except (OSError, GradeError):
            continue
    return result


def _tracked_and_untracked(workspace: Path) -> set[str]:
    tracked = set(_git_paths(workspace, ["ls-files", "-z"]))
    untracked = set(_git_paths(workspace, ["ls-files", "--others", "--exclude-standard"]))
    return {path.replace("\\", "/") for path in tracked | untracked if _safe_relative(path) and not _denied(path)}


def _workspace_hashes(workspace: Path) -> dict[str, str]:
    return _path_hashes(workspace, _tracked_and_untracked(workspace))


def _modified_paths(workspace: Path, revision: str) -> set[str]:
    names = set(_git_paths(workspace, ["diff", "--name-only", revision]))
    names.update(_git_paths(workspace, ["ls-files", "--others", "--exclude-standard"]))
    return {name.replace("\\", "/") for name in names if name and _safe_relative(name) and not name.startswith(".grader-")}


def _resolve_input(value: Any, spec: GradeSpec, name: str, *, required: bool = True) -> tuple[Path | None, str | None, str | None]:
    if value is None:
        if required:
            raise GradeSpecError(f"{name} is required")
        return None, None, None
    if isinstance(value, dict):
        if "path" in value or "file" in value:
            value = value.get("path", value.get("file"))
        elif "patch" in value and name.endswith("Patch"):
            inline = value.get("patch")
            if not isinstance(inline, str) or not inline:
                raise GradeSpecError(f"{name} is invalid")
            return None, inline, _sha256_text(inline)
    if not isinstance(value, str) or not value:
        if value == "" and name.endswith("Patch"):
            return None, "", _sha256_text("")
        raise GradeSpecError(f"{name} must name a file")
    if "\n" in value and ("diff --git " in value or value.startswith("--- ")):
        return None, value, _sha256_text(value)
    candidate = Path(value).expanduser()
    try:
        if candidate.is_symlink():
            raise GradeSpecError(f"{name} must not be a symlink")
    except OSError as error:
        raise GradeSpecError(f"{name} is not accessible") from error
    if not candidate.is_absolute():
        choices = [spec.spec_directory / candidate, spec.repository / candidate]
        candidate = next((item for item in choices if item.is_file()), choices[0])
    try:
        if candidate.is_symlink():
            raise GradeSpecError(f"{name} must not be a symlink")
        path = candidate.resolve(strict=True)
        if not path.is_file() or path.is_symlink() or any(_denied(part) for part in path.parts):
            raise GradeSpecError(f"{name} is not permitted")
        raw = path.read_bytes()
    except (OSError, UnicodeError) as error:
        raise GradeSpecError(f"{name} is not readable") from error
    return path, raw.decode("utf-8", errors="strict"), _sha256_bytes(raw)


def _stage_inline_patch(content: str) -> tuple[Path, Path]:
    try:
        descriptor, filename = tempfile.mkstemp(prefix="kartograph-grade-patch-", suffix=".patch")
        os.close(descriptor)
        path = Path(filename)
        path.write_text(content, encoding="utf-8")
    except OSError as error:
        raise PatchError("patch staging failed") from error
    return path, path


def _apply_patch(workspace: Path, patch_path: Path, *, label: str) -> None:
    try:
        if patch_path.stat().st_size == 0:
            return
    except OSError as error:
        raise PatchError(f"{label} is unreadable") from error
    try:
        checked = subprocess.run(["git", "apply", "--check", "--whitespace=nowarn", str(patch_path)], cwd=workspace, capture_output=True, text=True, timeout=60, check=False)
        if checked.returncode:
            raise PatchError(f"{label} does not apply")
        applied = subprocess.run(["git", "apply", "--whitespace=nowarn", str(patch_path)], cwd=workspace, capture_output=True, text=True, timeout=60, check=False)
        if applied.returncode:
            raise PatchError(f"{label} could not be applied")
    except (OSError, subprocess.TimeoutExpired) as error:
        raise PatchError(f"{label} could not be applied") from error


def _declared_patch_paths(content: str) -> set[str]:
    """무시된 추가 파일도 허용 목록을 우회하지 못하도록 패치 경로 헤더를 읽는다."""

    paths: set[str] = set()
    for line in content.splitlines():
        match = _DIFF_HEADER.match(line)
        if match:
            for value in match.groups():
                normalised = _safe_relative(value)
                if normalised is None or _denied(normalised):
                    raise PatchError("patch contains an invalid path")
                paths.add(normalised)
        elif line.startswith(("--- ", "+++ ")):
            value = line[4:].split("\t", 1)[0].split(" ", 1)[0]
            if value == "/dev/null":
                continue
            prefix = "a/" if line.startswith("--- ") else "b/"
            if value.startswith(prefix):
                value = value[2:]
            normalised = _safe_relative(value)
            if normalised is None or _denied(normalised):
                raise PatchError("patch contains an invalid path")
            paths.add(normalised)
    return paths


def _apply_input(workspace: Path, value: Any, spec: GradeSpec, name: str, *, required: bool = True) -> tuple[str | None, str | None]:
    path, content, digest = _resolve_input(value, spec, name, required=required)
    if content is None:
        return None, digest
    temporary: Path | None = None
    if path is None:
        temporary, _ = _stage_inline_patch(content)
        path = temporary
    try:
        _apply_patch(workspace, path, label=name)
    finally:
        if temporary is not None:
            try:
                temporary.unlink()
            except OSError:
                pass
    return digest, None


def _validate_native_paths(paths: Iterable[str]) -> None:
    invalid = [path for path in paths if not _is_build_path(path)]
    if invalid:
        raise PatchError("native build patch may change build configuration only")


def _validate_proposed_paths(paths: Iterable[str], edit_paths: Iterable[str]) -> None:
    edit_paths = tuple(edit_paths)
    invalid = [
        path for path in paths
        if not any(path == root or path.startswith(root + "/") for root in edit_paths)
        or _is_test_path(path) or _is_build_path(path)
    ]
    if invalid:
        raise PatchError("proposed patch changes an unallowed, test, or build path")


def _load_records(value: Any, spec: GradeSpec, name: str) -> tuple[list[dict[str, Any]], str]:
    digest: str
    if isinstance(value, dict) and ("path" in value or "file" in value):
        value = value.get("path", value.get("file"))
    if isinstance(value, (str, Path)):
        if isinstance(value, str) and value.lstrip().startswith(("[", "{")):
            try:
                raw = value.encode("utf-8")
                value = json.loads(value)
                digest = _sha256_bytes(raw)
            except (UnicodeError, json.JSONDecodeError) as error:
                raise GradeSpecError(f"{name} JSON is unreadable") from error
        else:
            path = Path(value).expanduser()
            if not path.is_absolute():
                choices = [spec.spec_directory / path, spec.repository / path]
                path = next((item for item in choices if item.is_file()), choices[0])
            try:
                if path.is_symlink():
                    raise GradeSpecError(f"{name} must not be a symlink")
                raw = path.read_bytes()
                value = json.loads(raw.decode("utf-8"))
                digest = _sha256_bytes(raw)
            except (OSError, UnicodeError, json.JSONDecodeError) as error:
                raise GradeSpecError(f"{name} JSON is unreadable") from error
    else:
        try:
            digest = _sha256_text(_canonical(value))
        except (TypeError, ValueError) as error:
            raise GradeSpecError(f"{name} is not JSON-compatible") from error
    if isinstance(value, dict):
        value = value.get("tests", value.get("records"))
    if not isinstance(value, list):
        raise GradeSpecError(f"{name} must contain a list")
    if any(not isinstance(item, dict) for item in value):
        raise GradeSpecError(f"{name} contains an invalid record")
    return [dict(item) for item in value], digest


def _record_text(item: Mapping[str, Any], key: str, aliases: tuple[str, ...] = ()) -> str:
    value = item.get(key)
    if value is None:
        for alias in aliases:
            if item.get(alias) is not None:
                value = item[alias]
                break
    if not isinstance(value, str) or not value or any(ord(c) < 32 and c not in "\t\n\r" for c in value):
        raise GradeSpecError("test record contains invalid identity text")
    return value


def _record_status(value: Any) -> str:
    if not isinstance(value, str):
        raise GradeSpecError("test record contains invalid status")
    normalised = value.lower()
    if normalised in {"pass", "passed", "success", "ok"}:
        return "passed"
    if normalised in {"fail", "failed", "error"}:
        return "failed"
    if normalised in {"skip", "skipped", "ignored"}:
        return "skipped"
    raise GradeSpecError("test record contains unknown status")


def _normalise_expected(records: list[dict[str, Any]], policy: str = "exact") -> tuple[
    list[dict[str, str | None]],
    Counter[tuple[str | None, str, str | tuple[str, str]]],
    list[dict[str, str | None]],
]:
    policy = _identity_policy(policy)
    result: list[dict[str, str | None]] = []
    counts: Counter[tuple[str | None, str, str | tuple[str, str]]] = Counter()
    skipped: list[dict[str, str | None]] = []
    for item in records:
        report_value = item.get("report", item.get("reportPath", item.get("reportRelativePath")))
        if report_value is None:
            if policy != "exact":
                raise GradeSpecError("expected test report path is required for testIdentityPolicy")
            report = None
        else:
            report = _safe_relative(report_value)
            if report is None or _denied(report):
                raise GradeSpecError("expected test report path is invalid")
        klass = _record_text(item, "class", ("classname", "className"))
        name = _record_text(item, "name")
        status = _record_status(item.get("status"))
        row = {"report": report, "class": klass, "name": name, "status": status}
        if status == "passed":
            result.append(row)
            counts[_comparison_key(row, policy)] += 1
        elif status == "skipped":
            skipped.append(row)
    if not result:
        raise GradeSpecError("expectedGoldTests has no passing tests")
    return result, counts, skipped


def _comparison_name(report: str | None, klass: str, name: str, policy: str) -> str | tuple[str, str]:
    if policy == "exact":
        return name
    for context_report, context_class, patterns in _DETEKT_IDENTITY_CONTEXTS:
        if report != context_report or klass != context_class:
            continue
        for pattern in patterns:
            match = pattern.fullmatch(name)
            if match:
                value = match.group(1) if match.lastindex else name.rsplit("@", 1)[0]
                return ("detekt-junit-object-identities-v1", value)
    return name


def _comparison_key(row: Mapping[str, Any], policy: str) -> tuple[str | None, str, str | tuple[str, str]]:
    return (row["report"], row["class"], _comparison_name(row["report"], row["class"], row["name"], policy))


def _raw_identity(row: Mapping[str, Any]) -> list[Any]:
    return [row["report"], row["class"], row["name"]]


def _comparison_sort_key(identity: tuple[str | None, str, str | tuple[str, str]]) -> tuple[Any, ...]:
    def component(value: Any) -> tuple[Any, ...]:
        if value is None:
            return (0, "")
        if isinstance(value, tuple):
            return (2, *value)
        return (1, value)
    return tuple(component(value) for value in identity)


def _bind_expected_reports(expected: list[dict[str, str | None]], rows: Iterable[Mapping[str, Any]]) -> list[dict[str, str | None]]:
    actual = list(rows)
    bound: list[dict[str, str | None]] = []
    for item in expected:
        report = item.get("report")
        if report is None:
            paths = sorted({str(row["report"]) for row in actual if row["class"] == item["class"] and row["name"] == item["name"]})
            if len(paths) > 1:
                raise GradeError("expected test identity is ambiguous across reports")
            report = paths[0] if paths else None
        bound.append({"report": report, "class": str(item["class"]), "name": str(item["name"]), "status": "passed"})
    return bound


def _normalise_baseline(records: list[dict[str, Any]], policy: str = "exact") -> list[dict[str, str | None]]:
    policy = _identity_policy(policy)
    result: list[dict[str, str | None]] = []
    for item in records:
        report_value = item.get("report", item.get("reportPath", item.get("reportRelativePath")))
        if report_value is not None:
            report = _safe_relative(report_value)
            if report is None or _denied(report):
                raise GradeSpecError("known baseline report path is invalid")
        else:
            if policy != "exact":
                raise GradeSpecError("known baseline report path is required for testIdentityPolicy")
            report = None
        klass = _record_text(item, "class", ("classname", "className"))
        name = _record_text(item, "name")
        status = _record_status(item.get("status"))
        if status == "passed":
            raise GradeSpecError("known baseline failures must not be passing")
        result.append({"report": report, "class": klass, "name": name, "status": status})
    return result


def _junit_rows(workspace: Path, reports: Iterable[Path]) -> list[dict[str, Any]]:
    rows: list[dict[str, Any]] = []
    for report in sorted(set(reports)):
        try:
            relative = report.relative_to(workspace).as_posix()
            root = ET.parse(report).getroot()
        except (OSError, ValueError, ET.ParseError) as error:
            raise GradeError("JUnit report is invalid") from error
        for testcase in root.iter():
            if not isinstance(testcase.tag, str) or testcase.tag.rsplit("}", 1)[-1] != "testcase":
                continue
            klass = testcase.attrib.get("classname")
            name = testcase.attrib.get("name")
            if not klass or not name:
                raise GradeError("JUnit testcase has no class or name")
            children = [child.tag.rsplit("}", 1)[-1] for child in testcase]
            status = "failed" if "failure" in children or "error" in children else "skipped" if "skipped" in children else "passed"
            rows.append({"report": relative, "class": klass, "name": name, "status": status,
                         "identity": [relative, klass, name],
                         "flaky": "flakyFailure" in children or "rerunFailure" in children})
    return rows


def _baseline_match(row: Mapping[str, Any], baseline: Iterable[Mapping[str, Any]], policy: str = "exact") -> bool:
    for item in baseline:
        if ((item.get("report") is None or row["report"] == item["report"])
                and _comparison_key(row, policy)[1:] == _comparison_key(item, policy)[1:]):
            return row["status"] == item["status"] or row["status"] in {"failed", "skipped"}
    return False


def compare_tests(
    rows: list[dict[str, Any]], expected: list[dict[str, str | None]],
    baseline: list[dict[str, str | None]],
    gold_skipped: list[dict[str, str | None]] | None = None,
    policy: str = "exact",
) -> dict[str, Any]:
    policy = _identity_policy(policy)
    if policy != "exact" and any(row.get("report") is None for row in expected):
        raise GradeSpecError("expected test report path is required for testIdentityPolicy")
    expected = _bind_expected_reports(expected, rows)
    gold_skipped = _bind_expected_reports(gold_skipped or [], rows)
    expected_counts = Counter(_comparison_key(row, policy) for row in expected)
    actual_counts = Counter(_comparison_key(row, policy) for row in rows)
    passing_counts = Counter(_comparison_key(row, policy) for row in rows if row["status"] == "passed")
    skipped_identities = {_comparison_key(row, policy) for row in gold_skipped}
    expected_identities = set(expected_counts)
    expected_rows = {key: row for row in expected for key in [_comparison_key(row, policy)]}
    actual_rows = {key: row for row in rows for key in [_comparison_key(row, policy)]}
    missing: list[dict[str, Any]] = []
    non_passing: list[dict[str, Any]] = []
    count_mismatch: list[dict[str, Any]] = []
    for identity, count in sorted(expected_counts.items(), key=lambda item: _comparison_sort_key(item[0])):
        if actual_counts[identity] < count:
            missing.append({"identity": _raw_identity(expected_rows[identity]), "expected": count, "observed": actual_counts[identity]})
        if passing_counts[identity] < count:
            non_passing.append({"identity": _raw_identity(expected_rows[identity]), "expected": count, "passing": passing_counts[identity]})
        if actual_counts[identity] > count:
            count_mismatch.append({"identity": _raw_identity(expected_rows[identity]), "expected": count, "observed": actual_counts[identity]})
    duplicates = [
        {"identity": _raw_identity(actual_rows[identity]), "count": count}
        for identity, count in sorted(actual_counts.items(), key=lambda item: _comparison_sort_key(item[0])) if count > 1
    ]
    baseline_failures = [row for row in rows if row["status"] != "passed" and _baseline_match(row, baseline, policy)]
    new_failures = [row for row in rows if row["status"] == "failed" and not _baseline_match(row, baseline, policy)]
    unexpected_skipped = [
        row for row in rows
        if row["status"] == "skipped"
        and _comparison_key(row, policy) not in skipped_identities
        and _comparison_key(row, policy) not in expected_identities
        and not _baseline_match(row, baseline, policy)
    ]
    return {
        "expectedPassing": len(expected),
        "observed": len(rows),
        "passing": sum(1 for row in rows if row["status"] == "passed"),
        "missingExpected": missing,
        "nonPassingExpected": non_passing,
        "expectedCountMismatch": count_mismatch,
        "duplicateIdentities": duplicates,
        "knownBaselineFailures": baseline_failures,
        "newFailures": new_failures,
        "goldSkipped": gold_skipped,
        "unexpectedSkipped": unexpected_skipped,
        "testIdentityPolicy": policy,
        "ok": bool(rows) and not missing and not non_passing and not count_mismatch and not new_failures and not unexpected_skipped,
    }


def _collect_report_files(workspace: Path, patterns: Iterable[str]) -> list[Path]:
    found: set[Path] = set()
    for pattern in patterns:
        for path in workspace.glob(pattern):
            try:
                if path.is_symlink() or not path.is_file() or not path.resolve(strict=True).is_relative_to(workspace.resolve(strict=True)):
                    continue
            except (OSError, ValueError):
                continue
            found.add(path.resolve(strict=True))
    return sorted(found)


def _run_command(command: tuple[str, ...], workspace: Path, environment: Mapping[str, str], *, timeout: float, stdout_path: Path, stderr_path: Path) -> dict[str, Any]:
    started = time.monotonic()
    process: subprocess.Popen[bytes] | None = None
    stdout_data = b""
    stderr_data = b""
    timed_out = False
    try:
        process = subprocess.Popen(list(command), cwd=workspace, env={**os.environ, **environment}, stdin=subprocess.DEVNULL, stdout=subprocess.PIPE, stderr=subprocess.PIPE, start_new_session=(os.name == "posix"))
        try:
            stdout_data, stderr_data = process.communicate(timeout=timeout)
        except subprocess.TimeoutExpired as timeout_error:
            timed_out = True
            stdout_data = timeout_error.output or b""
            stderr_data = timeout_error.stderr or b""
            try:
                if os.name == "posix":
                    os.killpg(process.pid, signal.SIGTERM)
                else:
                    process.terminate()
                process.wait(timeout=0.5)
            except (OSError, subprocess.TimeoutExpired):
                pass
            # 리더가 먼저 종료해도 자식이 프로세스 그룹을 유지할 수 있으므로 유예 후 그룹을 강제 종료한다.
            try:
                if os.name == "posix":
                    os.killpg(process.pid, signal.SIGKILL)
                else:
                    process.kill()
            except OSError:
                pass
            try:
                process.wait(timeout=1.0)
            except subprocess.TimeoutExpired:
                pass
            # 리더가 먼저 끝나 파이프를 자식이 잡고 있을 수 있으므로 그룹 종료 뒤 수거한다.
            try:
                stdout_data, stderr_data = process.communicate(timeout=1.0)
            except subprocess.TimeoutExpired:
                stdout_data, stderr_data = b"", b""
    except (OSError, ValueError):
        return {"returnCode": None, "timedOut": False, "seconds": time.monotonic() - started, "error": "process_failed"}
    stdout_path.write_bytes(stdout_data[:MAX_LOG_BYTES])
    stderr_path.write_bytes(stderr_data[:MAX_LOG_BYTES])
    return {
        "returnCode": process.returncode, "timedOut": timed_out,
        "seconds": round(time.monotonic() - started, 6),
        "stdoutBytes": len(stdout_data), "stderrBytes": len(stderr_data),
        "stdoutTruncated": len(stdout_data) > MAX_LOG_BYTES, "stderrTruncated": len(stderr_data) > MAX_LOG_BYTES,
    }


def _clone(spec: GradeSpec, output: Path) -> Path:
    if output.exists():
        if not output.is_dir() or any(output.iterdir()):
            raise GradeSpecError("output must be a new directory")
    else:
        try:
            output.mkdir(parents=True)
        except OSError as error:
            raise GradeError("unable to create output directory") from error
    workspace = output / "workspace"
    try:
        advertised = _git(spec.repository, ["rev-parse", "--verify", f"{spec.revision}^{{commit}}"]).strip().lower()
    except GradeError as error:
        raise GradeSpecError("requested revision is unavailable") from error
    if advertised != spec.revision:
        raise GradeSpecError("requested revision differs")
    try:
        clone = subprocess.run(["git", "clone", "--no-local", "--no-checkout", str(spec.repository), str(workspace)], capture_output=True, text=True, timeout=120, check=False)
        if clone.returncode:
            raise GradeError("unable to clone repository")
        checkout = subprocess.run(["git", "checkout", "--detach", spec.revision], cwd=workspace, capture_output=True, text=True, timeout=120, check=False)
        if checkout.returncode:
            raise GradeError("unable to check out requested revision")
    except (OSError, subprocess.TimeoutExpired) as error:
        raise GradeError("unable to prepare repository clone") from error
    if _git(workspace, ["rev-parse", "HEAD"]).strip().lower() != spec.revision or _git(workspace, ["status", "--porcelain"]).strip():
        raise GradeError("prepared workspace is not the requested clean commit")
    return workspace


def _write_json(path: Path, value: Any) -> None:
    path.write_text(json.dumps(value, ensure_ascii=False, indent=2, sort_keys=True) + "\n", encoding="utf-8")


def grade(
    spec: GradeSpec | Mapping[str, Any], *, output: Path | str,
    timeout_seconds: float | None = None,
) -> dict[str, Any]:
    """결정적 채점기를 실행하고 이식 가능한 결과 산출물을 쓴다."""

    if not isinstance(spec, GradeSpec):
        spec = GradeSpec.from_json(spec)
    timeout = spec.timeout_seconds if timeout_seconds is None else float(timeout_seconds)
    if not math.isfinite(timeout) or not 0 < timeout <= MAX_TIMEOUT_SECONDS:
        raise GradeSpecError("timeoutSeconds must be between 0 and 900")
    output_path = Path(output).expanduser().resolve(strict=False)
    total_started = time.monotonic()
    output_was_available = not output_path.exists() or (
        output_path.is_dir() and not any(output_path.iterdir())
    )
    workspace: Path | None = None
    logs: dict[str, Any] = {}
    outcome = "infrastructure_failure"
    error_code: str | None = None
    native_hash: str | None = None
    proposed_hash: str | None = None
    test_hash: str | None = None
    expected_hash: str | None = None
    baseline_hash: str | None = None
    baseline_records: list[dict[str, str | None]] = []
    expected_records: list[dict[str, str]] = []
    gold_skipped_records: list[dict[str, str | None]] = []
    rows: list[dict[str, Any]] = []
    comparison: dict[str, Any] = {"ok": False, "testIdentityPolicy": spec.test_identity_policy}
    native_paths: set[str] = set()
    proposed_paths: set[str] = set()
    try:
        workspace = _clone(spec, output_path)
        for edit_path in spec.edit_paths:
            _safe_workspace_path(workspace, edit_path, require_exists=False)
        baseline_hashes = _workspace_hashes(workspace)
        native_value = spec.native_build_patch
        if native_value is not None:
            try:
                native_path, native_content, native_hash = _resolve_input(native_value, spec, "nativeBuildPatch")
                native_declared_paths: set[str] = set()
                if native_content is not None:
                    native_declared_paths = _declared_patch_paths(native_content)
                    _validate_native_paths(native_declared_paths)
                temporary: Path | None = None
                if native_content is not None:
                    if native_path is None:
                        temporary, _ = _stage_inline_patch(native_content)
                        native_path = temporary
                    try:
                        _apply_patch(workspace, native_path, label="nativeBuildPatch")
                    finally:
                        if temporary is not None:
                            try:
                                temporary.unlink()
                            except OSError:
                                pass
            except (GradeSpecError, PatchError) as error:
                raise TrustedInputError("native build patch is invalid") from error
            native_after_hashes = _workspace_hashes(workspace)
            native_paths = set(_modified_paths(workspace, spec.revision))
            native_paths.update(native_declared_paths)
            native_paths.update(
                path for path in set(baseline_hashes) | set(native_after_hashes)
                if baseline_hashes.get(path) != native_after_hashes.get(path)
            )
            try:
                _validate_native_paths(native_paths)
            except PatchError as error:
                raise TrustedInputError("native build patch is invalid") from error
        native_hashes = _workspace_hashes(workspace)
        proposed_path, proposed_content, proposed_hash = _resolve_input(spec.proposed_patch, spec, "proposedPatch")
        proposed_declared_paths: set[str] = set()
        if proposed_content is not None:
            proposed_declared_paths = _declared_patch_paths(proposed_content)
        temporary = None
        if proposed_content is not None:
            if proposed_path is None:
                temporary, _ = _stage_inline_patch(proposed_content)
                proposed_path = temporary
            try:
                _apply_patch(workspace, proposed_path, label="proposedPatch")
            finally:
                if temporary is not None:
                    try:
                        temporary.unlink()
                    except OSError:
                        pass
        proposed_after_hashes = _workspace_hashes(workspace)
        proposed_paths = {path for path in set(native_hashes) | set(proposed_after_hashes) if native_hashes.get(path) != proposed_after_hashes.get(path)}
        proposed_paths.update(_modified_paths(workspace, spec.revision) - native_paths)
        proposed_paths.update(proposed_declared_paths)
        for path in proposed_paths:
            _safe_workspace_path(workspace, path, require_exists=False)
        _validate_proposed_paths(proposed_paths, spec.edit_paths)
        if proposed_paths & native_paths:
            raise PatchError("proposed patch changes a native build path")

        try:
            test_path, test_content, test_hash = _resolve_input(spec.test_patch, spec, "testPatch")
            if test_path is not None:
                try:
                    test_path.relative_to(workspace.resolve(strict=True))
                except ValueError:
                    pass
                else:
                    raise PatchError("test patch must remain outside the trial workspace")
            temporary = None
            if test_content is not None:
                if test_path is None:
                    temporary, _ = _stage_inline_patch(test_content)
                    test_path = temporary
                try:
                    _apply_patch(workspace, test_path, label="testPatch")
                finally:
                    if temporary is not None:
                        try:
                            temporary.unlink()
                        except OSError:
                            pass
        except (GradeSpecError, PatchError) as error:
            raise OracleApplicationError("test patch is invalid") from error
        expected_raw, expected_hash = _load_records(spec.expected_gold_tests, spec, "expectedGoldTests")
        expected_records, _expected_counts, gold_skipped_records = _normalise_expected(expected_raw, policy=spec.test_identity_policy)
        baseline_raw, baseline_hash = _load_records(spec.known_baseline_failures, spec, "knownBaselineFailures")
        baseline_records = _normalise_baseline(baseline_raw, policy=spec.test_identity_policy)
        deadline = time.monotonic() + timeout
        if spec.compile_command is not None:
            remaining = deadline - time.monotonic()
            if remaining <= 0:
                raise GradeError("compile timeout")
            logs["compile"] = _run_command(spec.compile_command, workspace, spec.test_env, timeout=remaining, stdout_path=output_path / "compile.stdout", stderr_path=output_path / "compile.stderr")
            if logs["compile"].get("timedOut"):
                outcome = "compile_timeout"
                error_code = "compile_timeout"
                raise GradeError("compile timeout")
            if logs["compile"].get("error") or logs["compile"].get("returnCode") != 0:
                outcome = "compile_failed"
                error_code = "compile_failed"
                raise GradeError("compile failed")
        reports_before = _collect_report_files(workspace, spec.report_globs)
        before_report_meta = {
            path: (path.stat().st_mtime_ns, path.stat().st_size, _sha256_bytes(path.read_bytes()))
            for path in reports_before
        }
        remaining = deadline - time.monotonic()
        if remaining <= 0:
            outcome = "test_timeout"
            error_code = "test_timeout"
            raise GradeError("test timeout")
        logs["test"] = _run_command(spec.test_command, workspace, spec.test_env, timeout=remaining, stdout_path=output_path / "test.stdout", stderr_path=output_path / "test.stderr")
        if logs["test"].get("timedOut"):
            outcome = "test_timeout"
            error_code = "test_timeout"
            raise GradeError("test timeout")
        if logs["test"].get("error"):
            outcome = "test_command_failed"
            error_code = "test_command_failed"
            raise GradeError("test command failed")
        reports_after = _collect_report_files(workspace, spec.report_globs)
        fresh_reports = []
        for path in reports_after:
            meta = (path.stat().st_mtime_ns, path.stat().st_size, _sha256_bytes(path.read_bytes()))
            if path not in before_report_meta or before_report_meta[path] != meta:
                fresh_reports.append(path)
        if not fresh_reports:
            outcome = "empty_test_results"
            error_code = "empty_test_results"
            raise GradeError("no fresh JUnit results")
        rows = _junit_rows(workspace, fresh_reports)
        if not rows:
            outcome = "empty_test_results"
            error_code = "empty_test_results"
            raise GradeError("JUnit reports contain no tests")
        comparison = compare_tests(rows, expected_records, baseline_records, gold_skipped_records, policy=spec.test_identity_policy)
        command_failed = bool(logs["test"].get("error")) or logs["test"].get("returnCode") not in {0, None}
        if not comparison["ok"]:
            outcome = "tests_rejected"
            error_code = "tests_rejected"
        elif command_failed:
            # 알려진 기준 실패 때문에 native 테스트 종료 코드가 0이 아닐 수 있다.
            # 위에 기록했으므로 새 실패로 판정하지 않는다.
            outcome = "passed_known_baseline_failures" if comparison["knownBaselineFailures"] else "test_command_failed"
            error_code = None if comparison["knownBaselineFailures"] else "test_command_failed"
        else:
            outcome = "passed"
        if error_code is not None:
            raise GradeError(error_code)
    except (GradeError, OSError, ValueError, ET.ParseError) as error:
        if isinstance(error, TrustedInputError):
            error_code = "grader_input_failure"
            outcome = "grader_input_failure"
        elif isinstance(error, OracleApplicationError):
            error_code = "oracle_application_failure"
            outcome = "oracle_application_failure"
        elif isinstance(error, PatchError):
            error_code = "patch_violation"
            outcome = "patch_rejected"
        if error_code is None:
            error_code = "grade_failed"
        if outcome == "infrastructure_failure":
            outcome = error_code
    finally:
        total_seconds = round(time.monotonic() - total_started, 6)
        paths_for_output = [spec.repository, output_path, workspace] if workspace is not None else [spec.repository, output_path]
        paths_for_output.append(Path.home())
        paths_for_output.extend(value for value in spec.test_env.values() if value and (value.startswith("/") or _DRIVE.match(value)))
        result = {
            "schemaVersion": 1,
            "id": spec.identifier,
            "revision": spec.revision,
            "outcome": outcome,
            "verdict": outcome in {"passed", "passed_known_baseline_failures"},
            "error": error_code,
            "inputs": {
                "nativeBuildPatchSha256": native_hash,
                "proposedPatchSha256": proposed_hash,
                "testPatchSha256": test_hash,
                "expectedGoldTestsSha256": expected_hash,
                "knownBaselineFailuresSha256": baseline_hash,
                "testIdentityPolicy": spec.test_identity_policy,
            },
            "patch": {
                "allowedPaths": sorted(proposed_paths & set(spec.edit_paths)),
                "modifiedPaths": sorted(proposed_paths),
                "nativeBuildPaths": sorted(native_paths),
                "editPaths": list(spec.edit_paths),
                "violations": sorted(proposed_paths - set(spec.edit_paths)),
            },
            "tests": {
                "reports": sorted(path.relative_to(workspace).as_posix() for path in _collect_report_files(workspace, spec.report_globs)) if workspace is not None else [],
                "rows": _scrub(rows, paths_for_output),
                "comparison": _scrub(comparison, paths_for_output),
                "knownBaselineFailures": _scrub(baseline_records, paths_for_output),
            },
            "process": _scrub(logs, paths_for_output),
            "timings": {"totalSeconds": total_seconds},
            "workspace": "workspace" if workspace is not None else None,
        }
        result["ok"] = result["verdict"]
        metrics = {
            "totalSeconds": total_seconds,
            "compileSeconds": logs.get("compile", {}).get("seconds"),
            "testSeconds": logs.get("test", {}).get("seconds"),
            "reportCount": len(result["tests"]["reports"]),
            "testCount": len(rows),
            "expectedPassingCount": len(expected_records),
        }
        if output_was_available and output_path.exists() and output_path.is_dir():
            try:
                _write_json(output_path / "result.json", _scrub(result, paths_for_output))
                _write_json(output_path / "metrics.json", _scrub(metrics, paths_for_output))
            except OSError:
                pass
    return _scrub(result, paths_for_output)


def _build_parser() -> argparse.ArgumentParser:
    parser = argparse.ArgumentParser(
        description="Deterministically grade one proposed impact-repair patch.",
        epilog=(
            "Spec fields: repository, revision, nativeBuildPatch, proposedPatch, editPaths, testPatch, "
            "testCommand, testEnv, reportsGlobs, expectedGoldTests and knownBaselineFailures. "
            "Optional testIdentityPolicy is exact (default) or detekt-junit-object-identities-v1. "
            "Optional compileCommand and timeoutSeconds are local grader controls. Artifacts are result.json, "
            "metrics.json, workspace and raw command logs."
        ),
    )
    parser.add_argument("--spec", required=True)
    parser.add_argument("--output", required=True)
    parser.add_argument("--timeout-seconds", type=float, default=None)
    return parser


def main(argv: list[str] | None = None) -> int:
    parser = _build_parser()
    try:
        args = parser.parse_args(argv)
    except SystemExit as error:
        return int(error.code)
    try:
        spec = load_spec(args.spec)
        result = grade(spec, output=args.output, timeout_seconds=args.timeout_seconds)
    except GradeSpecError:
        print("error: invalid grading specification", file=sys.stderr)
        return 64
    except GradeError:
        print("error: grading execution failed", file=sys.stderr)
        return 2
    print(json.dumps({"result": "result.json", "outcome": result.get("outcome")}, sort_keys=True))
    return 0 if result.get("verdict") else 1


if __name__ == "__main__":
    raise SystemExit(main())
