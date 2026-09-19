package dev.kartograph.index

import dev.kartograph.core.NodeId
import kotlin.test.Test
import kotlin.test.assertEquals

class ReferencedClassesTest {
    @Test
    fun `class targets map directly to the internal name`() {
        assertEquals(listOf("com/example/Foo"), referencedClassCandidates(NodeId("class:com/example/Foo")))
    }

    @Test
    fun `member targets expose every possible owner prefix`() {
        assertEquals(
            listOf("com/example/Foo"),
            referencedClassCandidates(NodeId("method:com/example/Foo#bar()V")),
        )
        assertEquals(
            listOf("com/example/Foo", "com/example/Foo#inner"),
            referencedClassCandidates(NodeId("method:com/example/Foo#inner#bar()V")),
        )
        assertEquals(
            listOf("com/example/Foo"),
            referencedClassCandidates(NodeId("field:com/example/Foo#FIELD:I")),
        )
    }

    @Test
    fun `targets without a class owner are ignored`() {
        assertEquals(emptyList(), referencedClassCandidates(NodeId("unit:module")))
        assertEquals(emptyList(), referencedClassCandidates(NodeId("method:")))
    }
}
