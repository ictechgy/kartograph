#!/usr/bin/env python3
"""수정 평가를 위한 제한된 로컬 read/edit/test/impact 제어기.

``RepairSession``은 의도적으로 model client나 grader가 아닌 제어기다. 상위
실행기가 새 workspace와 고정 command argv를 제공하며, request는 선언한
허용 목록 밖의 shell command나 경로를 전달할 수 없다.

공개 action schema(모든 값은 JSON 호환):

* ``list``: ``{"action": "list", "prefix"?: str, "offset"?: int,
  "limit"?: int}``.
* ``read``: ``{"action": "read", "path": str, "start"?: int,
  "column"?: int, "count"?: int}``; line은 1부터 시작하고 column은 0부터
  시작한다. 잘리면 ``nextStart``/``nextColumn``을 사용해 이어간다.
* ``search``: ``{"action": "search", "query": str, "prefix"?: str,
  "offset"?: int, "limit"?: int}``.
* ``replace``: ``{"action": "replace", "path": str, "old": str,
  "new": str}``. old text는 정확히 한 번 있어야 하며 test 파일은 수정할 수
  없다.
* ``test``: ``{"action": "test"}``; 설정한 test argv만 실행한다.
* ``refresh``: ``{"action": "refresh"}``; 이전 trial
  snapshot을 무효화하고 설정한 compiler argv를 실행한 다음
  ``binary snapshot <snapshot_arguments>``를 실행한다. 두 command가 모두
  성공하고 source hash가 변하지 않을 때만 결과를 설치한다.
* ``query`` (impact arm에서만 사용): ``{"action": "query", "symbol": str,
  "depth"?: int, "limit"?: int}``. limit을 생략하면 controller가
  ``--limit 5``를 사용하며, 저장된 snapshot에 제품 CLI의
  ``query <symbol> --graph-file <snapshot>``를 적용한다.
* ``impact`` (impact arm에서만 사용): ``{"action": "impact", "symbol"?: str |
  list[str], "file"?: str | list[str], "module"?: str | list[str],
  "affected_file"?: str | list[str], "kind"?: str | list[str],
  "test_status"?: str, "relation"?: str, "path_status"?: str,
  "sort"?: str, "depth"?: int, "limit"?: int, "offset"?: int,
  "all"?: bool, "visit_limit"?: int, "path_limit"?: int}``. 이 field들은
  제품 CLI가 문서화한 impact option으로 변환한다.

``execute``가 수락한 시도마다 call 하나를 소비한다. 출력과 진단은 제한하며
로컬 절대 경로를 정제한다. ``metrics()``는 안전한 counter와 timing만
보고하고 환경 값과 command argv는 반환하지 않는다. refresh 응답은
sourceHashCount를 노출하며, 상위 실행기는 source_hashes()로 전체 지문을
별도로 얻을 수 있다.
"""

from __future__ import annotations

import hashlib
import copy
import json
import math
import os
from pathlib import Path, PurePosixPath
import re
import selectors
import signal
import stat
import subprocess
import tempfile
import time
from typing import Any


_DRIVE_PATH = re.compile(r"^[A-Za-z]:")
_ABSOLUTE_PATH = re.compile(r"(?<![A-Za-z0-9_])/(?:[^\s\"']+/)*[^\s\"']+")
_SECRET_COMPONENTS = {
    ".git", ".ssh", ".aws", ".gnupg", ".netrc", "auth", "auth.json", "credentials",
    "credentials.json", "secrets", "secret", "keystore", "keychain", "id_rsa",
    "id_ed25519", "password", "passwords", "token", "tokens",
}
_SECRET_SUFFIXES = (".pem", ".key", ".p12", ".pfx", ".jks", ".keystore")
_IMPACT_LIST_OPTIONS = {
    "symbol": "--symbol", "file": "--file", "module": "--module",
    "affected_file": "--affected-file", "kind": "--kind",
}
_IMPACT_SCALAR_OPTIONS = {
    "test_status": ("--test-status", {"test", "production", "unknown"}),
    "relation": ("--relation", {"direct", "structural", "transitive", "unknown"}),
    "path_status": ("--path-status", {"complete", "partial", "unavailable"}),
    "sort": ("--sort", {"review", "usr", "module", "file", "test", "test-status", "relation", "path", "path-depth", "path-status"}),
}
_PRESERVE_IMPACT_KEYS = {
    "observedAffected", "unresolved", "truncated", "limitations", "summary",
    "navigation", "budgets", "status", "format", "version", "changed",
}


class RepairSession:
    """제한된 수정 실험 하나와 일회용 분석 snapshot을 소유한다."""

    def __init__(
        self,
        *,
        workspace: Path,
        arm: str,
        read_paths: list[str],
        edit_paths: list[str],
        compile_command: list[str],
        test_command: list[str],
        build_env: dict[str, str],
        analysis_env: dict[str, str],
        binary: Path | None,
        snapshot: Path | None,
        snapshot_arguments: list[str],
        snapshot_source_sha256: dict[str, str] | None = None,
        max_calls: int = 12,
        wall_seconds: float = 900,
        command_timeout: float = 180,
        max_output_chars: int = 12000,
    ) -> None:
        if arm not in {"source", "impact"}:
            raise ValueError("arm must be source or impact")
        if not isinstance(workspace, Path):
            workspace = Path(workspace)
        try:
            resolved_workspace = workspace.resolve(strict=True)
        except OSError as error:
            raise ValueError("workspace must be an existing directory") from error
        if not resolved_workspace.is_dir():
            raise ValueError("workspace must be an existing directory")
        if not isinstance(max_calls, int) or isinstance(max_calls, bool) or max_calls < 1:
            raise ValueError("max_calls must be positive")
        for name, value in (("wall_seconds", wall_seconds), ("command_timeout", command_timeout)):
            if not isinstance(value, (int, float)) or isinstance(value, bool) or not math.isfinite(value) or value <= 0:
                raise ValueError(name + " must be positive")
        if not isinstance(max_output_chars, int) or isinstance(max_output_chars, bool) or max_output_chars < 256:
            raise ValueError("max_output_chars must be at least 256 to retain error metadata")
        self.workspace = resolved_workspace
        self.arm = arm
        self._read_paths = self._normalise_configured_paths(read_paths)
        self._edit_paths = self._normalise_configured_paths(edit_paths)
        if any(not self._is_read_allowed(path) for path in self._edit_paths):
            raise ValueError("edit paths must be a subset of read paths")
        self._compile_command = self._validate_command(compile_command, "compile_command")
        self._test_command = self._validate_command(test_command, "test_command")
        self._build_env = self._validate_env(build_env)
        self._analysis_env = self._validate_env(analysis_env)
        self._binary = self._normalise_binary(binary)
        self._snapshot = self._normalise_snapshot(snapshot)
        self._snapshot_arguments = self._validate_command(snapshot_arguments, "snapshot_arguments")
        if snapshot_source_sha256 is not None and not isinstance(snapshot_source_sha256, dict):
            raise ValueError("snapshot_source_sha256 must be a mapping")
        if snapshot_source_sha256 is not None and any(
            not isinstance(key, str) or not isinstance(value, str)
            for key, value in snapshot_source_sha256.items()
        ):
            raise ValueError("snapshot_source_sha256 must contain string paths and hashes")
        self._snapshot_source_hashes = dict(snapshot_source_sha256) if snapshot_source_sha256 is not None else None
        self._snapshot_ready = bool(self._snapshot and self._snapshot.is_file() and self._snapshot_source_hashes is not None)
        self._max_calls = max_calls
        self._wall_seconds = float(wall_seconds)
        self._command_timeout = float(command_timeout)
        self._max_output_chars = max_output_chars
        self._started = time.monotonic()
        self._calls = 0
        self._metrics: dict[str, Any] = {
            "calls": 0, "sourceFilesExposed": 0, "sourceCharsExposed": 0, "sourceFilesScanned": 0,
            "edits": 0, "queries": 0, "symbolQueries": 0, "impactQueries": 0, "compiles": 0, "tests": 0,
            "captures": 0, "truncations": 0, "changedSourceHashes": [],
            "compileSeconds": 0.0, "testSeconds": 0.0, "captureSeconds": 0.0,
            "querySeconds": 0.0,
        }

    @staticmethod
    def _validate_command(command: list[str], name: str) -> tuple[str, ...]:
        if not isinstance(command, list) or any(not isinstance(value, str) or not value for value in command):
            raise ValueError(name + " must be a non-empty string argv list")
        return tuple(command)

    @staticmethod
    def _validate_env(environment: dict[str, str]) -> dict[str, str]:
        if not isinstance(environment, dict) or any(not isinstance(k, str) or not isinstance(v, str) for k, v in environment.items()):
            raise ValueError("environment must map strings to strings")
        return dict(environment)

    def _normalise_binary(self, binary: Path | None) -> Path | None:
        if binary is None:
            return None
        value = Path(binary)
        return value if value.is_absolute() else self.workspace / value

    def _normalise_snapshot(self, snapshot: Path | None) -> Path | None:
        if snapshot is None:
            return None
        value = Path(snapshot)
        if not value.is_absolute():
            value = self.workspace / value
        if value.is_symlink():
            raise ValueError("snapshot must not be a symlink")
        try:
            parent = value.parent.resolve(strict=False)
            target = value.resolve(strict=False)
            target.relative_to(self.workspace)
            parent.relative_to(self.workspace)
        except (OSError, ValueError) as error:
            raise ValueError("snapshot must be inside workspace") from error
        if self._denied(target.relative_to(self.workspace).as_posix()):
            raise ValueError("snapshot path is not permitted")
        return target

    def _normalise_configured_paths(self, paths: list[str]) -> tuple[str, ...]:
        if not isinstance(paths, list) or any(not isinstance(path, str) for path in paths):
            raise ValueError("paths must be a list of strings")
        normalised = []
        for path in paths:
            checked = self._normalise_relative(path)
            if checked is None:
                raise ValueError("path allowlist contains an invalid relative path")
            if checked not in normalised:
                normalised.append(checked)
        return tuple(normalised)

    def _normalise_relative(self, value: str) -> str | None:
        if not isinstance(value, str) or "\x00" in value or "\\" in value or value.startswith("/") or _DRIVE_PATH.match(value):
            return None
        if any(ord(character) < 32 for character in value):
            return None
        if value in {"", "."}:
            return ""
        parts = value.split("/")
        if any(part in {"", ".", ".."} for part in parts):
            return None
        return PurePosixPath(*parts).as_posix()

    @staticmethod
    def _denied(path: str) -> bool:
        for part in path.lower().split("/"):
            if part == ".env" or part.startswith(".env.") or part in _SECRET_COMPONENTS or part.endswith(_SECRET_SUFFIXES):
                return True
        return False

    @staticmethod
    def _is_test_path(path: str) -> bool:
        parts = path.lower().split("/")
        if any(part in {
            "test", "tests", "androidtest", "testfixtures", "test-fixtures",
            "integrationtest", "functionaltest", "commontest", "sharedtest",
        } for part in parts):
            return True
        stem = Path(parts[-1]).stem
        return stem.startswith("test") or stem.endswith("test") or stem.endswith("tests")

    def _is_read_allowed(self, path: str) -> bool:
        return any(root == "" or path == root or path.startswith(root + "/") for root in self._read_paths)

    def _is_edit_allowed(self, path: str) -> bool:
        return self._is_read_allowed(path) and any(root == "" or path == root or path.startswith(root + "/") for root in self._edit_paths)

    def _is_controller_artifact(self, path: str) -> bool:
        if self._snapshot is not None:
            try:
                if path == self._snapshot.relative_to(self.workspace).as_posix():
                    return True
            except ValueError:
                return True
        return Path(path).name.startswith(".repair-snapshot-")

    def _safe_existing(self, path: str, *, allow_directory: bool = False) -> tuple[Path | None, str | None]:
        normalised = self._normalise_relative(path)
        if normalised is None or self._denied(normalised):
            return None, "path_not_permitted"
        candidate = self.workspace / normalised
        try:
            relative_parts = normalised.split("/") if normalised else []
            current = self.workspace
            for part in relative_parts:
                current = current / part
                info = current.lstat()
                if stat.S_ISLNK(info.st_mode):
                    return None, "symlink_not_permitted"
            resolved = candidate.resolve(strict=True)
            resolved.relative_to(self.workspace)
            info = resolved.stat()
        except (OSError, ValueError):
            return None, "path_not_found"
        if stat.S_ISLNK(candidate.lstat().st_mode):
            return None, "symlink_not_permitted"
        if not stat.S_ISREG(info.st_mode) and not (allow_directory and stat.S_ISDIR(info.st_mode)):
            return None, "regular_file_required"
        return resolved, None

    def _iter_inventory(self) -> list[str]:
        found: set[str] = set()
        for configured in self._read_paths:
            if self._denied(configured):
                continue
            candidate = self.workspace / configured
            try:
                if candidate.is_symlink():
                    continue
                info = candidate.lstat()
            except OSError:
                continue
            if stat.S_ISREG(info.st_mode):
                if not self._is_controller_artifact(configured):
                    found.add(configured)
                continue
            if not stat.S_ISDIR(info.st_mode):
                continue
            pending = [candidate]
            while pending:
                directory = pending.pop()
                try:
                    entries = sorted(os.scandir(directory), key=lambda entry: entry.name)
                except OSError:
                    continue
                for entry in entries:
                    relative = entry.path[len(str(self.workspace)) + 1:]
                    relative = PurePosixPath(relative).as_posix()
                    if self._denied(relative) or self._is_controller_artifact(relative) or entry.is_symlink():
                        continue
                    try:
                        entry_info = entry.stat(follow_symlinks=False)
                    except OSError:
                        continue
                    if stat.S_ISREG(entry_info.st_mode):
                        found.add(relative)
                    elif stat.S_ISDIR(entry_info.st_mode):
                        pending.append(Path(entry.path))
        return sorted(found)

    def inventory(self) -> list[str]:
        """read 허용 목록에서 안정적이고 안전한 일반 파일 목록을 반환한다."""
        return self._iter_inventory()

    def source_hashes(self) -> dict[str, str]:
        """현재 보이는 파일을 내용 노출 없이 해시한다."""
        result: dict[str, str] = {}
        for relative in self.inventory():
            path, error = self._safe_existing(relative)
            if path is None or error:
                continue
            try:
                result[relative] = hashlib.sha256(path.read_bytes()).hexdigest()
            except OSError:
                continue
        return result

    def metrics(self) -> dict[str, Any]:
        result = dict(self._metrics)
        result["calls"] = self._calls
        result["elapsedSeconds"] = round(time.monotonic() - self._started, 6)
        result["wallExceeded"] = time.monotonic() >= self._started + self._wall_seconds
        result["maxCalls"] = self._max_calls
        result["wallSeconds"] = self._wall_seconds
        result["commandTimeout"] = self._command_timeout
        result["changedSourceHashes"] = list(self._metrics["changedSourceHashes"])
        for key in ("compileSeconds", "testSeconds", "captureSeconds", "querySeconds"):
            result[key] = round(result[key], 6)
        return result

    def _changed_hashes(self) -> tuple[dict[str, str], list[str]]:
        current = self.source_hashes()
        expected = self._snapshot_source_hashes
        if expected is None:
            changed = sorted(current)
        else:
            changed = sorted(set(current) | set(expected))
            changed = [path for path in changed if current.get(path) != expected.get(path)]
        self._metrics["changedSourceHashes"] = changed
        return current, changed

    def _scrub(self, text: str) -> str:
        value = text.replace(str(self.workspace), "<workspace>")
        return _ABSOLUTE_PATH.sub("<path>", value)

    def _diagnostic(self, text: str) -> tuple[str, bool]:
        scrubbed = self._scrub(text)
        truncated = len(scrubbed) > self._max_output_chars
        return scrubbed[:self._max_output_chars], truncated

    @staticmethod
    def _json_size(value: Any) -> int:
        return len(json.dumps(value, ensure_ascii=False))

    def _bounded_failure(self, action: str | None, *, return_code: int | None = None,
                         hint: str | None = None) -> dict[str, Any]:
        response: dict[str, Any] = {
            "ok": False, "action": action, "error": "output_limit_exceeded", "truncated": True,
        }
        if return_code is not None:
            response["returnCode"] = return_code
        if hint is None and action == "query":
            hint = "Use a smaller limit or a more focused symbol."
        if hint is not None:
            response["hint"] = hint
        if self._json_size(response) <= self._max_output_chars:
            return response
        minimal: dict[str, Any] = {"ok": False, "error": "output_limit_exceeded"}
        if return_code is not None:
            minimal["returnCode"] = return_code
        return minimal

    @staticmethod
    def _output_fields(value: Any) -> list[tuple[dict[str, Any], str]]:
        fields: list[tuple[dict[str, Any], str]] = []
        if isinstance(value, dict):
            for key, child in value.items():
                if key == "output" and isinstance(child, str):
                    fields.append((value, key))
                else:
                    fields.extend(RepairSession._output_fields(child))
        elif isinstance(value, list):
            for child in value:
                fields.extend(RepairSession._output_fields(child))
        return fields

    @staticmethod
    def _drop_empty_outputs(value: Any) -> None:
        if isinstance(value, dict):
            if value.get("output") == "":
                del value["output"]
            for child in value.values():
                RepairSession._drop_empty_outputs(child)
        elif isinstance(value, list):
            for child in value:
                RepairSession._drop_empty_outputs(child)

    @staticmethod
    def _first_return_code(value: Any) -> int | None:
        if isinstance(value, dict):
            code = value.get("returnCode")
            if isinstance(code, int) and not isinstance(code, bool):
                return code
            for child in value.values():
                code = RepairSession._first_return_code(child)
                if code is not None:
                    return code
        elif isinstance(value, list):
            for child in value:
                code = RepairSession._first_return_code(child)
                if code is not None:
                    return code
        return None

    def _bound_response(self, response: dict[str, Any]) -> dict[str, Any]:
        if self._json_size(response) <= self._max_output_chars:
            return response
        if response.get("action") in {"impact", "query"} and "result" in response:
            return self._bounded_failure(
                response.get("action"), return_code=self._first_return_code(response),
            )
        bounded = copy.deepcopy(response)
        self._drop_empty_outputs(bounded)
        while self._json_size(bounded) > self._max_output_chars:
            fields = self._output_fields(bounded)
            if not fields:
                break
            owner, key = max(fields, key=lambda field: len(field[0][field[1]]))
            original = owner[key]
            owner["truncated"] = True
            low, high = 0, len(original)
            best: str | None = None
            while low <= high:
                middle = (low + high) // 2
                owner[key] = original[:middle]
                if self._json_size(bounded) <= self._max_output_chars:
                    best = owner[key]
                    low = middle + 1
                else:
                    high = middle - 1
            owner[key] = best if best is not None else ""
            if best is None and len(original) == 0:
                break
        if self._json_size(bounded) <= self._max_output_chars:
            return bounded
        return_code = self._first_return_code(response)
        return self._bounded_failure(
            response.get("action") if isinstance(response.get("action"), str) else None,
            return_code=return_code,
        )

    def _error(self, action: str | None, error: str, **extra: Any) -> dict[str, Any]:
        response: dict[str, Any] = {"ok": False, "action": action, "error": error}
        response.update(extra)
        return response

    def _begin_call(self, action: str | None) -> dict[str, Any] | None:
        if self._calls >= self._max_calls:
            return self._error(action, "call_budget_exhausted", calls=self._calls, maxCalls=self._max_calls)
        if time.monotonic() >= self._started + self._wall_seconds:
            return self._error(action, "wall_timeout", calls=self._calls, maxCalls=self._max_calls)
        self._calls += 1
        self._metrics["calls"] = self._calls
        return None

    def execute(self, request: dict) -> dict[str, Any]:
        action = request.get("action") if isinstance(request, dict) and isinstance(request.get("action"), str) else None
        blocked = self._begin_call(action)
        if blocked is not None:
            return self._bound_response(blocked)
        if not isinstance(request, dict) or not isinstance(action, str):
            return self._bound_response(self._error(action, "invalid_request"))
        handlers = {
            "list": self._list, "read": self._read, "search": self._search,
            "replace": self._replace, "test": self._test, "refresh": self._refresh,
            "query": self._query, "impact": self._impact,
        }
        handler = handlers.get(action)
        if handler is None:
            return self._bound_response(self._error(action, "unknown_action"))
        if action in {"query", "impact"} and self.arm != "impact":
            return self._bound_response(self._error(action, f"{action}_unavailable_in_source_arm"))
        try:
            return self._bound_response(handler(request))
        except (OSError, ValueError, TypeError):
            return self._bound_response(self._error(action, "invalid_request"))

    @staticmethod
    def _integer(value: Any, default: int, *, minimum: int = 0) -> int | None:
        if value is None:
            return default
        if not isinstance(value, int) or isinstance(value, bool) or value < minimum:
            return None
        return value

    def _list(self, request: dict[str, Any]) -> dict[str, Any]:
        prefix = request.get("prefix", "")
        prefix = self._normalise_relative(prefix) if isinstance(prefix, str) else None
        if prefix is None:
            return self._error("list", "path_not_permitted")
        offset = self._integer(request.get("offset"), 0)
        limit = self._integer(request.get("limit"), 100, minimum=1)
        if offset is None or limit is None:
            return self._error("list", "invalid_pagination")
        paths = [path for path in self.inventory() if not prefix or path == prefix or path.startswith(prefix + "/")]
        page = paths[offset:offset + limit]
        page_truncated = offset + len(page) < len(paths)

        def response(items: list[str]) -> dict[str, Any]:
            return {"ok": True, "action": "list", "paths": items, "total": len(paths),
                    "returned": len(items), "offset": offset, "limit": limit,
                    "truncated": page_truncated or len(items) < len(page)}

        bounded_page: list[str] = []
        for path in page:
            candidate = response(bounded_page + [path])
            if self._json_size(candidate) > self._max_output_chars:
                if not bounded_page:
                    return self._bounded_failure("list")
                break
            bounded_page.append(path)
        result = response(bounded_page)
        truncated = result["truncated"]
        if truncated:
            self._metrics["truncations"] += 1
        return result

    def _read(self, request: dict[str, Any]) -> dict[str, Any]:
        relative = request.get("path")
        if not isinstance(relative, str) or not self._is_read_allowed(relative):
            return self._error("read", "path_not_allowed")
        normalised = self._normalise_relative(relative)
        path, error = self._safe_existing(relative)
        if normalised is None or path is None or error:
            return self._error("read", error or "path_not_permitted")
        try:
            content = path.read_text(encoding="utf-8")
        except (OSError, UnicodeError):
            return self._error("read", "file_not_readable")
        start = self._integer(request.get("start"), 1, minimum=1)
        column = self._integer(request.get("column"), 0)
        count = self._integer(request.get("count"), None, minimum=1)
        if start is None or column is None or (request.get("count") is not None and count is None):
            return self._error("read", "invalid_pagination")
        lines = content.splitlines(keepends=True)
        begin = min(start - 1, len(lines))
        end = min(begin + count, len(lines)) if count is not None else len(lines)
        if column > (len(lines[begin]) if begin < len(lines) else 0):
            return self._error("read", "invalid_pagination")
        selected = "".join(lines[begin:end])[column:]
        total_chars = len(selected)

        def cursor(length: int) -> tuple[int | None, int]:
            remaining = length
            line_number = begin
            line_column = column
            while line_number < end:
                available = max(0, len(lines[line_number]) - line_column)
                if remaining < available:
                    return line_number + 1, line_column + remaining
                remaining -= available
                line_number += 1
                line_column = 0
            return (line_number + 1 if line_number < len(lines) else None), 0

        def response(content: str) -> dict[str, Any]:
            next_start, next_column = cursor(len(content))
            return {"ok": True, "action": "read", "path": normalised, "content": content,
                    "start": start, "column": column, "lineCount": len(content.splitlines()),
                    "nextStart": next_start, "nextColumn": next_column,
                    "totalLines": len(lines), "contentChars": total_chars,
                    "truncated": len(content) < total_chars}

        low, high = 0, len(selected)
        best: str | None = None
        while low <= high:
            middle = (low + high) // 2
            candidate = response(selected[:middle])
            if self._json_size(candidate) <= self._max_output_chars:
                best = candidate["content"]
                low = middle + 1
            else:
                high = middle - 1
        if best is None:
            return self._bounded_failure("read")
        if not best and selected:
            return self._bounded_failure("read")
        result = response(best)
        truncated = result["truncated"]
        if truncated:
            self._metrics["truncations"] += 1
        self._metrics["sourceFilesExposed"] += 1
        self._metrics["sourceCharsExposed"] += len(best)
        return result

    def _search(self, request: dict[str, Any]) -> dict[str, Any]:
        query = request.get("query")
        if not isinstance(query, str) or not query:
            return self._error("search", "query_required")
        prefix = request.get("prefix", "")
        prefix = self._normalise_relative(prefix) if isinstance(prefix, str) else None
        if prefix is None:
            return self._error("search", "path_not_permitted")
        offset = self._integer(request.get("offset"), 0)
        limit = self._integer(request.get("limit"), 80, minimum=1)
        if offset is None or limit is None:
            return self._error("search", "invalid_pagination")
        matches: list[dict[str, Any]] = []
        for relative in self.inventory():
            if prefix and relative != prefix and not relative.startswith(prefix + "/"):
                continue
            path, error = self._safe_existing(relative)
            if path is None or error:
                continue
            try:
                contents = path.read_text(encoding="utf-8")
            except (OSError, UnicodeError):
                continue
            self._metrics["sourceFilesScanned"] += 1
            for line_number, line in enumerate(contents.splitlines(), 1):
                if query in line:
                    matches.append({"path": relative, "line": line_number, "text": line})
        page = matches[offset:offset + limit]
        page_truncated = offset + len(page) < len(matches)

        def response(items: list[dict[str, Any]]) -> dict[str, Any]:
            return {"ok": True, "action": "search", "matches": items, "total": len(matches),
                    "returned": len(items), "offset": offset, "limit": limit,
                    "truncated": page_truncated or len(items) < len(page) or any(
                        item["text"] != page[index]["text"] for index, item in enumerate(items)
                    )}

        bounded: list[dict[str, Any]] = []
        source_truncated = page_truncated
        for original in page:
            full_item = dict(original)
            candidate = response(bounded + [full_item])
            if self._json_size(candidate) <= self._max_output_chars:
                bounded.append(full_item)
                continue
            text = original["text"]
            low, high = 0, len(text)
            best: dict[str, Any] | None = None
            while low <= high:
                middle = (low + high) // 2
                item = dict(original)
                item["text"] = text[:middle]
                candidate = response(bounded + [item])
                if self._json_size(candidate) <= self._max_output_chars:
                    best = item
                    low = middle + 1
                else:
                    high = middle - 1
            if best is None:
                if not bounded:
                    return self._bounded_failure("search")
                source_truncated = True
                break
            bounded.append(best)
            source_truncated = True
            break
        truncated = page_truncated or len(bounded) < len(page) or source_truncated
        if truncated:
            self._metrics["truncations"] += 1
        used = sum(len(item["text"]) for item in bounded)
        self._metrics["sourceCharsExposed"] += used
        self._metrics["sourceFilesExposed"] += len({item["path"] for item in bounded})
        result = response(bounded)
        result["truncated"] = truncated
        return result

    def _replace(self, request: dict[str, Any]) -> dict[str, Any]:
        relative = request.get("path")
        old = request.get("old")
        new = request.get("new")
        if not isinstance(relative, str) or not isinstance(old, str) or not isinstance(new, str) or not old:
            return self._error("replace", "invalid_edit")
        normalised = self._normalise_relative(relative)
        if normalised is None or not self._is_edit_allowed(normalised) or self._is_test_path(normalised):
            return self._error("replace", "path_not_editable")
        path, error = self._safe_existing(normalised)
        if path is None or error:
            return self._error("replace", error or "path_not_permitted")
        try:
            before = path.read_text(encoding="utf-8")
        except (OSError, UnicodeError):
            return self._error("replace", "file_not_readable")
        if before.count(old) != 1:
            return self._error("replace", "old_text_must_occur_once", occurrences=before.count(old))
        after = before.replace(old, new, 1)
        try:
            path.write_text(after, encoding="utf-8")
        except OSError:
            return self._error("replace", "edit_failed")
        self._metrics["edits"] += 1
        return {"ok": True, "action": "replace", "path": normalised,
                "oldChars": len(old), "newChars": len(new),
                "sourceSha256": hashlib.sha256(after.encode()).hexdigest()}

    def _process_environment(self, extra: dict[str, str]) -> dict[str, str]:
        environment = os.environ.copy()
        environment.update(extra)
        return environment

    def _terminate(self, process: subprocess.Popen[bytes]) -> None:
        # leader가 먼저 종료해도 같은 소유 프로세스 그룹의 자식은 남을 수 있다.
        try:
            if os.name == "posix":
                os.killpg(process.pid, signal.SIGTERM)
            else:
                process.terminate()
        except ProcessLookupError:
            return
        try:
            process.wait(timeout=0.25)
        except subprocess.TimeoutExpired:
            # 유예 시간 뒤에는 아래에서 전체 소유 그룹을 종료한다.
            pass
        try:
            if os.name == "posix":
                os.killpg(process.pid, signal.SIGKILL)
            elif process.poll() is None:
                process.kill()
        except ProcessLookupError:
            # 그룹의 모든 프로세스가 이미 종료된 경우다.
            return
        process.wait(timeout=0.5)

    def _run_process(self, argv: tuple[str, ...], environment: dict[str, str], *, output_path: Path | None = None,
                     capture_limit: int | None = None, output_file_limit: int | None = None) -> dict[str, Any]:
        if not argv:
            return {"returncode": 0, "timedOut": False, "wallTimeout": False, "stdout": "", "stderr": "", "truncated": False, "seconds": 0.0, "error": None}
        limit = capture_limit or self._max_output_chars
        started = time.monotonic()
        stdout_handle = None
        try:
            if output_path is None:
                stdout_target: Any = subprocess.PIPE
            else:
                stdout_handle = output_path.open("wb")
                stdout_target = stdout_handle
            process = subprocess.Popen(argv, cwd=self.workspace, env=self._process_environment(environment),
                                       stdout=stdout_target, stderr=subprocess.PIPE, stdin=subprocess.DEVNULL,
                                       start_new_session=(os.name == "posix"))
        except (OSError, ValueError):
            if stdout_handle:
                stdout_handle.close()
            return {"returncode": None, "timedOut": False, "wallTimeout": False, "stdout": "", "stderr": "", "truncated": False, "seconds": time.monotonic() - started, "error": "process_error"}
        selector = selectors.DefaultSelector()
        if process.stderr is not None:
            selector.register(process.stderr, selectors.EVENT_READ, "stderr")
        if process.stdout is not None:
            selector.register(process.stdout, selectors.EVENT_READ, "stdout")
        parts = {"stdout": [], "stderr": []}
        captured = 0
        truncated = False
        timed_out = False
        wall_timeout = False
        command_deadline = started + self._command_timeout
        session_deadline = self._started + self._wall_seconds
        while selector.get_map() or process.poll() is None:
            now = time.monotonic()
            deadline = min(command_deadline, session_deadline)
            if output_path is not None and output_file_limit is not None:
                try:
                    if output_path.stat().st_size > output_file_limit:
                        truncated = True
                        self._terminate(process)
                        break
                except OSError:
                    pass
            if now >= deadline:
                timed_out = True
                wall_timeout = session_deadline <= command_deadline
                self._terminate(process)
                break
            for key, _ in selector.select(min(0.05, max(0.0, deadline - now))):
                try:
                    data = os.read(key.fileobj.fileno(), 4096)
                except OSError:
                    data = b""
                if not data:
                    try:
                        selector.unregister(key.fileobj)
                    except (KeyError, ValueError):
                        pass
                    continue
                text = data.decode("utf-8", errors="replace")
                before = captured
                if before < limit:
                    keep = text[:limit - before]
                    parts[key.data].append(keep)
                    captured += len(keep)
                if len(text) > max(0, limit - before):
                    truncated = True
        selector.close()
        try:
            returncode = process.wait(timeout=0.5)
        except subprocess.TimeoutExpired:
            self._terminate(process)
            returncode = process.poll()
        if stdout_handle:
            stdout_handle.close()
        for stream in (process.stdout, process.stderr):
            if stream is not None:
                try:
                    stream.close()
                except OSError:
                    pass
        seconds = time.monotonic() - started
        return {"returncode": returncode, "timedOut": timed_out, "wallTimeout": wall_timeout,
                "stdout": "".join(parts["stdout"]), "stderr": "".join(parts["stderr"]),
                "truncated": truncated, "seconds": seconds, "error": None}

    def _command_result(self, action: str, run: dict[str, Any]) -> dict[str, Any]:
        if run["error"]:
            return self._error(action, run["error"])
        output, diagnostic_truncated = self._diagnostic(run["stdout"] + run["stderr"])
        truncated = run["truncated"] or diagnostic_truncated
        if truncated:
            self._metrics["truncations"] += 1
        if run["wallTimeout"]:
            extra = {"seconds": round(run["seconds"], 6), "output": output, "truncated": truncated}
            if run["returncode"] is not None:
                extra["returnCode"] = run["returncode"]
            return self._error(action, "wall_timeout", **extra)
        if run["timedOut"]:
            extra = {"seconds": round(run["seconds"], 6), "output": output, "truncated": truncated}
            if run["returncode"] is not None:
                extra["returnCode"] = run["returncode"]
            return self._error(action, "timeout", **extra)
        if run["returncode"] != 0:
            return self._error(action, "command_failed", returnCode=run["returncode"], seconds=round(run["seconds"], 6), output=output, truncated=truncated)
        return {"ok": True, "action": action, "returnCode": 0, "seconds": round(run["seconds"], 6),
                "output": output, "truncated": truncated}

    def _test(self, request: dict[str, Any]) -> dict[str, Any]:
        del request
        if not self._test_command:
            return self._error("test", "test_not_configured")
        started = time.monotonic()
        run = self._run_process(self._test_command, self._build_env)
        self._metrics["tests"] += 1
        self._metrics["testSeconds"] += time.monotonic() - started
        return self._command_result("test", run)

    def _invalidate_snapshot(self) -> None:
        self._snapshot_ready = False
        self._snapshot_source_hashes = None
        self._metrics["changedSourceHashes"] = []
        if self._snapshot is None:
            return
        try:
            if self._snapshot.is_symlink():
                return
            if self._snapshot.exists():
                self._snapshot.unlink()
        except OSError:
            pass

    def _refresh(self, request: dict[str, Any]) -> dict[str, Any]:
        del request
        self._invalidate_snapshot()
        if not self._compile_command:
            return self._error("refresh", "compile_not_configured")
        before = self.source_hashes()
        compile_started = time.monotonic()
        compile_run = self._run_process(self._compile_command, self._build_env)
        self._metrics["compiles"] += 1
        self._metrics["compileSeconds"] += time.monotonic() - compile_started
        compile_result = self._command_result("compile", compile_run)
        if not compile_result["ok"]:
            return self._error("refresh", "compile_failed", compile=compile_result)
        after_compile = self.source_hashes()
        if after_compile != before:
            self._metrics["changedSourceHashes"] = sorted(set(before) | set(after_compile))
            self._metrics["changedSourceHashes"] = [path for path in self._metrics["changedSourceHashes"] if before.get(path) != after_compile.get(path)]
            return self._error("refresh", "source_changed_during_refresh", sourceHashCount=len(after_compile))
        if self.arm == "source" and (self._snapshot is None or self._binary is None):
            self._snapshot_source_hashes = after_compile
            return {"ok": True, "action": "refresh", "compile": compile_result,
                    "capture": None, "sourceHashCount": len(after_compile), "snapshotFreshness": "compile_only"}
        if self._snapshot is None or self._binary is None:
            return self._error("refresh", "capture_not_configured")
        try:
            if self._snapshot.is_symlink():
                return self._error("refresh", "snapshot_symlink_not_permitted")
            self._snapshot.parent.mkdir(parents=True, exist_ok=True)
            descriptor, temporary_name = tempfile.mkstemp(prefix=".repair-snapshot-", suffix=".partial", dir=self._snapshot.parent)
            os.close(descriptor)
            temporary = Path(temporary_name)
        except OSError:
            return self._error("refresh", "snapshot_not_writable")
        capture_started = time.monotonic()
        capture_run = self._run_process(tuple([str(self._binary), "snapshot", *self._snapshot_arguments]), self._analysis_env,
                                        output_path=temporary, capture_limit=self._max_output_chars,
                                        output_file_limit=64 * 1024 * 1024)
        self._metrics["captures"] += 1
        self._metrics["captureSeconds"] += time.monotonic() - capture_started
        if capture_run["truncated"]:
            self._metrics["truncations"] += 1
        if capture_run["error"] or capture_run["timedOut"] or capture_run["returncode"] != 0 or capture_run["truncated"]:
            try:
                temporary.unlink()
            except OSError:
                pass
            output, diagnostic_truncated = self._diagnostic(capture_run["stderr"])
            return self._error("refresh", "capture_failed", returnCode=capture_run["returncode"],
                               timedOut=capture_run["timedOut"], seconds=round(capture_run["seconds"], 6),
                               output=output, truncated=capture_run["truncated"] or diagnostic_truncated)
        try:
            with temporary.open("r", encoding="utf-8") as handle:
                json.load(handle)
            after_capture = self.source_hashes()
            if after_capture != before:
                raise RuntimeError("source_changed_during_refresh")
            if self._snapshot.is_symlink():
                raise RuntimeError("snapshot_symlink_not_permitted")
            os.replace(temporary, self._snapshot)
        except RuntimeError as error:
            try:
                temporary.unlink()
            except OSError:
                pass
            return self._error("refresh", str(error), sourceHashCount=len(self.source_hashes()))
        except (OSError, UnicodeError, ValueError, json.JSONDecodeError):
            try:
                temporary.unlink()
            except OSError:
                pass
            return self._error("refresh", "capture_invalid")
        self._snapshot_source_hashes = after_capture
        self._snapshot_ready = True
        return {"ok": True, "action": "refresh", "compile": compile_result,
                "capture": {"ok": True, "returnCode": 0, "seconds": round(capture_run["seconds"], 6)},
                "sourceHashCount": len(after_capture), "snapshotFreshness": "tracked"}

    def _request_values(self, request: dict[str, Any], key: str) -> list[str] | None:
        value = request.get(key)
        if value is None:
            return []
        values = value if isinstance(value, list) else [value]
        if any(not isinstance(item, str) or not item or any(ord(character) < 32 for character in item) for item in values):
            return None
        return values

    def _fresh_snapshot_error(self, action: str) -> dict[str, Any] | None:
        """저장 snapshot과 현재 노출 소스가 같은지 공통으로 확인한다."""

        if self._snapshot is None or self._binary is None or not self._snapshot_ready or not self._snapshot.is_file():
            return self._error(action, "snapshot_unavailable")
        current, changed = self._changed_hashes()
        if self._snapshot_source_hashes is None:
            return self._error(action, "snapshot_unverified", sourceHashCount=len(current))
        if changed:
            return self._error(action, "stale_source", changedSourceHashes=changed, sourceHashCount=len(current))
        return None

    def _query(self, request: dict[str, Any]) -> dict[str, Any]:
        stale = self._fresh_snapshot_error("query")
        if stale is not None:
            return stale
        if any(key not in {"action", "symbol", "depth", "limit"} for key in request):
            return self._error("query", "invalid_query_argument")
        symbol = request.get("symbol")
        if not isinstance(symbol, str) or not symbol or symbol.startswith("-") or any(ord(character) < 32 for character in symbol):
            return self._error("query", "symbol_required")
        argv: list[str] = [str(self._binary), "query", symbol, "--graph-file", str(self._snapshot)]
        for key, option in (("depth", "--depth"), ("limit", "--limit")):
            if key not in request:
                if key == "limit":
                    argv.extend((option, "5"))
                continue
            value = self._integer(request.get(key), None, minimum=1)
            if value is None:
                return self._error("query", "invalid_query_argument")
            argv.extend((option, str(value)))
        started = time.monotonic()
        run = self._run_process(tuple(argv), self._analysis_env,
                                capture_limit=max(self._max_output_chars * 128, 1_048_576))
        self._metrics["queries"] += 1
        self._metrics["symbolQueries"] += 1
        self._metrics["querySeconds"] += time.monotonic() - started
        if run["error"] or run["timedOut"] or run["returncode"] not in {0, 64}:
            return self._command_result("query", run)
        if run["truncated"]:
            return self._error("query", "native_output_limit", returnCode=run["returncode"], truncated=True,
                               hint="Use a smaller result limit or a more focused symbol.")
        try:
            payload = json.loads(run["stdout"])
        except (TypeError, ValueError):
            output, diagnostic_truncated = self._diagnostic(run["stdout"] + run["stderr"])
            if diagnostic_truncated:
                self._metrics["truncations"] += 1
            return self._error("query", "invalid_cli_json", returnCode=run["returncode"], output=output,
                               truncated=diagnostic_truncated)
        if not isinstance(payload, dict):
            return self._error("query", "invalid_query_document", returnCode=run["returncode"])
        response = {"ok": run["returncode"] == 0, "action": "query",
                    "returnCode": run["returncode"], "result": payload,
                    "seconds": round(run["seconds"], 6), "truncated": False}
        if self._json_size(response) > self._max_output_chars:
            self._metrics["truncations"] += 1
            return self._bounded_failure("query", return_code=run["returncode"],
                                         hint="Use a smaller limit or a more focused symbol.")
        return response

    def _project_impact(self, payload: Any, *, response_builder: Any | None = None) -> tuple[Any, bool]:
        # 경로 안의 간선을 잘라 거짓 witness를 만들지 않고, 배열의 완전한 항목만 줄인다.
        def size(value: Any) -> int:
            return self._json_size(value)

        def fits(value: Any) -> bool:
            return (response_builder(value) if response_builder is not None else size(value)) <= self._max_output_chars

        if fits(payload):
            return payload, False
        if not isinstance(payload, dict):
            marker = {"projection": {"truncated": True, "omittedSections": ["native"]}}
            return marker, True
        projected = copy.deepcopy(payload)
        projection: dict[str, Any] = {"truncated": True, "omittedSections": []}
        if self._max_output_chars >= 1024:
            projection["nextOffset"] = payload.get("navigation", {}).get("offset", 0) + len(payload.get("affected", []))
        projected["projection"] = projection
        original_counts: dict[str, int] = {}
        lists: list[tuple[str, dict[str, Any], str]] = []
        summary = projected.get("summary", {})
        if isinstance(summary, dict):
            for revision, counts in summary.items():
                if isinstance(counts, dict):
                    for key, value in counts.items():
                        if isinstance(value, list):
                            lists.append(("summary." + revision + "." + key, counts, key))
        for key in ("affected", "changed"):
            if isinstance(projected.get(key), list):
                lists.append((key, projected, key))
        for name, container, key in lists:
            original_counts[name] = len(container[key])

        def omit(name: str) -> None:
            if name not in projection["omittedSections"]:
                projection["omittedSections"].append(name)
                projection["omittedSections"].sort()

        # 입력 상세는 원래 CLI 한계와 분리해 표시한다. 관찰 수·native 결과 잘림·불확실성은 우선 보존한다.
        for key in ("inputs",):
            if key in projected and not fits(projected):
                del projected[key]
                omit(key)
        while not fits(projected):
            available = [(name, container, key) for name, container, key in lists if container[key]]
            if not available:
                break

            def priority(item: tuple[str, dict[str, Any], str]) -> int:
                name, container, key = item
                if name.startswith("summary."):
                    return 0
                if name == "changed":
                    return 1 if size(container[key]) > self._max_output_chars // 10 else 3
                return 2 if len(container[key]) > 1 else 4

            tier = min(priority(item) for item in available)
            name, container, key = max((item for item in available if priority(item) == tier),
                                       key=lambda item: size(item[1][item[2]]))
            values = container[key]
            container[key] = values[:len(values) // 2]
            omit(name)
            if name == "affected" and "nextOffset" in projection:
                projection["nextOffset"] = payload.get("navigation", {}).get("offset", 0) + len(container[key])
        # 작은 인위적 한도에서는 스키마 장식보다 실제 한계 정보를 우선한다.
        for key in ("format", "version", "navigation", "budgets"):
            if key in projected and not fits(projected):
                del projected[key]
                omit(key)
        if not fits(projected):
            essential = {key: copy.deepcopy(payload[key]) for key in
                         ("observedAffected", "unresolved", "limitations", "truncated") if key in payload}
            essential["projection"] = {"truncated": True}
            if fits(essential):
                return essential, True
            essential["projection"] = {
                "truncated": True, "error": "essential_evidence_exceeds_output_budget",
                "unresolvedCount": len(payload.get("unresolved", [])),
                "limitationCount": len(payload.get("limitations", [])),
            }
            if fits(essential):
                return essential, True
            return {"projection": {"truncated": True, "error": "essential_evidence_exceeds_output_budget"}}, True
        details = {name: {"total": original_counts[name], "returned": len(container[key])}
                   for name, container, key in lists if name in projection["omittedSections"]}
        candidate = copy.deepcopy(projected)
        candidate["projection"]["sectionCounts"] = details
        if "affected" in details:
            offset = payload.get("navigation", {}).get("offset", 0)
            candidate["projection"]["nextOffset"] = offset + len(projected.get("affected", []))
        if fits(candidate):
            projected = candidate
        return projected, True

    def _impact(self, request: dict[str, Any]) -> dict[str, Any]:
        stale = self._fresh_snapshot_error("impact")
        if stale is not None:
            return stale
        argv: list[str] = [str(self._binary), "impact"]
        for key in ("symbol", "file", "module", "affected_file", "kind"):
            aliases = {"symbol": ("symbol", "symbols"), "file": ("file", "files"),
                       "module": ("module", "modules"), "affected_file": ("affected_file", "affectedFiles"),
                       "kind": ("kind", "kinds")}[key]
            values = []
            for alias in aliases:
                alias_values = self._request_values(request, alias)
                if alias_values is None:
                    return self._error("impact", "invalid_impact_argument")
                values.extend(alias_values)
            if values is None:
                return self._error("impact", "invalid_impact_argument")
            if key in {"file", "affected_file"}:
                for value in values:
                    normalised = self._normalise_relative(value)
                    if normalised is None or not normalised or self._denied(normalised):
                        return self._error("impact", "invalid_impact_path")
            for value in values:
                argv.extend((_IMPACT_LIST_OPTIONS[key], value))
        scalar_aliases = {"test_status": ("test_status", "testStatus"), "path_status": ("path_status", "pathStatus"),
                          "relation": ("relation",), "sort": ("sort",)}
        for key, (option, allowed) in _IMPACT_SCALAR_OPTIONS.items():
            values = [request[alias] for alias in scalar_aliases[key] if alias in request]
            if len(values) > 1:
                return self._error("impact", "invalid_impact_argument")
            value = values[0] if values else None
            if value is not None and (not isinstance(value, str) or value not in allowed):
                return self._error("impact", "invalid_impact_argument")
            if value is not None:
                argv.extend((option, value))
        numeric_aliases = {"visit_limit": ("visit_limit", "visitLimit"), "path_limit": ("path_limit", "pathLimit"),
                           "depth": ("depth",), "limit": ("limit",), "offset": ("offset",)}
        for key, option in (("depth", "--depth"), ("limit", "--limit"), ("offset", "--offset"),
                            ("visit_limit", "--visit-limit"), ("path_limit", "--path-limit")):
            values = [request[alias] for alias in numeric_aliases[key] if alias in request]
            if len(values) > 1:
                return self._error("impact", "invalid_impact_argument")
            raw_value = values[0] if values else None
            value = self._integer(raw_value, None, minimum=0)
            if raw_value is not None and value is None:
                return self._error("impact", "invalid_impact_argument")
            if value is not None:
                argv.extend((option, str(value)))
        if "all" in request and not isinstance(request["all"], bool):
            return self._error("impact", "invalid_impact_argument")
        if request.get("all", False):
            if "limit" in request:
                return self._error("impact", "invalid_impact_argument")
            argv.append("--all")
        elif "limit" not in request:
            argv.extend(("--limit", "10"))
        argv.extend(("--graph-file", str(self._snapshot)))
        started = time.monotonic()
        run = self._run_process(tuple(argv), self._analysis_env, capture_limit=max(self._max_output_chars * 128, 1_048_576))
        self._metrics["queries"] += 1
        self._metrics["impactQueries"] += 1
        self._metrics["querySeconds"] += time.monotonic() - started
        if run["error"] or run["timedOut"] or run["returncode"] not in {0, 64}:
            return self._command_result("impact", run)
        if run["truncated"]:
            return self._error("impact", "native_output_limit", returnCode=run["returncode"], truncated=True,
                               hint="Use a smaller result limit or a more focused selector.")
        try:
            payload = json.loads(run["stdout"])
        except (TypeError, ValueError):
            output, diagnostic_truncated = self._diagnostic(run["stdout"] + run["stderr"])
            if diagnostic_truncated:
                self._metrics["truncations"] += 1
            return self._error("impact", "invalid_cli_json", returnCode=run["returncode"], output=output,
                               truncated=run["truncated"] or diagnostic_truncated)
        def make_response(result: Any, truncated: bool, *, compact: bool = False) -> dict[str, Any]:
            response = {"ok": run["returncode"] == 0, "action": "impact",
                        "returnCode": run["returncode"], "result": result}
            if not compact:
                response["seconds"] = round(run["seconds"], 6)
                response["truncated"] = truncated or run["truncated"]
            return response

        def full_response_size(result: Any) -> int:
        # 투영 결과가 바깥 응답 봉투까지 포함한 한도를 지키도록 보수적으로 계산한다.
            return self._json_size(make_response(result, False))

        def projection_loses_essential(result: Any) -> bool:
            projection = result.get("projection") if isinstance(result, dict) else None
            if not isinstance(projection, dict) or projection.get("error") != "essential_evidence_exceeds_output_budget":
                return False
            required = ("observedAffected", "unresolved", "limitations", "truncated")
            return not isinstance(payload, dict) or any(key in payload and key not in result for key in required)

        projected, truncated = self._project_impact(payload, response_builder=full_response_size)
        response = make_response(projected, truncated)
        if self._json_size(response) > self._max_output_chars or projection_loses_essential(projected):
            def compact_response_size(result: Any) -> int:
                return self._json_size(make_response(result, False, compact=True))

            projected, truncated = self._project_impact(payload, response_builder=compact_response_size)
            if projection_loses_essential(projected):
                return self._bounded_failure("impact", return_code=run["returncode"])
            response = make_response(projected, truncated, compact=True)
        if truncated:
            self._metrics["truncations"] += 1
        if self._json_size(response) > self._max_output_chars:
            return self._bounded_failure("impact", return_code=run["returncode"])
        return response
