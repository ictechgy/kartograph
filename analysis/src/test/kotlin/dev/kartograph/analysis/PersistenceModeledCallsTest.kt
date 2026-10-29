package dev.kartograph.analysis

import dev.kartograph.analysis.PersistenceModeledCalls.Attribution
import dev.kartograph.analysis.PersistenceModeledCalls.Evidence
import dev.kartograph.core.CallResolution
import dev.kartograph.core.CodeGraph
import dev.kartograph.core.ExternalCall
import dev.kartograph.core.GraphNode
import dev.kartograph.core.InvocationKind
import dev.kartograph.core.NodeId
import dev.kartograph.core.NodeKind
import dev.kartograph.core.SourceLocation
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** 상속 저장소 호출을 미해결에서 빼는 건전성 조건을 조건마다 하나씩 깨 본다. */
class PersistenceModeledCallsTest {
    private val caller = NodeId("method:demo/Service#run()V")
    private val servicePath = "src/main/java/demo/Service.java"
    private val ownersPath = "src/main/java/demo/Owners.java"

    private fun type(name: String, kind: NodeKind, vararg supertypes: String) = GraphNode(NodeId("class:$name"), name.substringAfterLast('/'), kind,
        jvmSignature = name, location = SourceLocation("src/main/java/$name.java"), supertypes = supertypes.toSet())

    private fun call(owner: String, line: Int? = 7, model: String? = null, resolution: CallResolution = CallResolution.UNRESOLVED, name: String = "save") =
        ExternalCall(caller, owner, name, "(Ljava/lang/Object;)Ljava/lang/Object;", InvocationKind.INTERFACE,
            line?.let { SourceLocation(servicePath, it) }, resolution = resolution, model = model)

    private val nodes = listOf(
        GraphNode(caller, "run", NodeKind.METHOD, location = SourceLocation(servicePath, 5)),
        type("demo/Base", NodeKind.INTERFACE, "org/springframework/data/jpa/repository/JpaRepository"),
        type("demo/Owners", NodeKind.INTERFACE, "demo/Base"),
        type("demo/Pets", NodeKind.INTERFACE, "org/springframework/data/repository/CrudRepository"),
        type("demo/Sink", NodeKind.INTERFACE, "java/util/function/Consumer"),
    )

    /** 호출 줄 7에 owners 사실, Owners 선언 파일에 owners 선언 사실이 있는 근거다. */
    private val evidence = Evidence(setOf(Attribution(caller.value, 7, "owners")), mapOf(ownersPath to setOf("owners")))

    @Test
    fun `only unresolved repository calls whose relation is attributed on the call line are modeled`() {
        val modeled = call("demo/Owners")
        val calls = listOf(
            modeled,
            call("demo/Owners", line = 8),
            call("demo/Owners", model = "reflection"),
            call("demo/Owners", resolution = CallResolution.PROJECT_CANDIDATES),
            call("demo/Sink"),
            call("demo/Pets"),
            call("demo/Unknown"),
        )
        val graph = CodeGraph(nodes, emptyList(), calls)
        assertEquals(setOf(modeled), PersistenceModeledCalls.select(graph, evidence))
        assertTrue(PersistenceModeledCalls.select(graph, Evidence.NONE).isEmpty())
        // 같은 줄의 사실이 이 저장소의 relation이 아니면(다른 문장의 사실) 근거가 아니다.
        val otherRelation = Evidence(setOf(Attribution(caller.value, 7, "audit_log")), mapOf(ownersPath to setOf("owners")))
        assertTrue(PersistenceModeledCalls.select(graph, otherRelation).isEmpty())
        // 저장소 선언 파일의 사실이 없으면 도메인 relation을 모른다.
        assertTrue(PersistenceModeledCalls.select(graph, Evidence(evidence.callSites, emptyMap())).isEmpty())
    }

    @Test
    fun `a call without a line uses the caller declaration line like the scanner`() {
        val lineless = call("demo/Owners", line = null)
        val graph = CodeGraph(nodes, emptyList(), listOf(lineless))
        assertEquals(setOf(lineless), PersistenceModeledCalls.select(graph, Evidence(setOf(Attribution(caller.value, 5, "owners")), evidence.declarations)))
        assertTrue(PersistenceModeledCalls.select(graph, evidence).isEmpty())
    }

    @Test
    fun `calls that project code could receive stay unresolved`() {
        val save = call("demo/Owners")
        // fragment가 같은 이름을 재정의하면 Spring Data가 프로젝트 구현으로 보낼 수 있다.
        val fragment = GraphNode(NodeId("method:demo/Base#save(Ljava/lang/Object;)Ljava/lang/Object;"), "save", NodeKind.METHOD)
        assertTrue(PersistenceModeledCalls.select(CodeGraph(nodes + fragment, emptyList(), listOf(save)), evidence).isEmpty())
        // 사용자 base class나 직접 구현이 있으면 상속 메서드가 프로젝트 코드로 갈 수 있다.
        val implementation = type("demo/CustomRepository", NodeKind.CLASS, "org/springframework/data/jpa/repository/support/SimpleJpaRepository")
        assertTrue(PersistenceModeledCalls.select(CodeGraph(nodes + implementation, emptyList(), listOf(save)), evidence).isEmpty())
        val handWritten = type("demo/OwnersImpl", NodeKind.CLASS, "demo/Owners")
        assertTrue(PersistenceModeledCalls.select(CodeGraph(nodes + handWritten, emptyList(), listOf(save)), evidence).isEmpty())
        // Kotlin object도 구현이 될 수 있다.
        val singleton = type("demo/CachedOwners", NodeKind.OBJECT, "demo/Owners")
        assertTrue(PersistenceModeledCalls.select(CodeGraph(nodes + singleton, emptyList(), listOf(save)), evidence).isEmpty())
        // Windows 구분자로 적힌 선언 경로도 같은 파일이다.
        val windows = Evidence(evidence.callSites, mapOf(PersistenceModeledCalls.normalizedPath("src\\main\\java\\demo\\Owners.java") to setOf("owners")))
        assertEquals(setOf(save), PersistenceModeledCalls.select(CodeGraph(nodes, emptyList(), listOf(save)), windows))
    }

    @Test
    fun `traversal stops counting modeled calls and reports how many it excluded`() {
        val modeled = call("demo/Owners")
        val other = call("demo/Sink")
        val graph = CodeGraph(nodes, emptyList(), listOf(modeled, other))
        val plain = LanguageTraversal.traverse(graph, listOf(caller.value), TraversalDirection.DEPENDENCIES)
        assertEquals(2, plain.roots.single().unresolvedCalls)
        assertTrue(plain.limitations.none { it.startsWith("persistence-modeled-calls:") })
        val narrowed = LanguageTraversal.traverse(graph, listOf(caller.value), TraversalDirection.DEPENDENCIES, modeledCalls = setOf(modeled))
        assertEquals(1, narrowed.roots.single().unresolvedCalls)
        assertTrue(narrowed.limitations.any { it.startsWith("persistence-modeled-calls: 1 call(s)") })
    }
}
