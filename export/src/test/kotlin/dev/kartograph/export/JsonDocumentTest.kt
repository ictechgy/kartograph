package dev.kartograph.export

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
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

    @Test
    fun `bounded JSON stops before visiting an item after the byte cap`() {
        var sentinelVisited = false
        val values = object : Iterable<Any?> {
            override fun iterator(): Iterator<Any?> = object : Iterator<Any?> {
                private var index = 0

                override fun hasNext(): Boolean = index < 2

                override fun next(): Any? = when (index++) {
                    0 -> "first"
                    else -> {
                        sentinelVisited = true
                        error("lazy sentinel was visited")
                    }
                }
            }
        }

        assertFailsWith<QuerySnapshotSizeException> {
            boundedJsonValue(values, maximumBytes = jsonValue(listOf("first")).toByteArray(Charsets.UTF_8).size - 1)
        }
        assertFalse(sentinelVisited)
    }

    @Test
    fun `bounded JSON preserves ASCII escaping UTF16 surrogates and trailing newline at exact boundary`() {
        for (value in listOf("ascii", "quote\"slash\\line\n", "한글", "😀", "\uD800")) {
            val expected = jsonValue(value) + "\n"
            val maximum = expected.toByteArray(Charsets.UTF_8).size
            assertEquals(expected, boundedJsonValue(value, maximum, "\n"))
            assertFailsWith<QuerySnapshotSizeException> {
                boundedJsonValue(value, maximum - 1, "\n")
            }
        }
    }
}
