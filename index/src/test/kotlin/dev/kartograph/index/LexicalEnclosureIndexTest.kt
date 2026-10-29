package dev.kartograph.index

import dev.kartograph.core.LexicalEnclosure
import dev.kartograph.core.NodeId
import dev.kartograph.index.fixture.LambdaEnclosureFixture
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertTrue
import org.junit.jupiter.api.io.TempDir

/** classfile `EnclosingMethod`를 지역·익명 class의 어휘적 소속 사실로 수집하는지 확인한다. */
class LexicalEnclosureIndexTest {
    private val fixture = LambdaEnclosureFixture()
    private val owner = "dev/kartograph/index/fixture/LambdaEnclosureFixture"
    private val classesRoot: Path
        get() = Path.of(requireNotNull(LambdaEnclosureFixture::class.java.protectionDomain.codeSource).location.toURI())

    @Test
    fun `local classes belong to their enclosing method or initializer class`() {
        val enclosures = ClassFileIndexer().index(listOf(classesRoot)).enclosures
        val registered = internalName(fixture.register())
        assertContains(enclosures, LexicalEnclosure(NodeId("class:$registered"), NodeId("method:$owner#register()Ljava/lang/Runnable;")))
        assertContains(enclosures, LexicalEnclosure(NodeId("class:${internalName(fixture.serializableLambda())}"),
            NodeId("method:$owner#serializableLambda()Lkotlin/jvm/functions/Function0;")))
        val initializer = enclosures.single { it.localClass == NodeId("class:${internalName(fixture.initializerCallback)}") }
        assertTrue(initializer.enclosing.value.startsWith("method:$owner#<init>") || initializer.enclosing == NodeId("class:$owner"))
    }

    @Test
    fun `nested lambdas chain through the enclosing local class method`() {
        val enclosures = ClassFileIndexer().index(listOf(classesRoot)).enclosures
        val anonymous = internalName(fixture.register())
        val nested = enclosures.filter { it.enclosing.value.startsWith("method:$anonymous#nestedLambda") }
        assertTrue(nested.isNotEmpty(), "the lambda inside the anonymous object must belong to its method")
    }

    @Test
    fun `cached facts keep enclosing declarations`(@TempDir directory: Path) {
        val cache = ClassIndexCache(directory.resolve("cache"), "enclosures")
        val cold = ClassFileIndexer(cache).index(listOf(classesRoot)).enclosures
        val warm = ClassFileIndexer(cache).index(listOf(classesRoot)).enclosures
        assertTrue(cold.isNotEmpty())
        kotlin.test.assertEquals(cold, warm)
    }

    @Test
    fun `kotlin function type calls are owned by kotlin jvm functions interfaces`() {
        // 리뷰 지적 반박 근거: Kotlin 함수 타입 호출의 JVM owner는 kotlin/jvm/functions/FunctionN이며 kotlin/FunctionN이 아니다.
        val graph = ClassFileIndexer().index(listOf(classesRoot))
        val owners = graph.externalCalls.filter { it.caller.value.startsWith("method:") && "LambdaEnclosureFixture" in it.caller.value &&
            it.name == "invoke" }.map { it.owner }.toSet()
        kotlin.test.assertEquals(setOf("kotlin/jvm/functions/Function0"), owners)
    }

    private fun internalName(value: Any): String = value::class.java.name.replace('.', '/')
}
