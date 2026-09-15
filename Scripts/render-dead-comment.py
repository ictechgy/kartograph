#!/usr/bin/env python3
"""dead JSON 리포트를 PR 코멘트용 마크다운으로 바꾼다. 게시는 호출자(gh 등)가 소유한다."""

import argparse
import json
import sys
from pathlib import Path

ESCAPES = str.maketrans({"|": "\\|", "`": "\\`", "\n": " "})


def cell(value):
    """마크다운 표 셀을 깨뜨리는 문자를 무력화한다."""
    return str(value).translate(ESCAPES)


def render_comment(report):
    """같은 리포트면 같은 코멘트 본문을 만든다. 절대경로는 리포트에 없는 그대로 둔다."""
    diagnostics = report.get("diagnostics")
    if diagnostics is None or report.get("command") != "dead":
        raise ValueError("report is not a kartograph dead JSON document")

    lines = []
    count = len(diagnostics)
    if count == 0:
        lines.append("## kartograph dead — no unreachable declarations")
    else:
        lines.append(f"## kartograph dead — {count} finding(s)")
        lines.append("")
        lines.append("| Location | Declaration | Confidence |")
        lines.append("|---|---|---|")
        for diagnostic in diagnostics:
            location = diagnostic.get("location") or {}
            path = location.get("path")
            if path is None:
                place = "-"
            else:
                line = location.get("line")
                place = f"{path}:{line}" if line else path
            confidence = diagnostic.get("confidence", "unmeasured")
            if diagnostic.get("testOnly"):
                confidence += " (used only by tests)"
            lines.append(f"| `{cell(place)}` | `{cell(diagnostic.get('nodeId', ''))}` | {confidence} |")

    summary = [f"{count} finding(s) reported"]
    suppressed = report.get("suppressedCount")
    if suppressed:
        summary.append(f"{suppressed} suppressed by baseline or suppress entries")
    expired = report.get("expiredSuppressions")
    if expired:
        summary.append(f"{expired} suppression(s) expired")
    lines.append("")
    lines.append(", ".join(summary) + ". Findings are reachability facts, not deletion approvals.")

    limitations = report.get("limitations") or []
    if limitations:
        lines.append("")
        lines.append("<details>")
        lines.append("<summary>Analysis limitations</summary>")
        lines.append("")
        for limitation in limitations:
            lines.append(f"- {cell(limitation)}")
        lines.append("")
        lines.append("</details>")
    return "\n".join(lines) + "\n"


def main(arguments=None):
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--report", type=Path, help="dead JSON report path; reads stdin when omitted")
    parser.add_argument("--output", type=Path, help="comment body path; writes stdout when omitted")
    options = parser.parse_args(arguments)
    try:
        content = options.report.read_text(encoding="utf-8") if options.report else sys.stdin.read()
        body = render_comment(json.loads(content))
    except (OSError, ValueError, json.JSONDecodeError) as error:
        print(f"error: unable to render a comment: {error}", file=sys.stderr)
        return 2
    if options.output:
        options.output.write_text(body, encoding="utf-8")
    else:
        sys.stdout.write(body)
    return 0


if __name__ == "__main__":
    sys.exit(main())
