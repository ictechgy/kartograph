package dev.kartograph.index

import dev.kartograph.analysis.LanguageTraversal
import dev.kartograph.analysis.TraversalDirection
import dev.kartograph.core.BridgeFact
import dev.kartograph.core.CodeGraph
import dev.kartograph.core.GraphNode
import dev.kartograph.core.JvmModifier
import dev.kartograph.core.NodeId
import dev.kartograph.core.NodeKind
import dev.kartograph.core.SourceLocation
import dev.kartograph.index.fixture.retrofit.ShopApi
import java.io.ByteArrayOutputStream
import java.nio.file.Files
import java.nio.file.Path
import javax.tools.ToolProvider
import kotlin.io.path.createDirectories
import kotlin.io.path.writeText
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import org.junit.jupiter.api.io.TempDir

/**
 * Retrofit route-call 사실이 snapshot 정점의 JVM 신원(`symbol.usr`)을 달고, 그 usr에서 시작한 역방향 순회(isthmus trace가
 * 쓰는 `impact --format language-traversal`)가 화면 계층 호출자까지 닿는지 실제 Kotlin·javac 바이트코드로 고정한다.
 *
 * 수정 전에는 서비스 메서드가 추상이라 bytecode에 줄 번호가 없고 사실 위치가 어노테이션 줄이어서, 위치 기반 부착이
 * 어떤 정점도 찾지 못해 usr가 빠졌다. 하위 인터페이스로 부른 상속 메서드는 그래프에 호출 간선조차 없었다.
 */
class RetrofitSymbolTest {
    @TempDir
    lateinit var project: Path

    @TempDir
    lateinit var outside: Path

    private val packagePath = "dev/kartograph/index/fixture/retrofit"

    private fun method(owner: String, signature: String): String = "method:$packagePath/$owner#$signature"

    @Test
    fun `retrofit facts carry interface method identities that reverse traversal follows to view models`() {
        val graph = fixtureGraph()
        val facts = RouteCallScanner(project).scan(generatedAt = "2026-01-01T00:00:00Z", graph = graph).facts
        val usrs = facts.associate { "${it.method} ${it.channel}#${it.symbol?.usr?.substringAfter('(')}" to it.symbol?.usr }
        assertEquals(
            mapOf(
                "GET /items/{}#I)Ljava/lang/String;" to method("CatalogApi", "item(I)Ljava/lang/String;"),
                "POST /orders#Ljava/util/Map;Lkotlin/coroutines/Continuation;)Ljava/lang/Object;" to
                    method("ShopApi", "placeOrder(Ljava/util/Map;Lkotlin/coroutines/Continuation;)Ljava/lang/Object;"),
                "DELETE /orders/{}#I)Ljava/lang/String;" to method("ShopApi", "cancel(I)Ljava/lang/String;"),
                "GET /search#Ljava/lang/String;I)Ljava/lang/String;" to method("ShopApi", "search(Ljava/lang/String;I)Ljava/lang/String;"),
                "GET /list#)Ljava/lang/String;" to method("ShopApi", "list()Ljava/lang/String;"),
                "GET /list#I)Ljava/lang/String;" to method("ShopApi", "list(I)Ljava/lang/String;"),
                "GET /nested#)Ljava/lang/String;" to method("RetrofitOuter\$NestedApi", "nested()Ljava/lang/String;"),
                "GET /java/{}#I)Ljava/lang/String;" to "method:demo/JavaApi#fetch(I)Ljava/lang/String;",
            ),
            usrs,
        )
        // 위치는 여전히 어노테이션 토큰이다 — 신원만 선언 정점으로 붙는다.
        assertEquals(12, facts.single { it.channel == "/items/{}" }.location.line)
        assertEquals("dev.kartograph.index.fixture.retrofit.RetrofitOuter\$NestedApi.nested", facts.single { it.channel == "/nested" }.symbol?.qualifiedName)

        val reached = reverseReach(graph, facts)
        val viewModel = { signature: String -> method("OrderViewModel", signature) }
        assertTrue(viewModel("open(I)Ljava/lang/String;") in reached.getValue("/items/{}"), "inherited method through a sub-interface receiver")
        assertTrue(viewModel("checkout(Lkotlin/coroutines/Continuation;)Ljava/lang/Object;") in reached.getValue("/orders"), "suspend")
        // 같은 엔드포인트를 부르는 두 저장소가 사실 하나의 usr에서 모두 닿는다 — 호출 지점 하나를 신원으로 고르면 한쪽을 잃는다.
        assertTrue(
            reached.getValue("/orders/{}").containsAll(listOf(
                method("OrderRepository", "cancel(I)Ljava/lang/String;"),
                method("AdminRepository", "forceCancel(I)Ljava/lang/String;"),
                viewModel("cancel(I)Ljava/lang/String;"),
            )),
            reached.getValue("/orders/{}").toString(),
        )
        assertTrue(viewModel("find()Ljava/lang/String;") in reached.getValue("/search"), "default argument goes through search\$default")
        assertTrue(viewModel("more()Ljava/lang/String;") in reached.getValue("/list"), "overload")
        assertTrue(viewModel("nested()Ljava/lang/String;") in reached.getValue("/nested"), "nested interface")
        assertTrue("method:demo/JavaViewModel#show()Ljava/lang/String;" in reached.getValue("/java/{}"), "java interface")
    }

    @Test
    fun `retrofit identity is withheld when the declaration is not unique or not the annotated method`() {
        val path = "src/main/kotlin/p/Api.kt"
        val fact = BridgeFact(kind = "route-call", channel = "/x", method = "GET", dynamic = false,
            location = dev.kartograph.core.BridgeLocation(path, 3, 5), symbol = dev.kartograph.core.BridgeSymbol("p.Api.get"), target = "http")
        fun node(signature: String, annotation: String = "retrofit2/http/GET", nodePath: String = path) = GraphNode(
            NodeId("method:p/Api#$signature"), signature.substringBefore('('), NodeKind.METHOD, location = SourceLocation(nodePath),
            jvmModifiers = setOf(JvmModifier.ABSTRACT), annotations = setOf(annotation),
        )
        fun attach(declaration: RetrofitDeclaration, vararg nodes: GraphNode) =
            RetrofitSymbolIndex(CodeGraph(nodes.toList(), emptyList())).attach(fact, declaration).symbol?.usr
        val get = RetrofitDeclaration("p/Api", "get", "GET", 1)

        assertEquals("method:p/Api#get(I)V", attach(get, node("get(I)V"), node("get(J)V", annotation = "retrofit2/http/POST")))
        assertEquals("method:p/Api#get(I)V", attach(get, node("get()V"), node("get(I)V")))
        assertEquals("method:p/Api#get(ILkotlin/coroutines/Continuation;)Ljava/lang/Object;",
            attach(get, node("get()V"), node("get(ILkotlin/coroutines/Continuation;)Ljava/lang/Object;")))
        assertEquals("method:p/Api#get-4ZD5Yi0(I)V", attach(get, node("get-4ZD5Yi0(I)V"), node("getAll(I)V")), "value class mangling")
        assertNull(attach(get, node("get(I)V"), node("get(J)V")), "same verb and arity is ambiguous")
        assertNull(attach(get, node("get(I)V", annotation = "retrofit2/http/POST")), "verb annotation must match")
        assertNull(attach(get, node("get(I)V", nodePath = "src/free/kotlin/p/Api.kt")), "another source set's copy")
        assertNull(attach(get.copy(owner = "p/Other"), node("get(I)V")))
    }

    @Test
    fun `parameter counts ignore nested commas, function type arrows and trailing commas`() {
        fun count(text: String): Int = parameterCount(text, 0, text.length - 1)
        assertEquals(0, count("()"))
        assertEquals(0, count("(  )"))
        assertEquals(1, count("(@Body payload: Map<String, List<Int>>)"))
        assertEquals(2, count("(@Query(value = \"q\", encoded = true) q: String, callback: (Int, String) -> Unit)"))
        assertEquals(2, count("(a: Int,\n b: Array<String>,\n)"))
        assertEquals(2, count("(@Path(\"id\") int id, java.util.Map<String, Object> body)"))
        // 기본값 식의 비교 연산자는 꺾쇠가 아니다.
        assertEquals(2, count("(all: Boolean = MAX > 0, q: String)"))
        assertEquals(2, count("(x: Int = if (a < b) 1 else 2, y: Int)"))
        assertEquals(1, count("(m: Map<String, Int> = mapOf<String, Int>())"))
    }

    /** Kotlin fixture 바이트코드와 javac로 만든 Java 서비스를 합친 그래프에 project 기준 소스 경로를 입힌다. */
    private fun fixtureGraph(): CodeGraph {
        val testClasses = Path.of(ShopApi::class.java.protectionDomain.codeSource.location.toURI())
        val classes = outside.resolve("classes").resolve(packagePath).createDirectories()
        Files.list(testClasses.resolve(packagePath)).use { files -> files.forEach { Files.copy(it, classes.resolve(it.fileName)) } }
        val source = Path.of("src/test/kotlin/$packagePath/RetrofitServiceFixture.kt")
        assertTrue(Files.isRegularFile(source), "run from the index module directory so the fixture source is visible")
        project.resolve("src/main/kotlin/$packagePath").createDirectories()
        Files.copy(source, project.resolve("src/main/kotlin/$packagePath/RetrofitServiceFixture.kt"))
        val javaClasses = compileJava(testClasses)
        val graph = ClassFileIndexer().index(listOf(outside.resolve("classes"), javaClasses))
        val paths = SourcePathIndex.resolve(graph, project).byNodeId
        val located = graph.nodes.values.map { node -> paths[node.id]?.let { node.copy(location = SourceLocation(it, node.location?.line)) } ?: node }
        return CodeGraph(located, graph.edges, graph.externalCalls, graph.serviceProviders, graph.enclosures, graph.callbackArguments,
            graph.parameterUses, graph.lambdaEscapes)
    }

    private fun compileJava(testClasses: Path): Path {
        val api = write("src/main/java/demo/JavaApi.java", """
            package demo;

            import retrofit2.http.GET;
            import retrofit2.http.Path;

            public interface JavaApi {
                @GET("java/{id}")
                String fetch(@Path("id") int id);
            }
            """)
        val callers = write("src/main/java/demo/JavaViewModel.java", """
            package demo;

            public final class JavaViewModel {
                private final JavaApi api;
                public JavaViewModel(JavaApi api) { this.api = api; }
                public String show() { return api.fetch(1); }
            }
            """)
        val classes = outside.resolve("java-classes").createDirectories()
        val errors = ByteArrayOutputStream()
        val arguments = listOf("--release", "17", "-g", "-classpath", testClasses.toString(), "-d", classes.toString(), api.toString(), callers.toString())
        assertEquals(0, requireNotNull(ToolProvider.getSystemJavaCompiler()).run(null, null, errors, *arguments.toTypedArray()), errors.toString())
        return classes
    }

    private fun write(relative: String, content: String): Path = project.resolve(relative).also { path ->
        path.parent.createDirectories()
        path.writeText(content.trimIndent() + "\n")
    }

    /** 사실 경로(channel)별로 그 usr에서 역방향으로 닿은 정점 usr다. isthmus capture처럼 사실 usr 전체를 root로 한 번에 순회한다. */
    private fun reverseReach(graph: CodeGraph, facts: List<BridgeFact>): Map<String, Set<String>> {
        val roots = facts.map { requireNotNull(it.symbol?.usr) }
        val result = LanguageTraversal.traverse(graph, roots, TraversalDirection.DEPENDENTS)
        return facts.withIndex().groupBy({ it.value.channel.orEmpty() }) { (index, _) ->
            result.reached.filter { index in it.roots }.map { it.node.id.value }
        }.mapValues { (_, lists) -> lists.flatten().toSet() }
    }
}
