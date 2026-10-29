package dev.kartograph.cli

import dev.kartograph.analysis.AuthoredCallUniverse
import dev.kartograph.analysis.DefaultRetention
import dev.kartograph.analysis.ReachabilityAnalyzer
import dev.kartograph.core.*
import dev.kartograph.index.ClassFileIndexer
import dev.kartograph.index.KeepRuleScanner
import java.nio.file.Files
import java.nio.file.Path
import org.junit.jupiter.api.io.TempDir
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class AuthoredCallApiSelfRetentionTest {
    @Test
    fun `self roots declare only the packaged authored universe entrypoint`(@TempDir root: Path) {
        val classes = Files.createDirectories(root.resolve("classes"))
        for (type in listOf(AuthoredCallUniverse::class.java, UnrelatedAuthoredPublicApi::class.java)) {
            val name = type.name.replace('.', '/') + ".class"
            val output = classes.resolve(name)
            Files.createDirectories(output.parent)
            Files.write(output, requireNotNull(type.getResourceAsStream("/$name")).use { it.readBytes() })
        }
        val indexed = ClassFileIndexer().indexWithObservations(listOf(classes), emptyList()).graph
        val owner = "dev/kartograph/analysis/AuthoredCallUniverse"
        val entrypoint = NodeId("method:$owner#examine(Ldev/kartograph/core/CodeGraph;Ldev/kartograph/analysis/AuthoredCallerScope;)Ldev/kartograph/analysis/AuthoredCallUniverseResult;")
        assertTrue(indexed.contains(entrypoint), "exact packaged API descriptor is missing")
        // 동일 이름 overload와 동일 owner helper도 명시한 signature의 root 범위 밖이다.
        val unused = listOf("examine()V" to Visibility.PUBLIC, "unrelatedPublic()V" to Visibility.PUBLIC,
            "unrelatedPrivate()V" to Visibility.PRIVATE).map { (signature, visibility) ->
            GraphNode(NodeId("method:$owner#$signature"), signature.substringBefore('('), NodeKind.METHOD,
                jvmSignature = "$owner#$signature", visibility = visibility, jvmVisibility = visibility)
        }
        val unrelated = NodeId("class:dev/kartograph/cli/UnrelatedAuthoredPublicApi")
        val graph = CodeGraph(indexed.nodes.values + unused, indexed.edges + unused.flatMap { member -> listOf(
            GraphEdge(NodeId("class:$owner"), member.id, EdgeKind.MEMBER),
            GraphEdge(member.id, unrelated, EdgeKind.REFERENCE),
        ) }, indexed.externalCalls)
        val rules = root.resolve("self.pro")
        Files.writeString(rules, Files.readString(Path.of("..", ".kartograph-self.pro")))
        for (members in listOf(false, true)) {
            val parsed = KeepRuleScanner(root, retainClassMembers = members).scan(listOf(rules))
            val evidence = DefaultRetention.find(graph, emptyList(), parsed)
            val reachable = ReachabilityAnalyzer.analyze(graph, evidence).reachableNodeIds
            assertTrue(entrypoint in reachable)
            val roots = evidence.filter { it.reason == RetentionReason.KEEP_RULE }.map { it.nodeId }.toSet()
            assertEquals(setOf(NodeId("class:$owner"), entrypoint), roots)
            assertFalse(unrelated in reachable, "unrelated public APIs are not external roots")
            for (member in unused) {
                assertFalse(member.id in roots)
                assertFalse(member.id in reachable)
            }
            // 실제 private helper는 examine의 일반 CALL로 도달하며 별도 root가 아니다.
            val helpers = indexed.nodes.keys.filter { it.value.startsWith("method:$owner#captureScope(") ||
                it.value.startsWith("method:$owner#examineCaptured(") }
            assertEquals(2, helpers.size)
            assertTrue(helpers.all { it in reachable && it !in roots })
        }
    }
}

public object UnrelatedAuthoredPublicApi {
    public fun unused(): String = "unrelated public API"
}
