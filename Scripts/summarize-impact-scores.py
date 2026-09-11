#!/usr/bin/env python3
"""공개 채점 요약을 원시 score 문서에서 손실 없는 정해진 조합으로 생성한다."""
import argparse
import json
from pathlib import Path


def main():
    parser = argparse.ArgumentParser(description="Assemble the recorded initial impact evaluation cohort.")
    for name in ("okhttp-score", "anki-score", "roundtrip", "runtime", "agent", "output"):
        parser.add_argument("--" + name, required=True)
    args = parser.parse_args()
    def read(value):
        return json.loads(Path(value).read_text())
    report = {"date": "2026-09-11", "datasetRevision": "b9025d86f7ae634901396766bd61f6d4ae6d2165", "platform": "macOS arm64",
        "scope": "Two native scoped test replays, not the full Harbor benchmark; test patch injected in both source versions.",
        "replays": {"okhttp-6887": read(args.okhttp_score), "anki-19661": read(args.anki_score)},
        "compactSnapshot": read(args.roundtrip), "runtime": read(args.runtime), "agent": read(args.agent),
        "selection": {"executed": ["square_okhttp-6887", "ankidroid_Anki-Android-19661"],
            "noFailureTarget": ["square_okhttp-8968", "ankidroid_Anki-Android-18903"],
            "notRun": ["ankidroid_Anki-Android-19384", "ankidroid_Anki-Android-19509", "ankidroid_Anki-Android-19982", "ankidroid_Anki-Android-20250"]}}
    Path(args.output).write_text(json.dumps(report, indent=2, ensure_ascii=True) + "\n")


if __name__ == "__main__":
    main()
