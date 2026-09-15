"""dead 리포트 → PR 코멘트 렌더러의 결정성과 실패 경계를 검증한다."""

import json
from pathlib import Path
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


def run_with(arguments, payload):
    return subprocess.run(
        [sys.executable, str(SCRIPT), *arguments],
        input=payload,
        capture_output=True,
        text=True,
        timeout=30,
    )


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
