package dev.kartograph.export

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class JsonDocumentTest {
    @Test
    fun `nested documents preserve key order spacing and primitive representations`() {
        val value = linkedMapOf(
            "values" to listOf(null, true, false, -3, 1.5),
            "empty" to emptyList<Any>(),
            "quote\"" to linkedMapOf("text" to "\n한\\", "object" to emptyMap<String, Any>()),
        )
        assertEquals(
            """{"values": [null, true, false, -3, 1.5], "empty": [], "quote\"": {"text": "\n\ud55c\\", "object": {}}}""",
            jsonValue(value),
        )
        assertFailsWith<IllegalStateException> { jsonValue(Any()) }
    }

    @Test
    fun `every UTF16 code unit round trips through ASCII JSON without identity loss`() {
        val text = CharArray(65_536) { it.toChar() }.concatToString()
        val rendered = jsonValue(text)
        assertTrue(rendered.all { it.code in 0x20..0x7e })
        assertEquals(text, SnapshotJsonParser(rendered).parse())
    }
}
