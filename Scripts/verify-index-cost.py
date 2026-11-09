#!/usr/bin/env python3
"""고정 baseline과 이미 빌드한 후보를 같은 입력으로 관측한다. smoke 판정은 바꾸지 않는다."""

import gzip
from collections import Counter
from contextlib import contextmanager
import ctypes
import errno
import hashlib
from itertools import zip_longest
import json
import math
import os
from pathlib import Path
import re
import shutil
import signal
import stat
import statistics
import subprocess
import sys
import tarfile
import tempfile
import threading
import time
import xml.etree.ElementTree as ET

# #207(78153c3)의 의도된 dead·metrics 출력 변경 뒤의 분석기를 고정 기준으로 쓴다. 이전 33f8ce1 기준의 정확한
# 출력 delta는 이 이동으로 필요 없어져 제거했다. 이전 bb13/5a/33f8ce1 실험의 기록은 별도 보존한다.
SOURCE_BASELINE = "78153c338e20db7193be4a93ac5de90fc594e026"
BASELINE = "78153c338e20db7193be4a93ac5de90fc594e026"
MODULES = ("core", "index", "analysis", "export", "cli", "gradle-plugin")
PROFILES = ("graph", "dead", "architecture")
LIMIT = 32 * 1024 * 1024
RAW_LIMIT = 256 * 1024 * 1024
JFR_LIMIT = 16 * 1024 * 1024
JFR_PRINT_STACK_DEPTH = 5
JFR_EVENTS = ("jdk.ExecutionSample", "jdk.ObjectAllocationSample")


class DiagnosticError(Exception):
    """원문 경로나 child 로그를 노출하지 않는 실패 코드다."""

    def __init__(self, reason, receipt=None):
        super().__init__(reason)
        self.receipt = receipt


def digest(data):
    return hashlib.sha256(data).hexdigest()


def file_record(path, relative):
    before = path.stat()
    if not stat.S_ISREG(before.st_mode) or before.st_size > 1024 * 1024 * 1024:
        raise DiagnosticError("invalid-inventory-file")
    checksum = hashlib.sha256()
    with path.open("rb") as source:
        for chunk in iter(lambda: source.read(1024 * 1024), b""):
            checksum.update(chunk)
    after = path.stat()
    if (before.st_size, before.st_mtime_ns) != (after.st_size, after.st_mtime_ns):
        raise DiagnosticError("inventory-changed-during-read")
    return {"path": relative, "size": after.st_size, "mtimeNs": after.st_mtime_ns,
            "sha256": checksum.hexdigest()}


def inventory(directory):
    if not directory.is_dir() or directory.is_symlink():
        raise DiagnosticError("missing-inventory-directory")
    rows = []
    for path in sorted(directory.rglob("*")):
        relative = path.relative_to(directory).as_posix()
        if len(relative) > 1024 or not re.fullmatch(r"[A-Za-z0-9_.$/()+@, =-]+", relative):
            raise DiagnosticError("invalid-inventory-name")
        if path.is_symlink():
            raise DiagnosticError("symlink-in-inventory")
        if path.is_dir():
            continue
        rows.append(file_record(path, relative))
        if len(rows) > 100000:
            raise DiagnosticError("inventory-too-large")
    if not rows:
        raise DiagnosticError("empty-inventory")
    return rows


def root_inventory(root):
    result = {}
    for module in MODULES:
        rows = inventory(root / module / "build/classes/kotlin/main")
        if not any(row["path"].endswith(".class") for row in rows):
            raise DiagnosticError("missing-classes")
        result[module] = rows
    return result


def require_equal(before, after, reason):
    if before != after:
        raise DiagnosticError(reason)


def verification_document(path):
    """고정 진단의 UTF-8 명시적 SHA256 metadata만 제한된 크기로 읽는다."""
    if path.is_symlink():
        raise DiagnosticError("linked-verification-metadata")
    with path.open("rb") as stream:
        raw = stream.read(2 * 1024 * 1024 + 1)
    if len(raw) > 2 * 1024 * 1024:
        raise DiagnosticError("invalid-verification-metadata")
    try:
        text = raw.decode("utf-8-sig")
        if "<!DOCTYPE" in text.upper() or "<!ENTITY" in text.upper():
            raise DiagnosticError("invalid-verification-metadata")
        root = ET.fromstring(text)
    except (ET.ParseError, UnicodeDecodeError) as error:
        raise DiagnosticError("invalid-verification-metadata") from error
    pending = [(root, 0)]
    visited = 0
    while pending:
        element, depth = pending.pop()
        visited += 1
        if depth > 16 or visited > 20_000:
            raise DiagnosticError("verification-metadata-structure-limit")
        pending.extend((child, depth + 1) for child in element)
    namespace = "{https://schema.gradle.org/dependency-verification}"
    def signature(element):
        return (element.tag, tuple(sorted(element.attrib.items())), (element.text or "").strip(),
                tuple(signature(child) for child in element))
    if root.tag != namespace + "verification-metadata" or [child.tag for child in root] != [namespace + "configuration", namespace + "components"]:
        raise DiagnosticError("unsupported-verification-metadata-policy")
    configuration, components = root
    if components.attrib:
        raise DiagnosticError("unsupported-verification-metadata-policy")
    artifacts = {}
    component_keys = set()
    for component in components:
        if component.tag != namespace + "component" or set(component.attrib) != {"group", "name", "version"} or any(
            not re.fullmatch(r"[A-Za-z0-9_.-]+", value) for value in component.attrib.values()
        ):
            raise DiagnosticError("unsupported-verification-metadata-component")
        coordinate = tuple(component.attrib[key] for key in ("group", "name", "version"))
        if coordinate in component_keys:
            raise DiagnosticError("duplicate-verification-component")
        component_keys.add(coordinate)
        for artifact in component:
            if artifact.tag != namespace + "artifact" or set(artifact.attrib) != {"name"} or not re.fullmatch(r"[A-Za-z0-9_.-]+", artifact.attrib["name"]):
                raise DiagnosticError("unsupported-verification-artifact")
            key = (*coordinate, artifact.attrib["name"])
            if key in artifacts or len(artifact) != 1:
                raise DiagnosticError("duplicate-or-alternative-verification-pin")
            checksum = artifact[0]
            if checksum.tag != namespace + "sha256" or set(checksum.attrib) - {"value", "origin"} or not re.fullmatch(r"[0-9a-f]{64}", checksum.get("value", "")) or len(checksum) or (checksum.text or "").strip():
                raise DiagnosticError("unsupported-verification-checksum")
            artifacts[key] = signature(artifact)
    if not artifacts:
        raise DiagnosticError("empty-verification-metadata")
    return raw, (tuple(sorted(root.attrib.items())), signature(configuration)), artifacts


def align_verification_metadata(repo, baseline, report):
    """원본 소스는 유지하고 추가 artifact 핀만 임시 baseline에 동일하게 적용한다."""
    relative = Path("gradle/verification-metadata.xml")
    original, old_policy, old = verification_document(baseline / relative)
    shared, new_policy, new = verification_document(repo / relative)
    require_equal(old_policy, new_policy, "verification-metadata-policy-changed")
    if any(key not in new or new[key] != value for key, value in old.items()):
        raise DiagnosticError("verification-metadata-existing-pin-changed")
    value = {"scope": "additive-exact-SHA256-artifact-pins-only; wrapper transition and source identities recorded separately",
             "baselineOriginalSha256": digest(original), "sharedSha256": digest(shared),
             "baselineArtifacts": len(old), "sharedArtifacts": len(new), "addedArtifacts": len(new) - len(old)}
    if original != shared:
        (baseline / relative).write_bytes(shared)
    report["verificationMetadataAlignment"] = value


def verify_aligned_metadata(repo, baseline, value):
    for root in (repo, baseline):
        raw, _, _ = verification_document(root / "gradle/verification-metadata.xml")
        require_equal(value["sharedSha256"], digest(raw), "verification-metadata-changed")


@contextmanager
def input_integrity_guard(report, key, checks):
    """후속 불변식 오류가 원래 child 실패와 receipt를 대체하지 않게 한다."""
    primary = None
    try:
        yield
    except BaseException as error:
        primary = error
        raise
    finally:
        result = {"unchanged": True, "errors": []}
        if primary is not None:
            result["primaryFailure"] = {"reason": str(primary) if isinstance(primary, DiagnosticError) else "interrupted-or-unexpected-child-error"}
            if isinstance(primary, DiagnosticError) and primary.receipt is not None:
                result["primaryFailure"]["receipt"] = primary.receipt
        for check in checks:
            try:
                check()
            except (DiagnosticError, OSError) as error:
                result["errors"].append({"reason": str(error) if isinstance(error, DiagnosticError) else "build-input-read-failed"})
        result["unchanged"] = not result["errors"]
        report[key] = result
        if result["errors"] and primary is None:
            raise DiagnosticError("aligned-build-inputs-changed")



WRAPPER_FILES = ("gradlew", "gradle/wrapper/gradle-wrapper.jar", "gradle/wrapper/gradle-wrapper.properties")


REVIEWED_WRAPPERS = {
    "9.7.1": ((8656, "a5a5c199ba02189ae8c46a334223371a20599d9c298ef65e7540ede4a3f72d59", 0o755),
              (47505, "7a9ce74cff467ca1bf60a4fcd9f05185acceda4d0f382434d393e17864262c5d", 0o644),
              (368, "d0bebe8133babb74ab29a31ee6616c56ef8985fc801e1fd16c61b5ed2189e907", 0o644)),
    "9.8.1": ((8656, "a5a5c199ba02189ae8c46a334223371a20599d9c298ef65e7540ede4a3f72d59", 0o755),
              (48749, "3b8a25775a69158b5ad2b1d17a80a88dc7b40a352a73eb27d06c612a7ce68e98", 0o644),
              (368, "b9c24447093e4fab2b1f37bfb461d2a1eb14157a4808dff50e0f954801a8f8f1", 0o644)),
}


WRAPPER_DISTRIBUTIONS = {"9.7.1": "acd53f1edaf02f1a8ff99879f8a34b302661a057d9b063ae9e35b552f804d20a",
                         "9.8.1": "dce76f55f8e251a3a1f130eb120f30b3d271de2b76c9b0729d316b5a1b6dc01f"}


@contextmanager
def wrapper_parent(root, relative):
    """root와 각 부모를 descriptor로 고정하며 링크를 따라가지 않는다."""
    descriptor = None
    try:
        flags = os.O_RDONLY | os.O_DIRECTORY | os.O_NOFOLLOW
        descriptor = os.open(root, flags)
        for name in Path(relative).parts[:-1]:
            child = os.open(name, flags, dir_fd=descriptor)
            os.close(descriptor)
            descriptor = child
        yield descriptor, Path(relative).name
    except OSError as error:
        raise DiagnosticError("invalid-build-wrapper") from error
    finally:
        if descriptor is not None:
            os.close(descriptor)


def wrapper_inputs(root):
    """세 고정 경로의 일반 파일만 1MiB씩 제한하여 읽는다."""
    records, contents = [], []
    for relative in WRAPPER_FILES:
        with wrapper_parent(root, relative) as (parent, name):
            descriptor = os.open(name, os.O_RDONLY | os.O_NOFOLLOW | os.O_NONBLOCK, dir_fd=parent)
            with os.fdopen(descriptor, "rb") as stream:
                before = os.fstat(stream.fileno())
                if not stat.S_ISREG(before.st_mode) or not 0 < before.st_size <= 1024 * 1024:
                    raise DiagnosticError("invalid-build-wrapper")
                raw = stream.read(1024 * 1024 + 1)
                after = os.fstat(stream.fileno())
                current = os.stat(name, dir_fd=parent, follow_symlinks=False)
            identity = lambda value: (value.st_dev, value.st_ino, value.st_mode, value.st_size, value.st_mtime_ns)
            if len(raw) != before.st_size or identity(before) != identity(after) or identity(before) != identity(current):
                raise DiagnosticError("build-wrapper-changed-during-read")
            records.append({"path": relative, "bytes": len(raw), "sha256": digest(raw), "mode": stat.S_IMODE(after.st_mode)})
            contents.append(raw)
    return records, contents


def replace_wrapper(root, relative, raw, mode):
    """검증을 마친 임시 archive의 한 파일을 링크 추적 없이 원자적으로 바꾼다."""
    with wrapper_parent(root, relative) as (parent, name):
        temporary = ".kartograph-wrapper-" + os.urandom(12).hex()
        descriptor = os.open(temporary, os.O_WRONLY | os.O_CREAT | os.O_EXCL | os.O_NOFOLLOW, 0o600, dir_fd=parent)
        try:
            with os.fdopen(descriptor, "wb") as stream:
                stream.write(raw)
                stream.flush()
                os.fchmod(stream.fileno(), mode)
                os.fsync(stream.fileno())
            os.replace(temporary, name, src_dir_fd=parent, dst_dir_fd=parent)
        finally:
            try:
                os.unlink(temporary, dir_fd=parent)
            except FileNotFoundError:
                pass


def align_build_wrappers(repo, baseline, report):
    """검토한 9.7.1→9.8.1에 한해 양쪽 엔진을 같은 도구로 빌드한다."""
    original, _ = wrapper_inputs(baseline)
    shared, contents = wrapper_inputs(repo)
    def version(rows):
        identity = tuple((row["bytes"], row["sha256"], row["mode"]) for row in rows)
        return next((name for name, value in REVIEWED_WRAPPERS.items() if identity == value), None)
    old_version, shared_version = version(original), version(shared)
    changed = original != shared
    if changed and (old_version, shared_version) != ("9.7.1", "9.8.1"):
        raise DiagnosticError("build-tool-identity-mismatch")
    value = {"scope": "derived baseline: original engine and build scripts under the common candidate wrapper; not the original build recipe",
             "baselineOriginalVersion": old_version, "sharedVersion": shared_version, "changed": changed,
             "baselineOriginal": original, "shared": shared,
             "baselineOriginalDistributionSha256": WRAPPER_DISTRIBUTIONS.get(old_version) if old_version is not None else None,
             "sharedDistributionSha256": WRAPPER_DISTRIBUTIONS.get(shared_version) if shared_version is not None else None}
    report["buildWrapperAlignment"] = value
    if changed:
        for row, raw in zip(shared, contents):
            replace_wrapper(baseline, row["path"], raw, row["mode"])
    verify_aligned_wrappers(repo, baseline, value)


def verify_aligned_wrappers(repo, baseline, value):
    for root in (repo, baseline):
        records, _ = wrapper_inputs(root)
        require_equal(value["shared"], records, "build-wrapper-changed")


@contextmanager
def aligned_build_inputs(repo, baseline, report):
    """실패한 child 증거를 보존하면서 성공·실패 양쪽의 도구 변이를 검사한다."""
    align_build_wrappers(repo, baseline, report)
    # 정렬 후에도 기존의 양쪽 wrapper 바이트 동일성 전제를 유지한다.
    _, candidate_bytes = wrapper_inputs(repo)
    _, baseline_bytes = wrapper_inputs(baseline)
    require_equal(candidate_bytes, baseline_bytes, "build-tool-identity-mismatch")
    align_verification_metadata(repo, baseline, report)
    with input_integrity_guard(report, "buildInputIntegrity", (
            lambda: verify_aligned_wrappers(repo, baseline, report["buildWrapperAlignment"]),
            lambda: verify_aligned_metadata(repo, baseline, report["verificationMetadataAlignment"]))):
        yield


def java_identity(home):
    # 선택된 JDK의 실행 파일·runtime image·release를 해시한다. 전체 OS identity는 아니다.
    return [file_record(home / name, name)
            for name in ("bin/java", "bin/javac", "release", "lib/modules")]


def kill_group(process):
    """호출자가 unreaped child 소유권 lock을 잡은 동안에만 group에 신호를 보낸다."""
    try:
        os.killpg(process.pid, signal.SIGKILL)
    except ProcessLookupError:
        pass


def nonreaping_waiter():
    """PID를 고정한 채 종료만 관측한다. 지원을 증명하지 못하면 spawn 전에 거부한다."""
    names = ("P_PID", "WEXITED", "WNOHANG", "WNOWAIT", "CLD_EXITED", "CLD_KILLED", "CLD_DUMPED")
    if (os.name != "posix" or sys.platform not in ("linux", "darwin") or not hasattr(os, "wait4") or
            any(not hasattr(os, name) for name in names) or
            signal.getsignal(signal.SIGCHLD) != signal.SIG_DFL):
        raise DiagnosticError("child-ownership-not-supported")
    options = os.WEXITED | os.WNOHANG | os.WNOWAIT
    waitid = getattr(os, "waitid", None)
    if callable(waitid):
        def observe(pid):
            result = waitid(os.P_PID, pid, options)
            if result is None:
                return False
            if (getattr(result, "si_pid", None) != pid or getattr(result, "si_signo", None) != signal.SIGCHLD or
                    getattr(result, "si_code", None) not in (os.CLD_EXITED, os.CLD_KILLED, os.CLD_DUMPED)):
                raise ChildProcessError(errno.ECHILD, "child-ownership-lost")
            return True
        return observe
    if sys.platform != "darwin":
        raise DiagnosticError("child-ownership-not-supported")
    # macOS system Python 3.9는 waitid를 노출하지 않는다. Darwin 64-bit의
    # sys/signal.h siginfo_t / sys/wait.h waitid(idtype_t, id_t, siginfo_t *, int) ABI다.
    class Sigval(ctypes.Union):
        _fields_ = [("integer", ctypes.c_int), ("pointer", ctypes.c_void_p)]
    class Siginfo(ctypes.Structure):
        _fields_ = [("signo", ctypes.c_int), ("error", ctypes.c_int), ("code", ctypes.c_int),
                    ("pid", ctypes.c_int), ("uid", ctypes.c_uint), ("status", ctypes.c_int),
                    ("address", ctypes.c_void_p), ("value", Sigval), ("band", ctypes.c_long),
                    ("padding", ctypes.c_ulong * 7)]
    if (ctypes.sizeof(ctypes.c_void_p), ctypes.sizeof(ctypes.c_long), ctypes.sizeof(Siginfo),
            Siginfo.pid.offset, os.P_PID, os.WEXITED, os.WNOHANG, os.WNOWAIT) != (8, 8, 104, 12, 1, 4, 1, 32):
        raise DiagnosticError("child-ownership-not-supported")
    try:
        waitid = ctypes.CDLL(None, use_errno=True).waitid
        waitid.argtypes = [ctypes.c_int, ctypes.c_uint, ctypes.POINTER(Siginfo), ctypes.c_int]
        waitid.restype = ctypes.c_int
    except (AttributeError, OSError):
        raise DiagnosticError("child-ownership-not-supported")
    def observe(pid):
        information = Siginfo()
        if waitid(os.P_PID, pid, ctypes.byref(information), options) != 0:
            raise OSError(ctypes.get_errno(), "nonreaping-wait-failed")
        if information.pid == 0:
            return False
        if (information.pid != pid or information.signo != signal.SIGCHLD or
                information.code not in (os.CLD_EXITED, os.CLD_KILLED, os.CLD_DUMPED)):
            raise ChildProcessError(errno.ECHILD, "child-ownership-lost")
        return True
    return observe


def group_census():
    """Darwin의 waitable 좀비만 남은 그룹을 bounded PID 목록으로 확인한다."""
    if sys.platform != "darwin":
        return lambda pid: True  # Linux는 zombie-only group 신호의 ESRCH를 처리한다.
    try:
        list_group = ctypes.CDLL("/usr/lib/libproc.dylib", use_errno=True).proc_listpgrppids
        # SDK libproc.h: int proc_listpgrppids(pid_t, void *, int); 반환값은 PID 개수다.
        list_group.argtypes = [ctypes.c_int, ctypes.c_void_p, ctypes.c_int]
        list_group.restype = ctypes.c_int
    except (AttributeError, OSError):
        raise DiagnosticError("child-ownership-not-supported")
    def has_other_members(pid):
        members = (ctypes.c_int * 4096)()
        ctypes.set_errno(0)
        count = list_group(pid, members, ctypes.sizeof(members))
        error = ctypes.get_errno()
        if (count < 0 or count >= len(members) or
                (count == 0 and error not in (0, errno.ESRCH)) or
                any(member <= 0 for member in members[:max(0, count)])):
            raise OSError(error or errno.EIO, "owned-group-census-failed")
        return any(member != pid for member in members[:count])
    return has_other_members


class OwnedProcessGroup:
    """reader 신호·종료 관측·최종 신호 폐쇄를 같은 lock으로 직렬화한다."""

    def __init__(self, process, observe, has_other_members):
        self.process, self.observe = process, observe
        self.has_other_members = has_other_members
        self.lock = threading.Lock()
        self.signal_open, self.lost = True, False

    def ready(self, observer=None):
        with self.lock:
            if not self.signal_open or self.process.returncode is not None:
                self.signal_open, self.lost = False, True
                raise ChildProcessError(errno.ECHILD, "child-ownership-lost")
            try:
                terminal = self.observe(self.process.pid)
            except InterruptedError:
                return False
            except OSError:
                self.signal_open, self.lost = False, True
                raise
            # 관측은 unreaped child를 확보한 같은 lock 안에서만 한다. 관측 오류는 소유권 상실이 아니다.
            if observer is not None:
                try:
                    observer.sample(self.process.pid, terminal)
                except DiagnosticError:
                    raise
                except Exception as error:
                    raise DiagnosticError("observer-sample-failed", {"exceptionType": type(error).__name__}) from error
            return terminal

    def signal_group(self):
        with self.lock:
            self._signal_locked()

    def _signal_locked(self):
        if not self.signal_open:
            return
        if self.process.returncode is not None:
            self.signal_open, self.lost = False, True
            return
        try:
            terminal = self.observe(self.process.pid)
        except OSError:
            self.signal_open, self.lost = False, True
            raise
        # Darwin은 zombie-only group에 EPERM을 반환한다. EPERM을 무시하지 않고,
        # WNOWAIT lease와 같은 그룹의 다른 PID가 없다는 조회를 함께 증명한 때만 생략한다.
        if terminal and not self.has_other_members(self.process.pid):
            return
        kill_group(self.process)

    def close_signals(self):
        # zombie 또는 살아 있는 direct child가 아직 PID를 소유한다. cleanup 후 모든
        # reader 신호를 먼저 폐쇄하고, 이 메서드가 반환된 뒤에만 wait4로 최종 reap한다.
        with self.lock:
            try:
                self._signal_locked()
            finally:
                self.signal_open = False


class SpooledOutput:
    """소유한 임시 출력의 끝까지 읽되 메모리에는 한 chunk만 올린다."""

    def __init__(self, path):
        self.path = Path(path)
        information = self.path.lstat()
        if not stat.S_ISREG(information.st_mode) or information.st_size > RAW_LIMIT:
            raise DiagnosticError("invalid-spooled-output")
        self.identity = (information.st_dev, information.st_ino,
                         information.st_size, information.st_mtime_ns)

    def __len__(self):
        return self.identity[2]

    def chunks(self):
        descriptor = os.open(self.path, os.O_RDONLY | os.O_NOFOLLOW)
        with os.fdopen(descriptor, "rb") as source:
            def unchanged():
                information = os.fstat(source.fileno())
                return (information.st_dev, information.st_ino, information.st_size,
                        information.st_mtime_ns) == self.identity
            if not unchanged():
                raise DiagnosticError("spooled-output-changed")
            for chunk in iter(lambda: source.read(65536), b""):
                yield chunk
            if not unchanged():
                raise DiagnosticError("spooled-output-changed")


def output_chunks(data):
    if isinstance(data, SpooledOutput):
        yield from data.chunks()
    else:
        for offset in range(0, len(data), 65536):
            yield data[offset:offset + 65536]


def output_digest(data):
    checksum = hashlib.sha256()
    for chunk in output_chunks(data):
        checksum.update(chunk)
    return checksum.hexdigest()


def output_equal(left, right):
    if isinstance(left, bytes) and isinstance(right, bytes):
        return left == right
    return len(left) == len(right) and all(
        a == b for a, b in zip_longest(output_chunks(left), output_chunks(right)))


def run(arguments, cwd, env, timeout=120, limit=LIMIT, phase="child",
        stdout_path=None, stdout_limit=RAW_LIMIT, observer=None):
    """각 child의 두 stream을 제한하고 timeout에는 소유한 process group을 끝낸다."""
    if not re.fullmatch(r"[a-z-]{1,64}", phase):
        raise DiagnosticError("invalid-process-phase")
    observe = nonreaping_waiter()
    has_other_members = group_census()
    if type(stdout_limit) is not int or not 0 < stdout_limit <= RAW_LIMIT:
        raise DiagnosticError("invalid-spooled-output-limit")
    process, owned, destination = None, None, None
    readers, started_readers, started_pipes = [], [], set()
    handoff_lock = threading.Lock()
    primary, cleanup_errors = None, []
    handlers, restored, pending = {}, set(), []
    streams = [bytearray(), bytearray()]
    counts = [0, 0]
    overflow = threading.Event()
    failures = []
    timed_out = False
    wait_failed = False
    observer_failure = None
    observer_initiated_failure = False
    observer_reap_failure = None
    reader_unclosed = False
    rss = {"status": "unavailable", "reason": "owned-wait4-rusage-not-observed"}
    started = 0

    def remember_failure(stage, error):
        nonlocal primary
        if primary is None:
            primary = error
        else:
            cleanup_errors.append((stage, error))

    def defer(number, frame):
        pending.append((handlers[number], number, frame))

    def restore_handlers():
        for number, handler in handlers.items():
            if number not in restored:
                try:
                    signal.signal(number, handler)
                except BaseException as error:
                    remember_failure("signal-handler-restore", error)
                else:
                    restored.add(number)

    def replay_pending():
        while pending:
            handler, number, frame = pending.pop(0)
            handler(number, frame)

    try:
        destination = Path(stdout_path).open("xb") if stdout_path is not None else None
        started = time.monotonic_ns()
        # Python handler는 main thread에서만 실행된다. worker 호출에는 신호 API가
        # 필요 없다. SIG_IGN/SIG_DFL은 바꾸지 않아 child의 disposition도 보존한다.
        with handoff_lock:
            if threading.current_thread() is threading.main_thread():
                for number in (signal.SIGINT, signal.SIGTERM):
                    handler = signal.getsignal(number)
                    if callable(handler):
                        handlers[number] = handler
                        signal.signal(number, defer)
            process = subprocess.Popen(list(map(str, arguments)), cwd=cwd, env=env,
                                       stdout=subprocess.PIPE, stderr=subprocess.PIPE,
                                       start_new_session=True)
            owned = OwnedProcessGroup(process, observe, has_other_members)
        def drain(index, stream):
            try:
                while True:
                    chunk = stream.read(65536)
                    if not chunk:
                        break
                    stream_limit = stdout_limit if index == 0 and destination is not None else limit
                    if counts[index] + len(chunk) > stream_limit:
                        overflow.set()
                        owned.signal_group()
                        break
                    if index == 0 and destination is not None:
                        destination.write(chunk)
                    else:
                        streams[index].extend(chunk)
                    counts[index] += len(chunk)
            except OSError:
                failures.append("stream-read-failed")
                owned.signal_group()
            finally:
                stream.close()
        readers = [threading.Thread(target=drain, args=(i, stream), daemon=True)
                   for i, stream in enumerate((process.stdout, process.stderr))]
        for index, reader in enumerate(readers):
            try:
                reader.start()
            except BaseException:
                if getattr(reader, "ident", None) is not None:
                    started_readers.append(reader)
                    started_pipes.add(index)
                raise
            else:
                started_readers.append(reader)
                started_pipes.add(index)
        # Thread.start의 native 시작과 ident 결합 사이에도 callable 예외를 미룬다.
        restore_handlers()
        if primary is not None:
            raise primary
        # 원래 callable을 그대로 호출한다. flag-only handler를 예외로 바꾸지 않는다.
        replay_pending()
        def wait_owned():
            deadline = time.monotonic() + timeout
            while True:
                if (owned.ready() if observer is None else owned.ready(observer)):
                    return
                if time.monotonic() >= deadline:
                    raise subprocess.TimeoutExpired(arguments, timeout)
                time.sleep(0.005)
        try:
            wait_owned()
        except subprocess.TimeoutExpired:
            timed_out = True
        except OSError:
            wait_failed = True
        except DiagnosticError as error:
            observer_failure = error
            # reader가 이미 종료를 유발했으면 뒤늦은 sample 오류가 원래 원인을 덮지 않는다.
            observer_initiated_failure = not (overflow.is_set() or failures)
    except BaseException as error:
        if primary is None:
            primary = error
        elif error is not primary:
            cleanup_errors.append(("setup", error))
    finally:
        if owned is not None and process is not None:
            try:
                owned.close_signals()
            except OSError as error:
                wait_failed = True
                if primary is not None:
                    remember_failure("signal-close", error)
            except BaseException as error:
                remember_failure("signal-close", error)
            if not owned.lost:
                # close_signals 이후에는 main·reader 어느 쪽도 numeric PGID를 사용하지 않는다.
                deadline = time.monotonic() + 10
                try:
                    while True:
                        try:
                            pid, status, usage = os.wait4(process.pid, os.WNOHANG)
                        except InterruptedError:
                            if time.monotonic() >= deadline:
                                raise ChildProcessError(errno.ECHILD, "final-child-wait-failed")
                            continue
                        if pid == process.pid:
                            process.returncode = os.waitstatus_to_exitcode(status)
                            if observer is not None:
                                try:
                                    observer.reaped(usage)
                                except Exception as error:
                                    # reap은 이미 끝났다. 콜백 오류를 wait4 오류로 오인하거나
                                    # reader join·출력 close를 건너뛰지 않고 별도 보존한다.
                                    observer_reap_failure = (error if isinstance(error, DiagnosticError) else
                                        DiagnosticError("observer-reap-failed", {"exceptionType": type(error).__name__}))
                            if sys.platform in ("linux", "darwin") and 0 < usage.ru_maxrss < 2**63:
                                rss = {"status": "observed", "peakBytes": int(usage.ru_maxrss) *
                                       (1024 if sys.platform == "linux" else 1),
                                       "scope": "owned-wait4-child-peak-rss; kernel-accounting-not-live-heap"}
                            else:
                                rss = {"status": "unavailable", "reason": "unknown-rusage-units"}
                            break
                        if pid != 0 or time.monotonic() >= deadline:
                            raise ChildProcessError(errno.ECHILD, "final-child-wait-failed")
                        time.sleep(0.005)
                except OSError as error:
                    wait_failed = True
                    if primary is not None:
                        remember_failure("owned-reap", error)
                    if error.errno != errno.ECHILD:
                        try:
                            process.wait(timeout=10)
                        except (OSError, subprocess.TimeoutExpired) as error:
                            if primary is not None:
                                remember_failure("owned-reap", error)
                            wait_failed = True  # 실패 receipt를 보존하며 신호 경로를 다시 열지 않는다.
                except BaseException as error:
                    remember_failure("owned-reap", error)
        elif process is not None:
            # constructor가 실패했을 때만 쓰는 첫 binding 경로다. reader는 아직 없다.
            # 원래 WNOWAIT와 census로 증명하지 못하면 숫자 신호·reap 모두 거부한다.
            leased = False
            with handoff_lock:
                try:
                    if process.returncode is not None:
                        raise ChildProcessError(errno.ECHILD, "child-ownership-lost")
                    terminal = observe(process.pid)
                    leased = True
                    if not terminal or has_other_members(process.pid):
                        kill_group(process)
                except BaseException as error:
                    remember_failure("unbound-signal-close", error)
                # 이 지점 뒤에는 unbound numeric PGID 경로를 다시 열지 않는다.
                if leased:
                    try:
                        process.wait(timeout=10)
                    except BaseException as error:
                        remember_failure("unbound-reap", error)
        for reader in started_readers:
            try:
                reader.join(timeout=10)
                if reader.is_alive():
                    reader_unclosed = True  # 이후 늦게 종료해도 부분 snapshot을 성공으로 바꾸지 않는다.
            except BaseException as error:
                remember_failure("reader-join", error)
        if process is not None:
            for index, source in enumerate((process.stdout, process.stderr)):
                if index not in started_pipes and source is not None:
                    try:
                        source.close()
                    except BaseException as error:
                        remember_failure("unstarted-source-close", error)
        restore_handlers()
        while pending:
            try:
                replay_pending()
            except BaseException as error:
                remember_failure("signal-replay", error)
        if destination is not None:
            try:
                destination.close()
            except BaseException as error:
                remember_failure("destination-close", error)
    if primary is not None:
        if observer_reap_failure is not None:
            cleanup_errors.append(("observer-reaped", observer_reap_failure))
        if cleanup_errors:
            setattr(primary, "launch_cleanup_errors", cleanup_errors)
        raise primary
    assert process is not None
    result = {"exit": process.returncode,
              "stdout": SpooledOutput(stdout_path) if destination is not None else bytes(streams[0]),
              "stderr": bytes(streams[1]),
              "wallNanos": time.monotonic_ns() - started, "rss": rss, "phase": phase}
    child_reason = ("child-stream-not-closed" if reader_unclosed else
                    "child-timeout" if timed_out else "child-output-limit" if overflow.is_set() else
                    "child-stream-failed" if failures else "child-wait-failed" if wait_failed else None)
    reason = child_reason
    if observer_failure is not None and (observer_initiated_failure or reason is None):
        reason = str(observer_failure)
    if reason is None and observer_reap_failure is not None:
        reason = str(observer_reap_failure)
    if reason:
        receipt = summary(result)
        if observer_failure is not None:
            receipt["observerSampleFailure"] = {"reason": str(observer_failure), "receipt": observer_failure.receipt}
            if observer_failure.receipt is not None:
                receipt["observerFailure"] = observer_failure.receipt
        if observer_reap_failure is not None:
            receipt["observerReapFailure"] = {"reason": str(observer_reap_failure), "receipt": observer_reap_failure.receipt}
            if child_reason is None and observer_failure is None and observer_reap_failure.receipt is not None:
                receipt["observerFailure"] = observer_reap_failure.receipt
        if observer_failure is not None or observer_reap_failure is not None:
            if child_reason is not None:
                receipt["childFailure"] = {"reason": child_reason}
            if wait_failed or reader_unclosed:
                receipt["observerCleanupIncomplete"] = True
        raise DiagnosticError(reason, receipt)
    return result


def summary(result):
    return {"phase": result.get("phase", "child"), "exit": result["exit"], "wallNanos": result["wallNanos"], "rss": result["rss"],
            **{stream: {"bytes": len(result[stream]), "sha256": output_digest(result[stream])}
               for stream in ("stdout", "stderr")}}


def successful(arguments, cwd, env, timeout=120, phase="child"):
    result = run(arguments, cwd, env, timeout, phase=phase)
    if result["exit"] != 0:
        raise DiagnosticError("required-child-failed", summary(result))
    return result


def validate_probe(result, profile):
    try:
        data = json.loads(result["stdout"])
    except (ValueError, UnicodeError):
        raise DiagnosticError("invalid-probe-output")
    if not isinstance(data, dict):
        raise DiagnosticError("invalid-probe-output")
    numeric = ("entryUptimeMillis", "invocationNanos", "currentThreadAllocatedBytes",
               "nodes", "locatedNodes", "edges", "externalCalls", "classFiles", "parsedClasses", "cacheHits",
               "cacheMisses", "readNanos", "cacheReadNanos", "parseNanos", "assemblyNanos",
               "hierarchyNanos", "runtimeNanos", "dispatchNanos", "cacheWriteNanos", "totalNanos")
    expected_keys = set(numeric) | {"profile", "allocationScope"}
    if (set(data) != expected_keys or data.get("profile") != profile or result["stderr"]
            or data.get("allocationScope") !=
            "index-invocation-current-thread-only; excludes-other-threads-and-startup"
            or any(not (type(data[key]) is int and 0 <= data[key] < 2**63)
                   for key in numeric if not (key == "currentThreadAllocatedBytes" and data[key] is None))):
        raise DiagnosticError("invalid-probe-output")
    return data


ARMS = ("baseline", "candidate")
PROFILE_COUNT_KEYS = ("nodes", "locatedNodes", "edges", "externalCalls", "classFiles", "parsedClasses", "cacheHits",
                      "cacheMisses")

# PR #223(라이브러리 재정의 callback 간선, SOURCE retention 어노테이션 표시, @JvmOverloads·@JvmStatic 분류)은 그래프
# 구조와 dead 출력을 의도적으로 바꾼다. 고정 baseline 78153c3 대비 그 변경만 정확히 허용한다(f5f5418·33f8ce1의 고정
# delta와 같은 방식). 입력은 이 브랜치 head의 production class라 결정적이므로 CI에서 관측한 arm별 값을 그대로 고정한다.
# - 각 arm 안의 표본은 계속 모두 같아야 하고, CLI는 두 arm 모두 종료 코드 0이어야 하며, 엔진 지문 규칙은 그대로다.
# - 두 arm이 같으면 고정값과 무관하게 기존 바이트 동일 판정으로 통과한다.
# - 두 arm이 다르면 baseline·후보 값이 함께 고정값과 같을 때만 허용한다. 아니면 관측값을 `contractDelta`에 남기고
#   단계 끝까지 관측을 모은 뒤 `structural-delta-unpinned`(고정값 없음) 또는 `structural-delta-stale`로 실패한다.
#   class 입력이 바뀌어 낡았으면 그 실행 로그의 `contractDelta.unmatched[].observed`를 다시 고정하거나 BASELINE을 옮긴다.
# BASELINE을 #223 병합 commit으로 옮기면 비활성화되며, 그때 이 상수·고정값·관련 테스트를 함께 지운다.
STRUCTURAL_DELTA_BASELINE = "78153c338e20db7193be4a93ac5de90fc594e026"
# 아래 값은 88bdf9e CI(run 42626332813, test job)의 관측이다. 노드·외부 호출·class 수는 같고 간선만 늘며(graph·architecture
# +1413, dead +1577), dead·dead_private stdout은 SOURCE_RETENTION_ANNOTATIONS 한계 한 줄(150바이트)만큼 길다.
# profile -> arm -> PROFILE_COUNT_KEYS 순서의 정수 목록.
EXPECTED_PROFILE_COUNTS = {
    "graph": {"baseline": [22239, 22239, 127956, 70701, 1557, 1557, 0, 0], "candidate": [22239, 22239, 129369, 70701, 1557, 1557, 0, 0]},
    "dead": {"baseline": [22239, 22239, 551346, 70701, 1557, 1557, 0, 0], "candidate": [22239, 22239, 552923, 70701, 1557, 1557, 0, 0]},
    "architecture": {"baseline": [22239, 22239, 104462, 70701, 1557, 1557, 0, 0], "candidate": [22239, 22239, 105875, 70701, 1557, 1557, 0, 0]},
}
# CLI 명령 -> arm -> {"exit", "stdout": {"bytes", "sha256"}, "stderr": {"bytes", "sha256"}}.
EXPECTED_CLI = {
    "graph": {
        "baseline": {"exit": 0,
                     "stdout": {"bytes": 34290939, "sha256": "ccec0adb2241cc6964a7fe4e0701d0e5fe8f9f00b7c83d0d8fbe3990cc7e20c1"},
                     "stderr": {"bytes": 137, "sha256": "b525606c58fc4a6c97135198cc35a921abf61be3d7ae18496da4f2eec36f3d5b"}},
        "candidate": {"exit": 0,
                     "stdout": {"bytes": 34561926, "sha256": "959ea990e5280c56a43a22c9b5ea40e1e065290c2e109717c9da22ad3b4bedfe"},
                     "stderr": {"bytes": 137, "sha256": "b525606c58fc4a6c97135198cc35a921abf61be3d7ae18496da4f2eec36f3d5b"}},
    },
    "dead": {
        "baseline": {"exit": 0,
                     "stdout": {"bytes": 1679, "sha256": "f7f783f5ee08d674c1cacfd3de0ac95aad4a8f434fc80990200e4f2edfc7e0aa"},
                     "stderr": {"bytes": 0, "sha256": "e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855"}},
        "candidate": {"exit": 0,
                     "stdout": {"bytes": 1829, "sha256": "be77eea46f0155c894e29778468774f074c4433f94698984f77b362174c97f43"},
                     "stderr": {"bytes": 0, "sha256": "e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855"}},
    },
    "dead_private": {
        "baseline": {"exit": 0,
                     "stdout": {"bytes": 1679, "sha256": "f7f783f5ee08d674c1cacfd3de0ac95aad4a8f434fc80990200e4f2edfc7e0aa"},
                     "stderr": {"bytes": 0, "sha256": "e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855"}},
        "candidate": {"exit": 0,
                     "stdout": {"bytes": 1829, "sha256": "be77eea46f0155c894e29778468774f074c4433f94698984f77b362174c97f43"},
                     "stderr": {"bytes": 0, "sha256": "e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855"}},
    },
}
# "semantic" -> arm -> {"graphs": graphs.json 내용, "payloadsSha256": raw payload 사전의 identity digest}.
EXPECTED_SEMANTIC = {"semantic": {
    "baseline": {"graphs": {"json": {"bytes": 283579005, "sha256": "fd5e931f4da92fe5384e788ba8030c1bfc0994e330a66b5d1aeb5c26dd6142d1"},
                          "ndjson": {"bytes": 343174848, "sha256": "786fc8af6b0b193e090bb3e479a8a19dc717b95b91d47933e021d36b37e0285e"}},
                 "payloadsSha256": "865fc35402f4f6306b6cbd280202348355e08d1c39f81679c133889e020222d4"},
    "candidate": {"graphs": {"json": {"bytes": 283942818, "sha256": "767b4bd549bdd833ab75200ad903ec4d7d6455f50a1271c81ec70ca1f89685ee"},
                          "ndjson": {"bytes": 343651588, "sha256": "add790da10905b5da8d1ee98d02d3baa8b6a301eb9f4e733126f521fb67002ce"}},
                 "payloadsSha256": "a9189bec1e9d38ac694f1dd52dc67044ea9f7823e963563783df2c1cdc574641"},
}}
STRUCTURAL_DELTA_PINS = {"profiles": EXPECTED_PROFILE_COUNTS, "cli": EXPECTED_CLI, "semantic": EXPECTED_SEMANTIC}


def structural_delta_active():
    return SOURCE_BASELINE == BASELINE == STRUCTURAL_DELTA_BASELINE


def structural_delta_record(report):
    return report.setdefault("contractDelta", {
        "baseline": STRUCTURAL_DELTA_BASELINE, "scope": "pinned-per-arm-structural-delta; exact-values-only",
        "accepted": [], "unmatched": []})


def accept_pinned_delta(stage, key, observed, report):
    """두 arm의 관측이 함께 고정값과 같을 때만 참이다. 다르면 관측을 남기고 거짓을 돌려준다(판정은 호출자가 미룬다)."""
    expected = STRUCTURAL_DELTA_PINS[stage].get(key)
    record = structural_delta_record(report)
    if expected == observed:
        record["accepted"].append({"stage": stage, "key": key})
        return True
    record["unmatched"].append({"stage": stage, "key": key, "pinned": expected is not None, "observed": observed})
    return False


def raise_pending_structural_delta(report):
    """앞 단계가 미룬 고정 delta 불일치를 모든 관측이 끝난 뒤 실패로 닫는다."""
    unmatched = report.get("contractDelta", {}).get("unmatched", [])
    if unmatched:
        unpinned = any(not row["pinned"] for row in unmatched)
        raise DiagnosticError("structural-delta-unpinned" if unpinned else "structural-delta-stale")


def check_profile_counts(records, report=None):
    for profile in PROFILES:
        by_arm = {arm: [[row[key] for key in PROFILE_COUNT_KEYS] for row in records
                        if row["profile"] == profile and row["arm"] == arm] for arm in ARMS}
        if any(len(values) != 3 or any(value != values[0] for value in values) for values in by_arm.values()):
            raise DiagnosticError("profile-structural-counts-changed")
        if by_arm["baseline"][0] == by_arm["candidate"][0]:
            continue
        if not structural_delta_active() or report is None:
            raise DiagnosticError("profile-structural-counts-changed")
        accept_pinned_delta("profiles", profile, {arm: by_arm[arm][0] for arm in ARMS}, report)


def successful_pair(left, right):
    return left["exit"] == 0 and right["exit"] == 0 and equivalent(left, right)


def source_identity(repo, ref, env):
    result = successful(["git", "rev-parse", "--verify", ref + "^{commit}"], repo, env)
    commit = result["stdout"].decode("ascii").strip()
    result = successful(["git", "rev-parse", "--verify", ref + "^{tree}"], repo, env)
    tree = result["stdout"].decode("ascii").strip()
    if not all(re.fullmatch(r"[0-9a-f]{40}", value) for value in (commit, tree)):
        raise DiagnosticError("invalid-source-identity")
    return {"commit": commit, "tree": tree}


# cache 엔진 지문(index의 CacheIdentity)은 index·core·ASM·kotlin-metadata·stdlib 등 엔진 쪽 jar 바이트에서 나온다.
# 배포 lib에서 엔진 밖의 kartograph module만 빼고 나머지를 버전과 무관한 artifact 이름으로 비교한다.
ENGINE_MODULES = frozenset(("core", "index"))
# CLI lib에 실제로 있는 엔진 밖 module만 명시한다. 여기에 없는 module(새 module, 엔진 역할을 모두 담은 gradle-plugin
# fat jar 등)은 일부러 엔진으로 세어 바이트가 다르면 같은 지문을 시끄럽게 실패시킨다.
NON_ENGINE_ARTIFACTS = frozenset(("analysis", "export", "cli"))


def engine_artifacts(rows):
    """배포 lib inventory의 엔진 쪽 파일을 artifact 이름→정렬된 sha256 목록으로 만든다. 빌드 시각은 보지 않는다."""
    result = {}
    for row in rows:
        path = row["path"]
        name = path[:-len(".jar")] if path.endswith(".jar") and "/" not in path else None
        artifact = re.sub(r"-[0-9][0-9A-Za-z.+_-]*$", "", name) if name is not None else path
        if name is not None and artifact in NON_ENGINE_ARTIFACTS:
            continue
        result.setdefault(artifact, []).append(row["sha256"])
    return {artifact: sorted(values) for artifact, values in result.items()}


def engine_jars_identical(libraries):
    """두 배포의 엔진 쪽 jar가 바이트 동일한지 본다.

    두 inventory가 없거나, 어느 한쪽이라도 엔진 module(index·core) jar를 엔진으로 분류하지 못했으면 비교 근거가
    없으므로 판정하지 않는다(None). 그러면 같은 지문은 실패한다. 빈 엔진 집합끼리 같다고 보지 않기 위한 조건이다.
    """
    if not isinstance(libraries, dict) or set(libraries) != {"baseline", "candidate"}:
        return None
    baseline, candidate = engine_artifacts(libraries["baseline"]), engine_artifacts(libraries["candidate"])
    if not all(ENGINE_MODULES <= set(artifacts) for artifacts in (baseline, candidate)):
        return None
    return baseline == candidate


def require_baseline_source(repo, source, baseline, env):
    """보조 baseline이 원본의 실행 소스·빌드 스크립트를 바꾸지 않았는지 Git tree로 확인한다."""
    changed = successful(["git", "diff", "--no-ext-diff", "--no-textconv", "--no-renames", "--name-only",
                          source, baseline, "--"], repo, env, phase="baseline-source-diff")
    expected = b"" if source == baseline else b"gradle/verification-metadata.xml\n"
    require_equal(changed["stdout"], expected, "baseline-executable-source-changed")


def identity_digest(value):
    return digest(json.dumps(value, sort_keys=True, separators=(",", ":")).encode("utf-8"))


def public_summary(report):
    # 경로·환경·원문 stream 없이 고정 계수와 identity hash만 log에 남긴다.
    result = {"format": report["format"], "status": report["status"],
              "reason": report.get("reason"), "scope": report["scope"],
              "residualScope": report["residualScope"]}
    if "failure" in report:
        result["failure"] = report["failure"]
    if "sourcesBefore" in report:
        result["sources"] = report["sourcesBefore"]
    if "verificationMetadataAlignment" in report:
        result["verificationMetadataAlignment"] = report["verificationMetadataAlignment"]
    for key in ("metadataInputIntegrity", "buildWrapperAlignment", "buildInputIntegrity", "executionInputIntegrity"):
        if key in report:
            result[key] = report[key]
    result["identitySha256"] = {key: identity_digest(report[key]) for key in (
        "rootsBefore", "projectBefore", "libsBefore", "launchersBefore", "jdkBefore",
        "probeSourceBefore", "semanticSourceBefore", "probesBefore") if key in report}
    result["profiles"] = report.get("profiles", [])
    result["cli"] = report.get("cli", [])
    if "cliTiming" in report:
        result["cliTiming"] = report["cliTiming"]
    if "semantic" in report:
        result["semantic"] = report["semantic"]
    if "contractDelta" in report:
        result["contractDelta"] = report["contractDelta"]
    encoded = json.dumps(result, sort_keys=True)
    if len(encoded.encode("utf-8")) > 65536:
        raise DiagnosticError("public-summary-too-large")
    return encoded


def _public_jfr_stack_trace_view(original):
    """표시 frame 한도와 녹화·rank의 잘림을 분리하며 옛 관측의 깊이는 추정하지 않는다."""
    if "stackTraceView" not in original:
        return {"status": "unavailable", "requestedDepth": None, "completeStacksGuaranteed": False,
                "scope": "source-observation-did-not-record-print-depth"}
    value = original["stackTraceView"]
    if (not isinstance(value, dict) or set(value) != {"requestedDepth", "completeStacksGuaranteed"}
            or type(value["requestedDepth"]) is not int or not 1 <= value["requestedDepth"] <= 256
            or value["completeStacksGuaranteed"] is not False):
        raise DiagnosticError("invalid-public-observation")
    return {"status": "observed", "requestedDepth": value["requestedDepth"], "completeStacksGuaranteed": False,
            "scope": "jfr-print-frame-view; recording-truncation-and-ranking-omissions-are-separate"}


def public_observation_summary(report):
    """이미 수집한 관측만 별도 제한 로그로 공개한다. 경로·환경·원시 stack은 복사하지 않는다."""
    def integer(value, maximum=2**63 - 1):
        if type(value) is not int or not 0 <= value <= maximum:
            raise DiagnosticError("invalid-public-observation")
        return value

    def checksum(value):
        if not isinstance(value, str) or not re.fullmatch(r"[0-9a-f]{64}", value):
            raise DiagnosticError("invalid-public-observation")
        return value

    def load(value):
        averages = value["loadAverage"]
        if (not isinstance(averages, list) or len(averages) != 3 or any(
                type(number) not in (int, float) or not math.isfinite(number)
                or not 0 <= number <= 1_000_000 for number in averages)):
            raise DiagnosticError("invalid-public-observation")
        cpus = value["logicalCpus"]
        if cpus is not None and integer(cpus, 65536) == 0:
            raise DiagnosticError("invalid-public-observation")
        return {"loadAverage": averages, "logicalCpus": cpus,
                "monotonicNanos": integer(value["monotonicNanos"])}

    result = {"format": "kartograph-index-observations-v1", "diagnosticStatus": report["status"],
              "reportIdentitySha256": identity_digest(report),
              "scope": "already-collected-observations; no-new-processes; no-performance-acceptance",
              "jfr": {"status": "unavailable"}, "cliLoad": {"status": "unavailable"}}
    try:
        if report["status"] not in ("PASS", "FAIL"):
            raise DiagnosticError("invalid-public-observation")
        if "jfr" in report:
            original = report["jfr"]
            samples = original["samples"]
            if (set(samples) != set(JFR_EVENTS) or original["command"] != "dead"
                    or original["outputMatchesUnprofiledCandidate"] is not True
                    or original["scope"] != "single-profiled-candidate; sampled-stacks-and-weighted-allocations; not-timing-acceptance"
                    or original["launcher"] != "direct-java-same-main-class-and-arguments; startup-not-equivalent"):
                raise DiagnosticError("invalid-public-observation")
            selected = {}
            for kind in JFR_EVENTS:
                row = samples[kind]
                total = integer(row["weight"])
                count = integer(row["events"], 20000)
                if kind == "jdk.ExecutionSample" and total != count:
                    raise DiagnosticError("invalid-public-observation")
                selected[kind] = {"events": count, "weight": total}
                for field in ("inclusive", "nearest"):
                    ranks = row[field]
                    if not isinstance(ranks, list) or len(ranks) > 30:
                        raise DiagnosticError("invalid-public-observation")
                    checked = []
                    for name, weight in ranks:
                        if (not isinstance(name, str) or len(name) > 1024 or not re.fullmatch(
                                r"dev/kartograph/(?:[A-Za-z0-9_$-]+/)*[A-Za-z0-9_$-]+\.(?:[A-Za-z0-9_$-]+|<init>|<clinit>)", name)):
                            raise DiagnosticError("invalid-public-observation")
                        checked.append([name, integer(weight, total)])
                    if (len({name for name, _ in checked}) != len(checked)
                            or checked != sorted(checked, key=lambda item: (-item[1], item[0]))):
                        raise DiagnosticError("invalid-public-observation")
                    selected[kind][field] = checked[:10]
                    selected[kind][field + "OmittedFromStoredTop30"] = max(0, len(checked) - 10)
            recording = original["recording"]
            size = integer(recording["size"], JFR_LIMIT)
            if size == 0:
                raise DiagnosticError("invalid-public-observation")
            result["jfr"] = {
                "status": "observed", "command": "dead", "samples": selected,
                "scope": "single-profiled-candidate; all-thread-samples; not-wall-time-or-total-heap",
                "launcher": "direct-java-same-main-class-and-arguments; startup-not-equivalent",
                "rankingScope": "top-10-of-stored-top-30-product-methods; full-tail-count-not-recorded; inclusive-rows-overlap",
                "stackTraceView": _public_jfr_stack_trace_view(original),
                "gcJitAttribution": "not-recorded", "outputMatchesUnprofiledCandidate": True,
                "configurationSha256": checksum(original["configurationSha256"]),
                "recording": {"size": size, "sha256": checksum(recording["sha256"])}}
        if "cliLoad" in report:
            rows = report["cliLoad"]
            if not isinstance(rows, list) or len(rows) > 36:
                raise DiagnosticError("invalid-public-observation")
            selected, seen = [], set()
            for row in rows:
                key = (row["command"], row["sample"], row["arm"])
                if (key[0] not in ("graph", "dead", "dead_private", "cycles", "rules", "metrics")
                        or type(key[1]) is not int or key[1] not in (1, 2, 3)
                        or key[2] not in ("baseline", "candidate") or key in seen):
                    raise DiagnosticError("invalid-public-observation")
                seen.add(key)
                before, after = load(row["before"]), load(row["after"])
                if before["monotonicNanos"] > after["monotonicNanos"]:
                    raise DiagnosticError("invalid-public-observation")
                selected.append({"command": key[0], "sample": key[1], "arm": key[2],
                                 "before": before, "after": after})
            result["cliLoad"] = {"status": "observed" if len(rows) == 36 else "partial",
                                 "scope": "same-runner-sequential-children; host-load-observed-not-controlled; not-process-cpu-or-gc",
                                 "expectedRows": 36, "observedRows": len(rows), "rows": selected}
        encoded = json.dumps(result, sort_keys=True, allow_nan=False)
    except (ValueError, KeyError, TypeError, AttributeError, OverflowError):
        raise DiagnosticError("invalid-public-observation") from None
    if len(encoded.encode("utf-8")) > 65536:
        raise DiagnosticError("public-observations-too-large")
    return encoded


def write_report(report, output):
    # 관측 공개 오류도 실패로 남기며 원본 관측은 result.json에서 보존한다.
    try:
        observations = public_observation_summary(report)
    except DiagnosticError as error:
        report["status"] = "FAIL"
        report.setdefault("reason", str(error))
        report["observationSummaryFailure"] = str(error)
        observations = json.dumps({"format": "kartograph-index-observations-v1",
                                   "diagnosticStatus": "FAIL", "status": "invalid",
                                   "reason": str(error)}, sort_keys=True)
    output.parent.mkdir(parents=True, exist_ok=True)
    output.write_text(json.dumps(report, indent=2, sort_keys=True) + "\n", encoding="utf-8")
    print(public_summary(report))
    print(observations)
    return 0 if report["status"] == "PASS" else 1


def equivalent(left, right):
    # 순서·중복·진단·줄바꿈·종료 코드를 있는 그대로 비교한다.
    return left["exit"] == right["exit"] and all(
        output_equal(left[key], right[key]) for key in ("stdout", "stderr"))


def schedule():
    return [(profile, sample, arm) for profile in PROFILES
            for sample, order in enumerate((("baseline", "candidate"), ("candidate", "baseline"),
                                            ("baseline", "candidate")), 1) for arm in order]


def cli_schedule():
    # 각 명령을 세 쌍씩 관측하고 첫 실행 위치도 번갈아 배치한다. 성공 표본을 고르지 않는다.
    return [(index, sample, ("baseline", "candidate") if (index + sample) % 2 else
             ("candidate", "baseline")) for sample in range(1, 4) for index in range(6)]


def host_load_snapshot():
    # 실행 인자·환경·다른 프로세스 신원은 수집하지 않는다. 동일 부하를 보장하는 값도 아니다.
    return {"loadAverage": list(os.getloadavg()), "logicalCpus": os.cpu_count(),
            "monotonicNanos": time.monotonic_ns()}


class RawStreams:
    """제한된 공개 fixture 출력만 손실 없이 보존한다. 기존 실행의 파일은 덮어쓰지 않는다."""

    def __init__(self, directory):
        self.directory = directory
        directory.mkdir()
        self.bytes = 0

    def retain(self, command, sample, arm, result):
        records = {}
        for stream in ("stdout", "stderr"):
            data = result[stream]
            if self.bytes + len(data) > RAW_LIMIT:
                raise DiagnosticError("raw-evidence-limit")
            name = f"{command}-{sample}-{arm}-{stream}.gz"
            with (self.directory / name).open("xb") as destination:
                with gzip.GzipFile(fileobj=destination, mode="wb", mtime=0, filename="") as archive:
                    for chunk in output_chunks(data):
                        archive.write(chunk)
            self.bytes += len(data)
            records[stream] = {"path": "raw/" + name, "bytes": len(data), "sha256": output_digest(data)}
        return records


def paired_cli(commands, project, env, located_nodes, report, raw):
    report["cli"] = []
    report["cliLoad"] = []
    for index, sample, order in cli_schedule():
        name = commands["baseline"][index][0]
        with tempfile.TemporaryDirectory(prefix="cli-streams-", dir=raw.directory.parent) as temporary:
            results, retained = {}, {}
            for arm in order:
                before = host_load_snapshot()
                results[arm] = run(commands[arm][index][1], project, env,
                                   phase="cli-" + name.replace("_", "-"),
                                   stdout_path=Path(temporary) / arm)
                after = host_load_snapshot()
                retained[arm] = raw.retain(name, sample, arm, results[arm])
                report["cliLoad"].append({"command": name, "sample": sample, "arm": arm,
                                          "before": before, "after": after})
            left, right = results["baseline"], results["candidate"]
            accepted = successful_pair(left, right)
            report["cli"].append({"command": name, "sample": sample, "order": list(order),
                                  "exactEqual": equivalent(left, right),
                                  "expectedContractDelta": False, "contractAccepted": accepted,
                                  "comparisonScope": "exact-byte-comparison",
                                  "raw": retained, **{arm: summary(result) for arm, result in results.items()}})
    report["cliTiming"] = {
        "scope": "three-paired-fresh-processes-per-command; no-performance-pass-or-budget-change",
        "loadScope": "same-runner-sequential-children; host-load-observed-not-controlled",
        "arms": {arm: {
            "medianSeconds": {name: statistics.median(
                row[arm]["wallNanos"] / 1e9 for row in report["cli"] if row["command"] == name)
                for name, _ in commands[arm]},
        } for arm in ("baseline", "candidate")},
        "pairedDeltasNanos": [{"command": row["command"], "sample": row["sample"],
                               "candidateMinusBaseline": row["candidate"]["wallNanos"] - row["baseline"]["wallNanos"]}
                              for row in report["cli"]],
    }
    for values in report["cliTiming"]["arms"].values():
        values["sumMedianSeconds"] = sum(values["medianSeconds"].values())
    pending = accept_pinned_cli_deltas(report)
    if not all(row["contractAccepted"] or row["command"] in pending for row in report["cli"]):
        raise DiagnosticError("cli-byte-mismatch-or-unsuccessful-exit")


def accept_pinned_cli_deltas(report):
    """바이트가 다른 명령에 고정 delta를 적용하고, 판정을 미룬 명령 이름 집합을 돌려준다.

    각 arm의 세 표본이 종료 코드·stdout·stderr 모두 같고 두 arm이 종료 코드 0일 때만 고정값과 비교한다.
    그렇지 않으면 아무것도 허용하지 않아 기존 `cli-byte-mismatch-or-unsuccessful-exit`가 그대로 난다.
    """
    pending = set()
    if not structural_delta_active():
        return pending
    for name in dict.fromkeys(row["command"] for row in report["cli"]):
        rows = [row for row in report["cli"] if row["command"] == name]
        if all(row["contractAccepted"] for row in rows):
            continue
        observed = {arm: [{key: row[arm][key] for key in ("exit", "stdout", "stderr")} for row in rows] for arm in ARMS}
        if any(len(rows) != 3 or any(value != values[0] for value in values) or values[0]["exit"] != 0
               for values in observed.values()):
            continue
        if accept_pinned_delta("cli", name, {arm: observed[arm][0] for arm in ARMS}, report):
            for row in rows:
                row.update(expectedContractDelta=True, contractAccepted=True, comparisonScope="pinned-structural-delta")
        else:
            pending.add(name)
    return pending


def cli_commands(binary, roots, project):
    classes = [value for root in roots for value in ("--classes", str(root))]
    dead = ["dead", *classes, "--project", str(project),
            "--manifest", str(project / "AndroidManifest.xml"), "--resources", str(project / "res"),
            "--namespace", "dev.kartograph", "--keep-rules", str(project / ".kartograph-self.pro")]
    return [
        ("graph", [binary, "graph", *classes, "--format", "dot"]),
        ("dead", [binary, *dead, "--strict"]),
        ("dead_private", [binary, *dead, "--include-private-members", "--strict"]),
        ("cycles", [binary, "cycles", *classes, "--strict"]),
        ("rules", [binary, "rules", *classes, "--config", str(project / ".kartograph.yml"), "--strict"]),
        ("metrics", [binary, "metrics", *classes]),
    ]


def compile_probe(repo, distribution, destination, java_home, env):
    destination.mkdir()
    successful([java_home / "bin/javac", "-cp", str(distribution / "lib/*"), "-d", destination,
                repo / "Scripts/IndexCostProbe.java"], repo, env, phase="compile-probe")
    return str(destination) + os.pathsep + str(distribution / "lib/*")


def compare_semantic_outputs(outputs, report, distribution_libraries=None):
    """engine 지문은 보존하고 v10 raw payload와 두 전체 graph 형식의 바이트를 비교한다.

    지문 판정은 두 배포의 엔진 쪽 jar 바이트로 한다(`distribution_libraries`는 arm별 lib inventory). 지문과 엔진 jar가
    함께 같으면 교차 엔진 동등성을 관측하지 못한 same-engine control로 통과한다. 지문만 같으면 각 arm이 자기 엔진을
    읽었다는 근거가 없고, 엔진 jar만 같으면 지문이 비결정적이거나 다른 코드를 읽은 것이므로 둘 다 실패한다.
    inventory가 없으면 엔진 jar 동일성을 모르므로 같은 지문은 실패한다.
    """
    records, payloads, graphs = {}, {}, {}
    for arm, directory in outputs.items():
        files = {}
        for name in ("raw-facts.json", "graphs.json"):
            path = directory / name
            if path.is_symlink() or not path.is_file() or not 0 < path.stat().st_size <= LIMIT:
                raise DiagnosticError("semantic-output-bound")
            files[name] = file_record(path, name)
        path = directory / "raw-facts.json"
        if path.stat().st_size > LIMIT:
            raise DiagnosticError("semantic-facts-bound")
        try:
            document = json.loads(path.read_bytes())
            graph = json.loads((directory / "graphs.json").read_bytes())
            if (not isinstance(graph, dict) or set(graph) != {"json", "ndjson"} or any(
                    not isinstance(row, dict) or set(row) != {"bytes", "sha256"} or type(row["bytes"]) is not int
                    or not 0 < row["bytes"] <= 512 * 1024 * 1024 or not isinstance(row["sha256"], str)
                    or not re.fullmatch(r"[0-9a-f]{64}", row["sha256"]) for row in graph.values())):
                raise ValueError()
            if (set(document) != {"format", "engine", "payloads"}
                    or document["format"] != "class-facts-v10-payload-sha256"
                    or not isinstance(document["engine"], str)
                    or not re.fullmatch(r"[0-9a-f]{64}", document["engine"])
                    or not isinstance(document["payloads"], dict) or not document["payloads"]
                    or any(not re.fullmatch(r"[0-9a-f]{64}", key) or not isinstance(value, str)
                           or not re.fullmatch(r"[0-9a-f]{64}", value)
                           for key, value in document["payloads"].items())):
                raise ValueError()
        except (ValueError, TypeError, KeyError):
            raise DiagnosticError("invalid-semantic-facts") from None
        payloads[arm] = document["payloads"]
        graphs[arm] = graph
        records[arm] = {"engine": document["engine"], "entries": len(payloads[arm]), "files": files, "graphs": graph}
    left, right = records["baseline"], records["candidate"]
    equal_graphs = graphs["baseline"] == graphs["candidate"]
    same_engine = left["engine"] == right["engine"]
    jars_identical = engine_jars_identical(distribution_libraries)
    report["semantic"] = {"scope": "non-timed-full-graph-JSON-NDJSON-and-v10-raw-payload; graph-bytes-not-retained",
                          "arms": records, "graphsEqual": equal_graphs,
                          "rawPayloadsEqual": payloads["baseline"] == payloads["candidate"],
                          "enginesDiffer": not same_engine, "engineJarsIdentical": jars_identical,
                          "engineScope": ("same-engine control: engine jars are byte-identical; "
                                          "cross-engine equivalence not exercised") if same_engine and jars_identical is True
                          else "recorded separately; implementation JAR identities change; each cache consumed by its own reader"}
    if not equal_graphs or payloads["baseline"] != payloads["candidate"]:
        observed = {arm: {"graphs": graphs[arm], "payloadsSha256": identity_digest(payloads[arm])} for arm in ARMS}
        if not structural_delta_active():
            raise DiagnosticError("semantic-graph-or-raw-payload-mismatch")
        report["semantic"]["comparisonScope"] = "pinned-structural-delta"
        accept_pinned_delta("semantic", "semantic", observed, report)
    if same_engine and jars_identical is not True:
        raise DiagnosticError("semantic-engine-did-not-change")
    if not same_engine and jars_identical is True:
        raise DiagnosticError("semantic-engine-fingerprint-unstable")


def semantic_oracle(repo, distributions, roots, java_home, env, work, report, raw):
    outputs = {}
    report["semanticProcesses"] = {}
    for arm, distribution in distributions.items():
        classes = work / ("semantic-probe-" + arm)
        classes.mkdir()
        successful([java_home / "bin/javac", "-encoding", "UTF-8", "-cp", str(distribution / "lib/*"), "-d", classes,
                    repo / "Scripts/IndexEquivalenceProbe.java"], repo, env, phase="compile-semantic-probe")
        outputs[arm] = work / ("semantic-output-" + arm)
        result = successful([java_home / "bin/java", "-cp", str(classes) + os.pathsep + str(distribution / "lib/*"),
                             "IndexEquivalenceProbe", outputs[arm], *roots], work, env, phase="semantic-oracle")
        report["semanticProcesses"][arm] = summary(result)
        if (outputs[arm] / "raw-facts.json").stat().st_size > LIMIT:
            raise DiagnosticError("semantic-facts-bound")
        raw.retain("semantic-facts", 1, arm,
                   {"stdout": (outputs[arm] / "raw-facts.json").read_bytes(), "stderr": result["stdout"]})
    compare_semantic_outputs(outputs, report, distribution_libraries=report.get("libsBefore"))


def summarize_jfr(data):
    """샘플의 가중치를 실제 heap 크기나 실행 시간으로 바꾸지 않는다."""
    result = {name: {"events": 0, "weight": 0, "inclusive": Counter(), "nearest": Counter()}
              for name in JFR_EVENTS}
    try:
        events = json.loads(data)["recording"]["events"]
        if not isinstance(events, list) or len(events) > 20000:
            raise ValueError()
        for event in events:
            kind, values = event["type"], event["values"]
            if kind not in result:
                raise ValueError()
            weight = values["weight"] if kind == "jdk.ObjectAllocationSample" else 1
            if type(weight) is not int or weight < 0:
                raise ValueError()
            row = result[kind]
            row["events"] += 1
            row["weight"] += weight
            frames = (values.get("stackTrace") or {}).get("frames", [])
            if len(frames) > 256:
                raise ValueError()
            product = []
            for frame in frames:
                method = frame["method"]
                owner = method["type"]["name"]
                name = owner + "." + method["name"]
                if owner.startswith("dev/kartograph/"):
                    if len(name) > 1024 or not re.fullmatch(r"[A-Za-z0-9_$/.<>-]+", name):
                        raise ValueError()
                    product.append(name)
            for name in set(product):
                row["inclusive"][name] += weight
            if product:
                row["nearest"][product[0]] += weight
        return {name: {**row, **{field: sorted(row[field].items(), key=lambda item: (-item[1], item[0]))[:30]
                                 for field in ("inclusive", "nearest")}} for name, row in result.items()}
    except (ValueError, KeyError, TypeError, AttributeError):
        raise DiagnosticError("invalid-jfr-events") from None


def capture_jfr(java_home, arguments, env, work, output, config, expected):
    """별도 단회 실행이며 paired 중앙값·정식 gate 예산에는 포함하지 않는다."""
    work.mkdir()
    configuration = config.read_bytes()
    (work / "capture.jfc").write_bytes(configuration)
    tool = java_home / "bin" / ("jfr.exe" if os.name == "nt" else "jfr")
    tool_before = file_record(tool, "bin/jfr")
    result = successful([java_home / "bin/java", "-Xlog:jfr+startup=off",
                         "-XX:StartFlightRecording=settings=capture.jfc,filename=capture.jfr,maxsize=16m,dumponexit=true",
                         *arguments], work, env, phase="jfr-candidate")
    if not equivalent(result, expected):
        raise DiagnosticError("jfr-cli-byte-mismatch", summary(result))
    recording = work / "capture.jfr"
    if not recording.is_file() or recording.is_symlink() or not 0 < recording.stat().st_size <= JFR_LIMIT:
        raise DiagnosticError("jfr-recording-size")
    # 알려진 두 샘플과 내부 metadata 이외의 event가 있으면 원본을 공개 artifact로 복사하지 않는다.
    listing = successful([tool, "summary", recording], work, env, phase="jfr-summary")
    counts = {}
    for line in listing["stdout"].decode("utf-8").splitlines():
        match = re.fullmatch(r"\s*(\S+)\s+(\d+)\s+\d+\s*", line)
        if match and int(match[2]):
            counts[match[1]] = int(match[2])
    if not counts or set(counts) - {*JFR_EVENTS, "jdk.CheckPoint", "jdk.Metadata"}:
        raise DiagnosticError("unexpected-jfr-event-type")
    events = successful([tool, "print", "--json", "--stack-depth", str(JFR_PRINT_STACK_DEPTH),
                         "--events", ",".join(JFR_EVENTS), recording],
                        work, env, phase="jfr-events")
    samples = summarize_jfr(events["stdout"])
    for name in JFR_EVENTS:
        if samples[name]["events"] != counts.get(name, 0):
            raise DiagnosticError("jfr-event-count-mismatch")
    if not sum(row["events"] for row in samples.values()):
        raise DiagnosticError("jfr-no-samples")
    require_equal(tool_before, file_record(tool, "bin/jfr"), "jfr-tool-changed")
    require_equal(configuration, config.read_bytes(), "jfr-configuration-changed")
    output.mkdir()
    with (output / "candidate.jfr").open("xb") as destination:
        destination.write(recording.read_bytes())
    return {"scope": "single-profiled-candidate; sampled-stacks-and-weighted-allocations; not-timing-acceptance",
            "command": "dead", "launcher": "direct-java-same-main-class-and-arguments; startup-not-equivalent",
            "stackTraceView": {"requestedDepth": JFR_PRINT_STACK_DEPTH, "completeStacksGuaranteed": False},
            "configurationSha256": digest(configuration), "tool": tool_before,
            "recording": file_record(output / "candidate.jfr", "jfr/candidate.jfr"),
            "eventCounts": counts, "samples": samples, "process": summary(result),
            "outputMatchesUnprofiledCandidate": True}


def experiment(repo, work, env, report, raw_directory=None):
    java_home = Path(env["JAVA_HOME"]).resolve(strict=True)
    jdk_before = java_identity(java_home)
    report["jdkBefore"] = jdk_before
    candidate = repo / "cli/build/install/kartograph"
    if not (candidate / "bin/kartograph").is_file() or not os.access(candidate / "bin/kartograph", os.X_OK):
        raise DiagnosticError("missing-candidate-distribution")
    candidate_before = inventory(candidate / "lib")
    launchers_candidate_before = inventory(candidate / "bin")
    candidate_source = source_identity(repo, "HEAD", env)
    probe_source_before = file_record(repo / "Scripts/IndexCostProbe.java", "IndexCostProbe.java")
    report["probeSourceBefore"] = probe_source_before
    semantic_source_before = file_record(repo / "Scripts/IndexEquivalenceProbe.java", "IndexEquivalenceProbe.java")
    report["semanticSourceBefore"] = semantic_source_before
    source_before = root_inventory(repo)
    frozen = work / "input"
    for module in MODULES:
        relative = Path(module) / "build/classes/kotlin/main"
        shutil.copytree(repo / relative, frozen / relative, copy_function=shutil.copy2)
    frozen_before = root_inventory(frozen)
    require_equal(source_before, frozen_before, "frozen-copy-mismatch")
    report["rootsBefore"] = frozen_before
    project = work / "project"
    project.mkdir()
    (project / "res").mkdir()
    (project / "AndroidManifest.xml").write_text(
        '<manifest xmlns:android="http://schemas.android.com/apk/res/android" package="dev.kartograph">\n'
        '    <application/>\n</manifest>\n', encoding="utf-8")
    for name in (".kartograph-self.pro", ".kartograph.yml"):
        shutil.copy2(repo / name, project / name)
    project_before = inventory(project)
    report["projectBefore"] = project_before
    baseline = work / "baseline"
    baseline.mkdir()
    # shallow checkout에서도 요청한 고정 object만 확보한다.
    for ref in (SOURCE_BASELINE, BASELINE):
        found = run(["git", "cat-file", "-e", ref + "^{commit}"], repo, env, phase="baseline-presence")
        if found["exit"] != 0:
            successful(["git", "fetch", "--no-tags", "--depth=1", "origin", ref], repo, env, phase="baseline-fetch")
    require_baseline_source(repo, SOURCE_BASELINE, BASELINE, env)
    baseline_source = source_identity(repo, BASELINE, env)
    if baseline_source["commit"] != BASELINE:
        raise DiagnosticError("baseline-resolution-mismatch")
    sources_before = {"baselineSource": source_identity(repo, SOURCE_BASELINE, env),
                      "baseline": baseline_source, "candidate": candidate_source}
    report["sourcesBefore"] = sources_before
    archive = work / "baseline.tar"
    successful(["git", "archive", "--format=tar", "--output", archive, BASELINE], repo, env, phase="baseline-archive")
    with tarfile.open(archive) as source:
        source.extractall(baseline, filter="data")
    with aligned_build_inputs(repo, baseline, report), input_integrity_guard(report, "metadataInputIntegrity", (
            lambda: verify_aligned_metadata(repo, baseline, report["verificationMetadataAlignment"]),)):
        report["baselinePreparation"] = summary(successful(
            [baseline / "gradlew", "--no-daemon", ":cli:installDist"], baseline, env, timeout=600, phase="baseline-build"))
        verify_aligned_metadata(repo, baseline, report["verificationMetadataAlignment"])
        distributions = {"baseline": baseline / "cli/build/install/kartograph", "candidate": candidate}
        libs_before = {arm: inventory(dist / "lib") for arm, dist in distributions.items()}
        require_equal(candidate_before, libs_before["candidate"], "candidate-rebuilt-or-changed")
        report["libsBefore"] = libs_before
        launchers_before = {arm: inventory(dist / "bin") for arm, dist in distributions.items()}
        require_equal(launchers_candidate_before, launchers_before["candidate"], "candidate-launchers-changed")
        report["launchersBefore"] = launchers_before
        require_equal(source_before, root_inventory(repo), "source-roots-changed-before-arms")
        require_equal(frozen_before, root_inventory(frozen), "frozen-roots-changed-before-arms")
        require_equal(jdk_before, java_identity(java_home), "selected-jdk-changed-before-arms")
        classpaths = {arm: compile_probe(repo, dist, work / ("probe-" + arm), java_home, env)
                      for arm, dist in distributions.items()}
        probes_before = {arm: inventory(work / ("probe-" + arm)) for arm in distributions}
        report["probesBefore"] = probes_before
        roots = [frozen / module / "build/classes/kotlin/main" for module in MODULES]
        report["profiles"] = []
        report["cli"] = []
        def verify_execution_inputs():
            report["sourcesAfter"] = {"baselineSource": source_identity(repo, SOURCE_BASELINE, env),
                                      "baseline": source_identity(repo, BASELINE, env),
                                      "candidate": source_identity(repo, "HEAD", env)}
            report["launchersAfter"] = {arm: inventory(dist / "bin") for arm, dist in distributions.items()}
            report["probeSourceAfter"] = file_record(repo / "Scripts/IndexCostProbe.java", "IndexCostProbe.java")
            report["semanticSourceAfter"] = file_record(repo / "Scripts/IndexEquivalenceProbe.java", "IndexEquivalenceProbe.java")
            report["probesAfter"] = {arm: inventory(work / ("probe-" + arm)) for arm in distributions}
            require_equal(sources_before, report["sourcesAfter"], "source-commit-or-tree-changed")
            require_equal(launchers_before, report["launchersAfter"], "distribution-launchers-changed")
            require_equal(probe_source_before, report["probeSourceAfter"], "probe-source-changed")
            require_equal(semantic_source_before, report["semanticSourceAfter"], "semantic-source-changed")
            require_equal(probes_before, report["probesAfter"], "compiled-probes-changed")
            report["rootsAfter"] = root_inventory(frozen)
            report["libsAfter"] = {arm: inventory(dist / "lib") for arm, dist in distributions.items()}
            report["jdkAfter"] = java_identity(java_home)
            report["projectAfter"] = inventory(project)
            require_equal(source_before, root_inventory(repo), "source-roots-changed")
            require_equal(frozen_before, report["rootsAfter"], "frozen-roots-changed")
            require_equal(libs_before, report["libsAfter"], "distribution-libs-changed")
            require_equal(jdk_before, report["jdkAfter"], "selected-jdk-changed")
            require_equal(project_before, report["projectAfter"], "shared-project-changed")

        with input_integrity_guard(report, "executionInputIntegrity", (verify_execution_inputs,)):
            for profile, sample, arm in schedule():
                result = successful([java_home / "bin/java", "-cp", classpaths[arm], "IndexCostProbe",
                                     profile, *roots], project, env, phase="index-probe")
                data = validate_probe(result, profile)
                report["profiles"].append({"arm": arm, "sample": sample, **data, "process": summary(result)})
            check_profile_counts(report["profiles"], report)
            commands = {arm: cli_commands(dist / "bin/kartograph", roots, project)
                        for arm, dist in distributions.items()}
            located_nodes = next(row["locatedNodes"] for row in report["profiles"] if row["profile"] == "dead")
            raw = RawStreams(raw_directory if raw_directory is not None else work / "raw")
            paired_cli(commands, project, env, located_nodes, report, raw)
            # 이미 보존한 paired 출력과 비교한다. 새 unprofiled 성공 표본을 만들지 않는다.
            prior = next(row for row in report["cli"] if row["command"] == "dead")
            expected = {"exit": prior["candidate"]["exit"]}
            for stream in ("stdout", "stderr"):
                receipt = prior["raw"]["candidate"][stream]
                value = gzip.decompress((raw.directory / Path(receipt["path"]).name).read_bytes())
                if len(value) != receipt["bytes"] or digest(value) != receipt["sha256"]:
                    raise DiagnosticError("jfr-reference-changed")
                expected[stream] = value
            report["jfr"] = capture_jfr(java_home,
                ["-cp", str(candidate / "lib/*"), "dev.kartograph.cli.MainKt", *commands["candidate"][1][1][1:]],
                env, work / "jfr-capture", raw.directory.parent / "jfr", repo / "Scripts/IndexCostCapture.jfc", expected)
            semantic_oracle(repo, distributions, roots, java_home, env, work, report, raw)


def main():
    repo = Path(__file__).resolve().parents[1]
    output = repo / "build/reports/index-cost/result.json"
    output.parent.mkdir(parents=True, exist_ok=True)
    report = {"format": "kartograph-index-cost-v1", "baselineSource": SOURCE_BASELINE, "baseline": BASELINE, "status": "FAIL",
              "scope": "frozen-class-and-config-diagnostic; original-source-tree-omitted; separate-from-mandatory-smoke",
              "residualScope": "process-minus-index-includes-startup-loading-probe-and-shutdown; not-query-only"}
    try:
        env = dict(os.environ)
        if sys.platform != "linux" or not env.get("JAVA_HOME") or not env.get("RUNNER_TEMP"):
            raise DiagnosticError("required-runner-input-missing")
        if any(env.get(name) for name in ("CLASSPATH", "JAVA_OPTS", "KARTOGRAPH_OPTS", "JAVA_TOOL_OPTIONS",
                                         "JDK_JAVA_OPTIONS", "_JAVA_OPTIONS")):
            raise DiagnosticError("injected-java-options")
        with tempfile.TemporaryDirectory(prefix="kartograph-index-cost-", dir=env["RUNNER_TEMP"]) as name:
            experiment(repo, Path(name), env, report, output.parent / "raw")
        # 단계들이 관측을 모으려고 미룬 고정 delta 불일치는 PASS 직전에 반드시 실패로 닫는다.
        raise_pending_structural_delta(report)
        report["status"] = "PASS"
    except DiagnosticError as error:
        report["reason"] = str(error)
        if error.receipt is not None:
            report["failure"] = error.receipt
    except Exception:
        report["reason"] = "unexpected-diagnostic-error"
    return write_report(report, output)


if __name__ == "__main__":
    sys.exit(main())
