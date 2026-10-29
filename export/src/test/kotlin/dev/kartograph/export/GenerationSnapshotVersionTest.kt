package dev.kartograph.export

import dev.kartograph.core.CodeGraph
import kotlin.test.Test
import kotlin.test.assertFailsWith

class GenerationSnapshotVersionTest {
    @Test
    fun `legacy version refuses mandatory generation evidence instead of ignoring it`() {
        val snapshot = QuerySnapshot(CodeGraph(emptyList(), emptyList()), emptyList(), emptyList())
        for (compact in listOf(false, true)) {
            val text = QuerySnapshotCodec.render(snapshot, compact)
            val downgraded = text.replaceFirst("{", "{\"generationEvidence\": null, ")
            assertFailsWith<IllegalArgumentException> { QuerySnapshotCodec.parse(downgraded) }
        }
    }
}
