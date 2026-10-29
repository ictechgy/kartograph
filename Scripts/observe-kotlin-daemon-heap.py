#!/usr/bin/env python3
"""Read bounded Kotlin daemon heap observations without changing a build verdict."""

import json
import os
from pathlib import Path
import re
import subprocess
import threading

DAEMON = "org.jetbrains.kotlin.daemon.KotlinCompileDaemon"
MAX_PROCESSES = 4
MAX_OUTPUT = 64 * 1024
TIMEOUT = 5


def read_command(command, timeout=TIMEOUT):
    """진단 출력만 제한해서 읽고 원시 JVM 인자나 오류 내용은 공개하지 않는다."""
    try:
        process = subprocess.Popen(command, stdout=subprocess.PIPE, stderr=subprocess.DEVNULL)
    except OSError:
        return None, "tool-unavailable"
    output = bytearray()
    exceeded = threading.Event()
    read_failed = threading.Event()

    def read_output():
        try:
            while chunk := process.stdout.read(min(4096, MAX_OUTPUT + 1 - len(output))):
                output.extend(chunk)
                if len(output) > MAX_OUTPUT:
                    exceeded.set()
                    process.kill()
                    return
        except OSError:
            read_failed.set()

    reader = threading.Thread(target=read_output, daemon=True)
    reader.start()
    reason = None
    try:
        process.wait(timeout=timeout)
    except subprocess.TimeoutExpired:
        reason = "observation-timeout"
        try:
            process.kill()
            process.wait(timeout=1)
        except (OSError, subprocess.TimeoutExpired):
            return None, "observation-process-unavailable"
    reader.join(timeout=1)
    if reader.is_alive():
        return None, "output-not-closed"
    process.stdout.close()
    if reason:
        return None, reason
    if exceeded.is_set():
        return None, "output-limit"
    if read_failed.is_set():
        return None, "output-unavailable"
    if process.returncode:
        return None, "process-exited-or-access-unavailable"
    return output.decode("utf-8", errors="replace"), None


def observe(java_home, read=read_command):
    """jps의 정확한 daemon 신원에서 발견한 PID만 읽는다. 종료한 JVM은 미관측이다."""
    result = {
        "schemaVersion": 1,
        "requestedMaxHeapBytes": 1024 ** 3,
        "identity": DAEMON,
        "status": "unobserved",
        "processes": [],
        "processesOmitted": 0,
        "limitations": [
            "Post-build process observation; exited daemons and earlier heap occupancy are not assessed.",
            "Observed daemons may belong to TestKit or fixture builds and are not bound to a particular compilation task or phase; a row does not prove the requested cap applied to that task.",
        ],
    }
    if not java_home:
        return {**result, "reason": "java-home-unavailable"}
    binary = Path(java_home) / "bin"
    listing, reason = read([str(binary / "jps"), "-l"])
    if reason:
        return {**result, "reason": reason}
    pids = sorted({line.split()[0] for line in listing.splitlines()
                   if re.fullmatch(r"[1-9][0-9]* " + re.escape(DAEMON), line)}, key=int)
    result["processesOmitted"] = max(0, len(pids) - MAX_PROCESSES)
    for pid in pids[:MAX_PROCESSES]:
        flags, reason = read([str(binary / "jcmd"), pid, "VM.flags"])
        maximum = re.findall(r"(?<!\S)-XX:MaxHeapSize=(\d+)(?!\S)", flags or "")
        row = {"pid": int(pid), "status": "unobserved"}
        if reason:
            row["reason"] = reason
        elif len(maximum) == 1:
            row.update(status="observed", maxHeapBytes=int(maximum[0]))
        else:
            row["reason"] = "max-heap-flag-unavailable"
        result["processes"].append(row)
    count = sum(row["status"] == "observed" for row in result["processes"])
    if count:
        result["status"] = "observed" if count == len(pids) else "partial"
    elif not pids:
        result["reason"] = "no-live-kotlin-daemon"
    return result


if __name__ == "__main__":
    print(json.dumps(observe(os.environ.get("JAVA_HOME")), sort_keys=True))
