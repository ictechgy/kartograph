package dev.kartograph.export

import java.math.BigInteger
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class McpJsonCodecTest {
    @Test
    fun `envelopes preserve exact integer and string ids and reject unsafe serialization`() {
        val id = "123456789012345678901234567890"
        assertEquals(BigInteger(id), (McpJsonCodec.parse("{\"id\":$id}") as Map<*, *>)["id"])
        assertEquals("{\"id\": $id}", McpJsonCodec.render(mapOf("id" to BigInteger(id))))
        assertEquals("string\nID", McpJsonCodec.parse("\"string\\nID\""))
        for (value in listOf(Double.NaN, Double.POSITIVE_INFINITY, Any(), mapOf(1 to "bad"))) {
            assertFailsWith<IllegalArgumentException> { McpJsonCodec.render(value) }
        }
    }

    @Test
    fun `shared parser rejects duplicate keys malformed numbers and deep input`() {
        for (json in listOf("{\"x\":1,\"x\":2}", "01", "1e", "1.", "[".repeat(34)+"]".repeat(34))) {
            assertFailsWith<IllegalArgumentException> { McpJsonCodec.parse(json) }
        }
        assertFailsWith<IllegalArgumentException> { McpJsonCodec.render("a".repeat(100), 30) }
        assertEquals("1.5", McpJsonCodec.render(McpJsonCodec.parse("1.5")))
    }

    @Test
    fun `numeric lexemes and exponents are bounded before arbitrary precision conversion`() {
        assertEquals(BigInteger("9".repeat(128)), McpJsonCodec.parse("9".repeat(128)))
        assertEquals("1E+10000", McpJsonCodec.render(McpJsonCodec.parse("1e10000")))
        for (json in listOf("9".repeat(129), "-" + "9".repeat(128), "1e10001", "1e-10001")) {
            assertFailsWith<IllegalArgumentException> { McpJsonCodec.parse(json) }
        }
    }
}
