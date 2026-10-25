package dev.kartograph.index

import dev.kartograph.core.GraphNode
import dev.kartograph.core.NodeAttribute
import dev.kartograph.core.NodeId
import dev.kartograph.core.NodeKind
import java.security.MessageDigest
import java.util.HexFormat
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals

class ClassFactsAttributeCompatibilityTest {
    @Test
    fun `existing attribute meanings and bytes survive new evidence attributes`() {
        val bytes = fixture("old-attributes.cix", "632552936a307e1b15200a74bdd6fe3bcdf02bb00984cc4afe50a51afc929fbf")
        val facts = ClassFactsCodec.decode(bytes, "legacy-v10-attributes", "fixture")

        assertEquals(
            setOf(
                NodeAttribute.DATA_CLASS, NodeAttribute.EXTENSION_FUNCTION, NodeAttribute.COMPILE_TIME_CONSTANT,
                NodeAttribute.INLINE_FUNCTION, NodeAttribute.FILE_FACADE, NodeAttribute.PROPERTY_ACCESSOR,
                NodeAttribute.GENERATED_INPUT, NodeAttribute.EXTERNAL_STUB,
                NodeAttribute.SOURCE_DECLARATION_LOCATION, NodeAttribute.ENUM_CONSTANT,
            ),
            facts.nodes.single().attributes,
        )
        assertContentEquals(bytes, ClassFactsCodec.encode(facts, "legacy-v10-attributes", "fixture"))
    }

    @Test
    fun `compiled enum cache keeps five enum fields and its exact v10 payload`() {
        val bytes = fixture("jvm-modifier.cix", "818f3b6a7762d90c801e3b0d53cb09b59c15a28a3d7f8b949c1b77fbf66f9681")
        val engine = "4aa58ee51a494026be23c906f67194bb3483b821e44a22c1ae38e393e627f155"
        val content = "34079b5214ef4882709da7d85db4dfa879de647dd286fe4f576531a714ae908d"
        val facts = ClassFactsCodec.decode(bytes, engine, content)

        assertEquals(
            setOf("FINAL", "ABSTRACT", "SYNTHETIC", "NATIVE", "STATIC"),
            facts.nodes.filter { NodeAttribute.ENUM_CONSTANT in it.attributes }.map { it.name }.toSet(),
        )
        assertContentEquals(bytes, ClassFactsCodec.encode(facts, engine, content))
    }

    @Test
    fun `new generated evidence remains distinct from old attributes in a round trip`() {
        val node = GraphNode(
            NodeId("class:probe/Generated"), "Generated", NodeKind.CLASS,
            attributes = setOf(NodeAttribute.GENERATED_SOURCE_EVIDENCE, NodeAttribute.GENERATED_INPUT),
        )
        val facts = ClassFacts("probe/Generated", listOf(node), emptyList())
        val bytes = ClassFactsCodec.encode(facts, "current-engine", "current-content")

        assertEquals(node, ClassFactsCodec.decode(bytes, "current-engine", "current-content").nodes.single())
    }

    private fun fixture(name: String, sha256: String): ByteArray {
        val bytes = requireNotNull(javaClass.getResourceAsStream("/cache-v10/$name")).use { it.readBytes() }
        assertEquals(sha256, HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes)))
        return bytes
    }
}
