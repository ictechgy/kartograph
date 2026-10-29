#!/usr/bin/env python3
"""JPA 명명 벡터를 실제 Hibernate 실행으로 다시 만든다(선택 실행, 기본 CI 밖).

harness/의 독립 Gradle 빌드가 fixtures/jpa-naming/src의 벡터 엔티티를 Hibernate 6·7로 부트스트랩하고
hbm2ddl 스크립트(SchemaExport와 같은 경로)를 쓴다. 이 스크립트는 그 DDL에서 읽은 테이블→컬럼을
fixtures/jpa-naming/vectors.json으로 모은다. Maven Central에서 의존성을 받으므로 네트워크가 필요하다.

사용: python3 experiments/jpa-persistence/run.py [--check]
  --check  파일을 쓰지 않고 현재 vectors.json과 다르면 종료 코드 1로 실패한다.
"""

import json
import subprocess
import sys
import tempfile
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
HARNESS = Path(__file__).resolve().parent / "harness"
VECTORS = ROOT / "fixtures" / "jpa-naming" / "vectors.json"

# (Gradle 하위 프로젝트, 오라클 profile, kartograph profile, Spring Boot 버전 또는 None)
RUNS = [
    ("hibernate6", "spring-boot", "spring-boot-3", "3.5.16"),
    ("hibernate7-2", "spring-boot", "spring-boot-4", "4.0.8"),
    ("hibernate7-4", "spring-boot", "spring-boot-4", "4.1.1"),
    ("hibernate6", "hibernate", "hibernate-6", None),
    ("hibernate7-2", "hibernate", "hibernate-7", None),
    ("hibernate7-4", "hibernate", "hibernate-7", None),
]
TIMEOUT_SECONDS = 1800


def run_oracle(project: str, profile: str, output: Path) -> dict:
    """오라클 한 번을 실행하고 tables.json을 읽는다. 실패는 원인과 함께 예외로 올린다."""
    command = [str(ROOT / "gradlew"), "--no-daemon", "-q", "-p", str(HARNESS), f":{project}:run",
               f"--args={profile} {output}"]
    completed = subprocess.run(command, cwd=ROOT, capture_output=True, text=True, timeout=TIMEOUT_SECONDS)
    if completed.returncode != 0:
        tail = "\n".join(completed.stderr.splitlines()[-20:])
        raise RuntimeError(f"oracle {project}/{profile} failed with exit {completed.returncode}; "
                           f"check JDK 21 and Maven Central access\n{tail}")
    return json.loads((output / "tables.json").read_text())


def case(project: str, profile: str, kartograph_profile: str, boot: str, result: dict) -> dict:
    """벡터 케이스 하나다 — provenance는 실제 실행(execution)이다."""
    oracle = {"hibernate": result["hibernateVersion"], "strategies": result["strategies"]}
    if boot:
        oracle["springBoot"] = boot
    return {
        "id": f"{kartograph_profile}/hibernate-{result['hibernateVersion']}",
        "profile": kartograph_profile,
        "provenance": "execution",
        "source": f"experiments/jpa-persistence/harness :{project} ({profile})",
        "oracle": oracle,
        "expect": {"tables": result["tables"]},
    }


def source_files() -> list:
    """벡터 엔티티 소스 목록이다 — 테스트가 classpath에서 같은 파일을 복사해 스캔한다."""
    base = VECTORS.parent
    return sorted(str(path.relative_to(base)) for path in (base / "src").rglob("*") if path.suffix in (".java", ".kt"))


def build_document() -> dict:
    cases = []
    with tempfile.TemporaryDirectory(prefix="jpa-naming-") as scratch:
        for index, (project, profile, kartograph_profile, boot) in enumerate(RUNS):
            output = Path(scratch) / f"{index}-{project}-{profile}"
            cases.append(case(project, profile, kartograph_profile, boot, run_oracle(project, profile, output)))
    return {
        "format": "kartograph-jpa-naming-vectors",
        "version": 1,
        "description": "JPA entity sources under src/main mapped to table and column names by real Hibernate "
                       "schema export. Table keys are schema-qualified when @Table(schema) is set; quotes are stripped.",
        "sources": source_files(),
        "cases": cases,
    }


def main() -> int:
    check = "--check" in sys.argv[1:]
    rendered = json.dumps(build_document(), indent=2, sort_keys=False, ensure_ascii=False) + "\n"
    if check:
        current = VECTORS.read_text() if VECTORS.exists() else ""
        if current != rendered:
            print("vectors.json differs from the Hibernate oracle; rerun without --check", file=sys.stderr)
            return 1
        print("vectors.json matches the Hibernate oracle")
        return 0
    VECTORS.write_text(rendered)
    print(f"wrote {VECTORS.relative_to(ROOT)} ({len(RUNS)} cases)")
    return 0


if __name__ == "__main__":
    sys.exit(main())
