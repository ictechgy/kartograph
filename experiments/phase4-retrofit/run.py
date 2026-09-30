#!/usr/bin/env python3
"""Retrofit 요청 오라클을 실제 MockWebServer 실행으로 다시 만든다(선택 실행, 기본 CI 밖).

harness/의 독립 Gradle 빌드가 fixtures/retrofit-corpus/oracle/retrofit-client의 합성 서비스를 Retrofit 2.12.0으로
컴파일하고, 메서드마다 요청을 보내 OkHttp MockWebServer가 받은 동사·경로·query를 기록한다. 이 스크립트는 그 기록에
소스 목록과 오라클 버전을 더해 fixtures/retrofit-corpus/oracle/retrofit-requests.json으로 쓴다. Maven Central에서
의존성을 받으므로 네트워크가 필요하다. MockWebServer는 오라클 프로세스 안에서만 떠 있다가 종료된다.

사용: python3 experiments/phase4-retrofit/run.py [--check]
  --check  파일을 쓰지 않고 현재 retrofit-requests.json과 다르면 종료 코드 1로 실패한다.
"""

import json
import subprocess
import sys
import tempfile
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
HARNESS = Path(__file__).resolve().parent / "harness"
ORACLE = ROOT / "fixtures" / "retrofit-corpus" / "oracle"
OUTPUT = ORACLE / "retrofit-requests.json"
TIMEOUT_SECONDS = 1800
VERSIONS = {"retrofit": "2.12.0", "okhttp": "3.14.9", "mockwebserver": "3.14.9", "dagger": "2.59", "koin": "4.2.2"}


def run_oracle(output: Path) -> dict:
    """오라클을 한 번 실행하고 기록을 읽는다. 실패는 원인과 해결 방향을 담아 예외로 올린다."""
    command = [str(ROOT / "gradlew"), "--no-daemon", "-q", "-p", str(HARNESS), "run", f"--args={output}"]
    completed = subprocess.run(command, cwd=ROOT, capture_output=True, text=True, timeout=TIMEOUT_SECONDS)
    if completed.returncode != 0:
        tail = "\n".join(completed.stderr.splitlines()[-20:])
        raise RuntimeError(f"Retrofit oracle failed with exit {completed.returncode}; "
                           f"check JDK 21 and Maven Central access\n{tail}")
    return json.loads(output.read_text())


def source_files(project: str = "retrofit-client") -> list:
    """합성 서비스 소스 목록이다 — 테스트가 classpath에서 같은 파일을 임시 프로젝트로 복사해 스캔한다."""
    return sorted(str(path.relative_to(ORACLE)) for path in (ORACLE / project).rglob("*")
                  if path.suffix in (".java", ".kt"))


def build_document() -> dict:
    with tempfile.TemporaryDirectory(prefix="retrofit-oracle-") as scratch:
        recorded = run_oracle(Path(scratch) / "raw.json")
    return {
        "format": "kartograph-retrofit-requests",
        "version": 1,
        "description": "Requests that Retrofit sent to OkHttp MockWebServer for each synthetic service method. "
                       "path is the encoded request path; pathArguments are the @Path values in template order.",
        "provenance": "execution",
        "oracle": dict(VERSIONS, source="experiments/phase4-retrofit/harness"),
        "sources": source_files(),
        "cases": recorded["cases"],
        # 인터셉터 결합 코퍼스는 따로 스캔하는 프로젝트다(재작성 인터셉터가 기존 코퍼스의 결합을 바꾸지 않게).
        "interceptorSources": source_files("interceptor-client"),
        "interceptorCases": recorded["interceptorCases"],
    }


def render(document: dict) -> str:
    return json.dumps(document, indent=2, sort_keys=True, ensure_ascii=False) + "\n"


def main(argv: list) -> int:
    check = argv == ["--check"]
    if argv and not check:
        print("usage: run.py [--check]", file=sys.stderr)
        return 64
    text = render(build_document())
    if check:
        current = OUTPUT.read_text() if OUTPUT.is_file() else ""
        if current != text:
            print(f"{OUTPUT.relative_to(ROOT)} differs from the Retrofit oracle; rerun without --check", file=sys.stderr)
            return 1
        print("Retrofit oracle matches the committed requests")
        return 0
    OUTPUT.write_text(text)
    print(f"wrote {OUTPUT.relative_to(ROOT)}")
    return 0


if __name__ == "__main__":
    sys.exit(main(sys.argv[1:]))
