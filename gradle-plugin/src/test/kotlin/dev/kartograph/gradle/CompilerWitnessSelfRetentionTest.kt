package dev.kartograph.gradle

import dev.kartograph.analysis.DefaultRetention
import dev.kartograph.analysis.ReachabilityAnalyzer
import dev.kartograph.core.CodeGraph
import dev.kartograph.core.GraphNode
import dev.kartograph.core.GraphEdge
import dev.kartograph.core.NodeKind
import dev.kartograph.core.Visibility
import dev.kartograph.core.EdgeKind
import dev.kartograph.core.NodeId
import dev.kartograph.core.RetentionReason
import dev.kartograph.index.ClassFileIndexer
import dev.kartograph.index.CompilerAuthoredCallOptions
import dev.kartograph.index.KeepRuleScanner
import java.nio.file.Files
import java.nio.file.Path
import org.gradle.api.Action
import org.gradle.api.Task
import org.gradle.api.specs.Spec
import org.junit.jupiter.api.io.TempDir
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class CompilerWitnessSelfRetentionTest {
    @Test
    fun `self roots retain registered producer callbacks but not unrelated callbacks or helpers`(@TempDir root: Path) {
        val selected = listOf(CompilerWitnesses::class.java, BeginWitness::class.java, CompleteWitness::class.java,
            MatchingWitness::class.java, WitnessSpec::class.java, CompilerAuthoredCallOptions::class.java,
            UnrelatedWitnessRoot::class.java, UnrelatedWitnessAction::class.java, UnrelatedWitnessSpec::class.java,
            UnrelatedWitnessHelper::class.java)
        val classes = Files.createDirectories(root.resolve("classes"))
        for (type in selected) {
            val name = type.name.replace('.', '/') + ".class"
            val bytes = requireNotNull(type.getResourceAsStream("/$name")).use { it.readBytes() }
            val output = classes.resolve(name)
            Files.createDirectories(output.parent)
            Files.write(output, bytes)
        }
        val indexed = ClassFileIndexer().indexWithObservations(listOf(classes), emptyList()).graph
        val callbacks = linkedMapOf(
            "BeginWitness" to ("execute(Ljava/lang/Object;)V" to "doFirst"),
            "CompleteWitness" to ("execute(Ljava/lang/Object;)V" to "doLast"),
            "MatchingWitness" to ("isSatisfiedBy(Ljava/lang/Object;)Z" to "upToDateWhen"),
        )
        // 실제 등록 증거를 먼저 검증하고, 동일 owner의 별도 미사용 member는 독립적인 음성 대조군으로 추가한다.
        val unused = callbacks.keys.flatMap { owner -> listOf(Visibility.PUBLIC, Visibility.PRIVATE).map { visibility ->
            val signature = "dev/kartograph/gradle/$owner#unrelated${visibility.name}()V"
            GraphNode(NodeId("method:$signature"), "unrelated${visibility.name}", NodeKind.METHOD,
                jvmSignature = signature, visibility = visibility, jvmVisibility = visibility)
        } }
        val graph = CodeGraph(indexed.nodes.values + unused, indexed.edges + unused.flatMap { node -> listOf(
            GraphEdge(NodeId("class:${node.id.value.substringAfter("method:").substringBefore('#')}"), node.id, EdgeKind.MEMBER),
            GraphEdge(node.id, NodeId("class:dev/kartograph/gradle/UnrelatedWitnessHelper"), EdgeKind.REFERENCE),
        ) }, indexed.externalCalls)
        // 실제 현재 producer bytecode에서 같은 호출자가 해당 callback을 만들고 Gradle에 등록한다.
        for ((owner, pair) in callbacks) {
            val (signature, registration) = pair
            assertTrue(graph.contains(NodeId("method:dev/kartograph/gradle/$owner#$signature")))
            val registrations = graph.externalCalls.filter { it.name == registration &&
                it.owner in setOf("org/gradle/api/Task", "org/gradle/api/tasks/TaskOutputs") }
            assertTrue(registrations.isNotEmpty(), "missing actual Gradle registration: $registration")
            assertTrue(registrations.any { call -> graph.outgoingEdgesFrom(call.caller).any { edge ->
                edge.kind == EdgeKind.CALL && edge.target.value.startsWith("method:dev/kartograph/gradle/$owner#<init>(")
            } }, "registration and callback constructor do not share the actual caller")
        }
        val productionRules = Files.readString(Path.of("..", ".kartograph-self.pro"))
        val rules = root.resolve("self.pro")
        Files.writeString(rules, productionRules + "\n-keep class dev.kartograph.gradle.UnrelatedWitnessRoot { *; }\n")
        val option = NodeId("class:dev/kartograph/index/CompilerAuthoredCallOptions")
        for (members in listOf(false, true)) {
            val parsed = KeepRuleScanner(root, retainClassMembers = members).scan(listOf(rules))
            // PrivateMemberRetention를 섞지 않고 정확히 keep root와 bytecode usage만 확인한다.
            val evidence = DefaultRetention.find(graph, emptyList(), parsed)
            val result = ReachabilityAnalyzer.analyze(graph, evidence)
            for ((owner, pair) in callbacks) {
                val callback = NodeId("method:dev/kartograph/gradle/$owner#${pair.first}")
                assertTrue(callback in result.reachableNodeIds, "registered runtime callback is not retained: $callback")
                assertTrue(evidence.any { it.nodeId == callback && it.reason == RetentionReason.KEEP_RULE })
            }
            assertTrue(option in result.reachableNodeIds, "producer's authored option must be reachable through its runtime callback")
            assertFalse(evidence.any { it.nodeId == option }, "authored options must not be rooted directly")
            for ((owner, signature) in listOf("UnrelatedWitnessAction" to "execute(Ljava/lang/Object;)V",
                "UnrelatedWitnessSpec" to "isSatisfiedBy(Ljava/lang/Object;)Z")) {
                assertTrue(NodeId("class:dev/kartograph/gradle/$owner") in result.reachableNodeIds)
                assertFalse(NodeId("method:dev/kartograph/gradle/$owner#$signature") in result.reachableNodeIds)
            }
            assertFalse(NodeId("class:dev/kartograph/gradle/UnrelatedWitnessHelper") in result.reachableNodeIds)
            for (member in unused) {
                assertFalse(evidence.any { it.nodeId == member.id }, "same-owner unrelated member became a root")
                assertFalse(member.id in result.reachableNodeIds)
            }
            assertEquals(3, evidence.count { it.reason == RetentionReason.KEEP_RULE && it.nodeId.value in callbacks.map { (owner, pair) ->
                "method:dev/kartograph/gradle/$owner#${pair.first}" } })
        }
    }
}

// 등록되지 않은 별개 Action/Spec의 class만 생성돼도 callback body는 자동 root가 아니어야 한다.
internal object UnrelatedWitnessRoot {
    fun create(): List<Any> = listOf(UnrelatedWitnessAction(), UnrelatedWitnessSpec())
}
internal class UnrelatedWitnessAction : Action<Task> {
    override fun execute(task: Task) { UnrelatedWitnessHelper.touch() }
}
internal class UnrelatedWitnessSpec : Spec<Task> {
    override fun isSatisfiedBy(task: Task): Boolean { UnrelatedWitnessHelper.touch(); return true }
}
internal object UnrelatedWitnessHelper {
    fun touch() { System.err.println("unrelated callback") }
}
