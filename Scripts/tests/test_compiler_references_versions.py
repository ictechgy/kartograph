"""compiler-references 실험 collector의 Kotlin 버전이 루트 plugin과 같다는 README 불변식을 빌드 전에 확인한다."""
import importlib.util
from pathlib import Path
import tempfile
import traceback
import unittest

ROOT = Path(__file__).resolve().parents[2]
SPEC = importlib.util.spec_from_file_location("compiler_references", ROOT / "experiments/compiler-references/run.py")
MODULE = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(MODULE)


def write_builds(root, root_plugin, plugin, compiler):
    """루트와 collector build script만 가진 최소 저장소 배치를 만든다."""
    (root / "build.gradle.kts").write_text(f'plugins {{\n    kotlin("jvm") version "{root_plugin}" apply false\n}}\n')
    collector = root / "experiments/compiler-references/collector"
    collector.mkdir(parents=True)
    (collector / "build.gradle.kts").write_text(
        f'plugins {{ kotlin("jvm") version "{plugin}" }}\nrepositories {{ mavenCentral() }}\n'
        f'dependencies {{ compileOnly("org.jetbrains.kotlin:kotlin-compiler-embeddable:{compiler}") }}\n')
    return collector.parent


class CompilerReferenceVersionTest(unittest.TestCase):
    def test_repository_collector_matches_the_root_kotlin_plugin(self):
        # collector는 offline으로 빌드되어 루트 빌드가 데운 캐시만 쓴다. 루트 Kotlin을 올리면 여기서 먼저 실패한다.
        self.assertRegex(MODULE.collector_kotlin_version(), r"^[0-9]+\.[0-9]+\.[0-9]+$")

    def test_mismatched_plugin_or_compiler_fails_before_gradle_with_a_clear_message(self):
        for label, plugin, compiler in (("plugin", "2.4.20", "2.4.21"), ("compiler", "2.4.21", "2.4.20"),
                                        ("both", "2.4.20", "2.4.20")):
            with self.subTest(label=label), tempfile.TemporaryDirectory() as temporary:
                here = write_builds(Path(temporary), "2.4.21", plugin, compiler)
                with self.assertRaises(RuntimeError) as raised:
                    MODULE.collector_kotlin_version(Path(temporary), here)
                message = str(raised.exception)
                for expected in (f"plugin {plugin}", f"compiler {compiler}", "root Kotlin JVM plugin 2.4.21",
                                 "experiments/compiler-references/collector/build.gradle.kts"):
                    self.assertIn(expected, message)
                self.assertNotIn(temporary, message)
        with tempfile.TemporaryDirectory() as temporary:
            here = write_builds(Path(temporary), "2.4.21", "2.4.21", "2.4.21")
            self.assertEqual("2.4.21", MODULE.collector_kotlin_version(Path(temporary), here))

    def test_missing_or_repeated_version_declarations_are_rejected(self):
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            here = write_builds(root, "2.4.21", "2.4.21", "2.4.21")
            collector = here / "collector/build.gradle.kts"
            original = collector.read_text()
            for label, text in (("no-compiler", original.replace("kotlin-compiler-embeddable", "kotlin-stdlib")),
                                ("two-plugins", original + 'plugins { kotlin("jvm") version "2.4.21" }\n')):
                with self.subTest(label=label):
                    collector.write_text(text)
                    with self.assertRaisesRegex(RuntimeError, "expected one"):
                        MODULE.collector_kotlin_version(root, here)
            collector.write_text(original)
            (root / "build.gradle.kts").write_text("plugins {}\n")
            with self.assertRaisesRegex(RuntimeError, "expected one Kotlin JVM plugin version in build.gradle.kts"):
                MODULE.collector_kotlin_version(root, here)

    def test_missing_build_files_name_repository_relative_paths_only(self):
        # 읽기 실패가 개인 절대경로를 담은 OSError로 새어 나가지 않고, 저장소 상대 경로만 남긴다.
        for missing in ("build.gradle.kts", "experiments/compiler-references/collector/build.gradle.kts"):
            with self.subTest(missing=missing), tempfile.TemporaryDirectory() as temporary:
                root = Path(temporary)
                here = write_builds(root, "2.4.21", "2.4.21", "2.4.21")
                (root / missing).unlink()
                with self.assertRaises(RuntimeError) as raised:
                    MODULE.collector_kotlin_version(root, here)
                self.assertIn(f"cannot read {missing}", str(raised.exception))
                rendered = "".join(traceback.format_exception(raised.exception))
                self.assertNotIn(temporary, rendered)
                self.assertNotIn(str(root.resolve()), rendered)

    def test_comments_neither_count_nor_mask_version_declarations(self):
        real_plugin = 'plugins { kotlin("jvm") version "2.4.21" }\n'
        real_compiler = 'dependencies { compileOnly("org.jetbrains.kotlin:kotlin-compiler-embeddable:2.4.21") }\n'
        stale = ('// plugins { kotlin("jvm") version "2.4.20" }\n'
                 '/* compileOnly("org.jetbrains.kotlin:kotlin-compiler-embeddable:2.4.20") */\n')
        accepted = {
            "commented-stale-lines": stale + real_plugin + real_compiler,
            "nested-block-comment": ('/* outer /* inner */ kotlin("jvm") version "2.4.20"\n'
                                     '"org.jetbrains.kotlin:kotlin-compiler-embeddable:2.4.20" */\n') + real_plugin + real_compiler,
            # 문자열 안의 `//`·`/*`는 주석이 아니다. 같은 줄의 실제 선언을 지우지 않고, 뒤의 진짜 주석만 뺀다.
            "comment-markers-inside-strings": (
                'tasks.withType<Delete>().configureEach { delete(fileTree("build") { include("**/*.tmp") }) }\n' + real_plugin +
                'repositories { maven { url = uri("https://example.invalid/maven") } }; ' + real_compiler.rstrip("\n") +
                ' // kotlin("jvm") version "2.4.20"\n'),
        }
        for label, collector in accepted.items():
            with self.subTest(label=label), tempfile.TemporaryDirectory() as temporary:
                root = Path(temporary)
                here = write_builds(root, "2.4.21", "2.4.21", "2.4.21")
                (here / "collector/build.gradle.kts").write_text(collector)
                self.assertEqual("2.4.21", MODULE.collector_kotlin_version(root, here))
        # 실제 선언이 변수 형식이면 주석 속 따옴표 형식이 대신 통과하지 않고 "found 0"으로 실패한다.
        variable = ('val kotlinVersion = "2.4.21"\nplugins { kotlin("jvm") version kotlinVersion }\n'
                    'dependencies { compileOnly(kotlin("compiler-embeddable", kotlinVersion)) }\n')
        matching = stale.replace("2.4.20", "2.4.21")
        rejected = {
            "variable-form-behind-stale-comments": (stale + variable, "Kotlin JVM plugin version"),
            # 주석 속 따옴표 선언이 루트와 같은 버전이면 c10be0e는 그 주석으로 통과했다.
            "variable-form-behind-matching-comments": (matching + variable, "Kotlin JVM plugin version"),
            "unterminated-block-comment": (real_plugin + "/* " + real_compiler, "kotlin-compiler-embeddable version"),
        }
        for label, (collector, missing) in rejected.items():
            with self.subTest(label=label), tempfile.TemporaryDirectory() as temporary:
                root = Path(temporary)
                here = write_builds(root, "2.4.21", "2.4.21", "2.4.21")
                (here / "collector/build.gradle.kts").write_text(collector)
                with self.assertRaisesRegex(RuntimeError, f"expected one {missing} in "
                                                          "experiments/compiler-references/collector/build.gradle.kts, found 0"):
                    MODULE.collector_kotlin_version(root, here)
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            here = write_builds(root, "2.4.21", "2.4.21", "2.4.21")
            (root / "build.gradle.kts").write_text('// kotlin("jvm") version "2.4.21"\nplugins { kotlin("jvm") version libs.versions.kotlin }\n')
            with self.assertRaisesRegex(RuntimeError, "expected one Kotlin JVM plugin version in build.gradle.kts, found 0"):
                MODULE.collector_kotlin_version(root, here)

    def test_comment_stripping_follows_strings_templates_and_raw_strings(self):
        backslash = chr(92)
        source = ('val a = "${providers.gradleProperty("x").get()} // kept" // dropped\n'
                  'val b = """raw // kept /* kept */""" /* dropped /* nested */ dropped */\n'
                  'val c = "escaped ' + backslash + '" // kept" // dropped\n'
                  'val e = "${"' + backslash + '""} // kept" // dropped\n'
                  "val f = '\"' // dropped\n"
                  'val d = 1 /* unterminated')
        expected = ('val a = "${providers.gradleProperty("x").get()} // kept" \n'
                    'val b = """raw // kept /* kept */"""  \n'
                    'val c = "escaped ' + backslash + '" // kept" \n'
                    'val e = "${"' + backslash + '""} // kept" \n'
                    "val f = '\"' \n"
                    'val d = 1  ')
        self.assertEqual(expected, MODULE.without_kotlin_comments(source))


if __name__ == "__main__":
    unittest.main()
