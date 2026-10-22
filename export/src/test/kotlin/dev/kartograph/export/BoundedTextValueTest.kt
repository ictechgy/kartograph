package dev.kartograph.export

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class BoundedTextValueTest {
    @Test fun `ASCII and newline stop exactly at the UTF8 byte budget`() {
        assertEquals("abc\n", boundedTextValue(4) { append("abc"); append('\n') })
        assertFailsWith<QuerySnapshotSizeException> { boundedTextValue(3) { append("abc"); append('\n') } }
        assertFailsWith<QuerySnapshotSizeException> { boundedTextValue(3) { append("abcd") } }
        assertFailsWith<IllegalArgumentException> { boundedTextValue(0) { append("") } }
    }

    @Test fun `non ASCII characters and split surrogate pairs count encoded bytes`() {
        assertEquals("가é", boundedTextValue(5) { append("가"); append('é') })
        assertFailsWith<QuerySnapshotSizeException> { boundedTextValue(4) { append("가"); append('é') } }
        val pair = "\uD83D\uDE80"
        assertEquals(pair, boundedTextValue(4) { append(pair[0]); append(pair[1]) })
        assertEquals(pair, boundedTextValue(4) { append(pair, 0, 1); append(pair, 1, 2) })
        assertFailsWith<QuerySnapshotSizeException> { boundedTextValue(3) { append(pair[0]); append(pair[1]) } }
    }

    @Test fun `maximum report limit accepts exact bytes and refuses one more`() {
        val limit = 16 * 1024 * 1024
        val exact = "x".repeat(limit)
        assertEquals(limit, boundedTextValue(limit) { append(exact) }.length)
        assertFailsWith<QuerySnapshotSizeException> { boundedTextValue(limit) { append(exact); append('x') } }
        val value = "x".repeat(limit - 2)
        assertEquals(limit, boundedJsonValue(value, limit).length)
        assertFailsWith<QuerySnapshotSizeException> { boundedJsonValue(value + "x", limit) }
    }
}
