package dev.kartograph.index

import dev.kartograph.core.Visibility
import dev.kartograph.index.fixture.JavaFixture
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals

class JavaVisibilityTest {
    @Test
    fun `uses JVM access flags when Kotlin metadata is absent`() {
        val graph = ClassFileIndexer().index(listOf(testClassesRoot))
        val owner = "dev/kartograph/index/fixture/JavaFixture"

        assertEquals(Visibility.PUBLIC, graph.node(JvmNodeId.classId(owner))?.visibility)
        assertEquals(
            Visibility.PROTECTED,
            graph.node(JvmNodeId.fieldId(owner, "protectedValue", "Ljava/lang/String;"))?.visibility,
        )
        assertEquals(
            Visibility.PRIVATE,
            graph.node(JvmNodeId.methodId(owner, "hidden", "()V"))?.visibility,
        )
        assertEquals(
            Visibility.PACKAGE_PRIVATE,
            graph.node(JvmNodeId.methodId(owner, "packageVisible", "()V"))?.visibility,
        )
    }

    private val testClassesRoot: Path
        get() = Path.of(requireNotNull(JavaFixture::class.java.protectionDomain.codeSource).location.toURI())
}
