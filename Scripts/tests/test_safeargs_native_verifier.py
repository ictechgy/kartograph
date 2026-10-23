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
        self.put('observations/gradle-result.properties', 'buildResult=succeeded\nelapsedNanos=12345\noutputTruncated=false\ntaskOutcomesTruncated=false\ntimingScope=TestKit build; excludes external tool setup\n')
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
            mapping = json.dumps([{"navFile": nav, "javaFiles": ["p.StartFragmentArgs", "p.EndFragmentArgs", "p.StartFragmentDirections"]}])
            headers = {}
            for name in gate.CLASSES:
                owner = name.removesuffix(".class")
                outer = owner.split("$")[0] if "$" in owner else None
                headers[name] = {"owner": owner, "sourceFile": (outer or owner).split("/")[-1] + ".java",
                                 "innerOwner": outer, "enclosingOwner": None, "sha256": "a" * 64}
            self.put(f"observations/{variant}.json", json.dumps({"providerDescriptor": descriptor, "providerSha256": gate.digest(descriptor.encode()),
                "navigation": {nav: gate.digest(self.files["fixture/" + nav])}, "compilerSources": sources, "generated": generated,
                "generatedSourceTexts": texts, "compiled": {name: "a" * 64 for name in gate.CLASSES},
                "mapping": {"path": f"build/mapping/{variant}/file_mappings.json", "text": mapping, "sha256": gate.digest(mapping.encode())},
                "profile": {"id": "gradle971-agp932", "gradle": "9.7.1", "agp": "9.3.2", "java": 17, "javaRuntime": "17.fixture", "javaVendor": "fixture"}, "generatorArtifacts": {"plugin.jar": gate.profiles.PLUGIN_SHA, "generator.jar": gate.profiles.GENERATOR_SHA}, "classHeaders": headers,
                "generatorActionTypes": ["org.gradle.api.internal.project.taskfactory.IncrementalTaskAction"],
                "generatorActionArtifacts": {"gradle-core.jar": gate.profiles.PROFILES["gradle971-agp932"][2]}}))
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

    def test_observed_profile_and_action_are_required(self):
        for key, wrong in [('profile', None), ('profile', {'id': 'gradle813-agp813-java'}),
                           ('generatorActionArtifacts', {'gradle-core-8.13.jar': '0' * 64})]:
            with self.subTest(key=key, wrong=wrong):
                self.setUp()
                self.change_observation(lambda value: value.update({key: wrong}))
                with self.assertRaisesRegex(ValueError, 'profile|artifact'):
                    gate.verify(self.xml, self.evidence)

    def test_813_requires_matching_actual_versions_and_action(self):
        for variant in ('Debug', 'Release'):
            name = f'observations/{variant}.json'
            value = json.loads(self.files[name])
            value['profile'].update(id='gradle813-agp813-java', gradle='8.13', agp='8.13.0')
            value['generatorActionArtifacts'] = {'gradle-core-8.13.jar': gate.profiles.PROFILES['gradle813-agp813-java'][2]}
            self.put(name, json.dumps(value))
        self.manifest()
        self.assertEqual('passed', gate.verify(self.xml, self.evidence, 'gradle813-agp813-java')['status'])
        with self.assertRaisesRegex(ValueError, 'profile'):
            gate.verify(self.xml, self.evidence)
        with self.assertRaisesRegex(ValueError, 'unknown'):
            gate.verify(self.xml, self.evidence, 'latest')

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

    def test_mapping_for_other_variant_is_rejected(self):
        def change(value):
            text = value["mapping"]["text"].replace("src/debug/", "src/main/")
            value["mapping"].update(text=text, sha256=gate.digest(text.encode()))
        self.change_observation(change)
        with self.assertRaisesRegex(ValueError, "per-input generator mapping"):
            gate.verify(self.xml, self.evidence)

    def test_mapping_bytes_changed_without_digest_is_rejected(self):
        self.change_observation(lambda value: value["mapping"].update(text="[]"))
        with self.assertRaisesRegex(ValueError, "mapping bytes"):
            gate.verify(self.xml, self.evidence)

    def test_same_basename_wrong_owner_is_rejected(self):
        self.change_observation(lambda value: value["classHeaders"]["other/StartFragmentArgs.class"].update(owner="p/StartFragmentArgs"))
        with self.assertRaisesRegex(ValueError, "header identity"):
            gate.verify(self.xml, self.evidence)

    def test_missing_sourcefile_is_not_a_proved_join(self):
        self.change_observation(lambda value: value["classHeaders"]["p/StartFragmentArgs.class"].update(sourceFile=None))
        with self.assertRaisesRegex(ValueError, "header identity"):
            gate.verify(self.xml, self.evidence)

    def test_nested_class_header_bytes_must_match(self):
        self.change_observation(lambda value: value["classHeaders"]["p/StartFragmentDirections$Idle.class"].update(sha256="b" * 64))
        with self.assertRaisesRegex(ValueError, "header bytes"):
            gate.verify(self.xml, self.evidence)

    def test_executing_artifacts_are_required(self):
        self.change_observation(lambda value: value.update(generatorArtifacts={}))
        with self.assertRaisesRegex(ValueError, "artifact evidence"):
            gate.verify(self.xml, self.evidence)

    def test_custom_action_is_not_default_generator_observation(self):
        self.change_observation(lambda value: value["generatorActionTypes"].append("org.gradle.api.internal.AbstractTask$ClosureTaskAction"))
        with self.assertRaisesRegex(ValueError, "default generator action"):
            gate.verify(self.xml, self.evidence)

    def test_two_individually_bounded_variants_must_fit_one_global_log(self):
        for variant in ("Debug", "Release"):
            name = f"observations/{variant}.json"
            value = json.loads(self.files[name])
            providers = json.loads(value["providerDescriptor"])
            providers["compiledClassRoot"] = "build/classes/" + "x" * 6500
            value["providerDescriptor"] = json.dumps(providers)
            value["providerSha256"] = gate.digest(value["providerDescriptor"].encode())
            self.put(name, json.dumps(value))
        self.manifest()
        result = gate.verify(self.xml, self.evidence)
        for variant in result["variants"]:
            self.assertLess(len(json.dumps(variant["generationObservation"]).encode()), 16 * 1024)
        with self.assertRaisesRegex(ValueError, "combined log budget"):
            gate.bounded_report(result)

    def test_log_budget_counts_unicode_utf8_bytes_and_final_newline(self):
        result = {"text": "\U0001f600" * 4080}
        rendered = gate.bounded_report(result)
        self.assertLessEqual(len((rendered + "\n").encode()), 16 * 1024)
        result["text"] += "\U0001f600" * 16
        with self.assertRaisesRegex(ValueError, "combined log budget"):
            gate.bounded_report(result)


if __name__ == "__main__":
    unittest.main()
