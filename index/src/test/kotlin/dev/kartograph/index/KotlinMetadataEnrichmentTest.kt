package dev.kartograph.index

import dev.kartograph.core.NodeAttribute
import dev.kartograph.core.NodeKind
import dev.kartograph.core.Visibility
import dev.kartograph.index.fixture.Caller
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class KotlinMetadataEnrichmentTest {
    @Test
    fun `restores Kotlin class visibility and declaration kind`() {
        val graph = ClassFileIndexer().index(listOf(testClassesRoot))
        val caller = graph.node(JvmNodeId.classId("dev/kartograph/index/fixture/Caller"))
        val dependency = graph.node(JvmNodeId.classId("dev/kartograph/index/fixture/Dependency"))
        val singleton = graph.node(JvmNodeId.classId("dev/kartograph/index/fixture/Singleton"))
        val privateTopLevel = graph.node(JvmNodeId.classId("dev/kartograph/index/fixture/PrivateTopLevel"))

        assertEquals(Visibility.INTERNAL, caller?.visibility)
        assertEquals(Visibility.PUBLIC, caller?.jvmVisibility)
        assertEquals(Visibility.INTERNAL, dependency?.visibility)
        assertTrue(NodeAttribute.DATA_CLASS in dependency?.attributes.orEmpty())
        assertEquals(NodeKind.OBJECT, singleton?.kind)
        assertEquals(Visibility.PRIVATE, privateTopLevel?.visibility)
        assertEquals(Visibility.PACKAGE_PRIVATE, privateTopLevel?.jvmVisibility)
    }

    @Test
    fun `restores extension function receiver and visibility`() {
        val graph = ClassFileIndexer().index(listOf(testClassesRoot))
        val extension = graph.node(
            JvmNodeId.methodId(
                "dev/kartograph/index/fixture/ProbeFixturesKt",
                "extensionValue",
                "(Ldev/kartograph/index/fixture/Dependency;)Ljava/lang/String;",
            ),
        )

        assertEquals(Visibility.INTERNAL, extension?.visibility)
        assertTrue(NodeAttribute.EXTENSION_FUNCTION in extension?.attributes.orEmpty())
        assertEquals("dev/kartograph/index/fixture/Dependency", extension?.extensionReceiverType)
    }

    @Test
    fun `classifies a Kotlin backing field as a property`() {
        val graph = ClassFileIndexer().index(listOf(testClassesRoot))
        val property = graph.node(
            JvmNodeId.fieldId(
                "dev/kartograph/index/fixture/Dependency",
                "value",
                "Ljava/lang/String;",
            ),
        )

        assertEquals(NodeKind.PROPERTY, property?.kind)
        assertEquals(Visibility.PUBLIC, property?.visibility)
    }

    @Test
    fun `preserves annotation names even when their graph nodes are external`() {
        val graph = ClassFileIndexer().index(listOf(testClassesRoot))
        val caller = graph.node(JvmNodeId.classId("dev/kartograph/index/fixture/Caller"))
        val property = graph.node(
            JvmNodeId.fieldId(
                "dev/kartograph/index/fixture/Dependency",
                "value",
                "Ljava/lang/String;",
            ),
        )

        assertTrue("dev/kartograph/index/fixture/Marker" in caller?.annotations.orEmpty())
        assertTrue("kotlin/Metadata" in caller?.annotations.orEmpty())
        assertTrue("dev/kartograph/index/fixture/Marker" in property?.annotations.orEmpty())
    }

    private val testClassesRoot: Path
        get() = Path.of(requireNotNull(Caller::class.java.protectionDomain.codeSource).location.toURI())
}
