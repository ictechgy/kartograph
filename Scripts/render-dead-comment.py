#!/usr/bin/env python3
"""dead JSON 리포트를 PR 코멘트용 마크다운으로 바꾼다. 게시는 호출자(gh 등)가 소유한다."""

import argparse
import json
import re
import string
import sys
from pathlib import Path

# 줄 끝이나 보이지 않는 구조로 해석될 수 있는 C0·DEL·C1 제어문자와 줄·문단 구분자다. 공백으로 바꾼다.
CONTROL_CHARACTERS = re.compile(r"[\x00-\x1f\x7f-\x9f\u2028\u2029]")
# README에 문서화된 confidence 등급이다. 셀 밖에 그대로 들어가므로 다른 값은 렌더링하지 않는다.
CONFIDENCE_TIERS = ("static", "needs-runtime-review", "runtime-observed", "unmeasured")
# 한 글자만으로 HTML·링크·강조·제목·code span·멘션·표 구분을 열 수 있는 문자다. `!`·`(`·`)`는 `[`가
# escape되면 이미지·링크를 만들지 못하므로 그대로 두어 `document(s)` 같은 정상 한계 문구의 바이트를 지킨다.
TEXT_ESCAPES = {"&": "&amp;", "<": "&lt;", ">": "&gt;", **{character: "\\" + character for character in "[]`*_~#@|"}}
ASCII_PUNCTUATION = frozenset(string.punctuation)
PIPE_WITH_BACKSLASHES = re.compile(r"(\\*)\|")
# 목록 항목 내용 첫머리에서 블록을 여는 bullet(`-`·`+`)과 ordered(숫자열 뒤 `.`·`)`) 표지다. `- ---`는 줄 전체가
# thematic break가 되고, 나머지는 항목 안에 중첩 목록을 연다(CommonMark 4.1, 5.2). `*`는 TEXT_ESCAPES가 escape한다.
LEADING_LIST_MARKER = re.compile(r"[-+]|[0-9]+[.)]")


def escape_table_pipe(match):
    """`|` 앞 backslash 연속을 홀수로 맞춰 어느 GFM 방식에서도 escape된 `|`로 읽히게 한다.

    cmark-gfm은 바로 앞 backslash 하나만 보지만 marked 등은 연속 개수가 짝수면 셀 구분자로 읽는다.
    렌더러는 `|` 앞 backslash 하나를 지우므로, 원래 연속이 홀수인 드문 값만 backslash 하나가 더 보인다.
    """
    backslashes = match.group(1)
    return backslashes + ("\\" if len(backslashes) % 2 == 0 else "\\\\") + "|"


def code_cell(value):
    """값을 표 셀 하나에 갇힌 code span으로 만든다.

    code span 안에서는 backslash escape가 동작하지 않으므로(CommonMark 6.1) 값 안의 가장 긴 백틱 연속보다
    하나 긴 fence로 감싼다. 값이 백틱이나 공백으로 시작하거나 끝나면 양쪽에 공백을 하나씩 둔다. 렌더러는
    양끝 공백을 하나씩 벗기므로 원래 값이 그대로 보인다. GFM 표는 code span 안의 `|`도 셀 구분으로 읽으므로
    escape한다.
    """
    text = PIPE_WITH_BACKSLASHES.sub(escape_table_pipe, CONTROL_CHARACTERS.sub(" ", str(value)))
    fence = "`" * (max((len(run) for run in re.findall("`+", text)), default=0) + 1)
    if text.strip(" ") and (text[0] in "` " or text[-1] in "` "):
        text = f" {text} "
    return f"{fence}{text}{fence}"


def escape_leading_list_marker(text):
    """항목 내용이 목록 표지로 시작하면 표지의 마지막 글자를 backslash로 escape한다.

    뒤에 공백이 오는지와 관계없이 escape한다. `--`는 표지가 아니지만 바깥 `- `와 합쳐 thematic break가 되고,
    backslash 뒤 문장부호는 그 글자로 보이므로 렌더링 결과는 같다.
    """
    marker = LEADING_LIST_MARKER.match(text)
    if marker is None:
        return text
    end = marker.end() - 1
    return text[:end] + "\\" + text[end:]


def text_cell(value):
    """한계 문구를 목록 항목 하나 안의 일반 텍스트로 만든다.

    HTML로 해석될 문자는 엔티티로, 마크다운 구문 문자는 backslash로 바꾼다. 원래 있던 backslash는 ASCII
    문장부호 앞에서만 escape로 읽히므로(CommonMark 2.4) 그때만 겹쳐 써서 뒤 문자의 escape가 풀리지 않게 한다.
    safe()가 남기는 `\\u005f` 같은 표기는 그대로 둔다.

    앞 공백은 지운다. 4칸 이상이면 항목 안의 들여쓴 code block이 되고(CommonMark 4.4, 5.2), 문단의 앞 공백은
    렌더링에서도 사라지므로 보이는 글자는 같다. 그다음 첫머리의 목록 표지를 escape한다.
    """
    text = CONTROL_CHARACTERS.sub(" ", str(value)).lstrip(" ")
    escaped = []
    for index, character in enumerate(text):
        if character == "\\":
            escaped.append("\\\\" if text[index + 1:index + 2] in ASCII_PUNCTUATION else "\\")
        else:
            escaped.append(TEXT_ESCAPES.get(character, character))
    return escape_leading_list_marker("".join(escaped))


def diagnostics_of(report):
    """dead 리포트의 진단 목록을 꺼낸다. 형식이 다르면 렌더링 전에 ValueError로 멈춘다."""
    if not isinstance(report, dict) or report.get("diagnostics") is None or report.get("command") != "dead":
        raise ValueError("report is not a kartograph dead JSON document")
    diagnostics = report["diagnostics"]
    if not isinstance(diagnostics, list) or not all(isinstance(item, dict) for item in diagnostics):
        raise ValueError("diagnostics must be a list of objects")
    return diagnostics


def place_of(diagnostic):
    """진단 위치를 `path:line`으로 만든다. 경로가 없으면 `-`, 줄이 없거나 0이면 경로만 쓴다."""
    location = diagnostic.get("location")
    if location is None:
        return "-"
    if not isinstance(location, dict):
        raise ValueError("diagnostic location must be an object or null")
    path, line = location.get("path"), location.get("line")
    if line is not None and (isinstance(line, bool) or not isinstance(line, int)):
        raise ValueError("diagnostic line must be an integer or null")
    if path is None:
        return "-"
    return f"{path}:{line}" if line else path


def confidence_of(diagnostic):
    """문서화된 confidence 등급만 받는다. 생략되면 측정하지 않은 finding이므로 unmeasured다."""
    confidence = diagnostic.get("confidence", "unmeasured")
    if not isinstance(confidence, str) or confidence not in CONFIDENCE_TIERS:
        raise ValueError("diagnostic confidence must be one of " + ", ".join(CONFIDENCE_TIERS))
    return confidence + " (used only by tests)" if diagnostic.get("testOnly") else confidence


def count_of(report, key):
    """요약에 넣을 개수를 읽는다. bool이 아닌 0 이상의 정수만 받고, 생략되면 0이다."""
    value = report.get(key, 0)
    if isinstance(value, bool) or not isinstance(value, int) or value < 0:
        raise ValueError(key + " must be a non-negative integer")
    return value


def render_comment(report):
    """같은 리포트면 같은 코멘트 본문을 만든다. 절대경로는 리포트에 없는 그대로 둔다.

    형식이 다른 필드는 일부만 렌더링하지 않고 ValueError로 멈춘다. 오류 문구에는 입력 값을 넣지 않는다.
    """
    diagnostics = diagnostics_of(report)
    suppressed = count_of(report, "suppressedCount")
    expired = count_of(report, "expiredSuppressions")
    limitations = report.get("limitations") or []
    if not isinstance(limitations, list):
        raise ValueError("limitations must be a list")

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
            place = code_cell(place_of(diagnostic))
            lines.append(f"| {place} | {code_cell(diagnostic.get('nodeId', ''))} | {confidence_of(diagnostic)} |")

    summary = [f"{count} finding(s) reported"]
    if suppressed:
        summary.append(f"{suppressed} suppressed by baseline or suppress entries")
    if expired:
        summary.append(f"{expired} suppression(s) expired")
    lines.append("")
    lines.append(", ".join(summary) + ". Findings are reachability facts, not deletion approvals.")

    if limitations:
        lines.append("")
        lines.append("<details>")
        lines.append("<summary>Analysis limitations</summary>")
        lines.append("")
        for limitation in limitations:
            lines.append(f"- {text_cell(limitation)}")
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
