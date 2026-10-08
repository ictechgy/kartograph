package dev.kartograph.export

import dev.kartograph.core.CodeGraph
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class QuerySnapshotSizeLimitTest {
    private val snapshot = QuerySnapshot(CodeGraph(emptyList(), emptyList()), emptyList(), emptyList())

    @Test
    fun `default and configurable snapshot limits are validated`() {
        assertEquals(64, QuerySnapshotCodec.DEFAULT_MAX_MIB)
        assertEquals(128, QuerySnapshotCodec.MAX_MIB)
        assertEquals(64 * 1024 * 1024, QuerySnapshotCodec.MAX_BYTES)
        assertEquals(QuerySnapshotCodec.MAX_BYTES, QuerySnapshotCodec.maximumBytes(64))
        assertEquals(128 * 1024 * 1024, QuerySnapshotCodec.maximumBytes(128))
        for (invalid in listOf(Int.MIN_VALUE, -1, 0, 129, Int.MAX_VALUE)) {
            assertFailsWith<IllegalArgumentException> { QuerySnapshotCodec.maximumBytes(invalid) }
        }
    }

    @Test
    fun `explicit byte limit rejects before parsing and round trips within the selected bound`() {
        val encoded = QuerySnapshotCodec.render(snapshot)
        val byteSize = encoded.toByteArray(Charsets.UTF_8).size

        assertFailsWith<IllegalArgumentException> { QuerySnapshotCodec.parse(encoded, byteSize - 1) }
        assertEquals(encoded, QuerySnapshotCodec.render(QuerySnapshotCodec.parse(encoded, byteSize)))
        assertEquals(encoded, QuerySnapshotCodec.render(snapshot, compact = false, maximumBytes = byteSize))
        assertFailsWith<IllegalArgumentException> {
            QuerySnapshotCodec.render(snapshot, compact = false, maximumBytes = byteSize - 1)
        }
        for (invalid in listOf(0, QuerySnapshotCodec.maximumBytes(128) + 1)) {
            assertFailsWith<IllegalArgumentException> { QuerySnapshotCodec.parse(encoded, invalid) }
        }
    }

    @Test
    fun `normal and compact bounded renders preserve exact bytes at their boundary`() {
        for (compact in listOf(false, true)) {
            val expected = QuerySnapshotCodec.render(snapshot, compact)
            val maximum = expected.toByteArray(Charsets.UTF_8).size
            assertEquals(expected, QuerySnapshotCodec.render(snapshot, compact, maximum))
            assertFailsWith<QuerySnapshotSizeException> {
                QuerySnapshotCodec.render(snapshot, compact, maximum - 1)
            }
        }
    }

    @Test
    fun `invalid byte maximum is rejected before snapshot assembly`() {
        val invalidLocation = dev.kartograph.core.GraphNode(
            dev.kartograph.core.NodeId("class:Invalid"), "Invalid", dev.kartograph.core.NodeKind.CLASS,
            location = dev.kartograph.core.SourceLocation("/private/source.kt"),
        )
        val invalidSnapshot = QuerySnapshot(
            CodeGraph(listOf(invalidLocation), emptyList()), emptyList(), emptyList(),
        )
        val error = assertFailsWith<IllegalArgumentException> {
            QuerySnapshotCodec.render(invalidSnapshot, maximumBytes = 0, compact = false)
        }
        assertEquals("query snapshot byte maximum must be between 1 byte and 128 MiB", error.message)
    }
}
