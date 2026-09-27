package dev.kartograph.cli

import dev.kartograph.core.EdgeKind
import dev.kartograph.core.NodeId
import dev.kartograph.export.ExternalInputBindingsCodec
import dev.kartograph.export.QuerySnapshotCodec
import java.io.ByteArrayOutputStream
import java.io.PrintStream
import java.nio.file.Files
import java.nio.file.Path
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
        val merged = fixture.merge()
        assertEquals(0, merged.first, merged.second)
        assertContains(merged.second, "warning: merged snapshot includes unverified members")
        val snapshot = QuerySnapshotCodec.parse(merged.stdout)
        val screen = NodeId("method:app/Screen#render()I")
        val api = NodeId("method:core/Api#load()I")
        assertTrue(snapshot.graph.edges.any { it.source == screen && it.target == api && it.kind == EdgeKind.CALL })
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
