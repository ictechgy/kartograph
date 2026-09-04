package dev.kartograph.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

class GraphModelTest {
    @Test
    fun `member containment does not imply usage`() {
        assertTrue(EdgeKind.CALL.impliesUsage)
        assertTrue(EdgeKind.FIELD_ACCESS.impliesUsage)
        assertTrue(EdgeKind.RETENTION.impliesUsage)
        assertFalse(EdgeKind.MEMBER.impliesUsage)
    }

    @Test
    fun `node keeps JVM identity separate from optional source facts`() {
        val generated = GraphNode(
            id = NodeId("class:fixture/Generated"),
            name = "Generated",
            kind = NodeKind.CLASS,
            jvmSignature = "fixture/Generated",
            location = null,
            synthesized = true,
        )

        assertEquals("fixture/Generated", generated.jvmSignature)
        assertNull(generated.location)
        assertTrue(generated.synthesized)
    }

    @Test
    fun `node identifiers and edge weights reject invalid empty values`() {
        assertFailsWith<IllegalArgumentException> { NodeId("") }
        assertFailsWith<IllegalArgumentException> {
            GraphEdge(NodeId("a"), NodeId("b"), EdgeKind.CALL, weight = 0)
        }
    }

    @Test
    fun `retention reasons carry stable English explanations`() {
        assertEquals("declared as an Android manifest component", RetentionReason.MANIFEST_COMPONENT.description)
        assertEquals("referenced by an Android XML resource", RetentionReason.XML_LAYOUT.description)
        assertEquals("annotated with androidx.annotation.Keep", RetentionReason.KEEP_ANNOTATION.description)
    }
}
