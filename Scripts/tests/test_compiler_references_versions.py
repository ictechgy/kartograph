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


if __name__ == "__main__":
    unittest.main()
