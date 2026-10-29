package dev.kartograph.cli

import dev.kartograph.core.AndroidResourceEvidence
import dev.kartograph.core.BuildWitness
import dev.kartograph.core.CodeGraph
import dev.kartograph.core.EdgeKind
import dev.kartograph.core.GeneratedDeclaration
import dev.kartograph.core.GenerationConsumptionObservation
import dev.kartograph.core.GenerationDigests
import dev.kartograph.core.GenerationEvidence
import dev.kartograph.core.GenerationOriginContext
import dev.kartograph.core.GenerationProviderBinding
import dev.kartograph.core.GenerationReceipt
import dev.kartograph.core.GenerationSource
import dev.kartograph.core.InputFingerprint
import dev.kartograph.core.NodeAttribute
import dev.kartograph.core.NodeId
import dev.kartograph.export.McpJsonCodec
import dev.kartograph.export.QuerySnapshot
import dev.kartograph.export.ExternalInputBindingsCodec
import dev.kartograph.export.QuerySnapshotCodec
import dev.kartograph.index.ContentFingerprint
import java.io.ByteArrayOutputStream
import java.io.PrintStream
import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest
import javax.tools.ToolProvider
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import org.junit.jupiter.api.io.TempDir

/** 모듈별 snapshot을 합친 그래프가 모듈 경계의 간선을 되살리고 신선도를 다시 검증할 수 있는지 확인한다. */
class SnapshotMergeCliTest {
    @Test
    fun `merge reindexes member class roots so cross-module edges and freshness survive`(@TempDir root: Path) {
        val fixture = Fixture(root)
        // 한 구성원은 call-site evidence가 없는 옛 문서여도 merge graph는 새로 캡처한 사실을 사용한다.
        val legacyApp = QuerySnapshotCodec.parse(Files.readString(fixture.appSnapshot)).copy(callSiteLinesCaptured = false)
        Files.writeString(fixture.appSnapshot, QuerySnapshotCodec.render(legacyApp))
        val merged = fixture.merge()
        assertEquals(0, merged.first, merged.second)
        assertContains(merged.second, "warning: merged snapshot includes unverified members")
        val snapshot = QuerySnapshotCodec.parse(merged.stdout)
        val screen = NodeId("method:app/Screen#render()I")
        val api = NodeId("method:core/Api#load()I")
        val call = snapshot.graph.edges.single { it.source == screen && it.target == api && it.kind == EdgeKind.CALL }
        assertTrue(call.callSiteLines.isNotEmpty())
        assertTrue(snapshot.callSiteLinesCaptured)
        assertTrue(snapshot.graph.edges.any { it.target == NodeId("class:core/Api") && it.source.value.startsWith("field:app/Screen#api") })
        assertEquals("aggregate:jvm", snapshot.scope)
        assertEquals(listOf(":app:jvm", ":core:jvm"), snapshot.provenance!!.memberScopes.sorted())
        assertTrue(snapshot.provenance!!.inputs.any { it.path.startsWith("core/") && it.role == "classes" })
        assertTrue(snapshot.limitations.contains(SnapshotAggregation.RETENTION_LIMITATION))
        assertFalse(merged.second.contains(root.toString()))

        // 모듈을 한 번에 캡처한 그래프와 같은 정점·간선을 만든다.
        val direct = run("snapshot", "--project", root.toString(), "--classes", fixture.appClasses.toString(),
            "--classes", fixture.coreClasses.toString())
        val single = QuerySnapshotCodec.parse(direct.stdout)
        assertEquals(single.graph.nodeIds, snapshot.graph.nodeIds)
        assertEquals(single.graph.edges, snapshot.graph.edges)
        // 다시 인덱싱한 콜백 사실도 경로를 옮기면서 잃지 않는다.
        assertTrue(snapshot.graph.callbackArguments.isNotEmpty() && snapshot.callbackFactsCaptured)
        assertEquals(single.graph.callbackArguments, snapshot.graph.callbackArguments)
        assertEquals(single.graph.parameterUses, snapshot.graph.parameterUses)
        assertEquals(single.graph.lambdaEscapes, snapshot.graph.lambdaEscapes)

        // 합친 로컬 연결로 외부 입력을 다시 찾는다. 수동 capture라 witness가 없다는 사실만 남는다.
        val output = root.resolve("merged.json").also { Files.writeString(it, merged.stdout) }
        val verified = run("verify-snapshot", "--graph-file", output.toString(), "--project", root.toString(),
            "--input-bindings", fixture.mergedBindings.toString())
        assertContains(verified.second, "missing-build-witness")
        assertFalse(verified.second.contains("missing-external-input"))
        assertFalse(Files.readString(fixture.mergedBindings).isBlank())

        // routes도 같은 연결을 받아 외부 입력 누락과 witness 누락을 구분한다.
        val routes = run("routes", "--role", "client", "--project", root.toString(), "--graph-file", output.toString())
        assertContains(routes.second, "missing-external-input")
        val bound = run("routes", "--role", "client", "--project", root.toString(), "--graph-file", output.toString(),
            "--input-bindings", fixture.mergedBindings.toString())
        assertEquals(0, bound.first, bound.second)
        assertFalse(bound.second.contains("missing-external-input"))
    }

    @Test
    fun `merge refuses stale or unlocatable members and requires a bindings output for external inputs`(@TempDir root: Path) {
        val fixture = Fixture(root)
        val withoutOutput = run("snapshot", "merge", "--project", root.toString(), *fixture.memberArguments())
        assertEquals(64, withoutOutput.first, withoutOutput.second)
        assertContains(withoutOutput.second, "--input-bindings-output")

        val unbound = run("snapshot", "merge", "--project", root.toString(),
            "--module", "core", "--graph-file", fixture.coreSnapshot.toString(),
            "--module", "app", "--graph-file", fixture.appSnapshot.toString(), "--input-bindings-output", fixture.mergedBindings.toString())
        assertEquals(2, unbound.first, unbound.second)
        assertContains(unbound.second, "missing-external-input")

        Files.writeString(fixture.coreClasses.resolve("extra.txt"), "changed")
        val stale = fixture.merge()
        assertEquals(2, stale.first, stale.second)
        assertContains(stale.second, "member :core:jvm is stale")
        assertFalse(stale.second.contains(root.toString()))
    }

    @Test
    fun `merge rejects invalid member groups`(@TempDir root: Path) {
        val fixture = Fixture(root)
        assertEquals(64, run("snapshot", "merge", "--project", root.toString(), "--module", "core",
            "--graph-file", fixture.coreSnapshot.toString()).first)
        assertEquals(64, run("snapshot", "merge", "--project", root.toString(), "--graph-file", fixture.coreSnapshot.toString()).first)
        assertEquals(64, run("snapshot", "merge", "--project", root.toString(), "--module", "core").first)
        val outside = Files.createDirectories(root.parent.resolve("${root.fileName}-outside"))
        val escaped = run("snapshot", "merge", "--project", root.resolve("app").toString(),
            "--module", outside.toString(), "--graph-file", fixture.coreSnapshot.toString(),
            "--module", ".", "--graph-file", fixture.appSnapshot.toString())
        assertEquals(2, escaped.first, escaped.second)
        assertContains(escaped.second, "inside --project")
        val duplicate = run("snapshot", "merge", "--project", root.toString(),
            "--module", "core", "--graph-file", fixture.coreSnapshot.toString(),
            "--module", root.resolve("core").toString(), "--graph-file", fixture.appSnapshot.toString())
        assertEquals(64, duplicate.first, duplicate.second)
        assertContains(duplicate.second, "only once")
        assertEquals(0, run("snapshot", "merge", "--help").first)
    }

    @Test
    fun `merge rejects captured-empty compiler positions independently of provenance`(@TempDir root: Path) {
        for (keepProvenance in listOf(true, false)) {
            val fixture = Fixture(root.resolve(if (keepProvenance) "verified" else "no-provenance"))
            val original = QuerySnapshotCodec.parse(Files.readString(fixture.coreSnapshot))
            val captured = original.copy(
                graph = original.graph.withCompilerCallPositions(emptyList()),
                provenance = original.provenance.takeIf { keepProvenance },
            )
            Files.writeString(fixture.coreSnapshot, QuerySnapshotCodec.render(captured))

            val result = fixture.merge()
            assertEquals(2, result.first, result.second)
            assertContains(result.second, "captured compiler call positions")
        }
    }

    @Test
    fun `merge refuses captured-empty Android resources without silently dropping them`(@TempDir root: Path) {
        for (keepProvenance in listOf(true, false)) {
            val fixture = Fixture(root.resolve(if (keepProvenance) "resource-provenance" else "resource-no-provenance"))
            val original = QuerySnapshotCodec.parse(Files.readString(fixture.coreSnapshot))
            val captured = original.copy(
                androidResourceEvidence = AndroidResourceEvidence(requireNotNull(original.scope), "dev.example",
                    emptyList(), emptyList(), emptyList()),
                provenance = original.provenance.takeIf { keepProvenance },
            )
            Files.writeString(fixture.coreSnapshot, QuerySnapshotCodec.render(captured))
            val result = fixture.merge()
            assertEquals(2, result.first, result.second)
            assertContains(result.second, "captured Android resources")
            assertTrue(result.stdout.isEmpty())
            assertFalse(Files.exists(fixture.mergedBindings))
        }
    }

    @Test
    fun `merge refuses captured-empty generation evidence before reindexing`(@TempDir root: Path) {
        assertGenerationRefused(root, withDeclarations = false)
    }

    @Test
    fun `merge refuses generated declarations without borrowing another unsupported channel`(@TempDir root: Path) {
        assertGenerationRefused(root, withDeclarations = true)
    }

    /** 생성만 캡처한 v3/v4 구성원을 다른 미지원 채널에 기대지 않고 실제 CLI로 거부한다. */
    private fun assertGenerationRefused(root: Path, withDeclarations: Boolean) {
        for (compact in listOf(false, true)) {
            val fixture = Fixture(root.resolve(if (compact) "compact" else "plain"))
            val original = QuerySnapshotCodec.parse(Files.readString(fixture.coreSnapshot))
            val captured = fixture.generationSnapshot(original, withDeclarations)
            assertFalse(captured.graph.compilerCallPositionsCaptured)
            assertTrue(captured.graph.compilerSourceUseEvidence == null && captured.androidResourceEvidence == null)
            assertTrue(captured.provenance!!.witnesses.all { it.compilerEvidence.isEmpty() })
            Files.writeString(fixture.coreSnapshot, QuerySnapshotCodec.render(captured, compact))
            val inputBytes = Files.readAllBytes(fixture.coreSnapshot)
            val roundTrip = QuerySnapshotCodec.parse(Files.readString(fixture.coreSnapshot))
            assertEquals(if (withDeclarations) 1 else 0, roundTrip.generationEvidence!!.declarations.size)
            val query = run("query", "class:core/Api", "--graph-file", fixture.coreSnapshot.toString())
            assertEquals(0, query.first, query.second)
            val summary = (McpJsonCodec.parse(query.stdout) as Map<*, *>)["generationSummary"] as Map<*, *>
            assertEquals(if (withDeclarations) 1L else 0L, summary["totalDeclarations"])

            val result = fixture.merge()
            assertEquals(2, result.first, result.second)
            assertContains(result.second, "captured generation evidence")
            assertContains(result.second, "query the member snapshots separately")
            assertTrue(result.stdout.isEmpty())
            assertFalse(Files.exists(fixture.mergedBindings))
            assertTrue(inputBytes.contentEquals(Files.readAllBytes(fixture.coreSnapshot)))
            assertFalse(result.second.contains(fixture.root.toString()))
        }
    }

    /** `core` 라이브러리와 이를 호출하는 `app` 모듈을 각자 캡처한 두 snapshot이다. */
    private inner class Fixture(val root: Path) {
        val coreClasses: Path = Files.createDirectories(root.resolve("core/build/classes"))
        val appClasses: Path = Files.createDirectories(root.resolve("app/build/classes"))
        val coreSnapshot: Path = root.resolve("core/build/snapshot.json")
        val appSnapshot: Path = root.resolve("app/build/snapshot.json")
        val appBindings: Path = root.resolve("app/build/bindings.json")
        val mergedBindings: Path = root.resolve("out/merged-bindings.json")

        init {
            val api = write("core/src/core/Api.java", "package core; public class Api { public int load() { return 1; } }")
            val screen = write("app/src/app/Screen.java",
                "package app; public class Screen { private final core.Api api = new core.Api(); public int render() { return api.load(); } " +
                    "public void click() { run(() -> api.load()); } static void run(Runnable task) { task.run(); } }")
            compile(coreClasses, null, api)
            compile(appClasses, coreClasses, screen)
            Files.writeString(coreSnapshot, capture("core", "--classes", coreClasses.toString(), "--scope", ":core:jvm"))
            val app = capture("app", "--classes", appClasses.toString(), "--classpath", coreClasses.toString(), "--scope", ":app:jvm")
            Files.writeString(appSnapshot, app)
            // app의 classpath는 app 모듈 밖이므로 plugin이 만드는 것과 같은 로컬 연결을 둔다.
            val slot = QuerySnapshotCodec.parse(app).provenance!!.inputs.single { it.role == "classpath" }.path
            Files.writeString(appBindings, ExternalInputBindingsCodec.render(mapOf(slot to coreClasses.toString())))
        }

        fun memberArguments(): Array<String> = arrayOf(
            "--module", "core", "--graph-file", coreSnapshot.toString(),
            "--module", root.resolve("app").toString(), "--graph-file", appSnapshot.toString(), "--input-bindings", appBindings.toString(),
        )

        fun merge(): Outcome = run("snapshot", "merge", "--project", root.toString(), *memberArguments(),
            "--input-bindings-output", mergedBindings.toString())

        /** 실제 javac class와 현재 파일 지문에 연결한 단일 모듈 생성 관측이다. 생산자 인증은 주장하지 않는다. */
        fun generationSnapshot(original: QuerySnapshot, withDeclarations: Boolean): QuerySnapshot {
            val module = root.resolve("core")
            val scope = requireNotNull(original.scope)
            val capturedProvenance = requireNotNull(original.provenance)
            val task = ":core:generateSafeArgsJvm"
            val compilerTask = ":core:compileJava"
            val hash = "a".repeat(64)
            fun input(role: String, path: String) = InputFingerprint(role, path, hash)
            val source = "src/core/Api.java"
            val sourceHash = MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(module.resolve(source)))
                .joinToString("") { "%02x".format(it.toInt() and 255) }
            val declarations = if (withDeclarations) {
                val classes = capturedProvenance.inputs.single { it.role == "classes" }
                listOf(GeneratedDeclaration(scope, task, hash, compilerTask, source, sourceHash,
                    NodeId("class:core/Api"), listOf(NodeId("class:core/Api")), classes.path, classes.path,
                    "core/Api.class", MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(coreClasses.resolve("core/Api.class")))
                        .joinToString("") { "%02x".format(it.toInt() and 255) }))
            } else emptyList()
            val provenance = if (withDeclarations) {
                // 이 공개 fixture의 compiler 관측 입력도 실제 존재하는 바이트로 만들어 stale 거부와 분리한다.
                val config = write("core/config/compiler-input.txt", "synthetic compiler input identity\n")
                val witness = BuildWitness(scope, "javac", compilerTask,
                    listOf(ContentFingerprint.capture(module, module.resolve(source), "sources", "source")) +
                        listOf("buildConfig", "compiler", "options").map { role ->
                            ContentFingerprint.capture(module, config, role, role)
                        }, capturedProvenance.inputs.filter { it.role == "classes" })
                capturedProvenance.copy(witnesses = listOf(witness))
            } else capturedProvenance
            val receipt = GenerationReceipt(scope, task, GenerationReceipt.POLICY, hash, "src",
                listOf(input("navigation", "res/navigation/main.xml"), input("generatorArtifact", "generator/plugin.jar"),
                    input("generatorActionArtifact", "generator/action.jar"), input("generatorOptions", "config/generator")),
                input("generationMapping", "build/mapping.json"),
                if (withDeclarations) listOf(InputFingerprint("generatedSource", source, sourceHash)) else emptyList(),
                if (withDeclarations) listOf(GenerationSource("res/navigation/main.xml", source, "core.Api", sourceHash)) else emptyList())
            val context = GenerationOriginContext(GenerationDigests.receipt(receipt), scope, task, "jvm", receipt.outputRoot,
                receipt.mapping.path, listOf(GenerationProviderBinding("navigationFiles", listOf("res/navigation/main.xml")),
                    GenerationProviderBinding("outputDir", listOf(receipt.outputRoot)), GenerationProviderBinding("incrementalFolder", listOf("build"))),
                GenerationOriginContext.ACTION_POLICY, receipt.inputs.filter { it.role == "generatorActionArtifact" })
            val observation = GenerationConsumptionObservation(context, context, hash, receipt.attempt, "current-cold-success", "SUCCEEDED",
                GenerationDigests.inventory(receipt), GenerationDigests.declarations(declarations, provenance))
            val owners = declarations.mapTo(mutableSetOf()) { it.owner }
            val graph = CodeGraph(original.graph.nodes.values.map { node ->
                if (node.id in owners) node.copy(synthesized = true, attributes = node.attributes + NodeAttribute.GENERATED_SOURCE_EVIDENCE) else node
            }, original.graph.edges, original.graph.externalCalls)
            return original.copy(graph = graph, provenance = provenance,
                generationEvidence = GenerationEvidence(listOf(receipt), declarations, listOf(observation)))
        }

        private fun capture(module: String, vararg arguments: String): String {
            val result = run("snapshot", "--project", root.resolve(module).toString(), *arguments)
            assertEquals(0, result.first, result.second)
            return result.stdout
        }

        private fun write(relative: String, text: String): Path =
            root.resolve(relative).also { Files.createDirectories(it.parent); Files.writeString(it, text) }

        private fun compile(output: Path, classpath: Path?, source: Path) {
            val options = listOf("-d", output.toString()) + (classpath?.let { listOf("-cp", it.toString()) } ?: emptyList())
            assertEquals(0, ToolProvider.getSystemJavaCompiler().run(null, null, null, *(options + source.toString()).toTypedArray()))
        }
    }

    /** 종료 코드와 출력이다. `second`는 오류 문구 검사용 합친 출력, `stdout`은 문서 파싱용이다. */
    private data class Outcome(val first: Int, val second: String, val stdout: String)

    private fun run(vararg args: String): Outcome {
        val out = ByteArrayOutputStream()
        val err = ByteArrayOutputStream()
        val status = KartographCli.run(args, PrintStream(out), PrintStream(err))
        return Outcome(status, out.toString(Charsets.UTF_8) + err.toString(Charsets.UTF_8), out.toString(Charsets.UTF_8))
    }
}
