"""실패한 공개 리소스 픽스처 child process의 제한된 진단만 출력한다."""
import hashlib
import json
import os
import re
import subprocess

MAX_LINES = 24
MAX_LINE = 400
MAX_BYTES = 8192
SECRET_NAME = re.compile(r"(?i)(token|password|secret|credential|api[_-]?key)")
PATH = re.compile(r"(?:[A-Za-z]:[\\/]|/)(?:[^\s\"'<>;,)]*)")


def _bytes(value):
    if value is None:
        return b""
    return value if isinstance(value, bytes) else value.encode("utf-8", errors="replace")


def _safe(line):
    if SECRET_NAME.search(line):
        return "<sensitive diagnostic line omitted>"
    for name, value in os.environ.items():
        if SECRET_NAME.search(name) and len(value) >= 4:
            line = line.replace(value, "<redacted>")
    line = PATH.sub("<path>", line)
    return "".join(character if character.isprintable() else " " for character in line)[:MAX_LINE]


def summary(phase, returncode, stdout, stderr, timed_out=False):
    if not re.fullmatch(r"[A-Za-z0-9_.-]{1,100}", phase):
        raise ValueError("invalid diagnostic phase")
    raw = _bytes(stdout) + b"\n" + _bytes(stderr)
    text = raw.decode("utf-8", errors="replace")
    structured = {}
    try:
        document = json.loads(_bytes(stdout).decode("utf-8"))
        if isinstance(document, dict):
            if isinstance(document.get("status"), str) and document["status"] in {"matched", "stale", "unavailable", "unknown", "notFound", "found"}:
                structured["status"] = document["status"]
            reasons = document.get("reasons")
            if isinstance(reasons, list) and len(reasons) <= 32 and all(
                    isinstance(reason, str) and re.fullmatch(r"[a-z][a-z0-9-]{0,99}", reason) for reason in reasons):
                structured["reasons"] = reasons
    except (ValueError, UnicodeDecodeError):
        pass
    selected = []
    in_failure = False
    for line in text.splitlines():
        stripped = line.strip()
        if stripped == "* What went wrong:":
            in_failure = True
        elif stripped.startswith("* Try:") or stripped.startswith("* Get more help"):
            in_failure = False
        if in_failure or stripped.startswith(("FAILURE:", "Execution failed for task ", "error:", "Error:", "Caused by:")):
            selected.append(_safe(stripped))
            selected = selected[-MAX_LINES:]
    selected = selected[-MAX_LINES:]
    while len(json.dumps(selected, ensure_ascii=True).encode("utf-8")) > MAX_BYTES:
        selected.pop(0)
    return {
        "format": "kartograph-resource-process-failure", "phase": phase,
        "returncode": returncode, "timedOut": timed_out,
        "fullLogSha256": hashlib.sha256(raw).hexdigest(),
        "hashBasis": "raw-stdout-plus-LF-plus-raw-stderr",
        "diagnosticLines": selected, "structuredStatus": structured,
        "unsupportedLogShape": not selected and not structured,
    }


def emit_failure(phase, returncode, stdout, stderr, expected=0, timed_out=False):
    if returncode == expected and not timed_out:
        return
    print(json.dumps(summary(phase, returncode, stdout, stderr, timed_out), sort_keys=True), flush=True)


def run(arguments, cwd, timeout, phase, expected=0):
    try:
        result = subprocess.run(list(map(str, arguments)), cwd=cwd, capture_output=True, timeout=timeout)
    except subprocess.TimeoutExpired as error:
        emit_failure(phase, None, error.stdout, error.stderr, timed_out=True)
        raise
    emit_failure(phase, result.returncode, result.stdout, result.stderr, expected)
    return subprocess.CompletedProcess(result.args, result.returncode,
        result.stdout.decode("utf-8", errors="replace"), result.stderr.decode("utf-8", errors="replace"))


if __name__ == "__main__":
    import sys
    from pathlib import Path
    emit_failure(sys.argv[1], int(sys.argv[2]), Path(sys.argv[3]).read_bytes(), b"")
