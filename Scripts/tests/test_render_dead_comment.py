"""dead 리포트 → PR 코멘트 렌더러의 결정성과 실패 경계를 검증한다."""

import json
from pathlib import Path
import re
import subprocess
import sys
import tempfile
import unittest

SCRIPT = Path(__file__).resolve().parents[1] / "render-dead-comment.py"

REPORT = {
    "command": "dead",
    "diagnostics": [
        {
            "location": {"column": 1, "line": 3, "path": "src/A.kt"},
            "message": "class:a/Unused is unreachable",
            "nodeId": "class:a/Unused",
            "ruleId": "dead",
            "state": "unreachable",
            "confidence": "static",
        },
        {
            "location": {"column": 2, "line": 7, "path": "src/Z|Weird.kt"},
            "message": "class:z/Unused is unreachable (used only by tests)",
            "nodeId": "class:z/Unused",
            "ruleId": "dead",
            "state": "unreachable",
            "testOnly": True,
            "confidence": "needs-runtime-review",
        },
    ],
    "limitations": [
        "reflection strings limitation text",
        "dynamic registration limitation text",
    ],
    "suppressedCount": 4,
    "expiredSuppressions": 1,
    "tool": "kartograph",
    "version": "0.9.0",
}

# kartograph가 실제로 내는 형태다. 위치 없는 finding, 생략된 confidence, external-retentions 안내의
# `document(s)`, 기록 표시용 safe()가 남기는 `\u005f` 같은 backslash 표기를 포함한다.
KARTOGRAPH_REPORT = {
    "command": "dead",
    "diagnostics": [
        {"location": None, "message": "class:a/NoSource is unreachable", "nodeId": "class:a/NoSource",
         "ruleId": "dead", "state": "unreachable"},
        {"location": {"column": 1, "line": 12, "path": "Observed.kt"}, "message": "class:b/Observed is unreachable",
         "nodeId": "class:b/Observed", "ruleId": "dead", "state": "unreachable", "confidence": "runtime-observed"},
        {"location": {"column": 1, "line": 0, "path": "Zero.kt"}, "message": "class:c/Zero is unreachable",
         "nodeId": "class:c/Zero$Inner", "ruleId": "dead", "state": "unreachable", "testOnly": True,
         "confidence": "unmeasured"},
    ],
    "unmatchedKeepRules": [],
    "inputHints": [],
    "limitations": [
        "compile-time constant owners are retained because inlined use sites are unavailable",
        'external-retentions limitation: {"message":"Expo\\u005fmodule \\u005c"x\\u005c" not resolved",'
        '"platform":"android","target":null,"tool":"isthmus"}… (3 characters omitted)',
        "external-retentions: 1 document(s) have captured capture context",
    ],
    "suppressedCount": 0,
    "tool": "kartograph",
    "version": "0.24.0",
}

# 수정 전 렌더러가 만든 본문이다. 정상 입력의 출력은 바이트 단위로 유지한다.
REPORT_BODY = """## kartograph dead — 2 finding(s)

| Location | Declaration | Confidence |
|---|---|---|
| `src/A.kt:3` | `class:a/Unused` | static |
| `src/Z\\|Weird.kt:7` | `class:z/Unused` | needs-runtime-review (used only by tests) |

2 finding(s) reported, 4 suppressed by baseline or suppress entries, 1 suppression(s) expired. \
Findings are reachability facts, not deletion approvals.

<details>
<summary>Analysis limitations</summary>

- reflection strings limitation text
- dynamic registration limitation text

</details>
"""

KARTOGRAPH_BODY = """## kartograph dead — 3 finding(s)

| Location | Declaration | Confidence |
|---|---|---|
| `-` | `class:a/NoSource` | unmeasured |
| `Observed.kt:12` | `class:b/Observed` | runtime-observed |
| `Zero.kt` | `class:c/Zero$Inner` | unmeasured (used only by tests) |

3 finding(s) reported. Findings are reachability facts, not deletion approvals.

<details>
<summary>Analysis limitations</summary>

- compile-time constant owners are retained because inlined use sites are unavailable
- external-retentions limitation: {"message":"Expo\\u005fmodule \\u005c"x\\u005c" not resolved",\
"platform":"android","target":null,"tool":"isthmus"}… (3 characters omitted)
- external-retentions: 1 document(s) have captured capture context

</details>
"""


def finding(path, line=1, node="class:a/Unused", confidence="static"):
    """위치 하나만 다른 dead finding을 만든다."""
    return {"location": {"column": 1, "line": line, "path": path}, "nodeId": node, "confidence": confidence}


def report_with(diagnostics, limitations=(), **fields):
    """기본 필드에 주어진 진단·한계·필드를 덧씌운 dead 리포트를 만든다."""
    return {"command": "dead", "diagnostics": list(diagnostics), "limitations": list(limitations),
            "suppressedCount": 0, **fields}


def table_rows(body):
    """표 머리·구분 줄을 포함한 표 행만 돌려준다."""
    return [line for line in body.split("\n") if line.startswith("|")]


def cell_delimiters(row):
    """두 GFM 판정 방식에서 셀 구분자로 읽히는 `|` 수를 센다.

    cmark-gfm은 바로 앞이 backslash가 아닌 `|`를, marked는 앞의 backslash 연속이 짝수인 `|`를 구분자로 본다.
    """
    strict = lenient = 0
    for index, character in enumerate(row):
        if character == "|":
            backslashes = len(row[:index]) - len(row[:index].rstrip("\\"))
            strict += backslashes == 0
            lenient += backslashes % 2 == 0
    return strict, lenient


# CommonMark 4.1의 thematic break 줄이다. `- ---`처럼 목록 항목 줄 전체가 여기에 맞으면 항목 대신 가로줄이 된다.
THEMATIC_BREAK = re.compile(r" {0,3}([-*_])(?:[ \t]*\1){2,}[ \t]*")
# 목록 항목 내용 첫머리에서 들여쓴 code block(CommonMark 4.4)이나 중첩 목록(5.2)을 여는 형태다.
NESTED_BLOCK_START = re.compile(r" {4}| {0,3}(?:[-+*]|[0-9]{1,9}[.)])(?:[ \t]|$)")


def opens_another_block(item):
    """`- `로 시작하는 목록 항목 줄이 가로줄이 되거나 항목 안에 다른 블록을 여는지 본다."""
    return bool(THEMATIC_BREAK.fullmatch(item) or NESTED_BLOCK_START.match(item[2:]))


def run_with(arguments, payload):
    return subprocess.run(
        [sys.executable, str(SCRIPT), *arguments],
        input=payload,
        capture_output=True,
        text=True,
        timeout=30,
    )


def render_bytes(report, timeout=30):
    """stdout을 줄바꿈 변환 없이 받는다. text 모드는 CR을 LF로 바꿔 남은 CR을 가린다. timeout은 실행 상한(초)이다."""
    result = subprocess.run([sys.executable, str(SCRIPT)], input=json.dumps(report).encode("utf-8"),
                            capture_output=True, timeout=timeout)
    return result.returncode, result.stdout.decode("utf-8"), result.stderr.decode("utf-8")


class RenderDeadCommentTest(unittest.TestCase):
    def test_renders_deterministic_markdown_table(self):
        payload = json.dumps(REPORT)

        first = run_with([], payload)
        second = run_with([], payload)

        self.assertEqual(0, first.returncode)
        self.assertEqual(first.stdout, second.stdout)
        self.assertIn("## kartograph dead — 2 finding(s)", first.stdout)
        self.assertIn("| Location | Declaration | Confidence |", first.stdout)
        self.assertIn("| `src/A.kt:3` | `class:a/Unused` | static |", first.stdout)
        self.assertIn("`src/Z\\|Weird.kt:7`", first.stdout)
        self.assertIn("needs-runtime-review (used only by tests)", first.stdout)
        self.assertIn("2 finding(s) reported, 4 suppressed by baseline or suppress entries, 1 suppression(s) expired", first.stdout)
        self.assertIn("<details>", first.stdout)
        self.assertIn("- reflection strings limitation text", first.stdout)
        self.assertIn("not deletion approvals", first.stdout)

    def test_normal_reports_keep_their_exact_bytes(self):
        for report, body in ((REPORT, REPORT_BODY), (KARTOGRAPH_REPORT, KARTOGRAPH_BODY)):
            with self.subTest(version=report["version"]):
                returncode, stdout, stderr = render_bytes(report)
                self.assertEqual(0, returncode, stderr)
                self.assertEqual(body, stdout)

    def test_control_characters_in_paths_cannot_end_their_table_row(self):
        paths = ["A.java\r\r## kartograph dead — no unreachable declarations\r\r<!--.java",
                 "B\u2028C\u2029D\u0085E.kt", "F\nG\x00H\x1bI\x7fJ\tK.kt"]
        returncode, body, stderr = render_bytes(report_with(finding(path, line=index + 1) for index, path in enumerate(paths)))

        self.assertEqual(0, returncode, stderr)
        self.assertFalse(set(body) & set("\r\x00\x1b\x7f\t\u2028\u2029\u0085"))
        rows = table_rows(body)[2:]
        self.assertEqual([
            "| `A.java  ## kartograph dead — no unreachable declarations  <!--.java:1` | `class:a/Unused` | static |",
            "| `B C D E.kt:2` | `class:a/Unused` | static |",
            "| `F G H I J K.kt:3` | `class:a/Unused` | static |",
        ], rows)
        for row in rows:
            self.assertEqual((4, 4), cell_delimiters(row), row)
        self.assertIn("\n\n3 finding(s) reported. Findings are", body)

    def test_backticks_in_values_cannot_close_their_code_span(self):
        diagnostics = [
            finding("x`[click](https://evil.example) @someone`z.java"),
            finding("`lead`", line=2),
            finding(" spaced ", line=None, node="class:a/Odd```Name"),
            finding("a\\|b|c\\", line=4, node="class:a/X\\|[click](https://evil.example)|"),
        ]
        returncode, body, stderr = render_bytes(report_with(diagnostics))

        self.assertEqual(0, returncode, stderr)
        rows = table_rows(body)[2:]
        self.assertEqual([
            "| ``x`[click](https://evil.example) @someone`z.java:1`` | `class:a/Unused` | static |",
            "| `` `lead`:2 `` | `class:a/Unused` | static |",
            "| `  spaced  ` | ````class:a/Odd```Name```` | static |",
            r"| `a\\\|b\|c\:4` | `class:a/X\\\|[click](https://evil.example)\|` | static |",
        ], rows)
        for row in rows:
            self.assertEqual((4, 4), cell_delimiters(row), row)

    def test_long_backslash_runs_render_in_linear_time(self):
        # JVM 이름은 backslash를 허용하므로 bytecode에서 온 nodeId에도 긴 연속이 올 수 있다. 연속 안의 위치마다
        # `|` 매치를 다시 시도하면 `|`로 끝나지 않는 연속에서 이차 시간이 되어 이 상한을 크게 넘는다.
        run = "\\" * 200_000
        report = report_with([finding(run, line=None, node="class:a/" + run + "|")])
        try:
            returncode, body, stderr = render_bytes(report, timeout=10)
        except subprocess.TimeoutExpired:
            self.fail("a long backslash run made comment rendering superlinear")

        self.assertEqual(0, returncode, stderr)
        rows = table_rows(body)[2:]
        self.assertEqual([f"| `{run}` | `class:a/{run}\\|` | static |"], rows)
        self.assertEqual((4, 4), cell_delimiters(rows[0]))

    def test_limitations_stay_plain_text_inside_their_list_item(self):
        limitations = [
            "<sub>tiny</sub> [l](https://evil.example)",
            "</details>\n\n## kartograph dead — no unreachable declarations",
            "![x](https://evil.example/p.png) **b** _i_ ~~s~~ `c` @someone &amp; \\[x\\](y)",
        ]
        returncode, body, stderr = render_bytes(report_with([], limitations))

        self.assertEqual(0, returncode, stderr)
        self.assertEqual([
            "- &lt;sub&gt;tiny&lt;/sub&gt; \\[l\\](https://evil.example)",
            "- &lt;/details&gt;  \\#\\# kartograph dead — no unreachable declarations",
            "- !\\[x\\](https://evil.example/p.png) \\*\\*b\\*\\* \\_i\\_ \\~\\~s\\~\\~ \\`c\\` <span>@</span>someone "
            "&amp;amp; \\\\\\[x\\\\\\](y)",
        ], [line for line in body.split("\n") if line.startswith("- ")])
        self.assertTrue(body.endswith("\n\n</details>\n"))

    def test_limitations_cannot_open_blocks_at_the_start_of_their_item(self):
        limitations = ["---", "- - -", "--", "-", "+ plus", "+", "1. nested", "2) paren", "1.",
                       "  - lead", "   12. lead", "    code", "\t- tab"]
        # 렌더러는 탭을 공백으로 바꾼다. escape 없이 그대로 붙인 수정 전 출력은 모두 다른 블록을 연다.
        self.assertTrue(all(opens_another_block("- " + limitation.replace("\t", " ")) for limitation in limitations))
        returncode, body, stderr = render_bytes(report_with([], limitations))

        self.assertEqual(0, returncode, stderr)
        items = [line for line in body.split("\n") if line.startswith("- ")]
        self.assertEqual(["- \\---", "- \\- - -", "- \\--", "- \\-", "- \\+ plus", "- \\+", "- 1\\. nested",
                          "- 2\\) paren", "- 1\\.", "- \\- lead", "- 12\\. lead", "- code", "- \\- tab"], items)
        for item in items:
            self.assertFalse(opens_another_block(item), item)
        self.assertTrue(body.endswith("\n\n</details>\n"))

    def test_limitations_cannot_form_github_math_or_mentions(self):
        limitations = [
            "$\\Huge{FAKE}$",
            "x $a$, $$b$$ and $`c`$",
            "\\$d\\$ @someone @org/team a@b.example",
            'external-retentions source: {"class":"a.Foo$Bar","annotation":"@Keep"}',
        ]
        returncode, body, stderr = render_bytes(report_with([], limitations))

        self.assertEqual(0, returncode, stderr)
        items = [line for line in body.split("\n") if line.startswith("- ")]
        self.assertEqual([
            "- <span>$</span>\\Huge{FAKE}<span>$</span>",
            "- x <span>$</span>a<span>$</span>, <span>$</span><span>$</span>b<span>$</span><span>$</span> and "
            "<span>$</span>\\`c\\`<span>$</span>",
            "- \\\\<span>$</span>d\\\\<span>$</span> <span>@</span>someone <span>@</span>org/team "
            "a<span>@</span>b.example",
            '- external-retentions source: {"class":"a.Foo<span>$</span>Bar","annotation":"<span>@</span>Keep"}',
        ], items)
        for item in items:
            # GitHub은 `\$`·`&#36;`·`\@`도 수식·멘션으로 읽으므로 span 밖에 `$`·`@`가 남으면 안 된다.
            self.assertNotIn("$", item.replace("<span>$</span>", ""), item)
            self.assertNotIn("@", item.replace("<span>@</span>", ""), item)
        self.assertTrue(body.endswith("\n\n</details>\n"))

    def test_malformed_fields_fail_as_tool_errors_without_echoing_them(self):
        injected = "static\n\n## injected"
        cases = {
            "unknown confidence": report_with([finding("A.kt", confidence="STATIC")]),
            "multiline confidence": report_with([finding("A.kt", confidence=injected)]),
            "null confidence": report_with([finding("A.kt", confidence=None)]),
            "numeric confidence": report_with([finding("A.kt", confidence=3)]),
            "string suppressed count": report_with([], suppressedCount="4\n\n## injected"),
            "boolean suppressed count": report_with([], suppressedCount=True),
            "negative suppressed count": report_with([], suppressedCount=-1),
            "fractional expired count": report_with([], expiredSuppressions=1.5),
            "string expired count": report_with([], expiredSuppressions="1 ## injected"),
            "string diagnostics": {**report_with([]), "diagnostics": "## injected"},
            "scalar diagnostic": report_with([1]),
            "string location": report_with([{"location": "## injected", "nodeId": "class:a/A"}]),
            "string line": report_with([finding("A.kt", line="3\n## injected")]),
            "boolean line": report_with([finding("A.kt", line=True)]),
            "string limitations": {**report_with([]), "limitations": "## injected"},
            "array document": [report_with([])],
        }
        for name, report in cases.items():
            with self.subTest(name=name):
                returncode, stdout, stderr = render_bytes(report)
                self.assertEqual(2, returncode, stderr)
                self.assertEqual("", stdout)
                self.assertIn("unable to render a comment", stderr)
                self.assertNotIn("Traceback", stderr)
                self.assertNotIn("injected", stderr)

    def test_omits_empty_sections(self):
        report = {
            "command": "dead",
            "diagnostics": [],
            "limitations": [],
            "suppressedCount": 0,
            "tool": "kartograph",
            "version": "0.9.0",
        }

        result = run_with([], json.dumps(report))

        self.assertEqual(0, result.returncode)
        self.assertIn("## kartograph dead — no unreachable declarations", result.stdout)
        self.assertNotIn("| Location |", result.stdout)
        self.assertNotIn("<details>", result.stdout)
        self.assertIn("0 finding(s) reported.", result.stdout)

    def test_writes_output_file_from_report_path(self):
        with tempfile.TemporaryDirectory(prefix="kartograph-comment-") as temporary:
            root = Path(temporary)
            report = root / "dead.json"
            body = root / "comment.md"
            report.write_text(json.dumps(REPORT), encoding="utf-8")

            result = run_with(["--report", str(report), "--output", str(body)], "")

            self.assertEqual(0, result.returncode)
            self.assertEqual("", result.stdout)
            written = body.read_text(encoding="utf-8")
            self.assertIn("## kartograph dead — 2 finding(s)", written)

    def test_rejects_documents_that_are_not_dead_reports(self):
        result = run_with([], json.dumps({"command": "graph"}))

        self.assertEqual(2, result.returncode)
        self.assertIn("not a kartograph dead JSON document", result.stderr)
        self.assertEqual("", result.stdout)

        broken = run_with([], "{not json")
        self.assertEqual(2, broken.returncode)
        self.assertIn("unable to render a comment", broken.stderr)


if __name__ == "__main__":
    unittest.main()
