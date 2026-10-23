"""보존/실행 gate 자체의 음성 회귀다. Android 실행 증거로 세지 않는다."""
import importlib.util
import json
from pathlib import Path
import tempfile
import unittest
import xml.etree.ElementTree as ET

SCRIPT = Path(__file__).resolve().parents[1] / "verify-safeargs-native-test.py"
spec = importlib.util.spec_from_file_location("safe_args_gate", SCRIPT)
gate = importlib.util.module_from_spec(spec)
spec.loader.exec_module(gate)


class SafeArgsNativeVerifierTest(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        self.root = Path(self.temp.name)
        self.xml = self.root / "suite.xml"
        self.evidence = self.root / "capture"
        self.evidence.mkdir()
        suite = ET.Element("testsuite", name=gate.SUITE, tests="1", skipped="0", failures="0", errors="0")
        ET.SubElement(suite, "testcase", name=gate.CASE, classname=gate.SUITE)
        ET.SubElement(suite, "system-out").text = "\n".join(
            f"safe-args-native complete={variant} freshness=matched controls=checked" for variant in ("Debug", "Release"))
        self.xml.write_bytes(ET.tostring(suite))
        self.files = {}
        for prefix in ("debug", "main"):
            self.put(f"fixture/src/{prefix}/res/navigation/graph.xml", prefix)
        for name in ("p/ManualArgs.java", "p/ManualDirections.java", "other/StartFragmentArgs.java"):
            self.put("fixture/src/main/java/" + name, name)
        outcomes = []
        for variant, seed in (("Debug", 11), ("Release", 22)):
            for task in (f"generateSafeArgs{variant}", f"compile{variant}JavaWithJavac", f"observeSafeArgs{variant}", f"kartographSnapshot{variant}"):
                outcomes.append(f":{task}=SUCCESS")
            providers = {"applicationId": "p.safeargs", "rFilePackage": "p", "useAndroidX": True, "generateKotlin": False,
                         "generatorTask": f":generateSafeArgs{variant}", "compilerTask": f":compile{variant}JavaWithJavac",
                         "generatedSourceRoot": f"build/generated/{variant}", "compiledClassRoot": f"build/classes/{variant}"}
            descriptor = json.dumps(providers)
            texts = {"StartFragmentArgs.java": f'put("seed", {seed})', "EndFragmentArgs.java": "end", "StartFragmentDirections.java": "directions"}
            generated = {"p/" + name: gate.digest(text.encode()) for name, text in texts.items()}
            nav = f"src/{'debug' if variant == 'Debug' else 'main'}/res/navigation/graph.xml"
            sources = {providers["generatedSourceRoot"] + "/" + name: sha for name, sha in generated.items()}
            sources.update({name.removeprefix("fixture/"): gate.digest(raw) for name, raw in self.files.items() if name.endswith(".java")})
            self.put(f"observations/{variant}.json", json.dumps({"providerDescriptor": descriptor, "providerSha256": gate.digest(descriptor.encode()),
                "navigation": {nav: gate.digest(self.files["fixture/" + nav])}, "compilerSources": sources, "generated": generated,
                "generatedSourceTexts": texts, "compiled": {name: "a" * 64 for name in gate.CLASSES}}))
            self.put(f"snapshots/{variant.lower()}-snapshot.json", "{}")
        self.put("observations/task-outcomes.txt", "\n".join(outcomes))
        self.manifest()

    def put(self, name, text):
        path = self.evidence / name
        path.parent.mkdir(parents=True, exist_ok=True)
        raw = text.encode()
        path.write_bytes(raw)
        self.files[name] = raw

    def manifest(self):
        (self.evidence / "capture.tsv").write_text("\n".join(
            f"{gate.digest(raw)}\t{len(raw)}\t{name}" for name, raw in sorted(self.files.items())) + "\n", encoding="utf-8")

    def change_observation(self, change):
        name = "observations/Debug.json"
        value = json.loads(self.files[name])
        change(value)
        self.put(name, json.dumps(value))
        self.manifest()

    def test_synthetic_complete_receipt_schema_passes(self):
        self.assertEqual("passed", gate.verify(self.xml, self.evidence)["status"])

    def test_skipped_execution_is_rejected(self):
        suite = ET.fromstring(self.xml.read_bytes())
        suite.set("skipped", "1")
        self.xml.write_bytes(ET.tostring(suite))
        with self.assertRaisesRegex(ValueError, "skipped or failed"):
            gate.verify(self.xml, self.evidence)

    def test_missing_completion_is_rejected(self):
        suite = ET.fromstring(self.xml.read_bytes())
        suite.find("system-out").text = ""
        self.xml.write_bytes(ET.tostring(suite))
        with self.assertRaisesRegex(ValueError, "completion marker"):
            gate.verify(self.xml, self.evidence)

    def test_captured_bytes_mismatch_is_rejected(self):
        (self.evidence / "observations/Debug.json").write_text("tampered", encoding="utf-8")
        with self.assertRaisesRegex(ValueError, "mismatch"):
            gate.verify(self.xml, self.evidence)

    def test_generated_source_missing_from_compiler_is_rejected(self):
        self.change_observation(lambda value: value["compilerSources"].pop("build/generated/Debug/p/StartFragmentArgs.java"))
        with self.assertRaisesRegex(ValueError, "compiler inputs"):
            gate.verify(self.xml, self.evidence)

    def test_handwritten_same_basename_missing_is_rejected(self):
        self.change_observation(lambda value: value["compiled"].pop("other/StartFragmentArgs.class"))
        with self.assertRaisesRegex(ValueError, "mixed compiled-root"):
            gate.verify(self.xml, self.evidence)

    def test_wrong_variant_provider_is_rejected(self):
        def change(value):
            providers = json.loads(value["providerDescriptor"])
            providers["generatorTask"] = ":generateSafeArgsRelease"
            value["providerDescriptor"] = json.dumps(providers)
            value["providerSha256"] = gate.digest(value["providerDescriptor"].encode())
        self.change_observation(change)
        with self.assertRaisesRegex(ValueError, "wrong variant"):
            gate.verify(self.xml, self.evidence)

    def test_cached_outcome_is_not_cold_execution(self):
        name = "observations/task-outcomes.txt"
        self.put(name, self.files[name].decode().replace(":generateSafeArgsDebug=SUCCESS", ":generateSafeArgsDebug=FROM_CACHE"))
        self.manifest()
        with self.assertRaisesRegex(ValueError, "cold task"):
            gate.verify(self.xml, self.evidence)

    def test_provider_digest_mismatch_is_rejected(self):
        self.change_observation(lambda value: value.update(providerSha256="0" * 64))
        with self.assertRaisesRegex(ValueError, "provider descriptor hash"):
            gate.verify(self.xml, self.evidence)


if __name__ == "__main__":
    unittest.main()
