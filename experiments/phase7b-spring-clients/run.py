#!/usr/bin/env python3
"""Spring HTTP 클라이언트 요청 오라클을 실제 실행으로 다시 만든다(선택 실행, 기본 CI 밖).

harness/의 독립 Gradle 빌드가 fixtures/spring-clients-corpus/oracle/spring-client의 합성 Spring Boot 클라이언트 앱을
Spring Boot 3.5.16(Spring Framework 6.2.19)으로 컴파일하고, 호출마다 요청을 보내 127.0.0.1의 기록 서버(JVM HTTP 프록시)가
받은 동사·경로·query·원래 host를 기록한다. 이 스크립트는 그 기록에 소스 목록과 버전을 더해
fixtures/spring-clients-corpus/oracle/spring-client-requests.json으로 쓴다. Maven Central·Gradle 플러그인 포털에서 의존성을
받으므로 네트워크가 필요하다. 기록 서버는 오라클 프로세스 안에서만 떠 있다가 종료된다.

사용: python3 experiments/phase7b-spring-clients/run.py [--check]
  --check  파일을 쓰지 않고 현재 spring-client-requests.json과 다르면 종료 코드 1로 실패한다.
"""

import json
import subprocess
import sys
import tempfile
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
HARNESS = Path(__file__).resolve().parent / "harness"
ORACLE = ROOT / "fixtures" / "spring-clients-corpus" / "oracle"
OUTPUT = ORACLE / "spring-client-requests.json"
TIMEOUT_SECONDS = 1800
VERSIONS = {"spring-boot": "3.5.16", "spring-framework": "6.2.19", "http": "JDK HttpClient + HttpURLConnection via JVM proxy"}


def run_oracle(output: Path) -> dict:
    """오라클을 한 번 실행하고 기록을 읽는다. 실패는 원인과 해결 방향을 담아 예외로 올린다."""
    command = [str(ROOT / "gradlew"), "--no-daemon", "-q", "-p", str(HARNESS), "run", f"--args={output}"]
    completed = subprocess.run(command, cwd=ROOT, capture_output=True, text=True, timeout=TIMEOUT_SECONDS)
    if completed.returncode != 0:
        tail = "\n".join(completed.stderr.splitlines()[-30:])
        raise RuntimeError(f"Spring client oracle failed with exit {completed.returncode}; "
                           f"check JDK 21 and Maven Central access\n{tail}")
    return json.loads(output.read_text())


def source_files() -> list:
    """합성 앱 소스·설정 목록이다 — 테스트가 classpath에서 같은 파일을 임시 프로젝트로 복사해 스캔한다."""
    return sorted(str(path.relative_to(ORACLE)) for path in (ORACLE / "spring-client").rglob("*")
                  if path.suffix in (".java", ".kt", ".yml", ".properties"))


def build_document() -> dict:
    with tempfile.TemporaryDirectory(prefix="spring-client-oracle-") as scratch:
        recorded = run_oracle(Path(scratch) / "raw.json")
    return {
        "format": "kartograph-spring-client-requests",
        "version": 1,
        "description": "Requests that the synthetic Spring Boot client app sent for each call, recorded by a local proxy. "
                       "path is the encoded request path; pathArguments fill the template's {} in order; basePath is set "
                       "only when the base URL is a runtime value the producer cannot resolve.",
        "provenance": "execution",
        "oracle": dict(VERSIONS, source="experiments/phase7b-spring-clients/harness"),
        "sources": source_files(),
        "cases": recorded["cases"],
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
            print(f"{OUTPUT.relative_to(ROOT)} differs from the Spring client oracle; rerun without --check", file=sys.stderr)
            return 1
        print("Spring client oracle matches the committed requests")
        return 0
    OUTPUT.write_text(text)
    print(f"wrote {OUTPUT.relative_to(ROOT)}")
    return 0


if __name__ == "__main__":
    sys.exit(main(sys.argv[1:]))
