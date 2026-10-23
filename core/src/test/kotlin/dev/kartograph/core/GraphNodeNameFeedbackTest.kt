package dev.kartograph.core

import kotlin.test.*

class GraphNodeNameFeedbackTest {
    @Test fun `module labels do not change language names but previous selectors remain accepted`() {
        val node = GraphNode(NodeId("class:sample/Screen"), "Screen", NodeKind.CLASS, moduleName = "app-java")
        assertEquals("sample.Screen", node.qualifiedName)
        assertTrue(node.matchesQualifiedName("sample.Screen"))
        assertTrue(node.matchesQualifiedName("app-java.sample.Screen"))
        assertFalse(node.matchesQualifiedName("other.sample.Screen"))
        assertEquals("app-java", node.moduleName)
        assertEquals("class:sample/Screen", node.id.value)
    }
}
